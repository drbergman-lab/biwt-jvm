package io.github.drbergmanlab.biwt.core.viz;

/**
 * Min/max reductions over substrate fields, ignoring {@code NaN} (clipped voxels). Used to autorange
 * the substrate colormap when the user leaves a {@code cmin}/{@code cmax} box empty.
 *
 * <p>Pure math — no JavaFX, headless-testable.
 */
public final class DataRange {

    private DataRange() {}

    /**
     * The {@code {min, max}} of a 2D field, skipping {@code NaN} entries.
     *
     * @param values a non-{@code null} (possibly ragged) 2D array; {@code NaN} entries are skipped
     * @return a two-element array {@code {min, max}}; both {@code NaN} if every entry is {@code NaN}
     *         (or the array is empty)
     */
    public static double[] minMaxIgnoringNaN(double[][] values) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean any = false;
        if (values != null) {
            for (double[] row : values) {
                if (row == null) continue;
                for (double v : row) {
                    if (Double.isNaN(v)) continue;
                    if (v < min) min = v;
                    if (v > max) max = v;
                    any = true;
                }
            }
        }
        return any ? new double[] {min, max} : new double[] {Double.NaN, Double.NaN};
    }
}
