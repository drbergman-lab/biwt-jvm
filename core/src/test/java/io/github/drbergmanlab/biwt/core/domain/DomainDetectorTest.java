package io.github.drbergmanlab.biwt.core.domain;

import org.junit.jupiter.api.Test;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class DomainDetectorTest {

    private static final double EPS = 1e-9;

    @Test
    void findsAnnotationByDefaultName() {
        ImageData<BufferedImage> data = makeImageData(100, 100, 0.5);
        addNamedRectangle(data, "abm_domain", 10, 10, 60, 60);

        AbmDomain d = new DomainDetector().detect(data, DomainDetectionOptions.defaults());

        assertEquals("annotation 'abm_domain'", d.sourceDescription());
        assertEquals(10.0, d.xMinPx(), EPS);
        assertEquals(70.0, d.xMaxPx(), EPS);
        assertEquals(60.0, d.widthPx(), EPS);
        assertEquals(30.0, d.widthMicrons(), EPS); // 60 px * 0.5 µm/px
        assertEquals(0.5, d.pixelWidthMicrons(), EPS);
        assertNotNull(d.clipMaskPx());
    }

    @Test
    void ignoresAnnotationsWithDifferentNames() {
        ImageData<BufferedImage> data = makeImageData(100, 100, 1.0);
        addNamedRectangle(data, "tumor", 10, 10, 60, 60);

        assertThrows(AnnotationNotFoundException.class,
                () -> new DomainDetector().detect(data, DomainDetectionOptions.defaults()));
    }

    @Test
    void fallsBackToWholeImageWhenRequested() {
        ImageData<BufferedImage> data = makeImageData(200, 150, 0.25);

        AbmDomain d = new DomainDetector().detect(data, DomainDetectionOptions.wholeImageFallback());

        assertEquals("whole image", d.sourceDescription());
        assertEquals(0.0, d.xMinPx(), EPS);
        assertEquals(200.0, d.xMaxPx(), EPS);
        assertEquals(150.0, d.heightPx(), EPS);
        assertEquals(50.0, d.widthMicrons(), EPS);  // 200 * 0.25
        assertEquals(37.5, d.heightMicrons(), EPS); // 150 * 0.25
    }

    @Test
    void askUserSignalsToGui() {
        ImageData<BufferedImage> data = makeImageData(50, 50, 1.0);

        assertThrows(AskUserRequiredException.class,
                () -> new DomainDetector().detect(data, DomainDetectionOptions.askUserFallback()));
    }

    @Test
    void rejectsNonRectangularAnnotation() {
        ImageData<BufferedImage> data = makeImageData(100, 100, 1.0);
        ROI ellipse = ROIs.createEllipseROI(10, 10, 60, 60, ImagePlane.getDefaultPlane());
        PathObject ann = PathObjects.createAnnotationObject(ellipse);
        ann.setName("abm_domain");
        data.getHierarchy().addObject(ann);

        assertThrows(NonRectangularDomainException.class,
                () -> new DomainDetector().detect(data, DomainDetectionOptions.defaults()));
    }

    @Test
    void rejectsMultipleMatchingAnnotations() {
        ImageData<BufferedImage> data = makeImageData(100, 100, 1.0);
        addNamedRectangle(data, "abm_domain", 10, 10, 30, 30);
        addNamedRectangle(data, "abm_domain", 50, 50, 30, 30);

        assertThrows(DomainException.class,
                () -> new DomainDetector().detect(data, DomainDetectionOptions.defaults()));
    }

    @Test
    void respectsCustomAnnotationName() {
        ImageData<BufferedImage> data = makeImageData(100, 100, 1.0);
        addNamedRectangle(data, "my_domain", 5, 5, 40, 40);

        AbmDomain d = new DomainDetector().detect(data,
                new DomainDetectionOptions("my_domain", DomainDetectionOptions.Fallback.FAIL));

        assertEquals(5.0, d.xMinPx(), EPS);
        assertEquals(45.0, d.xMaxPx(), EPS);
    }

    @Test
    void rejectsImageWithoutPixelCalibration() {
        // Default WrappedBufferedImageServer has no pixel-size calibration.
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ImageServer<BufferedImage> server = new WrappedBufferedImageServer("uncalibrated", img);
        ImageData<BufferedImage> data = new ImageData<>(server);

        DomainException ex = assertThrows(DomainException.class,
                () -> new DomainDetector().detect(data, DomainDetectionOptions.wholeImageFallback()));
        assertTrue(ex.getMessage().contains("calibration"), "got: " + ex.getMessage());
    }

    // ---- helpers ----

    private static ImageData<BufferedImage> makeImageData(int width, int height, double pxSizeMicrons) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        WrappedBufferedImageServer server = new WrappedBufferedImageServer("test", img);
        ImageServerMetadata calibrated = new ImageServerMetadata.Builder(server.getOriginalMetadata())
                .pixelSizeMicrons(pxSizeMicrons, pxSizeMicrons)
                .build();
        server.setMetadata(calibrated);
        return new ImageData<>(server);
    }

    private static void addNamedRectangle(ImageData<BufferedImage> data, String name,
                                          double x, double y, double w, double h) {
        ROI roi = ROIs.createRectangleROI(x, y, w, h, ImagePlane.getDefaultPlane());
        PathObject ann = PathObjects.createAnnotationObject(roi);
        ann.setName(name);
        data.getHierarchy().addObject(ann);
    }
}
