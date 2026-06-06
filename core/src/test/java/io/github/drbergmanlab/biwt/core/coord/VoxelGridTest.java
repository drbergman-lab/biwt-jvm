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
    void topLeftAnchorsBottomLeftCornerWithOverhangTopRight() {
        // Canonical example: annotation 310 × 410 µm, step 20 µm, top-left origin.
        // nx = ceil(310/20) = 16, ny = ceil(410/20) = 21.
        // Anchor = annotation bottom-left = (x_min, y_min) = (0, -410). Overhang at top & right:
        //   domain x ∈ [0, 320], y ∈ [-410, +10].
        VoxelGrid grid = VoxelGrid.cover(310.0, 410.0, 20.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        assertEquals(16, grid.nx());
        assertEquals(21, grid.ny());
        assertEquals(0.0,   grid.xMinMicrons(), EPS);
        assertEquals(-410.0, grid.yMinMicrons(), EPS);
        assertEquals(320.0,  grid.xMaxMicrons(), EPS);
        assertEquals(10.0,   grid.yMaxMicrons(), EPS);
        // x-centers 10, 30, …, 310; y-centers -400, -380, …, 0 (bottom → top).
        assertEquals(10.0,  grid.xCenter(0), EPS);
        assertEquals(310.0, grid.xCenter(15), EPS);
        assertEquals(-400.0, grid.yCenter(0), EPS);
        assertEquals(0.0,    grid.yCenter(20), EPS);
    }

    @Test
    void abmDomainCenterMakesGridSymmetricWhenStepDivides() {
        // Annotation 60 µm × 60 µm, step 20 → 3×3 grid covering 60 µm exactly (no overhang).
        // ABM_DOMAIN_CENTER centers the annotation on (0, 0): xMin = -annW/2 = -30, yMin = -30.
        //   Voxel (0, 0) is the bottom-left: center (xMin + 10, yMin + 10) = (−20, −20).
        // The annotation's image-pixel position is irrelevant — only its size matters.
        VoxelGrid grid = VoxelGrid.cover(60.0, 60.0, 20.0, CoordinateOrigin.ABM_DOMAIN_CENTER);
        assertEquals(-30.0, grid.xMinMicrons(), EPS);
        assertEquals(-30.0, grid.yMinMicrons(), EPS);
        assertEquals(-20.0, grid.xCenter(0), EPS);
        assertEquals(-20.0, grid.yCenter(0), EPS);
        // Exactly divisible → symmetric: voxel (nx − 1, ny − 1) mirrors voxel (0, 0).
        assertEquals( 20.0, grid.xCenter(2), EPS);
        assertEquals( 20.0, grid.yCenter(2), EPS);
        assertEquals( 30.0, grid.xMaxMicrons(), EPS);
        assertEquals( 30.0, grid.yMaxMicrons(), EPS);
    }

    @Test
    void abmDomainCenterCentersAnnotationButBoundsGoAsymmetricOnOverhang() {
        // Annotation 41 µm, step 20 → nx = 3, grid extent = 60 µm (19 µm overhang).
        // The ANNOTATION is centered on 0 (its edges at ±20.5); the DOMAIN bounds are asymmetric
        // because the one-sided overhang extends only toward +x: domain x ∈ [-20.5, 39.5].
        VoxelGrid grid = VoxelGrid.cover(41.0, 41.0, 20.0, CoordinateOrigin.ABM_DOMAIN_CENTER);
        assertEquals(-20.5, grid.xMinMicrons(), EPS);
        assertEquals( 39.5, grid.xMaxMicrons(), EPS);
        // Centers: −10.5, 9.5, 29.5 — the overhang voxel reaches past the annotation.
        assertEquals(-10.5, grid.xCenter(0), EPS);
        assertEquals(  9.5, grid.xCenter(1), EPS);
        assertEquals( 29.5, grid.xCenter(2), EPS);
    }

    @Test
    void yIndexIsBottomUpPhysiCellOrder() {
        // Voxel index k follows PhysiCell's mesh: k = 0 is the bottom row (smallest math y),
        // increasing upward. The image-row→math-y flip lives in the sampler, not the grid.
        VoxelGrid grid = VoxelGrid.cover(100.0, 100.0, 20.0, CoordinateOrigin.ABM_DOMAIN_CENTER);
        assertTrue(grid.yCenter(0) < grid.yCenter(grid.ny() - 1),
                "k = 0 (bottom) should have the smallest math y");
        // 5 × 5 grid centered at (0, 0): centers at -40, -20, 0, 20, 40 (bottom → top).
        assertEquals(-40.0, grid.yCenter(0), EPS);
        assertEquals(  0.0, grid.yCenter(2), EPS);
        assertEquals( 40.0, grid.yCenter(4), EPS);
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
