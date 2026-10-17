package com.archimatetool.sharepoint.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.resource.FontDescriptor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.archimatetool.sharepoint.auth.DeviceCodeInfo;

/**
 * Modeless dialog shown during OAuth2 device-code sign-in. Displays the user
 * code in large selectable text, a clickable verification URI, and a
 * "waiting" hint. The Cancel button simply closes the dialog; the sign-in
 * attempt continues/finishes in the background regardless.
 */
public class DeviceCodeDialog extends Dialog {

    private final DeviceCodeInfo info;

    /**
     * Creates the dialog.
     *
     * @param parentShell parent shell (may be null)
     * @param info device code data provided by the auth service
     */
    public DeviceCodeDialog(Shell parentShell, DeviceCodeInfo info) {
        super(parentShell);
        this.info = info;
        setShellStyle(SWT.DIALOG_TRIM | SWT.MODELESS | SWT.RESIZE);
        setBlockOnOpen(false);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Sign in to Microsoft 365");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite)super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayout(new GridLayout(1, false));
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label instructions = new Label(container, SWT.WRAP);
        instructions.setText(info.message != null && !info.message.isBlank()
                ? info.message
                : "To sign in, open the web address below and enter the code.");
        GridData instructionsData = new GridData(SWT.FILL, SWT.TOP, true, false);
        instructionsData.widthHint = 380;
        instructions.setLayoutData(instructionsData);

        Label codeLabel = new Label(container, SWT.NONE);
        codeLabel.setText("Code:");

        Text codeText = new Text(container, SWT.BORDER | SWT.READ_ONLY | SWT.CENTER);
        codeText.setText(info.userCode == null ? "" : info.userCode);
        FontDescriptor big = FontDescriptor.createFrom(codeText.getFont()).setHeight(22).setStyle(SWT.BOLD);
        Font bigFont = big.createFont(codeText.getDisplay());
        codeText.setFont(bigFont);
        codeText.addDisposeListener(e -> bigFont.dispose());
        codeText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Link link = new Link(container, SWT.NONE);
        String uri = info.verificationUri == null ? "https://microsoft.com/devicelogin" : info.verificationUri;
        link.setText("<a href=\"" + uri + "\">" + uri + "</a>");
        link.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                Program.launch(uri);
            }
        });
        link.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label waiting = new Label(container, SWT.WRAP);
        waiting.setText("Waiting for you to complete sign-in in the browser\u2026");
        waiting.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, true);
    }
}
