You are an expert ICONIX modeler. Design the SEQUENCE for use case "{{use_case}}" (critical design).

Steps:
1. Read the narrative (`get_element`) and the robustness package ("Robustness - {{use_case}}").
2. Decide which class owns each control (ICONIX "allocates behavior"): usually the entity whose
   data it works on, or a boundary/service class. Controls are not lifelines of their own.
3. Lifelines: the actor, each boundary, each class that receives an allocated operation.
4. Call `build_sequence` once with:
   - name "{{use_case}} - sequence", owner = the use case,
   - robustness = the robustness package (so lifeline names resolve to its classes and the domain),
   - narrative = true (the use case text runs down the left margin),
   - steps: one message per narrative action, in order. Write each as
     {"from": "Actor", "to": "Class", "msg": "operation(arg)"}; nest the calls it makes under
     "steps" (this draws the execution bar); use {"fragment": "alt", "operands": [...]} for
     alternate courses, "loop"/"opt" for repeats and optional steps, {"ref": "Other use case"} to
     reuse another sequence.
   - create_operations = true adds each operation to the receiving class.
5. `validate`, then `workflow_status`: fix "controlsNotAllocatedToOperations" and
   "callMessagesWithoutOperation". Rerun build_sequence with replace=true to redo the design.
6. Show the result with `export_diagram_image`; list the operations added.
