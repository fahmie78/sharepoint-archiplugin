package com.archimatetool.sharepoint.sync;

/**
 * Synchronization state of a bound model.
 */
public enum SyncStatus {

    /** Authentication lost or not signed in; retried on the next tick. */
    OFFLINE,

    /** Bound but no activity. */
    IDLE,

    /** A save/upload/download is in progress. */
    SYNCING,

    /** Local model and remote file are in sync. */
    UP_TO_DATE,

    /** Remote and local have diverged; waiting for user resolution. */
    CONFLICT,

    /** An error occurred (see the accompanying status message). */
    ERROR
}
