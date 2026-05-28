package io.github.drbergmanlab.biwt.core;

import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.export.SubstrateCsvWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Output of {@link BiwtSampler#run}. Holds the discovered domain, the grid (with effective step
 * size after µm→px reconciliation), and the sampled substrate fields.
 *
 * <p>{@code effectiveStepMicrons} may differ from the requested step size by up to one pixel's
 * worth of µm if the requested step didn't divide evenly into the image's pixel calibration.
 */
public record SamplingResult(
        AbmDomain domain,
        VoxelGrid grid,
        double requestedStepMicrons,
        double effectiveStepMicrons,
        List<NamedSubstrate> substrates
) {
    public void writeCsv(Path out) throws IOException {
        new SubstrateCsvWriter().write(out, grid, substrates);
    }
}
