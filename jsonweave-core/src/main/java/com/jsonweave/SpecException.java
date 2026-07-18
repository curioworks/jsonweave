package com.jsonweave;

/**
 * Thrown at compile time when a spec is structurally invalid: unknown operations,
 * malformed paths, undeclared variables, misplaced stages, and so on.
 * <p>
 * {@link #specPath()} points at the offending location inside the spec document
 * (e.g. {@code $.spend.total}).
 */
public class SpecException extends RuntimeException {

    private final String specPath;

    public SpecException(String message, String specPath) {
        super("at " + specPath + ": " + message);
        this.specPath = specPath;
    }

    /** Location of the problem inside the spec document. */
    public String specPath() {
        return specPath;
    }
}
