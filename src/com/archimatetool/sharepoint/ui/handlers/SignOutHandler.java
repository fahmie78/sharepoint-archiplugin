package com.archimatetool.sharepoint.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.sharepoint.auth.AuthService;

/**
 * Handler for the "Sign out" command. Confirms with the user, then clears the
 * account and the persisted token cache via {@link AuthService#signOut()}.
 */
public class SignOutHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        AuthService auth = AuthService.get();

        if(!auth.isSignedIn()) {
            MessageDialog.openInformation(shell, "SharePoint sign-out", "You are not signed in.");
            return null;
        }

        String user = auth.getSignedInUser();
        boolean confirm = MessageDialog.openConfirm(shell, "SharePoint sign-out",
                "Sign out" + (user != null ? " " + user : "") + " and remove the cached credentials?");
        if(confirm) {
            auth.signOut();
            MessageDialog.openInformation(shell, "SharePoint sign-out", "You have been signed out.");
        }
        return null;
    }
}
