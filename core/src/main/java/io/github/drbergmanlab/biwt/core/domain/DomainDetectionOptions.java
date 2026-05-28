package io.github.drbergmanlab.biwt.core.domain;

import java.util.Objects;

/**
 * Configuration for {@link DomainDetector#detect}.
 *
 * <p>{@code annotationName} is the case-sensitive name to search for in the image's annotation
 * hierarchy. {@code fallback} dictates what happens if no matching annotation is found.
 */
public record DomainDetectionOptions(String annotationName, Fallback fallback) {

    public static final String DEFAULT_ANNOTATION_NAME = "abm_domain";

    public DomainDetectionOptions {
        Objects.requireNonNull(annotationName, "annotationName");
        Objects.requireNonNull(fallback, "fallback");
        if (annotationName.isBlank()) {
            throw new IllegalArgumentException("annotationName must not be blank");
        }
    }

    /** Default options: look for {@code "abm_domain"}, fail if missing. */
    public static DomainDetectionOptions defaults() {
        return new DomainDetectionOptions(DEFAULT_ANNOTATION_NAME, Fallback.FAIL);
    }

    /** Look for {@code "abm_domain"}; fall back to the whole image if absent. */
    public static DomainDetectionOptions wholeImageFallback() {
        return new DomainDetectionOptions(DEFAULT_ANNOTATION_NAME, Fallback.WHOLE_IMAGE);
    }

    /** Look for {@code "abm_domain"}; ask the user if absent (GUI only). */
    public static DomainDetectionOptions askUserFallback() {
        return new DomainDetectionOptions(DEFAULT_ANNOTATION_NAME, Fallback.ASK_USER);
    }

    public enum Fallback {
        /** Throw {@link AnnotationNotFoundException} if no matching annotation exists. */
        FAIL,
        /** Use the full image extent as the domain if no matching annotation exists. */
        WHOLE_IMAGE,
        /** Throw {@link AskUserRequiredException} so the GUI can prompt. Headless callers should not use this. */
        ASK_USER
    }
}
