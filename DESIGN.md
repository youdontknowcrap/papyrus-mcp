# Papyrus MCP: design (v1)

Target: Papyrus-Desktop 7.1.0 (Eclipse 2025-06, Java 21, Sirius 7.4.13, GMF classic diagrams).
Goal: an Eclipse plugin that runs an MCP server inside Papyrus, so an AI bot can read and edit the
**open** model live. Every edit is undoable with Ctrl+Z.

## 1. Architecture

```
AI client ──HTTP POST /mcp (JSON-RPC 2.0)──> McpHttpServer (JDK HttpServer, 127.0.0.1:7341)
                                               │  4 daemon HTTP threads: parse, auth, serialize
                                               ▼
                                         ToolRegistry ── Tool.execute()
                                               │  UiSync.call(...)  = Display.syncExec
                                               ▼
                         SWT UI thread: ModelLocator → IMultiDiagramEditor → ServicesRegistry
                                               │       → ModelSet + TransactionalEditingDomain
                                               ▼
                    reads: domain.runExclusive(...)     writes: domain.getCommandStack().execute(cmd)
```

### 1.1 Transport: a small embedded server, not the MCP Java SDK
* **Chosen:** the JDK's `com.sun.net.httpserver` plus **Gson** (already ships with Papyrus as
  `com.google.gson_2.13.1`). Nothing extra to bundle. About 200 lines.
* **Why not the MCP Java SDK (io.modelcontextprotocol.sdk):** it needs Jackson, Project Reactor and
  a servlet transport. Those are not OSGi bundles in Papyrus 7.1, so we would have to embed them
  (fat jar plus `Bundle-ClassPath`), which risks classloader conflicts. We can switch later if
  we need streaming or sampling.
* Jetty 12 is in the install too (`org.eclipse.jetty.*_12.0.21`). It is a fallback if the JDK server
  is ever not enough.
* **Protocol:** MCP **2025-06-18 Streamable HTTP**. `POST /mcp` takes a request or a batch.
  * A request gets `200 application/json`.
  * A notification gets `202`.
  * `GET` gets `405`. The spec allows this when the server has no SSE stream.
  * `DELETE` ends the session.
  * `initialize` returns an `Mcp-Session-Id`.
* No SSE in v1. Every tool finishes quickly and returns one JSON response. Phase 5 can add SSE
  progress events for long layouts and validations.

### 1.2 Threading
* HTTP threads never touch EMF or the workbench directly. `UiSync.call` runs the tool body on the
  UI thread with `Display.syncExec` and passes back its result or its exception.
* **Reads** run inside `TransactionalEditingDomain.runExclusive`, so a read never sees half of a write.
* **Writes** go through `domain.getCommandStack().execute(...)` on the UI thread. GMF edit parts then
  refresh right away, and the Papyrus undo history is updated.
* Each `tools/call` that writes produces **exactly one** command on the stack (it can be a
  `CompoundCommand`), so one Ctrl+Z undoes one bot action.
* We plan a timeout wrapper for v1.1: use `asyncExec` with a `CompletableFuture` and a 30 s limit,
  then return an error if a modal dialog blocks the UI thread.

### 1.3 Finding the active model (`ModelLocator`, implemented)
1. Use the active editor if it is an `org.eclipse.papyrus.infra.ui.editor.IMultiDiagramEditor`.
2. Otherwise use the first restored Papyrus editor in any window.
3. From the editor, `getServicesRegistry()` gives:
   * `ModelSet` (the ResourceSet holding .di, .uml, .notation and .aird)
   * the `TransactionalEditingDomain`
   * `UmlModel.lookupRoot()`, the root `Package`
4. Phase 2 adds an optional `model` argument to every tool (resource URI or file name) and a
   `list_open_models` tool, for when more than one model is open.
5. **Element ids** are the XMI ids (`resource.getURIFragment(e)`). They stay the same across save
   and reload, and they match the ids in the existing `papyrus-out` files.

## 2. Undo semantics
* Write tools build commands through the **Papyrus Element Edit Service** (`ElementEditServiceUtils
  .getCommandProvider(container).getEditCommand(new CreateElementRequest(domain, container,
  UMLElementTypes.CLASS))`), then wrap them with `GMFtoEMFCommandWrapper`. This keeps Papyrus
  advice running: default names, stereotype rules, and cleanup of related elements and views on
  delete. It also matches what the palette and Model Explorer do.
* For plain property sets with no edit-helper (for example `set_property` on a simple attribute),
  use a `SetRequest` through the same service. Use raw EMF `SetCommand` only as a fallback.
* Each tool call becomes one compound command with a readable label such as "MCP: create Class
  Foo". Papyrus shows it in the Edit menu as "Undo MCP: create Class Foo".
* The `undo` and `redo` tools call `commandStack.undo()` / `redo()`. They only undo when the top
  of the stack is an MCP command, unless `force=true`. This stops a bot from undoing the user's
  own edits.
* A command that fails or throws is rolled back by the transaction, and the tool returns
  `isError:true` with the message.

## 3. Diagram creation and layout (GMF classic)
What we already know from `/workspace/mudhut/papyrus-out` (`gen_notation.py`,
`papy_templates.json`, `gmf_report.json`, all 15 diagrams checked in Papyrus 7.1):
* Diagram types: `PapyrusUMLClassDiagram`, `UseCase`, `PapyrusUMLActivityDiagram`,
  `PapyrusUMLStateMachineDiagram`, `PapyrusUMLComponentDiagram`, and others. Each needs a
  `PapyrusDiagramStyle` with `diagramKindId` and `owner`, plus
  `diagram_compatibility_version=1.4.0`.
* View types are the Papyrus 7 semantic hints, not numeric visualIDs: `Class_Shape`,
  `Actor_Shape`, `UseCase_Shape`, `Association_Edge`, and so on. `papy_templates.json` has the
  exact child compartments and labels for each shape in each diagram kind.
* `.di` pages are optional. Papyrus finds GMF diagrams through `PapyrusDiagramStyle.owner`.

**At runtime we do not write the notation by hand** (that was the offline generator). Instead:
* **create_diagram:** use the Papyrus `ViewPrototype` / `PolicyChecker` API (`org.eclipse.papyrus
  .infra.gmfdiag.common` and `infra.viewpoints.policy`) with the diagram kind id, for example
  `org.eclipse.papyrus.uml.diagram.class`, plus owner and name. This creates the Diagram, its
  style, and the sash page, all inside a command.
* **add_to_diagram (elements):** open or activate the diagram, get its `DiagramEditPart`, and send
  a `CreateViewRequest` with `ViewDescriptor(new EObjectAdapter(elem), Node.class,
  "Class_Shape"/null, prefHint)`. Papyrus works out the hint if we leave it out. Execute through
  the diagram's `DiagramCommandStack`, which shares the same TransactionalEditingDomain, so it is
  still one undo step.
* **Edges:** for elements already on the diagram, use Papyrus's
  `ShowHideRelatedLinkEditPolicy` / `ShowHideRelatedLinkRequest`, which draws every existing
  relationship between the shown nodes (`add_relationships_to_diagram`).
* **Layout:** there are two modes.
  * `layout="gmf"` runs the GMF `ArrangeRequest(ActionIds.ACTION_ARRANGE_ALL)`. It is built in and
    gives fair results.
  * `layout="graphviz"` reuses the `gen_notation.py` approach: call `dot -Tplain` (it is installed
    on the box; on the user's machine we check for it and fall back to gmf), then apply the
    positions with `SetBoundsCommand`, all in one compound command. This gave the good results in
    papyrus-out (LR rank for domain diagrams, a grid for use cases).
* Shapes can also be given explicit `x,y,w,h`.
* Sirius (`.aird`) diagrams are out of scope for v1. The tools refuse Sirius representations
  with a clear message.

## 4. Tool list (v1)
`id` = XMI id. Most tools accept an optional `model` argument (Phase 2).

| # | Tool | Params | Kind |
|---|---|---|---|
| 1 | `ping` ✅ | – | read |
| 2 | `list_packages` ✅ | `model?` | read |
| 3 | `list_open_models` ✅ | – | read |
| 4 | `get_element` ✅ | `id` or `qualifiedName`; `depth?=1`; `include?=[properties,comments,stereotypes,relationships]` | read |
| 5 | `list_elements` ✅ | `container`(id/qn), `metaclass?`, `recursive?=false`, `limit?=200`, `offset?` | read |
| 6 | `find_elements` ✅ | `query` (name substring or regex), `metaclass?`, `stereotype?`, `limit?` | read |
| 7 | `get_relationships` ✅ | `id`, `kind?` (Association/Generalization/Dependency/...), `direction?` | read |
| 8 | `get_stereotypes` ✅ | `id` → applied stereotypes plus tagged values; or `profile?` → what can be applied | read |
| 9 | `list_diagrams` ✅ | `owner?` → id, name, type, owner, elementCount (GMF and Sirius, flagged) | read |
| 10 | `create_element` ✅ | `container`, `metaclass` (Class, Interface, Enumeration, Package, Actor, UseCase, Component, DataType, Signal, Activity, StateMachine, Comment, Constraint...), `name`, `properties?` | write |
| 11 | `create_relationship` ✅ | `metaclass` (Association, Generalization, Dependency, Realization, Usage, Include, Extend, InterfaceRealization...), `source`, `target`, `name?`, `ends?{navigable, aggregation, multiplicity}` | write |
| 12 | `add_property` / `add_operation` ✅ | `classifier`, `name`, `type?`, `multiplicity?`, `visibility?`, `params?`, `returnType?` | write |
| 13 | `add_enumeration_literal` ✅ | `enumeration`, `name` | write |
| 14 | `update_element` ✅ | `id`, `set{feature: value}` (name, visibility, isAbstract, type, lower/upper, body...) | write |
| 15 | `set_comment` ✅ | `id`, `body`, `replace?` (annotates the element with an owned Comment) | write |
| 16 | `apply_stereotype` / `unapply_stereotype` ✅ | `id`, `stereotype` (qn), `values?{tag:value}` | write |
| 17 | `apply_profile` ✅ | `package`, `profile` (URI or registered name) | write |
| 18 | `move_element` ✅ | `id`, `newContainer` | write |
| 19 | `delete_elements` ✅ | `ids[]`, `dryRun?` (dry run reports what would be deleted, including views) | write |
| 20 | `batch` ✅ | `operations[]` (any write tools) → **one** undo step, ids from earlier ops usable as `$1`,`$2` | write |
| 21 | `create_diagram` ✅ | `kind` (class, usecase, activity, statemachine, component, package), `owner`, `name`, `open?=true` | write |
| 22 | `add_to_diagram` ✅ | `diagram`, `elements[]` (`{id, x?,y?,w?,h?}`), `withRelationships?=true` | write |
| 23 | `remove_from_diagram` ✅ | `diagram`, `elements[]` (view only; the model is untouched) | write |
| 24 | `layout_diagram` ✅ | `diagram`, `algorithm?=gmf\|graphviz`, `rankdir?=LR` | write |
| 25 | `export_diagram_image` ✅ | `diagram`, `format?=png\|svg` → base64 image content (so the bot can look at its work) | read |
| 26 | `validate` ✅ | `id?` (default root), `severity?` → list of `{severity, message, elementId, source}` | read* |
| 27 | `undo` / `redo` ✅ | `force?=false` | write |
| 28 | `save` ✅ | `model?` → `editor.doSave()` | write |
| 29 | `get_selection` ✅ | – → the elements selected in the Model Explorer or diagram, so the user can point at things | read |

| 30 | `get_changelog` ✅ | `since_seq?` (omit = this session's cursor), `client?` | read |

*`validate` changes only markers, never the model.

**All write tools** also accept `expected_version?` (integer) and `expected_etags?` (`{id: etag}`).
See §10.

## 5. Validation
* Use `org.eclipse.papyrus.uml.service.validation`, or EMF `Diagnostician` directly through
  Papyrus's `EValidator` registry on the ModelSet, which includes the EMF Validation framework
  constraints and the UML2 OCL constraints.
* Run it on the UI thread inside `runExclusive`. Return a flat list. Optionally
  (`createMarkers=true`) also create Problems-view markers the same way Papyrus's own
  "Validate Model" action does.

## 6. Security
* Bind only to `InetAddress.getLoopbackAddress()` (127.0.0.1). Never 0.0.0.0. Default port is
  **7341**. You can change it under Preferences > Papyrus MCP, or with `-Dpapyrus.mcp.port`.
* **Origin check** (the MCP spec requires DNS-rebinding protection): requests with no Origin, or
  an http(s) Origin on localhost/127.0.0.1/[::1], are allowed. All others get 403.
* **Optional Bearer token.** It is compared in constant time. If it is empty, no token is needed,
  but the loopback-only and Origin rules still apply. We recommend setting one: any local process
  can otherwise reach the port.
* A **read-only mode** preference refuses all write tools.
* There is no file-system tool. The bot cannot open or save arbitrary paths, only `save` the
  already-open model. This also means the user's model files are only changed when the user or the
  bot saves.
* Phase 5: a status-bar indicator that shows the server is on, and an optional "confirm each
  write" dialog.

## 7. Build
* **Now:** `org.papyrus.mcp/build.sh` uses plain `javac` against the installed
  `Papyrus/plugins/*.jar` and produces a dropins jar. This works today.
* **Later:** Maven Tycho 4.x with a target platform of the Papyrus 7.1 p2 repo
  (`https://download.eclipse.org/modeling/mdt/papyrus/papyrus-desktop/updates/releases/2025-06/`)
  plus Eclipse 2025-06, and a feature plus update-site project, so it can be installed with
  Help > Install New Software.
* We add bundles to `Require-Bundle` only as the phases need them:
  `org.eclipse.papyrus.infra.services.edit`, `org.eclipse.papyrus.uml.service.types`,
  `org.eclipse.gmf.runtime.diagram.ui`, `org.eclipse.papyrus.infra.gmfdiag.common`,
  `org.eclipse.papyrus.uml.service.validation`, `org.eclipse.emf.validation`.

## 8. Phased build order
1. **Phase 1 (done):** server, auth, preferences, ModelLocator, `ping`, `list_packages`.
2. **Phase 2 (done, tested live 2026-10-16):** `get_element`, `list_elements`, `find_elements`,
   `get_relationships`, `get_stereotypes`, `list_diagrams`, `get_selection`, `list_open_models`.
   Also the multi-client changelog (`get_changelog`), sessions, model version and etags, and
   `WriteSupport`. Still open: the UI timeout wrapper.
3. **Phase 3 (write) (done, tested live 2026-10-16; see §13):** the command helper (`McpCommands.run(label, ...)`), `create_element`,
   `create_relationship`, `add_property/operation/literal`, `update_element`, `set_comment`,
   `delete_elements`, `move_element`, `undo/redo`, `save`, `batch`.
4. **Phase 4 (stereotypes and validation) (done, see §14):** `apply_profile`, `apply/unapply_stereotype` with
   tagged values, `validate`.
5. **Phase 5 (diagrams) (done for class, usecase and component diagrams; see §15):** `create_diagram`, `add_to_diagram`, `remove_from_diagram`,
   `layout_diagram` (gmf, then graphviz), `export_diagram_image`.
6. **Phase 6:** Tycho build, update site, SSE progress, confirm-writes option. Sirius diagram
   tools (v2).

## 9. Risks
* **UI thread blocking:** a modal dialog stops `syncExec`. Mitigation: the timeout wrapper. Write
  tools must also never open dialogs: use Papyrus requests with no UI and set
  `IEditCommandRequest.setParameter("ui.mode", false)` where advices ask the user.
* **Papyrus internal APIs** (`ViewPrototype`, policy checker, `ShowHideRelatedLink`) are not
  stable API and may change in 7.2+. We keep them behind one `DiagramService` class.
* **Lazily restored editors:** `getEditor(false)` returns null until the user clicks the tab. The
  bot is then told "no model open". `list_open_models` will show restorable editors.
* **Several models open:** the active editor is picked by default, so the bot could edit the
  wrong model. Mitigation: the `model` argument, and every write result echoes the model URI.
* **Large models:** `list_elements` has paging (limit/offset), and output sizes have a limit.
* **Graphviz** may be missing on the user's machine: fall back to GMF arrange.
* **Security:** the port is reachable by any local process when no token is set. Recommend a token.
* **The MCP spec changes over time:** the protocol code is in one ~200-line class, and
  `protocolVersion` is negotiated in `initialize`.
* **Client support:** some MCP clients only speak stdio. Bridge with
  `npx mcp-remote http://127.0.0.1:7341/mcp`.

## 10. Multi-client changelog and stale-edit detection (implemented)
* **Client identity.** `initialize` stores a `Session`: the id from the `Mcp-Session-Id` header,
  plus `clientInfo.name` and `version`. Its label looks like `grok-build/1.0#e8a484b9`.
  * Each `tools/call` finds its session from the header.
  * A missing or unknown session is treated as `anonymous`. This lenient choice is kept for curl
    testing; it can be made strict later.
* **Changelog** (`util/Changelog`). Every successful write appends one entry, kept per model and
  keyed by the .uml URI. Each entry has:
  * `seq`: global and increasing
  * `timestamp`: ISO, UTC
  * `client` and `sessionId`
  * `tool`
  * `summary`
  * `undoLabel`: exactly what Papyrus shows as "Undo …"
  * `elementIds`
  * `versionBefore` and `versionAfter`
* **`get_changelog`.** Without `since_seq`, it returns the entries after this session's cursor,
  then moves the cursor forward. This answers "what did the other bots change since I last
  looked?". `since_seq=0` returns everything, and `client` filters by label.
* **Sidecar file.** This is optional: the preference "Save MCP changelog next to the model", or
  `-Dpapyrus.mcp.changelogSidecar=true`.
  * The log is written to `<model>.mcp-changelog.json` next to the .uml, using a temp file and an
    atomic move.
  * It is reloaded when the model is first used.
  * It is off by default, so the user's model folder gets no new files unless they opt in.
* **Model version.** `ModelLocator` adds a `CommandStackListener` to each editing domain. Every
  execute, undo or redo from **anyone** (bot or human) raises the version by one. Every read
  result includes `modelVersion`. Human edits are not in the changelog; they only show up as a
  version jump with no matching entry.
* **Etags.** `get_element` returns `etag`: a 12-hex SHA-256 of the element's own non-derived
  features and its applied stereotypes. It changes only when that element changes.
* **Stale-edit check** (`WriteSupport.checkFresh`, used by every write tool):
  * `expected_version` must equal the current version. This is strict: any change anywhere fails it.
  * `expected_etags` must match for each listed element. This is fine-grained and recommended
    for bots, so unrelated edits don't block them.
  * On a mismatch the tool returns `isError` with "stale edit…". The bot should re-read and retry.
* **The only allowed write path** is `WriteSupport.execute(session, model, args, tool, command,
  summary, ids)`. It does the freshness check, then one `commandStack.execute` (one undo step),
  then the changelog entry. Phase-3 tools must use it.
* Test-only tool `debug_rename`, turned on by `-Dpapyrus.mcp.debugTools=true`, exercises this path.

## 11. Clients and remote access
Two clients are expected:
1. **Grok Build desktop app**, on the same laptop as Papyrus. It connects directly to
   `http://127.0.0.1:7341/mcp`. Nothing is exposed to the network.
2. **Grok Bot**, which runs on a remote cloud box. It **cannot reach the laptop's localhost**, so
   it needs a deliberate, authenticated path. Papyrus itself **keeps binding to 127.0.0.1**. We
   never add a "listen on 0.0.0.0" option. The tunnel or proxy does the exposing.

**Rule: when the server is reachable from anything other than the laptop itself, a token is
mandatory.**
* Plan (v1.1): a `requireToken` preference. Startup refuses a remote-access setup with no token.
* The server rejects requests whose `Host` header is not loopback, unless `allowedHosts` (for
  example the tailnet name) is configured **and** a token is set.
* Use a long random token (for example `openssl rand -hex 32`). Put it in the Grok Bot MCP config
  as `Authorization: Bearer …`. Rotate it from the preference page.

| Option | How | Pros | Cons |
|---|---|---|---|
| **A. Tailscale (recommended)** | Laptop and cloud box join the same tailnet. On the laptop run `tailscale serve --bg --tcp 7341 tcp://127.0.0.1:7341` (or `--https=443 http://127.0.0.1:7341`). Grok Bot uses `https://<laptop>.<tailnet>.ts.net/mcp` plus the Bearer token. | WireGuard encryption, both devices identified, ACLs (tag the bot box and allow only that tag to reach the laptop on 7341), no public port, works behind NAT, `serve` (not `funnel`) stays private. | Tailscale must run on both machines. The cloud box needs an auth key. |
| **B. SSH reverse tunnel** | From the laptop run `ssh -N -R 127.0.0.1:7341:127.0.0.1:7341 user@cloudbox` (add `autossh` to keep it alive). Grok Bot then calls `http://127.0.0.1:7341/mcp` **on the cloud box**. | No extra software. The port is loopback-only on the box too (keep `GatewayPorts no`). Easy to stop (close ssh). | The laptop needs SSH access to the box. The tunnel drops when the laptop sleeps. Any process on the cloud box can use the port, so the token matters. |
| C. Cloudflare Tunnel or ngrok | Public HTTPS URL that forwards to localhost. | Simple. | **Public internet exposure.** Only acceptable with token plus Cloudflare Access/OAuth in front. Not recommended. |
| D. Port-forward on router / bind 0.0.0.0 | – | – | **Never.** |

**Recommendation:** use Tailscale with `tailscale serve`, a tailnet ACL that only lets the Grok Bot
box reach `laptop:7341`, and a mandatory Bearer token. If Tailscale isn't wanted, use an SSH
reverse tunnel. Read-only mode is a good first step for the remote bot. Every write the bot makes
is still undoable in Papyrus, and the changelog tags it as `grok-bot/...`, so you can see and undo
exactly what the remote bot did.

## 12. Test harness (box)
* `test/launch-papyrus-test.sh` starts the installed Papyrus under Xvfb with a **throwaway**
  configuration (`/tmp/pmcfg`). Our jar is added through `osgi.bundles` plus
  `simpleconfigurator.exclusiveInstallation=false`. The dropins reconciler was unreliable here,
  and p2 rewrites `bundles.info`.
* `-Dpapyrus.mcp.testOpen=<model.di>`: a startup hook (in `Startup`) links the folder as project
  `mcp-test` and opens the .di with `IDE.openEditor`. `--launcher.openFile` did not work.
* `test/smoke.py` acts as two clients (grok-build and grok-bot) and covers every read tool, the
  changelog, the cursors and stale edits. Its output is saved in `test/smoke-output.txt`.

## 13. Phase 3 as built: edit tools, undo, timeout
* **One command per call.** Each write becomes one `McpCommand`, which is an EMF
  `RecordingCommand`. Inside it we use plain UML2 API calls, and we also run Papyrus
  edit-service `ICommand`s directly. Everything is recorded into one ChangeDescription, so one
  Ctrl+Z reverts the whole tool call or batch.
* **Errors.** An error inside the command sets `OperationCanceledException`, which rolls the
  transaction back. Nothing reaches the undo history, and there is no changelog entry.
* **What goes through the Papyrus edit service** (`util/EditService`), so Papyrus advice runs:
  * `create_element`, using `CreateElementRequest` with type `org.eclipse.papyrus.uml.<Metaclass>`
  * `move_element` (`MoveRequest`)
  * `delete_elements` (`DestroyElementRequest`), which also removes the diagram views
  * If the service has no command, each one falls back to EMF.
* **What uses the UML2 API directly** (predictable owners): relationships, properties,
  operations, literals and comments.
  * Association: `source.createAssociation(...)`. The end named "target" is the end typed by
    the target and is navigable by default.
  * Dependency family: owned by the source's nearest package.
  * Include/Extend: owned by the use case.
  * Flows: owned by the activity.
  * Transition: owned by the source vertex's region.
* **`update_element`** sets any changeable, non-derived, non-containment feature by name.
  Enum values are given as literals, references as id or qualified name.
  * Convenience keys: `multiplicity`, `lower`, `upper`, `type`, `body`, `default`, `specification`.
  * Primitive types resolve by qualified name, for example `PrimitiveTypes::Integer`.
* **`delete_elements` with `dry_run`** is read-only. It reports:
  * the owned elements that would also be deleted
  * affected relationships
  * other elements that reference them
  * how many diagram views would be removed
* **`batch`** applies `operations[{tool, arguments}]` inside one command. `"$N"` means the id
  created by operation N. A failure anywhere rolls back the whole batch.
* **`undo` / `redo`**:
  * By default they act only if the top of the stack is an `McpCommand` from this session.
  * `scope=mcp` allows any bot's edit. `force=true` allows anything, including the user's edits.
  * Each one is written to the changelog.
* **`save`** calls `editor.doSave`.
* **UI timeout** (`UiSync`): requests are posted with `asyncExec` and waited on a future, for
  `-Dpapyrus.mcp.uiTimeoutMs` (default 30000).
  * If the request has not started when the timeout hits, it is cancelled and never runs:
    "UI thread is busy".
  * If it started but did not finish, the bot is told it may still complete and should check
    the changelog.
  * Reads still work while a dialog is open, because asyncExec runs inside modal loops.
    **Writes, undo, redo and save are refused while a modal dialog is open.**
* **Tests** (box, live Papyrus 7.1, original file names so notation refs resolve):
  * `test/smoke_edit.py`: all edit tools, stale etag and stale version, undo ownership,
    delete/undo, batch with `$refs`, batch rollback, one-step undo of a batch, save.
  * `test/smoke_timeout.py`: dialog refusal and both timeout paths. It uses test-only
    `debug_block_ui` and `debug_open_dialog`, which only exist with
    `-Dpapyrus.mcp.debugTools=true`.
  * Outputs are in `test/*-output.txt`.
* **Laptop:** `test/laptop-launch.sh` uses a throwaway `/tmp/papyrus-mcp-cfg` and loads the jar
  through `osgi.bundles`, so the install folder is never written. `test/laptop-smoke.sh` runs
  curl initialize, ping and list_packages.

## 14. Phase 4 as built: profiles, stereotypes, validation
* **`apply_profile(profile, package?, unapply?)`** resolves the profile in this order:
  1. a profile already loaded in the ModelSet, by name, qualified name or URI
  2. an alias (`Standard`/`StandardProfile`, `Ecore`)
  3. a URI or absolute path (pathmap:, platform:, file:)

  Only *defined* profiles can be applied. It uses UML2 `applyProfile` / `unapplyProfile` inside the
  McpCommand, so it is one undo step.
* **`apply_stereotype(id, stereotype, values?)`** applies the stereotype, or only sets values if
  it is already applied.
  * Tag values are converted by the tag's type: primitives (Integer, Boolean, Real, String),
    enums by literal name (UML2 maps them to the profile's EEnumLiteral), element refs by id or
    qualified name, and stereotype-typed refs through the target's stereotype application.
  * An unknown tag or literal is an error and nothing changes.
* **`unapply_stereotype(id, stereotype)`** removes a stereotype.
* **`validate(id?, severity?, limit?)`** uses Papyrus' `UMLDiagnostician`, the same engine as
  "Validate Model": UML2 constraints plus the EMF Validation framework. It falls back to the EMF
  `Diagnostician`.
  * It returns counts and a flat list of `{severity, message, source, code, element}`.
  * v1 does not create Problems-view markers.
* **Undo labels now use element names**, for example `MCP: move McpColor to McpSub`,
  `MCP: apply «Entity» to Scene`, `MCP: delete McpProbe2`. `WriteTool.label(m, args)` runs on
  the UI thread before execution, because the Papyrus operation history captures the label at
  that moment.
* **Test fixture:** `test/fixtures/McpTest.profile.uml`, built by `test/fixtures/gen/MakeProfile.java`
  with standalone UML2. It has stereotype `Entity`, which extends Class, with tags
  table:String, version:Integer and persistence:Persistence{Persistent, Transient}.
* **Finding on the test model:** a full validation reports 16 errors and 97 warnings. The errors
  say the activity nodes of "Onboarding" are not listed in `Activity.node`, which points to a
  structural flaw in the generated .uml from the earlier mudhut work. The warnings are mostly
  duplicate association names like "User plays" and "House helps". This is worth fixing in the
  real model.

## 15. Phase 5 as built: GMF diagrams
* **Kinds supported:**

  | Kind | Diagram type | Kind id |
  |---|---|---|
  | `class` | PapyrusUMLClassDiagram | org.eclipse.papyrus.uml.diagram.class |
  | `usecase` | UseCase | org.eclipse.papyrus.uml.diagram.useCase |
  | `component` | PapyrusUMLComponentDiagram | org.eclipse.papyrus.uml.diagram.component.root.package |

  Activity, state machine and sequence diagrams need frame nodes (the Activity or StateMachine
  shape with regions) and are refused with a clear message for now. Planned as phase 5b.
* **Pure notation-model approach, inside the McpCommand,** so diagrams are undoable and batchable
  together with model edits:
  * `ViewService.createDiagram`, then add `PapyrusDiagramStyle{diagramKindId, owner}` and the
    `diagram_compatibility_version=1.4.0` style. This is the knowledge carried over from
    papyrus-out. Then add the diagram to the model's .notation resource.
  * After the command, `open=true` opens the page through `IPageManager`. That is editor state
    and is not undoable.
* **Nodes:** `ViewService.createNode(diagram, element, hint, prefHint)`.
  * The hint comes from the diagram plugin's `UMLVisualIDRegistry.getNodeVisualID` (loaded
    reflectively). If that gives nothing, we use `<Metaclass>_Shape`.
  * Papyrus' own view providers build the full label and compartment structure.
* **Edges:** Papyrus' generated providers only provide an edge when the semantic adapter also
  adapts to the `IElementType` `org.eclipse.papyrus.umldi.<Metaclass>_Edge`. `Diagrams.createEdge`
  passes such an adapter, then connects source and target.
  * The ends follow Papyrus conventions: association = memberEnds[0].type → memberEnds[1].type,
    generalization specific → general, dependency client → supplier, include/extend,
    interface realization, package import.
* **`add_to_diagram`** auto-places nodes on a grid below existing content unless x and y are
  given. With `withRelationships`, it draws every relationship whose two ends are both shown.
  Elements already shown are skipped and reported.
* **`remove_from_diagram`** uses `ViewUtil.destroy`, which removes attached edges. The model is
  untouched.
* **`layout_diagram`:**
  * `graphviz` (default) runs `dot -Tplain` on the diagram graph, with generalizations pointing
    up. Node sizes are estimated from features; association ends are not counted as rows.
  * `grid` is the fallback when `dot` is missing or fails.
  * Edges are straightened.
  * **Deviation from §3:** GMF "Arrange All" needs live edit parts (an open editor), so it is not
    used in v1.
* **`export_diagram_image(diagram, format=png|jpg|svg)`** renders offscreen with GMF
  `CopyToImageUtil`. The diagram does not have to be open. PNG and JPG come back as an MCP
  `image` content item, and SVG as text. The temp file is deleted afterwards, there is no path
  argument, and output is capped at 8 MB.
* **Tests:** `test/smoke_p5.py`:
  * a class diagram with 6 classes and 5 associations
  * a use case diagram with 9 nodes and 13 edges
  * graphviz and grid layout
  * remove, then undo
  * one batch that builds model and diagram together and is undone in a single step
  * save, then reload with `RESET=0`, after which the diagrams are still there and still render

  PNGs are in `test/p5-images/`.
