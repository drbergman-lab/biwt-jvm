package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.BiwtCellPlacer;
import io.github.drbergmanlab.biwt.core.BiwtSampler;
import io.github.drbergmanlab.biwt.core.CellPlacementResult;
import io.github.drbergmanlab.biwt.core.SamplingPlan;
import io.github.drbergmanlab.biwt.core.SamplingResult;
import io.github.drbergmanlab.biwt.core.SubstrateSpec;
import io.github.drbergmanlab.biwt.core.cells.CellPlacementOptions;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.coord.PhysiCellDomain;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biwt.abm.BiwtAbmCommand.ChannelSet;
import qupath.ext.biwt.abm.BiwtAbmCommand.CommittedSubstrate;
import qupath.ext.biwt.abm.BiwtAbmCommand.SubstrateChoices;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.TransformedServerBuilder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The unified wizard — <b>Extensions → BIWT → Build initial conditions…</b> — that produces
 * substrates and/or cells in a single pass over one ABM domain and origin, so the two exports
 * always share the same coordinate frame. It reuses the substrate and cell machinery; the one
 * coordinate choice (annotation-center) and one domain selection are made once, here.
 */
public final class BiwtBuildCommand {

    private static final Logger logger = LoggerFactory.getLogger(BiwtBuildCommand.class);

    private static final String TITLE = "BIWT — Build initial conditions";
    private static final double DEFAULT_STEP_MICRONS = 20.0;
    private static final CoordinateOrigin ORIGIN = CoordinateOrigin.ABM_DOMAIN_CENTER;

    private final QuPathGUI qupath;
    private final BiwtSampler sampler = BiwtSampler.create();
    private final BiwtCellPlacer placer = BiwtCellPlacer.create();

    public BiwtBuildCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** What to build + the cell options. {@code voxelMicrons} is only meaningful when substrates run. */
    private record BuildInputs(boolean substrates, boolean cells, double voxelMicrons,
                               CellPlacementOptions cellOptions) {}

    public void run() {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(TITLE, "Open an image first.");
            return;
        }

        boolean go = Dialogs.showConfirmDialog(TITLE,
                "Build PhysiCell initial conditions from this image.\n\n"
                        + "Substrates sample raw channel intensities (normalize first). Cells come "
                        + "from existing classified detections (segment + classify first). Continue?");
        if (!go) return;

        BuildInputs inputs = promptForInputs(imageData);
        if (inputs == null) return;

        AbmDomain domain = WizardSupport.chooseDomain(qupath, imageData, TITLE);
        if (domain == null) return;

        // Plan substrates (if any) and resolve the PhysiCell domain to emit.
        SamplingPlan plan = null;
        PhysiCellDomain physiCellDomain;
        if (inputs.substrates()) {
            try {
                plan = sampler.plan(domain, inputs.voxelMicrons(), ORIGIN);
            } catch (DomainException d) {  // e.g. non-square pixels
                Dialogs.showErrorMessage(TITLE, d.getMessage());
                return;
            }
            physiCellDomain = plan.physiCellDomain();
        } else {
            physiCellDomain = PhysiCellDomain.ofAnnotation(domain, ORIGIN);
        }

        // Confirm — show what will be built and the PhysiCell domain bounds.
        String planLine = inputs.substrates()
                ? String.format("Substrates: %d × %d voxels (effective step %.4g µm)%n",
                        plan.grid().nx(), plan.grid().ny(), plan.effectiveStepMicrons())
                : "";
        if (!Dialogs.showConfirmDialog(TITLE, String.format(
                "Source: %s%n%s%s%nPhysiCell domain:%n%s%n%nProceed?",
                domain.sourceDescription(),
                planLine,
                inputs.cells() ? "Cells: from classified detections in the domain" : "",
                physiCellDomain.summary()))) {
            return;
        }

        // Define substrates (reuse the substrate dialog) if requested.
        List<SubstrateSpec> specs = new ArrayList<>();
        List<ColorTransforms.ColorTransform> transforms = new ArrayList<>();
        ChannelSet channelSet = null;
        if (inputs.substrates()) {
            channelSet = BiwtAbmCommand.buildChannelSet(imageData);
            // Substrates are optional even here: Finish works with an empty list, so the user can
            // reach this step and decide to add none (e.g. just cells + the domain).
            SubstrateChoices choices = BiwtAbmCommand.SubstrateDialog.show(qupath.getStage(), channelSet, true);
            if (choices == null) return;  // cancelled (distinct from "finished with none")
            for (int i = 0; i < choices.substrates().size(); i++) {
                CommittedSubstrate cs = choices.substrates().get(i);
                transforms.add(cs.transform());
                specs.add(new SubstrateSpec(cs.name(), i));
            }
        }

        // Choose output folder + base name. Use the *effective* outputs (substrates may have been
        // skipped by finishing the substrate dialog with none).
        SaveTarget target = chooseSaveTarget(imageData, !specs.isEmpty(), inputs.cells());
        if (target == null) return;

        runBuildTask(imageData, domain, plan, physiCellDomain, inputs,
                channelSet, transforms, specs, target);
    }

    // ---------------- parameters dialog ----------------

    private BuildInputs promptForInputs(ImageData<BufferedImage> imageData) {
        AtomicReference<BuildInputs> ref = new AtomicReference<>(null);

        Stage dialog = new Stage();
        dialog.setTitle(TITLE);
        dialog.initOwner(qupath == null ? null : qupath.getStage());
        dialog.initModality(Modality.APPLICATION_MODAL);

        CheckBox substrateCheck = new CheckBox("Build substrates");
        substrateCheck.setSelected(true);
        CheckBox cellCheck = new CheckBox("Build cells");
        cellCheck.setSelected(!imageData.getHierarchy().getDetectionObjects().isEmpty());

        TextField stepField = new TextField(Double.toString(DEFAULT_STEP_MICRONS));
        stepField.setPrefColumnCount(8);
        stepField.disableProperty().bind(substrateCheck.selectedProperty().not());

        CheckBox volumeCheck = new CheckBox("Include cell volume column (from segmented area)");
        volumeCheck.disableProperty().bind(cellCheck.selectedProperty().not());
        CheckBox unclassifiedCheck = new CheckBox("Place unclassified detections as type:");
        unclassifiedCheck.disableProperty().bind(cellCheck.selectedProperty().not());
        TextField unclassifiedField = new TextField("unclassified");
        unclassifiedField.setPrefColumnCount(12);
        unclassifiedField.disableProperty().bind(
                cellCheck.selectedProperty().not().or(unclassifiedCheck.selectedProperty().not()));

        // Substrates section.
        HBox voxelRow = new HBox(8, new Label("Voxel size (µm):"), stepField);
        voxelRow.setAlignment(Pos.CENTER_LEFT);
        VBox substrateSection = new VBox(8, substrateCheck, voxelRow);
        substrateSection.setPadding(new Insets(2, 0, 6, 16));

        // Cell-positioning section.
        HBox unclassifiedRow = new HBox(8, unclassifiedCheck, unclassifiedField);
        unclassifiedRow.setAlignment(Pos.CENTER_LEFT);
        VBox cellSection = new VBox(8, cellCheck, volumeCheck, unclassifiedRow);
        cellSection.setPadding(new Insets(2, 0, 6, 16));

        Label warning = new Label();
        warning.setStyle("-fx-text-fill: #cc0000;");

        Button okButton = new Button("Next…");
        Button cancelButton = new Button("Cancel");
        okButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);

        okButton.setOnAction(e -> {
            if (!substrateCheck.isSelected() && !cellCheck.isSelected()) {
                warning.setText("Choose substrates, cells, or both.");
                return;
            }
            double step = 0;
            if (substrateCheck.isSelected()) {
                try {
                    step = Double.parseDouble(stepField.getText().trim());
                } catch (NumberFormatException nfe) {
                    warning.setText("Enter a numeric voxel size.");
                    return;
                }
                if (!(step > 0)) {
                    warning.setText("Voxel size must be positive.");
                    return;
                }
            }
            CellPlacementOptions cellOptions = null;
            if (cellCheck.isSelected()) {
                String unclassified = null;
                if (unclassifiedCheck.isSelected()) {
                    unclassified = unclassifiedField.getText().trim();
                    if (unclassified.isEmpty()) {
                        warning.setText("Enter a type name for unclassified detections (or untick it).");
                        return;
                    }
                }
                cellOptions = new CellPlacementOptions(volumeCheck.isSelected(), unclassified, java.util.Map.of());
            }
            ref.set(new BuildInputs(substrateCheck.isSelected(), cellCheck.isSelected(), step, cellOptions));
            dialog.close();
        });
        cancelButton.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, cancelButton, okButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(10,
                sectionBanner("Substrates"), substrateSection,
                sectionBanner("Cell positioning"), cellSection,
                warning, buttons);
        root.setPadding(new Insets(16));
        dialog.setScene(new Scene(root));
        dialog.setResizable(false);
        dialog.showAndWait();
        return ref.get();
    }

    /** A full-width section banner for the parameters dialog. */
    private static Label sectionBanner(String text) {
        Label l = new Label(text);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setStyle("-fx-background-color:#dfe6ef; -fx-padding:5 8; -fx-font-weight:bold; "
                + "-fx-text-fill:#2a3b4d; -fx-font-size:12.5;");
        return l;
    }

    // ---------------- save target ----------------

    private record SaveTarget(Path folder, String substratesName, String cellsName, String domainName) {
        Path substratesCsv() { return folder.resolve(substratesName); }
        Path cellsCsv()      { return folder.resolve(cellsName); }
        Path domainXml()     { return folder.resolve(domainName); }
    }

    private SaveTarget chooseSaveTarget(ImageData<BufferedImage> imageData, boolean hasSubstrates, boolean hasCells) {
        AtomicReference<SaveTarget> ref = new AtomicReference<>(null);

        Stage dialog = new Stage();
        dialog.setTitle(TITLE + " — save outputs");
        dialog.initOwner(qupath == null ? null : qupath.getStage());
        dialog.initModality(Modality.APPLICATION_MODAL);

        TextField folderField = new TextField();
        folderField.setPrefColumnCount(28);
        folderField.setEditable(false);
        Button browse = new Button("Browse…");
        TextField baseField = new TextField(defaultBase(imageData));
        baseField.setPrefColumnCount(20);

        // One editable filename box per output that will actually be written. Editing the base name
        // re-derives all of them (overwriting manual edits); the box values are what gets saved.
        TextField subField = new TextField();
        TextField cellField = new TextField();
        TextField domainField = new TextField();
        for (TextField f : List.of(subField, cellField, domainField)) {
            f.setPrefColumnCount(28);
        }
        Runnable applyBase = () -> {
            String b = baseField.getText().trim();
            String prefix = b.isEmpty() ? "" : b + "-";
            subField.setText(prefix + "substrates.csv");
            cellField.setText(prefix + "cells.csv");
            domainField.setText(prefix + "physicell-domain.xml");
        };
        applyBase.run();
        baseField.textProperty().addListener((o, a, v) -> applyBase.run());

        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Output folder");
            File f = dc.showDialog(dialog);
            if (f != null) folderField.setText(f.getAbsolutePath());
        });

        Label warning = new Label();
        warning.setStyle("-fx-text-fill: #cc0000;");

        Button okButton = new Button("Save");
        Button cancelButton = new Button("Cancel");
        okButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);

        okButton.setOnAction(e -> {
            String folder = folderField.getText().trim();
            if (folder.isEmpty()) { warning.setText("Choose an output folder."); return; }
            String sub = subField.getText().trim();
            String cell = cellField.getText().trim();
            String domain = domainField.getText().trim();
            if (hasSubstrates && sub.isEmpty()) { warning.setText("Enter a substrates file name."); return; }
            if (hasCells && cell.isEmpty())    { warning.setText("Enter a cells file name."); return; }
            if (domain.isEmpty())              { warning.setText("Enter a domain file name."); return; }
            ref.set(new SaveTarget(Path.of(folder),
                    hasSubstrates ? sub : null, hasCells ? cell : null, domain));
            dialog.close();
        });
        cancelButton.setOnAction(e -> dialog.close());

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        int row = 0;
        form.add(new Label("Output folder:"), 0, row);
        form.add(new HBox(8, folderField, browse), 1, row++);
        form.add(new Label("Base name:"), 0, row);
        form.add(baseField, 1, row++);

        Label filesHeader = new Label("Files to write:");
        filesHeader.setStyle("-fx-font-weight:bold;");
        form.add(filesHeader, 0, row++, 2, 1);
        if (hasSubstrates) { form.add(new Label("Substrates:"), 0, row); form.add(subField, 1, row++); }
        if (hasCells)      { form.add(new Label("Cells:"), 0, row); form.add(cellField, 1, row++); }
        form.add(new Label("Domain:"), 0, row); form.add(domainField, 1, row++);

        HBox buttons = new HBox(10, cancelButton, okButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox root = new VBox(12, form, warning, buttons);
        root.setPadding(new Insets(16));
        dialog.setScene(new Scene(root));
        dialog.setResizable(false);
        dialog.showAndWait();
        return ref.get();
    }

    private static String defaultBase(ImageData<BufferedImage> imageData) {
        String base = imageData.getServer().getMetadata().getName();
        if (base == null || base.isBlank()) base = "biwt";
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base;
    }

    // ---------------- background build ----------------

    private void runBuildTask(ImageData<BufferedImage> imageData, AbmDomain domain, SamplingPlan plan,
                              PhysiCellDomain physiCellDomain, BuildInputs inputs, ChannelSet channelSet,
                              List<ColorTransforms.ColorTransform> transforms, List<SubstrateSpec> specs,
                              SaveTarget target) {
        Label status = new Label("Working…");
        ProgressBar bar = new ProgressBar();
        bar.setPrefWidth(360);
        VBox box = new VBox(12, new Label("Building initial conditions…"), status, bar);
        box.setPadding(new Insets(20));
        Stage progress = new Stage();
        progress.setTitle(TITLE);
        progress.initOwner(qupath.getStage());
        progress.initModality(Modality.NONE);
        progress.setScene(new Scene(box));
        progress.setResizable(false);

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                List<String> written = new ArrayList<>();
                String cellNote = "";

                if (!specs.isEmpty()) {  // substrates requested AND at least one defined
                    updateMessage("Sampling " + specs.size() + " substrate(s)…");
                    ImageServer<BufferedImage> server = new TransformedServerBuilder(channelSet.rawServer())
                            .applyColorTransforms(transforms)
                            .build();
                    SamplingResult sres = sampler.sample(server, plan, specs);
                    sres.writeCsv(target.substratesCsv());
                    written.add(target.substratesCsv().getFileName().toString());
                }

                if (inputs.cells()) {
                    updateMessage("Placing cells…");
                    CellPlacementResult cres = placer.place(imageData, domain, ORIGIN, inputs.cellOptions());
                    if (cres.count() == 0) {
                        cellNote = "  (no cells placed — none classified inside the domain)";
                    } else {
                        cres.writeCsv(target.cellsCsv());
                        written.add(cres.count() + " cells → " + target.cellsCsv().getFileName());
                    }
                }

                updateMessage("Writing domain…");
                physiCellDomain.writeXml(target.domainXml());
                written.add(target.domainXml().getFileName().toString());
                return String.join(", ", written) + cellNote;
            }
        };
        bar.progressProperty().bind(task.progressProperty());
        status.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            progress.close();
            Dialogs.showInfoNotification(TITLE, "Wrote: " + task.getValue());
            logger.info("BIWT build wrote: {}", task.getValue());
            WizardSupport.offerConfigUpdate(qupath, TITLE, physiCellDomain);
        });
        task.setOnFailed(e -> {
            progress.close();
            Throwable cause = task.getException();
            logger.error("BIWT build failed", cause);
            Dialogs.showErrorMessage(TITLE, cause == null ? "Build failed." : cause.getMessage());
        });

        progress.show();
        Thread t = new Thread(task, "biwt-build");
        t.setDaemon(true);
        t.start();
    }
}
