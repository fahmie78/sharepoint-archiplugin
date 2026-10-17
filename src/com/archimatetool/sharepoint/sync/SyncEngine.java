package com.archimatetool.sharepoint.sync;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.auth.AuthService;
import com.archimatetool.sharepoint.graph.DriveItem;
import com.archimatetool.sharepoint.graph.GraphAuthException;
import com.archimatetool.sharepoint.graph.GraphClient;
import com.archimatetool.sharepoint.graph.GraphException;
import com.archimatetool.sharepoint.graph.NotFoundException;
import com.archimatetool.sharepoint.graph.PreconditionFailedException;
import com.archimatetool.sharepoint.prefs.Prefs;

/**
 * Word-on-SharePoint-like sync engine connecting open ArchiMate models to files in a
 * SharePoint document library.
 * <p>
 * Behaviour:
 * </p>
 * <ul>
 * <li>Autosave: every {@code Prefs.getAutosaveSec()} seconds a dirty bound model is saved
 * locally and uploaded with {@code If-Match} optimistic concurrency.</li>
 * <li>Polling: every {@code Prefs.getPollSec()} seconds the remote item is checked; remote
 * changes trigger a reload (auto or prompted) or, when the local model is dirty, the
 * registered {@link ConflictResolver}.</li>
 * <li>Optional SharePoint check-out on edit / check-in on unbind.</li>
 * </ul>
 * <p>
 * All Graph/network work runs on a single daemon {@link ScheduledExecutorService}; model
 * manager mutations and user prompts run on the SWT UI thread. Listener callbacks may fire
 * on any thread (see {@link SyncListener}). Errors inside scheduled tasks are caught and
 * logged, never propagated.
 * </p>
 */
public final class SyncEngine {

    private static final String BUNDLE_ID = "com.archimatetool.sharepoint";

    /** Granularity of the internal tick; actual intervals come from the preferences. */
    private static final long TICK_SECONDS = 5;

    private static SyncEngine instance;

    /**
     * @return the lazily created singleton; first call registers the model-manager listener
     *         and starts the scheduler
     */
    public static synchronized SyncEngine get() {
        if (instance == null) {
            instance = new SyncEngine();
        }
        return instance;
    }

    /** Mutable per-binding runtime state. */
    private static final class BindingState {
        /** Swapped atomically when the model is re-bound to a different remote file. */
        volatile ModelBinding binding;
        /** Held while any Graph operation for this binding runs (never two concurrently). */
        final AtomicBoolean syncLock = new AtomicBoolean();
        volatile boolean dirty;
        volatile boolean checkedOut;
        /** True while a close/write/reopen reload is in flight (listener must not drop us). */
        volatile boolean reloading;
        volatile long lastAutosaveCheck;
        volatile long lastPoll;
        /** Remote ETag the user declined to reload/resolve; suppresses repeated prompts. */
        volatile String declinedETag;
        volatile SyncStatus lastStatus;
        volatile String lastMessage;

        BindingState(ModelBinding binding) {
            this.binding = binding;
            // restore the persisted check-out flag so the menu toggle survives restarts
            this.checkedOut = binding.checkedOut;
        }
    }

    /**
     * Sets the check-out flag on both the runtime state and the persisted binding
     * (Graph v1.0 cannot reliably report check-out state, so the persisted flag is
     * the cross-restart source of truth).
     */
    private void setCheckedOut(BindingState state, boolean value) {
        state.checkedOut = value;
        state.binding.checkedOut = value;
        BindingStore.upsert(state.binding);
    }

    /** A runnable executed on the UI thread that may fail. */
    private interface UiCall {
        void run() throws Exception;
    }

    private final ScheduledExecutorService scheduler;
    private final Map<IArchimateModel, BindingState> active = new HashMap<>();
    private final List<SyncListener> listeners = new CopyOnWriteArrayList<>();
    private final PropertyChangeListener modelListener = this::onModelEvent;

    private volatile ConflictResolver conflictResolver;
    private volatile ReloadPrompter reloadPrompter;
    private volatile GraphClient graphClient;
    private volatile boolean shutdown;

    private SyncEngine() {
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "sharepoint-sync");
            thread.setDaemon(true);
            return thread;
        });
        IEditorModelManager.INSTANCE.addPropertyChangeListener(modelListener);
        scheduler.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ configuration

    /**
     * Sets the resolver invoked (on the UI thread) when a sync conflict needs a user decision.
     *
     * @param resolver the resolver, or {@code null} to always postpone conflicts
     */
    public void setConflictResolver(ConflictResolver resolver) {
        this.conflictResolver = resolver;
    }

    /**
     * Sets the prompter invoked (on the UI thread) when a remote change could be reloaded
     * and automatic reload is disabled.
     *
     * @param prompter the prompter, or {@code null} to never reload on prompt
     */
    public void setReloadPrompter(ReloadPrompter prompter) {
        this.reloadPrompter = prompter;
    }

    /**
     * Adds a status listener. Listeners are notified on arbitrary threads.
     *
     * @param listener the listener to add
     */
    public void addListener(SyncListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a status listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(SyncListener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ public API

    /**
     * Binds an open model to a SharePoint file and starts autosave/polling for it.
     * The binding is persisted and an initial remote check is scheduled.
     *
     * @param model   the open model (must not be {@code null})
     * @param binding the binding describing the remote file (must not be {@code null})
     */
    public void bind(IArchimateModel model, ModelBinding binding) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(binding, "binding");
        if (shutdown) {
            return;
        }
        binding.model = model;
        File file = model.getFile();
        if (binding.localFilePath == null && file != null) {
            binding.localFilePath = file.getAbsolutePath();
        }
        BindingState state;
        synchronized (active) {
            BindingState existing = active.get(model);
            if (existing == null) {
                existing = new BindingState(binding);
                active.put(model, existing);
            } else if (existing.binding != binding) {
                // re-binding to a (possibly different) remote file: replace the binding
                // atomically and reset the guards tied to the previous remote file
                existing.binding = binding;
                existing.declinedETag = null;
                existing.dirty = false;
                existing.checkedOut = false;
                existing.lastStatus = null;
                existing.lastMessage = null;
            }
            state = existing;
        }
        BindingStore.upsert(binding);
        setStatus(state, SyncStatus.IDLE, "Connected to SharePoint");
        if (Prefs.isCheckOutOnEdit()) {
            schedule(() -> checkOutUnderLock(state));
        }
        // initial remote check
        schedule(() -> doPoll(state));
    }

    /**
     * Unbinds a model: stops syncing it, persists the binding, and checks the remote file
     * back in if this session checked it out.
     *
     * @param model the model to unbind
     */
    public void unbind(IArchimateModel model) {
        if (model == null) {
            return;
        }
        BindingState state;
        synchronized (active) {
            state = active.remove(model);
        }
        if (state == null) {
            return;
        }
        state.binding.model = null;
        if (state.checkedOut) {
            state.checkedOut = false;
            state.binding.checkedOut = false;
            ModelBinding binding = state.binding;
            schedule(() -> checkInQuietly(binding));
        }
        BindingStore.upsert(state.binding);
        setStatus(state, SyncStatus.IDLE, "Disconnected");
    }

    /**
     * @param model an open model
     * @return its binding, or {@code null} if the model is not connected
     */
    public ModelBinding bindingFor(IArchimateModel model) {
        BindingState state = stateFor(model);
        return state != null ? state.binding : null;
    }

    /**
     * Saves the model locally and uploads it to SharePoint immediately, regardless of the
     * autosave interval. Runs asynchronously.
     *
     * @param model the bound model
     */
    public void syncNow(IArchimateModel model) {
        BindingState state = stateFor(model);
        if (state != null) {
            schedule(() -> doSync(state, false));
        }
    }

    /**
     * Reports whether the bound SharePoint file is checked out to the current signed-in
     * user. The flag is kept in sync with the server by the poll task and by the
     * check-out/check-in/discard operations.
     *
     * @param model an open model
     * @return {@code true} when the bound file is checked out to the current user
     */
    public boolean isCheckedOut(IArchimateModel model) {
        BindingState state = stateFor(model);
        return state != null && state.checkedOut;
    }

    /**
     * Checks the bound SharePoint file out to the current user. Runs asynchronously under
     * the per-binding lock; the outcome is reported via status listeners.
     *
     * @param model the bound model
     */
    public void checkOutNow(IArchimateModel model) {
        BindingState state = stateFor(model);
        if (state != null) {
            schedule(() -> doCheckOut(state));
        }
    }

    /**
     * Checks the bound SharePoint file back in. Runs asynchronously under the
     * per-binding lock; the outcome is reported via status listeners.
     *
     * @param model the bound model
     */
    public void checkInNow(IArchimateModel model) {
        checkInNow(model, "Checked in from Archi");
    }

    /**
     * Checks the bound SharePoint file back in with the given comment. Runs
     * asynchronously under the per-binding lock; the outcome is reported via
     * status listeners.
     *
     * @param model   the bound model
     * @param comment the check-in comment; blank falls back to a default
     */
    public void checkInNow(IArchimateModel model, String comment) {
        BindingState state = stateFor(model);
        if (state != null) {
            String text = comment == null || comment.isBlank() ? "Checked in from Archi" : comment;
            schedule(() -> doCheckIn(state, text));
        }
    }

    /**
     * Discards the check-out of the bound SharePoint file, abandoning any changes made
     * to the server file while it was checked out. Runs asynchronously under the
     * per-binding lock; the outcome is reported via status listeners.
     *
     * @param model the bound model
     */
    public void discardCheckOutNow(IArchimateModel model) {
        BindingState state = stateFor(model);
        if (state != null) {
            schedule(() -> doDiscardCheckOut(state));
        }
    }

    /**
     * Re-evaluates all currently open models against the persisted bindings.
     * Called at startup to catch models that were already open before the
     * engine's model listener was registered (their OPENED events were missed).
     */
    public void rescanOpenModels() {
        if (shutdown) {
            return;
        }
        for (IArchimateModel model : IEditorModelManager.INSTANCE.getModels()) {
            try {
                onModelOpened(model);
            } catch (Throwable t) {
                log("Failed to restore binding for an open model", t);
            }
        }
    }

    /**
     * Stops the engine: unregisters the model listener and shuts the scheduler down.
     * All threads are daemon threads, so nothing is left running afterwards.
     */
    public void shutdown() {
        shutdown = true;
        try {
            IEditorModelManager.INSTANCE.removePropertyChangeListener(modelListener);
        } catch (Throwable t) {
            log("Failed to remove model listener", t);
        }
        synchronized (active) {
            active.clear();
        }
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ model events

    private void onModelEvent(PropertyChangeEvent event) {
        try {
            String property = event.getPropertyName();
            if (IEditorModelManager.COMMAND_STACK_CHANGED.equals(property)) {
                if (event.getSource() instanceof IArchimateModel model) {
                    BindingState state = stateFor(model);
                    if (state != null) {
                        state.dirty = true;
                    }
                }
            } else if (IEditorModelManager.PROPERTY_MODEL_REMOVED.equals(property)) {
                if (event.getNewValue() instanceof IArchimateModel model) {
                    onModelRemoved(model);
                }
            } else if (IEditorModelManager.PROPERTY_MODEL_OPENED.equals(property)) {
                if (event.getNewValue() instanceof IArchimateModel model) {
                    onModelOpened(model);
                }
            }
        } catch (Throwable t) {
            log("Error handling model event", t);
        }
    }

    private void onModelRemoved(IArchimateModel model) {
        BindingState state;
        synchronized (active) {
            state = active.get(model);
            if (state != null && !state.reloading) {
                active.remove(model);
            }
        }
        if (state != null && !state.reloading) {
            // keep the binding persisted so a reopened model reconnects automatically
            state.binding.model = null;
            if (state.checkedOut) {
                state.checkedOut = false;
                state.binding.checkedOut = false;
                schedule(() -> checkInQuietly(state.binding));
            }
            BindingStore.upsert(state.binding);
        }
    }

    private void onModelOpened(IArchimateModel model) {
        File file = model.getFile();
        if (file == null) {
            return;
        }
        String path = file.getAbsolutePath();
        synchronized (active) {
            if (active.containsKey(model)) {
                return;
            }
            // adopt a model reopened by our own reload into its existing state
            for (Map.Entry<IArchimateModel, BindingState> entry : active.entrySet()) {
                BindingState state = entry.getValue();
                if (state.reloading && path.equals(state.binding.localFilePath)) {
                    active.remove(entry.getKey());
                    state.binding.model = model;
                    active.put(model, state);
                    return;
                }
            }
        }
        // rebind automatically from the persisted store
        for (ModelBinding stored : BindingStore.load()) {
            if (path.equals(stored.localFilePath)) {
                bind(model, stored);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ periodic tasks

    private void tick() {
        try {
            if (shutdown) {
                return;
            }
            long now = System.currentTimeMillis();
            List<BindingState> snapshot;
            synchronized (active) {
                snapshot = new ArrayList<>(active.values());
            }
            long autosaveMs = Math.max(10, Prefs.getAutosaveSec()) * 1000L;
            long pollMs = Math.max(10, Prefs.getPollSec()) * 1000L;
            for (BindingState state : snapshot) {
                try {
                    if (state.binding.model == null) {
                        continue;
                    }
                    if (state.dirty && !state.syncLock.get()
                            && now - state.lastAutosaveCheck >= autosaveMs) {
                        state.lastAutosaveCheck = now;
                        schedule(() -> doSync(state, true));
                    }
                    if (!state.syncLock.get() && now - state.lastPoll >= pollMs) {
                        state.lastPoll = now;
                        schedule(() -> doPoll(state));
                    }
                } catch (Throwable t) {
                    log("Sync tick failed", t);
                }
            }
        } catch (Throwable t) {
            log("Sync tick failed", t);
        }
    }

    // ------------------------------------------------------------------ sync operations

    /**
     * Saves and uploads the model. When {@code onlyIfDirty} is set the operation is
     * skipped unless the model actually has unsaved changes.
     */
    private void doSync(BindingState state, boolean onlyIfDirty) {
        ModelBinding binding = state.binding;
        IArchimateModel model = binding.model;
        if (shutdown || model == null) {
            return;
        }
        if (!state.syncLock.compareAndSet(false, true)) {
            return;
        }
        try {
            if (binding.driveId == null || binding.itemPath == null) {
                setStatus(state, SyncStatus.ERROR, "Incomplete SharePoint binding");
                return;
            }
            if (model.getFile() == null) {
                setStatus(state, SyncStatus.ERROR,
                        "Save the model to a local file before syncing");
                return;
            }
            binding.localFilePath = model.getFile().getAbsolutePath();
            if (onlyIfDirty && !isModelDirty(model)) {
                state.dirty = false;
                return;
            }
            setStatus(state, SyncStatus.SYNCING, "Saving and uploading…");
            // clear dirty BEFORE saving so an edit made during save/upload is not clobbered
            state.dirty = false;
            saveModelOnUi(model);
            File file = model.getFile();
            if (file == null) {
                setStatus(state, SyncStatus.ERROR,
                        "Save the model to a local file before syncing");
                return;
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            ensureCheckedOut(state);
            DriveItem uploaded = graph().uploadByPath(binding.driveId, binding.itemPath, bytes,
                    binding.lastETag);
            // re-check on the UI thread: an edit during save/upload re-marks the model dirty
            state.dirty = isModelDirty(model);
            afterUploadSuccess(state, uploaded, "Uploaded " + file.getName());
        } catch (PreconditionFailedException e) {
            try {
                resolveAfterPreconditionFailed(state);
            } catch (Throwable t) {
                handleError(state, t);
            }
        } catch (Throwable t) {
            handleError(state, t);
        } finally {
            state.syncLock.set(false);
        }
    }

    /** Polls the remote item and reacts to changes (reload, prompt, or conflict). */
    private void doPoll(BindingState state) {
        state.lastPoll = System.currentTimeMillis();
        ModelBinding binding = state.binding;
        if (shutdown || binding.model == null || binding.driveId == null
                || binding.itemPath == null) {
            return;
        }
        if (!state.syncLock.compareAndSet(false, true)) {
            return;
        }
        try {
            DriveItem remote = graph().getItem(binding.driveId, binding.itemPath);
            updateCheckedOutFromRemote(state, remote);
            if (remote.id != null) {
                binding.itemId = remote.id;
            }
            String remoteETag = remote.eTag;
            if (remoteETag == null || remoteETag.equals(binding.lastETag)) {
                if (state.lastStatus == SyncStatus.OFFLINE || state.lastStatus == SyncStatus.ERROR) {
                    setStatus(state, SyncStatus.IDLE, "Connected");
                }
                return;
            }
            if (remoteETag.equals(state.declinedETag)) {
                return; // user already declined this remote version
            }
            SyncContext context = new SyncContext(binding, remoteETag, remote.lastModifiedBy,
                    remote.lastModified);
            if (isModelDirty(binding.model)) {
                setStatus(state, SyncStatus.CONFLICT, conflictMessage(remote));
                Resolution resolution = askResolver(context);
                handleResolution(state, resolution, remote);
            } else if (Prefs.isAutoReload()) {
                reloadFromRemote(state, remote);
            } else if (askPrompter(context)) {
                reloadFromRemote(state, remote);
            } else {
                state.declinedETag = remoteETag;
                setStatus(state, SyncStatus.IDLE, "Remote copy changed; reload declined");
            }
        } catch (Throwable t) {
            handleError(state, t);
        } finally {
            state.syncLock.set(false);
        }
    }

    /** Conflict flow after an upload failed with HTTP 412. */
    private void resolveAfterPreconditionFailed(BindingState state)
            throws GraphException, IOException {
        ModelBinding binding = state.binding;
        DriveItem remote = graph().getItem(binding.driveId, binding.itemPath);
        if (remote.eTag != null && remote.eTag.equals(binding.lastETag)) {
            // stale precondition failure: the remote copy still matches our last sync
            setStatus(state, SyncStatus.UP_TO_DATE, "Remote copy unchanged");
            return;
        }
        setStatus(state, SyncStatus.CONFLICT, conflictMessage(remote));
        Resolution resolution = askResolver(new SyncContext(binding, remote.eTag,
                remote.lastModifiedBy, remote.lastModified));
        handleResolution(state, resolution, remote);
    }

    private void handleResolution(BindingState state, Resolution resolution, DriveItem remote)
            throws GraphException, IOException {
        switch (resolution != null ? resolution : Resolution.POSTPONE) {
            case KEEP_MINE -> forceUpload(state, remote.eTag);
            case LOAD_THEIRS -> reloadFromRemote(state, remote);
            case SAVE_COPY_THEN_LOAD_THEIRS -> {
                saveConflictCopy(state);
                reloadFromRemote(state, remote);
            }
            case POSTPONE -> {
                // stay CONFLICT; the next autosave tick retries (poll must not re-prompt
                // for the same remote version). Re-mark dirty: doSync cleared the flag
                // before the upload, so without this no autosave retry would ever happen.
                state.declinedETag = remote.eTag;
                state.dirty = true;
            }
            default -> {
                // unreachable
            }
        }
    }

    /** Saves the local model and overwrites the remote file using the given ETag. */
    private void forceUpload(BindingState state, String remoteETag)
            throws GraphException, IOException {
        ModelBinding binding = state.binding;
        IArchimateModel model = binding.model;
        if (model == null || model.getFile() == null) {
            setStatus(state, SyncStatus.ERROR, "Save the model to a local file before syncing");
            return;
        }
        setStatus(state, SyncStatus.SYNCING, "Overwriting remote copy…");
        // clear dirty BEFORE saving so an edit made during save/upload is not clobbered
        state.dirty = false;
        saveModelOnUi(model);
        byte[] bytes = Files.readAllBytes(model.getFile().toPath());
        DriveItem uploaded = graph().uploadByPath(binding.driveId, binding.itemPath, bytes,
                remoteETag);
        // re-check on the UI thread: an edit during save/upload re-marks the model dirty
        state.dirty = isModelDirty(model);
        afterUploadSuccess(state, uploaded, "Kept local version (remote overwritten)");
    }

    /**
     * Downloads the remote file and reloads the model:
     * close (discarding local changes) → overwrite local file → reopen → rebind.
     */
    private void reloadFromRemote(BindingState state, DriveItem remote)
            throws GraphException, IOException {
        ModelBinding binding = state.binding;
        IArchimateModel model = binding.model;
        String itemId = remote.id != null ? remote.id : binding.itemId;
        if (model == null || itemId == null) {
            setStatus(state, SyncStatus.ERROR, "Cannot reload: model or remote item missing");
            return;
        }
        File localFile = binding.localFile() != null ? binding.localFile() : model.getFile();
        if (localFile == null) {
            setStatus(state, SyncStatus.ERROR, "Save the model to a local file before syncing");
            return;
        }
        setStatus(state, SyncStatus.SYNCING, "Downloading remote copy…");
        byte[] bytes = graph().download(binding.driveId, itemId);
        state.reloading = true;
        boolean[] closed = { false };
        try {
            File target = localFile;
            runOnUiAndWait(() -> {
                boolean wasClosed = IEditorModelManager.INSTANCE.closeModel(model, false);
                if (!wasClosed) {
                    throw new IOException("Model could not be closed");
                }
                closed[0] = true;
                Files.write(target.toPath(), bytes);
                IArchimateModel reopened = IEditorModelManager.INSTANCE.openModel(target);
                synchronized (active) {
                    active.remove(model);
                    binding.model = reopened;
                    if (reopened != null) {
                        active.put(reopened, state);
                    }
                }
            });
        } catch (Exception e) {
            if (closed[0]) {
                // close succeeded but write/reopen failed: the model's REMOVED event was
                // suppressed by the reloading flag, so drop the dead state here, detach
                // the binding from the closed model and let the persisted binding rebind
                // on the next PROPERTY_MODEL_OPENED
                synchronized (active) {
                    active.remove(model, state);
                }
                binding.model = null;
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IOException("Reload failed", e);
        } finally {
            state.reloading = false;
        }
        if (binding.model == null) {
            setStatus(state, SyncStatus.ERROR, "Reload failed: model could not be reopened");
            return;
        }
        if (remote.eTag != null) {
            binding.lastETag = remote.eTag;
        }
        binding.localFilePath = localFile.getAbsolutePath();
        state.dirty = false;
        state.declinedETag = null;
        BindingStore.upsert(binding);
        setStatus(state, SyncStatus.UP_TO_DATE, "Reloaded remote version");
    }

    /** Copies the local file to {@code <name>.conflict-YYYYMMDD-HHmmss.archimate}. */
    private void saveConflictCopy(BindingState state) throws IOException {
        File local = state.binding.localFile();
        if (local == null || !local.isFile()) {
            return;
        }
        String name = local.getName();
        String base = name.endsWith(".archimate")
                ? name.substring(0, name.length() - ".archimate".length()) : name;
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File copy = new File(local.getParentFile(),
                base + ".conflict-" + timestamp + ".archimate");
        Files.copy(local.toPath(), copy.toPath());
    }

    private void afterUploadSuccess(BindingState state, DriveItem uploaded, String message) {
        ModelBinding binding = state.binding;
        if (uploaded.eTag != null) {
            binding.lastETag = uploaded.eTag;
        }
        if (uploaded.id != null) {
            binding.itemId = uploaded.id;
        }
        state.declinedETag = null;
        BindingStore.upsert(binding);
        setStatus(state, SyncStatus.UP_TO_DATE, message);
    }

    // ------------------------------------------------------------------ check-out

    private void ensureCheckedOut(BindingState state) {
        if (state.checkedOut || !Prefs.isCheckOutOnEdit()) {
            return;
        }
        checkOutQuietly(state);
    }

    /**
     * Runs {@link #checkOutQuietly(BindingState)} under the per-binding sync lock so it
     * cannot race a {@code doPoll}/{@code doSync} Graph call for the same binding.
     * If a sync is already in progress the check-out is skipped; the upload path calls
     * {@link #ensureCheckedOut(BindingState)} before uploading anyway.
     */
    private void checkOutUnderLock(BindingState state) {
        if (!state.syncLock.compareAndSet(false, true)) {
            return;
        }
        try {
            checkOutQuietly(state);
        } finally {
            state.syncLock.set(false);
        }
    }

    private void checkOutQuietly(BindingState state) {
        ModelBinding binding = state.binding;
        if (binding.itemId == null || binding.driveId == null) {
            return;
        }
        try {
            graph().checkOut(binding.driveId, binding.itemId);
            setCheckedOut(state, true);
        } catch (GraphException e) {
            // 409 = already checked out (possibly by someone else); swallow/log either way
            log("Check-out failed (" + e.statusCode + "): " + e.getMessage(), null);
        }
    }

    private void checkInQuietly(ModelBinding binding) {
        if (binding.itemId == null || binding.driveId == null) {
            return;
        }
        try {
            graph().checkIn(binding.driveId, binding.itemId, "Autosaved by Archi");
        } catch (GraphException e) {
            // 409 = not checked out; swallow/log either way
            log("Check-in failed (" + e.statusCode + "): " + e.getMessage(), null);
        }
    }

    /**
     * Reconciles the local check-out flag with the remote publication facet. The flag is
     * only set when the remote check-out belongs to the current signed-in user; a remote
     * check-out by somebody else never flips {@code state.checkedOut} to {@code true}.
     * <p>
     * Graph v1.0 usually omits the publication facet entirely
     * ({@link DriveItem#publicationKnown} {@code == false}); in that case the locally
     * tracked (and persisted) state is kept untouched.
     */
    private void updateCheckedOutFromRemote(BindingState state, DriveItem remote) {
        if (remote == null || !remote.publicationKnown) {
            return;
        }
        boolean was = state.checkedOut;
        if (!remote.checkedOut) {
            setCheckedOut(state, false);
        } else {
            String checkedOutBy = remote.checkedOutBy;
            if (checkedOutBy != null && !checkedOutBy.isBlank()) {
                String user = AuthService.get().getSignedInUser();
                setCheckedOut(state, user != null && checkedOutBy.equalsIgnoreCase(user));
            } else {
                // unknown holder: only keep counting it as ours if we already hold it
                // locally (e.g. we just checked it out ourselves)
            }
        }
        if (state.checkedOut != was) {
            fireStatus(state);
        }
    }

    private void doCheckOut(BindingState state) {
        ModelBinding binding = state.binding;
        if (shutdown || binding.model == null) {
            return;
        }
        if (!state.syncLock.compareAndSet(false, true)) {
            return; // a sync is in progress; the next poll keeps the state fresh
        }
        try {
            if (binding.driveId == null || binding.itemId == null) {
                setStatus(state, SyncStatus.ERROR,
                        "Not connected to an existing SharePoint file");
                return;
            }
            graph().checkOut(binding.driveId, binding.itemId);
            setCheckedOut(state, true);
            setStatus(state, SyncStatus.IDLE, "Checked out " + binding.itemPath);
        } catch (Throwable t) {
            if (t instanceof GraphException ge && ge.statusCode == 400 && ge.getMessage() != null
                    && ge.getMessage().toLowerCase().contains("already checked out")) {
                // already checked out to us (e.g. from an earlier session): adopt the state
                setCheckedOut(state, true);
                setStatus(state, SyncStatus.IDLE,
                        "Already checked out to you: " + binding.itemPath);
            } else {
                handleError(state, t);
            }
        } finally {
            state.syncLock.set(false);
        }
    }

    private void doCheckIn(BindingState state, String comment) {
        ModelBinding binding = state.binding;
        if (shutdown || binding.model == null) {
            return;
        }
        if (!state.syncLock.compareAndSet(false, true)) {
            return;
        }
        try {
            if (binding.driveId == null || binding.itemId == null) {
                setStatus(state, SyncStatus.ERROR,
                        "Not connected to an existing SharePoint file");
                return;
            }
            graph().checkIn(binding.driveId, binding.itemId, comment);
            setCheckedOut(state, false);
            setStatus(state, SyncStatus.IDLE, "Checked in " + binding.itemPath);
        } catch (Throwable t) {
            handleError(state, t);
        } finally {
            state.syncLock.set(false);
        }
    }

    private void doDiscardCheckOut(BindingState state) {
        ModelBinding binding = state.binding;
        if (shutdown || binding.model == null) {
            return;
        }
        if (!state.syncLock.compareAndSet(false, true)) {
            return;
        }
        try {
            if (binding.driveId == null || binding.itemId == null) {
                setStatus(state, SyncStatus.ERROR,
                        "Not connected to an existing SharePoint file");
                return;
            }
            graph().discardCheckOut(binding.driveId, binding.itemId);
            setCheckedOut(state, false);
            setStatus(state, SyncStatus.IDLE, "Discarded check-out of " + binding.itemPath);
        } catch (Throwable t) {
            handleError(state, t);
        } finally {
            state.syncLock.set(false);
        }
    }

    // ------------------------------------------------------------------ user interaction

    private Resolution askResolver(SyncContext context) {
        ConflictResolver resolver = conflictResolver;
        if (resolver == null) {
            return Resolution.POSTPONE;
        }
        Resolution[] result = { Resolution.POSTPONE };
        try {
            runOnUiAndWait(() -> {
                Resolution r = resolver.resolveConflict(context);
                if (r != null) {
                    result[0] = r;
                }
            });
        } catch (Exception e) {
            log("ConflictResolver failed", e);
        }
        return result[0];
    }

    private boolean askPrompter(SyncContext context) {
        ReloadPrompter prompter = reloadPrompter;
        if (prompter == null) {
            return false;
        }
        boolean[] result = { false };
        try {
            runOnUiAndWait(() -> result[0] = prompter.confirmReload(context));
        } catch (Exception e) {
            log("ReloadPrompter failed", e);
        }
        return result[0];
    }

    // ------------------------------------------------------------------ helpers

    private BindingState stateFor(IArchimateModel model) {
        if (model == null) {
            return null;
        }
        synchronized (active) {
            return active.get(model);
        }
    }

    private GraphClient graph() {
        GraphClient client = graphClient;
        if (client == null) {
            synchronized (this) {
                client = graphClient;
                if (client == null) {
                    client = new GraphClient(AuthService.get()::acquireGraphToken);
                    graphClient = client;
                }
            }
        }
        return client;
    }

    private void schedule(Runnable task) {
        if (shutdown) {
            return;
        }
        try {
            scheduler.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log("Background task failed", t);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // engine shut down
        }
    }

    private void saveModelOnUi(IArchimateModel model) throws IOException {
        try {
            runOnUiAndWait(() -> IEditorModelManager.INSTANCE.saveModel(model));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to save model locally", e);
        }
    }

    private boolean isModelDirty(IArchimateModel model) {
        boolean[] dirty = { false };
        try {
            runOnUiAndWait(() -> dirty[0] = IEditorModelManager.INSTANCE.isModelDirty(model));
        } catch (Exception e) {
            log("isModelDirty failed", e);
        }
        return dirty[0];
    }

    private static void runOnUiAndWait(UiCall call) throws Exception {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || display.getThread() == Thread.currentThread()) {
            call.run();
            return;
        }
        Exception[] error = new Exception[1];
        display.syncExec(() -> {
            try {
                call.run();
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) {
            throw error[0];
        }
    }

    private void setStatus(BindingState state, SyncStatus status, String message) {
        if (status == state.lastStatus && Objects.equals(message, state.lastMessage)) {
            return;
        }
        state.lastStatus = status;
        state.lastMessage = message;
        fireStatus(state);
    }

    /** Notifies listeners of the current status, even if it did not change. */
    private void fireStatus(BindingState state) {
        SyncStatus status = state.lastStatus != null ? state.lastStatus : SyncStatus.IDLE;
        for (SyncListener listener : listeners) {
            try {
                listener.statusChanged(state.binding, status, state.lastMessage);
            } catch (Throwable t) {
                log("SyncListener failed", t);
            }
        }
    }

    private void handleError(BindingState state, Throwable t) {
        if (t instanceof NotFoundException) {
            setStatus(state, SyncStatus.ERROR,
                    "Remote file is missing: " + state.binding.itemPath);
        } else if (t instanceof GraphAuthException) {
            setStatus(state, SyncStatus.OFFLINE, "Authentication expired — sign in again");
        } else if (t instanceof PreconditionFailedException) {
            setStatus(state, SyncStatus.CONFLICT, conflictMessage(null));
        } else if (t instanceof GraphException graphException) {
            setStatus(state, SyncStatus.ERROR, "SharePoint error: " + graphException.getMessage());
        } else {
            log("Sync failed", t);
            String message = t.getMessage() != null ? t.getMessage() : t.toString();
            setStatus(state, SyncStatus.ERROR, message);
        }
    }

    private static String conflictMessage(DriveItem remote) {
        StringBuilder message = new StringBuilder("Remote copy changed");
        if (remote != null) {
            if (remote.lastModifiedBy != null) {
                message.append(" by ").append(remote.lastModifiedBy);
            }
            if (remote.lastModified != null) {
                message.append(" at ").append(remote.lastModified);
            }
        }
        return message.toString();
    }

    private static void log(String message, Throwable t) {
        try {
            Bundle bundle = Platform.getBundle(BUNDLE_ID);
            if (bundle != null) {
                ILog log = Platform.getLog(bundle);
                log.log(new Status(IStatus.ERROR, BUNDLE_ID, message, t));
                return;
            }
        } catch (Throwable ignored) {
            // fall through to stderr
        }
        System.err.println("[sharepoint-sync] " + message + (t != null ? ": " + t : ""));
    }
}
