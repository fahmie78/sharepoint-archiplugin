package com.archimatetool.sharepoint;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.archimatetool.sharepoint.sync.SyncEngine;

/**
 * Thin activator for the SharePoint sync plugin (bundle id
 * {@code com.archimatetool.sharepoint}).
 * <p>
 * On start it initializes the {@link SyncEngine} singleton (which registers its
 * model-manager listeners); on stop it shuts the engine down. All heavier
 * wiring (dialogs, persisted bindings) is done by the early-startup class.
 */
public class SharePointPlugin extends AbstractUIPlugin {

    /** Bundle symbolic name. */
    public static final String PLUGIN_ID = "com.archimatetool.sharepoint";

    private static SharePointPlugin instance;

    /**
     * @return the shared activator instance, or null if the bundle is not started.
     */
    public static SharePointPlugin getDefault() {
        return instance;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        // Initialize the sync engine so its listeners/timers are ready.
        SyncEngine.get();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        try {
            SyncEngine.get().shutdown();
        }
        catch(Throwable t) {
            // never fail bundle stop
            t.printStackTrace();
        }
        finally {
            instance = null;
            super.stop(context);
        }
    }
}
