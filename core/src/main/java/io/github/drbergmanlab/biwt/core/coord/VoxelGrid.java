package io.github.drbergmanlab.biwt.core.coord;

/**
 * Regular 2D voxel grid covering an ABM domain. Coordinates follow PhysiCell's
 * {@code Cartesian_Mesh::resize} convention along x: the i-th voxel center along x is
 * {@code xStartMicrons + (i + 0.5) * dxMicrons}.
 *
 * <p><b>Y axis convention.</b> PhysiCell math has +y pointing up, but image-pixel rows go
 * top-to-bottom. So voxel {@code j = 0} (the image's top row) gets the <em>largest</em> y value
 * and {@code j = ny - 1} gets the smallest. The {@link #yCenter} formula reflects this:
 * <pre>yCenter(j) = yStartMicrons - (j + 0.5) * dyMicrons</pre>
 * The grid orientation still matches the image — top-left voxel is still {@code (i = 0, j = 0)},
 * no transpose, no rotation — only the µm coordinate associated with each {@code j} flips.
 *
 * <p>For the MVP, the grid is sized so the smallest integer multiple of {@code stepSizeMicrons}
 * fully covers the annotation in each axis. The grid extent may therefore overhang the annotation
 * by up to {@code stepSize - 1} pixels per side; the sampler clips each window to the annotation.
 *
 * <p>The origin is anchored to the <em>voxel grid</em>, not to the image — the grid IS the ABM
 * domain. {@link CoordinateOrigin} picks where (0, 0) sits on it:
 * <ul>
 *   <li>{@link CoordinateOrigin#ABM_DOMAIN_CENTER}: grid center maps to (0, 0).
 *       Grid spans {@code [-W/2, +W/2] × [-H/2, +H/2]} in µm — the symmetric domain PhysiCell expects.
 *   <li>{@link CoordinateOrigin#ABM_DOMAIN_TOP_LEFT}: grid top-left corner maps to (0, 0).
 *       Grid extends into the fourth quadrant — x grows right, y grows up (so the image-bottom edge
 *       of the grid is at the most negative y).
 * </ul>
 */
public record VoxelGrid(
        int nx,
        int ny,
        double dxMicrons,
        double dyMicrons,
        double xStartMicrons,
        double yStartMicrons,
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

    public double xCenter(int i) {
        if (i < 0 || i >= nx) throw new IndexOutOfBoundsException("i=" + i + " out of [0, " + nx + ")");
        return xStartMicrons + (i + 0.5) * dxMicrons;
    }

    public double yCenter(int j) {
        if (j < 0 || j >= ny) throw new IndexOutOfBoundsException("j=" + j + " out of [0, " + ny + ")");
        // Image rows go top→bottom but math +y is up: subtract, not add.
        return yStartMicrons - (j + 0.5) * dyMicrons;
    }

    /**
     * Build a voxel grid that covers an annotation rectangle of size
     * {@code (annotationWidthMicrons, annotationHeightMicrons)}, using the requested step size.
     *
     * <p>The grid uses the smallest {@code nx} and {@code ny} such that
     * {@code nx * stepSizeMicrons >= annotationWidthMicrons} and similarly for y. Per the
     * clip-to-annotation rule, the grid extent may overhang the annotation by up to
     * {@code stepSizeMicrons} in each axis; the sampler handles the intersection.
     *
     * <p>The math-µm origin (where (0, 0) sits) is anchored to the voxel grid itself per the
     * {@code origin} argument — the image dimensions and the annotation's image-pixel position
     * don't enter the equation.
     *
     * @param annotationWidthMicrons annotation width in µm (must be > 0)
     * @param annotationHeightMicrons annotation height in µm (must be > 0)
     * @param stepSizeMicrons requested step size in µm (must be > 0)
     * @param origin coordinate origin convention
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

        double gridWidthMicrons = nx * stepSizeMicrons;
        double gridHeightMicrons = ny * stepSizeMicrons;

        double xStart;
        double yStart;
        switch (origin) {
            case ABM_DOMAIN_TOP_LEFT -> {
                // Grid top-left → (0, 0). Voxel (0, 0) center at (+0.5·dx, −0.5·dy).
                xStart = 0;
                yStart = 0;
            }
            case ABM_DOMAIN_CENTER -> {
                // Grid center → (0, 0). Grid spans [-W/2, +W/2] × [-H/2, +H/2].
                xStart = -gridWidthMicrons / 2.0;
                yStart = gridHeightMicrons / 2.0;
            }
            default -> throw new IllegalArgumentException("Unknown origin: " + origin);
        }

        return new VoxelGrid(nx, ny, stepSizeMicrons, stepSizeMicrons, xStart, yStart, origin);
    }
}
