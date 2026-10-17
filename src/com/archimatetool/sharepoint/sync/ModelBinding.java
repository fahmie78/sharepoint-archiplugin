package com.archimatetool.sharepoint.sync;

import java.io.File;

import com.archimatetool.model.IArchimateModel;

/**
 * Binding between an open ArchiMate model and a file in a SharePoint document library.
 * Instances are persisted as JSON by {@link BindingStore}; the {@link #model} reference
 * is transient and re-attached when the model is open.
 */
public final class ModelBinding {

    /** Graph site id. */
    public String siteId;

    /** Graph drive (document library) id. */
    public String driveId;

    /** Graph item id of the remote file (may change across uploads). */
    public String itemId;

    /** Path of the remote file relative to the drive root (e.g. {@code models/foo.archimate}). */
    public String itemPath;

    /** Human-readable URL of the remote file (for display purposes). */
    public String displayUrl;

    /** ETag of the remote file as of the last successful sync. */
    public String lastETag;

    /** Absolute path of the local model file. */
    public String localFilePath;

    /**
     * Persisted check-out flag: {@code true} when this session (machine + user)
     * last held the remote check-out. Graph v1.0 cannot reliably report the
     * check-out state, so this is the cross-restart source of truth for the
     * "Check out" / "Discard Check-out" menu toggle.
     */
    public boolean checkedOut;

    /** The bound model, set while it is open in the editor; never persisted. */
    public transient IArchimateModel model;

    /**
     * Creates an empty binding (all fields {@code null}); populated by the caller or by Gson.
     */
    public ModelBinding() {
    }

    /**
     * @return the local model file, or {@code null} if {@link #localFilePath} is unset
     */
    public File localFile() {
        return localFilePath == null ? null : new File(localFilePath);
    }
}
