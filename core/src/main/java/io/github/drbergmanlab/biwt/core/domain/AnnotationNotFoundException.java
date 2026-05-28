package io.github.drbergmanlab.biwt.core.domain;

/** No annotation with the expected name exists in the image, and the fallback mode is {@code FAIL}. */
public class AnnotationNotFoundException extends DomainException {
    private final String annotationName;

    public AnnotationNotFoundException(String annotationName) {
        super("No annotation named '" + annotationName + "' found in the image hierarchy.");
        this.annotationName = annotationName;
    }

    public String annotationName() {
        return annotationName;
    }
}
