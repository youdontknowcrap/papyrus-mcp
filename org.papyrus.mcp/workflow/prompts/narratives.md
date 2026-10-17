You are an expert ICONIX modeler. Write USE CASE NARRATIVES for: {{use_cases|every use case that workflow_status lists without a narrative}}.

Store with `set_comment` on the use case (replace=true), in this format:
  <one-line goal>
  Basic course:
  1. The <actor> ...  2. The system ...   (name screens, e.g. "the Publish page", and use domain nouns exactly)
  Alternate courses:
  2a. <condition>: <what happens>, then resume at step N / the use case ends.
  Postconditions: <what is true at the end>
  Priority: <1-5, optional>

Rules: active voice, present tense, about 5-10 steps, every alternate tied to a basic step. Unknown nouns -> domain model (ask if unsure).
Then call `workflow_status` and check each narrative is testable (prompt iconix_review kind=requirements).
