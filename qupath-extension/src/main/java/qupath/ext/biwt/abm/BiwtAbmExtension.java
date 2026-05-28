package qupath.ext.biwt.abm;

import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

public class BiwtAbmExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(BiwtAbmExtension.class);

    private static final String EXTENSION_NAME = "BIWT";
    private static final String EXTENSION_DESCRIPTION =
            "Sample digital pathology images on a regular grid and export substrate initial conditions for PhysiCell.";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");

    private boolean isInstalled = false;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.debug("{} already installed", EXTENSION_NAME);
            return;
        }
        isInstalled = true;

        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        MenuItem sampleItem = new MenuItem("Sample substrates…");
        sampleItem.setOnAction(e -> new BiwtAbmCommand(qupath).run());
        menu.getItems().add(sampleItem);
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }
}
