package org.papyrus.mcp.util;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.diagram.core.services.ViewService;
import org.eclipse.gmf.runtime.notation.*;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;

/**
 * Shared plumbing for the three "behavior" diagram kinds: sequence, activity and state machine.
 *
 * Unlike class/use case diagrams (where a bot drops elements on a canvas and we lay them out),
 * these diagrams are a PROJECTION of one behavior: the diagram is (re)drawn from the model by a
 * renderer (Sequences / Activities / StateMachines). Re-rendering keeps the diagram's id and
 * name, so a bot can edit the model in small steps and call the renderer again.
 *
 * Nodes are created with GMF ViewService + the Papyrus view providers, exactly like the palette
 * would (labels and compartments included). The right visual id for an element inside a given
 * container comes from the diagram plugin's UMLVisualIDRegistry, which we call reflectively.
 */
public final class Behaviors {
    private Behaviors() {}

    public static final Diagrams.Kind SEQUENCE = new Diagrams.Kind("sequence", "PapyrusUMLSequenceDiagram",
        "org.eclipse.papyrus.uml.diagram.sequence", "org.eclipse.papyrus.uml.diagram.sequence");
    public static final Diagrams.Kind ACTIVITY = new Diagrams.Kind("activity", "PapyrusUMLActivityDiagram",
        "org.eclipse.papyrus.uml.diagram.activity", "org.eclipse.papyrus.uml.diagram.activity");
    public static final Diagrams.Kind STATEMACHINE = new Diagrams.Kind("statemachine", "PapyrusUMLStateMachineDiagram",
        "org.eclipse.papyrus.uml.diagram.stateMachine", "org.eclipse.papyrus.uml.diagram.statemachine");
    public static final List<Diagrams.Kind> ALL = List.of(SEQUENCE, ACTIVITY, STATEMACHINE);

    public static Diagrams.Kind kindOf(Diagram d) {
        for (Diagrams.Kind k : ALL) if (k.type().equals(d.getType())) return k;
        return null;
    }

    private static org.osgi.framework.Bundle bundle(Diagrams.Kind k) {
        org.osgi.framework.Bundle b = org.eclipse.core.runtime.Platform.getBundle(k.plugin());
        if (b == null) throw new IllegalStateException("bundle " + k.plugin() + " not installed");
        return b;
    }

    /**
     * Create the view of element inside parent: tries parent itself, then its compartments
     * (children that are BasicCompartment/DecorationNode), the first place the registry accepts.
     */
    public static Node node(Diagrams.Kind k, View parent, EObject element, int x, int y, int w, int h) {
        View target = parent;
        String hint = hintAt(k, parent, element);
        if (hint == null) for (Object o : parent.getChildren()) {
            // compartments are the child views that hold nested nodes
            if (o instanceof View c && (c instanceof BasicCompartment || (c.getType() != null && c.getType().contains("Compartment")))) {
                String h2 = hintAt(k, c, element);
                if (h2 != null) { target = c; hint = h2; break; }
            }
        }
        if (hint == null) throw new IllegalStateException("Papyrus has no view for a " + element.eClass().getName()
            + " inside a " + parent.getType() + " on a " + k.key() + " diagram");
        Node n = ViewService.createNode(target, element, hint, Diagrams.prefHint(k));
        if (n == null) throw new IllegalStateException("Papyrus refused to create a " + hint + " view");
        Bounds b = NotationFactory.eINSTANCE.createBounds();
        b.setX(x); b.setY(y); b.setWidth(w); b.setHeight(h);
        n.setLayoutConstraint(b);
        return n;
    }
    private static String hintAt(Diagrams.Kind k, View container, EObject element) {
        try {
            Object v = bundle(k).loadClass(k.plugin() + ".part.UMLVisualIDRegistry")
                .getMethod("getNodeVisualID", View.class, EObject.class).invoke(null, container, element);
            if (v instanceof String s && !s.isEmpty() && !s.equals("-1")) return s;
        } catch (Exception ignore) {}
        return null;
    }

    /** First child compartment of v whose type ends with suffix (e.g. "SubvertexCompartment"). */
    public static View compartment(View v, String suffix) {
        for (Object o : v.getChildren()) if (o instanceof View c && c.getType() != null && c.getType().endsWith(suffix)) return c;
        return null;
    }

    /** Edge view for a model element (message, flow, transition) between two views. */
    public static Edge edge(Diagrams.Kind k, Diagram d, View src, View tgt, EObject element) {
        String hint = Diagrams.edgeHint(k, element);
        Edge e = Diagrams.createEdge(d, src, tgt, element, hint, Diagrams.prefHint(k));
        if (e == null) throw new IllegalStateException("Papyrus refused to draw a " + hint + " edge");
        return e;
    }

    public static IdentityAnchor anchor(double x, double y) {
        IdentityAnchor a = NotationFactory.eINSTANCE.createIdentityAnchor();
        a.setId(String.format(Locale.ROOT, "(%.4f,%.4f)", Math.max(0, Math.min(1, x)), Math.max(0, Math.min(1, y))));
        return a;
    }

    /**
     * Find the diagram of this kind for behavior (by name if given), or create it. If it exists,
     * all its content is removed so the renderer can draw it fresh (id and name are kept).
     */
    public static Diagram diagramFor(ModelLocator.Active m, Diagrams.Kind k, Behavior beh, String name) {
        String nm = name != null ? name : beh.getName();
        var nr = Diagrams.notationResource(m);
        for (EObject o : nr.getContents())
            if (o instanceof Diagram d && k.type().equals(d.getType()) && d.getElement() == beh && nm.equals(d.getName())) {
                new ArrayList<Object>(d.getEdges()).forEach(e -> org.eclipse.emf.ecore.util.EcoreUtil.delete((EObject) e));
                new ArrayList<Object>(d.getChildren()).forEach(c -> org.eclipse.emf.ecore.util.EcoreUtil.delete((EObject) c, true));
                return d;
            }
        Diagram d = null;
        try { d = ViewService.createDiagram(beh, k.type(), Diagrams.prefHint(k)); } catch (Exception ignore) {}
        if (d == null) {                       // fall back to a bare diagram; the renderer adds all nodes anyway
            d = NotationFactory.eINSTANCE.createDiagram();
            d.setType(k.type());
            d.setElement(beh);
            d.setMeasurementUnit(MeasurementUnit.PIXEL_LITERAL);
        }
        new ArrayList<Object>(d.getChildren()).forEach(c -> org.eclipse.emf.ecore.util.EcoreUtil.delete((EObject) c, true));
        d.setName(nm);
        // Papyrus shows the diagram under the behavior's owner (e.g. the use case) in the Model Explorer.
        Diagrams.addPapyrusStyles(d, k, beh.getOwner() != null ? beh.getOwner() : beh);
        nr.getContents().add(d);
        return d;
    }

    /** Owner for a new behavior: a classifier (ownedBehavior) or a package (packagedElement). */
    public static <B extends Behavior> B createBehavior(Element owner, String name, org.eclipse.emf.ecore.EClass ec) {
        @SuppressWarnings("unchecked") B b = (B) org.eclipse.uml2.uml.UMLFactory.eINSTANCE.create(ec);
        b.setName(name);
        if (owner instanceof BehavioredClassifier bc) bc.getOwnedBehaviors().add(b);
        else if (owner instanceof Package p) p.getPackagedElements().add(b);
        else throw new IllegalArgumentException("a " + ec.getName() + " can be owned by a classifier, use case or package, not a "
            + owner.eClass().getName());
        return b;
    }

    /** Behavior of class ec named name directly owned by owner, or null. */
    public static Behavior findBehavior(Element owner, String name, org.eclipse.emf.ecore.EClass ec) {
        for (Element e : owner.getOwnedElements())
            if (ec.isInstance(e) && name.equals(((NamedElement) e).getName())) return (Behavior) e;
        return null;
    }

    public static LiteralString literal(String v) {
        LiteralString l = UMLFactory.eINSTANCE.createLiteralString();
        l.setValue(v);
        return l;
    }

    public static int textWidth(String s, int min, int perChar, int pad) {
        return Math.max(min, (s == null ? 0 : s.length()) * perChar + pad);
    }

    /**
     * Graphviz "dot -Tplain" layout of boxes (ids with sizes) and directed edges.
     * Returns top-left positions in pixels, plus "__size" = total width/height.
     */
    public static Map<String, int[]> dot(Map<String, int[]> sizes, List<String[]> edges, String rankdir, double nodesep, double ranksep)
            throws IOException, InterruptedException {
        StringBuilder g = new StringBuilder("digraph G { rankdir=" + rankdir + "; nodesep=" + nodesep + "; ranksep=" + ranksep
            + "; node[shape=box,fixedsize=true];\n");
        Map<String, String> safe = new HashMap<>(), back = new HashMap<>();
        int i = 0;
        for (var e : sizes.entrySet()) {
            String id = "n" + (i++);
            safe.put(e.getKey(), id); back.put(id, e.getKey());
            g.append(String.format(Locale.ROOT, "%s [width=%.3f,height=%.3f];%n", id, e.getValue()[0] / 72.0, e.getValue()[1] / 72.0));
        }
        for (String[] e : edges) if (safe.containsKey(e[0]) && safe.containsKey(e[1])) g.append(safe.get(e[0])).append(" -> ").append(safe.get(e[1])).append(";\n");
        g.append("}");
        Process p = new ProcessBuilder("dot", "-Tplain").redirectErrorStream(true).start();
        try (OutputStream os = p.getOutputStream()) { os.write(g.toString().getBytes()); }
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException("dot failed: " + out);
        String[] lines = out.split("\n");
        String[] head = lines[0].split(" ");
        double W = Double.parseDouble(head[2]), H = Double.parseDouble(head[3]);
        Map<String, int[]> pos = new HashMap<>();
        for (String l : lines) {
            String[] f = l.split(" ");
            if (!f[0].equals("node")) continue;
            String key = back.get(f[1]);
            int[] s = sizes.get(key);
            double x = Double.parseDouble(f[2]) * 72, y = (H - Double.parseDouble(f[3])) * 72;
            pos.put(key, new int[]{(int) (x - s[0] / 2.0), (int) (y - s[1] / 2.0)});
        }
        pos.put("__size", new int[]{(int) (W * 72), (int) (H * 72)});
        return pos;
    }

    /** Simple fallback when graphviz is missing: one column in the given order. */
    public static Map<String, int[]> column(Map<String, int[]> sizes) {
        Map<String, int[]> pos = new HashMap<>();
        int y = 0, w = 0;
        for (var e : sizes.entrySet()) { pos.put(e.getKey(), new int[]{0, y}); y += e.getValue()[1] + 40; w = Math.max(w, e.getValue()[0]); }
        pos.put("__size", new int[]{w, y});
        return pos;
    }

    public static Map<String, int[]> layout(Map<String, int[]> sizes, List<String[]> edges, String rankdir) {
        try { return dot(sizes, edges, rankdir, 0.5, 0.6); } catch (Exception e) { return column(sizes); }
    }
}
