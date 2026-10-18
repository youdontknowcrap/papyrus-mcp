package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.uml2.uml.*;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.*;
import org.papyrus.mcp.util.WriteSupport.Ctx;

/*
 * Behavior tools: sequence diagrams (interactions), activities and state machines.
 *
 * Two levels, both going through WriteSupport (one undo step, changelog, stale checks):
 *  - high level: build_sequence / build_activity / build_state_machine take a whole JSON script
 *    and create model + diagram in ONE command. This is what an agent should normally use.
 *  - fine grained: create_interaction, add_lifeline, add_message, add_execution,
 *    add_combined_fragment, add_interaction_use, add_gate, add_state_invariant, remove_message,
 *    then create_sequence_diagram to (re)draw. get_interaction reads the result back.
 */

final class Beh {
    private Beh() {}
    static Interaction interaction(ModelLocator.Active m, String idOrQn) {
        Element e = ElementJson.resolve(m, idOrQn);
        if (e instanceof Interaction i) return i;
        if (e instanceof BehavioredClassifier bc)                 // e.g. a use case owning exactly one interaction
            for (Behavior b : bc.getOwnedBehaviors()) if (b instanceof Interaction i) return i;
        throw new IllegalArgumentException("'" + idOrQn + "' is not an Interaction (or a classifier owning one)");
    }
    /** Container for new fragments: an operand id, else the interaction. */
    static Element container(ModelLocator.Active m, Interaction i, JsonObject a) {
        String c = WriteToolHelpers.s(a, "container");
        if (c == null) return i;
        Element e = ElementJson.resolve(m, c);
        if (e instanceof InteractionOperand || e instanceof Interaction) return e;
        if (e instanceof CombinedFragment cf) return cf.getOperands().get(0);
        throw new IllegalArgumentException("container must be an InteractionOperand (or a CombinedFragment = its first operand)");
    }
    /** Index right after a message's last end inside the container (-1 = append). */
    static int after(ModelLocator.Active m, Element container, JsonObject a) {
        String am = WriteToolHelpers.s(a, "after");
        if (am == null) return -1;
        Element e = ElementJson.resolve(m, am);
        var fr = Sequences.fragments(container);
        int idx = -1;
        if (e instanceof Message msg) {
            for (MessageEnd me : new MessageEnd[]{msg.getSendEvent(), msg.getReceiveEvent()})
                if (me instanceof InteractionFragment f && fr.indexOf(f) >= 0) idx = Math.max(idx, fr.indexOf(f));
        } else if (e instanceof InteractionFragment f) idx = fr.indexOf(f);
        if (idx < 0) throw new IllegalArgumentException("'after' element is not in this container");
        return idx + 1;
    }
    static List<Lifeline> lifelines(Interaction i, JsonObject a, String key) {
        List<Lifeline> out = new ArrayList<>();
        if (a.has(key)) for (JsonElement c : a.getAsJsonArray(key)) out.add(Sequences.lifeline(i, c.getAsString()));
        return out;
    }
    static JsonObject ref(org.eclipse.emf.ecore.EObject e) { return ElementJson.ref(e); }
}

/** Tiny static helpers shared by these tools. */
final class WriteToolHelpers {
    private WriteToolHelpers() {}
    static String s(JsonObject a, String k) { return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : null; }
    static boolean b(JsonObject a, String k, boolean d) { return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsBoolean() : d; }
}

/** build_sequence: the main agentic tool. */
class BuildSequenceTool extends WriteTool {
    public String name() { return "build_sequence"; }
    public String description() {
        return "Build a whole sequence diagram in ONE undo step: an Interaction (owned by a use case, classifier or package), its lifelines, "
             + "messages in time order with execution bars, combined fragments (alt/opt/loop/par/break/critical...), interaction uses, gates, "
             + "state invariants and notes, then draw it. Sync calls find or create the operation on the receiver's class (ICONIX allocation). "
             + "ICONIX: pass robustness=<robustness package or use case> to use its Boundary/Control/Entity classes and actors as lifelines, and "
             + "narrative=true to put the use case text in the left margin. Steps: {from,to,msg:'op(args)',sort?:sync|async|signal|reply|create|delete,"
             + "returns?,steps?:[nested calls inside the bar],note?} | {fragment:'alt',operands:[{guard,steps}]} | {fragment:'loop',guard,steps} | "
             + "{ref:'Interaction'} | {invariant:'text',on:'Lifeline'} | {note:'text',on?}. 'gate:name' as from/to = frame gate.";
    }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{
            {"name", "string", "Interaction name (e.g. 'Hand Off a Chair - sequence')."},
            {"owner", "string", "Owner id/qn: use case, classifier or package (default: the robustness package's use case, else model root)."},
            {"robustness", "string", "Optional robustness package or use case: lifeline names are looked up there first (ICONIX)."},
            {"narrative", "boolean", "true = copy the owning use case's narrative into a left-margin note (ICONIX). A string = that text."},
            {"create_operations", "boolean", "Create missing operations on receivers' classes (default true)."},
            {"replace", "boolean", "Rebuild if an interaction with this name already exists on the owner (default false)."},
            {"diagram", "boolean", "Also draw the sequence diagram (default true)."},
            {"diagram_name", "string", "Diagram name (default: interaction name)."}}, "name", "steps");
        JsonObject ll = new JsonObject(); ll.addProperty("type", "array"); ll.addProperty("description", "Optional lifeline order: names or {name,type}. Unlisted names used in steps are added in order of first use.");
        s.getAsJsonObject("properties").add("lifelines", ll);
        JsonObject st = new JsonObject(); st.addProperty("type", "array"); st.addProperty("description", "Ordered steps (see tool description).");
        JsonObject it = new JsonObject(); it.addProperty("type", "object"); st.add("items", it);
        s.getAsJsonObject("properties").add("steps", st);
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "build sequence '" + str(a, "name") + "'"; }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception { return SequenceScript.build(ctx, a); }
}

class CreateInteractionTool extends WriteTool {
    public String name() { return "create_interaction"; }
    public String description() { return "Create an empty Interaction (sequence diagram model) owned by a use case, classifier or package. Undoable."; }
    protected JsonObject schema() { return schema(new String[][]{{"name", "string", "Name."}, {"owner", "string", "Owner id/qn (default model root)."}}, "name"); }
    public String label(ModelLocator.Active m, JsonObject a) { return "create interaction '" + str(a, "name") + "'"; }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Element owner = ElementJson.resolve(ctx.m, str(a, "owner"));
        Interaction i = Sequences.createInteraction(owner, need(a, "name"));
        ctx.touched(i); ctx.summaries.add("created interaction " + i.getName());
        return Beh.ref(i);
    }
}

class AddLifelineTool extends WriteTool {
    public String name() { return "add_lifeline"; }
    public String description() { return "Add a lifeline to an interaction. type = class/actor it stands for (an attribute of the interaction is created and represented), or represents = an existing property/part. Undoable."; }
    protected JsonObject schema() { return schema(new String[][]{{"interaction", "string", "Interaction id/qn."}, {"name", "string", "Lifeline name."},
        {"type", "string", "Classifier id/qn it represents."}, {"represents", "string", "Existing property/part id/qn instead of type."}}, "interaction", "name"); }
    public String label(ModelLocator.Active m, JsonObject a) { return "add lifeline '" + str(a, "name") + "'"; }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Interaction i = Beh.interaction(ctx.m, need(a, "interaction"));
        Type t = str(a, "type") == null ? null : (Type) ElementJson.resolve(ctx.m, str(a, "type"));
        ConnectableElement rep = str(a, "represents") == null ? null : (ConnectableElement) ElementJson.resolve(ctx.m, str(a, "represents"));
        Lifeline l = Sequences.addLifeline(i, need(a, "name"), t, rep);
        ctx.touched(l); ctx.summaries.add("added lifeline " + l.getName() + " to " + i.getName());
        return Beh.ref(l);
    }
}

class AddMessageTool extends WriteTool {
    public String name() { return "add_message"; }
    public String description() {
        return "Add a message between two lifelines (or 'gate:name'). sort = sync | async | signal | reply | create | delete. msg 'op(a,b)' "
             + "links the operation of that name on the receiver's class (created if create_operation, default true for calls). "
             + "Placed at the end, or after=<message id>, inside container=<operand id> for fragments. execution=true adds a bar on the receiver. Undoable.";
    }
    protected JsonObject schema() { return schema(new String[][]{{"interaction", "string", "Interaction id/qn."}, {"from", "string", "Sender lifeline name/id or gate:name."},
        {"to", "string", "Receiver lifeline name/id or gate:name."}, {"msg", "string", "Message text, e.g. 'submit(brief)'."}, {"sort", "string", "sync (default) | async | signal | reply | create | delete"},
        {"operation", "string", "Explicit operation id/qn to call."}, {"create_operation", "boolean", "Create the operation on the receiver if missing (default true for sync/async)."},
        {"returns", "string", "Return value name (adds a result parameter when the operation is created)."},
        {"container", "string", "InteractionOperand id (default: the interaction)."}, {"after", "string", "Insert after this message (id)."},
        {"execution", "boolean", "Add an execution bar on the receiver (default false)."}}, "interaction", "from", "to", "msg"); }
    public String label(ModelLocator.Active m, JsonObject a) { return "add message '" + str(a, "msg") + "'"; }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Interaction i = Beh.interaction(ctx.m, need(a, "interaction"));
        Element c = Beh.container(ctx.m, i, a);
        String f = need(a, "from"), t = need(a, "to");
        Lifeline from = f.startsWith("gate:") ? null : Sequences.lifeline(i, f), to = t.startsWith("gate:") ? null : Sequences.lifeline(i, t);
        Gate fg = from == null ? Sequences.gate(i, f.substring(5), true) : null, tg = to == null ? Sequences.gate(i, t.substring(5), true) : null;
        MessageSort ms = Sequences.sort(str(a, "sort"));
        List<String> call = Sequences.parseCall(need(a, "msg"));
        List<String> args = new ArrayList<>(call.subList(1, call.size()));
        Operation op = null;
        List<String> created = new ArrayList<>();
        if (str(a, "operation") != null) op = (Operation) ElementJson.resolve(ctx.m, str(a, "operation"));
        else if (to != null && (ms == MessageSort.SYNCH_CALL_LITERAL || ms == MessageSort.ASYNCH_CALL_LITERAL))
            op = Sequences.operation(to, call.get(0), args, str(a, "returns"), WriteToolHelpers.b(a, "create_operation", true), created);
        if (op != null && ms != MessageSort.REPLY_LITERAL) {
            long n = op.getOwnedParameters().stream().filter(p -> p.getDirection() == ParameterDirectionKind.IN_LITERAL || p.getDirection() == ParameterDirectionKind.INOUT_LITERAL).count();
            while (args.size() < n) args.add("arg" + args.size());
            while (args.size() > n) args.remove(args.size() - 1);
        }
        Message msg = Sequences.addMessage(c, Beh.after(ctx.m, c, a), from, fg, to, tg, ms, call.get(0), op, args);
        if (WriteToolHelpers.b(a, "execution", false) && to != null) Sequences.addExecution(c, to, (OccurrenceSpecification) msg.getReceiveEvent(), null);
        ctx.touched(msg); ctx.summaries.add("added message " + msg.getName());
        JsonObject o = Beh.ref(msg);
        JsonArray cr = new JsonArray(); created.forEach(cr::add); o.add("createdOperations", cr);
        return o;
    }
}

class AddExecutionTool extends WriteTool {
    public String name() { return "add_execution"; }
    public String description() { return "Add (or re-time) an execution bar on a lifeline from one message end to another: start_message (its receive end by default) to finish_message (its send end by default). Undoable."; }
    protected JsonObject schema() { return schema(new String[][]{{"interaction", "string", "Interaction id/qn."}, {"lifeline", "string", "Lifeline name/id."},
        {"start_message", "string", "Message id whose end starts the bar."}, {"start_end", "string", "receive (default) | send"},
        {"finish_message", "string", "Message id whose end finishes the bar (default: a short bar)."}, {"finish_end", "string", "send (default) | receive"}}, "interaction", "lifeline", "start_message"); }
    public String label(ModelLocator.Active m, JsonObject a) { return "add execution on " + str(a, "lifeline"); }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Interaction i = Beh.interaction(ctx.m, need(a, "interaction"));
        Lifeline l = Sequences.lifeline(i, need(a, "lifeline"));
        Message sm = (Message) ElementJson.resolve(ctx.m, need(a, "start_message"));
        OccurrenceSpecification start = (OccurrenceSpecification) ("send".equals(str(a, "start_end")) ? sm.getSendEvent() : sm.getReceiveEvent());
        OccurrenceSpecification fin = null;
        if (str(a, "finish_message") != null) {
            Message fm = (Message) ElementJson.resolve(ctx.m, str(a, "finish_message"));
            fin = (OccurrenceSpecification) ("receive".equals(str(a, "finish_end")) ? fm.getReceiveEvent() : fm.getSendEvent());
        }
        if (start.getCovered() != l || (fin != null && fin.getCovered() != l)) throw new IllegalArgumentException("both ends must be on lifeline " + l.getName());
        for (InteractionFragment f : l.getCoveredBys())        // re-time an existing bar that starts here
            if (f instanceof ExecutionSpecification es && es.getStart() == start && fin != null) {
                if (es.getFinish() instanceof ExecutionOccurrenceSpecification old && old != fin) EcoreUtil.delete(old);
                es.setFinish(fin); ctx.touched(es); ctx.summaries.add("re-timed execution on " + l.getName()); return Beh.ref(es);
            }
        BehaviorExecutionSpecification es = Sequences.addExecution((Element) start.getOwner(), l, start, fin);
        ctx.touched(es); ctx.summaries.add("added execution on " + l.getName());
        return Beh.ref(es);
    }
}

class AddCombinedFragmentTool extends WriteTool {
    public String name() { return "add_combined_fragment"; }
    public String description() {
        return "Add a combined fragment (alt | opt | loop | par | break | critical | neg | seq | strict | assert | ignore | consider) with operands [{guard}]. "
             + "Optionally move existing messages into it: messages=[ids] (first operand) or operand_messages=[[ids],[ids]]; it is placed where the first moved message was. Undoable.";
    }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{{"interaction", "string", "Interaction id/qn."}, {"operator", "string", "alt | opt | loop | par | break | critical | ..."},
            {"container", "string", "Operand id for nesting (default: interaction)."}, {"after", "string", "Place after this message (when not moving messages)."}}, "interaction", "operator");
        for (String k : new String[]{"operands", "covers", "messages", "operand_messages"}) {
            JsonObject p = new JsonObject(); p.addProperty("type", "array");
            p.addProperty("description", switch (k) { case "operands" -> "[{guard:'text'}] (default: one operand without guard)";
                case "covers" -> "Lifeline names (default: lifelines of moved messages, else all)"; case "messages" -> "Message ids to move into the first operand";
                default -> "Per-operand lists of message ids"; });
            s.getAsJsonObject("properties").add(k, p);
        }
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "add " + str(a, "operator") + " fragment"; }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Interaction i = Beh.interaction(ctx.m, need(a, "interaction"));
        Element c = Beh.container(ctx.m, i, a);
        List<String> guards = new ArrayList<>();
        if (a.has("operands")) for (JsonElement o : a.getAsJsonArray("operands"))
            guards.add(o.isJsonObject() && o.getAsJsonObject().has("guard") ? o.getAsJsonObject().get("guard").getAsString() : o.isJsonPrimitive() ? o.getAsString() : null);
        List<List<Message>> moves = new ArrayList<>();
        if (a.has("operand_messages")) for (JsonElement l : a.getAsJsonArray("operand_messages")) {
            List<Message> ms = new ArrayList<>(); for (JsonElement e : l.getAsJsonArray()) ms.add((Message) ElementJson.resolve(ctx.m, e.getAsString())); moves.add(ms);
        } else if (a.has("messages")) {
            List<Message> ms = new ArrayList<>(); for (JsonElement e : a.getAsJsonArray("messages")) ms.add((Message) ElementJson.resolve(ctx.m, e.getAsString())); moves.add(ms);
        }
        while (guards.size() < Math.max(1, moves.size())) guards.add(null);
        var fr = Sequences.fragments(c);
        // fragments that travel with each message: its ends + bars starting at them (+ their end markers)
        List<List<InteractionFragment>> travel = new ArrayList<>();
        int at = Integer.MAX_VALUE;
        Set<Lifeline> cov = new LinkedHashSet<>(Beh.lifelines(i, a, "covers"));
        for (List<Message> ms : moves) {
            Set<InteractionFragment> set = new LinkedHashSet<>();
            for (Message m : ms) for (InteractionFragment f : Sequences.fragmentsOf(m)) {
                if (fr.indexOf(f) < 0) throw new IllegalArgumentException("message " + m.getName() + " is not in this container");
                set.add(f);
                if (!a.has("covers")) cov.addAll(f.getCovereds());
                for (InteractionFragment g : fr) if (g instanceof ExecutionSpecification es && es.getStart() == f) {
                    set.add(es); if (es.getFinish() instanceof ExecutionOccurrenceSpecification eo) set.add(eo);
                }
            }
            List<InteractionFragment> ordered = new ArrayList<>(set);
            ordered.sort(Comparator.comparingInt(fr::indexOf));
            if (!ordered.isEmpty()) at = Math.min(at, fr.indexOf(ordered.get(0)));
            travel.add(ordered);
        }
        if (cov.isEmpty()) cov.addAll(i.getLifelines());
        for (List<InteractionFragment> t : travel) fr.removeAll(t);
        int index = at == Integer.MAX_VALUE ? Beh.after(ctx.m, c, a) : at;
        CombinedFragment cf = Sequences.addCombinedFragment(c, index, need(a, "operator"), cov, guards);
        for (int n = 0; n < travel.size() && n < cf.getOperands().size(); n++) Sequences.moveInto(cf.getOperands().get(n), travel.get(n));
        ctx.touched(cf); ctx.summaries.add("added " + cf.getInteractionOperator().getLiteral() + " fragment to " + i.getName());
        JsonObject o = Beh.ref(cf);
        JsonArray ops = new JsonArray(); for (InteractionOperand op : cf.getOperands()) ops.add(Beh.ref(op)); o.add("operands", ops);
        return o;
    }
}

class AddInteractionUseTool extends WriteTool {
    public String name() { return "add_interaction_use"; }
    public String description() { return "Add a 'ref' box that refers to another interaction, covering lifelines (default all). Undoable."; }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{{"interaction", "string", "Interaction id/qn."}, {"refers_to", "string", "Referenced interaction id/qn."},
            {"container", "string", "Operand id (default interaction)."}, {"after", "string", "Place after this message id."}}, "interaction", "refers_to");
        JsonObject p = new JsonObject(); p.addProperty("type", "array"); p.addProperty("description", "Lifeline names."); s.getAsJsonObject("properties").add("covers", p);
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "add ref to " + n(m, a, "refers_to"); }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Interaction i = Beh.interaction(ctx.m, need(a, "interaction"));
        Element c = Beh.container(ctx.m, i, a);
        List<Lifeline> cov = Beh.lifelines(i, a, "covers");
        InteractionUse u = Sequences.addInteractionUse(c, Beh.after(ctx.m, c, a), Beh.interaction(ctx.m, need(a, "refers_to")), cov.isEmpty() ? i.getLifelines() : cov);
        ctx.touched(u); ctx.summaries.add("added " + u.getName());
        return Beh.ref(u);
    }
}

class AddGateTool extends WriteTool {
    public String name() { return "add_gate"; }
    public String description() { return "Add a formal gate (a point on the diagram frame) to an interaction; messages use it with from/to 'gate:<name>'. Undoable."; }
    protected JsonObject schema() { return schema(new String[][]{{"interaction", "string", "Interaction id/qn."}, {"name", "string", "Gate name."}}, "interaction", "name"); }
    public String label(ModelLocator.Active m, JsonObject a) { return "add gate " + str(a, "name"); }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Interaction i = Beh.interaction(ctx.m, need(a, "interaction"));
        Gate g = Sequences.gate(i, need(a, "name"), true);
        ctx.touched(g); ctx.summaries.add("added gate " + g.getName());
        return Beh.ref(g);
    }
}

class AddStateInvariantTool extends WriteTool {
    public String name() { return "add_state_invariant"; }
    public String description() { return "Add a state invariant (a condition that must hold at that moment) on a lifeline. Undoable."; }
    protected JsonObject schema() { return schema(new String[][]{{"interaction", "string", "Interaction id/qn."}, {"lifeline", "string", "Lifeline name/id."},
        {"text", "string", "Condition, e.g. 'task.state = ready'."}, {"container", "string", "Operand id."}, {"after", "string", "Place after this message id."}}, "interaction", "lifeline", "text"); }
    public String label(ModelLocator.Active m, JsonObject a) { return "add state invariant " + str(a, "text"); }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Interaction i = Beh.interaction(ctx.m, need(a, "interaction"));
        Element c = Beh.container(ctx.m, i, a);
        StateInvariant s = Sequences.addStateInvariant(c, Beh.after(ctx.m, c, a), Sequences.lifeline(i, need(a, "lifeline")), need(a, "text"));
        ctx.touched(s); ctx.summaries.add("added state invariant " + s.getName());
        return Beh.ref(s);
    }
}

class RemoveMessageTool extends WriteTool {
    public String name() { return "remove_message"; }
    public String description() { return "Delete a message cleanly: its two ends, and execution bars that start at its receive end (bars that finish at it get a plain end instead). Operations are kept. Undoable."; }
    protected JsonObject schema() { return schema(new String[][]{{"message", "string", "Message id."}}, "message"); }
    public String label(ModelLocator.Active m, JsonObject a) { return "remove message " + n(m, a, "message"); }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Message msg = (Message) ElementJson.resolve(ctx.m, need(a, "message"));
        String nm = msg.getName();
        List<InteractionFragment> ends = Sequences.fragmentsOf(msg);
        for (InteractionFragment f : ends) {
            for (InteractionFragment g : new ArrayList<>(f.getCovereds().isEmpty() ? List.<InteractionFragment>of() : f.getCovereds().get(0).getCoveredBys())) {
                if (g instanceof ExecutionSpecification es) {
                    if (es.getStart() == f) { if (es.getFinish() instanceof ExecutionOccurrenceSpecification eo) EcoreUtil.delete(eo); EcoreUtil.delete(es); }
                    else if (es.getFinish() == f) {
                        ExecutionOccurrenceSpecification eo = UMLFactory.eINSTANCE.createExecutionOccurrenceSpecification();
                        var fr = Sequences.fragments((Element) f.getOwner());
                        fr.add(fr.indexOf(f), eo); eo.setCovered(f.getCovereds().get(0)); eo.setExecution(es); es.setFinish(eo);
                    }
                }
            }
        }
        for (InteractionFragment f : ends) EcoreUtil.delete(f);
        EcoreUtil.delete(msg);
        ctx.summaries.add("removed message " + nm);
        JsonObject o = new JsonObject(); o.addProperty("removed", nm); return o;
    }
}

class CreateSequenceDiagramTool extends WriteTool {
    public String name() { return "create_sequence_diagram"; }
    public String description() {
        return "Draw (or redraw) the sequence diagram of an interaction from the model: lifelines left to right, messages top to bottom in model order, "
             + "execution bars, fragment frames, refs, gates, invariants, notes. Redrawing keeps the diagram id. narrative=true puts the use case text "
             + "(or the interaction's narrative note) in the left margin. Undoable.";
    }
    protected JsonObject schema() { return schema(new String[][]{{"interaction", "string", "Interaction id/qn (or the use case owning it)."},
        {"name", "string", "Diagram name (default interaction name)."}, {"narrative", "boolean", "Left-margin narrative note (default: if the interaction has one)."}}, "interaction"); }
    public String label(ModelLocator.Active m, JsonObject a) { return "draw sequence diagram " + n(m, a, "interaction"); }
    public JsonObject apply(Ctx ctx, JsonObject a) {
        Interaction i = Beh.interaction(ctx.m, need(a, "interaction"));
        String narrative = null;
        for (Comment c : i.getOwnedComments()) if (c.getAnnotatedElements().contains(i)) narrative = c.getBody();
        if (WriteToolHelpers.b(a, "narrative", narrative != null) && narrative == null && i.getOwner() instanceof UseCase uc && !uc.getOwnedComments().isEmpty()) {
            Comment c = i.createOwnedComment(); c.setBody(uc.getOwnedComments().get(0).getBody()); c.getAnnotatedElements().add(i); narrative = c.getBody();
        }
        if (!WriteToolHelpers.b(a, "narrative", true)) narrative = null;
        Diagram d = Behaviors.diagramFor(ctx.m, Behaviors.SEQUENCE, i, str(a, "name"));
        JsonObject r = Sequences.render(d, i, narrative);
        r.addProperty("id", d.eResource().getURIFragment(d)); r.addProperty("name", d.getName());
        ctx.touched(i); ctx.summaries.add("drew sequence diagram " + d.getName());
        return r;
    }
}

class GetInteractionTool extends ReadTool {
    public String name() { return "get_interaction"; }
    public String description() { return "Read an interaction: lifelines (with types and stereotypes), gates, the ordered message list (sort, from, to, operation, arguments, enclosing fragment) and the nested sequence of messages, executions, fragments, refs and invariants. Read-only."; }
    public JsonObject inputSchema() { JsonObject s = baseSchema(); prop(s, "interaction", "string", "Interaction id/qn, or a use case/classifier owning one."); require(s, "interaction"); return s; }
    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) { return Sequences.describe(Beh.interaction(m, str(a, "interaction"))); }
}

class BuildActivityTool extends WriteTool {
    public String name() { return "build_activity"; }
    public String description() {
        return "Build an activity (model + activity diagram) in ONE undo step. nodes=[{id,kind,name?,partition?,body?,behavior?,event?,signal?}] with kind = initial | final | flowfinal | action | call | "
             + "decision | merge | fork | join | accept | send | buffer | datastore; flows=[{from,to,guard?}] by node id or name; partitions=[names] become swimlanes. "
             + "activity=<id> adds to an existing activity and redraws it. Undoable.";
    }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{{"name", "string", "Activity name (new activity)."}, {"owner", "string", "Owner id/qn: classifier, use case or package (default model root)."},
            {"activity", "string", "Existing activity id/qn to extend instead."}, {"replace", "boolean", "Rebuild an existing activity of this name."},
            {"diagram", "boolean", "Draw the diagram (default true)."}, {"diagram_name", "string", "Diagram name."}, {"rankdir", "string", "TB (default) | LR"}});
        for (String k : new String[]{"nodes", "flows", "partitions"}) { JsonObject p = new JsonObject(); p.addProperty("type", "array"); p.addProperty("description", "See tool description."); s.getAsJsonObject("properties").add(k, p); }
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "build activity '" + (str(a, "name") != null ? str(a, "name") : n(m, a, "activity")) + "'"; }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        if (str(a, "name") == null && str(a, "activity") == null) throw new IllegalArgumentException("give name (new activity) or activity (existing)");
        return Activities.build(ctx, a);
    }
}

class BuildStateMachineTool extends WriteTool {
    public String name() { return "build_state_machine"; }
    public String description() {
        return "Build a state machine (model + diagram) in ONE undo step. states=[{name,kind?,entry?,do?,exit?,states?:[nested],transitions?:[nested]}] with kind = state | initial | final | "
             + "choice | junction | fork | join | shallowHistory | deepHistory | terminate | entryPoint | exitPoint; transitions=[{from,to,trigger?,guard?,effect?}]. "
             + "Composite states get a nested region. state_machine=<id> adds to an existing one and redraws it. Undoable.";
    }
    protected JsonObject schema() {
        JsonObject s = schema(new String[][]{{"name", "string", "State machine name (new)."}, {"owner", "string", "Owner id/qn: class or package (default model root)."},
            {"state_machine", "string", "Existing state machine id/qn to extend."}, {"replace", "boolean", "Rebuild an existing one of this name."},
            {"diagram", "boolean", "Draw the diagram (default true)."}, {"diagram_name", "string", "Diagram name."}});
        for (String k : new String[]{"states", "transitions"}) { JsonObject p = new JsonObject(); p.addProperty("type", "array"); p.addProperty("description", "See tool description."); s.getAsJsonObject("properties").add(k, p); }
        return s;
    }
    public String label(ModelLocator.Active m, JsonObject a) { return "build state machine '" + (str(a, "name") != null ? str(a, "name") : n(m, a, "state_machine")) + "'"; }
    public JsonObject apply(Ctx ctx, JsonObject a) throws Exception {
        if (str(a, "name") == null && str(a, "state_machine") == null) throw new IllegalArgumentException("give name (new) or state_machine (existing)");
        return StateMachines.build(ctx, a);
    }
}
