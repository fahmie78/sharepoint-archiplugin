package com.archimatetool.sharepoint.sync;

/**
 * Asks the user whether a remotely changed file should be reloaded, used when automatic
 * reload is disabled in the preferences. Invoked by the {@link SyncEngine} ON the SWT UI
 * thread; the engine blocks until this method returns.
 */
public interface ReloadPrompter {

    /**
     * @param ctx context of the remote change
     * @return {@code true} to discard the (clean) local copy and reload the remote file
     */
    boolean confirmReload(SyncContext ctx);
}
