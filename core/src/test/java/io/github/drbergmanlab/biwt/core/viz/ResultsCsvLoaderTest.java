package io.github.drbergmanlab.biwt.core.viz;

import io.github.drbergmanlab.biwt.core.cells.CellRecord;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.export.CellCsvWriter;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.export.SubstrateCsvWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultsCsvLoaderTest {

    private static final double EPS = 1e-6;

    @Test
    void substrateRoundTripReconstructsGridAndValues(@TempDir Path dir) throws IOException {
        // 3×2 grid, dx=dy=20, centered origin.
        VoxelGrid grid = VoxelGrid.cover(60, 40, 20, CoordinateOrigin.ABM_DOMAIN_CENTER);
        assertEquals(3, grid.nx());
        assertEquals(2, grid.ny());

        // values[k][i] — k=0 is the bottom row; embed a NaN (clipped voxel).
        double[][] vals = {
                {1.0, 2.0, 3.0},          // k=0 (bottom)
                {4.0, Double.NaN, 6.0}    // k=1 (top)
        };
        NamedSubstrate oxygen = new NamedSubstrate("oxygen", vals);

        Path csv = dir.resolve("out-substrates.csv");
        new SubstrateCsvWriter().write(csv, grid, List.of(oxygen));

        assertFalse(ResultsCsvLoader.isCellsCsv(csv));
        ResultsCsvLoader.SubstrateData data = ResultsCsvLoader.loadSubstrates(csv);

        assertEquals(grid.nx(), data.grid().nx());
        assertEquals(grid.ny(), data.grid().ny());
        assertEquals(grid.dxMicrons(), data.grid().dxMicrons(), EPS);
        assertEquals(grid.dyMicrons(), data.grid().dyMicrons(), EPS);
        assertEquals(grid.xMinMicrons(), data.grid().xMinMicrons(), EPS);
        assertEquals(grid.yMinMicrons(), data.grid().yMinMicrons(), EPS);

        assertEquals(1, data.substrates().size());
        double[][] got = data.substrates().get(0).values();
        assertEquals("oxygen", data.substrates().get(0).name());
        for (int k = 0; k < grid.ny(); k++) {
            for (int i = 0; i < grid.nx(); i++) {
                if (Double.isNaN(vals[k][i])) {
                    assertTrue(Double.isNaN(got[k][i]), "expected NaN at k=" + k + ", i=" + i);
                } else {
                    assertEquals(vals[k][i], got[k][i], EPS, "k=" + k + ", i=" + i);
                }
            }
        }
    }

    @Test
    void cellsRoundTripWithVolume(@TempDir Path dir) throws IOException {
        List<CellRecord> cells = List.of(
                new CellRecord(10, 20, 0, "tumor", 2494.0),
                new CellRecord(-5.5, 33.25, 0, "stroma", Double.NaN));
        Path csv = dir.resolve("out-cells.csv");
        new CellCsvWriter().write(csv, cells, true);

        assertTrue(ResultsCsvLoader.isCellsCsv(csv));
        List<CellRecord> got = ResultsCsvLoader.loadCells(csv);
        assertEquals(2, got.size());
        assertEquals(10.0, got.get(0).xMicrons(), EPS);
        assertEquals(20.0, got.get(0).yMicrons(), EPS);
        assertEquals("tumor", got.get(0).type());
        assertEquals(2494.0, got.get(0).volumeMicrons3(), EPS);
        assertEquals(-5.5, got.get(1).xMicrons(), EPS);
        assertEquals("stroma", got.get(1).type());
        assertFalse(got.get(1).hasVolume());
    }

    @Test
    void cellsWithoutVolumeColumn(@TempDir Path dir) throws IOException {
        List<CellRecord> cells = List.of(new CellRecord(1, 2, 0, "a", Double.NaN));
        Path csv = dir.resolve("c.csv");
        new CellCsvWriter().write(csv, cells, false);
        List<CellRecord> got = ResultsCsvLoader.loadCells(csv);
        assertEquals(1, got.size());
        assertFalse(got.get(0).hasVolume());
    }

    @Test
    void quotedTypeWithCommaParses() {
        List<String> fields = ResultsCsvLoader.parseLine("1,2,0,\"T cell, CD8+\",500");
        assertEquals(5, fields.size());
        assertEquals("T cell, CD8+", fields.get(3));
    }
}
