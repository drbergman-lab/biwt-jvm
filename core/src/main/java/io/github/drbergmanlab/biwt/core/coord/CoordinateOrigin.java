package io.github.drbergmanlab.biwt.core.coord;

/**
 * Where the math (0, 0) point lands relative to the ABM domain (the voxel grid).
 *
 * <p>For both options the origin is anchored to the <em>voxel grid</em>, not the image —
 * when an annotation is the domain, this means the origin tracks the annotation, not where it
 * happens to sit on the slide.
 */
public enum CoordinateOrigin {
    /** ABM (0, 0) at the center of the voxel grid → symmetric coords {@code [-W/2, +W/2] × [-H/2, +H/2]}. */
    ABM_DOMAIN_CENTER,
    /** ABM (0, 0) at the top-left corner of the voxel grid → image extends into the fourth quadrant. */
    ABM_DOMAIN_TOP_LEFT
}
