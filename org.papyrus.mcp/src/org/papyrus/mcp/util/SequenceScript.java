package org.papyrus.mcp.util;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Package;

/**
 * build_sequence: turns a small JSON script into an Interaction (model) and its sequence
 * diagram, inside the caller's single command (so it is one undo step).
 *
 * Script, in plain words:
 *   lifelines  names (or {name, type}); any name used in a step that is not listed yet becomes a
 *              lifeline automatically, typed by the class/actor of the same name.
 *   robustness a robustness package (or its use case): names are looked up there first, so
 *              "Production Board" means that robustness diagram's boundary (ICONIX).
 *   steps      the story in time order. A step is one of
 *     {"from":"A","to":"B","msg":"draft(brief)","returns":"task","steps":[...nested calls...]}
 *     {"fragment":"alt","operands":[{"guard":"ok","steps":[...]},{"guard":"else","steps":[...]}]}
 *     {"fragment":"loop","guard":"each shot","steps":[...]}            (one-operand shorthand)
 *     {"ref":"Other Interaction"}  {"invariant":"task.ready","on":"B"}  {"note":"text","on":"B"}
 *   A sync call opens an execution bar on the receiver; nested "steps" happen inside it; the bar
 *   closes with a reply when "returns" is given. Operations are found on the receiver's class by
 *   name, or created there (ICONIX allocates behavior this way); turn off with create_operations=false.
 */
public final class SequenceScript {
    private final ModelLocator.Active m;
    private final Interaction i;
    private final List<Element> scope = new ArrayList<>();       // robustness package + linked elements
    private final boolean createOps;
    final List<String> createdOps = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    private final Map<Lifeline, Deque<BehaviorExecutionSpecification>> open = new HashMap<>();
    private final Map<BehaviorExecutionSpecification, Message> callOf = new HashMap<>();
    private int messages = 0;

    private SequenceScript(ModelLocator.Active m, Interaction i, boolean createOps) { this.m = m; this.i = i; this.createOps = createOps; }

    /** Run a whole script. Returns the result JSON (interaction, counts, created operations, diagram). */
    public static JsonObject build(WriteSupport.Ctx ctx, JsonObject s) throws Exception {
        ModelLocator.Active m = ctx.m;
        String name = req(s, "name");
        Element owner = s.has("owner") ? ElementJson.resolve(m, s.get("owner").getAsString()) : null;
        Element robust = s.has("robustness") ? robustnessPackage(m, ElementJson.resolve(m, s.get("robustness").getAsString())) : null;
        if (owner == null) owner = robust != null ? useCaseOf(robust) : m.root();
        if (owner == null) owner = m.root();

        // replace = rebuild an existing interaction of the same name (its diagram is redrawn under the same name).
        List<String> diagramNames = new ArrayList<>();
        Behavior old = Behaviors.findBehavior(owner, name, UMLPackage.Literals.INTERACTION);
        if (old != null) {
            if (!s.has("replace") || !s.get("replace").getAsBoolean())
                throw new IllegalArgumentException("interaction '" + name + "' already exists on " + (owner instanceof NamedElement ne0 && ne0.getName() != null ? ne0.getName() : ElementJson.id(owner)) + " (pass replace=true to rebuild it)");
            for (EObject o : new ArrayList<>(Diagrams.notationResource(m).getContents()))
                if (o instanceof Diagram d && d.getElement() == old) { diagramNames.add(d.getName()); EcoreUtil.delete(d, true); }
            EcoreUtil.delete(old, true);
        }
        Interaction i = Sequences.createInteraction(owner, name);
        SequenceScript sc = new SequenceScript(m, i, !s.has("create_operations") || s.get("create_operations").getAsBoolean());
        if (robust != null) sc.scope(robust);

        String narrative = null;
        if (s.has("narrative")) {
            JsonElement n = s.get("narrative");
            if (n.isJsonPrimitive() && n.getAsJsonPrimitive().isBoolean()) {
                if (n.getAsBoolean() && owner instanceof UseCase uc && !uc.getOwnedComments().isEmpty()) narrative = uc.getOwnedComments().get(0).getBody();
            } else narrative = n.getAsString();
            if (narrative != null) {
                // Papyrus notes do not word-wrap, so break the margin copy into short lines.
                narrative = wrap(narrative, 40);
                Comment c = i.createOwnedComment();
                c.setBody(narrative);
                c.getAnnotatedElements().add(i);
            }
        }
        if (s.has("lifelines")) for (JsonElement le : s.getAsJsonArray("lifelines")) {
            if (le.isJsonPrimitive()) sc.lifeline(le.getAsString(), null);
            else { JsonObject lo = le.getAsJsonObject(); sc.lifeline(req(lo, "name"), lo.has("type") ? lo.get("type").getAsString() : null); }
        }
        if (s.has("steps")) sc.steps(i, s.getAsJsonArray("steps"));

        ctx.touched(i);
        JsonObject out = new JsonObject();
        out.add("interaction", ElementJson.ref(i));
        out.add("owner", ElementJson.ref(owner));
        out.addProperty("lifelines", i.getLifelines().size());
        out.addProperty("messages", sc.messages);
        JsonArray ops = new JsonArray(); sc.createdOps.forEach(ops::add);
        out.add("createdOperations", ops);
        if (!sc.warnings.isEmpty()) { JsonArray w = new JsonArray(); sc.warnings.forEach(w::add); out.add("warnings", w); }
        if (!s.has("diagram") || s.get("diagram").getAsBoolean()) {
            String dn = s.has("diagram_name") ? s.get("diagram_name").getAsString() : (diagramNames.isEmpty() ? name : diagramNames.get(0));
            Diagram d = Behaviors.diagramFor(m, Behaviors.SEQUENCE, i, dn);
            JsonObject r = Sequences.render(d, i, narrative);
            r.addProperty("id", d.eResource().getURIFragment(d));
            r.addProperty("name", d.getName());
            out.add("diagram", r);
        }
        ctx.summaries.add("built sequence '" + name + "' (" + i.getLifelines().size() + " lifelines, " + sc.messages + " messages, "
            + sc.createdOps.size() + " new operations)");
        return out;
    }

    // ------------------------------------------------------------------ name resolution

    /** A robustness package: the element itself if a package, or the package with a Dependency to this use case. */
    public static Package robustnessPackage(ModelLocator.Active m, Element e) {
        if (e instanceof Package p) return p;
        if (e instanceof UseCase uc) {
            for (Element x : m.root().allOwnedElements())
                if (x instanceof Package p) {
                    for (Dependency d : p.getClientDependencies()) if (d.getSuppliers().contains(uc)) return p;
                    if (("Robustness - " + uc.getName()).equals(p.getName())) return p;
                }
            throw new IllegalArgumentException("use case " + uc.getName() + " has no robustness package (run iconix_robustness first)");
        }
        throw new IllegalArgumentException("robustness must name a package or a use case");
    }

    static UseCase useCaseOf(Element robustPkg) {
        if (robustPkg instanceof Package p)
            for (Dependency d : p.getClientDependencies()) for (NamedElement n : d.getSuppliers()) if (n instanceof UseCase uc) return uc;
        return null;
    }

    private void scope(Element pkg) {
        scope.add(pkg);
        // actors and entities linked to the robustness objects are part of the vocabulary too
        for (Element e : ((Package) pkg).allOwnedElements()) {
            scope.add(e);
            if (e instanceof NamedElement ne) {
                for (Dependency d : ne.getClientDependencies()) scope.addAll(d.getSuppliers());
                for (DirectedRelationship r : ne.getTargetDirectedRelationships()) scope.addAll(r.getSources());
            }
        }
    }

    /** Classifier for a lifeline name: robustness scope first, then the unique classifier with that name. */
    private Type resolveType(String name) {
        if (name.contains("::") || name.startsWith("_")) {
            Element e = ElementJson.resolve(m, name);
            if (e instanceof Type t) return t;
        }
        for (Element e : scope) if (e instanceof Classifier c && !(e instanceof Behavior) && name.equals(c.getName())) return c;
        List<Classifier> hits = new ArrayList<>();
        for (Element e : m.root().allOwnedElements())
            if (e instanceof Classifier c && !(e instanceof Behavior) && !(e instanceof Association) && name.equals(c.getName())) hits.add(c);
        if (hits.size() == 1) return hits.get(0);
        if (hits.isEmpty()) { warnings.add("no class or actor named '" + name + "': lifeline has no type"); return null; }
        List<String> qn = new ArrayList<>();
        for (Classifier c : hits) qn.add(c.getQualifiedName());
        throw new IllegalArgumentException("'" + name + "' is ambiguous: " + qn + ". Pass robustness=<package> or a qualified name as the lifeline type.");
    }

    private Lifeline lifeline(String name, String typeName) {
        for (Lifeline l : i.getLifelines()) if (name.equals(l.getName())) return l;
        Type t = resolveType(typeName != null ? typeName : name);
        String nm = name.contains("::") ? name.substring(name.lastIndexOf("::") + 2) : name;
        for (Lifeline l : i.getLifelines()) if (nm.equals(l.getName())) return l;
        return Sequences.addLifeline(i, nm, t, null);
    }

    // ------------------------------------------------------------------ steps

    private void steps(Element container, JsonArray steps) {
        List<BehaviorExecutionSpecification> openedHere = new ArrayList<>();
        for (JsonElement je : steps) {
            JsonObject st = je.getAsJsonObject();
            if (st.has("fragment")) fragment(container, st);
            else if (st.has("ref")) {
                Element r = ElementJson.resolve(m, st.get("ref").getAsString());
                if (!(r instanceof Interaction ri)) throw new IllegalArgumentException("ref must name an Interaction");
                Sequences.addInteractionUse(container, -1, ri, covers(st));
            } else if (st.has("invariant")) Sequences.addStateInvariant(container, -1, lifeline(req(st, "on"), null), st.get("invariant").getAsString());
            else if (st.has("note")) {
                Comment c = i.createOwnedComment();
                c.setBody(st.get("note").getAsString());
                if (st.has("on")) c.getAnnotatedElements().add(lifeline(st.get("on").getAsString(), null));
            } else message(container, st, openedHere);
        }
        for (BehaviorExecutionSpecification es : openedHere) if (es.getFinish() == null) Sequences.finishHere(container, es);
    }

    private Collection<Lifeline> covers(JsonObject st) {
        List<Lifeline> out = new ArrayList<>();
        if (st.has("covers")) for (JsonElement c : st.getAsJsonArray("covers")) out.add(lifeline(c.getAsString(), null));
        else out.addAll(i.getLifelines());
        return out;
    }

    private void fragment(Element container, JsonObject st) {
        JsonArray operands;
        if (st.has("operands")) operands = st.getAsJsonArray("operands");
        else {                                   // shorthand: {"fragment":"loop","guard":"...","steps":[...]}
            JsonObject one = new JsonObject();
            if (st.has("guard")) one.add("guard", st.get("guard"));
            one.add("steps", st.has("steps") ? st.get("steps") : new JsonArray());
            operands = new JsonArray(); operands.add(one);
        }
        List<String> guards = new ArrayList<>();
        for (JsonElement o : operands) guards.add(o.getAsJsonObject().has("guard") ? o.getAsJsonObject().get("guard").getAsString() : null);
        CombinedFragment cf = Sequences.addCombinedFragment(container, -1, req(st, "fragment"), List.of(), guards);
        for (int n = 0; n < operands.size(); n++) {
            JsonObject o = operands.get(n).getAsJsonObject();
            if (o.has("steps")) steps(cf.getOperands().get(n), o.getAsJsonArray("steps"));
        }
        // covered lifelines: as given, else every lifeline touched inside the box
        Set<Lifeline> cov = new LinkedHashSet<>();
        if (st.has("covers")) cov.addAll(covers(st));
        else for (InteractionOperand op : cf.getOperands()) collect(op, cov);
        if (cov.isEmpty()) cov.addAll(i.getLifelines());
        cf.getCovereds().addAll(cov);
        for (InteractionOperand op : cf.getOperands()) op.getCovereds().addAll(cov);
    }

    private static void collect(InteractionOperand op, Set<Lifeline> into) {
        for (InteractionFragment f : op.getFragments()) {
            into.addAll(f.getCovereds());
            if (f instanceof CombinedFragment c) for (InteractionOperand o : c.getOperands()) collect(o, into);
        }
    }

    private void message(Element container, JsonObject st, List<BehaviorExecutionSpecification> openedHere) {
        String fromS = req(st, "from"), toS = req(st, "to");
        String text = st.has("msg") ? st.get("msg").getAsString() : st.has("call") ? st.get("call").getAsString() : st.has("text") ? st.get("text").getAsString() : "";
        MessageSort ms = Sequences.sort(st.has("sort") ? st.get("sort").getAsString() : null);
        Lifeline from = fromS.startsWith("gate:") ? null : lifeline(fromS, null);
        Lifeline to = toS.startsWith("gate:") ? null : lifeline(toS, null);
        Gate fg = from == null ? Sequences.gate(i, fromS.substring(5), true) : null;
        Gate tg = to == null ? Sequences.gate(i, toS.substring(5), true) : null;
        String returns = st.has("returns") ? st.get("returns").getAsString() : null;

        if (ms == MessageSort.REPLY_LITERAL) { reply(container, from, fg, to, tg, text, null); return; }

        List<String> call = Sequences.parseCall(text);
        String name = call.get(0);
        List<String> args = new ArrayList<>(call.subList(1, call.size()));
        Operation op = null;
        boolean wantsOp = ms == MessageSort.SYNCH_CALL_LITERAL || ms == MessageSort.ASYNCH_CALL_LITERAL;
        if (wantsOp && to != null && !name.isEmpty()) {
            boolean create = createOps && (!st.has("create_operation") || st.get("create_operation").getAsBoolean());
            if (st.has("operation")) {
                Element e = ElementJson.resolve(m, st.get("operation").getAsString());
                if (e instanceof Operation o) op = o;
            } else op = Sequences.operation(to, name, args, returns, create, createdOps);
            if (op != null) args = matchArguments(op, args);
        }
        Message msg = Sequences.addMessage(container, -1, from, fg, to, tg, ms, name, op, args);
        messages++;
        if (st.has("note")) { Comment c = i.createOwnedComment(); c.setBody(st.get("note").getAsString()); c.getAnnotatedElements().add(msg); }

        // Execution bar on the receiver for calls (ICONIX: the receiver is doing the work now).
        boolean bar = to != null && (ms == MessageSort.SYNCH_CALL_LITERAL || st.has("steps"));
        if (!bar) return;
        BehaviorExecutionSpecification es = openExecution(container, to, (OccurrenceSpecification) msg.getReceiveEvent());
        callOf.put(es, msg);
        if (st.has("steps")) {
            steps(container, st.getAsJsonArray("steps"));
            close(container, es, returns, st.has("reply") && st.get("reply").getAsBoolean());
        } else if (st.has("open") && st.get("open").getAsBoolean()) {
            open.computeIfAbsent(to, x -> new ArrayDeque<>()).push(es);
            openedHere.add(es);
        } else close(container, es, returns, st.has("reply") && st.get("reply").getAsBoolean());
    }

    /** Execution bar starting at start; finish is set when it is closed. */
    private BehaviorExecutionSpecification openExecution(Element container, Lifeline l, OccurrenceSpecification start) {
        var fr = Sequences.fragments(container);
        BehaviorExecutionSpecification es = UMLFactory.eINSTANCE.createBehaviorExecutionSpecification();
        es.setName(Sequences.unique(container, "exec " + l.getName()));
        fr.add(fr.indexOf(start) + 1, es);
        es.getCovereds().add(l);
        es.setStart(start);
        return es;
    }

    /** Close a bar: with a reply message back to the caller when returns/reply asked, else a plain end. */
    private void close(Element container, BehaviorExecutionSpecification es, String returns, boolean reply) {
        Message call = callOf.get(es);
        if ((returns != null || reply) && call.getSendEvent() instanceof OccurrenceSpecification s) {
            Message r = reply(container, es.getCovereds().get(0), null, s.getCovered(), null, returns == null ? "" : returns, es);
            es.setFinish((OccurrenceSpecification) r.getSendEvent());
        } else Sequences.finishHere(container, es);
    }

    /** A reply message. Its signature is the call being answered; arguments match the operation's result parameters. */
    private Message reply(Element container, Lifeline from, Gate fg, Lifeline to, Gate tg, String text, BehaviorExecutionSpecification es) {
        if (es == null && from != null) {         // explicit reply step: answers the latest open bar on the sender
            Deque<BehaviorExecutionSpecification> q = open.get(from);
            if (q != null && !q.isEmpty()) es = q.pop();
        }
        Message call = es == null ? null : callOf.get(es);
        Operation op = call != null && call.getSignature() instanceof Operation o ? o : null;
        List<String> args = new ArrayList<>();
        if (op != null) {
            long outs = op.getOwnedParameters().stream().filter(p -> p.getDirection() != ParameterDirectionKind.IN_LITERAL).count();
            if (outs == 0 && text != null && !text.isBlank() && op.getOwnedParameters().stream().noneMatch(p -> "result".equals(p.getName()))) {
                op.createOwnedParameter("result", null).setDirection(ParameterDirectionKind.RETURN_LITERAL);
                outs = 1;
            }
            for (int n = 0; n < outs; n++) args.add(text == null || text.isBlank() ? "result" : text);
        }
        Message r = Sequences.addMessage(container, -1, from, fg, to, tg, MessageSort.REPLY_LITERAL,
            text == null || text.isBlank() ? (op != null ? op.getName() : "return") : text, op, args);
        messages++;
        if (es != null && es.getFinish() == null) es.setFinish((OccurrenceSpecification) r.getSendEvent());
        return r;
    }

    /** UML wants one argument per in/inout parameter of the called operation: pad or trim to fit. */
    private List<String> matchArguments(Operation op, List<String> args) {
        List<Parameter> ins = new ArrayList<>();
        for (Parameter p : op.getOwnedParameters())
            if (p.getDirection() == ParameterDirectionKind.IN_LITERAL || p.getDirection() == ParameterDirectionKind.INOUT_LITERAL) ins.add(p);
        List<String> out = new ArrayList<>();
        for (int n = 0; n < ins.size(); n++) out.add(n < args.size() ? args.get(n) : ins.get(n).getName());
        if (args.size() > ins.size()) warnings.add("call " + op.getName() + ": extra arguments " + args.subList(ins.size(), args.size()) + " ignored (operation has " + ins.size() + " parameters)");
        return out;
    }

    private static String req(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) throw new IllegalArgumentException("script step needs '" + k + "': " + o);
        return o.get(k).getAsString();
    }

    /** Break text into lines of at most about width characters, at spaces; keeps existing line breaks. */
    static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        for (String para : text.split("\n", -1)) {
            int len = 0;
            for (String w : para.split(" ")) {
                if (len > 0 && len + 1 + w.length() > width) { out.append('\n'); len = 0; }
                else if (len > 0) { out.append(' '); len++; }
                out.append(w); len += w.length();
            }
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }
}
