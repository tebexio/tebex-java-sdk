package io.tebex.exception;

/**
 * Base type for all checked failures raised by the Tebex SDK.
 */
public class TebexException extends Exception {

    /**
     * Creates a new exception with a human-readable message.
     *
     * @param message the detail message
     */
    public TebexException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a message and underlying cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public TebexException(String message, Throwable cause) {
        super(message, cause);
    }
}
