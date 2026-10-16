package org.papyrus.mcp.tools;

import com.google.gson.*;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.Stereotype;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

/** Package tree of the model (nested), with ids, qualified names, counts, stereotypes. */
public class ListPackagesTool extends ReadTool {
    public String name() { return "list_packages"; }
    public String description() { return "List all packages (nested) of a Papyrus UML model with ids, qualified names and element counts."; }
    public JsonObject inputSchema() { return baseSchema(); }

    protected JsonObject read(ModelLocator.Active m, JsonObject args, Session s) {
        JsonObject out = new JsonObject();
        out.add("packages", tree(m.root()));
        return out;
    }

    private JsonObject tree(Package p) {
        JsonObject o = ElementJson.ref(p);
        o.addProperty("ownedElementCount", p.getPackagedElements().size());
        JsonArray st = new JsonArray();
        for (Stereotype x : p.getAppliedStereotypes()) st.add(x.getQualifiedName());
        if (!st.isEmpty()) o.add("stereotypes", st);
        JsonArray kids = new JsonArray();
        for (Package c : p.getNestedPackages()) kids.add(tree(c));
        o.add("children", kids);
        return o;
    }
}
