package com.archimatetool.sharepoint.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * Persists {@link ModelBinding}s across restarts. Bindings are stored as a JSON array
 * (Gson) in the instance-scope preference key {@code "bindings"} of node
 * {@code "com.archimatetool.sharepoint"}.
 * <p>
 * All methods are static, synchronized and safe to call from any thread.
 * </p>
 */
public final class BindingStore {

    private static final String NODE = "com.archimatetool.sharepoint";
    private static final String KEY = "bindings";
    private static final Gson GSON = new Gson();

    private BindingStore() {
    }

    /**
     * Loads all persisted bindings. The {@link ModelBinding#model} reference is not set;
     * bindings are re-attached to models by the {@link SyncEngine} when matching models open.
     *
     * @return the persisted bindings (never {@code null}); corrupted data yields an empty list
     */
    public static synchronized List<ModelBinding> load() {
        String json = node().get(KEY, null);
        List<ModelBinding> result = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            try {
                ModelBinding[] parsed = GSON.fromJson(json, ModelBinding[].class);
                if (parsed != null) {
                    Collections.addAll(result, parsed);
                }
            } catch (JsonSyntaxException e) {
                // corrupted preference - start over with an empty list
            }
        }
        return result;
    }

    /**
     * Replaces the persisted bindings with the given list.
     *
     * @param bindings the bindings to store
     */
    public static synchronized void save(List<ModelBinding> bindings) {
        IEclipsePreferences node = node();
        if (bindings == null || bindings.isEmpty()) {
            node.remove(KEY);
        } else {
            node.put(KEY, GSON.toJson(bindings));
        }
        flush(node);
    }

    /**
     * Inserts or replaces the binding with the same {@code localFilePath}.
     *
     * @param binding the binding to store
     */
    public static synchronized void upsert(ModelBinding binding) {
        if (binding == null) {
            return;
        }
        List<ModelBinding> bindings = load();
        boolean replaced = false;
        for (int i = 0; i < bindings.size(); i++) {
            if (Objects.equals(bindings.get(i).localFilePath, binding.localFilePath)) {
                bindings.set(i, binding);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            bindings.add(binding);
        }
        save(bindings);
    }

    /**
     * Removes the binding with the given local file path, if present.
     *
     * @param localFilePath the local file path identifying the binding
     */
    public static synchronized void remove(String localFilePath) {
        List<ModelBinding> bindings = load();
        if (bindings.removeIf(b -> Objects.equals(b.localFilePath, localFilePath))) {
            save(bindings);
        }
    }

    private static IEclipsePreferences node() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    private static void flush(IEclipsePreferences node) {
        try {
            node.flush();
        } catch (BackingStoreException ignored) {
            // best-effort persistence
        }
    }
}
