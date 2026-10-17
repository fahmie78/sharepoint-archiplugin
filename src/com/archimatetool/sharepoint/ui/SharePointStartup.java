package com.archimatetool.sharepoint.ui;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IStartup;

import com.archimatetool.sharepoint.SharePointPlugin;
import com.archimatetool.sharepoint.auth.AuthService;
import com.archimatetool.sharepoint.sync.SyncEngine;
import com.archimatetool.sharepoint.sync.SyncStatus;
import com.archimatetool.sharepoint.ui.handlers.CheckOutHandler;

/**
 * Early-startup hook for the SharePoint plugin. Registers the UI
 * implementations of the sync engine callbacks:
 * <ul>
 *   <li>a {@code ConflictResolver} backed by {@link ConflictDialog};</li>
 *   <li>a {@code ReloadPrompter} backed by a question dialog;</li>
 *   <li>a {@code SyncListener} that logs status changes to the Eclipse Error Log;</li>
 *   <li>a {@code SyncListener} that refreshes the toggling "Check out" menu label.</li>
 * </ul>
 * Persisted bindings re-bind automatically when their model is opened: the
 * {@link SyncEngine} already listens for {@code PROPERTY_MODEL_OPENED}, so
 * ensuring the engine is initialized is sufficient here.
 * <p>
 * A background warm-up job calls {@code AuthService.acquireGraphToken()} so a
 * persisted MSAL session is restored silently at startup (priming autosave/sync
 * for restored bindings) without ever showing a dialog.
 * </p>
 */
public class SharePointStartup implements IStartup {

    @Override
    public void earlyStartup() {
        SyncEngine engine = SyncEngine.get();

        engine.setConflictResolver(context -> {
            Shell shell = Display.getDefault().getActiveShell();
            return new ConflictDialog(shell, context).openAndGetResolution();
        });

        engine.setReloadPrompter(context -> {
            Shell shell = Display.getDefault().getActiveShell();
            String path = context != null && context.binding != null && context.binding.itemPath != null
                    ? context.binding.itemPath : "the connected file";
            StringBuilder detail = new StringBuilder("The SharePoint copy of \"" + path + "\" has changed");
            if(context != null && context.remoteModifiedBy != null) {
                detail.append(" (by ").append(context.remoteModifiedBy);
                if(context.remoteModified != null) {
                    detail.append(", ").append(context.remoteModified);
                }
                detail.append(")");
            }
            detail.append(".\n\nYour local model has no unsaved changes. Reload the remote version now?");
            return MessageDialog.openQuestion(shell, "Remote copy changed", detail.toString());
        });

        engine.addListener((binding, status, message) -> log(status, binding, message));

        // catch models that were already open before the model listener was registered
        engine.rescanOpenModels();

        // keep the toggling "Check out" menu label in sync with the engine state
        engine.addListener((binding, status, message) -> {
            Display display = Display.getDefault();
            if(display != null && !display.isDisposed()) {
                display.asyncExec(CheckOutHandler::refreshMenuText);
            }
        });
        CheckOutHandler.refreshMenuText();

        warmUpSession();
    }

    /**
     * Restores a persisted sign-in session in the background (silent token
     * acquisition primes account restoration from the persisted MSAL cache, so
     * autosave/polling resume automatically for restored bindings). Never shows
     * UI; failures are only logged at INFO level.
     */
    private static void warmUpSession() {
        Job warmUp = new Job("Restoring SharePoint session") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    AuthService.get().acquireGraphToken().join();
                    String user = AuthService.get().getSignedInUser();
                    log(IStatus.INFO, "Signed in as " + (user != null ? user : "unknown user")
                            + " (restored session)");
                }
                catch(Throwable t) {
                    log(IStatus.INFO,
                            "SharePoint session not restored; sign in via the SharePoint menu");
                }
                return Status.OK_STATUS;
            }
        };
        warmUp.setSystem(true);
        warmUp.schedule();
    }

    private static void log(SyncStatus status, com.archimatetool.sharepoint.sync.ModelBinding binding, String message) {
        int severity = switch(status) {
            case ERROR, CONFLICT -> IStatus.WARNING;
            case OFFLINE -> IStatus.INFO;
            default -> IStatus.INFO;
        };
        String text = (binding != null && binding.itemPath != null ? binding.itemPath + ": " : "")
                + status + (message != null && !message.isBlank() ? " - " + message : "");
        log(severity, text);
    }

    private static void log(int severity, String text) {
        try {
            ILog log = Platform.getLog(Platform.getBundle(SharePointPlugin.PLUGIN_ID));
            log.log(new Status(severity, SharePointPlugin.PLUGIN_ID, text));
        }
        catch(Throwable t) {
            // fall back to stderr when the log service is unavailable
            System.err.println("[SharePoint] " + text);
        }
    }
}
