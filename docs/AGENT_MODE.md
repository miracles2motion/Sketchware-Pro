# Agent Mode — implementation notes

Grounding for the Agent Mode design: what was verified against the code in
this repository, what had to be corrected, and the defects in the upstream
LiteRT-LM runtime that the design has to work around.

## 0. Pivot: cloud providers instead of an on-device model

Agent Mode no longer runs a local LiteRT-LM model. On-device testing
showed the 1B model class cannot carry the agent's workload (recall and
basic knowledge fail inside Google's own AI Edge Gallery too), while the
runtime integration cost repeated broken builds. The model layer was
removed completely (dependency, native libraries, import flow, engine
lifecycle) and replaced with one OpenAI-compatible HTTP client
(`AiChatService`) plus provider presets: Gemini (through Google's
official `https://generativelanguage.googleapis.com/v1beta/openai/`
compatibility endpoint), OpenRouter, NVIDIA, and custom base URLs.
Users bring their own API key; keys live in app-private storage, never
in `.sketchware/ai/`. Model lists are fetched per key (`GET /models`),
quota failures (402/429) surface as a switch-model popup, and OpenRouter
keys additionally report remaining credit via `/auth/key`. The
tool-calling design below is unchanged; cloud models emit native
function calls for the same propose/execute gates.

## 1. Verified against the code

| Design claim | Where it lives | Status |
| --- | --- | --- |
| Mode bar in the `AppBarLayout` of `main.xml` | `res/layout/main.xml` — `DrawerLayout > CoordinatorLayout > AppBarLayout > FragmentContainerView + FAB + BottomNavigationView` | confirmed |
| `MainActivity` swaps `ProjectsFragment` / `ProjectsStoreFragment` in `binding.container` | `pro/sketchware/activities/main/activities/MainActivity.java` — `navigateToProjectsFragment()` / `navigateToSketchubFragment()` use `transaction.add(...)` + `hide(activeFragment)` + `show(...)` | confirmed |
| "New project" FAB is hidden per mode | `binding.createNewProject.show()` / `.hide()` | confirmed |
| UI is `ViewBean` trees | `com.besome.sketch.beans.ViewBean` (extends the jar-only `a.a.a.nA`), tree read through `jC.a(sc_id).d(xmlName)` → `ArrayList<ViewBean>` | confirmed |
| Logic is `BlockBean` graphs | `com.besome.sketch.beans.BlockBean` — Gson fields `opCode`, `spec`, `parameters`, `subStack1`, `subStack2`, `color`, `id`, `nextBlock`, plus `classInfo` (`a.a.a.Gx`) | confirmed |
| The block grammar is a machine-readable registry | `mod.hilal.saif.blocks.BlocksHandler.builtInBlocks(ArrayList<HashMap<String,Object>>)` — every entry carries `name`, `type`, `typeName`, `code`, `color`, `palette`, `spec`; `spec` is a printf-style grammar (`"… %s … %d … %m.Command"`) | confirmed |
| Extra palette blocks | `dev.aldi.sayuti.block.ExtraBlocks.extraBlocks(...)` / `ExtraPaletteBlock` (the `mod` package) | confirmed |
| Raw Java escape hatch | `addSourceDirectly` in `a.a.a.Fx` and `a.a.a.kq` | confirmed |
| Same codegen path as hand-built projects | `a.a.a.Jx` (activities), `a.a.a.Lx` (components), `a.a.a.Ox` (layouts), `a.a.a.Fx`, `a.a.a.ProjectBuilder` | confirmed |
| Model home is a hidden dot-folder next to `mysc/` | `a.a.a.wq` — `.sketchware/{mysc,libs,data,bak,temp,resources,download,tutorial,collection,upload}`; the new root is `wq.q` = `.sketchware/ai` | confirmed |
| `/sdcard/sketchware/{signed_apk,keystore,service_account,localization}` | `a.a.a.wq` constants `A`–`E`, `y`, `z` | confirmed |
| Reuse the project brief fields | `com.besome.sketch.projects.MyProjectSettingActivity` — `is_update` flag, `ThemeManager.getThemePresets()` / `generateRandomTheme()` | confirmed |
| Reuse the project options sheet | `com.besome.sketch.adapters.ProjectsAdapter.showProjectOptionsBottomSheet()` with `BottomSheetProjectOptionsBinding` | confirmed |
| Open a project with `toDesignActivity(sc_id)` | `pro.sketchware.activities.main.fragments.projects.ProjectsFragment.toDesignActivity(String)` | confirmed |
| Overlay precedent for the floating agent window | `mod.bobur.FloatingProgressWindow`, overlay permission handling in `mod.hilal.saif.activities.tools.ConfigActivity` | confirmed |
| Permissions already declared | `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `MANAGE_EXTERNAL_STORAGE`, `requestLegacyExternalStorage`, `largeHeap="true"` | confirmed |
| Markwon is available | dependency present; currently only used by `CommitAdapter` | confirmed |

## 2. Corrections to the design

- **`a.a.a.DB` is not in the source tree.** It is a compiled class in
  `app/libs/a.a.a-notimportant-classes.jar` (a Git LFS object). Its API is
  `new DB(Context, String)` plus single-letter overloads
  (`a(String,int)`, `a(String,String)`, `a(String,Object)`,
  `a(String,Object,boolean)`, `a(String,HashMap)`, `a()`, `a(String)`,
  `a(String,boolean)`, `b()`, `b(String)`, `c()`, `c(String)`, `d(String)`,
  `e(String)`, `f(String)`, `g(String)`). New agent state therefore lives in
  its own `agent-config.json` instead of piling into `DB`.
- **`a.a.a.nA`** (the `ViewBean` superclass) is also jar-only, but it is
  trivial — the view fields are all in the `ViewBean` source.
- **The project card field names in the design are wrong.** The list row is
  `res/layout/myprojects_item.xml` with the ids `project_name`, `app_name`,
  `package_name`, `img_icon`, `img_icon_view`, `tv_published`, `img_pin`,
  `expand` — there is no `my_ws_name`, `my_app_name` or `my_sc_pkg_name`.
- **The `ViewBeanFactory` is much smaller than the design assumes.**
  `pro.sketchware.tools.ViewBeanFactory` only has a constructor,
  `getConsideredTypeViewByName(String, int)` and `applyAttributes(Map)`.
  The widget catalog has to come from the palette and
  `pro.sketchware.widgets.WidgetsCreatorManager`, not from `ViewBeanFactory`.
- **The `main.xml` mode bar placement.** The `AppBarLayout` holds a
  `MaterialCardView` with a `MaterialToolbar`; the toggle group is a second
  card inside the same `AppBarLayout` so it stays pinned while the toolbar
  scrolls.
- **The GPU manifest entries go inside `<application>`**, not next to
  `<uses-permission>`.

## 3. LiteRT-LM defects the design has to work around

These are upstream issues, not bugs in this repository, but they change how the
agent loop has to be written.

### 3.1 `onDone()` is never delivered for a single-use conversation — #3266

`com.google.ai.edge.litertlm:litertlm-android` (reproduced on 0.13.1 and
0.16.0, still open as of 0.18.0) never calls `MessageCallback.onDone()` when a
`Conversation` is used exactly once. The consequences are severe:

- the turn never closes;
- **every later generation in the same process returns zero tokens** — the
  engine is dead until the process restarts;
- **closing the stuck conversation blocks forever** (the calling thread never
  returns).

Measured on the reporter's side: 0/4 single-use turns delivered `onDone()`,
while 4/4 turns that reused the same `Conversation` did.

Mitigation, implemented in `AiModelManager`:

1. one `Conversation` is created and **reused for every turn**, and turns
   use the streaming callback API — the call shape verified working on a
   device;
2. the engine and the conversation are only ever touched on a single
   background thread — closing a stuck conversation must not happen on the
   main thread, that would be an ANR;
3. an idle watchdog closes a turn that produced no token for 20 s, because
   the terminal callback cannot be relied upon;
4. a failed engine can be torn down and reloaded, so the manager treats
   "engine is dead" as recoverable.

### 3.1.1 Conversation memory: match AI Edge Gallery's configuration

Field report: with a reused `Conversation`, the model answered every
message as if it were the first. Two things were tried before the cause
was pinned down by comparing against Google's AI Edge Gallery
(`LlmChatModelHelper.kt`, the reference app for this API):

- Pasting the transcript into every message as plain text. The 1B model
  began answering the previous message and imitating its own bad replies
  (a feedback loop through the pasted history). Rejected on evidence.
- A fresh seeded conversation per turn with the synchronous
  `sendMessage`. Returned nothing on device. Rejected on evidence.

The configuration now mirrors Gallery's, which is the variable that was
actually different: `EngineConfig.maxNumTokens` is set from the model
file (`ModelInfo.maxContextTokens()`), `ConversationConfig.channels` is
left null (model default) instead of an empty list, one conversation is
created and reused, and only the new message is sent per turn. The app
still records the transcript, but only to re-seed a fresh conversation
if `getTokenCount()` passes 3000 - Gallery's `resetConversation`
pattern. A message sent before the engine is ready is queued and
delivered when loading finishes, instead of failing with "no model is
loaded".

### 3.2 Model output can abort the process — #3849

`liblitertlm_jni.so` raises `SIGABRT` when the model emits certain characters
(fullwidth `￡`, `￦`) on the CPU backend. Model output is untrusted input, so
this contradicts the "the model can never crash the app" rule directly.

### 3.3 Non-ASCII input can segfault the runtime — #1616

The ICU `RegexMatcher` in the runtime crashes on LaTeX, maths symbols and
non-Latin scripts. The user base of this app is heavily non-English, so this
is a live risk and not only a maths-app problem.

### 3.4 Version pinning

`latest.release` is not reproducible. The dependency is pinned to
`0.18.0`, the first release with the `ModelInfo` API — which gives metadata
and runtime requirements **without a full model load**, and is what the import
validation step needs.

## 4. The exact LiteRT-LM 0.18.0 API

Read from the published sources of the `v0.18.0` tag, because the Java view
of a Kotlin API is full of traps.

```kotlin
sealed class Backend(val name: String) {
  data class CPU(val threadCount: Int? = null, val numOfThreads: Int? = null) : Backend("CPU")
  class GPU : Backend("GPU")
  data class NPU(val nativeLibraryDir: String = "") : Backend("NPU")
  class GOOGLE_TENSOR : Backend("GOOGLE_TENSOR_ARTISAN")
}

data class EngineConfig @JvmOverloads constructor(
  val modelPath: String,
  val backend: Backend = Backend.CPU(),
  val visionBackend: Backend? = null,
  val audioBackend: Backend? = null,
  val maxNumTokens: Int? = null,
  val maxNumImages: Int? = null,
  val cacheDir: String? = null,
  val activationDataType: ActivationDataType? = null,
)

data class ConversationConfig @JvmOverloads constructor(
  val systemInstruction: Contents? = null,
  val initialMessages: List<Message> = listOf(),
  val tools: List<ToolProvider> = listOf(),
  val samplerConfig: SamplerConfig? = null,
  val automaticToolCalling: Boolean = true,
  val channels: List<Channel>? = null,
  ...)

data class SamplerConfig(val topK: Int, val topP: Double, val temperature: Double, val seed: Int = 0)
```

What that means in Java:

| Kotlin | Java |
| --- | --- |
| `Backend.CPU()` | `new Backend.CPU()` — a nested **class**, not a function |
| `Backend.GPU()` | `new Backend.GPU()` |
| `Backend.NPU(dir)` | `new Backend.NPU(dir)` |
| `EngineConfig(path, backend, cacheDir)` | `new EngineConfig(path, backend, null, null, null, null, cacheDir)` — `cacheDir` is the **seventh** parameter, `@JvmOverloads` only drops trailing ones |
| `Message.user(text)` | `Message.Companion.user(text)` — the companion is reached through `Companion` |
| `SamplerConfig(64, 0.95, 1.0f, 0)` | the same; `seed` has a default, the other three do not |
| `ModelInfo.from(path)` | `ModelInfo.from(path)` — `@JvmStatic` on the companion |
| `tool(myToolSet)` | `ToolKt.tool(myToolSet)` — a top level function |

`automaticToolCalling` defaults to **true**. It has to be turned off
explicitly, because the whole safety model of Agent Mode is built on the
application deciding what a tool call does.

`Conversation` also offers `cancelProcess()` (used for Stop) and `isAlive()`.

## 5. Build and memory arbitration

- A 1B int4 model holds roughly 1 GB of resident memory, and AAPT2/D8 need
  that memory too. `ProjectBuilder` therefore releases the engine before a
  build starts (`unloadBlocking`, bounded, never on the main thread), and
  leaving Agent Mode releases it as well.
- `EngineConfig.cacheDir` points at `context.getCacheDir()/litertlm` so the
  second load of a model is faster.
- The engine is loaded **lazily**: entering Agent Mode with a model configured
  starts the load, and the engine is not resident while the user only looks at
  the project list.

## 6. What is in the tree now

```
pro/sketchware/ai/
    AiPaths.java             filesystem layout under .sketchware/ai
    AiModelConfig.java       agent-config.json registry (Gson)
    AiModelManager.java      engine lifecycle, single thread, watchdog
    AgentKnowledgePack.java  the block catalog, exported for the model
    ChatMessage.java         chat message bean
    ChatAdapter.java         chat recycler adapter
pro/sketchware/activities/main/fragments/agent/
    AgentFragment.java       chat screen, model import, lazy engine load
res/layout/agent_chat.xml
res/layout/agent_chat_item_user.xml
res/layout/agent_chat_item_assistant.xml
```

The mode toggle lives in `main.xml` and is handled by `MainActivity`.

### The knowledge pack

`AgentKnowledgePack` exports the block catalog of *this* build of the app to
`.sketchware/ai/knowledge/blocks.json`. It calls the same
`BlocksHandler.builtInBlocks(...)` that the logic editor uses to build its
palette, so the pack always contains exactly the blocks the app supports,
including the ones that come from mod libraries.

Every entry carries the opcode, the block type, the palette, the grammar and
the grammar broken down into parameters:

```json
{
  "opCode": "setText",
  "type": "c",
  "kind": "statement",
  "spec": "set text of %m.TextView to %s",
  "parameters": [
    {"index": 0, "kind": "member", "memberType": "TextView", "label": "text of"},
    {"index": 1, "kind": "text", "label": "to"}
  ]
}
```

The parameter breakdown is what makes a block usable by a model: `%m.TextView`
is a slot for a widget of a known type, not a free text.

The widget palette is **not** compiled into the app. It lives in
`/storage/emulated/0/.sketchware/resources/widgets/widgets.json` and can be
extended by the user, so it is copied verbatim instead of being rebuilt.

## Phase 2 plan (agreed 2026-10-17, discussion state — not yet built)

**Principle:** model proposes, app executes. The model emits tool calls;
the app validates, the user authorizes, the app executes on the real
`ViewBean`/`BlockBean` data through the editor's own classes, so agent work
opens in the normal editor indistinguishable from hand-made work.

**Tool catalog v1:** `project_summary`, `create_project`/`open_project`,
`list_activities`/`create_activity`/`delete_activity`,
`edit_screen(activity, ops[])`, `set_event_handler(activity, widget, event, blocks)`,
`read_file`/`write_file` (sandboxed escape hatch), `build()`,
`get_build_errors()`. ~20 calls/turn cap, Stop always live.

**Five safety gates:** schema validation (`E_*` rejections), preconditions
(screen revisions — stale edits refused, user always wins), transactional
execution with snapshots, human approval for every mutation, containment
(project-jailed paths, catalog-validated ids).

**Plan-first execution (user decision):** substantial tasks begin with ONE
implementation plan with two sections — **Interface** and **Logic** —
generated in one pass so names/ids agree across sections. The user approves
whole or per section, edits it in plain words before approval, and execution
follows it (screens first, then wiring, build last). Trivial single edits
skip the plan and go straight to an approval card. The approved plan is
written into the project's `ai-manifest.md`.

**Approval tiers (user decision):** default is per-mutation approval;
"Approve plan & run" is the sanctioned auto mode; deletes and file
overwrites always ask in every mode.

**Reference resolution:** every turn carries a context snapshot — working
project (`sc_id`), last activity open in the editor, and that screen's
condensed widget list (id/type/text/colors). Referring expressions resolve
against that list; ambiguity produces a choice card, never a guess.

**Needs protocol:** when the agent needs something the user must supply
(asset, decision, permission, target project), it asks via an options card
(provide / placeholder / skip), or proceeds with a *declared* assumption for
safe cosmetics only.

**Gaps folded in (user-approved additions):**
1. Context budgeting — condensed projections and summaries, never raw files.
2. Turn-level **Undo** as a user-visible feature, not just plumbing.
3. Failed-step mid-plan card: retry / skip / stop.
4. Verify-after-execute: re-read the screen and report what is seen, plus
   the build result.
5. Pause/resume: leaving mid-turn parks execution safely.
6. Cost visibility: "n of 20 actions used" per turn.

**Build order:** read-only tools + Working-On card → create project/activity
+ approval cards → `edit_screen` → `set_event_handler` → build loop +
file tools. Each slice device-verified before the next.

**Durability & crash recovery (user-approved addition):** chat history is
currently in-memory only (gone if the app dies) — Phase 2 pulls basic
persistence forward, ahead of the Phase 3 history browser. The transcript is
appended to app-private storage as messages complete, so a restored chat is
restored memory (history is re-sent per request by design). Agent run state
(approved plan, steps done/pending, snapshot index, any pending approval
card) lives in the project beside `ai-manifest.md`; returning after a crash
offers one card: Resume / Undo the whole task / Discard. Steps apply
transactionally (complete write, then swap), are safe to re-run, and
re-verify target revisions before continuing.

**Context management (user-approved addition):** big screens and projects
are stored and indexed by the app; the model holds a page at a time.
Projections are condensed and capped (containers summarised, leaf detail on
demand); the model drills in with read-only tools (expand a container,
search a screen by text/type/colour). Targets are addressed by widget id
plus human label with the full path shown on approval cards. Edits are
small ops, never screen regeneration. Task memory is the on-disk checklist —
each step re-reads its own branch fresh (small, revision-checked) instead of
recalling earlier steps. Conversation context is budgeted: recent turns
verbatim, older turns condensed into an app-maintained running summary.

**Design competence (user-approved addition):** without help, the agent
builds correct apps wearing stock default widgets. Phase 2 therefore ships
(a) a **planning rubric** injected at plan time — always address screens,
data storage, navigation, empty states, naming — which stops category
omissions but cannot supply taste; and (b) an in-app **style kit**: a few
curated palettes, a rounded-button drawable, card styling, 8dp spacing,
applied through ordinary style ops. The plan's Interface section chooses a
palette (or takes the user's), which becomes project **design tokens**
(primary, surface, text, accent) stored with the project. Widgets are
created against tokens (`@primary` references resolved by the app at write
time), so colour use is consistent by construction rather than by model
memory; off-palette literals remain possible but are visible on approval
cards. Changing the palette mid-project updates the tokens and offers a
re-apply pass over existing screens. Visual iteration (render → look →
refine) stays with the later vision phase.

**Approval-card spec (user-approved):** users see sentences, never beans.
A mutation card states the action and target in Sketchware's own words
("MainActivity · button1 (\"Save\") — background: red → blue", colours as
named swatches), with a muted-green ✓ Approve and muted-red ✕ Reject
(icons + text, theme-aware, never colour alone). A collapsed "Details"
expander holds the exact ops for audit. Read-only actions appear as quiet
activity lines. Executed steps confirm what was *seen* after verification,
failures offer Retry/Skip/Stop in plain language; `E_*` codes are
model-facing only. Plan mode: one plan approval, then a collapsed executed
checklist with Stop and Undo task always visible.

**Risk register (hard requirements before/within slices):** (1) mutate
beans only through the app's own classes/save paths, proven per slice in
the real editor + builder; (2) screens open in the editor are locked
against the agent; (3) project/activity creation rides the app's own
creation flows; (4) block compilation starts from a safe subset of common
blocks; (5) explicit per-call size limits (ops count, string length,
nesting depth) on all untrusted model output; (6) the app allocates widget
ids, the model references them; (7) automatic project backup before the
agent's first mutation of an existing project, with restore; (8) a
Settings kill switch ("Allow the agent to modify projects") separate from
chat. Decisions: agent/tool turns run **non-streaming**; tool-calling
incapability is detected and explained ("this model can't operate tools");
same error twice stops the loop and asks the user; a first-read privacy
line discloses that project content goes to the chosen provider.
Verification per slice = golden tasks on sacrificial projects judged in
the real editor and compiler.

**Knowledge base status:** real but partial — the block catalog
(`blocks.json` from `BlocksHandler`) and the device widget palette.
Missing and to be completed *per slice, by extraction from the app*:
per-widget properties/events (edit_screen slice), block parameter/usage
detail (event-handler slice), build-error meanings. Knowledge is injected
as relevant slices and pulled via read-only lookups — never uploaded whole
per request.

## Phase 2 build log

### Slice 1 — read-only tools + Working-On card (built 2026-10-17, pending device test)

- `SketchwareProjects` — the read-only gateway: projects via `lC.a()/b(sc_id)`,
  activities via `jC.b(sc_id).b()`, screens via `jC.a(sc_id).d(xmlName)`.
  Nothing hand-parses project files; nothing writes.
- `AgentTools` — validated JSON-form tool contracts with `E_*` codes.
  Slice 1 tools: `project_summary`, `list_activities`, `get_screen`
  (condensed widget lines: id, type, text, bg colour, parent; capped 120).
- `AgentLoop` — non-streaming tool turns (`AiChatService.agentTurnSync`),
  ~20 calls/message cap, same-failure-twice circuit breaker, quiet
  activity lines in the transcript. Providers that reject tool calling
  fall back to the plain streaming chat automatically.
- Working-On card: always visible; tap binds a project (stored as
  `workingProjectId` in the config), shows name + app + package; the
  loop's system prompt carries the binding. No-project answers come from
  the tool result (`E_NO_PROJECT`), never a guess.
- Device test: bind a sacrificial project, ask "what screens does my
  project have?", "what widgets are on MainActivity?", and ask the same
  with no project bound (must explain the card, not invent screens).

### Slice 1 device verdict + fix pack (2026-10-18)

Device test findings (SportyNivida / Sporty Bet):
- Card binding, picker, live data all PASS — the agent reflected a newly
  created activity the moment it existed (1 screen → 2), and `get_screen`
  returned the real 8-widget tree of MainActivity.
- FAIL found: with the project unbound, the model answered from
  conversation memory instead of refusing. Guard added: the agent must
  always verify project contents with tools (memory goes stale), and
  when unbound it must ask for the Working-On card.
- Some Gemini Flash models (3.7/3.8) returned 503 while 3.6 Flash and
  Flash Lite worked through the identical code path — provider-side
  capacity, handled by the human error + retry.
- Fix pack: native-style project picker (rows like the app's project
  list); reasoning split out of answers at parse time
  (`reasoning_content` field and inline `<thought>` tags) into a
  collapsed "Thinking" block on the bubble; Markwon rendering for
  assistant bubbles (user/system text stays literal; plain text while
  streaming, formatted at completion).

### Slice 2 — creation behind approval cards (built 2026-10-18, pending device test)

- `SketchwareActions`: mutations through the app's own flows only.
  `createProject` mirrors MyProjectSettingActivity (lC registry, wq
  skeleton, default ProjectSettings); `createActivity` mirrors
  ManageViewActivity's persist sequence; one-time project backup under
  `.sketchware/ai-backups/<sc_id>` before the first change to an
  existing project. Every action verifies by reading back.
- `AgentTools`: read tools execute via dispatch as before; mutating
  tools (`create_project`, `create_activity`) can only `propose`
  (validate + plain-words card text) and `execute` (after approval).
  Dispatch refuses mutations by construction.
- `AgentLoop`: proposals pause the loop; the fragment shows an
  approval card in the transcript and blocks the loop thread until
  the user decides. Rejection returns `E_REJECTED` (never trips the
  circuit breaker). A created project auto-binds as working project.
- Approval card: title/body in plain words, collapsed Details, soft
  green ✓ Approve / muted red ✕ Reject, outcome confirmed on the card
  ("Done — verified…"). Cards never enter the provider history.
- Kill switch: Settings → "Allow the agent to modify projects" (off
  by default) gates all proposals with `E_MODIFY_OFF`.

### Slice 3 — edit_screen (built 2026-10-18, pending device test)

- `AgentEdits`: the op engine. Ops (add_widget, set_text, set_hint,
  set_background, set_text_color, set_text_size, remove_widget,
  move_widget; max 12) are simulated on a copy of the screen and
  validated against the evolving tree (parent must exist or be added
  earlier in the same call; containers only; no cycles). Widget ids are
  app-allocated (the app's own prefix+counter naming) unless the model
  supplies a valid free one. Colours accept #RRGGBB/#AARRGGBB and the
  project tokens @primary/@primary_dark/@accent, resolved from the
  project's own theme colours at write.
- Proposals freeze allocated ids and a SHA-256 screen revision;
  execution refuses with E_SCREEN_CHANGED if the screen moved since.
  Save replaces the screen in the project data manager's live view map
  (`jC.a(sc_id).c.put(xml, beans)`, the app's own whole-screen replace
  path), runs the app's save-on-close commit, then reads the screen
  back and compares it to the approved sim.
- Device failure root-caused 2026-10-18 from Sketchware's own code:
  the screen root is virtual (owned by `InjectRootLayoutManager`,
  default vertical LinearLayout) and is NOT a stored bean. Stored
  top-level widgets carry the literal parent `"root"` — the palette
  drop path (`ViewPane.updateViewBeanProperties`), `ViewBeanParser`,
  and preset beans all write exactly that, and Sketchware's view
  normalizer (`eC.a`, also invoked by its serializer) dereferences
  `bean.parent.equals("root")`. The agent's earlier invented root bean
  with `parent = null` therefore crashed saving and editor load with
  an NPE. Fix: widgets are added/moved with parent `"root"` and the
  real root type as `parentType`; no stored root is planted; saves are
  pre-checked through `eC.a(...)`; a failed commit restores the
  previous screen in the in-memory store so later reads cannot claim
  an unsaved edit happened.
- Full-path audit against Sketchware's own code (same day) found and
  fixed: `create_activity` rewrote the file manager's custom-view
  list from the wrong source (would drop a project's custom views) —
  both native lists (`jC.b().b()` activities, `jC.b().c()` custom
  views) are now carried through exactly as `ManageViewActivity`
  saves them; new widgets now start from the editor palette's own
  defaults (the `Icon*` beans: padding, stretched axes, starting
  text/hint, `convert` class name); a removed widget now goes through
  the editor's own delete (`eC.a(ProjectFileBean, ViewBean)`), which
  also removes its events and scrubs block references; an added
  Button gets the empty `onClick` event registration the editor
  performs on drop (duplicate-guarded); `FloatingActionButton` is
  rejected as a screen widget (it is the activity's separately-held
  floating button, and the editor would render a stored one as an
  unknown view); ScrollView parents accept only one direct child;
  screen reads now present the editor's normalized order (`eC.a`).
- Vocabulary/property pass (audit continuation): `add_widget` now
  covers all 48 editor widget types — name↔type and the FQCN
  `convert` names delegate to the app's own extended table
  (`mod.agus.jcoderz.beans.ViewBeans`), and per-type starting beans
  transcribe the extended palette icons' `getBean()` (padding,
  stretched axes, starting text/hint, `inject` attribute strings).
  New edit ops use the property editor's own `LayoutBean` encodings:
  `set_size` (match/wrap/dp), `set_orientation`, `set_padding`,
  `set_margin`, `set_gravity` (+`layout_gravity`), `set_weight`.
  `get_screen` reports non-default width/height/orientation. The
  knowledge pack now also exports `widgets.json` (all 48 types,
  container flag, and the event names the logic editor offers per
  class, from `a.a.a.oq`) beside `blocks.json`.
- Custom views are first-class screens: `list_activities` and
  `project_summary` report them, `get_screen`/`edit_screen` resolve
  them through the same bean store (Button `onClick` registration is
  skipped — custom views have no logic file).
- `project_summary` now reports each activity's declared logic side,
  read through the code generator's own accessors (`eC.e/k/j/i/g`):
  component ids, variable names, list names, more-block names, event
  count. Slice-4 groundwork pinned down: an activity's blocks live in
  `eC.b(javaName)` as a map keyed `targetId_eventName`, one event's
  blocks in `eC.a(javaName, key)`, events in `eC.g(javaName)`.
- **Slice 3 device-verified (2026-10-18, build `ff0044b`):** all six
  golden tests passed on a fresh project — create project, add screen,
  edit screen opening cleanly in the real editor and building, root
  background + sizing, reject leaving the project untouched, approved
  delete. One finding from the user's own screenshots: the asked-for
  "vertical layout" never materialized — widgets sat loose on the
  editor's root, which the visual editor renders fixed and
  untouchable (`ViewPane` marks it fixed; `ViewEditor` swallows
  touches on it), so the user can never select, pad or restyle the
  screen as a whole. Adopted standard (user's call, code-verified):
  building into an EMPTY screen first adds a real main layout
  (vertical LinearLayout, match_parent × match_parent; inside a
  ScrollView when the model opens with one), and every later
  top-level add is redirected into it. Synthesized and frozen like
  app-allocated ids, so the approval card shows it and execution
  replays it exactly. Screens with existing widgets are never
  restructured. Approval cards also gained a dim + three-dot working
  state while an approved change applies (`014eace`).
- `delete_activity` added (deletes always ask): removal persists
  through ManageViewActivity's own save sequence — both file lists
  written back, then `eC.a(hC)` reconciliation drops the deleted
  screen's widgets/events/blocks; an activity's drawer file is
  removed with it, as the screen manager does. The main screen is
  refused (it is the app's starting screen).
- Property ops round two: `set_image`/`set_scale_type`
  (`image.resName`/`scaleType`), `set_background_image`
  (`layout.backgroundResource`), `set_checked`/`set_enabled`
  (`ViewBean` fields), `set_text_style` (`text.textType`),
  `set_input_type` (`text.inputType`) — the property editor's own
  fields (`ViewPropertyItems`), gated to the widget kinds carrying
  them.
- Slice-4 (blocks) storage facts, from `LogicEditorActivity`: one
  event's blocks read via `eC.a(javaName, id + "_" + eventName)` and
  saved by the same 3-arg `eC.a(javaName, key, blocks)`; the full
  map is `eC.b(javaName)`; events register via
  `eC.a(javaName, 1, type, id, eventName)` (already mirrored for
  Buttons). `BlockBean` carries `id`, `nextBlock`, `subStack1/2`,
  `opCode`, `spec`, `type`, `parameters`, `paramClassInfo`,
  `classInfo`. Open before building: how the editor canvas assigns
  block ids and next/sub-stack links (construction lives in JAR
  classes, not repo sources) — derive from saved examples + the
  code generator's walk (`Fx`/`Hx`) before proposing any block.
- The floating button joined the surface: `edit_activity` can toggle
  it (with the design-library `useYn` flip the screen manager
  performs), and `edit_screen`'s `set_fab` sets its icon/background
  through the data manager's own fab store (`eC.h`), gated on the
  activity actually having one, with snapshot rollback.
- Repair note: a mid-session workspace restore silently dropped the
  `CreateCustomView` class body and appended a duplicated fragment to
  `AgentTools.java`, and a `Locale` import was missing — commits
  `489ffdb`/`ad601da` therefore never compiled. All three defects are
  fixed together; treat `47687db` as the last commit whose tree was
  structurally sound before this repair.
- Build-loop groundwork: the in-app builder is
  `a.a.a.ProjectBuilder` (resources → javac → dex → sign), driven
  from `DesignActivity` with a progress receiver; a `build()` tool
  must run it headlessly and harvest its error text — its own slice,
  after event wiring.
- One-time project backup (slice 2) precedes the first edit too.
  Approval details warn when the screen might be open in the editor.
- Still open from the risk register: a hard editor-open lock (the
  revision gate + warning cover the common cases), design-token
  palette picking and plan-first construction (edit ops resolve
  tokens, but palettes arrive with whole-screen planning).
- Colour honesty (from two device screenshots): (1) when the user
  says "show me" colours, the model was hiding a guessed hex in an
  approval card — now the guidance requires naming options and
  ending the turn, and a new interactive `ask_color` tool opens
  Sketchware's own `ColorPickerDialog` (the same PopupWindow the
  editor's colour property row uses) so the user picks by eye; the
  pick returns as a tool result and any change still needs a card.
  Cancelling the picker is a normal `{cancelled:true}` result,
  never an error, so it can't trip the same-failure stop.
  (2) "a lighter shade of the primary" was applied as the exact
  `@primary` — derived tints are now computed app-side from the
  project's real colour: `@primary_tint/_light/_pale` and
  `@accent_*` (40/65/85% toward white), so shades are arithmetic,
  not invented hex. (3) "agree first / no coding yet" now means no
  tool call at all, and a later "yes" only approves the
  interpretation stated back in the user's own words.
  **Device-verified 2026-10-18** (build `0ad290e`): the tint card
  named #FFCDA291 — exactly 40% from the copper primary #FFAC6448;
  the picked #FFEF9A9A still needed a card; closing the picker
  produced a plain-language ask, no error; agree-first produced no
  card and the three tints were offered by name; container-first
  led the EmptyActivity card ("First, the main layout to hold the
  screen"). One blemish: after a pick the model asked in chat *and*
  showed the card — fixed by instructing straight-to-card after a
  pick. Slices 1–3, container-first and the colour work are now all
  device-verified; next is slice 4, `set_event_handler`.
- Slice 4 (`set_event_handler`) built 2026-10-18, pending CI +
  device verification. Ground truth was extracted from the app's
  own JAR classes (`lq`/`xB`/`Rs` in the LFS payloads, disassembled
  string-switches): an event's blocks are a flat list keyed
  `targetId_eventName`, first bean is the head, links are integer
  ids, and `Fx` generates from the head. New block shapes come from
  the runtime tables themselves — `lq.a` (parameter slots),
  `xB.b().a` (canonical spec), `kq.a` (palette colour), `oq`
  (valid events) — never hand-copied literals. The tool appends a
  short statement chain (toast, set_text, set_visibility,
  set_enabled, finish) after the event's existing tail; the event
  is registered through the same manager call a Button drop uses
  when missing; approval, backup, commit and rollback mirror
  `edit_screen`; the proposal dry-runs the app's own `Fx`
  generator over the combined list before anything is saved, and
  the apply step verifies by read-back chain walk. Reporter
  (value) blocks, control flow and intent navigation stay out of
  this slice — they need the component-creation path, still
  unmapped.
- Device test of slice 4 (2026-10-18): toast append, run, text
  append and both rejections behaved; "(Runs after the 2 blocks…)"
  proved chaining. Activity events FAILED after approval: the
  proposal froze the resolved target id (the event name itself for
  activity events) and the executor re-read it as a widget id
  (`E_NO_WIDGET`). Fixed in `ade3295` by freezing the target FORM
  ("activity"), plus a corrective `E_BAD_TARGET` message, plus
  guidance that the user's newest message always wins after the
  agent re-proposed an unwelcome failed job over a new request
  (`81c3279`). Awaiting re-test.
- Build slice (`build_project`) built 2026-10-18, bundled with the
  two fixes above for a single device download. It mirrors the
  Design screen's build task step for step (`AgentBuild`) with
  Sketchware's own `ProjectBuilder`; compiler diagnostics surface
  through the same `zy` the app shows; the result reports the APK
  (path verified to exist, size, seconds) and nothing in the
  project is modified beyond the build/staging folders the app
  itself wipes each build. `get_build_errors` is folded into the
  build result (capped diagnostics) rather than a separate tool.
  Device test (2026-10-18): welcome toast on start, clean compile
  (100 KB, 2s), rejection honoured — the `ade3295`/`81c3279` bundle
  verified.
- Install continuation (built 2026-10-18, awaiting device test):
  a Done build card keeps its APK's path/size/mtime and grows an
  Install button; tapping it fires the editor's own installer
  intent (FileProvider ACTION_VIEW on `finalToInstallApkPath`).
  The row re-stats the file on every bind: same fingerprint →
  "Install · Built HH:mm · size"; different file → "Install latest
  build" with a note that it is probably the editor's build and
  installing installs that one (the editor's Run wipes the staging
  folder and rebuilds to the same path); file gone → disabled with
  "ask the agent to build it again". The fragment re-checks at tap
  time too, so a swapped build is never installed silently. Not a
  model tool: the tap is consent, and the tool description tells
  the model to point at the card rather than rebuild when asked to
  install an unchanged app.
- Build-card follow-ups (built 2026-10-18, bundled before any new
  download, awaiting device test): (a) the Install row only
  validated on bind, so returning from an editor build showed a
  stale label until any rebind — the fragment now rebinds build
  cards on onResume/onHiddenChanged; (b) a newer successful build
  of the same project supersedes older build cards (they keep
  their outcome line, lose the button, note says install from the
  latest card); a different project's card is untouched; (c) the
  "build after wiring behaviour" wording that made the model build
  after every change is gone from both the tool description and
  the loop guidance: build on ask (build/install/run), or to check
  a fix for errors a previous build reported, never chained onto
  routine edits. Device test of the install slice before these
  fixes: install-from-card, editor-swap honesty after a rebind,
  and conversational point-to-card all behaved. Build-card bundle
  device-verified 2026-10-18.
- Variables slice (built 2026-10-18, bundled with the build-card
  bundle for one download; awaiting device test):
  (a) new `create_variable` tool — declares one boolean/number/
  string variable on an activity through the editor's own add
  call (`eC.c(javaName, type, name)`), with the editor's own name
  rules (ZB pattern, `uq.b` keywords) plus duplicate checks
  across variables, lists, components and widgets; commit +
  read-back verify. Map/list variables deferred (components and
  the intent path are next in the logic line).
  (b) `set_event_handler` vocabulary grows: `set_variable`
  (literal or `from_variable` copy, type-checked against the
  declaration), `increase`/`decrease` for number variables, and
  `from_variable` on `toast`/`set_text` for string variables.
  Variable values use the editor's stored shape — a `getVar`
  bean whose spec is the variable name, referenced as "@id"
  (`Fx` resolves it raw), with the palette type letters from
  `ExtraPaletteBlock` (b/d/s). `project_summary` now reports
  each variable's type. Variables are read via `eC.k`, the
  accessor the editor and code generator share.
  (c) Guidance honesty rule: never blame the app for the agent's
  own gaps or invent manual walkthroughs — say "I
  can't do that yet" plainly (prompted by a device answer that
  blamed Sketchware for missing variables).
- Block census (`docs/BLOCK_CENSUS.md` + `docs/block_census.json`,
  2026-10-18): all 584 palette blocks enumerated from the app's
  own sources (palette builders, `lq` stock table, Pro block data,
  `Fx`). The translator goes data-driven from here; waves are
  (1) block editing (replace/remove, fixing "change means
  append"), (2) literal-only statements + `edit_project_theme`
  (theme colours in `lC.b(scId)`, five keys incl. controls),
  (3) reporter values everywhere, (4) control-flow containers,
  (5) components (Intent first), (6) map/list families. Raw-source
  and hosted-backend families stay out by design.
- Wave 1 + first statement pack (2026-10-18, CI-pending):
  (a) Block editing: `get_event_logic` renders one event's stored
  statements numbered (paths like [2], [1,2] inside containers);
  `edit_event_block` changes a value in place (toast message,
  set-text text, string/number/boolean variable values) or removes
  a statement by re-linking nextBlock/subStack1/subStack2. The
  event's first stored bean is never removable (Fx generates from
  index 0). Both plan against a fresh read, refuse on count/path
  drift (`E_LOGIC_CHANGED`), back up, save via `eC.a`, commit, and
  verify by read-back; replace additionally proves the after-state
  generates different code via an Fx dry-run.
  (b) `edit_project_theme`: the five theme colours from
  `MyProjectSettingActivity.themeColorKeys`, read/written through
  `lC.b(scId)`; yq generates colors.xml from that registry at
  build time, so read-back is the verification. Drift between
  card and tap refuses (`E_THEME_CHANGED`), backup first.
  (c) Statement pack added to `set_event_handler`: set_text_color,
  set_hint (EditText), set_clickable, set_image_url (ImageView,
  Glide emission), copy_to_clipboard, set_screen_title,
  open_drawer/close_drawer, load_url (WebView), set_progress
  (SeekBar). Every opCode, slot list, and Fx emission checked
  against `lq`/`Fx`; `setBackgroundColor` and
  showKeyboard/hideKeyboard were dropped — provably absent from
  Fx, so they would generate nothing.
  (d) Three device-glitch fixes: a pending/working approval card
  is closed as FAILED with a plain outcome when its turn dies
  (stop, error, view destroyed); guidance now forbids re-issuing
  a just-succeeded create and treats a follow-up E_NAME_TAKEN as
  the earlier success (tool message says the same). The
  model/key switch transcript lines were already in place.
- Placement smartness (2026-10-18, from device finding: a WebView
  added at the screen's top level after the match_parent main
  layout was stored correctly but parked off-screen):
  (a) Default parent: on a screen that already has widgets, an
  `add_widget` without a parent now lands in the screen's main
  container (first top-level match×match layout; a ScrollView
  resolves to its inner layout) instead of the top level; the
  parent is frozen into the op at proposal.
  (b) `visibilityWarnings`: a deterministic app-side check on
  every simulated edit flags widgets stranded after a
  match_parent sibling in a bounded LinearLayout (root included),
  exempting ScrollView subtrees; warnings appear on the approval
  card and in the tool result so the model fixes placement.
  (c) `get_screen` now renders the screen as an indented tree in
  stored order instead of a flat list with parent tags.
- Control flow + reporter values (2026-10-18, CI-pending):
  `set_event_handler` blocks can now nest: if/if_else/repeat/
  forever/break built through subStack1/subStack2 with the app's
  own opCodes and type letters (checked against Fx's generator:
  `if (%s)`, for-loop repeat, while(true), break;). Conditions
  and counts are recursive value expressions — variables,
  literals, + - * / %, < > =, and/or/not — stored as reporter
  beans referenced "@id", variables keeping the getVar shape.
  Caps: 8 per list, 3 containers deep, 24 statements total.
  Remaining waves: view/component reporters (e.g. text-of),
  components (Intent first), map/list variables.
- Weak-model resilience (2026-10-18, from device screenshot:
  edit_screen "failed twice" on moving webview1 into "linear 1"):
  (a) Widget/parent lookup in edit_screen and wiring now tolerates
  the user's spacing/case ("linear 1" resolves to linear1); ids
  never contain spaces so this cannot confuse real ids.
  (b) E_NO_WIDGET/E_NO_PARENT now list the screen's actual ids, so
  a wrong guess is correctable in one retry instead of two.
  (c) The loop's same-failure stop message now includes the actual
  last problem, so the user sees why it stopped.
  (d) Guidance: fix placement (move into the main container, or
  sizes/weights) instead of restructuring screens unprompted.
- View reporters wave (2026-10-18): expressions can now read
  widgets live - {"text_of":widget} (getText, s),
  {"url_of":webview} (webViewGetUrl, s), {"checked":checkbox}
  (getChecked, b), {"enabled":widget} (getEnable, b),
  {"progress_of":seekbar} (seekBarGetProgress, d); widget kind
  checked against each block's slot menu. "=" between text
  expressions routes to stringEquals (Fx emits .equals) instead
  of the numeric == block. Card lines read "text of edittext1",
  "checkbox1 is checked", etc.
- Components wave 1: Intent (2026-10-18): new statement
  open_screen {screen, component?} = the editor's intentSetScreen
  + startActivity pair over an Intent component. Existing Intent
  component reused; when none exists, apply() creates intent1 (or
  next free) via eC.a(javaName, COMPONENT_TYPE_INTENT, id) - the
  component picker's own call - named on the approval card and
  verified by read-back. Statement chains are now linked from
  their tail (top level and nested), since open_screen is two
  blocks. New reporter {"extra":"key"} = intentGetString.
- Lists & maps wave (2026-10-18): create_variable now declares
  map variables (eC.c code 3) and lists (list_string/list_number/
  list_map via eC.b, the variable dialog's list callback; eC.j
  codes 1=ListInt, 2=ListString, 3=ListMap per mq.b/Jx).
  project_summary reports list kinds. Statements: add_to_list,
  clear_list, show_list (listSetData on a ListView),
  put_in_map, clear_map, new_map. Reporters: list_length,
  item_of (getAtListStr/Int), list_has (containListStr/Int),
  map_get, map_has. All Fx-verified; slot counts re-checked by
  translate's lq table pass. List-of-maps item access stays out.
- Reorder + anti-rebuild (2026-10-18, from device screenshot:
  agent rebuilt the screen to reorder one widget and corrupted
  textview1 into a layout): move_widget gains before/after a
  sibling id (reorders the sibling group by index, destination
  inferred from the sibling when parent omitted). A batch that
  removes 2+ widgets and re-adds one by name is refused
  (E_REBUILD) - one-for-one replacement stays allowed. Guidance
  and tool docs: never remove+re-add to reorder. Size values
  tolerate fill_parent and "120dp"/decimals.
  (E_REBUILD) - one-for-one replacement stays allowed. Guidance
  and tool docs: never remove+re-add to reorder. Size values
  tolerate fill_parent and "120dp"/decimals.
- CRASH FIX (2026-10-18, device: editor crashed opening a button
  handler with a comparison): variables inside reporter beans
  (comparisons, calculations) kept their "@var:" placeholder -
  the resolution pass only scanned statement beans. Written to
  disk verbatim, the editor crashes parsing it as a block id.
  Pass now scans every created bean, and a guard refuses the
  proposal if any "@var:" survives. Handlers already saved with
  the placeholder still crash the editor until the bad block is
  removed (edit_event_block can, it never parses param refs).
- Batch from comprehensive device test (2026-10-18): set_event_
  handler failed with Unknown block "" whenever the event already
  had blocks (models echo "op"/stored opCodes after reading an
  event). blockKindOf() now accepts block/op/kind/opCode and maps
  stored opCodes (doToast, setText, ifElse, setVarInt...) to the
  statement vocabulary in both buildStatement and translate.
  Visibility warning no longer advises moving a widget INTO the
  full-height sibling ("into webview1"); it says move before it.
  Passed on device in the same run: open_screen + initializeLogic
  toast, list declare/add/show on SecondActivity, theme via
  picker, move-before reorder.
- Batch 2 from device test (2026-10-18): (a) block names also
  accepted as type/action/name, and inferred from shape
  (condition->if/if_else, times->repeat, screen->open_screen) -
  "no block name" failures. (b) "=" between booleans routes to
  the boolean "=" (models write checked == true). (c) The Fx
  generation proof falls back to the fresh blocks alone, so an
  older broken handler in the same event cannot veto new work
  (the corrupted taps handler blocked the edit-text test).
  (d) Removing a statement now drops its orphaned reporter
  beans (incl. "@var:" carriers) - the rescue path for the
  corrupted handler. (e) Guidance: never mirror a widget's
  value in a variable to compare it.
- Schema + editor-load gate (2026-10-18, user-directed after
  deep research into LogicEditorActivity/Fx): set_event_handler's
  blocks parameter now has a real item schema (required "block",
  enum of kinds). Unknown-block errors quote the received JSON
  (abbrev 300) so the model self-corrects and failures are
  visible, not guessed. editorLoadProblems() mirrors
  loadEventBlocks: non-numeric ids, "@..." params that don't
  parse/resolve, parameter count != spec slot count (Fx's own
  regex; getVar exempt). Gate runs on fresh beans at proposal,
  at wiring apply (fresh problems refuse; older ones surface as
  a named warning), and on the full result of edit_event_block.
  Same-failure loop signature now includes the payload hash, so
  a different second attempt is not killed as a repeat.
  Same-failure loop signature now includes the payload hash, so
  a different second attempt is not killed as a repeat.
- Chain-join fix (2026-10-19, device: proposals correct but
  apply failed "new blocks are not on the event's chain"):
  joined() linked the old chain's tail to plan.fresh.get(0),
  which for an if is a condition REPORTER created before the if
  bean - the new chain hung off a dead-end reporter. Both the
  proposal dry run and apply now join via freshGenerationList
  (chain heads first), and apply stores the combined list in
  editor order (editorOrder: main chain from element 0, the
  order Fx eventBlocks.get(0) and loadEventBlocks assume).
  order Fx eventBlocks.get(0) and loadEventBlocks assume).
- set_variable from expression (2026-10-19, device: map test
  toasted an empty variable): set_variable's value may now be
  any value expression (map_get, text_of, calculation, ...) -
  the setVar block's value slot takes the reporter, slot-kind
  checked against the variable's declared kind. Before, only
  literals and variable copies could be assigned, so a map's
  value could never reach a toast.
- create_component tool (2026-10-19, device: agent wrongly
  told the user it has no components tools, despite having
  created intent1 itself via open_screen): dedicated tool adds
  an Intent component via the picker's own eC.a(javaName,
  COMPONENT_TYPE_INTENT, name), name defaulting to the next
  free intentN across all namespaces, verified by read-back.
  Honest scope in the description: Intent only.
- Function expressions wave (2026-10-19): {"fn":name,"a":...,
  "b":...} reporters, all Fx-verified - mathAbs/Ceil/Floor/
  Round/Sqrt/Pow/Max/Min, random, mathPi, stringLength,
  stringJoin (concat), stringContains, toUpperCase/toLowerCase/
  trim, stringSub (from/to), toNumber, toStringWithDecimal
  (to_text). fn return kinds feed the "=" text/boolean routing;
  card text reads "length of …", "… joined with …", etc.
- 2026-10-19 wave 7 (stock teaching, from `docs/STOCK_BLOCK_KNOWLEDGE.md`):
  File family (write/delete/make folder/copy/move/copy folder/rename/
  list files + file_exists/is_file/is_folder/read_file/file_length/
  external_dir/app_dir/last_segment/starts_with/ends_with reporters),
  remaining List statements (remove/insert/set/reverse/shuffle/sort/
  swap, list-of-maps add/insert/set/get entries), map remove-key/
  keys-to-list/size/empty/has-value, index_of. Agent-known opCodes
  79 → 121 of 584. translate() slot validation now falls back to the
  xB spec tokens for Pro-defined blocks (editor's own shape source).
- 2026-10-19 wave 8 (stock teaching): all remaining Math reporters
  (trig/log/exp/degrees/dip/display size), string functions (replace,
  index_in, matches, reverse_text, is_empty, parse_int, format_number,
  substring_from), JSON conversions (map/list from/to JSON), widget
  value reporters (geometry for any widget + RatingBar/SeekBar/
  ViewPager/Spinner/EditText-selection/VideoView/WebView getters),
  and a registry-driven widget-action family (57 View statements:
  text size, backgrounds, gradient/stroke corners, gravity, focus,
  add/remove child views, video/web controls, autocomplete data...).
  Agent-known opCodes 121 → 228 of 584.
- 2026-10-19 wave 9 (stock teaching): Control containers whileLoop,
  switchNum/switchStr with case/default chains, tryCatch, continue,
  ternary reporters, drawer_open; plus the widget-gated View families
  via the registry (ListView, Spinner, ViewPager, RecyclerView,
  GridView, TabLayout, CardView, TimePicker, CalendarView,
  TextInputLayout, BadgeView, PatternLockView, OTPView, Lottie,
  CodeView, MapView, SwipeRefreshLayout, BottomNavigationView) and
  their reporters. Agent-known opCodes 228 → 328 of 584. Deliberately
  still out: custom-view data binding blocks (adapter mechanism),
  listener containers, FAB's widget-less blocks, bitmap-file ops.

## Wave 10 — components (2026-10-19)

- `create_component` now adds any of the 31 stock component types.
- ~71 component statement blocks + Timer/Dialog-button containers +
  22 component/auth/network reporters, all gated on the named
  component existing on the screen with the right type (the same
  gate the editor's palette applies). Coverage 328 → 424/584.
- Deliberately not yet taught: listener-container blocks (Firebase
  child events etc.), FirebaseAuth sign-in/storage actions, rewarded
  ads, and a handful of blocks whose stored picker-value formats are
  unverified — researched and recorded, not guessed.

## Wave 11 — verified leftovers (2026-10-19)

- ~100 more blocks taught, every one traced to its emission and
  stored shape in Fx/Lx/BlocksHandler first: intent flags, animator
  easing/repeat modes, Firebase sign-in and storage, Bluetooth
  connect/send, rewarded ads, permissions, custom toasts, the
  floating-button trio, bitmap file ops, typed map stores, menu
  blocks, indexed repeat loops, and the remaining reporters.
- Deliberately held for the next wave (evidence recorded, not
  guessed): component events and everything that hangs off them —
  date/time picker dialogs, AsyncTask, Firebase child events in
  their event form, custom-view list binding, listener containers.
- Coverage: 526/584 stock blocks.

## Wave 12 — component events and the values they pass

- Events for components: set_event_handler's target may now be a
  component id, validated against the editor's own event list and
  registered exactly as the Add Event screen registers it, so a
  Firebase DB, sign-in flow, network request, sensor, camera — every
  component — can now say what happened. Appending, reading and
  editing those handlers works the same as widget handlers.
- Event values: {"arg": "childKey"} and friends read what an event
  hands over (child keys/values, success flags, error messages,
  progress, responses, picked dates), including pulling one value out
  of a passed-in set with a map read. The names come from the
  generators' own declared locals, per event.
- New last-block and component blocks: return_fragment/return_title
  for pager pages, return_text and progress publishing for AsyncTask
  (now creatable via create_component type "asynctask", started with
  asynctask_execute), and the date/time picker dialogs whose answers
  arrive in onDateSet/onTimeSet handlers.
- specFor now falls back to the Pro palette's own spec text, so
  stored block shapes match what the editor itself stamps.
- Coverage: 533/584 stock blocks. Remaining: custom-view list
  binding, the MenuItem/SubMenu pair, and the by-design exclusions.

## Wave 13 — custom list rows (data binding)

- A ListView, GridView, RecyclerView, ViewPager or Spinner can now
  be filled from a list of maps through a custom item layout:
  create the layout as a custom view, attach it with edit_screen's
  set_list_item_view, bind data with the *_custom_data blocks, and
  dress each row in the widget's onBindCustomView handler — where
  the row's own widgets are the targets, {"arg": "position"} is the
  row number, and list_map_value reads that row's values.
- Coverage: 538/584 stock blocks.

## Wave 14 — menus, listener bodies, and the last singles

- Options menus inside onCreateOptionsMenu: named menu items and
  submenus the handler can later show, hide or disable.
- Listener bodies: otp_listener (the entered code is an event
  value inside) and youtube_listener (load a video when ready),
  plus crop_image and youtube_lifecycle.
- Stock-block census closed out: 556 of 584 blocks taught; the
  remaining 28 are documented exclusions (raw-source blocks, unsafe
  typed map reads, dead/junk blocks, More-block-only returns), each
  with its reason in docs/STOCK_BLOCK_KNOWLEDGE.md.

## Wave 15 — task journal, style brief, clean slate

- The app now keeps its own memory of agent work per project
  (ai/AgentJournal, tasks/<scId>.json under the agent root): each
  task with the steps that landed, were turned down, or failed, plus
  why an unfinished task stopped. The loop titles tasks from the
  user's message, records mutations as they execute, and closes the
  task on every exit path (answer, quota/error, empty reply, limit,
  repeated failure, stop) — interruption pauses, never loses.
- Every turn's system prompt carries a short verified brief: the
  style brief, the unfinished task with its finished steps, recent
  task titles. A fresh key or model resumes from the first
  unfinished step after re-checking the real project state. New
  tools: set_style_brief, get_task_log. Guidance adds resume rules
  and layout sense (match_parent vs wrap_content, spacing/type
  consistency, kids'-app direction).
- Clean slate: the working project is session-only now
  (AiProviderConfig no longer persists it). Reopening the agent
  starts with no project chosen; each project's journal waits
  inside it.

## Wave 16 — composer, Plan/Task log, task strip

- The composer is one rounded Material surface: text field, an
  in-box action row (Plan — the style brief, viewable/editable;
  Task log — the recorded tasks, clearable only by the user) and
  send. The top-bar history icon opens the same task log.
- A slim strip above the composer appears only while a task runs
  or sits paused, naming the task and the stop reason; Resume
  reopens the same journal entry and continues it.
- The project card settles into a compact chip once a project is
  chosen.

## Wave 17 — chat persistence; the task follows the work

- Device test: after creating a project and closing the app at the
  next approval card, the new project had a style brief but an empty
  task log, and the chat was gone. Causes: the create step was
  journaled under the previous project, tasks were only written when
  the first change landed, and the chat was never persisted at all.
- Tasks are now written when a turn starts (a finished turn with no
  steps was conversation and leaves no entry); a created project's
  steps journal under itself; at bind time the task and any agreed
  style brief carry into the new project.
- Conversations persist per project (ai/AgentChatLog,
  chats/<scId>.json — user/assistant text only, capped at 100) and
  return when the project is picked again; the conversation that
  creates a project follows it.

## Wave 18 — History means conversations

- Device test: the top-bar history icon opened the same task log as
  the in-box button — a duplicate. Task log stays where it is (the
  record of work); the history icon now opens Conversations: the
  projects that have a saved chat, and picking one binds the project
  and brings its conversation back.
- Companion fix from the same round of testing: a style brief agreed
  in the turn that creates a project now moves to that project (it
  was filed under whichever project was selected when the style was
  agreed, then filed again in the new one). Briefs record when they
  were saved, and only a brief from that same turn moves.

## Wave 19 — plan memory, drafts, layout rules

- The journal holds an agreed implementation plan per project;
  done steps are clamped to changes actually recorded, and a
  stopped turn leaves the current step honestly paused. set_plan
  saves/updates it; the turn brief carries plan progress (and the
  "style saved, no plan yet" state).
- Style/plan agreed before a project exists are held as drafts and
  deposited into the project the conversation creates; a same-turn
  plan also moves at bind like the style brief.
- Guidance: style-then-plan-then-build order; full layout rules
  (per-child wrap/match/weight with 0dp weighted sides, padding vs
  margin, composed clickable cards vs plain Buttons).

## Wave 20 — Style/Plan UI, project lock

- The composer row is Style · Plan · Task log: the old Plan button
  is now Style (Project style page), and a real Plan button shows
  the saved plan with ✓/●/○/❚❚ progress marks.
- The working project is locked while a turn runs (card disabled;
  picker guarded), so context cannot cross projects mid-flight.
- A quiet "Checking the project style and plan" stage line opens
  turns where either is still missing.

## Wave 21 — app-side design warnings

- Deterministic advisory layout checks over the resulting screen,
  riding the existing proposal-warning channel: weight without a
  0dp axis side, wrap-width text inputs, full-width ordinary
  buttons, sub-40dp touch targets, sub-12sp text, fixed widths
  over 360dp. Named widget + plain fix, at most six per card.

## Wave 22 — Project style renders as a design sheet

- The style page now sets its headed sections (Audience, Colours,
  Text sizes, Buttons and cards, Spacing, Do, Avoid) in bold with
  breathing room instead of one flat paragraph; older one-paragraph
  styles show unchanged.

## Wave 23 — conversation export

- New export icon in the model bar: one JSON per project with the
  whole conversation — chat messages, approval and build cards
  with outcomes, the Style, the Plan and the Task log, plus the
  provider and model names. API keys are never included. Written
  to .sketchware/ai/exports and offered through the share sheet.

## Wave 24 — the Plan moves while work lands

- As changes land, the plan's step in hand is marked current
  automatically; the strip above the composer follows it ("Plan
  step 3 of 7 · ...") and refreshes on every resolved approval,
  and an open Plan page re-reads itself while a turn runs.
  Completion still only comes from set_plan updates checked
  against recorded changes — live, but not self-awarded.

## Wave 25 — editor crash from agent-made ImageViews (found on device)

- Crash report: opening an agent-created project killed the editor
  (NPE at ViewPane.updateItemView: resName.equals on null). Cause:
  ImageBean leaves resName null unless someone sets it; the app's
  own palette writes "default_image", but the agent never did — an
  agent ImageView with no set_image saved resName=null, and the
  editor dereferences it on load. Fixes: agent-created ImageView /
  CircleImageView now default to "default_image"; simulate()
  normalizes any null resName it saves (healing screens broken by
  older builds on their next edit); ViewPane's two equals checks
  are null-safe so no malformed project can crash the editor here.

## Wave 26 — memory that no model can lose, design that is enforced, and honesty about what breaks

Everything below came from a real session the user exported and from
projects they built and opened on their own phone.

**1. No duplicate work, whoever is driving.** Every applied change
stores a fingerprint (tool + its arguments, key order ignored) in the
journal. A proposal of the same change later — after a retry, a
resumed task, or a completely different model — is refused by the app
before an approval card exists; the model is told it is already done.
`edit_project_theme` checks the project's live colours first: colours
that already match produce no card at all.

**2. A settled Plan is a contract.** `set_plan` no longer rewrites an
existing plan: settled steps keep their text, order and progress, and
only genuinely new, non-conflicting steps can be appended (and that
is logged). On resume a paused step wakes up as the step in hand
(`resumePlan`), so a stopped plan step is never left lying.

**3. Tasks continue instead of ending at a cap.** The cap is now a
segment boundary, not a stop: at 24 calls the loop rolls into the
next part of the same task (up to 6), answering the calls it cannot
run as deferred so the provider contract stays intact, and re-sending
the task's memory — journal brief plus a fresh snapshot of the
project — at the seam. The user does not re-request anything.

**4. The live activity line is a heartbeat, not chat.** One line,
rewritten in place, naming the exact thing: "Checking btn_sign_in
onClick in AuthActivity", not "Looking at what onClick does" five
times. It disappears when the turn ends; what it described is written
into the Task log as a check (screen, widget, event), which is
permanent and searchable.

**5. Design is applied, not only advised.** Newly added widgets are
corrected deterministically before saving: inputs fill a form's
width, a form's action button spans it and is at least 48dp tall, and
no widget is left labelled "EditText". Values the agent set itself
are never overwritten. A spinner now warns that it will stay on
screen until logic hides it.

**6. Logic that cannot be right is refused.** A true/false variable
can no longer be set with an empty value — the app used to read that
silently as false, which is how one flag ended up meaning both
"running" and "paused". New: `AgentLogic.danglingWidgetReferences`
reads every event of every screen and finds blocks naming a widget
that is not on their own screen — the exact class of bug that
compiles cleanly and then crashes on launch with a null view. It runs
after every mutation (the agent is told, so it can fix it), in the
build report, and in every export.

**7. An export that can actually be used.** Approval and build cards
now survive a restart (the chat log stores them), so exports contain
them. Exports also carry the project's current state — screens,
widgets with sizes and text, per-screen variables (variables are per
screen, which is what makes a "missing variable" failure
diagnosable), components, events — and the broken-reference report.
Rejected proposals record what the card actually proposed, and the
journal keeps every stored field, so the Style's saved date is no
longer lost on re-save.

**8. Real task titles.** A resume with no subject of its own
("continue") takes the plan's name instead of "Work in progress".

### Wave 26 follow-ups (found in the StickerBoardTodo export)

- Build fix: a missing `java.util.ArrayList` import in AgentJournal
  broke the compile (caught by CI, not shipped).
- Widget types named by their Android class now resolve: a model
  asking for `androidx.recyclerview.widget.RecyclerView` gets the
  palette's RecyclerView instead of "unknown widget type". An
  unknown type now lists every type the palette actually has, so
  "FrameLayout" stops being retried.
- `onCreate` is accepted as Sketchware's `initializeLogic` (models
  name screen events the Android way; refusing them only made them
  try again).
- A missing variable now names the screens that do declare
  variables: "Variables belong to ONE screen — a variable declared
  elsewhere does not exist here (AuthActivity declares ...)".
- `edit_screen` accepts 20 operations per call instead of 12, so a
  whole screen fits in one proposal.

### Wave 27: the project's setup, not just its screens

Everything above reads the screens. This wave reads what a project is
**set up for** — the Configuration panel — because Sketchware refuses
some widgets and components until their library is switched on, and the
agent used to add them anyway, producing screens that could never work.

The gating table is the app's own, read out of its code rather than
guessed:

| Thing | Requires | Where the app says so |
|---|---|---|
| AdView widget | AdMob | `ViewEditor.e()` → `design_library_guide_setup_first` |
| MapView widget | Google Maps | `ViewEditor.e()` |
| Firebase DB / Auth / Storage / Phone Auth / Cloud Message / Google Login | Firebase | `InnerAddComponentBottomSheet` |
| Firebase Auth family | Firebase **and** its API key | `design_library_firebase_guide_setup_first` |
| Interstitial / Rewarded ad | AdMob | `InnerAddComponentBottomSheet` |
| FragmentAdapter | AppCompat | `InnerAddComponentBottomSheet` |
| Material widgets (CardView, MaterialButton, RecyclerView, TabLayout, …) | AppCompat **for the build**, not for the editor | `ViewEditor.e()` lists them; `yq` only adds AndroidX when `appCompat.useYn == "Y"` |

Two honest distinctions the research forced:

- **Refuse** only what the editor refuses (AdView, MapView, Firebase
  and ad components). The message names the switch: "In order to use a
  CardView widget, AppCompat / Material has to be set up in the
  Library Manager first", matching the app's own wording.
- **Warn, don't block**, for Material widgets: the editor happily adds
  a CardView with AppCompat off, but the build then lacks the AndroidX
  dependencies. `get_project_setup` reports that, and the guidance
  tells the model to say so instead of discovering it at compile time.

**Permissions needed no new machinery.** The app derives them itself
when it generates the manifest (`Ix` writes RECORD_AUDIO, CAMERA,
ACCESS_FINE_LOCATION from a per-activity bitfield, plus anything in
`Permission Manager`), so the agent only reports what is declared —
and is told not to invent permission steps.

New: `get_project_setup` reads libraries, permissions, asset/Java/
resource/native-lib/import files, ProGuard presence and the tail of the
last compile log. Libraries and permissions are also in every project
read, so the model cannot propose an impossible widget without
seeing the fact first.

### Wave 27b: the navigation drawer

"Component settings" in the Configuration panel gives a screen a
toolbar, fullscreen, orientation, a floating button — **and a
navigation drawer**, which the agent could not do, while silently
claiming to change "one activity's settings".

A drawer is not a flag. The app's screen manager (`Fw.b()`) creates a
whole layout file named `_drawer_<activity>` when the option goes on,
deletes it when it goes off, and switches AppCompat on, because a
drawer layout is AndroidX. Setting the bit alone would have produced a
screen with a drawer that has no content.

So `edit_activity` now takes `drawer: true/false`, and the action
keeps the file in step, enables AppCompat the way the screen manager
does, and — because the app stores drawers in the same file list as
custom views — the drawer layout is now visible to the agent's screen
reads, so `edit_screen` can fill it with menu items. It remains a
layout without logic, like a custom view.

### Wave 28: grounding — the agent could see a screen's widgets, not its names

A conversation (KidsNotes, sc_id 612) ran the same failure six times:
`"There is no component called  on this screen"` — an EMPTY name. The
model wanted a shared-preferences component and could not name one, so
it invented a blank, was refused, and tried again.

Three causes, all ours:

1. **`get_screen` never listed what a screen declares.** It returned the
   widget tree and the events, but not the components, variables or
   lists — so writing logic meant remembering ids from turns earlier.
   It now returns `components`, `variables` (with kind), `lists` (with
   kind) and `more_blocks`.

2. **A wrong name was a dead end.** "There is no component called X"
   did not say what *was* there. Every refusal of a component or list
   now names the ones the screen actually has (`describe(...)`), so one
   failed call teaches instead of looping.

3. **`save_pref` could not save a value — only a literal.** Text slots
   in component actions ran through `stringOf`, so
   `{"text2": {"var": "draft"}}` silently became an empty string. That
   is why "save what the user typed" ended in a toast and a finish: the
   block to do it did not exist. Text slots now accept
   `{"var": "name"}`, written as the `@var:` placeholder the existing
   getVar pass already resolves (no new block shape), refused for
   non-text variables because `putString("k", (int) n)` would not
   compile — convert with `{"fn":"to_text",...}`.

Then the recipe itself, in the prompt: one shared-preferences
component, `save_pref` to store, `pref_get` inside `set_variable` on
`initializeLogic` to read back, `pref_has` to test, `split_into_list`
to turn saved text back into a list — and "if it cannot be expressed
with the blocks you have, say so plainly; never fake it with a toast
and a finish."

A fourth, smaller fix: adding a second component of a kind the screen
already has now answers with a note naming the existing one — that
conversation's screen collected several shared-preference components
and used none.

**The chat screen lost its history on every restart.** The saved
conversation was only restored when a project was picked by hand
(`chooseProject`); a cold start called `showCurrentState()`, which
cleared the list. Cards were being written and were being exported —
they just were not on screen, so the history looked empty and an export
from that state carried no cards. `showCurrentState()` now restores the
working project's conversation, cards included.

### Wave 29: the export becomes the whole record

The export used to be a summary, and a summary cannot be debugged. A
step reading "wanted to wire AddnoteActivity — failed" tells you
nothing: not which component was named, not which block, not what the
screen actually had. So the journal now keeps a **timeline**, and the
export carries it whole.

Appended by app code as things happen (never by the model), in order,
per project, capped at 1200 entries:

| kind | what it holds |
|---|---|
| `user_message` | what was asked, the project, the model, base URL |
| `system_prompt` | **exactly** what the model was told (instructions, guidance, project snapshot) |
| `model_reply` | its answer, its reasoning, and every tool call it chose with arguments |
| `model_error` | provider failures: kind, HTTP code, message |
| `tool_call` | every call: arguments, result, outcome, error code + message, duration |
| `card_shown` | the proposal as the user saw it: title, body, details, payload |
| `card_approved` / `card_rejected` | the decision |
| `applied` | what landed, read back from the project |
| `apply_failed` | what was refused, with the exact message |
| `broken_references` | references that would crash on launch, as found |
| `turn_end` | finished or stopped, and why |

Derived sections so the file answers questions without reading every
line: `log_stats` (per tool: attempts, ok, failed, applied; model calls
and total time), `block_activity` (operations asked for, operations
refused, and every failure with its arguments and error), `environment`
(device, Android, app version, libraries switched on), and a
`reading_guide` that tells the next reader where to look first.

So: "did it save the note?" is now checkable — `save_pref` either
appears in `operations_asked_for` and lands in `applied`, or it appears
in `operations_refused` with the reason.

### Wave 30: design before you build

"Create a project" produced a blank project. No questions, no look, no
plan — the user was handed an empty app and no say in it. The guidance
already said "style first, plan second, building third"; it sat in the
middle of a 200-line brief and was advice, so a tool-calling model
under pressure walked straight past it to `create_project`.

Advice that can be skipped is not a rule. Three changes:

**1. The gate is the tool, not the prompt.** `create_project` refuses
with `E_DESIGN_FIRST` until a style brief AND a plan exist (saved on
the project, or held as a draft for a project not yet created);
`create_activity` refuses until a plan exists. The refusal says exactly
what is missing and what to do about it — it never sends the model to
write a style when the plan is the thing it lacks.

**2. Brainstorming needs somewhere to happen.** New interactive tool
`ask_choice`: the agent names two to four whole directions and the user
taps one (a blocking dialog, exactly like the colour picker). A
dismissed question is not a loop — the model is told to choose itself,
say why in one line, save it and carry on.

**3. Thin designs are refused, not accepted.** `set_style_brief` under
140 characters is rejected with the headings it must have (Colours with
hex, Text sizes, Buttons and cards, Spacing, Do, Avoid). `set_plan`
with fewer than three real steps is rejected with what a plan must
name: every screen, what is on it, what data is kept where, what each
button does, the order of work.

And the rule moved to the top of the brief — `DESIGN BEFORE YOU BUILD`
is the first thing the model reads, not the fourteenth.

### Wave 30b: the cuts

The user noticed the style brief being cut short no matter how long it
was, and asked whether the plan was cut too. Both were:

| what | was | now |
|---|---|---|
| style brief | 600 characters | 4000 |
| each plan step | 140 characters | 400 |
| steps in a plan | 24 | 40 |
| task-log detail line | 200 characters | 400 |
| `block_activity` failure arguments in the export | 600 | 1500 |

600 characters cut every real brief in half mid-sentence — a style with
its headings, hexes and sizes is longer than that, and a cut-off style
is one the builder cannot follow. 140 per step is what turned a
detailed plan into a list of stubs. The truncation appends "…", so
what the user saw was a sentence that simply stopped.

### Wave 31: the design system

A style says "sunny and playful". What building actually needs is what
a button IS in that style: 56 tall, orange #FF8A3D, 16 bold white text,
16 of padding, 8 of margin. Left in the model's head that number drifts
from screen to screen — which is why screens of one app end up looking
like different apps. So the design system is a third thing the user
agrees, between style and plan.

**Storage.** Per project, in the journal, as structured tokens — not
prose, because it exists to be looked up. Each token: `key`
(`primary_button`), `label` ("Big friendly button"), `kind`, `props`.
Held as a draft when no project exists yet and deposited into the
project this work creates.

**Two tools.** `set_design_system` saves the whole set (validated: a
kind's properties are only what the builder can actually put on a
widget — a design system promising a corner radius the tools cannot set
is a lie the user finds on screen; minimum four components).
`edit_design_token` changes ONE component, because "make the button
taller" must never rewrite the set the user agreed.

**The gate.** `create_project` and `create_activity` now need style,
design system and plan. Advice in a 200-line brief was skipped; a
refusal is not.

**The page.** A "Design system" button beside Style, Plan and Task log.
It opens a list of components; tapping one shows its properties as
plain lines and — above them — the component drawn with those exact
measurements: the real height, the real colour, the real text size. A
component is something you look at, not something you read. "Change
this" puts the request in the chat for the agent to do.

**Corrected, because the user caught me.** I first limited the token
fields to *properties*, and wrote here that corner radius and elevation
were unavailable. They are available — as **blocks**, not properties:
`set_corner_radius` (radius + background colour, any view),
`set_radius_and_stroke`, `set_elevation`, and `set_card_radius` /
`set_card_elevation` / `set_card_color` on a CardView. They have been in
`AgentLogic`'s block table all along — the StickerBoard agent invented
`set_card_radius` in a moment of not knowing it already existed. What I
had audited was the *property* panel, which is a different list. Both
fields are now in the design system, and the guidance says where they
belong: the screen's `initializeLogic`, not a widget's properties.

Screens already built are never restyled silently — the agent offers.

### Wave 31c: custom attributes (the Inject field)

The user pointed out something the property audit had skipped: every
widget in Sketchware's property panel has a **Custom attributes** row
(the `inject` field), and it is where a lot of design actually lives —
as raw XML attributes written onto the widget's tag.

The editor's own list (`PropertyInputItem.populateAttributes`):

| on | attributes |
|---|---|
| every view | `android:background`, `backgroundTint`, `alpha`, `visibility`, `padding`, `layout_margin`, **`elevation`**, `rotation`, `scaleX/Y`, `translationX/Y` |
| TextView | `text`, `textSize`, `textColor`, `textStyle`, `fontFamily`, `typeface`, `lines`, `maxLines`, `singleLine`, `gravity`, `ellipsize`, `hint`, `textColorHint`, `inputType`, `imeOptions`, `drawableLeft/Top/Right/Bottom`, `drawablePadding`, `drawableTint` |
| ImageView | `src`, `scaleType`, `tint`, `adjustViewBounds`, `cropToPadding` |
| LinearLayout | `orientation`, `gravity`, `weightSum`, `baselineAligned` |
| ViewGroup | `clipChildren`, `clipToPadding`, `animateLayoutChanges` |
| ProgressBar | `max`, `progress`, `indeterminate`, `indeterminateTint`, `progressTint` |
| CompoundButton | `checked`, `button`, `buttonTint` |
| CardView | `app:cardBackgroundColor`, **`cardCornerRadius`**, **`cardElevation`**, `cardMaxElevation`, `cardUseCompatPadding`, `cardPreventCornerOverlap`, `contentPadding`, `strokeColor`, `strokeWidth` |
| MaterialButton | `app:icon`, `iconTint`, `iconGravity`, `iconPadding`, `strokeColor`, `strokeWidth`, **`cornerRadius`**, `rippleColor` |

So corner radius, elevation and outline are available **twice**: as
properties through custom attributes (declarative, visible in the
editor) and as blocks (`set_corner_radius` / `set_elevation` /
`set_card_radius`) for runtime changes.

New `edit_screen` operation **`set_attributes`** writes them:
`{"op":"set_attributes","id":"card1","attributes":{"app:cardCornerRadius":
"8dp","app:cardElevation":"4dp"}}`. Each name must be `namespace:name`,
values must contain no spaces, because the layout writer strips them
(`Ox`: `inject.replace(" ", "")`) — 8dp, never 8 dp.

**The lesson, twice now:** properties, blocks and the Inject field are
three different lists. A thing missing from one says nothing about the
others, and an agent that answers from a single list will keep telling
the user something is impossible when it is not.

`AgentEdits` carries that same table (`ATTRS_VIEW`, `ATTRS_TEXT_VIEW`,
`ATTRS_CARD_VIEW`, `ATTRS_MATERIAL_BUTTON` …) and `allowedAttributes()`
uses the editor's own class tests — `classInfo.a("TextView")` for
"is or extends", `classInfo.b("CardView")` for exactly that class — so
the agent and the property panel can never disagree.

Because the lists differ per widget, `set_attributes` **rejects a name
that does not belong to that widget**, and says what does:

> `card1` is a `MaterialButton`, and it does not take
> `app:cardCornerRadius`. That one belongs to a CardView. Custom
> attributes for a `MaterialButton`: android:alpha, android:background,
> … , app:cornerRadius, app:icon, … .

The alternative — writing the attribute anyway — would put a line in
the XML that fails at build time, which is worse than a refusal the
model can act on.

## Wave 31e: six red commits, and why

Wave 30 (`bd0f13c`) broke the build, and so did every commit after it,
because I pushed six times without ever looking at CI. The user had to
tell me. These were the seven errors:

| file | error |
|---|---|
| `AgentLoop.java:762` | `JsonElement` used, never imported |
| `AgentFragment.java:1413` | `setItems(items, (which, ignored) ->` — the parameters are `(DialogInterface, int)`, so `which` was the dialog and `items[which]` did not compile |
| `AgentTools.java` ×4 | `stringOf(...)` — a helper from `AgentEdits`. This file's own helper is `stringArg(...)` |
| `AgentFragment.java:891` | `int textColour = designColour(...)` — it returns a hex `String`; `setTextColor` wants an int |

Three of them have the same cause: **I wrote Java from habit instead of
from the file I was standing in.** `AgentFragment` already called
`setItems` correctly three times — at lines 841, 1174 and 1951, all
`(dialog, which) ->`. `AgentTools` already had `stringArg`. I did not
look at either, and the user was right to ask whether I had read the
repo's own code at all.

The second cause: my verification was counting braces. Braces cannot
tell you a symbol is missing or a lambda has its parameters backwards.

### What now binds

1. **`scripts/check_symbols.py`** before every push. It is a poor
   substitute for javac (no Android SDK in this sandbox, so no real
   compile), but it catches the two errors that actually happened:
   a type used without an import, and a method called that the file
   never declares. Both real failures are reproduced in its tests.
2. **Before using a helper, grep the file for how it does it there.**
   Not what I would call it — what it calls it.
3. **Before an Android call with a lambda, grep the repo for that
   call** and copy the parameter order that already compiles.
4. **CI is read, not assumed.** No commit is described as done until
   the run for its SHA says so.

## Wave 32: variables nothing uses

The MagicalNotes export (sc_id 608, `gemini-flash-lite-latest`) shows
it exactly. Across two screens the agent tried to declare **eight**
variables:

| declared | used by any block? |
|---|---|
| `noteText` (NoteActivity) | written once (`set noteText to ""`), never read |
| `currentNote` (NoteActivity) | never |
| `notesListText` (MainActivity) | never |
| `savedNotesJson` (MainActivity) | never |
| `myNotesList`, `myNotes`, `savedText`, `notesList` | refused / failed |

Four rejected by the user by hand, four declared, and not one of them
ever read. The user's words: *"it kept on creating variables it's not
going to use — how do[ I ]know? Yes I do."*

Three changes:

1. **`create_variable` now requires `used_by`** — name the event and
   what it does with the value, or the call is refused
   (`E_UNUSED_VARIABLE`). No answer, no variable. The justification is
   shown on the approval card so it can be judged before approving.
2. **`remove_variable`** deletes one, using the app's own deletion —
   `LogicEditorActivity.m(String)` calls `jC.a(scId).p(javaName, name)`.
   It refuses while any block still names the variable
   (`E_VARIABLE_IN_USE`), because deleting one that a block names
   leaves the app unbuildable.
3. **An unused-variable audit** after every applied change, in the tool
   result and in the export as `unused_variables`: "declared but never
   read or written — wire it up or remove it."

The principle: a variable is not a declaration of intent, it is
clutter in the user's project until something reads it.

## Wave 32b: finish ends the event

The four lines the user read off the generated Java of one button:

```java
noteText = "";
finish();
file1.edit().putString("magical_note", noteText).commit();
SketchwareUtil.showMessage(getApplicationContext(), "… Note saved …");
```

Two failures in four lines.

**1. `finish()` was second.** Everything after it is dead code, and in
the block editor those blocks sit *under* Finish Activity, which is
what looked wrong on screen. `checkTerminators` now refuses a
terminator that is not last (`E_AFTER_FINISH`), naming the blocks that
would be dead, and refuses appending to an event that already ends
with Finish Activity. Repeat copies are refused the same way — the
MagicalNotes event had two.

**2. The note was never read.** `noteText` is set to `""` and then
saved, while `note_input` — the box the user actually typed in — is
not named by any block. The app ran, saved nothing, and announced
success. `unreadInputs` now reports any EditText no block reads:

> note_input is a text box, but no block on that screen reads what is
> typed in it — whatever the user types is thrown away.

Both checks join the audit after every applied change, and appear in
the export as `unused_variables`.

## Wave 32c: onCreate and initializeLogic are two different things

The user corrected me here, and the correction is right. I had written
that the model "keeps calling `onCreate`; Sketchware calls it
`initializeLogic`" — as if it were a rename. It is not.

The generated activity has both (see `Jx`, the code generator):

```java
@Override
protected void onCreate(Bundle _savedInstanceState) {
    super.onCreate(_savedInstanceState);
    … setContentView, view binding, permissions …
    initialize(_savedInstanceState);   // wires the views
    initializeLogic();                 // runs the blocks you wired
}

private void initializeLogic() { … your blocks … }
```

`onCreate` is the real Android lifecycle method, and it still exists.
Sketchware Pro's generator keeps it to framework work and calls
`initializeLogic()` — a separate private method that holds the blocks
the user wires in the editor. The split exists so the generated
onCreate stays readable instead of crowded with user logic, which is
exactly the reason the user gave.

So "onCreate" and "initializeLogic" describe the same *moment* — when
the screen is created — but only `initializeLogic` is an event blocks
can attach to.

**The bug this exposed:** the near-miss mapping (`onCreate` →
`initializeLogic`) existed on the *write* path but not on the *read*
path. `set_event_handler` quietly accepted it; `get_event_logic`
refused it with `E_BAD_EVENT`. So a model that put its blocks under
onCreate could not read them back. Both `E_BAD_EVENT` failures in the
MagicalNotes export are this. `probeEvent` now uses the same alias,
and the error text explains the difference instead of just listing
names.

## Wave 32d: the save_pref message, and the export's missing block tree

**A block that needs a component now says which one.** The old
message was:

> This block acts on a component and none was named. This screen has:
> file1, intent1.

It listed the ids without saying which kind `save_pref` needed, so the
model guessed — and in MagicalNotes one refusal cost five retries of a
four-block handler, which is how duplicate and dead blocks got in.
Now:

> This block works on a SharedPreferences component, and none was
> named. Name one of this screen's SharedPreferences components:
> file1.

or, when the screen has none:

> This screen has no SharedPreferences component yet — add one with
> create_component {type: "shared_preferences"}, then name it here. Its
> components are: intent1 — none of them is a SharedPreferences.

The keyword comes from `create_component`'s own `TYPES` table, so the
two tools cannot disagree.

**The export writes the block tree.** `events` was being written as
`logic.events` — an int. A screen with one event exported `events: 1`,
throwing away the only record of what the agent actually wired, which
is the first thing needed when a generated screen misbehaves. It now
exports each event's numbered statements, keyed by its header:

```json
"events": {
  "When save_btn (…) onClick:": "1. set noteText to \"\"\n2. Finish Activity\n…"
}
```

## Wave 33: values that arrived as text

The 22 October MagicalNotes export (sc_id 608) shows the worst failure
yet, and it was mine. The model sent:

```json
{"variable":"savedNoteText","block":"set_variable",
 "value":"{\"pref_get\":\"file1\",\"key\":\"magical_note\"}"}
```

`pref_get` **is** a real expression in the schema — so is `text_of`.
The model had the vocabulary right. What it got wrong was quoting:
`value` arrived as a *string*, so `op.get("value").isJsonObject()`
was false, the expression path was skipped, and the literal text
`{"pref_get":"file1","key":"magical_note"}` was stored as the value.

The generated Java compiled fine, which is why the build reported
success:

```java
savedNoteText = "{\"pref_get\":\"file1\",\"key\":\"magical_note\"}";
```

The app saved and displayed raw JSON instead of the note. Same for
`{"var":"savedNoteText"}` in `add_to_list`. A compiler cannot catch
this — the code is valid, the meaning is wrong.

**Fix:** `expressionFromText` recognises a string that parses as an
object carrying one of the 40-odd known expression keys, and `expr()`
reads it as the expression it means. Safe because a genuine piece of
JSON text nobody means as an expression has none of those keys and is
still stored as text.

It is **not silent**: the reply says so, so the model learns the right
form instead of having its mistake quietly absorbed:

> (a value arrived as quoted text — {"var":"noteText"} and the like —
> so it was applied as the expression it meant, not as those letters.
> Send values as objects, without quotes around them.)

**The lesson, for the third time in a different shape:** the app must
judge meaning, not just syntax. A string is valid input for a value
slot; that does not make it what the model meant.

### When there is only one component, that is the answer

The improved `save_pref` message fired three times in the 22 October
run and the model still never named `file1` — because it does not know
the `component` parameter exists. It sent, three times:

```json
{"block":"save_pref","text":"magical_note","text2":"noteText"}
```

The screen had exactly one SharedPreferences component. Refusing that
is pedantry that costs the user three dead turns and a save button
that never saves. Now, when a component action needs a component, none
is named, and the screen has **exactly one** of the required kind, it
is used — and the approval card says so:

> In file1, save "noteText" under "magical_note" (this screen has one
> SharedPreferences component, file1)

Zero or several are still refused, with the list.

## Wave 34: values are blocks, not text

The user asked the question directly: *"why am I not able to set
review.setText(Box), where Box is a block under the variable that calls
the value of the Box variable? Why is it just writing JSON strings
that mean nothing instead of using the block under the variable I
created?"*

The answer was not the model. It was four places in my code where a
value slot accepted **literal text only**:

1. **`set_text` explicitly refused an expression** — "set_text takes
   plain text here. To show a value … first put it in a string
   variable with set_variable, then set_text {from_variable}."
   Sketchware's own `setText` block takes a reporter directly. My
   refusal forced a pointless two-step copy.
2. **`buildWidgetAction`'s `s`/`d` slots** read `stringOf(op, field)`.
3. **`buildComponentAction`'s slots** the same — which is why
   `save_pref` could not store a list turned into JSON.
4. **`textSlotValue`** accepted exactly one expression shape,
   `{"var":"name"}`, and nothing else.

So the model, wanting "the value of Box" or "this list as JSON", had
**nothing to reach for but text** — and invented `{"var":"Box"}` as a
string. It was working around my limitation, not being stupid. The
compiler accepted the result, the build reported success, and the app
stored and displayed raw JSON.

**All four now take expressions.** Widget and component value slots
route through `expr()` when given an object; `set_text` takes a
reporter; `list_from_json` takes any expression for its JSON; and
`textSlotValue` handles every expression shape, not just `var`.

**How they reach `expr()`.** `expr()` is an instance method of
`Builder`; `translate()` is a *static* helper that lives outside it
(Builder ends at `private static final class PathArg`). So the three
helpers stay static and take the `Builder` as their first argument:
`translate(builder, …)`, `buildWidgetAction(builder, …)`,
`buildComponentAction(builder, …)`, `textSlotValue(builder, …)`. The
one call site, `buildTranslated`, passes `this`. Making them instance
methods instead breaks the build — `translate` cannot see them. Two
further fixes came out of the red run: a `boolean` declared inside an
`if` block cannot be read after it (`choseOnly`), and
`describeExpr(` is not picked up by a search for `expr(`.

**And the shapes are documented** in `set_event_handler`, read off the
code rather than remembered (numeric functions are `{"fn":"random",
"a":0,"b":5}`, not `{"op":...}`):

| what | write |
|---|---|
| a variable's value | `{"var":"Box"}` |
| what is typed in a box | `{"text_of":"note_input"}` |
| a saved preference | `{"pref_get":"file1","key":"magical_note"}` |
| a list as JSON | `{"json_of_list":"allNotes"}` |
| a random number | `{"fn":"random","a":0,"b":5}` |

A quoted string is still a literal — but a string that parses as one
of these objects is now read as the expression it meant, and the model
is told.

**The lesson, in the user's own framing:** the blocks were taught. The
agent still could not use them, because the *slots* they plug into
were wired for text. Teaching the inventory is not the same as wiring
the sockets.

## Wave 35 — full reachability bundle (every value slot, palette gaps, event editing, and export fixes)

Wave 34 wired four sockets; a mechanical audit of `AgentLogic.java`,
`ExtraPaletteBlock.java`, `Fx.java`, `Gx.java` and the exported session
logs exposed the rest of the same class:

1. **Every remaining value slot in `translate()`, `Builder`,
   `buildWidgetAction` and `buildComponentAction` now routes through
   `expr()`** via `valueSlot`, `numberSlot` and `boolSlot`:
   - `toast` (its explicit object refusal cost 5 dead turns in
     MagicalNotes), `custom_toast`, `add_to_list`, `put_in_map`,
     `remove_map_key`, `add_map_entry` / `insert_map_entry` /
     `set_map_entry`, `map_put_number` / `map_put_flag`,
     `map_from_json` / `list_from_json`, `sort_list` (`key`),
     list `index` / `index2` (`indexParam`), `numberParam`,
     `numericField`, `set_hint`, `copy_to_clipboard`,
     `set_screen_title`, `load_url`, `set_image_url`, `set_progress`,
     `set_enabled`, `set_clickable`, `write_file`, `pathParam`,
     `copy_file` / `move_file` / `copy_folder` / `rename_file`,
     `crop_image`, `scale_bitmap` and kin, `split_into_list`,
     `open_asset`, `menu_add_item`, `dialog_*_button`, and `'b'`
     boolean slots in widget/component actions.
   - Common parameter aliases (`key`/`value` on `save_pref` and
     `put_extra`; `variable` on list blocks) are accepted directly.
2. **`EXPRESSION_KEYS` expanded from 48 to the full set of ~95
   top-level `expr()` keys** (including `fn`, `op`, `text_of`,
   `checked`, `enabled`, `extra`, `progress_of`, `url_of`,
   `selection_of`, etc., with single-key guards so multi-field data
   JSON is never mistaken for an expression), plus inner-quote cleanup
   (`{"\"text_of\"":...}`).
3. **Widget hierarchy & remaining stock blocks (`Gx.java` +
   `ExtraPaletteBlock.java`)**:
   - `CompoundButton` blocks (`set_checked`, `{"checked":"..."}`) now
     accept `CheckBox`, `Switch` and `RadioButton`.
   - `EditText` blocks accept `AutoCompleteTextView` and
     `MultiAutoCompleteTextView`; `SeekBar` progress blocks accept
     `ProgressBar`.
   - Added `set_image`, `set_typeface`, `show_keyboard`,
     `hide_keyboard`, `set_pref_file`, `set_custom_letter`,
     `list_set_checked`, `list_checked_positions_to_list`,
     `refresh_adapter`, inline row/view listeners (`when_clicked`,
     `when_long_clicked`, `when_checked_changed` with
     `{"is_checked":true}`, `when_swipe_refresh`), and reporters
     `current_time` / `fn:"now"`, `date_of`, `res_string`,
     `list_checked_position_of`, `list_checked_count_of`.
4. **Event editing, replacement, terminators & export fixes**:
   - `edit_event_block`: any `%s`/`%d`/`%b` slot is editable, object
     expressions in `replace_with` compile into reporter beans instead
     of failing with `JsonObject`, and statement `[1]` can be removed
     cleanly (promoting `nextBlock` to chain head or emptying the
     event).
   - `set_event_handler`: optional `replace: true` replaces an event's
     existing blocks in one step.
   - `Builder.requireComponent` and `expr()` component reporters share
     sole-component auto-resolution; `E_NO_VARIABLE` detects when the
     named identifier is a list on the same screen.
   - `checkTerminators` checks existing chain tails even when new
     blocks have no terminator, and guards nested sub-chains in
     `Builder.buildList`.
   - `build_project` and positional `edit_event_block` are never
     blocked by `E_ALREADY_DONE`; custom-view edits show their real
     screen name; underscored and extended-widget custom attributes
     validate cleanly in `AgentEdits`.

## Wave 36 — More Blocks & `addSourceDirectly`

1. **Full More Block lifecycle (`create_more_block` / `remove_more_block` / `call_more_block` / `return_value`)**:
   - Validated against `MoreblockValidator`, `uq.b`/`uq.a()`, screen identifiers (`eC.a`), `ReturnMoreblockManager` (the 8 supported return types: `void`, `string`, `number`, `boolean`, `map`, `list_string`, `list_map`, `view`), and `mq` parameter subtypes (`%s`, `%d`, `%b`, `%m.<subType>`, plus label words).
   - `create_more_block` can either declare the signature first or create and wire `<first>_moreBlock` in one approval card; `set_event_handler`, `get_event_logic`, and `edit_event_block` address More Blocks natively (`target: "<name>", event: "moreBlock"`).
   - Inside a More Block body, primitive parameters emit `getArg` via `{"arg":"p"}` or `{"var":"p"}`, `%m.*` parameters (lists, maps, widgets, components) automatically rewrite into typed `getArg` reporters (`_p`), and non-void More Blocks enforce and emit `BlocksHandler`'s `returnString`/`returnNumber`/`returnBoolean`/`returnMap`/`returnListStr`/`returnListMap`/`returnView` terminator blocks.
   - Void More Blocks emit statement `definedFunc` (`type = " "`); returning More Blocks emit value-reporter `definedFunc` inside `expr()` and reference slots (`{"call_more_block":"...","args":[...]}`).
2. **`addSourceDirectly`, inline Java reporters, and `"Import"` event**:
   - Statement `add_source_directly` (`addSourceDirectly`), inline value-slot reporters `{"java_expr":"..."}` (`asdString` / `asdNumber` / `asdBoolean`), `custom_import` (`customImport` in `target: "activity", event: "Import"`), `add_custom_variable` (`addCustomVariable`), and `add_initializer` (`addInitializer`), with `LogicEditorActivity`'s leading-space guard when raw code starts with `'@'` and plain-sentence approval previews (full code behind **Details** and in diagnostic exports).
   - Project audits (`danglingWidgetReferences`, `unusedVariables`, `variableUse`, `unreadInputs`), `get_screen`, and `AgentExport` inspect More Blocks (`eC.i`), `getVar` specs, and raw-Java identifier tokens.

## Wave 36b — On-device post-mortem (`MagicalNotes` exports `1626`, `1639`, `1659`)

1. **`onCreate_initializeLogic` vs `initializeLogic_initializeLogic` (`Jx.java:445, 855`, `rs.java:171`)**:
   - Sketchware's built-in startup event uses `targetId = "onCreate"`, `eventName = "initializeLogic"` (`"onCreate_initializeLogic"`), emitted inside `private void initializeLogic()` **after** `findViewById` and `getSharedPreferences`. Using `targetId = "initializeLogic"` wrote `"initializeLogic_initializeLogic"`, which `Jx.java:445` emits at line 1 of `initialize(Bundle)` before views or `SharedPreferences` initialize (`NullPointerException` on launch in `1639`).
   - `AgentLogic` now maps `initializeLogic` to `"onCreate_initializeLogic"` without adding a duplicate `EventBean` to `eC.g(javaName)`, includes `"onCreate_initializeLogic"` in `SketchwareProjects`, `AgentExport`, and `allActivityBlocks`, and migrates legacy non-raw `"initializeLogic_initializeLogic"` blocks automatically on edit/build.
2. **Nested parameter-bag expressions & stringified JSON array tool arguments**:
   - `cleanExprObject` flattens nested parameter-bag expressions such as `{"pref_has":{"component":"file1","key":"saved_notes_json"}}` and `{"pref_get":{"component":"file1","key":"saved_notes_json"}}` (plus `map_get`, `list_get`, `list_has`, `call_more_block`, `java_expr`, `extra`), eliminating the `[620]..[624]` retries.
   - `AgentTools.jsonArrayArg` parses stringified JSON array tool arguments (repairing trailing missing `}` when unambiguous) and reports `E_BAD_JSON` instead of silent `0` blocks (`1639` `[529]`) or misleading `E_BLOCKS_LIMIT` (`1659` `[630]`). `MAX_BLOCKS` raised from `8` to `16`.
3. **Readable `@id` reporter rendering, sole-list `show_list`, and transient HTTP 5xx retry**:
   - `describeBlock` resolves `@id` reporter beans recursively (`getVar`, `getArg`, nested reporters, `asd*`) instead of rendering opaque `(a value)` in `get_event_logic` and `AgentExport`.
   - `show_list` auto-resolves the screen's sole string list when `"list"` is omitted and lists declared string lists in errors; `AgentLoop` retries transient `NETWORK` and HTTP `500`/`502`/`503`/`504` errors up to twice with backoff (`model_retry` logged in the export).

## Wave 37 / Phase 1 — Libraries, Material 3, Maven/Local Libraries, Permissions & Plan/Resume Strip Fixes

1. **Implementation Plan progress persistence & step deduplication (`AgentJournal`, `AgentTools`, `AgentFragment`)**:
   - Fixed `AgentJournal.setPlan`, which previously returned early on existing plans without applying `doneSteps` or `currentStep` and appended duplicate numbered steps (`1, 2, 3, 2, 3...`) whenever the model re-sent slightly reworded steps.
   - `setPlan` now heals legacy duplicate steps (`dedupePlanSteps`), normalizes leading step numbers/bullets, preserves settled step wording/order, applies `done_steps`/`current_step` monotonically to disk, and supports progress-only `set_plan` calls without requiring `steps` to be re-sent.
   - `AgentFragment` updates a live `Plan (X/Y)` badge on the Plan chip, renders scrollable interactive rows in `showPlanDialog()` (tap to mark/unmark done), and `completeFinalBuildStepIfReady` advances the final build milestone when `build_project` succeeds.
2. **Floating Working / Resume Strip (`taskStrip` in `AgentFragment`, `AgentLoop`, `AgentJournal`)**:
   - Fixed `AgentLoop` passing `cancelled` (`true`) as `done` to `closeTask` during cancelled API requests (which previously marked stopped turns `"done"` and hid the **Resume** button), fixed unreachable `E_ALREADY_DONE`/`E_ALREADY_SET` branches in `proposeAndExecute`, and stopped `AgentJournal.brief` from resurrecting old `"stopped"` tasks after a newer `"done"` task.
   - Added `Listener.onStateChanged()` and `liveStepLabel` wiring so `taskStrip` updates live while running (`Step X/Y · <current tool/approval>`), opens the Plan/Task dialog on tap, shows **Resume** + **Dismiss** immediately when stopped/paused, and resumes without overwriting drafts in `chatInput`.
3. **Phase 1 Project Setup Tools (`AgentSetup`, `AgentTools`, `AgentExport`)**:
   - `configure_library`: configures AppCompat, Material 3 (`dynamic_colors`, `DayNight`/`Light`/`Dark` theme), Firebase (`google-services.json` parsing or explicit fields), AdMob (App ID, ad units, test devices), and Google Maps (API key) via `ManageLibraryActivity.saveLibraryConfiguration()`'s exact `jC` persistence sequence.
   - `manage_permissions`: adds/removes normalized Android Manifest permissions in `.sketchware/data/<sc_id>/permission` and supports `auto_add_recommended=true` powered by `AgentSetup.auditPrerequisites(scId)`.
   - `list_local_libraries` & `manage_local_libraries`: inspects and toggles downloaded local libraries (`.sketchware/data/<sc_id>/local_library`) and Excluded Built-in Libraries (`.sketchware/data/<sc_id>/excluded_library`).
   - `download_maven_library`: downloads, unzips, D8-dexes, and enables external `group:artifact:version` Maven dependencies via Sketchware Pro's `DependencyResolver`.

## Wave 38 / Phase 2 — Multimodal Vision, Screenshot-to-Screen, Icons, Drawables, Adaptive App Icons & Offscreen `ViewPane` Preview

1. **Multimodal Vision Chat & Image Attachments (`AgentMedia`, `ChatMessage`, `AgentChatLog`, `ChatAdapter`, `AgentFragment`, `AiChatService`, `AgentLoop`)**:
   - Users can attach up to 4 images per message (`GetMultipleContents`, removable composer strip, image-only or text+image messages, inline bubble thumbnails with tap-to-zoom preview, persisted in `AgentChatLog` and exported as metadata in `AgentExport`).
   - `AgentMedia` normalizes EXIF rotation, preserves PNG alpha (or stores opaque screenshots/photos as JPEG at max 1280px), and caches 1024px base64 `data:image/...` vision payloads (`LruCache`).
   - `AiChatService` serializes standard OpenAI-compatible `content` arrays (`text` + `image_url`), classifies non-vision model rejections (`ErrorKind.UNSUPPORTED` + `isVisionUnsupported`) with a one-tap **Switch model** retry flow in `AgentFragment`, and keeps message objects strictly free of non-standard JSON fields.
2. **Screenshot-to-Screen workflow & Phase 2 Asset Engine (`AgentAssets`, `AgentTools`, `AgentEdits`)**:
   - Screenshot-driven recreations extract palette, typography, spacing, radii, and icons directly from the attached image (`AGENT_GUIDANCE` + `requireDesign` without redundant `ask_choice`).
   - `search_builtin_icons` & `import_builtin_icon`: extracts and searches Sketchware Pro's built-in Material Icon Pack (`icons/icon_pack.zip`) across `round`, `outline`, `baseline`, `sharp`, and `twotone` styles and imports single or batch tinted `<vector>` XML drawables via `SvgUtils.convert` into `jC.d(scId)`, resetting `jC` cleanly around `commitProject`'s `h()` temp-dir cleanup.
   - `import_chat_image`: saves any user-attached chat image (`attachment_id` or `"latest"`) into `jC.d(scId)` as a PNG drawable with read-back verification.
   - `manage_drawable`: validates (`XmlPullParser` + auto `xmlns:android`), upserts, inspects (`list_resources.inspect_drawable`), and safely deletes custom XML drawables (`<shape>`, `<vector>`, `<ripple>`, `<selector>`, `<layer-list>`) in `files/resource/drawable/*.xml`; `AgentEdits` verifies drawable existence upfront on `set_image`, `set_background_image`, and `set_fab`.
   - `set_app_icon`: generates standard legacy (`48..192px`), Android 8+ Adaptive (`108..432px` foreground + solid/gradient background), and Android 13+ Monochrome launcher icons across all 5 `mipmap-*` folders, `icon.png`, `files/app-icon/{app_icon.png,data.json}`, and `lC` metadata from a Material icon, chat image, or monogram, refreshing `AgentFragment`'s working-on card icon immediately.
3. **Closed-loop Offscreen `ViewPane` Preview (`AgentPreview`, `ViewPane`, `preview_screen`)**:
   - Fixed `ViewPane.java:634` (`this.sc_id` fallback before static `DesignActivity.sc_id`) and built `AgentPreview.renderScreen` to inflate deep-copied `ViewBean` trees offscreen on the main looper under `Theme.SketchwarePro.ProjectPreview`, synchronously hydrate SVG/vector/`<shape>`/`onCreate_initializeLogic` visual styling, save rendered PNGs to `.sketchware/ai/previews/`, report dp bounds + `0dp`/overflow/truncation warnings, and inject transient post-tool-batch vision turns in `AgentLoop` (with automatic fallback for text-only models).

## Wave 39 / Phase 3 — Fragments, DialogFragments, BottomSheetDialogFragments, Navigation Drawers, Root Layout Conversion, Raw XML Layout Import & Cleanup Tools

1. **Fragments, DialogFragments & BottomSheetDialogFragments (`SketchwareProjects`, `SketchwareActions`, `AgentTools`, `AgentLogic`)**:
   - Sketchware Pro stores `Fragment`, `DialogFragment`, and `BottomSheetDialogFragment` entries in `jC.b(scId).b()` with `fileType = ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY` (`0`), distinguishing them strictly by `fileName` suffix (`_fragment`, `_dialog_fragment`, `_bottomdialog_fragment` — `AddViewActivity:276-331`, `Jx:173-175`, `Ix:521`).
   - Added `create_fragment` (and automatic Fragment-kind forwarding in `create_activity`), auto-enabling AppCompat/Material on `_bottomdialog_fragment`, matching natural aliases (`HomeFragment` -> `HomeFragmentActivity`), validating `return_fragment` against `SketchwareProjects.isFragment`, blocking `open_screen` on Fragment classes, and adding `replace_fragment`, `show_dialog_fragment`, and `dismiss_dialog_fragment` blocks.
2. **Navigation Drawers (`_drawer_*`, `EVENT_TYPE_DRAWER_VIEW`) & Floating Action Button (`_fab`)**:
   - `list_activities` and `get_screen` surface `fragments`, `drawers`, `options`, `fab_id`, `drawer_layout`, `drawer_widgets`, and `host_activity`; `edit_activity` rejects `toolbar`/`drawer` on Fragments and auto-enables `toolbar` when `drawer: true` is switched on.
   - `AgentLogic.populateActivityWidgets` loads `_fab` (`eC.h(xmlName)`) and drawer widgets (`_drawer_<id>` from `eC.d(getDrawerXmlName())`), registers `EventBean.EVENT_TYPE_DRAWER_VIEW` (`4`) with unprefixed `targetId` (`AddEventActivity:203`, `Hx:132,227`), emits `_drawer_<id>` inside block parameters, guards against main-vs-drawer `targetId_eventName` collisions, and includes `_fab` / `_drawer_*` in `danglingWidgetReferences` and `AgentExport`.
3. **Root Layout Conversion & Raw XML Layout Import (`convert_root_layout`, `edit_screen` `set_root`, `import_layout_xml`)**:
   - `SketchwareActions.setRootLayout` and `AgentEdits` (`set_root`) convert root `ViewGroup` classes (`LinearLayout`, `RelativeLayout`, `FrameLayout`, `ConstraintLayout`, `CoordinatorLayout`, `ScrollView`, `NestedScrollView`, `HorizontalScrollView`, `MaterialCardView`, `SwipeRefreshLayout`) and root XML attributes via `InjectRootLayoutManager`, keeping top-level `ViewBean.parentType` synchronized.
   - `import_layout_xml` runs `ViewBeanParser` on `Looper.getMainLooper()` (avoiding worker-thread `InvokeUtil.createView` crashes), validates RelativeLayout constraints with `CircularDependencyDetector`, normalizes via `eC.a(...)`, cleans removed widget events, and verifies read-back.
4. **Cleanup & Symmetry Tools (`remove_component`, `delete_custom_view`, `view_root` cleanup)**:
   - `remove_component` checks cross-event block references (`AgentLogic.componentUse`), deletes component events via `EventBean.deleteEvent`, and deletes the `ComponentBean` via `eC.b(javaName, componentBean)` (`br.java:168`).
   - `delete_custom_view` checks `ViewBean.customView` and `<include>` references across all screens (with optional `clear_references`), refuses `_drawer_*` layouts, and cleans stale `InjectRootLayoutManager` (`view_root`) entries alongside `delete_activity` and `edit_activity`.

## Wave 40 / Phase 4 — Structured `values` / `values-<variant>` Resources, Raw `res/` XML Manager, Menus, Animations, Fonts & Sounds

1. **Structured `values` & `values-<variant>` Resources (`AgentResources`, `get_values_resources`, `edit_values_resource`)**:
   - Reads, validates, upserts, and deletes entries in `strings.xml` (preserving `translatable="false"` and escaping apostrophes via `ResourcesEditorActivity.escapeXml`), `colors.xml`, `styles.xml`, `themes.xml`, and `arrays.xml` (`string-array`, `integer-array`, `array` with standard `aapt2`-compatible `<item>...</item>` tags) across `values` and any `values-<variant>` (`values-night` dark theme, `values-es`, `values-ar`, `values-pt-rBR`, etc.).
   - Seeds default base `strings.xml`, `colors.xml`, and `styles.xml` through `yq` (`getXMLString()`, `getXMLColor()`, `getXMLStyle()`) and avoids instantiating `ColorsEditorManager` directly (whose constructor reads static `DesignActivity.sc_id`, null outside `DesignActivity`).
   - Wired `@color/<name>` and `?attr/<name>` into `AgentEdits` (`ViewBean.layout.backgroundResColor`, `ViewBean.text.resTextColor`, `parseColor`) and `AgentLogic` (`R.color.<name>` and `R.attr.<name>` in `%m.color` slots per `Fx.java:209-225`), complementing `@string/<name>` and `{"res_string":"<name>"}`.
2. **Raw `res/` XML Resource Manager & XML Animations (`read_resource_xml`, `write_resource_xml`, `delete_resource_xml`, `start_xml_animation`)**:
   - Lists, reads, `DocumentBuilder`-validates (XXE/DOCTYPE disabled), writes, and deletes arbitrary XML resources under `.sketchware/data/<scId>/files/resource/<folder>/<file>.xml` (`menu/*.xml` for `menu_inflate`, `anim/*.xml`, `animator/*.xml`, `xml/*.xml`, `color/*.xml`, `drawable*/*.xml`, `values*/*.xml`), compiled by `ResourceCompiler` into `project-imported.zip` and overlaid onto `project.zip` via `aapt2 link -R`.
   - Added `start_xml_animation` (`play_xml_animation`) in `AgentLogic` (`AnimationUtils.loadAnimation` with ViewBinding and Activity/Fragment context support).
3. **Font Manager & Sound Manager (`list_font_and_sound_sources`, `import_project_font`, `delete_project_font`, `import_project_sound`, `delete_project_sound`)**:
   - Imports `.ttf`/`.otf` fonts into `jC.d(scId).d` (`kC.j()`) from bundled APK fonts (`gilroy`, `jetbrains_mono`, `droid_sans_mono`), user Font Collection (`Np.g().f()`), or local font files (magic-byte + `Typeface.createFromFile` validation), committing via `kC.y() -> eC.a(kC) -> eC.k() -> commitProject -> kC.z() -> resetResourceManager`, with widget/block reference checks on delete.
   - Imports or synthesizes audio resources into `jC.d(scId).c` (`kC.o()`) from 44.1 kHz 16-bit PCM `.wav` procedural presets (`click`, `pop`, `success`, `complete`, `error`, `notification`, `alarm`) or custom `frequency_hz`/`duration_ms`/`waveform` tones, user Sound Collection (`Qp.g().f()`), or local `.mp3`/`.ogg`/`.wav`/`.m4a` files, ready for `MediaPlayer` (`mp_create`) and `SoundPool` (`load_sound`), and exported in `AgentExport`.

## Wave 41 / Phase 5 — Java & Kotlin Source Manager, Manifest Component Registrations, Assets Manager & Native Libraries

1. **Java & Kotlin Manager (`AgentSources`, `read_source_files`, `write_source_file`, `delete_source_file`)**:
   - Reads, writes, and deletes `.java` and `.kt` classes under `.sketchware/data/<scId>/files/java/<path>` (compiled by ECJ in `ProjectBuilder` and `K2JVMCompiler` in `KotlinCompiler`, with `KotlinCompilerBridge` automatically adding `JETBRAINS_KOTLIN_STDLIB` whenever `.kt` files exist), plus lists `.jar` Kotlin compiler plugins in `files/kt_plugins/`.
   - Automatically computes and inserts `package <projectPkg>[.<subDir>]` when omitted, prevents duplicate-class collisions with visual Sketchware screens, and manages `AndroidManifest.xml` `<activity>`, `<service>`, and `<receiver>` lists (`fpu.getManifestJava`, `getManifestService`, `getManifestBroadcast` consumed by `Ix.java:645-663`), automatically cleaning manifest entries on delete.
   - Wired `AgentLogic` (`open_screen`, `set_intent_screen`, `start_service`, `stop_service`) and `list_activities` to resolve custom manifest-registered Java/Kotlin Activities, Services, and BroadcastReceivers alongside visual screens.
2. **Assets Manager (`read_assets`, `write_asset`, `delete_asset`)**:
   - Lists, reads (UTF-8 text or base64), validates (`.json` syntax verification), writes, and deletes files and nested folders under `.sketchware/data/<scId>/files/assets/<path>`, packaged directly into APK `assets/` for `open_asset`/`asset_text` blocks, `getAssets().open(...)`, and WebView `file:///android_asset/...`.
3. **Native Libraries Manager (`inspect_native_libs`, `import_native_lib`, `delete_native_lib`)**:
   - Lists, imports, and deletes `.so` JNI binaries across `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, and `armeabi` under `files/native_libs/<abi>/`, inspecting 20-byte ELF headers (`\x7FELF` + `e_machine`) to reject wrong-architecture `.so` imports and report missing `arm64-v8a` / cross-ABI mismatch warnings, with full state recorded in `AgentExport`.

## Wave 42 / Phase 6 — Full Pro Events, Component Callbacks, Custom Blocks/Components & XML/Java Command Blocks

1. **100% Event & Component Callback Scoping (`AgentLogic`, `create_component`)**:
   - Expanded `EVENT_ARG_TABLE` with all 29 missing built-in widget/component event locals from `Lx.getEventCode` and `ManageEvent.f` (`onCheckedChange`, `onItemSelected`, `onItemClicked`, `onItemLongClicked`, `onTextChanged`, `onQueryTextSubmit`, `onQueryTextChanged`, `onProgressChanged`, `onDateChange`, `onDateChanged`, `onTimeChanged`, `onPageStarted`, `onPageFinished`, `onPageSelected`, `onPageScrolled`, `onPageChanged`, `onScrollChanged`, `onScrolled`, `onRecyclerScrollChanged`, `onRecyclerScrolled`, `onNavigationItemSelected`, `onTabSelected`, `onTabUnselected`, `onTabReselected`, `onRatingChanged`, `onLetterSelected`, `onError`, `onFailureLink`, `onSuccessLink`, `onUserEarnedReward`), added `EVENT_REF_ARG_TABLE` (`onFilesPicked` -> `listStr` `filePath`, `onPatternLockProgress`/`Complete` -> `listStr` `pattern`, `onActivityResult` -> `intent` `data`), and parsed dynamic custom event slots from `EventsHandler.getBlocks`.
   - Fixed canonical event matching (`" onLongClick"` leading-space preservation and live `EventsHandler.getActivityEvents()` lookup) and wired `create_component` `extra_data` (`SharedPreferences` file name, `Firebase` path, `FilePicker` MIME type).
2. **Custom Blocks, Custom Components & Custom Events (`AgentExtensibility`, `list_custom_definitions`, `manage_custom_block`, `manage_custom_component_or_event`)**:
   - Reads and writes `My Block/block.json`, project `custom_blocks`, `system/component.json`, `system/events.json`, and `system/listeners.json`, refreshing `BlockLoader`, `ComponentsHandler`, and `EventsHandler` immediately and supporting direct custom block statement & expression invocation in `AgentLogic`.
3. **XML & Java Command Blocks (`manage_xml_commands`, `java_command_block`)**:
   - Reads and writes `.sketchware/data/<scId>/command` (migrating legacy temp commands and enabling `ProjectSettings.SETTING_NEW_XML_COMMAND`) and emits `CommandBlockJava` (`java_command_block`) for compile-time Java/XML transformations, exported in `AgentExport`.

## Wave 43 / Phase 7 — AndroidManifest Injection, AppCompat XML Injection, Pro ProjectSettings, ProGuard/R8 & StringFog

1. **AndroidManifest Manager (`AgentManifestAndSettings`, `inspect_manifest_and_settings`, `manage_manifest`)**:
   - Reads and writes `AndroidManifestInjector` files under `.sketchware/data/<scId>/Injection/androidmanifest/`: `activity_launcher.txt` (resolving screen names to `ProjectFileBean.fileName` as expected by `Ix.java:561`), `attributes.json` (`_application_attrs`, `_apply_for_all_activities`, `_application_permissions`, and per-Activity class names), `activities_components.json` (`<intent-filter>` deep links / share targets inside `<activity>`), and `app_components.txt` (`<provider>`, `<meta-data>`, `<service>`, `<receiver>` inside `<application>`), validated with `XmlPullParser`.
2. **AppCompat XML Injection (`manage_appcompat_injection`)**:
   - Customizes or resets `toolbar`, `appbarlayout`, `coordinatorlayout`, `drawerlayout`, and `navigationdrawer` XML attributes in `.sketchware/data/<scId>/injection/appcompat/<fileName>` and calls `AppCompatInjection.refreshInjections()` so generated layout XML picks up changes immediately.
3. **Project Metadata, Pro ProjectSettings, ProGuard/R8 & StringFog (`edit_project_settings`, `configure_proguard_and_stringfog`)**:
   - Updates `my_ws_name`, `my_app_name` (and `@string/app_name` in `strings.xml`), `my_sc_pkg_name`, `sc_ver_code`, and `sc_ver_name` via `lC.b(scId, metadata)`, plus `ProjectSettings` (`min_sdk`, `target_sdk`, `app_class`, `enable_viewbinding`, `enable_bridgeless_themes`, `disable_old_methods`), `ProguardHandler` (`proguard`, `proguard-rules.pro`, `proguard_fm`), and `StringfogHandler` (`stringfog`), with full state recorded in `AgentExport`.

## Wave 44 / Phase 8 — Source Inspector, Runtime Crash Logcat Reader, Collections & Release Packaging (`.aab` / Signing / `.swb`)

1. **Generated Source Inspector (`AgentPackagingAndDiagnostics.inspectGeneratedSource`, `inspect_generated_source`)**:
   - Runs Sketchware Pro's `SrcViewerActivity` pipeline (`yq.ExportType.SOURCE_CODE_VIEWING` + `ProjectBuilder.buildBuiltInLibraryInformation()`) to inspect numbered lines of generated `.java`, layout `.xml`, and `AndroidManifest.xml` files on demand without executing a full build.
2. **Live Runtime Logcat & Crash Reader (`read_app_logs`, `SketchApplication.onCreate`)**:
   - Registers a background ring-buffer `BroadcastReceiver` on `pro.sketchware.ACTION_NEW_DEBUG_LOG` in `SketchApplication` to capture live runtime logs and `AndroidRuntime` crash traces from installed Sketchware apps, combined with `.sketchware/logcat/<pkg>/*.txt` and `CompileErrorSaver` build logs (written automatically by `AgentBuild` on `zy` compile failures and cleared on clean builds).
3. **Collections Manager (`list_collections`, `manage_collections`)**:
   - Lists, saves, imports, and deletes reusable Widget Collections (`Rp.h()`), MoreBlock Collections (`Pp.h()`, auto-creating missing variables/lists and copying referenced images/sounds/fonts with `AgentAssets.resetResourceManager` guards), and Block Collections (`Mp.h()`).
4. **Release Packaging & `.swb` Backups (`build_release_package`, `export_project_backup`)**:
   - Builds Play Store `.aab` App Bundles (`AppBundleCompiler.createModuleMainArchive()` + `buildBundle()` + `ZipSigner` / `CustomKeySigner`) and signed release `.apk` packages (`runZipalign` + `TestkeySignBridge` or `ApkSigner.signWithKeyStore` V1+V2+V3) with ViewBinding, Kotlin, StringFog, and ProGuard/R8 support, and exports shareable `.swb` project archives via `BackupFactory`, with diagnostics and collections captured in `AgentExport`.

## Waves 45–48 / Phases 9–12 — Self-Hosted OpenUI Lang 1.0 Generative Chat UI, Low-Token Screen Bridge & CI Slim-Down

1. **Pure-Java OpenUI Lang 1.0 Core Engine (`OpenUiLang.java`, `ChatMessage`, `AgentChatLog`, `AgentExport`)**:
   - Zero-cost, zero-Gateway native Java 17 implementation of the `thesysdev/openui` OpenUI Lang 1.0 (`v0.5`) specification: line-oriented streaming lexer/AST parser (`id = Expression`, `$var = default`, `Query(...)`, `Mutation(...)`), positional component evaluation, reactive `$variable` `RuntimeState`, forward-reference `PendingRef` skeleton nodes, `inlineMode` Markdown + ```` ```openui-lang ```` splitter, `mergeStatements(previous, patch)` incremental line merger (`name = null` deletion + reachability pruning while preserving `$variables`), all 15 `@` built-ins (`@Count`, `@Sum`, `@Avg`, `@Min`, `@Max`, `@First`, `@Last`, `@Filter`, `@Sort`, `@Round`, `@Abs`, `@Floor`, `@Ceil`, `@Each`), and action AST (`@Run`, `@Set`, `@Reset`, `@ToAssistant`, `@OpenUrl`), persisted across restarts via `openui_state` in `AgentChatLog` and exported in `AgentExport` (`"openui"` summary + per-message `openui_blocks`/`openui_state`).
2. **Interactive Generative UI in Agent Chat (`OpenUiChatRenderer.java`, `agent_chat_item_assistant.xml`, `ChatAdapter`, `AgentFragment`)**:
   - Progressive native Material 3 renderer inside assistant bubbles (`RenderCache` incremental streaming updates + shimmer placeholders on `PendingRef` forward references) supporting 21+ layout, design-preview, interactive form, data/dashboard, and action components (`Stack`, `Row`, `Card`, `Header`, `Text`, `Badge`, `Divider`, `StyleCard`, `PaletteRow`, `ColorSwatch`, `TypePreview`, `TextInput`, `Select`, `ChoiceChips`, `Switch`, `Slider`, `KpiRow`/`Kpi`, `Table`/`Col`, `ProgressBar`, `BreakdownBar`, `Button`), while keeping native Approval/Build cards and native pages (`Project style`, `Design system`, `Plan`, `Task log`, `Conversations`) untouched.
3. **Low-Token Screen Builder, Incremental Patcher & Compact Reader (`OpenUiScreenBridge.java`, `get_screen`, `edit_screen`, `AgentEdits`)**:
   - Bidirectional OpenUI Lang 1.0 ↔ `ViewBean` / `AgentEdits` screen bridge: `get_screen` supports `format="openui"` (`OpenUiScreenBridge.toOpenUi(screenBeans)`, 1 line per widget, ~65% fewer context tokens), and `edit_screen` accepts `openui` (full screen layout in one call) or `openui_patch` (2–3 changed/new lines merged via `OpenUiLang.mergeStatements` without disturbing existing widget IDs or wired `BlockBean` events), compiling through `AgentEdits.simulate` (`MAX_COMPILED_OPS = 200`) with full Design System token, `@color/*`, `?attr/*`, `@drawable/*`, and validated `ViewBean.inject` attribute support.
4. **Context-Smart OpenUI Prompting & GitHub Actions Slim-Down (`AgentLoop`, `.github/workflows/android.yml`, `.github/workflows/agent-bootstrap.yml`)**:
   - Concise `inlineMode` + `openui` / `openui_patch` guidance and live `[OpenUI state: $var=...]` history forwarding in `AgentLoop`, plus workflow safeguards (`concurrency: cancel-in-progress: true`, `agent-bootstrap.yml` converted to `workflow_dispatch` only with `.apk` Git LFS uploads removed, and Telegram/`updateAppData` jobs gated off `arena/*` branches so each push runs a single clean `Build APKs` job).

## Wave 49 / Phase 13 — Modern Sketchware-Themed Agent UI Redesign

1. **Studio Header, Welcome Hero & Composer (`agent_chat.xml`, `AgentFragment`, `agent_*` drawables)**:
   - Upgraded Working-On project capsule (`20dp` radius, `1dp` `colorOutlineVariant` stroke, rounded `12dp` `ShapeableImageView` app icon, `WORKING PROJECT` overline), sleek `agent_model_pill_bg` spark + model selector pill, centered empty-state hero card (provider setup CTA or project-aware prompt with generic starter chips), zero-stagger `setItemAnimator(null)` streaming, primary-accented floating task/resume strip, and `24dp` outlined composer card with compact tonal pills (`Style`, `Design system`, `Plan`, `Task log`) and circular Send/Stop action button.
2. **Bubbles, Collapsible Reasoning, Heartbeat Pills & Approval/Build Cards (`agent_chat_item_*.xml`, `ChatAdapter`)**:
   - Crisp `20dp` `colorSurfaceContainerLow` assistant cards with `colorOnSurface` typography, `agent_thinking_box_bg` collapsible reasoning container, Sketchware-primary typing dots, `agent_system_pill_bg` centered tool activity pills, tappable `Tap to retry` pill, and modernized Approval/Build cards with `PROPOSED CHANGE` spark header, monospace `agent_details_well_bg` Details well, pill Approve/Reject buttons, and `agent_install_banner_bg` Build → Install banner.
3. **Provider Settings, Project Picker & Visual Design/Plan Sheets (`agent_settings.xml`, `AgentSettingsActivity`, `AgentFragment`)**:
   - Added top back-navigation header and 3 Material 3 section cards in `AgentSettingsActivity`, custom project icon loading in `ProjectPickerAdapter`, visual swatch rows + rounded live `corner_radius` component previews + direct property editing (`AgentJournal.setDesignToken`) in the Design System dialog, and a live Sketchware Primary progress bar + rounded step cards in the Implementation Plan dialog.

## Wave 50 — Field Diagnostic Audit (`HabitTracker` Export): Task Log Isolation & Block/Screen Hardening

1. **Task Log, Plan, Style, Design System & Chat Reused-`scId` Isolation (`AgentJournal`, `AgentChatLog`, `AgentLoop`, `AgentTools`, `AgentFragment`)**:
   - Eliminates cross-project ghost entries when Sketchware Pro deletes a project and reuses its numeric `sc_id`: `CreateProject.execute` wipes `AgentJournal` and `AgentChatLog` for `created.scId`, both stores stamp `"projectPackage"` on save and discard mismatched records on load, `AgentJournal.healReusedScId` cleans legacy reused-`scId` records already on device, and `AgentLoop` deposits drafts + moves style/design/plan before opening the first task of a newly created project.
2. **Custom-View `onBindCustomView` Reference Validation (`AgentLogic.danglingWidgetReferences`)**:
   - Includes widgets from `host.customView` when checking `onBindCustomView` handlers so custom-row widgets (`tv_name`, `iv_checkbox`, etc.) never trigger false-positive missing-widget warnings, and aligns `componentUse` / `moreBlockUse` with `allActivityBlocks`'s space-separated keys.
3. **Codegen `expectCode`, `@string/*` Block Slots & Screen/Block Ergonomics (`AgentLogic`, `AgentEdits`, `OpenUiScreenBridge`, `SketchwareActions`)**:
   - Fixed 12 `COMPONENT_ACTIONS` (including `pick_file` and `take_picture`) and 5 `WIDGET_ACTIONS` `expectCode` templates against `Fx` / `BlocksHandler`, auto-decoded `"@string/<name>"` in block text slots into `getResStr` (`getString(R.string.<name>)`), permitted empty `"blocks": []` on dialog buttons, supported `{"external_dir": "DIRECTORY_*"}`, improved `onFilesPicked` `filePath (listStr)` / `sp_create` / `read_file` / `play_sound` diagnostics, completed `isTextExpr` + boolean-literal `stringEquals` coercion, routed `set_in_list` with `"key"` on `list_map` to `set_map_entry`, added `@color/*` & `?attr/*` support on `set_root` / `set_fab`, and added `set_visibility` to `edit_screen` + `OpenUiScreenBridge`.

## Wave 51 / Phases 14–18 — Customizable OpenUI Design System Standard, High-Level Component Library & Runtime/Vision Hardening

1. **JSON-Driven OpenUI Theme Engine & Automatic Resource Materializer (`assets/openui/spec.json`, `scripts/check_openui_upstream.py`, `OpenUiThemeEngine`, `AgentJournal`, `AgentTools`, `SketchwareActions`)**:
   - Keeps OpenUI scales, text roles, 31 component schemas, and starter presets in `app/src/main/assets/openui/spec.json` (verified on demand against `thesysdev/openui` via `scripts/check_openui_upstream.py`), plus user preset JSON files in `.sketchware/ai/design_systems/*.json` (`AiPaths.designSystemsDir()`).
   - `OpenUiThemeEngine` derives WCAG-contrast-paired Light and Dark semantic palettes from any custom `brand_color`, `secondary_color`, `neutral_scale`, `corner_profile`, `density`, `theme_mode`, `font_heading`/`font_body`, or screenshot `light_overrides`/`dark_overrides`, and automatically materializes `values/colors.xml`, `values-night/colors.xml`, `values/styles.xml` (`AppTheme.*` Material 3 colour attributes + readable `AppBarOverlay` toolbar foreground), `color/openui_chip_text.xml`, and 21 `drawable/openui_*.xml` shapes/selectors (with `dynamic_colors = false` default guard so wallpaper tints never override custom app themes).
2. **OpenUI High-Level Screen Component Library, `TextView` Roles & Material 3 `<Button>` Safety (`OpenUiScreenBridge`, `AgentEdits`)**:
   - `OpenUiScreenBridge` compiles OpenUI's layout, form, button, status, data, `TextView` role (`TextContent`, `Heading`, `Body`, `Caption`, `Label`, `Text`, `BoldText`, `InlineHeader`, `CardHeader`, `TextCallout`, `Callout`), and Android-native chrome (`Form` auto-wrapped in `ScrollView`, `FormControl`, `Input`, `TextArea`, `Select`, `DatePicker`, `Chips`, `SegmentedControl`, `ColorSwatches`, `SwitchItem` / `Slider` / `SearchBar` preserving interactive widget IDs, multi-child `Card` inner body wrapper, `EmptyState`, `ListItem`, `Stat`, `Separator`, `CodeBlock`, `RecyclerList`, `SwipeRefresh`, `BottomNav`).
   - `AgentEdits` automatically injects `app:backgroundTint="@null"`, `android:insetTop/Bottom="0dp"`, `android:textAllCaps="false"`, and compact vertical padding safety on custom-drawable `<Button>` / `<MaterialButton>` widgets, supports `"token": "<key>"` in `add_widget` / `set_token`, and lazily materializes `openui_*` resources on legacy projects.
3. **Interactive Design System Sheet (`AgentFragment`) & Runtime/Multimodal Hardening (`Fx`, `BlocksHandler`, `AiChatService`, `AgentLoop`, `AgentPreview`)**:
   - Interactive **Design system** page with `☀ Light` / `☾ Dark` live preview toggle, WCAG badge, one-tap Brand/Corners/Density/Neutral/Mode pills, **Presets / Custom JSON** import & save, and **Sync theme to project**.
   - Null-safe empty-string guards on `strToMap`, `strToListMap`, `GsonStringToListString`, and `GsonStringToListNumber` (`Fx`, `BlocksHandler`); automatic session-cached `image_url` -> text-metadata fallback on text-only models (`AiChatService`, `AgentLoop`); and Light/Dark WCAG contrast + clipping + `CardView` overlap + unscrollable-form auditing in `AgentPreview`.

## Wave 52 / Phases 19–24 — Unified Design System, Smart Model Catalog, `✨ Auto` Router, 5s Auto-Approve & Material 3 Choice Cards

All findings from the third `HabitTracker` export plus the agreed design sessions, in one build:

1. **Real `R.id.<name>` in block values (`AgentLogic`, `AgentResources`)**:
   - Any number slot that means an Android resource id accepts `{"res_id":"menu_settings"}` (also `"@id/…"`, `"@+id/…"`, `"R.id.…"`) in `if` comparisons and `switch` cases (`onOptionsItemSelected`, `onNavigationItemSelected`, bottom navigation), compiling to the real `R.id.<name>` through an `asdNumber` reporter validated against every declared id (screen widget ids, drawer/toolbar built-ins, and every `@+id/*` / `<item type="id">` in `res/*.xml` via `AgentResources.collectDeclaredResourceIds`). Guessed integers like `2131230720` are over.
2. **Compact-button padding & invisible-text guards (`AgentEdits`)**:
   - `set_padding` and raw `set_attributes` (`android:padding*`) are refused on buttons ≤ 48dp tall / ≤ 48dp wide when the padding cannot fit (the 16dp-on-40dp chip bug), and `set_text_color` now computes WCAG contrast against the real ancestor surface (drawable tokens resolved per palette) in BOTH Light and Dark — warning below 4.2:1, hard block below 1.8:1 with a named readable alternative.
3. **One Design system — `Style` retired (`AgentJournal`, `AgentTools`, `AgentFragment`)**:
   - The composer bar is now `Design system · Plan · Task log`. `set_style_brief` mirrors its first line into `designSystem.summary`, shown as a tappable **Mood & Audience** banner atop the Design system page, so the two can never disagree. The first theme a project saves is pinned as `originalDesignSystem`, every change pushes a 10-step `designSystemHistory`, and the Design system header exposes **`↶ Undo (n)`** and **`↺ Original theme`** (instantly re-materialized into XML). The new `undo_design_system` tool (`undo` / `restore_original`) does the same from chat.
4. **Model catalog, badges, Hide & active probes (`ModelCatalog`, `AiChatService`, `AgentSettingsActivity`, `AgentFragment`)**:
   - `/models` metadata (OpenRouter + Gemini shapes) is parsed into a persisted `.sketchware/ai/model-catalog.json` — `Tools ✓`, `Vision` / `Text-only`, context size, `Free`, measured latency, last error — and both pickers filter junk (`tts`, `imagen`, `veo`, `embed`, `rerank`, …) through `ModelCatalog.visibleModels` with `showAllModels` as the escape hatch. Model Picker cards carry the badge, a one-tap **Test** (1-tool ping + 1×1-PNG vision check + latency), permanent **✕ Hide** with a Hidden-restore dialog, and **⚡ Verify top models**. 404s auto-hide as retired; live vision rejections persist "Text-only" across restarts.
5. **`✨ Auto` router + mid-turn failover + 90s stall cutoff (`AgentLoop`, `ModelCatalog`, `AiChatService`)**:
   - Choosing **`✨ Auto`** routes each turn to the best healthy model of the active provider (vision+tools when the turn carries images, fastest known-Tools model otherwise) and fails over mid-turn on 429/5xx/network with a 5-minute cooldown per model; `sanitizeMessagesForModel` strips provider-specific fields (and images for text-only targets) so completed `tool_calls` and `tool` results carry to the next model intact. Agent turns cap stalled reads at 90s instead of the silent 180s×3 (9-minute) hang.
6. **5-second Timed Auto-Approve (`ChatAdapter`, `ChatMessage`, `AiProviderConfig`)**:
   - On by default (`agent_settings.xml` switch + `⏱` toggle in the Model Picker footer): pending cards show a live `✓ Approve (5s)…` countdown and self-approve at zero — paused by **Details** or any card touch, cancelled instantly by Reject, and destructive deletes (`delete_activity`, `delete_custom_view`, `remove_component`, file/resource deletes) are manual-hold forever.
7. **Compact project index + read cache + vague-prompt/keyless-API standard (`AgentLoop`, `AgentJournal`, `SketchwareProjects`)**:
   - The system message carries a ~350-token PROJECT INDEX (per screen: widget ids, wired events, components, variables, lists, More Blocks, SharedPreferences files; plus item layouts and `res/menu/*.xml` ids), so switched/failed/`✨ Auto` models orient in one read. Read-only tool findings are cached and re-briefed on retry (`AgentJournal.readCache`, cleared on mutation or success). Vague prompts ("build me a notepad / weather app") brainstorm into complete plans, and live-data apps without keys use free keyless APIs (Open-Meteo) with offline cache.
8. **Material 3 card sheets & live colour swatches (`AgentFragment`)**:
   - `ask_choice`, the Model Picker, the Key Switcher and Design-System Presets render as tappable M3 cards (bold title + subtitle + active ring + trailing action) instead of bare text lists. Choices carrying hexes draw real colour swatch circles and Light→Dark gradient strips, and every `ask_choice` ends with **🎨 Pick a custom colour…**, **⌨ Type my own…**, and **✨ You decide**.
