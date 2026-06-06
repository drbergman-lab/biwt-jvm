package io.github.drbergmanlab.biwt.core.cells;

/**
 * One placed cell, in ABM µm coordinates, ready for a PhysiCell cell initial-conditions CSV.
 *
 * @param xMicrons        cell centroid x (µm)
 * @param yMicrons        cell centroid y (µm)
 * @param zMicrons        cell centroid z (µm); 0 for the 2D MVP
 * @param type            PhysiCell cell-type name (from the QuPath classification)
 * @param volumeMicrons3  total cell volume (µm³) estimated from the segmented area, or
 *                        {@link Double#NaN} when volume was not requested/derivable
 */
public record CellRecord(
        double xMicrons,
        double yMicrons,
        double zMicrons,
        String type,
        double volumeMicrons3
) {
    public CellRecord {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("cell type must be non-blank");
        }
    }

    public boolean hasVolume() {
        return !Double.isNaN(volumeMicrons3);
    }
}
