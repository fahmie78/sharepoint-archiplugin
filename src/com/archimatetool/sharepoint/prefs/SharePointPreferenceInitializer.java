package com.archimatetool.sharepoint.prefs;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.Preferences;

/**
 * Initializes default values for the SharePoint sync plugin preferences.
 * Registered through the {@code org.eclipse.core.runtime.preferences} extension point.
 */
public class SharePointPreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        Preferences node = InstanceScope.INSTANCE.getNode(PreferenceConstants.PLUGIN_ID);
        node.put(PreferenceConstants.P_CLIENT_ID, "");
        node.put(PreferenceConstants.P_TENANT, PreferenceConstants.DEFAULT_TENANT);
        node.putInt(PreferenceConstants.P_AUTOSAVE_SEC, PreferenceConstants.DEFAULT_AUTOSAVE_SEC);
        node.putInt(PreferenceConstants.P_POLL_SEC, PreferenceConstants.DEFAULT_POLL_SEC);
        node.putBoolean(PreferenceConstants.P_AUTO_RELOAD, true);
        node.putBoolean(PreferenceConstants.P_CHECKOUT_ON_EDIT, false);
    }
}
