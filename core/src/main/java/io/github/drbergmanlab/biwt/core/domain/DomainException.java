package io.github.drbergmanlab.biwt.core.domain;

/** Base type for all domain-detection failures. Subclasses signal specific recoverable cases to the GUI. */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
