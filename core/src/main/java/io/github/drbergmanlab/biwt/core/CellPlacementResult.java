package io.github.drbergmanlab.biwt.core;

import io.github.drbergmanlab.biwt.core.cells.CellPlacementOptions;
import io.github.drbergmanlab.biwt.core.cells.CellRecord;
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.coord.PhysiCellDomain;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.export.CellCsvWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Output of {@link BiwtCellPlacer#run}. Holds the domain the cells were placed in, the origin used,
 * the placed cells (in ABM µm coordinates), and the options.
 */
public record CellPlacementResult(
        AbmDomain domain,
        CoordinateOrigin origin,
        List<CellRecord> cells,
        CellPlacementOptions options
) {
    /** Write the PhysiCell cell-IC CSV ({@code x,y,z,type[,volume]}). */
    public void writeCsv(Path out) throws IOException {
        new CellCsvWriter().write(out, cells, options.includeVolume());
    }

    /** Number of placed cells. */
    public int count() {
        return cells.size();
    }

    /**
     * The PhysiCell domain bounds for these cells — a <em>bounds-only</em> domain (the annotation's
     * {@code x_min/x_max/y_min/y_max}, no voxel size), since cell placement defines no voxel grid.
     */
    public PhysiCellDomain physiCellDomain() {
        return PhysiCellDomain.ofAnnotation(domain, origin);
    }
}
