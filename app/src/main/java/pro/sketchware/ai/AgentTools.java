package pro.sketchware.ai;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
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
        register(new GetProjectSetup());
        register(new AskColor());
        register(new AskChoice());
        register(new SetStyleBrief());
        register(new SetDesignSystem());
        register(new EditDesignToken());
        register(new SetPlan());
        register(new GetTaskLog());
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
        register(new RemoveVariable());
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
     * The memory of a change: a stable fingerprint of the tool and its
     * arguments (key order and whitespace ignored). The app records it
     * when a change lands, so the same proposal later — from a retry,
     * a resumed task, or an entirely different model — can be refused
     * without even showing the user a card.
     */
    public static String fingerprint(String name, String argumentsJson) {
        JsonObject args;
        try {
            args = parseArgs(argumentsJson);
        } catch (Exception e) {
            args = new JsonObject();
        }
        return String.valueOf(name).trim().toLowerCase(Locale.ROOT)
                + "|" + canonical(args).toString().hashCode();
    }

    /** A JSON value with object keys in a fixed order, recursively. */
    private static JsonElement canonical(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (element.isJsonPrimitive()) {
            return element;
        }
        if (element.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                array.add(canonical(child));
            }
            return array;
        }
        JsonObject object = element.getAsJsonObject();
        JsonObject sorted = new JsonObject();
        ArrayList<String> keys = new ArrayList<>(object.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            sorted.add(key, canonical(object.get(key)));
        }
        return sorted;
    }

    /**
     * Asks a mutating tool to validate and describe a change. Nothing
     * executes here; the description is what the approval card shows.
     */
    public static ProposalResult propose(String name, String argumentsJson,
                                         Session session) {
        return propose(name, argumentsJson, session, false);
    }

    /**
     * Asks a mutating tool to validate and describe a change, with
     * {@code allowRepeat} lifting the duplicate refusal for this one
     * call (used when the model insists after being told the change
     * is already in the project — the user may genuinely want it
     * again).
     */
    public static ProposalResult propose(String name, String argumentsJson,
                                         Session session,
                                         boolean allowRepeat) {
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
        // The app remembers what landed. A change that is already in
        // the project never reaches an approval card again: it is
        // reported back to the model as done, whatever model sent it.
        if (!allowRepeat
                && session.workingProjectId != null
                && !session.workingProjectId.isEmpty()
                && AgentJournal.hasDoneFingerprint(session.workingProjectId,
                        fingerprint(name, argumentsJson))) {
            return ProposalResult.failed(error("E_ALREADY_DONE",
                    "This exact change is already in the project — it "
                            + "landed earlier in this work. Do not propose "
                            + "it again; continue with the next step — "
                            + "unless the user has explicitly asked for "
                            + "this exact change again, in which case "
                            + "call it once more and it will run."));
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
                            + "project card at the top of the chat and pick a "
                            + "project, then try again.");
        }
        if (SketchwareProjects.project(workingProjectId) == null) {
            throw new ToolException("E_NO_PROJECT",
                    "The working project no longer exists. Ask the user to "
                            + "pick a project with the project card at the top.");
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
            // Which libraries are switched on belongs in every
            // project read: it decides whether an AdView, MapView,
            // Firebase or ad component is even possible here.
            result.add("libraries", AgentSetup.libraries(scId));
            result.add("permissions", AgentSetup.permissions(scId));
            return result;
        }
    }

    /**
     * get_project_setup: what the project has been SET UP FOR — the
     * part that lives outside its screens. Sketchware refuses some
     * widgets and components until their library is switched on in
     * the Library Manager, and the agent has to know that before it
     * proposes anything that needs one.
     */
    private static final class GetProjectSetup implements Tool {
        @Override
        public String name() {
            return "get_project_setup";
        }

        @Override
        public String description() {
            return "Reads what the working project has been set up "
                    + "for: which libraries are switched on in the "
                    + "Library Manager (Firebase, AdMob, Google Maps, "
                    + "AppCompat), the permissions declared in the "
                    + "Permission Manager, which extra files the "
                    + "project carries (assets, Java/Kotlin, resources, "
                    + "native libraries, imports), whether it has "
                    + "ProGuard rules, and the tail of its last compile "
                    + "log. Check this BEFORE proposing anything that "
                    + "depends on a library — an AdView, MapView, "
                    + "Firebase, AdMob or FragmentAdapter component "
                    + "needs its library switched on first, and the app "
                    + "refuses the change until the user turns it on in "
                    + "Configuration → Library Manager. Also check it "
                    + "when the user reports a build or runtime "
                    + "problem: the compile log is the compiler's own "
                    + "last words on this project.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Checked what the project is set up for";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            JsonObject result = new JsonObject();
            result.add("libraries", AgentSetup.libraries(scId));
            result.add("permissions", AgentSetup.permissions(scId));
            result.add("files", AgentSetup.files(scId));
            String log = AgentSetup.compileLogTail(scId, 1500);
            if (!log.isEmpty()) {
                result.addProperty("last_compile_log_tail", log);
            }
            JsonArray notes = new JsonArray();
            JsonObject libraries = AgentSetup.libraries(scId);
            for (String key : new String[]{"firebase", "admob",
                    "google_maps", "appcompat"}) {
                if (!libraries.has(key)) {
                    notes.add("The project's library settings could not "
                            + "be read; ask the user what is enabled in "
                            + "the Library Manager before proposing "
                            + "anything that needs one.");
                    break;
                }
            }
            if (libraries.has("admob") && !libraries.get("admob")
                    .getAsBoolean()) {
                notes.add("AdMob is not switched on: AdView widgets and "
                        + "ad components cannot be added yet.");
            }
            if (libraries.has("google_maps") && !libraries
                    .get("google_maps").getAsBoolean()) {
                notes.add("Google Maps is not switched on: MapView "
                        + "cannot be added yet.");
            }
            if (libraries.has("firebase") && !libraries.get("firebase")
                    .getAsBoolean()) {
                notes.add("Firebase is not switched on: Firebase "
                        + "components cannot be added yet.");
            }
            if (libraries.has("appcompat") && !libraries.get("appcompat")
                    .getAsBoolean()) {
                notes.add("AppCompat / Material is not switched on: the "
                        + "editor still lets CardView, MaterialButton, "
                        + "RecyclerView, TabLayout and similar widgets "
                        + "be added, but the build only includes the "
                        + "AndroidX dependencies when this switch is on, "
                        + "so such a screen will not compile. Turn it on "
                        + "(Library Manager → AppCompat) before using "
                        + "Material widgets, or use plain widgets "
                        + "instead.");
            }
            if (notes.size() > 0) {
                result.add("notes", notes);
            }
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
                    + "blocks each already has, and — for an activity — "
                    + "the NAMES it declares: its components, its "
                    + "variables (with kind), its lists (with kind) and "
                    + "its more-blocks. Read this before writing logic: "
                    + "those names are the only ones that exist, and a "
                    + "name you invent or leave empty will be refused.";
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
            // What the screen declares — components, variables, lists.
            // Without this, writing logic is guesswork: a model has to
            // remember the ids it created turns ago, and when it
            // forgets it sends an empty name (which is exactly what the
            // KidsNotes conversation did, six times running).
            SketchwareProjects.LogicSummary summary =
                    SketchwareProjects.logicSummary(scId, activity);
            if (activity.fileType
                    == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                JsonArray components = new JsonArray();
                for (String id : summary.components) {
                    components.add(id);
                }
                result.add("components", components);
                JsonArray variables = new JsonArray();
                for (String name : summary.variables) {
                    variables.add(name + " ("
                            + summary.variableTypes.get(name) + ")");
                }
                result.add("variables", variables);
                JsonArray lists = new JsonArray();
                for (String name : summary.lists) {
                    lists.add(name + " ("
                            + summary.listTypes.get(name) + ")");
                }
                result.add("lists", lists);
                if (!summary.moreBlocks.isEmpty()) {
                    JsonArray moreBlocks = new JsonArray();
                    for (String name : summary.moreBlocks) {
                        moreBlocks.add(name);
                    }
                    result.add("more_blocks", moreBlocks);
                }
            }
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
                    + "ViewPager, Spinner), "
                    + "set_progress {id,progress?,max?} (ProgressBar/SeekBar), "
                    + "set_indeterminate {id,indeterminate}, set_alpha "
                    + "{id,alpha} (0 to 1), set_attributes {id,attributes} "
                    + "(the editor's Custom attributes: raw XML attributes "
                    + "written on the widget's tag — this is how corner "
                    + "radius, elevation, outline, ripple and card looks "
                    + "are set as properties. Pass an object, e.g. "
                    + "{\"app:cardCornerRadius\":\"8dp\","
                    + "\"app:cardElevation\":\"4dp\"} on a CardView, "
                    + "{\"app:cornerRadius\":\"12dp\",\"app:strokeColor\":"
                    + "\"#FF8A3D\",\"app:strokeWidth\":\"2dp\"} on a "
                    + "MaterialButton, {\"android:elevation\":\"4dp\"}, "
                    + "{\"android:drawableLeft\":\"@drawable/ic\"} on a "
                    + "TextView, {\"android:weightSum\":\"3\"} on a "
                    + "LinearLayout. Every widget has its own list — "
                    + "a CardView takes app:cardCornerRadius and "
                    + "app:cardElevation, a MaterialButton takes "
                    + "app:cornerRadius, an ImageView takes android:src, "
                    + "and only a LinearLayout takes android:weightSum — "
                    + "so a name that is right for one widget is rejected "
                    + "on another, with the correct list given back to you. "
                    + "Values must have no spaces — write 8dp, not 8 dp. "
                    + "On a plain Button or TextView that has no corner "
                    + "attribute of its own, use the set_corner_radius "
                    + "block instead), set_clickable "
                    + "{id,clickable}, "
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
                    + "activity event like onCreate. The target can "
                    + "also be a component's id (for example "
                    + "\"firebase1\") with one of that component's own "
                    + "events — components report what happened "
                    + "through their events, and the values an event "
                    + "passes are read with {\"arg\":\"name\"}. The "
                    + "new blocks are "
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
                    + "clear_map {variable}, new_map {variable}, "
                    + "remove_map_key {variable,key}, map_keys_to_list "
                    + "{variable,list} (all of a map's keys into a list "
                    + "of text), remove_from_list {list,index}, "
                    + "insert_in_list {list,index,text|value}, "
                    + "set_in_list {list,index,text|value}, "
                    + "reverse_list {list}, shuffle_list {list}, "
                    + "sort_list {list} (a list of maps also takes "
                    + "key, with numeric/ascending booleans), "
                    + "swap_in_list {list,index,index2}, "
                    + "get_map_from_list {list,index,variable} (a list "
                    + "of maps into a map variable), add_map_to_list "
                    + "{variable,list}, insert_map_to_list {variable,"
                    + "list,index}, add_map_entry {list,key,text}, "
                    + "insert_map_entry {list,index,key,text}, "
                    + "set_map_entry {list,index,key,text} (these four "
                    + "need a list of maps), map_from_json {variable,"
                    + "text|from_variable} and list_from_json {list,"
                    + "text|from_variable} (fill a map or list from "
                    + "JSON text), widget actions on a target widget: "
                    + "set_text_size {target,value}, set_background_color "
                    + "{target,color}, set_background_image {target,"
                    + "image} (a drawable name), set_elevation/set_alpha/"
                    + "set_rotation/set_scale_x/set_scale_y/"
                    + "set_translation_x/set_translation_y {target,"
                    + "value}, set_corner_radius {target,value,color}, "
                    + "set_stroke {target,value,color,color2}, "
                    + "set_gradient {target,color,color2}, "
                    + "set_radius_and_stroke {target,value,value2,color,"
                    + "color2}, set_gravity {target,gravity,gravity2} "
                    + "(constants like CENTER, LEFT, CENTER_VERTICAL), "
                    + "request_focus {target}, perform_click {target}, "
                    + "remove_all_views {target}, add_view {target,child}, "
                    + "add_view_at {target,child,value}, remove_view "
                    + "{target,child}, set_checked {target,flag} "
                    + "(a CheckBox), set_max {target,value} (a SeekBar), "
                    + "set_indeterminate {target,flag} (a ProgressBar), "
                    + "set_num_stars/set_rating/set_step_size {target,"
                    + "value} (a RatingBar), set_lines/set_max_lines "
                    + "{target,value}, set_selection {target,value,"
                    + "value2}, select_all_text {target}, show_error "
                    + "{target,text}, set_single_line {target,flag}, "
                    + "disable_suggestions {target}, set_hint_color "
                    + "{target,color} (EditText blocks), play_video/"
                    + "pause_video/stop_video {target}, set_video_url "
                    + "{target,text} (a VideoView), web_go_back/"
                    + "web_go_forward/web_clear_cache/web_clear_history/"
                    + "web_stop_loading/web_zoom_in/web_zoom_out "
                    + "{target}, set_cache_mode {target,mode} (a WebView; "
                    + "LOAD_DEFAULT, LOAD_NO_CACHE...), "
                    + "set_autocomplete_data {target,list} (an "
                    + "AutoCompleteTextView fed by a list of text), "
                    + "set_multiautocomplete_data {target,list}, "
                    + "set_threshold {target,value}, set_tokenizer "
                    + "{target} (MultiAutoCompleteTextView blocks), "
                    + "set_image_resource {target,image}, "
                    + "set_image_by_name {target,text}, set_image_file "
                    + "{target,text} (an ImageView from a file path), "
                    + "set_color_filter {target,color} (tint an "
                    + "ImageView), write_file {text|"
                    + "from_variable,path|path_from_variable} (write "
                    + "text to a file, replacing it), delete_file "
                    + "{path}, make_folder {path}, copy_file {from,to}, "
                    + "move_file {from,to}, copy_folder {from,to}, "
                    + "rename_file {from,to}, list_files {path,list} "
                    + "(the names in a folder into a list of text), "
                    + "and "
                    + "close_drawer {} (the screen's navigation drawer), "
                    + "save_pref {component,text,text2} (save text2 "
                    + "under the key text in a SharedPreferences "
                    + "component). VALUES ARE BLOCKS, NOT TEXT: any "
                    + "slot that takes text also takes a reporter, "
                    + "written as an object \u2014 never as quoted "
                    + "text. {\"var\":\"Box\"} the value a variable "
                    + "holds; {\"text_of\":\"note_input\"} what is "
                    + "typed in a text box; {\"pref_get\":\"file1\","
                    + "\"key\":\"magical_note\"} a saved preference; "
                    + "{\"json_of_list\":\"allNotes\"} a list turned "
                    + "into JSON; {\"fn\":\"random\",\"a\":0,"
                    + "\"b\":5} a random whole number; "
                    + "{\"number\":5} a number; {\"text\":\"hi\"} "
                    + "plain text. So: put a random number in Box = "
                    + "{\"block\":\"set_variable\",\"variable\":"
                    + "\"Box\",\"value\":{\"fn\":\"random\","
                    + "\"a\":0,\"b\":5}}; show it = "
                    + "{\"block\":\"set_text\",\"target\":\"review"
                    + "\",\"text\":{\"var\":\"Box\"}}; save a list "
                    + "into a preference = {\"block\":\"save_pref\","
                    + "\"component\":\"file1\",\"text\":"
                    + "\"magical_note\",\"text2\":{\"json_of_list\":"
                    + "\"allNotes\"}}; read it back into a list = "
                    + "{\"block\":\"list_from_json\",\"text\":"
                    + "{\"pref_get\":\"file1\",\"key\":"
                    + "\"magical_note\"},\"list\":\"allNotes\"}. "
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
                    + "break {} (leave a loop), continue {} (skip to "
                    + "the next loop round), while {condition,blocks} "
                    + "(run while a condition holds), try_catch "
                    + "{blocks,catch:[...]} (run blocks, and the catch "
                    + "blocks if anything fails), and switch {value,"
                    + "cases:[{match,blocks:[...]}],default:[...]} "
                    + "(run the case whose match equals the value — "
                    + "a number or text value, up to 12 cases; a case "
                    + "may have empty blocks). The gated widget families "
                    + "work only on their widget: ListView (refresh_list, "
                    + "list_smooth_scroll {value}, add_header/"
                    + "remove_header {child}), Spinner (set_spinner_data "
                    + "{list}, set_spinner_selection {value}), ViewPager "
                    + "(set_pager_page {value}), RecyclerView "
                    + "(recycler_layout, recycler_scroll_to {value}), "
                    + "GridView (grid_columns {value}), TabLayout "
                    + "(add_tab {text}, set_tab_colors {color,color2}, "
                    + "setup_with_pager {child}), CardView "
                    + "(set_card_color {color}, set_card_radius "
                    + "{value}), TimePicker (set_picker_hour/"
                    + "set_picker_minute {value}, set_24hour {flag}), "
                    + "CalendarView (set_calendar_date {value}, epoch "
                    + "milliseconds), TextInputLayout (til_box_color "
                    + "{color}, til_set_error {text}, til_counter_max "
                    + "{value}), BadgeView (set_badge_number {value}), "
                    + "PatternLockView (clear_pattern, "
                    + "pattern_dot_count {value}), OTPView "
                    + "(set_otp_text {text}), Lottie (set_lottie_asset "
                    + "{text}, set_lottie_speed {value}), CodeView "
                    + "(set_code_text {text}), MapView (map_zoom_in, "
                    + "map_add_marker {text,value,value2}, map_set_type "
                    + "{mode}), SwipeRefreshLayout (set_refreshing "
                    + "{flag}) and BottomNavigationView (bottom_add_item "
                    + "{value,text,image}). Conditions and counts "
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
                    + "{\"map_has\":\"info\",\"key\":\"name\"}, and "
                    + "functions {\"fn\":\"abs\",\"a\":...} (also ceil, "
                    + "floor, round, sqrt, pow, max, min, random, pi, "
                    + "len, concat, contains, upper, lower, trim, "
                    + "substring with from/to, to_number, to_text, "
                    + "starts_with, ends_with, last_segment (the last "
                    + "part of a path), external_dir (the device "
                    + "storage folder as text), app_dir (the app's "
                    + "private folder)), {\"file_exists\":path}, "
                    + "{\"is_file\":path}, {\"is_folder\":path}, "
                    + "{\"read_file\":path} (a file's text content), "
                    + "{\"file_length\":path} (a file's size), "
                    + "{\"index_of\":\"items\",\"value\":...} (an item's "
                    + "position in a list, as a number), "
                    + "{\"map_size\":\"info\"}, {\"map_empty\":\"info\"}, "
                    + "{\"map_has_value\":\"info\",\"value\":\"x\"}, "
                    + "{\"json_of_map\":\"info\"}, {\"json_of_list\":"
                    + "\"items\"} (JSON text of a map or list), "
                    + "trigonometry and more functions (sin, cos, tan, "
                    + "asin, acos, atan, exp, log, log10, to_degrees, "
                    + "to_radians, dip, e, display_width, "
                    + "display_height, replace, replace_first, "
                    + "index_in, last_index_in, matches, reverse_text, "
                    + "is_empty, parse_int, format_number with a "
                    + "pattern like \"0.00\", substring_from), and "
                    + "widget values {\"width_of\":\"view1\"} (also "
                    + "height_of, alpha_of, rotation_of, scale_x_of, "
                    + "scale_y_of, translation_x_of, translation_y_of "
                    + "for any widget), {\"rating_of\":\"ratingbar1\"}, "
                    + "{\"max_of\":\"seekbar1\"}, {\"page_of\":"
                    + "\"viewpager1\"}, {\"selection_of\":\"spinner1\"}, "
                    + "{\"selection_start_of\":\"edittext1\"}, "
                    + "{\"video_position_of\":\"videoview1\"}, "
                    + "{\"video_playing\":\"videoview1\"}, "
                    + "{\"web_progress_of\":\"webview1\"}, "
                    + "{\"can_go_back\":\"webview1\"}, "
                    + "{\"badge_count_of\":\"badgeview1\"}, "
                    + "{\"otp_text_of\":\"otpview1\"} (the code typed "
                    + "into an OTP view), {\"counter_max_of\":"
                    + "\"textinputlayout1\"}, {\"fn\":\"choose\","
                    + "\"condition\":...,\"a\":...,\"b\":...} (a if b, "
                    + "otherwise... a value picked by a condition), "
                    + "and {\"fn\":\"drawer_open\"} (whether the "
                    + "screen's drawer is open). Component blocks "
                    + "act on a component added with create_component "
                    + "(same screen) and name it in \"component\": "
                    + "Intent (set_intent_action/data/type {text}, "
                    + "put_extra {text,text2}, remove_extra {text}, "
                    + "launch_app {text}, start_with_chooser {text}); "
                    + "SharedPreferences (save_pref {text key, text2 "
                    + "value}, remove_pref {text}); Calendar "
                    + "(calendar_now, calendar_add/set {mode=YEAR|"
                    + "MONTH|DAY_OF_MONTH|HOUR_OF_DAY|MINUTE|SECOND, "
                    + "value}, calendar_set_time {value}); Vibrator "
                    + "(vibrate {value}); Timer (timer_after "
                    + "{delay,blocks}, timer_every {delay,period,"
                    + "blocks}, timer_cancel); Dialog (dialog_title/"
                    + "message {text}, dialog_show, dialog_ok_button "
                    + "{text,blocks}, dialog_negative_button, "
                    + "dialog_neutral_button); MediaPlayer (mp_create "
                    + "{sound}, mp_start/pause/release/reset, mp_seek "
                    + "{value}, mp_looping {flag}); SoundPool "
                    + "(sp_create {value}, sp_stop {value}); "
                    + "ObjectAnimator (anim_start/cancel, "
                    + "anim_duration {value}, anim_repeat {value}, "
                    + "anim_from_to {value,value2}, anim_target "
                    + "{target}, anim_property {text}); Gyroscope "
                    + "(gyro_start/stop); Firebase DB (fb_set {text "
                    + "path, variable=map}, fb_delete {text}, "
                    + "fb_push {variable=map}, fb_listen, "
                    + "fb_stop_listen); InterstitialAd (ad_load, "
                    + "ad_show); Camera (take_picture); FilePicker "
                    + "(pick_file); RequestNetwork (net_headers "
                    + "{variable=map}, net_start {mode=GET|POST|PUT|"
                    + "DELETE, text=url, text2=tag}); TextToSpeech "
                    + "(tts_speak {text}, tts_pitch/rate {value}, "
                    + "tts_stop, tts_shutdown); SpeechToText "
                    + "(stt_start/stop/shutdown); Bluetooth "
                    + "(bt_enable); LocationManager (loc_request "
                    + "{mode=GPS_PROVIDER|NETWORK_PROVIDER, value "
                    + "min ms, value2 min metres}, loc_remove); "
                    + "ProgressDialog (progress_create/show/dismiss, "
                    + "progress_message/title {text}, progress_value/"
                    + "max {value}, progress_cancelable/outside "
                    + "{flag}, progress_style {mode=STYLE_SPINNER|"
                    + "STYLE_HORIZONTAL}). Component values read "
                    + "live: {\"pref_get\":\"file1\",\"key\":\"name\"}, "
                    + "{\"pref_has\":...}, {\"calendar_millis\":"
                    + "\"calendar1\"}, {\"calendar_text\":...,\"format\":\"yyyy-MM-dd\"}, "
                    + "{\"calendar_diff\":...,\"other\":\"calendar2\"}, "
                    + "{\"mp_position_of\":\"mediaplayer1\"}, "
                    + "{\"mp_duration_of\":...}, {\"mp_playing\":...}, "
                    + "{\"mp_looping\":...}, {\"anim_running\":"
                    + "\"objectanimator1\"}, {\"tts_speaking\":\"texttospeech1\"}, "
                    + "{\"bt_enabled\":\"bluetoothconnect1\"}, "
                    + "{\"bt_on\":...}, {\"ad_loaded\":\"interstitialad1\"}, "
                    + "{\"fb_push_key\":\"firebase1\"}, {\"bt_uuid\":...}, "
                    + "{\"load_sound\":\"soundpool1\",\"sound\":\"click\"} "
                    + "(loads a raw sound, gives its number), "
                    + "{\"play_sound\":...,\"a\":sound number,\"b\":0} "
                    + "(0 = play once, -1 = loop), and functions "
                    + "{\"fn\":\"auth_email\"} (the signed-in user's "
                    + "email), auth_uid, auth_logged_in, is_connected "
                    + "(device online), {\"fn\":\"html\",\"a\":...} "
                    + "(HTML text), {\"fn\":\"hash_code\"}, "
                    + "{\"fn\":\"to_string\"} (whole number as "
                    + "text), {\"replace_all\":text,\"regex\":...,"
                    + "\"with\":...}, {\"instance_of\":text,\"class\":"
                    + "\"TextView\"}, {\"json_of_list_map\":\"items\"} "
                    + "(JSON of a list of maps). More widget "
                    + "blocks: MapView map_marker_color {text "
                    + "marker, mode=HUE_RED etc, value alpha}, "
                    + "map_marker_icon {text marker, image}; "
                    + "BubbleLayout bubble_color {color}, "
                    + "bubble_arrow_height/position/width {value}, "
                    + "bubble_corner_radius {value}, "
                    + "bubble_stroke_color {color}, "
                    + "bubble_stroke_width {value}; SignInButton "
                    + "signin_color_scheme {mode=COLOR_AUTO etc}, "
                    + "signin_size {mode=SIZE_STANDARD etc}; "
                    + "AdView banner_load_ad; any widget "
                    + "set_background_color_filter {color, mode="
                    + "SRC_IN etc}. More component blocks: "
                    + "Intent set_intent_flag {mode=NEW_TASK|"
                    + "CLEAR_TOP|SINGLE_TOP} (one flag at a time), "
                    + "send_broadcast; ObjectAnimator anim_value "
                    + "{value}, anim_repeat_mode {mode=RESTART|"
                    + "REVERSE}, anim_interpolator {mode=Linear|"
                    + "Accelerate|Decelerate|AccelerateDecelerate|"
                    + "Bounce}; Dialog dialog_icon {image}; "
                    + "RewardedVideoAd rewarded_load/rewarded_show "
                    + "(needs the project's ad setup to build); "
                    + "Firebase Auth auth_create_user {text email, "
                    + "text2 password}, auth_sign_in, "
                    + "auth_sign_in_anon, auth_reset_password "
                    + "{text email}, auth_sign_out (their result "
                    + "arrives in the component's own event — wire "
                    + "it with target = the component id, e.g. event "
                    + "onSignInUserComplete, and read "
                    + "{\"arg\":\"success\"} and "
                    + "{\"arg\":\"errorMessage\"}); Firebase Storage "
                    + "storage_upload {text local path, text2 "
                    + "storage path}, storage_download {text url, "
                    + "text2 local path}, storage_delete {text url}; "
                    + "Bluetooth bt_ready {text tag}, bt_ready_uuid "
                    + "{text uuid, text2 tag}, bt_connect {text "
                    + "address, text2 tag}, bt_connect_uuid, "
                    + "bt_disconnect {text tag}, bt_send {text data, "
                    + "text2 tag}, bt_paired {variable=list of maps "
                    + "to fill with name/address}; RequestNetwork "
                    + "net_params {variable=map, mode=REQUEST_PARAM|"
                    + "REQUEST_BODY}; Firebase DB container "
                    + "fb_get_children {component, variable=list of "
                    + "maps, blocks} (runs its blocks once the "
                    + "children load). Screen blocks: finish_all, "
                    + "light_status_bar, status_bar_color {color}, "
                    + "custom_toast {text, color text, value size, "
                    + "color2 background, value2 corner, mode="
                    + "TOP|CENTER|BOTTOM}, custom_toast_icon (plus "
                    + "image), the round floating button (no "
                    + "target: fab_icon {image}, fab_size {mode="
                    + "AUTO|MINI|NORMAL}, fab_visibility {mode=show|"
                    + "hide}), add_permission/remove_permission "
                    + "{text=CAMERA}, start_service/stop_service "
                    + "{screen}, menu_add_item {value id, text} and "
                    + "menu_inflate {menu} (only inside the "
                    + "onCreateOptionsMenu activity event), "
                    + "snackbar {target, text, action, blocks} "
                    + "(blocks run when its button is tapped), "
                    + "set_fragment_adapter {target=ViewPager, "
                    + "component, value tab count}. Repeat with a "
                    + "counter: repeat_index {times, name, blocks} "
                    + "and repeat_index_down — inside the blocks "
                    + "{\"var\":\"name\"} reads the counter. "
                    + "Values: {\"has_extra\":\"key\"} (opened with "
                    + "an extra), {\"list_map_value\":\"items\","
                    + "\"index\":...,\"key\":...}, {\"list_map_has\":...}, "
                    + "{\"pattern_md5_of\":\"patternview1\",\"list\":"
                    + "\"dots\"} (also pattern_sha1_of, "
                    + "pattern_text_of), {\"visibility_is\":\"button1\","
                    + "\"mode\":\"VISIBLE\"}, {\"can_pause\":\"videoview1\"} "
                    + "(also can_seek_back, can_seek_forward), "
                    + "{\"last_visible_of\":\"listview1\"}, "
                    + "{\"location_x_of\":\"view1\"} (screen position, "
                    + "also location_y_of), {\"public_dir\":"
                    + "\"DIRECTORY_DOWNLOADS\"}, {\"jpeg_rotation\":path}"
                    + ", {\"asset_text\":\"stream1\"} (needs "
                    + "open_asset {name, text path} earlier in the "
                    + "same handler), {\"fn\":\"list_scroll_state\","
                    + "\"mode\":\"SCROLL_STATE_IDLE\"} (also "
                    + "pager_scroll_state, recycler_scroll_state). "
                    + "Event values: inside a component or activity "
                    + "event, {\"arg\":\"name\"} reads a value the "
                    + "event itself passes — when a name is wrong, "
                    + "the refusal lists what that event passes. "
                    + "Firebase child events pass childKey (text) and "
                    + "childValue (a set of values: read one with "
                    + "{\"map_get\":{\"arg\":\"childValue\"},\"key\":"
                    + "\"name\"}); onCancelled passes errorCode "
                    + "(number) and errorMessage; the auth completion "
                    + "events pass success (true/false) and "
                    + "errorMessage; storage passes progressValue, "
                    + "downloadUrl or totalByteCount; network "
                    + "onResponse passes tag, response and "
                    + "responseHeaders (a set of values); speech "
                    + "passes result; Bluetooth passes tag and data; "
                    + "doInBackground passes param, onProgressUpdate "
                    + "passes value, onPostExecute passes result; "
                    + "onDateSet passes year, month and day. To "
                    + "show one in a toast or on a label, "
                    + "set_variable it into a string variable "
                    + "first, then toast {from_variable} or "
                    + "set_text {from_variable}. "
                    + "Component-event blocks: return_fragment "
                    + "{screen} (the last block of a Fragment "
                    + "Adapter's onFragmentAdded: which screen each "
                    + "page shows), return_title {title} (last block "
                    + "of onTabAdded), return_text {value} (last "
                    + "block of an AsyncTask's doInBackground: the "
                    + "result onPostExecute gets), asynctask_execute "
                    + "{component, message} (start a background "
                    + "task), asynctask_publish {value} (progress, "
                    + "only inside doInBackground), datepicker_show "
                    + "{} (shows the date picker; needs a "
                    + "date_picker component whose onDateSet handler "
                    + "takes the picked date), timepicker_show "
                    + "{component} (its onTimeSet handler takes the "
                    + "picked time). Custom list rows: give a "
                    + "ListView, GridView, RecyclerView, ViewPager "
                    + "or Spinner an item layout (create_custom_view, "
                    + "then edit_screen op set_list_item_view), fill "
                    + "it with list_custom_data / grid_custom_data / "
                    + "recycler_custom_data / pager_custom_data / "
                    + "spinner_custom_data {target, list} (a list of "
                    + "maps), and dress each row in the widget's "
                    + "onBindCustomView event: there the targets are "
                    + "the item layout's own widgets, "
                    + "{\"arg\":\"position\"} is the row number, and "
                    + "{\"list_map_value\": list, \"index\": "
                    + "{\"arg\":\"position\"}, \"key\": ...} reads "
                    + "that row's values. Menus (only inside the "
                    + "activity's onCreateOptionsMenu event): "
                    + "menu_item {name, id, title, icon, action} "
                    + "declares a named item (action is always, "
                    + "if_room, never, with_text or collapse), "
                    + "submenu {name, title, blocks} declares a "
                    + "submenu whose blocks are submenu_item "
                    + "{submenu, id, title}, and menu_item_visible / "
                    + "menu_item_enabled {name, flag} show, hide, "
                    + "enable or disable a declared item. Listener "
                    + "bodies: otp_listener {target, blocks} runs "
                    + "when a full code is typed into an OTPView — "
                    + "{\"arg\":\"otp\"} is the code inside; "
                    + "youtube_listener {target, video_id, blocks} "
                    + "loads a video when a YouTube player is ready, "
                    + "then runs its blocks. A YouTube player also "
                    + "needs youtube_lifecycle {target} once (it "
                    + "registers the player with the screen's "
                    + "lifecycle). Images: crop_image {text path, "
                    + "value request code} opens the crop screen "
                    + "for an image file; its answer arrives in "
                    + "onActivityResult under that code. Typed map stores: "
                    + "map_put_number {variable, "
                    + "key, value}, map_put_flag {variable, key, "
                    + "flag}, map_put_list {variable, key, list}, "
                    + "map_put_map {variable, key, from_variable}; "
                    + "list blocks add_all_to_list {list, from}, "
                    + "set_map_in_list {variable=map, index, list}, "
                    + "remove_map_from_list {variable=map, list}, "
                    + "split_into_list {text, text2 separator, "
                    + "list=list of text}; image-file blocks "
                    + "(text=from path, text2=to path): scale_bitmap "
                    + "{value width, value2 height}, "
                    + "resize_bitmap_ratio {value max size}, "
                    + "resize_bitmap_square {value}, "
                    + "resize_bitmap_circle, resize_bitmap_rounded "
                    + "{value radius}, crop_bitmap_center {value, "
                    + "value2}, rotate_bitmap {value degrees}, "
                    + "skew_bitmap {value, value2}, "
                    + "bitmap_brightness {value}, bitmap_contrast "
                    + "{value}, bitmap_color_filter {color}; "
                    + "= between texts "
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
                    "The widget id from get_screen, a component's id "
                            + "(e.g. firebase1) for that component's "
                            + "events, or \"activity\" for the screen's "
                            + "own events"));
            properties.add("event", stringSchema(
                    "The event, e.g. onClick for a tap, onCreate for "
                            + "when an activity-targeted screen starts, "
                            + "onChildAdded for a Firebase component"));
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
                    "write_file", "delete_file", "make_folder",
                    "copy_file", "move_file", "copy_folder",
                    "rename_file", "list_files", "remove_from_list",
                    "insert_in_list", "set_in_list", "reverse_list",
                    "shuffle_list", "sort_list", "swap_in_list",
                    "remove_map_key", "map_keys_to_list",
                    "get_map_from_list", "add_map_to_list",
                    "insert_map_to_list", "add_map_entry",
                    "insert_map_entry", "set_map_entry",
                    "map_from_json", "list_from_json",
                    "set_lines", "select_all_text", "set_max_lines",
                    "set_selection", "show_error", "set_single_line",
                    "disable_suggestions", "set_text_size",
                    "set_hint_color", "set_background_color",
                    "set_background_image", "set_background_resource",
                    "set_image_resource", "set_image_by_name",
                    "set_image_file", "set_color_filter", "set_elevation",
                    "set_alpha", "set_rotation", "set_scale_x",
                    "set_scale_y", "set_translation_x",
                    "set_translation_y", "set_corner_radius",
                    "set_stroke", "set_gradient", "set_radius_and_stroke",
                    "set_gravity", "request_focus", "perform_click",
                    "remove_all_views", "add_view", "add_view_at",
                    "remove_view", "set_checked", "set_max",
                    "set_indeterminate", "set_num_stars", "set_rating",
                    "set_step_size", "play_video", "pause_video",
                    "stop_video", "set_video_url", "web_go_back",
                    "web_go_forward", "web_clear_cache",
                    "web_clear_history", "web_stop_loading",
                    "web_zoom_in", "web_zoom_out", "set_cache_mode",
                    "set_autocomplete_data", "set_multiautocomplete_data",
                    "set_threshold", "set_tokenizer",
                    "refresh_list", "refresh_list_views",
                    "list_smooth_scroll", "list_set_selection",
                    "list_stack_from_bottom", "list_transcript_mode",
                    "add_header", "add_footer", "remove_header",
                    "remove_footer", "set_spinner_data",
                    "refresh_spinner", "set_spinner_selection",
                    "set_pager_page", "set_pager_limit",
                    "notify_pager_changed", "recycler_layout",
                    "recycler_layout_horizontal", "recycler_fixed_size",
                    "recycler_scroll_to", "recycler_scroll_offset",
                    "grid_columns", "grid_column_width", "grid_spacing_h",
                    "grid_spacing_v", "grid_stretch_mode", "add_tab",
                    "set_tab_colors", "set_tab_indicator_color",
                    "set_tab_indicator_height", "set_tab_ripple_color",
                    "set_inline_label", "setup_with_pager",
                    "set_card_color", "set_card_elevation",
                    "set_card_radius", "set_prevent_overlap",
                    "set_compat_padding", "set_picker_hour",
                    "set_picker_minute", "set_24hour",
                    "set_calendar_date", "set_calendar_min_date",
                    "set_calendar_max_date", "til_box_color",
                    "til_box_stroke", "til_box_mode", "til_corner_radii",
                    "til_counter_enabled", "til_counter_max",
                    "til_set_error", "til_error_enabled",
                    "set_badge_number", "set_badge_text",
                    "set_badge_background", "set_badge_text_color",
                    "set_badge_text_size", "clear_pattern",
                    "pattern_dot_count", "pattern_correct_color",
                    "pattern_normal_color", "pattern_wrong_color",
                    "pattern_view_mode", "set_otp_text",
                    "set_otp_field_count", "set_lottie_asset",
                    "set_lottie_json", "set_lottie_url",
                    "set_lottie_repeat", "set_lottie_speed",
                    "set_code_text", "apply_code", "set_code_language",
                    "set_code_theme", "map_zoom_in", "map_zoom_out",
                    "map_zoom_to", "map_move_camera", "map_add_marker",
                    "map_set_type", "map_marker_position",
                    "map_marker_visible", "map_marker_info",
                    "set_refreshing", "bottom_add_item",
                    "if", "if_else", "repeat", "forever", "break",
                    "while", "try_catch", "switch", "continue",
                    "set_intent_action", "set_intent_data",
                    "set_intent_type", "put_extra", "remove_extra",
                    "launch_app", "start_with_chooser", "save_pref",
                    "remove_pref", "calendar_now", "calendar_add",
                    "calendar_set", "calendar_set_time", "vibrate",
                    "timer_cancel", "dialog_title", "dialog_message",
                    "dialog_show", "mp_create", "mp_start", "mp_pause",
                    "mp_release", "mp_reset", "mp_seek", "mp_looping",
                    "sp_create", "sp_stop", "anim_start", "anim_cancel",
                    "anim_duration", "anim_repeat", "anim_from_to",
                    "anim_target", "anim_property", "gyro_start",
                    "gyro_stop", "fb_set", "fb_delete", "fb_push",
                    "fb_listen", "fb_stop_listen", "ad_load", "ad_show",
                    "take_picture", "pick_file", "net_headers",
                    "net_start", "tts_speak", "tts_pitch", "tts_rate",
                    "tts_stop", "tts_shutdown", "stt_start", "stt_stop",
                    "stt_shutdown", "bt_enable", "loc_request",
                    "loc_remove", "progress_create", "progress_show",
                    "progress_dismiss", "progress_message",
                    "progress_title", "progress_value", "progress_max",
                    "progress_cancelable", "progress_outside",
                    "progress_style", "timer_after", "timer_every",
                    "dialog_ok_button", "dialog_negative_button",
                    "dialog_neutral_button", "set_intent_flag",
                    "anim_value", "anim_repeat_mode",
                    "anim_interpolator", "dialog_icon",
                    "rewarded_load", "rewarded_show",
                    "auth_create_user", "auth_sign_in",
                    "auth_sign_in_anon", "auth_reset_password",
                    "auth_sign_out", "storage_upload",
                    "storage_download", "storage_delete", "bt_ready",
                    "bt_ready_uuid", "bt_connect", "bt_connect_uuid",
                    "bt_disconnect", "bt_send", "bt_paired",
                    "net_params", "send_broadcast", "finish_all",
                    "light_status_bar", "status_bar_color",
                    "custom_toast", "custom_toast_icon", "fab_icon",
                    "fab_size", "fab_visibility", "add_permission",
                    "remove_permission", "map_put_number",
                    "map_put_flag", "map_put_list", "map_put_map",
                    "add_all_to_list", "set_map_in_list",
                    "remove_map_from_list", "split_into_list",
                    "open_asset", "scale_bitmap", "resize_bitmap_ratio",
                    "resize_bitmap_square", "resize_bitmap_circle",
                    "resize_bitmap_rounded", "crop_bitmap_center",
                    "rotate_bitmap", "skew_bitmap",
                    "bitmap_brightness", "bitmap_contrast",
                    "bitmap_color_filter", "set_fragment_adapter",
                    "menu_add_item", "menu_inflate", "snackbar",
                    "fb_get_children", "repeat_index",
                    "repeat_index_down", "start_service",
                    "stop_service", "map_marker_color",
                    "map_marker_icon", "bubble_arrow_height",
                    "bubble_arrow_position", "bubble_arrow_width",
                    "bubble_color", "bubble_corner_radius",
                    "bubble_stroke_color", "bubble_stroke_width",
                    "signin_color_scheme", "signin_size",
                    "banner_load_ad",
                    "set_background_color_filter", "return_fragment",
                    "return_title", "return_text", "asynctask_execute",
                    "asynctask_publish", "datepicker_show",
                    "timepicker_show", "list_custom_data",
                    "grid_custom_data", "recycler_custom_data",
                    "pager_custom_data", "spinner_custom_data",
                    "menu_item", "submenu", "submenu_item",
                    "menu_item_visible", "menu_item_enabled",
                    "otp_listener", "youtube_listener",
                    "crop_image", "youtube_lifecycle"}) {
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
            return "Wants to wire " + stringArg(args, "target")
                    + " " + stringArg(args, "event") + " on "
                    + stringArg(args, "activity");
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
                    + "list_string, list_number, list_map. You must say "
                    + "what will use it, in \"used_by\" — the event and "
                    + "what it does with the value, e.g. \"save_btn "
                    + "onClick sets it from note_input and file1 saves "
                    + "it\". If you cannot say what will use it, you do "
                    + "not need the variable: do not declare it. "
                    + "Declaring variables \"in case\" fills the "
                    + "user's project with names nothing reads, and "
                    + "they have to reject them by hand. Declare a "
                    + "variable at the moment you have the blocks that "
                    + "use it, not before. A new "
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
            properties.add("used_by", stringSchema(
                    "What will read or write this variable — name the "
                            + "event and what it does, e.g. \"save_btn "
                            + "onClick sets it from note_input\". No "
                            + "answer, no variable."));
            return AgentTools.parameters(properties,
                    "activity", "name", "type", "used_by");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to add the variable "
                    + stringArg(args, "name");
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
            // The gate. A variable with no stated use is how a
            // two-screen notes app ended up with eight of them and a
            // user rejecting half by hand.
            String usedBy = stringArg(args, "used_by");
            if (usedBy.length() < 8) {
                throw new ToolException("E_UNUSED_VARIABLE",
                        "Say what will use \"" + stringArg(args, "name")
                                + "\" before declaring it — pass "
                                + "\"used_by\" naming the event and what "
                                + "it does with the value, e.g. \"save_btn "
                                + "onClick sets it from note_input and "
                                + "file1 saves it\". If nothing will use "
                                + "it yet, do not declare it: come back "
                                + "when you have the blocks that need it.");
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
            clean.addProperty("used_by", stringArg(args, "used_by"));
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
                            + startValue + ")"
                            + "\nTo be used by: "
                            + stringArg(args, "used_by"),
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


    /**
     * remove_variable: delete a variable the agent declared and never
     * used. The removal call is the app's own — LogicEditorActivity's
     * Remove-variable button calls jC.a(scId).p(javaName, name).
     */
    private static final class RemoveVariable implements Tool {

        @Override
        public String name() {
            return "remove_variable";
        }

        @Override
        public String description() {
            return "Proposes deleting one variable from one screen — "
                    + "the same deletion Sketchware's own Remove "
                    + "variable dialog performs. Use it to clean up a "
                    + "variable you declared and then never used. A "
                    + "variable that any block still reads or writes "
                    + "cannot be removed: wire the blocks to something "
                    + "else first, or remove those blocks. The user "
                    + "must approve before anything is deleted.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen, e.g. MainActivity"));
            properties.add("name", stringSchema(
                    "The variable to remove, e.g. notesListText"));
            return AgentTools.parameters(properties,
                    "activity", "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to remove the variable "
                    + stringArg(args, "name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        private ProjectFileBean screen(Session session, JsonObject args)
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
                        "Custom views have no variables. Pick an "
                                + "activity screen instead.");
            }
            return activity;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            ProjectFileBean activity = screen(session, args);
            String name = stringArg(args, "name");
            String javaName = activity.getJavaName();
            eC store = a.a.a.jC.a(scId);
            boolean declared = false;
            for (android.util.Pair<Integer, String> pair
                    : safeVariables(store, javaName)) {
                if (name.equals(pair.second)) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                throw new ToolException("E_NO_VARIABLE",
                        "\"" + name + "\" is not declared on "
                                + SketchwareProjects.activityName(activity)
                                + ", so there is nothing to remove. "
                                + "Its variables are: "
                                + variableNames(store, javaName) + ".");
            }
            String where = AgentLogic.variableUse(scId, javaName, name);
            if (where != null) {
                throw new ToolException("E_VARIABLE_IN_USE",
                        "\"" + name + "\" is still used — " + where
                                + ". Remove or change those blocks "
                                + "first; deleting a variable they name "
                                + "would leave the app unbuildable.");
            }
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", activity.fileName);
            clean.addProperty("name", name);
            return new AgentProposal(name(), clean, scId,
                    "Remove a variable from "
                            + SketchwareProjects.activityName(activity)
                            + " in "
                            + SketchwareProjects.project(scId).name,
                    "Remove " + name
                            + " — no block on that screen uses it",
                    "Deletes the variable from the screen, the same "
                            + "way Sketchware's Remove variable dialog "
                            + "does. A safety backup of the project is "
                            + "made first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            if (!scId.equals(session.workingProjectId)) {
                throw new ToolException("E_PROJECT_CHANGED",
                        "The working project changed since this was "
                                + "proposed, so nothing was removed.");
            }
            ProjectFileBean activity = screen(session, proposal.args);
            String name = stringArg(proposal.args, "name");
            String javaName = activity.getJavaName();
            eC store = a.a.a.jC.a(scId);
            try {
                SketchwareActions.backupProjectOnce(scId);
                // The app's own removal (LogicEditorActivity.m):
                // jC.a(scId).p(M.getJavaName(), str)
                store.p(javaName, name);
                SketchwareActions.commitProject(scId);
            } catch (Exception e) {
                throw new ToolException("E_APPLY_FAILED",
                        "Removing the variable failed: "
                                + String.valueOf(e.getMessage()));
            }
            for (android.util.Pair<Integer, String> pair
                    : safeVariables(store, javaName)) {
                if (name.equals(pair.second)) {
                    throw new ToolException("E_APPLY_FAILED",
                            "The variable was still there after the "
                                    + "removal, so nothing was changed.");
                }
            }
            JsonObject result = new JsonObject();
            result.addProperty("removed", name);
            result.addProperty("verified",
                    SketchwareProjects.activityName(activity)
                            + " no longer declares " + name
                            + " (read back and checked).");
            return result;
        }

        private static ArrayList<android.util.Pair<Integer, String>>
                safeVariables(eC store, String javaName) {
            try {
                ArrayList<android.util.Pair<Integer, String>> stored =
                        store.k(javaName);
                return stored == null
                        ? new ArrayList<>() : stored;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }

        private static String variableNames(eC store, String javaName) {
            StringBuilder out = new StringBuilder();
            for (android.util.Pair<Integer, String> pair
                    : safeVariables(store, javaName)) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(pair.second);
            }
            return out.length() == 0 ? "none" : out.toString();
        }
    }


    /**
     * The create_component keyword for a ComponentBean type, or "" if
     * the type is not one the tool can add. Used to tell a model not
     * just that a block needs a component, but which kind and how to
     * add one.
     */
    public static String componentTypeKeyword(int componentType) {
        for (Object[] row : CreateComponent.TYPES) {
            if (row[1] instanceof Integer number
                    && number == componentType) {
                return String.valueOf(row[0]);
            }
        }
        return "";
    }

    /** create_component: add a component of any stock kind. */
    private static final class CreateComponent implements Tool {
        private static final Pattern NAME_PATTERN =
                Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

        @Override
        public String name() {
            return "create_component";
        }

        @Override
        public String description() {
            return "Proposes adding a component to one screen, "
                    + "the way Sketchware's own component picker "
                    + "adds it. The \"type\" names the kind: intent, "
                    + "shared_preferences, calendar, vibrator, timer, "
                    + "firebase_db, dialog, media_player, sound_pool, "
                    + "object_animator, gyroscope, firebase_auth, "
                    + "interstitial_ad, firebase_storage, camera, "
                    + "file_picker, request_network, text_to_speech, "
                    + "speech_to_text, bluetooth, location, "
                    + "rewarded_ad, progress_dialog, date_picker, "
                    + "time_picker, notification, fragment_adapter, "
                    + "phone_auth, cloud_message, google_login, "
                    + "asynctask. A "
                    + "component unlocks its own block family on that "
                    + "screen (a Timer unlocks timer_after/timer_every, "
                    + "a Dialog its title/message/button blocks, and "
                    + "so on). Most components also have their own "
                    + "events — wire them with set_event_handler using "
                    + "the component's id as the target (an AsyncTask "
                    + "does its work in its doInBackground event, a "
                    + "Firebase DB reports changes in onChildAdded). "
                    + "Firebase and ad components also need "
                    + "the project's Firebase/ad setup before a build "
                    + "can pass. The user must approve before "
                    + "anything is added.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen, e.g. MainActivity"));
            properties.add("type", stringSchema(
                    "The component kind, e.g. timer, dialog, "
                            + "media_player (optional; intent is "
                            + "used when omitted)"));
            properties.add("name", stringSchema(
                    "The component's name, e.g. timer1 (optional; "
                            + "the next free name for its kind is "
                            + "used)"));
            return AgentTools.parameters(properties, "activity");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String component = stringArg(args, "name");
            String type = stringArg(args, "type");
            if (component.isEmpty()) {
                return "Wants to add a " + type + " component";
            }
            return "Wants to add the " + component + " "
                    + type + " component";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        private record Checked(ProjectFileBean activity, String name,
                               int type, String label) {
        }

        /** Model type name, ComponentBean type, id prefix, label. */
        private static final Object[][] TYPES = {
                {"intent", 1, "intent", "Intent"},
                {"shared_preferences", 2, "file", "SharedPreferences"},
                {"calendar", 3, "calendar", "Calendar"},
                {"vibrator", 4, "vibrator", "Vibrator"},
                {"timer", 5, "timer", "Timer"},
                {"firebase_db", 6, "firebase", "Firebase DB"},
                {"dialog", 7, "dialog", "Dialog"},
                {"media_player", 8, "mediaplayer", "MediaPlayer"},
                {"sound_pool", 9, "soundpool", "SoundPool"},
                {"object_animator", 10, "objectanimator",
                        "ObjectAnimator"},
                {"gyroscope", 11, "gyroscope", "Gyroscope"},
                {"firebase_auth", 12, "firebaseauth", "Firebase Auth"},
                {"interstitial_ad", 13, "interstitialad",
                        "Interstitial Ad"},
                {"firebase_storage", 14, "firebasestorage",
                        "Firebase Storage"},
                {"camera", 15, "camera", "Camera"},
                {"file_picker", 16, "filepicker", "FilePicker"},
                {"request_network", 17, "requestnetwork",
                        "RequestNetwork"},
                {"text_to_speech", 18, "texttospeech", "TextToSpeech"},
                {"speech_to_text", 19, "speechtotext", "SpeechToText"},
                {"bluetooth", 20, "bluetoothconnect",
                        "BluetoothConnect"},
                {"location", 21, "locationmanager", "LocationManager"},
                {"rewarded_ad", 22, "rewardedvideoad",
                        "Rewarded Video Ad"},
                {"progress_dialog", 23, "progressdialog",
                        "ProgressDialog"},
                {"date_picker", 24, "datepickerdialog",
                        "DatePickerDialog"},
                {"time_picker", 25, "timepickerdialog",
                        "TimePickerDialog"},
                {"notification", 26, "notification", "Notification"},
                {"fragment_adapter", 27, "fragmentadapter",
                        "FragmentAdapter"},
                {"phone_auth", 28, "firebasephoneauth", "Phone Auth"},
                {"cloud_message", 30, "firebasecloudmessage",
                        "Cloud Message"},
                {"google_login", 31, "firebasegooglelogin",
                        "Google Login"},
                {"asynctask", 36, "asynctask", "AsyncTask"},
        };

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
            String typeName = stringArg(args, "type");
            if (typeName.isEmpty()) {
                typeName = "intent";
            }
            Object[] typeRow = null;
            for (Object[] row : TYPES) {
                if (row[0].equals(typeName)) {
                    typeRow = row;
                    break;
                }
            }
            if (typeRow == null) {
                throw new ToolException("E_BAD_TYPE",
                        "Unknown component type \"" + typeName
                                + "\". Use one of: intent, "
                                + "shared_preferences, calendar, "
                                + "vibrator, timer, firebase_db, "
                                + "dialog, media_player, sound_pool, "
                                + "object_animator, gyroscope, "
                                + "firebase_auth, interstitial_ad, "
                                + "firebase_storage, camera, "
                                + "file_picker, request_network, "
                                + "text_to_speech, speech_to_text, "
                                + "bluetooth, location, rewarded_ad, "
                                + "progress_dialog, date_picker, "
                                + "time_picker, notification, "
                                + "fragment_adapter, phone_auth, "
                                + "cloud_message, google_login.");
            }
            int componentType = (Integer) typeRow[1];
            // The editor's own component picker refuses a component
            // whose library has not been set up (Firebase, AdMob,
            // AppCompat — InnerAddComponentBottomSheet). The agent
            // refuses it the same way, naming the switch to turn on,
            // instead of adding something that can never run. Checked
            // here so both the proposal and its replayed execution
            // agree.
            String needed =
                    AgentSetup.requirementForComponent(componentType);
            if (needed != null) {
                JsonObject libraries = AgentSetup.libraries(scId);
                if (libraries.has(needed)
                        && !libraries.get(needed).getAsBoolean()) {
                    throw new ToolException("E_LIBRARY_REQUIRED",
                            AgentSetup.requirementMessage(needed,
                                    "a " + typeRow[3] + " component",
                                    false));
                }
                if (AgentSetup.needsFirebaseAuthSetup(componentType)) {
                    boolean configured = libraries
                            .has("firebase_auth_configured")
                            && libraries.get("firebase_auth_configured")
                                    .getAsBoolean();
                    if (!configured) {
                        throw new ToolException("E_LIBRARY_REQUIRED",
                                AgentSetup.requirementMessage("firebase",
                                        "a " + typeRow[3] + " component",
                                        true));
                    }
                }
            }
            String prefix = (String) typeRow[2];
            String name = stringArg(args, "name");
            if (name.isEmpty()) {
                int n = 1;
                while (taken.contains(prefix + n)) {
                    n++;
                }
                name = prefix + n;
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
            return new Checked(activity, name, componentType,
                    (String) typeRow[3]);
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
            clean.addProperty("type", stringArg(args, "type"));
            return new AgentProposal(name(), clean, scId,
                    "Add a component to "
                            + SketchwareProjects.activityName(
                                    checked.activity())
                            + " in " + project.name,
                    "New " + checked.label() + " component: "
                            + checked.name(),
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
                store.a(javaName, checked.type(), checked.name());
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
                                && component.type == checked.type()) {
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
            // A second component of the same kind is rarely what was
            // meant: a model that cannot see the one it already made
            // keeps adding more — a screen in one conversation ended up
            // with four shared-preference components and used none.
            String already = "";
            try {
                ArrayList<com.besome.sketch.beans.ComponentBean>
                        components = store.e(javaName);
                if (components != null) {
                    for (com.besome.sketch.beans.ComponentBean component
                            : components) {
                        if (component.type == checked.type()
                                && !checked.name().equals(
                                        component.componentId)) {
                            already = String.valueOf(
                                    component.componentId);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // A hint that cannot be checked is simply not given.
            }
            JsonObject result = new JsonObject();
            result.addProperty("added", checked.name());
            result.addProperty("kind", checked.label());
            if (!already.isEmpty()) {
                result.addProperty("note",
                        "This screen already had a "
                                + checked.label() + " component: "
                                + already + ". Use that one — two of the "
                                + "same kind means two separate stores, "
                                + "and data saved in one is not in the "
                                + "other.");
            }
            result.addProperty("verified",
                    SketchwareProjects.activityName(checked.activity())
                            + " now has a " + checked.label()
                            + " component named "
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
            JsonObject result = AgentBuild.build(session.context, scId);
            // Compiling is not proof the app runs: a block that names
            // a widget its screen does not have compiles cleanly and
            // then crashes on launch. The build report says so, in
            // the same breath as the compile result.
            List<String> broken =
                    AgentLogic.danglingWidgetReferences(scId);
            if (!broken.isEmpty()) {
                StringBuilder text = new StringBuilder();
                for (String finding : broken) {
                    if (text.length() > 0) {
                        text.append("\n");
                    }
                    text.append("• ").append(finding);
                }
                result.add("runtimeWarnings", namesJson(broken));
                String previous = result.has("message")
                        ? result.get("message").getAsString() : "";
                result.addProperty("message",
                        (previous.isEmpty() ? "" : previous + "\n\n")
                                + "It compiled, but this project will "
                                + "crash when it opens until these are "
                                + "fixed — a block names a widget that is "
                                + "not on its screen:\n" + text
                                + "\nFix them, then build again.");
            }
            return result;
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
                    "The widget id from get_screen, a component's id "
                            + "for that component's events, or "
                            + "\"activity\" for the screen's own events"));
            properties.add("event", stringSchema(
                    "The event, e.g. onClick"));
            return AgentTools.parameters(properties,
                    "activity", "target", "event");
        }

        @Override
        public String activityLabel(JsonObject args) {
            // The live line must name the exact thing: five checks
            // of five different handlers must never read as the same
            // sentence repeated — that looks like a stuck app.
            String activity = stringArg(args, "activity");
            String target = stringArg(args, "target");
            String event = stringArg(args, "event");
            StringBuilder label = new StringBuilder("Checking ");
            if (target.isEmpty() || "activity".equals(target)) {
                label.append(activity.isEmpty() ? "the screen" : activity);
            } else {
                label.append(target);
                if (!activity.isEmpty()) {
                    label.append(" in ").append(activity);
                }
            }
            if (!event.isEmpty()) {
                label.append(" ").append(event);
            }
            return label.toString();
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
                    + stringArg(args, "target") + " "
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
            // The app checks the project itself before the user is
            // asked anything: colours that are already in place are
            // not a change, and must not produce another card. A key
            // the project has never stored counts as matching only a
            // true default, so nothing can be "already set" that is
            // not really there.
            boolean alreadySet = !colours.entrySet().isEmpty();
            for (java.util.Map.Entry<String, JsonElement> entry
                    : colours.entrySet()) {
                Integer after = plan.after.get(entry.getKey());
                Integer before = AgentLogic.themeColour(scId,
                        entry.getKey());
                if (after == null) {
                    alreadySet = false;
                    break;
                }
                if (before == null ? after != 0
                        : !before.equals(after)) {
                    alreadySet = false;
                    break;
                }
            }
            if (alreadySet) {
                throw new ToolException("E_ALREADY_SET",
                        "Nothing to change: " + plan.sentence
                                .replace('\n', ' ')
                                + " — the project already uses exactly "
                                + "those colours. Continue with the next "
                                + "step instead of proposing this again.");
            }
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

    /**
     * ask_choice: a real question with named options, answered by the
     * user. Design decisions have taste in them, and a model guessing
     * at taste is how an app ends up looking like nothing the user
     * wanted. This is the brainstorming step: two to four whole
     * directions, and the user picks one.
     */
    private static final class AskChoice implements Tool {
        @Override
        public String name() {
            return "ask_choice";
        }

        @Override
        public String description() {
            return "Asks the user to choose between two to four options "
                    + "you name, and pauses until they answer. Use it for "
                    + "any decision that is theirs to make: how the app "
                    + "should look, which way data should be kept, which "
                    + "flow to build. Each option must be a whole, "
                    + "specific direction — “sunny and playful: big "
                    + "rounded cards, orange #FF8A3D, cream background” "
                    + "— not a bare word like “modern”. You get their "
                    + "answer back as text; then save what was agreed "
                    + "(set_style_brief for a look, set_plan for a plan) "
                    + "and carry on. Never use it for something you can "
                    + "decide yourself, and never ask more than one "
                    + "question at a time in chat — ask here instead.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("question", stringSchema(
                    "The question, in the user's words, e.g. “How "
                            + "should the notes app look?”"));
            JsonObject options = new JsonObject();
            options.addProperty("type", "array");
            options.addProperty("description",
                    "Two to four options, each a whole direction in one "
                            + "sentence");
            JsonObject item = new JsonObject();
            item.addProperty("type", "string");
            options.add("items", item);
            properties.add("options", options);
            return AgentTools.parameters(properties, "question", "options");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Asked you to choose";
        }

        @Override
        public boolean interactive() {
            return true;
        }
    }

    /** set_style_brief: remembers the agreed style direction. */
    private static final class SetStyleBrief implements Tool {
        @Override
        public String name() {
            return "set_style_brief";
        }

        @Override
        public String description() {
            return "Saves the style direction the user has agreed for "
                    + "this project ('for young kids', 'dark and minimal', "
                    + "'friendly rounded shapes, our brand green') to the "
                    + "app's memory, so it survives the chat and any change "
                    + "of key or model. Call it as soon as a direction is "
                    + "agreed — no approval card, it changes nothing in the "
                    + "project itself. Write it in headed lines so it can "
                    + "be followed exactly: Audience; Mood; Colours (name "
                    + "the actual colours, e.g. 'primary cheerful orange "
                    + "#FF8A3D'); Text sizes (title/body/button sp); "
                    + "Buttons and cards (height, corner roundness); "
                    + "Spacing (screen padding, gaps); Do; Avoid. A new "
                    + "brief replaces the old one, so include anything "
                    + "that should carry over. If no project is chosen "
                    + "yet, the brief is held as a draft and lands in the "
                    + "project this work creates.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("brief", stringSchema(
                    "The style direction in plain words, e.g. \"For "
                            + "young kids: big readable text, large "
                            + "buttons, bright friendly colours, rounded "
                            + "shapes, simple layouts\""));
            return AgentTools.parameters(properties, "brief");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Remembered the project's style direction";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String brief = stringArg(args, "brief");
            if (brief.isEmpty()) {
                throw new ToolException("E_BAD_ARGS",
                        "Pass the agreed style direction in \"brief\".");
            }
            // Half a sentence is not a style: it is what makes every
            // screen afterwards look accidental. Require the detail the
            // builder is going to follow.
            if (brief.trim().length() < 140) {
                throw new ToolException("E_STYLE_TOO_THIN",
                        "That style is too thin to build from. A real one "
                                + "is headed lines: Audience; Mood; "
                                + "Colours (name each with its hex, e.g. "
                                + "primary #FF8A3D, background #FFF8F0, "
                                + "text #2B2B2B); Text sizes (title/body/"
                                + "button in sp); Buttons and cards "
                                + "(height, corner roundness); Spacing "
                                + "(screen padding, gaps); Do; Avoid. "
                                + "Write it in full — it is what the whole "
                                + "app is built to.");
            }
            JsonObject result = new JsonObject();
            if (session.workingProjectId == null
                    || session.workingProjectId.isEmpty()) {
                // Agreed before any project exists: held as a draft
                // and deposited into the project this work creates.
                AgentJournal.setDraftStyleBrief(brief);
                result.addProperty("saved", "draft");
                result.addProperty("message",
                        "Style remembered as a draft. When the project "
                                + "is created it becomes that project's "
                                + "style automatically.");
                return result;
            }
            String scId = requiredProject(session.workingProjectId);
            AgentJournal.setStyleBrief(scId, brief);
            result.addProperty("saved", true);
            result.addProperty("message",
                    "Style brief remembered for this project. Keep to it "
                            + "in everything you make here.");
            return result;
        }
    }

    /** set_plan: remembers the agreed implementation plan. */
    private static final class SetPlan implements Tool {
        @Override
        public String name() {
            return "set_plan";
        }

        @Override
        public String description() {
            return "Saves the implementation plan the user has agreed "
                    + "for this project — the start-to-finish build "
                    + "order — to the app's memory, where the Plan "
                    + "button shows it with its progress. Call it right "
                    + "after the plan is agreed, before building, and "
                    + "again whenever the plan changes or a milestone "
                    + "lands: pass the full step list each time, with "
                    + "\"done_steps\" set to how many leading steps are "
                    + "genuinely finished (check the real project "
                    + "first — the app will not let done steps run "
                    + "ahead of the changes it has recorded) and "
                    + "\"current_step\" as the 0-based step in hand. "
                    + "Steps are milestones ('Build the Quiz screen "
                    + "with its timer and saved best score'), not "
                    + "single tool calls, and should name the behaviour "
                    + "too: where buttons lead, what is saved, what "
                    + "happens at the end. For a small one-off request, "
                    + "no plan is needed. No approval card — it changes "
                    + "nothing in the project itself. With no project "
                    + "chosen yet, the plan is held as a draft and "
                    + "lands in the project this work creates. "
                    + "IMPORTANT: once saved, the plan is settled — a "
                    + "contract with the user. Calling this again does "
                    + "NOT rewrite it: settled steps keep their text, "
                    + "order and progress, and only genuinely new "
                    + "non-conflicting steps are appended (and logged). "
                    + "After a stop or a retry, resume the existing plan "
                    + "at its next unfinished step — never restate or "
                    + "re-plan it. Change it only when the user "
                    + "explicitly changes the scope, and then say "
                    + "plainly what you are adding and why.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("title", stringSchema(
                    "The plan's name, e.g. \"Little Learners app\""));
            properties.add("steps", stringSchema(
                    "The steps, one per line, in build order"));
            properties.add("done_steps", stringSchema(
                    "How many leading steps are finished (a number, "
                            + "e.g. \"2\"). Optional; default 0"));
            properties.add("current_step", stringSchema(
                    "The 0-based step now in hand (a number, e.g. "
                            + "\"2\"). Optional; default 0"));
            return AgentTools.parameters(properties, "title", "steps");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Saving the implementation plan";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String title = stringArg(args, "title");
            String steps = stringArg(args, "steps");
            if (steps.isEmpty()) {
                throw new ToolException("E_BAD_ARGS",
                        "Pass the agreed steps in \"steps\", one per line.");
            }
            // A plan of two lines is not a plan: it is the reason a
            // build goes sideways. Refuse it while the model is still
            // at the planning table rather than after the screens exist.
            int stepCount = 0;
            for (String line : steps.split("\n")) {
                if (line.trim().length() >= 8) {
                    stepCount++;
                }
            }
            if (stepCount < 3 || steps.trim().length() < 80) {
                throw new ToolException("E_PLAN_TOO_THIN",
                        "That plan is too thin to build from — a real one "
                                + "has at least three concrete steps (80+ "
                                + "characters) and names: every screen it "
                                + "will create, what is on each screen, "
                                + "what data is kept and where, what each "
                                + "button does, and the order the work "
                                + "happens in. Write that, show it to the "
                                + "user in chat, then save it.");
            }
            int done = Math.max(0, intArg(args, "done_steps"));
            int current = Math.max(0, intArg(args, "current_step"));
            JsonObject result = new JsonObject();
            if (session.workingProjectId == null
                    || session.workingProjectId.isEmpty()) {
                AgentJournal.setDraftPlan(title, steps);
                result.addProperty("saved", "draft");
                result.addProperty("message",
                        "Plan remembered as a draft. When the project "
                                + "is created it becomes that project's "
                                + "plan automatically.");
                return result;
            }
            String scId = requiredProject(session.workingProjectId);
            boolean hadPlan = AgentJournal.hasPlan(scId);
            int saved = AgentJournal.setPlan(scId, title, steps, done,
                    current);
            result.addProperty("saved", true);
            if (hadPlan) {
                result.addProperty("message",
                        saved == 0
                                ? "This project already has a settled "
                                        + "plan, and every step you just "
                                        + "sent is already in it. A settled "
                                        + "plan is a contract: its steps "
                                        + "cannot be rewritten — only new, "
                                        + "non-conflicting steps can be "
                                        + "appended. Continue with the next "
                                        + "step of the existing plan."
                                : saved + " new step"
                                        + (saved == 1 ? " was" : "s were")
                                        + " appended to the settled plan. "
                                        + "Existing steps were left exactly "
                                        + "as the user agreed them; their "
                                        + "text, order and progress cannot "
                                        + "be rewritten.");
                return result;
            }
            result.addProperty("message",
                    "Plan remembered for this project and shown under "
                            + "its Plan button. Work to it, and keep it "
                            + "updated as milestones land. Its steps are "
                            + "now settled: later set_plan calls can only "
                            + "append new steps, never rewrite these.");
            return result;
        }
    }

    /**
     * The properties each kind of component may carry. Only what the
     * builder can actually put on a widget is listed — a design system
     * that promises a corner radius the tools cannot set is a lie the
     * user discovers on screen.
     */
    private static final java.util.Map<String, String[]> DESIGN_FIELDS =
            new java.util.HashMap<>();

    static {
        // corner_radius and elevation ARE available: they are blocks,
        // not properties (set_corner_radius / set_radius_and_stroke /
        // set_elevation, and set_card_radius / set_card_elevation on a
        // CardView), which is why they belong in the screen's
        // initializeLogic rather than in a widget's properties. They
        // were left out here once, on the strength of a property audit
        // that never looked at blocks — and the user caught it.
        DESIGN_FIELDS.put("button", new String[]{"height", "background",
                "text_color", "text_size", "text_style", "padding",
                "margin", "corner_radius", "elevation"});
        DESIGN_FIELDS.put("card", new String[]{"background", "padding",
                "margin", "corner_radius", "elevation"});
        DESIGN_FIELDS.put("list_row", new String[]{"height", "background",
                "padding", "margin", "title_size", "subtitle_size",
                "corner_radius", "elevation"});
        DESIGN_FIELDS.put("input", new String[]{"height", "background",
                "hint_color", "text_color", "text_size", "padding",
                "margin", "corner_radius", "elevation"});
        DESIGN_FIELDS.put("screen", new String[]{"background", "padding",
                "gap"});
        DESIGN_FIELDS.put("text", new String[]{"text_size", "text_color",
                "text_style", "margin"});
    }

    /** The plain name of each component kind, for messages. */
    private static String designKindLabel(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) {
            case "button" -> "button";
            case "card" -> "card";
            case "list_row" -> "list row";
            case "input" -> "input field";
            case "screen" -> "screen";
            case "text" -> "text";
            default -> kind == null ? "" : kind;
        };
    }

    /**
     * Checks one design-system entry. A token is a name the builder can
     * look up later, so a token with no properties is just a word.
     */
    private static void checkDesignToken(JsonObject token, int index)
            throws ToolException {
        String key = stringArg(token, "key");
        if (key.isEmpty() || !key.matches("[a-z][a-z0-9_]{1,29}")) {
            throw new ToolException("E_BAD_TOKEN",
                    "Token " + (index + 1) + " needs a \"key\" in "
                            + "lowercase letters, digits and underscores "
                            + "(e.g. primary_button), 2 to 30 characters.");
        }
        String kind = stringArg(token, "kind").toLowerCase(Locale.ROOT);
        String[] allowed = DESIGN_FIELDS.get(kind);
        if (allowed == null) {
            throw new ToolException("E_BAD_TOKEN",
                    "Token \"" + key + "\" has kind \"" + kind
                            + "\", which is not one of: button, card, "
                            + "list_row, input, screen, text.");
        }
        if (!token.has("props") || !token.get("props").isJsonObject()) {
            throw new ToolException("E_BAD_TOKEN",
                    "Token \"" + key + "\" needs \"props\": the "
                            + "measurements every " + designKindLabel(kind)
                            + " uses.");
        }
        JsonObject props = token.getAsJsonObject("props");
        if (props.size() == 0) {
            throw new ToolException("E_BAD_TOKEN",
                    "Token \"" + key + "\" has no properties — a "
                            + "component with no measurements is nothing "
                            + "the builder can follow.");
        }
        StringBuilder unknown = new StringBuilder();
        for (String field : props.keySet()) {
            boolean known = false;
            for (String candidate : allowed) {
                if (candidate.equals(field)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                if (unknown.length() > 0) {
                    unknown.append(", ");
                }
                unknown.append(field);
            }
        }
        if (unknown.length() > 0) {
            throw new ToolException("E_BAD_TOKEN",
                    "A " + designKindLabel(kind) + " has no \"" + unknown
                            + "\"; its properties are: "
                            + String.join(", ", allowed) + ".");
        }
    }

    /**
     * set_design_system: saves the components the whole app is built
     * from. One coherent set, agreed as one.
     */
    private static final class SetDesignSystem implements Tool {
        @Override
        public String name() {
            return "set_design_system";
        }

        @Override
        public String description() {
            return "Saves the project's design system: the components "
                    + "every screen is built from, by name, with the "
                    + "measurements each one uses. Call it after the "
                    + "style is agreed and before the plan — the plan "
                    + "builds screens out of these. Pass the COMPLETE "
                    + "set (usually 7 to 10 components): "
                    + "primary_button, secondary_button, ghost_button, "
                    + "card, list_row, input, screen, title, body. Each "
                    + "entry: {\"key\":\"primary_button\","
                    + "\"label\":\"Big friendly button\","
                    + "\"kind\":\"button\",\"props\":{\"height\":\"56\","
                    + "\"background\":\"#FF8A3D\",\"text_color\":"
                    + "\"#FFFFFF\",\"text_size\":\"16\",\"text_style\":"
                    + "\"bold\",\"padding\":\"16\",\"margin\":\"8\"}}. "
                    + "Kinds and their properties: button [height, "
                    + "background, text_color, text_size, text_style, "
                    + "padding, margin]; card [background, padding, "
                    + "margin]; list_row [height, background, padding, "
                    + "margin, title_size, subtitle_size]; input "
                    + "[height, background, hint_color, text_color, "
                    + "text_size, padding, margin]; screen [background, "
                    + "padding, gap]; text [text_size, text_color, "
                    + "text_style, margin]. Sizes are plain numbers (dp "
                    + "or sp) and colours hex. corner_radius and "
                    + "elevation are real and you CAN build them — they "
                    + "are blocks, not properties: apply them in the "
                    + "screen's initializeLogic with set_corner_radius "
                    + "{target, value, color} (radius plus background "
                    + "colour in one), set_radius_and_stroke, "
                    + "set_elevation {target, value}, or on a CardView "
                    + "set_card_radius / set_card_elevation / "
                    + "set_card_color. No approval card — it changes "
                    + "nothing in the project itself. Calling it again "
                    + "replaces the set, so include everything that "
                    + "should carry over. To change ONE component later "
                    + "use edit_design_token — never rewrite the whole "
                    + "set to change one row.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("title", stringSchema(
                    "The set's name, e.g. \"Kids Notes components\""));
            properties.add("components", stringSchema(
                    "The components as a JSON array, one object each"));
            return AgentTools.parameters(properties, "title", "components");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Saving the design system";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String title = stringArg(args, "title");
            JsonArray components;
            try {
                components = com.google.gson.JsonParser
                        .parseString(stringArg(args, "components"))
                        .getAsJsonArray();
            } catch (Exception e) {
                throw new ToolException("E_BAD_ARGS",
                        "Pass the components as a JSON array in "
                                + "\"components\".");
            }
            if (components.size() < 4) {
                throw new ToolException("E_DESIGN_TOO_THIN",
                        "A design system needs at least four components "
                                + "(a button, a card or list row, an "
                                + "input, a screen) — this is what makes "
                                + "every screen look like the same app.");
            }
            JsonArray tokens = new JsonArray();
            for (int i = 0; i < components.size(); i++) {
                JsonElement element = components.get(i);
                if (!element.isJsonObject()) {
                    throw new ToolException("E_BAD_TOKEN",
                            "Component " + (i + 1) + " must be an object "
                                    + "with key, label, kind and props.");
                }
                JsonObject token = element.getAsJsonObject();
                checkDesignToken(token, i);
                tokens.add(token);
            }
            JsonObject result = new JsonObject();
            if (session.workingProjectId == null
                    || session.workingProjectId.isEmpty()) {
                AgentJournal.setDraftDesignSystem(title, tokens);
                result.addProperty("saved", "draft");
                result.addProperty("message",
                        "Design system held as a draft; it lands in the "
                                + "project this work creates. Build every "
                                + "screen from these components now.");
                return result;
            }
            String scId = requiredProject(session.workingProjectId);
            AgentJournal.setDesignSystem(scId, title, tokens);
            result.addProperty("saved", true);
            result.addProperty("components", tokens.size());
            result.addProperty("message",
                    "Design system saved for this project. Every screen "
                            + "you build now uses these components by "
                            + "name — a primary_button is the same button "
                            + "on every screen, so never invent new "
                            + "measurements for one.");
            return result;
        }
    }

    /** edit_design_token: changes one component of the design system. */
    private static final class EditDesignToken implements Tool {
        @Override
        public String name() {
            return "edit_design_token";
        }

        @Override
        public String description() {
            return "Changes ONE component of the project's design "
                    + "system — the user asked for one thing to change, "
                    + "so change that one thing. Pass its \"key\" "
                    + "(e.g. primary_button) and the component's FULL "
                    + "new entry (key, label, kind, props). Nothing else "
                    + "in the set is touched. Use set_design_system only "
                    + "when the whole set is being replaced. After the "
                    + "change, OFFER to update screens already built — "
                    + "never restyle them on your own.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("key", stringSchema(
                    "The component to change, e.g. primary_button"));
            properties.add("component", stringSchema(
                    "The component's full new entry, as JSON"));
            return AgentTools.parameters(properties, "key", "component");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Changing the " + stringArg(args, "key") + " component";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String key = stringArg(args, "key");
            JsonObject component;
            try {
                component = com.google.gson.JsonParser
                        .parseString(stringArg(args, "component"))
                        .getAsJsonObject();
            } catch (Exception e) {
                throw new ToolException("E_BAD_ARGS",
                        "Pass the component's new entry as JSON in "
                                + "\"component\".");
            }
            checkDesignToken(component, 0);
            if (!key.equals(stringArg(component, "key"))) {
                throw new ToolException("E_BAD_ARGS",
                        "The \"key\" must match the component's own "
                                + "\"key\" — one change at a time.");
            }
            String scId = requiredProject(session.workingProjectId);
            if (!AgentJournal.hasDesignSystem(scId)) {
                throw new ToolException("E_NO_DESIGN_SYSTEM",
                        "This project has no design system yet, so there "
                                + "is nothing to change. Propose the "
                                + "whole set with set_design_system "
                                + "first.");
            }
            if (!AgentJournal.setDesignToken(scId, key, component)) {
                JsonObject system = AgentJournal.designSystem(scId);
                StringBuilder known = new StringBuilder();
                JsonElement element = system.get("tokens");
                if (element != null && element.isJsonArray()) {
                    for (JsonElement token : element.getAsJsonArray()) {
                        if (!token.isJsonObject()) {
                            continue;
                        }
                        if (known.length() > 0) {
                            known.append(", ");
                        }
                        known.append(stringArg(token.getAsJsonObject(),
                                "key"));
                    }
                }
                throw new ToolException("E_NO_TOKEN",
                        "There is no component called \"" + key
                                + "\" in this project's design system. "
                                + "Its components are: " + known + ".");
            }
            JsonObject result = new JsonObject();
            result.addProperty("changed", key);
            result.addProperty("message",
                    "The " + key + " component is changed. Screens built "
                            + "from now on use it. Screens already built "
                            + "keep the old measurements until the user "
                            + "asks — offer to update them, do not do it "
                            + "on your own.");
            return result;
        }
    }

    /** get_task_log: the app's recorded task history for this project. */
    private static final class GetTaskLog implements Tool {
        @Override
        public String name() {
            return "get_task_log";
        }

        @Override
        public String description() {
            return "Reads the app's task log for this project: the "
                    + "requests made, the steps that were applied, the "
                    + "ones turned down or that failed, and why an "
                    + "unfinished task stopped. Use it when the user asks "
                    + "what was done or where something stopped, or when "
                    + "you need the detail behind your brief. The current "
                    + "state of the project itself still comes from your "
                    + "other tools — the log is the history, not the "
                    + "present.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Read the task log";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            JsonObject result = new JsonObject();
            result.addProperty("style_brief",
                    AgentJournal.styleBrief(scId));
            result.addProperty("task_log", AgentJournal.logText(scId));
            return result;
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
            String name = stringArg(args, "name");
            return name.isEmpty() ? "Wants to create a project"
                    : "Wants to create \"" + name + "\"";
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
            // Nothing is built before it has been designed. Asking the
            // model nicely did not work: it went straight to creating
            // the project with no look agreed and no plan, and the user
            // was left with a blank app and no say in it. So the gate is
            // the tool itself — the style and the plan have to exist
            // before this can even be proposed.
            requireDesign(session.workingProjectId == null
                    ? "" : session.workingProjectId, name, "app", true,
                    true, true);
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

    /**
     * The design gate: a build starts with an agreed look and an agreed
     * plan, never with a blank project.
     *
     * <p>What is missing decides the message — being told "save a style"
     * when the plan is the thing that is missing sends the model off
     * doing the wrong work.
     */
    private static void requireDesign(String scId, String what,
                                      String kind, boolean newProject,
                                      boolean styleToo, boolean designToo)
            throws ToolException {
        boolean hasStyle = !styleToo
                || AgentJournal.hasStyleBriefOrDraft(scId);
        boolean hasDesign = !designToo
                || AgentJournal.hasDesignSystemOrDraft(scId);
        boolean hasPlan = AgentJournal.hasPlanOrDraft(scId);
        if (hasStyle && hasDesign && hasPlan) {
            return;
        }
        StringBuilder message = new StringBuilder(
                "Nothing is built before it has been designed. ");
        if (!hasStyle) {
            message.append("There is no style yet, so first agree how “")
                    .append(what)
                    .append("” should look. Ask the user with ask_choice "
                            + "(two to four real directions, each one a "
                            + "whole look: “sunny and playful: big rounded "
                            + "cards, orange #FF8A3D, cream background” / "
                            + "“calm and minimal: thin type, one accent, lots "
                            + "of white space”). If they would rather you "
                            + "chose, say which you picked and why in one "
                            + "line. Then save it with set_style_brief as "
                            + "headed lines: Audience; Mood; Colours (with "
                            + "hex); Text sizes (title/body/button sp); "
                            + "Buttons and cards (height, corner roundness); "
                            + "Spacing; Do; Avoid. ");
        }
        if (!hasDesign) {
            message.append("There is no design system yet, so next turn "
                            + "the style into components and save them "
                            + "with set_design_system: primary_button, "
                            + "secondary_button, card, list_row, input, "
                            + "screen, title, body — each with the exact "
                            + "measurements every instance of it uses "
                            + "(height, background with hex, text size, "
                            + "text colour, padding, margin). Propose the "
                            + "whole set yourself from the style — do not "
                            + "interview the user property by property — "
                            + "then show it to them and let them change a "
                            + "row. From then on build every screen out "
                            + "of these components by name. ");
        }
        if (!hasPlan) {
            message.append("There is no plan yet, so next write the "
                            + "implementation plan for this ")
                    .append(kind)
                    .append(" and save it with set_plan: the screens by "
                            + "name, what is on each one, what data it "
                            + "keeps and where, what each button does, and "
                            + "the order you will build it in — steps a "
                            + "person could follow. Then tell the user the "
                            + "plan in chat and let them change it before "
                            + "you build. ");
        }
        if (newProject) {
            message.append("Do not create the project until the style, "
                    + "the design system and the plan are all saved; come "
                    + "back and create it as the first step of building.");
        } else {
            message.append("Do not add the screen until those are saved.");
        }
        throw new ToolException("E_DESIGN_FIRST", message.toString());
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
            String name = stringArg(args, "activity");
            return name.isEmpty() ? "Wants to add a screen"
                    : "Wants to add the screen " + name;
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
            // A new screen is real work: it is planned before it is
            // added, so the user sees where it fits before it exists.
            requireDesign(scId, asked, "screen", false, false, true);
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

    /** edit_activity: toolbar/drawer/orientation settings of a screen. */
    private static final class EditActivity implements Tool {
        @Override
        public String name() {
            return "edit_activity";
        }

        @Override
        public String description() {
            return "Proposes changing one activity's toolbar, "
                    + "fullscreen, navigation drawer, floating button "
                    + "or orientation setting. Only what is passed "
                    + "changes. Switching the drawer on also creates "
                    + "its screen — a layout named _drawer_<activity> "
                    + "— and switches AppCompat on, because a drawer "
                    + "needs it to compile; fill that layout with "
                    + "edit_screen afterwards (a drawer has no events "
                    + "of its own).";
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
            JsonObject drawer = new JsonObject();
            drawer.addProperty("type", "boolean");
            drawer.addProperty("description",
                    "Give the screen a navigation drawer (true) or "
                            + "remove it (false). Its layout, "
                            + "_drawer_<activity>, is created for you "
                            + "and is edited with edit_screen.");
            properties.add("drawer", drawer);
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
            return "Wants to change the settings of "
                    + stringArg(args, "activity");
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
            if (args.has("drawer")) {
                boolean show = args.get("drawer").getAsBoolean();
                options = show
                        ? options | ProjectFileBean.OPTION_ACTIVITY_DRAWER
                        : options & ~ProjectFileBean.OPTION_ACTIVITY_DRAWER;
                lines.add((show ? "Give " : "Remove the navigation drawer of ")
                        + SketchwareProjects.activityName(target)
                        + (show
                                ? " a navigation drawer (its _drawer_"
                                        + " layout is created too, and "
                                        + "AppCompat is switched on)"
                                : ""));
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
                                + "drawer, floating_button and/or "
                                + "orientation.");
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
            return "Wants to delete the screen "
                    + stringArg(args, "activity");
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
