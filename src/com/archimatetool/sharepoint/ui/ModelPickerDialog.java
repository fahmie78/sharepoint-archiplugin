package com.archimatetool.sharepoint.ui;

import java.io.File;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Simple picker that lists the currently open ArchiMate models (name and file)
 * and lets the user choose one. Used when {@link ActiveModelResolver} cannot
 * determine a single active model.
 */
public class ModelPickerDialog extends ElementListSelectionDialog {

    /**
     * Shows the picker for all currently open models.
     *
     * @param shell parent shell
     * @return the selected model, or null when cancelled or no model is open.
     */
    public static IArchimateModel pick(Shell shell) {
        List<IArchimateModel> models = IEditorModelManager.INSTANCE.getModels();
        if(models == null || models.isEmpty()) {
            MessageDialog.openInformation(shell, "SharePoint",
                    "There are no open models. Open a model first.");
            return null;
        }
        ModelPickerDialog dialog = new ModelPickerDialog(shell, models);
        if(dialog.open() == Window.OK) {
            return (IArchimateModel)dialog.getFirstResult();
        }
        return null;
    }

    /**
     * Creates the dialog.
     *
     * @param shell parent shell
     * @param models the open models to choose from
     */
    public ModelPickerDialog(Shell shell, List<IArchimateModel> models) {
        super(shell, new LabelProvider() {
            @Override
            public String getText(Object element) {
                if(element instanceof IArchimateModel model) {
                    String name = model.getName() == null ? "(unnamed)" : model.getName();
                    File file = model.getFile();
                    return file == null ? name + "  (not saved to a file)"
                                        : name + "  \u2014  " + file.getAbsolutePath();
                }
                return super.getText(element);
            }
        });
        setTitle("Select Model");
        setMessage("Select the model this command applies to:");
        setElements(models.toArray());
        setMultipleSelection(false);
    }
}
