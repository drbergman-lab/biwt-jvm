package qupath.ext.biwt.abm.transforms;

import io.github.drbergmanlab.biwt.core.channelmath.ChannelEnvironment;
import io.github.drbergmanlab.biwt.core.channelmath.Expression;
import qupath.lib.images.servers.ColorTransforms.ColorTransform;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * A {@link ColorTransform} that evaluates a compiled channel-math {@link Expression} per tile.
 *
 * <p>The transform is constructed by the wizard once the user's expression has compiled cleanly
 * and every identifier in it has been bound to an extractor function — see the
 * {@link Builder} for the construction pattern.
 *
 * <p>For each tile, the {@code extractChannel} call reads the referenced channels into per-pixel
 * {@code float[]} buffers, packs them into a {@link ChannelEnvironment}, then asks the expression
 * to evaluate. Identifiers not declared at construction time produce a
 * {@link NoSuchElementException} at evaluate time — the wizard validates the identifier set up
 * front so this should never fire in practice.
 */
public final class ChannelMathTransform implements ColorTransform {

    private final String displayName;
    private final Expression expression;
    private final Map<String, Function<BufferedImage, float[]>> extractors;
    private final boolean rgbRequired;

    private ChannelMathTransform(String displayName, Expression expression,
                                 Map<String, Function<BufferedImage, float[]>> extractors,
                                 boolean rgbRequired) {
        this.displayName = displayName;
        this.expression = expression;
        this.extractors = Map.copyOf(extractors);
        this.rgbRequired = rgbRequired;
    }

    @Override
    public float[] extractChannel(ImageServer<BufferedImage> server, BufferedImage img, float[] pixels) {
        int n = img.getWidth() * img.getHeight();
        if (pixels == null || pixels.length < n) {
            pixels = new float[n];
        }

        ChannelEnvironment env = new ChannelEnvironment();
        for (String id : expression.referencedIdentifiers()) {
            Function<BufferedImage, float[]> extractor = extractors.get(id);
            if (extractor == null) {
                throw new NoSuchElementException(
                        "ChannelMathTransform: no extractor registered for identifier '" + id + "'.");
            }
            env.register(id, extractor.apply(img));
        }

        expression.evaluate(env, pixels, n);
        return pixels;
    }

    @Override
    public boolean supportsImage(ImageServer<BufferedImage> server) {
        return !rgbRequired || server.isRGB();
    }

    @Override
    public String getName() {
        return displayName;
    }

    /** Construct a transform for the given expression and extractor map. */
    public static ChannelMathTransform of(String displayName,
                                          Expression expression,
                                          Map<String, Function<BufferedImage, float[]>> extractors,
                                          boolean rgbRequired) {
        return new ChannelMathTransform(displayName, expression, extractors, rgbRequired);
    }

    // ---- Pixel-extraction helpers reused by the wizard ----

    /** Read a single band into a fresh {@code float[]}. */
    public static float[] readBand(BufferedImage img, int band) {
        int w = img.getWidth();
        int h = img.getHeight();
        float[] out = new float[w * h];
        img.getRaster().getSamples(0, 0, w, h, band, out);
        return out;
    }

    /** Per-pixel optical density of one RGB 8-bit band: {@code OD = -log10((v + 1) / 255)}. */
    public static float[] readOpticalDensity(BufferedImage img, int band) {
        float[] raw = readBand(img, band);
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (float) odOf(raw[i]);
        }
        return raw;
    }

    /** Sum of OD over all three RGB bands. */
    public static float[] readOpticalDensitySum(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;
        float[] out = new float[n];
        float[] band = new float[n];
        int bands = Math.min(3, img.getRaster().getNumBands());
        for (int b = 0; b < bands; b++) {
            img.getRaster().getSamples(0, 0, w, h, b, band);
            if (b == 0) {
                for (int i = 0; i < n; i++) out[i] = (float) odOf(band[i]);
            } else {
                for (int i = 0; i < n; i++) out[i] += (float) odOf(band[i]);
            }
        }
        return out;
    }

    private static final double EPS = 1.0;
    private static final double MAX_8BIT = 255.0;
    private static final double INV_LN10 = 1.0 / Math.log(10);

    private static double odOf(double value) {
        return -Math.log((value + EPS) / MAX_8BIT) * INV_LN10;
    }
}
