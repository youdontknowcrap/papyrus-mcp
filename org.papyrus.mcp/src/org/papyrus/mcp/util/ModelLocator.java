package org.papyrus.mcp.util;

import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.papyrus.infra.ui.editor.IMultiDiagramEditor;
import org.eclipse.papyrus.infra.core.resource.ModelSet;
import org.eclipse.papyrus.infra.core.services.ServicesRegistry;
import org.eclipse.papyrus.uml.tools.model.UmlModel;
import org.eclipse.ui.*;
import org.eclipse.uml2.uml.Package;

/**
 * Finds the Papyrus model the bot should act on. Must be called on the UI thread.
 *
 * Order: active editor if it is a Papyrus IMultiDiagramEditor; otherwise the first open
 * Papyrus editor in any window. Later phases add an explicit "model" argument (resource URI)
 * so a bot can target one of several open models.
 *
 * From the editor we get the ServicesRegistry, which hands out the ModelSet (a ResourceSet
 * containing .uml/.notation/.di) and the shared TransactionalEditingDomain whose command
 * stack IS the editor's undo history.
 */
public final class ModelLocator {
    private ModelLocator() {}

    public record Active(IMultiDiagramEditor editor, ServicesRegistry services, ModelSet modelSet,
                         TransactionalEditingDomain domain, Package root) {}

    public static Active require() throws Exception {
        Active a = findActive();
        if (a == null) throw new IllegalStateException("No Papyrus model is open. Open a .di file in Papyrus first.");
        return a;
    }

    public static Active findActive() throws Exception {
        IWorkbench wb = PlatformUI.getWorkbench();
        IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
        if (win != null && win.getActivePage() != null) {
            IEditorPart ed = win.getActivePage().getActiveEditor();
            if (ed instanceof IMultiDiagramEditor m) return from(m);
        }
        for (IWorkbenchWindow w : wb.getWorkbenchWindows())
            for (IWorkbenchPage p : w.getPages())
                for (IEditorReference r : p.getEditorReferences()) {
                    IEditorPart ed = r.getEditor(false); // don't force-restore lazily restored editors
                    if (ed instanceof IMultiDiagramEditor m) return from(m);
                }
        return null;
    }

    private static Active from(IMultiDiagramEditor ed) throws Exception {
        ServicesRegistry reg = ed.getServicesRegistry();
        ModelSet ms = reg.getService(ModelSet.class);
        TransactionalEditingDomain dom = reg.getService(TransactionalEditingDomain.class);
        UmlModel uml = (UmlModel) ms.getModel(UmlModel.MODEL_ID);
        Package root = (Package) uml.lookupRoot();
        return new Active(ed, reg, ms, dom, root);
    }
}
