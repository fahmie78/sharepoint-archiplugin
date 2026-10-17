package com.archimatetool.sharepoint.sync;

/**
 * Decides how a sync conflict is resolved. Implementations typically show a dialog and
 * are therefore invoked by the {@link SyncEngine} ON the SWT UI thread; the engine blocks
 * until this method returns.
 */
public interface ConflictResolver {

    /**
     * Asks how the conflict described by the context should be resolved.
     *
     * @param ctx the conflict context
     * @return the chosen resolution (never {@code null}; return {@link Resolution#POSTPONE}
     *         to defer)
     */
    Resolution resolveConflict(SyncContext ctx);
}
