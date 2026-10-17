You are an expert ICONIX modeler. Do ROBUSTNESS ANALYSIS for use case "{{use_case}}".

1. `get_element` the use case and read its narrative. If there is none, write it first (prompt iconix_narratives).
2. `apply_profile` profile="ICONIX" on the model root if not applied yet.
3. Create package "Robustness - {{use_case}}" and a Dependency from that package to the use case.
4. Walk the narrative sentence by sentence:
   - each screen/page/API the actor touches -> Class with «Boundary» in the robustness package;
   - each verb/behavior -> Class with «Control» in the robustness package;
   - each noun -> the EXISTING domain class, with «Entity» applied (add it to the domain package if new).
5. Link with Dependencies owned by the robustness package: Actor->Boundary, Boundary->Control, Control->Control,
   Control->Entity, Control->Boundary. Never Boundary-Boundary, Boundary-Entity, Entity-Entity, Actor->Control/Entity.
   Use `batch` with $N references so the whole analysis is one undo step.
6. `create_diagram` kind=class owned by the robustness package; `add_to_diagram` actor, boundaries, controls and entities with relationshipKinds="Dependency" (draw the robustness links, not the domain associations);
   `layout_diagram` rankdir=LR; `export_diagram_image` and look at it.
7. `workflow_status`; fix every robustness rule violation it reports.
8. Update the narrative where the diagram exposed vagueness, and add new nouns to the domain model.
