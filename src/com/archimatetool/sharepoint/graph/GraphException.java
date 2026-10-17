package com.archimatetool.sharepoint.graph;

/**
 * Exception signalling a failed Microsoft Graph request. Carries the HTTP status code
 * ({@code -1} for client-side/network errors).
 */
public class GraphException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The HTTP status code returned by Graph, or {@code -1} for client-side failures. */
    public final int statusCode;

    /**
     * Creates an exception with a status code and message.
     *
     * @param statusCode the HTTP status code, or {@code -1} for client-side failures
     * @param message    the detail message (includes the Graph {@code error.message} when available)
     */
    public GraphException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    GraphException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}
