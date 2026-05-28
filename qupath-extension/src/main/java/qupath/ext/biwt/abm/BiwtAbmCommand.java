package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.BiwtSampler;
import io.github.drbergmanlab.biwt.core.SamplingPlan;
import io.github.drbergmanlab.biwt.core.SamplingResult;
import io.github.drbergmanlab.biwt.core.SubstrateSpec;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.domain.AnnotationNotFoundException;
import io.github.drbergmanlab.biwt.core.domain.AskUserRequiredException;
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
import io.github.drbergmanlab.biwt.core.domain.NonRectangularDomainException;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.TransformedServerBuilder;
import qupath.lib.gui.QuPathGUI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The interactive wizard launched from <b>Extensions → BIWT → Sample substrates…</b>.
 *
 * <p>Step sequence:
 * <ol>
 *   <li>Normalization reminder (design doc §6).</li>
 *   <li>Voxel size input (µm).</li>
 *   <li>Domain detection — auto-find an annotation named {@code abm_domain}; if absent, offer
 *       to use the whole image as the ABM domain.</li>
 *   <li>Plan confirmation — shows {@code nx × ny} voxels and the effective step in µm.</li>
 *   <li>Substrate definition — a single modal with Add / Finish / Cancel buttons.
 *       The channel dropdown shows raw image channels and, when applicable, the
 *       color-deconvolution channels (H, E, Residual).</li>
 *   <li>File-save chooser, then sampling on a background thread with a progress indicator.</li>
 * </ol>
 *
 * <p>All UI work is on the JavaFX thread; the actual pixel-reading pass runs on a background
 * thread via {@link Task} so the QuPath UI doesn't freeze on large images.
 */
public final class BiwtAbmCommand {

    private static final Logger logger = LoggerFactory.getLogger(BiwtAbmCommand.class);

    private static final String TITLE = "BIWT";
    private static final double DEFAULT_STEP_MICRONS = 20.0;
    private static final CoordinateOrigin DEFAULT_ORIGIN = CoordinateOrigin.IMAGE_CENTER;

    private final QuPathGUI qupath;
    private final BiwtSampler sampler = BiwtSampler.create();

    public BiwtAbmCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Entry point — call from the menu-item action handler on the JavaFX thread. */
    public void run() {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(TITLE, "Open an image first.");
            return;
        }

        // Step 1: normalization reminder.
        boolean go = Dialogs.showConfirmDialog(TITLE,
                "Ensure all image normalization is complete before proceeding.\n\n"
                        + "BIWT samples raw channel intensities and makes no assumptions about\n"
                        + "color, stain separation, or background subtraction. Continue?");
        if (!go) return;

        // Steps 2 & 3: plan = step-size input + detect domain + reconcile.
        SamplingPlan plan = planWithUser(imageData);
        if (plan == null) return;

        // Step 4: confirmation showing nx × ny and effective step.
        if (!confirmPlan(plan)) return;

        // Step 5: build the channel choices (raw + deconvolved if H&E) and prompt for substrates.
        ChannelSet channelSet = buildChannelSet(imageData);
        SubstrateChoices substrates = SubstrateDialog.show(qupath.getStage(), channelSet);
        if (substrates == null || substrates.specs.isEmpty()) return;

        // Step 6: file-save chooser.
        Path outPath = chooseOutputPath(imageData);
        if (outPath == null) return;

        // Step 7: background sampling.
        runSamplingTask(channelSet.transformedServer, plan, substrates.specs, outPath);
    }

    // ---------------- steps 2 & 3 (plan: step size + detect domain) ----------------

    private SamplingPlan planWithUser(ImageData<BufferedImage> imageData) {
        Double stepMicrons = Dialogs.showInputDialog(TITLE,
                "Voxel size in µm (single scalar for x and y):", DEFAULT_STEP_MICRONS);
        if (stepMicrons == null) return null;
        if (!(stepMicrons > 0)) {
            Dialogs.showErrorMessage(TITLE, "Voxel size must be a positive number.");
            return null;
        }

        try {
            return sampler.plan(imageData, DomainDetectionOptions.defaults(),
                    stepMicrons, DEFAULT_ORIGIN);
        } catch (AnnotationNotFoundException ann) {
            boolean useWholeImage = Dialogs.showYesNoDialog(TITLE,
                    "No annotation named 'abm_domain' was found.\n\n"
                            + "Use the whole image as the ABM domain instead?");
            if (!useWholeImage) return null;
            try {
                return sampler.plan(imageData, DomainDetectionOptions.wholeImageFallback(),
                        stepMicrons, DEFAULT_ORIGIN);
            } catch (DomainException e2) {
                Dialogs.showErrorMessage(TITLE, e2.getMessage());
                return null;
            }
        } catch (NonRectangularDomainException nr) {
            Dialogs.showErrorMessage(TITLE, nr.getMessage());
            return null;
        } catch (AskUserRequiredException askEx) {
            logger.warn("Unexpected ASK_USER signal from default options", askEx);
            return null;
        } catch (DomainException d) {
            Dialogs.showErrorMessage(TITLE, d.getMessage());
            return null;
        }
    }

    // ---------------- step 4 (plan confirmation) ----------------

    private boolean confirmPlan(SamplingPlan plan) {
        String message = String.format(
                "Source: %s%n"
                        + "Grid:   %d × %d voxels%n"
                        + "Requested step: %.4g µm%n"
                        + "Effective step: %.4g µm%s%n"
                        + "Pixel size:     %.4g µm%n%n"
                        + "Proceed?",
                plan.domain().sourceDescription(),
                plan.grid().nx(), plan.grid().ny(),
                plan.requestedStepMicrons(),
                plan.effectiveStepMicrons(),
                effectiveStepNote(plan),
                plan.domain().pixelWidthMicrons());
        return Dialogs.showConfirmDialog(TITLE, message);
    }

    private static String effectiveStepNote(SamplingPlan plan) {
        if (Math.abs(plan.requestedStepMicrons() - plan.effectiveStepMicrons()) < 1e-9) {
            return "";
        }
        return "  (rounded to whole pixels)";
    }

    // ---------------- step 5 (channel set + substrate dialog) ----------------

    /**
     * Build a transformed server exposing every "logical channel" the user can pick from:
     * the raw image channels, plus the color-deconvolution channels (Hematoxylin, Eosin,
     * Residual) when the image has stains defined.
     */
    private static ChannelSet buildChannelSet(ImageData<BufferedImage> imageData) {
        ImageServer<BufferedImage> raw = imageData.getServer();
        List<ImageChannel> rawChannels = raw.getMetadata().getChannels();

        List<ColorTransforms.ColorTransform> transforms = new ArrayList<>();
        List<ChannelChoice> choices = new ArrayList<>();

        for (int i = 0; i < rawChannels.size(); i++) {
            ImageChannel c = rawChannels.get(i);
            String label = (c.getName() == null || c.getName().isBlank())
                    ? "Channel " + i
                    : c.getName();
            transforms.add(ColorTransforms.createChannelExtractor(i));
            choices.add(new ChannelChoice(label, transforms.size() - 1));
        }

        ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains();
        if (stains != null && raw.isRGB()) {
            for (int s = 1; s <= 3; s++) {
                String stainName = stains.getStain(s).getName();
                if (stainName == null || stainName.isBlank()) continue;
                transforms.add(ColorTransforms.createColorDeconvolvedChannel(stains, s));
                choices.add(new ChannelChoice("Deconvolved: " + stainName, transforms.size() - 1));
            }
        }

        ImageServer<BufferedImage> transformed = new TransformedServerBuilder(raw)
                .applyColorTransforms(transforms)
                .build();
        return new ChannelSet(transformed, choices);
    }

    // ---------------- step 6 (file save) ----------------

    private Path chooseOutputPath(ImageData<BufferedImage> imageData) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save substrates CSV");
        fc.setInitialFileName(suggestFileName(imageData));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        Stage owner = qupath == null ? null : qupath.getStage();
        File f = fc.showSaveDialog(owner);
        return f == null ? null : f.toPath();
    }

    private static String suggestFileName(ImageData<BufferedImage> imageData) {
        String base = imageData.getServer().getMetadata().getName();
        if (base == null || base.isBlank()) base = "substrates";
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "-substrates.csv";
    }

    // ---------------- step 7 (background sampling) ----------------

    private void runSamplingTask(ImageServer<BufferedImage> server,
                                 SamplingPlan plan,
                                 List<SubstrateSpec> substrates,
                                 Path outPath) {
        ProgressIndicator indicator = new ProgressIndicator(-1);
        indicator.setPrefSize(80, 80);
        Label label = new Label(
                "Sampling " + plan.grid().nx() + " × " + plan.grid().ny()
                        + " voxels for " + substrates.size() + " substrate(s)…");
        VBox box = new VBox(12, label, indicator);
        box.setPadding(new Insets(20));
        box.setAlignment(Pos.CENTER);
        Stage progressStage = new Stage();
        progressStage.setTitle(TITLE);
        progressStage.initOwner(qupath.getStage());
        progressStage.initModality(Modality.WINDOW_MODAL);
        progressStage.setScene(new Scene(box));
        progressStage.setResizable(false);

        Task<SamplingResult> task = new Task<>() {
            @Override
            protected SamplingResult call() throws Exception {
                SamplingResult result = sampler.sample(server, plan, substrates);
                result.writeCsv(outPath);
                return result;
            }
        };
        task.setOnSucceeded(e -> {
            progressStage.close();
            SamplingResult r = task.getValue();
            Dialogs.showInfoNotification(TITLE,
                    "Saved " + r.grid().nx() + " × " + r.grid().ny()
                            + " voxels to " + outPath.getFileName());
            logger.info("BIWT wrote {} ({} × {} voxels, {} substrate(s))",
                    outPath, r.grid().nx(), r.grid().ny(), r.substrates().size());
        });
        task.setOnFailed(e -> {
            progressStage.close();
            Throwable cause = task.getException();
            logger.error("BIWT sampling failed", cause);
            Dialogs.showErrorMessage(TITLE, cause == null ? "Sampling failed." : cause.getMessage());
        });

        Thread t = new Thread(task, "biwt-sampler");
        t.setDaemon(true);
        t.start();
        Platform.runLater(progressStage::show);
    }

    // ---------------- supporting types ----------------

    /** One row in the channel dropdown: human-readable label + its position in the transformed server. */
    record ChannelChoice(String label, int indexInTransformedServer) {
        @Override public String toString() { return label; }
    }

    /** The transformed server (raw + any derived channels) paired with its channel choices. */
    record ChannelSet(ImageServer<BufferedImage> transformedServer, List<ChannelChoice> choices) {}

    /** Wrapper for the substrate dialog's return value (specs already mapped to server indices). */
    record SubstrateChoices(List<SubstrateSpec> specs) {}

    /** Modal dialog: build a list of substrates with Add / Finish / Cancel. */
    private static final class SubstrateDialog {

        static SubstrateChoices show(Stage owner, ChannelSet channelSet) {
            if (channelSet.choices().isEmpty()) {
                Dialogs.showErrorMessage(TITLE, "Image has no channels reported by its server.");
                return null;
            }

            List<SubstrateSpec> specs = new ArrayList<>();
            ObservableList<String> listItems = FXCollections.observableArrayList();
            boolean[] confirmed = { false };

            TextField nameField = new TextField();
            nameField.setPromptText("e.g. oxygen");
            ChoiceBox<ChannelChoice> channelBox = new ChoiceBox<>();
            channelBox.getItems().addAll(channelSet.choices());
            channelBox.setConverter(new StringConverter<>() {
                @Override public String toString(ChannelChoice c) { return c == null ? "" : c.label(); }
                @Override public ChannelChoice fromString(String s) { return null; }
            });
            channelBox.getSelectionModel().selectFirst();

            ListView<String> list = new ListView<>(listItems);
            list.setPrefHeight(140);

            Button removeButton = new Button("Remove selected");
            removeButton.disableProperty().bind(
                    list.getSelectionModel().selectedIndexProperty().lessThan(0));
            removeButton.setOnAction(e -> {
                int idx = list.getSelectionModel().getSelectedIndex();
                if (idx < 0) return;
                specs.remove(idx);
                listItems.remove(idx);
            });

            Button addButton = new Button("Add substrate");
            Button finishButton = new Button("Finish");
            Button cancelButton = new Button("Cancel");

            addButton.disableProperty().bind(
                    nameField.textProperty().isEmpty()
                            .or(channelBox.getSelectionModel().selectedItemProperty().isNull()));
            finishButton.disableProperty().bind(Bindings.isEmpty(listItems));

            // Layout
            GridPane form = new GridPane();
            form.setHgap(8);
            form.setVgap(8);
            form.add(new Label("Name:"), 0, 0);
            form.add(nameField, 1, 0);
            form.add(new Label("Channel:"), 0, 1);
            form.add(channelBox, 1, 1);
            GridPane.setHgrow(nameField, Priority.ALWAYS);
            GridPane.setHgrow(channelBox, Priority.ALWAYS);

            HBox buttons = new HBox(10, addButton, finishButton, cancelButton);
            buttons.setAlignment(Pos.CENTER_RIGHT);

            HBox listRow = new HBox(8, removeButton);
            listRow.setAlignment(Pos.CENTER_LEFT);

            VBox root = new VBox(12,
                    new Label("Substrates added so far:"),
                    list,
                    listRow,
                    new Label("Add a substrate:"),
                    form,
                    buttons);
            root.setPadding(new Insets(15));
            root.setPrefWidth(420);

            Stage stage = new Stage();
            stage.setTitle("BIWT — define substrates");
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setScene(new Scene(root));
            stage.setResizable(false);

            addButton.setOnAction(e -> {
                ChannelChoice choice = channelBox.getValue();
                String name = nameField.getText().trim();
                specs.add(new SubstrateSpec(name, choice.indexInTransformedServer()));
                listItems.add(name + "  —  " + choice.label());
                nameField.clear();
                nameField.requestFocus();
            });
            finishButton.setOnAction(e -> { confirmed[0] = true; stage.close(); });
            cancelButton.setOnAction(e -> { confirmed[0] = false; stage.close(); });

            stage.showAndWait();
            return confirmed[0] ? new SubstrateChoices(specs) : null;
        }
    }
}
