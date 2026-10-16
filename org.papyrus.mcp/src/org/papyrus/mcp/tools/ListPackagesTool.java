package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.transaction.RunnableWithResult;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;
import org.papyrus.mcp.util.ModelLocator;
import org.papyrus.mcp.util.UiSync;

/**
 * Lists the package tree of the active model. Read-only: we read inside
 * TransactionalEditingDomain.runExclusive so a concurrent write can't tear the read.
 */
public class ListPackagesTool implements Tool {
    public String name() { return "list_packages"; }
    public String description() {
        return "List all packages (nested) of the active Papyrus UML model with ids, qualified names and element counts.";
    }
    public JsonObject inputSchema() { return ToolRegistry.schema(); }

    public JsonObject execute(JsonObject args) throws Exception {
        return UiSync.call(() -> {
            ModelLocator.Active m = ModelLocator.require();
            // runExclusive takes a Runnable; RunnableWithResult carries the value back out.
            RunnableWithResult.Impl<JsonObject> r = new RunnableWithResult.Impl<>() {
                @Override public void run() {
                    JsonObject out = new JsonObject();
                    out.add("packages", tree(m.root()));
                    setResult(out);
                }
            };
            m.domain().runExclusive(r);
            return r.getResult();
        });
    }

    private JsonObject tree(Package p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id(p));
        o.addProperty("name", p.getName());
        o.addProperty("qualifiedName", p.getQualifiedName());
        o.addProperty("metaclass", p.eClass().getName());
        o.addProperty("ownedElementCount", p.getPackagedElements().size());
        JsonArray st = new JsonArray();
        for (Stereotype s : p.getAppliedStereotypes()) st.add(s.getQualifiedName());
        if (!st.isEmpty()) o.add("stereotypes", st);
        JsonArray kids = new JsonArray();
        for (Package c : p.getNestedPackages()) kids.add(tree(c));
        o.add("children", kids);
        return o;
    }

    /** Stable id = XMI id (survives save/reload); falls back to URI fragment. */
    static String id(EObject e) {
        Resource r = e.eResource();
        return r == null ? null : r.getURIFragment(e);
    }
}
