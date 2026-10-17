package com.archimatetool.sharepoint.prefs;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

/**
 * Preference page for the SharePoint sync plugin.
 * <p>
 * Edits the six preferences defined in {@link PreferenceConstants} and shows a
 * short explanation of the required Microsoft Entra ID app registration.
 */
public class SharePointPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    /**
     * Creates the page using an instance-scoped preference store for this plugin.
     */
    public SharePointPreferencePage() {
        super(GRID);
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, PreferenceConstants.PLUGIN_ID));
        setDescription("SharePoint synchronization settings for Archi models.");
    }

    @Override
    public void init(IWorkbench workbench) {
        // nothing to do; the store is set in the constructor
    }

    @Override
    protected void performApply() {
        super.performApply();
        flushPreferences();
    }

    @Override
    public boolean performOk() {
        boolean ok = super.performOk();
        flushPreferences();
        return ok;
    }

    /**
     * Explicitly flushes the instance-scope preferences to disk. Archi is a
     * standalone RCP app without a workspace save cycle, so relying on the
     * framework to persist dirty preference nodes at shutdown is not safe.
     */
    private static void flushPreferences() {
        try {
            InstanceScope.INSTANCE.getNode(PreferenceConstants.PLUGIN_ID).flush();
        }
        catch(org.osgi.service.prefs.BackingStoreException e) {
            // best-effort persistence; nothing sensible to do in a preference page
        }
    }

    @Override
    protected void createFieldEditors() {
        addField(new StringFieldEditor(PreferenceConstants.P_CLIENT_ID,
                "Entra ID application (client) ID:", getFieldEditorParent()));
        addField(new StringFieldEditor(PreferenceConstants.P_TENANT,
                "Tenant ID (or 'organizations'):", getFieldEditorParent()));

        IntegerFieldEditor autosave = new IntegerFieldEditor(PreferenceConstants.P_AUTOSAVE_SEC,
                "Autosave interval (seconds):", getFieldEditorParent());
        autosave.setValidRange(10, 3600);
        addField(autosave);

        IntegerFieldEditor poll = new IntegerFieldEditor(PreferenceConstants.P_POLL_SEC,
                "Remote change poll interval (seconds):", getFieldEditorParent());
        poll.setValidRange(10, 3600);
        addField(poll);

        addField(new BooleanFieldEditor(PreferenceConstants.P_AUTO_RELOAD,
                "Reload automatically when the remote file changes and the local model is unmodified",
                getFieldEditorParent()));
        addField(new BooleanFieldEditor(PreferenceConstants.P_CHECKOUT_ON_EDIT,
                "Check out the file on first edit and check in on close",
                getFieldEditorParent()));

        Group info = new Group(getFieldEditorParent(), SWT.NONE);
        info.setText("Microsoft Entra ID app registration");
        info.setLayout(new org.eclipse.swt.layout.GridLayout());
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false);
        gd.horizontalSpan = 3;
        info.setLayoutData(gd);

        Label note = new Label(info, SWT.WRAP);
        note.setText("Sign-in uses the native app registration of your Microsoft 365 tenant.\n"
                + "Register a 'Mobile and desktop applications' app in Entra ID with:\n"
                + "  - Redirect URI: http://localhost\n"
                + "  - 'Allow public client flows': enabled\n"
                + "  - Delegated Microsoft Graph permissions: Sites.ReadWrite.All, "
                + "Files.ReadWrite.All, offline_access, User.Read\n"
                + "Then paste the application (client) ID above.");
        GridData noteData = new GridData(SWT.FILL, SWT.TOP, true, false);
        noteData.widthHint = 420;
        note.setLayoutData(noteData);
    }
}
