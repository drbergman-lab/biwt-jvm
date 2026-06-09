package io.github.drbergmanlab.biwt.core.viz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColorMapTest {

    private final ColorMap cm = ColorMap.VIRIDIS;

    private static int alpha(int argb) { return (argb >>> 24) & 0xFF; }

    @Test
    void nanIsTransparent() {
        assertEquals(0, cm.argb(Double.NaN, 0, 1));
        assertEquals(0, alpha(cm.argb(Double.NaN, 0, 1)));
    }

    @Test
    void endpointsAreOpaqueAndDistinct() {
        int low = cm.argb(0.0, 0.0, 1.0);
        int high = cm.argb(1.0, 0.0, 1.0);
        assertEquals(0xFF, alpha(low));
        assertEquals(0xFF, alpha(high));
        assertNotEquals(low, high);
        // Low end matches the gradient's first stop; high end its last stop.
        assertEquals(cm.colorAt(0.0), low);
        assertEquals(cm.colorAt(1.0), high);
    }

    @Test
    void valuesBelowMinAndAboveMaxClamp() {
        assertEquals(cm.argb(0.0, 0.0, 1.0), cm.argb(-5.0, 0.0, 1.0));
        assertEquals(cm.argb(1.0, 0.0, 1.0), cm.argb(99.0, 0.0, 1.0));
    }

    @Test
    void degenerateRangeUsesMidpointNotDivByZero() {
        int mid = cm.colorAt(0.5);
        assertEquals(mid, cm.argb(7.0, 5.0, 5.0));   // min == max
        assertEquals(mid, cm.argb(7.0, 5.0, 3.0));   // max < min
        assertEquals(0xFF, alpha(cm.argb(7.0, 5.0, 5.0)));
    }

    @Test
    void midValueDiffersFromEndpoints() {
        int low = cm.argb(0.0, 0.0, 1.0);
        int mid = cm.argb(0.5, 0.0, 1.0);
        int high = cm.argb(1.0, 0.0, 1.0);
        assertNotEquals(low, mid);
        assertNotEquals(high, mid);
    }
}
