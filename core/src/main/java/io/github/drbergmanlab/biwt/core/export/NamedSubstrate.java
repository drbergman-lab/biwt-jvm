package io.github.drbergmanlab.biwt.core.export;

import java.util.Objects;

/**
 * A named substrate together with its sampled values on the voxel grid.
 *
 * <p>{@code values[j][i]} is the substrate concentration at voxel column {@code i},
 * row {@code j} (matrix-style indexing, row-major). The dimensions must match the
 * {@link io.github.drbergmanlab.biwt.core.coord.VoxelGrid} passed to the CSV writer:
 * {@code values.length == grid.ny()} and {@code values[0].length == grid.nx()}.
 */
public record NamedSubstrate(String name, double[][] values) {
    public NamedSubstrate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(values, "values");
        if (name.isBlank()) {
            throw new IllegalArgumentException("substrate name must not be blank");
        }
        if (values.length == 0 || values[0] == null || values[0].length == 0) {
            throw new IllegalArgumentException("substrate values must be a non-empty 2D array");
        }
        int cols = values[0].length;
        for (int j = 0; j < values.length; j++) {
            if (values[j] == null || values[j].length != cols) {
                throw new IllegalArgumentException(
                        "ragged substrate values: row " + j + " has length "
                                + (values[j] == null ? "null" : values[j].length)
                                + ", expected " + cols);
            }
        }
    }
}
