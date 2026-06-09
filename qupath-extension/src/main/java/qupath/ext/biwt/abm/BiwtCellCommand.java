package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.BiwtCellPlacer;
import io.github.drbergmanlab.biwt.core.CellPlacementResult;
import io.github.drbergmanlab.biwt.core.cells.CellPlacementOptions;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biwt.abm.viz.ViewerModel;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import java.awt.image.BufferedImage;

/**
 * The interactive wizard launched from <b>Extensions → BIWT → Place cells…</b> — the discrete-cell
 * sibling of {@link BiwtAbmCommand}.
 *
 * <p>BIWT does not segment or classify; it links. The user first runs a QuPath segmentation
 * (StarDist, Cellpose, InstanSeg, or built-in cell detection) and classifies the cells. This wizard
 * then reads those detections, places each centroid in the ABM frame (the same frame the substrate
 * export uses for the same domain + origin), and writes a PhysiCell cell-IC CSV ({@code x,y,z,type}
 * with named types, optional {@code volume}).
 *
 * <p>Cell placement is geometric only (no pixel reads), so it runs synchronously on the JavaFX thread.
 */
public final class BiwtCellCommand {

    private static final Logger logger = LoggerFactory.getLogger(BiwtCellCommand.class);

    private static final String TITLE = "BIWT — Place cells";
    /** The user's choices from the options dialog. */
    private record CellInputs(CoordinateOrigin origin, CellPlacementOptions options) {}

    private final QuPathGUI qupath;
    private final BiwtCellPlacer placer = BiwtCellPlacer.create();

    public BiwtCellCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Entry point — call from the menu-item action handler on the JavaFX thread. */
    public void run() {
        ImageData<BufferedImage> imageData = qupath.getImageData();
        if (imageData == null) {
            Dialogs.showErrorMessage(TITLE, "Open an image first.");
            return;
        }
        if (imageData.getHierarchy().getDetectionObjects().isEmpty()) {
            Dialogs.showErrorMessage(TITLE,
                    "No detection objects found.\n\n"
                            + "Run a cell segmentation (StarDist, Cellpose, InstanSeg, or QuPath's\n"
                            + "built-in cell detection) and classify the cells first — BIWT links\n"
                            + "those results into PhysiCell, it does not segment.");
            return;
        }

        CellInputs inputs = promptForInputs();
        if (inputs == null) return;

        CellPlacementResult result = placeWithUser(imageData, inputs);
        if (result == null) return;

        if (result.count() == 0) {
            Dialogs.showErrorMessage(TITLE,
                    "No cells placed.\n\n"
                            + "No classified detections fell inside the ABM domain. Check that the\n"
                            + "detections are classified and lie within the domain"
                            + (inputs.options().unclassifiedTypeName() == null
                                    ? ", or enable \"place unclassified detections\"." : "."));
            return;
        }

        Path outPath = chooseOutputPath(imageData);
        if (outPath == null) return;

        Path domainPath = WizardSupport.domainSidecarPath(outPath);
        try {
            result.writeCsv(outPath);
            result.physiCellDomain().writeXml(domainPath);  // bounds-only <domain> (x/y bounds)
        } catch (IOException e) {
            logger.error("Failed to write cell outputs", e);
            Dialogs.showErrorMessage(TITLE, "Failed to write output: " + e.getMessage());
            return;
        }
        logger.info("BIWT placed {} cell(s) → {}", result.count(), outPath);
        Dialogs.showInfoNotification(TITLE,
                "Placed " + result.count() + " cell(s) to " + outPath.getFileName()
                        + " (+ " + domainPath.getFileName() + ")");
        WizardSupport.offerConfigUpdate(qupath, TITLE, result.physiCellDomain());
        WizardSupport.offerResultsPreview(qupath, TITLE, ViewerModel.of(null, result));
    }

    // ---------------- options dialog ----------------

    private CellInputs promptForInputs() {
        AtomicReference<CellInputs> resultRef = new AtomicReference<>(null);

        Stage dialog = new Stage();
        dialog.setTitle(TITLE);
        dialog.initOwner(qupath == null ? null : qupath.getStage());
        dialog.initModality(Modality.APPLICATION_MODAL);

        CheckBox volumeCheck = new CheckBox("Include volume column (estimated from segmented area)");

        CheckBox unclassifiedCheck = new CheckBox("Place unclassified detections as type:");
        TextField unclassifiedField = new TextField("unclassified");
        unclassifiedField.setPrefColumnCount(12);
        unclassifiedField.disableProperty().bind(unclassifiedCheck.selectedProperty().not());

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.add(volumeCheck, 0, 0, 2, 1);
        form.add(unclassifiedCheck, 0, 1, 2, 1);
        form.add(unclassifiedField, 1, 2);

        Label warning = new Label();
        warning.setStyle("-fx-text-fill: #cc0000;");

        Button okButton = new Button("Place cells");
        Button cancelButton = new Button("Cancel");
        okButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);

        okButton.setOnAction(e -> {
            CoordinateOrigin origin = CoordinateOrigin.ABM_DOMAIN_CENTER;
            String unclassified = null;
            if (unclassifiedCheck.isSelected()) {
                unclassified = unclassifiedField.getText().trim();
                if (unclassified.isEmpty()) {
                    warning.setText("Enter a type name for unclassified detections (or untick the box).");
                    return;
                }
            }
            CellPlacementOptions options = new CellPlacementOptions(
                    volumeCheck.isSelected(), unclassified, java.util.Map.of());
            resultRef.set(new CellInputs(origin, options));
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

    // ---------------- domain detect + place (with whole-image fallback) ----------------

    private CellPlacementResult placeWithUser(ImageData<BufferedImage> imageData, CellInputs in) {
        AbmDomain domain = WizardSupport.chooseDomain(qupath, imageData, TITLE);
        if (domain == null) return null;
        try {
            return placer.place(imageData, domain, in.origin(), in.options());
        } catch (DomainException d) {
            Dialogs.showErrorMessage(TITLE, d.getMessage());
            return null;
        }
    }

    // ---------------- file save ----------------

    private Path chooseOutputPath(ImageData<BufferedImage> imageData) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save cells CSV");
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
        if (base == null || base.isBlank()) base = "cells";
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "-cells.csv";
    }
}
