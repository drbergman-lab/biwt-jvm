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

/**
 * Finds the ABM domain in a QuPath image — either an annotation with a specific name (default
 * {@code "abm_domain"}) or the whole image, depending on {@link DomainDetectionOptions}.
 *
 * <p>Headless: pure logic on the {@code ImageData} and {@code ImageServer}. No GUI imports.
 */
public final class DomainDetector {

    public AbmDomain detect(ImageData<BufferedImage> imageData, DomainDetectionOptions opts) {
        ImageServer<BufferedImage> server = imageData.getServer();
        PixelCalibration cal = server.getPixelCalibration();
        double pxW = cal.getPixelWidthMicrons();
        double pxH = cal.getPixelHeightMicrons();
        if (!(pxW > 0) || !(pxH > 0) || Double.isNaN(pxW) || Double.isNaN(pxH)) {
            throw new DomainException(
                    "Image has no pixel-size calibration. Set the pixel size in µm under "
                            + "'Image properties' before running BIWT.");
        }

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

        PathObject match = matches.get(0);
        ROI roi = match.getROI();
        if (!(roi instanceof RectangleROI)) {
            String type = roi == null ? "null" : roi.getRoiName();
            throw new NonRectangularDomainException(opts.annotationName(), type);
        }

        return fromAnnotation(opts.annotationName(), (RectangleROI) roi, pxW, pxH);
    }

    private static AbmDomain fromAnnotation(String name, RectangleROI roi,
                                            double pxW, double pxH) {
        double xMin = roi.getBoundsX();
        double yMin = roi.getBoundsY();
        double xMax = xMin + roi.getBoundsWidth();
        double yMax = yMin + roi.getBoundsHeight();
        return new AbmDomain(
                "annotation '" + name + "'",
                xMin, yMin, xMax, yMax,
                pxW, pxH,
                roi.getShape()
        );
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
