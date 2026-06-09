package io.github.drbergmanlab.biwt.core.viz;

/**
 * Geometry helpers for drawing a placed cell as a disk in the results visualizer.
 *
 * <p>A {@link io.github.drbergmanlab.biwt.core.cells.CellRecord} carries a {@code volume} (µm³) that
 * may be {@link Double#NaN} when the user didn't request the volume column. The viewer draws each
 * cell as its great-circle cross-section, so it needs the equivalent-sphere radius for that volume,
 * falling back to PhysiCell's default cell volume when none is available.
 *
 * <p>Pure math — no JavaFX, headless-testable.
 */
public final class CellGeometry {

    /**
     * PhysiCell's default cell volume (µm³). A cell with no segmented volume is drawn at this size,
     * which corresponds to an equivalent-sphere radius of ≈ 8.412 µm.
     */
    public static final double DEFAULT_CELL_VOLUME_MICRONS3 = 2494.0;

    private CellGeometry() {}

    /**
     * Equivalent-sphere radius (µm) of a cell of the given volume: {@code r = cbrt(3V / 4π)}.
     *
     * @param volumeMicrons3 cell volume in µm³ (must be finite and {@code > 0})
     * @return the radius in µm
     * @throws IllegalArgumentException if {@code volumeMicrons3} is not a positive finite number
     */
    public static double radiusMicronsForVolume(double volumeMicrons3) {
        if (!(volumeMicrons3 > 0) || Double.isInfinite(volumeMicrons3)) {
            throw new IllegalArgumentException(
                    "volume must be a positive finite number (got " + volumeMicrons3 + ")");
        }
        return Math.cbrt(3.0 * volumeMicrons3 / (4.0 * Math.PI));
    }

    /**
     * The volume to draw a cell at: its own volume, or {@link #DEFAULT_CELL_VOLUME_MICRONS3} when the
     * cell carries no volume ({@code NaN}).
     *
     * @param volumeMicrons3 the cell's stored volume (may be {@code NaN})
     * @return a positive volume suitable for {@link #radiusMicronsForVolume(double)}
     */
    public static double resolveVolume(double volumeMicrons3) {
        return Double.isNaN(volumeMicrons3) ? DEFAULT_CELL_VOLUME_MICRONS3 : volumeMicrons3;
    }

    /**
     * Convenience: the radius (µm) to draw a cell at, resolving a missing volume to the default.
     *
     * @param volumeMicrons3 the cell's stored volume (may be {@code NaN})
     * @return the radius in µm
     */
    public static double radiusMicronsForCell(double volumeMicrons3) {
        return radiusMicronsForVolume(resolveVolume(volumeMicrons3));
    }
}
