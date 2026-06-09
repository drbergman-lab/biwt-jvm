package io.github.drbergmanlab.biwt.core.viz;

/**
 * Immutable mapping from ABM world coordinates (µm) to screen pixels for the results visualizer.
 *
 * <p>Two invariants make the tissue render correctly:
 * <ul>
 *   <li><b>Equal x/y scale (letterbox).</b> One scale factor is used for both axes
 *       ({@code min} of the per-axis fits), so disks stay circular and aspect ratio is true. The
 *       world is centered in the screen rectangle, leaving symmetric margins on the over-long axis.</li>
 *   <li><b>Y-flip.</b> PhysiCell math {@code +y} is up, but screen rows increase downward. The
 *       largest world-y maps to the smallest screen-y (top of the plot), so tissue shows
 *       right-side-up.</li>
 * </ul>
 *
 * <p>Pure math — no JavaFX, headless-testable. Drawing code calls {@link #screenX(double)} /
 * {@link #screenY(double)} for points and {@link #screenLen(double)} for radii; the inverse methods
 * support hit-testing.
 */
public final class WorldToScreen {

    private final double worldXMin;
    private final double worldYMax;
    private final double screenX0;
    private final double screenY0;
    private final double scale;
    private final double offsetX;
    private final double offsetY;

    /**
     * Build a letterboxed, y-flipped transform from a world rectangle onto a screen rectangle.
     *
     * @param worldXMin     smallest world x (µm)
     * @param worldXMax     largest world x (µm); must exceed {@code worldXMin}
     * @param worldYMin     smallest world y (µm)
     * @param worldYMax     largest world y (µm); must exceed {@code worldYMin}
     * @param screenX       left edge of the screen rectangle (px)
     * @param screenY       top edge of the screen rectangle (px)
     * @param screenWidth   screen rectangle width (px); must be {@code > 0}
     * @param screenHeight  screen rectangle height (px); must be {@code > 0}
     */
    public WorldToScreen(double worldXMin, double worldXMax,
                         double worldYMin, double worldYMax,
                         double screenX, double screenY,
                         double screenWidth, double screenHeight) {
        double worldWidth = worldXMax - worldXMin;
        double worldHeight = worldYMax - worldYMin;
        if (!(worldWidth > 0) || !(worldHeight > 0)) {
            throw new IllegalArgumentException("world rectangle must have positive extent (got "
                    + worldWidth + " x " + worldHeight + ")");
        }
        if (!(screenWidth > 0) || !(screenHeight > 0)) {
            throw new IllegalArgumentException("screen rectangle must have positive extent (got "
                    + screenWidth + " x " + screenHeight + ")");
        }
        this.worldXMin = worldXMin;
        this.worldYMax = worldYMax;
        this.screenX0 = screenX;
        this.screenY0 = screenY;
        this.scale = Math.min(screenWidth / worldWidth, screenHeight / worldHeight);
        this.offsetX = (screenWidth - worldWidth * scale) / 2.0;
        this.offsetY = (screenHeight - worldHeight * scale) / 2.0;
    }

    /** Screen x (px) for a world x (µm). */
    public double screenX(double worldX) {
        return screenX0 + offsetX + (worldX - worldXMin) * scale;
    }

    /** Screen y (px) for a world y (µm) — y-flipped (largest world y → top of the plot). */
    public double screenY(double worldY) {
        return screenY0 + offsetY + (worldYMax - worldY) * scale;
    }

    /** A length in µm scaled to screen pixels (for circle radii). */
    public double screenLen(double worldLength) {
        return worldLength * scale;
    }

    /** Inverse of {@link #screenX(double)}: world x (µm) for a screen x (px). */
    public double worldX(double screenX) {
        return worldXMin + (screenX - screenX0 - offsetX) / scale;
    }

    /** Inverse of {@link #screenY(double)}: world y (µm) for a screen y (px). */
    public double worldY(double screenY) {
        return worldYMax - (screenY - screenY0 - offsetY) / scale;
    }

    /** The single px-per-µm scale factor applied to both axes. */
    public double scale() {
        return scale;
    }
}
