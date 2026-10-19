package pro.sketchware.ai;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import a.a.a.eC;
import com.besome.sketch.beans.ProjectFileBean;

/**
 * The agent's toolbox. Every tool is a validated JSON-form contract: the
 * model proposes a call, this class validates it against the schema it
 * declared, and only then does app code run.
 *
 * <p>Read-only tools execute immediately through {@link #dispatch}.
 * Mutating tools never execute there: {@link #propose} validates and
 * describes the change in plain words, and {@link #execute} runs it
 * only after the user has approved that description. That split is the
 * core safety rule of the agent — model proposes, user decides, app
 * executes.
 */
public final class AgentTools {

    /** A tool call the model proposed that must never reach real code. */
    public static final class ToolException extends Exception {
        /** Model-facing error code, e.g. {@code E_NO_PROJECT}. */
        public final String code;

        ToolException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Everything a tool needs besides its arguments. */
    public static final class Session {
        public final Context context;
        public final String workingProjectId;
        /** The Settings kill switch for any project change. */
        public final boolean allowModify;

        public Session(Context context, String workingProjectId,
                       boolean allowModify) {
            this.context = context;
            this.workingProjectId = workingProjectId;
            this.allowModify = allowModify;
        }
    }

    /** One callable tool. */
    public interface Tool {
        String name();

        String description();

        JsonObject parameters();

        /** Activity lines the user sees while this tool runs. */
        String activityLabel(JsonObject args);

        /** True when the tool changes something and needs approval. */
        default boolean mutates() {
            return false;
        }

        /**
         * True when the tool pauses the turn for the user to do
         * something in the app's own UI (e.g. pick a colour). The loop
         * routes these to the listener instead of {@link #run}.
         */
        default boolean interactive() {
            return false;
        }

        /** Read-only tools: validate and run in one step. */
        default JsonObject run(Session session, JsonObject args)
                throws ToolException {
            throw new ToolException("E_INTERNAL", "Tool cannot run directly.");
        }

        /** Mutating tools: validate and describe, without executing. */
        default AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            throw new ToolException("E_INTERNAL", "Tool cannot propose.");
        }

        /** Mutating tools: perform an approved proposal and Verify. */
        default JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            throw new ToolException("E_INTERNAL", "Tool cannot execute.");
        }
    }

    /** The outcome of asking a mutating tool to propose. */
    public static final class ProposalResult {
        public AgentProposal proposal;
        public JsonObject error;

        static ProposalResult of(AgentProposal proposal) {
            ProposalResult result = new ProposalResult();
            result.proposal = proposal;
            return result;
        }

        static ProposalResult failed(JsonObject error) {
            ProposalResult result = new ProposalResult();
            result.error = error;
            return result;
        }
    }

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    static {
        register(new ProjectSummary());
        register(new ListActivities());
        register(new ListResources());
        register(new GetScreen());
        register(new AskColor());
        register(new CreateProject());
        register(new CreateActivity());
        register(new CreateCustomView());
        register(new DeleteActivity());
        register(new EditActivity());
        register(new EditScreen());
        register(new SetEventHandler());
        register(new GetEventLogic());
        register(new EditEventBlock());
        register(new CreateVariable());
        register(new CreateComponent());
        register(new EditProjectTheme());
        register(new BuildProject());
    }

    private static void register(Tool tool) {
        TOOLS.put(tool.name(), tool);
    }

    /** The {@code tools} array for one agent request. */
    public static JsonArray definitions() {
        JsonArray tools = new JsonArray();
        for (Tool tool : TOOLS.values()) {
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.parameters());
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "function");
            entry.add("function", function);
            tools.add(entry);
        }
        return tools;
    }

    /** True when executing this tool would change something. */
    public static boolean isMutation(String name) {
        Tool tool = TOOLS.get(name);
        return tool != null && tool.mutates();
    }

    /** True when the named tool pauses for an in-app user action. */
    public static boolean isInteractive(String name) {
        Tool tool = TOOLS.get(name);
        return tool != null && tool.interactive();
    }

    /** The human activity label for a proposed call. */
    public static String labelFor(String name, String argumentsJson) {
        Tool tool = TOOLS.get(name);
        if (tool == null) {
            return "Working…";
        }
        try {
            return tool.activityLabel(parseArgs(argumentsJson));
        } catch (Exception ignored) {
            return "Working…";
        }
    }

    /**
     * Validates and runs one read-only call, returning the result object.
     * Mutating tools are refused here by construction — they must go
     * through {@link #propose} and user approval first.
     */
    public static JsonObject dispatch(String name, String argumentsJson,
                                      Session session) {
        Tool tool = TOOLS.get(name);
        if (tool == null) {
            return error("E_UNKNOWN_TOOL", "There is no tool named " + name + ".");
        }
        if (tool.mutates()) {
            return error("E_INTERNAL", "That change must be proposed first.");
        }
        JsonObject args;
        try {
            args = parseArgs(argumentsJson);
        } catch (Exception e) {
            return error("E_BAD_ARGS", "The tool arguments were not valid JSON.");
        }
        try {
            return tool.run(session, args);
        } catch (ToolException e) {
            return error(e.code, e.getMessage());
        } catch (Exception e) {
            return error("E_TOOL_FAILED",
                    "The tool failed: " + String.valueOf(e.getMessage()));
        }
    }

    /**
     * Asks a mutating tool to validate and describe a change. Nothing
     * executes here; the description is what the approval card shows.
     */
    public static ProposalResult propose(String name, String argumentsJson,
                                         Session session) {
        Tool tool = TOOLS.get(name);
        if (tool == null || !tool.mutates()) {
            return ProposalResult.failed(error("E_UNKNOWN_TOOL",
                    "There is no change-making tool named " + name + "."));
        }
        if (!session.allowModify) {
            return ProposalResult.failed(error("E_MODIFY_OFF",
                    "The user has not allowed the agent to modify projects. "
                            + "Ask them to open Agent settings and turn on "
                            + "\"Allow the agent to modify projects\" first."));
        }
        JsonObject args;
        try {
            args = parseArgs(argumentsJson);
        } catch (Exception e) {
            return ProposalResult.failed(error("E_BAD_ARGS",
                    "The tool arguments were not valid JSON."));
        }
        try {
            return ProposalResult.of(tool.propose(session, args));
        } catch (ToolException e) {
            return ProposalResult.failed(error(e.code, e.getMessage()));
        } catch (Exception e) {
            return ProposalResult.failed(error("E_TOOL_FAILED",
                    "The tool failed: " + String.valueOf(e.getMessage())));
        }
    }

    /** Runs an approved proposal and returns the verified result. */
    public static JsonObject execute(AgentProposal proposal, Session session) {
        Tool tool = TOOLS.get(proposal.tool);
        if (tool == null) {
            return error("E_INTERNAL", "The tool no longer exists.");
        }
        try {
            return tool.execute(session, proposal);
        } catch (ToolException e) {
            return error(e.code, e.getMessage());
        } catch (Exception e) {
            return error("E_TOOL_FAILED",
                    "The change failed: " + String.valueOf(e.getMessage()));
        }
    }

    private static JsonObject parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.trim().isEmpty()) {
            return new JsonObject();
        }
        return JsonParser.parseString(argumentsJson).getAsJsonObject();
    }

    private static JsonObject error(String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("error", code);
        result.addProperty("message", message == null ? "" : message);
        return result;
    }

    // -- shared helpers ---------------------------------------------------

    private static String requiredProject(String workingProjectId)
            throws ToolException {
        if (workingProjectId == null || workingProjectId.isEmpty()) {
            throw new ToolException("E_NO_PROJECT",
                    "No working project is chosen. Ask the user to tap the "
                            + "\"Working on\" card at the top of the chat and "
                            + "pick a project, then try again.");
        }
        if (SketchwareProjects.project(workingProjectId) == null) {
            throw new ToolException("E_NO_PROJECT",
                    "The working project no longer exists. Ask the user to "
                            + "pick a project with the \"Working on\" card.");
        }
        return workingProjectId;
    }

    /** Declared variables (name + kind) for one screen's summary. */
    private static JsonArray variablesJson(
            SketchwareProjects.LogicSummary logic) {
        return variablesJson(logic.variables, logic.variableTypes);
    }

    /** Name+kind entries as JSON, capped at 24. */
    private static JsonArray variablesJson(
            List<String> names,
            java.util.LinkedHashMap<String, String> types) {
        JsonArray array = new JsonArray();
        for (Map.Entry<String, String> variable
                : types.entrySet()) {
            if (array.size() >= 24) {
                break;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", variable.getKey());
            entry.addProperty("type", variable.getValue());
            array.add(entry);
        }
        return array;
    }

    /** Names as a JSON array, capped so a huge project stays one read. */
    private static JsonArray namesJson(List<String> names) {
        JsonArray array = new JsonArray();
        for (String name : names) {
            if (array.size() >= 24) {
                break;
            }
            array.add(name);
        }
        return array;
    }

    private static String stringArg(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return "";
        }
        return args.get(key).getAsString().trim();
    }

    private static boolean booleanArg(JsonObject args, String key) {
        try {
            return args.has(key) && !args.get(key).isJsonNull()
                    && args.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonArray intArrayOfProperty(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject parameters(JsonObject properties, String... required) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        if (required.length > 0) {
            JsonArray names = new JsonArray();
            for (String name : required) {
                names.add(name);
            }
            parameters.add("required", names);
        }
        return parameters;
    }

    // -- the read-only tools ----------------------------------------------

    /** project_summary: what the working project is. */
    private static final class ProjectSummary implements Tool {
        @Override
        public String name() {
            return "project_summary";
        }

        @Override
        public String description() {
            return "Summarizes the working project: its name, app name, "
                    + "package, and screens with widget counts.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Read the project summary";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject result = new JsonObject();
            result.addProperty("project", project.name);
            result.addProperty("app_name", project.appName);
            result.addProperty("package", project.packageName);
            JsonArray screens = new JsonArray();
            for (ProjectFileBean activity : SketchwareProjects.activities(scId)) {
                JsonObject screen = new JsonObject();
                screen.addProperty("activity",
                        SketchwareProjects.activityName(activity));
                screen.addProperty("widgets",
                        SketchwareProjects.screenBeans(scId, activity).size());
                SketchwareProjects.LogicSummary logic =
                        SketchwareProjects.logicSummary(scId, activity);
                screen.add("components", namesJson(logic.components));
                screen.add("variables", variablesJson(logic));
                screen.add("lists", variablesJson(logic.lists,
                        logic.listTypes));
                screen.add("more_blocks", namesJson(logic.moreBlocks));
                screen.addProperty("events", logic.events);
                screens.add(screen);
            }
            result.add("screens", screens);
            JsonArray customViews = new JsonArray();
            for (ProjectFileBean view : SketchwareProjects.customViews(scId)) {
                JsonObject customView = new JsonObject();
                customView.addProperty("name", view.fileName);
                customView.addProperty("widgets",
                        SketchwareProjects.screenBeans(scId, view).size());
                customViews.add(customView);
            }
            result.add("custom_views", customViews);
            return result;
        }
    }

    /** list_activities: the screens of the working project. */
    private static final class ListActivities implements Tool {
        @Override
        public String name() {
            return "list_activities";
        }

        @Override
        public String description() {
            return "Lists the activities (screens) of the working project.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Listed the project's screens";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            JsonArray activities = new JsonArray();
            for (ProjectFileBean activity : SketchwareProjects.activities(scId)) {
                activities.add(SketchwareProjects.activityName(activity));
            }
            JsonArray customViews = new JsonArray();
            for (ProjectFileBean view : SketchwareProjects.customViews(scId)) {
                customViews.add(view.fileName);
            }
            JsonObject result = new JsonObject();
            result.add("activities", activities);
            result.add("custom_views", customViews);
            return result;
        }
    }

    /** list_resources: the project's image, sound and font names. */
    private static final class ListResources implements Tool {
        @Override
        public String name() {
            return "list_resources";
        }

        @Override
        public String description() {
            return "Lists the working project's resources by name: "
                    + "images (for set_image and set_background_image), "
                    + "sounds, and fonts.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Listed the project's resources";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            SketchwareProjects.Resources resources =
                    SketchwareProjects.resources(scId);
            JsonObject result = new JsonObject();
            result.add("images", namesJson(resources.images));
            result.add("sounds", namesJson(resources.sounds));
            result.add("fonts", namesJson(resources.fonts));
            return result;
        }
    }

    /** get_screen: one screen's widgets, condensed. */
    private static final class GetScreen implements Tool {
        @Override
        public String name() {
            return "get_screen";
        }

        @Override
        public String description() {
            return "Reads one screen of the working project as an indented "
                    + "widget tree (nesting = containment, in on-screen "
                    + "order): id, type, visible text, background colour, "
                    + "and sizes, plus the screen's events and how many "
                    + "blocks each already has.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The activity name, e.g. MainActivity"));
            return AgentTools.parameters(properties, "activity");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String activity = stringArg(args, "activity");
            return activity.isEmpty() ? "Looked at a screen"
                    : "Looked at " + activity;
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "activity");
            ProjectFileBean activity =
                    SketchwareProjects.findScreen(scId, asked);
            if (activity == null) {
                List<ProjectFileBean> available =
                        SketchwareProjects.activities(scId);
                StringBuilder names = new StringBuilder();
                for (ProjectFileBean candidate : available) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(SketchwareProjects.activityName(candidate));
                }
                for (ProjectFileBean candidate
                        : SketchwareProjects.customViews(scId)) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(candidate.fileName)
                            .append(" (custom view)");
                }
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no screen named \"" + asked + "\". "
                                + "Available screens: " + names);
            }
            JsonObject result = new JsonObject();
            result.addProperty("activity",
                    SketchwareProjects.screenName(activity));
            result.addProperty("kind",
                    activity.fileType
                            == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY
                            ? "activity" : "custom_view");
            result.addProperty("root",
                    SketchwareProjects.rootDescription(scId, activity));
            JsonArray widgets = new JsonArray();
            for (String line : SketchwareProjects.screenLines(scId, activity, 120)) {
                widgets.add(line);
            }
            result.add("widgets", widgets);
            JsonArray events = new JsonArray();
            for (String line : SketchwareProjects.eventLines(scId, activity)) {
                events.add(line);
            }
            result.add("events", events);
            return result;
        }
    }

    /** edit_screen: small validated operations on one screen. */
    private static final class EditScreen implements Tool {
        @Override
        public String name() {
            return "edit_screen";
        }

        @Override
        public String description() {
            return "Proposes edits to one screen of the working project as "
                    + "a list of small operations. When a screen is empty, "
                    + "the app first adds the screen's main layout (a "
                    + "full-screen vertical LinearLayout) automatically "
                    + "and puts everything you add inside it — that is the "
                    + "standard structure; to make a scrolling page, add a "
                    + "ScrollView first and the main layout goes inside "
                    + "it; to use a different main container, add it first "
                    + "yourself. Operations run in order "
                    + "and each is checked against the screen as the "
                    + "earlier ones leave it. Ops: add_widget "
                    + "{type,parent?,id?,text?,background?} (types: "
                    + "LinearLayout, RelativeLayout, ScrollView, "
                    + "HorizontalScrollView, CardView, RadioGroup, "
                    + "TextInputLayout, SwipeRefreshLayout, TabLayout, "
                    + "ViewPager, BottomNavigationView, Button, "
                    + "MaterialButton, TextView, EditText, "
                    + "AutoCompleteTextView, ImageView, CircleImageView, "
                    + "CheckBox, RadioButton, Switch, SeekBar, RatingBar, "
                    + "ProgressBar, Spinner, ListView, GridView, "
                    + "RecyclerView, SearchView, WebView, VideoView, "
                    + "CalendarView, DatePicker, TimePicker, AnalogClock, "
                    + "DigitalClock and more; parent defaults to the "
                    + "screen's main container — the full-screen layout "
                    + "everything else lives in — when the screen has "
                    + "one, so new widgets land where they are visible; "
                    + "pass parent \"root\" only to deliberately place a "
                    + "widget at the screen's top level, and expect a "
                    + "placement warning if a widget would end up "
                    + "off-screen; omit id to let the app name it), "
                    + "set_text "
                    + "{id,text}, set_hint {id,hint} (text inputs only), "
                    + "set_background {id,color}, set_text_color {id,color}, "
                    + "set_text_size {id,size}, set_size {id,width?,height?} "
                    + "(\"match_parent\", \"wrap_content\" or dp), "
                    + "set_orientation {id,orientation} (vertical/horizontal), "
                    + "set_padding/set_margin {id,dp? or left/top/right/"
                    + "bottom}, set_gravity {id,gravity,layout_gravity?}, "
                    + "set_weight {id,weight}, set_image {id,image} "
                    + "(ImageView; image is a project picture name), "
                    + "set_scale_type {id,scale_type}, set_background_image "
                    + "{id,image}, set_checked {id,checked}, set_enabled "
                    + "{id,enabled}, set_text_style {id,style} (normal, bold, "
                    + "italic, bold_italic), set_input_type {id,input_type} "
                    + "(text, number, phone, password, number_decimal), "
                    + "set_font {id,font} (a font from list_resources, or "
                    + "default_font), set_list_item_view {id,custom_view} "
                    + "(a custom view name from list_activities, or \"none\" "
                    + "to clear; for ListView, GridView, RecyclerView, "
                    + "ViewPager), "
                    + "set_progress {id,progress?,max?} (ProgressBar/SeekBar), "
                    + "set_indeterminate {id,indeterminate}, set_alpha "
                    + "{id,alpha} (0 to 1), set_clickable {id,clickable}, "
                    + "set_root {background?,orientation?} (the screen "
                    + "itself: background colour and, for a LinearLayout "
                    + "root, how it stacks), "
                    + "remove_widget {id} (removes what is inside it too), "
                    + "move_widget {id,parent} (to the end of a layout), "
                    + "move_widget {id,before} / {id,after} (next to a "
                    + "sibling — this is how order inside one layout "
                    + "changes; never remove and re-add widgets to "
                    + "reorder them, that destroys their text and "
                    + "settings). "
                    + "Colours are #RRGGBB or the project tokens @primary, "
                    + "@primary_dark, @accent — including lighter tints "
                    + "computed from them here: @primary_tint, "
                    + "@primary_light, @primary_pale (and the @accent_ "
                    + "equivalents). Use a tint token, never a guessed hex, "
                    + "when the user asks for a shade of a theme colour. "
                    + "Example: [{\"op\":"
                    + "\"add_widget\",\"type\":\"Button\",\"text\":\"Save\"},"
                    + "{\"op\":\"set_background\",\"id\":\"button1\","
                    + "\"color\":\"@primary\"}]. At most "
                    + AgentEdits.MAX_OPS + " operations per call. The user "
                    + "must approve before anything changes.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen to edit, e.g. MainActivity"));
            JsonObject opsSchema = new JsonObject();
            opsSchema.addProperty("type", "array");
            opsSchema.addProperty("description",
                    "The operations, in order");
            JsonObject item = new JsonObject();
            item.addProperty("type", "object");
            opsSchema.add("items", item);
            properties.add("ops", opsSchema);
            return AgentTools.parameters(properties, "activity", "ops");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to edit " + stringArg(args, "activity");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            LocatedScreen located = locate(session, args);
            JsonArray ops = args.has("ops") && args.get("ops").isJsonArray()
                    ? args.getAsJsonArray("ops") : null;
            if (ops == null || ops.size() == 0
                    || ops.size() > AgentEdits.MAX_OPS) {
                throw new ToolException("E_OPS_LIMIT",
                        "Give between 1 and " + AgentEdits.MAX_OPS
                                + " operations per edit.");
            }
            AgentEdits.Outcome outcome = AgentEdits.simulate(
                    located.scId, located.beans, ops, false,
                    located.rootParentType);
            requireFabIfAsked(outcome.frozenOps, located);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(located.scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", located.activity.fileName);
            clean.addProperty("revision",
                    AgentEdits.revisionOf(located.beans));
            clean.add("ops", outcome.frozenOps);

            StringBuilder body = new StringBuilder();
            int shown = Math.min(outcome.lines.size(), 8);
            for (int i = 0; i < shown; i++) {
                if (body.length() > 0) {
                    body.append("\n");
                }
                body.append("• ").append(outcome.lines.get(i));
            }
            if (outcome.lines.size() > shown) {
                body.append("\n…and ")
                        .append(outcome.lines.size() - shown)
                        .append(" more changes");
            }
            for (String warning : outcome.warnings) {
                body.append("\n⚠ ").append(warning);
            }
            return new AgentProposal(name(), clean, located.scId,
                    "Edit " + SketchwareProjects.activityName(located.activity)
                            + " in " + project.name,
                    body.toString(),
                    "If this screen is open in the editor, close it before "
                            + "approving — the editor would overwrite the "
                            + "change when it saves. Exact operations:\n"
                            + new com.google.gson.GsonBuilder()
                                    .setPrettyPrinting().create()
                                    .toJson(outcome.frozenOps));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            LocatedScreen located;
            try {
                JsonObject args = new JsonObject();
                args.addProperty("activity",
                        stringArg(proposal.args, "activity"));
                located = locate(new Session(session.context,
                        proposal.workingProjectId, session.allowModify), args);
            } catch (ToolException e) {
                throw e;
            }
            ArrayList<com.besome.sketch.beans.ViewBean> current = located.beans;
            String expected = stringArg(proposal.args, "revision");
            if (!expected.isEmpty()
                    && !expected.equals(AgentEdits.revisionOf(current))) {
                throw new ToolException("E_SCREEN_CHANGED",
                        "The screen changed since this edit was proposed, "
                                + "so it was not applied. Look at the screen "
                                + "again and propose a fresh edit.");
            }
            JsonArray ops = proposal.args.getAsJsonArray("ops");
            AgentEdits.Outcome outcome = AgentEdits.simulate(
                    located.scId, current, ops, true, located.rootParentType);
            requireFabIfAsked(outcome.frozenOps, located);
            // The pre-edit screen, snapshotted before anything native is
            // touched: the editor's own delete path mutates the live bean
            // list, so the live list alone is not a rollback copy.
            ArrayList<com.besome.sketch.beans.ViewBean> previous =
                    AgentEdits.deepCopy(current);
            java.util.Map<String, com.besome.sketch.beans.ViewBean>
                    currentById = new java.util.HashMap<>();
            for (com.besome.sketch.beans.ViewBean bean : current) {
                currentById.put(bean.id, bean);
            }
            java.util.Set<String> outcomeIds = new java.util.HashSet<>();
            for (com.besome.sketch.beans.ViewBean bean : outcome.beans) {
                outcomeIds.add(bean.id);
            }
            pro.sketchware.managers.inject.InjectRootLayoutManager.Root
                    previousRoot = null;
            com.besome.sketch.beans.ViewBean previousFab = null;
            try {
                SketchwareActions.backupProjectOnce(located.scId);
                // Removed widgets go through the editor's own delete,
                // which also removes their events and block references;
                // the outcome list below then replaces what remains.
                for (com.besome.sketch.beans.ViewBean bean : current) {
                    if (!outcomeIds.contains(bean.id)) {
                        SketchwareActions.deleteWidgetLikeEditor(
                                located.scId, located.activity, bean);
                    }
                }
                SketchwareActions.saveScreen(located.scId, located.activity,
                        outcome.beans);
                // Screen-level changes ride in set_root ops; they are
                // written through the root layout manager the editor
                // itself uses, with the previous root kept for rollback.
                java.util.Map<String, String> rootUpdates =
                        new java.util.LinkedHashMap<>();
                for (com.google.gson.JsonElement element
                        : outcome.frozenOps) {
                    JsonObject op = element.getAsJsonObject();
                    if (!"set_root".equals(op.has("op")
                            ? op.get("op").getAsString() : "")) {
                        continue;
                    }
                    if (op.has("background_hex")) {
                        rootUpdates.put("android:background",
                                op.get("background_hex").getAsString());
                    }
                    if (op.has("orientation")) {
                        rootUpdates.put("android:orientation",
                                op.get("orientation").getAsString());
                    }
                }
                if (!rootUpdates.isEmpty()) {
                    previousRoot = SketchwareActions.setRootAttributes(
                            located.scId, located.activity, rootUpdates);
                }
                // Floating-button changes ride in set_fab ops; they go
                // to the activity's own fab bean in the data manager.
                for (com.google.gson.JsonElement element
                        : outcome.frozenOps) {
                    JsonObject op = element.getAsJsonObject();
                    if (!"set_fab".equals(op.has("op")
                            ? op.get("op").getAsString() : "")) {
                        continue;
                    }
                    String icon = op.has("icon")
                            ? op.get("icon").getAsString() : null;
                    Integer background = op.has("background_hex")
                            ? Integer.valueOf((int) Long.parseLong(
                                    op.get("background_hex").getAsString()
                                            .substring(1), 16))
                            : null;
                    com.besome.sketch.beans.ViewBean snapshot =
                            SketchwareActions.setFab(located.scId,
                                    located.activity, icon, background);
                    if (previousFab == null) {
                        previousFab = snapshot;
                    }
                }
                // A Button the editor adds always gets its empty onClick
                // event registered; mirror that for added Buttons.
                // Custom views have no logic file, so no event is
                // registered for them.
                if (located.activity.fileType
                        == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY)
                for (com.besome.sketch.beans.ViewBean bean : outcome.beans) {
                    if (!currentById.containsKey(bean.id)
                            && bean.type
                                    == com.besome.sketch.beans.ViewBean
                                            .VIEW_TYPE_WIDGET_BUTTON) {
                        SketchwareActions.registerButtonClickEvent(
                                located.scId, located.activity, bean);
                    }
                }
                SketchwareActions.commitProject(located.scId);
            } catch (Exception e) {
                // The data manager holds the screen in memory as well as on
                // disk. If the commit failed, put the previous screen back
                // in memory too — otherwise later reads (and the editor,
                // in this same process) would see an edit that never saved.
                try {
                    SketchwareActions.saveScreen(located.scId,
                            located.activity, previous);
                    SketchwareActions.restoreRoot(located.scId,
                            located.activity, previousRoot);
                    SketchwareActions.restoreFab(located.scId,
                            located.activity, previousFab);
                } catch (Exception ignored) {
                    // Best effort: the save failure is the error to report.
                }
                throw new ToolException("E_SAVE_FAILED",
                        "Saving the screen failed: "
                                + String.valueOf(e.getMessage()));
            }
            ArrayList<com.besome.sketch.beans.ViewBean> readBack =
                    SketchwareProjects.screenBeans(located.scId, located.activity);
            // screenBeans presents the editor's normalized order, so the
            // approved outcome is compared in that same order.
            if (!AgentEdits.canonical(readBack)
                    .equals(AgentEdits.canonical(eC.a(outcome.beans)))) {
                throw new ToolException("E_VERIFY_FAILED",
                        "The saved screen does not match the approved edit, "
                                + "so treat the edit as not applied.");
            }
            JsonObject result = new JsonObject();
            result.addProperty("edited_activity",
                    SketchwareProjects.screenName(located.activity));
            result.addProperty("changes", outcome.lines.size());
            if (!outcome.warnings.isEmpty()) {
                JsonArray warnings = new JsonArray();
                for (String warning : outcome.warnings) {
                    warnings.add(warning);
                }
                result.add("warnings", warnings);
            }
            result.addProperty("verified",
                    SketchwareProjects.screenName(located.activity)
                            + " now has " + readBack.size() + " widgets."
                            + (outcome.warnings.isEmpty() ? ""
                                    : " Placement warnings: "
                                            + outcome.warnings.size()
                                            + " — see \"warnings\"; fix "
                                            + "them with a follow-up edit "
                                            + "instead of leaving a widget "
                                            + "off-screen."));
            return result;
        }

        /**
         * The floating button lives outside the widget list, so
         * {@link AgentEdits} cannot know whether it exists; that check
         * belongs to the screen the edit targets.
         */
        private static void requireFabIfAsked(JsonArray frozenOps,
                                              LocatedScreen located)
                throws ToolException {
            boolean asked = false;
            for (com.google.gson.JsonElement element : frozenOps) {
                JsonObject op = element.getAsJsonObject();
                String kind = op.has("op") ? op.get("op").getAsString()
                        : "";
                if (kind.toLowerCase(Locale.ROOT)
                        .replace("_", "").equals("setfab")) {
                    asked = true;
                    break;
                }
            }
            if (!asked) {
                return;
            }
            ProjectFileBean file = located.activity;
            if (file.fileType != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY
                    || !file.hasActivityOption(
                            ProjectFileBean.OPTION_ACTIVITY_FAB)) {
                throw new ToolException("E_NO_FAB",
                        SketchwareProjects.screenName(file)
                                + " has no floating button to change. "
                                + "Turn it on first with edit_activity's "
                                + "floating_button setting, then set its "
                                + "icon in a separate edit.");
            }
        }

        /** The bound project, the screen, and its current beans. */
        private LocatedScreen locate(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "activity");
            ProjectFileBean activity =
                    SketchwareProjects.findScreen(scId, asked);
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no screen named \"" + asked + "\" in "
                                + "the working project.");
            }
            return new LocatedScreen(scId, activity,
                    SketchwareProjects.screenBeans(scId, activity),
                    SketchwareProjects.rootParentType(scId, activity));
        }

        private static final class LocatedScreen {
            final String scId;
            final ProjectFileBean activity;
            final ArrayList<com.besome.sketch.beans.ViewBean> beans;
            final int rootParentType;

            LocatedScreen(String scId, ProjectFileBean activity,
                          ArrayList<com.besome.sketch.beans.ViewBean> beans,
                          int rootParentType) {
                this.scId = scId;
                this.activity = activity;
                this.beans = beans;
                this.rootParentType = rootParentType;
            }
        }
    }

    /** set_event_handler: make a widget (or the screen) do something. */
    private static final class SetEventHandler implements Tool {
        @Override
        public String name() {
            return "set_event_handler";
        }

        @Override
        public String description() {
            return "Proposes behaviour for one event of one screen: when "
                    + "a widget is tapped (or another of its events "
                    + "fires), or when the screen itself runs an "
                    + "activity event like onCreate. The new blocks are "
                    + "APPENDED after whatever the event already does — "
                    + "check get_screen first, which lists each event "
                    + "and how many blocks it already has. Never append "
                    + "a second copy of a block to replace the first: "
                    + "to change or remove one that is already wired, "
                    + "read it with get_event_logic and use "
                    + "edit_event_block. Blocks (1 to "
                    + AgentLogic.MAX_BLOCKS + "): toast {message} "
                    + "(show a short message) or {from_variable} (show "
                    + "a string variable's value), set_text {target,"
                    + "text} (change a text widget's text) or {target,"
                    + "from_variable} (use a string variable's value), "
                    + "set_visibility {target,visible} or {target,state} "
                    + "(visible, gone, invisible), set_enabled {target,"
                    + "enabled}, set_text_color {target,color} (a text "
                    + "widget's text colour, #RRGGBB), set_hint {target,"
                    + "text} (an EditText's hint), set_clickable {target,"
                    + "clickable} (whether taps land), set_image_url "
                    + "{target,url} (load an image from a URL into an "
                    + "ImageView), set_progress {target,value} (a "
                    + "SeekBar's progress), copy_to_clipboard {text}, "
                    + "set_screen_title {text}, open_drawer {} and "
                    + "add_to_list {list,text|value|from_variable}, "
                    + "clear_list {list}, show_list {target,list} "
                    + "(a ListView shows a list of text), "
                    + "put_in_map {variable,key,text|from_variable}, "
                    + "clear_map {variable}, new_map {variable}, and "
                    + "close_drawer {} (the screen's navigation drawer), "
                    + "open_screen {screen} (open another screen of "
                    + "the project; adds an Intent component if the "
                    + "screen has none), "
                    + "load_url {target,url} (a WebView), finish {} "
                    + "(close the screen), "
                    + "set_variable {variable,value} or {variable,"
                    + "from_variable} (assign; value may also be a "
                    + "value expression like {\"text_of\":\"edittext1\"} "
                    + "or {\"map_get\":\"info\",\"key\":\"name\"}), "
                    + "increase {variable} "
                    + "and decrease {variable} (add or subtract 1 from "
                    + "a number variable). Containers hold nested block "
                    + "lists: if {condition,then:[...]}, if_else "
                    + "{condition,then:[...],else:[...]}, repeat "
                    + "{times,blocks:[...]}, forever {blocks:[...]}, and "
                    + "break {} (leave a loop). Conditions and counts "
                    + "are value expressions: {\"var\":\"taps\"}, "
                    + "{\"number\":3}, {\"bool\":true}, calculations "
                    + "{\"op\":\"+\",\"a\":...,\"b\":...} (also - * / %), "
                    + "comparisons {\"op\":\">\",\"a\":...,\"b\":...} "
                    + "(also < and =), and logic {\"op\":\"and\",\"a\":...,"
                    + "\"b\":...} (also or, and {\"op\":\"not\",\"a\":...}); "
                    + "expressions nest inside each other. Values can "
                    + "also read widgets live: {\"text_of\":\"edittext1\"} "
                    + "(a text widget's current text), {\"url_of\":\"webview1\"}, "
                    + "Compare a widget's own value directly in the "
                    + "condition — never create a variable just to "
                    + "hold a widget's text or state. "
                    + "{\"checked\":\"checkbox1\"}, {\"enabled\":\"button1\"}, "
                    + "{\"progress_of\":\"seekbar1\"}, {\"extra\":\"name\"} "
                    + "(text another screen passed this one), "
                    + "{\"list_length\":\"items\"}, {\"item_of\":\"items\","
                    + "\"index\":0}, {\"list_has\":\"items\",\"value\":...}, "
                    + "{\"map_get\":\"info\",\"key\":\"name\"}, "
                    + "{\"map_has\":\"info\",\"key\":\"name\"}; = between texts "
                    + "compares their text. At most "
                    + "8 blocks per list and 24 blocks total per handler. "
                    + "Variable blocks need the "
                    + "variable declared first — project_summary lists "
                    + "each screen's variables with types; declare "
                    + "missing ones with create_variable. Only string "
                    + "variables can provide text. Example: "
                    + "{\"activity\":\"MainActivity\",\"target\":"
                    + "\"button1\",\"event\":\"onClick\",\"blocks\":[{"
                    + "\"block\":\"toast\",\"message\":\"Saved\"},{\"block\":"
                    + "\"set_text\",\"target\":\"textview1\",\"text\":"
                    + "\"Done\"}]}. Use target \"activity\" with an "
                    + "activity event (onCreate, onResume, ...) for "
                    + "things that happen when the screen starts. The "
                    + "user must approve before anything changes.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen, e.g. MainActivity"));
            properties.add("target", stringSchema(
                    "The widget id from get_screen, or \"activity\" "
                            + "for the screen's own events"));
            properties.add("event", stringSchema(
                    "The event, e.g. onClick for a tap, onCreate for "
                            + "when an activity-targeted screen starts"));
            JsonObject blocksSchema = new JsonObject();
            blocksSchema.addProperty("type", "array");
            blocksSchema.addProperty("description",
                    "The blocks to append, in order. Every block, "
                            + "including blocks nested inside "
                            + "then/else/blocks lists, is an object "
                            + "with a \"block\" name from the enum.");
            JsonObject item = new JsonObject();
            item.addProperty("type", "object");
            JsonObject itemProps = new JsonObject();
            JsonObject blockProp = new JsonObject();
            blockProp.addProperty("type", "string");
            JsonArray kinds = new JsonArray();
            for (String kind : new String[]{
                    "toast", "set_text", "set_visibility", "set_enabled",
                    "set_text_color", "set_hint", "set_clickable",
                    "set_image_url", "set_progress", "copy_to_clipboard",
                    "set_screen_title", "open_drawer", "close_drawer",
                    "open_screen", "load_url", "finish", "set_variable",
                    "increase", "decrease", "add_to_list", "clear_list",
                    "show_list", "put_in_map", "clear_map", "new_map",
                    "if", "if_else", "repeat", "forever", "break"}) {
                kinds.add(kind);
            }
            blockProp.add("enum", kinds);
            blockProp.addProperty("description",
                    "What this block does (required)");
            itemProps.add("block", blockProp);
            item.add("properties", itemProps);
            JsonArray required = new JsonArray();
            required.add("block");
            item.add("required", required);
            blocksSchema.add("items", item);
            properties.add("blocks", blocksSchema);
            return AgentTools.parameters(properties,
                    "activity", "target", "event", "blocks");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to wire " + stringArg(args, "activity");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "activity");
            ProjectFileBean activity =
                    SketchwareProjects.findActivity(scId, asked);
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no activity named \"" + asked
                                + "\" in the working project.");
            }
            JsonArray blocks = args.has("blocks")
                    && args.get("blocks").isJsonArray()
                    ? args.getAsJsonArray("blocks") : null;
            AgentLogic.Plan plan = AgentLogic.plan(session.context, scId,
                    activity, stringArg(args, "target"),
                    stringArg(args, "event"), blocks);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", activity.fileName);
            // Freeze the target FORM the executor must re-resolve: for
            // activity events the resolved id is the event name
            // itself, which re-reads as a (nonexistent) widget id.
            clean.addProperty("target", plan.activityEvent
                    ? "activity" : plan.targetId);
            clean.addProperty("event", plan.eventName);
            clean.addProperty("activity_event", plan.activityEvent);
            clean.addProperty("existing", plan.existingCount);
            clean.add("blocks", blocks);

            StringBuilder body = new StringBuilder(plan.header);
            for (String line : plan.lines) {
                body.append("\n• ").append(line);
            }
            if (plan.existingCount > 0) {
                body.append("\n(Runs after the ")
                        .append(plan.existingCount)
                        .append(" block")
                        .append(plan.existingCount == 1 ? "" : "s")
                        .append(" this event already has.)");
            } else if (!plan.eventExists) {
                body.append("\n(This creates the ")
                        .append(plan.eventName).append(" event.)");
            }
            return new AgentProposal(name(), clean, scId,
                    "Wire " + SketchwareProjects.activityName(activity)
                            + " in " + project.name,
                    body.toString(),
                    "If this screen's logic is open in the editor, "
                            + "close it before approving — the editor "
                            + "would overwrite the change when it saves. "
                            + "Exact blocks:\n"
                            + new com.google.gson.GsonBuilder()
                                    .setPrettyPrinting().create()
                                    .toJson(blocks));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            if (!scId.equals(session.workingProjectId)) {
                throw new ToolException("E_PROJECT_CHANGED",
                        "The working project changed since this handler "
                                + "was proposed, so it was not applied.");
            }
            ProjectFileBean activity = SketchwareProjects.findActivity(
                    scId, stringArg(proposal.args, "activity"));
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "That activity no longer exists.");
            }
            // Rebuild the plan from the frozen arguments; the plan
            // re-reads the event, so any drift since the card was
            // shown is caught either as a changed-block count or as
            // a fresh generation proof.
            JsonArray blocks = proposal.args.has("blocks")
                    && proposal.args.get("blocks").isJsonArray()
                    ? proposal.args.getAsJsonArray("blocks") : null;
            AgentLogic.Plan plan = AgentLogic.plan(session.context, scId,
                    activity, stringArg(proposal.args, "target"),
                    stringArg(proposal.args, "event"), blocks);
            if (plan.existingCount
                    != intArg(proposal.args, "existing")) {
                throw new ToolException("E_LOGIC_CHANGED",
                        "This event's blocks changed since the handler "
                                + "was proposed, so it was not applied. "
                                + "Look at the screen again and propose "
                                + "a fresh one.");
            }
            return AgentLogic.apply(session.context, plan);
        }
    }

    /** create_variable: declare one activity variable. */
    private static final class CreateVariable implements Tool {
        /** The editor's own name rule (ZB): a valid identifier. */
        private static final Pattern NAME_PATTERN =
                Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

        @Override
        public String name() {
            return "create_variable";
        }

        @Override
        public String description() {
            return "Proposes declaring one variable on one screen, "
                    + "the way Sketchware's own logic editor adds it. "
                    + "Types: boolean, number, string, map, "
                    + "list_string, list_number, list_map. A new "
                    + "variable starts as false / "
                    + "0 / empty text; give it an initial value by "
                    + "wiring the screen's start event (onCreate or "
                    + "initializeLogic) with a set_variable block. "
                    + "project_summary shows each screen's variables "
                    + "and their types. The user must approve before "
                    + "anything is declared.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen, e.g. MainActivity"));
            properties.add("name", stringSchema(
                    "The variable name, e.g. score"));
            properties.add("type", stringSchema(
                    "boolean, number, string, map, list_string, "
                            + "list_number or list_map"));
            return AgentTools.parameters(properties,
                    "activity", "name", "type");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to add a variable";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        /** The frozen, re-validatable shape of one declaration. */
        private record Checked(ProjectFileBean activity, String name,
                               int typeCode, String typeName,
                               boolean list) {
        }

        private Checked check(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "activity");
            ProjectFileBean activity =
                    SketchwareProjects.findActivity(scId, asked);
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no activity named \"" + asked
                                + "\" in the working project.");
            }
            if (activity.fileType
                    != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                throw new ToolException("E_NOT_ACTIVITY",
                        "Custom views have no variables to declare. "
                                + "Pick an activity screen instead.");
            }
            String typeName = stringArg(args, "type");
            String lowered = typeName.trim().toLowerCase();
            boolean list = lowered.startsWith("list");
            int typeCode;
            if (list) {
                typeCode = switch (lowered) {
                    case "list_number", "list of numbers" -> 1;
                    case "list_string", "list of text",
                            "list of strings" -> 2;
                    case "list_map", "list of maps" -> 3;
                    default -> -1;
                };
                if (typeCode < 0) {
                    throw new ToolException("E_BAD_TYPE",
                            "List variables are list_string, "
                                    + "list_number or list_map.");
                }
            } else {
                typeCode = SketchwareProjects.variableTypeCode(typeName);
                if (typeCode < 0) {
                    throw new ToolException("E_BAD_TYPE",
                            "Variables created this way are boolean, "
                                    + "number, string, map, "
                                    + "list_string, list_number or "
                                    + "list_map.");
                }
            }
            String name = stringArg(args, "name");
            if (!NAME_PATTERN.matcher(name).matches()
                    || name.length() > 100) {
                throw new ToolException("E_BAD_NAME",
                        "A variable name starts with a letter and uses "
                                + "only letters, numbers and "
                                + "underscores, up to 100 characters "
                                + "(Sketchware's own rule).");
            }
            String problem = nameConflict(session, scId, activity, name);
            if (problem != null) {
                throw new ToolException("E_NAME_TAKEN", problem
                        + " If you created it earlier in this turn, "
                        + "that create already succeeded — continue "
                        + "with the rest of the task instead of "
                        + "creating it again or asking about it.");
            }
            return new Checked(activity, name, typeCode,
                    list ? SketchwareProjects.listTypeName(typeCode)
                            : SketchwareProjects.variableTypeName(
                                    typeCode),
                    list);
        }

        /** The editor's exclusion rule: the name must be free across
         * the screen's fields — keywords, variables, lists,
         * components, widgets and views alike. */
        private String nameConflict(Session session, String scId,
                                    ProjectFileBean activity,
                                    String name) {
            for (String keyword : a.a.a.uq.b) {
                if (keyword.equals(name)) {
                    return "\"" + name + "\" is a reserved word in "
                            + "Sketchware and cannot be a variable "
                            + "name.";
                }
            }
            eC store = a.a.a.jC.a(scId);
            String javaName = activity.getJavaName();
            try {
                ArrayList<android.util.Pair<Integer, String>> stored =
                        store.k(javaName);
                if (stored != null) {
                    for (android.util.Pair<Integer, String> pair
                            : stored) {
                        if (name.equals(pair.second)) {
                            return "\"" + name + "\" is already "
                                    + "declared on this screen.";
                        }
                    }
                }
                ArrayList<android.util.Pair<Integer, String>> lists =
                        store.j(javaName);
                if (lists != null) {
                    for (android.util.Pair<Integer, String> pair
                            : lists) {
                        if (name.equals(pair.second)) {
                            return "\"" + name + "\" is already the "
                                    + "name of a list on this screen.";
                        }
                    }
                }
                ArrayList<com.besome.sketch.beans.ComponentBean>
                        components = store.e(javaName);
                if (components != null) {
                    for (com.besome.sketch.beans.ComponentBean component
                            : components) {
                        if (name.equals(component.componentId)) {
                            return "\"" + name + "\" is already a "
                                    + "component on this screen.";
                        }
                    }
                }
            } catch (Exception ignored) {
                // An unreadable logic side simply defers to the
                // pattern and keyword checks above.
            }
            for (com.besome.sketch.beans.ViewBean bean
                    : SketchwareProjects.screenBeans(scId, activity)) {
                if (name.equals(bean.id)) {
                    return "\"" + name + "\" is already a widget on "
                            + "this screen; a variable cannot share "
                            + "its name.";
                }
            }
            return null;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            Checked checked = check(session, args);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity",
                    checked.activity().fileName);
            clean.addProperty("name", checked.name());
            clean.addProperty("type", checked.typeName());
            String startValue = checked.list() ? "empty"
                    : switch (checked.typeCode()) {
                        case 0 -> "false";
                        case 1 -> "0";
                        case 3 -> "empty";
                        default -> "empty text";
                    };
            return new AgentProposal(name(), clean, scId,
                    "Add a variable to "
                            + SketchwareProjects.activityName(
                                    checked.activity())
                            + " in " + project.name,
                    "New " + checked.typeName() + " variable: "
                            + checked.name() + " (starts as "
                            + startValue + ")",
                    "Declares the variable on the screen, the same "
                            + "entry Sketchware's logic editor adds. "
                            + "Nothing else on the screen changes. A "
                            + "safety backup of the project is made "
                            + "first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            if (!scId.equals(session.workingProjectId)) {
                throw new ToolException("E_PROJECT_CHANGED",
                        "The working project changed since this "
                                + "variable was proposed, so it was not "
                                + "added.");
            }
            Checked checked = check(session, proposal.args);
            eC store = a.a.a.jC.a(scId);
            String javaName = checked.activity().getJavaName();
            try {
                SketchwareActions.backupProjectOnce(scId);
                if (checked.list()) {
                    // The editor's list-add path (eC.b), as the
                    // variable dialog's list callback calls it.
                    store.b(javaName, checked.typeCode(),
                            checked.name());
                } else {
                    store.c(javaName, checked.typeCode(),
                            checked.name());
                }
                SketchwareActions.commitProject(scId);
            } catch (Exception e) {
                throw new ToolException("E_APPLY_FAILED",
                        "Adding the variable failed: "
                                + String.valueOf(e.getMessage()));
            }
            Integer stored = checked.list()
                    ? AgentLogic.listsOf(store, javaName)
                            .get(checked.name())
                    : AgentLogic.variablesOf(store, javaName)
                            .get(checked.name());
            if (stored == null || stored != checked.typeCode()) {
                throw new ToolException("E_APPLY_FAILED",
                        "The variable was written but did not read "
                                + "back from the screen's logic.");
            }
            JsonObject result = new JsonObject();
            result.addProperty("declared", checked.name());
            result.addProperty("type", checked.typeName());
            result.addProperty("verified",
                    SketchwareProjects.activityName(checked.activity())
                            + " now has a " + checked.typeName()
                            + " variable named " + checked.name()
                            + " (read back and checked).");
            return result;
        }
    }


    /** create_component: add an Intent component to one screen. */
    private static final class CreateComponent implements Tool {
        private static final Pattern NAME_PATTERN =
                Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

        @Override
        public String name() {
            return "create_component";
        }

        @Override
        public String description() {
            return "Proposes adding an Intent component to one "
                    + "screen, the way Sketchware's own component "
                    + "picker adds it. An Intent is what open_screen "
                    + "uses to open another screen — open_screen "
                    + "also adds one automatically when the screen "
                    + "has none; use this when the user asks for the "
                    + "component itself. Only Intent components can "
                    + "be added this way yet; other kinds "
                    + "(SharedPreferences, Dialog, SoundPool, ...) "
                    + "cannot. The user must approve before anything "
                    + "is added.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen, e.g. MainActivity"));
            properties.add("name", stringSchema(
                    "The component's name, e.g. intent1 (optional; "
                            + "the next free intent name is used)"));
            return AgentTools.parameters(properties, "activity");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to add a component";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        private record Checked(ProjectFileBean activity, String name) {
        }

        /** Every name already taken on this screen, any namespace. */
        private java.util.Set<String> takenNames(String scId,
                                                 String javaName,
                                                 ProjectFileBean activity) {
            java.util.Set<String> taken = new java.util.HashSet<>();
            eC store = a.a.a.jC.a(scId);
            try {
                ArrayList<android.util.Pair<Integer, String>> vars =
                        store.k(javaName);
                if (vars != null) {
                    for (android.util.Pair<Integer, String> pair : vars) {
                        if (pair.second != null) taken.add(pair.second);
                    }
                }
                ArrayList<android.util.Pair<Integer, String>> lists =
                        store.j(javaName);
                if (lists != null) {
                    for (android.util.Pair<Integer, String> pair : lists) {
                        if (pair.second != null) taken.add(pair.second);
                    }
                }
                ArrayList<com.besome.sketch.beans.ComponentBean>
                        components = store.e(javaName);
                if (components != null) {
                    for (com.besome.sketch.beans.ComponentBean component
                            : components) {
                        if (component.componentId != null) {
                            taken.add(component.componentId);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Unreadable logic defers to the other checks.
            }
            for (com.besome.sketch.beans.ViewBean bean
                    : SketchwareProjects.screenBeans(scId, activity)) {
                taken.add(bean.id);
            }
            return taken;
        }

        private Checked check(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "activity");
            ProjectFileBean activity =
                    SketchwareProjects.findActivity(scId, asked);
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no activity named \"" + asked
                                + "\" in the working project.");
            }
            if (activity.fileType
                    != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                throw new ToolException("E_NOT_ACTIVITY",
                        "Custom views have no components. Pick an "
                                + "activity screen instead.");
            }
            String javaName = activity.getJavaName();
            java.util.Set<String> taken =
                    takenNames(scId, javaName, activity);
            String name = stringArg(args, "name");
            if (name.isEmpty()) {
                int n = 1;
                while (taken.contains("intent" + n)) {
                    n++;
                }
                name = "intent" + n;
            }
            if (!NAME_PATTERN.matcher(name).matches()
                    || name.length() > 100) {
                throw new ToolException("E_BAD_NAME",
                        "A component name starts with a letter and "
                                + "uses only letters, numbers and "
                                + "underscores, up to 100 characters "
                                + "(Sketchware's own rule).");
            }
            for (String keyword : a.a.a.uq.b) {
                if (keyword.equals(name)) {
                    throw new ToolException("E_BAD_NAME",
                            "\"" + name + "\" is a reserved word in "
                                    + "Sketchware and cannot be a "
                                    + "component name.");
                }
            }
            if (taken.contains(name)) {
                throw new ToolException("E_NAME_TAKEN",
                        "\"" + name + "\" is already used on this "
                                + "screen. Pick another name, or use "
                                + "the one that is there.");
            }
            return new Checked(activity, name);
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            Checked checked = check(session, args);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity",
                    checked.activity().fileName);
            clean.addProperty("name", checked.name());
            return new AgentProposal(name(), clean, scId,
                    "Add a component to "
                            + SketchwareProjects.activityName(
                                    checked.activity())
                            + " in " + project.name,
                    "New Intent component: " + checked.name(),
                    "Adds the component to the screen, the same "
                            + "entry Sketchware's component picker "
                            + "adds. Nothing else on the screen "
                            + "changes. A safety backup of the "
                            + "project is made first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            if (!scId.equals(session.workingProjectId)) {
                throw new ToolException("E_PROJECT_CHANGED",
                        "The working project changed since this "
                                + "component was proposed, so it was "
                                + "not added.");
            }
            Checked checked = check(session, proposal.args);
            eC store = a.a.a.jC.a(scId);
            String javaName = checked.activity().getJavaName();
            try {
                SketchwareActions.backupProjectOnce(scId);
                // The component picker's own add call.
                store.a(javaName,
                        com.besome.sketch.beans.ComponentBean
                                .COMPONENT_TYPE_INTENT,
                        checked.name());
                SketchwareActions.commitProject(scId);
            } catch (Exception e) {
                throw new ToolException("E_APPLY_FAILED",
                        "Adding the component failed: "
                                + String.valueOf(e.getMessage()));
            }
            boolean found = false;
            try {
                ArrayList<com.besome.sketch.beans.ComponentBean>
                        components = store.e(javaName);
                if (components != null) {
                    for (com.besome.sketch.beans.ComponentBean component
                            : components) {
                        if (checked.name().equals(component.componentId)
                                && component.type
                                        == com.besome.sketch.beans
                                                .ComponentBean
                                                .COMPONENT_TYPE_INTENT) {
                            found = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Read-back below reports it.
            }
            if (!found) {
                throw new ToolException("E_APPLY_FAILED",
                        "The component was written but did not read "
                                + "back from the screen's logic.");
            }
            JsonObject result = new JsonObject();
            result.addProperty("added", checked.name());
            result.addProperty("kind", "Intent");
            result.addProperty("verified",
                    SketchwareProjects.activityName(checked.activity())
                            + " now has an Intent component named "
                            + checked.name()
                            + " (read back and checked).");
            return result;
        }
    }

    /** build_project: compile the working project, read its errors. */
    private static final class BuildProject implements Tool {
        @Override
        public String name() {
            return "build_project";
        }

        @Override
        public String description() {
            return "Compiles the working project with Sketchware's own "
                    + "builder (resources, code generation, javac, dex, "
                    + "signing — the same pipeline as the Build button) "
                    + "and reports success or the compiler errors. "
                    + "Nothing in the project is changed; the APK is "
                    + "written where Sketchware's own build puts it. A "
                    + "successful build leaves an Install button on its "
                    + "card: the user can install that exact APK from "
                    + "the card right away or at any later time, with "
                    + "no rebuild. So if the user later asks to install "
                    + "or run the app and nothing has changed since the "
                    + "last successful build, point them to the Install "
                    + "button on that build card instead of building "
                    + "again. Build only when the user asks to build, "
                    + "install or run, or to check a fix for errors a "
                    + "previous build reported — do not build routinely "
                    + "after edits or wiring; those are already checked "
                    + "before they run. It can take a minute or two. "
                    + "The user approves first.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to build the project";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("project", project.name);
            return new AgentProposal(name(), clean, scId,
                    "Build " + project.name,
                    "Compile “" + project.appName + "” with Sketchware's "
                            + "builder and check for errors. Your screens "
                            + "and blocks are not changed; when it "
                            + "finishes, this card gets an Install button "
                            + "so you can install the result. This can "
                            + "take a minute or two.",
                    "The same pipeline as Sketchware's Build button: "
                            + "resources → generated code → javac → dex "
                            + "→ signed APK.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            if (!scId.equals(session.workingProjectId)) {
                throw new ToolException("E_PROJECT_CHANGED",
                        "The working project changed since this build "
                                + "was proposed, so it was not run.");
            }
            try {
                SketchwareActions.backupProjectOnce(scId);
            } catch (Exception ignored) {
                // A backup problem must not block a build; building
                // never touches project data anyway.
            }
            return AgentBuild.build(session.context, scId);
        }
    }

    /** get_event_logic (read-only): an event's statements numbered. */
    private static final class GetEventLogic implements Tool {
        @Override
        public String name() {
            return "get_event_logic";
        }

        @Override
        public String description() {
            return "Reads what one event already does, statement by "
                    + "statement, numbered the way edit_event_block "
                    + "addresses them ([2] for the second statement, "
                    + "[1,2] for the second statement inside a "
                    + "container). Call this before changing or "
                    + "removing a block — never guess positions. "
                    + "get_screen already tells you which events exist "
                    + "and how many blocks each has; this lists them.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen, e.g. MainActivity"));
            properties.add("target", stringSchema(
                    "The widget id from get_screen, or \"activity\" "
                            + "for the screen's own events"));
            properties.add("event", stringSchema(
                    "The event, e.g. onClick"));
            return AgentTools.parameters(properties,
                    "activity", "target", "event");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Looking at what " + stringArg(args, "event")
                    + " does";
        }

        @Override
        public boolean mutates() {
            return false;
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            ProjectFileBean activity = SketchwareProjects.findActivity(
                    scId, stringArg(args, "activity"));
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no activity named \""
                                + stringArg(args, "activity")
                                + "\" in the working project.");
            }
            AgentLogic.EditPlan probe = AgentLogic.describeEvent(
                    scId, activity, stringArg(args, "target"),
                    stringArg(args, "event"));
            JsonObject result = new JsonObject();
            result.addProperty("event", probe.header);
            result.addProperty("blocks", probe.eventRendering);
            result.addProperty("count", probe.existingCount);
            return result;
        }
    }

    /** edit_event_block: change or remove one stored statement. */
    private static final class EditEventBlock implements Tool {
        @Override
        public String name() {
            return "edit_event_block";
        }

        @Override
        public String description() {
            return "Changes or removes one statement that is already "
                    + "wired into an event — the fix for pile-ups like "
                    + "two set_text blocks saying the same thing. "
                    + "Read the event with get_event_logic first, then "
                    + "pass block_path (the statement's numbers, e.g. "
                    + "[2], or [1,2] inside a container). To change a "
                    + "value, pass replace_with {text|message|value|"
                    + "number: the new value} — only values a block "
                    + "holds directly can change (a toast's message, a "
                    + "set-text's text, a variable's value or number). "
                    + "To remove the statement, pass remove: true. The "
                    + "event's first statement cannot be removed — "
                    + "change its value instead. The user approves "
                    + "first; if the event changed since the card, the "
                    + "edit refuses rather than touching the wrong "
                    + "block.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen, e.g. MainActivity"));
            properties.add("target", stringSchema(
                    "The widget id, or \"activity\" for the screen's "
                            + "own events"));
            properties.add("event", stringSchema("The event name"));
            JsonObject path = new JsonObject();
            path.addProperty("type", "array");
            path.addProperty("description",
                    "1-based statement positions from get_event_logic");
            properties.add("block_path", path);
            properties.add("remove", new JsonObject());
            JsonObject with = new JsonObject();
            with.addProperty("type", "object");
            with.addProperty("description",
                    "{text|message|value|number: new value}");
            properties.add("replace_with", with);
            return AgentTools.parameters(properties,
                    "activity", "target", "event", "block_path");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to change a block in "
                    + stringArg(args, "event");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            ProjectFileBean activity = SketchwareProjects.findActivity(
                    scId, stringArg(args, "activity"));
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no activity named \""
                                + stringArg(args, "activity")
                                + "\" in the working project.");
            }
            JsonObject edit = new JsonObject();
            edit.add("block_path", args.has("block_path")
                    ? args.get("block_path") : new JsonArray());
            edit.add("remove", args.has("remove") ? args.get("remove")
                    : new com.google.gson.JsonPrimitive(false));
            if (args.has("replace_with")) {
                edit.add("replace_with", args.get("replace_with"));
            }
            AgentLogic.EditPlan plan = AgentLogic.planEdit(
                    session.context, scId, activity,
                    stringArg(args, "target"),
                    stringArg(args, "event"), edit);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", activity.fileName);
            // Freeze the target FORM, like set_event_handler: for
            // activity events plan.targetId is the event name itself,
            // which would re-read as a (nonexistent) widget id.
            clean.addProperty("target", plan.activityEvent
                    ? "activity" : plan.targetId);
            clean.addProperty("event", plan.eventName);
            clean.addProperty("activity_event", plan.activityEvent);
            clean.addProperty("existing", plan.existingCount);
            clean.add("block_path", intArrayOfProperty(plan.targetPath));
            clean.addProperty("remove", plan.remove);
            if (!plan.values.isEmpty()) {
                JsonObject with = new JsonObject();
                for (AgentLogic.EditableValue value : plan.values) {
                    with.addProperty(value.kind, value.newValue);
                }
                clean.add("values", with);
            }
            StringBuilder body = new StringBuilder(plan.header).append("\n");
            if (plan.remove) {
                body.append("Remove the statement: ")
                        .append(plan.blockDescription);
            } else {
                for (AgentLogic.EditableValue value : plan.values) {
                    body.append("Change ").append(value.kind)
                            .append(" from “")
                            .append(value.currentValue)
                            .append("” to “").append(value.newValue)
                            .append("”.");
                }
            }
            return new AgentProposal(name(), clean, scId,
                    "Change " + SketchwareProjects.activityName(activity)
                            + " logic",
                    body.toString(),
                    "Position " + pathLabel(plan.targetPath)
                            + " (" + plan.blockOpCode + "), "
                            + plan.existingCount + " stored blocks:\n"
                            + plan.eventRendering);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            if (!scId.equals(session.workingProjectId)) {
                throw new ToolException("E_PROJECT_CHANGED",
                        "The working project changed since this edit "
                                + "was proposed, so it was not applied.");
            }
            ProjectFileBean activity = SketchwareProjects.findActivity(
                    scId, stringArg(proposal.args, "activity"));
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "That activity no longer exists.");
            }
            JsonObject edit = new JsonObject();
            edit.add("block_path", proposal.args.get("block_path"));
            edit.add("remove", new com.google.gson.JsonPrimitive(
                    booleanArg(proposal.args, "remove")));
            if (proposal.args.has("values")) {
                edit.add("replace_with", proposal.args.get("values"));
            }
            AgentLogic.EditPlan plan = AgentLogic.planEdit(
                    session.context, scId, activity,
                    booleanArg(proposal.args, "activity_event")
                            ? "activity"
                            : stringArg(proposal.args, "target"),
                    stringArg(proposal.args, "event"), edit);
            if (plan.existingCount
                    != intArg(proposal.args, "existing")) {
                throw new ToolException("E_LOGIC_CHANGED",
                        "This event's blocks changed since the edit "
                                + "was proposed, so it was not applied.");
            }
            return AgentLogic.applyEdit(session.context, plan);
        }

        private static String pathLabel(int[] path) {
            StringBuilder label = new StringBuilder();
            for (int i = 0; i < path.length; i++) {
                if (i > 0) label.append(", ");
                label.append(path[i]);
            }
            return label.toString();
        }
    }

    /** edit_project_theme: theme colours through the registry. */
    private static final class EditProjectTheme implements Tool {
        @Override
        public String name() {
            return "edit_project_theme";
        }

        @Override
        public String description() {
            return "Changes the working project's theme colours "
                    + "(color_primary, color_primary_dark, "
                    + "color_accent, color_control_highlight, "
                    + "color_control_normal) to exact hex values — "
                    + "the same ones the project settings screen "
                    + "writes and a build turns into the app's "
                    + "colors.xml. Present the new colour clearly on "
                    + "the approval card with its before value. Wide "
                    + "taste choices belong to ask_color first; this "
                    + "tool is for exact values. Only a colour can "
                    + "change here — no fonts, sounds or layouts.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            JsonObject colours = new JsonObject();
            colours.addProperty("type", "object");
            colours.addProperty("description",
                    "Theme keys to new #RRGGBB / #AARRGGBB values");
            properties.add("colors", colours);
            return AgentTools.parameters(properties, "colors");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to change the project colours";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject colours = args.has("colors")
                    && args.get("colors").isJsonObject()
                    ? args.getAsJsonObject("colors") : new JsonObject();
            AgentLogic.ThemePlan plan = AgentLogic.planTheme(scId,
                    colours);
            JsonObject clean = new JsonObject();
            clean.add("colors", colours);
            return new AgentProposal(name(), clean, scId,
                    "Change " + project.name + " colours",
                    plan.sentence,
                    "Theme values written: "
                            + plan.after.keySet());
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            if (!scId.equals(session.workingProjectId)) {
                throw new ToolException("E_PROJECT_CHANGED",
                        "The working project changed since this was "
                                + "proposed, so it was not run.");
            }
            AgentLogic.ThemePlan plan = AgentLogic.planTheme(scId,
                    proposal.args.getAsJsonObject("colors"));
            return AgentLogic.applyTheme(plan);
        }
    }

    private static int intArg(JsonObject args, String key) {
        try {
            return args.get(key).getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }

    // -- interactive tools (the user acts; nothing changes yet) ---------

    /** ask_color: Sketchware's own colour picker, answered by the user. */
    private static final class AskColor implements Tool {
        @Override
        public String name() {
            return "ask_color";
        }

        @Override
        public String description() {
            return "Opens Sketchware's colour picker so the USER picks "
                    + "a colour by eye; you get the exact colour back. "
                    + "Use it whenever a colour choice is theirs: they "
                    + "asked to see options, want a shade or tint judged "
                    + "visually, or you would otherwise be guessing. "
                    + "Pass a starting colour in \"initial\" when one is "
                    + "being replaced. If the user closes the picker, "
                    + "the result says cancelled — then ask in words or "
                    + "continue without that change. Once the user picks, "
                    + "go straight to the approval card for the change; "
                    + "do not ask again in chat first. Never call this when "
                    + "the user already named an exact colour or token.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("question", stringSchema(
                    "What the colour is for, in the user's words, "
                            + "e.g. \"the main screen background\""));
            properties.add("initial", stringSchema(
                    "Optional starting colour (#RRGGBB or #AARRGGBB), "
                            + "e.g. the colour being replaced"));
            return AgentTools.parameters(properties, "question");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Asked you to pick a colour";
        }

        @Override
        public boolean interactive() {
            return true;
        }
    }

    // -- the change-making tools (approval required) -----------------------

    /** create_project: a brand-new project, the app's own way. */
    private static final class CreateProject implements Tool {
        private static final Pattern PACKAGE_PATTERN =
                Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+");

        @Override
        public String name() {
            return "create_project";
        }

        @Override
        public String description() {
            return "Proposes creating a brand-new Sketchware project with "
                    + "an empty main screen. The user must approve before "
                    + "anything is created. The new project becomes the "
                    + "working project.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("name", stringSchema(
                    "The app's display name, e.g. \"Notes App\""));
            properties.add("package_name", stringSchema(
                    "Optional package name, e.g. com.example.notes. "
                            + "Leave empty for a sensible default."));
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to create a project";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String name = stringArg(args, "name");
            if (name.isEmpty() || name.length() > 60) {
                throw new ToolException("E_BAD_NAME",
                        "A project needs a name of 1 to 60 characters.");
            }
            String pkg = stringArg(args, "package_name");
            if (!pkg.isEmpty() && (pkg.length() > 120
                    || !PACKAGE_PATTERN.matcher(pkg).matches())) {
                throw new ToolException("E_BAD_PACKAGE",
                        "The package name must look like com.example.app "
                                + "(lowercase words joined by dots).");
            }
            JsonObject clean = new JsonObject();
            clean.addProperty("name", name);
            clean.addProperty("package_name", pkg);
            return new AgentProposal(name(), clean, "",
                    "Create a new project",
                    "“" + name + "”"
                            + (pkg.isEmpty() ? "" : " · " + pkg),
                    "Makes the project with an empty main screen, like "
                            + "using New project. The new project becomes "
                            + "the working project. Nothing else changes.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String name = stringArg(proposal.args, "name");
            String pkg = stringArg(proposal.args, "package_name");
            try {
                SketchwareProjects.ProjectInfo created =
                        SketchwareActions.createProject(
                                session.context, name, pkg);
                JsonObject result = new JsonObject();
                result.addProperty("created_project", created.appName);
                result.addProperty("package", created.packageName);
                result.addProperty("verified",
                        "The project now exists in the user's project list.");
                result.addProperty("bind_project", created.scId);
                return result;
            } catch (Exception e) {
                throw new ToolException("E_CREATE_FAILED",
                        "Creating the project failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** create_activity: one new empty screen in the working project. */
    private static final class CreateActivity implements Tool {
        private static final Pattern NAME_PATTERN =
                Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");

        @Override
        public String name() {
            return "create_activity";
        }

        @Override
        public String description() {
            return "Proposes adding one new empty activity (screen) to "
                    + "the working project. The user must approve before "
                    + "anything is added.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The new activity name, e.g. SettingsActivity"));
            return AgentTools.parameters(properties, "activity");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to add a screen";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "activity");
            String fileName = fileNameFor(asked);
            if (!NAME_PATTERN.matcher(fileName).matches()) {
                throw new ToolException("E_BAD_NAME",
                        "An activity name must start with a letter and use "
                                + "only letters, numbers and underscores, "
                                + "e.g. SettingsActivity.");
            }
            if (SketchwareProjects.findActivity(scId, asked) != null
                    || SketchwareProjects.findActivity(scId, fileName) != null) {
                throw new ToolException("E_ACTIVITY_EXISTS",
                        "The project already has a screen named " + asked + ".");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", fileName);
            return new AgentProposal(name(), clean, scId,
                    "Add a screen to " + project.name,
                    "New activity: "
                            + SketchwareProjects.activityName(
                                    new ProjectFileBean(
                                            ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY,
                                            fileName)),
                    "Adds one empty screen. Nothing already in the project "
                            + "changes. A safety backup of the project is "
                            + "made first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String fileName = stringArg(proposal.args, "activity");
            if (SketchwareProjects.findActivity(scId, fileName) != null) {
                throw new ToolException("E_ACTIVITY_EXISTS",
                        "That screen already exists now, so it was not "
                                + "added again.");
            }
            try {
                SketchwareActions.createActivity(scId, fileName);
                JsonObject result = new JsonObject();
                result.addProperty("created_activity",
                        SketchwareProjects.activityName(
                                SketchwareProjects.findActivity(scId, fileName)));
                result.addProperty("verified",
                        "The screen now exists in the project.");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_CREATE_FAILED",
                        "Adding the screen failed: "
                                + String.valueOf(e.getMessage()));
            }
        }

        /** The on-disk file name for a name like "SettingsActivity". */
        private static String fileNameFor(String asked) {
            String name = asked == null ? "" : asked.trim();
            if (name.endsWith(".xml")) {
                name = name.substring(0, name.length() - ".xml".length());
            }
            if (name.endsWith("Activity") && name.length() > "Activity".length()) {
                name = name.substring(0, name.length() - "Activity".length());
            }
            if (!name.isEmpty()) {
                name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
            }
            return name;
        }
    }

    /** create_custom_view: one new reusable layout in the project. */
    private static final class CreateCustomView implements Tool {
        private static final Pattern NAME_PATTERN =
                Pattern.compile("[a-z][a-z0-9_]{0,39}");

        @Override
        public String name() {
            return "create_custom_view";
        }

        @Override
        public String description() {
            return "Proposes adding one new empty custom view (a "
                    + "reusable layout, e.g. a list item design) to the "
                    + "working project. The user must approve before "
                    + "anything is added.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("name", stringSchema(
                    "The custom view name, lowercase with underscores, "
                            + "e.g. item_task"));
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to add a custom view";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String name = stringArg(args, "name").trim()
                    .toLowerCase(Locale.ROOT);
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new ToolException("E_BAD_NAME",
                        "A custom view name starts with a lowercase letter "
                                + "and uses only lowercase letters, numbers "
                                + "and underscores, e.g. item_task.");
            }
            for (ProjectFileBean view
                    : SketchwareProjects.customViews(scId)) {
                if (view.fileName.equalsIgnoreCase(name)) {
                    throw new ToolException("E_CUSTOM_VIEW_EXISTS",
                            "The project already has a custom view named "
                                    + name + ".");
                }
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("name", name);
            return new AgentProposal(name(), clean, scId,
                    "Add a custom view to " + project.name,
                    "New custom view: " + name,
                    "Adds one empty reusable layout. Nothing already in "
                            + "the project changes. A safety backup of the "
                            + "project is made first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String name = stringArg(proposal.args, "name");
            try {
                SketchwareActions.createCustomView(scId, name);
                JsonObject result = new JsonObject();
                result.addProperty("created_custom_view", name);
                result.addProperty("verified",
                        "The custom view now exists in the project and "
                                + "can be edited with edit_screen.");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_CREATE_FAILED",
                        "Adding the custom view failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** edit_activity: toolbar/fullscreen/orientation of one screen. */
    private static final class EditActivity implements Tool {
        @Override
        public String name() {
            return "edit_activity";
        }

        @Override
        public String description() {
            return "Proposes changing one activity's toolbar, fullscreen "
                    + "or orientation setting. Only what is passed "
                    + "changes; the drawer and floating-button settings "
                    + "are not touched by this tool.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The activity, e.g. SettingsActivity"));
            JsonObject toolbar = new JsonObject();
            toolbar.addProperty("type", "boolean");
            toolbar.addProperty("description",
                    "Show the top toolbar (true) or hide it (false)");
            properties.add("toolbar", toolbar);
            JsonObject fullscreen = new JsonObject();
            fullscreen.addProperty("type", "boolean");
            fullscreen.addProperty("description",
                    "Fullscreen (true) or normal (false)");
            properties.add("fullscreen", fullscreen);
            JsonObject floatingButton = new JsonObject();
            floatingButton.addProperty("type", "boolean");
            floatingButton.addProperty("description",
                    "Show the floating action button (true) or hide it "
                            + "(false). Its icon is set with edit_screen's "
                            + "set_fab.");
            properties.add("floating_button", floatingButton);
            JsonObject orientation = new JsonObject();
            orientation.addProperty("type", "string");
            orientation.addProperty("description",
                    "portrait, landscape, or both");
            properties.add("orientation", orientation);
            return AgentTools.parameters(properties, "activity");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to change a screen's settings";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "activity");
            ProjectFileBean target =
                    SketchwareProjects.findActivity(scId, asked);
            if (target == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no screen named \"" + asked + "\" in "
                                + "the working project.");
            }
            int options = target.options;
            int orientation = target.orientation;
            List<String> lines = new java.util.ArrayList<>();
            if (args.has("toolbar")) {
                boolean show = args.get("toolbar").getAsBoolean();
                options = show
                        ? options | ProjectFileBean.OPTION_ACTIVITY_TOOLBAR
                        : options & ~ProjectFileBean.OPTION_ACTIVITY_TOOLBAR;
                lines.add((show ? "Show " : "Hide ")
                        + SketchwareProjects.activityName(target)
                        + "'s top toolbar");
            }
            if (args.has("fullscreen")) {
                boolean full = args.get("fullscreen").getAsBoolean();
                options = full
                        ? options | ProjectFileBean.OPTION_ACTIVITY_FULLSCREEN
                        : options & ~ProjectFileBean.OPTION_ACTIVITY_FULLSCREEN;
                lines.add("Make " + SketchwareProjects.activityName(target)
                        + (full ? " fullscreen" : " not fullscreen"));
            }
            if (args.has("floating_button")) {
                boolean show = args.get("floating_button").getAsBoolean();
                options = show
                        ? options | ProjectFileBean.OPTION_ACTIVITY_FAB
                        : options & ~ProjectFileBean.OPTION_ACTIVITY_FAB;
                lines.add((show ? "Show " : "Hide ")
                        + SketchwareProjects.activityName(target)
                        + "'s floating button");
            }
            if (args.has("orientation")) {
                String word = stringArg(args, "orientation")
                        .toLowerCase(Locale.ROOT);
                orientation = switch (word) {
                    case "portrait" -> ProjectFileBean.ORIENTATION_PORTRAIT;
                    case "landscape" -> ProjectFileBean.ORIENTATION_LANDSCAPE;
                    case "both" -> ProjectFileBean.ORIENTATION_BOTH;
                    default -> throw new ToolException("E_BAD_VALUE",
                            "Orientation is portrait, landscape or both.");
                };
                lines.add("Let " + SketchwareProjects.activityName(target)
                        + " rotate: " + word);
            }
            if (lines.isEmpty()) {
                throw new ToolException("E_BAD_OP",
                        "Nothing to change: pass toolbar, fullscreen, "
                                + "floating_button and/or orientation.");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", target.fileName);
            clean.addProperty("options", options);
            clean.addProperty("orientation", orientation);
            return new AgentProposal(name(), clean, scId,
                    "Change " + SketchwareProjects.activityName(target)
                            + " in " + project.name,
                    String.join("; ", lines) + ".",
                    "Only this screen's settings change. A safety backup "
                            + "of the project is made first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String fileName = stringArg(proposal.args, "activity");
            int options = proposal.args.get("options").getAsInt();
            int orientation = proposal.args.get("orientation").getAsInt();
            try {
                SketchwareActions.editActivityOptions(
                        scId, fileName, options, orientation);
                JsonObject result = new JsonObject();
                result.addProperty("edited_activity", fileName);
                result.addProperty("verified",
                        "The screen's settings now match what was "
                                + "approved.");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_EDIT_FAILED",
                        "Changing the screen failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** delete_activity: removes one screen, always behind approval. */
    private static final class DeleteActivity implements Tool {
        @Override
        public String name() {
            return "delete_activity";
        }

        @Override
        public String description() {
            return "Proposes deleting one activity (screen) of the "
                    + "working project, with its widgets, events and "
                    + "blocks. Deleting always needs the user's approval. "
                    + "The main screen cannot be deleted.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The activity to delete, e.g. SettingsActivity"));
            return AgentTools.parameters(properties, "activity");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to delete a screen";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "activity");
            ProjectFileBean target =
                    SketchwareProjects.findActivity(scId, asked);
            if (target == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no screen named \"" + asked + "\" in "
                                + "the working project.");
            }
            if ("main".equalsIgnoreCase(target.fileName)) {
                throw new ToolException("E_MAIN_ACTIVITY",
                        "The main screen is the app's starting screen and "
                                + "cannot be deleted. Rename or rebuild it "
                                + "instead.");
            }
            int widgets = SketchwareProjects
                    .screenBeans(scId, target).size();
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", target.fileName);
            return new AgentProposal(name(), clean, scId,
                    "Delete a screen from " + project.name,
                    "Delete "
                            + SketchwareProjects.activityName(target)
                            + " and everything in it (" + widgets
                            + (widgets == 1 ? " widget" : " widgets")
                            + ", plus its events and blocks).",
                    "This cannot be undone from here. A safety backup of "
                            + "the project is made first, and the deletion "
                            + "goes through Sketchware's own screen-manager "
                            + "save, like deleting it by hand.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String fileName = stringArg(proposal.args, "activity");
            if (SketchwareProjects.findActivity(scId, fileName) == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "That screen no longer exists, so nothing was "
                                + "deleted.");
            }
            try {
                SketchwareActions.deleteActivity(scId, fileName);
                JsonObject result = new JsonObject();
                result.addProperty("deleted_activity", fileName);
                result.addProperty("verified",
                        "The screen is gone from the project.");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_DELETE_FAILED",
                        "Deleting the screen failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }
}
