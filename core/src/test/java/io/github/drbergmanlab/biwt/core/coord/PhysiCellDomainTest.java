package io.github.drbergmanlab.biwt.core.coord;

import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

class PhysiCellDomainTest {

    private static final double EPS = 1e-9;

    @Test
    void derivesBoundsFromCanonicalGrid() {
        // Annotation 310 × 410 µm, step 20, top-left → domain x∈[0,320], y∈[-410,10].
        VoxelGrid grid = VoxelGrid.cover(310.0, 410.0, 20.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        PhysiCellDomain d = PhysiCellDomain.of(grid);

        assertEquals(0.0,    d.xMinMicrons(), EPS);
        assertEquals(320.0,  d.xMaxMicrons(), EPS);
        assertEquals(-410.0, d.yMinMicrons(), EPS);
        assertEquals(10.0,   d.yMaxMicrons(), EPS);
        // 2D: single z slice of thickness dx centered on 0.
        assertEquals(-10.0, d.zMinMicrons(), EPS);
        assertEquals(10.0,  d.zMaxMicrons(), EPS);
        assertEquals(20.0,  d.dxMicrons(), EPS);
        assertEquals(20.0,  d.dyMicrons(), EPS);
        assertEquals(20.0,  d.dzMicrons(), EPS);
        assertEquals(16, d.nx());
        assertEquals(21, d.ny());
        assertEquals(1,  d.nz());
        assertTrue(d.use2D());
    }

    @Test
    void rendersPasteReadyPhysiCellXml() {
        VoxelGrid grid = VoxelGrid.cover(310.0, 410.0, 20.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        String xml = PhysiCellDomain.of(grid).toXml();

        assertTrue(xml.startsWith("<domain>"), xml);
        assertTrue(xml.contains("<x_min>0</x_min>"), xml);
        assertTrue(xml.contains("<x_max>320</x_max>"), xml);
        assertTrue(xml.contains("<y_min>-410</y_min>"), xml);
        assertTrue(xml.contains("<y_max>10</y_max>"), xml);
        assertTrue(xml.contains("<dx>20</dx>"), xml);
        assertTrue(xml.contains("<use_2D>true</use_2D>"), xml);
        assertTrue(xml.trim().endsWith("</domain>"), xml);
    }

    @Test
    void ofAnnotationGivesBoundsOnlyXy() {
        // 310 × 410 µm annotation, center origin → x∈[-155,155], y∈[-205,205], no voxel sizes.
        AbmDomain ann = new AbmDomain("t", 100, 200, 410, 610, 1.0, 1.0, new Rectangle(100, 200, 310, 410));
        PhysiCellDomain d = PhysiCellDomain.ofAnnotation(ann, CoordinateOrigin.ABM_DOMAIN_CENTER);

        assertFalse(d.voxelSized());
        assertEquals(-155.0, d.xMinMicrons(), EPS);
        assertEquals(155.0, d.xMaxMicrons(), EPS);
        assertEquals(-205.0, d.yMinMicrons(), EPS);
        assertEquals(205.0, d.yMaxMicrons(), EPS);

        // Only the four x/y bound tags — no z, no dx/dy/dz, no use_2D.
        assertEquals(4, d.domainTags().size());
        assertTrue(d.domainTags().containsKey("x_min"));
        assertFalse(d.domainTags().containsKey("dx"));
        String xml = d.toXml();
        assertTrue(xml.contains("<x_min>-155</x_min>"), xml);
        assertFalse(xml.contains("dx"), xml);
        assertFalse(xml.contains("use_2D"), xml);
    }

    @Test
    void keepsFractionalBoundsForNonIntegerSteps() {
        // Center origin, non-dividing step → fractional, asymmetric bounds.
        VoxelGrid grid = VoxelGrid.cover(41.0, 41.0, 20.0, CoordinateOrigin.ABM_DOMAIN_CENTER);
        PhysiCellDomain d = PhysiCellDomain.of(grid);
        assertEquals(-20.5, d.xMinMicrons(), EPS);
        assertEquals(39.5,  d.xMaxMicrons(), EPS);
        assertTrue(d.toXml().contains("<x_min>-20.5</x_min>"), d.toXml());
    }
}
