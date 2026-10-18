package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gmf.runtime.diagram.core.preferences.PreferencesHint;
import org.eclipse.gmf.runtime.diagram.core.services.ViewService;
import org.eclipse.gmf.runtime.diagram.core.util.ViewUtil;
import org.eclipse.gmf.runtime.notation.*;
import org.eclipse.gmf.runtime.notation.datatype.RelativeBendpoint;
import org.eclipse.papyrus.infra.core.sashwindows.di.service.IPageManager;
import org.eclipse.uml2.uml.Element;
import org.eclipse.uml2.uml.Generalization;
import org.eclipse.uml2.uml.InterfaceRealization;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.*;
import org.papyrus.mcp.util.WriteSupport.Ctx;

/*
 * Phase 5: GMF (classic Papyrus) diagram tools. All diagram changes are notation-model changes
 * inside the same McpCommand as model edits, so they are undoable and batchable too.
 */

/** create_diagram: new GMF diagram (class, usecase, component) owned by a package/classifier. */
class CreateDiagramTool extends WriteTool {
    public String name() { return "create_diagram"; }
    public String description() {
        return "Create a classic (GMF) Papyrus diagram: kind = class | usecase | component (empty canvas; then add_to_diagram), or sequence | activity | statemachine "
             + "(drawn from the behavior given as owner; calling again with the same name redraws it). Optionally open it in the editor. Undoable.";
    }
    protected JsonObject schema() {
        return schema(new String[][]{{"kind", "string", "class | usecase | component | sequence | activity | statemachine"}, {"owner", "string", "Owner/context element id/qn (default model root)."},
            {"name", "string", "Diagram name."}, {"open", "boolean", "Open it as an editor page (default true)."}}, "kind", "name");
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "create " + str(a, "kind") + " diagram '" + str(a, "name") + "'"; }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        String kindKey = need(a, "kind");
        // Behavior diagrams are drawn from their behavior (owner = the Interaction / Activity / StateMachine).
        for (Diagrams.Kind bk : Behaviors.ALL) if (bk.key().equals(kindKey)) return behaviorDiagram(ctx, a, bk);
        Diagrams.Kind k = Diagrams.kind(kindKey);
        Element owner = ElementJson.resolve(ctx.m, str(a, "owner"));
        Diagram d = ViewService.createDiagram(owner, k.type(), Diagrams.prefHint(k));
        if (d == null) throw new IllegalStateException("Papyrus refused to create a " + k.key() + " diagram on " + owner.eClass().getName());
        d.setName(need(a, "name"));
        Diagrams.addPapyrusStyles(d, k, owner);
        Diagrams.notationResource(ctx.m).getContents().add(d);
        ctx.touched(owner);
        ctx.summaries.add("created " + k.key() + " diagram '" + d.getName() + "' on " + EditUtil.nm(owner));
        JsonObject o = new JsonObject();
        o.addProperty("id", d.eResource().getURIFragment(d));
        o.addProperty("name", d.getName());
        o.addProperty("type", d.getType());
        o.addProperty("kind", k.kindId());
        return o;
    }
    private JsonObject behaviorDiagram(Ctx ctx, JsonObject a, Diagrams.Kind bk) {
        Element owner = ElementJson.resolve(ctx.m, str(a, "owner"));
        org.eclipse.emf.ecore.EClass want = bk == Behaviors.SEQUENCE ? org.eclipse.uml2.uml.UMLPackage.Literals.INTERACTION
            : bk == Behaviors.ACTIVITY ? org.eclipse.uml2.uml.UMLPackage.Literals.ACTIVITY : org.eclipse.uml2.uml.UMLPackage.Literals.STATE_MACHINE;
        org.eclipse.uml2.uml.Behavior beh = want.isInstance(owner) ? (org.eclipse.uml2.uml.Behavior) owner : null;
        if (beh == null && owner instanceof org.eclipse.uml2.uml.BehavioredClassifier bc)
            for (org.eclipse.uml2.uml.Behavior b : bc.getOwnedBehaviors()) if (want.isInstance(b)) { beh = b; break; }
        if (beh == null) throw new IllegalArgumentException("a " + bk.key() + " diagram is drawn from a " + want.getName()
            + ": pass owner=<" + want.getName() + " id> (create one with " + (bk == Behaviors.SEQUENCE ? "build_sequence or create_interaction" : bk == Behaviors.ACTIVITY ? "build_activity" : "build_state_machine") + ")");
        Diagram d = Behaviors.diagramFor(ctx.m, bk, beh, str(a, "name"));
        JsonObject r = bk == Behaviors.SEQUENCE ? Sequences.render(d, (org.eclipse.uml2.uml.Interaction) beh, null)
            : bk == Behaviors.ACTIVITY ? Activities.render(d, (org.eclipse.uml2.uml.Activity) beh, "TB")
            : StateMachines.render(d, (org.eclipse.uml2.uml.StateMachine) beh);
        r.addProperty("id", d.eResource().getURIFragment(d));
        r.addProperty("name", d.getName());
        r.addProperty("type", d.getType());
        ctx.touched(beh);
        ctx.summaries.add("drew " + bk.key() + " diagram '" + d.getName() + "'");
        return r;
    }

    @Override public JsonObject execute(JsonObject a) throws Exception {
        JsonObject r = super.execute(a);
        if (!a.has("open") || a.get("open").getAsBoolean()) {
            // Opening a page is editor state (sash model), not a model change: done after the command.
            try {
                UiSync.call(() -> {
                    ModelLocator.Active m = ModelLocator.require(str(a, "model"));
                    Diagram d = Diagrams.find(m, r.get("id").getAsString());
                    m.services().getService(IPageManager.class).openPage(d);
                    return null;
                });
                r.addProperty("opened", true);
            } catch (Exception e) { r.addProperty("opened", false); r.addProperty("openError", e.getMessage()); }
        }
        return r;
    }
}

/** add_to_diagram: show elements (and, by default, the relationships among shown elements). */
class AddToDiagramTool extends WriteTool {
    public String name() { return "add_to_diagram"; }
    public String description() {
        return "Show model elements on a GMF diagram (nodes with full compartments, positioned by x/y or auto-placed) and, "
             + "unless withRelationships=false, draw every relationship between shown elements. Undoable.";
    }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{{"diagram", "string", "Diagram id or name."},
            {"elements", "array", "[\"id\", ...] or [{id, x?, y?, w?, h?}, ...]"},
            {"withRelationships", "boolean", "Also draw relationships among all shown elements (default true)."},
            {"relationshipKinds", "string", "Optional comma-separated metaclass names, e.g. Dependency: draw only these relationship kinds (a robustness diagram wants its Dependencies, not the Associations between entities)."}}, "diagram");
        s.getAsJsonObject("properties").getAsJsonObject("elements").add("items", new JsonObject());
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) {
        int n = a.has("elements") ? a.getAsJsonArray("elements").size() : 0;
        String dn; try { dn = Diagrams.find(m, need(a, "diagram")).getName(); } catch (Exception e) { dn = str(a, "diagram"); }
        return "add " + n + " element(s) to diagram '" + dn + "'";
    }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Diagram d = Diagrams.find(ctx.m, need(a, "diagram"));
        Diagrams.Kind k = Diagrams.kindOf(d);
        PreferencesHint ph = Diagrams.prefHint(k);
        JsonArray added = new JsonArray(), skipped = new JsonArray(), edges = new JsonArray();
        int maxY = 0;
        for (Object o : d.getChildren())
            if (o instanceof Node n && n.getLayoutConstraint() instanceof Bounds b) maxY = Math.max(maxY, b.getY() + Math.max(b.getHeight(), 100));
        int auto = 0;
        if (a.has("elements")) for (JsonElement je : a.getAsJsonArray("elements")) {
            JsonObject spec = je.isJsonObject() ? je.getAsJsonObject() : new JsonObject();
            String id = je.isJsonObject() ? spec.get("id").getAsString() : je.getAsString();
            Element e = ElementJson.resolve(ctx.m, id);
            if (Diagrams.nodeFor(d, e) != null) { skipped.add(id + ": already shown"); continue; }
            String hint = Diagrams.nodeHint(k, d, e);
            Node n = ViewService.createNode(d, e, hint, ph);
            if (n == null) { skipped.add(id + ": a " + e.eClass().getName() + " cannot be shown on a " + k.key() + " diagram"); continue; }
            Bounds b = n.getLayoutConstraint() instanceof Bounds bb ? bb : NotationFactory.eINSTANCE.createBounds();
            n.setLayoutConstraint(b);
            boolean hasXY = spec.has("x") && spec.has("y");
            b.setX(hasXY ? spec.get("x").getAsInt() : 40 + (auto % 4) * 280);
            b.setY(hasXY ? spec.get("y").getAsInt() : maxY + 40 + (auto / 4) * 220);
            if (!hasXY) auto++;
            if (spec.has("w")) b.setWidth(spec.get("w").getAsInt());
            if (spec.has("h")) b.setHeight(spec.get("h").getAsInt());
            JsonObject r = ElementJson.ref(e);
            r.addProperty("viewType", n.getType());
            added.add(r);
            ctx.touched(e);
        }
        // relationshipKinds (optional, e.g. "Dependency"): draw only those relationship metaclasses.
        // A robustness diagram wants its Dependencies but not the Associations between entities.
        Set<String> onlyKinds = null;
        if (a.has("relationshipKinds") && !a.get("relationshipKinds").isJsonNull()) {
            onlyKinds = new HashSet<>();
            for (String kindName : a.get("relationshipKinds").getAsString().split(",")) if (!kindName.isBlank()) onlyKinds.add(kindName.trim());
        }
        if (!a.has("withRelationships") || a.get("withRelationships").getAsBoolean()) {
            Map<EObject, Node> shown = new LinkedHashMap<>();
            for (Object o : d.getChildren()) if (o instanceof Node n && n.getElement() != null) shown.put(n.getElement(), n);
            Set<EObject> rels = new LinkedHashSet<>();
            for (EObject e : shown.keySet()) if (e instanceof Element el) rels.addAll(Diagrams.relationshipsOf(el));
            for (EObject r : rels) {
                if (Diagrams.hasEdge(d, r)) continue;
                if (onlyKinds != null && !onlyKinds.contains(r.eClass().getName())) continue;
                EObject[] ends = Diagrams.ends(r);
                if (ends == null || !shown.containsKey(ends[0]) || !shown.containsKey(ends[1])) continue;
                Edge ed = Diagrams.createEdge(d, shown.get(ends[0]), shown.get(ends[1]), r, Diagrams.edgeHint(k, r), ph);
                if (ed == null) { skipped.add(ElementJson.id(r) + ": cannot draw " + r.eClass().getName() + " here"); continue; }
                JsonObject eo = ElementJson.ref(r);
                eo.addProperty("viewType", ed.getType());
                edges.add(eo);
            }
        }
        ctx.summaries.add("added " + added.size() + " node(s), " + edges.size() + " edge(s) to diagram '" + d.getName() + "'");
        JsonObject o = new JsonObject();
        o.addProperty("diagram", d.getName());
        o.add("nodes", added);
        o.add("edges", edges);
        o.add("skipped", skipped);
        return o;
    }
}

class RemoveFromDiagramTool extends WriteTool {
    public String name() { return "remove_from_diagram"; }
    public String description() { return "Remove the views of elements/relationships from a GMF diagram (the model is not changed). Undoable."; }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{{"diagram", "string", "Diagram id or name."}, {"ids", "array", "Element/relationship ids or qns."}}, "diagram", "ids");
        s.getAsJsonObject("properties").getAsJsonObject("ids").add("items", JsonParser.parseString("{\"type\":\"string\"}"));
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "remove " + a.getAsJsonArray("ids").size() + " view(s) from diagram"; }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Diagram d = Diagrams.find(ctx.m, need(a, "diagram"));
        Set<EObject> targets = new HashSet<>();
        for (JsonElement i : a.getAsJsonArray("ids")) targets.add(ElementJson.resolve(ctx.m, i.getAsString()));
        List<View> doomed = new ArrayList<>();
        for (Object o : d.getChildren()) if (targets.contains(((View) o).getElement())) doomed.add((View) o);
        for (Object o : d.getEdges()) if (targets.contains(((View) o).getElement())) doomed.add((View) o);
        for (View v : doomed) if (v.eContainer() != null) ViewUtil.destroy(v); // also removes attached edges
        ctx.summaries.add("removed " + doomed.size() + " view(s) from diagram '" + d.getName() + "'");
        JsonObject o = new JsonObject();
        o.addProperty("removedViews", doomed.size());
        return o;
    }
}

/**
 * layout_diagram: graphviz "dot" (same approach as the papyrus-out generator) or a simple grid.
 * Sets explicit x/y/width/height on top-level nodes and straightens edges.
 */
class LayoutDiagramTool extends WriteTool {
    public String name() { return "layout_diagram"; }
    public String description() {
        return "Auto-layout a GMF diagram. algorithm = graphviz (default; needs 'dot' installed, falls back to grid) | grid. "
             + "rankdir = LR | TB (default TB; generalizations point up). Undoable.";
    }
    protected JsonObject schema() {
        return schema(new String[][]{{"diagram", "string", "Diagram id or name."}, {"algorithm", "string", "graphviz | grid"},
            {"rankdir", "string", "TB | LR"}, {"nodesep", "number", "Graphviz nodesep inches (default 0.6)."}, {"ranksep", "number", "Graphviz ranksep inches (default 0.9)."}}, "diagram");
    }
    public String label(ModelLocator.Active m, JsonObject a) {
        String dn; try { dn = Diagrams.find(m, need(a, "diagram")).getName(); } catch (Exception e) { dn = str(a, "diagram"); }
        return "layout diagram '" + dn + "'";
    }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        Diagram d = Diagrams.find(ctx.m, need(a, "diagram"));
        List<Node> nodes = new ArrayList<>();
        for (Object o : d.getChildren()) if (o instanceof Node n && n.getLayoutConstraint() instanceof Bounds) nodes.add(n);
        Map<Node, int[]> size = new LinkedHashMap<>();
        for (Node n : nodes) {
            Bounds b = (Bounds) n.getLayoutConstraint();
            int[] est = Diagrams.estimateSize(n);
            size.put(n, new int[]{b.getWidth() > 0 ? b.getWidth() : est[0], b.getHeight() > 0 ? b.getHeight() : est[1]});
        }
        String algo = Objects.requireNonNullElse(str(a, "algorithm"), "graphviz");
        Map<Node, int[]> pos = null;
        String used = algo;
        if (algo.equals("graphviz")) {
            try { pos = dot(d, nodes, size, a); } catch (Exception e) { used = "grid (graphviz failed: " + e.getMessage() + ")"; }
        }
        if (pos == null) pos = grid(nodes, size);
        for (Node n : nodes) {
            Bounds b = (Bounds) n.getLayoutConstraint();
            int[] p = pos.get(n), s = size.get(n);
            b.setX(p[0]); b.setY(p[1]); b.setWidth(s[0]); b.setHeight(s[1]);
        }
        for (Object o : d.getEdges()) {
            Edge e = (Edge) o;
            RelativeBendpoints rb = e.getBendpoints() instanceof RelativeBendpoints x ? x : NotationFactory.eINSTANCE.createRelativeBendpoints();
            rb.setPoints(List.of(new RelativeBendpoint(0, 0, 0, 0), new RelativeBendpoint(0, 0, 0, 0)));
            e.setBendpoints(rb);
        }
        ctx.summaries.add("laid out diagram '" + d.getName() + "' (" + nodes.size() + " nodes, " + used + ")");
        JsonObject o = new JsonObject();
        o.addProperty("diagram", d.getName());
        o.addProperty("nodes", nodes.size());
        o.addProperty("algorithm", used);
        return o;
    }

    private static Map<Node, int[]> grid(List<Node> nodes, Map<Node, int[]> size) {
        Map<Node, int[]> pos = new HashMap<>();
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(nodes.size()))), x = 40, y = 40, rowH = 0, c = 0;
        for (Node n : nodes) {
            pos.put(n, new int[]{x, y});
            int[] s = size.get(n);
            rowH = Math.max(rowH, s[1]);
            x += s[0] + 60;
            if (++c % cols == 0) { x = 40; y += rowH + 60; rowH = 0; }
        }
        return pos;
    }

    private static Map<Node, int[]> dot(Diagram d, List<Node> nodes, Map<Node, int[]> size, JsonObject a) throws Exception {
        String rankdir = Objects.requireNonNullElse(str(a, "rankdir"), "TB");
        double ns = a.has("nodesep") ? a.get("nodesep").getAsDouble() : 0.6, rs = a.has("ranksep") ? a.get("ranksep").getAsDouble() : 0.9;
        Map<Node, String> ids = new HashMap<>();
        StringBuilder g = new StringBuilder("digraph G { rankdir=" + rankdir + "; nodesep=" + ns + "; ranksep=" + rs + "; node[shape=box,fixedsize=true];\n");
        int i = 0;
        for (Node n : nodes) {
            String id = "n" + (i++);
            ids.put(n, id);
            int[] s = size.get(n);
            g.append(String.format(Locale.ROOT, "%s [width=%.3f,height=%.3f];%n", id, s[0] / 72.0, s[1] / 72.0));
        }
        for (Object o : d.getEdges()) {
            Edge e = (Edge) o;
            String s = ids.get(e.getSource()), t = ids.get(e.getTarget());
            if (s == null || t == null) continue;
            // Parents above children: draw generalization/realization from general to specific.
            boolean up = e.getElement() instanceof Generalization || e.getElement() instanceof InterfaceRealization;
            g.append(up ? t + " -> " + s : s + " -> " + t).append(";\n");
        }
        g.append("}");
        Process p = new ProcessBuilder("dot", "-Tplain").redirectErrorStream(true).start();
        try (OutputStream os = p.getOutputStream()) { os.write(g.toString().getBytes()); }
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException("dot failed: " + out);
        String[] lines = out.split("\n");
        double H = Double.parseDouble(lines[0].split(" ")[3]);
        Map<String, Node> byId = new HashMap<>();
        ids.forEach((n, id) -> byId.put(id, n));
        Map<Node, int[]> pos = new HashMap<>();
        for (String l : lines) {
            String[] f = l.split(" ");
            if (!f[0].equals("node")) continue;
            Node n = byId.get(f[1]);
            int[] s = size.get(n);
            double x = Double.parseDouble(f[2]) * 72, y = (H - Double.parseDouble(f[3])) * 72;
            pos.put(n, new int[]{(int) (x - s[0] / 2.0) + 30, (int) (y - s[1] / 2.0) + 30});
        }
        return pos;
    }
}

/** export_diagram_image: offscreen render (GMF CopyToImageUtil), returned as MCP image content. */
class ExportDiagramImageTool extends ReadTool {
    public String name() { return "export_diagram_image"; }
    public String description() { return "Render a GMF diagram to PNG (returned as an image so you can look at it) or SVG (returned as text). Read-only."; }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "diagram", "string", "Diagram id or name.");
        prop(s, "format", "string", "png (default) | svg | jpg");
        require(s, "diagram");
        return s;
    }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        try {
            Diagram d = Diagrams.find(m, str(a, "diagram"));
            String fmt = Objects.requireNonNullElse(str(a, "format"), "png").toLowerCase();
            var iff = switch (fmt) {
                case "svg" -> org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat.SVG;
                case "jpg", "jpeg" -> org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat.JPEG;
                default -> org.eclipse.gmf.runtime.diagram.ui.image.ImageFileFormat.PNG;
            };
            Path tmp = Files.createTempFile("papyrus-mcp-", "." + fmt);
            try {
                PreferencesHint ph;
                Diagrams.Kind bk = Behaviors.kindOf(d);
                try { ph = Diagrams.prefHint(bk != null ? bk : Diagrams.kindOf(d)); } catch (Exception e) { ph = PreferencesHint.USE_DEFAULTS; }
                new org.eclipse.gmf.runtime.diagram.ui.render.util.CopyToImageUtil().copyToImage(
                        d, new org.eclipse.core.runtime.Path(tmp.toString()), iff, new org.eclipse.core.runtime.NullProgressMonitor(), ph);
                byte[] bytes = Files.readAllBytes(tmp);
                if (bytes.length > 8 * 1024 * 1024) throw new IllegalStateException("image too large (" + bytes.length + " bytes)");
                JsonObject o = new JsonObject();
                o.addProperty("diagram", d.getName());
                o.addProperty("format", fmt);
                o.addProperty("bytes", bytes.length);
                if (fmt.equals("svg")) o.addProperty("svg", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                else {
                    JsonObject img = new JsonObject();
                    img.addProperty("data", Base64.getEncoder().encodeToString(bytes));
                    img.addProperty("mimeType", fmt.equals("png") ? "image/png" : "image/jpeg");
                    o.add("_image", img); // ToolRegistry turns this into an MCP image content item
                }
                return o;
            } finally { Files.deleteIfExists(tmp); }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
    }
}
