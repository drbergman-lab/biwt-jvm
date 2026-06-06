package io.github.drbergmanlab.biwt.core;

import io.github.drbergmanlab.biwt.core.coord.PhysiCellDomain;
import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.sampling.SamplingKernel;

/**
 * Output of {@link BiwtSampler#plan} — everything needed to describe the sampling work
 * <em>before</em> actually reading pixels. The GUI uses this to show a confirmation step
 * ("Grid will be 50 × 50 voxels, effective dx = 19.8 µm. Proceed?") before committing to
 * the expensive sampling pass.
 *
 * <p>{@code effectiveStepMicrons} may differ from {@code requestedStepMicrons} by up to one
 * pixel's worth of µm if the requested step didn't divide evenly into the image's pixel
 * calibration.
 */
public record SamplingPlan(
        AbmDomain domain,
        VoxelGrid grid,
        SamplingKernel kernel,
        double requestedStepMicrons,
        double effectiveStepMicrons
) {
    /** The PhysiCell domain bounds implied by this plan's grid (for the config XML). */
    public PhysiCellDomain physiCellDomain() {
        return PhysiCellDomain.of(grid);
    }
}
