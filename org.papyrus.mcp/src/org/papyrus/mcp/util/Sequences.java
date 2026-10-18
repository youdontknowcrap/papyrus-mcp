package org.papyrus.mcp.util;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.gmf.runtime.notation.*;
import org.eclipse.gmf.runtime.notation.Node;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Class;

/**
 * Sequence diagrams: the model operations (interactions, lifelines, messages, executions,
 * combined fragments, interaction uses, gates, state invariants) and the renderer that draws
 * a classic Papyrus sequence diagram from the model.
 *
 * Plain words: in UML a sequence diagram is backed by an Interaction. Time runs top to bottom
 * and is stored as the ORDER of "fragments" in the Interaction (or inside an operand of an
 * alt/opt/loop box). A message has two ends ("occurrence specifications"): the send event on
 * the sender's lifeline and the receive event on the receiver's lifeline. An execution bar is
 * an ExecutionSpecification that starts and finishes at two events on one lifeline.
 * The renderer walks the fragments in order and gives every event a y coordinate.
 */
public final class Sequences {
    private Sequences() {}

    // ------------------------------------------------------------------ model: containers & lookup

    /** The fragment list of an Interaction or InteractionOperand. */
    public static EList<InteractionFragment> fragments(Element container) {
        if (container instanceof Interaction i) return i.getFragments();
        if (container instanceof InteractionOperand o) return o.getFragments();
        throw new IllegalArgumentException("fragments live in an Interaction or an InteractionOperand, not a " + container.eClass().getName());
    }

    public static Interaction interactionOf(Element e) {
        for (Element o = e; o != null; o = o.getOwner()) if (o instanceof Interaction i) return i;
        throw new IllegalArgumentException(e.eClass().getName() + " is not inside an Interaction");
    }

    public static Lifeline lifeline(Interaction i, String nameOrId) {
        for (Lifeline l : i.getLifelines()) if (nameOrId.equals(l.getName()) || nameOrId.equals(ElementJson.id(l))) return l;
        List<String> names = new ArrayList<>();
        for (Lifeline l : i.getLifelines()) names.add(l.getName());
        throw new IllegalArgumentException("no lifeline '" + nameOrId + "' in interaction " + i.getName() + " (lifelines: " + names + ")");
    }

    public static Gate gate(Interaction i, String name, boolean create) {
        for (Gate g : i.getFormalGates()) if (name.equals(g.getName())) return g;
        if (!create) throw new IllegalArgumentException("no gate '" + name + "' on " + i.getName());
        return i.createFormalGate(name);
    }

    /** Type of the lifeline (via represents), or null. */
    public static Type typeOf(Lifeline l) {
        return l.getRepresents() != null ? l.getRepresents().getType() : null;
    }

    // ------------------------------------------------------------------ model: creation

    public static Interaction createInteraction(Element owner, String name) {
        return Behaviors.createBehavior(owner, name, UMLPackage.Literals.INTERACTION);
    }

    /**
     * New lifeline. type = the class/actor it stands for: we create an attribute of the
     * Interaction typed by it and let the lifeline represent that attribute (the standard
     * UML way). represents = an existing property/part instead.
     */
    public static Lifeline addLifeline(Interaction i, String name, Type type, ConnectableElement represents) {
        Lifeline l = i.createLifeline(name);
        if (represents == null && type != null) {
            Property p = i.createOwnedAttribute(name, type);
            represents = p;
        }
        if (represents != null) l.setRepresents(represents);
        return l;
    }

    public static MessageSort sort(String s) {
        return switch (s == null ? "sync" : s.toLowerCase(Locale.ROOT)) {
            case "sync", "synch", "synchcall", "call" -> MessageSort.SYNCH_CALL_LITERAL;
            case "async", "asynch", "asynchcall" -> MessageSort.ASYNCH_CALL_LITERAL;
            case "signal", "asynchsignal" -> MessageSort.ASYNCH_SIGNAL_LITERAL;
            case "reply", "return" -> MessageSort.REPLY_LITERAL;
            case "create", "createmessage" -> MessageSort.CREATE_MESSAGE_LITERAL;
            case "delete", "destroy", "deletemessage" -> MessageSort.DELETE_MESSAGE_LITERAL;
            default -> throw new IllegalArgumentException("message sort must be sync | async | signal | reply | create | delete, not " + s);
        };
    }

    /** "submit(brief, cap)" -> ["submit", "brief", "cap"]; "submit" -> ["submit"]. */
    public static List<String> parseCall(String text) {
        List<String> out = new ArrayList<>();
        int p = text.indexOf('(');
        if (p < 0 || !text.trim().endsWith(")")) { out.add(text.trim()); return out; }
        out.add(text.substring(0, p).trim());
        String inner = text.substring(p + 1, text.lastIndexOf(')')).trim();
        if (!inner.isEmpty()) for (String a : inner.split(",")) out.add(a.trim());
        return out;
    }

    /**
     * The operation a message calls on the receiver's type: found by name (including inherited
     * ones), or created on the receiver's class if create is true (ICONIX: this is how behavior is
     * allocated to classes). Returns null when the receiver has no class (e.g. an actor) or none
     * was found and create is false.
     */
    public static Operation operation(Lifeline receiver, String name, List<String> params, String returns, boolean create, List<String> created) {
        Type t = typeOf(receiver);
        if (!(t instanceof Classifier c)) return null;
        for (Operation op : c.getAllOperations()) if (name.equals(op.getName())) return op;
        if (!create || t instanceof Actor) return null;
        Operation op;
        if (t instanceof Class cl) op = cl.createOwnedOperation(name, null, null);
        else if (t instanceof Interface in) op = in.createOwnedOperation(name, null, null);
        else return null;
        for (String p : params) op.createOwnedParameter(p.replaceAll("[^A-Za-z0-9_]", ""), null);
        if (returns != null && !returns.isBlank()) op.createOwnedParameter("result", null).setDirection(ParameterDirectionKind.RETURN_LITERAL);
        created.add(c.getName() + "::" + name);
        return op;
    }

    /** One message end (send or receive) as an occurrence specification in the container at index. */
    private static MessageOccurrenceSpecification end(Element container, int index, String name, Lifeline l, boolean destruction) {
        EList<InteractionFragment> fr = fragments(container);
        MessageOccurrenceSpecification os = (MessageOccurrenceSpecification) UMLFactory.eINSTANCE.create(destruction
            ? UMLPackage.Literals.DESTRUCTION_OCCURRENCE_SPECIFICATION : UMLPackage.Literals.MESSAGE_OCCURRENCE_SPECIFICATION);
        os.setName(name);
        if (index < 0 || index > fr.size()) fr.add(os); else fr.add(index, os);
        os.setCovered(l);
        return os;
    }

    /**
     * Add a message. from/to are lifelines; a null lifeline with a gate name makes a message
     * from/to the diagram frame (formal gate). Both ends go into container (an Interaction or an
     * operand of a combined fragment) at index (-1 = at the end). The message itself is always
     * owned by the Interaction (that is where UML keeps messages).
     */
    public static Message addMessage(Element container, int index, Lifeline from, Gate fromGate, Lifeline to, Gate toGate,
                                     MessageSort ms, String name, Operation sig, List<String> args) {
        Interaction i = interactionOf(container);
        Message m = i.createMessage(name);
        m.setMessageSort(ms);
        int idx = index;
        MessageEnd send, recv;
        if (from != null) { send = end(container, idx, unique(container, name + "_send"), from, false); if (idx >= 0) idx++; }
        else send = fromGate;
        if (to != null) recv = end(container, idx, unique(container, name + "_recv"), to, ms == MessageSort.DELETE_MESSAGE_LITERAL);
        else recv = toGate;
        m.setSendEvent(send);
        m.setReceiveEvent(recv);
        if (send instanceof MessageOccurrenceSpecification s) s.setMessage(m);
        if (recv instanceof MessageOccurrenceSpecification r) r.setMessage(m);
        if (send instanceof Gate g) g.setName(g.getName());     // gates are named by the caller
        if (sig != null) m.setSignature(sig);
        if (args != null) for (String a : args) m.getArguments().add(Behaviors.literal(a));
        return m;
    }

    /**
     * Execution bar on lifeline l from start to finish. A null finish makes a new execution
     * occurrence right after start (a zero-length bar the renderer still shows).
     */
    public static BehaviorExecutionSpecification addExecution(Element container, Lifeline l, OccurrenceSpecification start, OccurrenceSpecification finish) {
        EList<InteractionFragment> fr = fragments(container);
        BehaviorExecutionSpecification es = UMLFactory.eINSTANCE.createBehaviorExecutionSpecification();
        es.setName(unique(container, "exec " + l.getName()));
        int at = fr.indexOf(start);
        fr.add(at < 0 ? fr.size() : at + 1, es);
        es.getCovereds().add(l);
        es.setStart(start);
        if (finish == null) {
            ExecutionOccurrenceSpecification eos = UMLFactory.eINSTANCE.createExecutionOccurrenceSpecification();
            eos.setName(es.getName() + " end");
            fr.add(fr.indexOf(es) + 1, eos);
            eos.setCovered(l);
            eos.setExecution(es);
            finish = eos;
        }
        es.setFinish(finish);
        return es;
    }

    /** End an execution at the current end of the container with a fresh execution occurrence. */
    public static void finishHere(Element container, BehaviorExecutionSpecification es) {
        ExecutionOccurrenceSpecification eos = UMLFactory.eINSTANCE.createExecutionOccurrenceSpecification();
        eos.setName(es.getName() + " end");
        fragments(container).add(eos);
        eos.setCovered(es.getCovereds().get(0));
        eos.setExecution(es);
        es.setFinish(eos);
    }

    public static CombinedFragment addCombinedFragment(Element container, int index, String operator, Collection<Lifeline> covers, List<String> guards) {
        InteractionOperatorKind k = InteractionOperatorKind.get(operator.toLowerCase(Locale.ROOT));
        if (k == null) throw new IllegalArgumentException("operator must be one of alt opt loop par break critical neg seq strict assert ignore consider, not " + operator);
        CombinedFragment cf = UMLFactory.eINSTANCE.createCombinedFragment();
        cf.setName(unique(container, operator));
        EList<InteractionFragment> fr = fragments(container);
        if (index < 0 || index > fr.size()) fr.add(cf); else fr.add(index, cf);
        cf.setInteractionOperator(k);
        cf.getCovereds().addAll(covers);
        List<String> gs = guards == null || guards.isEmpty() ? Collections.singletonList(null) : guards;
        for (String g : gs) {
            InteractionOperand op = cf.createOperand(g == null ? "operand" : g);
            op.getCovereds().addAll(covers);
            if (g != null && !g.isBlank()) {
                InteractionConstraint c = op.createGuard("guard");
                c.setSpecification(Behaviors.literal(g));
            }
        }
        return cf;
    }

    public static InteractionUse addInteractionUse(Element container, int index, Interaction refersTo, Collection<Lifeline> covers) {
        InteractionUse u = UMLFactory.eINSTANCE.createInteractionUse();
        u.setName(unique(container, "ref " + (refersTo == null ? "?" : refersTo.getName())));
        EList<InteractionFragment> fr = fragments(container);
        if (index < 0 || index > fr.size()) fr.add(u); else fr.add(index, u);
        u.setRefersTo(refersTo);
        u.getCovereds().addAll(covers);
        return u;
    }

    public static StateInvariant addStateInvariant(Element container, int index, Lifeline l, String text) {
        StateInvariant s = UMLFactory.eINSTANCE.createStateInvariant();
        s.setName(text);
        EList<InteractionFragment> fr = fragments(container);
        if (index < 0 || index > fr.size()) fr.add(s); else fr.add(index, s);
        s.getCovereds().add(l);
        Constraint c = s.createInvariant("invariant");
        c.setSpecification(Behaviors.literal(text));
        return s;
    }

    /** Move fragments (e.g. a message's two ends and its executions) into an operand, keeping their order. */
    public static void moveInto(InteractionOperand op, List<InteractionFragment> frs) {
        for (InteractionFragment f : frs) op.getFragments().add(f);
    }

    /** All fragments that belong to a message: its two ends plus executions started by its receive end. */
    public static List<InteractionFragment> fragmentsOf(Message m) {
        List<InteractionFragment> out = new ArrayList<>();
        if (m.getSendEvent() instanceof InteractionFragment s) out.add(s);
        if (m.getReceiveEvent() instanceof InteractionFragment r) out.add(r);
        return out;
    }

    // ------------------------------------------------------------------ read: JSON view

    public static JsonObject describe(Interaction i) {
        JsonObject o = new JsonObject();
        o.add("interaction", ElementJson.ref(i));
        o.add("owner", ElementJson.ref(i.getOwner()));
        JsonArray lls = new JsonArray();
        for (Lifeline l : i.getLifelines()) {
            JsonObject lo = ElementJson.ref(l);
            Type t = typeOf(l);
            if (t != null) { lo.addProperty("type", t.getName()); lo.addProperty("typeId", ElementJson.id(t));
                JsonArray st = new JsonArray(); for (Stereotype s : t.getAppliedStereotypes()) st.add(s.getName()); lo.add("typeStereotypes", st); }
            lls.add(lo);
        }
        o.add("lifelines", lls);
        JsonArray gates = new JsonArray();
        for (Gate g : i.getFormalGates()) gates.add(ElementJson.ref(g));
        o.add("gates", gates);
        JsonArray seq = new JsonArray(), msgs = new JsonArray();
        walk(i, "", seq, msgs, new HashSet<>());
        o.add("sequence", seq);
        o.add("messages", msgs);
        return o;
    }

    private static String who(MessageEnd e) {
        if (e instanceof OccurrenceSpecification os && os.getCovered() != null) return os.getCovered().getName();
        if (e instanceof Gate g) return "gate:" + g.getName();
        return "(lost/found)";
    }

    private static void walk(Element container, String path, JsonArray seq, JsonArray msgs, Set<Message> seen) {
        for (InteractionFragment f : fragments(container)) {
            if (f instanceof MessageOccurrenceSpecification mos && mos.getMessage() != null) {
                Message m = mos.getMessage();
                if (!seen.add(m)) continue;
                JsonObject mo = ElementJson.ref(m);
                mo.addProperty("index", msgs.size() + 1);
                mo.addProperty("sort", m.getMessageSort().getLiteral());
                mo.addProperty("from", who(m.getSendEvent()));
                mo.addProperty("to", who(m.getReceiveEvent()));
                if (m.getSignature() != null) { mo.addProperty("operation", m.getSignature().getName()); mo.addProperty("operationId", ElementJson.id(m.getSignature()));
                    if (m.getSignature().getOwner() instanceof NamedElement ow) mo.addProperty("operationOwner", ow.getName()); }
                JsonArray args = new JsonArray();
                for (ValueSpecification v : m.getArguments()) args.add(v.stringValue());
                if (args.size() > 0) mo.add("arguments", args);
                if (!path.isEmpty()) mo.addProperty("in", path);
                msgs.add(mo);
                JsonObject s = new JsonObject();
                s.addProperty("kind", "message"); s.addProperty("text", mo.get("index").getAsInt() + ". " + who(m.getSendEvent())
                    + " -> " + who(m.getReceiveEvent()) + ": " + m.getName() + " [" + m.getMessageSort().getLiteral() + "]");
                s.addProperty("id", ElementJson.id(m));
                seq.add(s);
            } else if (f instanceof ExecutionSpecification es) {
                JsonObject s = new JsonObject();
                s.addProperty("kind", "execution"); s.addProperty("id", ElementJson.id(es));
                s.addProperty("lifeline", es.getCovereds().isEmpty() ? null : es.getCovereds().get(0).getName());
                s.addProperty("start", es.getStart() == null ? null : es.getStart().getName());
                s.addProperty("finish", es.getFinish() == null ? null : es.getFinish().getName());
                seq.add(s);
            } else if (f instanceof CombinedFragment cf) {
                JsonObject s = new JsonObject();
                s.addProperty("kind", "fragment"); s.addProperty("operator", cf.getInteractionOperator().getLiteral()); s.addProperty("id", ElementJson.id(cf));
                JsonArray cov = new JsonArray(); for (Lifeline l : cf.getCovereds()) cov.add(l.getName()); s.add("covers", cov);
                JsonArray ops = new JsonArray();
                int n = 0;
                for (InteractionOperand op : cf.getOperands()) {
                    JsonObject oo = new JsonObject();
                    oo.addProperty("id", ElementJson.id(op));
                    oo.addProperty("guard", guard(op));
                    JsonArray inner = new JsonArray();
                    walk(op, path + (path.isEmpty() ? "" : " / ") + cf.getInteractionOperator().getLiteral() + "[" + Objects.toString(guard(op), "") + "]#" + (++n), inner, msgs, seen);
                    oo.add("sequence", inner);
                    ops.add(oo);
                }
                s.add("operands", ops);
                seq.add(s);
            } else if (f instanceof InteractionUse u) {
                JsonObject s = new JsonObject();
                s.addProperty("kind", "interactionUse"); s.addProperty("id", ElementJson.id(u));
                s.addProperty("refersTo", u.getRefersTo() == null ? null : u.getRefersTo().getName());
                seq.add(s);
            } else if (f instanceof StateInvariant si) {
                JsonObject s = new JsonObject();
                s.addProperty("kind", "stateInvariant"); s.addProperty("id", ElementJson.id(si));
                s.addProperty("lifeline", si.getCovereds().isEmpty() ? null : si.getCovereds().get(0).getName());
                s.addProperty("text", si.getInvariant() != null && si.getInvariant().getSpecification() != null ? si.getInvariant().getSpecification().stringValue() : si.getName());
                seq.add(s);
            }
        }
    }

    public static String guard(InteractionOperand op) {
        return op.getGuard() != null && op.getGuard().getSpecification() != null ? op.getGuard().getSpecification().stringValue() : null;
    }

    // ------------------------------------------------------------------ rendering

    // Layout constants (pixels). Time flows down: each message gets its own row.
    static final int LL_TOP = 10, HEAD = 40, FIRST = 75, STEP = 40, GAP = 50, MARGIN_W = 270, NOTE_W = 220;

    /** Rows and boxes computed by the walk, then turned into views. */
    private static final class Plan {
        final Map<OccurrenceSpecification, Integer> y = new HashMap<>();
        final Map<Message, int[]> msgY = new LinkedHashMap<>();           // [sendY, recvY]
        final List<Object[]> boxes = new ArrayList<>();                   // {CombinedFragment, top, bottom, depth, List<int[]> operand tops/bottoms}
        final List<Object[]> uses = new ArrayList<>();                    // {InteractionUse, top, bottom}
        final List<Object[]> invariants = new ArrayList<>();              // {StateInvariant, y}
        final Map<Lifeline, Integer> createdAt = new HashMap<>(), destroyedAt = new HashMap<>();
        int cursor = FIRST - STEP;
    }

    private static void plan(Element container, Plan p, int depth) {
        for (InteractionFragment f : fragments(container)) {
            if (f instanceof MessageOccurrenceSpecification mos) {
                Message m = mos.getMessage();
                if (m == null) { p.cursor += 10; p.y.put(mos, p.cursor); continue; }
                int[] my = p.msgY.get(m);
                if (my == null) {
                    p.cursor += STEP;
                    boolean self = m.getSendEvent() instanceof OccurrenceSpecification s && m.getReceiveEvent() instanceof OccurrenceSpecification r
                        && s.getCovered() == r.getCovered();
                    my = new int[]{p.cursor, self ? p.cursor + 20 : p.cursor};
                    if (self) p.cursor += 20;
                    p.msgY.put(m, my);
                }
                p.y.put(mos, mos == m.getSendEvent() ? my[0] : my[1]);
                if (m.getMessageSort() == MessageSort.CREATE_MESSAGE_LITERAL && mos == m.getReceiveEvent()) p.createdAt.put(mos.getCovered(), my[1]);
                if (mos instanceof DestructionOccurrenceSpecification) p.destroyedAt.put(mos.getCovered(), my[1]);
            } else if (f instanceof OccurrenceSpecification os) {      // execution start/finish that is not a message end
                p.cursor += 12;
                p.y.put(os, p.cursor);
            } else if (f instanceof CombinedFragment cf) {
                p.cursor += 20;
                int top = p.cursor;
                p.cursor += 10;
                List<int[]> ops = new ArrayList<>();
                for (InteractionOperand op : cf.getOperands()) {
                    int ot = p.cursor;
                    p.cursor += 15;
                    plan(op, p, depth + 1);
                    p.cursor += 20;
                    ops.add(new int[]{ot, p.cursor});
                }
                p.cursor += 5;
                p.boxes.add(new Object[]{cf, top, p.cursor, depth, ops});
            } else if (f instanceof InteractionUse u) {
                p.cursor += 25;
                p.uses.add(new Object[]{u, p.cursor, p.cursor + 40});
                p.cursor += 40;
            } else if (f instanceof StateInvariant si) {
                p.cursor += 25;
                p.invariants.add(new Object[]{si, p.cursor});
                p.cursor += 20;
            }
        }
    }

    /** Lifelines touched inside a fragment (used when a combined fragment covers nothing explicitly). */
    private static Set<Lifeline> touched(CombinedFragment cf) {
        Set<Lifeline> s = new LinkedHashSet<>(cf.getCovereds());
        if (!s.isEmpty()) return s;
        for (InteractionOperand op : cf.getOperands()) for (InteractionFragment f : op.getFragments()) {
            s.addAll(f.getCovereds());
            if (f instanceof CombinedFragment in) s.addAll(touched(in));
        }
        return s;
    }

    /**
     * Draw interaction i on diagram d (d must be empty). narrative = text for the ICONIX left
     * margin (null = none). Returns a short summary.
     */
    /** The child view (or compartment child view) already showing element, or null. */
    static Node existingView(View parent, EObject element) {
        for (Object o : parent.getChildren()) if (o instanceof Node n) {
            if (n.getElement() == element) return n;
        }
        return null;
    }

    /** A name not yet used by another fragment of the interaction: "base", "base 2", "base 3"... */
    public static String unique(Element container, String base) {
        Interaction i = interactionOf(container);
        Set<String> used = new HashSet<>();
        for (Iterator<EObject> it = i.eAllContents(); it.hasNext(); ) if (it.next() instanceof NamedElement ne && ne.getName() != null) used.add(ne.getName());
        if (!used.contains(base)) return base;
        for (int n = 2; ; n++) if (!used.contains(base + " " + n)) return base + " " + n;
    }

    public static JsonObject render(Diagram d, Interaction i, String narrative) {
        Diagrams.Kind k = Behaviors.SEQUENCE;
        Plan p = new Plan();
        plan(i, p, 0);
        int bottom = p.cursor + STEP + 20;

        // Lifeline columns: width from the label ("name : Type"), left to right in model order.
        int left = narrative != null ? MARGIN_W + 30 : 20;
        Map<Lifeline, int[]> col = new LinkedHashMap<>();             // x, width
        int x = left;
        for (Lifeline l : i.getLifelines()) {
            Type t = typeOf(l);
            // Papyrus always prints "name : Type" in the header
            String label = l.getName() + (t != null ? " : " + t.getName() : "");
            int w = Behaviors.textWidth(label, 110, 8, 40);
            col.put(l, new int[]{x, w});
            x += w + GAP;
        }
        List<Comment> notes = new ArrayList<>();
        for (Comment c : i.getOwnedComments()) if (narrative == null || !narrative.equals(c.getBody())) notes.add(c);
        int width = x + (notes.isEmpty() ? 0 : NOTE_W + 20) + 20;

        Node frame = Behaviors.node(k, d, i, 10, 10, width, bottom + 60);
        View comp = Behaviors.compartment(frame, "SubfragmentCompartment");
        if (comp == null) comp = frame;

        // Lifelines with their execution bars, invariants and destruction marks.
        Map<Lifeline, Node> llView = new HashMap<>();
        for (var en : col.entrySet()) {
            Lifeline l = en.getKey();
            int top = p.createdAt.containsKey(l) ? p.createdAt.get(l) - HEAD / 2 : LL_TOP;
            int end = p.destroyedAt.getOrDefault(l, bottom);
            llView.put(l, Behaviors.node(k, comp, l, en.getValue()[0], top, en.getValue()[1], Math.max(end - top + 10, HEAD + 20)));
        }
        Map<ExecutionSpecification, Node> exView = new HashMap<>();
        for (Lifeline l : i.getLifelines()) {
            // nesting depth of each bar: bars that start inside another bar on the same lifeline shift right
            List<ExecutionSpecification> bars = new ArrayList<>();
            for (InteractionFragment f : l.getCoveredBys()) if (f instanceof ExecutionSpecification es) bars.add(es);
            for (ExecutionSpecification es : bars) {
                Integer y0 = p.y.get(es.getStart()), y1 = p.y.get(es.getFinish());
                if (y0 == null) continue;
                if (y1 == null || y1 < y0 + 10) y1 = (y1 == null ? y0 : y1) + 14;
                int nest = 0;
                for (ExecutionSpecification o : bars) if (o != es && p.y.get(o.getStart()) != null && p.y.get(o.getFinish()) != null
                        && p.y.get(o.getStart()) < y0 && p.y.get(o.getFinish()) >= y1) nest++;
                Node ln = llView.get(l);
                Bounds lb = (Bounds) ln.getLayoutConstraint();
                exView.put(es, Behaviors.node(k, ln, es, lb.getWidth() / 2 - 8 + nest * 6, y0 - lb.getY(), 16, y1 - y0));
            }
        }
        for (Object[] si : p.invariants) {
            StateInvariant s = (StateInvariant) si[0];
            Lifeline l = s.getCovereds().get(0);
            Node ln = llView.get(l); Bounds lb = (Bounds) ln.getLayoutConstraint();
            int w = Behaviors.textWidth(s.getName(), 80, 7, 30);
            Behaviors.node(k, ln, s, lb.getWidth() / 2 - w / 2, (int) si[1] - lb.getY(), w, 26);
        }
        for (var en : p.destroyedAt.entrySet()) {
            Lifeline l = en.getKey();
            for (InteractionFragment f : l.getCoveredBys()) if (f instanceof DestructionOccurrenceSpecification dos) {
                Node ln = llView.get(l); Bounds lb = (Bounds) ln.getLayoutConstraint();
                try { Behaviors.node(k, ln, dos, lb.getWidth() / 2 - 10, en.getValue() - lb.getY() - 10, 20, 20); } catch (Exception ignore) {}
            }
        }

        // Combined fragments (outer first so nesting looks right) and interaction uses.
        p.boxes.sort(Comparator.comparingInt(b -> (int) b[3]));
        for (Object[] b : p.boxes) {
            CombinedFragment cf = (CombinedFragment) b[0];
            int top = (int) b[1], bot = (int) b[2], depth = (int) b[3];
            int[] span = span(touched(cf), col, i);
            int pad = 30 - depth * 8;
            int bx = span[0] - pad, bw = span[1] - span[0] + 2 * pad;
            Node cfn = Behaviors.node(k, comp, cf, bx, top, bw, bot - top);
            @SuppressWarnings("unchecked") List<int[]> ops = (List<int[]>) b[4];
            View cfc = Behaviors.compartment(cfn, "SubfragmentCompartment");
            for (int o = 0; o < cf.getOperands().size() && o < ops.size(); o++) {
                int[] oy = ops.get(o);
                InteractionOperand op = cf.getOperands().get(o);
                // Papyrus may already have drawn the operand when it drew the fragment; reuse that
                // view (otherwise its default small size shrinks the frame) instead of adding a second one.
                Node on = existingView(cfc != null ? cfc : cfn, op);
                if (on != null) {
                    Bounds ob = NotationFactory.eINSTANCE.createBounds();
                    ob.setX(0); ob.setY(oy[0] - top); ob.setWidth(bw); ob.setHeight(oy[1] - oy[0]);
                    on.setLayoutConstraint(ob);
                } else try { Behaviors.node(k, cfc != null ? cfc : cfn, op, 0, oy[0] - top, bw, oy[1] - oy[0]); } catch (Exception ignore) {}
            }
        }
        for (Object[] u : p.uses) {
            InteractionUse iu = (InteractionUse) u[0];
            int[] span = span(new LinkedHashSet<>(iu.getCovereds().isEmpty() ? i.getLifelines() : iu.getCovereds()), col, i);
            Behaviors.node(k, comp, iu, span[0] - 15, (int) u[1], span[1] - span[0] + 30, (int) u[2] - (int) u[1]);
        }

        // Gates sit on the left border of the frame.
        Map<Gate, Node> gateView = new HashMap<>();
        for (Gate g : i.getFormalGates()) {
            int gy = 60;
            for (var en : p.msgY.entrySet()) if (en.getKey().getSendEvent() == g || en.getKey().getReceiveEvent() == g) { gy = en.getValue()[0] + 22; break; }
            try { gateView.put(g, Behaviors.node(k, frame, g, -8, gy, 16, 16)); } catch (Exception ignore) {}
        }

        // Messages: horizontal edges whose anchors sit at the message's row on each lifeline.
        int drawn = 0;
        for (var en : p.msgY.entrySet()) {
            Message m = en.getKey();
            int[] my = en.getValue();
            View sv = viewOf(m.getSendEvent(), llView, gateView), tv = viewOf(m.getReceiveEvent(), llView, gateView);
            if (sv == null || tv == null) continue;
            Edge e = Behaviors.edge(k, d, sv, tv, m);
            e.setSourceAnchor(anchorFor(sv, my[0], m.getSendEvent() instanceof Gate));
            boolean create = m.getMessageSort() == MessageSort.CREATE_MESSAGE_LITERAL;
            e.setTargetAnchor(create ? Behaviors.anchor(0, HEAD / 2.0 / ((Bounds) ((Node) tv).getLayoutConstraint()).getHeight())
                : anchorFor(tv, my[1], m.getReceiveEvent() instanceof Gate));
            RelativeBendpoints rb = NotationFactory.eINSTANCE.createRelativeBendpoints();
            rb.setPoints(List.of(new org.eclipse.gmf.runtime.notation.datatype.RelativeBendpoint(0, 0, 0, 0),
                new org.eclipse.gmf.runtime.notation.datatype.RelativeBendpoint(0, 0, 0, 0)));
            e.setBendpoints(rb);
            // Show "name(arguments)" only: without this Papyrus prints parameter and return types,
            // which read "<Undefined>" while the domain model has no types yet.
            org.eclipse.gmf.runtime.notation.StringListValueStyle mask = NotationFactory.eINSTANCE.createStringListValueStyle();
            mask.setName("maskLabel");
            mask.getStringListValue().addAll(List.of("name", "derived"));
            e.getStyles().add(mask);
            drawn++;
        }

        // ICONIX: the use case text runs down the left margin; other notes go on the right.
        if (narrative != null) {
            Comment c = null;
            for (Comment oc : i.getOwnedComments()) if (narrative.equals(oc.getBody())) c = oc;
            if (c != null) Behaviors.node(k, comp, c, 10, LL_TOP, MARGIN_W, Math.max(120, 30 + narrative.split("\n").length * 17));
        }
        int ny = LL_TOP;
        for (Comment c : notes) {
            int h = 40 + (c.getBody() == null ? 0 : c.getBody().length() / 28 * 15);
            Behaviors.node(k, comp, c, x, ny, NOTE_W, h);
            ny += h + 15;
        }
        JsonObject o = new JsonObject();
        o.addProperty("lifelines", col.size());
        o.addProperty("messages", drawn);
        o.addProperty("executions", exView.size());
        o.addProperty("combinedFragments", p.boxes.size());
        o.addProperty("interactionUses", p.uses.size());
        o.addProperty("stateInvariants", p.invariants.size());
        o.addProperty("gates", gateView.size());
        return o;
    }

    private static int[] span(Collection<Lifeline> lls, Map<Lifeline, int[]> col, Interaction i) {
        int a = Integer.MAX_VALUE, b = 0;
        for (Lifeline l : lls.isEmpty() ? i.getLifelines() : lls) {
            int[] c = col.get(l);
            if (c == null) continue;
            a = Math.min(a, c[0]); b = Math.max(b, c[0] + c[1]);
        }
        if (a == Integer.MAX_VALUE) { a = 20; b = 200; }
        return new int[]{a, b};
    }

    private static View viewOf(MessageEnd e, Map<Lifeline, Node> ll, Map<Gate, Node> gates) {
        if (e instanceof OccurrenceSpecification os) return ll.get(os.getCovered());
        if (e instanceof Gate g) return gates.get(g);
        return null;
    }

    private static IdentityAnchor anchorFor(View v, int y, boolean gate) {
        if (gate) return Behaviors.anchor(0.5, 0.5);
        Bounds b = (Bounds) ((Node) v).getLayoutConstraint();
        return Behaviors.anchor(0.5, (y - b.getY()) / (double) b.getHeight());
    }
}
