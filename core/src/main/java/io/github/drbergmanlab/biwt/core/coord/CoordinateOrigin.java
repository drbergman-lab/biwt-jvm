package io.github.drbergmanlab.biwt.core.coord;

/**
 * Where the math (0, 0) point lands relative to the ABM domain. The origin is anchored to the
 * <em>annotation</em> (its size), never to where it sits on the slide — so the same annotation
 * always yields the same coordinates.
 *
 * <p>The origin's only job is to fix the numeric anchor {@code (xMin, yMin)} = the domain's
 * bottom-left corner; the voxel-grid discretization and the cell pixel→µm transform both read it
 * from {@link #minCornerMicrons(double, double)}, so they share one frame.
 */
public enum CoordinateOrigin {
    /**
     * The annotation is centered on (0, 0): {@code xMin = -annW/2}, {@code yMin = -annH/2}. When the
     * voxel step divides the annotation the domain is symmetric; otherwise the one-sided overhang
     * makes the domain <em>bounds</em> mildly asymmetric while the annotation stays centered.
     */
    ABM_DOMAIN_CENTER,
    /**
     * The annotation's top-left corner is (0, 0): {@code xMin = 0}, {@code yMin = -annH}. The domain
     * extends right and up (into the overhang).
     */
    ABM_DOMAIN_TOP_LEFT;

    /**
     * The domain's bottom-left corner in µm — the anchor both the voxel grid and the cell transform
     * discretize from. PhysiCell builds its mesh up from this corner.
     *
     * @param annotationWidthMicrons  annotation width in µm
     * @param annotationHeightMicrons annotation height in µm
     * @return {@code {xMin, yMin}}
     */
    public double[] minCornerMicrons(double annotationWidthMicrons, double annotationHeightMicrons) {
        return switch (this) {
            case ABM_DOMAIN_TOP_LEFT -> new double[] {0.0, -annotationHeightMicrons};
            case ABM_DOMAIN_CENTER -> new double[] {-annotationWidthMicrons / 2.0, -annotationHeightMicrons / 2.0};
        };
    }
}
