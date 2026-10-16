package org.papyrus.mcp.util;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.util.UMLUtil;

/** JSON views of UML elements shared by all read tools. Call inside runExclusive. */
public final class ElementJson {
    private ElementJson() {}

    public static String id(EObject e) {
        Resource r = e.eResource();
        return r == null ? null : r.getURIFragment(e);
    }

    /** Small reference: id, metaclass, name, qualifiedName. */
    public static JsonObject ref(EObject e) {
        JsonObject o = new JsonObject();
        if (e == null) return o;
        o.addProperty("id", id(e));
        o.addProperty("metaclass", e.eClass().getName());
        if (e instanceof NamedElement n) {
            if (n.getName() != null) o.addProperty("name", n.getName());
            if (n.getQualifiedName() != null) o.addProperty("qualifiedName", n.getQualifiedName());
        }
        if (e.eIsProxy()) o.addProperty("unresolvedProxy", EcoreUtil.getURI(e).toString());
        return o;
    }

    /** Resolve by XMI id in the model's resources, else by qualified name. */
    public static Element resolve(ModelLocator.Active m, String idOrQn) {
        if (idOrQn == null) return m.root();
        EObject e = m.umlResource().getEObject(idOrQn);
        if (e == null) for (Resource r : m.modelSet().getResources()) {
            if (r.getURI().fileExtension() != null && r.getURI().fileExtension().equals("uml")) {
                e = r.getEObject(idOrQn);
                if (e != null) break;
            }
        }
        if (e instanceof Element el) return el;
        Collection<NamedElement> found = UMLUtil.findNamedElements(m.modelSet(), idOrQn);
        if (!found.isEmpty()) return found.iterator().next();
        throw new IllegalArgumentException("element not found: " + idOrQn);
    }

    /** Full view of one element, with selectable sections. */
    public static JsonObject detail(Element e, Set<String> include, int depth, ResourceSet rs) {
        JsonObject o = ref(e);
        o.addProperty("etag", etag(e));
        if (e.getOwner() != null) o.add("owner", ref(e.getOwner()));
        if (include.contains("properties")) o.add("properties", properties(e));
        if (include.contains("comments")) o.add("comments", comments(e, rs));
        if (include.contains("stereotypes")) o.add("stereotypes", stereotypes(e));
        if (include.contains("relationships")) o.add("relationships", relationships(e, null, "both"));
        if (depth > 0) {
            JsonArray kids = new JsonArray();
            for (Element c : e.getOwnedElements())
                kids.add(depth > 1 ? detail(c, include, depth - 1, rs) : ref(c));
            o.add("ownedElements", kids);
        }
        return o;
    }

    /** Non-derived structural features that are set: attributes as values, references as refs. */
    public static JsonObject properties(EObject e) {
        JsonObject p = new JsonObject();
        for (EStructuralFeature f : e.eClass().getEAllStructuralFeatures()) {
            if (f.isDerived() || f.isTransient() || !e.eIsSet(f)) continue;
            if (f instanceof EReference r && r.isContainment()) continue; // children are listed separately
            Object v = e.eGet(f, false);
            p.add(f.getName(), value(v));
        }
        // Useful derived conveniences
        if (e instanceof MultiplicityElement me) {
            p.addProperty("lower", me.getLower());
            p.addProperty("upper", me.getUpper());
        }
        if (e instanceof TypedElement te && te.getType() != null) p.add("type", ref(te.getType()));
        if (e instanceof Constraint c && c.getSpecification() != null) p.addProperty("specification", c.getSpecification().stringValue());
        return p;
    }

    private static JsonElement value(Object v) {
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof Collection<?> c) {
            JsonArray a = new JsonArray();
            int n = 0;
            for (Object x : c) { if (n++ >= 100) { a.add("..." + (c.size() - 100) + " more"); break; } a.add(value(x)); }
            return a;
        }
        if (v instanceof EObject eo) return ref(eo);
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Number nu) return new JsonPrimitive(nu);
        return new JsonPrimitive(String.valueOf(v));
    }

    /** Owned comments plus comments elsewhere that annotate this element. */
    public static JsonArray comments(Element e, ResourceSet rs) {
        JsonArray a = new JsonArray();
        Set<Comment> seen = new LinkedHashSet<>(e.getOwnedComments());
        for (EStructuralFeature.Setting s : EcoreUtil.UsageCrossReferencer.find(e, rs))
            if (s.getEObject() instanceof Comment c && s.getEStructuralFeature() == UMLPackage.Literals.COMMENT__ANNOTATED_ELEMENT)
                seen.add(c);
        for (Comment c : seen) {
            JsonObject o = new JsonObject();
            o.addProperty("id", id(c));
            o.addProperty("body", c.getBody());
            o.addProperty("owned", c.getOwner() == e);
            a.add(o);
        }
        return a;
    }

    public static JsonArray stereotypes(Element e) {
        JsonArray a = new JsonArray();
        for (Stereotype st : e.getAppliedStereotypes()) {
            JsonObject o = new JsonObject();
            o.addProperty("stereotype", st.getQualifiedName());
            o.addProperty("profile", st.getProfile() == null ? null : st.getProfile().getQualifiedName());
            JsonObject tags = new JsonObject();
            for (Property p : st.getAllAttributes()) {
                if (p.getName() == null || p.getName().startsWith("base_")) continue;
                try { tags.add(p.getName(), value(e.getValue(st, p.getName()))); } catch (Exception ignore) {}
            }
            o.add("taggedValues", tags);
            a.add(o);
        }
        return a;
    }

    /**
     * Relationships touching e. kind = metaclass name filter (Association, Generalization, ...);
     * direction = out | in | both (associations have no direction: always included as "both").
     */
    public static JsonArray relationships(Element e, String kind, String direction) {
        JsonArray a = new JsonArray();
        boolean out = !"in".equals(direction), in = !"out".equals(direction);
        List<Relationship> rels = new ArrayList<>();
        if (out) rels.addAll(e.getSourceDirectedRelationships());
        if (in) rels.addAll(e.getTargetDirectedRelationships());
        if (e instanceof Type t) rels.addAll(t.getAssociations());
        Set<Relationship> seen = new HashSet<>();
        for (Relationship r : rels) {
            if (!seen.add(r)) continue;
            if (kind != null && !r.eClass().getName().equals(kind)) continue;
            JsonObject o = ref(r);
            if (r instanceof DirectedRelationship d) {
                JsonArray s = new JsonArray(), t = new JsonArray();
                d.getSources().forEach(x -> s.add(ref(x)));
                d.getTargets().forEach(x -> t.add(ref(x)));
                o.add("sources", s);
                o.add("targets", t);
                o.addProperty("direction", d.getSources().contains(e) ? "out" : "in");
            } else if (r instanceof Association as) {
                JsonArray ends = new JsonArray();
                for (Property p : as.getMemberEnds()) {
                    JsonObject en = new JsonObject();
                    en.addProperty("id", id(p));
                    en.addProperty("name", p.getName());
                    en.add("type", ref(p.getType()));
                    en.addProperty("navigable", p.isNavigable());
                    en.addProperty("aggregation", p.getAggregation().getLiteral());
                    en.addProperty("multiplicity", p.getLower() + ".." + (p.getUpper() < 0 ? "*" : p.getUpper()));
                    ends.add(en);
                }
                o.add("ends", ends);
                o.addProperty("direction", "both");
            }
            a.add(o);
        }
        return a;
    }

    /**
     * Per-element etag: short hash of the element's own set features + applied stereotypes.
     * Changes when the element itself changes (not when unrelated elements change).
     */
    public static String etag(EObject e) {
        StringBuilder sb = new StringBuilder();
        for (EStructuralFeature f : e.eClass().getEAllStructuralFeatures()) {
            if (f.isDerived() || f.isTransient() || !e.eIsSet(f)) continue;
            Object v = e.eGet(f, false);
            sb.append(f.getName()).append('=');
            if (v instanceof Collection<?> c) for (Object x : c) sb.append(x instanceof EObject eo ? EcoreUtil.getURI(eo).fragment() : x).append(',');
            else sb.append(v instanceof EObject eo ? EcoreUtil.getURI(eo).fragment() : v);
            sb.append(';');
        }
        if (e instanceof Element el) for (Stereotype st : el.getAppliedStereotypes()) sb.append('<').append(st.getQualifiedName()).append('>');
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (Exception ex) { return Integer.toHexString(sb.toString().hashCode()); }
    }
}
