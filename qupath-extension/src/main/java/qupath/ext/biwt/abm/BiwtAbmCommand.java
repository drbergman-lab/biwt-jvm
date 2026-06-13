package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.BiwtSampler;
import io.github.drbergmanlab.biwt.core.SamplingPlan;
import io.github.drbergmanlab.biwt.core.SamplingResult;
import io.github.drbergmanlab.biwt.core.SubstrateSpec;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.export.SubstrateCsvWriter;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
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
import qupath.ext.biwt.abm.viz.ViewerModel;
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
    /** Step size in µm + the coordinate-origin convention (always ABM-domain center for the GUI). */
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

        // Step 1: normalization reminder (forward gate; no Back).
        boolean go = Dialogs.showConfirmDialog(TITLE,
                "Ensure all image normalization is complete before proceeding.\n\n"
                        + "BIWT samples raw channel intensities and makes no assumptions about\n"
                        + "color, stain separation, or background subtraction. Continue?");
        if (!go) return;

        // Remaining steps run as a small phase machine so the substrate dialog's Back button can
        // return to the parameters screen with its values preserved. The domain picker and plan
        // confirmation are QuPath OK/Cancel dialogs (forward gates): Cancel aborts the wizard.
        PlanInputs inputs = null;                       // preserved across Back
        List<CommittedSubstrate> priorSubs = List.of(); // preserved across Back
        SamplingPlan plan = null;
        Phase phase = Phase.PARAMS;
        while (true) {
            switch (phase) {
                case PARAMS -> {
                    inputs = promptForPlanInputs(inputs);
                    if (inputs == null) return;         // cancel
                    phase = Phase.DOMAIN;
                }
                case DOMAIN -> {
                    AbmDomain domain = WizardSupport.chooseDomain(qupath, imageData, TITLE);
                    if (domain == null) return;         // cancel of the picker aborts
                    try {
                        plan = sampler.plan(domain, inputs.stepMicrons(), inputs.origin());
                    } catch (DomainException d) {        // e.g. non-square pixels
                        Dialogs.showErrorMessage(TITLE, d.getMessage());
                        return;
                    }
                    Nav<Boolean> conf = WizardSupport.confirmWithBack(
                            qupath == null ? null : qupath.getStage(), TITLE,
                            planSummary(plan), plan.physiCellDomain().summary());
                    if (conf.isCancel()) return;
                    if (conf.isBack()) { phase = Phase.PARAMS; continue; }
                    phase = Phase.SUBSTRATES;
                }
                case SUBSTRATES -> {
                    ChannelSet channelSet = buildChannelSet(imageData);
                    Nav<SubstrateChoices> nav =
                            SubstrateDialog.show(qupath.getStage(), channelSet, false, priorSubs);
                    if (nav.isCancel()) return;
                    if (nav.isBack()) { phase = Phase.PARAMS; continue; }
                    SubstrateChoices substrates = nav.value();
                    priorSubs = substrates.substrates();
                    if (substrates.substrates().isEmpty()) return; // Finish is disabled when empty

                    // Save target is a native file chooser (no Back). Cancel aborts.
                    Path outPath = chooseOutputPath(imageData);
                    if (outPath == null) return;

                    // Dialogs were non-modal — make sure the user didn't switch images mid-wizard.
                    if (!WizardSupport.confirmSameImage(qupath, imageData, TITLE)) return;

                    // Build the sampling server: one channel per substrate, in user-submitted order.
                    List<ColorTransforms.ColorTransform> finalTransforms =
                            new ArrayList<>(substrates.substrates().size());
                    List<SubstrateSpec> specs = new ArrayList<>(substrates.substrates().size());
                    for (int i = 0; i < substrates.substrates().size(); i++) {
                        CommittedSubstrate cs = substrates.substrates().get(i);
                        finalTransforms.add(cs.transform());
                        specs.add(new SubstrateSpec(cs.name(), i));
                    }
                    ImageServer<BufferedImage> samplingServer =
                            new TransformedServerBuilder(channelSet.rawServer())
                                    .applyColorTransforms(finalTransforms)
                                    .build();

                    runSamplingTask(samplingServer, plan, specs, outPath);
                    return;
                }
            }
        }
    }

    /** Wizard phases for {@link #run()}; the substrate screen's Back returns to {@code PARAMS}. */
    private enum Phase { PARAMS, DOMAIN, SUBSTRATES }

    // ---------------- step: sampling parameters ----------------

    /**
     * Custom dialog asking for the step size (µm) and the coordinate-origin convention.
     * Returns null on cancel. Validates that the step is positive before returning.
     *
     * @param prior previously entered values to pre-fill (on Back re-entry), or {@code null}
     */
    private PlanInputs promptForPlanInputs(PlanInputs prior) {
        var resultRef = new java.util.concurrent.atomic.AtomicReference<PlanInputs>(null);

        Stage dialog = new Stage();
        dialog.setTitle(TITLE + " — sampling parameters");
        dialog.initOwner(qupath == null ? null : qupath.getStage());
        // Non-modal so the user can pan/zoom the image while the wizard is open; run() re-checks
        // the active image hasn't changed before committing.
        dialog.initModality(Modality.NONE);

        TextField stepField = new TextField(
                Double.toString(prior == null ? DEFAULT_STEP_MICRONS : prior.stepMicrons()));
        stepField.setPrefColumnCount(8);

        // (0, 0) sits at the ABM-domain center; the plan-confirmation dialog shows the resulting
        // domain bounds, which is the concrete, non-editorialized way to convey that.
        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(new Label("Voxel size (µm):"), 0, 0);
        form.add(stepField, 1, 0);

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
            resultRef.set(new PlanInputs(step, CoordinateOrigin.ABM_DOMAIN_CENTER));
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

    // ---------------- step 4 (plan confirmation) ----------------

    /** The plan-confirmation prose: source, grid size, effective step, pixel size (no domain block). */
    private static String planSummary(SamplingPlan plan) {
        return String.format(
                "Source: %s%n"
                        + "Grid:   %d × %d voxels%n"
                        + "Requested step: %.4g µm%n"
                        + "Effective step: %.4g µm%s%n"
                        + "Pixel size:     %.4g µm",
                plan.domain().sourceDescription(),
                plan.grid().nx(), plan.grid().ny(),
                plan.requestedStepMicrons(),
                plan.effectiveStepMicrons(),
                effectiveStepNote(plan),
                plan.domain().pixelWidthMicrons());
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
    static ChannelSet buildChannelSet(ImageData<BufferedImage> imageData) {
        ImageServer<BufferedImage> raw = imageData.getServer();
        List<ImageChannel> rawChannels = raw.getMetadata().getChannels();
        boolean isRgb = raw.isRGB();

        List<ChannelChoice> options = new ArrayList<>();
        Map<String, Function<BufferedImage, float[]>> extractors = new HashMap<>();
        List<InsertableIdentifier> insertables = new ArrayList<>();

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
            insertables.add(new InsertableIdentifier(label, insertTextFor(label)));
        }

        // Color deconvolution — dropdown entries (prefixed "Deconvolved:") and expression
        // identifiers. We register both the long stain name (resolves via typing) and the short
        // alias (H/E) but only emit one consolidated button "Hematoxylin (H)" that inserts H.
        ColorDeconvolutionStains stains = imageData.getColorDeconvolutionStains();
        if (stains != null && isRgb) {
            for (int s = 1; s <= 3; s++) {
                String stainName = stains.getStain(s).getName();
                if (stainName == null || stainName.isBlank()) continue;
                ColorTransforms.ColorTransform transform =
                        ColorTransforms.createColorDeconvolvedChannel(stains, s);
                options.add(new ChannelChoice("Deconvolved: " + stainName, transform));
                extractors.put(stainName, img -> transform.extractChannel(raw, img, null));

                String alias = shortAliasFor(stainName);
                if (alias != null && !extractors.containsKey(alias)) {
                    extractors.put(alias, img -> transform.extractChannel(raw, img, null));
                    insertables.add(new InsertableIdentifier(stainName + " (" + alias + ")", alias));
                } else {
                    insertables.add(new InsertableIdentifier(stainName, insertTextFor(stainName)));
                }
            }
        }

        // OD-sum dropdown entry; OD identifier aliases for expressions.
        if (isRgb) {
            options.add(new ChannelChoice("Optical density sum", new OpticalDensitySumTransform()));
            extractors.put("OD_R", img -> ChannelMathTransform.readOpticalDensity(img, 0));
            extractors.put("OD_G", img -> ChannelMathTransform.readOpticalDensity(img, 1));
            extractors.put("OD_B", img -> ChannelMathTransform.readOpticalDensity(img, 2));
            extractors.put("OD_sum", img -> ChannelMathTransform.readOpticalDensitySum(img));
            insertables.add(new InsertableIdentifier("OD_R", "OD_R"));
            insertables.add(new InsertableIdentifier("OD_G", "OD_G"));
            insertables.add(new InsertableIdentifier("OD_B", "OD_B"));
            insertables.add(new InsertableIdentifier("OD_sum", "OD_sum"));
        }

        return new ChannelSet(raw, options, extractors, insertables, isRgb);
    }

    /**
     * The text a palette button inserts for a channel named {@code name}: the bare name when it is
     * a legal bare identifier (so {@code R}, {@code OD_sum} insert as-is), else the bracketed form
     * {@code [name]} so names with spaces or punctuation parse as a single channel reference.
     */
    private static String insertTextFor(String name) {
        return isBareIdentifier(name) ? name : "[" + name + "]";
    }

    /** True when {@code name} matches the parser's bare-identifier rule (letter/_ then letter/digit/_). */
    private static boolean isBareIdentifier(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /** Common short alias for an H&E stain name, or null if no obvious alias. */
    private static String shortAliasFor(String stainName) {
        String lower = stainName.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("hematoxylin") || lower.equals("haematoxylin")) return "H";
        if (lower.equals("eosin")) return "E";
        return null;
    }

    // ---------------- step 6 (file save) ----------------

    private Path chooseOutputPath(ImageData<BufferedImage> imageData) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save substrates CSV");
        fc.setInitialFileName(suggestFileName(imageData));
        fc.setInitialDirectory(WizardSupport.defaultOutputDirectory(imageData));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        Stage owner = qupath == null ? null : qupath.getStage();
        File f = fc.showSaveDialog(owner);
        if (f == null) {
            return null;
        }
        WizardSupport.rememberOutputDir(f.toPath().getParent());
        return f.toPath();
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
                    plan.physiCellDomain().writeXml(WizardSupport.domainSidecarPath(outPath));
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
                            + " voxels to " + outPath.getFileName()
                            + " (+ " + WizardSupport.domainSidecarPath(outPath).getFileName() + ")");
            logger.info("BIWT wrote {} ({} × {} voxels, {} substrate(s))",
                    outPath, r.grid().nx(), r.grid().ny(), r.substrates().size());
            WizardSupport.offerConfigUpdate(qupath, TITLE, plan.physiCellDomain());
            WizardSupport.offerResultsPreview(qupath, TITLE, ViewerModel.of(r, null));
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
     * that produces this channel's values from the raw server. (Expression mode is a separate radio,
     * not a dropdown entry.)
     */
    record ChannelChoice(String label, ColorTransforms.ColorTransform transform) {
        @Override public String toString() { return label; }
    }

    /**
     * Everything the substrate dialog needs: the raw server, the dropdown options, the
     * per-identifier pixel extractors an expression can reference, and a curated list of
     * "click to insert" identifiers the expression palette renders as buttons.
     *
     * <p>{@code extractorsForExpression} is the resolution map — every identifier the user might
     * legally type, including short aliases like {@code H}/{@code E} <em>and</em> their long
     * stain-name equivalents. {@code insertables} is the user-facing palette — one entry per
     * concept, with the long-name aliases consolidated into the short-name button to avoid
     * duplicates (e.g. one button labelled {@code "Hematoxylin (H)"} that inserts {@code H}).
     */
    record ChannelSet(
            ImageServer<BufferedImage> rawServer,
            List<ChannelChoice> options,
            Map<String, Function<BufferedImage, float[]>> extractorsForExpression,
            List<InsertableIdentifier> insertables,
            boolean rgb) {}

    /** One palette button: the label the user sees and the text that gets inserted at the caret. */
    record InsertableIdentifier(String displayLabel, String insertText) {}

    /** One committed substrate: name, its own {@link ColorTransforms.ColorTransform}, and a label for the list. */
    record CommittedSubstrate(String name, ColorTransforms.ColorTransform transform, String displayLabel) {}

    /** Wrapper for the substrate dialog's return value. */
    record SubstrateChoices(List<CommittedSubstrate> substrates) {}

    /**
     * Non-modal dialog: build a list of substrates with Add / Back / Finish / Cancel / Remove.
     * Returns a {@link Nav}: Finish advances with the list, Back asks the wizard to step to the
     * previous screen (the substrate list is handed back so the caller can re-seed it via
     * {@code initial}), Cancel (or closing the window) aborts.
     */
    static final class SubstrateDialog {

        /**
         * @param allowEmptyFinish when true, Finish works with zero substrates (advances with an
         *     empty list) — the combined wizard treats substrates as optional. When false
         *     (substrate-only wizard), Finish requires at least one.
         * @param initial substrates to pre-populate the list with (for Back re-entry); may be empty.
         */
        static Nav<SubstrateChoices> show(Stage owner, ChannelSet channelSet, boolean allowEmptyFinish,
                                          List<CommittedSubstrate> initial) {
            if (channelSet.options().isEmpty()) {
                Dialogs.showErrorMessage(TITLE, "Image has no channels reported by its server.");
                return Nav.cancel();
            }

            List<CommittedSubstrate> committed = new ArrayList<>(initial == null ? List.of() : initial);
            ObservableList<String> listItems = FXCollections.observableArrayList();
            for (CommittedSubstrate cs : committed) {
                listItems.add(cs.displayLabel());
            }
            var navResult = new java.util.concurrent.atomic.AtomicReference<Nav<SubstrateChoices>>(Nav.cancel());

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

            // -------- source mode: a channel from the dropdown, or a typed expression --------
            RadioButton channelModeRadio = new RadioButton("Channel");
            RadioButton expressionModeRadio = new RadioButton("Expression");
            ToggleGroup sourceModeGroup = new ToggleGroup();
            channelModeRadio.setToggleGroup(sourceModeGroup);
            expressionModeRadio.setToggleGroup(sourceModeGroup);
            channelModeRadio.setSelected(true);
            HBox sourceModeRow = new HBox(16, channelModeRadio, expressionModeRadio);
            sourceModeRow.setAlignment(Pos.CENTER_LEFT);
            javafx.beans.binding.BooleanExpression isExpressionMode = expressionModeRadio.selectedProperty();

            // -------- expression editor (shown only in Expression mode) --------
            TextArea expressionArea = new TextArea();
            expressionArea.setPromptText("e.g. 0.5*H - 0.3*E + clip(R, 0, 200)");
            expressionArea.setPrefRowCount(2);
            Label expressionStatus = new Label();
            expressionStatus.setWrapText(true);

            // Two palette rows: one for channel identifiers, one for built-in functions. Each
            // button inserts at the current caret position; for function buttons the caret then
            // jumps to the first argument slot so the user can keep typing.
            FlowPane channelPalette = new FlowPane(6, 4);
            Label channelHeader = new Label("Channels:");
            channelHeader.setStyle("-fx-text-fill: #555;");
            channelPalette.getChildren().add(channelHeader);
            for (InsertableIdentifier ins : channelSet.insertables()) {
                channelPalette.getChildren().add(
                        makeInsertButton(expressionArea, ins.displayLabel(), ins.insertText(),
                                ins.insertText().length()));
            }

            // Images can have many channels (tens or more). Keep the palette in a scroll box with a
            // capped default height so it never pushes the rest of the form (and the buttons) off
            // screen; the box grows when the user enlarges the resizable dialog.
            ScrollPane channelScroll = new ScrollPane(channelPalette);
            channelScroll.setFitToWidth(true);
            channelScroll.setPrefViewportHeight(110);
            channelScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            channelScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            channelScroll.setStyle("-fx-background-color: transparent;");

            FlowPane functionPalette = new FlowPane(6, 4);
            Label functionHeader = new Label("Functions:");
            functionHeader.setStyle("-fx-text-fill: #555;");
            functionPalette.getChildren().add(functionHeader);
            for (FunctionInsert fn : FUNCTION_PALETTE) {
                functionPalette.getChildren().add(
                        makeInsertButton(expressionArea, fn.label(), fn.insertText(), fn.caretOffset()));
            }

            // The channel dropdown vs the expression editor are shown/collapsed by source mode;
            // those bindings live in the layout section below (where exprBox is assembled).

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
            expressionModeRadio.selectedProperty().addListener((obs, was, now) -> {
                if (now) revalidateExpression.run();
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
            Button backButton = new Button("Back");
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
                            .or(nameAlreadyUsed)
                            .or(isExpressionMode.not()
                                    .and(channelBox.getSelectionModel().selectedItemProperty().isNull()))
                            .or(isExpressionMode.and(compiledExpression.isNull())));
            if (!allowEmptyFinish) {
                finishButton.disableProperty().bind(Bindings.isEmpty(listItems));
            }

            // Enter in the name field commits the substrate, if Add is enabled.
            nameField.setOnAction(e -> {
                if (!addButton.isDisable()) addButton.fire();
            });

            // -------- layout --------
            HBox nameRow = new HBox(8, nameField, nameWarning);
            HBox.setHgrow(nameField, Priority.ALWAYS);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            // Channel mode shows the dropdown; Expression mode shows the editor + palettes. Each
            // input collapses (unmanaged) when the other mode is active, so the dialog stays compact.
            Label channelLabel = new Label("Channel:");
            channelLabel.visibleProperty().bind(isExpressionMode.not());
            channelLabel.managedProperty().bind(isExpressionMode.not());
            channelBox.visibleProperty().bind(isExpressionMode.not());
            channelBox.managedProperty().bind(isExpressionMode.not());

            VBox exprBox = new VBox(8,
                    new Label("Expression:"), expressionArea, channelScroll, functionPalette, expressionStatus);
            VBox.setVgrow(channelScroll, Priority.ALWAYS);
            exprBox.visibleProperty().bind(isExpressionMode);
            exprBox.managedProperty().bind(isExpressionMode);

            GridPane form = new GridPane();
            form.setHgap(8);
            form.setVgap(8);
            form.add(new Label("Name:"), 0, 0);
            form.add(nameRow, 1, 0);
            form.add(new Label("Source:"), 0, 1);
            form.add(sourceModeRow, 1, 1);
            form.add(channelLabel, 0, 2);
            form.add(channelBox, 1, 2);
            form.add(exprBox, 0, 3, 2, 1);
            GridPane.setHgrow(nameRow, Priority.ALWAYS);
            GridPane.setHgrow(channelBox, Priority.ALWAYS);
            GridPane.setHgrow(exprBox, Priority.ALWAYS);
            GridPane.setVgrow(exprBox, Priority.ALWAYS);

            HBox buttons = new HBox(10, addButton, backButton, finishButton, cancelButton);
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
            // Extra window height flows into the form, and from there into the channel-palette
            // scroll box (the row marked vgrow), so enlarging the dialog shows more channels.
            VBox.setVgrow(form, Priority.ALWAYS);

            Stage stage = new Stage();
            stage.setTitle("BIWT — define substrates");
            stage.initOwner(owner);
            // Non-modal: the user can pan/zoom the source image while defining substrates. The
            // calling wizard re-validates the active image before sampling.
            stage.initModality(Modality.NONE);
            stage.setScene(new Scene(root));
            stage.setResizable(true);
            stage.setMinWidth(440);
            stage.setMinHeight(380);

            addButton.setOnAction(e -> {
                ChannelChoice choice = channelBox.getValue();
                String name = nameField.getText().trim();
                CommittedSubstrate cs;
                if (expressionModeRadio.isSelected()) {
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
            // Back hands the committed list back so the wizard can re-seed this dialog on return.
            finishButton.setOnAction(e -> { navResult.set(Nav.next(new SubstrateChoices(committed))); stage.close(); });
            backButton.setOnAction(e -> { navResult.set(Nav.back()); stage.close(); });
            cancelButton.setOnAction(e -> { navResult.set(Nav.cancel()); stage.close(); });

            stage.showAndWait();
            return navResult.get();
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

        /**
         * Build a small palette button. {@code caretAdvance} is how far past the original caret
         * position the cursor should land after insertion — equal to {@code insertText.length()}
         * for plain identifier inserts (cursor at the end), or pointing inside the parens for
         * function inserts so the user can keep typing.
         */
        private static Button makeInsertButton(TextArea target, String label,
                                               String insertText, int caretAdvance) {
            Button b = new Button(label);
            // '_' in a Button label is otherwise consumed as the mnemonic-accelerator marker on
            // some platforms (so 'OD_R' would render as 'ODR'). Disable that interpretation.
            b.setMnemonicParsing(false);
            b.setStyle("-fx-font-size: 11; -fx-padding: 1 6 1 6;");
            b.setFocusTraversable(false);
            b.setOnAction(ev -> {
                int caret = target.getCaretPosition();
                target.insertText(caret, insertText);
                target.positionCaret(caret + caretAdvance);
                target.requestFocus();
            });
            return b;
        }
    }

    /** Built-in functions surfaced in the expression palette. Order matches the PRD listing. */
    private record FunctionInsert(String label, String insertText, int caretOffset) {}

    private static final List<FunctionInsert> FUNCTION_PALETTE = List.of(
            new FunctionInsert("log(_)",       "log()",     4),
            new FunctionInsert("log10(_)",     "log10()",   6),
            new FunctionInsert("exp(_)",       "exp()",     4),
            new FunctionInsert("sqrt(_)",      "sqrt()",    5),
            new FunctionInsert("abs(_)",       "abs()",     4),
            new FunctionInsert("min(_, _)",    "min(, )",   4),
            new FunctionInsert("max(_, _)",    "max(, )",   4),
            new FunctionInsert("clip(_, _, _)","clip(, , )", 5)
    );
}
