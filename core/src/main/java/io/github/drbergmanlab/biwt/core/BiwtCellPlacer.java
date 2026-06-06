package io.github.drbergmanlab.biwt.core;

import io.github.drbergmanlab.biwt.core.cells.CellExtractor;
import io.github.drbergmanlab.biwt.core.cells.CellPlacementOptions;
import io.github.drbergmanlab.biwt.core.cells.CellRecord;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.coord.CoordinateTransform;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions;
import io.github.drbergmanlab.biwt.core.domain.DomainDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Top-level entry point for cell initial-condition export — the sibling of {@link BiwtSampler} for
 * the discrete (cell) side of a PhysiCell model.
 *
 * <p>BIWT is the linker: cell segmentation and classification are done by existing QuPath tools
 * (StarDist, Cellpose, InstanSeg, or built-in cell detection). This reads the resulting detection
 * objects, places each centroid in the ABM frame (the same frame the substrate voxel grid uses for
 * the same domain + origin), reads the QuPath classification as the cell type, and writes a
 * PhysiCell cell-IC CSV.
 *
 * <p>Headless example (Groovy):
 * <pre>{@code
 * def result = BiwtCellPlacer.create().run(
 *     getCurrentImageData(),
 *     DomainDetectionOptions.wholeImageFallback(),
 *     CoordinateOrigin.ABM_DOMAIN_CENTER,
 *     CellPlacementOptions.defaults().withVolume(true))
 * result.writeCsv(java.nio.file.Path.of("/tmp/cells.csv"))
 * }</pre>
 *
 * <p>Unlike substrate sampling, cell placement is continuous (no voxel snapping) and so does not
 * require square pixels — the per-axis calibration is honored by the transform and the volume
 * estimate.
 */
public final class BiwtCellPlacer {

    private static final Logger logger = LoggerFactory.getLogger(BiwtCellPlacer.class);

    private final DomainDetector detector;
    private final CellExtractor extractor;

    private BiwtCellPlacer(DomainDetector detector, CellExtractor extractor) {
        this.detector = detector;
        this.extractor = extractor;
    }

    public static BiwtCellPlacer create() {
        return new BiwtCellPlacer(new DomainDetector(), new CellExtractor());
    }

    /**
     * Detect the domain, build the shared coordinate transform, and place every classified detection
     * whose centroid falls inside the domain.
     *
     * @param imageData     the image (its hierarchy supplies the detections)
     * @param domainOptions how to find the ABM domain (same as substrate sampling)
     * @param origin        where (0, 0) sits — match the substrate run so cells and substrates align
     * @param options       classification / volume options
     */
    public CellPlacementResult run(ImageData<BufferedImage> imageData,
                                   DomainDetectionOptions domainOptions,
                                   CoordinateOrigin origin,
                                   CellPlacementOptions options) {
        return place(imageData, detector.detect(imageData, domainOptions), origin, options);
    }

    /**
     * Place cells against an already-resolved domain — used by the GUI after the user has picked
     * which annotation defines the ABM domain.
     */
    public CellPlacementResult place(ImageData<BufferedImage> imageData,
                                     AbmDomain domain,
                                     CoordinateOrigin origin,
                                     CellPlacementOptions options) {
        CoordinateTransform transform = CoordinateTransform.of(domain, origin);
        List<CellRecord> cells = extractor.extract(imageData, domain, transform, options);
        logger.info("Placed {} cell(s) in domain '{}'", cells.size(), domain.sourceDescription());
        return new CellPlacementResult(domain, origin, cells, options);
    }
}
