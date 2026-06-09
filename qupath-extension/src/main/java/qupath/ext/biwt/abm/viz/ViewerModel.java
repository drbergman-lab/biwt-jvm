package qupath.ext.biwt.abm.viz;

import io.github.drbergmanlab.biwt.core.CellPlacementResult;
import io.github.drbergmanlab.biwt.core.SamplingResult;
import io.github.drbergmanlab.biwt.core.cells.CellRecord;
import io.github.drbergmanlab.biwt.core.coord.PhysiCellDomain;
import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.viz.CellGeometry;
import javafx.scene.paint.Color;

import java.util.List;
import java.util.Map;

/**
 * The immutable data the {@link ResultsViewer} renders: cells, an optional voxel grid, the substrate
 * fields, the default world rectangle (µm) to show, and optional fixed type→color overrides.
 *
 * <p>Cells and substrates share one ABM µm frame, so {@code (xMin..yMax)} is the union extent the
 * viewer opens at; the user can then zoom via the limit boxes. Build one with {@link #of} from the
 * in-memory build results, or {@link #ofData} when reconstructing from saved CSVs.
 *
 * @param cells       placed cells (possibly empty)
 * @param grid        the substrate voxel grid, or {@code null} when there are no substrates
 * @param substrates  the substrate fields (possibly empty)
 * @param xMin        default world left bound (µm)
 * @param xMax        default world right bound (µm)
 * @param yMin        default world bottom bound (µm)
 * @param yMax        default world top bound (µm)
 * @param typeColors  optional fixed colors per cell-type name, or {@code null} for auto assignment
 */
public record ViewerModel(
        List<CellRecord> cells,
        VoxelGrid grid,
        List<NamedSubstrate> substrates,
        double xMin, double xMax, double yMin, double yMax,
        Map<String, Color> typeColors
) {

    public boolean hasCells() {
        return cells != null && !cells.isEmpty();
    }

    public boolean hasSubstrates() {
        return substrates != null && !substrates.isEmpty() && grid != null;
    }

    /**
     * Build a model from the in-memory build outputs. Either argument may be {@code null}; at least
     * one must be non-{@code null}. The default world rectangle is the grid bounds when substrates
     * exist, else the cell domain's bounds, else the cells' bounding box (padded for their radii).
     */
    public static ViewerModel of(SamplingResult substrateResult, CellPlacementResult cellResult) {
        VoxelGrid grid = substrateResult == null ? null : substrateResult.grid();
        List<NamedSubstrate> subs = substrateResult == null ? List.of() : substrateResult.substrates();
        List<CellRecord> cells = cellResult == null ? List.of() : cellResult.cells();

        double[] bounds;
        if (grid != null) {
            bounds = gridBounds(grid);
        } else if (cellResult != null) {
            PhysiCellDomain d = cellResult.physiCellDomain();
            bounds = new double[] {d.xMinMicrons(), d.xMaxMicrons(), d.yMinMicrons(), d.yMaxMicrons()};
        } else {
            bounds = cellBounds(cells);
        }
        return new ViewerModel(cells, grid, subs, bounds[0], bounds[1], bounds[2], bounds[3], null);
    }

    /**
     * Build a model from data reconstructed from CSVs (no domain object). The default world
     * rectangle is the grid bounds when present, else the cells' padded bounding box.
     */
    public static ViewerModel ofData(VoxelGrid grid, List<NamedSubstrate> subs, List<CellRecord> cells) {
        List<NamedSubstrate> safeSubs = subs == null ? List.of() : subs;
        List<CellRecord> safeCells = cells == null ? List.of() : cells;
        double[] bounds = grid != null ? gridBounds(grid) : cellBounds(safeCells);
        return new ViewerModel(safeCells, grid, safeSubs, bounds[0], bounds[1], bounds[2], bounds[3], null);
    }

    private static double[] gridBounds(VoxelGrid g) {
        return new double[] {g.xMinMicrons(), g.xMaxMicrons(), g.yMinMicrons(), g.yMaxMicrons()};
    }

    /** Bounding box of the cells, expanded by each cell's draw radius plus a 5% margin. */
    private static double[] cellBounds(List<CellRecord> cells) {
        if (cells == null || cells.isEmpty()) {
            return new double[] {-1, 1, -1, 1}; // nothing to show — a unit box keeps the transform valid
        }
        double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
        double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        for (CellRecord c : cells) {
            double r = CellGeometry.radiusMicronsForCell(c.volumeMicrons3());
            xMin = Math.min(xMin, c.xMicrons() - r);
            xMax = Math.max(xMax, c.xMicrons() + r);
            yMin = Math.min(yMin, c.yMicrons() - r);
            yMax = Math.max(yMax, c.yMicrons() + r);
        }
        double padX = Math.max((xMax - xMin) * 0.05, 1e-6);
        double padY = Math.max((yMax - yMin) * 0.05, 1e-6);
        return new double[] {xMin - padX, xMax + padX, yMin - padY, yMax + padY};
    }
}
