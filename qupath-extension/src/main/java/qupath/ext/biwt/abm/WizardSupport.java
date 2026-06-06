package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.coord.PhysiCellConfigUpdater;
import io.github.drbergmanlab.biwt.core.coord.PhysiCellDomain;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions;
import io.github.drbergmanlab.biwt.core.domain.DomainDetector;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Small GUI helpers shared by the BIWT wizards (substrate, cell, and combined). */
final class WizardSupport {

    private static final Logger logger = LoggerFactory.getLogger(WizardSupport.class);

    private WizardSupport() {}

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
        boolean go = Dialogs.showConfirmDialog(title,
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
}
