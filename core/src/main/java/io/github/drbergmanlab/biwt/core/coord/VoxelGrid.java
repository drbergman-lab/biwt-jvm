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
 * where {@code yStartMicrons} is the math-µm y of the image's top edge minus the annotation's
 * top-pixel offset. The grid orientation still matches the image — top-left voxel is still
 * (i = 0, j = 0), no transpose, no rotation — only the µm coordinate associated with each j flips.
 *
 * <p>For the MVP, the grid is sized so the smallest integer multiple of {@code stepSizeMicrons}
 * fully covers the annotation in each axis. The grid extent may therefore overhang the annotation
 * by up to {@code stepSize - 1} pixels per side; the sampler clips each window to the annotation.
 *
 * <p>The origin is determined by {@link CoordinateOrigin}:
 * <ul>
 *   <li>{@link CoordinateOrigin#IMAGE_CENTER} (MVP default): the image center maps to (0, 0).
 *       The image spans {@code [-W/2, +W/2] × [-H/2, +H/2]} in µm.
 *   <li>{@link CoordinateOrigin#IMAGE_TOP_LEFT} (deferred): the image top-left maps to (0, 0)
 *       and the image extends into the fourth quadrant — x grows right, y grows up (so
 *       image-bottom is at the most negative y).
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

        // Math-µm coordinates of the image-pixel top-left, per origin convention.
        // x grows right (same as image-pixel x); y grows up (opposite of image-pixel y).
        double xShift;  // math-µm x of image-pixel-x = 0
        double yShift;  // math-µm y of image-pixel-y = 0
        switch (origin) {
            case IMAGE_TOP_LEFT -> {
                xShift = 0;
                yShift = 0;
            }
            case IMAGE_CENTER -> {
                xShift = -imageWidthMicrons / 2.0;
                yShift = imageHeightMicrons / 2.0;
            }
            default -> throw new IllegalArgumentException("Unknown origin: " + origin);
        }

        // xStart is the µm x of image-pixel-x = annotationXMinMicrons (grows right).
        double xStart = xShift + annotationXMinMicrons;
        // yStart is the µm y of image-pixel-y = annotationYMinMicrons (math y, grows up,
        // so subtracting the image-pixel y offset).
        double yStart = yShift - annotationYMinMicrons;

        return new VoxelGrid(nx, ny, stepSizeMicrons, stepSizeMicrons, xStart, yStart, origin);
    }
}
