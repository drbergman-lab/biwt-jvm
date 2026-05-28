package io.github.drbergmanlab.biwt.core.domain;

import java.awt.Shape;
import java.util.Objects;

/**
 * An ABM domain — the region of the image to sample. Bounds are in pixel coordinates of the
 * full-resolution image; per-axis µm calibration converts to physical units.
 *
 * <p>{@code clipMaskPx} is the original annotation outline as a {@link Shape} in pixel coordinates.
 * For an axis-aligned rectangle this is just the bounds rectangle, but the sampler should always
 * intersect each sampling window with this Shape rather than assuming the bounds are the limit —
 * this leaves room for non-rectangular domains in a later version without changing the sampler.
 *
 * <p>For the MVP the source is either a {@code RectangleROI} annotation or the whole image.
 */
public record AbmDomain(
        String sourceDescription,
        double xMinPx,
        double yMinPx,
        double xMaxPx,
        double yMaxPx,
        double pixelWidthMicrons,
        double pixelHeightMicrons,
        Shape clipMaskPx
) {
    public AbmDomain {
        Objects.requireNonNull(sourceDescription, "sourceDescription");
        Objects.requireNonNull(clipMaskPx, "clipMaskPx");
        if (!(xMaxPx > xMinPx) || !(yMaxPx > yMinPx)) {
            throw new IllegalArgumentException("domain bounds must have positive width and height (got "
                    + (xMaxPx - xMinPx) + " x " + (yMaxPx - yMinPx) + ")");
        }
        if (!(pixelWidthMicrons > 0) || !(pixelHeightMicrons > 0)) {
            throw new IllegalArgumentException("pixel size in microns must be positive (got "
                    + pixelWidthMicrons + " x " + pixelHeightMicrons + ")");
        }
    }

    public double widthPx() {
        return xMaxPx - xMinPx;
    }

    public double heightPx() {
        return yMaxPx - yMinPx;
    }

    public double widthMicrons() {
        return widthPx() * pixelWidthMicrons;
    }

    public double heightMicrons() {
        return heightPx() * pixelHeightMicrons;
    }
}
