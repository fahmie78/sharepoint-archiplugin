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

    /**
     * {@code true} if the item is currently checked out (SharePoint check-out).
     * Only meaningful when {@link #publicationKnown} is {@code true}.
     */
    public boolean checkedOut;

    /**
     * Email (preferred) or display name of the user the item is checked out to,
     * or {@code null} when unknown or not checked out.
     */
    public String checkedOutBy;

    /**
     * {@code true} when the response actually contained the {@code publication}
     * facet. Graph v1.0 rejects {@code $expand=publication} and does not reliably
     * return the facet via {@code $select}, so callers must treat the check-out
     * fields as unknown when this is {@code false} (and fall back to locally
     * tracked state).
     */
    public boolean publicationKnown;
}
