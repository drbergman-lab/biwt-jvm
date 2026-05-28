package io.github.drbergmanlab.biwt.core.coord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoxelGridTest {

    private static final double EPS = 1e-9;

    @Test
    void voxelCentersFollowPhysiCellConvention() {
        // x_start = 0, dx = 20, nx = 3  →  centers at 10, 30, 50
        VoxelGrid grid = new VoxelGrid(3, 1, 20.0, 20.0, 0.0, 0.0, CoordinateOrigin.IMAGE_TOP_LEFT);
        assertEquals(10.0, grid.xCenter(0), EPS);
        assertEquals(30.0, grid.xCenter(1), EPS);
        assertEquals(50.0, grid.xCenter(2), EPS);
    }

    @Test
    void coverSizesGridToSmallestIntegerMultiple() {
        // Annotation 41 µm wide, step 20  →  nx = ceil(41/20) = 3 (covers 60 µm, overhangs by 19)
        VoxelGrid grid = VoxelGrid.cover(
                41.0, 41.0, 20.0,
                100.0, 100.0,
                0.0, 0.0,
                CoordinateOrigin.IMAGE_TOP_LEFT);
        assertEquals(3, grid.nx());
        assertEquals(3, grid.ny());
    }

    @Test
    void coverExactlyDivisibleGivesExactGrid() {
        // Annotation 60 µm wide, step 20  →  nx = 3, no overhang
        VoxelGrid grid = VoxelGrid.cover(
                60.0, 40.0, 20.0,
                100.0, 100.0,
                0.0, 0.0,
                CoordinateOrigin.IMAGE_TOP_LEFT);
        assertEquals(3, grid.nx());
        assertEquals(2, grid.ny());
    }

    @Test
    void imageCenterOriginShiftsToImageCenter() {
        // Image 100x100, annotation at (10,10) size 60x60, step 20 → grid covers 60x60.
        // Image center = (50, 50). With IMAGE_CENTER, annotation top-left (10,10) maps to (-40, -40).
        VoxelGrid grid = VoxelGrid.cover(
                60.0, 60.0, 20.0,
                100.0, 100.0,
                10.0, 10.0,
                CoordinateOrigin.IMAGE_CENTER);
        assertEquals(-40.0, grid.xStartMicrons(), EPS);
        assertEquals(-40.0, grid.yStartMicrons(), EPS);
        // First voxel center at (-40 + 10, -40 + 10) = (-30, -30)
        assertEquals(-30.0, grid.xCenter(0), EPS);
        assertEquals(-30.0, grid.yCenter(0), EPS);
    }

    @Test
    void imageCenterMapsToOriginWhenAnnotationCoversImage() {
        // Image 100x100, annotation == image, step 20, IMAGE_CENTER.
        // Grid covers exactly the image; voxel centers should be symmetric around 0.
        VoxelGrid grid = VoxelGrid.cover(
                100.0, 100.0, 20.0,
                100.0, 100.0,
                0.0, 0.0,
                CoordinateOrigin.IMAGE_CENTER);
        assertEquals(5, grid.nx());
        assertEquals(5, grid.ny());
        // Centers: -40, -20, 0, 20, 40
        assertEquals(-40.0, grid.xCenter(0), EPS);
        assertEquals(0.0, grid.xCenter(2), EPS);
        assertEquals(40.0, grid.xCenter(4), EPS);
    }

    @Test
    void rejectsZeroOrNegativeDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new VoxelGrid(0, 1, 1.0, 1.0, 0.0, 0.0, CoordinateOrigin.IMAGE_TOP_LEFT));
        assertThrows(IllegalArgumentException.class,
                () -> new VoxelGrid(1, 1, 0.0, 1.0, 0.0, 0.0, CoordinateOrigin.IMAGE_TOP_LEFT));
        assertThrows(IllegalArgumentException.class,
                () -> VoxelGrid.cover(10, 10, -1, 100, 100, 0, 0, CoordinateOrigin.IMAGE_TOP_LEFT));
    }

    @Test
    void xCenterRejectsOutOfBoundsIndex() {
        VoxelGrid grid = new VoxelGrid(3, 3, 20.0, 20.0, 0.0, 0.0, CoordinateOrigin.IMAGE_TOP_LEFT);
        assertThrows(IndexOutOfBoundsException.class, () -> grid.xCenter(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.xCenter(3));
    }
}
