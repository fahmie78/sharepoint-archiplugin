package com.archimatetool.sharepoint.graph;

/**
 * A file or folder within a drive, as returned by Microsoft Graph.
 */
public final class DriveItem {

    /** The Graph item id. */
    public String id;

    /** The item name (file name). */
    public String name;

    /** The current ETag, used for optimistic concurrency (sent verbatim as If-Match). */
    public String eTag;

    /** Graph path of the parent (e.g. {@code /drives/{id}/root:/folder}). */
    public String parentPath;

    /** Size in bytes. */
    public long size;

    /** ISO-8601 timestamp of the last modification. */
    public String lastModified;

    /** Display name of the user who last modified the item. */
    public String lastModifiedBy;

    /** {@code true} if this item is a folder. */
    public boolean folder;
}
