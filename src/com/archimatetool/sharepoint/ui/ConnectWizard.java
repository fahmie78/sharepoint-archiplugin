package com.archimatetool.sharepoint.ui;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.ListViewer;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.sharepoint.graph.Drive;
import com.archimatetool.sharepoint.graph.DriveItem;
import com.archimatetool.sharepoint.graph.GraphClient;
import com.archimatetool.sharepoint.graph.GraphException;
import com.archimatetool.sharepoint.graph.NotFoundException;
import com.archimatetool.sharepoint.graph.Site;
import com.archimatetool.sharepoint.prefs.Prefs;
import com.archimatetool.sharepoint.sync.ModelBinding;
import com.archimatetool.sharepoint.sync.SyncEngine;

/**
 * Three-page wizard that connects an open ArchiMate model to a file in a
 * SharePoint document library:
 * <ol>
 *   <li>SharePoint site URL, validated against Microsoft Graph.</li>
 *   <li>Document library (drive) selection.</li>
 *   <li>Remote folder + file name, with an overwrite option.</li>
 * </ol>
 * All Graph calls run off the UI thread via the wizard container's progress
 * service. On {@link #performFinish()} a {@link ModelBinding} is built and is
 * available through {@link #getBinding()}.
 */
public class ConnectWizard extends Wizard {

    private final IArchimateModel model;
    private final GraphClient client;

    private SitePage sitePage;
    private DrivePage drivePage;
    private LocationPage locationPage;

    private ModelBinding binding;

    /**
     * Creates the wizard.
     *
     * @param model the model to connect; must already have a local file
     * @param client Graph client bound to the signed-in user's token provider
     */
    public ConnectWizard(IArchimateModel model, GraphClient client) {
        this.model = model;
        this.client = client;
        setWindowTitle("Connect model to SharePoint");
        setNeedsProgressMonitor(true);
    }

    /**
     * @return the binding built by {@link #performFinish()}, or null when not finished.
     */
    public ModelBinding getBinding() {
        return binding;
    }

    /**
     * Site URL to prefill the wizard with: derived from the model's existing
     * connection when there is one, otherwise the last URL used (persisted across
     * restarts).
     */
    private String initialSiteUrl() {
        ModelBinding existing = SyncEngine.get().bindingFor(model);
        if(existing != null && existing.displayUrl != null && existing.itemPath != null
                && existing.displayUrl.endsWith("/" + existing.itemPath)) {
            return existing.displayUrl.substring(0,
                    existing.displayUrl.length() - existing.itemPath.length() - 1);
        }
        return Prefs.getLastSiteUrl();
    }

    @Override
    public void addPages() {
        sitePage = new SitePage();
        drivePage = new DrivePage();
        locationPage = new LocationPage();
        addPage(sitePage);
        addPage(drivePage);
        addPage(locationPage);
    }

    @Override
    public boolean canFinish() {
        return getContainer().getCurrentPage() == locationPage && locationPage.isPageComplete();
    }

    @Override
    public boolean performFinish() {
        final Site site = sitePage.getValidatedSite();
        final Drive drive = drivePage.getSelectedDrive();
        final String folderPath = locationPage.getNormalizedFolderPath();
        final String fileName = locationPage.getFileName();
        final boolean overwrite = locationPage.isOverwrite();
        if(site == null || drive == null || fileName.isBlank()) {
            return false;
        }

        try {
            getContainer().run(true, false, monitor -> {
                monitor.beginTask("Connecting to SharePoint", IProgressMonitor.UNKNOWN);
                try {
                    String effectiveFolder = folderPath;
                    if(!effectiveFolder.isEmpty()) {
                        try {
                            client.listChildren(drive.id, effectiveFolder);
                        }
                        catch(NotFoundException nf) {
                            boolean[] useRoot = { false };
                            String folderForDialog = effectiveFolder;
                            Display.getDefault().syncExec(() ->
                                useRoot[0] = MessageDialog.openConfirm(getShell(), "Folder not found",
                                        "The folder \"" + folderForDialog + "\" does not exist in \""
                                        + drive.name + "\".\n\nCreate the file at the library root instead?"));
                            if(!useRoot[0]) {
                                throw new GraphException(0, "Folder \"" + folderForDialog + "\" does not exist.");
                            }
                            effectiveFolder = "";
                        }
                    }

                    String itemPath = effectiveFolder.isEmpty() ? fileName : effectiveFolder + "/" + fileName;

                    String eTag = null;
                    String itemId = null;
                    try {
                        DriveItem existing = client.getItem(drive.id, itemPath);
                        eTag = existing.eTag;
                        itemId = existing.id;
                        if(!overwrite) {
                            throw new GraphException(0, "\"" + itemPath
                                    + "\" already exists. Go back and tick the overwrite option, or choose a different name.");
                        }
                    }
                    catch(NotFoundException nf) {
                        // new file: no eTag yet
                    }

                    File localFile = model.getFile();
                    ModelBinding b = new ModelBinding();
                    b.siteId = site.id;
                    b.driveId = drive.id;
                    b.itemId = itemId;
                    b.itemPath = itemPath;
                    b.displayUrl = (site.webUrl != null ? site.webUrl : "") + "/" + itemPath;
                    b.lastETag = eTag;
                    b.localFilePath = localFile == null ? null : localFile.getAbsolutePath();
                    b.model = model;
                    binding = b;
                    // remember the target so the wizard can prefill it next time
                    Prefs.setLastConnection(
                            sitePage.validatedUrl != null ? sitePage.validatedUrl
                                    : sitePage.urlText.getText().trim(),
                            folderPath);
                }
                catch(GraphException ge) {
                    throw new InvocationTargetException(ge);
                }
            });
        }
        catch(InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            MessageDialog.openError(getShell(), "Connect failed",
                    "Could not connect to SharePoint:\n\n" + String.valueOf(cause.getMessage()));
            return false;
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return binding != null;
    }

    /** Runs a Graph call in the container's progress thread, surfacing errors on the given page. */
    private void runWithBusy(WizardPage page, RunnableWithError action) {
        try {
            getContainer().run(true, false, monitor -> {
                monitor.beginTask("Contacting SharePoint", IProgressMonitor.UNKNOWN);
                try {
                    action.run();
                }
                catch(GraphException ge) {
                    throw new InvocationTargetException(ge);
                }
            });
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch(InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            page.setErrorMessage(String.valueOf(cause.getMessage()));
        }
    }

    @FunctionalInterface
    private interface RunnableWithError {
        void run() throws GraphException;
    }

    // ---------------------------------------------------------------- pages

    /** Page 1: SharePoint site URL with live validation via {@code resolveSite}. */
    public class SitePage extends WizardPage {

        private Text urlText;
        private Site validatedSite;
        private String validatedUrl;

        SitePage() {
            super("site", "SharePoint site", null);
            setDescription("Enter the URL of the SharePoint site that hosts the document library, "
                    + "e.g. https://contoso.sharepoint.com/sites/team");
            setPageComplete(false);
        }

        @Override
        public void createControl(Composite parent) {
            Composite container = new Composite(parent, SWT.NONE);
            container.setLayout(new GridLayout(2, false));

            Label label = new Label(container, SWT.NONE);
            label.setText("Site URL:");

            urlText = new Text(container, SWT.BORDER);
            urlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            // prefill: the model's existing connection, else the last used site URL
            String prefill = initialSiteUrl();
            if(!prefill.isEmpty()) {
                urlText.setText(prefill);
            }
            urlText.addModifyListener(e -> {
                // any edit invalidates a previous validation
                validatedSite = null;
                validatedUrl = null;
                updatePageCompletion();
            });

            setControl(container);
            updatePageCompletion();
        }

        /**
         * Enables Next as soon as the URL is syntactically plausible. Network
         * validation against Microsoft Graph happens in {@link #getNextPage()},
         * not here — a page must never depend on getNextPage() to become
         * complete, because the Next button is enabled by page completeness.
         */
        private void updatePageCompletion() {
            String url = urlText.getText().trim();
            if(url.isEmpty()) {
                setErrorMessage(null);
                setPageComplete(false);
                return;
            }
            if(!isPlausibleSiteUrl(url)) {
                setErrorMessage("The URL should look like https://<tenant>.sharepoint.com/sites/<site>");
                setPageComplete(false);
                return;
            }
            setErrorMessage(null);
            setPageComplete(true);
        }

        private static boolean isPlausibleSiteUrl(String url) {
            try {
                java.net.URI uri = new java.net.URI(url);
                String scheme = uri.getScheme();
                return scheme != null
                        && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                        && uri.getHost() != null && !uri.getHost().isBlank();
            }
            catch(java.net.URISyntaxException e) {
                return false;
            }
        }

        Site getValidatedSite() {
            return validatedSite;
        }

        @Override
        public IWizardPage getNextPage() {
            if(validatedSite != null && validatedUrl != null && validatedUrl.equals(urlText.getText().trim())) {
                return drivePage;
            }
            final String url = urlText.getText().trim();
            if(url.isEmpty()) {
                setErrorMessage("Enter the SharePoint site URL.");
                return null;
            }
            setErrorMessage(null);
            Site[] result = new Site[1];
            runWithBusy(this, () -> result[0] = client.resolveSite(url));
            if(result[0] == null) {
                // error message already set by runWithBusy
                return null;
            }
            validatedSite = result[0];
            validatedUrl = url;
            setMessage("Connected to site: "
                    + (validatedSite.displayName != null ? validatedSite.displayName : url));
            setPageComplete(true);
            return drivePage;
        }
    }

    /** Page 2: document library (drive) selection. */
    public class DrivePage extends WizardPage {

        private ListViewer viewer;
        private List<Drive> drives = new ArrayList<>();
        private String loadedSiteId;

        DrivePage() {
            super("drive", "Document library", null);
            setDescription("Select the document library that contains (or will contain) the model file.");
            setPageComplete(false);
        }

        @Override
        public void createControl(Composite parent) {
            Composite container = new Composite(parent, SWT.NONE);
            container.setLayout(new GridLayout(1, false));

            viewer = new ListViewer(container, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
            viewer.setContentProvider(ArrayContentProvider.getInstance());
            viewer.setLabelProvider(new LabelProvider() {
                @Override
                public String getText(Object element) {
                    return element instanceof Drive drive ? drive.name : String.valueOf(element);
                }
            });
            viewer.getList().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            viewer.addSelectionChangedListener(e -> setPageComplete(getSelectedDrive() != null));

            setControl(container);
        }

        @Override
        public void setVisible(boolean visible) {
            super.setVisible(visible);
            Site site = sitePage.getValidatedSite();
            if(visible && site != null && !site.id.equals(loadedSiteId)) {
                loadDrives();
            }
        }

        private void loadDrives() {
            Site site = sitePage.getValidatedSite();
            java.util.concurrent.atomic.AtomicReference<List<Drive>> result = new java.util.concurrent.atomic.AtomicReference<>();
            runWithBusy(this, () -> result.set(client.listDrives(site.id)));
            if(result.get() == null) {
                loadedSiteId = null; // allow retry on next visit
                return;
            }
            loadedSiteId = site.id;
            drives = result.get();
            viewer.setInput(drives);
            if(drives.size() == 1) {
                viewer.setSelection(new StructuredSelection(drives.get(0)));
            }
            else {
                // setInput() does not fire a selection event; reset completeness explicitly
                setPageComplete(getSelectedDrive() != null);
            }
        }

        Drive getSelectedDrive() {
            IStructuredSelection sel = viewer == null ? StructuredSelection.EMPTY : viewer.getStructuredSelection();
            Object first = sel.getFirstElement();
            return first instanceof Drive drive ? drive : null;
        }
    }

    /** Page 3: remote folder path, file name and overwrite option. */
    public class LocationPage extends WizardPage {

        private Text folderText;
        private Text fileText;
        private Button overwriteCheckbox;

        LocationPage() {
            super("location", "Remote file", null);
            setDescription("Choose the folder and file name in the document library.");
            setPageComplete(false);
        }

        @Override
        public void createControl(Composite parent) {
            Composite container = new Composite(parent, SWT.NONE);
            container.setLayout(new GridLayout(2, false));

            Label folderLabel = new Label(container, SWT.NONE);
            folderLabel.setText("Folder path:");
            folderText = new Text(container, SWT.BORDER);
            String lastFolder = Prefs.getLastFolderPath();
            folderText.setText(lastFolder.isEmpty() ? "/" : "/" + lastFolder + "/");
            folderText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            Label fileLabel = new Label(container, SWT.NONE);
            fileLabel.setText("File name:");
            fileText = new Text(container, SWT.BORDER);
            fileText.setText(defaultFileName());
            fileText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            overwriteCheckbox = new Button(container, SWT.CHECK);
            overwriteCheckbox.setText("If the file already exists, overwrite it with my local model on first sync");
            GridData checkboxData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            checkboxData.horizontalSpan = 2;
            overwriteCheckbox.setLayoutData(checkboxData);

            folderText.addModifyListener(e -> validate());
            fileText.addModifyListener(e -> validate());

            setControl(container);
            validate();
        }

        private String defaultFileName() {
            String name = model.getName() == null || model.getName().isBlank() ? "model" : model.getName().trim();
            name = name.replaceAll("[\"*\\\\:<>?/|#%]", "_");
            return name + ".archimate";
        }

        private void validate() {
            setErrorMessage(null);

            String rawName = fileText.getText();
            if(rawName.isBlank()) {
                setErrorMessage("Enter a file name.");
                setPageComplete(false);
                return;
            }
            String nameReason = invalidSegmentReason(rawName);
            if(nameReason != null) {
                setErrorMessage("The file name " + nameReason);
                setPageComplete(false);
                return;
            }
            if(!rawName.trim().toLowerCase().endsWith(".archimate")) {
                setErrorMessage("The file name should end with .archimate");
                setPageComplete(false);
                return;
            }

            String rawFolder = folderText.getText().replace('\\', '/');
            for(String segment : rawFolder.split("/")) {
                if(segment.isEmpty()) {
                    continue; // leading/trailing/double slashes are tolerated
                }
                String folderReason = invalidSegmentReason(segment);
                if(folderReason != null) {
                    setErrorMessage("Folder segment \"" + segment + "\" " + folderReason);
                    setPageComplete(false);
                    return;
                }
            }

            setPageComplete(true);
        }

        /**
         * Checks one SharePoint path segment (file name or folder name) for
         * illegal characters and leading/trailing dots or spaces.
         *
         * @param segment the raw (untrimmed) segment
         * @return a description of the problem, or null when the segment is valid
         */
        private static String invalidSegmentReason(String segment) {
            if(!segment.equals(segment.trim())) {
                return "must not start or end with a space.";
            }
            if(segment.startsWith(".") || segment.endsWith(".")) {
                return "must not start or end with a dot.";
            }
            for(char c : "\"*:<>?|#%".toCharArray()) {
                if(segment.indexOf(c) >= 0) {
                    return "contains the illegal character '" + c + "'.";
                }
            }
            return null;
        }

        String getFileName() {
            return fileText == null ? "" : fileText.getText().trim();
        }

        boolean isOverwrite() {
            return overwriteCheckbox != null && overwriteCheckbox.getSelection();
        }

        /** @return folder path without leading/trailing slashes; empty for the library root. */
        String getNormalizedFolderPath() {
            if(folderText == null) {
                return "";
            }
            String path = folderText.getText().trim().replace('\\', '/');
            while(path.startsWith("/")) {
                path = path.substring(1);
            }
            while(path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return path;
        }
    }
}
