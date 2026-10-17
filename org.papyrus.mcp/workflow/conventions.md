# How the workflow is stored in a Papyrus model

`workflow_status` relies on these conventions, so tools and people find each ICONIX artifact
without guessing.

| Artifact | Convention |
|---|---|
| Domain model | Plain `Class`es (no «Boundary»/«Control») outside robustness packages. Generalizations and Associations (aggregation shared/composite on the PART end for has-a). |
| Actors | `Actor` elements. |
| Use cases | `UseCase` elements in packages (one package = one epic), linked to actors by Associations. |
| Narrative | An owned `Comment` of the use case whose text contains `Basic course` and, if any, `Alternate course`. Optional `Postconditions:` and `Priority: <n>` lines. |
| Robustness package | A `Package` with a `Dependency` from the package to the use case. A package named exactly "Robustness - <use case name>" also counts. |
| Robustness objects | Classes with «Boundary» / «Control» (ICONIX profile: `apply_profile profile="ICONIX"`) owned by the robustness package. Entities = domain classes, ideally with «Entity» applied. |
| Robustness links | `Dependency` (or Association) owned by the robustness package: Actor->Boundary, Boundary->Control, Control->Control, Control->Entity, Control->Boundary. |
| Robustness diagram | A class diagram owned by the robustness package. |
| Sequence | An `Interaction` owned by the use case, or a use case comment starting with `Sequence:`. |
