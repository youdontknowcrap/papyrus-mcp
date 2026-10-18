package org.papyrus.mcp.util;

import com.google.gson.*;
import java.util.*;
import org.eclipse.gmf.runtime.notation.*;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.uml2.uml.*;

/**
 * State machine diagrams: build a StateMachine from a JSON script and draw it, including
 * composite states (one nested region each), laid out left to right with graphviz.
 *
 * Script: {"name":..., "owner":<class or package>,
 *   "states":[{"name":"start","kind":"initial"}, {"name":"Draft","entry":"open editor","do":"autosave","exit":"close"},
 *             {"name":"Review","states":[...nested...],"transitions":[...nested...]}, {"name":"done","kind":"final"}],
 *   "transitions":[{"from":"start","to":"Draft"}, {"from":"Draft","to":"Review","trigger":"submit","guard":"complete","effect":"notify"}]}
 * Kinds: state (default), initial, final, choice, junction, fork, join, shallowHistory, deepHistory,
 * terminate, entryPoint, exitPoint. Triggers become signal events in "<machine> Events".
 */
public final class StateMachines {
    private StateMachines() {}

    public static JsonObject build(WriteSupport.Ctx ctx, JsonObject s) throws Exception {
        ModelLocator.Active m = ctx.m;
        StateMachine sm;
        if (s.has("state_machine")) {
            Element e = ElementJson.resolve(m, s.get("state_machine").getAsString());
            if (!(e instanceof StateMachine x)) throw new IllegalArgumentException("state_machine must name a StateMachine");
            sm = x;
        } else {
            Element owner = s.has("owner") ? ElementJson.resolve(m, s.get("owner").getAsString()) : m.root();
            String name = s.get("name").getAsString();
            Behavior old = Behaviors.findBehavior(owner, name, UMLPackage.Literals.STATE_MACHINE);
            if (old != null) {
                if (!s.has("replace") || !s.get("replace").getAsBoolean())
                    throw new IllegalArgumentException("state machine '" + name + "' exists (pass replace=true to rebuild, or state_machine=<id> to add to it)");
                Activities.removeDiagrams(m, old);
                org.eclipse.emf.ecore.util.EcoreUtil.delete(old, true);
            }
            sm = Behaviors.createBehavior(owner, name, UMLPackage.Literals.STATE_MACHINE);
        }
        Region top = sm.getRegions().isEmpty() ? sm.createRegion("main") : sm.getRegions().get(0);
        int[] count = new int[2];
        Map<String, Vertex> names = new HashMap<>();
        for (Element e : sm.allOwnedElements()) if (e instanceof Vertex v && v.getName() != null) names.putIfAbsent(v.getName(), v);
        fill(sm, top, s, names, count);
        ctx.touched(sm);
        JsonObject out = new JsonObject();
        out.add("stateMachine", ElementJson.ref(sm));
        out.addProperty("verticesCreated", count[0]);
        out.addProperty("transitionsCreated", count[1]);
        if (!s.has("diagram") || s.get("diagram").getAsBoolean()) {
            Diagram d = Behaviors.diagramFor(m, Behaviors.STATEMACHINE, sm, s.has("diagram_name") ? s.get("diagram_name").getAsString() : null);
            JsonObject r = render(d, sm);
            r.addProperty("id", d.eResource().getURIFragment(d));
            r.addProperty("name", d.getName());
            out.add("diagram", r);
        }
        ctx.summaries.add("built state machine '" + sm.getName() + "' (+" + count[0] + " vertices, +" + count[1] + " transitions)");
        return out;
    }

    private static void fill(StateMachine sm, Region r, JsonObject s, Map<String, Vertex> names, int[] count) {
        List<Object[]> nested = new ArrayList<>();
        if (s.has("states")) for (JsonElement je : s.getAsJsonArray("states")) {
            JsonObject st = je.getAsJsonObject();
            String name = st.get("name").getAsString();
            String kind = st.has("kind") ? st.get("kind").getAsString() : "state";
            Vertex v;
            switch (kind.toLowerCase(Locale.ROOT)) {
                case "state" -> {
                    State x = (State) r.createSubvertex(name, UMLPackage.Literals.STATE);
                    if (st.has("entry")) x.setEntry(opaque(st.get("entry").getAsString()));
                    if (st.has("do")) x.setDoActivity(opaque(st.get("do").getAsString()));
                    if (st.has("exit")) x.setExit(opaque(st.get("exit").getAsString()));
                    if (st.has("states")) nested.add(new Object[]{x.createRegion(name + " region"), st});
                    v = x;
                }
                case "final" -> v = r.createSubvertex(name, UMLPackage.Literals.FINAL_STATE);
                default -> {
                    PseudostateKind pk = PseudostateKind.get(kind.equalsIgnoreCase("shallowhistory") ? "shallowHistory"
                        : kind.equalsIgnoreCase("deephistory") ? "deepHistory" : kind.equalsIgnoreCase("entrypoint") ? "entryPoint"
                        : kind.equalsIgnoreCase("exitpoint") ? "exitPoint" : kind.toLowerCase(Locale.ROOT));
                    if (pk == null) throw new IllegalArgumentException("state kind '" + kind + "' unknown");
                    Pseudostate p = (Pseudostate) r.createSubvertex(name, UMLPackage.Literals.PSEUDOSTATE);
                    p.setKind(pk);
                    v = p;
                }
            }
            names.put(name, v);
            count[0]++;
        }
        for (Object[] n : nested) fill(sm, (Region) n[0], (JsonObject) n[1], names, count);
        if (s.has("transitions")) for (JsonElement je : s.getAsJsonArray("transitions")) {
            JsonObject t = je.getAsJsonObject();
            Vertex src = vertex(names, t.get("from").getAsString()), tgt = vertex(names, t.get("to").getAsString());
            // UML: a transition lives in the innermost region containing both ends
            Region owner = commonRegion(src, tgt, r);
            Transition tr = owner.createTransition(t.has("name") ? t.get("name").getAsString() : null);
            tr.setSource(src); tr.setTarget(tgt);
            if (t.has("trigger")) {
                Trigger trg = tr.createTrigger(t.get("trigger").getAsString());
                trg.setEvent(Events.signalEvent(sm, t.get("trigger").getAsString()));
            }
            if (t.has("guard")) { Constraint c = tr.createGuard("guard"); c.setSpecification(Behaviors.literal(t.get("guard").getAsString())); }
            if (t.has("effect")) tr.setEffect(opaque(t.get("effect").getAsString()));
            count[1]++;
        }
    }

    private static Region commonRegion(Vertex a, Vertex b, Region fallback) {
        for (Region r = a.getContainer(); r != null; r = r.getState() != null ? r.getState().getContainer() : null)
            for (Region q = b.getContainer(); q != null; q = q.getState() != null ? q.getState().getContainer() : null)
                if (r == q) return r;
        return fallback;
    }

    private static OpaqueBehavior opaque(String text) {
        OpaqueBehavior b = UMLFactory.eINSTANCE.createOpaqueBehavior();
        b.setName(text);
        return b;
    }

    private static Vertex vertex(Map<String, Vertex> names, String n) {
        Vertex v = names.get(n);
        if (v == null) throw new IllegalArgumentException("transition end '" + n + "' is not a state name (known: " + names.keySet() + ")");
        return v;
    }

    // ------------------------------------------------------------------ rendering

    /** Size of a vertex view; composite states get the size of their laid-out inner region. */
    private static int[] size(Vertex v, Map<Region, Map<String, int[]>> layouts) {
        if (v instanceof Pseudostate p) return switch (p.getKind()) {
            case FORK_LITERAL, JOIN_LITERAL -> new int[]{8, 80};
            case CHOICE_LITERAL -> new int[]{30, 30};
            default -> new int[]{20, 20};
        };
        if (v instanceof FinalState) return new int[]{24, 24};
        State st = (State) v;
        int lines = (st.getEntry() != null ? 1 : 0) + (st.getDoActivity() != null ? 1 : 0) + (st.getExit() != null ? 1 : 0);
        int w = Behaviors.textWidth(st.getName(), 110, 9, 40);
        for (Behavior b : new Behavior[]{st.getEntry(), st.getDoActivity(), st.getExit()})
            if (b != null) w = Math.max(w, Behaviors.textWidth(b.getName(), 0, 7, 70));
        int h = 60 + lines * 18;
        if (!st.getRegions().isEmpty()) {
            int iw = 0, ih = 0;
            for (Region r : st.getRegions()) { int[] t = layout(r, layouts).get("__size"); iw = Math.max(iw, t[0] + 60); ih += t[1] + 60; }
            w = Math.max(w, iw); h += ih;
        }
        return new int[]{w, h};
    }

    private static Map<String, int[]> layout(Region r, Map<Region, Map<String, int[]>> layouts) {
        Map<String, int[]> done = layouts.get(r);
        if (done != null) return done;
        Map<String, int[]> sizes = new LinkedHashMap<>();
        for (Vertex v : r.getSubvertices()) sizes.put(ElementJson.id(v), size(v, layouts));
        List<String[]> edges = new ArrayList<>();
        for (Transition t : r.getTransitions()) edges.add(new String[]{ElementJson.id(t.getSource()), ElementJson.id(t.getTarget())});
        Map<String, int[]> pos = Behaviors.layout(sizes, edges, "LR");
        for (var e : sizes.entrySet()) pos.put("s:" + e.getKey(), e.getValue());
        layouts.put(r, pos);
        return pos;
    }

    public static JsonObject render(Diagram d, StateMachine sm) {
        Diagrams.Kind k = Behaviors.STATEMACHINE;
        Map<Region, Map<String, int[]>> layouts = new HashMap<>();
        int w = 0, h = 0;
        for (Region r : sm.getRegions()) { int[] t = layout(r, layouts).get("__size"); w = Math.max(w, t[0] + 80); h += t[1] + 80; }
        Node frame = Behaviors.node(k, d, sm, 20, 20, Math.max(w, 300), h + 40);
        View rc = Behaviors.compartment(frame, "RegionCompartment");
        Map<Vertex, Node> views = new HashMap<>();
        int y = 0;
        for (Region r : sm.getRegions()) {
            int[] t = layouts.get(r).get("__size");
            Node rn = Behaviors.node(k, rc != null ? rc : frame, r, 0, y, Math.max(w, 300), t[1] + 80);
            drawRegion(k, rn, r, layouts, views);
            y += t[1] + 80;
        }
        int drawn = 0;
        for (Element e : sm.allOwnedElements()) if (e instanceof Transition t) {
            Node sv = views.get(t.getSource()), tv = views.get(t.getTarget());
            if (sv == null || tv == null) continue;
            Behaviors.edge(k, d, sv, tv, t);
            drawn++;
        }
        JsonObject o = new JsonObject();
        o.addProperty("vertices", views.size());
        o.addProperty("transitions", drawn);
        return o;
    }

    private static void drawRegion(Diagrams.Kind k, Node regionView, Region r, Map<Region, Map<String, int[]>> layouts, Map<Vertex, Node> views) {
        View sub = Behaviors.compartment(regionView, "SubvertexCompartment");
        if (sub == null) sub = regionView;
        Map<String, int[]> pos = layouts.get(r);
        for (Vertex v : r.getSubvertices()) {
            int[] p = pos.get(ElementJson.id(v)), s = pos.get("s:" + ElementJson.id(v));
            Node vn = Behaviors.node(k, sub, v, p[0] + 30, p[1] + 30, s[0], s[1]);
            views.put(v, vn);
            if (v instanceof State st && !st.getRegions().isEmpty()) {
                View src = Behaviors.compartment(vn, "RegionCompartment");
                int yy = 0;
                for (Region inner : st.getRegions()) {
                    int[] t = layouts.get(inner).get("__size");
                    Node irn = Behaviors.node(k, src != null ? src : vn, inner, 0, yy, s[0] - 4, t[1] + 60);
                    drawRegion(k, irn, inner, layouts, views);
                    yy += t[1] + 60;
                }
            }
        }
    }
}
