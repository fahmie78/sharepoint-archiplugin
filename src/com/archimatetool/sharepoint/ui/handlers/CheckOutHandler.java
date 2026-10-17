package com.archimatetool.sharepoint.ui.handlers;

import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.sync.ModelBinding;
import com.archimatetool.sharepoint.sync.SyncEngine;
import com.archimatetool.sharepoint.ui.ActiveModelResolver;

/**
 * Handler for the toggling "Check out" command. When the bound SharePoint file is not
 * checked out to the current user, the command checks it out via
 * {@link SyncEngine#checkOutNow(IArchimateModel)}; when it is checked out, the command
 * offers to discard the check-out via
 * {@link SyncEngine#discardCheckOutNow(IArchimateModel)} after a confirmation.
 * <p>
 * Implements {@link IElementUpdater} so the menu label switches between "Check out" and
 * "Discard Check-out"; the label is refreshed through {@link #refreshMenuText()},
 * which the startup class triggers on every sync status change.
 * </p>
 */
public class CheckOutHandler extends AbstractHandler implements IElementUpdater {

    /** The command id whose menu label this handler keeps up to date. */
    public static final String COMMAND_ID = "com.archimatetool.sharepoint.commands.checkOut";

    /**
     * Asks the command service to re-evaluate the label of the check-out command
     * (calls {@link #updateElement(UIElement, Map)}). Must be called on the UI thread.
     */
    public static void refreshMenuText() {
        try {
            ICommandService service = PlatformUI.getWorkbench().getService(ICommandService.class);
            if(service != null) {
                service.refreshElements(COMMAND_ID, null);
            }
        }
        catch(Throwable t) {
            // never let label refreshing break sync handling
        }
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        try {
            // resolve quietly: never open dialogs or touch the network from here
            IArchimateModel model = ActiveModelResolver.resolve();
            boolean checkedOut = model != null && SyncEngine.get().isCheckedOut(model);
            element.setText(checkedOut ? "Discard Check-out" : "Check out");
        }
        catch(Throwable t) {
            element.setText("Check out");
        }
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);

        IArchimateModel model = ActiveModelResolver.resolveOrPick(shell);
        if(model == null) {
            return null;
        }
        ModelBinding binding = SyncEngine.get().bindingFor(model);
        if(binding == null || binding.itemId == null) {
            MessageDialog.openWarning(shell, "Check out",
                    "The model \"" + model.getName() + "\" is not connected to an existing SharePoint file.");
            return null;
        }

        if(!SyncEngine.get().isCheckedOut(model)) {
            SyncEngine.get().checkOutNow(model);
        }
        else {
            boolean confirmed = MessageDialog.openConfirm(shell, "Discard check-out",
                    "Any changes made to the server file while it was checked out will be discarded. Continue?");
            if(confirmed) {
                SyncEngine.get().discardCheckOutNow(model);
            }
        }
        return null;
    }
}
