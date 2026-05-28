package io.github.drbergmanlab.biwt.core.sampling;

/**
 * The pixel-space shape of one sample. For the MVP {@code windowSizePx == stridePx == stepSizePx}
 * (non-overlapping square tiles). The two parameters are exposed separately so a future version
 * can support overlapping kernels without refactoring the sampler API.
 */
public record SamplingKernel(int windowSizePx, int stridePx) {
    public SamplingKernel {
        if (windowSizePx <= 0) {
            throw new IllegalArgumentException("windowSizePx must be positive (got " + windowSizePx + ")");
        }
        if (stridePx <= 0) {
            throw new IllegalArgumentException("stridePx must be positive (got " + stridePx + ")");
        }
    }

    /** Convenience: {@code window == stride == size}. */
    public static SamplingKernel nonOverlapping(int sizePx) {
        return new SamplingKernel(sizePx, sizePx);
    }
}
