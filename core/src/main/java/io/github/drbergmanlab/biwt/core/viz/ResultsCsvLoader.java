package io.github.drbergmanlab.biwt.core.viz;

import io.github.drbergmanlab.biwt.core.cells.CellRecord;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads BIWT output CSVs back into the in-memory data model the results visualizer renders, so a
 * saved export can be reopened without re-running the build.
 *
 * <p>Two file shapes are understood, distinguished by the header's fourth column:
 * <ul>
 *   <li><b>Cells</b> — header {@code x,y,z,type[,volume]}; each row becomes a {@link CellRecord}.</li>
 *   <li><b>Substrates</b> — header {@code x,y,z,<name…>}; the voxel grid is reconstructed from the
 *       sorted unique x/y centers (PhysiCell mesh order, {@code k = 0} = smallest y) and each named
 *       column is re-binned into {@code values[k][i]}.</li>
 * </ul>
 *
 * <p>Pure logic — only {@code :core} types, no JavaFX.
 */
public final class ResultsCsvLoader {

    private ResultsCsvLoader() {}

    /** A reconstructed substrate export: the voxel grid and its named fields. */
    public record SubstrateData(VoxelGrid grid, List<NamedSubstrate> substrates) {}

    /** True if {@code csv}'s header marks it as a cells file ({@code x,y,z,type,…}). */
    public static boolean isCellsCsv(Path csv) throws IOException {
        List<String> header = parseLine(firstLine(csv));
        return header.size() >= 4 && header.get(3).trim().equalsIgnoreCase("type");
    }

    /**
     * Parse a cells CSV ({@code x,y,z,type[,volume]}) into {@link CellRecord}s. Rows whose type is
     * blank are skipped (a cell type must be non-blank).
     */
    public static List<CellRecord> loadCells(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty()) {
            throw new IOException("empty CSV: " + csv);
        }
        List<String> header = parseLine(lines.get(0));
        boolean hasVolume = header.size() >= 5 && header.get(4).trim().equalsIgnoreCase("volume");

        List<CellRecord> cells = new ArrayList<>();
        for (int r = 1; r < lines.size(); r++) {
            String line = lines.get(r);
            if (line.isBlank()) continue;
            List<String> f = parseLine(line);
            if (f.size() < 4) continue;
            double x = parseNum(f.get(0));
            double y = parseNum(f.get(1));
            double z = parseNum(f.get(2));
            String type = f.get(3).trim();
            if (type.isBlank()) continue;
            double volume = hasVolume && f.size() >= 5 && !f.get(4).isBlank()
                    ? parseNum(f.get(4)) : Double.NaN;
            cells.add(new CellRecord(x, y, z, type, volume));
        }
        return cells;
    }

    /**
     * Parse a substrate CSV ({@code x,y,z,<name…>}) and reconstruct the voxel grid plus one
     * {@link NamedSubstrate} per named column.
     *
     * @throws IOException              on read failure or a malformed/too-narrow file
     * @throws IllegalArgumentException if the coordinates don't form a regular grid
     */
    public static SubstrateData loadSubstrates(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv);
        if (lines.isEmpty()) {
            throw new IOException("empty CSV: " + csv);
        }
        List<String> header = parseLine(lines.get(0));
        if (header.size() < 4) {
            throw new IOException("substrate CSV needs at least one value column: " + csv);
        }
        List<String> names = header.subList(3, header.size());

        // First pass: gather coordinates and raw cell values per row.
        List<double[]> coords = new ArrayList<>();           // {x, y} per data row
        List<double[]> rowValues = new ArrayList<>();          // one value per substrate, per row
        for (int r = 1; r < lines.size(); r++) {
            String line = lines.get(r);
            if (line.isBlank()) continue;
            List<String> f = parseLine(line);
            if (f.size() < header.size()) continue;
            double x = parseNum(f.get(0));
            double y = parseNum(f.get(1));
            double[] vals = new double[names.size()];
            for (int c = 0; c < names.size(); c++) {
                vals[c] = parseNum(f.get(3 + c));
            }
            coords.add(new double[] {x, y});
            rowValues.add(vals);
        }
        if (coords.isEmpty()) {
            throw new IOException("substrate CSV has no data rows: " + csv);
        }

        double[] xs = uniqueSortedAxis(coords, 0);
        double[] ys = uniqueSortedAxis(coords, 1);
        int nx = xs.length;
        int ny = ys.length;
        double dx = spacing(xs, ys);
        double dy = spacing(ys, xs);
        double xMin = xs[0] - dx / 2.0;
        double yMin = ys[0] - dy / 2.0;
        VoxelGrid grid = new VoxelGrid(nx, ny, dx, dy, xMin, yMin, CoordinateOrigin.ABM_DOMAIN_CENTER);

        // Second pass: bin each row into values[k][i] (k from ascending y, i from ascending x).
        List<double[][]> fields = new ArrayList<>(names.size());
        for (int c = 0; c < names.size(); c++) {
            double[][] grid2d = new double[ny][nx];
            for (double[] row : grid2d) {
                Arrays.fill(row, Double.NaN);
            }
            fields.add(grid2d);
        }
        for (int r = 0; r < coords.size(); r++) {
            int i = nearestIndex(xs, coords.get(r)[0]);
            int k = nearestIndex(ys, coords.get(r)[1]);
            double[] vals = rowValues.get(r);
            for (int c = 0; c < names.size(); c++) {
                fields.get(c)[k][i] = vals[c];
            }
        }

        List<NamedSubstrate> substrates = new ArrayList<>(names.size());
        for (int c = 0; c < names.size(); c++) {
            substrates.add(new NamedSubstrate(names.get(c).trim(), fields.get(c)));
        }
        return new SubstrateData(grid, substrates);
    }

    // ---------------- grid reconstruction helpers ----------------

    /** Sorted, de-duplicated values along axis {@code axis} (0 = x, 1 = y), merging near-equal coords. */
    private static double[] uniqueSortedAxis(List<double[]> coords, int axis) {
        double[] all = coords.stream().mapToDouble(c -> c[axis]).sorted().toArray();
        // A regular grid's spacing bounds the merge tolerance: anything closer than a small
        // fraction of the smallest gap is the same coordinate seen on another row.
        double tol = mergeTolerance(all);
        List<Double> uniq = new ArrayList<>();
        for (double v : all) {
            if (uniq.isEmpty() || Math.abs(v - uniq.get(uniq.size() - 1)) > tol) {
                uniq.add(v);
            }
        }
        return uniq.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Tolerance for merging equal coordinates: a small fraction of the data's overall span. */
    private static double mergeTolerance(double[] sorted) {
        double span = sorted[sorted.length - 1] - sorted[0];
        return span > 0 ? span * 1e-6 + 1e-9 : 1e-9;
    }

    /** The regular spacing along {@code primary}; falls back to the other axis for a 1-wide grid. */
    private static double spacing(double[] primary, double[] other) {
        if (primary.length > 1) {
            return (primary[primary.length - 1] - primary[0]) / (primary.length - 1);
        }
        if (other.length > 1) {
            return (other[other.length - 1] - other[0]) / (other.length - 1);
        }
        return 1.0; // single voxel — size is arbitrary, only used for the (unused) clip rectangle
    }

    /** Index of the entry in sorted {@code axis} nearest to {@code value}. */
    private static int nearestIndex(double[] axis, double value) {
        int idx = Arrays.binarySearch(axis, value);
        if (idx >= 0) return idx;
        int ins = -idx - 1;
        if (ins == 0) return 0;
        if (ins == axis.length) return axis.length - 1;
        return (value - axis[ins - 1]) <= (axis[ins] - value) ? ins - 1 : ins;
    }

    // ---------------- minimal CSV parsing ----------------

    private static String firstLine(Path csv) throws IOException {
        try (var lines = Files.lines(csv)) {
            return lines.findFirst().orElseThrow(() -> new IOException("empty CSV: " + csv));
        }
    }

    /** Parse one RFC-4180-style CSV line: quoted fields with doubled quotes, comma separators. */
    static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        out.add(field.toString());
        return out;
    }

    private static double parseNum(String s) {
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("NaN")) {
            return Double.NaN;
        }
        return Double.parseDouble(t);
    }
}
