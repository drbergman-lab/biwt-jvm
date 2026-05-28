package io.github.drbergmanlab.biwt.core;

import java.util.Objects;

/** What the user wants sampled: a name (column in the output CSV) and the source channel index. */
public record SubstrateSpec(String name, int channelIndex) {
    public SubstrateSpec {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("substrate name must not be blank");
        }
        if (channelIndex < 0) {
            throw new IllegalArgumentException("channelIndex must be >= 0 (got " + channelIndex + ")");
        }
    }
}
