package io.github.drbergmanlab.biwt.core;

import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BiwtSamplerTest {

    private static final double EPS = 1e-9;

    @Test
    void endToEndProducesPhysiCellCsvWithKnownValues() throws Exception {
        // 100x100 image, pixel value = x + y, 0.5 µm/px.
        // abm_domain rectangle at (10, 10) size 80x80 px → 40x40 µm.
        // Request step size 10 µm → stridePx = 20 → 4x4 grid.
        // For voxel (i, j): window in image-px is [10+20i, 10+20j] size 20x20.
        //   Mean of (x+y) = (mean x) + (mean y) = (19.5 + 20i) + (19.5 + 20j) = 39 + 20(i+j)
        ImageData<BufferedImage> data = makeImageData(100, 100, 0.5, (x, y) -> x + y);
        ROI roi = ROIs.createRectangleROI(10, 10, 80, 80, ImagePlane.getDefaultPlane());
        PathObject ann = PathObjects.createAnnotationObject(roi);
        ann.setName("abm_domain");
        data.getHierarchy().addObject(ann);

        SamplingRequest req = new SamplingRequest(
                data,
                DomainDetectionOptions.defaults(),
                10.0,
                CoordinateOrigin.IMAGE_TOP_LEFT,
                List.of(new SubstrateSpec("intensity", 0))
        );
        SamplingResult result = BiwtSampler.create().run(req);

        // Effective step size matches the request exactly here: 20 px * 0.5 µm/px = 10 µm.
        assertEquals(10.0, result.requestedStepMicrons(), EPS);
        assertEquals(10.0, result.effectiveStepMicrons(), EPS);
        assertEquals(4, result.grid().nx());
        assertEquals(4, result.grid().ny());

        double[][] vals = result.substrates().get(0).values();
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                assertEquals(39.0 + 20.0 * (i + j), vals[j][i], EPS,
                        "voxel (" + i + ", " + j + ")");
            }
        }
    }

    @Test
    void reconcilesNonDivisibleStepToEffectiveValue() throws Exception {
        // 100x100 image at 0.3 µm/px. Request 7 µm → stridePx = round(7 / 0.3) = round(23.33) = 23.
        // Effective step = 23 * 0.3 = 6.9 µm.
        ImageData<BufferedImage> data = makeImageData(100, 100, 0.3, (x, y) -> 50);
        ROI roi = ROIs.createRectangleROI(0, 0, 100, 100, ImagePlane.getDefaultPlane());
        PathObject ann = PathObjects.createAnnotationObject(roi);
        ann.setName("abm_domain");
        data.getHierarchy().addObject(ann);

        SamplingRequest req = new SamplingRequest(
                data,
                DomainDetectionOptions.defaults(),
                7.0,
                CoordinateOrigin.IMAGE_TOP_LEFT,
                List.of(new SubstrateSpec("uniform", 0))
        );
        SamplingResult result = BiwtSampler.create().run(req);

        assertEquals(7.0, result.requestedStepMicrons(), EPS);
        assertEquals(6.9, result.effectiveStepMicrons(), 1e-12);
        // Uniform image → uniform value.
        for (double[] row : result.substrates().get(0).values()) {
            for (double v : row) assertEquals(50.0, v, EPS);
        }
    }

    @Test
    void writesPhysiCellCsvRoundTrip(@TempDir Path tmp) throws Exception {
        // 20x20 image, all = 7, 1 µm/px. Annotation = whole image, step 10 µm → 2x2 grid.
        ImageData<BufferedImage> data = makeImageData(20, 20, 1.0, (x, y) -> 7);

        SamplingRequest req = new SamplingRequest(
                data,
                DomainDetectionOptions.wholeImageFallback(),
                10.0,
                CoordinateOrigin.IMAGE_TOP_LEFT,
                List.of(new SubstrateSpec("oxygen", 0))
        );
        SamplingResult result = BiwtSampler.create().run(req);
        Path out = tmp.resolve("substrates.csv");
        result.writeCsv(out);

        List<String> lines = Files.readAllLines(out);
        assertEquals("x,y,z,oxygen", lines.get(0));
        // 2x2 grid: centers (5,5), (15,5), (5,15), (15,15)
        assertEquals(5, lines.size());
        assertTrue(lines.get(1).startsWith("5,5,0,7"));
        assertTrue(lines.get(4).startsWith("15,15,0,7"));
    }

    @Test
    void rejectsNonSquarePixels() {
        // 50x50 image with anisotropic calibration 0.5x1.0 µm/px.
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_GRAY);
        WrappedBufferedImageServer server = new WrappedBufferedImageServer("test", img);
        server.setMetadata(new ImageServerMetadata.Builder(server.getOriginalMetadata())
                .pixelSizeMicrons(0.5, 1.0)
                .build());
        ImageData<BufferedImage> data = new ImageData<>(server);

        SamplingRequest req = new SamplingRequest(
                data,
                DomainDetectionOptions.wholeImageFallback(),
                10.0,
                CoordinateOrigin.IMAGE_TOP_LEFT,
                List.of(new SubstrateSpec("x", 0))
        );
        assertThrows(RuntimeException.class, () -> BiwtSampler.create().run(req));
    }

    // ---- helpers ----

    @FunctionalInterface
    interface PixelFn {
        int sample(int x, int y);
    }

    private static ImageData<BufferedImage> makeImageData(int w, int h, double pxMicrons, PixelFn pixel) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.getRaster().setSample(x, y, 0, pixel.sample(x, y));
            }
        }
        WrappedBufferedImageServer server = new WrappedBufferedImageServer("test", img);
        server.setMetadata(new ImageServerMetadata.Builder(server.getOriginalMetadata())
                .pixelSizeMicrons(pxMicrons, pxMicrons)
                .build());
        return new ImageData<>(server);
    }
}
