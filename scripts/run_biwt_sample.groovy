/*
 * BIWT — sanity-check the core sampler against the currently open image in QuPath.
 *
 * Usage in QuPath's script editor (Automate → Show script editor):
 *   1. Open an image (e.g. test_data/small.tif).
 *   2. (Optional) Draw an axis-aligned rectangle, right-click → Annotations → Set properties → Name it "abm_domain".
 *      If you skip this, the script uses the whole image.
 *   3. Verify the image has a pixel size set (Image tab → Properties → Pixel width/height in µm).
 *      Without a calibration the script will throw with a clear message.
 *   4. Open this file in the script editor (or paste) and click Run.
 *
 * Output: a CSV at ~/biwt-test-output.csv plus a summary printed to the script-editor log.
 */

import io.github.drbergmanlab.biwt.core.BiwtSampler
import io.github.drbergmanlab.biwt.core.SamplingRequest
import io.github.drbergmanlab.biwt.core.SubstrateSpec
import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions

import java.nio.file.Path

// ------------------------------- knobs --------------------------------------
double stepSizeMicrons = 20.0       // ABM voxel size in µm
int    channelIndex    = 0          // which channel becomes the "intensity" substrate
String outputName      = "biwt-test-output.csv"
// ----------------------------------------------------------------------------

def imageData = getCurrentImageData()
if (imageData == null) {
    println "No image is open. Open one first."
    return
}

// Look for an annotation named "abm_domain"; fall back to the whole image if absent.
def options = DomainDetectionOptions.wholeImageFallback()

def request = new SamplingRequest(
    imageData,
    options,
    stepSizeMicrons,
    CoordinateOrigin.IMAGE_CENTER,
    [new SubstrateSpec("intensity", channelIndex)]
)

def result = BiwtSampler.create().run(request)

def outPath = Path.of(System.getProperty("user.home"), outputName)
result.writeCsv(outPath)

println "BIWT sample complete."
println "  Source:      ${result.domain().sourceDescription()}"
println "  Grid:        ${result.grid().nx()} × ${result.grid().ny()} voxels"
println "  Step (req):  ${result.requestedStepMicrons()} µm"
println "  Step (eff):  ${result.effectiveStepMicrons()} µm"
println "  Pixel size:  ${result.domain().pixelWidthMicrons()} µm"
println "  Substrates:  ${result.substrates().collect { it.name() }}"
println "  Output:      ${outPath}"
