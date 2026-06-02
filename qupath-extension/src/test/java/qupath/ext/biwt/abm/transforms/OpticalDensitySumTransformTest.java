package qupath.ext.biwt.abm.transforms;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class OpticalDensitySumTransformTest {

    private static final float EPS = 1e-4f;
    private static final double MAX_8BIT = 255.0;

    /** Reference implementation of one channel's OD, matching the production formula. */
    private static double od(int v) {
        return -Math.log10((v + OpticalDensitySumTransform.EPSILON) / MAX_8BIT);
    }

    /** Build a 1x1 RGB image with the given channel values. */
    private static BufferedImage onePixel(int r, int g, int b) {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, (r << 16) | (g << 8) | b);
        return img;
    }

    @Test
    void whitePixelHasNearZeroOd() {
        // value 255 → (255+1)/255 ≈ 1.004 → -log10 ≈ -0.0017 per channel; sum ≈ -0.0051.
        BufferedImage img = onePixel(255, 255, 255);
        float[] out = new OpticalDensitySumTransform().extractChannel(null, img, null);
        assertEquals(3 * od(255), out[0], EPS);
        assertTrue(Math.abs(out[0]) < 0.01f, "white should be near zero OD, got " + out[0]);
    }

    @Test
    void blackPixelHasMaxOd() {
        // value 0 → (0+1)/255 → -log10(1/255) ≈ 2.4065 per channel; sum ≈ 7.2196.
        BufferedImage img = onePixel(0, 0, 0);
        float[] out = new OpticalDensitySumTransform().extractChannel(null, img, null);
        assertEquals(3 * od(0), out[0], EPS);
        assertEquals(7.2196f, out[0], 1e-3f);
    }

    @Test
    void sumIsPerChannelOdSummed() {
        // Distinct R/G/B values → OD(R) + OD(G) + OD(B).
        BufferedImage img = onePixel(10, 128, 200);
        float[] out = new OpticalDensitySumTransform().extractChannel(null, img, null);
        double expected = od(10) + od(128) + od(200);
        assertEquals((float) expected, out[0], EPS);
    }

    @Test
    void multiPixelImageIsRowMajor() {
        // 2x1 image: left pixel dark, right pixel white. Output index 0 = left, 1 = right.
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0);                       // black
        img.setRGB(1, 0, (255 << 16) | (255 << 8) | 255); // white
        float[] out = new OpticalDensitySumTransform().extractChannel(null, img, null);
        assertEquals(2, out.length);
        assertEquals(3 * od(0), out[0], EPS);
        assertEquals(3 * od(255), out[1], EPS);
        assertTrue(out[0] > out[1], "darker pixel should have higher OD");
    }

    @Test
    void reusesProvidedBufferWhenLargeEnough() {
        BufferedImage img = onePixel(50, 50, 50);
        float[] buffer = new float[1];
        float[] out = new OpticalDensitySumTransform().extractChannel(null, img, buffer);
        assertSame(buffer, out, "should reuse the provided buffer in place");
    }

    @Test
    void nameIsStable() {
        assertEquals("Optical density sum", new OpticalDensitySumTransform().getName());
    }
}
