package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

/** Applied stereotypes (with tagged values), applicable stereotypes, and applied profiles. */
public class GetStereotypesTool extends ReadTool {
    public String name() { return "get_stereotypes"; }
    public String description() {
        return "For an element: applied stereotypes with tagged values and the stereotypes that could be applied. "
             + "Without id: profiles applied to the model and their stereotypes.";
    }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "id", "string", "Optional XMI id or qualified name.");
        return s;
    }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        JsonObject out = new JsonObject();
        String id = str(a, "id");
        if (id != null) {
            Element e = ElementJson.resolve(m, id);
            out.add("element", ElementJson.ref(e));
            out.add("applied", ElementJson.stereotypes(e));
            JsonArray appl = new JsonArray();
            for (Stereotype st : e.getApplicableStereotypes()) appl.add(st.getQualifiedName());
            out.add("applicable", appl);
        } else {
            JsonArray profs = new JsonArray();
            for (Profile p : m.root().getAllAppliedProfiles()) {
                JsonObject po = ElementJson.ref(p);
                JsonArray sts = new JsonArray();
                for (Stereotype st : p.getOwnedStereotypes()) {
                    JsonObject so = ElementJson.ref(st);
                    JsonArray ext = new JsonArray();
                    for (org.eclipse.uml2.uml.Class mc : st.getAllExtendedMetaclasses()) ext.add(mc.getName());
                    so.add("extends", ext);
                    sts.add(so);
                }
                po.add("stereotypes", sts);
                profs.add(po);
            }
            out.add("appliedProfiles", profs);
        }
        return out;
    }
}
