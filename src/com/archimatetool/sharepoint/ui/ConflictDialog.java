package com.archimatetool.sharepoint.ui;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.sharepoint.sync.Resolution;
import com.archimatetool.sharepoint.sync.SyncContext;

/**
 * Conflict prompt implementing the {@code ConflictResolver} contract of the
 * sync engine. It is opened on the UI thread and returns one of the four
 * {@link Resolution} values; closing the dialog maps to
 * {@link Resolution#POSTPONE}.
 */
public class ConflictDialog extends Dialog {

    /** Button id: overwrite the server with the local model. */
    public static final int ID_KEEP_MINE = 10;
    /** Button id: discard local changes and load the remote copy. */
    public static final int ID_LOAD_THEIRS = 11;
    /** Button id: save a local conflict copy, then load the remote copy. */
    public static final int ID_SAVE_COPY_THEN_LOAD_THEIRS = 12;
    /** Button id: postpone the decision until the next sync tick. */
    public static final int ID_POSTPONE = 13;

    private final SyncContext context;
    private Resolution resolution = Resolution.POSTPONE;

    /**
     * Creates the dialog.
     *
     * @param parentShell parent shell (may be null)
     * @param context conflict details (binding + remote metadata)
     */
    public ConflictDialog(Shell parentShell, SyncContext context) {
        super(parentShell);
        this.context = context;
    }

    /**
     * Opens the dialog (blocking) and returns the chosen resolution.
     *
     * @return the user's choice; {@link Resolution#POSTPONE} when cancelled.
     */
    public Resolution openAndGetResolution() {
        open();
        return resolution;
    }

    /**
     * @return the resolution chosen by the user (valid after {@link #open()}).
     */
    public Resolution getResolution() {
        return resolution;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Sync conflict");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite)super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayout(new GridLayout(1, false));
        GridDataFactory.fillDefaults().grab(true, true).applyTo(container);

        String path = context != null && context.binding != null && context.binding.itemPath != null
                ? context.binding.itemPath : "(unknown file)";
        String by = context != null && context.remoteModifiedBy != null
                ? context.remoteModifiedBy : "someone else";
        String when = context != null && context.remoteModified != null
                ? context.remoteModified : "an unknown time";

        Label headline = new Label(container, SWT.WRAP);
        headline.setText("The SharePoint copy of \"" + path + "\" was changed by " + by
                + " at " + when + ",\nbut your local model also has unsaved changes.");
        GridDataFactory.fillDefaults().grab(true, false).hint(420, SWT.DEFAULT).applyTo(headline);

        Label question = new Label(container, SWT.WRAP);
        question.setText("How do you want to resolve the conflict?");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(question);

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, ID_KEEP_MINE, "Keep mine (overwrite server)", false);
        createButton(parent, ID_LOAD_THEIRS, "Load theirs (discard my changes)", false);
        createButton(parent, ID_SAVE_COPY_THEN_LOAD_THEIRS, "Save my copy && load theirs", false);
        createButton(parent, ID_POSTPONE, "Ask me later", true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        resolution = switch(buttonId) {
            case ID_KEEP_MINE -> Resolution.KEEP_MINE;
            case ID_LOAD_THEIRS -> Resolution.LOAD_THEIRS;
            case ID_SAVE_COPY_THEN_LOAD_THEIRS -> Resolution.SAVE_COPY_THEN_LOAD_THEIRS;
            default -> Resolution.POSTPONE;
        };
        setReturnCode(buttonId);
        close();
    }

    @Override
    protected void handleShellCloseEvent() {
        resolution = Resolution.POSTPONE;
        super.handleShellCloseEvent();
    }

    @Override
    public boolean close() {
        // Closing without a button always postpones.
        if(getReturnCode() != ID_KEEP_MINE && getReturnCode() != ID_LOAD_THEIRS
                && getReturnCode() != ID_SAVE_COPY_THEN_LOAD_THEIRS) {
            resolution = Resolution.POSTPONE;
        }
        return super.close();
    }
}
