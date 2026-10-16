package org.papyrus.mcp.util;

import java.util.*;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.notation.*;
import org.eclipse.uml2.uml.*;
import org.eclipse.gmf.runtime.notation.Node;
import org.osgi.framework.Bundle;

/**
 * GMF (classic Papyrus) diagram helpers. Knowledge reused from /workspace/mudhut/papyrus-out:
 * diagram types, diagramKindIds, PapyrusDiagramStyle{diagramKindId, owner}, compatibility style
 * 1.4.0, and the Papyrus 7 string visual ids ("Class_Shape", "Association_Edge", ...).
 *
 * Views are created with GMF ViewService + the Papyrus view providers of each diagram plugin,
 * so nodes get their full label/compartment structure exactly like palette-created ones, and it
 * works without the diagram being open. Visual ids come from the plugin's UMLVisualIDRegistry
 * (loaded reflectively, no compile-time dependency on the diagram plugins).
 */
public final class Diagrams {
    private Diagrams() {}

    public record Kind(String key, String type, String kindId, String plugin) {}

    public static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        add("class", "PapyrusUMLClassDiagram", "org.eclipse.papyrus.uml.diagram.class", "org.eclipse.papyrus.uml.diagram.clazz");
        add("usecase", "UseCase", "org.eclipse.papyrus.uml.diagram.useCase", "org.eclipse.papyrus.uml.diagram.usecase");
        add("component", "PapyrusUMLComponentDiagram", "org.eclipse.papyrus.uml.diagram.component.root.package", "org.eclipse.papyrus.uml.diagram.component");
    }
    private static void add(String k, String t, String id, String p) { KINDS.put(k, new Kind(k, t, id, p)); }

    public static Kind kindOf(Diagram d) {
        for (Kind k : KINDS.values()) if (k.type.equals(d.getType())) return k;
        throw new IllegalArgumentException("diagram type " + d.getType() + " is not supported by diagram tools yet (supported: " + KINDS.keySet() + ")");
    }

    public static Kind kind(String key) {
        Kind k = KINDS.get(key);
        if (k == null) throw new IllegalArgumentException("unsupported diagram kind '" + key + "' (supported: " + KINDS.keySet()
            + "; activity/statemachine/sequence need frame nodes and come later)");
        return k;
    }

    private static java.lang.Class<?> cls(Kind k, String simple) throws Exception {
        Bundle b = Platform.getBundle(k.plugin);
        if (b == null) throw new IllegalStateException("bundle " + k.plugin + " not installed");
        return b.loadClass(k.plugin + ".part." + simple);
    }

    public static PreferencesHint prefHint(Kind k) {
        try { return (PreferencesHint) cls(k, "UMLDiagramEditorPlugin").getField("DIAGRAM_PREFERENCES_HINT").get(null); }
        catch (Exception e) { return PreferencesHint.USE_DEFAULTS; }
    }

    public static String nodeHint(Kind k, View container, EObject element) {
        try {
            Object v = cls(k, "UMLVisualIDRegistry").getMethod("getNodeVisualID", View.class, EObject.class).invoke(null, container, element);
            if (v instanceof String s && !s.isEmpty() && !s.equals("-1")) return s;
        } catch (Exception ignore) {}
        return element.eClass().getName() + "_Shape";
    }

    public static String edgeHint(Kind k, EObject element) {
        try {
            Object v = cls(k, "UMLVisualIDRegistry").getMethod("getLinkWithClassVisualID", EObject.class).invoke(null, element);
            if (v instanceof String s && !s.isEmpty() && !s.equals("-1")) return s;
        } catch (Exception ignore) {}
        return element.eClass().getName() + "_Edge";
    }

    /**
     * Create an edge view. Papyrus' generated view providers only provide an edge when the semantic
     * adapter also adapts to the matching IElementType (org.eclipse.papyrus.umldi.<hint>), so we
     * pass an adapter that answers both EObject and IElementType, then connect source/target.
     */
    public static Edge createEdge(Diagram d, View src, View tgt, EObject rel, String hint, PreferencesHint ph) {
        org.eclipse.gmf.runtime.emf.type.core.IElementType et =
            org.eclipse.gmf.runtime.emf.type.core.ElementTypeRegistry.getInstance().getType("org.eclipse.papyrus.umldi." + hint);
        org.eclipse.core.runtime.IAdaptable ad = new org.eclipse.core.runtime.IAdaptable() {
            @Override public <T> T getAdapter(java.lang.Class<T> c) {
                if (c.isInstance(rel)) return c.cast(rel);
                if (et != null && c.isInstance(et)) return c.cast(et);
                return null;
            }
        };
        Edge e = org.eclipse.gmf.runtime.diagram.core.services.ViewService.getInstance()
            .createEdge(ad, d, hint, org.eclipse.gmf.runtime.diagram.core.util.ViewUtil.APPEND, true, ph);
        if (e == null) e = org.eclipse.gmf.runtime.diagram.core.services.ViewService.createEdge(src, tgt, rel, hint, ph);
        if (e == null) return null;
        e.setSource(src);
        e.setTarget(tgt);
        return e;
    }

    /** The .notation resource that belongs to the model. */
    public static Resource notationResource(ModelLocator.Active m) {
        var base = m.umlResource().getURI().trimFileExtension();
        for (Resource r : m.modelSet().getResources())
            if ("notation".equals(r.getURI().fileExtension()) && r.getURI().trimFileExtension().equals(base)) return r;
        throw new IllegalStateException("no .notation resource for " + m.uri());
    }

    /** Diagram by XMI id or exact name (GMF only). */
    public static Diagram find(ModelLocator.Active m, String idOrName) {
        Resource nr = notationResource(m);
        EObject o = nr.getEObject(idOrName);
        if (o instanceof Diagram d) return d;
        for (EObject c : nr.getContents()) if (c instanceof Diagram d && idOrName.equals(d.getName())) return d;
        throw new IllegalArgumentException("no GMF diagram with id or name '" + idOrName + "' (see list_diagrams)");
    }

    /** Adds the Papyrus styles that make Papyrus 7 recognize and open the diagram. */
    public static void addPapyrusStyles(Diagram d, Kind k, EObject owner) {
        EPackage sp = EPackage.Registry.INSTANCE.getEPackage("http://www.eclipse.org/papyrus/infra/gmfdiag/style");
        if (sp != null && d.getStyles().stream().noneMatch(s -> ((EObject) s).eClass().getName().equals("PapyrusDiagramStyle"))) {
            EClass c = (EClass) sp.getEClassifier("PapyrusDiagramStyle");
            EObject st = sp.getEFactoryInstance().create(c);
            st.eSet(c.getEStructuralFeature("diagramKindId"), k.kindId);
            st.eSet(c.getEStructuralFeature("owner"), owner);
            @SuppressWarnings("unchecked") List<Object> styles = d.getStyles();
            styles.add(st);
        }
        if (d.getNamedStyle(NotationPackage.Literals.STRING_VALUE_STYLE, "diagram_compatibility_version") == null) {
            StringValueStyle v = NotationFactory.eINSTANCE.createStringValueStyle();
            v.setName("diagram_compatibility_version");
            v.setStringValue("1.4.0");
            @SuppressWarnings("unchecked") List<Object> styles = d.getStyles();
            styles.add(v);
        }
    }

    /** Top-level node of the diagram showing element e, or null. */
    public static Node nodeFor(Diagram d, EObject e) {
        for (Object o : d.getChildren()) if (o instanceof Node n && n.getElement() == e) return n;
        return null;
    }

    public static boolean hasEdge(Diagram d, EObject rel) {
        for (Object o : d.getEdges()) if (((Edge) o).getElement() == rel) return true;
        return false;
    }

    /** [source, target] semantic ends of a relationship as drawn by Papyrus, or null. */
    public static EObject[] ends(EObject r) {
        if (r instanceof Association a && a.getMemberEnds().size() == 2)
            return new EObject[]{a.getMemberEnds().get(0).getType(), a.getMemberEnds().get(1).getType()};
        if (r instanceof Generalization g) return new EObject[]{g.getSpecific(), g.getGeneral()};
        if (r instanceof InterfaceRealization ir) return new EObject[]{ir.getImplementingClassifier(), ir.getContract()};
        if (r instanceof Include i) return new EObject[]{i.getIncludingCase(), i.getAddition()};
        if (r instanceof Extend x) return new EObject[]{x.getExtension(), x.getExtendedCase()};
        if (r instanceof PackageImport pi) return new EObject[]{pi.getImportingNamespace(), pi.getImportedPackage()};
        if (r instanceof Dependency dp && !dp.getClients().isEmpty() && !dp.getSuppliers().isEmpty())
            return new EObject[]{dp.getClients().get(0), dp.getSuppliers().get(0)};
        return null;
    }

    /** Relationships touching e (directed + associations + package imports). */
    public static Set<EObject> relationshipsOf(Element e) {
        Set<EObject> s = new LinkedHashSet<>();
        s.addAll(e.getSourceDirectedRelationships());
        s.addAll(e.getTargetDirectedRelationships());
        if (e instanceof Type t) s.addAll(t.getAssociations());
        return s;
    }

    /** Rough rendered size of a node, for layout when the view has no explicit size. */
    public static int[] estimateSize(Node n) {
        EObject e = n.getElement();
        if (e instanceof Actor) return new int[]{60, 100};
        if (e instanceof UseCase uc) return new int[]{Math.max(160, len(uc) * 8 + 40), 70};
        int rows = 0;
        if (e instanceof Classifier c) {
            // association ends are drawn as edge labels, not compartment rows
            for (Object f : c.getFeatures()) if ((f instanceof Property p && p.getAssociation() == null) || f instanceof Operation) rows++;
            if (c instanceof org.eclipse.uml2.uml.Enumeration en) rows += en.getOwnedLiterals().size();
        }
        int w = Math.max(140, Math.min(320, len(e) * 9 + 40));
        if (e instanceof Classifier c) for (Object f : c.getFeatures())
            if (f instanceof NamedElement ne && ne.getName() != null) w = Math.max(w, Math.min(360, ne.getName().length() * 8 + 90));
        return new int[]{w, Math.min(500, 60 + rows * 18 + (rows > 0 ? 20 : 0))};
    }
    private static int len(EObject e) { return e instanceof NamedElement ne && ne.getName() != null ? ne.getName().length() : 8; }
}
