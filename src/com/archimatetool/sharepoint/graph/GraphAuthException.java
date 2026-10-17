package com.archimatetool.sharepoint.graph;

/**
 * HTTP 401/403 — authentication or authorization failure against Microsoft Graph;
 * the user typically needs to sign in (again).
 */
public final class GraphAuthException extends GraphException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a detail message and status code.
     *
     * @param statusCode 401 or 403
     * @param message    the detail message
     */
    public GraphAuthException(int statusCode, String message) {
        super(statusCode, message);
    }

    /**
     * Creates the exception with a detail message; status code is fixed to 401.
     *
     * @param message the detail message
     */
    public GraphAuthException(String message) {
        super(401, message);
    }
}
