package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.BiwtSampler;
import io.github.drbergmanlab.biwt.core.SamplingPlan;
import io.github.drbergmanlab.biwt.core.SamplingResult;
import io.github.drbergmanlab.biwt.core.SubstrateSpec;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.export.SubstrateCsvWriter;
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
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
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
import qupath.ext.biwt.abm.transforms.ChannelMathTransform;
import qupath.ext.biwt.abm.transforms.OpticalDensitySumTransform;
import io.github.drbergmanlab.biwt.core.channelmath.Expression;
import io.github.drbergmanlab.biwt.core.channelmath.ExpressionParseException;
import io.github.drbergmanlab.biwt.core.channelmath.ExpressionParser;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
    private static final CoordinateOrigin DEFAULT_ORIGIN = CoordinateOrigin.ABM_DOMAIN_CENTER;

    /** Step size in µm + the coordinate-origin convention the user picked. */
    private record PlanInputs(double stepMicrons, CoordinateOrigin origin) {}

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

        // Step 5: build the channel choices (raw + deconvolved if H&E + OD-sum + Expression…)
        // and prompt for substrates.
        ChannelSet channelSet = buildChannelSet(imageData);
        SubstrateChoices substrates = SubstrateDialog.show(qupath.getStage(), channelSet);
        if (substrates == null || substrates.substrates().isEmpty()) return;

        // Step 6: file-save chooser.
        Path outPath = chooseOutputPath(imageData);
        if (outPath == null) return;

        // Build the sampling server: one channel per substrate, in user-submitted order.
        List<ColorTransforms.ColorTransform> finalTransforms =
                new ArrayList<>(substrates.substrates().size());
        List<SubstrateSpec> specs = new ArrayList<>(substrates.substrates().size());
        for (int i = 0; i < substrates.substrates().size(); i++) {
            CommittedSubstrate cs = substrates.substrates().get(i);
            finalTransforms.add(cs.transform());
            specs.add(new SubstrateSpec(cs.name(), i));
        }
        ImageServer<BufferedImage> samplingServer = new TransformedServerBuilder(channelSet.rawServer())
                .applyColorTransforms(finalTransforms)
                .build();

        // Step 7: background sampling.
        runSamplingTask(samplingServer, plan, specs, outPath);
    }

    // ---------------- steps 2 & 3 (plan: step size + detect domain) ----------------

    private SamplingPlan planWithUser(ImageData<BufferedImage> imageData) {
        PlanInputs inputs = promptForPlanInputs();
        if (inputs == null) return null;

        try {
            return sampler.plan(imageData, DomainDetectionOptions.defaults(),
                    inputs.stepMicrons(), inputs.origin());
        } catch (AnnotationNotFoundException ann) {
            boolean useWholeImage = Dialogs.showYesNoDialog(TITLE,
                    "No annotation named 'abm_domain' was found.\n\n"
                            + "Use the whole image as the ABM domain instead?");
            if (!useWholeImage) return null;
            try {
                return sampler.plan(imageData, DomainDetectionOptions.wholeImageFallback(),
                        inputs.stepMicrons(), inputs.origin());
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

    /**
     * Custom dialog asking for the step size (µm) and the coordinate-origin convention.
     * Returns null on cancel. Validates that the step is positive before returning.
     */
    private PlanInputs promptForPlanInputs() {
        var resultRef = new java.util.concurrent.atomic.AtomicReference<PlanInputs>(null);

        Stage dialog = new Stage();
        dialog.setTitle(TITLE + " — sampling parameters");
        dialog.initOwner(qupath == null ? null : qupath.getStage());
        dialog.initModality(Modality.APPLICATION_MODAL);

        TextField stepField = new TextField(Double.toString(DEFAULT_STEP_MICRONS));
        stepField.setPrefColumnCount(8);

        javafx.scene.control.RadioButton centerRadio = new javafx.scene.control.RadioButton("ABM domain center");
        javafx.scene.control.RadioButton topLeftRadio = new javafx.scene.control.RadioButton("ABM domain top-left");
        javafx.scene.control.ToggleGroup originGroup = new javafx.scene.control.ToggleGroup();
        centerRadio.setToggleGroup(originGroup);
        topLeftRadio.setToggleGroup(originGroup);
        if (DEFAULT_ORIGIN == CoordinateOrigin.ABM_DOMAIN_TOP_LEFT) {
            topLeftRadio.setSelected(true);
        } else {
            centerRadio.setSelected(true);
        }
        // (The "ABM domain" wording is deliberate: the origin tracks the voxel grid — i.e. the
        // annotation when one is defined — not the image as a whole. This matters when the
        // annotation sits in a corner of the slide.)
        VBox originBox = new VBox(4, centerRadio, topLeftRadio);

        // Small canvas that draws the ABM domain and shows where (0, 0) lands for the
        // current radio selection. Re-renders whenever the toggle changes.
        javafx.scene.canvas.Canvas originPreview = new javafx.scene.canvas.Canvas(140, 100);
        Runnable redrawPreview = () -> drawOriginPreview(originPreview,
                topLeftRadio.isSelected() ? CoordinateOrigin.ABM_DOMAIN_TOP_LEFT : CoordinateOrigin.ABM_DOMAIN_CENTER);
        redrawPreview.run();
        originGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> redrawPreview.run());

        HBox originRow = new HBox(20, originBox, originPreview);
        originRow.setAlignment(Pos.CENTER_LEFT);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("Voxel size (µm):"), 0, 0);
        form.add(stepField, 1, 0);
        form.add(new Label("ABM (0, 0) at:"), 0, 1);
        form.add(originRow, 1, 1);

        Button okButton = new Button("OK");
        Button cancelButton = new Button("Cancel");
        okButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);

        Label warning = new Label();
        warning.setStyle("-fx-text-fill: #cc0000;");

        okButton.setOnAction(e -> {
            String txt = stepField.getText().trim();
            double step;
            try {
                step = Double.parseDouble(txt);
            } catch (NumberFormatException nfe) {
                warning.setText("Enter a number.");
                return;
            }
            if (!(step > 0)) {
                warning.setText("Voxel size must be positive.");
                return;
            }
            CoordinateOrigin origin = topLeftRadio.isSelected()
                    ? CoordinateOrigin.ABM_DOMAIN_TOP_LEFT
                    : CoordinateOrigin.ABM_DOMAIN_CENTER;
            resultRef.set(new PlanInputs(step, origin));
            dialog.close();
        });
        cancelButton.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, cancelButton, okButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, form, warning, buttons);
        root.setPadding(new Insets(16));
        dialog.setScene(new Scene(root));
        dialog.setResizable(false);
        dialog.showAndWait();

        return resultRef.get();
    }

    /**
     * Render a tiny "ABM domain" rectangle on the given canvas with a dot marking where (0, 0)
     * lands for the chosen origin convention. Re-called whenever the radio selection changes.
     */
    private static void drawOriginPreview(javafx.scene.canvas.Canvas canvas, CoordinateOrigin origin) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double W = canvas.getWidth();
        double H = canvas.getHeight();
        g.clearRect(0, 0, W, H);

        // ABM domain rectangle (light fill, gray border). Extra top padding leaves room for the
        // "ABM domain" caption above the rectangle so it can't be overrun by the (0, 0) marker
        // when the user picks the top-left origin.
        double topPad = 18;
        double pad = 12;
        double rx = pad, ry = topPad, rw = W - 2 * pad, rh = H - topPad - pad;
        g.setFill(Color.gray(0.96));
        g.fillRect(rx, ry, rw, rh);
        g.setStroke(Color.gray(0.5));
        g.setLineWidth(1);
        g.strokeRect(rx, ry, rw, rh);

        // "ABM domain" caption just above the rectangle's top-left corner.
        g.setFill(Color.gray(0.45));
        g.setFont(Font.font(9));
        g.fillText("ABM domain", rx, ry - 4);

        // Dot at the origin location.
        double dotX, dotY;
        switch (origin) {
            case ABM_DOMAIN_TOP_LEFT -> {
                dotX = rx;
                dotY = ry;
            }
            case ABM_DOMAIN_CENTER -> {
                dotX = rx + rw / 2;
                dotY = ry + rh / 2;
            }
            default -> {
                dotX = rx + rw / 2;
                dotY = ry + rh / 2;
            }
        }
        double dotR = 4;
        g.setFill(Color.CRIMSON);
        g.fillOval(dotX - dotR, dotY - dotR, 2 * dotR, 2 * dotR);

        // Label (0,0) — position so it stays inside the canvas for both layouts.
        g.setFill(Color.BLACK);
        g.setFont(Font.font(11));
        if (origin == CoordinateOrigin.ABM_DOMAIN_TOP_LEFT) {
            g.fillText("(0, 0)", dotX + 6, dotY + 14);
        } else {
            g.fillText("(0, 0)", dotX + 6, dotY + 4);
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
     * Build the {@link ChannelSet}: dropdown options + per-identifier extractors for the
     * expression editor. Each option carries the {@link ColorTransforms.ColorTransform} that
     * produces its channel values from the raw server; the sampling server is constructed at
     * Finish time, one channel per substrate.
     */
    private static ChannelSet buildChannelSet(ImageData<BufferedImage> imageData) {
        ImageServer<BufferedImage> raw = imageData.getServer();
        List<ImageChannel> rawChannels = raw.getMetadata().getChannels();
        boolean isRgb = raw.isRGB();

        List<ChannelChoice> options = new ArrayList<>();
        Map<String, Function<BufferedImage, float[]>> extractors = new HashMap<>();

        // Raw channels — dropdown entries AND expression identifiers.
        for (int i = 0; i < rawChannels.size(); i++) {
            ImageChannel c = rawChannels.get(i);
            String label = (c.getName() == null || c.getName().isBlank())
                    ? "Channel " + i
                    : c.getName();
            ColorTransforms.ColorTransform transform = ColorTransforms.createChannelExtractor(i);
            options.add(new ChannelChoice(label, transform));
            int band = i;
            extractors.put(label, img -> ChannelMathTransform.readBand(img, band));
        }

        // Color deconvolution — dropdown entries (prefixed "Deconvolved:") and expression
        // identifiers (raw stain name, e.g. "Hematoxylin" / "H").
        ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains();
        if (stains != null && isRgb) {
            for (int s = 1; s <= 3; s++) {
                String stainName = stains.getStain(s).getName();
                if (stainName == null || stainName.isBlank()) continue;
                ColorTransforms.ColorTransform transform =
                        ColorTransforms.createColorDeconvolvedChannel(stains, s);
                options.add(new ChannelChoice("Deconvolved: " + stainName, transform));
                extractors.put(stainName, img -> transform.extractChannel(raw, img, null));
            }
        }

        // OD-sum dropdown entry; OD identifier aliases for expressions.
        if (isRgb) {
            options.add(new ChannelChoice("Optical density sum", new OpticalDensitySumTransform()));
            extractors.put("OD_R", img -> ChannelMathTransform.readOpticalDensity(img, 0));
            extractors.put("OD_G", img -> ChannelMathTransform.readOpticalDensity(img, 1));
            extractors.put("OD_B", img -> ChannelMathTransform.readOpticalDensity(img, 2));
            extractors.put("OD_sum", img -> ChannelMathTransform.readOpticalDensitySum(img));
        }

        // Expression sentinel — always last in the dropdown.
        options.add(ChannelChoice.EXPRESSION);

        return new ChannelSet(raw, options, extractors, isRgb);
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
        Label headerLabel = new Label(
                "Sampling " + plan.grid().nx() + " × " + plan.grid().ny()
                        + " voxels for " + substrates.size() + " substrate(s)…");
        Label statusLabel = new Label("Preparing…");
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(360);
        VBox box = new VBox(12, headerLabel, statusLabel, progressBar);
        box.setPadding(new Insets(20));
        box.setAlignment(Pos.CENTER_LEFT);
        Stage progressStage = new Stage();
        progressStage.setTitle(TITLE);
        progressStage.initOwner(qupath.getStage());
        // Non-modal: user can pan/zoom the image while sampling runs. (Don't switch images.)
        progressStage.initModality(Modality.NONE);
        progressStage.setScene(new Scene(box));
        progressStage.setResizable(false);

        Task<SamplingResult> task = new Task<>() {
            @Override
            protected SamplingResult call() throws Exception {
                int total = substrates.size();
                int workerCount = Math.min(total, Runtime.getRuntime().availableProcessors());
                updateMessage("Sampling " + total + " substrate(s) in parallel (" + workerCount + " workers)…");
                updateProgress(0, total);

                ExecutorService pool = Executors.newFixedThreadPool(workerCount, r -> {
                    Thread t = new Thread(r, "biwt-sample-worker");
                    t.setDaemon(true);
                    return t;
                });
                AtomicInteger completed = new AtomicInteger(0);
                try {
                    List<Future<NamedSubstrate>> futures = new ArrayList<>(total);
                    for (SubstrateSpec spec : substrates) {
                        futures.add(pool.submit(() -> {
                            NamedSubstrate ns = sampler.sampleOne(server, plan, spec);
                            int done = completed.incrementAndGet();
                            updateProgress(done, total);
                            updateMessage("Completed " + done + " of " + total + " substrate(s)…");
                            return ns;
                        }));
                    }

                    // Preserve user-supplied order — substrates may finish in arbitrary order
                    // but the CSV columns should match the order the user added them.
                    List<NamedSubstrate> sampled = new ArrayList<>(total);
                    for (Future<NamedSubstrate> f : futures) {
                        try {
                            sampled.add(f.get());
                        } catch (java.util.concurrent.ExecutionException ee) {
                            Throwable cause = ee.getCause();
                            if (cause instanceof IOException ioe) throw ioe;
                            if (cause instanceof RuntimeException re) throw re;
                            throw new IOException(cause);
                        }
                    }

                    updateMessage("Writing CSV…");
                    new SubstrateCsvWriter().write(outPath, plan.grid(), sampled);
                    return new SamplingResult(
                            plan.domain(), plan.grid(),
                            plan.requestedStepMicrons(), plan.effectiveStepMicrons(),
                            sampled);
                } finally {
                    pool.shutdown();
                }
            }
        };
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());
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

    /**
     * One row in the channel dropdown. {@code transform} is the {@link ColorTransforms.ColorTransform}
     * that produces this channel's values from the raw server — or {@code null} for the sentinel
     * "Expression…" option that triggers the expression editor instead.
     */
    record ChannelChoice(String label, ColorTransforms.ColorTransform transform) {
        @Override public String toString() { return label; }

        /** Sentinel option that opens the expression text area; {@code transform} is null. */
        static final ChannelChoice EXPRESSION = new ChannelChoice("Expression…", null);
    }

    /**
     * Everything the substrate dialog needs: the raw server (we build the sampling server from
     * scratch at Finish, one channel per substrate), the dropdown options, and the per-identifier
     * pixel extractors that an expression can reference.
     */
    record ChannelSet(
            ImageServer<BufferedImage> rawServer,
            List<ChannelChoice> options,
            Map<String, Function<BufferedImage, float[]>> extractorsForExpression,
            boolean rgb) {}

    /** One committed substrate: name, its own {@link ColorTransforms.ColorTransform}, and a label for the list. */
    record CommittedSubstrate(String name, ColorTransforms.ColorTransform transform, String displayLabel) {}

    /** Wrapper for the substrate dialog's return value. */
    record SubstrateChoices(List<CommittedSubstrate> substrates) {}

    /** Modal dialog: build a list of substrates with Add / Finish / Cancel / Remove. */
    private static final class SubstrateDialog {

        static SubstrateChoices show(Stage owner, ChannelSet channelSet) {
            if (channelSet.options().isEmpty()) {
                Dialogs.showErrorMessage(TITLE, "Image has no channels reported by its server.");
                return null;
            }

            List<CommittedSubstrate> committed = new ArrayList<>();
            ObservableList<String> listItems = FXCollections.observableArrayList();
            boolean[] confirmed = { false };

            // -------- form fields --------
            TextField nameField = new TextField();
            nameField.setPromptText("e.g. oxygen");
            ChoiceBox<ChannelChoice> channelBox = new ChoiceBox<>();
            channelBox.getItems().addAll(channelSet.options());
            channelBox.setConverter(new StringConverter<>() {
                @Override public String toString(ChannelChoice c) { return c == null ? "" : c.label(); }
                @Override public ChannelChoice fromString(String s) { return null; }
            });
            channelBox.getSelectionModel().selectFirst();

            // -------- expression editor (shown only when "Expression…" is picked) --------
            TextArea expressionArea = new TextArea();
            expressionArea.setPromptText("e.g. 0.5*H - 0.3*E + clip(R, 0, 200)");
            expressionArea.setPrefRowCount(2);
            Label expressionStatus = new Label();
            expressionStatus.setWrapText(true);

            javafx.beans.binding.BooleanBinding isExpressionMode = Bindings.createBooleanBinding(
                    () -> channelBox.getValue() == ChannelChoice.EXPRESSION,
                    channelBox.valueProperty());
            expressionArea.visibleProperty().bind(isExpressionMode);
            expressionArea.managedProperty().bind(isExpressionMode);
            expressionStatus.visibleProperty().bind(isExpressionMode);
            expressionStatus.managedProperty().bind(isExpressionMode);

            javafx.beans.property.SimpleObjectProperty<Expression> compiledExpression =
                    new javafx.beans.property.SimpleObjectProperty<>(null);
            Runnable revalidateExpression = () -> {
                String text = expressionArea.getText() == null ? "" : expressionArea.getText().trim();
                if (text.isEmpty()) {
                    compiledExpression.set(null);
                    expressionStatus.setText("");
                    return;
                }
                try {
                    Expression e = ExpressionParser.parse(text);
                    String missing = findMissingIdentifier(e, channelSet.extractorsForExpression());
                    if (missing != null) {
                        compiledExpression.set(null);
                        expressionStatus.setText("✗ Unknown channel '" + missing + "'.");
                        expressionStatus.setStyle("-fx-text-fill: #cc0000;");
                        return;
                    }
                    compiledExpression.set(e);
                    expressionStatus.setText("✓ uses " + String.join(", ", e.referencedIdentifiers()));
                    expressionStatus.setStyle("-fx-text-fill: #007700;");
                } catch (ExpressionParseException ex) {
                    compiledExpression.set(null);
                    expressionStatus.setText("✗ " + ex.getMessage());
                    expressionStatus.setStyle("-fx-text-fill: #cc0000;");
                }
            };
            expressionArea.textProperty().addListener((obs, oldVal, newVal) -> revalidateExpression.run());
            // Re-validate when switching INTO expression mode (in case the user already typed text).
            channelBox.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal == ChannelChoice.EXPRESSION) revalidateExpression.run();
            });

            // -------- list + remove --------
            ListView<String> list = new ListView<>(listItems);
            list.setPrefHeight(140);

            Button removeButton = new Button("Remove selected");
            removeButton.disableProperty().bind(
                    list.getSelectionModel().selectedIndexProperty().lessThan(0));
            removeButton.setOnAction(e -> {
                int idx = list.getSelectionModel().getSelectedIndex();
                if (idx < 0) return;
                committed.remove(idx);
                listItems.remove(idx);
            });

            // -------- buttons --------
            Button addButton = new Button("Add substrate");
            Button finishButton = new Button("Finish");
            Button cancelButton = new Button("Cancel");

            // PhysiCell requires unique substrate names — disable Add when typed name is duplicate.
            javafx.beans.binding.BooleanBinding nameAlreadyUsed = Bindings.createBooleanBinding(
                    () -> {
                        String n = nameField.getText().trim();
                        if (n.isEmpty()) return false;
                        return committed.stream().anyMatch(s -> s.name().equals(n));
                    },
                    nameField.textProperty(), listItems);

            Label nameWarning = new Label("already in use");
            nameWarning.setStyle("-fx-text-fill: #cc0000;");
            nameWarning.visibleProperty().bind(nameAlreadyUsed);
            nameWarning.managedProperty().bind(nameAlreadyUsed);

            addButton.disableProperty().bind(
                    nameField.textProperty().isEmpty()
                            .or(channelBox.getSelectionModel().selectedItemProperty().isNull())
                            .or(nameAlreadyUsed)
                            .or(isExpressionMode.and(compiledExpression.isNull())));
            finishButton.disableProperty().bind(Bindings.isEmpty(listItems));

            // Enter in the name field commits the substrate, if Add is enabled.
            nameField.setOnAction(e -> {
                if (!addButton.isDisable()) addButton.fire();
            });

            // -------- layout --------
            HBox nameRow = new HBox(8, nameField, nameWarning);
            HBox.setHgrow(nameField, Priority.ALWAYS);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            GridPane form = new GridPane();
            form.setHgap(8);
            form.setVgap(8);
            form.add(new Label("Name:"), 0, 0);
            form.add(nameRow, 1, 0);
            form.add(new Label("Channel:"), 0, 1);
            form.add(channelBox, 1, 1);
            form.add(new Label("Expression:"), 0, 2);
            form.add(expressionArea, 1, 2);
            form.add(new Label(""), 0, 3);  // spacer for the status row's row index
            form.add(expressionStatus, 1, 3);
            GridPane.setHgrow(nameRow, Priority.ALWAYS);
            GridPane.setHgrow(channelBox, Priority.ALWAYS);
            GridPane.setHgrow(expressionArea, Priority.ALWAYS);

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
            root.setPrefWidth(460);

            Stage stage = new Stage();
            stage.setTitle("BIWT — define substrates");
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setScene(new Scene(root));
            stage.setResizable(false);

            addButton.setOnAction(e -> {
                ChannelChoice choice = channelBox.getValue();
                String name = nameField.getText().trim();
                CommittedSubstrate cs;
                if (choice == ChannelChoice.EXPRESSION) {
                    Expression expr = compiledExpression.get();
                    if (expr == null) return;
                    Map<String, Function<BufferedImage, float[]>> exprExtractors = new HashMap<>();
                    for (String id : expr.referencedIdentifiers()) {
                        exprExtractors.put(id, lookupExtractor(channelSet.extractorsForExpression(), id));
                    }
                    String exprText = expressionArea.getText().trim();
                    ChannelMathTransform transform = ChannelMathTransform.of(
                            "expr: " + exprText, expr, exprExtractors, channelSet.rgb());
                    cs = new CommittedSubstrate(name, transform, name + "  —  " + exprText);
                    expressionArea.clear();
                } else {
                    cs = new CommittedSubstrate(name, choice.transform(), name + "  —  " + choice.label());
                }
                committed.add(cs);
                listItems.add(cs.displayLabel());
                nameField.clear();
                nameField.requestFocus();
            });
            finishButton.setOnAction(e -> { confirmed[0] = true; stage.close(); });
            cancelButton.setOnAction(e -> { confirmed[0] = false; stage.close(); });

            stage.showAndWait();
            return confirmed[0] ? new SubstrateChoices(committed) : null;
        }

        /** Returns the first identifier in {@code expr} that has no extractor, or null if all resolve. */
        private static String findMissingIdentifier(Expression expr,
                                                    Map<String, Function<BufferedImage, float[]>> extractors) {
            for (String id : expr.referencedIdentifiers()) {
                if (!extractors.containsKey(id)) {
                    boolean foundIgnoreCase = extractors.keySet().stream()
                            .anyMatch(k -> k.equalsIgnoreCase(id));
                    if (!foundIgnoreCase) return id;
                }
            }
            return null;
        }

        /** Case-insensitive lookup; throws if no match. */
        private static Function<BufferedImage, float[]> lookupExtractor(
                Map<String, Function<BufferedImage, float[]>> extractors, String id) {
            Function<BufferedImage, float[]> direct = extractors.get(id);
            if (direct != null) return direct;
            for (var entry : extractors.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(id)) return entry.getValue();
            }
            throw new NoSuchElementException("Unknown identifier '" + id + "'.");
        }
    }
}
