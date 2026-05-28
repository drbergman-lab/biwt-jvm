package io.github.drbergmanlab.biwt.core;

import io.github.drbergmanlab.biwt.core.coord.CoordinateOrigin;
import io.github.drbergmanlab.biwt.core.domain.DomainDetectionOptions;
import qupath.lib.images.ImageData;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/**
 * Everything BIWT needs to run end-to-end against one image: which annotation to use, the step
 * size in µm, the coordinate origin convention, and the list of substrates to sample.
 */
public record SamplingRequest(
        ImageData<BufferedImage> imageData,
        DomainDetectionOptions domainOptions,
        double stepSizeMicrons,
        CoordinateOrigin origin,
        List<SubstrateSpec> substrates
) {
    public SamplingRequest {
        Objects.requireNonNull(imageData, "imageData");
        Objects.requireNonNull(domainOptions, "domainOptions");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(substrates, "substrates");
        if (!(stepSizeMicrons > 0)) {
            throw new IllegalArgumentException("stepSizeMicrons must be positive (got " + stepSizeMicrons + ")");
        }
        if (substrates.isEmpty()) {
            throw new IllegalArgumentException("substrates must not be empty");
        }
        substrates = List.copyOf(substrates);
    }
}
