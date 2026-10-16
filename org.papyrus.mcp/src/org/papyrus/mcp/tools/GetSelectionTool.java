package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.papyrus.infra.emf.utils.EMFHelper;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

/** What the user has selected in Papyrus (Model Explorer or diagram), so they can point at things. */
public class GetSelectionTool extends ReadTool {
    public String name() { return "get_selection"; }
    public String description() { return "Elements currently selected by the user in Papyrus (Model Explorer tree or diagram)."; }
    public JsonObject inputSchema() { return baseSchema(); }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        JsonArray items = new JsonArray();
        IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        ISelection sel = w == null ? null : w.getSelectionService().getSelection();
        if (sel instanceof IStructuredSelection ss)
            for (Object o : ss.toList()) {
                EObject e = EMFHelper.getEObject(o); // adapts edit parts / tree nodes to the semantic element
                if (e instanceof org.eclipse.gmf.runtime.notation.View v && v.getElement() != null) e = v.getElement();
                if (e != null) items.add(ElementJson.ref(e));
            }
        JsonObject out = new JsonObject();
        out.add("selection", items);
        return out;
    }
}
