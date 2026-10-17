package com.archimatetool.sharepoint.auth;

/**
 * Exception signalling an authentication or configuration failure in the SharePoint auth layer.
 */
public final class AuthException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public AuthException(String message) {
        super(message);
    }
}
