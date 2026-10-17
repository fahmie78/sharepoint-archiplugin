package com.archimatetool.sharepoint.graph;

/**
 * HTTP 412 — the If-Match ETag did not match the remote item (optimistic concurrency conflict).
 */
public final class PreconditionFailedException extends GraphException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a detail message; status code is fixed to 412.
     *
     * @param message the detail message
     */
    public PreconditionFailedException(String message) {
        super(412, message);
    }
}
