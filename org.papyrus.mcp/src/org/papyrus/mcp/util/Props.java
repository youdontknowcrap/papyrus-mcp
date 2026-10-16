package org.papyrus.mcp.util;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.uml2.uml.*;

/**
 * Generic "set feature from JSON" used by update_element and the properties arg of create tools.
 * Conveniences: multiplicity "0..*", lower, upper, type (id/qn), body, default, specification.
 * Everything else: any non-derived, non-containment EMF feature of the metaclass by name;
 * attributes are converted from strings (enums by literal, e.g. visibility "private"),
 * references take element ids / qualified names (arrays for many-valued).
 */
public final class Props {
    private Props() {}

    public static void set(ModelLocator.Active m, Element e, JsonObject set) {
        for (Map.Entry<String, JsonElement> en : set.entrySet()) setOne(m, e, en.getKey(), en.getValue());
    }

    public static void setOne(ModelLocator.Active m, Element e, String key, JsonElement v) {
        switch (key) {
            case "multiplicity" -> { mult(e, v.getAsString()); return; }
            case "lower" -> { asMult(e).setLower(v.getAsInt()); return; }
            case "upper" -> { asMult(e).setUpper(upper(v.getAsString())); return; }
            case "type" -> {
                if (!(e instanceof TypedElement te)) throw new IllegalArgumentException(e.eClass().getName() + " has no type");
                te.setType(v.isJsonNull() ? null : (Type) ElementJson.resolve(m, v.getAsString()));
                return;
            }
            case "body" -> { if (e instanceof Comment c) { c.setBody(v.getAsString()); return; } }
            case "default" -> { if (e instanceof Property p) { p.setDefault(v.isJsonNull() ? null : v.getAsString()); return; } }
            case "specification" -> {
                if (e instanceof Constraint c) {
                    OpaqueExpression oe = c.getSpecification() instanceof OpaqueExpression x ? x
                            : (OpaqueExpression) c.createSpecification(null, null, UMLPackage.Literals.OPAQUE_EXPRESSION);
                    oe.getBodies().clear(); oe.getLanguages().clear();
                    oe.getLanguages().add("OCL"); oe.getBodies().add(v.getAsString());
                    return;
                }
            }
            default -> {}
        }
        EStructuralFeature f = e.eClass().getEStructuralFeature(key);
        if (f == null) throw new IllegalArgumentException(e.eClass().getName() + " has no feature '" + key + "'");
        if (f.isDerived() || !f.isChangeable()) throw new IllegalArgumentException("feature '" + key + "' is derived/read-only");
        if (f instanceof EReference r && r.isContainment())
            throw new IllegalArgumentException("'" + key + "' holds owned elements; use create_element / add_property instead");
        if (f.isMany()) {
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) e.eGet(f);
            list.clear();
            if (!v.isJsonNull()) for (JsonElement x : v.getAsJsonArray()) list.add(convert(m, f, x));
        } else {
            e.eSet(f, v.isJsonNull() ? null : convert(m, f, v));
        }
    }

    private static Object convert(ModelLocator.Active m, EStructuralFeature f, JsonElement v) {
        if (f instanceof EAttribute a) return EcoreUtil.createFromString(a.getEAttributeType(), v.getAsString());
        return ElementJson.resolve(m, v.getAsString());
    }

    private static MultiplicityElement asMult(Element e) {
        if (e instanceof MultiplicityElement me) return me;
        throw new IllegalArgumentException(e.eClass().getName() + " has no multiplicity");
    }

    public static int upper(String s) { return s.equals("*") ? LiteralUnlimitedNatural.UNLIMITED : Integer.parseInt(s.trim()); }

    /** "1", "*", "0..1", "1..*". */
    public static void mult(Element e, String s) {
        MultiplicityElement me = asMult(e);
        int[] lu = parseMult(s);
        me.setLower(lu[0]);
        me.setUpper(lu[1]);
    }

    public static int[] parseMult(String s) {
        s = s.trim();
        if (s.contains("..")) { String[] p = s.split("\\.\\."); return new int[]{Integer.parseInt(p[0].trim()), upper(p[1])}; }
        if (s.equals("*")) return new int[]{0, -1};
        int n = Integer.parseInt(s);
        return new int[]{n, n};
    }
}
