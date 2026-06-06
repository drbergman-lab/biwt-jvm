/*
 * BIWT — sanity-check cell placement against the currently open image in QuPath.
 *
 * Prerequisite: the image must already have *detection objects* (cells) from a
 * segmentation, ideally classified. Get them however you like:
 *   - Extensions → StarDist / Cellpose / InstanSeg, or
 *   - Analyze → Cell detection (built-in watershed), then
 *   - classify (e.g. an object classifier, or Classify → ... ) so each cell has a class.
 *
 * Usage in QuPath's script editor (Automate → Show script editor):
 *   1. Open an image and run a segmentation so detections exist.
 *   2. (Optional) Draw an axis-aligned rectangle, name it "abm_domain"
 *      (right-click → Annotations → Set properties → Name). Else the whole image is used.
 *   3. Verify the image has a pixel size set (Image tab → Properties → Pixel width/height µm).
 *   4. Paste/open this file and click Run.
 *
 * Output: a CSV at ~/biwt-cells-output.csv plus a summary printed to the log,
 * including per-type counts and the first few rows so you can eyeball them.
 */

import io.github.drbergmanlab.biwt.core.BiwtCellPlacer
import io.github.drbergmanlab.biwt.core.cells.CellPlacementOptions
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions

import java.nio.file.Path

// ------------------------------- knobs --------------------------------------
boolean includeVolume       = true     // add a "volume" column (equivalent-sphere from area)
String  unclassifiedAs       = null    // null = drop unclassified; or e.g. "unclassified"
def     origin              = CoordinateOrigin.ABM_DOMAIN_CENTER
String  outputPath          = "~/biwt-cells-output.csv"
// ----------------------------------------------------------------------------

def imageData = getCurrentImageData()
if (imageData == null) {
    println "No image is open. Open one first."
    return
}

int nDetections = imageData.getHierarchy().getDetectionObjects().size()
if (nDetections == 0) {
    println "No detection objects found. Run a cell segmentation first."
    return
}

def options = new CellPlacementOptions(includeVolume, unclassifiedAs, [:])
def result = BiwtCellPlacer.create().run(
        imageData, DomainDetectionOptions.wholeImageFallback(), origin, options)

def expanded = outputPath.startsWith("~/")
        ? Path.of(System.getProperty("user.home"), outputPath.substring(2))
        : Path.of(outputPath)

println "BIWT cell placement:"
println "  Source:           ${result.domain().sourceDescription()}"
println "  Detections found: ${nDetections}"
println "  Cells placed:     ${result.count()}"
if (result.count() == 0) {
    println "  -> Nothing placed. Check the detections are classified and inside the domain,"
    println "     or set unclassifiedAs = \"unclassified\" above to keep unclassified cells."
    return
}

// Per-type counts.
def byType = result.cells().groupBy { it.type() }.collectEntries { k, v -> [k, v.size()] }
println "  By type:          ${byType}"

// First few rows, as they will appear in the CSV.
println "  First rows (x, y, z, type${includeVolume ? ', volume' : ''}):"
result.cells().take(5).each { c ->
    def base = "    ${c.xMicrons()}, ${c.yMicrons()}, ${c.zMicrons()}, ${c.type()}"
    println includeVolume ? "${base}, ${c.volumeMicrons3()}" : base
}

expanded.parent?.toFile()?.mkdirs()
result.writeCsv(expanded)
println "  Output:           ${expanded}"
