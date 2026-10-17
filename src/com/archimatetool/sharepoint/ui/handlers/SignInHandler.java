package com.archimatetool.sharepoint.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.sharepoint.auth.AuthCallback;
import com.archimatetool.sharepoint.auth.AuthService;
import com.archimatetool.sharepoint.ui.DeviceCodeDialog;

/**
 * Handler for the "Sign in" command. Starts the interactive OAuth2 flow
 * (system browser with localhost loopback, device code fallback) unless the
 * user is already signed in.
 */
public class SignInHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        AuthService auth = AuthService.get();

        if(auth.isSignedIn()) {
            String user = auth.getSignedInUser();
            MessageDialog.openInformation(shell, "SharePoint sign-in",
                    "You are already signed in" + (user != null ? " as " + user : "") + ".");
            return null;
        }

        startSignIn(shell, auth, null);
        return null;
    }

    /**
     * Starts the interactive sign-in flow.
     *
     * @param shell parent shell for dialogs
     * @param auth the auth service
     * @param onSuccessUi optional follow-up action run on the UI thread after
     *        a successful sign-in (used by the connect command); may be null.
     */
    static void startSignIn(Shell shell, AuthService auth, Runnable onSuccessUi) {
        Display display = Display.getDefault();
        auth.signInInteractive(
                url -> {
                    if(!Program.launch(url)) {
                        throw new Exception("Could not open the system browser for " + url);
                    }
                },
                deviceCode -> display.asyncExec(() -> {
                    if(display.isDisposed()) {
                        return;
                    }
                    Shell parent = liveShell(shell);
                    new DeviceCodeDialog(parent, deviceCode).open();
                }),
                new AuthCallback() {
                    @Override
                    public void onSuccess(String accountName) {
                        display.asyncExec(() -> {
                            if(display.isDisposed()) {
                                return;
                            }
                            Shell parent = liveShell(shell);
                            MessageDialog.openInformation(parent, "SharePoint sign-in",
                                    "Signed in" + (accountName != null ? " as " + accountName : "") + ".");
                            if(onSuccessUi != null) {
                                onSuccessUi.run();
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        display.asyncExec(() -> {
                            if(display.isDisposed()) {
                                return;
                            }
                            Shell parent = liveShell(shell);
                            MessageDialog.openError(parent, "SharePoint sign-in failed",
                                    String.valueOf(error.getMessage()));
                        });
                    }
                });
    }

    /**
     * Returns the captured shell when still alive, otherwise the current active
     * workbench/display shell. Must be called on the UI thread.
     */
    static Shell liveShell(Shell captured) {
        if(captured != null && !captured.isDisposed()) {
            return captured;
        }
        org.eclipse.ui.IWorkbenchWindow window =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if(window != null && window.getShell() != null && !window.getShell().isDisposed()) {
            return window.getShell();
        }
        return Display.getDefault().getActiveShell();
    }
}
