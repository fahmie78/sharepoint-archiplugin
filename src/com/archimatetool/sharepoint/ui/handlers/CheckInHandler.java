package com.archimatetool.sharepoint.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.sync.ModelBinding;
import com.archimatetool.sharepoint.sync.SyncEngine;
import com.archimatetool.sharepoint.ui.ActiveModelResolver;

/**
 * Handler for the "Check in" command. Prompts for an optional check-in comment
 * and checks the bound SharePoint file back in through the {@link SyncEngine}
 * (so the engine's check-out state — and with it the "Check out" /
 * "Discard Check-out" menu label — stays consistent). The outcome is reported
 * via the sync status listeners (Eclipse Error Log).
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
        if(!SyncEngine.get().isCheckedOut(model)) {
            MessageDialog.openInformation(shell, "Check in",
                    "\"" + binding.itemPath + "\" is not checked out.");
            return null;
        }

        InputDialog commentDialog = new InputDialog(shell, "Check in",
                "Check-in comment for \"" + binding.itemPath + "\":", "Checked in from Archi", null);
        if(commentDialog.open() != Window.OK) {
            return null;
        }

        SyncEngine.get().checkInNow(model, commentDialog.getValue());
        return null;
    }
}
