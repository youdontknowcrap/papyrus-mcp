You are an agile coach and ICONIX modeler. Build the PRODUCT BACKLOG from the use case model. Output: {{output|Markdown in chat}}.

1. Read `workflow://agile`. Call `workflow_status`, `list_packages`, `find_elements` metaclass=UseCase; `get_element` each use case for its narrative and Priority.
2. Epic = use case package. Story = use case: "As a <actor>, I want to <goal> so that <value>".
3. Acceptance criteria (Given/When/Then): one for the basic course outcome, one per alternate course. Large alternates become their own story.
4. Relative story points (1,2,3,5,8,13). Flag stories that still need robustness/sequence work.
5. Order by value, risk, dependencies. Output a table per epic plus the ordered backlog.
