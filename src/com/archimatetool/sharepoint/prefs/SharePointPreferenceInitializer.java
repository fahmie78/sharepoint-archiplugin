package com.archimatetool.sharepoint.prefs;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

/**
 * Initializes default values for the SharePoint sync plugin preferences.
 * Registered through the {@code org.eclipse.core.runtime.preferences} extension point.
 * <p>
 * IMPORTANT: defaults MUST be written to the {@link DefaultScope} node, never to
 * {@code InstanceScope}. Writing to InstanceScope would (a) persist the defaults as if
 * they were user settings and (b) overwrite the user's stored values every time the
 * initializer runs (i.e. after every restart), which resets the preference page.
 */
public class SharePointPreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences node = DefaultScope.INSTANCE.getNode(PreferenceConstants.PLUGIN_ID);
        node.put(PreferenceConstants.P_CLIENT_ID, "");
        node.put(PreferenceConstants.P_TENANT, PreferenceConstants.DEFAULT_TENANT);
        node.putInt(PreferenceConstants.P_AUTOSAVE_SEC, PreferenceConstants.DEFAULT_AUTOSAVE_SEC);
        node.putInt(PreferenceConstants.P_POLL_SEC, PreferenceConstants.DEFAULT_POLL_SEC);
        node.putBoolean(PreferenceConstants.P_AUTO_RELOAD, true);
        node.putBoolean(PreferenceConstants.P_CHECKOUT_ON_EDIT, false);
    }
}
