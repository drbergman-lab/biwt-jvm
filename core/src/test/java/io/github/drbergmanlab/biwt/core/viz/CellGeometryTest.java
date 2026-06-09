package io.github.drbergmanlab.biwt.core.viz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CellGeometryTest {

    private static final double EPS = 1e-6;

    @Test
    void radiusFromVolumeMatchesEquivalentSphere() {
        // V = (4/3)πr³ with r = 10 → V = 4188.79…; invert back to 10.
        double v = (4.0 / 3.0) * Math.PI * 1000.0;
        assertEquals(10.0, CellGeometry.radiusMicronsForVolume(v), EPS);
    }

    @Test
    void defaultVolumeGivesKnownRadius() {
        // PhysiCell default cell volume 2494 µm³ → r ≈ 8.412 µm.
        double r = CellGeometry.radiusMicronsForVolume(CellGeometry.DEFAULT_CELL_VOLUME_MICRONS3);
        assertEquals(8.412, r, 1e-3);
    }

    @Test
    void resolveVolumeFallsBackOnNaN() {
        assertEquals(CellGeometry.DEFAULT_CELL_VOLUME_MICRONS3, CellGeometry.resolveVolume(Double.NaN), 0.0);
        assertEquals(500.0, CellGeometry.resolveVolume(500.0), 0.0);
    }

    @Test
    void radiusForCellUsesDefaultWhenMissing() {
        double expected = CellGeometry.radiusMicronsForVolume(CellGeometry.DEFAULT_CELL_VOLUME_MICRONS3);
        assertEquals(expected, CellGeometry.radiusMicronsForCell(Double.NaN), EPS);
    }

    @Test
    void nonPositiveVolumeRejected() {
        assertThrows(IllegalArgumentException.class, () -> CellGeometry.radiusMicronsForVolume(0));
        assertThrows(IllegalArgumentException.class, () -> CellGeometry.radiusMicronsForVolume(-1));
        assertThrows(IllegalArgumentException.class,
                () -> CellGeometry.radiusMicronsForVolume(Double.POSITIVE_INFINITY));
    }
}
