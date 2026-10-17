package com.archimatetool.sharepoint.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.auth.AuthService;
import com.archimatetool.sharepoint.graph.GraphClient;
import com.archimatetool.sharepoint.graph.GraphException;
import com.archimatetool.sharepoint.sync.ModelBinding;
import com.archimatetool.sharepoint.sync.SyncEngine;
import com.archimatetool.sharepoint.ui.ActiveModelResolver;

/**
 * Handler for the "Check in" command. Prompts for an optional check-in comment
 * and checks the bound SharePoint file back in via the Graph API. Runs in a
 * background job and reports the outcome in a status dialog.
 */
public class CheckInHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);

        IArchimateModel model = ActiveModelResolver.resolveOrPick(shell);
        if(model == null) {
            return null;
        }
        ModelBinding binding = SyncEngine.get().bindingFor(model);
        if(binding == null || binding.itemId == null) {
            MessageDialog.openWarning(shell, "Check in",
                    "The model \"" + model.getName() + "\" is not connected to an existing SharePoint file.");
            return null;
        }

        InputDialog commentDialog = new InputDialog(shell, "Check in",
                "Check-in comment for \"" + binding.itemPath + "\":", "Checked in from Archi", null);
        if(commentDialog.open() != Window.OK) {
            return null;
        }
        String comment = commentDialog.getValue();

        GraphClient client = new GraphClient(AuthService.get()::acquireGraphToken);
        Job job = new Job("Checking in " + binding.itemPath) {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    client.checkIn(binding.driveId, binding.itemId, comment);
                    CheckInHandler.notify(shell, true, binding.itemPath, null);
                }
                catch(GraphException e) {
                    CheckInHandler.notify(shell, false, binding.itemPath, e);
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }

    private static void notify(Shell shell, boolean success, String itemPath, GraphException error) {
        Display display = Display.getDefault();
        display.asyncExec(() -> {
            if(display.isDisposed()) {
                return;
            }
            Shell parent = SignInHandler.liveShell(shell);
            if(success) {
                MessageDialog.openInformation(parent, "Check in",
                        "\"" + itemPath + "\" has been checked in.");
            }
            else {
                MessageDialog.openError(parent, "Check in failed", String.valueOf(error.getMessage()));
            }
        });
    }
}
