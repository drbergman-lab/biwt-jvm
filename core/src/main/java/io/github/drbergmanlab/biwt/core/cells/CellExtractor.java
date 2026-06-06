package io.github.drbergmanlab.biwt.core.cells;

import io.github.drbergmanlab.biwt.core.coord.CoordinateTransform;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.interfaces.ROI;

import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a QuPath detection hierarchy into a list of {@link CellRecord}s in the ABM frame.
 *
 * <p>BIWT does not segment or classify — it links. This reads whatever detection objects already
 * exist (from StarDist, Cellpose, InstanSeg, or QuPath's built-in cell detection), takes each
 * centroid, maps it through the shared {@link CoordinateTransform} (so cells land in the same frame
 * as the substrate voxels), and reads the QuPath classification as the PhysiCell cell type.
 *
 * <p>Rules:
 * <ul>
 *   <li>Cells whose centroid falls outside the domain's clip mask are dropped (consistent with the
 *       substrate clip-to-annotation rule).</li>
 *   <li>Unclassified detections are dropped, or assigned a default type, per
 *       {@link CellPlacementOptions#unclassifiedTypeName()}.</li>
 *   <li>When requested, {@code volume} is the equivalent-sphere volume of the segmented area:
 *       {@code area → r = sqrt(area/π) → V = (4/3)π r³}.</li>
 * </ul>
 *
 * <p>Headless — uses only {@code qupath.lib.*} (no GUI).
 */
public final class CellExtractor {

    /**
     * Extract placed cells from the image's detection hierarchy.
     *
     * @param imageData the image (its hierarchy supplies the detections)
     * @param domain    the ABM domain (supplies the clip mask and pixel calibration)
     * @param transform the shared pixel→µm transform (same frame as the voxel grid)
     * @param options   classification / volume options
     */
    public List<CellRecord> extract(ImageData<BufferedImage> imageData,
                                    AbmDomain domain,
                                    CoordinateTransform transform,
                                    CellPlacementOptions options) {
        Objects.requireNonNull(imageData, "imageData");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(options, "options");

        Shape clip = domain.clipMaskPx();
        double pxArea = domain.pixelWidthMicrons() * domain.pixelHeightMicrons();

        List<CellRecord> cells = new ArrayList<>();
        for (PathObject det : imageData.getHierarchy().getDetectionObjects()) {
            ROI roi = det.getROI();
            if (roi == null) {
                continue;
            }
            double cx = roi.getCentroidX();
            double cy = roi.getCentroidY();
            if (!clip.contains(cx, cy)) {
                continue;
            }

            String type = resolveType(det.getPathClass(), options);
            if (type == null) {
                continue; // unclassified, and the caller asked to drop them
            }

            double volume = Double.NaN;
            if (options.includeVolume()) {
                double areaMicrons2 = roi.getArea() * pxArea;
                volume = equivalentSphereVolume(areaMicrons2);
            }

            cells.add(new CellRecord(
                    transform.xMicrons(cx),
                    transform.yMicrons(cy),
                    0.0,
                    type,
                    volume));
        }
        return cells;
    }

    /** PhysiCell type name for a detection's class, or {@code null} to drop it. */
    private static String resolveType(PathClass pathClass, CellPlacementOptions options) {
        if (pathClass == null || pathClass.getName() == null || pathClass.getName().isBlank()) {
            return options.unclassifiedTypeName(); // null → drop
        }
        return options.resolveType(pathClass.getName());
    }

    /** Volume (µm³) of a sphere whose great-circle area equals the segmented 2D area. */
    static double equivalentSphereVolume(double areaMicrons2) {
        if (!(areaMicrons2 > 0)) {
            return 0.0;
        }
        double radius = Math.sqrt(areaMicrons2 / Math.PI);
        return (4.0 / 3.0) * Math.PI * radius * radius * radius;
    }
}
