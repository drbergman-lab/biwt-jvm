package io.github.drbergmanlab.biwt.core.channelmath;

/**
 * Thrown by {@link ExpressionParser#parse(String)} when the input doesn't conform to the channel-
 * math grammar. Carries a 1-based column so the GUI can point at the offending character.
 */
public class ExpressionParseException extends RuntimeException {
    private final int column;

    public ExpressionParseException(String message, int column) {
        super(message + " (column " + column + ")");
        this.column = column;
    }

    /** 1-based character column in the original expression source. */
    public int column() {
        return column;
    }
}
