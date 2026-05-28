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
 * {@link AbmDomain}'s annotation mask. For each voxel {@code (i, j)}:
 *
 * <ol>
 *   <li>Compute the pixel-space window of size {@link SamplingKernel#windowSizePx} starting at
 *       {@code (domain.xMinPx + i*stride, domain.yMinPx + j*stride)}.</li>
 *   <li>Clip the window to the image bounds (the grid may overhang the annotation by up to
 *       {@code stride - 1} pixels per side, which can push the window past the image edge).</li>
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
     * Sample the given channel into a {@code values[ny][nx]} array indexed by (row j, column i).
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

        // Anchor the grid at the annotation top-left (in pixel space).
        // Using ints because window/stride live in pixel space.
        int x0Px = (int) Math.round(domain.xMinPx());
        int y0Px = (int) Math.round(domain.yMinPx());

        double[][] values = new double[ny][nx];

        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int wx0 = x0Px + i * kernel.stridePx();
                int wy0 = y0Px + j * kernel.stridePx();
                int wx1 = wx0 + kernel.windowSizePx();
                int wy1 = wy0 + kernel.windowSizePx();

                // Clip the window to the image bounds.
                int cx0 = Math.max(0, wx0);
                int cy0 = Math.max(0, wy0);
                int cx1 = Math.min(imageW, wx1);
                int cy1 = Math.min(imageH, wy1);

                if (cx1 <= cx0 || cy1 <= cy0) {
                    values[j][i] = Double.NaN;
                    continue;
                }

                values[j][i] = sampleOneWindow(server, path, mask, channelIndex,
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
