package qupath.ext.biwt.abm.transforms;

import qupath.lib.images.servers.ColorTransforms.ColorTransform;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * Sum of per-channel optical density for an 8-bit RGB image:
 *
 * <p>{@code OD_c = -log10((value_c + EPS) / 255)}, summed over R, G, B.
 *
 * <p>White pixels (value ≈ 255) → OD ≈ 0. Dark/strongly-stained pixels → larger OD. The small
 * {@link #EPSILON} avoids {@code log(0)} for fully-saturated black pixels.
 *
 * <p>This is the same quantity QuPath uses internally for its OD-sum pixel-classifier feature.
 * Implemented as a {@link ColorTransform} so it can be added to a
 * {@link qupath.lib.images.servers.TransformedServerBuilder#applyColorTransforms} pipeline and
 * sampled like any other channel.
 */
public final class OpticalDensitySumTransform implements ColorTransform {

    /** Small offset added inside the log to keep {@code value = 0} finite. One quantization step on 8 bits. */
    public static final double EPSILON = 1.0;

    private static final double MAX_8BIT = 255.0;
    private static final double INV_LN10 = 1.0 / Math.log(10);

    @Override
    public float[] extractChannel(ImageServer<BufferedImage> server, BufferedImage img, float[] pixels) {
        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;
        if (pixels == null || pixels.length < n) {
            pixels = new float[n];
        }

        WritableRaster raster = img.getRaster();
        int bands = Math.min(3, raster.getNumBands());

        // Zero the output for the first band; subsequent bands accumulate.
        float[] band = new float[n];
        for (int b = 0; b < bands; b++) {
            raster.getSamples(0, 0, w, h, b, band);
            if (b == 0) {
                for (int i = 0; i < n; i++) {
                    pixels[i] = (float) odOf(band[i]);
                }
            } else {
                for (int i = 0; i < n; i++) {
                    pixels[i] += (float) odOf(band[i]);
                }
            }
        }
        return pixels;
    }

    @Override
    public boolean supportsImage(ImageServer<BufferedImage> server) {
        // Restrict to RGB 8-bit because the 255 normalization and 3-channel assumption are baked in.
        return server.isRGB();
    }

    @Override
    public String getName() {
        return "Optical density sum";
    }

    private static double odOf(double value) {
        // -log10((value + EPS) / 255) = (ln(255) - ln(value + EPS)) / ln(10)
        return -Math.log((value + EPSILON) / MAX_8BIT) * INV_LN10;
    }
}
