package io.github.drbergmanlab.biwt.core.export;

import io.github.drbergmanlab.biwt.core.cells.CellRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes a PhysiCell cell initial-conditions CSV with a header row and <b>named</b> cell types:
 * {@code x,y,z,type} (or {@code x,y,z,type,volume} when volumes are included). The {@code type}
 * column holds the cell-type name, which must match a {@code <cell_definition name="…">} in the
 * PhysiCell config. Coordinates are µm in the same frame as the substrate export.
 *
 * <p>Rows are written in the order the cells were extracted (a cell IC is an unordered point set —
 * PhysiCell places each cell at its own {@code (x, y, z)}, so order is irrelevant on load).
 */
public final class CellCsvWriter {

    /**
     * @param out           destination CSV
     * @param cells         the placed cells (must be non-empty)
     * @param includeVolume emit a trailing {@code volume} column
     */
    public void write(Path out, List<CellRecord> cells, boolean includeVolume) throws IOException {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException(
                    "no cells to write — check the domain covers your detections and that they are classified");
        }

        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write(includeVolume ? "x,y,z,type,volume" : "x,y,z,type");
            w.newLine();

            for (CellRecord c : cells) {
                StringBuilder row = new StringBuilder();
                appendNumber(row, c.xMicrons());
                row.append(',');
                appendNumber(row, c.yMicrons());
                row.append(',');
                appendNumber(row, c.zMicrons());
                row.append(',');
                row.append(escape(c.type()));
                if (includeVolume) {
                    row.append(',');
                    appendNumber(row, c.volumeMicrons3());
                }
                w.write(row.toString());
                w.newLine();
            }
        }
    }

    private static void appendNumber(StringBuilder out, double v) {
        if (Double.isNaN(v)) {
            out.append("NaN");
        } else if (Double.isInfinite(v)) {
            out.append(v > 0 ? "Infinity" : "-Infinity");
        } else if (v == Math.floor(v) && Math.abs(v) < 1e15) {
            out.append(String.format(Locale.ROOT, "%d", (long) v));
        } else {
            out.append(String.format(Locale.ROOT, "%g", v));
        }
    }

    /** CSV-escape a type name only if it contains a comma, quote, or newline (rare for cell types). */
    private static String escape(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
