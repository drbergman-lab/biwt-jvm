package io.github.drbergmanlab.biwt.core.cells;

import java.util.Map;
import java.util.Objects;

/**
 * Options for {@link CellExtractor}.
 *
 * @param includeVolume       when true, derive a {@code volume} (µm³) per cell from its segmented
 *                            area (equivalent-sphere) and emit it as a CSV column
 * @param unclassifiedTypeName what to do with detections that have no QuPath classification:
 *                            {@code null} drops them; a non-blank name assigns that type
 * @param typeNameOverrides   optional map from QuPath {@code PathClass} name → PhysiCell cell-type
 *                            name; names not present map to themselves (identity)
 */
public record CellPlacementOptions(
        boolean includeVolume,
        String unclassifiedTypeName,
        Map<String, String> typeNameOverrides
) {
    public CellPlacementOptions {
        typeNameOverrides = typeNameOverrides == null ? Map.of() : Map.copyOf(typeNameOverrides);
        if (unclassifiedTypeName != null && unclassifiedTypeName.isBlank()) {
            throw new IllegalArgumentException("unclassifiedTypeName must be null (drop) or non-blank");
        }
    }

    /** Defaults: no volume column, drop unclassified detections, identity type names. */
    public static CellPlacementOptions defaults() {
        return new CellPlacementOptions(false, null, Map.of());
    }

    /** This, but with {@code includeVolume} toggled. */
    public CellPlacementOptions withVolume(boolean include) {
        return new CellPlacementOptions(include, unclassifiedTypeName, typeNameOverrides);
    }

    /** This, but with unclassified detections assigned {@code name} (or dropped if {@code null}). */
    public CellPlacementOptions withUnclassifiedAs(String name) {
        return new CellPlacementOptions(includeVolume, name, typeNameOverrides);
    }

    /** Resolve a QuPath class name to its PhysiCell type name (identity unless overridden). */
    public String resolveType(String pathClassName) {
        Objects.requireNonNull(pathClassName, "pathClassName");
        return typeNameOverrides.getOrDefault(pathClassName, pathClassName);
    }
}
