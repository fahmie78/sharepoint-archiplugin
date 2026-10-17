package com.archimatetool.sharepoint.sync;

/**
 * Context of a detected remote change / conflict, passed to {@link ConflictResolver}
 * and {@link ReloadPrompter}.
 */
public final class SyncContext {

    /** The affected binding. */
    public final ModelBinding binding;

    /** The current remote ETag (differs from {@code binding.lastETag}). */
    public final String remoteETag;

    /** Display name of the user who last modified the remote file (may be {@code null}). */
    public final String remoteModifiedBy;

    /** ISO-8601 timestamp of the last remote modification (may be {@code null}). */
    public final String remoteModified;

    /**
     * Creates a new context.
     *
     * @param binding          the affected binding
     * @param remoteETag       the current remote ETag
     * @param remoteModifiedBy display name of the last remote editor, or {@code null}
     * @param remoteModified   timestamp of the last remote modification, or {@code null}
     */
    public SyncContext(ModelBinding binding, String remoteETag, String remoteModifiedBy,
            String remoteModified) {
        this.binding = binding;
        this.remoteETag = remoteETag;
        this.remoteModifiedBy = remoteModifiedBy;
        this.remoteModified = remoteModified;
    }
}
