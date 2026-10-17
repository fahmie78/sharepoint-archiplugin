package com.archimatetool.sharepoint.ui;

import java.util.List;

import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateModelObject;

/**
 * Resolves which open ArchiMate model a SharePoint command should act on.
 * <ol>
 *   <li>If exactly one model is open in the {@link IEditorModelManager}, use it.</li>
 *   <li>Otherwise inspect the active workbench selection for an
 *       {@link IArchimateModelObject} and use its owning model.</li>
 *   <li>Otherwise return null; callers may then show {@link ModelPickerDialog}.</li>
 * </ol>
 * All methods must be called on the UI thread.
 */
public final class ActiveModelResolver {

    private ActiveModelResolver() {
        // static helpers only
    }

    /**
     * Resolves the "active" model without showing any UI.
     *
     * @return the resolved model, or null when ambiguous or none is open.
     */
    public static IArchimateModel resolve() {
        List<IArchimateModel> models = IEditorModelManager.INSTANCE.getModels();
        if(models == null || models.isEmpty()) {
            return null;
        }
        if(models.size() == 1) {
            return models.get(0);
        }

        IArchimateModel fromSelection = fromActiveSelection();
        if(fromSelection != null && models.contains(fromSelection)) {
            return fromSelection;
        }
        return null;
    }

    /**
     * Resolves the active model; when ambiguous, shows the {@link ModelPickerDialog}
     * so the user can choose one of the open models.
     *
     * @param shell parent shell for the picker dialog
     * @return the chosen model, or null when cancelled or no model is open.
     */
    public static IArchimateModel resolveOrPick(Shell shell) {
        IArchimateModel model = resolve();
        if(model != null) {
            return model;
        }
        return ModelPickerDialog.pick(shell);
    }

    private static IArchimateModel fromActiveSelection() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if(window == null) {
                return null;
            }
            IWorkbenchPage page = window.getActivePage();
            if(page == null) {
                return null;
            }
            Object selection = page.getSelection();
            if(selection instanceof IStructuredSelection structured && !structured.isEmpty()) {
                Object first = structured.getFirstElement();
                if(first instanceof IArchimateModelObject modelObject) {
                    return modelObject.getArchimateModel();
                }
            }
        }
        catch(Throwable t) {
            // never let selection inspection break command handling
        }
        return null;
    }
}
