package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.uml2.uml.Element;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

/** Paged listing of the owned elements of a container. */
public class ListElementsTool extends ReadTool {
    public String name() { return "list_elements"; }
    public String description() { return "List elements owned by a container (package, class, ...), optionally recursive and filtered by metaclass. Paged."; }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "container", "string", "XMI id or qualified name. Default: model root.");
        prop(s, "metaclass", "string", "UML metaclass filter incl. subtypes, e.g. Class, Classifier, Association, Comment.");
        prop(s, "recursive", "boolean", "Include all descendants. Default false.");
        prop(s, "limit", "integer", "Max results (default 200, max 1000).");
        prop(s, "offset", "integer", "Skip this many matches (paging). Default 0.");
        return s;
    }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        Element c = ElementJson.resolve(m, str(a, "container"));
        String mc = str(a, "metaclass");
        boolean rec = a.has("recursive") && a.get("recursive").getAsBoolean();
        int limit = Math.min(integer(a, "limit", 200), 1000), offset = integer(a, "offset", 0), total = 0;
        JsonArray items = new JsonArray();
        Iterator<? extends EObject> it = rec ? c.eAllContents() : c.getOwnedElements().iterator();
        while (it.hasNext()) {
            EObject e = it.next();
            if (!(e instanceof Element)) continue;
            if (!FindElementsTool.matchesMetaclass(e, mc)) continue;
            if (total >= offset && items.size() < limit) items.add(ElementJson.ref(e));
            total++;
        }
        JsonObject out = new JsonObject();
        out.add("container", ElementJson.ref(c));
        out.addProperty("total", total);
        out.addProperty("offset", offset);
        out.add("elements", items);
        return out;
    }
}
