package io.github.drbergmanlab.biwt.core.coord;

import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

class CoordinateTransformTest {

    private static final double EPS = 1e-9;

    /** Annotation pixel bbox (100,200)-(410,610) at 1 µm/px → 310 × 410 µm. */
    private static AbmDomain canonicalDomain() {
        return new AbmDomain("test", 100, 200, 410, 610, 1.0, 1.0, new Rectangle(100, 200, 310, 410));
    }

    @Test
    void topLeftPlacesCentroidWithYFlip() {
        // The canonical worked example: a cell at pixel (222, 333) → (122, -133).
        CoordinateTransform t = CoordinateTransform.of(canonicalDomain(), CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        assertEquals(122.0,  t.xMicrons(222), EPS);
        assertEquals(-133.0, t.yMicrons(333), EPS);
        // Corners: top-left pixel (100,200) → (0, 0); bottom-left pixel (100,610) → (0, -410).
        assertEquals(0.0,    t.xMicrons(100), EPS);
        assertEquals(0.0,    t.yMicrons(200), EPS);
        assertEquals(-410.0, t.yMicrons(610), EPS);
    }

    @Test
    void centerOffsetsByHalfTheAnnotation() {
        CoordinateTransform t = CoordinateTransform.of(canonicalDomain(), CoordinateOrigin.ABM_DOMAIN_CENTER);
        // Annotation centered on 0: center pixel (255,405) → (0,0); our (222,333) shifts by (-155,-205).
        assertEquals(-33.0, t.xMicrons(222), EPS);
        assertEquals( 72.0, t.yMicrons(333), EPS);
        assertEquals(0.0, t.xMicrons(255), EPS);
        assertEquals(0.0, t.yMicrons(405), EPS);
    }

    @Test
    void agreesWithVoxelGridAtVoxelCenters() {
        // A cell sitting exactly on a substrate voxel center must get that voxel's coordinates.
        AbmDomain domain = canonicalDomain();
        CoordinateTransform t = CoordinateTransform.of(domain, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        VoxelGrid grid = VoxelGrid.cover(domain.widthMicrons(), domain.heightMicrons(), 20.0,
                CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        // Voxel (i=0, k=0) center is at pixel (xMinPx + 10, yMaxPx - 10) = (110, 600).
        assertEquals(grid.xCenter(0), t.xMicrons(110), EPS);
        assertEquals(grid.yCenter(0), t.yMicrons(600), EPS);
    }

    @Test
    void honorsNonSquarePixels() {
        // 0.5 µm/px in x, 2.0 µm/px in y. Domain bbox (0,0)-(10,10) px → 5 × 20 µm.
        AbmDomain domain = new AbmDomain("nonsquare", 0, 0, 10, 10, 0.5, 2.0, new Rectangle(0, 0, 10, 10));
        CoordinateTransform t = CoordinateTransform.of(domain, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        // xMin=0, yMin=-20. pixel (4, 3): x = 4*0.5 = 2; y = -20 + (10-3)*2 = -6.
        assertEquals(2.0,  t.xMicrons(4), EPS);
        assertEquals(-6.0, t.yMicrons(3), EPS);
    }
}
