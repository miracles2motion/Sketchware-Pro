# Sketchware Pro Agent Mode — Complete Master Roadmap

This document is the permanent source of truth for taking Agent Mode from basic multi-screen apps (Waves 1–36b) to **100% coverage of Sketchware Pro**, including multimodal **Screenshot / Image-to-Screen recreation**, every manager in `DesignDrawer`, developer tools, and release packaging.

---

## Baseline Already Shipped (Waves 1–36b, commit `f30f789`)

- **Design-first workflow & anti-gravity gates:** Style brief (`set_style_brief`), editable Design System with live rendered preview (`set_design_system`, `edit_design_token`), locked implementation plan (`set_plan`), task log (`get_task_log`), lossless diagnostic JSON export (`AgentExport`), and cloud BYOK OpenAI-compatible chat with transient 5xx retry (`AiChatService`, `AgentLoop`).
- **Projects, screens & custom row views:** `create_project`, `create_activity`, `edit_activity`, `delete_activity`, `create_custom_view`, `edit_project_theme`, `edit_screen` (container-first layouts, widgets, custom XML attribute injection via `ViewBean.inject`).
- **Variables, lists, maps & components:** `create_variable`, `remove_variable`, `create_component` (22 stock/Pro component types).
- **Logic, More Blocks & raw Java:** `set_event_handler` (`replace: true`), `get_event_logic` (recursive `@id` rendering), `edit_event_block`, `create_more_block` / `remove_more_block` / `call_more_block` / `return_value` (all 8 `ReturnMoreblockManager` return types + typed parameters), `add_source_directly`, inline `java_expr` (`asdString`/`asdNumber`/`asdBoolean`), `custom_import` (`Import` event), `add_custom_variable`, `add_initializer`, and built-in `onCreate_initializeLogic` wiring.
- **On-device build & install:** `build_project` (`AgentBuild` -> APK compile + install card).

---

## Complete Feature Matrix vs. Sketchware Pro (`DesignDrawer` + Home + Compiler)

Every one of Sketchware Pro's **19 `DesignDrawer` items** plus project-level and multimodal capabilities is mapped to a concrete phase below:

| Sketchware Pro Subsystem | Authoritative Repo Classes | Phase |
|---|---|---|
| Library Manager (AppCompat, Material 3, Firebase, AdMob, Google Maps, Local Libs, Maven downloader, Excluded Built-in Libs) | `ManageLibraryActivity`, `ManageCompatActivity`, `Material3LibraryManager`, `FirebaseActivity`, `AdmobActivity`, `ManageGoogleMapActivity`, `ManageLocalLibrary`, `DependencyResolver.kt`, `ExcludeBuiltInLibrariesActivity` | **Phase 1 (DONE)** |
| Permission Manager + auto-prerequisite checks | `ManagePermissionActivity`, `ListPermission`, `FileResConfig` | **Phase 1 (DONE)** |
| Multimodal Vision Chat + Screenshot-to-Screen + Chat-Image-to-Asset + Offscreen `ViewPane` Preview | `AgentFragment`, `AiChatService`, `ChatMessage`, `AgentMedia`, `AgentPreview`, `LayoutPreviewActivity`, `ViewPane` | **Phase 2 (DONE)** |
| Image Manager, Built-in Icon Pack, Vector/Shape Icon Creator & Adaptive Launcher Icons | `AgentAssets`, `ManageImageActivity`, `ImportIconActivity`, `IconCreatorActivity`, `jC.d(scId)` | **Phase 2 (DONE)** |
| View Manager: Fragments, DialogFragments, BottomSheetFragments, Drawer Layouts (`_drawer_*`), Root Layout Injection, Raw XML Import, `remove_component`, `delete_custom_view` | `ManageViewActivity`, `AddViewActivity`, `InjectRootLayoutManager`, `ViewBeanParser`, `eC` | **Phase 3 (DONE)** |
| Resource Editor (`strings.xml`, `colors.xml`, `styles.xml`, `arrays.xml`) & Raw Resource Manager (`res/drawable`, `menu`, `anim`, `xml`, `values-night`) | `ResourcesEditorActivity`, `StringsEditorManager`, `ColorsEditorManager`, `StylesEditorManager`, `ArraysEditorManager`, `ManageResourceActivity` | **Phase 4 (DONE)** |
| Font Manager (`assets/fonts`) & Sound Manager (`res/raw`) | `ManageFontActivity`, `ManageSoundActivity`, `jC.d(scId)` | **Phase 4 (DONE)** |
| Java & Kotlin Manager (`.java` / `.kt` classes) & Assets Manager (`assets/`) & Native Libs (`.so`) | `ManageJavaActivity`, `KotlinCompilerBridge`, `ManageAssetsActivity`, `ManageNativelibsActivity` | **Phase 5 (DONE)** |
| Full Pro Events, Component Callbacks, Custom Blocks, Command Blocks (`ManageXMLCommandActivity`) & Developer Custom Components/Events | `EventsHandler`, `ManageEvent`, `CustomBlocksManager`, `BlocksManager`, `ManageXMLCommandActivity`, `CommandBlock`, `ComponentsHandler` | **Phase 6 (DONE)** |
| AndroidManifest Injection & AppCompat XML Injection (`Toolbar`, `AppBarLayout`, `CoordinatorLayout`, `FAB`) | `AndroidManifestInjection`, `AndroidManifestInjector`, `ManageAppCompatActivity`, `AppCompatInjection` | **Phase 7 (DONE)** |
| Pro Project Settings (`minSdk`, `targetSdk`, `ViewBinding`, custom `Application` class, version/package), ProGuard/R8 & StringFog | `MyProjectSettingActivity`, `ProjectSettings`, `VersionDialog`, `ManageProguardActivity`, `ProguardHandler`, `ManageStringFogFragment`, `StringfogHandler` | **Phase 7 (DONE)** |
| Show Source Code Inspector, Runtime Logcat Crash Reader, Collections Manager, `.aab` App Bundles, Keystore Signing & `.swb` Backups | `SrcViewerActivity`, `Jx`, `LogReaderActivity`, `CompileErrorSaver`, `ManageCollectionActivity`, `AppBundleCompiler`, `TestkeySignBridge`, `BackupFactory` | **Phase 8 (DONE)** |
| OpenUI Lang 1.0 Core Engine (Streaming Parser, Reactive `$variable` State, `@` Built-ins, `Action` Executor & `mergeStatements` Patcher) | `OpenUiLang`, `ChatMessage`, `AgentChatLog`, `AgentExport` | **Phase 9 (DONE)** |
| Interactive Generative UI Inside Chat (`inlineMode` Native Material 3 Views, Style/Palette Cards, Two-Way Controls & `@ToAssistant` Actions) | `OpenUiChatRenderer`, `ChatAdapter`, `AgentFragment` | **Phase 10 (DONE)** |
| Low-Token Screen Builder, Incremental Patcher & Compact Screen Reader (`OpenUI Lang` ↔ `ViewBean` Bridge) | `OpenUiScreenBridge`, `AgentTools`, `SketchwareActions`, `AgentEdits` | **Phase 11 (DONE)** |
| Context-Smart OpenUI Prompting, GitHub Actions CI Slim-Down & Final Bundle Verification | `AgentLoop`, `.github/workflows/agent-bootstrap.yml`, `.github/workflows/android.yml` | **Phase 12 (DONE)** |
| **Post-OpenUI Milestone:** Modern Sketchware-Themed Agent UI Redesign | `AgentFragment`, `AgentSettingsActivity`, `ChatAdapter`, `res/layout/agent_*`, `res/drawable/agent_*` | **Phase 13 (DONE)** |

---

## Detailed Phase-by-Phase Plan

### Phase 1 — Libraries, Material 3, Maven Dependencies & Permissions (DONE)
**Goal:** Unlock real Material 3 UI, networking, Firebase, maps, and third-party libraries without manual setup.
1. **Built-in Library Configuration (`configure_library`):**
   - Toggle & configure **AppCompat** (`jC.c(scId).c()`), **Material 3** (`Material3LibraryManager`: `material3`, `dynamic_colors`, `theme` Light/Dark/DayNight), **Firebase** (`jC.c(scId).d()`: project ID, app ID, API key, storage bucket), **AdMob** (`jC.c(scId).b()`), and **Google Maps** (`jC.c(scId).e()`).
2. **Local Libraries & Maven Downloader (`manage_local_libraries`):**
   - List installed local libraries in `.sketchware/libs/local_libs/`, enable/disable them per project (`FileResConfig`), download Maven coordinates (`group:artifact:version`) via `DependencyResolver.kt`, and manage excluded built-in libraries (`ExcludeBuiltInLibrariesActivity`).
3. **Permission Manager (`manage_permissions`):**
   - List, add, and remove Android manifest permissions (`FileResConfig` on `.sketchware/data/<sc_id>/permission`).
   - Automatic pre-build / component audit: warn or propose `INTERNET` when `RequestNetwork` / `WebView` / `Firebase` is used, `VIBRATE` for `Vibrator`, `CAMERA` for `Camera`, etc.

---

### Phase 2 — Multimodal Vision (Screenshot-to-Screen), Icons, Drawables & Visual Self-Check (DONE)
**Goal:** Let the user send a screenshot, UI mockup, sketch, or logo in chat and have the agent recreate the screen/view or import the visual asset faithfully.
1. **Image Attachments in Agent Chat (`AgentFragment` + `ChatAdapter` + `AgentMedia` + `AiChatService`):**
   - Attach button (`@drawable/ic_mtrl_image`) beside the chat input supporting 1–4 images (`GetMultipleContents`), removable composer thumbnail strip before send, rounded inline thumbnails with tap-to-zoom preview in chat bubbles, persistence in `AgentChatLog`, and full attachment metadata in `AgentExport`.
   - OpenAI-compatible multimodal `content` array in `AiChatService` (`{"type":"text",...}` + `{"type":"image_url","image_url":{"url":"data:image/...;base64,..."}}`), EXIF-oriented and resized cleanly on-device (`AgentMedia`, max 1280px stored / 1024px vision).
   - Graceful model check: `AiChatService.isVisionUnsupported` surfaces a plain dialog with **Switch model** (`fetchModelsAndPick()` -> `retryFailedTurn()`), and `preview_screen` automatically strips transient preview images and retries if the active model is text-only.
2. **Screenshot-to-Screen & Mockup-to-CustomView Workflow:**
   - Guided visual decomposition in `AgentLoop.AGENT_GUIDANCE` and `AgentTools.requireDesign`: derives palette, typography scale, spacing/radii, icons, and container hierarchy directly from the user's attached screenshot without redundant `ask_choice` quizzes.
3. **Chat-Image-to-Project-Asset, Built-in Material Icons, Custom XML Drawables & Launcher Icons (`AgentAssets`):**
   - `search_builtin_icons` & `import_builtin_icon`: searches Sketchware Pro's 2,000+ Material Icon Pack (`assets/icons/icon_pack.zip`) across `round`, `outline`, `baseline`, `sharp`, and `twotone` styles and converts tinted `<vector>` XMLs via `SvgUtils.convert` into `jC.d(scId)`.
   - `import_chat_image`: imports user-attached chat images (`attachment_id` or `"latest"`) into Project Image Manager (`drawable-xhdpi` + `jC.d(scId)`), with `resetResourceManager(scId)` guarding `jC` temp-dir lifecycle across `commitProject`.
   - `manage_drawable`: creates, validates (`XmlPullParser` + auto `xmlns:android`), updates, and safely deletes custom XML drawables (`<shape>`, `<vector>`, `<ripple>`, `<selector>`, `<layer-list>`) in `files/resource/drawable/*.xml` and checks active widget/FAB references before deletion; `AgentEdits` validates drawable existence on `set_image`, `set_background_image`, and `set_fab`.
   - `set_app_icon`: generates standard legacy (`48..192px`) + Android 8+ Adaptive (`108..432px` foreground + solid/gradient background) + Android 13+ Monochrome launcher icons across all 5 `mipmap-*` folders, `icon.png`, `files/app-icon/{app_icon.png,data.json}`, and `lC` project metadata from a Material icon, chat image, or monogram, refreshing `AgentFragment`'s project header live.
4. **Closed-Loop Visual Self-Check (`preview_screen` / `AgentPreview`):**
   - Renders any Activity or Custom View offscreen on the main looper using Sketchware Pro's real `ViewPane` (`Theme.SketchwarePro.ProjectPreview`) with synchronous SVG/vector/`<shape>`/`onCreate_initializeLogic` visual hydration, saves a PNG to `.sketchware/ai/previews/`, reports measured dp geometry + 0dp/overflow/truncation warnings, and injects the rendered PNG into the model's next vision turn for closed-loop visual comparison.

---

### Phase 3 — Fragments, BottomSheets, Drawers, Root Layouts & Cleanup Tools (DONE)
**Goal:** Support multi-tab architectures (`TabLayout` + `ViewPager`), bottom sheets, navigation drawers, and full cleanup.
1. **Fragments, DialogFragments & BottomSheetDialogFragments (`create_fragment`, `FragmentAdapter`, `replace_fragment`, `show_dialog_fragment`):**
   - `create_fragment` (plus automatic Fragment-kind forwarding in `create_activity`) creates `Fragment` (`<base>_fragment`), `DialogFragment` (`<base>_dialog_fragment`), and `BottomSheetDialogFragment` (`<base>_bottomdialog_fragment`, auto-enabling AppCompat/Material) in `jC.b(scId).b()` (`PROJECT_FILE_TYPE_ACTIVITY` + suffix convention matching `AddViewActivity` / `Jx` / `Ix`).
   - Full `FragmentAdapter` + `ViewPager` (`set_fragment_adapter`, `onFragmentAdded` -> validated `return_fragment`), container hosting (`replace_fragment`), Dialog/BottomSheet display (`show_dialog_fragment`, `dismiss_dialog_fragment`), and `open_screen` guard against passing Fragment classes to `startActivity`.
2. **Navigation Drawer & FAB Wiring (`_drawer_*`, `EVENT_TYPE_DRAWER_VIEW`, `_fab`):**
   - `list_activities` and `get_screen` expose `fragments`, `drawers`, `options`, `fab_id`, `drawer_layout`, `drawer_widgets`, and `host_activity`.
   - `AgentLogic` populates `_fab` (`eC.h(xmlName)`) and drawer widgets (`_drawer_<id>` from `eC.d(getDrawerXmlName())`), registers `EVENT_TYPE_DRAWER_VIEW` (`4`) with unprefixed `targetId` (matching `AddEventActivity` & `Hx`), emits `_drawer_<id>` inside block parameters, guards against main-vs-drawer `targetId_eventName` collisions, and recognizes `_fab` / `_drawer_*` in `danglingWidgetReferences`.
3. **Root Layout Conversion & Raw XML Layout Import (`convert_root_layout`, `edit_screen` `set_root`, `import_layout_xml`):**
   - Converts root `ViewGroup` containers (`LinearLayout`, `RelativeLayout`, `FrameLayout`, `ConstraintLayout`, `CoordinatorLayout`, `ScrollView`, `NestedScrollView`, `HorizontalScrollView`, `MaterialCardView`, `SwipeRefreshLayout`) and custom root XML attributes via `InjectRootLayoutManager`, keeping top-level `ViewBean.parentType` in sync.
   - `import_layout_xml` validates and imports complete Android XML layouts on the main looper through `ViewBeanParser`, `CircularDependencyDetector`, `InjectRootLayoutManager`, and `eC.a(...)` normalization.
4. **Cleanup & Symmetry Tools (`remove_component`, `delete_custom_view`, `view_root` cleanup):**
   - `remove_component` checks cross-event block references (`AgentLogic.componentUse`), deletes component events via `EventBean.deleteEvent`, and removes the `ComponentBean` via `eC.b(javaName, componentBean)` (`br.java:168`).
   - `delete_custom_view` checks `ViewBean.customView` and `<include>` references across all screens (with optional `clear_references`), refuses `_drawer_*` layouts with a pointer to `edit_activity`, and cleans stale `InjectRootLayoutManager` (`view_root`) entries alongside `delete_activity` and `edit_activity`.

---

### Phase 4 — Structured Resource Editor (`res/values`), Menus, Animations & Fonts/Sounds (DONE)
**Goal:** Full control over Android resources, dark-mode palettes, toolbar menus, animations, typography, and audio (`54` registered tools).
1. **Structured Resource Editor (`get_values_resources`, `edit_values_resource`):**
   - Read, upsert, and delete entries in `strings.xml` (including `translatable="false"`), `colors.xml`, `styles.xml`, `themes.xml`, and `arrays.xml` (`string-array`, `integer-array`, `array` with `aapt2`-valid `<item>` children) across `values` and any `values-<variant>` (`values-night` dark mode, `values-es` / `values-ar` localization), seeding defaults via `yq` and avoiding `ColorsEditorManager`'s static `DesignActivity.sc_id` dependency.
   - Wired `@color/<name>` and `?attr/<name>` into `AgentEdits` (`ViewBean.layout.backgroundResColor`, `ViewBean.text.resTextColor`, `parseColor`) and `AgentLogic` (`R.color.<name>` / `R.attr.<name>` in `%m.color` slots per `Fx.java:209-225`), alongside `@string/<name>` and `{"res_string":"<name>"}`.
2. **Raw `res/` XML Manager (`read_resource_xml`, `write_resource_xml`, `delete_resource_xml`):**
   - List, read, `DocumentBuilder`-validate, write, and delete arbitrary XML files under `files/resource/<folder>/<file>.xml` (`menu`, `anim`, `animator`, `xml`, `color`, `drawable*`, `values*`), plus `start_xml_animation` block support in `AgentLogic` (`AnimationUtils.loadAnimation`).
3. **Font Manager & Sound Manager (`list_font_and_sound_sources`, `import_project_font`, `delete_project_font`, `import_project_sound`, `delete_project_sound`):**
   - Import/delete `.ttf`/`.otf` fonts (`jC.d(scId).d`, bundled APK fonts `gilroy` / `jetbrains_mono` / `droid_sans_mono`, `Np.g().f()` collection, or local files) and `.wav`/`.mp3`/`.ogg`/`.m4a` sounds (`jC.d(scId).c`, 44.1 kHz 16-bit PCM `.wav` procedural presets & custom tones, `Qp.g().f()` collection, or local files) with reference checks and read-back verification.

---

### Phase 5 — Java / Kotlin Manager, Assets Manager & Native Libraries (DONE)
**Goal:** Let Sketchware Pro projects combine visual blocks with real `.java` / `.kt` classes, packaged assets, and JNI `.so` libraries (`63` registered tools).
1. **Java & Kotlin Manager (`read_source_files`, `write_source_file`, `delete_source_file`):**
   - Create, read, update, and delete `.java` and `.kt` classes under `.sketchware/data/<sc_id>/files/java/` (matching `ManageJavaActivity` + `KotlinCompilerBridge` auto-stdlib), auto-normalizing `package` declarations, guarding against visual screen class collisions, and managing `AndroidManifest.xml` `<activity>`, `<service>`, and `<receiver>` registrations (`fpu.getManifestJava`, `getManifestService`, `getManifestBroadcast`).
   - Wired `open_screen`, `set_intent_screen`, and `start_service` / `stop_service` in `AgentLogic` to resolve custom manifest-registered Java/Kotlin Activities, Services, and BroadcastReceivers seamlessly alongside visual screens.
2. **Assets Manager (`read_assets`, `write_asset`, `delete_asset`):**
   - List, read, write (UTF-8 text with automatic `.json` validation, base64, or device file copy), and delete packaged files under `.sketchware/data/<sc_id>/files/assets/` (`ManageAssetsActivity` / `ResourceCompiler`).
3. **Native Libraries (`inspect_native_libs`, `import_native_lib`, `delete_native_lib`):**
   - Inspect ABI `.so` folders (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, `armeabi`), verify ELF `\x7FELF` + `e_machine` headers against target ABIs, and surface 64-bit / cross-ABI mismatch warnings cleanly.

---

### Phase 6 — Full Pro Events, Component Callbacks, Custom Blocks & Command Blocks (DONE)
**Goal:** 100% coverage of Sketchware Pro's extensibility system (`67` registered tools).
1. **All Activity, Menu & Component Events (`AgentLogic` + `create_component` `extra_data`):**
   - Complete wiring and event-argument scoping across every Activity, Widget, Menu, and Component event (`EVENT_ARG_TABLE` + `EVENT_REF_ARG_TABLE` + dynamic `EventsHandler.getBlocks` parsing), including `onLongClick` (canonical leading-space resolution), `onFilesPicked` (`listStr` `filePath`), `onActivityResult` (`requestCode`, `resultCode`, `intent` `data`), `onOptionsItemSelected` (`id`, `title`), `onCreateOptionsMenu`, `onSaveInstanceState`, `onTabLayoutNewTabAdded`, `RecyclerView`/`ViewPager`/`TabLayout`/`BottomNavigationView`/`SearchView`/`PatternLockView`/`WaveSideBar`/`RatingBar`/`VideoView`, and all `Firebase`, `RequestNetwork`, `BluetoothConnect`, `LocationManager`, `Camera`, `SpeechToText`, `FragmentAdapter`, and `AdMob` callbacks.
   - `create_component` supports `extra_data` (`SharedPreferences` file name, `FirebaseDB`/`FirebaseStorage` path, `FilePicker` MIME type) and user Custom Components.
2. **User Custom Blocks & Developer Managers (`list_custom_definitions`, `manage_custom_block`, `manage_custom_component_or_event`):**
   - Dynamically loads and creates user Custom Blocks (`My Block/block.json` + project-pinned `custom_blocks`), Custom Components (`component.json`), and Custom Events/Listeners (`events.json` + `listeners.json`) with immediate `BlockLoader` / `ComponentsHandler` / `EventsHandler` cache refresh and direct statement/reporter invocation in `set_event_handler`.
3. **XML & Java Command Blocks (`manage_xml_commands` + `java_command_block`):**
   - Create, list, update, delete, and clear compile-time XML Command rules in `.sketchware/data/<sc_id>/command` (auto-enabling `ProjectSettings.SETTING_NEW_XML_COMMAND`) plus `java_command_block` (`CommandBlockJava`) for compile-time Activity Java transformations.

---

### Phase 7 (DONE) — AndroidManifest Injection, AppCompat XML Injection & Pro Project Settings
**Goal:** Everything outside individual screens that controls how Android installs, themes, and compiles the app.
1. **AndroidManifest Manager (`inspect_manifest_and_settings`, `manage_manifest`):**
   - Launcher activity override (`activity_launcher.txt` resolved to `ProjectFileBean.fileName`), per-Activity and all-Activities manifest attributes (`screenOrientation`, `windowSoftInputMode`, `configChanges`, `exported`, `launchMode`), `<application>` attributes, `<intent-filter>` deep links / share targets (`activities_components.json`), and `<application>` `<provider>`, `<meta-data>`, `<service>`, and `<receiver>` XML (`app_components.txt`) via `AndroidManifestInjector` with `XmlPullParser` well-formedness validation.
2. **AppCompat XML Injection (`manage_appcompat_injection`):**
   - Read/write/reset `.sketchware/data/<sc_id>/injection/appcompat/<fileName>` (`toolbar`, `appbarlayout`, `coordinatorlayout`, `drawerlayout`, `navigationdrawer`) with immediate `AppCompatInjection.refreshInjections()` cache reload.
3. **Pro Project Settings, ViewBinding, ProGuard/R8 & StringFog (`edit_project_settings`, `configure_proguard_and_stringfog`):**
   - Update `project_name`, `app_name` (synced to `@string/app_name`), `package_name`, `version_code`, and `version_name` (`lC.b(scId, metadata)`), `ProjectSettings` (`min_sdk`, `target_sdk`, `application_class`, `enable_viewbinding`, `enable_bridgeless_themes`, `disable_old_methods`), **ProGuard / R8** (`ProguardHandler`, `proguard-rules.pro`, `proguard_fm` full-mode local libs), and **StringFog** (`StringfogHandler`), exported in `AgentExport`.

---

### Phase 8 (DONE) — Source Inspector, Runtime Crash Logcat Reader, Collections & Release Packaging (`.aab` / Signing / `.swb`)
**Goal:** Self-debugging, cross-project reuse, and Play Store–ready outputs.
1. **Generated Source Inspector (`inspect_generated_source`):**
   - Generates and reads numbered lines of the exact `.java`, `res/layout/*.xml`, and `AndroidManifest.xml` output (`SrcViewerActivity` / `yq.ExportType.SOURCE_CODE_VIEWING`) without running a full APK build so the agent can pinpoint `javac` or `aapt2` errors by line number.
2. **Runtime Crash & Logcat Reader (`read_app_logs`):**
   - Captures live runtime logs and `AndroidRuntime` stack traces via a background ring-buffer `BroadcastReceiver` on `pro.sketchware.ACTION_NEW_DEBUG_LOG` registered in `SketchApplication.onCreate()`, plus exported `.sketchware/logcat/<pkg>/*.txt` files and `CompileErrorSaver` last-build logs.
3. **Collections Manager (`list_collections`, `manage_collections`):**
   - Lists, saves, imports (auto-provisioning missing variables, lists, and image/sound/font resources), and deletes reusable **Widget Collections** (`Rp`), **MoreBlock Collections** (`Pp`), and **Block Collections** (`Mp`).
4. **Release Packaging (`build_release_package`, `export_project_backup`):**
   - Builds and signs Play Store `.aab` Android App Bundles (`AppBundleCompiler` + `ZipSigner` / `CustomKeySigner`) and signed release `.apk` files (`runZipalign` + `TestkeySignBridge` / `ApkSigner` V1+V2+V3), and exports `.swb` project backups (`BackupFactory`).

---

## OpenUI Lang 1.0 Upgrade Roadmap (Phases 9–12 — Batched Locally, Single CI Push at Completion)

> **Workflow policy:** Commit each phase locally (`git commit` only, **no `git push`**) while building Phases 9–12. Only push once Phase 12 (including the GitHub Actions slim-down and full static audit) is complete so CI runs a single clean build.

### Phase 9 (DONE) — OpenUI Lang 1.0 Core Engine (`OpenUiLang.java`)
**Goal:** Pure-Java, zero-dependency implementation of the MIT OpenUI Lang 1.0 specification for progressive streaming, reactive local state, incremental patching, and lossless diagnostics.
1. **Streaming & Inline Fence Extractor (`splitSegments`):**
   - Splits assistant replies into interleaved `MARKDOWN` prose and `OPENUI` (` ```openui ` / ` ```openui-lang `) segments, supporting unclosed fences mid-stream and OpenUI 1.0 `]]>openui:` stored message markers without polluting visible text.
2. **Line-Oriented AST Parser, Forward References & Incremental Patching (`mergeStatements`):**
   - Parses `id = Component(args...)`, `$var = default` reactive state declarations, `id = null` deletions, positional arguments (plus forgiving `key: value` recovery), ternary/arithmetic/boolean/member-pluck expressions, `@Name(args)` built-ins/actions, and `Action([...])` lists.
   - Implements OpenUI 1.0 entry resolution (`root`, with fallback to first root-capable component + `no-root` warning after stream completion), top-down forward references (`PendingRef` placeholders while streaming), "last definition wins", and `mergeStatements(previous, patch)` incremental merging.
3. **Reactive State Runtime, Built-in `@` Functions & Persistence (`ChatMessage`, `AgentChatLog`, `AgentExport`):**
   - Evaluates `$variable` two-way bindings, `@Count`, `@Sum`, `@Avg`, `@Min`, `@Max`, `@First`, `@Last`, `@Filter`, `@Sort`, `@Round`, `@Abs`, `@Floor`, `@Ceil`, `@Percent`, and `@Each`, and executes `@Set`, `@Reset`, `@OpenUrl`, and `@ToAssistant(message, context)` locally with zero LLM calls on tap.
   - Persists `$variable` state per chat message in `AgentChatLog` and records parsed OpenUI summaries, state snapshots, and parser diagnostics in `AgentExport`.

---

### Phase 10 (DONE) — Interactive Generative UI Inside Chat (`OpenUiChatRenderer.java` + `ChatAdapter`)
**Goal:** Render live, native Material 3 interactive cards, style/palette pickers, forms, and summaries inside assistant chat bubbles while preserving native Approval/Build cards and Markdown prose.
1. **Native Android Component Library (`OpenUiChatRenderer`):**
   - Layout & typography: `Stack`, `Row`, `Card`, `Header`, `Text`, `Badge`, `Divider`.
   - Design & Anti-Gravity brainstorming: `StyleCard`, `PaletteRow`, `ColorSwatch`, `TypePreview` wired to project Design System tokens when present.
   - Two-way `$variable`-bound interactive controls (zero tokens per tap): `TextInput`, `Select`, `ChoiceChips`, `Switch`, `Slider`.
   - Data & summaries: `KpiRow`, `Table`, `ProgressBar`, `BreakdownBar`.
   - Actions: `Button` executing local `@Set`/`@Reset`, `@OpenUrl`, or `@ToAssistant(message, context)` (sending user choices + form state straight back into `AgentFragment` as a clean user turn).
2. **Progressive Streaming in `ChatAdapter`:**
   - Renders mixed Markdown + native OpenUI views inside `AssistantHolder` smoothly as lines stream in, keeping Approval cards (`TYPE_APPROVAL`), Build/Install cards, Design System page, Plan sheet, and Task Log untouched.

---

### Phase 11 (DONE) — Low-Token Screen Builder, Incremental Patcher & Compact Reader (`OpenUiScreenBridge.java`)
**Goal:** Cut screen-building and screen-reading context tokens by ~60–65% while producing 100% standard Sketchware Pro `ViewBean`s behind the Design Gate and Approval Card.
1. **OpenUI ↔ `ViewBean` Translator:**
   - Translates compact 1-line widget statements (`root = Column([topBar, heroCard, list])`) into validated Sketchware Pro `ViewBean` hierarchies across Activities, Fragments, Drawers, and Custom Views — preserving Design System tokens, `@color/*`, `?attr/*`, `@string/*`, and per-widget validated `ViewBean.inject` attributes.
2. **2-Line Incremental Screen Patching:**
   - Merges small OpenUI widget patches (`mergeStatements`) into existing screens without regenerating untouched widgets or breaking existing widget IDs and wired `BlockBean` events.
3. **Compact Screen Reader:**
   - Allows `get_screen` (and compact context summaries) to serialize `ViewBean` trees as 1-line-per-widget OpenUI statements (~2.5–3× smaller in conversation history than verbose JSON).

---

### Phase 12 (DONE) — Context-Smart Prompting, CI Workflow Slim-Down & Final Bundle Audit
**Goal:** Keep total prompt/context usage leaner than before, prevent GitHub Actions from ever disabling repo workflows again, and verify the entire Phases 9–12 bundle before a single push.
1. **Context-Smart OpenUI System Prompting (`AgentLoop`):**
   - Concise, scoped OpenUI Lang 1.0 signature block for chat UI (`inlineMode`) and screen shorthand (`editMode`), keeping net multi-turn token usage substantially lower than JSON-only sessions.
2. **GitHub Actions Slim-Down (`.github/workflows/`):**
   - Add `concurrency: cancel-in-progress: true`, stop force-pushing the ~140 MB release APK into Git LFS on every build, avoid duplicate `assembleRelease` runs per push, and gate branch-external bot jobs (`notifyTelegram`, `aggregateAndSend`, `updateAppData`) cleanly.
3. **Full Audit, Documentation (`AGENT_MODE.md`) & Single CI Push:**
   - Run static symbol/type/arity/contract verification across all classes, update `docs/AGENT_MODE.md`, push once to `origin/arena/8a5fd688-sketchware-pro`, and verify CI green before any APK download.

---

## Phase 13 (DONE) — Modern Sketchware-Themed Agent UI Redesign
- **Cohesive Sketchware Material 3 Studio Chrome (`agent_chat.xml`, `AgentFragment`):** Outlined `colorSurfaceContainerLow` Working-On project capsule with `WORKING PROJECT` overline and rounded `12dp` `ShapeableImageView` project icon; sleek `agent_model_pill_bg` spark + model selector pill alongside `Conversations`, `Export`, and `Settings` icon actions; centered Welcome / Empty-State hero card with `agent_ic_spark` PrimaryContainer emblem, `Set up AI provider` CTA when unconfigured, and generic quick-starter chips (`Design a new screen`, `Recreate a screenshot`, `Inspect & improve project`) when ready; zero-stagger `setItemAnimator(null)` streaming; upgraded floating task/resume status strip; and outlined `24dp` studio composer with compact `17dp` tonal tool pills (`Style`, `Design system`, `Plan`, `Task log`) and `42dp` circular Send/Stop button.
- **Refined Chat Bubbles, Reasoning Box & Approval/Build Cards (`agent_chat_item_*.xml`, `ChatAdapter`):** High-contrast `colorOnSurface` assistant cards (`20dp` radius, `1dp` `colorOutlineVariant` hairline), rounded `agent_thinking_box_bg` collapsible reasoning block, Sketchware-blue `agent_typing_dot` pulse pill, centered `agent_system_pill_bg` live tool heartbeat pills, tappable `Tap to retry` pill, and upgraded Approval/Build cards with `PROPOSED CHANGE` spark header, monospace `agent_details_well_bg` collapsible Details well, emerald/coral pill buttons, and `agent_install_banner_bg` Build → Install banner.
- **Redesigned Settings, Project Picker & Interactive Sheets (`agent_settings.xml`, `AgentSettingsActivity`, `AgentFragment`):** Top navigation bar + 3-card Material 3 settings layout (`Provider & API keys`, `Model selection`, `Project permissions`), custom project icons in `agent_project_row.xml`, visual swatch + live `corner_radius` Design System cards with direct user property editing (`AgentJournal.setDesignToken`), and a live Sketchware Primary progress bar + rounded step cards on the Implementation Plan sheet.

---

## Wave 50 (DONE) — Field Diagnostic Audit (`HabitTracker` Export): Task Log / Reused-`scId` Isolation & Block/Screen Plumbing Hardening
- **Task Log, Plan, Style, Design System & Chat Isolation (`AgentJournal`, `AgentChatLog`, `AgentLoop`, `AgentTools`, `AgentFragment`):** Wipes stale `.sketchware/ai/{tasks,chats}/<scId>.json` records on `create_project`, stamps `"projectPackage"` on save to guard against Sketchware Pro reusing deleted numeric `sc_id`s, self-heals existing reused-`scId` journals on load, deposits drafts & moves agreed design artefacts (`moveStyleBriefIfAgreedSince`, `moveDesignSystemIfAgreedSince`, `movePlanIfAgreedSince`) before opening the new project's first task, titles the Task Log dialog with the project name, and offers `No project` directly from `Conversations`.
- **Zero False-Positive `onBindCustomView` Warnings (`AgentLogic.danglingWidgetReferences`):** Includes custom-view item widgets (`host.customView`) when checking `onBindCustomView` handlers, plus space-separated event-key fixes in `componentUse` and `moreBlockUse`.
- **Codegen Survival (`expectCode`) & Block/Screen Hardening (`AgentLogic`, `AgentEdits`, `OpenUiScreenBridge`, `SketchwareActions`):** Fixed 12 `COMPONENT_ACTIONS` (`pick_file`, `take_picture`, `launch_app`, `start_with_chooser`, `set_pref_file`, `stt_shutdown`, `storage_upload`, `storage_download`, `storage_delete`, `send_broadcast`, `ad_load`, `rewarded_load`) and 5 `WIDGET_ACTIONS` (`select_all_text`, `set_max_lines`, `set_selection`, `show_error`, `notify_pager_changed`) `expectCode` templates; auto-compiled `"@string/<name>"` in block text slots into `getResStr` (`getString(R.string.<name>)`); allowed empty `"blocks": []` on dialog button containers; added `external_dir` `DIRECTORY_*` alias, `onFilesPicked` `filePath (listStr)` `arg` guidance, `sp_create` max-streams default/hint, reporter-as-statement guidance (`read_file`, `play_sound`), complete `isTextExpr` + boolean-literal `stringEquals` coercion, `set_in_list` → `set_map_entry` routing on `list_map` when `"key"` is present, `@color/*` / `?attr/*` support on `set_root` & `set_fab`, and `set_visibility` in `edit_screen` + `OpenUiScreenBridge`.


