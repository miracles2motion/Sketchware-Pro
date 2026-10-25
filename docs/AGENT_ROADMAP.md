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

---

## OpenUI Design System Standard & Runtime/Multimodal Hardening Roadmap (Phases 14–18)

**Context:** Brings OpenUI's second half (`@openuidev/react-ui` semantic token engine + `openuiLibrary` pre-engineered component standard) natively into Sketchware Pro so generated apps are customizable to **any colour palette, mood, or screenshot** while enforcing standardized spacing, touch heights, Material 3 `backgroundTint` safety, and mathematical Light + Dark WCAG contrast — bundled alongside the `list_from_json` empty-string guard and text-only model image fallback into a single CI build at Phase 18.

---

### Phase 14 (DONE) — JSON-Driven OpenUI Spec, Customizable Theme Engine & Automatic Resource Materialization (`assets/openui/spec.json`, `scripts/check_openui_upstream.py`, `OpenUiThemeEngine.java`, `AgentJournal.java`, `AgentTools.java`, `SketchwareActions.java`)
**Goal:** Replace loose freehand component notes in `set_design_system` with a 100% customizable, JSON-driven OpenUI semantic theme (`lightTheme` + `darkTheme` + layout/radius/typography scales) that automatically generates contrast-safe Android resources (`values/colors.xml`, `values-night/colors.xml`, `values/styles.xml`, `color/openui_chip_text.xml`, and `drawable/openui_*.xml`).
1. **JSON-Driven Spec (`app/src/main/assets/openui/spec.json`) + On-Demand Upstream Checker (`scripts/check_openui_upstream.py`):**
   - Stores OpenUI neutral scales, status pairs, spacing/radius/corner/density/typography scales, text roles, 31 component schemas, and 5 starter presets in `assets/openui/spec.json`, paired with `scripts/check_openui_upstream.py` watching only `spec`, `ThemeProvider`, and `genui-lib` in `thesysdev/openui` on demand (never wasting CI on upstream web/docs commits).
   - User-defined preset folder `/sdcard/.sketchware/ai/design_systems/*.json` (`AiPaths.designSystemsDir()`) lets users save or drop in their own custom Design System JSON presets.
2. **Customizable Theme Seed + Overrides (`OpenUiThemeEngine.java`):**
   - Accepts **any custom brand/accent hex**, optional secondary/neutral undertone (`neutral`, `slate`, `gray`, `zinc`, `stone`, `warm`, or custom surface/background hexes, including colours extracted from an attached screenshot), **corner profile** (`sharp` 6dp, `balanced` 12dp, `soft` 20dp, `pill` 999dp), **density** (`compact` / `comfortable` / `spacious`), and **theme mode** (`DayNight`, `Light`, `Dark`), plus explicit per-token overrides.
   - Uses WCAG 2.1 relative-luminance contrast math (`>= 4.5:1` for body/input text, `>= 3.0:1` for large/button text and interactive strokes) to derive matched **Light** and **Dark** semantic tokens modeled directly on OpenUI's `defaultTheme.ts`.
3. **Automatic Project Materialization, `AppTheme` (`styles.xml`) Wiring & Dynamic-Color Guard:**
   - Materializes `res/values/colors.xml` & `res/values-night/colors.xml`, `res/values/styles.xml` (`AppTheme`, `AppTheme.FullScreen`, `AppTheme.AppBarOverlay` with readable `@color/openui_text_on_accent` toolbar foreground, `AppTheme.PopupOverlay`, `AppTheme.DebugActivity`), `res/color/openui_chip_text.xml`, and 21 `res/drawable/openui_*.xml` shapes/selectors, enables Material 3 with `dynamic_colors = false` by default so wallpaper tints never override custom palettes, and applies `@color/openui_bg` root backgrounds to newly created Activity/Fragment/Drawer layouts (`SketchwareActions`).

---

### Phase 15 (DONE) — OpenUI High-Level Component Library & Material 3 Layout/Tint Safety (`OpenUiScreenBridge.java`, `AgentEdits.java`)
**Goal:** Let `edit_screen` (`openui` / `openui_patch` and `token` in raw `ops`) compile OpenUI's standardized high-level components and `TextView` roles directly into clean, well-proportioned Sketchware `ViewBean` trees while eliminating Material 3 `<Button>` tinting and vertical-padding clipping bugs.
1. **Standardized OpenUI Screen Components (`OpenUiScreenBridge.java`, `MAX_COMPILED_OPS = 300`):**
   - Compiles OpenUI's layout, form, button, status, data, `TextView`-role (`TextContent`, `Heading`, `Title`, `Subtitle`, `Body`, `Caption`, `Label`, `Text`, `BoldText`, `InlineHeader`, `CardHeader`, `TextCallout`, `Callout`), and Android-native chrome (`Form` auto-wrapped in `ScrollView`, `FormControl`, `Input`, `TextArea`, `Select`, `DatePicker`, `Chips`, `SegmentedControl`, `ColorSwatches` with oval swatch drawables, `SwitchItem` / `Slider` / `SearchBar` preserving exact interactive widget IDs, `Card` auto-wrapping multi-child layouts inside `<id>_body` so `FrameLayout` children never overlap, `EmptyState`, `ListItem`, `Stat`, `Separator`, `CodeBlock`, `RecyclerList`, `SwipeRefresh`, `BottomNav`).
2. **Material 3 `<Button>` Tint & Padding Safety + Token Enforcement (`AgentEdits.java`):**
   - Automatically injects `app:backgroundTint="@null"`, `android:insetTop="0dp"`, `android:insetBottom="0dp"`, and `android:textAllCaps="false"` (plus `0dp` vertical padding & `minHeight` on compact `<= 44dp` buttons/chips/swatches) whenever a custom `backgroundResource` is set on a `Button` / `MaterialButton`.
   - Supports `"token": "<key>"` in `add_widget` and `set_token`, and lazily materializes `openui_*` resources if referenced on a legacy project.

---

### Phase 16 (DONE) — Interactive Design System Page with Live Light/Dark Preview, Preset JSON Import/Save & Chat Parity (`AgentFragment.java`, `OpenUiChatRenderer.java`)
**Goal:** Upgrade Agent Mode's user-facing **Design system** page and chat preview renderer so the user can visually inspect, preview, import/save custom JSON presets, and customize their app's OpenUI theme in both Light and Dark mode without touching XML.
1. **Light / Dark Preview Switcher + Live Stage + One-Tap Customization (`AgentFragment.java`):**
   - Live `☀ Light` / `☾ Dark` preview toggle + WCAG contrast badge (`AA x.x:1 ✓`), live rendered OpenUI mini-screen card, one-tap pills for `Brand #RRGGBB`, `Corners`, `Density`, `Neutral`, and `Mode`, **Presets / Custom JSON** (starter presets + `.sketchware/ai/design_systems/*.json` user presets + Paste Custom JSON + Save Preset JSON + Copy JSON), and one-tap **Sync theme to project** upgrade action.
   - `OpenUiChatRenderer.java` recognizes all OpenUI screen aliases (`InlineHeader`, `BoldText`, `Heading`, `Body`, `Caption`, `Label`, `Chips`, `SegmentedControl`, `SwitchItem`, `Separator`, `Callout`, `Buttons`, `Form`) so chat previews and screen layouts share one unified vocabulary.

---

### Phase 17 (DONE) — Runtime Crash Guard (`strToListMap` / `strToMap`), Vision Fallback & Contrast Audit (`Fx.java`, `BlocksHandler.java`, `AiChatService.java`, `AgentLoop.java`, `AgentPreview.java`)
**Goal:** Fix both field bugs from `HabitTracker` Export #2 and catch low-contrast or clipped screens automatically before build.
1. **Null-Safe JSON Deserialization Across All 4 Gson Blocks (`Fx.java`, `BlocksHandler.java`):**
   - `strToMap`, `strToListMap`, `GsonStringToListString`, and `GsonStringToListNumber` append `if (<var> == null) <var> = new HashMap<>() / new ArrayList<>();` right in generated Java so empty `""` SharedPreferences/files on first launch never leave a `null` list/map (fixing both newly built and pre-existing projects on recompile).
2. **Graceful Text-Only Model Image Fallback (`AiChatService.java`, `AgentLoop.java`):**
   - Detects when a text-only model (such as `nvidia/nemotron-3-ultra-550b-a55b` returning `HTTP 500`, `400`, `404`, `415`, or `422` on `image_url`) rejects image pixels: caches the model as text-only for the session, strips `image_url` to text metadata while keeping attachments on disk for `import_chat_image` / `set_app_icon`, and retries automatically without failing the turn.
3. **Light & Dark Contrast + Clipping + CardView/Form Audit in `preview_screen` (`AgentPreview.java`):**
   - Audits screens in both Light and Dark palettes for low WCAG contrast, hardcoded DayNight hexes, unguarded `<Button>` custom drawables, compact vertical button clipping, multi-child `CardView` overlap, and unscrollable multi-field forms, while resolving `@color/*` and `<selector>` shapes inside rendered preview PNGs.

---

### Phase 18 (DONE) — System Prompt Alignment, Full Static Audit, Documentation & Single Green CI Build
**Goal:** Align the agent's prompt with the OpenUI Theme + Component workflow, verify every class statically, update `docs/AGENT_MODE.md`, and run GitHub Actions **once** for a single clean APK download.
1. **Concise OpenUI Design-System Prompt Update (`AgentLoop.java`):**
   - Overwrites legacy manual `set_corner_radius` Design System instructions with customizable OpenUI Theme tokens (`set_design_system` -> auto-materialized Light/Dark resources) and high-level OpenUI screen + `TextView` role components.
2. **Static Verification & Single CI Push:**
   - Verified via `scripts/check_openui_upstream.py`, `tree-sitter-java` + `javalang` AST checks, and `scripts/check_symbols.py` across all modified files before single bundled CI push.

---

## Smart Model Catalog, Dual Auto Modes, Unified Design System & Export #3 Hardening Roadmap (Phases 19–24)

**Context:** Captures every finding from `HabitTracker-agent-export-20261023-1824.json` plus all capabilities agreed in user design sessions. All six phases shipped in ONE commit and ONE green `Android CI` build.

---

### Phase 19 (DONE) — Export #3 Compiler, Menu `R.id`, Compact Padding & Hard Contrast Guards (`AgentLogic.java`, `AgentEdits.java`)
1. **Real `R.id.<name>` Support in Block Number/Comparison/Switch Slots (`AgentLogic.java`):**
   - Accept `{"res_id": "menu_settings"}`, `"@id/menu_settings"`, `"@+id/menu_settings"`, and `"R.id.menu_settings"` in integer/number slots, `if` comparisons (`=`, `!=`), and `switch` `cases` (`onOptionsItemSelected`, `onNavigationItemSelected`, etc.), compiling to real `R.id.<name>` in generated Java and validating against screen widget IDs + `res/menu/*.xml` `@+id/*` entries so models never guess fake integers like `2131230720`.
2. **Compact Button Padding Guard on `set_attributes` (`AgentEdits.java`):**
   - Prevent raw `set_attributes` (`android:padding`, `android:paddingTop`, `android:paddingBottom`, `android:paddingVertical`) on compact `Button` / `MaterialButton` widgets (`height <= 48dp`) from crushing text height and re-clipping chip labels.
3. **Hard Contrast Rejection on Unreadable Same-Surface Text (`AgentEdits.java`):**
   - Block setting widget `text_color` / `android:textColor` to a colour with `< 1.8:1` contrast against its own background or `openui_*` surface in Light or Dark mode (e.g. `@color/openui_sunk` text on `openui_sunk_input`), returning an actionable error naming `@color/openui_text_primary` / `@color/openui_text_on_accent`.

   - **Shipped:** `{"res_id":"menu_settings"}` (also `@id/…`, `@+id/…`, `R.id.…`) compiles to real `R.id.<name>` via an `asdNumber` reporter validated against `AgentResources.collectDeclaredResourceIds` (every screen widget id + every `@+id/*` / `<item type="id">` in `res/` XML); `switchValueKind` + `describeExpr` + the system prompt teach it; `set_padding` and raw `android:padding*` injects are hard-blocked on compact (≤48dp) buttons; `set_text_color` computes WCAG against the real ancestor surface in Light AND Dark, warning below `4.2:1` (3.0 for 18sp+) and hard-blocking `< 1.8:1` invisible text.
---

### Phase 20 (DONE) — Unified Design System (Retiring Separate `Style` Pill) + Theme Undo & `↺ Original Theme` (`AgentJournal.java`, `AgentTools.java`, `AgentLoop.java`, `agent_chat.xml`, `AgentFragment.java`)
1. **Merge `Style` into `Design system`:**
   - Remove the redundant `Style` button from the composer bar (`agent_chat.xml`), leaving **3 clean pills: `Design system` · `Plan` · `Task log`** and eliminating split-brain conflicts between old `styleBrief` text and live `Design system` palettes.
   - Fold the human **Mood & Audience summary** banner into the top of `Design system` (`designSystem.summary`); streamline `checkDesignGate` to **Brainstorm (`ask_choice`) → `Design system` → `Plan`** while keeping `set_style_brief` as a backward-compatible alias updating `designSystem.summary`.
2. **`↺ Original Project Theme` + 10-Step `↶ Undo` Stack:**
   - Snapshot `originalDesignSystem` automatically when a project's first theme is saved (never overwritten by later preset experiments) alongside a 10-step `designSystemHistory` stack in `AgentJournal`.
   - Expose one-tap **`↶ Undo`** and **`↺ Original Theme`** in the **Design system** header and Presets sheet, re-syncing project XML resources immediately.

   - **Shipped:** `Style` pill removed from the composer (`Design system · Plan · Task log` remain); `styleBrief` now mirrors its first line into `designSystem.summary` (shown as a tappable Mood & Audience banner at the top of the Design system page, edited by the existing brief editor); `AgentJournal` keeps `originalDesignSystem` (pinned on first save) + a 10-step `designSystemHistory`; **`↶ Undo (n)`** and **`↺ Original theme`** buttons re-materialize XML instantly; new `undo_design_system` tool (`undo` / `restore_original`); exports carry `design_system_history` + `original_design_system`.
---

### Phase 21 (DONE) — Model Capability Catalog, Metadata Badges, Permanent Hide & Active Model Probe (`ModelCatalog.java`, `AiProviderConfig.java`, `AiChatService.java`, `AgentSettingsActivity.java`, `AgentFragment.java`)
1. **Fix Model Filtering Everywhere & Extract Zero-Token `/models` Capabilities:**
   - Route both `AgentSettingsActivity` and the top-bar chat Model Picker through `ModelCatalog`, filtering non-chat models (`tts`, `whisper`, `imagen`, `flux`, `dall-e`, `veo`, `sora`, `embed`, `rerank`, `moderation`, `guard`, `ocr`, `aqa`, `live`, `audio`, `video`) by default and honoring `showAllModels`.
   - Parse rich `/models` metadata from OpenRouter (`input_modalities`, `supported_parameters` `"tools"`, `context_length`, `pricing`), Gemini (`supportedGenerationMethods`, `inputTokenLimit`), and NVIDIA/OpenAI into persisted `ModelInfo` badges:
     - **`Tools ✓`**, **`Vision`**, **`Text-only`**, **`Chat only · Can't edit project`** (when tool-calling is unsupported), context size (`128K`, `1M`), **`Free`**, and measured latency (`1.3s`).
2. **Permanent Per-Model Hide (`✕ Hide` + Unhide Manager in Settings):**
   - Allow hiding any model permanently per provider (`hiddenModels` in `AiProviderConfig`) directly from the Model Picker or Settings, with a *"Hidden models (N) — Manage / Restore"* dialog in Settings.
3. **Active Model Tester (`Test model` & `Verify top models`):**
   - Lightweight ~15-token active probe (1-tool ping + 68-byte `1×1` PNG check when vision capability is unknown) to detect dead/retired (`404`), overloaded (`503`), tool-capable, and vision-capable models and record latency permanently.

   - **Shipped:** new `ModelCatalog.java` (`.sketchware/ai/model-catalog.json`) parses OpenRouter (`architecture.input_modalities`, `supported_parameters.tools`, `context_length`, `pricing`) and Gemini (`supportedGenerationMethods`, `inputTokenLimit`) into persisted `Tools ✓ · Vision / Text-only · ctx · Free · latency` badges plus the honest "Chat only · Can't edit project" label; both Settings and the chat Model Picker now go through `ModelCatalog.visibleModels` (junk `tts/imagen/veo/embed/rerank/…` filtered by default, `showAllModels` overrides); per-model permanent **Hide (✕)** + a Hidden-restore dialog; **Test** / **⚡ Verify top models** probes (1-tool ping + 1×1-PNG vision check + latency, 404s auto-hidden as retired); live vision rejections persist "Text-only" across restarts.
---

### Phase 22 (DONE) — Dual Auto Modes: `✨ Auto (Smart Model Router)` + 5s Timed Auto-Approve (`AiChatService.java`, `AgentLoop.java`, `AgentFragment.java`, `ChatAdapter.java`, `AgentSettingsActivity.java`)
1. **`✨ Auto` Smart Model Router (Active-Provider-First + Cross-Provider Failover):**
   - Pinned at the top of the Model Picker (`model = "auto"`). Selects the best healthy `Vision + Tools` model in the active provider when screenshots or `preview_screen` visuals are active, or the best fast `Tools` model otherwise.
   - Replaces silent `180s × 3` hangs with a shorter stall timeout, live retry/failover status notes (*"Gemini Flash busy (503) — switching to …"*), 5-minute model cooldowns on `503`/`429`/timeout, and automatic failover across models in the active provider first and then across other configured provider keys.
   - **Zero-loss mid-turn context handoff:** Strips provider-specific fields (`thought_signature`, reasoning metadata, and `image_url` only if failing over to a text-only model) on the live `messages` list so completed `tool_calls` and `tool` results carry straight to the next model without re-reading files.
2. **5-Second Timed Auto-Approve (Including `build_project`):**
   - Configurable in Settings + quick toggle in Agent Mode (**default: `5s`**, including `build_project`).
   - Displays live **`Approve (5s)…` → `(1s)…`** countdown on the green button; tapping **`Details`** immediately pauses the timer for manual review; **Reject** / **Stop** cancels immediately; destructive deletes (`delete_activity`, `delete_custom_view`, `remove_component`) remain manual.

   - **Shipped:** model `auto` (shown as **`✨ Auto`**) routes per turn — vision+tools model when the turn carries images, otherwise the fastest known-Tools model — and `AgentLoop` fails over mid-turn on 429/5xx/network (5-min cooldown per model, `sanitizeMessagesForModel` strips `thought_signature`-class fields and downgrades images for text-only targets so completed tool calls carry over); agent turns use a 90s read timeout instead of silent 180s×3; **5-second Timed Auto-Approve** on by default (Settings switch + `⏱` toggle in the Model Picker footer): live `✓ Approve (5s)…` countdown on the card, paused by Details or any touch, immediate on Reject, and `delete_activity` / `delete_custom_view` / `remove_component` / file-resource deletes are manual-hold forever.
---

### Phase 23 (DONE) — Compact Project Index, Failed-Turn Read Cache & Vague-Prompt Intelligence (`AgentLoop.java`, `AgentTools.java`)
1. **Compact Project Index (~350 Tokens) in `systemMessage`:**
   - Summarizes screens, key widget IDs, `RecyclerView → item_*` links, components, variables/lists, SharedPreferences keys, `@+id/*` menu items, and wired events so newly switched or `✨ Auto` models orient immediately without 4–5 discovery tool calls and never drift on storage keys across screens.
2. **Failed-Turn Read Cache & Clean Retry UI:**
   - Preserves read-only tool findings across `Tap to retry` / `Resume` until the next project edit, and removes superseded error banners once a retry succeeds.
3. **Vague-Prompt Product & Keyless-API Standard:**
   - Guides short/vague prompts (*"build me a notepad"*, *"build me a weather forecasting app"*) through visual brainstorming → custom OpenUI Design System → complete real-world Plan, defaulting live-data apps without user keys to **free keyless public APIs** (e.g. Open-Meteo geocoding + forecast) with `INTERNET` permission + `RequestNetwork` + offline cache.

   - **Shipped:** the system message now carries a **PROJECT INDEX** (~350 tokens): per screen its widget ids, wired events, components, variables, lists, More Blocks and SharedPreferences files (`param1`), plus item layouts and `res/menu/*.xml` ids with a `{"res_id":…}` hint; **read-tool retry cache** (`AgentJournal.readCache`) re-briefs failed turns with what `get_screen` / `get_event_logic` / `read_source_files` etc. already found, cleared on mutation or success; AGENT_GUIDANCE now handles vague prompts (brainstorm → complete plan, never ask the user to design) and mandates free keyless APIs (Open-Meteo geocoding+forecast, INTERNET permission, offline cache) for live-data apps without keys.
---

### Phase 24 (DONE) — Modern Material 3 Choice/Picker Sheets with Live Colour Swatches, Full Static Audit & Single Green CI Build (`AgentFragment.java`, `AgentSettingsActivity.java`)
1. **Material 3 Card Bottom-Sheets Replacing Bare `AlertDialog.setItems`:**
   - Upgrade **`ask_choice`**, **Model Picker**, **Key Switcher**, and **Design System Presets** from borderless text lists into rounded, outlined, ripple-enabled Material 3 cards with clear **Title + Subtitle + right-hand badge/radio/chevron**.
2. **Live Colour Swatches & Palette Preview Inside `ask_choice`:**
   - Render real `#RRGGBB` colour swatch circles + mini Light/Dark surface preview strips directly inside each style/colour option card, plus **"Pick custom colour…"** (Sketchware colour wheel), **"Type my own…"**, and **"You decide"** actions at the bottom.
3. **Full Static Audit, Documentation & Single Green `Android CI` Build:**
   - Run `tree-sitter-java`, cross-class method/arity/visibility/import/checked-exception checks, `scripts/check_symbols.py`, and `scripts/check_openui_upstream.py`, update `docs/AGENT_MODE.md` + `docs/AGENT_ROADMAP.md`, and push **once** for a single verified GREEN APK.
   - **Shipped:** `ask_choice`, the Model Picker, the Key Switcher and Design-System Presets are now Material 3 card sheets (`showCardSheet`) — rounded filled/outlined cards with bold title + subtitle, radio-style active ring, and **live colour swatch circles + Light→Dark gradient strips** wherever a choice carries hexes; every `ask_choice` also offers **🎨 Pick a custom colour…** (Sketchware colour wheel), **⌨ Type my own…**, and **✨ You decide**; full static audit (tree-sitter parse of all 16 touched classes + cross-file symbol contract + strings/layout checks) before the single green CI push.

## Wave 53 / Phases 25–30 — SkyCast Field Fixes: Strength Router, Honest Handover, Clean Pickers (`ModelCatalog.java`, `AgentLoop.java`, `AgentFragment.java`, `AgentSettingsActivity.java`, `agent_chat.xml`)

**Context:** Every finding from `SkyCast-agent-export-20261023-2038.json` (Auto picked `gemini-3-flash-preview` because it was *fastest*, a mid-turn 429 handed the conversation to `gemini-3.1-flash-lite`, which 400-rejected the carried-over payload; the choice cards had colour words but no hexes so no swatches drew; the choice sheet never closed on tap; welcome pills clipped; picker controls buried under 300 cards). All fixes shipped in ONE commit and ONE green `Android CI` build.

---

### Phase 25 (DONE) — Strength × Aliveness Router
- `pickAutoModel` no longer ranks by speed. Family strength first (Claude/GPT/Gemini-Pro/DeepSeek/Grok/Kimi/Qwen-Max/large-Llama/Mistral-Large above Flash/Lite/Mini tiers; `preview`/`exp`/`beta` penalized), then known-tools bonus, context size, recency of errors (−40 when the model errored in the last 30 min), latency only breaking ties. A clean turn calls `markSuccess` (clears cooldown); `auto_route` reason now reads "strongest healthy model".

### Phase 26 (DONE) — Escalating Cooldowns & Rate-Limit Visibility
- `cooldown()` doubles the remaining window on every consecutive failure (5 → 10 → 20 … capped at 60 min); `coolingForMillis()` powers a `⏳ Rate-limited · Xm left` badge on picker cards, and cooling models sink to the bottom of the list.

### Phase 27 (DONE) — Honest Handover (Auto switches, manual never)
- Mid-turn failover is gated on ✨ Auto. A manually picked model that 429s/5xxs keeps the turn: the error bubble surfaces, a system note names what Auto would have tried ("tap the model bar to try X, or switch to ✨ Auto"), and the model still earns its cooldown badge. Auto's switches are now a persistent SYSTEM chat message with friendly names (`✨ Auto: gemini-3-flash-preview busy (429) — switching to …`) plus a live header callback.

### Phase 28 (DONE) — Sanitizer Hardening (the SkyCast 400)
- `sanitizeMessagesForModel` now rewrites `content: null` → `""` (Gemini's OpenAI-compat endpoint 400s on null content on assistant tool-call messages) and strips `name` from `role:"tool"` messages. A 400 that arrives right after a handover is reclassified as switchable (`handoverRejected`), so the turn recovers instead of dying and re-picking the same broken pair next turn.

### Phase 29 (DONE) — Clean Pickers & Live Header
- Model cards are name + badge only (`Untested — long-press to test or hide`); `Test` / `✕ Hide` moved to a long-press options sheet; ids shown via `displayName` (`models/gemini-3.1-flash-lite` → `gemini-3.1-flash-lite`). The picker is now pinned-header + scroll: `✨ Auto` card and the `⚡ Verify · ⏱ · Hidden` controls stay fixed on screen. Settings' model dropdown shows the same badges (two-line `BadgeAdapter`). The chat-bar header goes live: `✨ Auto · <model>` on route and on every mid-turn switch.
- Welcome quick actions stack vertically (three full pills) — the horizontal `HorizontalScrollView` that clipped "Inspect & improve project" is gone. A failed turn's empty `…` placeholder becomes the error text itself instead of a ghost bubble.

### Phase 30 (DONE) — Swatches Always Draw & Sheets Close
- The system prompt now requires hex codes inside every colour/style `ask_choice` option ("the app draws swatches from those hexes"), and as a code safety net `showChoiceQuestion` approximates swatches from ~30 colour words (soft blue, mint, charcoal, frost…) when an option still arrives without hexes. Every answer tap — option or "✨ You decide" — closes the sheet immediately (first tap wins; no second-tap overwrite), while 🎨 / ⌨ launchers keep it open and close it only when they succeed.

## Wave 54 / Phases 31–34 — SkycastWeather Field Fixes: Plan Tracks Reality, Concrete Models Only, Teaching Errors (`AgentJournal.java`, `AgentLoop.java`, `AgentLogic.java`, `ModelCatalog.java`)

**Context:** From `SkycastWeather-agent-export-20261023-2324/-2358.json`: the plan froze at 0/6 across 137 tool calls; ✨ Auto resolved to the pseudo-model `openrouter/auto` in 6 of 8 turns (header honestly showed "Auto · auto"; backend model unknowable); `set_screen_title needs "text"` repeated five times with no teaching context and killed the turn; a confused model confabulated that the wiring tool was missing.

---

### Phase 31 (DONE) — Reality-driven plan progress
- Every applied change records evidence (`AgentJournal.notePlanEvidence`); a plan step completes when ~80% of its meaningful keywords appear in the evidence, advancing monotonically. The Plan page now moves with the actual work, never waiting on the model's bookkeeping.

### Phase 32 (DONE) — Concrete models only
- `ModelCatalog.isRouterPseudoModel` (ids whose bare name is `auto`/`router`/`smart`/`default`) is junk-filtered from pickers, excluded from ✨ Auto under every flag, and badged "Provider router — not a real model". Routing through `openrouter/auto` — the root of the invisible-backend problem — is structurally impossible.

### Phase 33 (DONE) — Block errors teach
- Every refused block now reports its position, name, the exact received JSON, and the fix instruction (`Block 2 of 3 (set_screen_title): needs "text" — e.g. {"block":"set_screen_title","text":"Skycast"}. You sent: … — fix that field and resend the whole call.`). A second identical failure raises a visible ⚠️ system note before the turn stops, so the user sees the precise blocker.

### Phase 34 (DONE) — Anti-confabulation guard
- The system prompt states flatly: tools never disappear mid-session; an error means invalid arguments — read it, fix the named field, resend; never claim a tool is missing or offer workarounds for missing tools.

## Wave 55 / Phases 35–38 — RecipeFinder Field Fixes (`AgentLogic.java`, `AgentBuild.java`, `AiChatService.java`, `ModelCatalog.java`)

**Context:** From `RecipeFinder-agent-export-20261024-1200.json` (274 tool calls, 73 errors, 5 failed builds): the nested `list_map_value` object form failed with "no list of maps called ''" (the handler read the whole object as the list name); 5 javac builds burned ~30–90 s each on `_meal_detail cannot be resolved` with no translation to agent language; the 429 cascade still touched `openrouter/free`, `openrouter/auto-beta` and `typesafe/jev-router` (pseudo-filter gaps); and OpenRouter's default max_tokens = whole context caused "can only afford 18906 tokens" 402s on reachable models.

---

### Phase 35 (DONE) — Nested `list_map_value` / `list_map_has`
- The expression handler now accepts both shapes — flat `{"list_map_value":"list"}` and the nested object `{"list_map_value":{"list":…,"index":…,"key":…}}` models naturally write — and when the list is missing, the error names every declared list of maps instead of an empty name.

### Phase 36 (DONE) — javac errors translated
- `_x cannot be resolved` in any compile failure is now explained in agent language: it is the generated field of the screen's variable/list `x`, undeclared on that screen — declare it (right type) or remove the using blocks. The five blind build attempts become one informed fix.

### Phase 37 (DONE) — Pseudo-model filter closed
- `auto`, `auto-*`, `free`, `free-*`, `router`, `*-router`, `smart`, `default` are all filtered/route-blocked/badged — `openrouter/free`, `openrouter/auto-beta` and `typesafe/jev-router` can no longer be picked or routed through.

### Phase 38 (DONE) — Honest request sizes & free-tier memory
- OpenRouter requests carry `max_tokens: 8192` (agent replies are tool-call JSON), killing the "requested 131072 but can only afford 18906" 402 class on reachable models.
- `:free` 429 cooldowns start at 15 minutes (they reset hourly/daily, not every 5), and all cooldowns persist to a sidecar file so an app restart no longer forgets which models are resting.

## Wave 56 / Phase 39 — Every Sheet Closes on Selection & Wears the Active Ring (`AgentFragment.java`)

**Context:** The user caught two surviving sheet defects: the Key Picker resumed the failed turn *behind* the still-open sheet, the Design-System Presets sheet applied a theme while staying open, and the model long-press sheet ran Test/Hide behind itself; and selections were not visibly reflected on the tapped card.

---

### Phase 39 (DONE) — Close-first selections + active ring everywhere
- **Key Picker:** the pick dismisses the sheet *before* `retryFailedTurn()` — the retry can never resume behind a dialog. The active key keeps its ✓ ring.
- **Design-System Presets:** Apply closes the sheet first, then applies; the currently-applied preset wears the active stroke and a "✓ Applied" trailing label; Import / Save stay open as launchers (matching 🎨 / ⌨ behaviour in ask_choice).
- **Model long-press actions:** Test and Hide close the action sheet before doing anything — a probe or a hide can never run behind the dialog.
- Every sheet now follows one rule: a selection acts only after its dialog is gone; only sub-launchers stay open, and they close on success.

## Wave 57 / Phases 40–42 (DONE) — RecipeFinder Ghost Declarations, Raw-Code Hygiene & Empty-Bubble Turn End

**Context (field report, 24 Oct):** RecipeFinder's javac failures were finally diagnosed by the model itself (excellent report, followed the user's instructions exactly after earlier confusion). The project holds **two parallel naming systems**: raw injected declarations with NO underscore (`meal_detail`, `detail_image`, `detail_name`, `detail_category`, `ingredients_list` — emitted by `addSourceDirectly`/`addCustomVariable` blocks the agent wrote during an earlier struggling session) versus standard Sketchware block references WITH underscore (`_meal_detail`, …). Secondary raw-code bugs in the same injected lines: a `Map<String,Object>` declared where the list add expects `HashMap<String,Object>`; an unclosed string literal split across lines (`…append(step).append(". \n`); a reference to an undefined `_activity` field. NOTE: `MainActivity`/`SearchresultsActivity` now compile — only `MealdetailActivity` still fails. The user was given a manual fix script (edit the raw blocks to underscore names, fix the two lines, rebuild); Wave 57 makes the APP prevent this class of failure. Also registered: a turn that ends with a completely EMPTY assistant bubble (observed twice this session), and the earlier `create_variable` → `E_NAME_TAKEN` vs javac "cannot be resolved" deadlock (store says declared, generator skips — likely a store entry the IDE generator doesn't read; the app's own add-variable/add-list path must be audited).

---

### Phase 40 (DONE) — Ghost-declaration audit & repair (`AgentTools.java`)
- **Audit result:** the agent's `create_variable` is sound — its execute path writes through the IDE's own store lists (`store.b` variables / `store.c` lists), calls `commitProject`, and reads the entry back to verify. The RecipeFinder ghosts came from RAW `addCustomVariable` declarations injected during the earlier struggling session, not from the tool — so no write-path change was needed; Phase 41's shadow guard now blocks that injection class at the source.
- **Repair path shipped:** the `E_NAME_TAKEN` refusal (both throw sites, `create_variable` and the list twin) no longer dead-ends — it names what exists, tells the model to USE the existing entry (`{"list":name}` / `{"var":name}`), offers `remove_variable` + re-create when the type is wrong, and explicitly forbids declaring a raw-code copy under the same or similar name. The conflict detail itself now says what the existing entry is (type-aware wording via the store type codes) instead of the old flat "already declared".

### Phase 41 (DONE) — Raw-code hygiene (`AgentLogic.java`)
- `Builder.validateRawJava(code, isFieldDeclaration)` now gates **every user raw-code entry point** before `sanitizeRawJava`: `add_source_directly`/raw_java/java_code/source_directly, `add_custom_variable`/`add_initializer` (as field declarations), nested raw bodies inside wrapped blocks, and the `edit_block` slot-`i` replacement path (`builder.validateRawJava` in the edit planner). The internal generators' own `addSourceDirectly` beans (dialog show/dismiss etc.) are pre-validated code and stay outside the gate, as do single-expression `asd*` value slots (a bare local before `)` there is legitimate).
- **Unclosed-literal guard:** per raw line, unescaped-quote parity — odd count ⇒ `E_BAD_BLOCK` "unclosed string literal (it must be one complete line)" naming the offending line. This is exactly the `. \n` split-line bug from MealdetailActivity L253.
- **Shadow guard (field declarations):** a raw declaration whose name already exists on the screen — variable, list, component, More Block, or widget — ⇒ `E_BAD_BLOCK` naming the collision and the rule (lists/variables arrive with the `_` prefix in generated code; use the existing field instead of redeclaring). This kills the ghost-declaration class: `meal_detail`, `detail_image`, `detail_name`, `detail_category`, `ingredients_list` would all have been refused on sight.
- **Prefix guards (whole snippet):** a `_name` reference where `name` is a widget/component id ⇒ `E_BAD_BLOCK` "widget fields WITHOUT an underscore prefix — write name" (the `_detail_image` misuse); a bare `name` reference where `name` is a store variable/list ⇒ `E_BAD_BLOCK` "…WITH an underscore prefix — write _name" (the declaration-vs-reference split from the field report). Snippet-local declarations, Builder loop variables, and MoreBlock `%m.` reference params are exempt so legitimate locals never trip it.

### Phase 42 (DONE) — Empty-bubble turn end (`AgentFragment.java`)
- At COMPLETE, a reply whose text is blank (whitespace-only) no longer renders as an empty bubble: the bubble flips to ERROR state with "The model returned an empty reply — tap to retry." and joins the standard failed-bubble retry affordance (observed twice in the RecipeFinder session — the model ended its turn after tool errors with nothing on screen).

### Hotfix — Model-selector dropdown crash (`AgentSettingsActivity.java`)
- Field report (24 Oct): Settings → OpenRouter → Refresh models → tap the model selector ⇒ `IllegalStateException: ArrayAdapter requires the resource ID to be a TextView` (Caused by `TwoLineListItem cannot be cast to TextView`) at `BadgeAdapter.getView`. Latent since Wave 53: `BadgeAdapter` passed `android.R.layout.simple_list_item_2` (root `TwoLineListItem`) to `ArrayAdapter` and called `super.getView()`, and ArrayAdapter's default binder only auto-fills a bare `TextView` — it crashes the first time the dropdown opens with a **non-empty** cached model list (empty caches never reach `getView`, which is why it hid through Waves 53–56). Fixed: the super class gets the TextView-rooted `simple_dropdown_item_1line`, `getView` inflates/binds the `TwoLineListItem` row itself (recycling only same-type rows), and `getDropDownView` routes through `getView` so the dropdown's scrap stays homogeneous. Same visuals as before (14sp name, 11sp badge at 0.85 alpha).

---

## Wave 58 / Phases 43–44 (DONE) — The Invisible Choice Popup (field report, 25 Oct)

**Field report:** "Create for me a habit tracker app" in a fresh draft chat (gpt-oss-120b, OpenRouter) never showed the design-choice popup. Export forensics: the model made **zero tool calls** — the system prompt's ask_choice instruction was answered by *drawing* an OpenUI fence (`ChoiceChips( label: "…", $style = "", options: [...] )`) in the reply text instead. Three independent layers then each contributed to the user seeing nothing:
1. **Grammar rejection:** OpenUI Lang statements are `identifier = Expression` lines; the fence had no statement-level `=` (and `label:`/`$style =` argument syntax is not the grammar), so `parse` produced zero statements and the whole block was skipped as malformed.
2. **Silent renderer:** `renderProgramInto` found no root and, not streaming, added **no view at all** — an empty gap where the choices should be, no error, no source.
3. **No tool → no popup:** the ask_choice sheet only exists inside the tool path, so nothing tappable ever appeared; the user answered "I did see anything".

### Phase 43 (DONE) — Choice auto-bounce (`AgentLoop.java`, `AgentFragment.java`, `OpenUiLang.java`)
- `OpenUiLang.extractChatChoice(text)`: tolerant recovery that ignores the strict grammar — finds an ```openui fence rooted at `ChoiceChips`/`Select`/`Choice`, collects its string literals (skipping empties, `$var` bindings, bare hexes and bare numbers), and returns `[question, option…]`. Validated in a standalone harness against the exact fence from the field export (question + 3 options), the positional `Select` form, and four negatives (Table/StyleCard/no-fence) — no false positives.
- `AgentFragment.maybeBounceChatChoice` at turn end (after COMPLETE): when **no choice sheet ran this turn** (`choiceSheetShownThisTurn` — set by the tool path, reset in `startRequest`), the extracted question/options go through the very same `awaitChoice` sheet (swatch rows, 🎨/⌨/✨ rows included) on a daemon thread; the pick is sent as the user's own message via `sendChoiceAsUser`. One-shot per turn; a properly tool-called ask_choice never double-pops.
- Prompt hardened in both places: the DESIGN BEFORE YOU BUILD rule now says the options must be INSIDE the ask_choice tool call (never an ```openui block, ChoiceChips/Select, or a plain list — the app can only pop the sheet from the tool call); the screen-component catalog now marks ChoiceChips/Select "app screens only, never inside a chat reply".

### Phase 44 (DONE) — OpenUI blocks are never silent (`OpenUiChatRenderer.java`)
- `renderProgramInto` now receives the raw segment source; when no root resolves (or `buildNodeView` yields nothing) on a completed reply, it renders a fallback card — "This interactive block could not be displayed — its source is below." — with the source in selectable 11sp monospace (capped at 1200 chars). Streaming keeps the skeleton. No reply can again lose its interactive block into an empty gap.

### Hotfix — bounce precision & stale-sheet cancel (`AgentFragment.java`)
- Field follow-up ("everything worked until the last update — did tool calling get killed?"): audit proved the tool machinery untouched by Wave 58 (`AgentTools` registry +50 tools, `AiChatService` wire protocol, and the AgentLoop dispatch chain have zero diff); the two visible defects were in the bounce itself. (1) It fired on ANY ChoiceChips/Select fence — including deliberate in-chat interactive OpenUI (layout picks, mockups), whose turn-end pick was then injected as a user message, hijacking the conversation. Now it fires only on the design-question signature: ≥2 options carrying hex codes (which the DESIGN BEFORE YOU BUILD prompt mandates for style directions). (2) A bounce sheet could linger up to 10 minutes after the turn; picking late either dropped silently or fired an out-of-context message. Now `cancelPendingChoiceSheet()` dismisses it the moment the user sends their own message or resumes work (tool-path sheets are untouchable — they only exist while generating). The `choiceSheetShownThisTurn` flag also moved from `awaitChoice` into the `onAskChoice` listener so only the tool path can set it.

### Hotfix 2 — the prompt line caused a NEW failure mode (`AgentLoop.java`)
- Field report (25 Oct, 12:53): with the Wave 58 prompt (26,536 vs 26,203 chars) gpt-oss-120b answered the design gate by **printing the ask_choice call as raw JSON text** in the bubble — zero real tool calls. The 12:00 turns (old prompt) prove the model already skipped the tool call by DRAWING chips; my "Put the options INSIDE the ask_choice tool call" sentence merely flipped the failure mode to hand-written JSON. Baseline (23 Oct HabitTracker exports): same model made plenty of real calls at ~23.8k prompt chars — prompt growth plus a 20B-class model is the underlying fragility.
- **`recoverTextToolCall` (the structural fix):** before a reply is judged, if its ENTIRE content is a JSON object naming a tool — `{"tool":…}` / `{"name":…}` or the OpenAI wire shape `{"type":"function","function":{…}}`, arguments as object or string, optionally fenced — the call is reconstructed and dispatched normally. The matching `tool_calls` entry is written into the raw assistant message so the tool result is protocol-valid. Verified against the exact failing reply from the export (recovers ask_choice with question + 3 options) and negatives (prose, random JSON, empty — untouched). The model's worst habit now produces the correct popup.
- **Prompt reword:** "Make ask_choice a REAL function call — question and options go in the arguments and the app pops the sheet itself. Do not print the call as text or JSON in your reply, and do not draw the options as an ```openui block, a ChoiceChips/Select component, or a plain list.
- **Option count raised (field request):** the design gate now asks for **three to five** whole directions ("more choice is better than less"), matching the ask-choice-over-chat rule that already said three to five — the model had drifted to the minimum.

### Hotfix 4 — the JSON ban (field report: JSON still shown on a8812cd; directive: "ban json from ever entering the chat")
- The guarantee moved to the door of the chat instead of chasing shapes, in two layers:
- **AgentLoop (agent turns):** recovery is now shape-blind — bare object, fenced (tag on its own line or sharing the first line, `json` remnant stripped), wrapped in prose (string-aware balanced-brace scan), a top-level ARRAY of calls, a `{"tool_calls":[…]}` wrapper, the OpenAI wire shape, arguments under `arguments`/`parameters`/`args`, tool names in any casing/separator (`askChoice` → ask_choice), and the intent fallback: an object carrying `question` + ≥2 `options` IS an ask_choice even with no usable name. Execution stays strict — only `AgentTools.isInteractive` names are ever lifted. After recovery, `scrubToolJson` masks ANY remaining tool-shaped object (mutations, unknown tools) to a one-line note: banned from display without being executed. Plain data the user asked for (sample payload, config) is untouched — no tool keys ⇒ no mask.
- **AgentFragment (every completed bubble, plain-streaming path included):** `setStreamingComplete` scrubs the final text through the same `scrubToolJson`, and `bounceIfActionable` pops the real choice sheet from either source — a drawn ChoiceChips fence (design-signature rule unchanged) or a text-object ask_choice via `extractTextChoice` — sending the pick as the user's message. One bounce per turn (`choiceBounceRunning`); manual send cancels a pending bounce sheet.
- **No model flagging:** per the user's direction, no model is hidden or deprioritized — the ban targets the text, never the model list. Port-tested: the field reply plus parameters/camelCase/array/wrapper/wire/intent-only/prose/fence-tag shapes all recover or mask correctly; plain-data JSON and prose pass through untouched. (Docs note: an earlier failed edit truncated this file for one commit, 80a8163 — restored here in full.)

### Hotfix 5 — exports retention (field request: ".Sketchware/ai/exports is collecting space; shared them — what next?")
- The exports folder had no cleanup at all: every Share/Export wrote a 0.5–4 MB JSON that lived forever. Now `AgentExport.prune()` keeps it bounded — deletes anything older than **7 days**, then (oldest first) until the folder is under **20 MB** — and runs silently in two places: when the agent opens (background thread in `onViewCreated`) and before each new export is written. Sharing needs no bookkeeping: a shared export simply ages out like the rest. Exports made recently and not yet shared always survive the age pass.
### Hotfix 3 — recovery hardened after the field retest (`AgentLoop.java`)
- Field retest on `apk-release-0f508bd` still showed JSON bubbles. Two gaps: (1) recovery required the ENTIRE reply to be the object — a sentence around it (or a fence tag sharing its first line) bailed; (2) even when recovery fired, the raw JSON stayed in the bubble next to the sheet, so it still READ as broken.
- Now: a string-aware balanced-brace scan lifts the FIRST `{...}` that names a **known interactive tool** (`AgentTools.isInteractive` gate — a hand-written `add_source_directly` or unknown tool is never executed), fence-tag tolerant; the JSON is stripped from the bubble (kept prose, or "The question sheet is open — pick there." when the object was the whole reply); the matching `tool_calls` entry is still declared into the raw message. Port-tested against the field reply and 7 more cases: prose-before/after, ```json fence, nested args, non-interactive tool (refused), unknown tool (refused), random JSON (refused), prose (refused).

---

## Wave 59 / Phase 45 (PENDING) — The Plan Contract: the implementation plan becomes state the app tracks, not a promise the model keeps

**Field context (25 Oct):** the user reports the agent does not work in tandem with the implementation plan — models drift off it, and the plan checklist's own checkboxes can be freely tapped with no effect on anything. Verdict: the plan is currently a display, not a contract; the model is asked to remember it (it doesn't) and the user's taps do nothing (nothing listens). Build the contract in ONE later build, only after the user confirms the JSON-ban APK (35407f0) holds in the field. **No model flagging or hiding of any kind — model availability stays untouched.**

### Phase 45 (PENDING) — Plan as contract (`AgentLoop.java`, plan tool + UI surface)
1. **App-verified ticks:** a step may only be marked done when the artifact it names verifiably exists in the project (screen/widget/event/component/compile — checked through the app's own project state, never through the model's claim). The model never ticks its own boxes.
2. **The plan rides in every turn:** open steps (and who owns each — agent or user) are re-sent in the model's briefing EVERY turn, so attention drift cannot lose the plan mid-build.
3. **User taps steer the agent:** unticking a step ⇒ the next briefing says "the user reopened this step — it is not acceptable as-is"; ticking a step ⇒ recorded as the user's sign-off. The checklist becomes the steering wheel, not a mirror.
4. **Drift is named, never blocked:** work that matches no open step gets a quiet one-line note in the tool result ("this is not in the plan — update the plan or return to it"). No refusals, no dead ends.

**Acceptance:** in a fresh draft-chat build, a model that skips verifiable steps cannot end the task with them unticked unexplained (the briefing forces the gap into the open); a user untick forces an explicit rework mention next turn; zero new E_* refusals introduced.

---

## Wave 60 / Phases 46–48 (PENDING — DO NOT BUILD until the user explicitly says so) — Agent-Minted Vocabulary: custom blocks, events & components via tool calls

**Research findings (25 Oct; merged from two repo-verified passes):** the out-of-editor managers are JSON stores the IDE reads at build time — the agent can mint new building blocks without compiler changes:
- **Blocks Manager:** `mod.hilal.saif.activities.tools.BlocksManager` (+ Creator/Details, Settings→Tools) over TWO global files: `.sketchware/resources/block/My Block/block.json` and `palette.json`. A block is `{name, spec, optional spec2, code, type/typeName, color, palette}` — `spec` is the on-screen text with argument placeholders (`%s` string, `%d` number, `%b` boolean, `%m.X` menu), `code` the matching Java template with positional `%1$s…` refs. Loaded by `ExtraBlockFile` + `BlockLoader` (hot `refresh()` exists) into EVERY project's palette; Sketchware's built-in blocks use the same shape (`BlocksHandler.builtInBlocks`) — a custom block is literally "a built-in block the user added". Per-project usage tracked in `.sketchware/data/<sc_id>/custom_blocks` (`CustomBlocksManager`).
- **Events:** an events manager surface EXISTS — `pro.sketchware.fragments.settings.events` (EventsManagerFragment + creator + details) writing `.sketchware/data/system/events.json` + `listeners.json`, entries `{name, var, listener, icon, description, parameters}` (file-verified). The parallel pass judged the editor-side event system hardcoded per widget/component type and favors the **events-as-blocks** pattern (`BlocksHandler` already ships event-style blocks like "When %m.view clicked" with a `setOnClickListener` template) — Phase 47 must verify in-app how custom events surface in the editor before writing events.json, and teach events-as-blocks in the prompt either way.
- **Components:** Developer Tools → Component Manager (`ManageCustomComponentActivity` / `AddCustomComponentActivity`) over `.sketchware/data/system/component.json` (`wq.CUSTOM_COMPONENT_FILE`): register a class from an ENABLED library as a reusable component type with declared methods; import/export supported.
- The agent today has `create_more_block` (project-local procedures) but NO tool touches the global stores.

**Risks the phases must handle:** the stores are GLOBAL across projects (a bad template breaks every project using the block) ⇒ name-conflict gates, create-time validation, an edit/repair tool; same-turn use needs the palette reload (`BlockLoader.refresh()`); templates are the skill class that produced the Mealdetail mess ⇒ `%s`-template validation and propose/approve cards are mandatory.

### Phase 46 (PENDING) — `create_custom_block` / `edit_custom_block` / `list_custom_blocks`
Write through the SAME block.json/palette.json the manager reads (human and agent share one truth; agent-made blocks stay fully editable in the manager UI), then `BlockLoader.refresh()`. Validation: unique name (no clash with built-in/ExtraBlocks), legal spec grammar (balanced, known placeholder kinds), code template referencing exactly the spec'd params in order, balanced braces/quotes, size cap, valid palette index + color. Approve card shows the human-readable block. `project_state` gains a "custom vocabulary" section.

### Phase 47 (PENDING) — `create_custom_event` (after in-app verification)
Write `{name, var, listener, icon, description, parameters}` into events.json/listeners.json with the same gates — ONLY after verifying how custom events surface in the editor; teach events-as-blocks templates in the prompt as the dependable alternative.

### Phase 48 (PENDING) — `create_custom_component` / `list_custom_components`
Define a component type in component.json (library class gated on the library being enabled in the working project, declared methods), usable afterwards through the existing create_component flow; same gates, approve card, read-back, project_state vocabulary.

**Order and gate:** Phase 46 is the big unlock, well-bounded storage; 47 and 48 follow. Land as ONE build each, after Wave 59's plan contract — and NOTHING gets built until the user explicitly says so. **Acceptance (all three):** a definition created in one project is usable there and in future projects; every definition lands only through an approval card; a conflicting or malformed template is refused with a repair-path message (E_NAME_TAKEN style, no dead ends); nothing new is flagged or hidden in the model picker.

---

## Wave 61 / Phases 49–51 (PENDING — DO NOT BUILD until the user explicitly says so) — Core platform gaps: snapshots, Firestore, background work

**Field context (25 Oct):** persisted from the gap analysis (all repo-verified). The user's rule: CORE features get phases here; everything else is sidelined to the backlog list at the end of this wave. Nothing is built until the user says so, one build per wave.

### Phase 49 (PENDING) — Project snapshots & one-tap rollback (the safety layer)
Auto-snapshot the project before every agent mutation batch (compact point-in-time copies alongside the manual .swb backup path — `mod.hey.studios.project.backup.BackupFactory/BackupRestoreManager` already provide restore plumbing), a timeline UI in project settings, and one-tap restore. This is the pairing that makes the agent fearless: every change undoable. Sizes capped, snapshots pruned like ai/exports (age + total-bytes). Also the agent's own escape hatch: a `restore_snapshot` tool for the user via approval card.

### Phase 50 (PENDING) — Firestore support (the biggest backend gap)
Native Firestore component + blocks (CRUD, queries, listeners) alongside the existing FirebaseDB/RTDB blocks; requires the Firestore library shipped/enabled like the other Firebase pieces and a helper-first design so weak models don't hand-write listeners. First candidate to be built USING the Wave 60 custom-block system where sensible — dog-food test for agent-minted vocabulary.

### Phase 51 (PENDING) — Background work (WorkManager component)
A WorkManager-backed background-task component with blocks (schedule one-time/periodic work, constraints, cancel), closing the "timers die with the app" gap. Manifest wiring via the existing injection path.

**Sidelined (backlog, not phases):** localization manager (values-<lang> UI), test support (unit/UI), in-app billing blocks, crash/analytics reporting for built apps, home-screen widget builder, Wear/TV targets, ConstraintLayout palette entry, API designer for hosted backends.
