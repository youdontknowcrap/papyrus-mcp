package org.papyrus.mcp.tools;

import com.google.gson.*;
import java.util.*;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gmf.runtime.notation.Diagram;
import org.eclipse.uml2.uml.*;
import org.eclipse.uml2.uml.Class;
import org.eclipse.uml2.uml.Package;
import org.papyrus.mcp.server.Session;
import org.papyrus.mcp.util.ElementJson;
import org.papyrus.mcp.util.ModelLocator;

/**
 * workflow_status: inspects the model and reports how far the ICONIX workflow has got, using the
 * conventions in workflow/conventions.md (resource workflow://iconix/conventions).
 *
 * Plain words: it answers "what stage are we at, and what is still missing?" It lists use cases
 * with no narrative, use cases with no robustness diagram, robustness entities that are not in
 * the domain model, and robustness links that break the ICONIX rules (actors only talk to
 * boundaries, boundaries talk to controls, nouns never talk directly to nouns).
 * It only reads the model; it never changes it.
 */
public class WorkflowStatusTool extends ReadTool {
    public String name() { return "workflow_status"; }
    public String description() {
        return "Report ICONIX workflow progress for the model: stage done/missing, use cases without narratives / actors / "
             + "robustness / sequence, robustness entities not in the domain model, and robustness rule violations "
             + "(actor->boundary only, boundary->control, no noun-noun links). Read-only. See resource workflow://iconix.";
    }
    public JsonObject inputSchema() {
        JsonObject s = baseSchema();
        prop(s, "package", "string", "Optional id/qualified name: only check use cases inside this package (e.g. one epic).");
        prop(s, "limit", "integer", "Max names listed per finding (default 50).");
        return s;
    }

    /** The four kinds of robustness object, plus actors and "other". */
    enum Kind { ACTOR, BOUNDARY, CONTROL, ENTITY, OTHER }

    static boolean has(Element e, String stereo) {
        for (Stereotype s : e.getAppliedStereotypes()) if (s.getName().equals(stereo)) return true;
        return false;
    }

    /** True for a plain UML Class (not a Behavior, Stereotype, Component, ... which are subclasses). */
    static boolean plainClass(EObject e) { return e.eClass() == UMLPackage.Literals.CLASS; }

    protected JsonObject read(ModelLocator.Active m, JsonObject a, Session s) {
        int limit = integer(a, "limit", 50);
        Package root = m.root();
        Element scope = str(a, "package") == null ? root : ElementJson.resolve(m, str(a, "package"));

        // ---- 1. Collect everything once -------------------------------------------------------
        List<UseCase> useCases = new ArrayList<>();
        List<Actor> actors = new ArrayList<>();
        List<Class> classes = new ArrayList<>();
        List<Package> packages = new ArrayList<>();
        for (Element e : root.allOwnedElements()) {
            if (e instanceof UseCase uc) { if (scope == root || isInside(uc, scope)) useCases.add(uc); }
            else if (e instanceof Actor ac) actors.add(ac);
            else if (plainClass(e)) classes.add((Class) e);
            if (e instanceof Package p && !(e instanceof Profile)) packages.add(p);
        }

        // ---- 2. Find robustness packages: Package --Dependency--> UseCase, or name convention ---
        Map<UseCase, Package> robustness = new LinkedHashMap<>();
        Set<Package> robustPkgs = new LinkedHashSet<>();
        for (Package p : packages) {
            for (Dependency d : p.getClientDependencies())
                for (NamedElement sup : d.getSuppliers())
                    if (sup instanceof UseCase uc) { robustness.putIfAbsent(uc, p); robustPkgs.add(p); }
            String n = p.getName();
            if (n != null && n.startsWith("Robustness - ")) {
                robustPkgs.add(p);
                for (Element e : root.allOwnedElements())
                    if (e instanceof UseCase uc && n.substring(13).trim().equals(uc.getName())) robustness.putIfAbsent(uc, p);
            }
        }

        // ---- 3. Domain classes = plain classes, not Boundary/Control, not owned by a robustness package
        Set<Class> domain = new LinkedHashSet<>();
        for (Class c : classes)
            if (!has(c, "Boundary") && !has(c, "Control") && !insideAny(c, robustPkgs)) domain.add(c);

        // ---- 4. Diagrams per owner (GMF notation), so we can say "has a robustness diagram" ----
        Set<EObject> diagramOwners = new HashSet<>();
        for (Resource r : m.modelSet().getResources()) {
            if (!"notation".equals(r.getURI().fileExtension())) continue;
            for (EObject o : r.getContents()) if (o instanceof Diagram d) diagramOwners.add(diagramOwner(d));
        }

        // ---- 5. Per use case findings ---------------------------------------------------------
        JsonArray noNarrative = new JsonArray(), noAlternate = new JsonArray(), noActor = new JsonArray(),
                  noRobust = new JsonArray(), robustNoDiagram = new JsonArray(), noSequence = new JsonArray();
        int narrated = 0, robusted = 0, sequenced = 0;
        for (UseCase uc : useCases) {
            String text = commentsText(uc).toLowerCase(Locale.ROOT);
            boolean basic = text.contains("basic course");
            if (basic) narrated++; else add(noNarrative, uc, limit);
            if (basic && !text.contains("alternate course")) add(noAlternate, uc, limit);
            if (!hasActor(uc)) add(noActor, uc, limit);
            Package rp = robustness.get(uc);
            if (rp == null) add(noRobust, uc, limit);
            else { robusted++; if (!diagramOwners.contains(rp)) add(robustNoDiagram, uc, limit); }
            boolean seq = text.contains("sequence:");
            for (Behavior b : uc.getOwnedBehaviors()) if (b instanceof Interaction) seq = true;
            if (seq) sequenced++; else add(noSequence, uc, limit);
        }

        // ---- 6. Robustness checks: entities and link rules ------------------------------------
        JsonArray entityNotDomain = new JsonArray(), unstereotyped = new JsonArray(), violations = new JsonArray();
        int links = 0;
        for (Class c : classes)                       // «Entity» on something that is not a domain class
            if (has(c, "Entity") && !domain.contains(c)) add(entityNotDomain, c, limit);
        for (Package rp : robustPkgs)                  // robustness classes must be «Boundary» or «Control»
            for (Element e : rp.allOwnedElements())
                if (plainClass(e) && !has(e, "Boundary") && !has(e, "Control")) add(unstereotyped, (NamedElement) e, limit);
        // Links: every Dependency/Association with at least one end that is a robustness object
        // (a «Boundary»/«Control» or anything owned by a robustness package), wherever the link
        // itself is stored. Papyrus stores a Dependency next to its client, so an Actor->Boundary
        // link usually lives in the actors' package, not in the robustness package.
        for (Element e : root.allOwnedElements()) {
            for (NamedElement[] pr : endsOf(e)) {
                Package rp = robustPkgOf(pr[0], robustPkgs);
                if (rp == null) rp = robustPkgOf(pr[1], robustPkgs);
                if (rp == null) continue;                // not part of any robustness analysis
                links++;
                Kind k1 = kind(pr[0], domain), k2 = kind(pr[1], domain);
                String why = rule(k1, k2);
                if (why != null && violations.size() < limit) {
                    JsonObject v = new JsonObject();
                    v.add("link", ElementJson.ref(e));
                    v.addProperty("from", pr[0].getName() + " (" + k1.name().toLowerCase() + ")");
                    v.addProperty("to", pr[1].getName() + " (" + k2.name().toLowerCase() + ")");
                    v.addProperty("robustnessPackage", rp.getName());
                    v.addProperty("rule", why);
                    violations.add(v);
                }
            }
        }
        boolean profileApplied = false;
        for (Profile p : root.getAllAppliedProfiles()) if ("ICONIX".equals(p.getName())) profileApplied = true;

        // ---- 7. Stage summary in plain words --------------------------------------------------
        int domainOps = 0;
        for (Class c : domain) domainOps += c.getOwnedOperations().size();
        JsonArray stages = new JsonArray();
        stage(stages, "1 domain model", !domain.isEmpty(), domain.size() + " domain classes"
            + (domainOps > 0 && sequenced == 0 ? "; note: " + domainOps + " operations exist before any sequence design (ICONIX adds operations in stage 5)" : ""));
        stage(stages, "2 use cases", !useCases.isEmpty() && noActor.isEmpty(),
            useCases.size() + " use cases, " + actors.size() + " actors, " + noActor.size() + " use cases without an actor");
        stage(stages, "3 narratives (requirements review)", !useCases.isEmpty() && narrated == useCases.size(),
            narrated + "/" + useCases.size() + " use cases have a narrative; " + noAlternate.size() + " lack alternate courses");
        stage(stages, "4 robustness (preliminary design review)", robusted > 0 && violations.isEmpty() && entityNotDomain.isEmpty(),
            robusted + "/" + useCases.size() + " use cases have robustness analysis; " + violations.size() + " rule violations"
            + (profileApplied ? "" : "; ICONIX profile not applied (apply_profile profile=ICONIX)"));
        stage(stages, "5 sequence (critical design review)", sequenced > 0 && sequenced >= robusted,
            sequenced + "/" + useCases.size() + " use cases have a sequence design");

        JsonObject out = new JsonObject();
        out.add("stages", stages);
        out.addProperty("next", next(domain.isEmpty(), useCases.isEmpty(), noActor.size(), noNarrative.size(),
            robusted, violations.size() + entityNotDomain.size() + unstereotyped.size(), sequenced));
        JsonObject f = new JsonObject();
        f.add("useCasesWithoutNarrative", noNarrative);
        f.add("narrativesWithoutAlternateCourses", noAlternate);
        f.add("useCasesWithoutActor", noActor);
        f.add("useCasesWithoutRobustness", noRobust);
        f.add("robustnessWithoutDiagram", robustNoDiagram);
        f.add("useCasesWithoutSequence", noSequence);
        f.add("entitiesNotInDomainModel", entityNotDomain);
        f.add("unstereotypedClassesInRobustness", unstereotyped);
        f.add("robustnessRuleViolations", violations);
        out.add("findings", f);
        JsonObject c = new JsonObject();
        c.addProperty("useCases", useCases.size());
        c.addProperty("actors", actors.size());
        c.addProperty("domainClasses", domain.size());
        c.addProperty("robustnessPackages", robustPkgs.size());
        c.addProperty("robustnessLinksChecked", links);
        c.addProperty("iconixProfileApplied", profileApplied);
        out.add("counts", c);
        return out;
    }

    /** The one-sentence "what to do next" a bot should follow (the first unfinished stage wins). */
    static String next(boolean noDomain, boolean noUc, int noActor, int noNarr, int robusted, int problems, int seq) {
        if (noDomain) return "Build the domain model (prompt iconix_domain_model).";
        if (noUc) return "Identify actors and use cases (prompt iconix_use_cases).";
        if (noActor > 0) return "Connect every use case to an actor (see useCasesWithoutActor).";
        if (noNarr > 0) return "Write narratives (prompt iconix_narratives), then run iconix_review kind=requirements.";
        if (robusted == 0) return "Pick the top use cases for this sprint and do robustness analysis (prompt iconix_robustness).";
        if (problems > 0) return "Fix the robustness findings, then run iconix_review kind=preliminary.";
        if (seq < robusted) return "Design sequences for the analysed use cases (prompt iconix_sequence), then iconix_review kind=critical.";
        return "In-scope use cases are designed. Plan the next sprint (prompt sprint_plan).";
    }

    /** Returns null if the link is allowed, else the rule it breaks (order-insensitive). */
    static String rule(Kind a, Kind b) {
        if (a == Kind.OTHER || b == Kind.OTHER) return null; // not a robustness object (e.g. the use case)
        EnumSet<Kind> s = EnumSet.of(a, b);
        if (s.contains(Kind.ACTOR)) return s.equals(EnumSet.of(Kind.ACTOR, Kind.BOUNDARY)) ? null
            : "actors may only talk to boundaries";
        if (a == Kind.BOUNDARY && b == Kind.BOUNDARY) return "boundary-boundary: nouns never talk to nouns (put a control between)";
        if (s.equals(EnumSet.of(Kind.BOUNDARY, Kind.ENTITY))) return "boundary-entity: nouns never talk to nouns (put a control between)";
        if (a == Kind.ENTITY && b == Kind.ENTITY) return "entity-entity: nouns never talk to nouns (that relation belongs in the domain model)";
        return null; // boundary-control, control-control, control-entity
    }

    static Kind kind(NamedElement e, Set<Class> domain) {
        if (e instanceof Actor) return Kind.ACTOR;
        if (has(e, "Boundary")) return Kind.BOUNDARY;
        if (has(e, "Control")) return Kind.CONTROL;
        if (has(e, "Entity") || (e instanceof Class c && domain.contains(c))) return Kind.ENTITY;
        return Kind.OTHER;
    }

    /** (source, target) pairs of a Dependency or binary Association; empty for anything else. */
    static List<NamedElement[]> endsOf(Element e) {
        List<NamedElement[]> r = new ArrayList<>();
        if (e instanceof Dependency d)
            for (NamedElement c : d.getClients()) for (NamedElement t : d.getSuppliers()) r.add(new NamedElement[]{c, t});
        else if (e instanceof Association as && as.getMemberEnds().size() == 2
                && as.getMemberEnds().get(0).getType() != null && as.getMemberEnds().get(1).getType() != null)
            r.add(new NamedElement[]{as.getMemberEnds().get(0).getType(), as.getMemberEnds().get(1).getType()});
        return r;
    }

    static boolean hasActor(UseCase uc) {
        for (Association as : uc.getAssociations())
            for (Property p : as.getMemberEnds()) if (p.getType() instanceof Actor) return true;
        for (Element e : uc.getModel() == null ? List.<Element>of() : uc.getModel().allOwnedElements())
            if (e instanceof Include in && in.getAddition() == uc) return true;     // included by another use case
        return false;
    }

    static String commentsText(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Comment c : e.getOwnedComments()) if (c.getBody() != null) sb.append(c.getBody()).append('\n');
        return sb.toString();
    }

    /** The robustness package an element belongs to: its owner chain, or (for B/C stereotypes) null if loose. */
    static Package robustPkgOf(Element e, Collection<Package> ps) {
        for (Element o = e.getOwner(); o != null; o = o.getOwner()) if (o instanceof Package p && ps.contains(p)) return p;
        return null;
    }

    static boolean isInside(Element e, Element container) {
        for (Element o = e.getOwner(); o != null; o = o.getOwner()) if (o == container) return true;
        return false;
    }

    static boolean insideAny(Element e, Collection<Package> ps) {
        for (Element o = e.getOwner(); o != null; o = o.getOwner()) if (ps.contains(o)) return true;
        return false;
    }

    /** Papyrus records the real owner in PapyrusDiagramStyle.owner; fall back to the context element. */
    static EObject diagramOwner(Diagram d) {
        for (Object st : d.getStyles()) {
            EObject so = (EObject) st;
            if (so.eClass().getName().equals("PapyrusDiagramStyle")) {
                EStructuralFeature of = so.eClass().getEStructuralFeature("owner");
                if (of != null && so.eGet(of) instanceof EObject ow) return ow;
            }
        }
        return d.getElement();
    }

    static void add(JsonArray a, NamedElement e, int limit) {
        if (a.size() >= limit) return;
        a.add(e.getQualifiedName() != null ? e.getQualifiedName() : e.getName());
    }

    static void stage(JsonArray a, String name, boolean done, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("stage", name);
        o.addProperty("status", done ? "done" : "missing");
        o.addProperty("detail", detail);
        a.add(o);
    }
}
