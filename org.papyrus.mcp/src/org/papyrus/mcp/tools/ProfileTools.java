package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.common.util.BasicDiagnostic;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.Diagnostician;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;
import org.eclipse.uml2.uml.Enumeration;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.*;
import org.papyrus.mcp.util.WriteSupport.Ctx;

/*
 * Phase 4: profiles, stereotypes, validation. Profile/stereotype edits are WriteTools (one undo
 * step, changelog); validate is a ReadTool (does not touch the model).
 */

final class ProfileUtil {
    private ProfileUtil() {}

    /** Well-known aliases for profiles shipped with UML2/Papyrus. */
    static final Map<String, String> ALIASES = Map.of(
        "Standard", "pathmap://UML_PROFILES/Standard.profile.uml",
        "StandardProfile", "pathmap://UML_PROFILES/Standard.profile.uml",
        "Ecore", "pathmap://UML_PROFILES/Ecore.profile.uml");

    /**
     * Resolve a profile: (1) a profile already applied/loaded in the ModelSet by name or qualified
     * name, (2) an alias, (3) a URI (pathmap:, platform:, file: or an absolute path). Loading a new
     * profile resource into the ModelSet is a resource-set change, not a model change.
     */
    static Profile find(ModelLocator.Active m, String spec) {
        for (Resource r : m.modelSet().getResources())
            for (EObject o : r.getContents())
                if (o instanceof Profile p && (spec.equals(p.getName()) || spec.equals(p.getQualifiedName()) || spec.equals(p.getURI()))) return p;
        String uri = ALIASES.getOrDefault(spec, spec);
        URI u = uri.startsWith("/") ? URI.createFileURI(uri) : URI.createURI(uri);
        Resource r = m.modelSet().getResource(u, true);
        for (EObject o : r.getContents()) if (o instanceof Profile p) return p;
        throw new IllegalArgumentException("no Profile in " + u);
    }

    /** Applicable stereotype on e by name or qualified name; also checks already-applied ones. */
    static Stereotype stereotype(Element e, String spec) {
        for (Stereotype s : e.getAppliedStereotypes()) if (spec.equals(s.getName()) || spec.equals(s.getQualifiedName())) return s;
        for (Stereotype s : e.getApplicableStereotypes()) if (spec.equals(s.getName()) || spec.equals(s.getQualifiedName())) return s;
        List<String> av = new ArrayList<>();
        for (Stereotype s : e.getApplicableStereotypes()) av.add(s.getQualifiedName());
        throw new IllegalArgumentException("stereotype '" + spec + "' is not applicable to " + e.eClass().getName()
            + " (is its profile applied? applicable: " + av + ")");
    }

    /** JSON -> tagged value of the right kind for property p (primitives, enums, element refs, lists). */
    static Object tagValue(ModelLocator.Active m, Property p, JsonElement v) {
        if (v == null || v.isJsonNull()) return null;
        if (p.isMultivalued() && v.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement x : v.getAsJsonArray()) l.add(single(m, p.getType(), x));
            return l;
        }
        return single(m, p.getType(), v);
    }

    private static Object single(ModelLocator.Active m, Type t, JsonElement v) {
        String tn = t == null ? "" : t.getName();
        if (t instanceof Enumeration en) {
            EnumerationLiteral lit = en.getOwnedLiteral(v.getAsString());
            if (lit == null) throw new IllegalArgumentException("no literal '" + v.getAsString() + "' in " + en.getName());
            return lit; // UML2 converts EnumerationLiteral to the profile's EEnumLiteral
        }
        if (t instanceof PrimitiveType) {
            switch (tn) {
                case "Integer": case "UnlimitedNatural": return v.getAsInt();
                case "Boolean": return v.getAsBoolean();
                case "Real": return v.getAsDouble();
                default: return v.getAsString();
            }
        }
        if (t instanceof org.eclipse.uml2.uml.Class || t instanceof Interface) {
            Element ref = ElementJson.resolve(m, v.getAsString());
            if (t instanceof Stereotype st) { // tag typed by a stereotype: needs the stereotype application
                EObject app = ref.getStereotypeApplication(st);
                if (app == null) throw new IllegalArgumentException(v.getAsString() + " does not have stereotype " + st.getName());
                return app;
            }
            return ref;
        }
        return v.getAsString();
    }

    static void setValues(ModelLocator.Active m, Element e, Stereotype st, JsonObject values) {
        for (var en : values.entrySet()) {
            Property p = st.getAttribute(en.getKey(), null);
            if (p == null || en.getKey().startsWith("base_"))
                throw new IllegalArgumentException("stereotype " + st.getName() + " has no tag '" + en.getKey() + "'");
            e.setValue(st, en.getKey(), tagValue(m, p, en.getValue()));
        }
    }
}

class ApplyProfileTool extends WriteTool {
    public String name() { return "apply_profile"; }
    public String description() {
        return "Apply a UML profile to a package (default: model root). profile = name of a loaded profile, alias "
             + "(Standard, Ecore), or URI (pathmap://..., platform:/plugin/..., platform:/resource/..., file:/... or absolute path). Undoable.";
    }
    protected JsonObject schema() {
        return schema(new String[][]{{"profile", "string", "Profile name/alias/URI."}, {"package", "string", "Target package id/qn (default model root)."},
            {"unapply", "boolean", "true = remove the profile application instead (stereotype applications from it are removed too)."}}, "profile");
    }
    public String label(ModelLocator.Active m, JsonObject a) {
        String pr = str(a, "profile");
        pr = pr == null ? "?" : pr.replaceAll(".*[/\\\\]", "").replaceAll("\\.profile\\.uml$", "");
        return (a.has("unapply") && a.get("unapply").getAsBoolean() ? "unapply profile " : "apply profile ") + pr
            + " to " + (str(a, "package") == null ? m.root().getName() : n(m, a, "package"));
    }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Package pkg = (Package) ElementJson.resolve(ctx.m, str(a, "package"));
        Profile p = ProfileUtil.find(ctx.m, need(a, "profile"));
        boolean un = a.has("unapply") && a.get("unapply").getAsBoolean();
        if (un) { if (pkg.unapplyProfile(p).isEmpty() && pkg.getProfileApplication(p) != null) throw new IllegalStateException("unapply failed"); }
        else {
            if (!p.isDefined()) throw new IllegalArgumentException("profile " + p.getName() + " is not defined (static/defined profiles only)");
            if (pkg.applyProfile(p).isEmpty() && !pkg.isProfileApplied(p)) throw new IllegalStateException("apply failed");
        }
        ctx.touched(pkg);
        ctx.summaries.add((un ? "unapplied" : "applied") + " profile " + p.getQualifiedName() + " on " + EditUtil.nm(pkg));
        JsonObject o = ElementJson.ref(pkg);
        JsonArray st = new JsonArray();
        if (!un) for (Stereotype s : p.getOwnedStereotypes()) st.add(s.getQualifiedName());
        o.addProperty("profile", p.getQualifiedName());
        o.add("stereotypes", st);
        return o;
    }
}

class ApplyStereotypeTool extends WriteTool {
    public String name() { return "apply_stereotype"; }
    public String description() {
        return "Apply a stereotype to an element (its profile must be applied) and/or set tagged values. "
             + "If already applied, only sets the values. values: {tag: value}; enums by literal name, element refs by id/qn. Undoable.";
    }
    protected JsonObject schema() {
        return schema(new String[][]{{"id", "string", "Element id/qn."}, {"stereotype", "string", "Stereotype name or qualified name."},
            {"values", "object", "Optional tagged values {tag: value}."}}, "id", "stereotype");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "apply «" + str(a, "stereotype") + "» to " + n(m, a, "id"); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Element e = ElementJson.resolve(ctx.m, need(a, "id"));
        Stereotype st = ProfileUtil.stereotype(e, need(a, "stereotype"));
        boolean was = e.isStereotypeApplied(st);
        if (!was && e.applyStereotype(st) == null) throw new IllegalStateException("could not apply " + st.getQualifiedName());
        if (a.has("values")) ProfileUtil.setValues(ctx.m, e, st, a.getAsJsonObject("values"));
        ctx.summaries.add((was ? "set tagged values of «" : "applied «") + st.getName() + "» on " + EditUtil.nm(e));
        JsonObject o = EditUtil.created(ctx, e, null);
        ctx.summaries.remove(null);
        o.add("stereotypes", ElementJson.stereotypes(e));
        return o;
    }
}

class UnapplyStereotypeTool extends WriteTool {
    public String name() { return "unapply_stereotype"; }
    public String description() { return "Remove a stereotype (and its tagged values) from an element. Undoable."; }
    protected JsonObject schema() {
        return schema(new String[][]{{"id", "string", "Element id/qn."}, {"stereotype", "string", "Stereotype name or qualified name."}}, "id", "stereotype");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "unapply «" + str(a, "stereotype") + "» from " + n(m, a, "id"); }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Element e = ElementJson.resolve(ctx.m, need(a, "id"));
        Stereotype st = ProfileUtil.stereotype(e, need(a, "stereotype"));
        if (!e.isStereotypeApplied(st)) throw new IllegalArgumentException(st.getName() + " is not applied to " + EditUtil.nm(e));
        e.unapplyStereotype(st);
        ctx.summaries.add("unapplied «" + st.getName() + "» from " + EditUtil.nm(e));
        JsonObject o = EditUtil.created(ctx, e, null);
        ctx.summaries.remove(null);
        o.add("stereotypes", ElementJson.stereotypes(e));
        return o;
    }
}

/**
 * validate: Papyrus' UML diagnostician (UML2 OCL constraints + EMF Validation framework
 * constraints, same engine as Papyrus "Validate Model"), falling back to the EMF Diagnostician.
 * Read-only: results are returned, not written as Problems markers (v1).
 */
class ValidateTool extends ReadTool {
    public String name() { return "validate"; }
    public String description() {
        return "Validate the model or a subtree with Papyrus' UML validation. Returns [{severity, message, element, source, code}]. Read-only.";
    }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "id", "string", "Root element to validate (default: whole model).");
        prop(s, "severity", "string", "Minimum severity: info | warning (default) | error.");
        prop(s, "limit", "integer", "Max problems returned (default 200).");
        return s;
    }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        Element root = ElementJson.resolve(m, str(a, "id"));
        int min = switch (Objects.requireNonNullElse(str(a, "severity"), "warning")) {
            case "info" -> Diagnostic.INFO; case "error" -> Diagnostic.ERROR; default -> Diagnostic.WARNING; };
        Diagnostic d;
        String engine;
        try {
            var pd = new org.eclipse.papyrus.uml.service.validation.api.UMLDiagnostician();
            pd.initialize(new org.eclipse.emf.edit.provider.ComposedAdapterFactory(
                    org.eclipse.emf.edit.provider.ComposedAdapterFactory.Descriptor.Registry.INSTANCE), new org.eclipse.core.runtime.NullProgressMonitor());
            BasicDiagnostic bd = pd.createDefaultDiagnostic(root);
            pd.validate(root, bd, pd.createDefaultContext());
            d = bd;
            engine = "papyrus-uml";
        } catch (Throwable t) {
            d = Diagnostician.INSTANCE.validate(root);
            engine = "emf";
        }
        JsonArray probs = new JsonArray();
        int[] counts = new int[3];
        flatten(d, min, probs, counts, integer(a, "limit", 200));
        JsonObject o = new JsonObject();
        o.addProperty("engine", engine);
        o.add("root", ElementJson.ref(root));
        o.addProperty("errors", counts[2]);
        o.addProperty("warnings", counts[1]);
        o.addProperty("infos", counts[0]);
        o.add("problems", probs);
        return o;
    }
    private void flatten(Diagnostic d, int min, JsonArray out, int[] counts, int limit) {
        for (Diagnostic c : d.getChildren()) {
            if (c.getChildren().isEmpty() || c.getData().stream().anyMatch(x -> x instanceof Element)) {
                int sev = c.getSeverity();
                if (sev == Diagnostic.ERROR) counts[2]++; else if (sev == Diagnostic.WARNING) counts[1]++; else if (sev == Diagnostic.INFO) counts[0]++;
                if (sev >= min && out.size() < limit) {
                    JsonObject o = new JsonObject();
                    o.addProperty("severity", sev == Diagnostic.ERROR ? "error" : sev == Diagnostic.WARNING ? "warning" : "info");
                    o.addProperty("message", c.getMessage());
                    o.addProperty("source", c.getSource());
                    o.addProperty("code", c.getCode());
                    for (Object x : c.getData()) if (x instanceof Element el) { o.add("element", ElementJson.ref(el)); break; }
                    out.add(o);
                }
            }
            if (!c.getChildren().isEmpty()) flatten(c, min, out, counts, limit);
        }
    }
}
