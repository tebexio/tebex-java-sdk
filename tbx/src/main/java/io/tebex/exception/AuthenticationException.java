package io.tebex.exception;

/**
 * Raised when the Tebex API rejects the supplied secret key (HTTP 403), or when
 * the key does not resolve to a store (HTTP 404). Distinguishes an
 * authentication problem from a generic transport or server error.
 */
public class AuthenticationException extends TebexException {

    /**
     * Creates a new authentication exception.
     *
     * @param message the detail message
     */
    public AuthenticationException(String message) {
        super(message);
    }
}
