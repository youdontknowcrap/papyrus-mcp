package org.papyrus.mcp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.emf.common.command.CommandStackListener;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eclipse.papyrus.infra.core.resource.ModelSet;
import org.eclipse.papyrus.infra.core.services.ServicesRegistry;
import org.eclipse.papyrus.infra.ui.editor.IMultiDiagramEditor;
import org.eclipse.papyrus.uml.tools.model.UmlModel;
import org.eclipse.ui.*;
import org.eclipse.uml2.uml.Package;

/**
 * Finds the Papyrus model(s) the bot acts on. Must be called on the UI thread.
 *
 * Selection: optional "model" argument (substring of the .uml/.di URI or the editor title);
 * otherwise the active Papyrus editor; otherwise the first restored Papyrus editor.
 *
 * Also owns the per-model VERSION counter used for stale-edit detection: a CommandStackListener
 * on the editing domain bumps it on every execute/undo/redo, whoever caused it (bot or human).
 */
public final class ModelLocator {
    private ModelLocator() {}

    public record Active(IMultiDiagramEditor editor, ServicesRegistry services, ModelSet modelSet,
                         TransactionalEditingDomain domain, Package root) {
        /** Canonical model key: URI of the .uml resource. */
        public String uri() { return root.eResource().getURI().toString(); }
        public long version() { return versionOf(domain); }
        public Resource umlResource() { return root.eResource(); }
    }

    private static final Map<TransactionalEditingDomain, AtomicLong> VERSIONS = new WeakHashMap<>();

    public static synchronized long versionOf(TransactionalEditingDomain d) {
        AtomicLong v = VERSIONS.get(d);
        if (v == null) {
            AtomicLong nv = new AtomicLong(0);
            VERSIONS.put(d, nv);
            CommandStackListener l = e -> nv.incrementAndGet();
            d.getCommandStack().addCommandStackListener(l);
            v = nv;
        }
        return v.get();
    }

    public static Active require() throws Exception { return require(null); }

    public static Active require(String model) throws Exception {
        Active a = find(model);
        if (a == null) throw new IllegalStateException(model == null
                ? "No Papyrus model is open. Open a .di file in Papyrus first."
                : "No open Papyrus model matches '" + model + "'. Call list_open_models.");
        return a;
    }

    public static Active findActive() throws Exception { return find(null); }

    public static Active find(String model) throws Exception {
        if (model == null || model.isBlank()) {
            IWorkbenchWindow win = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (win != null && win.getActivePage() != null
                    && win.getActivePage().getActiveEditor() instanceof IMultiDiagramEditor m) return from(m);
            List<Active> all = all();
            return all.isEmpty() ? null : all.get(0);
        }
        for (Active a : all())
            if (a.uri().contains(model) || a.editor().getTitle().contains(model)) return a;
        return null;
    }

    /** All restored Papyrus editors (unrestored editor tabs are reported by list_open_models separately). */
    public static List<Active> all() throws Exception {
        List<Active> out = new ArrayList<>();
        for (IEditorReference r : papyrusEditorRefs()) {
            IEditorPart ed = r.getEditor(false);
            if (ed instanceof IMultiDiagramEditor m) out.add(from(m));
        }
        return out;
    }

    public static List<IEditorReference> papyrusEditorRefs() {
        List<IEditorReference> out = new ArrayList<>();
        for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows())
            for (IWorkbenchPage p : w.getPages())
                for (IEditorReference r : p.getEditorReferences())
                    if (r.getId() != null && r.getId().startsWith("org.eclipse.papyrus")) out.add(r);
        return out;
    }

    private static Active from(IMultiDiagramEditor ed) throws Exception {
        ServicesRegistry reg = ed.getServicesRegistry();
        ModelSet ms = reg.getService(ModelSet.class);
        TransactionalEditingDomain dom = reg.getService(TransactionalEditingDomain.class);
        UmlModel uml = (UmlModel) ms.getModel(UmlModel.MODEL_ID);
        Package root = (Package) uml.lookupRoot();
        versionOf(dom); // make sure the listener is installed early
        return new Active(ed, reg, ms, dom, root);
    }
}
