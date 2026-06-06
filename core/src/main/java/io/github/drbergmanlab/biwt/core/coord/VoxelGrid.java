package io.github.drbergmanlab.biwt.core.coord;

/**
 * Regular 2D voxel grid covering an ABM domain, indexed exactly like a PhysiCell
 * {@code Cartesian_Mesh}: voxel {@code (i, k)} is anchored at the domain's bottom-left corner
 * {@code (xMinMicrons, yMinMicrons)} and its center is
 * <pre>
 *   xCenter(i) = xMinMicrons + (i + 0.5) * dxMicrons     i = 0 … nx-1  (left → right)
 *   yCenter(k) = yMinMicrons + (k + 0.5) * dyMicrons     k = 0 … ny-1  (bottom → top)
 * </pre>
 * PhysiCell builds its mesh the same way — start at {@code (x_min, y_min)}, step half a voxel in,
 * then stride a full voxel until {@code (x_max, y_max)}. So index {@code k} increases with the
 * PhysiCell math y (up), and {@code k = 0} is the bottom row.
 *
 * <p><b>Where the y-flip lives.</b> Image-pixel rows go top→bottom while PhysiCell math +y is up.
 * That flip is handled by the <em>sampler</em>, which anchors its pixel windows at the annotation's
 * <em>bottom</em> edge so that voxel {@code k = 0} (smallest math y) aggregates the image's bottom
 * rows. The grid itself carries no flip — it is a plain monotonic mesh.
 *
 * <p><b>Sizing and overhang.</b> The grid is the smallest integer multiple of the step covering the
 * annotation: {@code nx = ceil(annW/step)}, {@code ny = ceil(annH/step)}. The leftover (when the
 * step does not divide the annotation) lands as partial voxels at the <em>top and right</em> edges —
 * the far corners from the {@code (x_min, y_min)} anchor. The sampler clips those windows to the
 * annotation; they still count as full-size voxels in the PhysiCell domain.
 *
 * <p><b>Origin.</b> The discretization algorithm is origin-agnostic; {@link CoordinateOrigin} only
 * sets the numeric anchor {@code (xMinMicrons, yMinMicrons)}:
 * <ul>
 *   <li>{@link CoordinateOrigin#ABM_DOMAIN_TOP_LEFT}: the annotation's top-left corner is (0, 0),
 *       so {@code xMin = 0} and {@code yMin = -annH}. The domain extends right and up into the
 *       overhang ({@code x_max = nx·dx}, {@code y_max = yMin + ny·dy ≥ 0}).
 *   <li>{@link CoordinateOrigin#ABM_DOMAIN_CENTER}: the <em>annotation</em> is centered on (0, 0),
 *       so {@code xMin = -annW/2} and {@code yMin = -annH/2}. When the step does not divide the
 *       annotation the domain <em>bounds</em> come out slightly asymmetric (the overhang is
 *       one-sided, top-right) even though the annotation itself is centered.
 * </ul>
 */
public record VoxelGrid(
        int nx,
        int ny,
        double dxMicrons,
        double dyMicrons,
        double xMinMicrons,
        double yMinMicrons,
        CoordinateOrigin origin
) {
    public VoxelGrid {
        if (nx <= 0 || ny <= 0) {
            throw new IllegalArgumentException("nx and ny must be positive (got nx=" + nx + ", ny=" + ny + ")");
        }
        if (!(dxMicrons > 0) || !(dyMicrons > 0)) {
            throw new IllegalArgumentException("dx and dy must be positive and finite (got dx=" + dxMicrons + ", dy=" + dyMicrons + ")");
        }
    }

    /** Center x (µm) of voxel column {@code i}, {@code i = 0 … nx-1} left → right. */
    public double xCenter(int i) {
        if (i < 0 || i >= nx) throw new IndexOutOfBoundsException("i=" + i + " out of [0, " + nx + ")");
        return xMinMicrons + (i + 0.5) * dxMicrons;
    }

    /** Center y (µm) of voxel row {@code k}, {@code k = 0 … ny-1} bottom → top (PhysiCell index). */
    public double yCenter(int k) {
        if (k < 0 || k >= ny) throw new IndexOutOfBoundsException("k=" + k + " out of [0, " + ny + ")");
        return yMinMicrons + (k + 0.5) * dyMicrons;
    }

    /** Right edge of the domain (µm): {@code xMin + nx·dx}. */
    public double xMaxMicrons() {
        return xMinMicrons + nx * dxMicrons;
    }

    /** Top edge of the domain (µm): {@code yMin + ny·dy}. */
    public double yMaxMicrons() {
        return yMinMicrons + ny * dyMicrons;
    }

    /**
     * Build a voxel grid that covers an annotation rectangle of size
     * {@code (annotationWidthMicrons, annotationHeightMicrons)} using the requested step size.
     *
     * <p>The grid uses the smallest {@code nx, ny} such that {@code nx·step ≥ annotationWidth} and
     * {@code ny·step ≥ annotationHeight}. The overhang (up to one step per far edge) is clipped by
     * the sampler. {@code origin} sets the numeric {@code (xMin, yMin)} anchor; the annotation's
     * image-pixel position never enters the equation.
     *
     * @param annotationWidthMicrons  annotation width in µm (must be > 0)
     * @param annotationHeightMicrons annotation height in µm (must be > 0)
     * @param stepSizeMicrons         voxel step size in µm (must be > 0)
     * @param origin                  coordinate origin convention
     */
    public static VoxelGrid cover(
            double annotationWidthMicrons,
            double annotationHeightMicrons,
            double stepSizeMicrons,
            CoordinateOrigin origin
    ) {
        if (!(stepSizeMicrons > 0)) {
            throw new IllegalArgumentException("stepSizeMicrons must be positive (got " + stepSizeMicrons + ")");
        }
        if (!(annotationWidthMicrons > 0) || !(annotationHeightMicrons > 0)) {
            throw new IllegalArgumentException("annotation dimensions must be positive (got "
                    + annotationWidthMicrons + " x " + annotationHeightMicrons + ")");
        }

        int nx = (int) Math.ceil(annotationWidthMicrons / stepSizeMicrons);
        int ny = (int) Math.ceil(annotationHeightMicrons / stepSizeMicrons);

        double[] anchor = origin.minCornerMicrons(annotationWidthMicrons, annotationHeightMicrons);
        return new VoxelGrid(nx, ny, stepSizeMicrons, stepSizeMicrons, anchor[0], anchor[1], origin);
    }
}
