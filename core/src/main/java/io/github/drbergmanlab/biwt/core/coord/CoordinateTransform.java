package io.github.drbergmanlab.biwt.core.coord;

import io.github.drbergmanlab.biwt.core.domain.AbmDomain;

import java.util.Objects;

/**
 * Maps a continuous image-pixel position to ABM µm coordinates in the same frame the
 * {@link VoxelGrid} discretizes. Unlike the grid (which snaps to voxel centers), this is exact —
 * used to place cell centroids so a cell and a substrate voxel at the same physical location share
 * coordinates.
 *
 * <p>The frame is anchored at the annotation's bottom-left pixel corner
 * {@code (xMinPx, yMaxPx)} ↔ {@code (xMinMicrons, yMinMicrons)}, with the y-axis flipped (image rows
 * go top→bottom, PhysiCell math +y is up):
 * <pre>
 *   xMicrons(px) = xMinMicrons + (px - xMinPx) * pixelWidthMicrons
 *   yMicrons(py) = yMinMicrons + (yMaxPx - py) * pixelHeightMicrons
 * </pre>
 * {@code (xMinMicrons, yMinMicrons)} comes from {@link CoordinateOrigin#minCornerMicrons}, so this
 * transform and {@link VoxelGrid#cover} agree by construction.
 */
public record CoordinateTransform(
        double xMinPx,
        double yMaxPx,
        double pixelWidthMicrons,
        double pixelHeightMicrons,
        double xMinMicrons,
        double yMinMicrons
) {
    public CoordinateTransform {
        if (!(pixelWidthMicrons > 0) || !(pixelHeightMicrons > 0)) {
            throw new IllegalArgumentException("pixel size must be positive (got "
                    + pixelWidthMicrons + " x " + pixelHeightMicrons + ")");
        }
    }

    /**
     * Build the transform for a domain and origin — the same anchor the voxel grid uses.
     *
     * @param domain the ABM domain (supplies the pixel anchor and calibration)
     * @param origin where (0, 0) sits
     */
    public static CoordinateTransform of(AbmDomain domain, CoordinateOrigin origin) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(origin, "origin");
        double[] anchor = origin.minCornerMicrons(domain.widthMicrons(), domain.heightMicrons());
        return new CoordinateTransform(
                domain.xMinPx(), domain.yMaxPx(),
                domain.pixelWidthMicrons(), domain.pixelHeightMicrons(),
                anchor[0], anchor[1]);
    }

    /** ABM x (µm) for an image-pixel x coordinate. */
    public double xMicrons(double px) {
        return xMinMicrons + (px - xMinPx) * pixelWidthMicrons;
    }

    /** ABM y (µm) for an image-pixel y coordinate (y-flip: smaller pixel row → larger math y). */
    public double yMicrons(double py) {
        return yMinMicrons + (yMaxPx - py) * pixelHeightMicrons;
    }
}
