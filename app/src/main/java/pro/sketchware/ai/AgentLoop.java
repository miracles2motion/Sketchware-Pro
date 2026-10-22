package pro.sketchware.ai;

import android.content.Context;

import com.besome.sketch.beans.ProjectFileBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
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
    /** How many segments one user message may run, at most. */
    private static final int MAX_SEGMENTS = 6;

    private static final String AGENT_GUIDANCE =
            // Design first — and it is first in the brief for a reason:
            // told politely further down, it was skipped every time.
            "\n\nDESIGN BEFORE YOU BUILD (the most important rule here). "
                    + "When the user asks for an app, a screen, or any "
                    + "substantial feature, do NOT start creating — the "
                    + "tools will refuse. First: (1) ask how it should "
                    + "look with ask_choice, offering two to four WHOLE "
                    + "directions in the user's own terms (each one a "
                    + "complete look: colours, type, shapes, mood — not a "
                    + "label like “modern”); (2) save the agreed look with "
                    + "set_style_brief as headed lines — Audience; Mood; "
                    + "Colours (with hex); Text sizes (title/body/button "
                    + "sp); Buttons and cards (height, corner roundness); "
                    + "Spacing; Do; Avoid; (3) turn the style into "
                    + "COMPONENTS and save them with set_design_system — "
                    + "primary_button, secondary_button, card, list_row, "
                    + "input, screen, title, body, each with the exact "
                    + "measurements every instance uses (height, "
                    + "background, text size, text colour, padding, "
                    + "margin, corner radius, elevation). Propose the "
                    + "whole set yourself from the style and show it to "
                    + "the user; do not interview them property by "
                    + "property. Corner radius and elevation are "
                    + "BLOCKS, not properties: when you build a screen, "
                    + "add them to its initializeLogic — "
                    + "set_corner_radius {target, value, color} for any "
                    + "view, set_card_radius / set_card_elevation / "
                    + "set_card_color for a CardView, set_elevation "
                    + "{target, value} — so a card is round on the "
                    + "device, not only in the plan; (4) write the "
                    + "implementation plan — the screens by name, what is "
                    + "on each, what data it keeps and where, what each "
                    + "button does, the order you will build it — save it "
                    + "with set_plan, then tell the user the plan in chat "
                    + "and let them change it; (5) only then create the "
                    + "project and build. From then on, build EVERY "
                    + "screen out of the saved components by name — a "
                    + "primary_button is the same button on every screen, "
                    + "so never invent fresh measurements per screen. If "
                    + "the user asks to change one component, use "
                    + "edit_design_token for that one thing and offer to "
                    + "update screens already built; never restyle them "
                    + "on your own. If the user says “you decide” "
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
            + "and color \"@color/<name>\" or \"?attr/<name>\". Use "
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
            + "keep to it afterwards."
            + "\n\nORDER OF WORK for a new app or substantial build: style "
            + "first, plan second, building third. If the project has no "
            + "style brief, ask what look and feel the user wants; if "
            + "they don't know, propose one yourself in plain words, "
            + "save it when they accept (or tell you to decide), then "
            + "agree the implementation plan and save it with set_plan "
            + "BEFORE building. Save both even when no project is "
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
        final String[] journalProject = {workingProjectId};
        final boolean[] taskClosed = {false};
        final boolean[] previewVisionDisabled = {false};
        List<JsonObject> messages = new ArrayList<>();
        messages.add(systemMessage(workingProjectId));
        int kept = 0;
        int imageTurnsRemaining = 3;
        for (int i = history.size() - 1; i >= 0 && kept < 40; i--) {
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
                entry.addProperty("content", message.text);
            }
            messages.add(1, entry);
            kept++;
        }

        int calls = 0;
        String lastFailedCall = "";
        int sameFailure = 0;
        final int[] segment = {1};
        final boolean[] rollover = {false};
        // Fingerprints refused as "already done" in this turn: a
        // second request for the same change is the user asking for
        // it on purpose, so it is let through.
        final java.util.Set<String> insisted = new java.util.HashSet<>();
        while (!cancelled) {
            AiChatService.AgentReply reply = null;
            long requestedAt = System.currentTimeMillis();
            for (int attempt = 0; ; attempt++) {
                requestedAt = System.currentTimeMillis();
                try {
                    reply = service.agentTurnSync(baseUrl, apiKey, model,
                            messages, AgentTools.definitions());
                    break;
                } catch (AiChatService.AgentRequestException e) {
                    if (!cancelled && !previewVisionDisabled[0]
                            && AiChatService.isVisionUnsupported(e.error)
                            && removePreviewInspectionMessages(messages)) {
                        previewVisionDisabled[0] = true;
                        continue;
                    }
                    int code = e.error.httpCode;
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
                    closeTask(journalProject[0], false,
                            cancelled ? "you stopped it" : stopReason(e.error),
                            taskClosed, listener);
                    if (!cancelled) {
                        dispatch(listener,
                                () -> listener.onError(e.error));
                    }
                    return;
                }
            }
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
                    closeTask(journalProject[0], false,
                            "the model returned an empty reply",
                            taskClosed, listener);
                    dispatch(listener, () -> listener.onError(new AiChatService.AiError(
                            AiChatService.ErrorKind.EMPTY, 200, "empty reply")));
                } else {
                    closeTask(journalProject[0], true, "", taskClosed,
                            listener);
                    String thinking = reply.thinking == null ? "" : reply.thinking;
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
                    String label = proposalLabel(call);
                    dispatch(listener, () -> listener.onToolNote(label));
                    result = proposeAndExecute(call, session, listener,
                            journalProject[0], insisted);
                    if (cancelled) {
                        closeTask(journalProject[0], false, "you stopped it",
                                taskClosed, listener);
                        return;
                    }
                    if (result.has("bind_project")) {
                        String previousProject = journalProject[0];
                        workingProjectId = result.get("bind_project").getAsString();
                        journalProject[0] = workingProjectId;
                        // The task follows the work: whatever the turn
                        // opened under the old project closes there,
                        // and the created project gets the task — and
                        // any style brief agreed along the way — so
                        // its log is never empty after an interruption.
                        AgentJournal.endTurn(previousProject, true, "");
                        AgentJournal.beginTurn(workingProjectId, lastUserText);
                        AgentJournal.moveStyleBriefIfAgreedSince(
                                previousProject, workingProjectId,
                                turnStartedAt);
                        AgentJournal.movePlanIfAgreedSince(
                                previousProject, workingProjectId,
                                turnStartedAt);
                        // Style/plan agreed before any project was
                        // chosen become this project's own.
                        AgentJournal.depositDrafts(workingProjectId);
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
                    result = AgentTools.dispatch(call.name, call.arguments, session);
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
                        String stopText = "The agent stopped: the same step "
                                + "failed twice (" + call.name + ")."
                                + (reason.isEmpty() ? ""
                                        : " Last problem: " + reason)
                                + " Tell me how you'd like to proceed.";
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
                JsonObject toolMessage = new JsonObject();
                toolMessage.addProperty("role", "tool");
                toolMessage.addProperty("tool_call_id", call.id);
                toolMessage.addProperty("content", result.toString());
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
        result.addProperty("message",
                "The user picked this colour themselves in Sketchware's "
                        + "colour picker. Use the hex exactly, and propose "
                        + "the change on an approval card right away — the "
                        + "card is the confirmation, so do not ask again "
                        + "in chat first.");
        return result;
    }

    /**
     * The brainstorming question: named options, answered by the user.
     * A dismissed question is not a refusal to answer — the model is
     * told to propose one itself and carry on, because a conversation
     * that stops to repeat the question is worse than a decided one.
     */
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
                                         java.util.Set<String> insisted) {
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
            // under wherever the turn happened to start.
            String created = result.get("bind_project").getAsString();
            if (!created.isEmpty()) {
                journal = created;
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

    private JsonObject systemMessage(String workingProjectId) {
        StringBuilder prompt = new StringBuilder(AiChatService.SYSTEM_PROMPT)
                .append(AGENT_GUIDANCE);
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

    /** Delivers a callback on the main thread. */
    private void dispatch(Listener listener, Runnable action) {
        // The loop runs on the background executor; AgentFragment's
        // listener posts to the UI itself.
        action.run();
    }
}
