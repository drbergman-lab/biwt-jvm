package io.github.drbergmanlab.biwt.core.sampling;

import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import org.junit.jupiter.api.Test;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.function.IntBinaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class SubstrateSamplerTest {

    private static final double EPS = 1e-9;

    @Test
    void uniformImageProducesUniformMean() throws Exception {
        // 100x100 image, all pixels = 42. 4x4 grid of 25-pixel windows over a (0,0)-(100,100) domain.
        ImageServer<BufferedImage> server = grayServer(100, 100, 1.0, (x, y) -> 42);
        AbmDomain domain = wholeImageDomain(server, 1.0);

        double[][] vals = new SubstrateSampler().sample(server, domain, 4, 4,
                SamplingKernel.nonOverlapping(25), 0);

        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                assertEquals(42.0, vals[j][i], EPS, "voxel (" + i + "," + j + ")");
            }
        }
    }

    @Test
    void columnGradientGivesExpectedMeanPerColumn() throws Exception {
        // 20x10 image: pixel value = x (column index). Window 5x5 → mean of x for x in [iw, iw+5).
        ImageServer<BufferedImage> server = grayServer(20, 10, 1.0, (x, y) -> x);
        AbmDomain domain = wholeImageDomain(server, 1.0);

        double[][] vals = new SubstrateSampler().sample(server, domain, 4, 2,
                SamplingKernel.nonOverlapping(5), 0);

        // Window for column i covers x in [5i, 5i+5). Mean = 5i + 2 (= (0+1+2+3+4)/5 + 5i)
        for (int j = 0; j < 2; j++) {
            assertEquals(2.0,  vals[j][0], EPS);
            assertEquals(7.0,  vals[j][1], EPS);
            assertEquals(12.0, vals[j][2], EPS);
            assertEquals(17.0, vals[j][3], EPS);
        }
    }

    @Test
    void windowStraddlingImageEdgeAveragesOnlyInImagePixels() throws Exception {
        // 10x10 image, all pixels = 100. Domain = whole image. Grid 2x2 with stepSize 7.
        // Voxel (1,0) window starts at x=7, would go to x=14, clipped to x=10 → 3 columns x 7 rows.
        ImageServer<BufferedImage> server = grayServer(10, 10, 1.0, (x, y) -> 100);
        AbmDomain domain = wholeImageDomain(server, 1.0);

        double[][] vals = new SubstrateSampler().sample(server, domain, 2, 2,
                SamplingKernel.nonOverlapping(7), 0);

        // All pixels still have value 100, so mean is 100 even when the window is clipped.
        assertEquals(100.0, vals[0][0], EPS);
        assertEquals(100.0, vals[0][1], EPS);
        assertEquals(100.0, vals[1][0], EPS);
        assertEquals(100.0, vals[1][1], EPS);
    }

    @Test
    void clipMaskRestrictsAveragingToAnnotation() throws Exception {
        // 10x10 image with value = x. Clip mask is the rectangle x in [0, 5).
        // Grid 2x1 with window 5: voxel (0,0) window x in [0,5) → mean = 2.0
        //                         voxel (1,0) window x in [5,10) → entirely outside mask → NaN.
        ImageServer<BufferedImage> server = grayServer(10, 10, 1.0, (x, y) -> x);

        // Custom domain: bounds say (0,0)-(10,10) (so grid extends across image),
        // but clipMask is the left half only.
        AbmDomain domain = new AbmDomain(
                "test-mask",
                0, 0, 10, 10,
                1.0, 1.0,
                new Rectangle(0, 0, 5, 10));

        double[][] vals = new SubstrateSampler().sample(server, domain, 2, 1,
                SamplingKernel.nonOverlapping(5), 0);

        assertEquals(2.0, vals[0][0], EPS);
        assertTrue(Double.isNaN(vals[0][1]), "expected NaN, got " + vals[0][1]);
    }

    @Test
    void partialMaskWindowAveragesOnlyInsidePixels() throws Exception {
        // 10x10 image with value = x. Clip mask covers x in [0, 7) — the right half (7..9) excluded.
        // Window 5x5 at x=[5,10) → pixels with x in {5, 6} are in mask, x in {7, 8, 9} are not.
        // Mean of x for x in {5, 6} = 5.5.
        ImageServer<BufferedImage> server = grayServer(10, 10, 1.0, (x, y) -> x);
        AbmDomain domain = new AbmDomain(
                "partial-mask",
                0, 0, 10, 10,
                1.0, 1.0,
                new Rectangle(0, 0, 7, 10));

        double[][] vals = new SubstrateSampler().sample(server, domain, 2, 1,
                SamplingKernel.nonOverlapping(5), 0);

        assertEquals(2.0, vals[0][0], EPS);
        assertEquals(5.5, vals[0][1], EPS);
    }

    @Test
    void rejectsBadChannelIndex() {
        ImageServer<BufferedImage> server = grayServer(10, 10, 1.0, (x, y) -> 0);
        AbmDomain domain = wholeImageDomain(server, 1.0);

        SubstrateSampler s = new SubstrateSampler();
        assertThrows(IllegalArgumentException.class,
                () -> s.sample(server, domain, 1, 1, SamplingKernel.nonOverlapping(10), -1));
        assertThrows(IllegalArgumentException.class,
                () -> s.sample(server, domain, 1, 1, SamplingKernel.nonOverlapping(10), 5));
    }

    // ---- helpers ----

    /** Build a grayscale ImageServer with the given pixel-fill function and µm calibration. */
    private static ImageServer<BufferedImage> grayServer(int w, int h, double pxMicrons,
                                                         IntBinaryOperator pixel) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.getRaster().setSample(x, y, 0, pixel.applyAsInt(x, y));
            }
        }
        WrappedBufferedImageServer server = new WrappedBufferedImageServer("test", img);
        server.setMetadata(new ImageServerMetadata.Builder(server.getOriginalMetadata())
                .pixelSizeMicrons(pxMicrons, pxMicrons)
                .build());
        return server;
    }

    private static AbmDomain wholeImageDomain(ImageServer<BufferedImage> server, double pxMicrons) {
        int w = server.getWidth();
        int h = server.getHeight();
        return new AbmDomain("whole image", 0, 0, w, h, pxMicrons, pxMicrons,
                new Rectangle(0, 0, w, h));
    }
}
