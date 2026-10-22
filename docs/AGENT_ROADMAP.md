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
| Resource Editor (`strings.xml`, `colors.xml`, `styles.xml`, `arrays.xml`) & Raw Resource Manager (`res/drawable`, `menu`, `anim`, `xml`, `values-night`) | `ResourcesEditorActivity`, `StringsEditorManager`, `ColorsEditorManager`, `StylesEditorManager`, `ArraysEditorManager`, `ManageResourceActivity` | Phase 4 |
| Font Manager (`assets/fonts`) & Sound Manager (`res/raw`) | `ManageFontActivity`, `ManageSoundActivity`, `jC.d(scId)` | Phase 4 |
| Java & Kotlin Manager (`.java` / `.kt` classes) & Assets Manager (`assets/`) & Native Libs (`.so`) | `ManageJavaActivity`, `KotlinCompilerBridge`, `ManageAssetsActivity`, `ManageNativelibsActivity` | Phase 5 |
| Full Pro Events, Component Callbacks, Custom Blocks, Command Blocks (`ManageXMLCommandActivity`) & Developer Custom Components/Events | `EventsHandler`, `ManageEvent`, `CustomBlocksManager`, `BlocksManager`, `ManageXMLCommandActivity`, `CommandBlock`, `ComponentsHandler` | Phase 6 |
| AndroidManifest Injection & AppCompat XML Injection (`Toolbar`, `AppBarLayout`, `CoordinatorLayout`, `FAB`) | `AndroidManifestInjection`, `AndroidManifestInjector`, `ManageAppCompatActivity`, `AppCompatInjection` | Phase 7 |
| Pro Project Settings (`minSdk`, `targetSdk`, `ViewBinding`, custom `Application` class, version/package), ProGuard/R8 & StringFog | `MyProjectSettingActivity`, `ProjectSettings`, `VersionDialog`, `ManageProguardActivity`, `ProguardHandler`, `ManageStringFogFragment`, `StringfogHandler` | Phase 7 |
| Show Source Code Inspector, Runtime Logcat Crash Reader, Collections Manager, `.aab` App Bundles, Keystore Signing & `.swb` Backups | `SrcViewerActivity`, `Jx`, `LogReaderActivity`, `CompileErrorSaver`, `ManageCollectionActivity`, `AppBundleCompiler`, `TestkeySignBridge`, `BackupFactory` | Phase 8 |

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
