package io.github.drbergmanlab.biwt.core.domain;

import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.PathObject;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/**
 * Finds the ABM domain in a QuPath image — either an annotation with a specific name (default
 * {@code "abm_domain"}) or the whole image, depending on {@link DomainDetectionOptions}.
 *
 * <p>Headless: pure logic on the {@code ImageData} and {@code ImageServer}. No GUI imports.
 */
public final class DomainDetector {

    public AbmDomain detect(ImageData<BufferedImage> imageData, DomainDetectionOptions opts) {
        ImageServer<BufferedImage> server = imageData.getServer();
        double[] px = pixelMicrons(server);
        double pxW = px[0];
        double pxH = px[1];

        List<PathObject> matches = imageData.getHierarchy().getAnnotationObjects().stream()
                .filter(o -> opts.annotationName().equals(o.getName()))
                .toList();

        if (matches.isEmpty()) {
            return switch (opts.fallback()) {
                case FAIL -> throw new AnnotationNotFoundException(opts.annotationName());
                case ASK_USER -> throw new AskUserRequiredException(opts.annotationName());
                case WHOLE_IMAGE -> fromWholeImage(server, pxW, pxH);
            };
        }

        if (matches.size() > 1) {
            throw new DomainException("Multiple annotations named '" + opts.annotationName()
                    + "' found (" + matches.size() + "). Keep exactly one.");
        }

        return fromAnnotation(imageData, matches.get(0));
    }

    /**
     * Build the domain from a specific user-chosen annotation — used by the GUI when there is no
     * {@code abm_domain} (offer to use another annotation) or more than one candidate (let the user
     * pick which). The annotation must be an axis-aligned rectangle.
     */
    public AbmDomain fromAnnotation(ImageData<BufferedImage> imageData, PathObject annotation) {
        Objects.requireNonNull(annotation, "annotation");
        double[] px = pixelMicrons(imageData.getServer());
        ROI roi = annotation.getROI();
        String displayName = annotation.getName() == null || annotation.getName().isBlank()
                ? "(unnamed)" : annotation.getName();
        if (!(roi instanceof RectangleROI rect)) {
            String type = roi == null ? "null" : roi.getRoiName();
            throw new NonRectangularDomainException(displayName, type);
        }
        double xMin = rect.getBoundsX();
        double yMin = rect.getBoundsY();
        return new AbmDomain(
                "annotation '" + displayName + "'",
                xMin, yMin, xMin + rect.getBoundsWidth(), yMin + rect.getBoundsHeight(),
                px[0], px[1],
                rect.getShape()
        );
    }

    /** Pixel size in µm {@code {width, height}}; throws if the image has no calibration. */
    private static double[] pixelMicrons(ImageServer<BufferedImage> server) {
        PixelCalibration cal = server.getPixelCalibration();
        double pxW = cal.getPixelWidthMicrons();
        double pxH = cal.getPixelHeightMicrons();
        if (!(pxW > 0) || !(pxH > 0) || Double.isNaN(pxW) || Double.isNaN(pxH)) {
            throw new DomainException(
                    "Image has no pixel-size calibration. Set the pixel size in µm under "
                            + "'Image properties' before running BIWT.");
        }
        return new double[] {pxW, pxH};
    }

    private static AbmDomain fromWholeImage(ImageServer<BufferedImage> server, double pxW, double pxH) {
        int w = server.getWidth();
        int h = server.getHeight();
        return new AbmDomain(
                "whole image",
                0, 0, w, h,
                pxW, pxH,
                new Rectangle(0, 0, w, h)
        );
    }
}
