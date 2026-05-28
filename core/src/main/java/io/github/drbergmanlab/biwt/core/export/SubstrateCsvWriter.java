package io.github.drbergmanlab.biwt.core.export;

import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes a PhysiCell-compatible substrate CSV.
 *
 * <p>Schema: {@code x,y,z,substrate_1,substrate_2,...} with one row per voxel center.
 * Coordinates are in µm and follow the {@link VoxelGrid} convention (PhysiCell's
 * {@code x_start + (i+0.5)*dx}).
 *
 * <p>For the 2D MVP, {@code z = 0} in every row. Row ordering iterates {@code j} (y)
 * as the outer loop and {@code i} (x) as the inner loop, matching common ABM
 * input-file conventions.
 */
public final class SubstrateCsvWriter {

    public void write(Path out, VoxelGrid grid, List<NamedSubstrate> substrates) throws IOException {
        if (substrates.isEmpty()) {
            throw new IllegalArgumentException("substrates must contain at least one entry");
        }
        for (NamedSubstrate s : substrates) {
            if (s.values().length != grid.ny() || s.values()[0].length != grid.nx()) {
                throw new IllegalArgumentException(String.format(
                        "substrate '%s' dimensions %dx%d do not match grid %dx%d",
                        s.name(), s.values()[0].length, s.values().length, grid.nx(), grid.ny()));
            }
        }

        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            StringBuilder header = new StringBuilder("x,y,z");
            for (NamedSubstrate s : substrates) {
                header.append(',').append(s.name());
            }
            w.write(header.toString());
            w.newLine();

            for (int j = 0; j < grid.ny(); j++) {
                double y = grid.yCenter(j);
                for (int i = 0; i < grid.nx(); i++) {
                    double x = grid.xCenter(i);
                    StringBuilder row = new StringBuilder();
                    appendNumber(row, x);
                    row.append(',');
                    appendNumber(row, y);
                    row.append(",0");
                    for (NamedSubstrate s : substrates) {
                        row.append(',');
                        appendNumber(row, s.values()[j][i]);
                    }
                    w.write(row.toString());
                    w.newLine();
                }
            }
        }
    }

    private static void appendNumber(StringBuilder out, double v) {
        if (Double.isNaN(v)) {
            out.append("NaN");
        } else if (Double.isInfinite(v)) {
            out.append(v > 0 ? "Infinity" : "-Infinity");
        } else if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            out.append(String.format(Locale.ROOT, "%d", (long) v));
        } else {
            out.append(String.format(Locale.ROOT, "%g", v));
        }
    }
}
