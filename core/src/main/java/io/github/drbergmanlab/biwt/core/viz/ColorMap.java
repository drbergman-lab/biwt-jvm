package io.github.drbergmanlab.biwt.core.viz;

/**
 * Maps a scalar value to a packed ARGB color across a {@code [min, max]} range, using a
 * perceptually-uniform viridis-like gradient by default.
 *
 * <p>The API is deliberately plain (a packed {@code int}) so it stays in {@code :core} with no
 * JavaFX dependency; the extension bridges the {@code int} to a {@code javafx.scene.paint.Color}.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code NaN} value → {@code 0} (fully-transparent ARGB) so the caller can treat clipped
 *       voxels as "no data".</li>
 *   <li>{@code value <= min} clamps to the low color; {@code value >= max} clamps to the high
 *       color.</li>
 *   <li>Degenerate range ({@code min == max}, or a non-finite bound) → the gradient midpoint, so a
 *       single-valued field still renders without dividing by zero.</li>
 * </ul>
 */
public final class ColorMap {

    /** Stop positions in {@code [0, 1]}, ascending. */
    private static final double[] STOPS = {
            0.0, 0.13, 0.25, 0.38, 0.50, 0.63, 0.75, 0.88, 1.0
    };
    /** Viridis-like RGB anchors aligned with {@link #STOPS}. */
    private static final int[][] COLORS = {
            {68, 1, 84}, {71, 44, 122}, {59, 81, 139}, {44, 113, 142},
            {33, 144, 141}, {39, 173, 129}, {92, 200, 99}, {170, 220, 50}, {253, 231, 37}
    };

    /** The default viridis-like colormap. */
    public static final ColorMap VIRIDIS = new ColorMap();

    private ColorMap() {}

    /**
     * Packed ARGB color for {@code value} across {@code [min, max]}.
     *
     * @param value the scalar value ({@code NaN} → transparent)
     * @param min   the low end of the color range
     * @param max   the high end of the color range
     * @return a packed {@code 0xAARRGGBB} int; {@code 0} (transparent) when {@code value} is
     *         {@code NaN}
     */
    public int argb(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return 0; // transparent — caller treats as "no data"
        }
        double t;
        if (!(max > min) || Double.isNaN(min) || Double.isNaN(max)
                || Double.isInfinite(min) || Double.isInfinite(max)) {
            t = 0.5; // degenerate range → midpoint
        } else if (value <= min) {
            t = 0.0;
        } else if (value >= max) {
            t = 1.0;
        } else {
            t = (value - min) / (max - min);
        }
        return colorAt(t);
    }

    /**
     * Packed (opaque) ARGB at fraction {@code t ∈ [0, 1]} along the gradient, clamped to the ends.
     * Used to paint the colorbar independently of any data range.
     *
     * @param t position along the gradient ({@code 0} = low color, {@code 1} = high color)
     * @return a packed {@code 0xFFRRGGBB} int
     */
    public int colorAt(double t) {
        if (t <= 0) return pack(COLORS[0]);
        if (t >= 1) return pack(COLORS[COLORS.length - 1]);
        for (int s = 1; s < STOPS.length; s++) {
            if (t <= STOPS[s]) {
                double span = STOPS[s] - STOPS[s - 1];
                double f = span <= 0 ? 0 : (t - STOPS[s - 1]) / span;
                int r = lerp(COLORS[s - 1][0], COLORS[s][0], f);
                int g = lerp(COLORS[s - 1][1], COLORS[s][1], f);
                int b = lerp(COLORS[s - 1][2], COLORS[s][2], f);
                return pack(r, g, b);
            }
        }
        return pack(COLORS[COLORS.length - 1]);
    }

    private static int lerp(int a, int b, double f) {
        return (int) Math.round(a + (b - a) * f);
    }

    private static int pack(int[] rgb) {
        return pack(rgb[0], rgb[1], rgb[2]);
    }

    private static int pack(int r, int g, int b) {
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
