package io.github.drbergmanlab.biwt.core.viz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DataRangeTest {

    @Test
    void ignoresNaNEntries() {
        double[][] v = {
                {1.0, Double.NaN, 3.0},
                {Double.NaN, -2.0, 5.0}
        };
        double[] mm = DataRange.minMaxIgnoringNaN(v);
        assertEquals(-2.0, mm[0], 0.0);
        assertEquals(5.0, mm[1], 0.0);
    }

    @Test
    void allNaNYieldsNaNRange() {
        double[][] v = {{Double.NaN, Double.NaN}, {Double.NaN, Double.NaN}};
        double[] mm = DataRange.minMaxIgnoringNaN(v);
        assertTrue(Double.isNaN(mm[0]));
        assertTrue(Double.isNaN(mm[1]));
    }

    @Test
    void singleValueGivesEqualMinMax() {
        double[][] v = {{4.0, Double.NaN}, {Double.NaN, 4.0}};
        double[] mm = DataRange.minMaxIgnoringNaN(v);
        assertEquals(4.0, mm[0], 0.0);
        assertEquals(4.0, mm[1], 0.0);
    }

    @Test
    void nullArrayIsNaNRange() {
        double[] mm = DataRange.minMaxIgnoringNaN(null);
        assertTrue(Double.isNaN(mm[0]));
        assertTrue(Double.isNaN(mm[1]));
    }
}
