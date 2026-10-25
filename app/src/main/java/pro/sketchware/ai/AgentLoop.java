package pro.sketchware.ai;

import android.content.Context;

import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The agent loop: the model proposes tool calls, the app executes them,
 * results go back, and the model continues — until it answers in plain
 * text. Hard-capped (about twenty calls per message), and stopped early
 * when the same failure repeats, instead of spinning against the user's
 * key.
 *
 * <p>Runs on a background executor; listener callbacks already arrive on
 * the main thread. When the provider cannot do tool calling at all, the
 * loop reports it as an unsupported error and the chat falls back to a
 * plain conversation — the user is never stuck.
 */
public class AgentLoop {

    /**
     * Tool calls allowed before the loop rolls into the next segment
     * of the SAME task. This is not a task limit: when work is still
     * in progress at the cap, the loop continues by itself with a
     * fresh request carrying the task's memory, so a big build is not
     * chopped into pieces the user has to re-request — and a resumed
     * segment can never redo what already landed, because the app
     * refuses duplicates by fingerprint.
     */
    private static final int MAX_TOOL_CALLS = 24;
    /**
     * Build verbs held after a plan is saved, until the user taps
     * Start. Field finding 25 Oct: a weak model saved the plan and
     * charged straight into building — the user never got their
     * review moment. Now the loop physically refuses these tools in
     * the same turn, with E_PLAN_REVIEW telling the model to stop.
     */
    private static final java.util.Set<String> PLAN_GATE_TOOLS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "create_project", "create_activity",
                    "create_fragment", "create_custom_view",
                    "delete_activity", "delete_custom_view",
                    "edit_activity", "edit_screen",
                    "convert_root_layout", "import_layout_xml",
                    "set_event_handler", "edit_event_block",
                    "create_variable", "remove_variable",
                    "create_more_block", "remove_more_block",
                    "create_component", "remove_component",
                    "edit_project_theme", "build_project",
                    "build_release_package", "manage_custom_block",
                    "manage_custom_component_or_event"));
    /** How many segments one user message may run, at most. */
    private static final int MAX_SEGMENTS = 6;

    private static final String AGENT_GUIDANCE =
            // Design first — and it is first in the brief for a reason:
            // told politely further down, it was skipped every time.
            "\n\nDESIGN BEFORE YOU BUILD (the most important rule here). "
                    + "When the user asks for an app, a screen, or any "
                    + "substantial feature, do NOT start creating — the "
                    + "tools will refuse. First: (1) ask how it should "
                    + "look with ask_choice, offering three to five "
                    + "WHOLE directions — more choice is better than "
                    + "less (each one a "
                    + "complete look: colours, type, shapes, mood — not a "
                    + "label like “modern”. EVERY option about colours "
                    + "or style MUST spell out its real hex codes inside "
                    + "the option text, e.g. “Soft blue/purple gradient "
                    + "(#B39DDB → #90CAF9), thin font, frosted glass” — "
                    + "the app draws swatches from those hexes, so an "
                    + "option without hexes shows no colours. Use 2–4 "
                    + "hexes per option (background → accent). Make "
                    + "ask_choice a REAL function call — question and "
                    + "options go in the arguments and the app pops "
                    + "the sheet itself. Do not print the call as text "
                    + "or JSON in your reply, and do not draw the "
                    + "options as an ```openui block, a "
                    + "ChoiceChips/Select component, or a plain list: "
                    + "none of those shows the user a popup. NEVER "
                    + "re-ask a question the user already answered: if "
                    + "a Design System or style brief is saved for this "
                    + "project (it is in the PROJECT INDEX) or the "
                    + "conversation already holds their colour/audience/"
                    + "mood choice, that question is SETTLED — build "
                    + "with what is saved; asking again erases trust, "
                    + "not choices.”; "
                    + "(2) save the agreed look with "
                    + "set_style_brief as headed lines — Audience; Mood; "
                    + "Colours (with hex); Text sizes (title/body/button "
                    + "sp); Buttons and cards (height, corner roundness); "
                    + "Spacing; Do; Avoid; (3) save the customizable "
                    + "OpenUI Design System with set_design_system "
                    + "(title, brand_color, optional secondary_color, "
                    + "neutral_scale: neutral|slate|gray|zinc|stone|warm, "
                    + "corner_profile: sharp|balanced|soft|pill, "
                    + "density: compact|comfortable|spacious, "
                    + "theme_mode: DayNight|Light|Dark, optional "
                    + "font_heading/font_body or light_overrides/"
                    + "dark_overrides — never lock every app to one "
                    + "colour; derive brand_color, surfaces, and shapes "
                    + "from the user's chosen style or screenshot!). "
                    + "set_design_system automatically computes WCAG-paired "
                    + "Light & Dark tokens and materializes values/colors.xml, "
                    + "values-night/colors.xml, values/styles.xml (AppTheme), "
                    + "color/openui_chip_text.xml, and 20 rounded/stroked "
                    + "res/drawable/openui_*.xml drawables — so you do NOT "
                    + "need to hand-write theme colors.xml or initializeLogic "
                    + "set_corner_radius blocks for standard cards, inputs, "
                    + "chips, or buttons; (4) write the implementation plan "
                    + "— the screens by name, what is on each, what data it "
                    + "keeps and where, what each button does, the order you "
                    + "will build it — save it with set_plan, then tell the "
                    + "user the plan in chat and let them change it; (5) only "
                    + "then create the project and build. Build EVERY screen "
                    + "(MainActivity, secondary forms, dialogs, and item_* "
                    + "custom rows alike) using OpenUI screen components in "
                    + "edit_screen's `openui` / `openui_patch` (or "
                    + "token=\"<key>\") and @color/openui_* semantic colours "
                    + "— NEVER hardcode dark #111827 or white #FFFFFF hexes "
                    + "in DayNight apps. If the user asks to change a theme "
                    + "setting or one component, use edit_design_token "
                    + "(which re-syncs @color/openui_* and openui_* drawables "
                    + "automatically) and offer to update screens if layout "
                    + "measurements changed. If the user says “you decide” "
                    + "or does not answer, choose yourself, say which you "
                    + "chose and why in one line, save it, and carry on — "
                    + "never stall repeating a question, and never build "
                    + "an app the user has never seen a description of. "
                    + "A saved style and plan are reused, not re-asked; a "
                    + "small one-off change needs neither."
                    + "\n\nYou are connected to the user's Sketchware Pro app and can "
                    + "look at the project they are working on with your "
                    + "tools. Use the tools when a question is about their "
                    + "project instead of guessing. You can also create "
                    + "projects and screens, and edit screens widget by "
                    + "widget: every change is shown to the "
                    + "user on an approval card first and only runs if "
                    + "they approve it, so propose changes freely but "
                    + "never claim a change happened before the tool "
                    + "confirms it — and if the user rejects one, do not "
                    + "claim the app itself lacks a feature your tools "
                    + "don't have yet: Sketchware is a full app builder "
                    + "and can do far more than your current tools. When "
                    + "asked for something your tools genuinely cannot "
                    + "do yet, say plainly that YOU can't do that yet — "
                    + "never invent manual instructions or walkthrough "
                    + "steps to cover the gap, and offer the closest "
                    + "thing you can do. You HAVE full More Blocks "
                    + "(create_more_block, remove_more_block, "
                    + "call_more_block, return_value) and Add Source "
                    + "Directly (add_source_directly, java_expr, "
                    + "custom_import): prefer Sketchware's real visual "
                    + "blocks whenever they express the logic cleanly, "
                    + "use More Blocks to factor reusable per-screen "
                    + "logic without duplicating chains, and use "
                    + "add_source_directly or java_expr whenever the "
                    + "user asks for it or a feature needs direct "
                    + "Android/Java APIs (remember Sketchware prefixes "
                    + "More Block methods and event/More Block "
                    + "parameters with '_', like _saveNote(...) and "
                    + "_position). Never remove and re-add widgets to "
                    + "change their order or position — use move_widget "
                    + "with before/after; removing a widget destroys "
                    + "its text and settings, and re-adding guesses "
                    + "them. When a widget is not visible because "
                    + "of where it sits in a layout, fix the placement: "
                    + "move it into the screen's main container, or "
                    + "adjust sizes and weights — do not restructure "
                    + "the screen (for example swapping its main "
                    + "container for a ScrollView) unless the user asks "
                    + "for scrolling. When a tool confirms a change "
                    + "succeeded, that change is DONE — never propose "
                    + "the same create or edit again in the same turn. "
                    + "If a create then reports the name is already "
                    + "taken, that is your own earlier success coming "
                    + "back: treat the thing as existing, continue with "
                    + "the rest of the task, and do not ask the user "
                    + "about a duplicate. The user's newest message "
                    + "always wins: "
                    + "if an earlier step failed, stopped, or was "
                    + "rejected, do not bring it back on top of a new "
                    + "instruction — only pick it up again if the user "
                    + "themselves returns to that subject. Do not build "
                    + "after every change: wiring and edits are already "
                    + "checked before they run. Build only when the "
                    + "user asks to build, install or run, or to check "
                    + "a fix for errors a previous build reported — "
                    + "never more than one build per user request "
                    + "unless the build itself reported errors. Do not "
                    + "chain a build onto routine edits unprompted. "
                    + "Always check a project's "
                    + "current screens and widgets with the tools, even if "
                    + "earlier messages mention them — the user may have "
                    + "changed the project since, and remembered details go "
                    + "stale. Nearly everything the "
                    + "tools tell you is invisible to the user — answer in "
                    + "plain words and never show raw tool output, ids, or "
                    + "JSON. If a tool reports that no working project is "
                    + "chosen, relay its guidance to the user. When the user "
                    + "leaves a choice open or says they don't know the "
                    + "options (\"show me\", \"I don't know many…\", \"not "
                    + "sure\"), never silently pick a taste choice for them "
                    + "and never hide it inside an approval card: offer "
                    + "three to five named options in plain words (for "
                    + "colours, friendly names with their hex), say which "
                    + "you'd suggest in one short line, and end your turn "
                    + "so they can pick. Only trivial cosmetics needed to "
                    + "finish a build you've already been asked for "
                    + "(spacing, placeholder text) may be chosen quietly. "
                    + "When the user asks to agree or discuss first (\"let's "
                    + "agree first\", \"no coding yet\", \"what do you "
                    + "think\"), do not call any tool: talk it through and "
                    + "rest the agreement in their own words, exactly as "
                    + "they meant them — a \"shade of the primary\" is a "
                    + "lighter tint of it, not the primary itself, and a "
                    + "later \"yes\" only ever approves the interpretation "
                    + "you stated back, never a different one you quietly "
                    + "substituted. Vague amounts (\"a shade\", \"a bit "
                    + "bigger\", \"a few\") are choices to offer, not "
                    + "values to invent."
            + "\n\nPROJECT SETUP (LIBRARIES, MATERIAL 3, MAVEN & "
            + "PERMISSIONS): call get_project_setup to check which "
            + "libraries, Material 3 settings, local libraries, "
            + "excluded built-in libraries, and Android permissions "
            + "are active, plus automatic prerequisite warnings and "
            + "the last compile log tail. Never send the user to "
            + "manual menus when your tools can configure it directly: "
            + "use configure_library to enable/configure AppCompat, "
            + "Material 3 (with dynamic_colors and DayNight/Light/Dark "
            + "theme), Firebase (accepting google-services.json text "
            + "or database_url + app_id + api_key + storage_bucket), "
            + "AdMob (app_id + banner/interstitial/reward ad units + "
            + "test devices), or Google Maps (api_key) — ask the user "
            + "in chat only for credentials/keys you cannot invent. "
            + "Before or when adding Material/AndroidX widgets "
            + "(CardView, MaterialButton, RecyclerView, TabLayout, "
            + "CollapsingToolbarLayout, TextInputLayout, "
            + "SwipeRefreshLayout, ViewPager, BottomNavigationView), "
            + "ensure AppCompat is switched on via configure_library. "
            + "Use manage_permissions to declare or remove Android "
            + "permissions (or auto_add_recommended=true to satisfy "
            + "missing network/camera/audio/location permissions), "
            + "list_local_libraries and manage_local_libraries to "
            + "enable/disable downloaded local libraries or exclude "
            + "conflicting built-in libraries, and "
            + "download_maven_library (group:artifact:version) to "
            + "download, dex, and enable external Maven/AndroidX "
            + "libraries on the device."
            + "\n\nVISUALS, SCREENSHOT-TO-SCREEN, ICONS, DRAWABLES & "
            + "PREVIEW: the user can attach screenshots, UI mockups, "
            + "wireframes, or logos in chat (shown with [Attached chat "
            + "image N: id=\"att_...\"]). When the user attaches a "
            + "screenshot or mockup to recreate as a screen or app, do "
            + "NOT ask generic style-quiz questions unless they asked "
            + "for variations — the screenshot IS the visual direction: "
            + "inspect its exact background/surface/accent/text hex "
            + "colours, typography scale, spacing, radii, icons, and "
            + "layout hierarchy, then save them directly with "
            + "set_style_brief, set_design_system, and set_plan before "
            + "creating screens. Use search_builtin_icons and "
            + "import_builtin_icon to search and import crisp tinted "
            + "Material icons from Sketchware Pro's built-in 2,000+ "
            + "icon pack (styles: round, outline, baseline, sharp, "
            + "twotone; batch multiple icons in one call). Use "
            + "manage_drawable to create custom Android XML drawables "
            + "(<shape>, <vector>, <ripple>, <selector>, <layer-list>) "
            + "for pill buttons, gradients, custom strokes, or custom "
            + "vector paths, then apply them via set_image or "
            + "set_background_image. Use import_chat_image to add an "
            + "attached chat image (by attachment_id) into Project "
            + "Image Manager for ImageViews, and use set_app_icon to "
            + "generate standard + Android Adaptive Launcher Icons "
            + "across all mipmap densities from an attached image, a "
            + "built-in Material icon, or monogram initials. After "
            + "building or significantly editing a complex screen (and "
            + "always when recreating a screenshot), call preview_screen "
            + "to render it offscreen with Sketchware's real ViewPane, "
            + "check measured dp bounds and any 0dp/clipping warnings, "
            + "visually compare the rendered preview against the "
            + "reference design, and fix any layout defects before "
            + "finishing."
            + "\n\nFRAGMENTS, DIALOGS, DRAWERS & ADVANCED LAYOUTS: "
            + "Create Fragments (kind=\"fragment\"), DialogFragments "
            + "(kind=\"dialog_fragment\"), or BottomSheetDialogFragments "
            + "(kind=\"bottom_sheet_fragment\") with create_fragment. "
            + "Fragments are NOT standalone Activities — never pass them "
            + "to open_screen; host regular Fragments via a "
            + "fragment_adapter component + ViewPager (set_fragment_adapter "
            + "and onFragmentAdded -> return_fragment) or inside a layout "
            + "container with replace_fragment, and open DialogFragments / "
            + "BottomSheets with show_dialog_fragment (close inside them "
            + "with dismiss_dialog_fragment). Enabling drawer: true on an "
            + "Activity with edit_activity creates its _drawer_<activity> "
            + "layout; populate that layout with edit_screen (activity: "
            + "\"_drawer_<activity>\"), wire clicks on the host Activity "
            + "with set_event_handler targeting the drawer widget id (or "
            + "\"_drawer_<id>\"), and open/close the drawer with "
            + "open_drawer / close_drawer. When a screen has floating_button "
            + "on, wire its click with target \"_fab\" and event "
            + "\"onClick\". Use convert_root_layout (or edit_screen's "
            + "set_root) to switch root ViewGroups (LinearLayout, "
            + "ConstraintLayout, CoordinatorLayout, FrameLayout, "
            + "RelativeLayout, NestedScrollView, etc.), import_layout_xml "
            + "when importing a full Android XML layout directly, and "
            + "remove_component / delete_custom_view when cleaning up "
            + "unused components or row layouts."
            + "\n\nRESOURCES, LOCALIZATION, DARK THEME, MENUS, ANIMATIONS, "
            + "FONTS & SOUNDS: Use get_values_resources and "
            + "edit_values_resource to manage strings.xml, colors.xml, "
            + "styles.xml, themes.xml, and arrays.xml across values and any "
            + "values-<variant> (pass variant=\"night\" for dark-theme "
            + "colors/styles, or variant=\"es\", \"fr\", \"ar\", etc. for "
            + "translations). Widgets accept \"@string/<name>\" in text/hint "
            + "and \"@color/<name>\" or \"?attr/<name>\" in background and "
            + "text_color; logic blocks accept {\"res_string\":\"<name>\"} "
            + "and color \"@color/<name>\" or \"?attr/<name>\". In ANY "
            + "number slot that means an Android resource id — the menu "
            + "item check in onOptionsItemSelected "
            + "({\"op\":\"=\",\"a\":{\"arg\":\"id\"},\"b\":{\"res_id\":"
            + "\"menu_settings\"}}), onNavigationItemSelected or "
            + "bottom-navigation ids — use {\"res_id\":\"<name>\"} "
            + "(\"@id/<name>\" also works), which compiles to the real "
            + "R.id.<name> that aapt2 assigns; NEVER guess the integer "
            + "value of an R.id constant. Use "
            + "write_resource_xml / read_resource_xml / delete_resource_xml "
            + "for raw res/<folder>/<file>.xml resources: e.g. "
            + "menu/main_menu.xml (wire onCreateOptionsMenu -> menu_inflate "
            + "and onOptionsItemSelected), anim/fade_in.xml (play on any "
            + "widget with start_xml_animation), animator/*.xml, "
            + "xml/file_paths.xml, or color/*.xml state lists. Use "
            + "list_font_and_sound_sources, import_project_font (bundled "
            + "gilroy / jetbrains_mono / droid_sans_mono, collection, or "
            + ".ttf/.otf file -> apply via set_font or set_typeface), and "
            + "import_project_sound (synthesized presets click, pop, "
            + "success, complete, error, notification, alarm, custom "
            + "frequency_hz tones, collection, or audio file -> play via "
            + "mediaplayer mp_create/mp_start or soundpool load_sound)."
            + "\n\nJAVA, KOTLIN, ASSETS & NATIVE LIBRARIES: Use "
            + "read_source_files, write_source_file, and delete_source_file "
            + "to manage real .java and .kt classes in files/java/<path> "
            + "(custom RecyclerView adapters, models, helpers, Services, "
            + "BroadcastReceivers, or custom Activities). Kotlin stdlib is "
            + "added automatically when .kt files exist; pass "
            + "register_in_manifest=\"activity\", \"service\", or "
            + "\"receiver\" so AndroidManifest.xml declares the component, "
            + "then trigger it from blocks via open_screen, "
            + "set_intent_screen + send_broadcast, or start_service / "
            + "stop_service. Use read_assets, write_asset, and delete_asset "
            + "for packaged files in assets/<path> (JSON, HTML/CSS/JS for "
            + "WebView file:///android_asset/<path>, CSV, shaders, etc., "
            + "read in blocks via open_asset / asset_text), and "
            + "inspect_native_libs / import_native_lib / delete_native_lib "
            + "for JNI .so binaries across arm64-v8a, armeabi-v7a, x86_64, "
            + "and x86."
            + "\n\nPRO EVENTS, CUSTOM BLOCKS/COMPONENTS & COMMAND BLOCKS: "
            + "Every Sketchware Pro Activity, Widget, Menu, and Component "
            + "callback (including onLongClick, onFilesPicked with listStr "
            + "filePath, onActivityResult with requestCode/resultCode/data, "
            + "onOptionsItemSelected with id/title, onCreateOptionsMenu, "
            + "onTabLayoutNewTabAdded, RecyclerView/ViewPager/TabLayout/"
            + "BottomNavigationView/SearchView/Firebase/RequestNetwork/"
            + "Bluetooth/Location/AdMob events) is wired with full event "
            + "argument scoping. Pass extra_data to create_component for "
            + "SharedPreferences file names, Firebase DB/Storage paths, or "
            + "FilePicker MIME types. Use list_custom_definitions, "
            + "manage_custom_block, manage_custom_component_or_event, and "
            + "manage_xml_commands (plus {\"block\":\"java_command_block\"} "
            + "or {\"block\":\"<customBlockName>\",\"args\":[...]}) for "
            + "reusable Custom Blocks, Custom Components/Events, and "
            + "compile-time Java/XML Command patches."
            + "\n\nMANIFEST, APPCOMPAT INJECTION, PROJECT SETTINGS & "
            + "SHRINKING: Use inspect_manifest_and_settings, "
            + "manage_manifest (launcher_activity override e.g. "
            + "SplashActivity, <application> and <activity> attributes, "
            + "<intent-filter> deep links/share targets, and <provider>/"
            + "<meta-data> XML), manage_appcompat_injection (toolbar, "
            + "appbarlayout, coordinatorlayout, drawerlayout, "
            + "navigationdrawer XML attributes), edit_project_settings "
            + "(project_name, app_name, package_name, version_code, "
            + "version_name, min_sdk, target_sdk, application_class, "
            + "enable_viewbinding, enable_bridgeless_themes), and "
            + "configure_proguard_and_stringfog (ProGuard/R8 rules, "
            + "full-mode local libs, and StringFog)."
            + "\n\nSOURCE INSPECTOR, RUNTIME LOGCAT, COLLECTIONS & RELEASE "
            + "PACKAGING: Use inspect_generated_source to read numbered "
            + "lines of generated MainActivity.java, layout XMLs, or "
            + "AndroidManifest.xml when pinpointing compiler errors; "
            + "read_app_logs to inspect live runtime logs, AndroidRuntime "
            + "crash stack traces, and the last compile error log; "
            + "list_collections and manage_collections to save/import "
            + "reusable Widget, MoreBlock, and Block Collections; "
            + "build_release_package for Play Store .aab App Bundles or "
            + "signed release .apk files; and export_project_backup for "
            + ".swb project archives."
            + "\n\nOPENUI LANG 1.0 (INTERACTIVE CHAT UI & DESIGN-SYSTEM "
            + "SCREEN COMPILER): In assistant replies (`inlineMode`), keep "
            + "normal explanations in Markdown and use a fenced ```openui-lang "
            + "block (`id = Component(...)` one assignment per line, entry "
            + "`root = Stack([...])`) whenever presenting visual style "
            + "directions, colour palettes, multi-field setup choices, or "
            + "structured summaries/tables. Chat components: "
            + "Stack(children, gap?), Row(children, gap?), "
            + "Card(children, title?, subtitle?, accentColor?), "
            + "Header(title, subtitle?, badge?), Text(content, variant?), "
            + "Badge(label, color?), Divider(), StyleCard(name, tagline, "
            + "colors, mood?, action?), PaletteRow(swatches), "
            + "ColorSwatch(label, hex), TypePreview(sample, sizeSp?, "
            + "weight?, color?), TextInput(label, $var, placeholder?), "
            + "Select(label, $var, options), ChoiceChips(label, $var, "
            + "options, multi?) — these two are for the app's screens "
            + "only, never inside a chat reply, Switch(label, $var, "
            + "subtitle?), "
            + "Slider(label, $var, min?, max?, step?), KpiRow(items) / "
            + "Kpi(label, value, delta?), Table(headers, rows) / "
            + "Col(header, values), ProgressBar(label, value, max?, "
            + "color?), BreakdownBar(segments), and Button(label, action, "
            + "variant?) with `$var = default` reactive state and actions "
            + "@ToAssistant(\"msg\", context?), @Set($var, val), "
            + "@Reset($var), @OpenUrl(\"url\"). Never fake approval or "
            + "build cards in OpenUI — real project edits always call "
            + "tools. When reading or editing Sketchware screens, prefer "
            + "get_screen(activity, format=\"openui\") and edit_screen with "
            + "`openui` or `openui_patch` (`id = null` deletes; untouched "
            + "widgets & wired events stay intact). Use OpenUI's high-level "
            + "screen & TextView-role components so every screen inherits "
            + "paired Light & Dark @color/openui_* and openui_* drawables: "
            + "Screen([...]), Stack([...], {direction, gap: \"s\"|\"m\"|\"l\", p}), "
            + "Card([...], {variant: \"card\"|\"sunk\"}), "
            + "Form([fields], buttons) (auto-wrapped in ScrollView!), "
            + "FormControl(\"Label\", inputWidget, \"helper?\"), "
            + "Input(\"Hint\", {type}), TextArea(\"Hint\", {rows}), "
            + "Select(\"Hint\"), DatePicker(\"Pick date\"), "
            + "Chips([\"A\",\"B\"], {ids: [\"chip_a\",\"chip_b\"], selectedIndex: 0}), "
            + "ColorSwatches([\"#10B981\",\"#3B82F6\"], {ids: [\"sw_0\",\"sw_1\"], selectedIndex: 0}), "
            + "SwitchItem(\"Title\", \"Subtitle?\") (keeps exact id on Switch!), "
            + "Slider(\"Label\", {max: 100}) (keeps exact id on SeekBar!), "
            + "SearchBar(\"Search...\") (keeps exact id on EditText!), "
            + "Button(\"Save\", {variant: \"primary\"|\"secondary\"|\"tertiary\"|\"destructive\"}), "
            + "Buttons([btn1, btn2]), Heading(\"Title\"), Body(\"Text\"), "
            + "Caption(\"Muted\"), Label(\"Field label\"), "
            + "TextContent(\"Copy\", {size: \"small\"|\"default\"|\"large\"|\"large-heavy\"}), "
            + "Text(\"Value\", \"+12%\", {variant: \"number\", subtextVariant: \"metric\"}), "
            + "InlineHeader(\"Title\", \"Sub\"), CardHeader(\"Title\", \"Sub\"), "
            + "Callout(\"info\"|\"success\"|\"warning\"|\"danger\", \"Title\", \"Body\"), "
            + "Tag(\"Active\", {variant: \"success\"}), "
            + "EmptyState(\"No items yet\", \"Tap + to add one\", {icon}), "
            + "ListItem(\"Title\", \"Subtitle\", {trailing}), "
            + "Stat(\"Streak\", \"14d\", \"+2d\"), Separator(), "
            + "RecyclerList(\"item_row\"), SwipeRefresh([list]), BottomNav({menu})."
            + "\n\nKEEPING DATA (so it is still there after the app "
            + "closes): add ONE shared_preferences component to the "
            + "screen with create_component — it is named file1, file2 "
            + "and so on, and you must use that exact name. Then: "
            + "save with save_pref {component:\"file1\", text:\"key\", "
            + "text2:\"a literal\"} or text2:{\"var\":\"notesText\"} to "
            + "store what a variable holds; read back inside an "
            + "expression with {\"pref_get\":\"file1\",\"key\":\"key\"} "
            + "(usually into a variable with set_variable, on "
            + "initializeLogic so it happens when the screen opens); "
            + "test for it with {\"pref_has\":\"file1\",\"key\":\"key\"}. "
            + "Preferences hold text only — to keep a list, save it as "
            + "one text and split it back with split_into_list {list, "
            + "text, text2: separator}. A text slot accepts a quoted "
            + "text or {\"var\":\"name\"}, never a number variable "
            + "directly: convert with {\"fn\":\"to_text\","
            + "\"a\":{\"var\":\"n\"}}. If what the user asked for "
            + "cannot be expressed with the blocks you have, say so "
            + "plainly — never fake it with a toast and a finish."
            + "\n\nMEMORY: the app keeps a task log per project — what was "
            + "asked, the steps that landed, any style brief agreed — and "
            + "you are briefed with it at the start of a turn. When a task "
            + "is unfinished, continue it from the first step that is not "
            + "done; never redo finished steps and never propose a change "
            + "the user already turned down under a different name. The "
            + "log records what the app applied, not what you remember "
            + "saying, so trust your tool reads of the project over both. "
            + "Before proposing any change, check the real project "
            + "state with a tool: colours already set, a screen or "
            + "variable or widget that already exists, are already "
            + "done — the app refuses them and tells you so, and the "
            + "user never sees a card for them. Variables and widgets "
            + "belong to ONE screen: a variable or widget created on "
            + "one activity does not exist on another, so create it on "
            + "the screen whose logic uses it. After a stop, a retry "
            + "or a model change, you resume the SAME task and the "
            + "SAME plan — never restate the plan, never re-propose "
            + "finished steps. The memory lives in the app, not in "
            + "you: a different model picks up exactly where you left "
            + "off by reading the same brief. "
            + "When the user agrees a style direction for the project "
            + "('for kids', 'dark and minimal', 'use our brand colour'), "
            + "save it with set_style_brief so it survives the chat, and "
            + "keep to it afterwards. The style and the Design system are "
            + "ONE memory now: set_style_brief's first line shows as the "
            + "Design system's Mood & Audience summary, and there is no "
            + "separate style page. If the user wants a previous look "
            + "back, use undo_design_system (\"undo\" for one step back, "
            + "\"restore_original\" for the project's first-ever theme)."
            + "\n\nORDER OF WORK for a new app or substantial build: style "
            + "first, plan second, building third. If the project has no "
            + "style brief, ask what look and feel the user wants; if "
            + "they don't know, propose one yourself in plain words, "
            + "save it when they accept (or tell you to decide), then "
            + "agree the implementation plan and save it with set_plan "
            + "BEFORE building. VAGUE PROMPTS (\"build me a notepad\", "
            + "\"build me a weather forecasting app\") are still whole "
            + "products: brainstorm 2–4 named directions with ask_choice "
            + "(with real colours and shapes), turn the pick into a "
            + "complete set_design_system + plan covering every screen, "
            + "and build the whole thing — never ask the user to design "
            + "it for you. When an app needs live data and the user gave "
            + "no API key, use a FREE KEYLESS public API (weather: "
            + "Open-Meteo https://api.open-meteo.com/v1/forecast and its "
            + "geocoding API; no key, CORS-free, plain JSON via "
            + "RequestNetwork), declare INTERNET permission, and cache "
            + "the last JSON in a file/SharedPreferences so the app "
            + "still opens offline. Save both even when no project is "
            + "chosen yet — they are held as drafts and land in the "
            + "project this work creates. If a style and plan already "
            + "exist, use them and don't re-ask. As each plan milestone "
            + "genuinely lands and you have verified it against the "
            + "real project, call set_plan with done_steps set to how "
            + "many leading milestones are now done (you do NOT need "
            + "to resend \"steps\" when updating progress). If the "
            + "user changes direction mid-plan, update "
            + "the plan rather than quietly abandoning it. Small "
            + "one-off requests need neither a style nor a plan. A "
            + "saved style guides new work; it never silently restyles "
            + "what is already built — redesigning existing screens is "
            + "separate work to agree and plan."
            + "\n\nLAYOUT SENSE: match_parent suits text inputs, lists, "
            + "scroll areas and the main content column; wrap_content suits "
            + "labels, icons, chips and ordinary buttons — a primary action "
            + "button at the bottom of a form may span the form's width "
            + "instead. Keep spacing and text sizes consistent across a "
            + "screen (margins in steps of 4, body text around 14-18sp, "
            + "headings clearly bigger), give touch targets room, and "
            + "never stretch a small widget across a screen just to fill "
            + "it. For kids' apps: big readable text (18sp+), large touch "
            + "targets, bright friendly colours, simple rounded layouts."
            + "\n\nWEIGHT: inside a LinearLayout, decide for every child: "
            + "hug its content (wrap_content), fill the parent "
            + "(match_parent), or share the leftover space (weight). A "
            + "weighted child uses 0dp on the layout's axis (width in a "
            + "horizontal layout, height in a vertical one). Patterns: "
            + "text column between an icon and an arrow gets weight 1; "
            + "the main content between a header and a bottom action "
            + "gets weight 1; equal action buttons share equal weights; "
            + "an input beside a send button takes weight 1 while the "
            + "button wraps. match_parent on one child of a row leaves "
            + "nothing for its siblings — that is what weight is for. "
            + "Weight takes space; gravity only aligns inside space "
            + "already given. Before adding widgets, check each planned "
            + "child against these choices, its padding (space inside) "
            + "and margins (space outside, consistent across the "
            + "screen), and the saved style's sizes. A tappable row or "
            + "menu entry with an icon, title and subtitle is often "
            + "better composed as a whole clickable layout/card than a "
            + "plain Button; a single-word action stays a Button. "
            + "Match the sizing pattern of widgets already on the "
            + "screen before inventing a new one.";

    public interface Listener {
        /** A quiet activity line, e.g. "Looked at MainActivity". */
        void onToolNote(String line);

        /** The turn finished: answer text, plus any model reasoning. */
        void onAnswer(String text, String thinking);

        /** The turn failed (or tool calling is unsupported). */
        void onError(AiChatService.AiError error);

        /**
         * A change needs the user's decision. Called on the loop thread
         * and expected to block until the user decides; false rejects.
         */
        boolean onApproval(AgentProposal proposal);

        /**
         * The agent asks the user to pick a colour in Sketchware's own
         * picker. Called on the loop thread and expected to block until
         * the user picks or closes it. Returns {"picked": "#AARRGGBB",
         * optionally "name": "..."}, or null when no colour was picked.
         */
        JsonObject onPickColor(String question, String initialHex);

        /**
         * The agent asks the user to choose between named options.
         * Called on the loop thread and expected to block until the
         * user picks or dismisses it. Returns the option they chose,
         * or null when they dismissed it.
         */
        String onAskChoice(String question, java.util.List<String> options);

        /** The decision was acted on; the card can show the outcome. */
        void onApprovalResolved(AgentProposal proposal, JsonObject result);

        /** A created project just became the working project. */
        void onWorkingProjectChanged(String scId);

        /** Task or plan progress changed; UI strip/badges should refresh. */
        default void onStateChanged() {
        }

        /**
         * A lasting note in the chat itself — e.g. a mid-turn model
         * switch — not a transient activity line.
         */
        default void onSystemNote(String line) {
        }

        /**
         * The router resolved the model actually doing this turn (✨
         * Auto): the chat-bar header should show it next to "Auto".
         */
        default void onResolvedModel(String modelId) {
        }
    }

    /** True from the moment a plan is created until the turn ends:
     * build tools are refused so the user can review the plan. */
    private boolean planGateArmed;

    /** The tool result that holds the model while the user reviews
     * the plan it just saved. */
    private static JsonObject planReviewBlocked() {
        JsonObject blocked = new JsonObject();
        blocked.addProperty("error", "E_PLAN_REVIEW");
        blocked.addProperty("message",
                "The plan was just saved and the user is reviewing it "
                        + "now. Do not build yet — end your turn this "
                        + "instant. Building starts when the user taps "
                        + "Start (their next message).");
        return blocked;
    }

    /** A plan CREATION (title + steps), not a progress update. The
     * arguments travel as raw text; only a creation carries a real
     * \"steps\" field — progress updates carry done_steps (which does
     * NOT contain the quoted \"steps\" token, the underscore guards it). */
    /** True when the project (or the pre-project draft) ALREADY has
     * an agreed plan — a set_plan call then updates it and must NOT
     * re-arm the review gate. */
    private static boolean planAlreadyKnown(AgentTools.Session session) {
        String scId = session == null ? ""
                : session.workingProjectId;
        if (scId != null && !scId.isEmpty()
                && AgentJournal.hasPlan(scId)) {
            return true;
        }
        return AgentJournal.draftPlan().has("steps");
    }

    private final Context context;
    private final AiChatService service;
    private final Executor io;
    private volatile boolean cancelled;

    public AgentLoop(Context context, AiChatService service, Executor io) {
        this.context = context.getApplicationContext();
        this.service = service;
        this.io = io;
    }

    /** Stops the in-flight turn. */
    public void cancel() {
        cancelled = true;
        service.cancel();
    }

    /**
     * The ✨ Auto router's pool: the active provider's fetched models
     * (set by the chat before each turn; empty means "no auto").
     */
    public volatile List<String> autoModelPool = new ArrayList<>();
    /** Whether the pool should include non-chat models. */
    public volatile boolean autoShowAll = false;

    /**
     * The last Auto-chosen model that produced a real answer. Routing
     * tries it FIRST on later turns — field finding: with no memory,
     * Auto re-rolled the whole catalog every turn (135 failovers in
     * one session, three different models redoing the same design).
     */
    private volatile String stickyAutoModel = "";

    /** Minimum gap between agent requests, so a build turn cannot
     * machine-gun a free key: 4s = max ~15 requests/minute, the
     * free tier of the strictest mainstream provider. Field finding:
     * ONE build message fired 190+ requests back-to-back and burned
     * the key in under a minute. */
    private static final long MIN_REQUEST_GAP_MS = 4000L;

    /** One tool result larger than this is truncated — it would
     * otherwise ride along in every later request of the turn. */
    private static final int TOOL_RESULT_CAP = 12000;
    /** The newest N tool results stay full; older ones are stubbed. */
    private static final int MAX_FULL_TOOL_RESULTS = 24;

    /** Bounds the turn's payload: the snowball of full tool results
     * (each request carried ALL previous ones) is what made requests
     * grow to hundreds of thousands of tokens by call #150. */
    private static void compactToolHistory(
            List<JsonObject> messages) {
        int seen = 0;
        for (JsonObject m : messages) {
            if (m.has("role") && "tool".equals(
                    m.get("role").getAsString())) {
                seen++;
            }
        }
        int toStub = seen - MAX_FULL_TOOL_RESULTS;
        if (toStub <= 0) {
            return;
        }
        for (JsonObject m : messages) {
            if (toStub <= 0) {
                return;
            }
            if (m.has("role") && "tool".equals(
                    m.get("role").getAsString())) {
                String content = m.has("content")
                        && m.get("content").isJsonPrimitive()
                        ? m.get("content").getAsString() : "";
                if (!content.startsWith("[earlier tool result")) {
                    m.addProperty("content",
                            "[earlier tool result omitted to keep the "
                                    + "request small — re-read with the "
                                    + "tool if needed. Was: "
                                    + AgentJournal.cap(content, 160));
                    toStub--;
                }
            }
        }
    }
    private long lastAgentRequestAt;
    /** Consecutive Auto turns that died without a real answer. */
    private int autoDeadTurns;

    /**
     * Runs one user turn. {@code history} is the visible conversation
     * (user/assistant text); the loop takes it from there.
     */
    public void run(final String baseUrl, final String apiKey, final String model,
                    final List<ChatMessage> history, String workingProjectId,
                    final boolean allowModify, final Listener listener) {
        cancelled = false;
        io.execute(() -> runTurn(baseUrl, apiKey, model, history,
                workingProjectId, allowModify, listener));
    }

    private void runTurn(String baseUrl, String apiKey, String model,
                         List<ChatMessage> history, String workingProjectId,
                         boolean allowModify, Listener listener) {
        // The app's own record of this project: the task is titled
        // from the user's message and closed wherever the turn ends,
        // so an interrupted task is paused — never silently lost.
        planGateArmed = false;
        String lastUserText = "";
        ChatMessage lastUserMessage = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role == ChatMessage.Role.USER) {
                lastUserMessage = history.get(i);
                lastUserText = lastUserMessage.text == null
                        ? "" : lastUserMessage.text.trim();
                if (lastUserText.isEmpty()
                        && lastUserMessage.hasAttachments()) {
                    lastUserText = "Attached image ("
                            + lastUserMessage.attachments.size() + ")";
                }
                break;
            }
        }
        AgentJournal.beginTurn(workingProjectId, lastUserText);
        // The safety layer: anything this turn might change is
        // snapshotted first, so the user can see the diff and undo it.
        // Best-effort — a failed snapshot never blocks a turn.
        if (workingProjectId != null && !workingProjectId.isEmpty()
                && allowModify) {
            AgentSnapshots.snapshot(workingProjectId);
        }
        dispatch(listener, listener::onStateChanged);
        {
            // What the turn started from: the user's own words and the
            // project it was aimed at. The export has to be readable
            // from the first line, with nothing assumed.
            JsonObject asked = new JsonObject();
            asked.addProperty("text", lastUserText);
            asked.addProperty("project", workingProjectId);
            asked.addProperty("model", model);
            asked.addProperty("provider_base_url", baseUrl == null
                    ? "" : baseUrl);
            asked.addProperty("history_messages", history.size());
            asked.addProperty("allow_modify", allowModify);
            int attachmentCount = lastUserMessage == null
                    ? 0 : lastUserMessage.attachments.size();
            asked.addProperty("attachment_count", attachmentCount);
            if (attachmentCount > 0) {
                JsonArray attSummary = new JsonArray();
                for (ChatMessage.Attachment att
                        : lastUserMessage.attachments) {
                    JsonObject one = new JsonObject();
                    one.addProperty("id", att.id);
                    one.addProperty("mime", att.mimeType);
                    one.addProperty("width", att.width);
                    one.addProperty("height", att.height);
                    one.addProperty("bytes", att.bytes);
                    attSummary.add(one);
                }
                asked.add("attachments", attSummary);
            }
            AgentJournal.record(workingProjectId, "user_message", asked);
        }
        final long turnStartedAt = System.currentTimeMillis();
        // Pre-project turns keep their history too: routing decisions,
        // errors and the task log land in the draft journal and ride
        // into the project when it is created.
        final String[] journalProject = {
                workingProjectId == null || workingProjectId.isEmpty()
                        ? AgentJournal.DRAFT_KEY : workingProjectId};
        final boolean[] taskClosed = {false};
        final boolean[] previewVisionDisabled = {
                AiChatService.isModelKnownTextOnly(model)};
        // Read cache: read-only facts a previous FAILED turn fetched,
        // handed to this turn so a retry does not pay for them twice.
        // Cleared the moment anything mutates or the turn succeeds.
        final StringBuilder readFacts = new StringBuilder();
        {
            String cached = AgentJournal.readCache(workingProjectId);
            if (!cached.isEmpty()) {
                readFacts.append(cached);
            }
        }
        List<JsonObject> messages = new ArrayList<>();
        JsonObject systemMsg = systemMessage(workingProjectId);
        if (readFacts.length() > 0) {
            // Facts still true when the last turn died, re-briefed so
            // the retry starts where it stopped reading, not from zero.
            systemMsg.addProperty("content",
                    systemMsg.get("content").getAsString()
                            + "\n\nFACTS READ BEFORE THE LAST TURN FAILED "
                            + "(still true; do not re-read these unless "
                            + "you must verify a change):\n" + readFacts);
        }
        messages.add(systemMsg);
        int kept = 0;
        int recency = -1;
        int imageTurnsRemaining = previewVisionDisabled[0] ? 0 : 3;
        // Prior conversation: the last 8 messages maximum, and older
        // assistant replies trimmed to a paragraph. Field finding:
        // 40 full messages rode into EVERY request — on a 100-message
        // session that alone dwarfed the actual build context.
        for (int i = history.size() - 1; i >= 0 && kept < 8; i--) {
            ChatMessage message = history.get(i);
            if (message.state == ChatMessage.State.STREAMING
                    || message.role == ChatMessage.Role.SYSTEM
                    || message.role == ChatMessage.Role.APPROVAL) {
                continue;
            }
            boolean hasText = message.text != null
                    && !message.text.trim().isEmpty();
            boolean hasImages = message.role == ChatMessage.Role.USER
                    && message.hasAttachments();
            if (!hasText && !hasImages) {
                continue;
            }
            recency++;
            if (recency >= 2 && message.role == ChatMessage.Role.ASSISTANT
                    && message.text != null
                    && message.text.length() > 700) {
                String trimmed = message.text.substring(0, 700) + "…";
                message = new ChatMessage(message.role, trimmed);
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("role",
                    message.role == ChatMessage.Role.USER ? "user" : "assistant");
            if (hasImages) {
                boolean inlineBytes = imageTurnsRemaining > 0;
                if (inlineBytes) {
                    imageTurnsRemaining--;
                }
                entry.add("content",
                        AiChatService.buildUserContent(message, inlineBytes));
            } else {
                entry.addProperty("content", formatHistoryContent(message));
            }
            messages.add(1, entry);
            kept++;
        }

        int calls = 0;
        String lastFailedCall = "";
        int sameFailure = 0;
        final int[] segment = {1};
        final boolean[] rollover = {false};
        // ✨ Auto: when the user's model is "auto", the router picks
        // the concrete model for this turn (vision-capable when the
        // turn carries images, otherwise the fastest Tools ✓ model of
        // the active provider), and failures fail over mid-turn.
        final boolean autoRequested = "auto".equalsIgnoreCase(model);
        final String autoProvider =
                ModelCatalog.providerNameForBaseUrl(baseUrl);
        if (autoRequested) {
            boolean needsVision =
                    AiChatService.hasMultimodalImageParts(messages);
            String chosen = "";
            if (!stickyAutoModel.isEmpty()
                    && autoModelPool.contains(stickyAutoModel)) {
                // It delivered last time: stay with it instead of
                // re-rolling the catalog (switching only on failure).
                chosen = stickyAutoModel;
            }
            if (chosen.isEmpty()) {
                chosen = ModelCatalog.pickAutoModel(autoProvider,
                        autoModelPool, autoShowAll, needsVision, "");
            }
            if (autoDeadTurns >= 3) {
                // Say it out loud instead of serving another silent
                // dead turn: the user re-typed "Hello" six times.
                dispatch(listener, () -> listener.onSystemNote(
                        "Auto's models have failed the last "
                                + autoDeadTurns + " turns in a row. "
                                + "Pick a specific model in settings "
                                + "(for example nemotron-3-ultra or "
                                + "glm-5-3) or try again later."));
                autoDeadTurns = 0;
            }
            if (!chosen.isEmpty()) {
                JsonObject routed = new JsonObject();
                routed.addProperty("reason", chosen
                        .equals(stickyAutoModel)
                        ? "sticky — it worked last turn"
                        : needsVision ? "vision needed"
                        : "strongest healthy model");
                routed.addProperty("chosen", chosen);
                AgentJournal.record(journalProject[0], "auto_route", routed);
                final String picked = chosen;
                String note = "✨ Auto → "
                        + ModelCatalog.displayName(picked)
                        + (needsVision ? " (vision)" : "");
                dispatch(listener, () -> listener.onToolNote(note));
                dispatch(listener, () -> listener
                        .onResolvedModel(picked));
                model = picked;
            } else {
                // Nothing to route to: say so instead of sending the
                // literal "auto" to a provider and dying confusingly.
                JsonObject failed = new JsonObject();
                failed.addProperty("model", "auto");
                failed.addProperty("error_kind", "EMPTY");
                failed.addProperty("http_code", 0);
                failed.addProperty("message",
                        "✨ Auto has no models to choose from for this "
                                + "provider yet");
                AgentJournal.record(journalProject[0], "model_error",
                        failed);
                closeTask(journalProject[0], false,
                        "✨ Auto had no model list to choose from",
                        taskClosed, listener);
                dispatch(listener, () -> listener.onError(
                        new AiChatService.AiError(
                                AiChatService.ErrorKind.EMPTY, 0,
                                "✨ Auto needs a model list: open Settings "
                                        + "→ Fetch models once, then pick "
                                        + "✨ Auto again.")));
                return;
            }
        }
        // Fingerprints refused as "already done" in this turn: a
        // second request for the same change is the user asking for
        // it on purpose, so it is let through.
        final java.util.Set<String> insisted = new java.util.HashSet<>();
        // True once this turn has already switched models mid-flight:
        // a 400 from the new model then means "rejected the handover
        // payload", which is switchable, not a dead end.
        boolean failedOver = false;
        // Paid-model learning and the switch budget: a free key meets
        // 402 on every paid model, so after three of those the router
        // goes free-only, and no turn ever walks more than six models.
        final int[] paidCount = {0};
        final int[] switchCount = {0};
        final boolean[] paidConfirmed = {false};
        final boolean[] paidNoteSent = {false};
        while (!cancelled) {
            AiChatService.AgentReply reply = null;
            long requestedAt = System.currentTimeMillis();
            for (int attempt = 0; ; attempt++) {
                long sinceLast =
                        System.currentTimeMillis() - lastAgentRequestAt;
                if (lastAgentRequestAt > 0 && sinceLast >= 0
                        && sinceLast < MIN_REQUEST_GAP_MS
                        && !cancelled) {
                    try {
                        Thread.sleep(MIN_REQUEST_GAP_MS - sinceLast);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                lastAgentRequestAt = System.currentTimeMillis();
                requestedAt = lastAgentRequestAt;
                try {
                    reply = service.agentTurnSync(baseUrl, apiKey, model,
                            messages, AgentTools.definitions());
                    break;
                } catch (AiChatService.AgentRequestException e) {
                    int code = e.error.httpCode;
                    boolean likelyVisionRejection =
                            AiChatService.isVisionUnsupported(e.error)
                            || ((code == 400 || code == 404 || code == 415
                                    || code == 422 || code == 500)
                                    && AiChatService.hasMultimodalImageParts(messages));
                    if (!cancelled && likelyVisionRejection
                            && AiChatService.downgradeMultimodalMessagesToText(
                                    messages, model)) {
                        previewVisionDisabled[0] = true;
                        AiChatService.markModelVisionUnsupported(model);
                        JsonObject fb = new JsonObject();
                        fb.addProperty("model", model);
                        fb.addProperty("http_code", code);
                        fb.addProperty("reason",
                                AgentJournal.cap(
                                        String.valueOf(e.error.message), 300));
                        AgentJournal.record(journalProject[0],
                                "vision_fallback_to_text", fb);
                        dispatch(listener, () -> listener.onToolNote(
                                "Model rejected image pixels — continuing with saved attachment metadata"));
                        continue;
                    }
                    boolean transientFailure = !cancelled && attempt < 2
                            && (e.error.kind
                                            == AiChatService.ErrorKind.NETWORK
                                    || code == 500 || code == 502
                                    || code == 503 || code == 504);
                    if (transientFailure) {
                        JsonObject retried = new JsonObject();
                        retried.addProperty("model", model);
                        retried.addProperty("attempt", attempt + 1);
                        retried.addProperty("error_kind",
                                String.valueOf(e.error.kind));
                        retried.addProperty("http_code", code);
                        retried.addProperty("message",
                                AgentJournal.cap(
                                        String.valueOf(e.error.message),
                                        600));
                        retried.addProperty("took_ms",
                                System.currentTimeMillis() - requestedAt);
                        AgentJournal.record(journalProject[0],
                                "model_retry", retried);
                        long waitMs = attempt == 0 ? 1500L : 3000L;
                        long deadline = System.currentTimeMillis()
                                + waitMs;
                        while (!cancelled
                                && System.currentTimeMillis() < deadline) {
                            try {
                                Thread.sleep(150L);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        if (!cancelled) {
                            continue;
                        }
                    }
                    // Cross-provider failover: the provider is clearly
                    // struggling (5xx/429/network after retries), so
                    // rest this model and hand the whole turn — the
                    // messages list with its completed tool calls —
                    // to the next healthy model. A quota hit (429)
                    // fails over on the first hit; a dead provider
                    // gets its two quick retries first. A 400 that
                    // arrives right after a handover means the new
                    // model rejected the carried-over payload, so it
                    // counts as switchable too (the sanitizer has
                    // already normalized the history). A manually
                    // picked model is NEVER abandoned silently: the
                    // user chose it, so the error surfaces and a hint
                    // names what ✨ Auto would have tried instead.
                    if (code == 429
                            || e.error.kind
                                    == AiChatService.ErrorKind.QUOTA) {
                        // Quota is per KEY, not per model: walking the
                        // catalog against one key torches every model
                        // on it and extends the provider's penalty
                        // windows. ONE strike, a LONG rest (15 min —
                        // the doubling still grows it if it repeats),
                        // and the turn ends honestly right here.
                        ModelCatalog.cooldown(autoProvider, model,
                                System.currentTimeMillis()
                                        + 15L * 60_000L);
                        JsonObject quotaHit = new JsonObject();
                        quotaHit.addProperty("model", model);
                        quotaHit.addProperty("http_code", code);
                        quotaHit.addProperty("rest_minutes", 15);
                        AgentJournal.record(journalProject[0],
                                "model_error", quotaHit);
                        closeTask(journalProject[0], false,
                                "the key hit its quota on " + model,
                                taskClosed, listener);
                        dispatch(listener, () -> listener.onError(
                                new AiChatService.AiError(
                                        AiChatService.ErrorKind.QUOTA,
                                        code,
                                        "Quota/limit hit on "
                                                + ModelCatalog.displayName(
                                                model) + ". It rests 15 "
                                                + "minutes — wait a bit, "
                                                + "or pick another model "
                                                + "in settings.")));
                        return;
                    }
                    boolean handoverRejected = code == 400 && failedOver;
                    boolean modelGone = code == 404
                            || e.error.kind
                                    == AiChatService.ErrorKind.MODEL_GONE;
                    boolean failoverEligible = !cancelled
                            && (code == 429
                                    || e.error.kind
                                            == AiChatService.ErrorKind.QUOTA
                                    || handoverRejected
                                    || modelGone
                                    || attempt >= 1)
                            && (code == 429 || code == 500 || code == 502
                                    || code == 503 || code == 504
                                    || code == 404
                                    || handoverRejected
                                    || modelGone
                                    || e.error.kind
                                            == AiChatService.ErrorKind.NETWORK
                                    || e.error.kind
                                            == AiChatService.ErrorKind.QUOTA);
                    boolean paid = code == 402
                            && e.error.kind
                                    == AiChatService.ErrorKind.QUOTA;
                    if (failoverEligible && autoRequested) {
                        if (paid) {
                            // 402 = this key cannot buy this model.
                            // Remember it forever, journal it, and do
                            // NOT spam a chat note per paid model —
                            // one summary says it once.
                            ModelCatalog.markPaid(autoProvider, model);
                            paidCount[0]++;
                            if (paidCount[0] >= 3) {
                                paidConfirmed[0] = true;
                            }
                        } else {
                            ModelCatalog.cooldown(autoProvider, model,
                                    System.currentTimeMillis()
                                            + 5L * 60_000L);
                        }
                        switchCount[0]++;
                        if (switchCount[0] > 3) {
                            // A router that never settles is worse
                            // than an honest stop.
                            String giveUp = "✨ Auto tried "
                                    + switchCount[0]
                                    + " models without a working one. "
                                    + "Check credits on your key, or "
                                    + "pick a specific model.";
                            AgentJournal.setReadCache(journalProject[0],
                                    readFacts.toString());
                            closeTask(journalProject[0], false,
                                    "✨ Auto exhausted its model chain",
                                    taskClosed, listener);
                            dispatch(listener, () -> listener
                                    .onError(new AiChatService.AiError(
                                            AiChatService.ErrorKind.EMPTY,
                                            0, giveUp)));
                            return;
                        }
                        boolean needsVisionNow =
                                AiChatService.hasMultimodalImageParts(
                                        messages);
                        String next = ModelCatalog.pickAutoModel(
                                autoProvider, autoModelPool, autoShowAll,
                                needsVisionNow, model,
                                paidConfirmed[0]);
                        if (next.isEmpty() && !paidConfirmed[0]) {
                            // Any other visible healthy model is still
                            // better than a dead turn.
                            next = ModelCatalog.pickAutoModel(
                                    autoProvider, autoModelPool, true,
                                    false, model);
                        }
                        if (!next.isEmpty()) {
                            ModelCatalog.sanitizeMessagesForModel(
                                    messages, next);
                            JsonObject switched = new JsonObject();
                            switched.addProperty("from", model);
                            switched.addProperty("to", next);
                            switched.addProperty("http_code", code);
                            switched.addProperty("mid_turn", true);
                            if (paid) {
                                switched.addProperty("reason",
                                        "needs credits (402)");
                            }
                            AgentJournal.record(journalProject[0],
                                    "model_failover", switched);
                            final String switchedTo = next;
                            if (paid) {
                                if (paidConfirmed[0]
                                        && !paidNoteSent[0]) {
                                    paidNoteSent[0] = true;
                                    String summary = "✨ Auto: "
                                            + paidCount[0]
                                            + " models need credits "
                                            + "on this key — using the "
                                            + "best free model instead";
                                    dispatch(listener, () -> listener
                                            .onSystemNote(summary));
                                }
                            } else {
                                String reason = handoverRejected
                                        ? "rejected the handover (400)"
                                        : modelGone
                                        ? "is no longer available (404)"
                                        : "busy (" + (code == 0
                                        ? "network"
                                        : String.valueOf(code)) + ")";
                                String note = "✨ Auto: "
                                        + ModelCatalog.displayName(model)
                                        + " " + reason
                                        + " — switching to "
                                        + ModelCatalog.displayName(next);
                                dispatch(listener, () -> listener
                                        .onSystemNote(note));
                            }
                            dispatch(listener, () -> listener
                                    .onResolvedModel(switchedTo));
                            model = next;
                            failedOver = true;
                            attempt = -1;
                            continue;
                        }
                    } else if (failoverEligible) {
                        // Manual pick: the user chose this model, so it
                        // is their call what happens next — surface the
                        // failure and name the model ✨ Auto would have
                        // used, but never switch behind their back.
                        // The cooldown still applies: the picker shows
                        // the rate-limited badge and ✨ Auto avoids it.
                        if (paid) {
                            ModelCatalog.markPaid(autoProvider, model);
                        }
                        ModelCatalog.cooldown(autoProvider, model,
                                System.currentTimeMillis()
                                        + 5L * 60_000L);
                        String suggest = ModelCatalog.pickAutoModel(
                                autoProvider, autoModelPool, autoShowAll,
                                AiChatService.hasMultimodalImageParts(
                                        messages), model, paid);
                        if (!suggest.isEmpty()) {
                            String hint = ModelCatalog.displayName(model)
                                    + (paid
                                    ? " needs credits your key does "
                                    + "not have (402) — pick a Free "
                                    + "model, or try "
                                    : " hit " + (code == 0
                                    ? "a network error"
                                    : String.valueOf(code))
                                    + " — tap the model bar to try ")
                                    + ModelCatalog.displayName(suggest)
                                    + ", or switch to ✨ Auto.";
                            dispatch(listener,
                                    () -> listener.onSystemNote(hint));
                        }
                    }
                    // A provider failure is part of the story: the export
                    // has to show that the model was never reached (or
                    // answered with an error), not just that nothing moved.
                    JsonObject failed = new JsonObject();
                    failed.addProperty("model", model);
                    failed.addProperty("error_kind",
                            String.valueOf(e.error.kind));
                    failed.addProperty("http_code", e.error.httpCode);
                    failed.addProperty("message",
                            AgentJournal.cap(
                                    String.valueOf(e.error.message),
                                    1200));
                    failed.addProperty("took_ms",
                            System.currentTimeMillis() - requestedAt);
                    failed.addProperty("messages_sent", messages.size());
                    AgentJournal.record(journalProject[0], "model_error",
                            failed);
                    // What this turn already read survives its death:
                    // the retry is briefed, not blind.
                    AgentJournal.setReadCache(journalProject[0],
                            readFacts.toString());
                    // The catalog must learn from every dead turn, or
                    // ✨ Auto re-picks the same broken model forever:
                    // a 404 / MODEL_GONE retires the model (hidden +
                    // lastError, exactly like a failed probe), and any
                    // other error puts it on an escalating cooldown so
                    // the next route lands on a different model.
                    boolean retired = code == 404
                            || e.error.kind
                                    == AiChatService.ErrorKind.MODEL_GONE;
                    if (retired) {
                        ModelCatalog.update(autoProvider, model,
                                info -> {
                                    info.hidden = true;
                                    info.lastError =
                                            "Retired: cannot chat (404)";
                                    info.updatedAt =
                                            System.currentTimeMillis();
                                });
                    } else {
                        ModelCatalog.cooldown(autoProvider, model,
                                System.currentTimeMillis()
                                        + 5L * 60_000L);
                    }
                    closeTask(journalProject[0], false,
                            cancelled ? "you stopped it" : stopReason(e.error),
                            taskClosed, listener);
                    if (!cancelled) {
                        if (autoRequested) {
                            autoDeadTurns++;
                        }
                        dispatch(listener,
                                () -> listener.onError(e.error));
                    }
                    return;
                }
            }
            // A model that fumbles real function calling may still
            // print the call as a JSON object (gpt-oss-120b did this
            // when the design rule leaned too hard on "the tool
            // call"). Recover it BEFORE the reply is judged, so it
            // runs exactly like a native call and the user gets the
            // real popup instead of a raw JSON bubble.
            recoverTextToolCall(reply);
            // The chat-side ban: nothing tool-shaped survives
            // to the bubble, whatever shape slipped recovery.
            reply.content = scrubToolJson(reply.content);
            {
                // What the model said, before anything is done with
                // it: the answer it wrote, the reasoning it kept
                // separate, and every tool call it asked for with the
                // arguments it chose. This is the line of the export
                // that settles "did the model even try the right
                // thing".
                JsonObject said = new JsonObject();
                said.addProperty("model", model);
                said.addProperty("took_ms",
                        System.currentTimeMillis() - requestedAt);
                said.addProperty("messages_sent", messages.size());
                said.addProperty("answer",
                        AgentJournal.cap(reply.content, 4000));
                if (reply.thinking != null && !reply.thinking.isEmpty()) {
                    said.addProperty("thinking",
                            AgentJournal.cap(reply.thinking, 4000));
                }
                JsonArray wanted = new JsonArray();
                for (AiChatService.ToolCallData call : reply.toolCalls) {
                    JsonObject one = new JsonObject();
                    one.addProperty("id", call.id);
                    one.addProperty("tool", call.name);
                    one.addProperty("arguments",
                            AgentJournal.cap(call.arguments, 4000));
                    wanted.add(one);
                }
                if (wanted.size() > 0) {
                    said.add("tool_calls", wanted);
                }
                AgentJournal.record(journalProject[0], "model_reply", said);
            }
            if (reply.toolCalls.isEmpty()) {
                String answer = reply.content == null ? "" : reply.content.trim();
                if (answer.isEmpty()) {
                    if (autoRequested) {
                        autoDeadTurns++;
                    }
                    closeTask(journalProject[0], false,
                            "the model returned an empty reply",
                            taskClosed, listener);
                    dispatch(listener, () -> listener.onError(new AiChatService.AiError(
                            AiChatService.ErrorKind.EMPTY, 200, "empty reply")));
                } else {
                    closeTask(journalProject[0], true, "", taskClosed,
                            listener);
                    // A finished turn makes the read cache redundant:
                    // the answer is in the chat and the index is fresh.
                    AgentJournal.clearReadCache(journalProject[0]);
                    // A clean answer proves the model is alive again.
                    ModelCatalog.markSuccess(autoProvider, model);
                    String thinking = reply.thinking == null ? "" : reply.thinking;
                    if (autoRequested && answer != null
                            && !answer.trim().isEmpty()) {
                        stickyAutoModel = model;
                        autoDeadTurns = 0;
                    }
                    dispatch(listener, () -> listener.onAnswer(answer, thinking));
                }
                return;
            }
            if (reply.rawMessage != null) {
                messages.add(reply.rawMessage);
            }
            String pendingPreviewScreen = "";
            String pendingPreviewPath = "";
            for (AiChatService.ToolCallData call : reply.toolCalls) {
                if (cancelled) {
                    closeTask(journalProject[0], false, "you stopped it",
                            taskClosed, listener);
                    return;
                }
                if (++calls > MAX_TOOL_CALLS) {
                    // Not a stop: the same task continues in another
                    // segment, with its memory re-sent, so nothing is
                    // re-proposed or lost at the seam. The calls this
                    // reply still holds are answered as deferred
                    // rather than dropped — a provider rejects a
                    // reply whose tool calls get no answer.
                    if (segment[0] < MAX_SEGMENTS) {
                        rollover[0] = true;
                    } else {
                        closeTask(journalProject[0], false,
                                "it reached the limit of " + MAX_TOOL_CALLS
                                        + " steps for one message",
                                taskClosed, listener);
                        dispatch(listener,
                                () -> listener.onError(
                                        new AiChatService.AiError(
                                                AiChatService.ErrorKind.HTTP,
                                                0,
                                                "The agent stopped after "
                                                        + MAX_TOOL_CALLS
                                                        + " tool calls for "
                                                        + "one message.")));
                        return;
                    }
                }
                if (rollover[0]) {
                    // This part is full: the call is answered, not
                    // run, and the task continues in the next part.
                    JsonObject deferred = new JsonObject();
                    deferred.addProperty("deferred", true);
                    deferred.addProperty("message",
                            "Not run yet — this part of the task is "
                                    + "full. The work continues in the "
                                    + "next part; call this again there "
                                    + "if it is still needed. Already "
                                    + "applied steps are skipped "
                                    + "automatically.");
                    JsonObject toolMessage = new JsonObject();
                    toolMessage.addProperty("role", "tool");
                    toolMessage.addProperty("tool_call_id", call.id);
                    toolMessage.addProperty("content",
                            deferred.toString());
                    messages.add(toolMessage);
                    continue;
                }
                AgentTools.Session session = new AgentTools.Session(
                        context, workingProjectId, allowModify);
                long callStartedAt = System.currentTimeMillis();
                JsonObject result;
                if (AgentTools.isMutation(call.name)) {
                    if (planGateArmed
                            && PLAN_GATE_TOOLS.contains(call.name)) {
                        // The plan was JUST saved; the user reviews.
                        result = planReviewBlocked();
                    } else {
                        String label = proposalLabel(call);
                        dispatch(listener, () -> listener
                                .onToolNote(label));
                        result = proposeAndExecute(call, session,
                                listener, journalProject[0], insisted,
                                turnStartedAt, lastUserText);
                    }
                    if (cancelled) {
                        closeTask(journalProject[0], false, "you stopped it",
                                taskClosed, listener);
                        return;
                    }
                    if (!result.has("error")) {
                        // The project just changed: cached read facts
                        // are stale now, and the fresh index covers it.
                        readFacts.setLength(0);
                        AgentJournal.clearReadCache(journalProject[0]);
                    }
                    if (result.has("bind_project")) {
                        String previousProject = journalProject[0];
                        workingProjectId = result.get("bind_project").getAsString();
                        journalProject[0] = workingProjectId;
                        // The task follows the work: whatever the turn
                        // opened under the old project closes there,
                        // and the created project gets the task — and
                        // any style/design/plan agreed along the way — so
                        // its log is never empty after an interruption.
                        AgentJournal.endTurn(previousProject, true, "");
                        AgentJournal.moveStyleBriefIfAgreedSince(
                                previousProject, workingProjectId,
                                turnStartedAt);
                        AgentJournal.moveDesignSystemIfAgreedSince(
                                previousProject, workingProjectId,
                                turnStartedAt);
                        AgentJournal.movePlanIfAgreedSince(
                                previousProject, workingProjectId,
                                turnStartedAt);
                        // Style/plan agreed before any project was
                        // chosen become this project's own.
                        AgentJournal.depositDrafts(workingProjectId);
                        AgentJournal.beginTurn(workingProjectId, lastUserText);
                        result.remove("bind_project");
                        String bound = workingProjectId;
                        dispatch(listener,
                                () -> listener.onWorkingProjectChanged(bound));
                    }
                } else if ("ask_choice".equals(call.name)) {
                    String label = AgentTools.labelFor(call.name, call.arguments);
                    dispatch(listener, () -> listener.onToolNote(label));
                    result = askChoice(call, listener);
                } else if (AgentTools.isInteractive(call.name)) {
                    String label = AgentTools.labelFor(call.name, call.arguments);
                    dispatch(listener, () -> listener.onToolNote(label));
                    result = askUser(call, listener);
                } else {
                    String label = AgentTools.labelFor(call.name, call.arguments);
                    dispatch(listener, () -> listener.onToolNote(label));
                    if (planGateArmed
                            && PLAN_GATE_TOOLS.contains(call.name)) {
                        // The plan was JUST saved; the user reviews.
                        result = planReviewBlocked();
                    } else {
                        result = AgentTools.dispatch(call.name,
                                call.arguments, session);
                    }
                    if ("set_plan".equals(call.name)
                            && (result == null
                                    || !result.has("error"))
                            && !planAlreadyKnown(session)) {
                        // Arm ONLY for a genuine FIRST plan. Field
                        // finding: progress updates that re-sent
                        // "steps" re-armed the gate mid-build and the
                        // loop refused the very screens it was
                        // building — the plan already existed, so
                        // this is an UPDATE, never a new review.
                        planGateArmed = true;
                    }
                    if ("set_plan".equals(call.name)
                            || "set_design_system".equals(call.name)
                            || "set_style_brief".equals(call.name)) {
                        dispatch(listener, listener::onStateChanged);
                    }
                    // The small live line disappears when the turn
                    // ends, so what was looked at — with its exact
                    // screen, widget and event — is written down.
                    if (!"set_style_brief".equals(call.name)
                            && !"set_plan".equals(call.name)
                            && !"get_task_log".equals(call.name)) {
                        AgentJournal.noteCheck(journalProject[0], call.name,
                                label);
                    }
                    // Read-only tools feed the retry cache: if the turn
                    // dies later, the retry is briefed with what was
                    // already read.
                    if (READ_CACHE_TOOLS.contains(call.name)) {
                        if (readFacts.length() > 2200) {
                            readFacts.setLength(0);
                        }
                        readFacts.append("• ").append(label).append(": ")
                                .append(AgentJournal.cap(
                                        result.toString(), 260))
                                .append('\n');
                    }
                }
                {
                    // Every tool call, in order, with what was asked
                    // and what came back. Arguments included, because
                    // an empty component name or an invented block is
                    // only visible here — the label never shows it.
                    JsonObject entry = new JsonObject();
                    entry.addProperty("tool", call.name);
                    entry.addProperty("call_id", call.id);
                    entry.addProperty("arguments",
                            AgentJournal.cap(call.arguments, 4000));
                    entry.addProperty("took_ms",
                            System.currentTimeMillis() - callStartedAt);
                    if (result.has("error")) {
                        entry.addProperty("outcome", "error");
                        entry.addProperty("error",
                                result.get("error").getAsString());
                        entry.addProperty("error_message",
                                AgentJournal.cap(
                                        result.has("message")
                                                ? result.get("message")
                                                        .getAsString()
                                                : "", 1200));
                    } else {
                        entry.addProperty("outcome", "ok");
                    }
                    entry.addProperty("result",
                            AgentJournal.cap(result.toString(), 4000));
                    AgentJournal.record(journalProject[0], "tool_call",
                            entry);
                }
                boolean failed = result.has("error")
                        && !"E_REJECTED".equals(result.get("error").getAsString());
                if (failed) {
                    // A different payload is a different attempt:
                    // only the same call failing the same way twice
                    // in a row stops the turn.
                    String signature = call.name + "|"
                            + result.get("error").getAsString() + "|"
                            + (call.arguments == null ? 0
                                    : call.arguments.hashCode());
                    sameFailure = signature.equals(lastFailedCall)
                            ? sameFailure + 1 : 1;
                    lastFailedCall = signature;
                    if (sameFailure >= 2) {
                        String reason = result.has("message")
                                ? result.get("message").getAsString()
                                : "";
                        String again = "⚠️ " + call.name
                                + " failed twice with the same problem: "
                                + (reason.isEmpty()
                                ? "invalid arguments" : reason)
                                + " — the next attempt must fix exactly "
                                + "that field.";
                        dispatch(listener, () -> listener
                                .onSystemNote(again));
                        String stopText = "The agent stopped: the same step "
                                + "failed twice (" + call.name + ")."
                                + (reason.isEmpty() ? ""
                                        : " Last problem: " + reason)
                                + " Tell me how you'd like to proceed.";
                        AgentJournal.setReadCache(journalProject[0],
                                readFacts.toString());
                        closeTask(journalProject[0], false,
                                "the same step failed twice (" + call.name
                                        + ")", taskClosed, listener);
                        dispatch(listener, () -> listener.onError(
                                new AiChatService.AiError(
                                        AiChatService.ErrorKind.HTTP, 0,
                                        stopText)));
                        return;
                    }
                } else {
                    lastFailedCall = "";
                    sameFailure = 0;
                }
                if (result.has("_attach_preview_image")) {
                    pendingPreviewPath = result.get("_attach_preview_image")
                            .getAsString();
                    result.remove("_attach_preview_image");
                    pendingPreviewScreen = result.has("screen")
                            ? result.get("screen").getAsString() : "screen";
                }
                String content = result.toString();
                if (content.length() > TOOL_RESULT_CAP) {
                    content = content.substring(0, TOOL_RESULT_CAP)
                            + "\n…(truncated — ask a narrower question)";
                }
                compactToolHistory(messages);
                JsonObject toolMessage = new JsonObject();
                toolMessage.addProperty("role", "tool");
                toolMessage.addProperty("tool_call_id", call.id);
                toolMessage.addProperty("content", content);
                messages.add(toolMessage);
            }
            if (!pendingPreviewPath.isEmpty() && !previewVisionDisabled[0]
                    && !rollover[0]) {
                JsonObject previewTurn =
                        AiChatService.buildPreviewInspectionMessage(
                                pendingPreviewScreen, pendingPreviewPath);
                if (previewTurn != null) {
                    removePreviewInspectionMessages(messages);
                    messages.add(previewTurn);
                }
            }
            if (rollover[0]) {
                // The seam between two parts of the same task: the
                // memory is re-sent, the plan stays as it is, and
                // what already landed cannot be proposed again.
                segment[0]++;
                calls = 0;
                rollover[0] = false;
                dispatch(listener, () -> listener.onToolNote(
                        "Continuing part " + segment[0] + " of "
                                + MAX_SEGMENTS
                                + " — skipping what is already done"));
                messages.add(continueMessage(workingProjectId,
                        journalProject[0]));
            }
        }
        // The loop only falls through here when the turn was stopped.
        closeTask(journalProject[0], false, "you stopped it", taskClosed,
                listener);
    }

    /** Removes any transient preview-screen vision messages, returning true if at least one was removed. */
    private static boolean removePreviewInspectionMessages(
            List<JsonObject> messages) {
        return messages.removeIf(AiChatService::isPreviewInspectionMessage);
    }

    /** Closes the open task in the project journal, once per turn. */
    private void closeTask(String scId, boolean done, String reason,
                           boolean[] closed, Listener listener) {
        if (closed[0]) {
            return;
        }
        closed[0] = true;
        JsonObject ended = new JsonObject();
        ended.addProperty("done", done);
        ended.addProperty("reason", reason == null ? "" : reason);
        AgentJournal.record(scId, "turn_end", ended);
        AgentJournal.endTurn(scId, done, reason);
        dispatch(listener, listener::onStateChanged);
    }

    /** A short plain reason a request-level failure stopped a task. */
    private static String stopReason(AiChatService.AiError error) {
        if (error != null
                && error.kind == AiChatService.ErrorKind.QUOTA) {
            return "the provider's limit was hit (rate limit or quota)";
        }
        String message = error == null || error.message == null
                ? "" : error.message.trim();
        if (message.isEmpty()) {
            return "something went wrong";
        }
        return message.length() <= 120
                ? message : message.substring(0, 117) + "…";
    }

    /**
     * Interactive tools: the turn pauses while the user acts in the
     * app's own UI. Their action (or their closing the UI) is the tool
     * result — a cancellation is information, not a failure.
     */
    private JsonObject askUser(AiChatService.ToolCallData call,
                               Listener listener) {
        JsonObject args = new JsonObject();
        try {
            args = com.google.gson.JsonParser.parseString(
                    call.arguments == null ? "{}" : call.arguments)
                    .getAsJsonObject();
        } catch (Exception ignored) {
            // An unparseable call still deserves the picker, unfilled.
        }
        String question = args.has("question")
                ? args.get("question").getAsString() : "a colour";
        String initial = args.has("initial")
                ? args.get("initial").getAsString() : "";
        JsonObject picked = listener.onPickColor(question, initial);
        JsonObject result = new JsonObject();
        if (picked == null) {
            result.addProperty("cancelled", true);
            result.addProperty("message",
                    "The user closed the colour picker without picking. "
                            + "Ask what they would like in words, or "
                            + "continue without that colour change — do "
                            + "not pick a colour for them instead.");
            return result;
        }
        result.add("picked_colour", picked);
        if (picked.has("applied_everywhere")) {
            result.addProperty("message",
                    "The user applied this colour to the WHOLE app "
                            + "themselves: the design system was "
                            + "re-branded and the theme resources were "
                            + "materialized. Do NOT propose any colour "
                            + "change for it — build with the OpenUI "
                            + "tokens (@color/openui_*), which already "
                            + "carry this colour everywhere.");
        } else {
            result.addProperty("message",
                    "The user picked this colour themselves in "
                            + "Sketchware's colour picker. Use the hex "
                            + "exactly, and propose the change on an "
                            + "approval card right away — the card is "
                            + "the confirmation, so do not ask again "
                            + "in chat first.");
        }
        return result;
    }

    /**
     * The brainstorming question: named options, answered by the user.
     * A dismissed question is not a refusal to answer — the model is
     * told to propose one itself and carry on, because a conversation
     * that stops to repeat the question is worse than a decided one.
     */
    /**
     * Recovers tool calls the model printed as text. A weak model will
     * sometimes ANSWER with the call as a JSON object instead of making
     * a real function call — the user then sees raw JSON and no popup.
     * Recovery is strict about EXECUTION (only a tool the app knows and
     * asks the user with is ever lifted; a mutation or an unknown name
     * is never run) and forgiving about SHAPE: bare object, fenced,
     * wrapped in prose, an array of calls, a {"tool_calls":[…]} wrapper,
     * arguments under any common key, the name in any casing/separators,
     * or no usable name at all when the object carries a question with
     * options (the ask_choice signature). The JSON never reaches the
     * chat: what replaces it is the real sheet, or plain words.
     */
    private static void recoverTextToolCall(
            AiChatService.AgentReply reply) {
        if (reply == null || reply.content == null
                || !reply.toolCalls.isEmpty()
                || reply.rawMessage == null) {
            return;
        }
        String text = reply.content.trim();
        // A fenced block: ``` or ```json (tag may share the first line).
        if (text.startsWith("```")) {
            int open = text.indexOf('\n');
            int close = text.lastIndexOf("```");
            if (open > 3 && close > open) {
                text = text.substring(open + 1, close).trim();
            } else if (close > 3) {
                text = text.substring(3, close).trim();
            }
            if (text.startsWith("json")) {
                text = text.substring(4).trim();
            }
        }
        // 1) The whole reply is one JSON value (object or array).
        if (text.startsWith("{") || text.startsWith("[")) {
            java.util.List<AiChatService.ToolCallData> calls =
                    callsFromJsonValue(text);
            if (calls != null && !calls.isEmpty()) {
                for (AiChatService.ToolCallData call : calls) {
                    declareRecoveredCall(reply, call);
                }
                reply.content =
                        "The question sheet is open — pick there.";
                return;
            }
            // A pure value naming no tool stays as text.
            return;
        }
        // 2) The JSON sits inside prose: find the first balanced {...}
        // (string-aware) that carries a tool, lift it out, and keep the
        // surrounding words as the bubble.
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = balancedObjectEnd(text, start);
            if (end < 0) {
                break;
            }
            java.util.List<AiChatService.ToolCallData> calls =
                    callsFromJsonValue(text.substring(start, end + 1));
            if (calls != null && !calls.isEmpty()) {
                for (AiChatService.ToolCallData call : calls) {
                    declareRecoveredCall(reply, call);
                }
                String rest = (text.substring(0, start) + " "
                        + text.substring(end + 1)).trim();
                reply.content = rest.isEmpty()
                        ? "The question sheet is open — pick there."
                        : rest;
                return;
            }
            start = text.indexOf('{', start + 1);
        }
    }

    /** Index of the brace that closes the one at {@code start}, or -1. */
    private static int balancedObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The recoverable calls one JSON value (object or array) holds. */
    private static java.util.List<AiChatService.ToolCallData>
            callsFromJsonValue(String json) {
        try {
            JsonElement parsed = com.google.gson.JsonParser
                    .parseString(json);
            java.util.List<AiChatService.ToolCallData> out =
                    new java.util.ArrayList<>();
            if (parsed.isJsonArray()) {
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        AiChatService.ToolCallData call =
                                callFromJsonObject(
                                        element.getAsJsonObject());
                        if (call != null) {
                            out.add(call);
                        }
                    }
                }
                return out;
            }
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                if (obj.has("tool_calls")
                        && obj.get("tool_calls").isJsonArray()) {
                    for (JsonElement element
                            : obj.getAsJsonArray("tool_calls")) {
                        if (element.isJsonObject()) {
                            AiChatService.ToolCallData call =
                                    callFromJsonObject(
                                            element.getAsJsonObject());
                            if (call != null) {
                                out.add(call);
                            }
                        }
                    }
                    return out;
                }
                AiChatService.ToolCallData call =
                        callFromJsonObject(obj);
                if (call != null) {
                    out.add(call);
                }
                return out;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Tool names matched with case and separators forgiven. */
    private static String canonicalToolName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String norm = raw.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9]", "");
        for (String known : new String[] {
                "ask_choice", "pick_color", "ask_user", "ask_file"}) {
            if (!AgentTools.isInteractive(known)) {
                continue;
            }
            if (norm.equals(known.replace("_", ""))) {
                return known;
            }
        }
        return null;
    }

    /** The interactive call this object carries, or null. */
    private static AiChatService.ToolCallData callFromJsonObject(
            JsonObject obj) {
        try {
            String rawName = "";
            JsonElement argsElement = null;
            if (obj.has("function") && obj.get("function").isJsonObject()) {
                JsonObject fn = obj.getAsJsonObject("function");
                rawName = fn.has("name")
                        && fn.get("name").isJsonPrimitive()
                        ? fn.get("name").getAsString() : "";
                argsElement = fn.get("arguments");
            } else {
                rawName = obj.has("tool")
                        && obj.get("tool").isJsonPrimitive()
                        ? obj.get("tool").getAsString()
                        : obj.has("name")
                        && obj.get("name").isJsonPrimitive()
                        ? obj.get("name").getAsString() : "";
                argsElement = obj.has("arguments") ? obj.get("arguments")
                        : obj.has("parameters") ? obj.get("parameters")
                        : obj.has("args") ? obj.get("args") : null;
            }
            String name = canonicalToolName(rawName);
            // Intent fallback: a question with options IS an ask_choice,
            // name or no name — the model's intent is unambiguous.
            if (name == null
                    && obj.has("question") && obj.has("options")
                    && obj.get("options").isJsonArray()
                    && obj.getAsJsonArray("options").size() >= 2
                    && AgentTools.isInteractive("ask_choice")) {
                name = "ask_choice";
                argsElement = obj;
            }
            if (name == null) {
                return null;
            }
            String arguments = "{}";
            if (argsElement != null && !argsElement.isJsonNull()) {
                arguments = argsElement.isJsonPrimitive()
                        ? argsElement.getAsString()
                        : argsElement.toString();
                com.google.gson.JsonParser.parseString(arguments);
            }
            AiChatService.ToolCallData call =
                    new AiChatService.ToolCallData();
            call.id = "call_text_" + System.currentTimeMillis();
            call.name = name;
            call.arguments = arguments;
            return call;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Declares the recovered call in the raw assistant message. */
    private static void declareRecoveredCall(
            AiChatService.AgentReply reply,
            AiChatService.ToolCallData call) {
        JsonArray declared;
        JsonElement existing =
                reply.rawMessage.get("tool_calls");
        if (existing != null && existing.isJsonArray()) {
            declared = existing.getAsJsonArray();
        } else {
            declared = new JsonArray();
            reply.rawMessage.add("tool_calls", declared);
        }
        JsonObject wire = new JsonObject();
        wire.addProperty("id", call.id);
        wire.addProperty("type", "function");
        JsonObject fn = new JsonObject();
        fn.addProperty("name", call.name);
        fn.addProperty("arguments", call.arguments);
        wire.add("function", fn);
        declared.add(wire);
        reply.toolCalls.add(call);
    }

    /**
     * True when this JSON object is shaped like a tool call (any wire
     * dialect) — even one the app refuses to execute. Plain data the
     * user actually asked for never matches: a lone "name", a sample
     * payload, a config snippet all pass through untouched.
     */
    private static boolean looksLikeToolObject(String json) {
        try {
            JsonElement parsed = com.google.gson.JsonParser
                    .parseString(json);
            if (!parsed.isJsonObject()) {
                return false;
            }
            JsonObject obj = parsed.getAsJsonObject();
            if (obj.has("tool_calls") || obj.has("function")
                    || obj.has("tool")) {
                return true;
            }
            boolean hasName = obj.has("name")
                    && obj.get("name").isJsonPrimitive();
            boolean hasArgs = obj.has("arguments")
                    || obj.has("parameters") || obj.has("args");
            if (hasName && hasArgs) {
                return true;
            }
            return obj.has("question") && obj.has("options")
                    && obj.get("options").isJsonArray();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * The chat-side guarantee: any remaining tool-shaped JSON in a
     * reply is masked to a plain line before the user can read it.
     * JSON the user actually asked for (not tool-shaped) is untouched.
     */
    public static String scrubToolJson(String text) {
        if (text == null || text.isEmpty()
                || (!text.contains("{") && !text.contains("["))) {
            return text;
        }
        String out = text;
        int start = out.indexOf('{');
        while (start >= 0) {
            int end = balancedObjectEnd(out, start);
            if (end < 0) {
                break;
            }
            String candidate = out.substring(start, end + 1);
            java.util.List<AiChatService.ToolCallData> calls =
                    callsFromJsonValue(candidate);
            // Interactive calls are handled (recovered earlier); any
            // OTHER tool-shaped object — a mutation, an unknown tool —
            // is banned from display too, without being executed.
            boolean handled = calls != null && !calls.isEmpty()
                    || looksLikeToolObject(candidate);
            if (handled) {
                out = out.substring(0, start)
                        + "[The model wrote a tool call as text — the "
                        + "app handled it; nothing for you to do.]"
                        + out.substring(end + 1);
                start = out.indexOf('{', start);
            } else {
                start = out.indexOf('{', start + 1);
            }
        }
        return out;
    }

    /**
     * The ask_choice a text object carries: [question, option…] or null.
     * Used by the chat to pop the real sheet even when the loop's
     * recovery missed the shape entirely.
     */
    public static String[] extractTextChoice(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = balancedObjectEnd(text, start);
            if (end < 0) {
                break;
            }
            try {
                JsonObject obj = com.google.gson.JsonParser
                        .parseString(text.substring(start, end + 1))
                        .getAsJsonObject();
                JsonElement argsElement = obj;
                if (obj.has("function")
                        && obj.get("function").isJsonObject()) {
                    argsElement = obj.getAsJsonObject("function");
                }
                JsonObject args = argsElement.isJsonObject()
                        && (argsElement.getAsJsonObject().has("arguments")
                        || argsElement.getAsJsonObject().has("parameters")
                        || argsElement.getAsJsonObject().has("args")
                        || argsElement.getAsJsonObject().has("question"))
                        ? argsElement.getAsJsonObject() : null;
                if (args != null) {
                    args = args.has("arguments")
                            ? args.get("arguments").isJsonPrimitive()
                            ? com.google.gson.JsonParser.parseString(
                            args.get("arguments").getAsString())
                            .getAsJsonObject()
                            : args.get("arguments").isJsonObject()
                            ? args.getAsJsonObject("arguments") : null
                            : args.has("parameters")
                            ? args.getAsJsonObject("parameters")
                            : args.has("args")
                            ? args.getAsJsonObject("args") : args;
                }
                if (args != null && args.has("question")
                        && args.has("options")
                        && args.get("options").isJsonArray()
                        && args.getAsJsonArray("options").size() >= 2) {
                    String[] out = new String[1 + args
                            .getAsJsonArray("options").size()];
                    out[0] = args.get("question").isJsonPrimitive()
                            ? args.get("question").getAsString() : "";
                    int i = 1;
                    for (JsonElement option
                            : args.getAsJsonArray("options")) {
                        out[i++] = option.isJsonPrimitive()
                                ? option.getAsString()
                                : option.toString();
                    }
                    return out;
                }
            } catch (Exception ignored) {
                // Not JSON — keep scanning.
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    private JsonObject askChoice(AiChatService.ToolCallData call,
                                 Listener listener) {
        JsonObject args = new JsonObject();
        try {
            args = com.google.gson.JsonParser.parseString(
                    call.arguments == null ? "{}" : call.arguments)
                    .getAsJsonObject();
        } catch (Exception ignored) {
            // An unparseable call still gets the question, unfilled.
        }
        String question = args.has("question")
                ? args.get("question").getAsString() : "";
        java.util.List<String> options = new java.util.ArrayList<>();
        if (args.has("options") && args.get("options").isJsonArray()) {
            for (JsonElement element : args.getAsJsonArray("options")) {
                String option = element.isJsonPrimitive()
                        ? element.getAsString() : "";
                if (!option.trim().isEmpty()) {
                    options.add(option.trim());
                }
            }
        }
        JsonObject result = new JsonObject();
        if (question.isEmpty() || options.size() < 2) {
            result.addProperty("error", "E_BAD_ARGS");
            result.addProperty("message",
                    "ask_choice needs a \"question\" and at least two "
                            + "\"options\", each a whole direction in one "
                            + "sentence.");
            return result;
        }
        String picked = listener.onAskChoice(question, options);
        if (picked == null || picked.trim().isEmpty()) {
            result.addProperty("cancelled", true);
            result.addProperty("message",
                    "The user did not choose. Pick the option you would "
                            + "stand behind, say in one line why, save it "
                            + "with set_style_brief or set_plan, and carry "
                            + "on — do not ask the same question again in "
                            + "chat.");
            return result;
        }
        result.addProperty("chosen", picked);
        result.addProperty("message",
                "The user chose this themselves: “" + picked
                        + "”. Save what was agreed (set_style_brief for a "
                        + "look, set_plan for a plan) and follow it "
                        + "exactly. Do not ask the same question again.");
        return result;
    }

    /**
     * The mutation path: validate into a proposal, let the user decide
     * on the card, and only then execute. Any refusal along the way is
     * reported to the model as a normal tool result.
     */
    private JsonObject proposeAndExecute(AiChatService.ToolCallData call,
                                         AgentTools.Session session,
                                         Listener listener,
                                         String journalProject,
                                         java.util.Set<String> insisted,
                                         long turnStartedAt,
                                         String lastUserText) {
        String fingerprint = AgentTools.fingerprint(call.name,
                call.arguments);
        AgentTools.ProposalResult proposed = AgentTools.propose(call.name,
                call.arguments, session, insisted.contains(fingerprint));
        if (proposed.error != null) {
            String errCode = proposed.error.has("error")
                    ? proposed.error.get("error").getAsString() : "";
            if ("E_ALREADY_DONE".equals(errCode)) {
                AgentJournal.noteCheck(journalProject, call.name,
                        "Already in the project: " + proposalLabel(call));
                insisted.add(fingerprint);
                return proposed.error;
            }
            if ("E_ALREADY_SET".equals(errCode)) {
                AgentJournal.noteCheck(journalProject, call.name,
                        "Already set: " + proposalLabel(call));
                insisted.add(fingerprint);
                return proposed.error;
            }
            AgentJournal.mutationFailed(journalProject, call.name,
                    AgentTools.labelFor(call.name, call.arguments),
                    proposed.error.has("message")
                            ? proposed.error.get("message").getAsString()
                            : "");
            return proposed.error;
        }
        AgentProposal proposal = proposed.proposal;
        // The change belongs to the project it targets (a created
        // project journals under itself from its first step).
        String journal = proposal.workingProjectId == null
                || proposal.workingProjectId.isEmpty()
                ? journalProject : proposal.workingProjectId;
        {
            // The card the user was shown, exactly: what it claimed it
            // would do, the details behind it, and the payload that
            // would be applied. A rejection is only diagnosable if the
            // record can show what was actually offered.
            JsonObject card = new JsonObject();
            card.addProperty("tool", call.name);
            card.addProperty("arguments",
                    AgentJournal.cap(call.arguments, 4000));
            card.addProperty("title",
                    proposal.title == null ? "" : proposal.title);
            card.addProperty("body",
                    proposal.body == null ? "" : proposal.body);
            card.addProperty("details",
                    proposal.details == null ? "" : proposal.details);
            AgentJournal.record(journal, "card_shown", card);
        }
        boolean approved = listener.onApproval(proposal);
        {
            JsonObject decision = new JsonObject();
            decision.addProperty("tool", call.name);
            decision.addProperty("title",
                    proposal.title == null ? "" : proposal.title);
            decision.addProperty("approved", approved);
            AgentJournal.record(journal, approved ? "card_approved"
                    : "card_rejected", decision);
        }
        if (!approved) {
            AgentJournal.mutationRejected(journal, call.name,
                    proposal.title,
                    proposal.details == null ? "" : proposal.details);
            JsonObject rejected = new JsonObject();
            rejected.addProperty("error", "E_REJECTED");
            rejected.addProperty("message",
                    "The user rejected this change. Do not retry it or "
                            + "argue; ask what they would like instead.");
            dispatch(listener, () -> listener.onApprovalResolved(proposal, rejected));
            return rejected;
        }
        JsonObject result = AgentTools.execute(proposal,
                new AgentTools.Session(context, proposal.workingProjectId,
                        session.allowModify));
        if (result.has("bind_project")) {
            // A project this step created journals under itself, not
            // under wherever the turn happened to start. Deposit any
            // drafts/artefacts first so the new project's first task
            // picks up its own plan title instead of a short confirmation.
            String created = result.get("bind_project").getAsString();
            if (!created.isEmpty()) {
                journal = created;
                AgentJournal.moveStyleBriefIfAgreedSince(
                        journalProject, created, turnStartedAt);
                AgentJournal.moveDesignSystemIfAgreedSince(
                        journalProject, created, turnStartedAt);
                AgentJournal.movePlanIfAgreedSince(
                        journalProject, created, turnStartedAt);
                AgentJournal.depositDrafts(created);
                AgentJournal.beginTurn(created, lastUserText);
            }
        }
        if (result.has("error")) {
            AgentJournal.mutationFailed(journal, call.name, proposal.title,
                    result.has("message")
                            ? result.get("message").getAsString() : "");
            JsonObject apply = new JsonObject();
            apply.addProperty("tool", call.name);
            apply.addProperty("outcome", "failed");
            apply.addProperty("error", result.get("error").getAsString());
            apply.addProperty("message", AgentJournal.cap(
                    result.has("message")
                            ? result.get("message").getAsString() : "",
                    1200));
            AgentJournal.record(journal, "apply_failed", apply);
        } else {
            // The fingerprint is the app's memory of this exact
            // change: a later proposal of it — from a retry, a resumed
            // task, or a different model entirely — is refused before
            // it can reach the user.
            AgentJournal.mutationDone(journal, call.name, proposal.title,
                    AgentTools.fingerprint(call.name, call.arguments));
            AgentJournal.touchPlanCurrent(journal);
            if ("build_project".equals(call.name)) {
                AgentJournal.completeFinalBuildStepIfReady(journal);
                // A green build re-verifies reality: reopened steps'
                // locks lift here, and nowhere else.
                if (result != null && !result.has("error")) {
                    AgentJournal.clearReopensOnBuildSuccess(journal);
                }
            }
            {
                // The change as it landed, read back from the project
                // — not as it was promised. This is the only place
                // where "was it really created?" is answered.
                JsonObject applied = new JsonObject();
                applied.addProperty("tool", call.name);
                applied.addProperty("title",
                        proposal.title == null ? "" : proposal.title);
                applied.addProperty("result",
                        AgentJournal.cap(result.toString(), 4000));
                AgentJournal.record(journal, "applied", applied);
                // The change itself is the evidence: the Plan page
                // tracks reality, not the model's bookkeeping.
                AgentJournal.notePlanEvidence(journal, call.name,
                        proposal == null ? null : proposal.args);
                // The Plan Contract: work that matches no open step is
                // NAMED, not blocked — one line in the tool result.
                String drift = AgentJournal.driftNote(journal,
                        proposal == null ? "" : proposal.title);
                if (drift != null && result != null
                        && !result.has("error")) {
                    result.addProperty("plan_note", drift);
                }
            }
            // The change compiles and saves, and can still be broken:
            // a block naming a widget its screen does not have builds
            // fine and crashes on launch. The agent is told about it
            // here, while it can still fix it.
            List<String> broken =
                    AgentLogic.danglingWidgetReferences(journal);
            if (!broken.isEmpty()) {
                result.add("runtimeWarnings", jsonList(broken));
                StringBuilder text = new StringBuilder(
                        "Applied — but this project now has "
                                + broken.size()
                                + " broken reference(s) that would make "
                                + "the app crash when it opens:");
                for (String finding : broken) {
                    text.append("\n• ").append(finding);
                }
                result.addProperty("warning", text.toString());
                AgentJournal.noteCheck(journal, call.name,
                        "Broken reference found in " + proposal.title);
                JsonObject dangling = new JsonObject();
                dangling.add("references", jsonList(broken));
                dangling.addProperty("after", call.name);
                AgentJournal.record(journal, "broken_references", dangling);
            }
            // Variables declared and never used: the agent invented
            // four of them for a two-screen notes app and the user had
            // to reject four more by hand. Say so while they are cheap
            // to delete.
            List<String> unused = AgentLogic.unusedVariables(journal);
            unused.addAll(AgentLogic.unreadInputs(journal));
            if (!unused.isEmpty()) {
                result.add("unusedVariables", jsonList(unused));
                StringBuilder text = new StringBuilder();
                if (result.has("warning")) {
                    text.append(result.get("warning").getAsString());
                } else {
                    text.append("Applied.");
                }
                text.append("\n\nWorth a look — nothing uses these yet. "
                        + "Wire them up, or remove a variable with "
                        + "remove_variable:");
                for (String finding : unused) {
                    text.append("\n• ").append(finding);
                }
                result.addProperty("warning", text.toString());
            }
        }
        dispatch(listener, () -> listener.onApprovalResolved(proposal, result));
        return result;
    }

    /** A JSON array of plain strings, for tool results. */
    private static JsonArray jsonList(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** The plain label of a proposed call, for the log. */
    private static String proposalLabel(AiChatService.ToolCallData call) {
        try {
            return AgentTools.labelFor(call.name, call.arguments);
        } catch (Exception e) {
            return String.valueOf(call.name);
        }
    }

    /**
     * The seam between two segments of the same task: the model is
     * told to carry on, and handed the app's own memory again — the
     * real state of the project and the work already landed — so a
     * continuation can never restart it or redo finished steps.
     */
    private JsonObject continueMessage(String boundProject,
                                       String journalProject) {
        StringBuilder text = new StringBuilder(
                "Continue the same task exactly where you stopped. Do not "
                        + "start over, do not restate the plan, and do not "
                        + "re-propose anything already applied — the app "
                        + "rejects duplicates automatically and the task "
                        + "log below is the truth.");
        if (journalProject != null && !journalProject.isEmpty()) {
            text.append("\n\n").append(
                    AgentJournal.brief(journalProject).trim());
            text.append("\n\n").append(snapshot(journalProject));
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", text.toString());
        return message;
    }

    /** The project as it is right now, in a few plain lines. */
    private String snapshot(String scId) {
        StringBuilder out = new StringBuilder("The project right now:");
        try {
            for (ProjectFileBean activity
                    : SketchwareProjects.activities(scId)) {
                out.append("\n· ")
                        .append(SketchwareProjects.activityName(activity))
                        .append(" (")
                        .append(SketchwareProjects.screenBeans(scId,
                                activity).size())
                        .append(" widgets)");
            }
        } catch (Exception ignored) {
            // The snapshot is an aid, never a requirement.
        }
        out.append("\nRe-check this with your tools before proposing "
                + "anything.");
        return out.toString();
    }

    /** Read-only tools whose findings are worth remembering across a
     * failed turn's retry. */
    private static final java.util.Set<String> READ_CACHE_TOOLS =
            java.util.Set.of("get_screen", "get_event_logic",
                    "list_activities", "list_resources",
                    "get_values_resources", "read_source_files",
                    "get_project_setup", "list_local_libraries",
                    "inspect_manifest_and_settings",
                    "list_custom_definitions", "read_resource_xml",
                    "list_font_and_sound_sources", "read_assets");

    /**
     * The compact project index: every screen with its widget ids,
     * wired events, components, variables, lists, More Blocks and
     * SharedPreferences files, plus the custom views (RecyclerView
     * item layouts) and the menu files with their item ids — all in
     * bounded, token-cheap lines. A model that just arrived (switch,
     * retry, or the ✨ Auto router) reads this in the system message
     * instead of spending 4–5 tool calls discovering it, and storage
     * keys stay consistent across screens because every screen's keys
     * are visible side by side.
     */
    private static String compactProjectIndex(String scId) {
        if (scId == null || scId.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try {
            List<ProjectFileBean> activities =
                    SketchwareProjects.activities(scId);
            if (activities == null || activities.isEmpty()) {
                return "";
            }
            out.append("\n\nPROJECT INDEX (current truth; check here "
                    + "before reading files):");
            for (ProjectFileBean activity : activities) {
                String name = SketchwareProjects.activityName(activity);
                out.append("\n• ").append(name).append(":");
                ArrayList<ViewBean> beans =
                        SketchwareProjects.screenBeans(scId, activity);
                StringBuilder ids = new StringBuilder();
                if (beans != null) {
                    int shown = 0;
                    for (ViewBean bean : beans) {
                        if (bean == null || bean.id == null
                                || bean.id.isEmpty()) {
                            continue;
                        }
                        if (shown >= 18) {
                            ids.append(" …(+")
                                    .append(beans.size() - shown)
                                    .append(")");
                            break;
                        }
                        if (ids.length() > 0) {
                            ids.append(", ");
                        }
                        ids.append(bean.id);
                        shown++;
                    }
                }
                out.append(" widgets[").append(ids).append("]");
                SketchwareProjects.LogicSummary logic =
                        SketchwareProjects.logicSummary(scId, activity);
                List<String> events =
                        SketchwareProjects.eventLines(scId, activity);
                if (!events.isEmpty()) {
                    StringBuilder ev = new StringBuilder();
                    int shown = 0;
                    for (String line : events) {
                        if (shown >= 10) {
                            ev.append(" …(+")
                                    .append(events.size() - shown)
                                    .append(")");
                            break;
                        }
                        if (ev.length() > 0) {
                            ev.append("; ");
                        }
                        ev.append(line);
                        shown++;
                    }
                    out.append(" events[").append(ev).append("]");
                }
                if (!logic.components.isEmpty()) {
                    out.append(" components[")
                            .append(joinCaps(logic.components, 12))
                            .append("]");
                }
                if (!logic.variables.isEmpty()) {
                    out.append(" vars[")
                            .append(joinCaps(logic.variables, 10))
                            .append("]");
                }
                if (!logic.lists.isEmpty()) {
                    out.append(" lists[")
                            .append(joinCaps(logic.lists, 8))
                            .append("]");
                }
                if (!logic.moreBlocks.isEmpty()) {
                    out.append(" moreBlocks[")
                            .append(joinCaps(logic.moreBlocks, 6))
                            .append("]");
                }
                if (!logic.prefFiles.isEmpty()) {
                    out.append(" prefs[")
                            .append(joinCaps(logic.prefFiles, 8))
                            .append("]");
                }
            }
            List<ProjectFileBean> customViews =
                    SketchwareProjects.customViews(scId);
            if (customViews != null && !customViews.isEmpty()) {
                StringBuilder items = new StringBuilder();
                for (ProjectFileBean view : customViews) {
                    if (items.length() > 0) {
                        items.append(", ");
                    }
                    items.append(SketchwareProjects.screenName(view))
                            .append(" (")
                            .append(SketchwareProjects
                                    .screenBeans(scId, view).size())
                            .append(" widgets)");
                }
                out.append("\n• Item layouts (RecyclerView rows): ")
                        .append(items);
            }
            java.util.Map<String, List<String>> menus =
                    AgentResources.collectMenuResourceIdsByFile(scId);
            for (Map.Entry<String, List<String>> menu : menus.entrySet()) {
                out.append("\n• res/menu/").append(menu.getKey())
                        .append(".xml ids[")
                        .append(joinCaps(menu.getValue(), 12))
                        .append("] — compare with {\"res_id\":\"…\"}");
            }
        } catch (Throwable ignored) {
            return "";
        }
        return out.length() > 2600 ? out.substring(0, 2600)
                : out.toString();
    }

    /** Comma-joins, capped, for the project index lines. */
    private static String joinCaps(List<String> values, int cap) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (shown >= cap) {
                out.append(" …(+)").append(values.size() - shown);
                break;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value);
            shown++;
        }
        return out.toString();
    }

    private JsonObject systemMessage(String workingProjectId) {
        StringBuilder prompt = new StringBuilder(AiChatService.SYSTEM_PROMPT)
                .append(AGENT_GUIDANCE)
                .append("\n\nTOOLS NEVER DISAPPEAR: the tool list above "
                        + "is complete for the whole session. When a "
                        + "tool call returns an error, the tool is "
                        + "working — the arguments were invalid. Read "
                        + "the error, fix the exact field it names, "
                        + "and resend. Never claim a tool is missing, "
                        + "broken, or unavailable, and never offer to "
                        + "work around a missing tool.");
        if (workingProjectId != null && !workingProjectId.isEmpty()) {
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(workingProjectId);
            if (project != null) {
                prompt.append("\n\nThe user is working on the Sketchware "
                        + "project \"").append(project.name)
                        .append("\" (app \"").append(project.appName)
                        .append("\", package ").append(project.packageName)
                        .append(").");
                // The app's own record of work on this project: the
                // style brief, any unfinished task with the steps
                // already done, and recent task titles. A fresh key or
                // model resumes from this — not from chat memory.
                prompt.append(AgentJournal.brief(workingProjectId));
                // Per-turn snapshot: the bound project's screens as they
                // are right now, so a basic "what's in my project" never
                // has to be answered from conversation memory — which is
                // exactly how the agent once described the previous
                // project after the user switched mid-chat.
                StringBuilder screens = new StringBuilder();
                for (ProjectFileBean activity
                        : SketchwareProjects.activities(workingProjectId)) {
                    if (screens.length() > 0) {
                        screens.append(", ");
                    }
                    screens.append(SketchwareProjects.activityName(activity))
                            .append(" (")
                            .append(SketchwareProjects
                                    .screenBeans(workingProjectId, activity)
                                    .size())
                            .append(" widgets)");
                }
                if (screens.length() > 0) {
                    prompt.append(" Its screens right now: ")
                            .append(screens).append(".");
                }
                // The compact project index (~350 tokens): the ids,
                // events, components, variables and storage keys that
                // exist right now — so a fresh or switched model orients
                // in ONE read instead of 4–5 discovery calls, and never
                // drifts on SharedPreferences keys between screens.
                String index = compactProjectIndex(workingProjectId);
                if (!index.isEmpty()) {
                    prompt.append(index);
                }
                prompt.append(" Widget details are available through your "
                        + "tools. If earlier replies in this conversation "
                        + "describe a different project or different "
                        + "screens, the user switched projects since; trust "
                        + "this snapshot and your tools over them.");
            }
        } else {
            prompt.append("\n\nNo working project is chosen right now. If "
                    + "the user asks about any project's screens or "
                    + "widgets, do not answer from earlier conversation: "
                    + "ask them to choose the project with the \"Working "
                    + "on\" card at the top of the chat first.");
        }
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", prompt.toString());
        // Exactly what the model was told — the instructions, the
        // guidance and the project snapshot it was briefed with. If the
        // agent behaves strangely, this is where you see whether it was
        // ever told the thing it missed.
        JsonObject briefed = new JsonObject();
        briefed.addProperty("length_chars", prompt.length());
        briefed.addProperty("prompt",
                AgentJournal.cap(prompt.toString(), 12000));
        AgentJournal.record(workingProjectId == null ? ""
                : workingProjectId, "system_prompt", briefed);
        return system;
    }

    private static String formatHistoryContent(ChatMessage message) {
        if (message == null || message.text == null) {
            return "";
        }
        if (message.role != ChatMessage.Role.ASSISTANT
                || message.openUiState == null
                || message.openUiState.size() == 0) {
            return message.text;
        }
        StringBuilder pairs = new StringBuilder();
        for (java.util.Map.Entry<String, JsonElement> e
                : message.openUiState.entrySet()) {
            JsonElement val = e.getValue();
            if (val == null || val.isJsonNull()) {
                continue;
            }
            if (e.getKey().startsWith("seg_") && val.isJsonObject()) {
                for (java.util.Map.Entry<String, JsonElement> inner
                        : val.getAsJsonObject().entrySet()) {
                    if (inner.getValue() == null || inner.getValue().isJsonNull()) {
                        continue;
                    }
                    if (pairs.length() > 0) {
                        pairs.append(", ");
                    }
                    pairs.append('$').append(inner.getKey()).append('=')
                            .append(inner.getValue().toString());
                }
            } else {
                if (pairs.length() > 0) {
                    pairs.append(", ");
                }
                pairs.append('$').append(e.getKey()).append('=')
                        .append(val.toString());
            }
        }
        if (pairs.length() == 0) {
            return message.text;
        }
        return message.text + "\n[OpenUI state: " + pairs + "]";
    }

    /** Delivers a callback on the main thread. */
    private void dispatch(Listener listener, Runnable action) {
        // The loop runs on the background executor; AgentFragment's
        // listener posts to the UI itself.
        action.run();
    }
}
