package com.archimatetool.sharepoint.graph;

/**
 * A single version of a file within a drive, as returned by the Microsoft Graph
 * driveItem versions endpoint.
 * <p>
 * Note: driveItemVersion has no comment field on Graph v1.0; {@link #comment} is
 * enriched best-effort from the check-in comments of the document library's
 * backing list and is {@code null} when unavailable.
 * </p>
 */
public final class DriveItemVersion {

    /** The version id (e.g. {@code "3.0"}). */
    public String id;

    /** The check-in comment, or {@code null} when unavailable. */
    public String comment;

    /** ISO-8601 timestamp of the version's modification. */
    public String lastModified;

    /** Display name of the user who created the version. */
    public String lastModifiedBy;

    /** Size in bytes. */
    public long size;
}
