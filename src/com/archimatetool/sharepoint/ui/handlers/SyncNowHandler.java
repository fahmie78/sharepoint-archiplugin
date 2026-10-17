package com.archimatetool.sharepoint.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.sync.SyncEngine;
import com.archimatetool.sharepoint.ui.ActiveModelResolver;

/**
 * Handler for the "Sync now" command. Triggers an immediate save + upload of
 * the resolved bound model, regardless of the autosave interval.
 */
public class SyncNowHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);

        IArchimateModel model = ActiveModelResolver.resolveOrPick(shell);
        if(model == null) {
            return null;
        }
        if(SyncEngine.get().bindingFor(model) == null) {
            MessageDialog.openWarning(shell, "Sync now",
                    "The model \"" + model.getName() + "\" is not connected to SharePoint.");
            return null;
        }

        SyncEngine.get().syncNow(model);
        return null;
    }
}
