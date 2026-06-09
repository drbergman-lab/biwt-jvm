package qupath.ext.biwt.abm;

import io.github.drbergmanlab.biwt.core.cells.CellRecord;
import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.viz.ResultsCsvLoader;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biwt.abm.viz.ResultsViewer;
import qupath.ext.biwt.abm.viz.ViewerModel;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * <b>Extensions → BIWT → View results…</b> — open a saved BIWT export in the interactive
 * {@link ResultsViewer} without re-running the build.
 *
 * <p>The user picks a substrates or cells CSV; the command loads it and, when a matching sibling
 * (same base name, the other half of a {@code Build initial conditions…} export) sits next to it,
 * loads that too so cells and substrates show together in one frame.
 */
public final class BiwtViewCommand {

    private static final Logger logger = LoggerFactory.getLogger(BiwtViewCommand.class);
    private static final String TITLE = "BIWT — View results";

    private final QuPathGUI qupath;

    public BiwtViewCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** Entry point — call from the menu-item action handler on the JavaFX thread. */
    public void run() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open a BIWT CSV (substrates or cells)");
        fc.setInitialDirectory(WizardSupport.defaultOutputDirectory(null));
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        Stage owner = qupath == null ? null : qupath.getStage();
        File file = fc.showOpenDialog(owner);
        if (file == null) {
            return;
        }

        try {
            ViewerModel model = loadModel(file.toPath());
            ResultsViewer.show(owner, TITLE, model);
        } catch (IOException | RuntimeException e) {
            logger.error("Failed to load BIWT results from {}", file, e);
            Dialogs.showErrorMessage(TITLE, "Could not read results: " + e.getMessage());
        }
    }

    /** Load the chosen CSV plus any matching sibling into a viewer model. */
    private ViewerModel loadModel(Path chosen) throws IOException {
        VoxelGrid grid = null;
        List<NamedSubstrate> substrates = List.of();
        List<CellRecord> cells = List.of();

        if (ResultsCsvLoader.isCellsCsv(chosen)) {
            cells = ResultsCsvLoader.loadCells(chosen);
            Path sibling = sibling(chosen, "-cells.csv", "-substrates.csv");
            if (sibling != null) {
                ResultsCsvLoader.SubstrateData data = ResultsCsvLoader.loadSubstrates(sibling);
                grid = data.grid();
                substrates = data.substrates();
            }
        } else {
            ResultsCsvLoader.SubstrateData data = ResultsCsvLoader.loadSubstrates(chosen);
            grid = data.grid();
            substrates = data.substrates();
            Path sibling = sibling(chosen, "-substrates.csv", "-cells.csv");
            if (sibling != null) {
                cells = ResultsCsvLoader.loadCells(sibling);
            }
        }
        return ViewerModel.ofData(grid, substrates, cells);
    }

    /**
     * The companion file for {@code chosen}: if its name ends with {@code fromSuffix}, the sibling
     * with {@code toSuffix} in its place, when that file exists. Returns {@code null} otherwise.
     */
    private static Path sibling(Path chosen, String fromSuffix, String toSuffix) {
        String name = chosen.getFileName().toString();
        if (!name.toLowerCase().endsWith(fromSuffix)) {
            return null;
        }
        String base = name.substring(0, name.length() - fromSuffix.length());
        Path candidate = chosen.resolveSibling(base + toSuffix);
        return Files.exists(candidate) ? candidate : null;
    }
}
