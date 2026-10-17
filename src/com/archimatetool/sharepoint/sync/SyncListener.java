package com.archimatetool.sharepoint.sync;

/**
 * Listener for sync status changes of a bound model.
 * <p>
 * Notifications may arrive on ANY thread (typically the sync engine's background
 * scheduler); implementations that update the UI must marshal to the SWT UI thread
 * themselves (e.g. {@code Display.asyncExec}).
 * </p>
 */
public interface SyncListener {

    /**
     * Called when the sync status of a binding changes.
     *
     * @param binding the affected binding
     * @param status  the new status
     * @param message a human-readable detail message (may be {@code null})
     */
    void statusChanged(ModelBinding binding, SyncStatus status, String message);
}
