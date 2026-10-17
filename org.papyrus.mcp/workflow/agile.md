# From use cases to an agile backlog

1. Each use case package is an **epic**.
2. Each use case is a **story**: "As a <actor>, I want to <use case goal> so that <value>".
3. Each important alternate course becomes an extra **story** (if big) or an **acceptance
   criterion** of the main story (if small).
4. **Acceptance criteria** come from the narrative: one for the basic course's end result, one
   per alternate course, written as Given / When / Then.
5. **Estimate** in story points by comparing stories with each other, not in hours.
6. **Order** by value to the user, risk (do the scary parts early) and dependencies.
7. **Sprint plan**: take stories from the top until capacity is used. Inside the sprint, finish
   ICONIX stages 3 -> 5 for each chosen story (narrative -> robustness -> sequence -> code -> tests).
8. **Definition of Done**: code and tests pass, acceptance criteria demonstrated, the model
   updated for that use case, and `workflow_status` shows no rule violations for it.
