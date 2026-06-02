package qupath.ext.biwt.abm.transforms;

import io.github.drbergmanlab.biwt.core.channelmath.Expression;
import io.github.drbergmanlab.biwt.core.channelmath.ExpressionParser;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ChannelMathTransformTest {

    private static final float EPS = 1e-4f;

    /** RGB image of size w×h with per-pixel channel values from the supplied functions. */
    private static BufferedImage rgb(int w, int h, ChannelFn r, ChannelFn g, ChannelFn b) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, (r.at(x, y) << 16) | (g.at(x, y) << 8) | b.at(x, y));
            }
        }
        return img;
    }

    @FunctionalInterface
    interface ChannelFn { int at(int x, int y); }

    private static Map<String, Function<BufferedImage, float[]>> rgbExtractors() {
        return Map.of(
                "R", img -> ChannelMathTransform.readBand(img, 0),
                "G", img -> ChannelMathTransform.readBand(img, 1),
                "B", img -> ChannelMathTransform.readBand(img, 2));
    }

    @Test
    void evaluatesLinearCombinationPerPixel() {
        // 2x1 image: pixel 0 = (10, 20, 30), pixel 1 = (40, 50, 60).
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, (10 << 16) | (20 << 8) | 30);
        img.setRGB(1, 0, (40 << 16) | (50 << 8) | 60);

        Expression e = ExpressionParser.parse("R + 2*G - B");
        ChannelMathTransform t = ChannelMathTransform.of("test", e, rgbExtractors(), true);
        float[] out = t.extractChannel(null, img, null);

        assertEquals(10 + 2 * 20 - 30, out[0], EPS);   // 20
        assertEquals(40 + 2 * 50 - 60, out[1], EPS);   // 80
    }

    @Test
    void onlyExtractsReferencedChannels() {
        // Expression references only R. Provide a G extractor that would throw if invoked.
        BufferedImage img = rgb(1, 1, (x, y) -> 77, (x, y) -> 0, (x, y) -> 0);
        Map<String, Function<BufferedImage, float[]>> extractors = Map.of(
                "R", i -> ChannelMathTransform.readBand(i, 0),
                "G", i -> { throw new AssertionError("G should not be extracted"); });

        Expression e = ExpressionParser.parse("R * 2");
        float[] out = ChannelMathTransform.of("test", e, extractors, true).extractChannel(null, img, null);
        assertEquals(154f, out[0], EPS);
    }

    @Test
    void missingExtractorThrows() {
        BufferedImage img = rgb(1, 1, (x, y) -> 1, (x, y) -> 1, (x, y) -> 1);
        Expression e = ExpressionParser.parse("Q + 1");  // Q has no extractor
        ChannelMathTransform t = ChannelMathTransform.of("test", e, rgbExtractors(), true);
        assertThrows(NoSuchElementException.class, () -> t.extractChannel(null, img, null));
    }

    @Test
    void readBandReturnsRawChannel() {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, (11 << 16) | (22 << 8) | 33);
        img.setRGB(1, 0, (44 << 16) | (55 << 8) | 66);
        assertArrayEquals(new float[] {11, 44}, ChannelMathTransform.readBand(img, 0), EPS);
        assertArrayEquals(new float[] {22, 55}, ChannelMathTransform.readBand(img, 1), EPS);
        assertArrayEquals(new float[] {33, 66}, ChannelMathTransform.readBand(img, 2), EPS);
    }

    @Test
    void odSumHelperMatchesStandaloneTransform() {
        // The OD_sum extractor used by expressions must agree with OpticalDensitySumTransform.
        BufferedImage img = rgb(3, 2, (x, y) -> (x * 40) % 256, (x, y) -> 128, (x, y) -> (y * 90) % 256);
        float[] viaHelper = ChannelMathTransform.readOpticalDensitySum(img);
        float[] viaTransform = new OpticalDensitySumTransform().extractChannel(null, img, null);
        assertArrayEquals(viaTransform, viaHelper, EPS);
    }

    @Test
    void odSumIsUsableAsAnExpressionIdentifier() {
        BufferedImage img = rgb(1, 1, (x, y) -> 0, (x, y) -> 0, (x, y) -> 0);  // black → max OD
        Map<String, Function<BufferedImage, float[]>> extractors = Map.of(
                "OD_sum", ChannelMathTransform::readOpticalDensitySum);
        Expression e = ExpressionParser.parse("OD_sum * 2");
        float[] out = ChannelMathTransform.of("test", e, extractors, true).extractChannel(null, img, null);
        float odSum = ChannelMathTransform.readOpticalDensitySum(img)[0];
        assertEquals(odSum * 2, out[0], EPS);
    }

    @Test
    void readOpticalDensityMatchesPerChannelFormula() {
        BufferedImage img = rgb(1, 1, (x, y) -> 64, (x, y) -> 0, (x, y) -> 0);
        float expected = (float) (-Math.log10((64 + OpticalDensitySumTransform.EPSILON) / 255.0));
        assertEquals(expected, ChannelMathTransform.readOpticalDensity(img, 0)[0], EPS);
    }
}
