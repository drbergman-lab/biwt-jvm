package qupath.ext.biwt.abm;

/**
 * The outcome of a navigable wizard step: advance to the next step carrying a value
 * ({@link #next}), step back to the previous custom screen ({@link #back}), or cancel the whole
 * wizard ({@link #cancel}).
 *
 * <p>Only the multi-field dialogs that carry a <b>Back</b> button produce these. Simple OK/Cancel
 * confirmations and the native file chooser stay value-or-{@code null}: they can advance or abort
 * but never request a step back.
 *
 * @param <T> the value type produced when the step advances
 */
record Nav<T>(Nav.Kind kind, T value) {

    enum Kind { NEXT, BACK, CANCEL }

    /** Advance to the next step carrying {@code value}. */
    static <T> Nav<T> next(T value) { return new Nav<>(Kind.NEXT, value); }

    /** Step back to the previous custom screen (state is preserved by the caller). */
    static <T> Nav<T> back() { return new Nav<>(Kind.BACK, null); }

    /** Cancel the wizard entirely. */
    static <T> Nav<T> cancel() { return new Nav<>(Kind.CANCEL, null); }

    boolean isNext() { return kind == Kind.NEXT; }
    boolean isBack() { return kind == Kind.BACK; }
    boolean isCancel() { return kind == Kind.CANCEL; }
}
