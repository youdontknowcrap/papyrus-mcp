# ICONIX + agile workflow (checklist)

ICONIX is a short, use-case-driven path from requirements to code: four modeling steps, with a
review after each milestone. In an agile team each sprint runs this loop only for the few use
cases chosen for that sprint. The model does not have to be complete before coding starts.

Call the tool `workflow_status` at any time: it inspects the model and tells you which stage is
done and what is missing. Each stage below has a matching MCP prompt (in brackets).

## Stage 1: Domain model, the vocabulary  [iconix_domain_model]
- [ ] Find the nouns of the problem: real-world things, not database tables or screens.
- [ ] Draw classes in a domain package. Add generalization (is-a) and aggregation (has-a).
- [ ] No operations yet. Attributes only when obvious.
- [ ] Each class has a one-line comment saying what it is.

## Stage 2: Use cases, what people want to achieve  [iconix_use_cases]
- [ ] Actors are outside the system: people, roles, or other systems.
- [ ] Use cases are verb-noun goals, about one sitting each ("Publish a Show").
- [ ] Group use cases in packages; each package becomes an agile epic.
- [ ] Every use case is associated with at least one actor (or included by another use case).

## Stage 3: Narratives, then the Requirements Review  [iconix_narratives, iconix_review kind=requirements]
- [ ] Each use case owns a comment with "Basic course:" (numbered sunny-day steps: the user
      does X, the system does Y) and "Alternate courses:" (what can go wrong or differ).
      Use the domain model's nouns and name the screens.
- [ ] Requirements Review: every use case written, consistent with the domain model, testable.

## Stage 4: Robustness analysis, then the Preliminary Design Review  [iconix_robustness, iconix_review kind=preliminary]
- [ ] For each use case in scope: a robustness package linked to the use case by a Dependency
      (package -> use case), with a class diagram inside it.
- [ ] Objects: «Boundary» (screens, pages, API endpoints), «Control» (verbs, logic),
      «Entity» (domain classes). Walk the narrative sentence by sentence.
- [ ] Rules: actor <-> boundary only; boundary <-> control; control <-> entity;
      control <-> control. Never noun <-> noun (boundary-boundary, boundary-entity, entity-entity).
- [ ] Every entity is a domain-model class. Add newly discovered nouns to the domain model.
- [ ] Rewrite the narrative where the robustness diagram showed it was vague.
- [ ] Preliminary Design Review: narratives, robustness and domain model agree.

## Stage 5: Sequence diagrams, then the Critical Design Review  [iconix_sequence, iconix_review kind=critical]
- [ ] One sequence per use case: actor, boundaries, controls, entities become lifelines.
- [ ] Controls turn into operations on classes. Now add the operations.
- [ ] Critical Design Review: every message is an operation; the class model is ready to code.

## Agile mapping  [agile_backlog_from_usecases, sprint_plan]
- Epic = use case package. Story = use case (basic course). Big alternates = extra stories.
- Acceptance criteria = the narrative's postconditions plus one check per alternate course.
- A sprint takes the top stories by value and risk and runs stages 3 -> 5 for just those, then
  code and tests. Run `workflow_status` at the start and end of every sprint.
