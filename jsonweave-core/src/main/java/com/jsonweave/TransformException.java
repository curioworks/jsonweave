package com.jsonweave;

/**
 * Thrown while applying a compiled {@link Transform} to a document, when an operation
 * receives data it cannot process (e.g. a custom function failing, or a value of an
 * unusable type where the operation is strict).
 * <p>
 * Jsonweave is deliberately lenient at runtime — most type mismatches and missing data
 * simply produce a missing value — so this exception is reserved for unrecoverable cases.
 */
public class TransformException extends RuntimeException {

    private final String specPath;

    public TransformException(String message, String specPath) {
        super("at " + specPath + ": " + message);
        this.specPath = specPath;
    }

    public TransformException(String message, String specPath, Throwable cause) {
        super("at " + specPath + ": " + message, cause);
        this.specPath = specPath;
    }

    /** Location, inside the spec document, of the operation that failed. */
    public String specPath() {
        return specPath;
    }
}
