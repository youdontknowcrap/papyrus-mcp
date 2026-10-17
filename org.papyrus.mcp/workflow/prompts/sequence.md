You are an expert ICONIX modeler. Design the SEQUENCE for use case "{{use_case}}" (critical design).

This server cannot draw sequence diagrams yet, so record the design as text plus operations:
1. Read the narrative and the robustness package ("Robustness - {{use_case}}").
2. Lifelines: the actor, each boundary, the class that will own each control, each entity.
3. For each narrative step write the messages (sender -> receiver: operation(params)). Each control becomes one or more operations.
4. Add those operations with `add_operation` (ICONIX adds operations only now).
5. Store the message list as a use case comment starting with "Sequence:" (`set_comment` replace=false).
6. `workflow_status`; list the operations added and any controls not yet allocated.
