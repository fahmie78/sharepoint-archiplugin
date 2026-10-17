package com.archimatetool.sharepoint.sync;

/**
 * How a sync conflict between the local model and the remote SharePoint file
 * should be resolved.
 */
public enum Resolution {

    /** Overwrite the remote file with the local model. */
    KEEP_MINE,

    /** Discard local changes and reload the remote file. */
    LOAD_THEIRS,

    /** Save a timestamped copy of the local file next to it, then reload the remote file. */
    SAVE_COPY_THEN_LOAD_THEIRS,

    /** Do nothing now; the conflict is raised again on the next autosave tick. */
    POSTPONE
}
