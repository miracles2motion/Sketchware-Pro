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
