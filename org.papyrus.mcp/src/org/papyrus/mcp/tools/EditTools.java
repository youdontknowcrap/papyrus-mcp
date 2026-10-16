package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gmf.runtime.notation.View;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.Enumeration;
import org.papyrus.mcp.util.*;
import org.papyrus.mcp.util.ModelLocator;
import org.papyrus.mcp.util.WriteSupport.Ctx;

/*
 * Phase-3 model-editing tools. Each is a WriteTool: apply() runs inside one McpCommand (one undo
 * step + one changelog entry) and returns JSON; "id" in the result is what batch's $N refers to.
 * Package-private classes in one file to keep the tool set easy to scan.
 */

final class EditUtil {
    private EditUtil() {}
    static EClass metaclass(String name) {
        EClassifier c = UMLPackage.eINSTANCE.getEClassifier(name);
        if (!(c instanceof EClass ec) || ec.isAbstract()) throw new IllegalArgumentException("not a concrete UML metaclass: " + name);
        return ec;
    }
    static JsonObject created(Ctx ctx, EObject e, String what) {
        ctx.touched(e);
        if (what != null) ctx.summaries.add(what);
        JsonObject o = ElementJson.ref(e);
        o.addProperty("etag", ElementJson.etag(e));
        return o;
    }
    static String nm(Element e) { return e instanceof NamedElement n && n.getName() != null ? n.getName() : ElementJson.id(e); }
    static String lowerFirst(String s) { return s == null || s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }
}

/** create_element: any concrete UML metaclass inside a container, via the Papyrus edit service. */
class CreateElementTool extends WriteTool {
    public String name() { return "create_element"; }
    public String description() {
        return "Create a UML element (Class, Interface, Enumeration, DataType, Package, Actor, UseCase, Component, Signal, "
             + "Activity, StateMachine, Comment, Constraint, InstanceSpecification, ...) in a container. Undoable.";
    }
    protected JsonObject schema() {
        return schema(new String[][]{
            {"container", "string", "Owner: XMI id or qualified name (default: model root)."},
            {"metaclass", "string", "Concrete UML metaclass name, e.g. Class."},
            {"name", "string", "Name of the new element."},
            {"properties", "object", "Optional features to set, as in update_element (e.g. {\"isAbstract\":true,\"visibility\":\"private\"})."}},
            "metaclass");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "create " + str(a, "metaclass") + " " + Objects.toString(str(a, "name"), ""); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Element c = ElementJson.resolve(ctx.m, str(a, "container"));
        EClass ec = EditUtil.metaclass(need(a, "metaclass"));
        EObject e = EditService.create(ctx.m, c, ec);
        if (str(a, "name") != null && e instanceof NamedElement n) n.setName(str(a, "name"));
        if (a.has("properties")) Props.set(ctx.m, (Element) e, a.getAsJsonObject("properties"));
        return EditUtil.created(ctx, e, "created " + ec.getName() + " '" + EditUtil.nm((Element) e) + "' in " + EditUtil.nm(c));
    }
}

/** create_relationship: associations, generalizations, dependencies, include/extend, flows, transitions. */
class CreateRelationshipTool extends WriteTool {
    public String name() { return "create_relationship"; }
    public String description() {
        return "Create a relationship: Association, Generalization, Dependency, Usage, Abstraction, Realization, "
             + "InterfaceRealization, Include, Extend, PackageImport, ControlFlow, ObjectFlow, Transition. Undoable.";
    }
    protected JsonObject schema() {
        return schema(new String[][]{
            {"metaclass", "string", "Relationship metaclass."},
            {"source", "string", "Source element id/qn (client, specific, includingCase, ...)."},
            {"target", "string", "Target element id/qn (supplier, general, addition, ...)."},
            {"name", "string", "Optional name."},
            {"ends", "object", "Association only: {\"source\":{name,navigable,aggregation(none|shared|composite),multiplicity}, \"target\":{...}}. "
                             + "Defaults: target end navigable 1, source end not navigable 1. For composition put aggregation=composite on the PART end (the end typed by the part); the diamond is drawn at the opposite (whole) end, as in UML."}},
            "metaclass", "source", "target");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "create " + str(a, "metaclass") + " " + n(m, a, "source") + " -> " + n(m, a, "target"); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Element s = ElementJson.resolve(ctx.m, need(a, "source")), t = ElementJson.resolve(ctx.m, need(a, "target"));
        String mc = need(a, "metaclass"), name = str(a, "name");
        EClass ec = EditUtil.metaclass(mc);
        Element r;
        switch (mc) {
            case "Association" -> {
                JsonObject ends = a.has("ends") ? a.getAsJsonObject("ends") : new JsonObject();
                JsonObject se = ends.has("source") ? ends.getAsJsonObject("source") : new JsonObject();
                JsonObject te = ends.has("target") ? ends.getAsJsonObject("target") : new JsonObject();
                int[] tm = Props.parseMult(te.has("multiplicity") ? te.get("multiplicity").getAsString() : "1");
                int[] sm = Props.parseMult(se.has("multiplicity") ? se.get("multiplicity").getAsString() : "1");
                // UML2: called on the source type; "end1" is the end typed by target (owned by source when navigable).
                Association as = ((Type) s).createAssociation(
                        !te.has("navigable") || te.get("navigable").getAsBoolean(),
                        AggregationKind.get(te.has("aggregation") ? te.get("aggregation").getAsString() : "none"),
                        te.has("name") ? te.get("name").getAsString() : EditUtil.lowerFirst(EditUtil.nm(t)), tm[0], tm[1], (Type) t,
                        se.has("navigable") && se.get("navigable").getAsBoolean(),
                        AggregationKind.get(se.has("aggregation") ? se.get("aggregation").getAsString() : "none"),
                        se.has("name") ? se.get("name").getAsString() : EditUtil.lowerFirst(EditUtil.nm(s)), sm[0], sm[1]);
                if (name != null) as.setName(name);
                r = as;
            }
            case "Generalization" -> r = ((Classifier) s).createGeneralization((Classifier) t);
            case "InterfaceRealization" -> r = ((BehavioredClassifier) s).createInterfaceRealization(name, (Interface) t);
            case "Include" -> r = ((UseCase) s).createInclude(name, (UseCase) t);
            case "Extend" -> r = ((UseCase) s).createExtend(name, (UseCase) t);
            case "PackageImport" -> r = ((Namespace) s).createPackageImport((Package) t);
            case "ControlFlow", "ObjectFlow" -> {
                Activity act = ((ActivityNode) s).getActivity();
                ActivityEdge e = act.createEdge(name, ec);
                e.setSource((ActivityNode) s);
                e.setTarget((ActivityNode) t);
                r = e;
            }
            case "Transition" -> {
                Region reg = ((Vertex) s).getContainer();
                Transition tr = reg.createTransition(name);
                tr.setSource((Vertex) s);
                tr.setTarget((Vertex) t);
                r = tr;
            }
            default -> {
                if (!UMLPackage.Literals.DEPENDENCY.isSuperTypeOf(ec)) throw new IllegalArgumentException("unsupported relationship: " + mc);
                Package p = s.getNearestPackage();
                Dependency d = (Dependency) p.createPackagedElement(name, ec);
                d.getClients().add((NamedElement) s);
                d.getSuppliers().add((NamedElement) t);
                r = d;
            }
        }
        ctx.touched(s); ctx.touched(t);
        return EditUtil.created(ctx, r, "created " + mc + " " + EditUtil.nm(s) + " -> " + EditUtil.nm(t));
    }
}

/** add_property: attribute on a Class/Interface/DataType/Signal/... */
class AddPropertyTool extends WriteTool {
    public String name() { return "add_property"; }
    public String description() { return "Add an attribute (Property) to a classifier. Undoable."; }
    protected JsonObject schema() {
        return schema(new String[][]{
            {"classifier", "string", "Owner id/qn."}, {"name", "string", "Attribute name."},
            {"type", "string", "Optional type id/qn (e.g. a Class, Enumeration or a primitive type's qualified name)."},
            {"multiplicity", "string", "Optional, e.g. 1, 0..1, 0..*, 1..*."},
            {"visibility", "string", "public|private|protected|package (optional)."},
            {"aggregation", "string", "none|shared|composite (optional)."},
            {"default", "string", "Optional default value (text)."}},
            "classifier", "name");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "add property " + n(m, a, "classifier") + "." + str(a, "name"); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Classifier c = (Classifier) ElementJson.resolve(ctx.m, need(a, "classifier"));
        EStructuralFeature f = c.eClass().getEStructuralFeature("ownedAttribute");
        if (f == null) throw new IllegalArgumentException(c.eClass().getName() + " cannot own attributes");
        Property p = UMLFactory.eINSTANCE.createProperty();
        p.setName(need(a, "name"));
        @SuppressWarnings("unchecked") List<Object> l = (List<Object>) c.eGet(f);
        l.add(p);
        for (String k : new String[]{"type", "multiplicity", "visibility", "aggregation", "default"})
            if (a.has(k)) Props.setOne(ctx.m, p, k, a.get(k));
        ctx.touched(c);
        return EditUtil.created(ctx, p, "added property " + EditUtil.nm(c) + "." + p.getName());
    }
}

/** add_operation: operation with parameters and return type. */
class AddOperationTool extends WriteTool {
    public String name() { return "add_operation"; }
    public String description() { return "Add an operation to a Class/Interface/DataType, with parameters and return type. Undoable."; }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{
            {"classifier", "string", "Owner id/qn."}, {"name", "string", "Operation name."},
            {"params", "array", "Optional [{name, type?, direction?(in|out|inout), multiplicity?}]."},
            {"returnType", "string", "Optional return type id/qn."},
            {"visibility", "string", "Optional."}, {"isAbstract", "boolean", "Optional."}, {"isQuery", "boolean", "Optional."}},
            "classifier", "name");
        s.getAsJsonObject("properties").getAsJsonObject("params").add("items", JsonParser.parseString("{\"type\":\"object\"}"));
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "add operation " + n(m, a, "classifier") + "." + str(a, "name") + "()"; }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Classifier c = (Classifier) ElementJson.resolve(ctx.m, need(a, "classifier"));
        EStructuralFeature f = c.eClass().getEStructuralFeature("ownedOperation");
        if (f == null) throw new IllegalArgumentException(c.eClass().getName() + " cannot own operations");
        Operation op = UMLFactory.eINSTANCE.createOperation();
        op.setName(need(a, "name"));
        @SuppressWarnings("unchecked") List<Object> l = (List<Object>) c.eGet(f);
        l.add(op);
        if (a.has("params")) for (JsonElement pe : a.getAsJsonArray("params")) {
            JsonObject po = pe.getAsJsonObject();
            Parameter p = op.createOwnedParameter(po.get("name").getAsString(), null);
            if (po.has("type")) p.setType((Type) ElementJson.resolve(ctx.m, po.get("type").getAsString()));
            if (po.has("direction")) p.setDirection(ParameterDirectionKind.get(po.get("direction").getAsString()));
            if (po.has("multiplicity")) Props.mult(p, po.get("multiplicity").getAsString());
        }
        if (a.has("returnType")) {
            Parameter r = op.createOwnedParameter("return", (Type) ElementJson.resolve(ctx.m, a.get("returnType").getAsString()));
            r.setDirection(ParameterDirectionKind.RETURN_LITERAL);
        }
        for (String k : new String[]{"visibility", "isAbstract", "isQuery"}) if (a.has(k)) Props.setOne(ctx.m, op, k, a.get(k));
        ctx.touched(c);
        return EditUtil.created(ctx, op, "added operation " + EditUtil.nm(c) + "." + op.getName() + "()");
    }
}

class AddEnumerationLiteralTool extends WriteTool {
    public String name() { return "add_enumeration_literal"; }
    public String description() { return "Add a literal to an Enumeration. Undoable."; }
    protected JsonObject schema() {
        return schema(new String[][]{{"enumeration", "string", "Enumeration id/qn."}, {"name", "string", "Literal name."}}, "enumeration", "name");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "add literal " + n(m, a, "enumeration") + "::" + str(a, "name"); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Enumeration en = (Enumeration) ElementJson.resolve(ctx.m, need(a, "enumeration"));
        EnumerationLiteral l = en.createOwnedLiteral(need(a, "name"));
        ctx.touched(en);
        return EditUtil.created(ctx, l, "added literal " + en.getName() + "::" + l.getName());
    }
}

class UpdateElementTool extends WriteTool {
    public String name() { return "update_element"; }
    public String description() {
        return "Set features of an element: name, visibility, isAbstract, type, multiplicity, lower, upper, default, body, "
             + "specification, or any non-derived UML feature by name (references by id/qn). Undoable.";
    }
    protected JsonObject schema() {
        return schema(new String[][]{{"id", "string", "Element id/qn."}, {"set", "object", "{feature: value}; null clears."}}, "id", "set");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "update " + n(m, a, "id") + " " + a.getAsJsonObject("set").keySet(); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Element e = ElementJson.resolve(ctx.m, need(a, "id"));
        String before = EditUtil.nm(e);
        Props.set(ctx.m, e, a.getAsJsonObject("set"));
        return EditUtil.created(ctx, e, "updated " + before + " " + a.getAsJsonObject("set").keySet());
    }
}

class SetCommentTool extends WriteTool {
    public String name() { return "set_comment"; }
    public String description() { return "Set (replace, default) or add an owned comment that annotates the element. Undoable."; }
    protected JsonObject schema() {
        return schema(new String[][]{{"id", "string", "Element id/qn."}, {"body", "string", "Comment text."},
            {"replace", "boolean", "true (default): replace the first owned comment's text; false: add another comment."}}, "id", "body");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "comment on " + n(m, a, "id"); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Element e = ElementJson.resolve(ctx.m, need(a, "id"));
        boolean replace = !a.has("replace") || a.get("replace").getAsBoolean();
        Comment c = replace && !e.getOwnedComments().isEmpty() ? e.getOwnedComments().get(0) : e.createOwnedComment();
        c.setBody(need(a, "body"));
        if (!c.getAnnotatedElements().contains(e)) c.getAnnotatedElements().add(e);
        ctx.touched(e);
        return EditUtil.created(ctx, c, "set comment on " + EditUtil.nm(e));
    }
}

class MoveElementTool extends WriteTool {
    public String name() { return "move_element"; }
    public String description() { return "Move an element to a new owner (e.g. a class to another package). Undoable."; }
    protected JsonObject schema() {
        return schema(new String[][]{{"id", "string", "Element id/qn."}, {"newContainer", "string", "New owner id/qn."}}, "id", "newContainer");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "move " + n(m, a, "id") + " to " + n(m, a, "newContainer"); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Element e = ElementJson.resolve(ctx.m, need(a, "id")), t = ElementJson.resolve(ctx.m, need(a, "newContainer"));
        EditService.move(ctx.m, e, t);
        if (e.getOwner() != t) throw new IllegalStateException("move did not take effect");
        ctx.touched(t);
        return EditUtil.created(ctx, e, "moved " + EditUtil.nm(e) + " to " + EditUtil.nm(t));
    }
}

/** delete_elements: Papyrus destroy (also removes diagram views); dry_run reports impact only. */
class DeleteElementsTool extends WriteTool {
    public String name() { return "delete_elements"; }
    public String description() {
        return "Delete elements (with owned elements, dependent relationships and their diagram views). "
             + "dry_run=true only reports what would be affected. Undoable.";
    }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{{"ids", "array", "Element ids/qns."}, {"dry_run", "boolean", "Report only (default false)."}}, "ids");
        s.getAsJsonObject("properties").getAsJsonObject("ids").add("items", JsonParser.parseString("{\"type\":\"string\"}"));
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) {
        JsonArray ids = a.getAsJsonArray("ids");
        if (ids.size() == 1) { JsonObject one = new JsonObject(); one.add("id", ids.get(0)); return "delete " + n(m, one, "id"); }
        return "delete " + ids.size() + " elements";
    }

    @Override public JsonObject execute(JsonObject a) throws Exception {
        if (!(a.has("dry_run") && a.get("dry_run").getAsBoolean())) return super.execute(a);
        // Dry run: read-only, no command, no changelog.
        return UiSync.call(() -> {
            ModelLocator.Active m = ModelLocator.require(str(a, "model"));
            JsonObject[] out = new JsonObject[1];
            Exception[] err = new Exception[1];
            m.domain().runExclusive(() -> { try { out[0] = impact(m, a); } catch (Exception e) { err[0] = e; } });
            if (err[0] != null) throw err[0];
            out[0].addProperty("dryRun", true);
            out[0].addProperty("modelVersion", m.version());
            return out[0];
        });
    }

    private JsonObject impact(ModelLocator.Active m, JsonObject a) {
        Set<EObject> doomed = new LinkedHashSet<>();
        JsonArray roots = new JsonArray();
        for (JsonElement i : a.getAsJsonArray("ids")) {
            Element e = ElementJson.resolve(m, i.getAsString());
            roots.add(ElementJson.ref(e));
            doomed.add(e);
            e.eAllContents().forEachRemaining(doomed::add);
        }
        JsonArray rels = new JsonArray(), refs = new JsonArray();
        int views = 0;
        Map<EObject, Collection<EStructuralFeature.Setting>> usages = EcoreUtil.UsageCrossReferencer.findAll(doomed, m.modelSet());
        Set<EObject> seen = new HashSet<>(), relSeen = new HashSet<>();
        for (var en : usages.entrySet())
            for (EStructuralFeature.Setting st : en.getValue()) {
                EObject user = st.getEObject();
                if (doomed.contains(user) || !seen.add(user)) continue;
                if (user instanceof View) views++;
                else if (user instanceof Relationship || (user instanceof Property p && p.getAssociation() != null)) {
                    EObject rel = user instanceof Property p2 ? p2.getAssociation() : user;
                    if (relSeen.add(rel)) rels.add(ElementJson.ref(rel));
                }
                else if (user instanceof Element) refs.add(ElementJson.ref(user));
            }
        JsonObject o = new JsonObject();
        o.add("delete", roots);
        o.addProperty("ownedElementsAlsoDeleted", doomed.size() - roots.size());
        o.add("relationshipsAffected", rels);
        o.add("otherReferencingElements", refs);
        o.addProperty("diagramViewsRemoved", views);
        return o;
    }

    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        JsonArray done = new JsonArray();
        List<Element> targets = new ArrayList<>();
        for (JsonElement i : a.getAsJsonArray("ids")) targets.add(ElementJson.resolve(ctx.m, i.getAsString()));
        for (Element e : targets) {
            done.add(ElementJson.ref(e));
            ctx.touched(e);
            ctx.summaries.add("deleted " + e.eClass().getName() + " '" + EditUtil.nm(e) + "'");
            EditService.destroy(ctx.m, e);
        }
        JsonObject o = new JsonObject();
        o.add("deleted", done);
        return o;
    }
}

/**
 * batch: several write tools in ONE command (one undo step, one changelog entry).
 * Arguments may reference earlier results: "$1" = id of op 1's result, "$2.id" likewise.
 * Any failure rolls back the whole batch.
 */
class BatchTool extends WriteTool {
    private final ToolRegistry reg;
    BatchTool(ToolRegistry reg) { this.reg = reg; }
    public String name() { return "batch"; }
    public String description() {
        return "Run several edit tools atomically as ONE undo step. operations=[{tool, arguments}]; "
             + "string arguments \"$N\" are replaced by the id created by operation N (1-based). All-or-nothing.";
    }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{{"operations", "array", "[{\"tool\":\"create_element\",\"arguments\":{...}}, ...]"},
            {"label", "string", "Optional undo label."}}, "operations");
        s.getAsJsonObject("properties").getAsJsonObject("operations").add("items", JsonParser.parseString("{\"type\":\"object\"}"));
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) {
        return str(a, "label") != null ? str(a, "label") : "batch (" + a.getAsJsonArray("operations").size() + " ops)";
    }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        List<String> ids = new ArrayList<>();
        JsonArray results = new JsonArray();
        int n = 0;
        for (JsonElement opEl : a.getAsJsonArray("operations")) {
            n++;
            JsonObject op = opEl.getAsJsonObject();
            String tool = op.get("tool").getAsString();
            Tool t = reg.get(tool);
            if (!(t instanceof WriteTool wt) || t instanceof BatchTool)
                throw new IllegalArgumentException("op " + n + ": '" + tool + "' is not a batchable edit tool");
            JsonObject args = (JsonObject) subst(op.has("arguments") ? op.get("arguments").deepCopy() : new JsonObject(), ids);
            JsonObject r;
            try { r = wt.apply(ctx, args); }
            catch (Exception e) { throw new IllegalStateException("op " + n + " (" + tool + ") failed: " + e.getMessage(), e); }
            ids.add(r.has("id") ? r.get("id").getAsString() : null);
            results.add(r);
        }
        JsonObject o = new JsonObject();
        o.add("results", results);
        return o;
    }
    private static JsonElement subst(JsonElement e, List<String> ids) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            if (s.matches("\\$\\d+(\\.id)?")) {
                int i = Integer.parseInt(s.replaceAll("\\D", "")) - 1;
                if (i < 0 || i >= ids.size() || ids.get(i) == null) throw new IllegalArgumentException("bad reference " + s);
                return new JsonPrimitive(ids.get(i));
            }
            return e;
        }
        if (e.isJsonArray()) { JsonArray a = new JsonArray(); e.getAsJsonArray().forEach(x -> a.add(subst(x, ids))); return a; }
        if (e.isJsonObject()) { JsonObject o = new JsonObject(); e.getAsJsonObject().entrySet().forEach(en -> o.add(en.getKey(), subst(en.getValue(), ids))); return o; }
        return e;
    }
}
