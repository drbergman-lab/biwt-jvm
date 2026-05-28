package io.github.drbergmanlab.biwt.core.coord;

/**
 * Regular 2D voxel grid covering an ABM domain. Coordinates follow PhysiCell's
 * {@code Cartesian_Mesh::resize} convention: the i-th voxel center along x is
 * {@code xStartMicrons + (i + 0.5) * dxMicrons}.
 *
 * <p>For the MVP, the grid is sized so the smallest integer multiple of {@code stepSizeMicrons}
 * fully covers the annotation in each axis. The grid extent may therefore overhang the annotation
 * by up to {@code stepSize - 1} pixels per side; the sampler clips each window to the annotation.
 *
 * <p>The origin is determined by {@link CoordinateOrigin}:
 * <ul>
 *   <li>{@link CoordinateOrigin#IMAGE_CENTER} (MVP default): the image center maps to (0, 0).
 *       {@code xStartMicrons = -(nx * dxMicrons) / 2}.
 *   <li>{@link CoordinateOrigin#IMAGE_TOP_LEFT} (deferred): the image top-left maps to (0, 0).
 *       {@code xStartMicrons = 0}.
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
        return yStartMicrons + (j + 0.5) * dyMicrons;
    }

    /**
     * Build a voxel grid that covers an annotation rectangle of size
     * {@code (annotationWidthMicrons, annotationHeightMicrons)}, using the requested step size.
     *
     * <p>The grid uses the smallest {@code nx} and {@code ny} such that
     * {@code nx * stepSizeMicrons >= annotationWidthMicrons} and similarly for y.
     * Per the clip-to-annotation rule, the grid extent may overhang the annotation by up to
     * {@code stepSizeMicrons} in each axis; the sampler handles the intersection.
     *
     * @param annotationWidthMicrons annotation width in µm (must be > 0)
     * @param annotationHeightMicrons annotation height in µm (must be > 0)
     * @param stepSizeMicrons requested step size in µm (must be > 0)
     * @param imageWidthMicrons image width in µm, used only when {@code origin == IMAGE_CENTER}
     * @param imageHeightMicrons image height in µm, used only when {@code origin == IMAGE_CENTER}
     * @param annotationXMinMicrons annotation top-left x in image-pixel-equivalent µm
     * @param annotationYMinMicrons annotation top-left y in image-pixel-equivalent µm
     * @param origin coordinate origin convention
     */
    public static VoxelGrid cover(
            double annotationWidthMicrons,
            double annotationHeightMicrons,
            double stepSizeMicrons,
            double imageWidthMicrons,
            double imageHeightMicrons,
            double annotationXMinMicrons,
            double annotationYMinMicrons,
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

        // Grid spans [annotationXMin, annotationXMin + nx * stepSize) in image-µm space.
        // Shift so the origin matches the requested convention.
        double xShift;
        double yShift;
        switch (origin) {
            case IMAGE_TOP_LEFT -> {
                xShift = 0;
                yShift = 0;
            }
            case IMAGE_CENTER -> {
                xShift = -imageWidthMicrons / 2.0;
                yShift = -imageHeightMicrons / 2.0;
            }
            default -> throw new IllegalArgumentException("Unknown origin: " + origin);
        }

        double xStart = annotationXMinMicrons + xShift;
        double yStart = annotationYMinMicrons + yShift;

        return new VoxelGrid(nx, ny, stepSizeMicrons, stepSizeMicrons, xStart, yStart, origin);
    }
}
