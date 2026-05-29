package io.github.drbergmanlab.biwt.core.export;

import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubstrateCsvWriterTest {

    @Test
    void writesPhysiCellSchemaForSingleSubstrate(@TempDir Path tmp) throws Exception {
        // 3x1 grid, dx=20, top-left origin: xCenter = 10, 30, 50 (i = 0..2).
        // Y axis is flipped relative to image rows: with yStart=0, yCenter(0) = -10.
        VoxelGrid grid = new VoxelGrid(3, 1, 20.0, 20.0, 0.0, 0.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        NamedSubstrate oxygen = new NamedSubstrate("oxygen", new double[][] {{38.0, 37.0, 36.0}});

        Path out = tmp.resolve("substrates.csv");
        new SubstrateCsvWriter().write(out, grid, List.of(oxygen));

        List<String> lines = Files.readAllLines(out);
        assertEquals(4, lines.size());
        assertEquals("x,y,z,oxygen", lines.get(0));
        assertEquals("10,-10,0,38", lines.get(1));
        assertEquals("30,-10,0,37", lines.get(2));
        assertEquals("50,-10,0,36", lines.get(3));
    }

    @Test
    void writesMultipleSubstratesInColumnOrder(@TempDir Path tmp) throws Exception {
        // 2x2 grid, dx=20, top-left origin: xCenter = 10, 30; yCenter(0) = -10, yCenter(1) = -30.
        VoxelGrid grid = new VoxelGrid(2, 2, 20.0, 20.0, 0.0, 0.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        NamedSubstrate oxygen = new NamedSubstrate("oxygen", new double[][] {{38.0, 37.0}, {36.0, 35.0}});
        NamedSubstrate ecm = new NamedSubstrate("ecm", new double[][] {{1.0, 0.98}, {0.96, 0.94}});

        Path out = tmp.resolve("substrates.csv");
        new SubstrateCsvWriter().write(out, grid, List.of(oxygen, ecm));

        List<String> lines = Files.readAllLines(out);
        assertEquals("x,y,z,oxygen,ecm", lines.get(0));
        // j=0 row (image top, math y = -10), then j=1 row (image bottom, math y = -30).
        assertTrue(lines.get(1).startsWith("10,-10,0,38,1"),  "got: " + lines.get(1));
        assertTrue(lines.get(2).startsWith("30,-10,0,37,"),   "got: " + lines.get(2));
        assertTrue(lines.get(3).startsWith("10,-30,0,36,"),   "got: " + lines.get(3));
        assertTrue(lines.get(4).startsWith("30,-30,0,35,"),   "got: " + lines.get(4));
    }

    @Test
    void writesNanForEmptyIntersection(@TempDir Path tmp) throws Exception {
        VoxelGrid grid = new VoxelGrid(2, 1, 10.0, 10.0, 0.0, 0.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        NamedSubstrate s = new NamedSubstrate("oxygen", new double[][] {{1.0, Double.NaN}});

        Path out = tmp.resolve("substrates.csv");
        new SubstrateCsvWriter().write(out, grid, List.of(s));

        List<String> lines = Files.readAllLines(out);
        assertTrue(lines.get(2).endsWith(",NaN"), "got: " + lines.get(2));
    }

    @Test
    void rejectsMismatchedDimensions(@TempDir Path tmp) {
        VoxelGrid grid = new VoxelGrid(3, 2, 20, 20, 0, 0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        NamedSubstrate wrongShape = new NamedSubstrate("x", new double[][] {{1, 2}, {3, 4}}); // 2x2 vs grid 3x2
        assertThrows(IllegalArgumentException.class,
                () -> new SubstrateCsvWriter().write(tmp.resolve("out.csv"), grid, List.of(wrongShape)));
    }

    @Test
    void rejectsEmptySubstrateList(@TempDir Path tmp) {
        VoxelGrid grid = new VoxelGrid(1, 1, 1, 1, 0, 0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        assertThrows(IllegalArgumentException.class,
                () -> new SubstrateCsvWriter().write(tmp.resolve("out.csv"), grid, List.of()));
    }
}
