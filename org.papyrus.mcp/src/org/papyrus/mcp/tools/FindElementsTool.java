package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.util.Iterator;
import java.util.regex.Pattern;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.uml2.uml.*;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

/** Model-wide search by name (substring, case-insensitive, or /regex/), metaclass, stereotype. */
public class FindElementsTool extends ReadTool {
    public String name() { return "find_elements"; }
    public String description() {
        return "Search the whole model by name (case-insensitive substring, or /regex/), metaclass and/or applied stereotype.";
    }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "query", "string", "Name substring, or /regex/. Empty = any name.");
        prop(s, "metaclass", "string", "UML metaclass incl. subtypes (Class, Actor, UseCase, Property, ...).");
        prop(s, "stereotype", "string", "Applied stereotype name or qualified name.");
        prop(s, "limit", "integer", "Max results (default 100).");
        return s;
    }

    static boolean matchesMetaclass(EObject e, String mc) {
        if (mc == null || mc.isBlank()) return true;
        EClass want = (EClass) UMLPackage.eINSTANCE.getEClassifier(mc);
        if (want == null) throw new IllegalArgumentException("unknown UML metaclass: " + mc);
        return want.isSuperTypeOf(e.eClass());
    }

    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        String q = str(a, "query"), st = str(a, "stereotype"), mc = str(a, "metaclass");
        Pattern re = q != null && q.length() > 2 && q.startsWith("/") && q.endsWith("/")
                ? Pattern.compile(q.substring(1, q.length() - 1)) : null;
        String low = q == null ? null : q.toLowerCase();
        int limit = integer(a, "limit", 100), total = 0;
        JsonArray items = new JsonArray();
        for (Iterator<EObject> it = m.root().eAllContents(); it.hasNext();) {
            EObject e = it.next();
            if (!(e instanceof Element el) || !matchesMetaclass(e, mc)) continue;
            if (q != null && !q.isEmpty()) {
                String n = e instanceof NamedElement ne && ne.getName() != null ? ne.getName() : "";
                if (re != null ? !re.matcher(n).find() : !n.toLowerCase().contains(low)) continue;
            }
            if (st != null && el.getAppliedStereotypes().stream()
                    .noneMatch(x -> st.equals(x.getName()) || st.equals(x.getQualifiedName()))) continue;
            if (items.size() < limit) items.add(ElementJson.ref(e));
            total++;
        }
        JsonObject out = new JsonObject();
        out.addProperty("total", total);
        out.add("elements", items);
        return out;
    }
}
