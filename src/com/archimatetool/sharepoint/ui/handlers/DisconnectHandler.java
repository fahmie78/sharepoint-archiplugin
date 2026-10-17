package com.archimatetool.sharepoint.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.sync.BindingStore;
import com.archimatetool.sharepoint.sync.ModelBinding;
import com.archimatetool.sharepoint.sync.SyncEngine;
import com.archimatetool.sharepoint.ui.ActiveModelResolver;

/**
 * Handler for the "Disconnect" command. Stops syncing the resolved model and
 * removes its persisted binding after a confirmation.
 */
public class DisconnectHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);

        IArchimateModel model = ActiveModelResolver.resolveOrPick(shell);
        if(model == null) {
            return null;
        }

        ModelBinding binding = SyncEngine.get().bindingFor(model);
        if(binding == null) {
            MessageDialog.openWarning(shell, "Disconnect from SharePoint",
                    "The model \"" + model.getName() + "\" is not connected to SharePoint.");
            return null;
        }

        boolean confirm = MessageDialog.openConfirm(shell, "Disconnect from SharePoint",
                "Stop syncing \"" + model.getName() + "\" with\n" + binding.displayUrl
                + "\n\nThe local file and the SharePoint copy are both kept.");
        if(confirm) {
            SyncEngine.get().unbind(model);
            if(binding.localFilePath != null) {
                BindingStore.remove(binding.localFilePath);
            }
        }
        return null;
    }
}
