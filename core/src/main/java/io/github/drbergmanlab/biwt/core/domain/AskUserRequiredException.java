package io.github.drbergmanlab.biwt.core.domain;

/**
 * Signal that no matching annotation was found and the caller asked for {@code ASK_USER} fallback.
 * The GUI catches this to launch its annotation-picker; headless callers should never set this fallback
 * mode and so should never see this exception.
 */
public class AskUserRequiredException extends DomainException {
    private final String annotationName;

    public AskUserRequiredException(String annotationName) {
        super("No annotation named '" + annotationName + "' found; caller requested user interaction.");
        this.annotationName = annotationName;
    }

    public String annotationName() {
        return annotationName;
    }
}
