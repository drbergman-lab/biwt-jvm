package io.github.drbergmanlab.biwt.core.export;

import io.github.drbergmanlab.biwt.core.cells.CellRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CellCsvWriterTest {

    @Test
    void writesHeaderedNamedTypesWithoutVolume(@TempDir Path tmp) throws Exception {
        List<CellRecord> cells = List.of(
                new CellRecord(10.0, -20.0, 0.0, "Tumor", Double.NaN),
                new CellRecord(-5.5, 30.0, 0.0, "Stroma", Double.NaN));

        Path out = tmp.resolve("cells.csv");
        new CellCsvWriter().write(out, cells, false);

        List<String> lines = Files.readAllLines(out);
        assertEquals(3, lines.size());
        assertEquals("x,y,z,type", lines.get(0));
        assertEquals("10,-20,0,Tumor", lines.get(1));
        assertTrue(lines.get(2).startsWith("-5.5") && lines.get(2).endsWith(",Stroma"), lines.get(2));
    }

    @Test
    void writesVolumeColumnWhenRequested(@TempDir Path tmp) throws Exception {
        List<CellRecord> cells = List.of(new CellRecord(0.0, 0.0, 0.0, "Tumor", 904.0));
        Path out = tmp.resolve("cells.csv");
        new CellCsvWriter().write(out, cells, true);

        List<String> lines = Files.readAllLines(out);
        assertEquals("x,y,z,type,volume", lines.get(0));
        assertEquals("0,0,0,Tumor,904", lines.get(1));
    }

    @Test
    void escapesTypeNamesContainingCommas(@TempDir Path tmp) throws Exception {
        List<CellRecord> cells = List.of(new CellRecord(1.0, 2.0, 0.0, "odd,name", Double.NaN));
        Path out = tmp.resolve("cells.csv");
        new CellCsvWriter().write(out, cells, false);
        assertEquals("1,2,0,\"odd,name\"", Files.readAllLines(out).get(1));
    }

    @Test
    void rejectsEmptyCellList(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class,
                () -> new CellCsvWriter().write(tmp.resolve("cells.csv"), List.of(), false));
    }
}
