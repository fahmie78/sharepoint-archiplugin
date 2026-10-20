package com.archimatetool.sharepoint.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.sync.ModelBinding;
import com.archimatetool.sharepoint.sync.SyncEngine;
import com.archimatetool.sharepoint.ui.ActiveModelResolver;
import com.archimatetool.sharepoint.ui.VersionHistoryDialog;

/**
 * Handler for the "Version history..." command. Opens the
 * {@link VersionHistoryDialog} for the active model's connected SharePoint file;
 * shows an info dialog instead when the model is not connected.
 */
public class VersionHistoryHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);

        IArchimateModel model = ActiveModelResolver.resolveOrPick(shell);
        if(model == null) {
            return null;
        }
        ModelBinding binding = SyncEngine.get().bindingFor(model);
        if(binding == null || binding.itemId == null) {
            MessageDialog.openInformation(shell, "Version history",
                    "The model \"" + model.getName() + "\" is not connected to an existing SharePoint file.");
            return null;
        }

        new VersionHistoryDialog(shell, model).open();
        return null;
    }
}
