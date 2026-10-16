package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.util.*;
import org.eclipse.uml2.uml.Element;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

public class GetElementTool extends ReadTool {
    public String name() { return "get_element"; }
    public String description() {
        return "Get one element by XMI id or qualified name, with properties, comments, stereotypes (tagged values), "
             + "relationships and owned elements. Returns an etag usable as expected_etags in edits.";
    }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "id", "string", "XMI id or qualified name (e.g. 'Model::Domain::Scene'). Default: model root.");
        prop(s, "depth", "integer", "Levels of owned elements to include (0 = none, 1 = refs only, 2+ = nested detail). Default 1.");
        prop(s, "include", "array", "Sections: properties, comments, stereotypes, relationships. Default all.");
        s.getAsJsonObject("properties").getAsJsonObject("include").add("items", JsonParser.parseString("{\"type\":\"string\"}"));
        return s;
    }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        Element e = ElementJson.resolve(m, str(a, "id"));
        Set<String> inc = new HashSet<>(List.of("properties", "comments", "stereotypes", "relationships"));
        if (a.has("include")) { inc.clear(); a.getAsJsonArray("include").forEach(x -> inc.add(x.getAsString())); }
        JsonObject out = new JsonObject();
        out.add("element", ElementJson.detail(e, inc, integer(a, "depth", 1), m.modelSet()));
        return out;
    }
}
