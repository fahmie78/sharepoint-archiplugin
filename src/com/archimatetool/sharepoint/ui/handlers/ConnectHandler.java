package com.archimatetool.sharepoint.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.auth.AuthService;
import com.archimatetool.sharepoint.graph.GraphClient;
import com.archimatetool.sharepoint.sync.BindingStore;
import com.archimatetool.sharepoint.sync.ModelBinding;
import com.archimatetool.sharepoint.sync.SyncEngine;
import com.archimatetool.sharepoint.ui.ActiveModelResolver;
import com.archimatetool.sharepoint.ui.ConnectWizard;

/**
 * Handler for the "Connect to SharePoint" command. Ensures the user is signed
 * in (starting the sign-in flow when needed), resolves the target model, and
 * opens the {@link ConnectWizard}. On finish the model is bound in the
 * {@link SyncEngine} and the binding is persisted via {@link BindingStore}.
 */
public class ConnectHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        AuthService auth = AuthService.get();

        if(!auth.isSignedIn()) {
            boolean start = MessageDialog.openConfirm(shell, "Sign in required",
                    "You need to sign in to Microsoft 365 before connecting a model. Sign in now?");
            if(!start) {
                return null;
            }
            SignInHandler.startSignIn(shell, auth, () -> connect(SignInHandler.liveShell(shell)));
            return null;
        }

        connect(shell);
        return null;
    }

    private void connect(Shell shell) {
        IArchimateModel model = ActiveModelResolver.resolveOrPick(shell);
        if(model == null) {
            return;
        }
        if(model.getFile() == null) {
            MessageDialog.openError(shell, "Connect to SharePoint",
                    "The model \"" + model.getName() + "\" has never been saved.\n"
                    + "Save the model to a local file first, then connect it.");
            return;
        }
        if(SyncEngine.get().bindingFor(model) != null) {
            MessageDialog.openInformation(shell, "Connect to SharePoint",
                    "The model \"" + model.getName() + "\" is already connected to SharePoint.");
            return;
        }

        GraphClient client = new GraphClient(AuthService.get()::acquireGraphToken);
        ConnectWizard wizard = new ConnectWizard(model, client);
        WizardDialog dialog = new WizardDialog(shell, wizard);
        if(dialog.open() == Window.OK && wizard.getBinding() != null) {
            ModelBinding binding = wizard.getBinding();
            SyncEngine.get().bind(model, binding);
            BindingStore.upsert(binding);
            MessageDialog.openInformation(shell, "Connect to SharePoint",
                    "Model \"" + model.getName() + "\" is now connected to\n" + binding.displayUrl);
        }
    }
}
