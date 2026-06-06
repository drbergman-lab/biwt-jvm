package io.github.drbergmanlab.biwt.core.coord;

import io.github.drbergmanlab.biwt.core.domain.AbmDomain;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * The PhysiCell simulation domain implied by a BIWT export — the exact values a user must set in
 * their PhysiCell config so the simulated mesh lines up with the exported coordinates.
 *
 * <p>Two flavors via {@code voxelSized}:
 * <ul>
 *   <li><b>Voxel-sized</b> ({@link #of(VoxelGrid)}, substrate export): full
 *       {@code x_min … z_max, dx, dy, dz, use_2D}, with a single z slice of thickness {@code dz = dx}
 *       centered on 0. The mesh must match the substrate voxels exactly.</li>
 *   <li><b>Bounds-only</b> ({@link #ofAnnotation(AbmDomain, CoordinateOrigin)}, cell export): just
 *       {@code x_min, x_max, y_min, y_max} from the annotation extent. Cells need no voxel size, so
 *       the user's existing {@code dx}/{@code dz}/{@code z} stay untouched.</li>
 * </ul>
 *
 * <p>{@link #toXml()} renders the {@code <domain>} block, {@link #summary()} a dialog-friendly form,
 * and {@link #domainTags()} is the single source of truth both consume (and that
 * {@link PhysiCellConfigUpdater} writes back).
 */
public record PhysiCellDomain(
        double xMinMicrons, double xMaxMicrons,
        double yMinMicrons, double yMaxMicrons,
        double zMinMicrons, double zMaxMicrons,
        double dxMicrons, double dyMicrons, double dzMicrons,
        int nx, int ny, int nz,
        boolean use2D,
        boolean voxelSized
) {

    /** Derive the full 2D PhysiCell domain from a voxel grid (single z slice of thickness {@code dx}). */
    public static PhysiCellDomain of(VoxelGrid grid) {
        Objects.requireNonNull(grid, "grid");
        double dz = grid.dxMicrons();
        return new PhysiCellDomain(
                grid.xMinMicrons(), grid.xMaxMicrons(),
                grid.yMinMicrons(), grid.yMaxMicrons(),
                -dz / 2.0, dz / 2.0,
                grid.dxMicrons(), grid.dyMicrons(), dz,
                grid.nx(), grid.ny(), 1,
                true, true);
    }

    /**
     * A bounds-only domain from an annotation's extent in the chosen frame — for cell export, where
     * no voxel grid exists. Carries only the {@code x_min/x_max/y_min/y_max} bounds (the raw,
     * unrounded annotation box); z and voxel sizes are left to the user's config.
     */
    public static PhysiCellDomain ofAnnotation(AbmDomain domain, CoordinateOrigin origin) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(origin, "origin");
        double w = domain.widthMicrons();
        double h = domain.heightMicrons();
        double[] min = origin.minCornerMicrons(w, h);
        return new PhysiCellDomain(
                min[0], min[0] + w, min[1], min[1] + h,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN,
                0, 0, 0,
                true, false);
    }

    /**
     * The PhysiCell {@code <domain>} child tags in canonical order, mapped to their formatted string
     * values. Single source of truth for {@link #toXml()} and for {@link PhysiCellConfigUpdater}.
     * A bounds-only domain returns just the four {@code x/y} bounds.
     */
    public java.util.LinkedHashMap<String, String> domainTags() {
        java.util.LinkedHashMap<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("x_min", num(xMinMicrons));
        tags.put("x_max", num(xMaxMicrons));
        tags.put("y_min", num(yMinMicrons));
        tags.put("y_max", num(yMaxMicrons));
        if (voxelSized) {
            tags.put("z_min", num(zMinMicrons));
            tags.put("z_max", num(zMaxMicrons));
            tags.put("dx", num(dxMicrons));
            tags.put("dy", num(dyMicrons));
            tags.put("dz", num(dzMicrons));
            tags.put("use_2D", Boolean.toString(use2D));
        }
        return tags;
    }

    /** A paste-ready PhysiCell {@code <domain>} XML block (tab-indented, trailing newline). */
    public String toXml() {
        StringBuilder b = new StringBuilder("<domain>\n");
        domainTags().forEach((tag, value) ->
                b.append('\t').append('<').append(tag).append('>')
                        .append(value).append("</").append(tag).append(">\n"));
        b.append("</domain>\n");
        return b.toString();
    }

    /** Compact one-block summary for a confirmation dialog. */
    public String summary() {
        if (!voxelSized) {
            return String.format(Locale.ROOT,
                    "x: [%s, %s]%ny: [%s, %s]   (domain size only — voxel size unchanged)",
                    num(xMinMicrons), num(xMaxMicrons), num(yMinMicrons), num(yMaxMicrons));
        }
        return String.format(Locale.ROOT,
                "x: [%s, %s]  dx %s  (nx %d)%n"
                        + "y: [%s, %s]  dy %s  (ny %d)%n"
                        + "z: [%s, %s]  dz %s  (2D)",
                num(xMinMicrons), num(xMaxMicrons), num(dxMicrons), nx,
                num(yMinMicrons), num(yMaxMicrons), num(dyMicrons), ny,
                num(zMinMicrons), num(zMaxMicrons), num(dzMicrons));
    }

    /** Write {@link #toXml()} to {@code out}. */
    public void writeXml(Path out) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write(toXml());
        }
    }

    /** Print integers without trailing zeros; otherwise a compact decimal. */
    private static String num(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.format(Locale.ROOT, "%d", (long) v);
        }
        return String.format(Locale.ROOT, "%s", v);
    }
}
