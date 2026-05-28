package io.github.drbergmanlab.biwt.core;

import io.github.drbergmanlab.biwt.core.coord.VoxelGrid;
import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import io.github.drbergmanlab.biwt.core.domain.DomainDetector;
import io.github.drbergmanlab.biwt.core.domain.DomainException;
import io.github.drbergmanlab.biwt.core.export.NamedSubstrate;
import io.github.drbergmanlab.biwt.core.sampling.SamplingKernel;
import io.github.drbergmanlab.biwt.core.sampling.SubstrateSampler;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level entry point — wires {@link DomainDetector}, step-size reconciliation,
 * {@link VoxelGrid} construction, and {@link SubstrateSampler} together. Headless: a Groovy
 * script can do the entire export in ~10 lines:
 *
 * <pre>{@code
 * def request = new SamplingRequest(
 *     imageData,
 *     DomainDetectionOptions.wholeImageFallback(),
 *     20.0,                              // µm
 *     CoordinateOrigin.IMAGE_CENTER,
 *     [new SubstrateSpec("oxygen", 0), new SubstrateSpec("ecm", 1)]
 * )
 * def result = BiwtSampler.create().run(request)
 * result.writeCsv(java.nio.file.Path.of("/tmp/substrates.csv"))
 * }</pre>
 *
 * <p>The MVP enforces square pixels (throws if {@code pixelWidthMicrons != pixelHeightMicrons}
 * within a tiny epsilon). Non-square pixels become reachable when the sampler supports
 * per-axis strides.
 */
public final class BiwtSampler {

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

    public SamplingResult run(SamplingRequest request) throws IOException {
        ImageData<BufferedImage> imageData = request.imageData();
        ImageServer<BufferedImage> server = imageData.getServer();

        // 1) Discover the domain (annotation or whole image).
        AbmDomain domain = detector.detect(imageData, request.domainOptions());

        // 2) Enforce square pixels for MVP.
        if (Math.abs(domain.pixelWidthMicrons() - domain.pixelHeightMicrons()) > SQUARE_PIXEL_TOLERANCE) {
            throw new DomainException("MVP requires square pixels; image has "
                    + domain.pixelWidthMicrons() + " x " + domain.pixelHeightMicrons() + " µm.");
        }
        double pxMicrons = domain.pixelWidthMicrons();

        // 3) Reconcile the requested step size (µm) with the pixel grid.
        int stridePx = Math.max(1, (int) Math.round(request.stepSizeMicrons() / pxMicrons));
        double effectiveStepMicrons = stridePx * pxMicrons;

        // 4) Build the voxel grid covering the annotation with the effective step size.
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
                request.origin()
        );

        // 5) Sample each substrate.
        SamplingKernel kernel = SamplingKernel.nonOverlapping(stridePx);
        List<NamedSubstrate> namedSubstrates = new ArrayList<>(request.substrates().size());
        for (SubstrateSpec spec : request.substrates()) {
            double[][] values = sampler.sample(server, domain, grid.nx(), grid.ny(), kernel, spec.channelIndex());
            namedSubstrates.add(new NamedSubstrate(spec.name(), values));
        }

        return new SamplingResult(domain, grid, request.stepSizeMicrons(), effectiveStepMicrons, namedSubstrates);
    }
}
