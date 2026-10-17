package com.archimatetool.sharepoint.graph;

/**
 * HTTP 404 — the requested site, drive or item does not exist (any more).
 */
public final class NotFoundException extends GraphException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a detail message; status code is fixed to 404.
     *
     * @param message the detail message
     */
    public NotFoundException(String message) {
        super(404, message);
    }
}
