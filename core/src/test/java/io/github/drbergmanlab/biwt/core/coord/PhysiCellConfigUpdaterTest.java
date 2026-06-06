package io.github.drbergmanlab.biwt.core.coord;

import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PhysiCellConfigUpdaterTest {

    /** Canonical 310×410/20/top-left domain → x∈[0,320], y∈[-410,10]. */
    private static PhysiCellDomain canonical() {
        return PhysiCellDomain.of(VoxelGrid.cover(310.0, 410.0, 20.0, CoordinateOrigin.ABM_DOMAIN_TOP_LEFT));
    }

    private static final String CONFIG = """
            <PhysiCell_settings version="1.14.0">
            \t<domain>
            \t\t<x_min units="micron">-1000</x_min>
            \t\t<x_max units="micron">1000</x_max>
            \t\t<y_min units="micron">-1000</y_min>
            \t\t<y_max units="micron">1000</y_max>
            \t\t<z_min units="micron">-10</z_min>
            \t\t<z_max units="micron">10</z_max>
            \t\t<dx units="micron">20</dx>
            \t\t<dy units="micron">20</dy>
            \t\t<dz units="micron">20</dz>
            \t\t<use_2D>true</use_2D>
            \t</domain>
            \t<overall><max_time units="min">1440</max_time></overall>
            </PhysiCell_settings>
            """;

    @Test
    void rewritesBoundsAndPreservesAttributesAndRestOfFile() {
        String out = PhysiCellConfigUpdater.applyToXml(CONFIG, canonical());

        // Values updated, units attribute preserved.
        assertTrue(out.contains("<x_min units=\"micron\">0</x_min>"), out);
        assertTrue(out.contains("<x_max units=\"micron\">320</x_max>"), out);
        assertTrue(out.contains("<y_min units=\"micron\">-410</y_min>"), out);
        assertTrue(out.contains("<y_max units=\"micron\">10</y_max>"), out);
        assertTrue(out.contains("<dx units=\"micron\">20</dx>"), out);
        // Everything outside <domain> untouched.
        assertTrue(out.contains("<PhysiCell_settings version=\"1.14.0\">"), out);
        assertTrue(out.contains("<overall><max_time units=\"min\">1440</max_time></overall>"), out);
        assertTrue(out.contains("<use_2D>true</use_2D>"), out);
    }

    @Test
    void appendsMissingTag() {
        String noDz = CONFIG.replace("\t\t<dz units=\"micron\">20</dz>\n", "");
        String out = PhysiCellConfigUpdater.applyToXml(noDz, canonical());
        assertTrue(out.contains("<dz>20</dz>"), out);
        // Still inside the domain block (before </domain>).
        assertTrue(out.indexOf("<dz>20</dz>") < out.indexOf("</domain>"), out);
    }

    @Test
    void boundsOnlyDomainUpdatesOnlyXyAndLeavesVoxelSizesAndZ() {
        AbmDomain ann = new AbmDomain("t", 100, 200, 410, 610, 1.0, 1.0, new Rectangle(100, 200, 310, 410));
        PhysiCellDomain bounds = PhysiCellDomain.ofAnnotation(ann, CoordinateOrigin.ABM_DOMAIN_CENTER);

        String out = PhysiCellConfigUpdater.applyToXml(CONFIG, bounds);

        // x/y bounds updated…
        assertTrue(out.contains("<x_min units=\"micron\">-155</x_min>"), out);
        assertTrue(out.contains("<y_max units=\"micron\">205</y_max>"), out);
        // …while voxel sizes and z bounds are left exactly as the user had them.
        assertTrue(out.contains("<dx units=\"micron\">20</dx>"), out);
        assertTrue(out.contains("<z_min units=\"micron\">-10</z_min>"), out);
    }

    @Test
    void throwsWhenNoDomainElement() {
        String noDomain = "<PhysiCell_settings>\n\t<overall/>\n</PhysiCell_settings>\n";
        assertThrows(IllegalArgumentException.class,
                () -> PhysiCellConfigUpdater.applyToXml(noDomain, canonical()));
    }

    @Test
    void updateDomainWritesBackupAndRewritesFile(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("PhysiCell_settings.xml");
        Files.writeString(config, CONFIG);

        Path backup = PhysiCellConfigUpdater.updateDomain(config, canonical());

        assertTrue(Files.exists(backup));
        assertEquals(CONFIG, Files.readString(backup));               // backup is the original
        assertTrue(Files.readString(config).contains("<x_max units=\"micron\">320</x_max>"));
    }
}
