package io.github.drbergmanlab.biwt.core.domain;

/**
 * The matching annotation exists but is not an axis-aligned rectangle.
 * The MVP requires a {@link qupath.lib.roi.RectangleROI} (which is inherently axis-aligned in QuPath).
 */
public class NonRectangularDomainException extends DomainException {
    private final String annotationName;
    private final String actualRoiType;

    public NonRectangularDomainException(String annotationName, String actualRoiType) {
        super("Annotation '" + annotationName + "' must be an axis-aligned rectangle (got "
                + actualRoiType + "). Redraw it with the rectangle tool.");
        this.annotationName = annotationName;
        this.actualRoiType = actualRoiType;
    }

    public String annotationName() {
        return annotationName;
    }

    public String actualRoiType() {
        return actualRoiType;
    }
}
