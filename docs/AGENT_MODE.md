# Agent Mode — implementation notes

Grounding for the Agent Mode design: what was verified against the code in
this repository, what had to be corrected, and the defects in the upstream
LiteRT-LM runtime that the design has to work around.

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

1. turns use the **synchronous** `sendMessage`, which is the reporter's own
   workaround: it returns the full reply and needs no terminal callback. The
   cost is that replies are no longer streamed token by token;
2. the engine and the conversation are only ever touched on a single
   background thread — closing a stuck conversation must not happen on the
   main thread, that would be an ANR;
3. a watchdog abandons a turn whose native call does not return within
   180 s, because even a synchronous call cannot be relied upon to finish;
4. a failed engine can be torn down and reloaded, so the manager treats
   "engine is dead" as recoverable.

### 3.1.1 The native conversation memory cannot be trusted

Reusing one `Conversation` was the original mitigation for #3266, and it
failed in the field: on a real device the model answered every message as if
it were the first — no reference to earlier turns, near-identical generic
replies to unrelated questions. Whatever the native session does with its
KV cache across turns depends on the model file and its chat template, and
the app cannot inspect either.

So the app owns the transcript. Every turn creates a fresh `Conversation`
whose `initialMessages` are the recent transcript (bounded to the newest
16 messages / ~8000 characters), sends one synchronous message, and closes
the conversation. Memory then comes from data the app controls, and a fresh
conversation per turn is exactly the shape the synchronous call makes safe.
The price is re-prefilling that bounded history on every turn.

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
