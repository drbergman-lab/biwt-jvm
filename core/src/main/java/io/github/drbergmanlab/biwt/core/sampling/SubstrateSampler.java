package io.github.drbergmanlab.biwt.core.sampling;

import io.github.drbergmanlab.biwt.core.domain.AbmDomain;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.regions.RegionRequest;

import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

/**
 * Samples one channel of an {@link ImageServer} on a regular grid of windows clipped to the
 * {@link AbmDomain}'s annotation mask. Windows are anchored at the annotation's <b>bottom-left</b>
 * pixel corner and tiled right and up, matching the PhysiCell mesh: voxel column {@code i} grows
 * rightward from {@code xMinPx}, and voxel row {@code k = 0} is the <em>bottom</em> row (largest
 * pixel y, smallest PhysiCell math y), growing upward. That bottom anchoring is where the image
 * row→math-y flip lives. For each voxel {@code (i, k)}:
 *
 * <ol>
 *   <li>Compute the pixel-space window of size {@link SamplingKernel#windowSizePx}: x in
 *       {@code [xMinPx + i*stride, …]}, y ending at {@code yMaxPx - k*stride} (so row 0 sits on the
 *       annotation bottom). The overhang (partial voxels) therefore lands at the top and right.</li>
 *   <li>Clip the window to the image bounds.</li>
 *   <li>Read that clipped tile via {@link ImageServer#readRegion(RegionRequest)} at downsample 1.0.</li>
 *   <li>Average the selected channel's pixel values, including only pixels whose centers lie inside
 *       {@code domain.clipMaskPx} (the annotation outline).</li>
 *   <li>If no pixel qualifies (empty intersection or empty clipped window), the cell is {@code NaN}.</li>
 * </ol>
 *
 * <p>Headless — no GUI imports.
 */
public final class SubstrateSampler {

    /**
     * Sample the given channel into a {@code values[ny][nx]} array indexed by (row k, column i),
     * where {@code k = 0} is the bottom row (PhysiCell mesh index) and {@code i = 0} the left column.
     *
     * @param nx number of voxel columns (x); the caller usually passes {@code grid.nx()}
     * @param ny number of voxel rows (y); the caller usually passes {@code grid.ny()}
     */
    public double[][] sample(
            ImageServer<BufferedImage> server,
            AbmDomain domain,
            int nx,
            int ny,
            SamplingKernel kernel,
            int channelIndex
    ) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(kernel, "kernel");
        if (nx <= 0 || ny <= 0) {
            throw new IllegalArgumentException("nx and ny must be positive (got nx=" + nx + ", ny=" + ny + ")");
        }
        if (channelIndex < 0 || channelIndex >= server.nChannels()) {
            throw new IllegalArgumentException("channelIndex " + channelIndex
                    + " out of range [0, " + server.nChannels() + ")");
        }

        int imageW = server.getWidth();
        int imageH = server.getHeight();
        Shape mask = domain.clipMaskPx();
        String path = server.getPath();

        // Anchor at the annotation's bottom-left pixel corner: x grows right from xMinPx, y is
        // tiled upward from the bottom edge yMaxPx so row k = 0 sits on the annotation bottom.
        // (Window/stride live in pixel space, so ints.)
        int xLeftPx = (int) Math.round(domain.xMinPx());
        int yBottomPx = (int) Math.round(domain.yMaxPx());

        double[][] values = new double[ny][nx];

        for (int k = 0; k < ny; k++) {
            for (int i = 0; i < nx; i++) {
                int wx0 = xLeftPx + i * kernel.stridePx();
                int wx1 = wx0 + kernel.windowSizePx();
                int wy1 = yBottomPx - k * kernel.stridePx();
                int wy0 = wy1 - kernel.windowSizePx();

                // Clip the window to the image bounds.
                int cx0 = Math.max(0, wx0);
                int cy0 = Math.max(0, wy0);
                int cx1 = Math.min(imageW, wx1);
                int cy1 = Math.min(imageH, wy1);

                if (cx1 <= cx0 || cy1 <= cy0) {
                    values[k][i] = Double.NaN;
                    continue;
                }

                values[k][i] = sampleOneWindow(server, path, mask, channelIndex,
                        cx0, cy0, cx1 - cx0, cy1 - cy0);
            }
        }

        return values;
    }

    /** Mean of the channel within the window-image intersection further intersected with {@code mask}. */
    private static double sampleOneWindow(ImageServer<BufferedImage> server, String path,
                                          Shape mask, int channelIndex,
                                          int x, int y, int w, int h) throws IOException {
        // Fast reject: if the bounding box doesn't even touch the mask, skip the read.
        if (!mask.intersects(x, y, w, h)) {
            return Double.NaN;
        }

        RegionRequest request = RegionRequest.createInstance(path, 1.0, x, y, w, h);
        BufferedImage tile = server.readRegion(request);
        if (tile == null) {
            return Double.NaN;
        }

        // If the mask fully contains the window, skip the per-pixel contains() test.
        boolean maskCoversWindow = mask.contains(x, y, w, h);

        double sum = 0;
        long count = 0;
        for (int ty = 0; ty < tile.getHeight(); ty++) {
            int imgY = y + ty;
            for (int tx = 0; tx < tile.getWidth(); tx++) {
                int imgX = x + tx;
                if (maskCoversWindow || mask.contains(imgX + 0.5, imgY + 0.5)) {
                    sum += tile.getRaster().getSampleDouble(tx, ty, channelIndex);
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }
}
