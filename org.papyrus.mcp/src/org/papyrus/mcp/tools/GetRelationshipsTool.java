package org.papyrus.mcp.tools;

import com.google.gson.JsonObject;
import org.eclipse.uml2.uml.Element;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

public class GetRelationshipsTool extends ReadTool {
    public String name() { return "get_relationships"; }
    public String description() { return "Relationships of an element: associations (with ends), generalizations, dependencies, realizations, include/extend, ..."; }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "id", "string", "XMI id or qualified name.");
        prop(s, "kind", "string", "Optional exact metaclass: Association, Generalization, Dependency, Realization, Usage, Include, Extend, ...");
        prop(s, "direction", "string", "out | in | both (default both). Associations are always 'both'.");
        require(s, "id");
        return s;
    }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        Element e = ElementJson.resolve(m, str(a, "id"));
        JsonObject out = new JsonObject();
        out.add("element", ElementJson.ref(e));
        out.add("relationships", ElementJson.relationships(e, str(a, "kind"), a.has("direction") ? str(a, "direction") : "both"));
        return out;
    }
}
