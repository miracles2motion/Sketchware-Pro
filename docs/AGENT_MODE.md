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

1. one `Conversation` is created and **reused for every turn**;
2. the engine and the conversation are only ever touched on a single
   background thread — closing a stuck conversation must not happen on the
   main thread, that would be an ANR;
3. an idle watchdog closes a turn that produced no token for 20 s, because the
   terminal callback cannot be relied upon;
4. a failed engine can be torn down and reloaded, so the manager treats
   "engine is dead" as recoverable.

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

## 4. Build and memory arbitration

- A 1B int4 model holds roughly 1 GB of resident memory, and AAPT2/D8 need
  that memory too. `ProjectBuilder` therefore releases the engine before a
  build starts (`unloadBlocking`, bounded, never on the main thread), and
  leaving Agent Mode releases it as well.
- `EngineConfig.cacheDir` points at `context.getCacheDir()/litertlm` so the
  second load of a model is faster.
- The engine is loaded **lazily**: entering Agent Mode with a model configured
  starts the load, and the engine is not resident while the user only looks at
  the project list.

## 5. What is in the tree now

```
pro/sketchware/ai/
    AiPaths.java          filesystem layout under .sketchware/ai
    AiModelConfig.java    agent-config.json registry (Gson)
    AiModelManager.java   engine lifecycle, single thread, watchdog
    ChatMessage.java      chat message bean
    ChatAdapter.java      chat recycler adapter
pro/sketchware/activities/main/fragments/agent/
    AgentFragment.java    chat screen, model import, lazy engine load
res/layout/agent_chat.xml
res/layout/agent_chat_item_user.xml
res/layout/agent_chat_item_assistant.xml
```

The mode toggle lives in `main.xml` and is handled by `MainActivity`.
