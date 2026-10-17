package com.archimatetool.sharepoint.prefs;

/**
 * Preference key constants for the SharePoint sync plugin.
 * Stored in the instance-scope preference node
 * {@code "com.archimatetool.sharepoint"}.
 */
public final class PreferenceConstants {

    /** Preference node / bundle id. */
    public static final String PLUGIN_ID = "com.archimatetool.sharepoint";

    /** Entra ID app registration (application) client ID. */
    public static final String P_CLIENT_ID = "clientId";

    /** Entra tenant id or alias; default {@code "organizations"}. */
    public static final String P_TENANT = "tenantId";

    /** Autosave interval in seconds; default 60. */
    public static final String P_AUTOSAVE_SEC = "autosaveSec";

    /** Remote poll interval in seconds; default 30. */
    public static final String P_POLL_SEC = "pollSec";

    /** Reload remote changes automatically when local copy is clean; default true. */
    public static final String P_AUTO_RELOAD = "autoReload";

    /** Check the file out on edit / check in on close; default false. */
    public static final String P_CHECKOUT_ON_EDIT = "checkOutOnEdit";

    /** Last SharePoint site URL used in the connect wizard (prefill). */
    public static final String P_LAST_SITE_URL = "lastSiteUrl";

    /** Last folder path used in the connect wizard (prefill). */
    public static final String P_LAST_FOLDER_PATH = "lastFolderPath";

    /** Default value for {@link #P_TENANT}. */
    public static final String DEFAULT_TENANT = "organizations";

    /** Default value for {@link #P_AUTOSAVE_SEC}. */
    public static final int DEFAULT_AUTOSAVE_SEC = 60;

    /** Default value for {@link #P_POLL_SEC}. */
    public static final int DEFAULT_POLL_SEC = 30;

    private PreferenceConstants() {
        // constants only
    }
}
