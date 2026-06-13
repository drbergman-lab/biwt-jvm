package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.coord.PhysiCellConfigUpdater;
import io.github.drbergmanlab.biwt.core.coord.PhysiCellDomain;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions;
import io.github.drbergmanlab.biwt.core.domain.DomainDetector;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biwt.abm.viz.ResultsViewer;
import qupath.ext.biwt.abm.viz.ViewerModel;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Small GUI helpers shared by the BIWT wizards (substrate, cell, and combined). */
final class WizardSupport {

    private static final Logger logger = LoggerFactory.getLogger(WizardSupport.class);

    /**
     * The last output folder the user saved to, persisted across QuPath restarts so that processing
     * several images in a row defaults to the same destination. Empty until the first save.
     */
    private static final StringProperty lastOutputDir =
            PathPrefs.createPersistentPreference("biwt.lastOutputDir", "");

    private WizardSupport() {}

    /**
     * A sensible default output folder for the save dialogs, shared by all three wizards so they
     * agree. Resolution order: the last folder the user saved to (when it still exists), else the
     * directory containing the current image, else the user's home directory.
     */
    static String defaultOutputFolder(ImageData<BufferedImage> imageData) {
        String last = lastOutputDir.get();
        if (last != null && !last.isBlank() && new File(last).isDirectory()) {
            return last;
        }
        String imageDir = imageDirectory(imageData);
        if (imageDir != null) {
            return imageDir;
        }
        return System.getProperty("user.home", "");
    }

    /** As {@link #defaultOutputFolder} but as an existing {@link File}, or {@code null} if none. */
    static File defaultOutputDirectory(ImageData<BufferedImage> imageData) {
        File dir = new File(defaultOutputFolder(imageData));
        return dir.isDirectory() ? dir : null;
    }

    /** Persist {@code folder} as the tool's last-used output directory (no-op for a null/blank path). */
    static void rememberOutputDir(Path folder) {
        if (folder != null) {
            lastOutputDir.set(folder.toAbsolutePath().toString());
        }
    }

    /** The directory containing the current image (from its server URI), or {@code null} if not a local file. */
    private static String imageDirectory(ImageData<BufferedImage> imageData) {
        if (imageData == null) {
            return null;
        }
        try {
            for (URI uri : imageData.getServer().getURIs()) {
                Path p = GeneralTools.toPath(uri);
                if (p == null) {
                    continue;
                }
                Path dir = Files.isDirectory(p) ? p : p.getParent();
                if (dir != null && Files.isDirectory(dir)) {
                    return dir.toAbsolutePath().toString();
                }
            }
        } catch (Exception e) {
            logger.debug("Could not derive image directory for default output folder", e);
        }
        return null;
    }

    /**
     * Resolve the ABM domain, asking the user when it's ambiguous. If exactly one {@code abm_domain}
     * annotation exists it's used directly. Otherwise (none, or several) the user picks from the
     * image's rectangle annotations or "whole image". Returns {@code null} if the user cancels or on
     * a fatal error (already reported).
     */
    static AbmDomain chooseDomain(QuPathGUI qupath, ImageData<BufferedImage> imageData, String title) {
        DomainDetector detector = new DomainDetector();
        List<PathObject> named = imageData.getHierarchy().getAnnotationObjects().stream()
                .filter(o -> "abm_domain".equals(o.getName()))
                .toList();

        if (named.size() == 1) {
            try {
                return detector.fromAnnotation(imageData, named.get(0));
            } catch (DomainException e) {
                Dialogs.showErrorMessage(title, e.getMessage());
                return null;
            }
        }

        String message = named.size() > 1
                ? "More than one 'abm_domain' annotation found.\nChoose which one defines the ABM domain:"
                : "No annotation named 'abm_domain' found.\nChoose an annotation to use as the ABM domain, or the whole image:";

        LinkedHashMap<String, PathObject> byLabel = new LinkedHashMap<>();
        for (PathObject o : imageData.getHierarchy().getAnnotationObjects()) {
            if (o.getROI() instanceof RectangleROI) {
                byLabel.put(labelFor(o, byLabel.size()), o);
            }
        }
        List<String> choices = new ArrayList<>(byLabel.keySet());
        String wholeImage = "Whole image";
        choices.add(wholeImage);

        String chosen = Dialogs.showChoiceDialog(title, message, choices,
                choices.size() == 1 ? wholeImage : choices.get(0));
        if (chosen == null) {
            return null; // cancelled
        }
        try {
            return chosen.equals(wholeImage)
                    ? detector.detect(imageData, DomainDetectionOptions.wholeImageFallback())
                    : detector.fromAnnotation(imageData, byLabel.get(chosen));
        } catch (DomainException e) {
            Dialogs.showErrorMessage(title, e.getMessage());
            return null;
        }
    }

    /**
     * Guard for the now-non-modal wizard dialogs: confirm the active image is still the one the
     * wizard captured at the start. Pan/zoom is harmless, but switching images mid-wizard would
     * sample/place against the wrong image — this catches that. Returns {@code true} to proceed; on
     * a mismatch it reports the problem and returns {@code false} so the caller aborts cleanly.
     */
    static boolean confirmSameImage(QuPathGUI qupath, ImageData<BufferedImage> captured, String title) {
        if (qupath != null && qupath.getImageData() != captured) {
            Dialogs.showErrorMessage(title,
                    "The active image changed while the wizard was open.\n\n"
                            + "Switch back to the original image, or restart the wizard.");
            return false;
        }
        return true;
    }

    /**
     * A confirmation step that can also step <b>Back</b>. Shows the prose {@code summary} in the
     * normal UI font and the {@code domainBlock} (x/y/z bounds) as a monospaced data card so its
     * columns line up, then Back / Cancel / Proceed. Used for the plan-confirmation screen so the
     * user can return to the parameters screen instead of only aborting. Non-modal, matching the
     * other wizard dialogs. Returns {@link Nav}: next = Proceed, back = step to the previous screen,
     * cancel = abort.
     *
     * @param summary     prose lines describing the source and what will be built (UI font)
     * @param domainBlock the PhysiCell {@code x/y/z} bounds table (rendered monospaced)
     */
    static Nav<Boolean> confirmWithBack(Stage owner, String title, String summary, String domainBlock) {
        var ref = new java.util.concurrent.atomic.AtomicReference<Nav<Boolean>>(Nav.cancel());

        Stage dialog = new Stage();
        dialog.setTitle(title);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.NONE);

        Label summaryLabel = new Label(summary);
        summaryLabel.setStyle("-fx-font-size: 13;");

        // The domain bounds as a small monospaced card so the columns align and read as a table.
        Label domainHeader = new Label("PhysiCell domain — set these in your config XML");
        domainHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #2a3b4d; -fx-font-size: 11.5;");
        Label domainText = new Label(domainBlock);
        domainText.setStyle("-fx-font-family: monospace; -fx-font-size: 12.5;");
        VBox domainCard = new VBox(6, domainHeader, domainText);
        domainCard.setPadding(new Insets(10, 12, 10, 12));
        domainCard.setStyle("-fx-background-color: #f2f5f9; -fx-background-radius: 6; "
                + "-fx-border-color: #d4dbe5; -fx-border-radius: 6;");

        Label prompt = new Label("Proceed with these settings?");
        prompt.setStyle("-fx-font-size: 13; -fx-font-weight: bold;");

        Button backButton = new Button("Back");
        Button proceedButton = new Button("Proceed");
        Button cancelButton = new Button("Cancel");
        proceedButton.setDefaultButton(true);
        cancelButton.setCancelButton(true);
        proceedButton.setOnAction(e -> { ref.set(Nav.next(Boolean.TRUE)); dialog.close(); });
        backButton.setOnAction(e -> { ref.set(Nav.back()); dialog.close(); });
        cancelButton.setOnAction(e -> { ref.set(Nav.cancel()); dialog.close(); });

        HBox buttons = new HBox(10, backButton, cancelButton, proceedButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(14, summaryLabel, domainCard, prompt, buttons);
        root.setPadding(new Insets(18, 20, 18, 20));
        root.setMinWidth(440);
        dialog.setScene(new Scene(root));
        dialog.setResizable(false);
        dialog.showAndWait();
        return ref.get();
    }

    private static String labelFor(PathObject o, int idx) {
        ROI roi = o.getROI();
        String name = (o.getName() == null || o.getName().isBlank()) ? "unnamed rectangle" : o.getName();
        return String.format("%d. %s  (%.0f×%.0f px)", idx + 1, name, roi.getBoundsWidth(), roi.getBoundsHeight());
    }

    /** Sibling path for the PhysiCell {@code <domain>} XML: {@code foo.csv → foo-physicell-domain.xml}. */
    static Path domainSidecarPath(Path csvPath) {
        String name = csvPath.getFileName().toString();
        String base = name.toLowerCase().endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
        return csvPath.resolveSibling(base + "-physicell-domain.xml");
    }

    /**
     * After an export, offer to rewrite a user-chosen PhysiCell config's {@code <domain>} to match.
     * No-op if the user declines or cancels the file chooser. A bounds-only {@code domain} updates
     * only the x/y bounds; a voxel-sized one updates the full block. A {@code .bak} backup is written.
     */
    static void offerConfigUpdate(QuPathGUI qupath, String title, PhysiCellDomain domain) {
        boolean go = Dialogs.showYesNoDialog(title,
                "Update a PhysiCell config file's <domain> to match this export?\n\n"
                        + (domain.voxelSized()
                                ? "Sets x/y/z bounds and dx/dy/dz."
                                : "Sets only the x/y domain bounds (your voxel size is left unchanged)."));
        if (!go) {
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Select PhysiCell config XML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PhysiCell config (XML)", "*.xml"));
        Stage owner = qupath == null ? null : qupath.getStage();
        File file = fc.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        try {
            Path backup = PhysiCellConfigUpdater.updateDomain(file.toPath(), domain);
            Dialogs.showInfoNotification(title,
                    "Updated <domain> in " + file.getName() + " (backup: " + backup.getFileName() + ")");
            logger.info("BIWT updated <domain> in {} (backup {})", file, backup.getFileName());
        } catch (Exception e) {
            logger.error("Failed to update PhysiCell config {}", file, e);
            Dialogs.showErrorMessage(title, "Could not update the config: " + e.getMessage());
        }
    }

    /**
     * After a successful export, offer to open the in-memory results in the interactive
     * {@link ResultsViewer}. No-op when {@code model} is {@code null} or has nothing to show, or
     * when the user declines.
     */
    static void offerResultsPreview(QuPathGUI qupath, String title, ViewerModel model) {
        if (model == null || (!model.hasCells() && !model.hasSubstrates())) {
            return;
        }
        boolean go = Dialogs.showYesNoDialog(title, "Preview the results in an interactive viewer?");
        if (!go) {
            return;
        }
        ResultsViewer.show(qupath == null ? null : qupath.getStage(), title + " — results", model);
    }
}
