package com.archimatetool.sharepoint.prefs;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.Preferences;

/**
 * Static accessors for the SharePoint sync plugin preferences.
 * <p>
 * Reads from {@code InstanceScope.INSTANCE.getNode("com.archimatetool.sharepoint")}
 * applying the defaults defined in {@link PreferenceConstants}. This class has no
 * dependency on the UI activator so it can be used from any bundle class.
 */
public final class Prefs {

    private Prefs() {
        // static accessors only
    }

    private static Preferences node() {
        return InstanceScope.INSTANCE.getNode(PreferenceConstants.PLUGIN_ID);
    }

    /**
     * @return the Entra ID app registration client ID, or an empty string when not configured.
     */
    public static String getClientId() {
        return node().get(PreferenceConstants.P_CLIENT_ID, "");
    }

    /**
     * @return the Entra tenant id or alias; defaults to {@code "organizations"}.
     */
    public static String getTenant() {
        String tenant = node().get(PreferenceConstants.P_TENANT, PreferenceConstants.DEFAULT_TENANT);
        return tenant == null || tenant.isBlank() ? PreferenceConstants.DEFAULT_TENANT : tenant.trim();
    }

    /**
     * @return the autosave interval in seconds; defaults to 60.
     */
    public static int getAutosaveSec() {
        int v = node().getInt(PreferenceConstants.P_AUTOSAVE_SEC, PreferenceConstants.DEFAULT_AUTOSAVE_SEC);
        return v < 1 ? PreferenceConstants.DEFAULT_AUTOSAVE_SEC : v;
    }

    /**
     * @return the remote poll interval in seconds; defaults to 30.
     */
    public static int getPollSec() {
        int v = node().getInt(PreferenceConstants.P_POLL_SEC, PreferenceConstants.DEFAULT_POLL_SEC);
        return v < 1 ? PreferenceConstants.DEFAULT_POLL_SEC : v;
    }

    /**
     * @return true when remote changes should be reloaded automatically while the local copy is clean.
     */
    public static boolean isAutoReload() {
        return node().getBoolean(PreferenceConstants.P_AUTO_RELOAD, true);
    }

    /**
     * @return true when the remote file should be checked out on edit and checked in on close.
     */
    public static boolean isCheckOutOnEdit() {
        return node().getBoolean(PreferenceConstants.P_CHECKOUT_ON_EDIT, false);
    }
}
