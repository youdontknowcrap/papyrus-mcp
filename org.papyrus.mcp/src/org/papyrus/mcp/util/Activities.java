package org.papyrus.mcp.util;

import com.google.gson.*;
import java.util.*;
import org.eclipse.gmf.runtime.notation.*;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Package;

/**
 * Activity diagrams: build an Activity from a JSON script and draw it (graphviz layout,
 * optional swimlanes for partitions).
 *
 * Script: {"name":..., "owner":..., "partitions":["Creator","Studio"],
 *          "nodes":[{"id":"start","kind":"initial"}, {"id":"a1","kind":"action","name":"Write logline","partition":"Creator"},
 *                   {"id":"d","kind":"decision"}, ...],
 *          "flows":[{"from":"start","to":"a1"}, {"from":"d","to":"a2","guard":"approved"}]}
 * Node kinds: initial, final, flowfinal, action, call (calls "behavior"), decision, merge, fork,
 * join, accept (waits for "event"), send (sends "signal"), buffer, datastore.
 * Flows are control flows. Object nodes (buffer/datastore) are marked "control type" so control
 * flows may touch them; pins and object flows between actions are not generated (limit).
 */
public final class Activities {
    private Activities() {}

    static final Map<String, org.eclipse.emf.ecore.EClass> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("initial", UMLPackage.Literals.INITIAL_NODE);
        KINDS.put("final", UMLPackage.Literals.ACTIVITY_FINAL_NODE);
        KINDS.put("flowfinal", UMLPackage.Literals.FLOW_FINAL_NODE);
        KINDS.put("action", UMLPackage.Literals.OPAQUE_ACTION);
        KINDS.put("call", UMLPackage.Literals.CALL_BEHAVIOR_ACTION);
        KINDS.put("decision", UMLPackage.Literals.DECISION_NODE);
        KINDS.put("merge", UMLPackage.Literals.MERGE_NODE);
        KINDS.put("fork", UMLPackage.Literals.FORK_NODE);
        KINDS.put("join", UMLPackage.Literals.JOIN_NODE);
        KINDS.put("accept", UMLPackage.Literals.ACCEPT_EVENT_ACTION);
        KINDS.put("send", UMLPackage.Literals.SEND_SIGNAL_ACTION);
        KINDS.put("buffer", UMLPackage.Literals.CENTRAL_BUFFER_NODE);
        KINDS.put("datastore", UMLPackage.Literals.DATA_STORE_NODE);
    }

    public static JsonObject build(WriteSupport.Ctx ctx, JsonObject s) throws Exception {
        ModelLocator.Active m = ctx.m;
        Activity a;
        boolean extend = s.has("activity");
        if (extend) {
            Element e = ElementJson.resolve(m, s.get("activity").getAsString());
            if (!(e instanceof Activity x)) throw new IllegalArgumentException("activity must name an Activity");
            a = x;
        } else {
            Element owner = s.has("owner") ? ElementJson.resolve(m, s.get("owner").getAsString()) : m.root();
            String name = s.get("name").getAsString();
            Behavior old = Behaviors.findBehavior(owner, name, UMLPackage.Literals.ACTIVITY);
            if (old != null) {
                if (!s.has("replace") || !s.get("replace").getAsBoolean())
                    throw new IllegalArgumentException("activity '" + name + "' exists (pass replace=true to rebuild, or activity=<id> to add to it)");
                removeDiagrams(m, old);
                org.eclipse.emf.ecore.util.EcoreUtil.delete(old, true);
            }
            a = Behaviors.createBehavior(owner, name, UMLPackage.Literals.ACTIVITY);
        }
        Map<String, ActivityPartition> parts = new HashMap<>();
        for (ActivityPartition p : a.getPartitions()) parts.put(p.getName(), p);
        if (s.has("partitions")) for (JsonElement p : s.getAsJsonArray("partitions"))
            parts.computeIfAbsent(p.getAsString(), n -> a.createPartition(n));
        Map<String, ActivityNode> ids = new HashMap<>();
        for (ActivityNode n : a.getOwnedNodes()) { ids.put(ElementJson.id(n), n); if (n.getName() != null) ids.putIfAbsent(n.getName(), n); }
        int created = 0, flows = 0;
        if (s.has("nodes")) for (JsonElement je : s.getAsJsonArray("nodes")) {
            JsonObject n = je.getAsJsonObject();
            String kind = n.has("kind") ? n.get("kind").getAsString().toLowerCase(Locale.ROOT) : "action";
            var ec = KINDS.get(kind);
            if (ec == null) throw new IllegalArgumentException("node kind must be one of " + KINDS.keySet() + ", not " + kind);
            String name = n.has("name") ? n.get("name").getAsString() : (kind.equals("action") ? n.get("id").getAsString() : null);
            ActivityNode node = a.createOwnedNode(name, ec);
            created++;
            if (node instanceof OpaqueAction oa && n.has("body")) { oa.getLanguages().add("English"); oa.getBodies().add(n.get("body").getAsString()); }
            if (node instanceof CallBehaviorAction cba && n.has("behavior")) {
                Element b = ElementJson.resolve(m, n.get("behavior").getAsString());
                if (b instanceof Behavior bb) cba.setBehavior(bb);
            }
            if (node instanceof AcceptEventAction aea) {
                Trigger t = aea.createTrigger(n.has("event") ? n.get("event").getAsString() : name);
                t.setEvent(Events.signalEvent(a, t.getName()));
            }
            if (node instanceof SendSignalAction ssa) {
                ssa.setSignal(Events.signal(a, n.has("signal") ? n.get("signal").getAsString() : name));
                ssa.createTarget("target", null);
            }
            if (node instanceof ObjectNode on) on.setIsControlType(true);
            if (n.has("partition")) {
                ActivityPartition p = parts.computeIfAbsent(n.get("partition").getAsString(), x -> a.createPartition(x));
                node.getInPartitions().add(p);
            }
            ids.put(n.has("id") ? n.get("id").getAsString() : name, node);
            if (name != null) ids.putIfAbsent(name, node);
        }
        if (s.has("flows")) for (JsonElement je : s.getAsJsonArray("flows")) {
            JsonObject f = je.getAsJsonObject();
            ActivityNode src = node(ids, f.get("from").getAsString()), tgt = node(ids, f.get("to").getAsString());
            ControlFlow cf = (ControlFlow) a.createEdge(f.has("name") ? f.get("name").getAsString() : null, UMLPackage.Literals.CONTROL_FLOW);
            cf.setSource(src); cf.setTarget(tgt);
            if (f.has("guard")) cf.setGuard(Behaviors.literal(f.get("guard").getAsString()));
            flows++;
        }
        ctx.touched(a);
        JsonObject out = new JsonObject();
        out.add("activity", ElementJson.ref(a));
        out.addProperty("nodesCreated", created);
        out.addProperty("flowsCreated", flows);
        if (!s.has("diagram") || s.get("diagram").getAsBoolean()) {
            Diagram d = Behaviors.diagramFor(m, Behaviors.ACTIVITY, a, s.has("diagram_name") ? s.get("diagram_name").getAsString() : null);
            JsonObject r = render(d, a, s.has("rankdir") ? s.get("rankdir").getAsString() : "TB");
            r.addProperty("id", d.eResource().getURIFragment(d));
            r.addProperty("name", d.getName());
            out.add("diagram", r);
        }
        ctx.summaries.add("built activity '" + a.getName() + "' (+" + created + " nodes, +" + flows + " flows)");
        return out;
    }

    static void removeDiagrams(ModelLocator.Active m, Behavior b) {
        for (var o : new ArrayList<>(Diagrams.notationResource(m).getContents()))
            if (o instanceof Diagram d && d.getElement() == b) org.eclipse.emf.ecore.util.EcoreUtil.delete(d, true);
    }

    private static ActivityNode node(Map<String, ActivityNode> ids, String k) {
        ActivityNode n = ids.get(k);
        if (n == null) throw new IllegalArgumentException("flow end '" + k + "' is not a node id or name (known: " + ids.keySet() + ")");
        return n;
    }

    static int[] size(ActivityNode n) {
        if (n instanceof InitialNode) return new int[]{20, 20};
        if (n instanceof FinalNode) return new int[]{24, 24};
        if (n instanceof DecisionNode || n instanceof MergeNode) return new int[]{40, 40};
        if (n instanceof ForkNode || n instanceof JoinNode) return new int[]{100, 8};
        return new int[]{Behaviors.textWidth(n.getName(), 120, 8, 40), 50};
    }

    /** Draw activity a on (empty) diagram d. Partitions become vertical swimlanes. */
    public static JsonObject render(Diagram d, Activity a, String rankdir) {
        Diagrams.Kind k = Behaviors.ACTIVITY;
        Map<String, int[]> sizes = new LinkedHashMap<>();
        Map<String, ActivityNode> byKey = new HashMap<>();
        for (ActivityNode n : a.getOwnedNodes()) {
            if (n instanceof Pin) continue;
            String key = ElementJson.id(n);
            sizes.put(key, size(n)); byKey.put(key, n);
        }
        List<String[]> edges = new ArrayList<>();
        for (ActivityEdge e : a.getEdges()) if (e.getSource() != null && e.getTarget() != null)
            edges.add(new String[]{ElementJson.id(e.getSource()), ElementJson.id(e.getTarget())});
        Map<String, int[]> pos = Behaviors.layout(sizes, edges, rankdir);
        int[] total = pos.get("__size");

        // swimlanes: one column per partition, nodes keep their graphviz row (y) and are centred in their lane
        List<ActivityPartition> lanes = new ArrayList<>(a.getPartitions());
        Map<ActivityPartition, int[]> laneX = new LinkedHashMap<>();
        int top = 40, lw = 0;
        if (!lanes.isEmpty()) {
            int x = 20;
            for (ActivityPartition p : lanes) {
                int w = Behaviors.textWidth(p.getName(), 200, 9, 60);
                for (ActivityNode n : p.getNodes()) w = Math.max(w, size(n)[0] + 80);
                laneX.put(p, new int[]{x, w});
                x += w;
            }
            lw = x;
        }
        int width = Math.max(total[0] + 80, lw + 40), height = total[1] + 140;
        Node frame = Behaviors.node(k, d, a, 20, 20, width, height);
        View comp = Behaviors.compartment(frame, "ActivityNodeCompartment");
        if (comp == null) comp = frame;
        Map<ActivityPartition, View> laneComp = new HashMap<>();
        for (var en : laneX.entrySet()) {
            try {
                Node ln = Behaviors.node(k, comp, en.getKey(), en.getValue()[0], 10, en.getValue()[1], total[1] + 80);
                View lc = Behaviors.compartment(ln, "ActivityNodeCompartment");
                laneComp.put(en.getKey(), lc != null ? lc : ln);
            } catch (Exception ignore) {}
        }
        Map<ActivityNode, Node> views = new HashMap<>();
        for (var en : byKey.entrySet()) {
            ActivityNode n = en.getValue();
            int[] p = pos.get(en.getKey()), s = sizes.get(en.getKey());
            ActivityPartition lane = n.getInPartitions().isEmpty() ? null : n.getInPartitions().get(0);
            if (lane != null && laneComp.containsKey(lane)) {
                int[] lx = laneX.get(lane);
                try { views.put(n, Behaviors.node(k, laneComp.get(lane), n, lx[1] / 2 - s[0] / 2, p[1] + 20, s[0], s[1])); continue; }
                catch (Exception ignore) {}          // fall back to the activity compartment
            }
            int x = lane != null && laneX.containsKey(lane) ? laneX.get(lane)[0] + laneX.get(lane)[1] / 2 - s[0] / 2 : p[0] + 30;
            views.put(n, Behaviors.node(k, comp, n, x, p[1] + top, s[0], s[1]));
        }
        int drawn = 0;
        for (ActivityEdge e : a.getEdges()) {
            Node sv = views.get(e.getSource()), tv = views.get(e.getTarget());
            if (sv == null || tv == null) continue;
            Behaviors.edge(k, d, sv, tv, e);
            drawn++;
        }
        JsonObject o = new JsonObject();
        o.addProperty("nodes", views.size());
        o.addProperty("flows", drawn);
        o.addProperty("swimlanes", laneComp.size());
        return o;
    }
}
