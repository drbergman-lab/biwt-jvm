package io.github.drbergmanlab.biwt.core.cells;

import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.coord.CoordinateTransform;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CellExtractorTest {

    private static final double EPS = 1e-9;

    /** Whole-image domain bounds (0,0)-(100,100) px @ 1 µm/px, with a left-half clip mask. */
    private static AbmDomain leftHalfClipDomain() {
        return new AbmDomain("test", 0, 0, 100, 100, 1.0, 1.0, new Rectangle(0, 0, 50, 100));
    }

    private static CoordinateTransform topLeft(AbmDomain d) {
        return CoordinateTransform.of(d, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
    }

    @Test
    void placesClassifiedCellsAndDropsOutOfDomainAndUnclassified() {
        ImageData<BufferedImage> data = blankImage(100, 100, 1.0);
        addDetection(data, 20, 20, 20, 20, "Tumor");   // centroid (30,30) — inside clip
        addDetection(data, 60, 60, 10, 10, "Stroma");  // centroid (65,65) — x>50, outside clip
        addDetection(data, 10, 10, 4, 4, null);        // unclassified — dropped by default

        AbmDomain domain = leftHalfClipDomain();
        List<CellRecord> cells = new CellExtractor()
                .extract(data, domain, topLeft(domain), CellPlacementOptions.defaults());

        assertEquals(1, cells.size());
        CellRecord c = cells.get(0);
        assertEquals(30.0,  c.xMicrons(), EPS);   // x = px
        assertEquals(-30.0, c.yMicrons(), EPS);   // y = -py (top-left, y-flip)
        assertEquals(0.0,   c.zMicrons(), EPS);
        assertEquals("Tumor", c.type());
        assertFalse(c.hasVolume());
    }

    @Test
    void assignsUnclassifiedDefaultWhenConfigured() {
        ImageData<BufferedImage> data = blankImage(100, 100, 1.0);
        addDetection(data, 20, 20, 20, 20, "Tumor");
        addDetection(data, 10, 10, 4, 4, null);        // centroid (12,12)

        AbmDomain domain = leftHalfClipDomain();
        List<CellRecord> cells = new CellExtractor().extract(data, domain, topLeft(domain),
                CellPlacementOptions.defaults().withUnclassifiedAs("default"));

        assertEquals(2, cells.size());
        assertTrue(cells.stream().anyMatch(c -> c.type().equals("default")
                && c.xMicrons() == 12.0 && c.yMicrons() == -12.0));
    }

    @Test
    void derivesVolumeFromAreaWhenRequested() {
        ImageData<BufferedImage> data = blankImage(100, 100, 1.0);
        addDetection(data, 20, 20, 20, 20, "Tumor"); // area 400 px² → 400 µm²

        AbmDomain domain = leftHalfClipDomain();
        List<CellRecord> cells = new CellExtractor().extract(data, domain, topLeft(domain),
                CellPlacementOptions.defaults().withVolume(true));

        assertEquals(1, cells.size());
        assertTrue(cells.get(0).hasVolume());
        // V = (4/3)π r³, r = sqrt(400/π) → ≈ 6018.0 µm³.
        assertEquals(6018.0, cells.get(0).volumeMicrons3(), 1.0);
    }

    @Test
    void appliesTypeNameOverride() {
        ImageData<BufferedImage> data = blankImage(100, 100, 1.0);
        addDetection(data, 20, 20, 20, 20, "Tumor");

        AbmDomain domain = leftHalfClipDomain();
        CellPlacementOptions opts = new CellPlacementOptions(false, null, Map.of("Tumor", "malignant"));
        List<CellRecord> cells = new CellExtractor().extract(data, domain, topLeft(domain), opts);

        assertEquals("malignant", cells.get(0).type());
    }

    @Test
    void equivalentSphereVolumeMatchesFormula() {
        assertEquals(0.0, CellExtractor.equivalentSphereVolume(0.0), EPS);
        // 4/(3√π) · A^1.5 for A = 400.
        assertEquals(6018.02, CellExtractor.equivalentSphereVolume(400.0), 0.1);
    }

    // ---- helpers ----

    private static ImageData<BufferedImage> blankImage(int w, int h, double pxMicrons) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WrappedBufferedImageServer server = new WrappedBufferedImageServer("test", img);
        server.setMetadata(new ImageServerMetadata.Builder(server.getOriginalMetadata())
                .pixelSizeMicrons(pxMicrons, pxMicrons)
                .build());
        return new ImageData<>(server);
    }

    private static void addDetection(ImageData<BufferedImage> data, int x, int y, int w, int h, String pathClass) {
        ROI roi = ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane());
        PathClass pc = pathClass == null ? null : PathClass.fromString(pathClass);
        PathObject det = PathObjects.createDetectionObject(roi, pc);
        data.getHierarchy().addObject(det);
    }
}
