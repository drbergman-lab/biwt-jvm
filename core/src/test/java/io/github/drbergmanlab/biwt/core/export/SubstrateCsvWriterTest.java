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
        // 3x1 grid, dx=20: xCenter = 10, 30, 50 (i = 0..2). yMin = -20 → yCenter(0) = -10.
        VoxelGrid grid = new VoxelGrid(3, 1, 20.0, 20.0, 0.0, -20.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
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
    void writesRowsInPhysiCellMeshOrderBottomUp(@TempDir Path tmp) throws Exception {
        // 2x2 grid, dx=20, yMin=-40: xCenter = 10, 30; yCenter(0) = -30 (bottom), yCenter(1) = -10 (top).
        // Rows are written bottom-first (ascending y), x striding inner — PhysiCell mesh order.
        VoxelGrid grid = new VoxelGrid(2, 2, 20.0, 20.0, 0.0, -40.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT);
        NamedSubstrate oxygen = new NamedSubstrate("oxygen", new double[][] {{38.0, 37.0}, {36.0, 35.0}});
        NamedSubstrate ecm = new NamedSubstrate("ecm", new double[][] {{1.0, 0.98}, {0.96, 0.94}});

        Path out = tmp.resolve("substrates.csv");
        new SubstrateCsvWriter().write(out, grid, List.of(oxygen, ecm));

        List<String> lines = Files.readAllLines(out);
        assertEquals("x,y,z,oxygen,ecm", lines.get(0));
        // k=0 row first (bottom, math y = -30), then k=1 row (top, math y = -10).
        assertTrue(lines.get(1).startsWith("10,-30,0,38,1"),  "got: " + lines.get(1));
        assertTrue(lines.get(2).startsWith("30,-30,0,37,"),   "got: " + lines.get(2));
        assertTrue(lines.get(3).startsWith("10,-10,0,36,"),   "got: " + lines.get(3));
        assertTrue(lines.get(4).startsWith("30,-10,0,35,"),   "got: " + lines.get(4));
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
