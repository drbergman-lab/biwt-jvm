package io.github.drbergmanlab.biwt.core.viz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldToScreenTest {

    private static final double EPS = 1e-9;

    @Test
    void yIsFlippedLargestWorldYAtTop() {
        // Square world onto a square screen: no letterboxing, scale = 1.
        WorldToScreen t = new WorldToScreen(0, 100, 0, 100, 0, 0, 100, 100);
        // Largest world-y (100) → top of screen (y = 0); smallest (0) → bottom (y = 100).
        assertEquals(0.0, t.screenY(100), EPS);
        assertEquals(100.0, t.screenY(0), EPS);
        // x is not flipped.
        assertEquals(0.0, t.screenX(0), EPS);
        assertEquals(100.0, t.screenX(100), EPS);
    }

    @Test
    void equalScaleLetterboxesTheLongerAxis() {
        // World is square (100×100) but screen is wide (200×100): scale = min(2, 1) = 1, and the
        // world is centered horizontally with a 50 px margin on each side.
        WorldToScreen t = new WorldToScreen(0, 100, 0, 100, 0, 0, 200, 100);
        assertEquals(1.0, t.scale(), EPS);
        assertEquals(50.0, t.screenX(0), EPS);    // left margin
        assertEquals(150.0, t.screenX(100), EPS); // right margin symmetric
        // Vertical axis fills the screen exactly (no margin).
        assertEquals(0.0, t.screenY(100), EPS);
        assertEquals(100.0, t.screenY(0), EPS);
    }

    @Test
    void screenLenScalesByTheCommonFactor() {
        WorldToScreen t = new WorldToScreen(0, 50, 0, 50, 0, 0, 100, 100); // scale = 2
        assertEquals(2.0, t.scale(), EPS);
        assertEquals(20.0, t.screenLen(10), EPS);
    }

    @Test
    void inverseRoundTrips() {
        WorldToScreen t = new WorldToScreen(-20, 80, 10, 110, 5, 7, 300, 200);
        for (double wx : new double[] {-20, 0, 33.3, 80}) {
            assertEquals(wx, t.worldX(t.screenX(wx)), 1e-6);
        }
        for (double wy : new double[] {10, 55.5, 110}) {
            assertEquals(wy, t.worldY(t.screenY(wy)), 1e-6);
        }
    }

    @Test
    void degenerateRectanglesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WorldToScreen(0, 0, 0, 100, 0, 0, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new WorldToScreen(0, 100, 0, 100, 0, 0, 0, 100));
    }
}
