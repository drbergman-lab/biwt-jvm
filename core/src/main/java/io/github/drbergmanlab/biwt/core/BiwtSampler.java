package io.github.drbergmanlab.biwt.core;

import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions;
import io.github.drbergmanlab.biwt.core.domain.DomainDetector;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.sampling.SamplingKernel;
import io.github.drbergmanlab.biwt.core.sampling.SubstrateSampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level entry point — wires {@link DomainDetector}, step-size reconciliation,
 * {@link VoxelGrid} construction, and {@link SubstrateSampler} together.
 *
 * <p>Two ways to use it:
 * <ul>
 *   <li>{@link #run(SamplingRequest)} — one-shot, headless. Best from Groovy:
 *       <pre>{@code
 * def request = new SamplingRequest(imageData, DomainDetectionOptions.wholeImageFallback(),
 *     20.0, CoordinateOrigin.IMAGE_CENTER, [new SubstrateSpec("oxygen", 0)])
 * def result = BiwtSampler.create().run(request)
 * result.writeCsv(java.nio.file.Path.of("/tmp/substrates.csv"))
 *       }</pre>
 *   </li>
 *   <li>{@link #plan} + {@link #sample(ImageData, SamplingPlan, List)} — two-phase, for the GUI:
 *       the wizard calls {@code plan} to show "Grid will be N × M voxels, effective dx = X µm.
 *       Proceed?" before committing to the expensive sampling pass.</li>
 * </ul>
 *
 * <p>The MVP enforces square pixels (throws if {@code pixelWidthMicrons != pixelHeightMicrons}
 * within a tiny epsilon). Non-square pixels become reachable when {@link SubstrateSampler}
 * supports per-axis strides.
 */
public final class BiwtSampler {

    private static final Logger logger = LoggerFactory.getLogger(BiwtSampler.class);
    private static final double SQUARE_PIXEL_TOLERANCE = 1e-9;

    private final DomainDetector detector;
    private final SubstrateSampler sampler;

    private BiwtSampler(DomainDetector detector, SubstrateSampler sampler) {
        this.detector = detector;
        this.sampler = sampler;
    }

    public static BiwtSampler create() {
        return new BiwtSampler(new DomainDetector(), new SubstrateSampler());
    }

    /**
     * Detect the domain, reconcile the µm step size with the pixel calibration, and build the
     * voxel grid. Does not touch image pixels beyond reading metadata.
     */
    public SamplingPlan plan(ImageData<BufferedImage> imageData,
                             DomainDetectionOptions domainOptions,
                             double requestedStepMicrons,
                             CoordinateOrigin origin) {
        if (!(requestedStepMicrons > 0)) {
            throw new IllegalArgumentException("requestedStepMicrons must be positive (got " + requestedStepMicrons + ")");
        }
        ImageServer<BufferedImage> server = imageData.getServer();

        AbmDomain domain = detector.detect(imageData, domainOptions);

        if (Math.abs(domain.pixelWidthMicrons() - domain.pixelHeightMicrons()) > SQUARE_PIXEL_TOLERANCE) {
            throw new DomainException("MVP requires square pixels; image has "
                    + domain.pixelWidthMicrons() + " x " + domain.pixelHeightMicrons() + " µm.");
        }
        double pxMicrons = domain.pixelWidthMicrons();

        int stridePx = Math.max(1, (int) Math.round(requestedStepMicrons / pxMicrons));
        double effectiveStepMicrons = stridePx * pxMicrons;

        double imageWidthMicrons = server.getWidth() * pxMicrons;
        double imageHeightMicrons = server.getHeight() * pxMicrons;
        VoxelGrid grid = VoxelGrid.cover(
                domain.widthMicrons(),
                domain.heightMicrons(),
                effectiveStepMicrons,
                imageWidthMicrons,
                imageHeightMicrons,
                domain.xMinPx() * pxMicrons,
                domain.yMinPx() * pxMicrons,
                origin
        );

        SamplingKernel kernel = SamplingKernel.nonOverlapping(stridePx);

        return new SamplingPlan(domain, grid, kernel, requestedStepMicrons, effectiveStepMicrons);
    }

    /**
     * Run the actual pixel-reading pass against a previously-computed plan and an arbitrary
     * server. Pass the original {@code imageData.getServer()} for raw channels, or a
     * {@link qupath.lib.images.servers.TransformedServerBuilder} output for derived channels
     * such as color-deconvolution H/E/Residual.
     *
     * <p>Note this is the only method that touches image pixels.
     */
    public SamplingResult sample(ImageServer<BufferedImage> server,
                                 SamplingPlan plan,
                                 List<SubstrateSpec> substrates) throws IOException {
        if (substrates.isEmpty()) {
            throw new IllegalArgumentException("substrates must not be empty");
        }

        List<NamedSubstrate> namedSubstrates = new ArrayList<>(substrates.size());
        long totalStart = System.nanoTime();
        for (SubstrateSpec spec : substrates) {
            long t0 = System.nanoTime();
            double[][] values = sampler.sample(server, plan.domain(),
                    plan.grid().nx(), plan.grid().ny(), plan.kernel(), spec.channelIndex());
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            logger.info("Sampled '{}' (channel {}) in {} ms — grid {}×{}",
                    spec.name(), spec.channelIndex(), elapsedMs, plan.grid().nx(), plan.grid().ny());
            namedSubstrates.add(new NamedSubstrate(spec.name(), values));
        }
        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;
        logger.info("Sampled {} substrate(s) in {} ms total", substrates.size(), totalMs);

        return new SamplingResult(
                plan.domain(), plan.grid(),
                plan.requestedStepMicrons(), plan.effectiveStepMicrons(),
                namedSubstrates);
    }

    /** One-shot convenience: {@code plan} + {@code sample} using the image's raw server channels. */
    public SamplingResult run(SamplingRequest request) throws IOException {
        SamplingPlan p = plan(request.imageData(), request.domainOptions(),
                request.stepSizeMicrons(), request.origin());
        return sample(request.imageData().getServer(), p, request.substrates());
    }
}
