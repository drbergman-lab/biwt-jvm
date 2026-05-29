package io.github.drbergmanlab.biwt.core.coord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoxelGridTest {

    private static final double EPS = 1e-9;

    @Test
    void voxelCentersFollowPhysiCellConvention() {
        // x_start = 0, dx = 20, nx = 3  →  centers at 10, 30, 50
        VoxelGrid grid = new VoxelGrid(3, 1, 20.0, 20.0, 0.0, 0.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        assertEquals(10.0, grid.xCenter(0), EPS);
        assertEquals(30.0, grid.xCenter(1), EPS);
        assertEquals(50.0, grid.xCenter(2), EPS);
    }

    @Test
    void coverSizesGridToSmallestIntegerMultiple() {
        // Annotation 41 µm wide, step 20  →  nx = ceil(41/20) = 3 (covers 60 µm, overhangs by 19)
        VoxelGrid grid = VoxelGrid.cover(41.0, 41.0, 20.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        assertEquals(3, grid.nx());
        assertEquals(3, grid.ny());
    }

    @Test
    void coverExactlyDivisibleGivesExactGrid() {
        // Annotation 60 µm wide, step 20  →  nx = 3, no overhang
        VoxelGrid grid = VoxelGrid.cover(60.0, 40.0, 20.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        assertEquals(3, grid.nx());
        assertEquals(2, grid.ny());
    }

    @Test
    void abmDomainCenterMakesGridSymmetric() {
        // Annotation 60 µm × 60 µm, step 20 → 3×3 grid covering 60 µm × 60 µm exactly.
        // With ABM_DOMAIN_CENTER, the grid center is at (0, 0):
        //   xStart = -W/2 = -30, yStart = +H/2 = 30.
        //   Voxel (0, 0) center: (xStart + 10, yStart − 10) = (−20, 20).
        // The annotation's image-pixel position is irrelevant — only grid extent matters.
        VoxelGrid grid = VoxelGrid.cover(60.0, 60.0, 20.0, CoordinateOrigin.ABM_DOMAIN_CENTER);
        assertEquals(-30.0, grid.xStartMicrons(), EPS);
        assertEquals( 30.0, grid.yStartMicrons(), EPS);
        assertEquals(-20.0, grid.xCenter(0), EPS);
        assertEquals( 20.0, grid.yCenter(0), EPS);
        // Symmetric: voxel (nx − 1, ny − 1) should mirror voxel (0, 0).
        assertEquals( 20.0, grid.xCenter(2), EPS);
        assertEquals(-20.0, grid.yCenter(2), EPS);
    }

    @Test
    void abmDomainCenterStaysSymmetricWhenStepDoesNotDivideAnnotation() {
        // Annotation 41 µm, step 20 → nx = 3, grid extent = 60 µm.
        // The grid is symmetric around (0, 0) regardless of how it lines up with the annotation.
        VoxelGrid grid = VoxelGrid.cover(41.0, 41.0, 20.0, CoordinateOrigin.ABM_DOMAIN_CENTER);
        assertEquals(-30.0, grid.xStartMicrons(), EPS);
        assertEquals( 30.0, grid.yStartMicrons(), EPS);
        // Centers: −20, 0, 20.
        assertEquals(-20.0, grid.xCenter(0), EPS);
        assertEquals(  0.0, grid.xCenter(1), EPS);
        assertEquals( 20.0, grid.xCenter(2), EPS);
    }

    @Test
    void yAxisIsFlippedRelativeToImageRows() {
        // Image rows go top→bottom but math +y is up. So voxel j = 0 (image top) has the
        // LARGEST y, and voxel j = ny − 1 (image bottom) has the smallest. Regression test for
        // the y-flip bug surfaced earlier by Plots.scatter showing an upside-down image.
        VoxelGrid grid = VoxelGrid.cover(100.0, 100.0, 20.0, CoordinateOrigin.ABM_DOMAIN_CENTER);
        assertTrue(grid.yCenter(0) > grid.yCenter(grid.ny() - 1),
                "image-top voxel should have larger math y than image-bottom voxel");
        // 5 × 5 grid centered at (0, 0): centers at -40, -20, 0, 20, 40.
        assertEquals( 40.0, grid.yCenter(0), EPS);
        assertEquals(  0.0, grid.yCenter(2), EPS);
        assertEquals(-40.0, grid.yCenter(4), EPS);
    }

    @Test
    void rejectsZeroOrNegativeDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new VoxelGrid(0, 1, 1.0, 1.0, 0.0, 0.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT));
        assertThrows(IllegalArgumentException.class,
                () -> new VoxelGrid(1, 1, 0.0, 1.0, 0.0, 0.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT));
        assertThrows(IllegalArgumentException.class,
                () -> VoxelGrid.cover(10, 10, -1, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT));
    }

    @Test
    void xCenterRejectsOutOfBoundsIndex() {
        VoxelGrid grid = new VoxelGrid(3, 3, 20.0, 20.0, 0.0, 0.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        assertThrows(IndexOutOfBoundsException.class, () -> grid.xCenter(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.xCenter(3));
    }
}
