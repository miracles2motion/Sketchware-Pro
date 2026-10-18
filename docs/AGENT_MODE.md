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
  earlier in the same call; containers only; no cycles; exactly one
  root). Widget ids are app-allocated (the app's own prefix+counter
  naming) unless the model supplies a valid free one. Colours accept
  #RRGGBB/#AARRGGBB and the project tokens @primary/@primary_dark/
  @accent, resolved from the project's own theme colours at write.
- Proposals freeze allocated ids and a SHA-256 screen revision;
  execution refuses with E_SCREEN_CHANGED if the screen moved since.
  Save goes through the editor's own `cC.c(sc_id).b(xml, beans)`,
  then the screen is read back and compared to the approved sim.
- One-time project backup (slice 2) precedes the first edit too.
  Approval details warn when the screen might be open in the editor.
- Still open from the risk register: a hard editor-open lock (the
  revision gate + warning cover the common cases), design-token
  palette picking and plan-first construction (edit ops resolve
  tokens, but palettes arrive with whole-screen planning).
