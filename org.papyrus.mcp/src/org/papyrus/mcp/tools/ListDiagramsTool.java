package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.gmf.runtime.notation.Style;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

/**
 * GMF diagrams from *.notation and Sirius representations from *.aird in the ModelSet.
 * Sirius/PapyrusDiagramStyle are read reflectively to avoid hard dependencies on those bundles.
 */
public class ListDiagramsTool extends ReadTool {
    public String name() { return "list_diagrams"; }
    public String description() { return "List diagrams of the model: GMF (editable by this server) and Sirius (listed, flagged read-only in v1)."; }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "owner", "string", "Optional XMI id/qualified name: only diagrams owned by this element.");
        return s;
    }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        String ownerFilter = str(a, "owner") == null ? null : ElementJson.id(ElementJson.resolve(m, str(a, "owner")));
        JsonArray out = new JsonArray();
        for (Resource r : m.modelSet().getResources()) {
            String ext = r.getURI().fileExtension();
            if ("notation".equals(ext)) {
                for (EObject root : r.getContents()) {
                    if (!(root instanceof Diagram d)) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("id", r.getURIFragment(d));
                    o.addProperty("name", d.getName());
                    o.addProperty("framework", "gmf");
                    o.addProperty("type", d.getType());
                    EObject owner = d.getElement();
                    for (Object st : d.getStyles()) {
                        EObject so = (EObject) st;
                        if (so.eClass().getName().equals("PapyrusDiagramStyle")) {
                            EStructuralFeature kf = so.eClass().getEStructuralFeature("diagramKindId");
                            EStructuralFeature of = so.eClass().getEStructuralFeature("owner");
                            if (kf != null) o.addProperty("kind", String.valueOf(so.eGet(kf)));
                            if (of != null && so.eGet(of) instanceof EObject ow) owner = ow;
                        }
                    }
                    if (ownerFilter != null && (owner == null || !ownerFilter.equals(ElementJson.id(owner)))) continue;
                    o.add("owner", ElementJson.ref(owner));
                    o.add("context", ElementJson.ref(d.getElement()));
                    o.addProperty("nodeCount", d.getChildren().size());
                    o.addProperty("edgeCount", d.getEdges().size());
                    out.add(o);
                }
            } else if ("aird".equals(ext)) {
                for (java.util.Iterator<EObject> it = r.getAllContents(); it.hasNext();) {
                    EObject e = it.next();
                    if (!e.eClass().getName().equals("DRepresentationDescriptor")) continue;
                    JsonObject o = new JsonObject();
                    o.addProperty("id", r.getURIFragment(e));
                    EStructuralFeature nf = e.eClass().getEStructuralFeature("name");
                    EStructuralFeature tf = e.eClass().getEStructuralFeature("target");
                    o.addProperty("name", nf == null ? null : String.valueOf(e.eGet(nf)));
                    o.addProperty("framework", "sirius");
                    o.addProperty("readOnly", true);
                    if (tf != null && e.eGet(tf) instanceof EObject t) {
                        if (ownerFilter != null && !ownerFilter.equals(ElementJson.id(t))) continue;
                        o.add("owner", ElementJson.ref(t));
                    }
                    out.add(o);
                }
            }
        }
        JsonObject res = new JsonObject();
        res.add("diagrams", out);
        return res;
    }
}
