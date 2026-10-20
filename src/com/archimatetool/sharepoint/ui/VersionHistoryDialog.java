package com.archimatetool.sharepoint.ui;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.graph.DriveItemVersion;
import com.archimatetool.sharepoint.sync.SyncEngine;

/**
 * Shows the SharePoint version history of the connected file (including check-in
 * comments) and lets the user restore any version into the locally open model.
 * <p>
 * The versions are loaded asynchronously via an Eclipse {@link Job} delegating to
 * {@link SyncEngine#listVersions}; the Graph calls never run on the UI thread.
 * Restoring discards local changes (after confirmation when the model is dirty)
 * and never uploads by itself — publishing the restored version is an explicit
 * follow-up "Sync now" offered after a successful restore.
 * </p>
 */
public class VersionHistoryDialog extends TitleAreaDialog {

    private static final int ID_RESTORE = 10;

    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final IArchimateModel model;
    private final Shell parentShell;

    private TableViewer viewer;
    private Button restoreButton;

    /**
     * Creates the dialog.
     *
     * @param parentShell parent shell
     * @param model       the connected model whose remote file history is shown
     */
    public VersionHistoryDialog(Shell parentShell, IArchimateModel model) {
        super(parentShell);
        this.parentShell = parentShell;
        this.model = model;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Version history");
    }

    @Override
    public void create() {
        super.create();
        setTitle("SharePoint version history");
        setMessage("Loading...");
        loadVersions();
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite)super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayout(new GridLayout(1, false));
        GridDataFactory.fillDefaults().grab(true, true).hint(720, 320).applyTo(container);

        viewer = new TableViewer(container, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        createColumn("Version", 70, v -> v.id);
        createColumn("Modified", 150, v -> formatLocalTime(v.lastModified));
        createColumn("Modified By", 140, v -> v.lastModifiedBy);
        createColumn("Size", 90, v -> formatSize(v.size));
        createColumn("Comment", 260, v -> v.comment);
        viewer.getTable().setHeaderVisible(true);
        viewer.getTable().setLinesVisible(true);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        GridDataFactory.fillDefaults().grab(true, true).applyTo(viewer.getTable());
        viewer.addSelectionChangedListener(event -> updateRestoreButton());
        return area;
    }

    private void createColumn(String title, int width, Function<DriveItemVersion, String> text) {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                String value = text.apply((DriveItemVersion)element);
                return value != null ? value : "";
            }
        });
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        restoreButton = createButton(parent, ID_RESTORE, "Restore", false);
        restoreButton.setEnabled(false);
        createButton(parent, IDialogConstants.CANCEL_ID, "Close", true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if(buttonId == ID_RESTORE) {
            onRestore();
            return;
        }
        super.buttonPressed(buttonId);
    }

    private void updateRestoreButton() {
        if(restoreButton != null && !restoreButton.isDisposed()) {
            restoreButton.setEnabled(selectedVersion() != null);
        }
    }

    private DriveItemVersion selectedVersion() {
        if(viewer == null || viewer.getTable().isDisposed()) {
            return null;
        }
        IStructuredSelection selection = viewer.getStructuredSelection();
        return selection.isEmpty() ? null : (DriveItemVersion)selection.getFirstElement();
    }

    // ------------------------------------------------------------------ loading

    private void loadVersions() {
        Job job = new Job("Loading SharePoint version history") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                // callbacks are marshalled to the UI thread by the SyncEngine
                SyncEngine.get().listVersions(model,
                        VersionHistoryDialog.this::onVersionsLoaded,
                        VersionHistoryDialog.this::onVersionsFailed);
                return Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private void onVersionsLoaded(List<DriveItemVersion> versions) {
        if(getShell() == null || getShell().isDisposed()) {
            return;
        }
        viewer.setInput(versions);
        setMessage(versions.isEmpty() ? "No versions found."
                : versions.size() + " version(s), newest first");
    }

    private void onVersionsFailed(Throwable t) {
        if(getShell() == null || getShell().isDisposed()) {
            return;
        }
        setMessage("Failed to load the version history.", IMessageProvider.ERROR);
        MessageDialog.openError(getShell(), "Version history",
                "Failed to load the version history:\n"
                        + (t.getMessage() != null ? t.getMessage() : t.toString()));
    }

    // ------------------------------------------------------------------ restore

    private void onRestore() {
        DriveItemVersion version = selectedVersion();
        if(version == null) {
            return;
        }
        if(isModelDirty()) {
            boolean confirmed = MessageDialog.openConfirm(getShell(), "Restore version",
                    "Restoring will discard your unsaved local changes. Continue?");
            if(!confirmed) {
                return;
            }
        }
        close();
        SyncEngine.get().restoreVersion(model, version, reopened -> {
            if(reopened == null || parentShell == null || parentShell.isDisposed()) {
                return; // failure is already reported via the sync status
            }
            boolean upload = MessageDialog.openQuestion(parentShell, "Version restored",
                    "Version " + version.id + " was restored locally.\n\n"
                            + "Upload the restored version as the new current SharePoint version now?");
            if(upload) {
                SyncEngine.get().syncNow(reopened);
            }
        });
    }

    private boolean isModelDirty() {
        try {
            return IEditorModelManager.INSTANCE.isModelDirty(model);
        }
        catch(Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ formatting

    /** Formats an ISO-8601 timestamp in the local time zone ({@code ""} when absent). */
    private static String formatLocalTime(String iso) {
        if(iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return LOCAL_TIME.format(Instant.parse(iso));
        }
        catch(RuntimeException e) {
            try {
                return LOCAL_TIME.format(OffsetDateTime.parse(iso).toInstant());
            }
            catch(RuntimeException ignored) {
                return iso;
            }
        }
    }

    /** Formats a byte count as B/KB/MB. */
    private static String formatSize(long bytes) {
        if(bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if(kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        return String.format("%.1f MB", kb / 1024.0);
    }
}
