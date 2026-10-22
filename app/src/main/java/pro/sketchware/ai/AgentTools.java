package pro.sketchware.ai;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
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
        register(new ListLocalLibraries());
        register(new SearchBuiltinIcons());
        register(new PreviewScreen());
        register(new GetValuesResources());
        register(new ReadResourceXml());
        register(new ListFontAndSoundSources());
        register(new ReadSourceFiles());
        register(new ReadAssets());
        register(new InspectNativeLibs());
        register(new ListCustomDefinitions());
        register(new InspectManifestAndSettings());
        register(new InspectGeneratedSource());
        register(new ReadAppLogs());
        register(new ListCollections());
        register(new AskColor());
        register(new AskChoice());
        register(new SetStyleBrief());
        register(new SetDesignSystem());
        register(new EditDesignToken());
        register(new SetPlan());
        register(new GetTaskLog());
        register(new CreateProject());
        register(new CreateActivity());
        register(new CreateFragment());
        register(new CreateCustomView());
        register(new DeleteActivity());
        register(new DeleteCustomView());
        register(new EditActivity());
        register(new EditScreen());
        register(new ConvertRootLayout());
        register(new ImportLayoutXml());
        register(new SetEventHandler());
        register(new GetEventLogic());
        register(new EditEventBlock());
        register(new CreateVariable());
        register(new RemoveVariable());
        register(new CreateMoreBlock());
        register(new RemoveMoreBlock());
        register(new CreateComponent());
        register(new RemoveComponent());
        register(new ImportBuiltinIcon());
        register(new ImportChatImage());
        register(new ManageDrawable());
        register(new SetAppIcon());
        register(new EditValuesResource());
        register(new WriteResourceXml());
        register(new DeleteResourceXml());
        register(new ImportProjectFont());
        register(new DeleteProjectFont());
        register(new ImportProjectSound());
        register(new DeleteProjectSound());
        register(new WriteSourceFile());
        register(new DeleteSourceFile());
        register(new WriteAsset());
        register(new DeleteAsset());
        register(new ImportNativeLib());
        register(new DeleteNativeLib());
        register(new ManageCustomBlock());
        register(new ManageCustomComponentOrEvent());
        register(new ManageXmlCommands());
        register(new ManageManifest());
        register(new ManageAppCompatInjection());
        register(new EditProjectSettings());
        register(new ConfigureProguardAndStringFog());
        register(new ConfigureLibrary());
        register(new ManagePermissions());
        register(new ManageLocalLibraries());
        register(new DownloadMavenLibrary());
        register(new EditProjectTheme());
        register(new ManageCollections());
        register(new BuildProject());
        register(new BuildReleasePackage());
        register(new ExportProjectBackup());
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
                && !"build_project".equals(name)
                && !"build_release_package".equals(name)
                && !"export_project_backup".equals(name)
                && !"edit_event_block".equals(name)
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

    /**
     * Reads a JSON array tool parameter, recovering cleanly when an
     * OpenAI-compatible model serialises the array as a JSON string
     * and throwing {@code E_BAD_JSON} (never a misleading count error
     * or silent {@code null}) when that string has broken syntax.
     */
    private static JsonArray jsonArrayArg(JsonObject args, String key)
            throws ToolException {
        if (args == null || !args.has(key) || args.get(key) == null
                || args.get(key).isJsonNull()) {
            return null;
        }
        JsonElement elem = args.get(key);
        if (elem.isJsonArray()) {
            return elem.getAsJsonArray();
        }
        if (elem.isJsonPrimitive()
                && elem.getAsJsonPrimitive().isString()) {
            String raw = elem.getAsString().trim();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                JsonElement parsed = JsonParser.parseString(raw);
                if (parsed != null && parsed.isJsonArray()) {
                    return parsed.getAsJsonArray();
                }
            } catch (Exception firstError) {
                if (raw.startsWith("[") && raw.endsWith("]")) {
                    int diff = unquotedBraceDelta(raw);
                    if (diff > 0 && diff <= 3) {
                        StringBuilder sb = new StringBuilder(
                                raw.substring(0, raw.length() - 1));
                        for (int b = 0; b < diff; b++) {
                            sb.append('}');
                        }
                        sb.append(']');
                        String repaired = sb.toString();
                        try {
                            JsonElement parsed =
                                    JsonParser.parseString(repaired);
                            if (parsed != null && parsed.isJsonArray()) {
                                return parsed.getAsJsonArray();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                throw new ToolException("E_BAD_JSON",
                        "\"" + key + "\" was passed as a malformed JSON "
                                + "string ("
                                + String.valueOf(firstError.getMessage())
                                + "). Pass \"" + key + "\" directly as a "
                                + "JSON array [...] (not a quoted "
                                + "string) with matching braces.");
            }
        }
        throw new ToolException("E_BAD_JSON",
                "\"" + key + "\" must be a JSON array [...].");
    }

    private static int unquotedBraceDelta(String raw) {
        boolean inString = false;
        boolean escaped = false;
        int delta = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') delta++;
                else if (c == '}') delta--;
            }
        }
        return delta;
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

    private static JsonObject booleanSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject intSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject stringArraySchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "array");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        schema.add("items", items);
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
                    + "for: built-in libraries (AppCompat, Material 3 "
                    + "with dynamic colors and DayNight/Light/Dark "
                    + "theme, Firebase, AdMob, Google Maps), enabled "
                    + "and available local/Maven libraries, excluded "
                    + "built-in libraries, declared Android "
                    + "permissions, an automatic prerequisite audit "
                    + "(missing permissions or disabled AppCompat for "
                    + "widgets/components already in the project), "
                    + "extra files (assets, Java/Kotlin, resources, "
                    + "native libraries, imports, ProGuard), and the "
                    + "tail of the last compile log. Check this before "
                    + "configuring libraries/permissions or diagnosing "
                    + "build/runtime problems.";
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
            JsonObject libraries = AgentSetup.libraries(scId);
            result.add("libraries", libraries);
            result.add("local_libraries", AgentSetup.localLibraries(scId));
            result.add("excluded_builtin_libraries",
                    AgentSetup.excludedBuiltInLibraries(scId));
            result.add("permissions", AgentSetup.permissions(scId));
            JsonObject audit = AgentSetup.auditPrerequisites(scId);
            result.add("prerequisite_audit", audit);
            result.add("files", AgentSetup.files(scId));
            String log = AgentSetup.compileLogTail(scId, 1500);
            if (!log.isEmpty()) {
                result.addProperty("last_compile_log_tail", log);
            }
            JsonArray notes = new JsonArray();
            if (audit.has("appcompat_needed_but_off")
                    && audit.get("appcompat_needed_but_off").getAsBoolean()) {
                notes.add("Material/AndroidX widgets exist while AppCompat "
                        + "is off — call configure_library with "
                        + "library=\"appcompat\" and enabled=true before "
                        + "building.");
            }
            if (audit.has("missing_permissions")
                    && audit.getAsJsonArray("missing_permissions").size() > 0) {
                notes.add("Missing recommended Android permissions: "
                        + audit.getAsJsonArray("missing_permissions")
                        + " — call manage_permissions with "
                        + "auto_add_recommended=true or add=[...].");
            }
            if (libraries.has("appcompat") && !libraries.get("appcompat")
                    .getAsBoolean()) {
                notes.add("AppCompat is currently off. Switch it on via "
                        + "configure_library(library=\"appcompat\", "
                        + "enabled=true) before using CardView, "
                        + "MaterialButton, RecyclerView, TabLayout, "
                        + "TextInputLayout, or Material 3.");
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
            return "Lists the working project's screens and layouts: "
                    + "activities, fragments (Fragment, DialogFragment, "
                    + "BottomSheetDialogFragment), drawers (_drawer_*), "
                    + "and custom_views.";
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
            JsonArray fragments = new JsonArray();
            for (ProjectFileBean activity
                    : SketchwareProjects.activities(scId)) {
                if (SketchwareProjects.isFragment(activity)) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name",
                            SketchwareProjects.activityName(activity));
                    entry.addProperty("file_name", activity.fileName);
                    entry.addProperty("kind",
                            SketchwareProjects.screenKind(activity));
                    fragments.add(entry);
                } else {
                    activities.add(SketchwareProjects.activityName(activity));
                }
            }
            JsonArray drawers = new JsonArray();
            JsonArray customViews = new JsonArray();
            for (ProjectFileBean view : SketchwareProjects.customViews(scId)) {
                if (SketchwareProjects.isDrawer(view)) {
                    drawers.add(view.fileName);
                } else {
                    customViews.add(view.fileName);
                }
            }
            JsonObject result = new JsonObject();
            result.add("activities", activities);
            result.add("fragments", fragments);
            result.add("drawers", drawers);
            result.add("custom_views", customViews);
            try {
                pro.sketchware.utility.FileResConfig frc =
                        new pro.sketchware.utility.FileResConfig(scId);
                result.add("custom_manifest_activities",
                        namesJsonAll(frc.getJavaManifestList()));
                result.add("custom_manifest_services",
                        namesJsonAll(frc.getServiceManifestList()));
                result.add("custom_manifest_receivers",
                        namesJsonAll(frc.getBroadcastManifestList()));
            } catch (Exception ignored) {
            }
            return result;
        }
    }

    /** list_resources: the project's image, drawable, sound, font & icon state. */
    private static final class ListResources implements Tool {
        @Override
        public String name() {
            return "list_resources";
        }

        @Override
        public String description() {
            return "Lists the working project's resources by name: "
                    + "images (from Image Manager / import_builtin_icon / "
                    + "import_chat_image), custom_drawables (XML shapes, "
                    + "ripples, selectors & vectors in res/drawable from "
                    + "manage_drawable), sounds, fonts, and launcher icon "
                    + "status. Optionally pass inspect_drawable to read "
                    + "back one custom XML drawable's full source.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("inspect_drawable", stringSchema(
                    "Optional custom XML drawable name from custom_drawables "
                            + "to read back its full XML content"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String inspect = stringArg(args, "inspect_drawable");
            return inspect.isEmpty()
                    ? "Listed the project's resources"
                    : "Inspected drawable " + inspect;
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            SketchwareProjects.Resources resources =
                    SketchwareProjects.resources(scId);
            JsonObject result = new JsonObject();
            result.add("images", namesJsonAll(resources.images));
            result.add("custom_drawables",
                    namesJsonAll(resources.customDrawables));
            result.add("sounds", namesJsonAll(resources.sounds));
            result.add("fonts", namesJsonAll(resources.fonts));
            result.add("value_variants",
                    namesJsonAll(AgentResources.listValueVariants(scId)));
            result.add("bundled_fonts_available",
                    namesJsonAll(AgentResources.BUNDLED_FONTS));
            result.add("sound_presets_available",
                    namesJsonAll(AgentResources.SOUND_PRESETS));
            try {
                result.add("raw_resource_files",
                        AgentResources.listRawResourceFiles(scId, null));
            } catch (Exception ignored) {
            }
            try {
                java.util.HashMap<String, Object> meta = a.a.a.lC.b(scId);
                result.addProperty("custom_icon",
                        meta != null && a.a.a.yB.a(meta, "custom_icon"));
                result.addProperty("is_icon_adaptive",
                        meta != null && a.a.a.yB.a(meta, "isIconAdaptive"));
            } catch (Exception ignored) {
            }
            String inspect = stringArg(args, "inspect_drawable");
            if (!inspect.isEmpty()) {
                result.addProperty("inspected_drawable", inspect);
                result.addProperty("drawable_xml",
                        AgentAssets.readCustomDrawableXml(scId, inspect));
            }
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
                    SketchwareProjects.screenKind(activity));
            result.addProperty("root",
                    SketchwareProjects.rootDescription(scId, activity));
            if (activity.fileType
                    == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                JsonObject opts = new JsonObject();
                opts.addProperty("toolbar",
                        !SketchwareProjects.isFragment(activity)
                                && activity.hasActivityOption(
                                        ProjectFileBean
                                                .OPTION_ACTIVITY_TOOLBAR));
                opts.addProperty("fullscreen",
                        activity.hasActivityOption(
                                ProjectFileBean.OPTION_ACTIVITY_FULLSCREEN));
                opts.addProperty("floating_button",
                        activity.hasActivityOption(
                                ProjectFileBean.OPTION_ACTIVITY_FAB));
                opts.addProperty("drawer",
                        !SketchwareProjects.isFragment(activity)
                                && activity.hasActivityOption(
                                        ProjectFileBean
                                                .OPTION_ACTIVITY_DRAWER));
                String orient = switch (activity.orientation) {
                    case ProjectFileBean.ORIENTATION_LANDSCAPE -> "landscape";
                    case ProjectFileBean.ORIENTATION_BOTH -> "both";
                    default -> "portrait";
                };
                opts.addProperty("orientation", orient);
                result.add("options", opts);
                if (activity.hasActivityOption(
                        ProjectFileBean.OPTION_ACTIVITY_FAB)) {
                    result.addProperty("fab_id", "_fab");
                }
                ProjectFileBean drawerFile =
                        SketchwareProjects.drawerFileOf(scId, activity);
                if (drawerFile != null) {
                    result.addProperty("drawer_layout", drawerFile.fileName);
                    JsonArray drawerWidgets = new JsonArray();
                    for (String line : SketchwareProjects.screenLines(
                            scId, drawerFile, 60)) {
                        drawerWidgets.add(line);
                    }
                    result.add("drawer_widgets", drawerWidgets);
                }
            } else if (SketchwareProjects.isDrawer(activity)) {
                ProjectFileBean host =
                        SketchwareProjects.drawerHostActivity(scId, activity);
                if (host != null) {
                    String hostName = SketchwareProjects.activityName(host);
                    result.addProperty("host_activity", hostName);
                    result.addProperty("drawer_events_note",
                            "Wire drawer widget events on " + hostName
                                    + " with target \"<widgetId>\" or "
                                    + "\"_drawer_<widgetId>\".");
                }
            }
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
                        moreBlocks.add(
                                AgentLogic.describeMoreBlockSignature(
                                        name,
                                        summary.moreBlockSpecs.get(name)));
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
            JsonArray ops = jsonArrayArg(args, "ops");
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
                    "Edit " + SketchwareProjects.screenName(located.activity)
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
                String rootClassUpdate = null;
                java.util.Map<String, String> rootUpdates =
                        new java.util.LinkedHashMap<>();
                for (com.google.gson.JsonElement element
                        : outcome.frozenOps) {
                    JsonObject op = element.getAsJsonObject();
                    if (!"set_root".equals(op.has("op")
                            ? op.get("op").getAsString() : "")) {
                        continue;
                    }
                    if (op.has("resolved_class_name")) {
                        rootClassUpdate =
                                op.get("resolved_class_name").getAsString();
                    }
                    if (op.has("background_hex")) {
                        rootUpdates.put("android:background",
                                op.get("background_hex").getAsString());
                    }
                    if (op.has("orientation")) {
                        rootUpdates.put("android:orientation",
                                op.get("orientation").getAsString());
                    }
                    if (op.has("resolved_attributes")
                            && op.get("resolved_attributes").isJsonObject()) {
                        for (java.util.Map.Entry<String, JsonElement> entry
                                : op.getAsJsonObject("resolved_attributes")
                                        .entrySet()) {
                            rootUpdates.put(entry.getKey(),
                                    entry.getValue().getAsString());
                        }
                    }
                }
                if (rootClassUpdate != null || !rootUpdates.isEmpty()) {
                    previousRoot = SketchwareActions.setRootLayout(
                            located.scId, located.activity, rootClassUpdate,
                            rootUpdates, false);
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
                    + "APPENDED after whatever the event already does "
                    + "(or pass replace: true to replace the event's "
                    + "existing blocks cleanly) — "
                    + "check get_screen first, which lists each event "
                    + "and how many blocks it already has. Never append "
                    + "a second copy of a block to replace the first: "
                    + "to change or remove one that is already wired, "
                    + "read it with get_event_logic and use "
                    + "edit_event_block, or pass replace: true. Every "
                    + "value slot (toast message, set_text, add_to_list, "
                    + "put_in_map, list index, save_pref, list_from_json, "
                    + "set_enabled, set_checked, set_progress, file "
                    + "paths) accepts value expressions directly "
                    + "({\"var\":\"x\"}, {\"text_of\":\"id\"}, "
                    + "{\"pref_get\":\"file1\",\"key\":\"k\"}, "
                    + "{\"json_of_list\":\"list\"}) — never wrap an "
                    + "expression object in quotes. Blocks (1 to "
                    + AgentLogic.MAX_BLOCKS + "): toast {message} "
                    + "(show a message or expression) or {from_variable}, "
                    + "show_keyboard {}, hide_keyboard {}, set_text {target,"
                    + "text} (change a text widget's text) or {target,"
                    + "from_variable} (use a string variable's value), "
                    + "set_typeface {target,font,style}, "
                    + "set_visibility {target,visible} or {target,state} "
                    + "(visible, gone, invisible), set_enabled {target,"
                    + "enabled}, set_text_color {target,color} (a text "
                    + "widget's text colour, #RRGGBB), set_hint {target,"
                    + "text} (an EditText's hint), set_clickable {target,"
                    + "clickable} (whether taps land), set_image "
                    + "{target,image} (an ImageView drawable), "
                    + "set_image_url "
                    + "{target,url} (load an image from a URL into an "
                    + "ImageView), set_progress {target,value} (a "
                    + "SeekBar or ProgressBar's progress), copy_to_clipboard {text}, "
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
                    + "(a CheckBox, Switch or RadioButton), "
                    + "when_clicked/when_long_clicked {target,blocks} "
                    + "and when_checked_changed {target,blocks} (inline "
                    + "listeners for row widgets inside "
                    + "onBindCustomView; read the toggle state with "
                    + "{\"is_checked\":true}) and refresh_adapter {} "
                    + "(inside onBindCustomView), set_max {target,value} (a SeekBar), "
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
                    + "activity event (initializeLogic, onResume, "
                    + "Import, ...) for screen-level behaviour, or "
                    + "target \"<moreBlockName>\" with event "
                    + "\"moreBlock\" to wire a More Block declared with "
                    + "create_more_block. MORE BLOCKS & RAW JAVA: call "
                    + "a void More Block as {\"block\":\"call_more_block\","
                    + "\"name\":\"refreshUi\",\"args\":[...]}, call a "
                    + "returning More Block inside any matching value "
                    + "slot as {\"call_more_block\":\"formatNote\","
                    + "\"args\":[...]}, read More Block parameters inside "
                    + "its body with {\"arg\":\"paramName\"} (or the "
                    + "parameter name directly for list/map/widget/"
                    + "component parameters), and finish a returning "
                    + "More Block with {\"block\":\"return_value\","
                    + "\"value\":...}. For custom Java statements use "
                    + "{\"block\":\"add_source_directly\",\"code\":\"...\"} "
                    + "(inside a More Block or event, parameters are "
                    + "prefixed with '_' like _title or _position, and "
                    + "More Block methods are _<name>(...)), for inline "
                    + "Java expressions in any value slot use "
                    + "{\"java_expr\":\"...\"}, and for extra Java "
                    + "imports use {\"block\":\"custom_import\",\"import\":"
                    + "\"org.json.JSONArray\"} in target \"activity\", "
                    + "event \"Import\" (or write fully-qualified class "
                    + "names). The user must approve before anything "
                    + "changes.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen, e.g. MainActivity"));
            properties.add("target", stringSchema(
                    "The widget id from get_screen, a component's id "
                            + "(e.g. firebase1), \"activity\" for the "
                            + "screen's own events (initializeLogic, "
                            + "onResume, Import, ...), or a More Block's "
                            + "name (with event \"moreBlock\")"));
            properties.add("event", stringSchema(
                    "The event, e.g. onClick for a tap, initializeLogic "
                            + "when an activity starts, Import for "
                            + "custom_import, onChildAdded for Firebase, "
                            + "or \"moreBlock\" when wiring a More Block"));
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
                    "crop_image", "youtube_lifecycle",
                    "set_image", "set_typeface", "show_keyboard",
                    "hide_keyboard", "set_pref_file",
                    "set_custom_letter", "list_set_checked",
                    "list_checked_positions_to_list",
                    "refresh_adapter", "notify_data_changed",
                    "when_clicked", "when_long_clicked",
                    "when_checked_changed", "when_swipe_refresh",
                    "call_more_block", "more_block", "return_value",
                    "return", "return_number", "return_boolean",
                    "return_map", "return_list", "return_view",
                    "add_source_directly", "raw_java", "custom_import",
                    "add_custom_variable", "add_initializer"}) {
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
            JsonObject replaceProp = new JsonObject();
            replaceProp.addProperty("type", "boolean");
            replaceProp.addProperty("description",
                    "Optional: true to replace the event's existing "
                            + "blocks instead of appending");
            properties.add("replace", replaceProp);
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
            JsonArray blocks = jsonArrayArg(args, "blocks");
            boolean replace = booleanArg(args, "replace");
            AgentLogic.Plan plan = AgentLogic.plan(session.context, scId,
                    activity, stringArg(args, "target"),
                    stringArg(args, "event"), blocks, replace);
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
            clean.addProperty("replace", plan.replace);
            clean.add("blocks", blocks);

            StringBuilder body = new StringBuilder(plan.header);
            for (String line : plan.lines) {
                body.append("\n• ").append(line);
            }
            if (plan.existingCount > 0) {
                body.append(plan.replace
                                ? "\n(Replaces the "
                                : "\n(Runs after the ")
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
            JsonArray blocks = jsonArrayArg(proposal.args, "blocks");
            AgentLogic.Plan plan = AgentLogic.plan(session.context, scId,
                    activity, stringArg(proposal.args, "target"),
                    stringArg(proposal.args, "event"), blocks,
                    booleanArg(proposal.args, "replace"));
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
                if (AgentLogic.moreBlocksOf(store, javaName)
                        .containsKey(name)) {
                    return "\"" + name + "\" is already a More Block "
                            + "on this screen.";
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

    /** create_more_block: declare (and optionally wire) one More Block. */
    private static final class CreateMoreBlock implements Tool {
        @Override
        public String name() {
            return "create_more_block";
        }

        @Override
        public String description() {
            return "Proposes creating a reusable More Block (custom "
                    + "function) on one activity screen — matching "
                    + "Sketchware Pro's Make Block dialog and "
                    + "ReturnMoreblockManager. Supports typed "
                    + "parameters (string, number, boolean, map, "
                    + "list_string, list_number, list_map, view, or "
                    + "widget/component types like textview, edittext, "
                    + "listview, intent, shared_preferences) and the 8 "
                    + "Sketchware Pro return types (void, string, "
                    + "number, boolean, map, list_string, list_map, "
                    + "view). You may also pass \"blocks\" directly to "
                    + "create the More Block and wire its body in one "
                    + "step (non-void More Blocks must finish with "
                    + "{\"block\":\"return_value\",\"value\":...}), or "
                    + "wire/update its body afterwards with "
                    + "set_event_handler (target: \"<name>\", event: "
                    + "\"moreBlock\"). Call void More Blocks as "
                    + "{\"block\":\"call_more_block\",\"name\":\"...\","
                    + "\"args\":[...]} and returning More Blocks inside "
                    + "value slots as {\"call_more_block\":\"...\","
                    + "\"args\":[...]}.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The activity screen, e.g. MainActivity"));
            properties.add("name", stringSchema(
                    "The More Block's Java identifier name, e.g. "
                            + "saveNote or formatTimestamp"));
            JsonObject paramsSchema = new JsonObject();
            paramsSchema.addProperty("type", "array");
            paramsSchema.addProperty("description",
                    "Optional parameters in order, each {\"name\":"
                            + "\"title\",\"type\":\"string\"} (types: "
                            + "string, number, boolean, map, "
                            + "list_string, list_number, list_map, "
                            + "view, textview, edittext, button, "
                            + "imageview, listview, intent, "
                            + "shared_preferences, calendar, etc., "
                            + "plus optional \"label\" words)");
            JsonObject paramItem = new JsonObject();
            paramItem.addProperty("type", "object");
            JsonObject paramProps = new JsonObject();
            paramProps.add("name", stringSchema(
                    "Parameter identifier, e.g. title"));
            paramProps.add("type", stringSchema(
                    "Parameter type, e.g. string, number, boolean, "
                            + "map, list_string, list_number, "
                            + "list_map, view"));
            paramProps.add("label", stringSchema(
                    "Optional display word(s) shown before this slot "
                            + "on the purple block"));
            paramItem.add("properties", paramProps);
            paramsSchema.add("items", paramItem);
            properties.add("parameters", paramsSchema);
            JsonObject retSchema = stringSchema(
                    "Optional return type: void (default), string, "
                            + "number, boolean, map, list_string, "
                            + "list_map, or view");
            JsonArray retEnum = new JsonArray();
            for (String r : new String[]{"void", "string", "number",
                    "boolean", "map", "list_string", "list_map",
                    "view"}) {
                retEnum.add(r);
            }
            retSchema.add("enum", retEnum);
            properties.add("return_type", retSchema);
            JsonObject blocksSchema = new JsonObject();
            blocksSchema.addProperty("type", "array");
            blocksSchema.addProperty("description",
                    "Optional initial body blocks for this More Block "
                            + "(same block objects set_event_handler "
                            + "accepts)");
            JsonObject blockItem = new JsonObject();
            blockItem.addProperty("type", "object");
            blocksSchema.add("items", blockItem);
            properties.add("blocks", blocksSchema);
            return AgentTools.parameters(properties, "activity", "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to create More Block "
                    + stringArg(args, "name") + " on "
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
            JsonArray rawParams = jsonArrayArg(args, "parameters");
            String rawReturn = stringArg(args, "return_type");
            AgentLogic.MoreBlockDef def =
                    AgentLogic.buildMoreBlockSpec(scId, activity,
                            stringArg(args, "name"), rawParams,
                            rawReturn);
            JsonArray parsedBlocks = jsonArrayArg(args, "blocks");
            JsonArray blocks = parsedBlocks != null
                    && !parsedBlocks.isEmpty() ? parsedBlocks : null;
            AgentLogic.Plan bodyPlan = blocks != null
                    ? AgentLogic.planMoreBlock(session.context, scId,
                            activity, def, blocks, true)
                    : null;

            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", activity.fileName);
            clean.addProperty("name", def.bareName);
            if (rawParams != null) {
                clean.add("parameters", rawParams);
            }
            clean.addProperty("return_type", def.humanReturnType());
            if (blocks != null) {
                clean.add("blocks", blocks);
            }

            StringBuilder body = new StringBuilder("New More Block: ")
                    .append(def.signature());
            if (bodyPlan != null) {
                for (String line : bodyPlan.lines) {
                    body.append("\n• ").append(line);
                }
            }
            String details = "Sketchware spec: " + def.second
                    + (blocks == null ? ""
                            : "\nExact blocks:\n"
                                    + new com.google.gson.GsonBuilder()
                                            .setPrettyPrinting()
                                            .create().toJson(blocks));
            return new AgentProposal(name(), clean, scId,
                    "Add More Block to "
                            + SketchwareProjects.activityName(activity)
                            + " in " + project.name,
                    body.toString(), details);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            if (!scId.equals(session.workingProjectId)) {
                throw new ToolException("E_PROJECT_CHANGED",
                        "The working project changed since this More "
                                + "Block was proposed, so it was not "
                                + "created.");
            }
            ProjectFileBean activity = SketchwareProjects.findActivity(
                    scId, stringArg(proposal.args, "activity"));
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "That activity no longer exists.");
            }
            JsonArray rawParams =
                    jsonArrayArg(proposal.args, "parameters");
            String rawReturn = stringArg(proposal.args, "return_type");
            AgentLogic.MoreBlockDef def =
                    AgentLogic.buildMoreBlockSpec(scId, activity,
                            stringArg(proposal.args, "name"), rawParams,
                            rawReturn);
            JsonArray parsedBlocks =
                    jsonArrayArg(proposal.args, "blocks");
            JsonArray blocks = parsedBlocks != null
                    && !parsedBlocks.isEmpty() ? parsedBlocks : null;
            AgentLogic.Plan bodyPlan = blocks != null
                    ? AgentLogic.planMoreBlock(session.context, scId,
                            activity, def, blocks, true)
                    : null;

            String javaName = activity.getJavaName();
            eC store = a.a.a.jC.a(scId);
            boolean registered = false;
            try {
                SketchwareActions.backupProjectOnce(scId);
                store.a(javaName, def.first, def.second);
                registered = true;
                if (bodyPlan != null) {
                    AgentLogic.apply(session.context, bodyPlan);
                } else {
                    SketchwareActions.commitProject(scId);
                }
            } catch (Exception e) {
                if (registered) {
                    try {
                        store.n(javaName, def.first);
                        SketchwareActions.commitProject(scId);
                    } catch (Exception ignored) {
                        // Best-effort rollback.
                    }
                }
                if (e instanceof ToolException te) {
                    throw te;
                }
                throw new ToolException("E_APPLY_FAILED",
                        "Creating More Block \"" + def.bareName
                                + "\" failed: "
                                + String.valueOf(e.getMessage()));
            }
            if (!AgentLogic.moreBlocksOf(store, javaName)
                    .containsKey(def.bareName)) {
                throw new ToolException("E_APPLY_FAILED",
                        "More Block \"" + def.bareName
                                + "\" did not read back after saving.");
            }
            JsonObject result = new JsonObject();
            result.addProperty("created", true);
            result.addProperty("activity",
                    SketchwareProjects.activityName(activity));
            result.addProperty("name", def.bareName);
            result.addProperty("key", def.first);
            result.addProperty("signature", def.signature());
            result.addProperty("return_type", def.humanReturnType());
            result.addProperty("wired_blocks",
                    bodyPlan == null ? 0 : blocks.size());
            if (!def.isVoid() && bodyPlan == null) {
                result.addProperty("warning",
                        def.bareName + " returns "
                                + def.humanReturnType()
                                + " and has 0 blocks so far — wire a "
                                + "return_value block into it with "
                                + "set_event_handler (target \""
                                + def.bareName
                                + "\", event \"moreBlock\") before "
                                + "building, or Java compilation will "
                                + "fail.");
            }
            result.addProperty("verified",
                    "Created More Block " + def.signature() + " on "
                            + SketchwareProjects.activityName(activity)
                            + (bodyPlan == null ? ""
                                    : " and wired its blocks")
                            + " (read back and checked).");
            return result;
        }
    }

    /** remove_more_block: delete an unused More Block from a screen. */
    private static final class RemoveMoreBlock implements Tool {
        @Override
        public String name() {
            return "remove_more_block";
        }

        @Override
        public String description() {
            return "Proposes deleting one More Block from one activity "
                    + "screen — the same deletion Sketchware's Logic "
                    + "Editor performs. Refuses when any event or other "
                    + "More Block on that screen still calls it.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The activity screen, e.g. MainActivity"));
            properties.add("name", stringSchema(
                    "The More Block name to remove, e.g. saveNote"));
            return AgentTools.parameters(properties, "activity", "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to remove More Block "
                    + stringArg(args, "name");
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
            String rawName = mod.hey.studios.moreblock
                    .ReturnMoreblockManager.getMbName(
                            stringArg(args, "name"));
            String javaName = activity.getJavaName();
            eC store = a.a.a.jC.a(scId);
            java.util.LinkedHashMap<String, AgentLogic.MoreBlockDef> all =
                    AgentLogic.moreBlocksOf(store, javaName);
            AgentLogic.MoreBlockDef def = all.get(rawName);
            if (def == null) {
                throw new ToolException("E_NO_MORE_BLOCK",
                        "\"" + rawName + "\" is not a More Block on "
                                + SketchwareProjects.activityName(activity)
                                + "."
                                + (all.isEmpty() ? ""
                                        : " Its More Blocks are: "
                                                + String.join(", ",
                                                        all.keySet())
                                                + "."));
            }
            String callers = AgentLogic.moreBlockCallSites(scId,
                    javaName, def);
            if (callers != null) {
                throw new ToolException("E_MORE_BLOCK_IN_USE",
                        "More Block \"" + def.bareName
                                + "\" is still called in " + callers
                                + ". Remove or update those calls first "
                                + "so the project stays buildable.");
            }
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", activity.fileName);
            clean.addProperty("name", def.bareName);
            return new AgentProposal(name(), clean, scId,
                    "Remove More Block from "
                            + SketchwareProjects.activityName(activity)
                            + " in "
                            + SketchwareProjects.project(scId).name,
                    "Remove More Block " + def.signature()
                            + " — nothing on that screen calls it",
                    "Deletes the More Block and its blocks, matching "
                            + "Sketchware's Logic Editor deletion.");
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
            ProjectFileBean activity = SketchwareProjects.findActivity(
                    scId, stringArg(proposal.args, "activity"));
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "That activity no longer exists.");
            }
            String rawName = mod.hey.studios.moreblock
                    .ReturnMoreblockManager.getMbName(
                            stringArg(proposal.args, "name"));
            String javaName = activity.getJavaName();
            eC store = a.a.a.jC.a(scId);
            AgentLogic.MoreBlockDef def =
                    AgentLogic.moreBlocksOf(store, javaName).get(rawName);
            if (def == null) {
                throw new ToolException("E_NO_MORE_BLOCK",
                        "More Block \"" + rawName
                                + "\" is already gone.");
            }
            String callers = AgentLogic.moreBlockCallSites(scId,
                    javaName, def);
            if (callers != null) {
                throw new ToolException("E_MORE_BLOCK_IN_USE",
                        "More Block \"" + def.bareName
                                + "\" is now called in " + callers
                                + "; nothing was removed.");
            }
            try {
                SketchwareActions.backupProjectOnce(scId);
                store.n(javaName, def.first);
                SketchwareActions.commitProject(scId);
            } catch (Exception e) {
                throw new ToolException("E_APPLY_FAILED",
                        "Removing More Block failed: "
                                + String.valueOf(e.getMessage()));
            }
            if (AgentLogic.moreBlocksOf(store, javaName)
                    .containsKey(def.bareName)) {
                throw new ToolException("E_APPLY_FAILED",
                        "The More Block was still present after removal.");
            }
            JsonObject result = new JsonObject();
            result.addProperty("removed", def.bareName);
            result.addProperty("verified",
                    SketchwareProjects.activityName(activity)
                            + " no longer declares More Block "
                            + def.signature()
                            + " (read back and checked).");
            return result;
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
                            + "media_player, or a custom component "
                            + "typeName (optional; intent is used when "
                            + "omitted)"));
            properties.add("name", stringSchema(
                    "The component's name, e.g. timer1 (optional; "
                            + "the next free name for its kind is "
                            + "used)"));
            properties.add("extra_data", stringSchema(
                    "Optional extra parameter: SharedPreferences file "
                            + "name (defaults to component name), "
                            + "Firebase DB/Storage node path (e.g. "
                            + "\"notes\"), or FilePicker MIME type (e.g. "
                            + "\"image/*\")"));
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
                               int type, String label, String extraData) {
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
                AgentExtensibility.CustomComponentDef customComp =
                        AgentExtensibility.findCustomComponent(typeName);
                if (customComp != null) {
                    typeRow = new Object[]{
                            customComp.typeName,
                            customComp.id,
                            customComp.varName.isEmpty()
                                    ? "comp" : customComp.varName,
                            customComp.name
                    };
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
                                + "cloud_message, google_login, "
                                + "asynctask (or a custom component "
                                + "typeName).");
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
            String extraData = stringArg(args, "extra_data").trim();
            if (componentType
                    == com.besome.sketch.beans.ComponentBean
                            .COMPONENT_TYPE_SHAREDPREF) {
                if (extraData.isEmpty()) {
                    extraData = name.length() > 20
                            ? name.substring(0, 20) : name;
                }
            } else if (componentType
                    == com.besome.sketch.beans.ComponentBean
                            .COMPONENT_TYPE_FILE_PICKER) {
                if (extraData.isEmpty()) {
                    extraData = "*" + "/*";
                }
            }
            return new Checked(activity, name, componentType,
                    (String) typeRow[3], extraData);
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
            if (!checked.extraData().isEmpty()) {
                clean.addProperty("extra_data", checked.extraData());
            }
            return new AgentProposal(name(), clean, scId,
                    "Add a component to "
                            + SketchwareProjects.activityName(
                                    checked.activity())
                            + " in " + project.name,
                    "New " + checked.label() + " component: "
                            + checked.name()
                            + (checked.extraData().isEmpty() ? ""
                                    : " (" + checked.extraData() + ")"),
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
                // The component picker's own add call (with param1 when
                // SharedPreferences / Firebase / FilePicker uses it).
                if (!checked.extraData().isEmpty()) {
                    store.a(javaName, checked.type(), checked.name(),
                            checked.extraData());
                } else {
                    store.a(javaName, checked.type(), checked.name());
                }
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
            JsonObject audit = AgentSetup.auditPrerequisites(scId);
            if (audit.has("missing_permissions")
                    && audit.getAsJsonArray("missing_permissions").size() > 0) {
                result.add("missing_permissions",
                        audit.getAsJsonArray("missing_permissions"));
            }
            if (audit.has("appcompat_needed_but_off")
                    && audit.get("appcompat_needed_but_off").getAsBoolean()) {
                result.addProperty("appcompat_warning",
                        "Material/AndroidX widgets are placed while AppCompat "
                                + "is off — enable AppCompat via "
                                + "configure_library(library=\"appcompat\", "
                                + "enabled=true).");
            }
            return result;
        }
    }
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
                    "The widget id from get_screen, a component's id, "
                            + "\"activity\" for the screen's own events, "
                            + "or a More Block's name (with event "
                            + "\"moreBlock\")"));
            properties.add("event", stringSchema(
                    "The event, e.g. onClick, initializeLogic, Import, "
                            + "or \"moreBlock\""));
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
                    + "wired into an event or More Block — the fix for "
                    + "pile-ups like two set_text blocks saying the "
                    + "same thing. Read the event or More Block with "
                    + "get_event_logic first, then pass block_path (the "
                    + "statement's numbers, e.g. [2], or [1,2] inside a "
                    + "container). To change a value, pass replace_with "
                    + "{text|text2|message|value|number|flag|code|"
                    + "import|<paramName>: the new value or expression "
                    + "object, e.g. {\"text\":{\"text_of\":\"note_input\"}}} "
                    + "— any value slot of the block (including "
                    + "add_source_directly code, custom_import path, and "
                    + "call_more_block arguments) can be rewritten. "
                    + "To remove the statement (including position [1]), "
                    + "pass remove: true. The user approves "
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
                    "The widget id, component id, \"activity\", or a "
                            + "More Block's name (with event "
                            + "\"moreBlock\")"));
            properties.add("event", stringSchema(
                    "The event name, or \"moreBlock\""));
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
                    if (value.rawElement != null) {
                        with.add(value.kind, value.rawElement);
                    } else {
                        with.addProperty(value.kind, value.newValue);
                    }
                }
                clean.add("values", with);
            }
            StringBuilder body = new StringBuilder(plan.header).append("\n");
            if (plan.remove) {
                body.append("Remove the statement: ")
                        .append(plan.blockDescription);
            } else {
                for (AgentLogic.EditableValue value : plan.values) {
                    String shownNew = value.shownNewValue != null
                            ? value.shownNewValue : value.newValue;
                    body.append("Change ").append(value.kind)
                            .append(" from “")
                            .append(value.currentValue)
                            .append("” to “").append(shownNew)
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
        return intArg(args, key, -1);
    }

    private static int intArg(JsonObject args, String key, int fallback) {
        try {
            if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
                return fallback;
            }
            return args.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Integer optionalIntArg(JsonObject args, String key) {
        try {
            if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
                return null;
            }
            return args.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean optionalBooleanArg(JsonObject args, String key) {
        try {
            if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
                return null;
            }
            return args.get(key).getAsBoolean();
        } catch (Exception e) {
            return null;
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

    /** set_plan: remembers and updates the agreed implementation plan. */
    private static final class SetPlan implements Tool {
        @Override
        public String name() {
            return "set_plan";
        }

        @Override
        public String description() {
            return "Saves the agreed implementation plan OR updates its "
                    + "milestone progress in the app's Plan tracker. "
                    + "(1) First-time plan creation: pass \"title\" and "
                    + "\"steps\" (one milestone per line, 3+ steps, 80+ "
                    + "chars). With no project chosen yet, it is held as "
                    + "a draft and deposited when the project is created. "
                    + "(2) Progress updates during building: once a plan "
                    + "already exists, call set_plan with ONLY "
                    + "\"done_steps\" (how many leading milestones are "
                    + "finished, e.g. \"2\") and optional "
                    + "\"current_step\" (0-based index now in hand, e.g. "
                    + "\"2\") — you do NOT need to resend \"steps\"! "
                    + "(3) Appending new scope: settled steps keep their "
                    + "exact text and order; pass genuinely new "
                    + "higher-numbered steps in \"steps\" only when the "
                    + "user expands scope.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("title", stringSchema(
                    "The plan's name, e.g. \"Little Learners app\" "
                            + "(required when creating a plan; optional "
                            + "when updating progress)"));
            properties.add("steps", stringSchema(
                    "The milestone steps, one per line, in build order "
                            + "(required when creating a plan or "
                            + "appending new steps; omit when only "
                            + "updating done_steps/current_step)"));
            properties.add("done_steps", stringSchema(
                    "How many leading steps are finished (a number, "
                            + "e.g. \"2\")"));
            properties.add("current_step", stringSchema(
                    "The 0-based step now in hand (a number, e.g. "
                            + "\"2\")"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            int done = intArg(args, "done_steps");
            return done > 0
                    ? "Updating plan progress (" + done + " done)"
                    : "Saving the implementation plan";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String title = stringArg(args, "title");
            String steps = stringArg(args, "steps");
            int done = intArg(args, "done_steps");
            int current = intArg(args, "current_step");

            boolean noProject = session.workingProjectId == null
                    || session.workingProjectId.isEmpty();
            boolean hadPlan = !noProject
                    && AgentJournal.hasPlan(session.workingProjectId);

            if (!hadPlan) {
                if (steps.isEmpty()) {
                    throw new ToolException("E_BAD_ARGS",
                            "No plan is saved yet — pass \"title\" and "
                                    + "the agreed \"steps\" (one per line).");
                }
                int stepCount = 0;
                for (String line : steps.split("\n")) {
                    if (line.trim().length() >= 8) {
                        stepCount++;
                    }
                }
                if (stepCount < 3 || steps.trim().length() < 80) {
                    throw new ToolException("E_PLAN_TOO_THIN",
                            "That plan is too thin to build from — a real "
                                    + "one has at least three concrete "
                                    + "steps (80+ characters) and names: "
                                    + "every screen it will create, what "
                                    + "is on each screen, what data is "
                                    + "kept and where, what each button "
                                    + "does, and the order the work "
                                    + "happens in. Write that, show it to "
                                    + "the user in chat, then save it.");
                }
            } else if (steps.isEmpty() && done < 0 && current < 0) {
                throw new ToolException("E_BAD_ARGS",
                        "Pass \"done_steps\" (and optional "
                                + "\"current_step\") to update progress, "
                                + "or new lines in \"steps\" to append "
                                + "new milestones.");
            }

            JsonObject result = new JsonObject();
            if (noProject) {
                AgentJournal.setDraftPlan(title, steps);
                result.addProperty("saved", "draft");
                result.addProperty("message",
                        "Plan remembered as a draft. When the project "
                                + "is created it becomes that project's "
                                + "plan automatically.");
                return result;
            }
            String scId = requiredProject(session.workingProjectId);
            int saved = AgentJournal.setPlan(scId, title, steps,
                    hadPlan ? done : Math.max(0, done),
                    hadPlan ? current : Math.max(0, current));
            int[] progress = AgentJournal.planProgress(scId);
            result.addProperty("saved", true);
            result.addProperty("done_steps", progress[0]);
            result.addProperty("total_steps", progress[1]);
            if (hadPlan) {
                String progressLine = "Plan progress saved: " + progress[0]
                        + " of " + progress[1] + " steps done.";
                if (saved > 0) {
                    progressLine += " Appended " + saved + " new step"
                            + (saved == 1 ? "." : "s.");
                }
                result.addProperty("message", progressLine);
                return result;
            }
            result.addProperty("message",
                    "Plan saved (" + progress[1] + " steps). Call "
                            + "set_plan with done_steps as each milestone "
                            + "finishes.");
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
                    .append("” should look. (If the user attached a "
                            + "screenshot or UI mockup to recreate, derive "
                            + "the palette, typography, corner radii, and "
                            + "spacing directly from that image instead of "
                            + "calling ask_choice.) Otherwise ask the user "
                            + "with ask_choice (two to four real directions, "
                            + "each one a whole look: “sunny and playful: "
                            + "big rounded cards, orange #FF8A3D, cream "
                            + "background” / “calm and minimal: thin type, "
                            + "one accent, lots of white space”). If they "
                            + "would rather you chose, say which you picked "
                            + "and why in one line. Then save it with "
                            + "set_style_brief as headed lines: Audience; "
                            + "Mood; Colours (with hex); Text sizes "
                            + "(title/body/button sp); Buttons and cards "
                            + "(height, corner roundness); Spacing; Do; "
                            + "Avoid. ");
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
            properties.add("kind", stringSchema(
                    "Optional screen kind: activity (default), fragment, "
                            + "dialog_fragment, or bottom_sheet_fragment"));
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
            String kind = inferFragmentKindOrEmpty(asked,
                    stringArg(args, "kind"));
            if (!kind.isEmpty()) {
                JsonObject forwarded = new JsonObject();
                forwarded.addProperty("name", asked);
                forwarded.addProperty("kind", kind);
                AgentProposal fp = new CreateFragment().propose(
                        session, forwarded);
                JsonObject clean = fp.args.deepCopy();
                clean.addProperty("activity",
                        stringArg(fp.args, "file_name"));
                clean.addProperty("fragment_mode", true);
                return new AgentProposal(name(), clean, scId,
                        fp.title, fp.body, fp.details);
            }
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
            if (proposal.args != null
                    && proposal.args.has("fragment_mode")) {
                return new CreateFragment().execute(session, proposal);
            }
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
            boolean isFragment = SketchwareProjects.isFragment(target);
            int options = target.options;
            int orientation = target.orientation;
            List<String> lines = new java.util.ArrayList<>();
            if (args.has("toolbar")) {
                boolean show = args.get("toolbar").getAsBoolean();
                if (isFragment && show) {
                    throw new ToolException("E_FRAGMENT_OPTION",
                            SketchwareProjects.activityName(target)
                                    + " is a Fragment and does not own a top "
                                    + "toolbar — configure the toolbar on "
                                    + "its host Activity instead.");
                }
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
                if (isFragment && show) {
                    throw new ToolException("E_FRAGMENT_OPTION",
                            SketchwareProjects.activityName(target)
                                    + " is a Fragment and cannot have its "
                                    + "own navigation drawer — enable the "
                                    + "drawer on its host Activity instead.");
                }
                options = show
                        ? options | ProjectFileBean.OPTION_ACTIVITY_DRAWER
                        : options & ~ProjectFileBean.OPTION_ACTIVITY_DRAWER;
                if (show && !args.has("toolbar")
                        && (options
                                & ProjectFileBean.OPTION_ACTIVITY_TOOLBAR) == 0) {
                    options |= ProjectFileBean.OPTION_ACTIVITY_TOOLBAR;
                    lines.add("Show "
                            + SketchwareProjects.activityName(target)
                            + "'s top toolbar for the drawer toggle");
                }
                lines.add((show ? "Give " : "Remove the navigation drawer of ")
                        + SketchwareProjects.activityName(target)
                        + (show
                                ? " a navigation drawer (its _drawer_"
                                        + target.fileName
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

    // -- Phase 1: Libraries, Material 3, Maven/Local Libraries & Permissions

    private static List<String> stringListArg(JsonObject args, String key)
            throws ToolException {
        JsonArray arr = jsonArrayArg(args, key);
        if (arr == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el != null && el.isJsonPrimitive()) {
                String s = el.getAsString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** list_local_libraries (read-only): local libs, built-in libs & permission search. */
    private static final class ListLocalLibraries implements Tool {
        @Override
        public String name() {
            return "list_local_libraries";
        }

        @Override
        public String description() {
            return "Lists all local/Maven libraries downloaded on the "
                    + "device, which ones are enabled in the working "
                    + "project, excluded built-in libraries, and "
                    + "(optionally) searches known built-in library names "
                    + "or available Android permissions.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("filter_builtin", stringSchema(
                    "Optional substring to search Sketchware's built-in "
                            + "library names (e.g. \"material\", "
                            + "\"okhttp\", \"lifecycle\"). Pass \"*\" "
                            + "to list all."));
            properties.add("filter_permissions", stringSchema(
                    "Optional substring to search valid Android "
                            + "Manifest permissions (e.g. \"CAMERA\", "
                            + "\"MEDIA\", \"BLUETOOTH\")."));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Checked local libraries and setup catalog";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = session.workingProjectId == null
                    ? "" : session.workingProjectId;
            JsonObject result = new JsonObject();
            result.add("local_libraries", AgentSetup.localLibraries(scId));
            if (!scId.isEmpty()) {
                result.add("excluded_builtin_libraries",
                        AgentSetup.excludedBuiltInLibraries(scId));
            }
            String builtinFilter = stringArg(args, "filter_builtin");
            if (!builtinFilter.isEmpty()) {
                JsonArray builtins = new JsonArray();
                String needle = "*".equals(builtinFilter)
                        ? "" : builtinFilter.toLowerCase(Locale.ROOT);
                for (String libName : AgentSetup.knownBuiltInLibraryNames()) {
                    if (needle.isEmpty() || libName.toLowerCase(Locale.ROOT)
                            .contains(needle)) {
                        builtins.add(libName);
                    }
                }
                result.add("matching_builtin_libraries", builtins);
            }
            String permFilter = stringArg(args, "filter_permissions");
            if (!permFilter.isEmpty()) {
                JsonArray perms = new JsonArray();
                for (String p : AgentSetup.availablePermissions(
                        "*".equals(permFilter) ? "" : permFilter)) {
                    perms.add(p);
                }
                result.add("matching_permissions", perms);
            }
            return result;
        }
    }

    /** configure_library: AppCompat, Material 3, Firebase, AdMob, Google Maps. */
    private static final class ConfigureLibrary implements Tool {
        @Override
        public String name() {
            return "configure_library";
        }

        @Override
        public String description() {
            return "Enables, disables, or configures one of Sketchware "
                    + "Pro's built-in libraries for the working project: "
                    + "\"appcompat\" (AndroidX AppCompat & Material "
                    + "Components), \"material3\" (Material 3 components, "
                    + "dynamic_colors, and theme: \"DayNight\", \"Light\" "
                    + "or \"Dark\" — automatically turns on AppCompat "
                    + "when enabled), \"firebase\" (accepts "
                    + "google_services_json content OR database_url, "
                    + "app_id, api_key, storage_bucket — automatically "
                    + "turns on AppCompat when enabled), \"admob\" "
                    + "(app_id, banner_ad_unit_id, "
                    + "interstitial_ad_unit_id, reward_ad_unit_id, "
                    + "test_devices), or \"google_maps\" (api_key).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("library", stringSchema(
                    "Which built-in library: \"appcompat\", \"material3\", "
                            + "\"firebase\", \"admob\", or \"google_maps\""));
            properties.add("enabled", booleanSchema(
                    "True to switch the library on, false to switch it off"));
            properties.add("dynamic_colors", booleanSchema(
                    "For material3: enable Android 12+ Material You "
                            + "dynamic colors (optional)"));
            properties.add("theme", stringSchema(
                    "For material3: \"DayNight\", \"Light\", or \"Dark\" "
                            + "(optional; default \"DayNight\")"));
            properties.add("google_services_json", stringSchema(
                    "For firebase: full contents of google-services.json "
                            + "to extract database_url, app_id, api_key, "
                            + "and storage_bucket automatically"));
            properties.add("database_url", stringSchema(
                    "For firebase: Realtime Database host/URL "
                            + "(e.g. \"my-app-default-rtdb.firebaseio.com\")"));
            properties.add("app_id", stringSchema(
                    "For firebase (mobilesdk_app_id, e.g. \"1:123...:android:abc\") "
                            + "or admob (AdMob App ID, e.g. \"ca-app-pub-...\")"));
            properties.add("api_key", stringSchema(
                    "For firebase (Web/Android API key) or google_maps "
                            + "(Maps SDK API key)"));
            properties.add("storage_bucket", stringSchema(
                    "For firebase: Storage bucket (e.g. \"my-app.appspot.com\")"));
            properties.add("banner_ad_unit_id", stringSchema(
                    "For admob: Banner Ad Unit ID (or \"Name : id\")"));
            properties.add("banner_ad_name", stringSchema(
                    "For admob: Optional friendly name for banner ad unit"));
            properties.add("interstitial_ad_unit_id", stringSchema(
                    "For admob: Interstitial Ad Unit ID (or \"Name : id\")"));
            properties.add("interstitial_ad_name", stringSchema(
                    "For admob: Optional friendly name for interstitial ad unit"));
            properties.add("reward_ad_unit_id", stringSchema(
                    "For admob: Optional Rewarded Video Ad Unit ID"));
            properties.add("reward_ad_name", stringSchema(
                    "For admob: Optional friendly name for reward ad unit"));
            properties.add("test_devices", stringArraySchema(
                    "For admob: Optional list of test device IDs"));
            return AgentTools.parameters(properties, "library", "enabled");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Configuring "
                    + AgentSetup.libraryName(
                            stringArg(args, "library").toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String lib = stringArg(args, "library").toLowerCase(Locale.ROOT);
            if ("googlemap".equals(lib) || "maps".equals(lib)) {
                lib = AgentSetup.GOOGLE_MAPS;
            } else if ("compat".equals(lib)) {
                lib = AgentSetup.APPCOMPAT;
            } else if ("m3".equals(lib) || "material_3".equals(lib)) {
                lib = AgentSetup.MATERIAL3;
            }
            if (!args.has("enabled") || args.get("enabled").isJsonNull()) {
                throw new ToolException("E_BAD_ARGS",
                        "Pass \"enabled\": true or false.");
            }
            boolean turnOn = booleanArg(args, "enabled");
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            com.besome.sketch.beans.ProjectLibraryBean[] beans =
                    AgentSetup.loadLibraryBeans(scId);
            com.besome.sketch.beans.ProjectLibraryBean compat = beans[0];
            com.besome.sketch.beans.ProjectLibraryBean firebase = beans[1];
            com.besome.sketch.beans.ProjectLibraryBean admob = beans[2];
            com.besome.sketch.beans.ProjectLibraryBean maps = beans[3];

            JsonObject clean = new JsonObject();
            clean.addProperty("library", lib);
            clean.addProperty("enabled", turnOn);

            String title;
            String body;
            String details;

            switch (lib) {
                case AgentSetup.APPCOMPAT -> {
                    boolean wasOn = compat.isEnabled();
                    if (wasOn == turnOn) {
                        throw new ToolException("E_ALREADY_SET",
                                "AppCompat is already "
                                        + (turnOn ? "enabled" : "disabled")
                                        + " on this project.");
                    }
                    title = (turnOn ? "Enable" : "Disable")
                            + " AppCompat in " + project.name;
                    body = turnOn
                            ? "Switch on AndroidX AppCompat & Material "
                                    + "Components so CardView, RecyclerView, "
                                    + "TabLayout, TextInputLayout, and "
                                    + "Material buttons compile cleanly."
                            : "Switch off AppCompat (also turns off Material 3 "
                                    + "and Firebase if active).";
                    details = "Library: AppCompat\nState: "
                            + (wasOn ? "on" : "off") + " → "
                            + (turnOn ? "on" : "off");
                }
                case AgentSetup.MATERIAL3 -> {
                    String theme = stringArg(args, "theme");
                    if (theme.isEmpty()) {
                        Object existingTheme = compat.configurations.get("theme");
                        theme = existingTheme instanceof String s && !s.isEmpty()
                                ? s : "DayNight";
                    }
                    if (!"DayNight".equals(theme) && !"Light".equals(theme)
                            && !"Dark".equals(theme)) {
                        throw new ToolException("E_BAD_ARGS",
                                "Material 3 \"theme\" must be \"DayNight\", "
                                        + "\"Light\", or \"Dark\".");
                    }
                    boolean dyn = args.has("dynamic_colors")
                            ? booleanArg(args, "dynamic_colors")
                            : Boolean.TRUE.equals(
                                    compat.configurations.get("dynamic_colors"));
                    clean.addProperty("dynamic_colors", dyn);
                    clean.addProperty("theme", theme);
                    title = (turnOn ? "Enable" : "Disable")
                            + " Material 3 in " + project.name;
                    body = turnOn
                            ? "Enable Material 3 (" + theme + " theme, "
                                    + "dynamic colors "
                                    + (dyn ? "on" : "off") + ")"
                                    + (compat.isEnabled() ? "."
                                            : " and switch on AppCompat.")
                            : "Disable Material 3 styling on " + project.name + ".";
                    details = "Library: Material 3\nEnabled: " + turnOn
                            + "\nTheme: " + theme
                            + "\nDynamic colors: " + dyn
                            + "\nAppCompat: " + (turnOn || compat.isEnabled());
                }
                case AgentSetup.FIREBASE -> {
                    Map<String, String> parsed = AgentSetup
                            .parseGoogleServicesJson(
                                    stringArg(args, "google_services_json"));
                    String dbUrl = !stringArg(args, "database_url").isEmpty()
                            ? AgentSetup.stripHttps(
                                    stringArg(args, "database_url"))
                            : parsed.getOrDefault("database_url",
                                    firebase.data == null ? "" : firebase.data.trim());
                    String appId = !stringArg(args, "app_id").isEmpty()
                            ? stringArg(args, "app_id")
                            : parsed.getOrDefault("app_id",
                                    firebase.reserved1 == null ? ""
                                            : firebase.reserved1.trim());
                    String apiKey = !stringArg(args, "api_key").isEmpty()
                            ? stringArg(args, "api_key")
                            : parsed.getOrDefault("api_key",
                                    firebase.reserved2 == null ? ""
                                            : firebase.reserved2.trim());
                    String bucket = !stringArg(args, "storage_bucket").isEmpty()
                            ? AgentSetup.stripHttps(
                                    stringArg(args, "storage_bucket"))
                            : parsed.getOrDefault("storage_bucket",
                                    firebase.reserved3 == null ? ""
                                            : firebase.reserved3.trim());
                    if (turnOn && (dbUrl.isEmpty() || appId.isEmpty()
                            || apiKey.isEmpty())) {
                        throw new ToolException("E_BAD_ARGS",
                                "Enabling Firebase requires \"google_services_json\" "
                                        + "or \"database_url\", \"app_id\", and "
                                        + "\"api_key\". Ask the user for their "
                                        + "google-services.json or Firebase "
                                        + "project settings first.");
                    }
                    clean.addProperty("database_url", dbUrl);
                    clean.addProperty("app_id", appId);
                    clean.addProperty("api_key", apiKey);
                    clean.addProperty("storage_bucket", bucket);
                    title = (turnOn ? "Configure Firebase in "
                            : "Disable Firebase in ") + project.name;
                    body = turnOn
                            ? "Enable Firebase (" + dbUrl + ")"
                                    + (compat.isEnabled() ? "."
                                            : " and switch on AppCompat.")
                            : "Switch off Firebase in " + project.name + ".";
                    details = "Library: Firebase\nEnabled: " + turnOn
                            + "\nDatabase URL: " + dbUrl
                            + "\nApp ID: " + appId
                            + "\nAPI key configured: " + (!apiKey.isEmpty())
                            + "\nStorage bucket: "
                            + (bucket.isEmpty() ? "(none)" : bucket);
                }
                case AgentSetup.ADMOB -> {
                    String appId = !stringArg(args, "app_id").isEmpty()
                            ? stringArg(args, "app_id")
                            : (admob.appId == null ? "" : admob.appId.trim());
                    String bannerId = !stringArg(args, "banner_ad_unit_id").isEmpty()
                            ? stringArg(args, "banner_ad_unit_id")
                            : (admob.reserved1 == null ? "" : admob.reserved1.trim());
                    String bannerName = stringArg(args, "banner_ad_name");
                    String interId = !stringArg(args, "interstitial_ad_unit_id").isEmpty()
                            ? stringArg(args, "interstitial_ad_unit_id")
                            : (admob.reserved2 == null ? "" : admob.reserved2.trim());
                    String interName = stringArg(args, "interstitial_ad_name");
                    String rewardId = !stringArg(args, "reward_ad_unit_id").isEmpty()
                            ? stringArg(args, "reward_ad_unit_id")
                            : (admob.reserved3 == null ? "" : admob.reserved3.trim());
                    String rewardName = stringArg(args, "reward_ad_name");
                    List<String> testDevices = args.has("test_devices")
                            ? stringListArg(args, "test_devices") : null;
                    if (turnOn && appId.isEmpty()) {
                        throw new ToolException("E_BAD_ARGS",
                                "Enabling AdMob requires \"app_id\" (and "
                                        + "ad unit IDs for banner/interstitial). "
                                        + "Ask the user for their AdMob App ID "
                                        + "or whether they want Google's official "
                                        + "AdMob test IDs for development.");
                    }
                    clean.addProperty("app_id", appId);
                    clean.addProperty("banner_ad_unit_id", bannerId);
                    clean.addProperty("banner_ad_name", bannerName);
                    clean.addProperty("interstitial_ad_unit_id", interId);
                    clean.addProperty("interstitial_ad_name", interName);
                    clean.addProperty("reward_ad_unit_id", rewardId);
                    clean.addProperty("reward_ad_name", rewardName);
                    if (testDevices != null) {
                        JsonArray td = new JsonArray();
                        for (String d : testDevices) {
                            td.add(d);
                        }
                        clean.add("test_devices", td);
                    }
                    title = (turnOn ? "Configure AdMob in "
                            : "Disable AdMob in ") + project.name;
                    body = turnOn
                            ? "Enable AdMob (App ID " + appId + ")."
                            : "Switch off AdMob in " + project.name + ".";
                    details = "Library: AdMob\nEnabled: " + turnOn
                            + "\nApp ID: " + appId
                            + "\nBanner unit: "
                            + (bannerId.isEmpty() ? "(none)" : bannerId)
                            + "\nInterstitial unit: "
                            + (interId.isEmpty() ? "(none)" : interId)
                            + "\nReward unit: "
                            + (rewardId.isEmpty() ? "(none)" : rewardId);
                }
                case AgentSetup.GOOGLE_MAPS -> {
                    String apiKey = !stringArg(args, "api_key").isEmpty()
                            ? stringArg(args, "api_key")
                            : (maps.data == null ? "" : maps.data.trim());
                    if (turnOn && apiKey.isEmpty()) {
                        throw new ToolException("E_BAD_ARGS",
                                "Enabling Google Maps requires a non-empty "
                                        + "\"api_key\". Ask the user for their "
                                        + "Google Maps SDK API key first.");
                    }
                    clean.addProperty("api_key", apiKey);
                    title = (turnOn ? "Configure Google Maps in "
                            : "Disable Google Maps in ") + project.name;
                    body = turnOn
                            ? "Enable Google Maps SDK with the provided API key."
                            : "Switch off Google Maps in " + project.name + ".";
                    details = "Library: Google Maps\nEnabled: " + turnOn
                            + "\nAPI key configured: " + (!apiKey.isEmpty());
                }
                default -> throw new ToolException("E_BAD_ARGS",
                        "Unknown library \"" + lib + "\". Choose one of: "
                                + "\"appcompat\", \"material3\", \"firebase\", "
                                + "\"admob\", \"google_maps\".");
            }
            return new AgentProposal(name(), clean, scId, title, body, details);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String lib = stringArg(proposal.args, "library");
            boolean turnOn = booleanArg(proposal.args, "enabled");
            com.besome.sketch.beans.ProjectLibraryBean[] beans =
                    AgentSetup.loadLibraryBeans(scId);
            com.besome.sketch.beans.ProjectLibraryBean compat = beans[0];
            com.besome.sketch.beans.ProjectLibraryBean firebase = beans[1];
            com.besome.sketch.beans.ProjectLibraryBean admob = beans[2];
            com.besome.sketch.beans.ProjectLibraryBean maps = beans[3];

            switch (lib) {
                case AgentSetup.APPCOMPAT -> {
                    compat.useYn = turnOn ? "Y" : "N";
                    if (!turnOn) {
                        compat.configurations.put("material3", false);
                        firebase.useYn = "N";
                    }
                }
                case AgentSetup.MATERIAL3 -> {
                    if (turnOn) {
                        compat.useYn = "Y";
                    }
                    compat.configurations.put("material3", turnOn);
                    compat.configurations.put("dynamic_colors",
                            booleanArg(proposal.args, "dynamic_colors"));
                    compat.configurations.put("theme",
                            stringArg(proposal.args, "theme"));
                }
                case AgentSetup.FIREBASE -> {
                    firebase.useYn = turnOn ? "Y" : "N";
                    firebase.data = stringArg(proposal.args, "database_url");
                    firebase.reserved1 = stringArg(proposal.args, "app_id");
                    firebase.reserved2 = stringArg(proposal.args, "api_key");
                    firebase.reserved3 = stringArg(proposal.args,
                            "storage_bucket");
                    if (turnOn) {
                        compat.useYn = "Y";
                    }
                }
                case AgentSetup.ADMOB -> {
                    admob.useYn = turnOn ? "Y" : "N";
                    admob.appId = stringArg(proposal.args, "app_id");
                    String bId = stringArg(proposal.args, "banner_ad_unit_id");
                    if (!bId.isEmpty()) {
                        admob.reserved1 = AgentSetup.registerAdUnit(admob,
                                stringArg(proposal.args, "banner_ad_name"), bId);
                    }
                    String iId = stringArg(proposal.args,
                            "interstitial_ad_unit_id");
                    if (!iId.isEmpty()) {
                        admob.reserved2 = AgentSetup.registerAdUnit(admob,
                                stringArg(proposal.args, "interstitial_ad_name"),
                                iId);
                    }
                    String rId = stringArg(proposal.args, "reward_ad_unit_id");
                    if (!rId.isEmpty()) {
                        admob.reserved3 = AgentSetup.registerAdUnit(admob,
                                stringArg(proposal.args, "reward_ad_name"), rId);
                    }
                    if (proposal.args.has("test_devices")) {
                        AgentSetup.replaceTestDevices(admob,
                                stringListArg(proposal.args, "test_devices"));
                    }
                }
                case AgentSetup.GOOGLE_MAPS -> {
                    maps.useYn = turnOn ? "Y" : "N";
                    maps.data = stringArg(proposal.args, "api_key");
                }
                default -> throw new ToolException("E_BAD_ARGS",
                        "Unsupported library: " + lib);
            }
            try {
                AgentSetup.saveBuiltInLibraries(scId, compat, firebase,
                        admob, maps);
            } catch (Exception e) {
                throw new ToolException("E_SAVE_FAILED",
                        "Saving library settings failed: "
                                + String.valueOf(e.getMessage()));
            }
            JsonObject verified = AgentSetup.libraries(scId);
            JsonObject result = new JsonObject();
            result.addProperty("configured", lib);
            result.addProperty("enabled", turnOn);
            result.add("libraries", verified);
            result.addProperty("verified",
                    AgentSetup.libraryName(lib) + " is now "
                            + (turnOn ? "enabled" : "disabled")
                            + " (read back from Library Manager state).");
            return result;
        }
    }

    /** manage_permissions: declare or remove Android Manifest permissions. */
    private static final class ManagePermissions implements Tool {
        @Override
        public String name() {
            return "manage_permissions";
        }

        @Override
        public String description() {
            return "Adds and/or removes Android Manifest permissions "
                    + "for the working project (saved in Sketchware's "
                    + "Permission Manager). Accepts full names like "
                    + "\"android.permission.INTERNET\" or short names "
                    + "like \"INTERNET\", \"CAMERA\", "
                    + "\"POST_NOTIFICATIONS\". Set "
                    + "\"auto_add_recommended\": true to automatically "
                    + "add all missing permissions detected for the "
                    + "project's widgets, components, and libraries.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("add", stringArraySchema(
                    "Permissions to add, e.g. "
                            + "[\"android.permission.INTERNET\", "
                            + "\"android.permission.CAMERA\"]"));
            properties.add("remove", stringArraySchema(
                    "Permissions to remove"));
            properties.add("auto_add_recommended", booleanSchema(
                    "When true, automatically includes every missing "
                            + "permission found by get_project_setup's "
                            + "prerequisite_audit"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Updating Android permissions";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            List<String> rawAdd = stringListArg(args, "add");
            List<String> rawRemove = stringListArg(args, "remove");
            boolean autoRec = booleanArg(args, "auto_add_recommended");

            java.util.Set<String> normalizedAdd = new java.util.LinkedHashSet<>();
            for (String p : rawAdd) {
                String norm = AgentSetup.normalizePermission(p);
                if (norm == null) {
                    throw new ToolException("E_BAD_PERMISSION",
                            "Unrecognized Android permission \"" + p
                                    + "\". Pass standard Android permission "
                                    + "names such as "
                                    + "\"android.permission.INTERNET\" or "
                                    + "call list_local_libraries with "
                                    + "filter_permissions to search.");
                }
                normalizedAdd.add(norm);
            }
            if (autoRec) {
                JsonObject audit = AgentSetup.auditPrerequisites(scId);
                if (audit.has("missing_permissions")) {
                    for (JsonElement el
                            : audit.getAsJsonArray("missing_permissions")) {
                        normalizedAdd.add(el.getAsString());
                    }
                }
            }
            java.util.Set<String> normalizedRemove =
                    new java.util.LinkedHashSet<>();
            for (String p : rawRemove) {
                String norm = AgentSetup.normalizePermission(p);
                normalizedRemove.add(norm != null ? norm : p.trim());
            }
            normalizedAdd.removeAll(normalizedRemove);

            if (normalizedAdd.isEmpty() && normalizedRemove.isEmpty()) {
                throw new ToolException("E_ALREADY_SET",
                        autoRec
                                ? "All recommended permissions are already "
                                        + "declared in this project."
                                : "Pass at least one permission in \"add\" "
                                        + "or \"remove\", or set "
                                        + "\"auto_add_recommended\": true.");
            }

            java.util.Set<String> existing = new java.util.LinkedHashSet<>();
            for (JsonElement el : AgentSetup.permissions(scId)) {
                existing.add(el.getAsString());
            }
            List<String> effectiveAdd = new ArrayList<>();
            for (String p : normalizedAdd) {
                if (!existing.contains(p)) {
                    effectiveAdd.add(p);
                }
            }
            List<String> effectiveRemove = new ArrayList<>();
            for (String p : normalizedRemove) {
                if (existing.contains(p)) {
                    effectiveRemove.add(p);
                }
            }
            if (effectiveAdd.isEmpty() && effectiveRemove.isEmpty()) {
                throw new ToolException("E_ALREADY_SET",
                        "Those permissions are already in that exact state "
                                + "on this project.");
            }

            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            JsonArray addArr = new JsonArray();
            for (String p : effectiveAdd) {
                addArr.add(p);
            }
            JsonArray remArr = new JsonArray();
            for (String p : effectiveRemove) {
                remArr.add(p);
            }
            clean.add("add", addArr);
            clean.add("remove", remArr);

            StringBuilder body = new StringBuilder();
            if (!effectiveAdd.isEmpty()) {
                body.append("Add ")
                        .append(effectiveAdd.size())
                        .append(effectiveAdd.size() == 1
                                ? " permission: " : " permissions: ")
                        .append(String.join(", ", effectiveAdd))
                        .append(".");
            }
            if (!effectiveRemove.isEmpty()) {
                if (body.length() > 0) {
                    body.append("\n");
                }
                body.append("Remove ")
                        .append(effectiveRemove.size())
                        .append(effectiveRemove.size() == 1
                                ? " permission: " : " permissions: ")
                        .append(String.join(", ", effectiveRemove))
                        .append(".");
            }
            return new AgentProposal(name(), clean, scId,
                    "Update permissions in " + project.name,
                    body.toString(),
                    "Saved directly to Sketchware's Permission Manager "
                            + "(.sketchware/data/" + scId + "/permission).");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            List<String> add = stringListArg(proposal.args, "add");
            List<String> remove = stringListArg(proposal.args, "remove");
            try {
                List<String> updated =
                        AgentSetup.updatePermissions(scId, add, remove);
                JsonObject result = new JsonObject();
                JsonArray arr = new JsonArray();
                for (String p : updated) {
                    arr.add(p);
                }
                result.add("permissions", arr);
                result.addProperty("count", updated.size());
                result.addProperty("verified",
                        "Project now declares " + updated.size()
                                + " permission(s).");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_SAVE_FAILED",
                        "Updating permissions failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** manage_local_libraries: toggle local libraries and excluded built-in libraries. */
    private static final class ManageLocalLibraries implements Tool {
        @Override
        public String name() {
            return "manage_local_libraries";
        }

        @Override
        public String description() {
            return "Enables or disables downloaded local libraries for "
                    + "the working project, and/or configures Excluded "
                    + "Built-in Libraries (useful when a newer local "
                    + "Maven library replaces an older built-in JAR). "
                    + "Call list_local_libraries first to inspect exact "
                    + "library folder names.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("enable", stringArraySchema(
                    "Local library names to enable in this project"));
            properties.add("disable", stringArraySchema(
                    "Local library names to disable in this project"));
            properties.add("excluding_enabled", booleanSchema(
                    "Optional: enable or disable built-in library exclusion"));
            properties.add("exclude_builtin", stringArraySchema(
                    "Optional: built-in library names to exclude during build"));
            properties.add("include_builtin", stringArraySchema(
                    "Optional: previously excluded built-in libraries to re-include"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Updating local & built-in library configuration";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            List<String> enable = stringListArg(args, "enable");
            List<String> disable = stringListArg(args, "disable");
            Boolean excludingEnabled = args.has("excluding_enabled")
                    && !args.get("excluding_enabled").isJsonNull()
                    ? booleanArg(args, "excluding_enabled") : null;
            List<String> excludeBuiltin = stringListArg(args, "exclude_builtin");
            List<String> includeBuiltin = stringListArg(args, "include_builtin");

            if (enable.isEmpty() && disable.isEmpty()
                    && excludingEnabled == null
                    && excludeBuiltin.isEmpty()
                    && includeBuiltin.isEmpty()) {
                throw new ToolException("E_BAD_ARGS",
                        "Pass at least one of \"enable\", \"disable\", "
                                + "\"excluding_enabled\", \"exclude_builtin\", "
                                + "or \"include_builtin\".");
            }

            if (!enable.isEmpty()) {
                java.util.Set<String> onDisk = new java.util.LinkedHashSet<>();
                JsonObject local = AgentSetup.localLibraries(scId);
                for (JsonElement el
                        : local.getAsJsonArray("available_on_device")) {
                    onDisk.add(el.getAsJsonObject().get("name").getAsString());
                }
                for (String req : enable) {
                    if (!onDisk.contains(req)) {
                        throw new ToolException("E_LIBRARY_NOT_FOUND",
                                "Local library \"" + req + "\" is not "
                                        + "downloaded on this device yet. "
                                        + "Call list_local_libraries to see "
                                        + "available names, or call "
                                        + "download_maven_library to download "
                                        + "it from Maven.");
                    }
                }
            }

            if (!excludeBuiltin.isEmpty()) {
                java.util.Set<String> known = new java.util.LinkedHashSet<>(
                        AgentSetup.knownBuiltInLibraryNames());
                for (String b : excludeBuiltin) {
                    if (!known.contains(b)) {
                        throw new ToolException("E_LIBRARY_NOT_FOUND",
                                "Unknown built-in library \"" + b + "\". "
                                        + "Call list_local_libraries with "
                                        + "filter_builtin=\"*\" to see exact "
                                        + "built-in library names.");
                    }
                }
                if (excludingEnabled == null) {
                    excludingEnabled = true;
                }
            }

            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            if (!enable.isEmpty()) {
                clean.add("enable", namesJsonAll(enable));
            }
            if (!disable.isEmpty()) {
                clean.add("disable", namesJsonAll(disable));
            }
            if (excludingEnabled != null) {
                clean.addProperty("excluding_enabled", excludingEnabled);
            }
            if (!excludeBuiltin.isEmpty()) {
                clean.add("exclude_builtin", namesJsonAll(excludeBuiltin));
            }
            if (!includeBuiltin.isEmpty()) {
                clean.add("include_builtin", namesJsonAll(includeBuiltin));
            }

            StringBuilder summary = new StringBuilder();
            if (!enable.isEmpty()) {
                summary.append("Enable local libraries: ")
                        .append(String.join(", ", enable)).append(". ");
            }
            if (!disable.isEmpty()) {
                summary.append("Disable local libraries: ")
                        .append(String.join(", ", disable)).append(". ");
            }
            if (excludingEnabled != null || !excludeBuiltin.isEmpty()
                    || !includeBuiltin.isEmpty()) {
                summary.append("Update excluded built-in libraries.");
            }
            return new AgentProposal(name(), clean, scId,
                    "Update libraries in " + project.name,
                    summary.toString().trim(), clean.toString());
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            List<String> enable = stringListArg(proposal.args, "enable");
            List<String> disable = stringListArg(proposal.args, "disable");
            Boolean excludingEnabled = proposal.args.has("excluding_enabled")
                    ? booleanArg(proposal.args, "excluding_enabled") : null;
            List<String> excludeBuiltin =
                    stringListArg(proposal.args, "exclude_builtin");
            List<String> includeBuiltin =
                    stringListArg(proposal.args, "include_builtin");

            try {
                JsonObject result = new JsonObject();
                if (!enable.isEmpty() || !disable.isEmpty()) {
                    List<String> nowEnabled = AgentSetup.updateLocalLibraries(
                            scId, enable, disable);
                    result.add("enabled_local_libraries",
                            namesJsonAll(nowEnabled));
                }
                if (excludingEnabled != null || !excludeBuiltin.isEmpty()
                        || !includeBuiltin.isEmpty()) {
                    AgentSetup.updateExcludedBuiltInLibraries(
                            scId, excludingEnabled,
                            excludeBuiltin, includeBuiltin);
                    result.add("excluded_builtin_libraries",
                            AgentSetup.excludedBuiltInLibraries(scId));
                }
                result.addProperty("verified",
                        "Local and built-in library configuration saved.");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_SAVE_FAILED",
                        "Updating library configuration failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** download_maven_library: download + D8 dex + enable a Maven artifact. */
    private static final class DownloadMavenLibrary implements Tool {
        @Override
        public String name() {
            return "download_maven_library";
        }

        @Override
        public String description() {
            return "Downloads an external Maven/AndroidX/Google library "
                    + "by coordinate (\"group:artifact:version\", e.g. "
                    + "\"com.squareup.okhttp3:okhttp:4.12.0\"), unzips "
                    + "AAR/JAR resources, compiles classes to DEX with "
                    + "D8 via Sketchware Pro's DependencyResolver, saves "
                    + "it into .sketchware/libs/local_libs, and enables "
                    + "it in the working project.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("coordinate", stringSchema(
                    "Maven coordinate in group:artifact:version format, "
                            + "e.g. \"com.google.code.gson:gson:2.11.0\""));
            properties.add("skip_subdependencies", booleanSchema(
                    "True to download only this artifact without "
                            + "transitive sub-dependencies (default false)"));
            properties.add("enable_in_project", booleanSchema(
                    "True (default) to automatically enable the downloaded "
                            + "library in the working project"));
            return AgentTools.parameters(properties, "coordinate");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Downloading Maven library "
                    + stringArg(args, "coordinate");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String coord = stringArg(args, "coordinate");
            String[] parts = coord.split(":");
            if (parts.length != 3 || parts[0].trim().isEmpty()
                    || parts[1].trim().isEmpty()
                    || parts[2].trim().isEmpty()) {
                throw new ToolException("E_BAD_COORDINATE",
                        "Invalid Maven coordinate \"" + coord
                                + "\". Pass \"group:artifact:version\" "
                                + "(three colon-separated parts).");
            }
            boolean skipDeps = booleanArg(args, "skip_subdependencies");
            boolean enableInProject = !args.has("enable_in_project")
                    || args.get("enable_in_project").isJsonNull()
                    || booleanArg(args, "enable_in_project");

            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("coordinate",
                    parts[0].trim() + ":" + parts[1].trim() + ":"
                            + parts[2].trim());
            clean.addProperty("skip_subdependencies", skipDeps);
            clean.addProperty("enable_in_project", enableInProject);

            return new AgentProposal(name(), clean, scId,
                    "Download Maven library " + parts[1].trim(),
                    "Download “" + clean.get("coordinate").getAsString()
                            + "”"
                            + (skipDeps
                                    ? " (without sub-dependencies)"
                                    : " and its required sub-dependencies")
                            + ", compile to DEX with D8, and "
                            + (enableInProject
                                    ? "enable it in " + project.name + "."
                                    : "save it to Local Libraries."),
                    "Coordinate: " + clean.get("coordinate").getAsString()
                            + "\nTransitive dependencies: "
                            + (skipDeps ? "skipped" : "included")
                            + "\nEnable in project: " + enableInProject);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String coord = stringArg(proposal.args, "coordinate");
            boolean skipDeps = booleanArg(proposal.args,
                    "skip_subdependencies");
            boolean enable = !proposal.args.has("enable_in_project")
                    || booleanArg(proposal.args, "enable_in_project");
            try {
                JsonObject out = AgentSetup.downloadMavenLibrary(
                        scId, coord, skipDeps, enable);
                out.addProperty("verified",
                        "Downloaded and processed "
                                + out.getAsJsonArray("downloaded_libraries")
                                        .size() + " library artifact(s).");
                return out;
            } catch (Exception e) {
                throw new ToolException("E_DOWNLOAD_FAILED",
                        "Maven library download/dexing failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** search_builtin_icons: search Sketchware Pro's built-in Material Icon Pack. */
    private static final class SearchBuiltinIcons implements Tool {
        @Override
        public String name() {
            return "search_builtin_icons";
        }

        @Override
        public String description() {
            return "Searches Sketchware Pro's built-in Material Icon Pack "
                    + "by keyword (e.g. \"search\", \"arrow_back\", \"home\", "
                    + "\"delete\", \"calendar\", \"person\") and returns "
                    + "matching icon names and available styles (baseline, "
                    + "outline, round, sharp, twotone) ready for "
                    + "import_builtin_icon or set_app_icon.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("query", stringSchema(
                    "Search keyword(s), e.g. \"search\", \"back arrow\", "
                            + "\"shopping cart\", \"notification\""));
            properties.add("style", stringSchema(
                    "Optional Material style filter: \"baseline\" (filled), "
                            + "\"outline\", \"round\", \"sharp\", or "
                            + "\"twotone\""));
            properties.add("limit", integerSchema(
                    "Maximum results to return (1-50, default 20)"));
            return AgentTools.parameters(properties, "query");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Searching built-in icons for \""
                    + stringArg(args, "query") + "\"";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String query = stringArg(args, "query");
            String style = stringArg(args, "style");
            int limit = intArg(args, "limit");
            return AgentAssets.searchBuiltinIcons(
                    session.context, query, style, limit <= 0 ? 20 : limit);
        }
    }

    /** import_builtin_icon: import one or more tinted Material vector icons. */
    private static final class ImportBuiltinIcon implements Tool {
        @Override
        public String name() {
            return "import_builtin_icon";
        }

        @Override
        public String description() {
            return "Imports one or more Material icons from Sketchware "
                    + "Pro's built-in icon pack as tinted Android VectorDrawable "
                    + "XML assets in the working project's Image Manager, "
                    + "ready for set_image, set_background_image, or set_fab. "
                    + "Pass either a single {icon, res_name, style?, color?} "
                    + "or a batch \"icons\" array of {icon, res_name, style?, "
                    + "color?} objects.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("icon", stringSchema(
                    "Built-in icon name from search_builtin_icons (e.g. "
                            + "\"search\", \"arrow_back\", \"favorite\")"));
            properties.add("res_name", stringSchema(
                    "Project drawable resource name in lowercase snake_case "
                            + "(e.g. \"ic_search\", \"ic_back\")"));
            properties.add("style", stringSchema(
                    "Icon style: \"round\" (default), \"outline\", "
                            + "\"baseline\" (filled), \"sharp\", or "
                            + "\"twotone\""));
            properties.add("color", stringSchema(
                    "Hex tint colour, e.g. \"#1E293B\" or \"#FFFFFF\" "
                            + "(default \"#000000\")"));
            JsonObject batchSchema = new JsonObject();
            batchSchema.addProperty("type", "array");
            batchSchema.addProperty("description",
                    "Optional batch list of {icon, res_name, style?, color?} "
                            + "icons to import in one approval step");
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("type", "object");
            JsonObject itemProps = new JsonObject();
            itemProps.add("icon", stringSchema("Built-in icon name"));
            itemProps.add("res_name", stringSchema("Drawable resource name"));
            itemProps.add("style", stringSchema("Style variant"));
            itemProps.add("color", stringSchema("Hex tint colour"));
            itemObj.add("properties", itemProps);
            batchSchema.add("items", itemObj);
            properties.add("icons", batchSchema);
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String single = stringArg(args, "res_name");
            if (!single.isEmpty()) {
                return "Importing icon " + single;
            }
            return "Importing built-in Material icons";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            List<AgentAssets.IconImportSpec> specs = parseSpecs(args);
            JsonArray cleanArr = new JsonArray();
            StringBuilder details = new StringBuilder();
            List<String> names = new ArrayList<>();
            for (AgentAssets.IconImportSpec spec : specs) {
                JsonObject item = new JsonObject();
                item.addProperty("icon", spec.iconName);
                item.addProperty("res_name", spec.resName);
                item.addProperty("style", spec.style);
                item.addProperty("color", spec.colorHex);
                cleanArr.add(item);
                names.add(spec.resName);
                if (details.length() > 0) {
                    details.append('\n');
                }
                details.append(spec.resName)
                        .append(" ← ")
                        .append(spec.iconName)
                        .append(" (")
                        .append(spec.style)
                        .append(", ")
                        .append(spec.colorHex)
                        .append(')');
            }
            JsonObject clean = new JsonObject();
            clean.add("icons", cleanArr);
            String title = specs.size() == 1
                    ? "Import icon " + specs.get(0).resName
                    : "Import " + specs.size() + " Material icons";
            String summary = specs.size() == 1
                    ? "Add vector icon “" + specs.get(0).resName
                            + "” (" + specs.get(0).iconName + ", "
                            + specs.get(0).style + " in "
                            + specs.get(0).colorHex + ") to the project."
                    : "Add " + specs.size() + " vector icons to the project: "
                            + String.join(", ", names) + ".";
            return new AgentProposal(name(), clean, scId, title, summary,
                    details.toString());
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            List<AgentAssets.IconImportSpec> specs =
                    parseSpecs(proposal.args);
            JsonArray imported = AgentAssets.importBuiltinIcons(
                    session.context, scId, specs, true);
            JsonObject out = new JsonObject();
            out.addProperty("count", imported.size());
            out.add("imported_icons", imported);
            return out;
        }

        private static List<AgentAssets.IconImportSpec> parseSpecs(
                JsonObject args) throws ToolException {
            List<AgentAssets.IconImportSpec> specs = new ArrayList<>();
            JsonArray arr = jsonArrayArg(args, "icons");
            if (arr != null && arr.size() > 0) {
                if (arr.size() > 25) {
                    throw new ToolException("E_TOO_MANY_ICONS",
                            "Import at most 25 icons per call.");
                }
                for (JsonElement el : arr) {
                    if (el == null || !el.isJsonObject()) {
                        throw new ToolException("E_BAD_ICON_SPEC",
                                "Each entry in \"icons\" must be an object "
                                        + "{icon, res_name, style?, color?}.");
                    }
                    specs.add(oneSpec(el.getAsJsonObject()));
                }
            } else {
                specs.add(oneSpec(args));
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (AgentAssets.IconImportSpec s : specs) {
                if (!seen.add(s.resName)) {
                    throw new ToolException("E_DUPLICATE_NAME",
                            "Duplicate drawable name \"" + s.resName
                                    + "\" in the same import batch.");
                }
            }
            return specs;
        }

        private static AgentAssets.IconImportSpec oneSpec(JsonObject obj)
                throws ToolException {
            String icon = stringArg(obj, "icon");
            if (icon.isEmpty()) {
                icon = stringArg(obj, "icon_name");
            }
            if (icon.isEmpty()) {
                throw new ToolException("E_BAD_ICON_NAME",
                        "Provide \"icon\" (built-in Material icon name, "
                                + "e.g. \"search\" or \"arrow_back\").");
            }
            String style = AgentAssets.normalizeIconStyle(
                    stringArg(obj, "style"));
            String rawRes = stringArg(obj, "res_name");
            String resName = rawRes.isEmpty()
                    ? AgentAssets.defaultIconResName(icon, style)
                    : AgentAssets.validateResName(rawRes);
            String color = AgentAssets.normalizeHexColor(
                    stringArg(obj, "color"), "#000000");
            return new AgentAssets.IconImportSpec(
                    icon.toLowerCase(Locale.ROOT).trim(),
                    resName,
                    style,
                    color);
        }
    }

    /** import_chat_image: save a user-attached chat image as a project drawable. */
    private static final class ImportChatImage implements Tool {
        @Override
        public String name() {
            return "import_chat_image";
        }

        @Override
        public String description() {
            return "Imports an image the user attached in chat into the "
                    + "working project's Image Manager as a PNG drawable "
                    + "(for banners, avatars, logos, illustrations, or "
                    + "backgrounds via set_image / set_background_image). "
                    + "Pass attachment_id from the message's [Attached "
                    + "images: id=att_...] note, or \"latest\" for the "
                    + "most recently attached image.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("attachment_id", stringSchema(
                    "Attachment ID from [Attached images: id=att_...], "
                            + "or \"latest\" for the newest attached image"));
            properties.add("res_name", stringSchema(
                    "Project drawable resource name in lowercase snake_case "
                            + "(e.g. \"img_banner\", \"logo_brand\")"));
            properties.add("max_dimension", integerSchema(
                    "Optional max pixel width/height (64-2048, default 1024)"));
            return AgentTools.parameters(
                    properties, "attachment_id", "res_name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Importing chat image as " + stringArg(args, "res_name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String resName = AgentAssets.validateResName(
                    stringArg(args, "res_name"));
            String resolvedId = resolveAttachmentId(
                    scId, stringArg(args, "attachment_id"));
            int maxDim = intArg(args, "max_dimension");
            if (maxDim <= 0) {
                maxDim = 1024;
            }
            maxDim = Math.max(64, Math.min(2048, maxDim));

            JsonObject clean = new JsonObject();
            clean.addProperty("attachment_id", resolvedId);
            clean.addProperty("res_name", resName);
            clean.addProperty("max_dimension", maxDim);

            return new AgentProposal(name(), clean, scId,
                    "Save chat image as " + resName,
                    "Import attached image (" + resolvedId
                            + ") into the project's Image Manager as “"
                            + resName + "”.",
                    "Attachment: " + resolvedId
                            + "\nDrawable name: " + resName
                            + "\nMax dimension: " + maxDim + "px");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            return AgentAssets.importChatImage(
                    session.context,
                    scId,
                    stringArg(proposal.args, "attachment_id"),
                    stringArg(proposal.args, "res_name"),
                    intArg(proposal.args, "max_dimension"),
                    true);
        }
    }

    /** manage_drawable: create/update/delete custom XML drawables & images. */
    private static final class ManageDrawable implements Tool {
        @Override
        public String name() {
            return "manage_drawable";
        }

        @Override
        public String description() {
            return "Creates, updates, or deletes custom Android XML "
                    + "drawables (<shape>, <selector>, <ripple>, "
                    + "<layer-list>, <vector>) in res/drawable/*.xml, or "
                    + "deletes an unused image from the Image Manager. "
                    + "Use action=\"upsert\" with {name, xml} (or a batch "
                    + "\"drawables\" array of {name, xml}) to craft custom "
                    + "pills, gradients, outlined inputs, ripples, or "
                    + "badges, then apply them via edit_screen "
                    + "set_background_image or set_image.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("action", stringSchema(
                    "\"upsert\" (default: create or update XML drawable) "
                            + "or \"delete\" (delete custom drawable or "
                            + "project image)"));
            properties.add("name", stringSchema(
                    "Drawable name in lowercase snake_case without .xml "
                            + "(e.g. \"bg_search_pill\", \"bg_hero_gradient\")"));
            properties.add("xml", stringSchema(
                    "Full Android drawable XML (<shape>, <ripple>, "
                            + "<selector>, <layer-list>, or <vector>) when "
                            + "action is \"upsert\""));
            JsonObject batchSchema = new JsonObject();
            batchSchema.addProperty("type", "array");
            batchSchema.addProperty("description",
                    "Optional batch list of {name, xml} drawables to upsert "
                            + "in one approval step");
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("type", "object");
            JsonObject itemProps = new JsonObject();
            itemProps.add("name", stringSchema("Drawable name"));
            itemProps.add("xml", stringSchema("Drawable XML"));
            itemObj.add("properties", itemProps);
            batchSchema.add("items", itemObj);
            properties.add("drawables", batchSchema);
            properties.add("force", booleanSchema(
                    "When action is \"delete\", true to delete even if "
                            + "referenced by a widget (default false)"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String action = stringArg(args, "action");
            String dName = stringArg(args, "name");
            if ("delete".equalsIgnoreCase(action)) {
                return "Deleting drawable " + dName;
            }
            return dName.isEmpty()
                    ? "Saving custom XML drawables"
                    : "Saving custom drawable " + dName;
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String action = stringArg(args, "action").toLowerCase(Locale.ROOT);
            if (action.isEmpty()) {
                action = "upsert";
            }
            if ("delete".equals(action) || "remove".equals(action)) {
                String dName = AgentAssets.validateResName(
                        stringArg(args, "name"));
                boolean force = booleanArg(args, "force");
                List<String> refs =
                        AgentAssets.findDrawableReferences(scId, dName);
                if (!refs.isEmpty() && !force) {
                    throw new ToolException("E_IN_USE",
                            "Drawable \"" + dName + "\" is still used on "
                                    + String.join(", ", refs)
                                    + ". Update those widgets first or pass "
                                    + "force=true.");
                }
                SketchwareProjects.Resources res =
                        SketchwareProjects.resources(scId);
                if (!res.images.contains(dName)
                        && !res.customDrawables.contains(dName)) {
                    throw new ToolException("E_NOT_FOUND",
                            "No custom drawable or project image named \""
                                    + dName + "\" exists.");
                }
                JsonObject clean = new JsonObject();
                clean.addProperty("action", "delete");
                clean.addProperty("name", dName);
                clean.addProperty("force", force);
                return new AgentProposal(name(), clean, scId,
                        "Delete drawable " + dName,
                        "Remove drawable “" + dName + "” from the project.",
                        "Action: delete\nDrawable: " + dName);
            }
            if (!"upsert".equals(action) && !"create".equals(action)
                    && !"save".equals(action)) {
                throw new ToolException("E_BAD_ACTION",
                        "Invalid action \"" + action
                                + "\". Use \"upsert\" or \"delete\".");
            }

            List<AgentAssets.DrawableSpec> specs = parseDrawableSpecs(args);
            JsonArray cleanArr = new JsonArray();
            List<String> names = new ArrayList<>();
            StringBuilder details = new StringBuilder();
            for (AgentAssets.DrawableSpec spec : specs) {
                if (SketchwareProjects.resources(scId).images
                        .contains(spec.name)) {
                    throw new ToolException("E_NAME_COLLISION",
                            "An image named \"" + spec.name
                                    + "\" already exists in the Image "
                                    + "Manager. Pick a distinct name or "
                                    + "delete it first.");
                }
                JsonObject item = new JsonObject();
                item.addProperty("name", spec.name);
                item.addProperty("xml", spec.xml);
                cleanArr.add(item);
                names.add(spec.name);
                if (details.length() > 0) {
                    details.append("\n\n");
                }
                details.append("--- res/drawable/")
                        .append(spec.name)
                        .append(".xml ---\n")
                        .append(spec.xml);
            }
            JsonObject clean = new JsonObject();
            clean.addProperty("action", "upsert");
            clean.add("drawables", cleanArr);
            String title = specs.size() == 1
                    ? "Save drawable " + specs.get(0).name
                    : "Save " + specs.size() + " custom XML drawables";
            String summary = specs.size() == 1
                    ? "Save custom XML drawable “" + specs.get(0).name
                            + "” in res/drawable."
                    : "Save " + specs.size()
                            + " custom XML drawables in res/drawable: "
                            + String.join(", ", names) + ".";
            return new AgentProposal(name(), clean, scId, title, summary,
                    details.toString());
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String action = stringArg(proposal.args, "action");
            if ("delete".equalsIgnoreCase(action)) {
                return AgentAssets.deleteDrawableOrImage(
                        scId,
                        stringArg(proposal.args, "name"),
                        booleanArg(proposal.args, "force"));
            }
            List<AgentAssets.DrawableSpec> specs =
                    parseDrawableSpecs(proposal.args);
            JsonArray saved = AgentAssets.upsertCustomDrawables(scId, specs);
            JsonObject out = new JsonObject();
            out.addProperty("count", saved.size());
            out.add("saved_drawables", saved);
            return out;
        }

        private static List<AgentAssets.DrawableSpec> parseDrawableSpecs(
                JsonObject args) throws ToolException {
            List<AgentAssets.DrawableSpec> specs = new ArrayList<>();
            JsonArray arr = jsonArrayArg(args, "drawables");
            if (arr != null && arr.size() > 0) {
                if (arr.size() > 20) {
                    throw new ToolException("E_TOO_MANY_DRAWABLES",
                            "Save at most 20 custom drawables per call.");
                }
                for (JsonElement el : arr) {
                    if (el == null || !el.isJsonObject()) {
                        throw new ToolException("E_BAD_DRAWABLE",
                                "Each entry in \"drawables\" must be {name, xml}.");
                    }
                    JsonObject obj = el.getAsJsonObject();
                    specs.add(AgentAssets.validateDrawableXml(
                            stringArg(obj, "name"),
                            stringArg(obj, "xml")));
                }
            } else {
                specs.add(AgentAssets.validateDrawableXml(
                        stringArg(args, "name"),
                        stringArg(args, "xml")));
            }
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (AgentAssets.DrawableSpec s : specs) {
                if (!seen.add(s.name)) {
                    throw new ToolException("E_DUPLICATE_NAME",
                            "Duplicate drawable name \"" + s.name + "\".");
                }
            }
            return specs;
        }
    }

    /** set_app_icon: generate legacy + adaptive + monochrome launcher icons. */
    private static final class SetAppIcon implements Tool {
        @Override
        public String name() {
            return "set_app_icon";
        }

        @Override
        public String description() {
            return "Sets the working project's app launcher icon across "
                    + "all 5 Android mipmap densities (mdpi..xxxhdpi), "
                    + "generating both legacy rounded-square icons AND "
                    + "Android 8+ Adaptive + Android 13+ Monochrome themed "
                    + "icons. Supports three sources: \"builtin_icon\" "
                    + "(any Material icon from search_builtin_icons), "
                    + "\"chat_image\" (a user-attached image or \"latest\"), "
                    + "or \"monogram\" (1-3 crisp bold letters/initials), "
                    + "on a solid or gradient background.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("source", stringSchema(
                    "\"builtin_icon\", \"chat_image\", or \"monogram\""));
            properties.add("builtin_icon", stringSchema(
                    "Material icon name when source is \"builtin_icon\" "
                            + "(e.g. \"edit_note\", \"bolt\", \"check_circle\")"));
            properties.add("icon_style", stringSchema(
                    "Material icon style: \"round\" (default), \"baseline\", "
                            + "\"outline\", \"sharp\", or \"twotone\""));
            properties.add("attachment_id", stringSchema(
                    "Attachment ID (or \"latest\") when source is "
                            + "\"chat_image\""));
            properties.add("monogram_text", stringSchema(
                    "1-3 character monogram/initials when source is "
                            + "\"monogram\" (e.g. \"MN\", \"S+\")"));
            properties.add("foreground_color", stringSchema(
                    "Hex colour for the foreground icon or monogram "
                            + "(default \"#FFFFFF\")"));
            properties.add("background_color", stringSchema(
                    "Hex background colour (or gradient start colour), "
                            + "e.g. \"#4F46E5\""));
            properties.add("background_end_color", stringSchema(
                    "Optional hex gradient end colour for a smooth two-stop "
                            + "launcher background, e.g. \"#7C3AED\""));
            properties.add("gradient_angle", integerSchema(
                    "Gradient angle in degrees: 0 (left→right), 45, 90 "
                            + "(top→bottom), or 135 (top-left→bottom-right, "
                            + "default)"));
            properties.add("adaptive", booleanSchema(
                    "True (default) to generate Android 8+ adaptive "
                            + "foreground/background + Android 13+ monochrome "
                            + "layers alongside legacy mipmaps"));
            properties.add("scale", numberSchema(
                    "Foreground glyph scale inside the icon (0.40 to 1.0, "
                            + "default 0.62 for icons/monograms or 0.85 "
                            + "for chat images)"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Designing the app's launcher icon";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String rawSource = stringArg(args, "source").toLowerCase(Locale.ROOT);
            String builtinIcon = stringArg(args, "builtin_icon");
            if (builtinIcon.isEmpty()) {
                builtinIcon = stringArg(args, "icon");
            }
            String iconStyle = stringArg(args, "icon_style").isEmpty()
                    ? "round"
                    : AgentAssets.normalizeIconStyle(
                            stringArg(args, "icon_style"));
            String attachmentId = stringArg(args, "attachment_id");
            String monogram = stringArg(args, "monogram_text");
            if (monogram.isEmpty()) {
                monogram = stringArg(args, "text");
            }

            String source = switch (rawSource) {
                case "builtin_icon", "builtin", "icon", "material",
                        "material_icon" -> "builtin_icon";
                case "chat_image", "image", "attachment", "chat", "photo" ->
                        "chat_image";
                case "monogram", "text", "initials", "letters" -> "monogram";
                case "" -> !attachmentId.isEmpty() ? "chat_image"
                        : (!builtinIcon.isEmpty() ? "builtin_icon"
                                : (!monogram.isEmpty() ? "monogram" : ""));
                default -> rawSource;
            };

            String fg = stringArg(args, "foreground_color");
            if (fg.isEmpty()) {
                fg = "#FFFFFF";
            }
            String bg = stringArg(args, "background_color");
            if (bg.isEmpty()) {
                bg = "#4F46E5";
            }
            String bgEnd = stringArg(args, "background_end_color");
            int angle = intArg(args, "gradient_angle");
            if (angle < 0) {
                angle = 135;
            }
            boolean adaptive = !args.has("adaptive")
                    || args.get("adaptive").isJsonNull()
                    || booleanArg(args, "adaptive");
            float scale = floatArg(args, "scale",
                    "chat_image".equals(source) ? 0.85f : 0.62f);

            String sourceSummary;
            switch (source) {
                case "builtin_icon" -> {
                    if (builtinIcon.isEmpty()) {
                        throw new ToolException("E_BAD_ICON_SOURCE",
                                "Pass \"builtin_icon\" (e.g. \"bolt\") when "
                                        + "source is \"builtin_icon\".");
                    }
                    sourceSummary = "Material icon “" + builtinIcon + "” ("
                            + iconStyle + ", " + fg + ")";
                }
                case "chat_image" -> {
                    attachmentId = resolveAttachmentId(scId, attachmentId);
                    sourceSummary = "chat image (" + attachmentId + ")";
                }
                case "monogram" -> {
                    if (monogram.isEmpty()) {
                        throw new ToolException("E_BAD_ICON_SOURCE",
                                "Pass 1-3 characters in \"monogram_text\" "
                                        + "when source is \"monogram\".");
                    }
                    if (monogram.length() > 3) {
                        monogram = monogram.substring(0, 3);
                    }
                    sourceSummary = "monogram “" + monogram + "” (" + fg + ")";
                }
                default -> throw new ToolException("E_BAD_ICON_SOURCE",
                        "Invalid source \"" + source
                                + "\". Use \"builtin_icon\", \"chat_image\", "
                                + "or \"monogram\".");
            }

            String bgSummary = bgEnd.isEmpty()
                    ? bg
                    : bg + " → " + bgEnd + " gradient";
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);

            JsonObject clean = new JsonObject();
            clean.addProperty("source", source);
            clean.addProperty("builtin_icon", builtinIcon);
            clean.addProperty("icon_style", iconStyle);
            clean.addProperty("attachment_id", attachmentId);
            clean.addProperty("monogram_text", monogram);
            clean.addProperty("foreground_color", fg);
            clean.addProperty("background_color", bg);
            clean.addProperty("background_end_color", bgEnd);
            clean.addProperty("gradient_angle", angle);
            clean.addProperty("adaptive", adaptive);
            clean.addProperty("scale", scale);

            return new AgentProposal(name(), clean, scId,
                    "Set launcher icon for " + project.name,
                    "Generate " + (adaptive ? "adaptive + monochrome + legacy"
                            : "standard") + " launcher icons for "
                            + project.name + " using " + sourceSummary
                            + " on " + bgSummary + ".",
                    "Source: " + sourceSummary
                            + "\nBackground: " + bgSummary
                            + "\nAdaptive + Monochrome (mdpi..xxxhdpi): "
                            + adaptive);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            String source = stringArg(a, "source");
            AgentAssets.AppIconSpec spec = new AgentAssets.AppIconSpec();
            if ("chat_image".equalsIgnoreCase(source)) {
                spec.attachmentId = stringArg(a, "attachment_id");
                spec.fullBleed = floatArg(a, "scale", 0.85f) >= 0.96f;
            } else if ("builtin_icon".equalsIgnoreCase(source)) {
                spec.builtinIcon = stringArg(a, "builtin_icon");
                spec.builtinStyle = stringArg(a, "icon_style");
            } else {
                spec.monogramText = stringArg(a, "monogram_text");
            }
            spec.foregroundColorHex = AgentAssets.normalizeHexColor(
                    stringArg(a, "foreground_color"), "#FFFFFF");
            spec.backgroundColorHex = AgentAssets.normalizeHexColor(
                    stringArg(a, "background_color"), "#4F46E5");
            String bgEnd = stringArg(a, "background_end_color");
            if (!bgEnd.isEmpty()) {
                spec.gradientStartHex = spec.backgroundColorHex;
                spec.gradientEndHex = AgentAssets.normalizeHexColor(
                        bgEnd, "#7C3AED");
                int angle = intArg(a, "gradient_angle");
                spec.gradientDirection = switch (angle) {
                    case 0 -> "L_R";
                    case 45 -> "BL_TR";
                    case 90 -> "T_B";
                    default -> "TL_BR";
                };
            }
            spec.adaptive = !a.has("adaptive") || booleanArg(a, "adaptive");
            return AgentAssets.setAppIcon(session.context, scId, spec);
        }
    }

    /** preview_screen: offscreen ViewPane visual render + dp bounds audit. */
    private static final class PreviewScreen implements Tool {
        @Override
        public String name() {
            return "preview_screen";
        }

        @Override
        public String description() {
            return "Renders an Activity screen or Custom View offscreen "
                    + "using Sketchware Pro's real ViewPane, returning "
                    + "measured dp coordinates/sizes for every widget, "
                    + "layout warnings (0dp collapsed views, right/bottom "
                    + "overflow without a ScrollView, truncated text, "
                    + "wrapped buttons), AND attaching the rendered PNG "
                    + "image so you can visually inspect alignment, "
                    + "contrast, hierarchy, and fidelity against any "
                    + "user-attached screenshot before building.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("screen", stringSchema(
                    "Activity or Custom View name (e.g. \"main\", "
                            + "\"MainActivity\", or \"row_note\")"));
            properties.add("width_dp", integerSchema(
                    "Viewport width in dp (240-600, default 360)"));
            properties.add("height_dp", integerSchema(
                    "Viewport height in dp (200-1400, default 800 for "
                            + "activities or 320 for custom views)"));
            properties.add("include_image", booleanSchema(
                    "True (default) to attach the rendered PNG to your "
                            + "next vision turn for visual inspection"));
            return AgentTools.parameters(properties, "screen");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Previewing screen " + stringArg(args, "screen");
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String screen = stringArg(args, "screen");
            if (screen.isEmpty()) {
                screen = "main";
            }
            int widthDp = intArg(args, "width_dp");
            int heightDp = intArg(args, "height_dp");
            boolean includeImage = !args.has("include_image")
                    || args.get("include_image").isJsonNull()
                    || booleanArg(args, "include_image");
            return AgentPreview.renderScreen(
                    session.context, scId, screen,
                    widthDp, heightDp, includeImage);
        }
    }

    private static String resolveAttachmentId(String scId, String rawId)
            throws ToolException {
        String wanted = rawId == null ? "" : rawId.trim();
        if (wanted.isEmpty() || "latest".equalsIgnoreCase(wanted)
                || "last".equalsIgnoreCase(wanted)) {
            List<ChatMessage> log = AgentChatLog.load(scId);
            for (int i = log.size() - 1; i >= 0; i--) {
                ChatMessage m = log.get(i);
                if (m != null && m.hasAttachments()) {
                    ChatMessage.Attachment last =
                            m.attachments.get(m.attachments.size() - 1);
                    if (last != null && last.path != null
                            && new java.io.File(last.path).isFile()) {
                        return last.id;
                    }
                }
            }
            throw new ToolException("E_NO_ATTACHMENT",
                    "No attached image was found in this conversation. "
                            + "Ask the user to attach an image with the "
                            + "image button first.");
        }
        java.io.File found = AgentMedia.findAttachmentFile(scId, wanted);
        if (found == null || !found.isFile()) {
            throw new ToolException("E_NO_ATTACHMENT",
                    "Could not find chat image attachment \"" + wanted
                            + "\". Pass an attachment ID from [Attached "
                            + "images: id=att_...] or \"latest\".");
        }
        return wanted;
    }

    private static JsonObject integerSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject numberSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "number");
        schema.addProperty("description", description);
        return schema;
    }

    private static float floatArg(JsonObject args, String key, float fallback) {
        try {
            if (args != null && args.has(key) && !args.get(key).isJsonNull()) {
                return args.get(key).getAsFloat();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static JsonArray namesJsonAll(List<String> values) {
        JsonArray arr = new JsonArray();
        if (values != null) {
            for (String v : values) {
                arr.add(v);
            }
        }
        return arr;
    }

    private static String inferFragmentKindOrEmpty(String askedName,
                                                   String explicitKind)
            throws ToolException {
        if (explicitKind != null && !explicitKind.trim().isEmpty()) {
            String k = explicitKind.trim().toLowerCase(Locale.ROOT)
                    .replace("-", "_").replace(" ", "_");
            return switch (k) {
                case "activity", "screen" -> "";
                case "fragment", "page_fragment" -> "fragment";
                case "dialog_fragment", "dialogfragment", "dialog" ->
                        "dialog_fragment";
                case "bottom_sheet_fragment", "bottom_sheet_dialog_fragment",
                        "bottomdialog_fragment", "bottomsheet",
                        "bottom_sheet" -> "bottom_sheet_fragment";
                default -> throw new ToolException("E_BAD_KIND",
                        "Unknown screen kind \"" + explicitKind
                                + "\". Use activity, fragment, "
                                + "dialog_fragment, or "
                                + "bottom_sheet_fragment.");
            };
        }
        if (askedName == null) {
            return "";
        }
        String lower = askedName.trim().toLowerCase(Locale.ROOT);
        if (lower.endsWith("bottomsheetdialogfragment")
                || lower.endsWith("bottomsheetfragment")
                || lower.endsWith("_bottomdialog_fragment")
                || lower.endsWith("_bottom_sheet_fragment")) {
            return "bottom_sheet_fragment";
        }
        if (lower.endsWith("dialogfragment")
                || lower.endsWith("_dialog_fragment")) {
            return "dialog_fragment";
        }
        if (lower.endsWith("fragment") || lower.endsWith("_fragment")) {
            return "fragment";
        }
        return "";
    }

    /**
     * create_fragment: adds a Fragment, DialogFragment, or
     * BottomSheetDialogFragment to the working project.
     */
    private static final class CreateFragment implements Tool {
        private static final Pattern BASE_PATTERN =
                Pattern.compile("[a-z][a-z0-9_]{0,34}");

        @Override
        public String name() {
            return "create_fragment";
        }

        @Override
        public String description() {
            return "Proposes adding a Fragment, DialogFragment, or "
                    + "BottomSheetDialogFragment to the working project. "
                    + "Regular fragments (kind=\"fragment\") are displayed "
                    + "via FragmentAdapter + ViewPager (return_fragment) or "
                    + "replace_fragment inside a container; dialog fragments "
                    + "(kind=\"dialog_fragment\" or \"bottom_sheet_fragment\") "
                    + "are displayed with show_dialog_fragment and closed "
                    + "with dismiss_dialog_fragment. BottomSheetDialogFragment "
                    + "automatically switches AppCompat/Material on.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("name", stringSchema(
                    "Fragment name, e.g. HomeFragment, ConfirmDialog, or "
                            + "FilterBottomSheet"));
            properties.add("kind", stringSchema(
                    "One of: fragment (default), dialog_fragment, or "
                            + "bottom_sheet_fragment"));
            JsonObject fab = new JsonObject();
            fab.addProperty("type", "boolean");
            fab.addProperty("description",
                    "Optional floating action button on this fragment "
                            + "(default false)");
            properties.add("floating_button", fab);
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String n = stringArg(args, "name");
            return n.isEmpty() ? "Wants to add a fragment"
                    : "Wants to add fragment " + n;
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "name").trim();
            if (asked.isEmpty()) {
                asked = stringArg(args, "activity").trim();
            }
            if (asked.isEmpty()) {
                throw new ToolException("E_BAD_NAME",
                        "Pass \"name\" for the fragment, e.g. HomeFragment.");
            }
            String explicitKind = stringArg(args, "kind");
            String kind = inferFragmentKindOrEmpty(asked,
                    explicitKind.isEmpty() ? null : explicitKind);
            if (kind.isEmpty()) {
                kind = "fragment";
            }
            if ("fragment".equals(kind)) {
                requireDesign(scId, asked, "screen", false, false, true);
            }
            String base = normalizeFragmentBase(asked);
            if (!BASE_PATTERN.matcher(base).matches()) {
                throw new ToolException("E_BAD_NAME",
                        "Fragment base name must start with a letter and use "
                                + "letters, numbers and underscores, e.g. "
                                + "HomeFragment or filter_sheet.");
            }
            String suffix = switch (kind) {
                case "dialog_fragment" -> "_dialog_fragment";
                case "bottom_sheet_fragment" -> "_bottomdialog_fragment";
                default -> "_fragment";
            };
            String fileName = base + suffix;
            if (SketchwareProjects.findActivity(scId, fileName) != null
                    || SketchwareProjects.findActivity(scId, asked) != null) {
                throw new ToolException("E_ACTIVITY_EXISTS",
                        "The project already has a fragment or screen named "
                                + asked + ".");
            }
            boolean fab = args != null && args.has("floating_button")
                    && !args.get("floating_button").isJsonNull()
                    && args.get("floating_button").getAsBoolean();
            int options = fab ? ProjectFileBean.OPTION_ACTIVITY_FAB : 0;
            ProjectFileBean previewBean = new ProjectFileBean(
                    ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY, fileName,
                    ProjectFileBean.ORIENTATION_PORTRAIT, 0, options);
            String className = SketchwareProjects.activityName(previewBean);
            String labelKind = switch (kind) {
                case "dialog_fragment" -> "DialogFragment";
                case "bottom_sheet_fragment" -> "BottomSheetDialogFragment";
                default -> "Fragment";
            };
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("file_name", fileName);
            clean.addProperty("kind", kind);
            clean.addProperty("options", options);
            return new AgentProposal(name(), clean, scId,
                    "Add " + labelKind + " to " + project.name,
                    "New " + labelKind + ": " + className + " (layout "
                            + fileName + ".xml"
                            + (fab ? ", with floating button" : "") + ")",
                    "Creates " + className
                            + ("bottom_sheet_fragment".equals(kind)
                                    ? " and switches AppCompat/Material on."
                                    : ".")
                            + " Edit its layout with edit_screen and wire its "
                            + "events with set_event_handler.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String fileName = stringArg(proposal.args, "file_name");
            String kind = stringArg(proposal.args, "kind");
            int options = proposal.args != null
                    && proposal.args.has("options")
                    ? proposal.args.get("options").getAsInt() : 0;
            if (SketchwareProjects.findActivity(scId, fileName) != null) {
                throw new ToolException("E_ACTIVITY_EXISTS",
                        "That fragment already exists now, so it was not "
                                + "added again.");
            }
            try {
                SketchwareActions.createScreen(scId, fileName, options,
                        ProjectFileBean.ORIENTATION_PORTRAIT,
                        "bottom_sheet_fragment".equals(kind));
                ProjectFileBean created =
                        SketchwareProjects.findActivity(scId, fileName);
                JsonObject result = new JsonObject();
                result.addProperty("created_fragment",
                        SketchwareProjects.activityName(created));
                result.addProperty("file_name", fileName);
                result.addProperty("kind", kind);
                result.addProperty("verified",
                        "The fragment now exists in the project and can be "
                                + "edited with edit_screen and wired with "
                                + "set_event_handler.");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_CREATE_FAILED",
                        "Adding the fragment failed: "
                                + String.valueOf(e.getMessage()));
            }
        }

        private static String normalizeFragmentBase(String raw) {
            String s = raw == null ? "" : raw.trim();
            if (s.endsWith(".xml")) {
                s = s.substring(0, s.length() - ".xml".length());
            }
            if (s.endsWith(".java")) {
                s = s.substring(0, s.length() - ".java".length());
            }
            if (s.endsWith("Activity") && s.length() > "Activity".length()) {
                s = s.substring(0, s.length() - "Activity".length());
            }
            StringBuilder snake = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (i > 0 && s.charAt(i - 1) != '_') {
                        snake.append('_');
                    }
                    snake.append(Character.toLowerCase(c));
                } else {
                    snake.append(Character.toLowerCase(c));
                }
            }
            String out = snake.toString().replaceAll("_+", "_");
            String[] suffixes = {
                    "_bottom_sheet_dialog_fragment",
                    "_bottom_sheet_fragment",
                    "_bottomdialog_fragment",
                    "_dialog_fragment",
                    "_fragment"
            };
            for (String suf : suffixes) {
                if (out.endsWith(suf) && out.length() > suf.length()) {
                    out = out.substring(0, out.length() - suf.length());
                    break;
                }
            }
            while (out.startsWith("_")) {
                out = out.substring(1);
            }
            while (out.endsWith("_")) {
                out = out.substring(0, out.length() - 1);
            }
            return out;
        }
    }

    /** delete_custom_view: removes one custom view layout safely. */
    private static final class DeleteCustomView implements Tool {
        @Override
        public String name() {
            return "delete_custom_view";
        }

        @Override
        public String description() {
            return "Proposes deleting one custom view layout from the "
                    + "working project. Refuses if any ListView, RecyclerView, "
                    + "Spinner, GridView or <include> tag still references it "
                    + "unless clear_references is true. Navigation drawer "
                    + "layouts (_drawer_*) are removed via edit_activity "
                    + "(drawer: false) instead.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("name", stringSchema(
                    "The custom view name to delete, e.g. item_task"));
            JsonObject clearRefs = new JsonObject();
            clearRefs.addProperty("type", "boolean");
            clearRefs.addProperty("description",
                    "Optional: clear customView / include references on "
                            + "other screens before deleting (default false)");
            properties.add("clear_references", clearRefs);
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to delete custom view " + stringArg(args, "name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String asked = stringArg(args, "name").trim();
            ProjectFileBean target =
                    SketchwareProjects.findCustomView(scId, asked);
            if (target == null) {
                throw new ToolException("E_CUSTOM_VIEW_NOT_FOUND",
                        "There is no custom view named \"" + asked
                                + "\" in the working project.");
            }
            if (SketchwareProjects.isDrawer(target)) {
                ProjectFileBean host =
                        SketchwareProjects.drawerHostActivity(scId, target);
                throw new ToolException("E_DRAWER_LAYOUT",
                        target.fileName + " is a navigation drawer layout. "
                                + "Turn off drawer: false on "
                                + (host != null
                                        ? SketchwareProjects
                                                .activityName(host)
                                        : "its host activity")
                                + " with edit_activity instead.");
            }
            boolean clearRefs = args != null
                    && args.has("clear_references")
                    && !args.get("clear_references").isJsonNull()
                    && args.get("clear_references").getAsBoolean();
            List<String> refs = SketchwareProjects.customViewReferences(
                    scId, target.fileName);
            if (!refs.isEmpty() && !clearRefs) {
                throw new ToolException("E_CUSTOM_VIEW_IN_USE",
                        "Custom view \"" + target.fileName
                                + "\" is still used by: "
                                + String.join("; ", refs)
                                + ". Update those widgets first or pass "
                                + "clear_references=true.");
            }
            int widgets =
                    SketchwareProjects.screenBeans(scId, target).size();
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("name", target.fileName);
            clean.addProperty("clear_references", clearRefs);
            String summary = "Delete custom view " + target.fileName + " ("
                    + widgets + (widgets == 1 ? " widget" : " widgets") + ")"
                    + (refs.isEmpty() ? "."
                            : " and clear references on: "
                                    + String.join("; ", refs) + ".");
            return new AgentProposal(name(), clean, scId,
                    "Delete custom view from " + project.name,
                    summary,
                    "A safety backup of the project is made first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String fileName = stringArg(proposal.args, "name");
            boolean clearRefs = proposal.args != null
                    && proposal.args.has("clear_references")
                    && proposal.args.get("clear_references").getAsBoolean();
            try {
                List<String> cleared = SketchwareActions.deleteCustomView(
                        scId, fileName, clearRefs);
                JsonObject result = new JsonObject();
                result.addProperty("deleted_custom_view", fileName);
                result.add("cleared_references", namesJsonAll(cleared));
                result.addProperty("verified",
                        "Custom view " + fileName + " has been removed.");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_DELETE_FAILED",
                        "Deleting the custom view failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** remove_component: removes one component from an activity or fragment. */
    private static final class RemoveComponent implements Tool {
        @Override
        public String name() {
            return "remove_component";
        }

        @Override
        public String description() {
            return "Proposes removing one component (Intent, "
                    + "SharedPreferences, Timer, Dialog, FragmentAdapter, "
                    + "etc.) and its component events from an activity or "
                    + "fragment screen. Refuses if blocks outside that "
                    + "component's own events still reference it unless "
                    + "force is true.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The screen owning the component, e.g. MainActivity"));
            properties.add("name", stringSchema(
                    "The component id to remove, e.g. timer1"));
            JsonObject force = new JsonObject();
            force.addProperty("type", "boolean");
            force.addProperty("description",
                    "Optional: remove even if other blocks still name this "
                            + "component (default false)");
            properties.add("force", force);
            return AgentTools.parameters(properties, "activity", "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to remove component " + stringArg(args, "name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String askedScreen = stringArg(args, "activity");
            ProjectFileBean activity =
                    SketchwareProjects.findActivity(scId, askedScreen);
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no activity or fragment named \""
                                + askedScreen + "\".");
            }
            String componentId = stringArg(args, "name").trim();
            if (componentId.isEmpty()) {
                componentId = stringArg(args, "component").trim();
            }
            if (componentId.isEmpty()) {
                componentId = stringArg(args, "id").trim();
            }
            if (componentId.isEmpty()) {
                throw new ToolException("E_BAD_NAME",
                        "Pass \"name\" with the component id to remove.");
            }
            SketchwareProjects.LogicSummary summary =
                    SketchwareProjects.logicSummary(scId, activity);
            if (!summary.components.contains(componentId)) {
                throw new ToolException("E_NO_COMPONENT",
                        SketchwareProjects.activityName(activity)
                                + " has no component named \"" + componentId
                                + "\". Existing components: "
                                + (summary.components.isEmpty() ? "none"
                                        : String.join(", ",
                                                summary.components))
                                + ".");
            }
            boolean force = args != null && args.has("force")
                    && !args.get("force").isJsonNull()
                    && args.get("force").getAsBoolean();
            String use = AgentLogic.componentUse(
                    scId, activity.getJavaName(), componentId);
            if (use != null && !force) {
                throw new ToolException("E_COMPONENT_IN_USE",
                        "Cannot remove component \"" + componentId
                                + "\" on "
                                + SketchwareProjects.activityName(activity)
                                + " because " + use
                                + ". Remove or update that block first, or "
                                + "pass force=true.");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("activity", activity.fileName);
            clean.addProperty("name", componentId);
            clean.addProperty("force", force);
            return new AgentProposal(name(), clean, scId,
                    "Remove component from "
                            + SketchwareProjects.activityName(activity),
                    "Remove component " + componentId + " from "
                            + SketchwareProjects.activityName(activity)
                            + " in " + project.name
                            + (use != null
                                    ? " (warning: " + use + ")" : "."),
                    "Removes the component and any events belonging to it. "
                            + "A safety backup of the project is made first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String actFile = stringArg(proposal.args, "activity");
            String componentId = stringArg(proposal.args, "name");
            ProjectFileBean activity =
                    SketchwareProjects.findActivity(scId, actFile);
            if (activity == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "That screen no longer exists.");
            }
            try {
                int removedEvents = SketchwareActions.removeComponent(
                        scId, activity, componentId);
                JsonObject result = new JsonObject();
                result.addProperty("removed_component", componentId);
                result.addProperty("removed_events", removedEvents);
                result.addProperty("verified",
                        "Component " + componentId + " was removed from "
                                + SketchwareProjects.activityName(activity)
                                + ".");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_REMOVE_FAILED",
                        "Removing the component failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * convert_root_layout: converts a screen's root ViewGroup class and/or
     * root XML attributes via InjectRootLayoutManager.
     */
    private static final class ConvertRootLayout implements Tool {
        @Override
        public String name() {
            return "convert_root_layout";
        }

        @Override
        public String description() {
            return "Proposes converting the root ViewGroup container of an "
                    + "activity, fragment, drawer (_drawer_*), or custom view "
                    + "(e.g. LinearLayout, RelativeLayout, FrameLayout, "
                    + "ConstraintLayout, CoordinatorLayout, ScrollView, "
                    + "NestedScrollView, HorizontalScrollView, "
                    + "MaterialCardView, SwipeRefreshLayout) and optionally "
                    + "setting root XML attributes via Sketchware Pro's "
                    + "InjectRootLayoutManager.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "Target screen, fragment, drawer, or custom view name"));
            properties.add("class_name", stringSchema(
                    "Root ViewGroup class, e.g. LinearLayout, "
                            + "ConstraintLayout, FrameLayout, RelativeLayout, "
                            + "CoordinatorLayout, NestedScrollView"));
            JsonObject attrs = new JsonObject();
            attrs.addProperty("type", "object");
            attrs.addProperty("description",
                    "Optional XML attributes on the root ViewGroup, e.g. "
                            + "{\"android:padding\":\"16dp\","
                            + "\"android:background\":\"#FFFFFF\"}");
            properties.add("attributes", attrs);
            JsonObject replace = new JsonObject();
            replace.addProperty("type", "boolean");
            replace.addProperty("description",
                    "Optional: replace existing root attributes instead of "
                            + "merging (default false)");
            properties.add("replace_attributes", replace);
            return AgentTools.parameters(properties, "activity", "class_name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to convert root layout of "
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
            String askedScreen = stringArg(args, "activity");
            ProjectFileBean screen =
                    SketchwareProjects.findScreen(scId, askedScreen);
            if (screen == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no screen or layout named \""
                                + askedScreen + "\".");
            }
            String askedClass = stringArg(args, "class_name");
            if (askedClass.isEmpty()) {
                askedClass = stringArg(args, "layout_type");
            }
            String resolved =
                    SketchwareActions.resolveRootClassName(askedClass);
            if (resolved == null || resolved.isEmpty()) {
                throw new ToolException("E_BAD_VALUE",
                        "\"" + askedClass + "\" is not a supported root "
                                + "ViewGroup. Use LinearLayout, "
                                + "RelativeLayout, FrameLayout, "
                                + "ConstraintLayout, CoordinatorLayout, "
                                + "ScrollView, NestedScrollView, "
                                + "HorizontalScrollView, MaterialCardView, "
                                + "or SwipeRefreshLayout.");
            }
            JsonObject cleanAttrs = new JsonObject();
            if (args != null && args.has("attributes")
                    && args.get("attributes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry
                        : args.getAsJsonObject("attributes").entrySet()) {
                    String k = entry.getKey() == null ? ""
                            : entry.getKey().trim();
                    if (!k.matches("[A-Za-z_][A-Za-z0-9_.:-]*")
                            || k.startsWith("xmlns")) {
                        throw new ToolException("E_BAD_VALUE",
                                "Invalid root attribute name \"" + k + "\".");
                    }
                    String v = entry.getValue() == null
                            || entry.getValue().isJsonNull() ? ""
                            : entry.getValue().getAsString().trim();
                    if (v.contains("\"") || v.contains("\n")
                            || v.contains("\r")) {
                        throw new ToolException("E_BAD_VALUE",
                                "Invalid root attribute value for " + k + ".");
                    }
                    cleanAttrs.addProperty(k, v);
                }
            }
            boolean replace = args != null
                    && args.has("replace_attributes")
                    && !args.get("replace_attributes").isJsonNull()
                    && args.get("replace_attributes").getAsBoolean();
            String before = SketchwareProjects.rootDescription(scId, screen);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("screen", screen.fileName);
            clean.addProperty("class_name", resolved);
            clean.addProperty("replace_attributes", replace);
            clean.add("attributes", cleanAttrs);
            return new AgentProposal(name(), clean, scId,
                    "Convert root layout of "
                            + SketchwareProjects.screenName(screen),
                    "Root container on "
                            + SketchwareProjects.screenName(screen) + " in "
                            + project.name + ": " + before + " -> "
                            + resolved
                            + (cleanAttrs.size() > 0
                                    ? " (+" + cleanAttrs.size() + " attribute"
                                            + (cleanAttrs.size() == 1
                                                    ? "" : "s") + ")"
                                    : ""),
                    "Updates InjectRootLayoutManager and keeps top-level "
                            + "widget parentType values in sync. A safety "
                            + "backup is made first.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String screenKey = stringArg(proposal.args, "screen");
            ProjectFileBean screen =
                    SketchwareProjects.findScreen(scId, screenKey);
            if (screen == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "That screen no longer exists.");
            }
            String resolved = stringArg(proposal.args, "class_name");
            boolean replace = proposal.args != null
                    && proposal.args.has("replace_attributes")
                    && proposal.args.get("replace_attributes").getAsBoolean();
            Map<String, String> updates = new LinkedHashMap<>();
            if (proposal.args != null && proposal.args.has("attributes")
                    && proposal.args.get("attributes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry
                        : proposal.args.getAsJsonObject("attributes")
                                .entrySet()) {
                    updates.put(entry.getKey(),
                            entry.getValue().getAsString());
                }
            }
            try {
                SketchwareActions.backupProjectOnce(scId);
                SketchwareActions.setRootLayout(
                        scId, screen, resolved, updates, replace);
                SketchwareActions.commitProject(scId);
                JsonObject result = new JsonObject();
                result.addProperty("screen",
                        SketchwareProjects.screenName(screen));
                result.addProperty("root",
                        SketchwareProjects.rootDescription(scId, screen));
                result.addProperty("verified",
                        "Root layout updated to " + resolved + ".");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_ROOT_CONVERT_FAILED",
                        "Converting the root layout failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * import_layout_xml: validates and imports raw Android layout XML into
     * any screen, fragment, drawer, or custom view via ViewBeanParser.
     */
    private static final class ImportLayoutXml implements Tool {
        @Override
        public String name() {
            return "import_layout_xml";
        }

        @Override
        public String description() {
            return "Proposes importing raw Android layout XML into an "
                    + "activity, fragment, drawer (_drawer_*), or custom view "
                    + "using Sketchware Pro's ViewBeanParser, "
                    + "CircularDependencyDetector, and "
                    + "InjectRootLayoutManager. Prefer edit_screen for "
                    + "incremental changes; use import_layout_xml when "
                    + "importing complete Android XML layouts (including "
                    + "ConstraintLayout / CoordinatorLayout / Material3 XML).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "Target screen, fragment, drawer, or custom view name"));
            properties.add("xml", stringSchema(
                    "Complete Android layout XML starting with a ViewGroup "
                            + "root tag"));
            return AgentTools.parameters(properties, "activity", "xml");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to import XML layout into "
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
            String askedScreen = stringArg(args, "activity");
            ProjectFileBean screen =
                    SketchwareProjects.findScreen(scId, askedScreen);
            if (screen == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no screen or layout named \""
                                + askedScreen + "\".");
            }
            String xml = stringArg(args, "xml");
            if (xml.trim().isEmpty()) {
                throw new ToolException("E_BAD_XML",
                        "Pass non-empty Android layout XML in \"xml\".");
            }
            SketchwareActions.ParsedLayoutXml parsed;
            try {
                parsed = SketchwareActions.parseLayoutXml(xml);
            } catch (Exception e) {
                throw new ToolException("E_BAD_XML",
                        "Layout XML failed Sketchware validation: "
                                + String.valueOf(e.getMessage()));
            }
            ArrayList<com.besome.sketch.beans.ViewBean> existing =
                    SketchwareProjects.screenBeans(scId, screen);
            StringBuilder preview = new StringBuilder();
            int shown = Math.min(parsed.beans.size(), 8);
            for (int i = 0; i < shown; i++) {
                com.besome.sketch.beans.ViewBean b = parsed.beans.get(i);
                if (preview.length() > 0) {
                    preview.append(", ");
                }
                preview.append(b.id).append(" (")
                        .append(b.convert != null && !b.convert.isEmpty()
                                ? b.convert : AgentEdits.typeName(b.type))
                        .append(")");
            }
            if (parsed.beans.size() > shown) {
                preview.append(", ...");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("screen", screen.fileName);
            clean.addProperty("revision", AgentEdits.revisionOf(existing));
            clean.addProperty("xml", xml);
            String summary = "Import XML layout into "
                    + SketchwareProjects.screenName(screen) + " in "
                    + project.name + ": root " + parsed.root.getClassName()
                    + ", " + parsed.beans.size()
                    + (parsed.beans.size() == 1 ? " widget" : " widgets")
                    + (existing.isEmpty() ? "."
                            : " (replaces " + existing.size()
                                    + " existing widget"
                                    + (existing.size() == 1 ? "" : "s")
                                    + ").");
            return new AgentProposal(name(), clean, scId,
                    "Import XML layout into "
                            + SketchwareProjects.screenName(screen),
                    summary,
                    "Parsed widgets: "
                            + (preview.length() == 0 ? "empty container"
                                    : preview.toString())
                            + "\n\nXML:\n" + xml);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String screenKey = stringArg(proposal.args, "screen");
            ProjectFileBean screen =
                    SketchwareProjects.findScreen(scId, screenKey);
            if (screen == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "That screen no longer exists.");
            }
            ArrayList<com.besome.sketch.beans.ViewBean> current =
                    SketchwareProjects.screenBeans(scId, screen);
            String expected = stringArg(proposal.args, "revision");
            if (!expected.isEmpty()
                    && !expected.equals(AgentEdits.revisionOf(current))) {
                throw new ToolException("E_SCREEN_CHANGED",
                        "The screen changed since this XML import was "
                                + "proposed. Inspect it again with get_screen "
                                + "before importing.");
            }
            String xml = stringArg(proposal.args, "xml");
            try {
                SketchwareActions.ParsedLayoutXml imported =
                        SketchwareActions.importLayoutXml(scId, screen, xml);
                JsonObject result = new JsonObject();
                result.addProperty("screen",
                        SketchwareProjects.screenName(screen));
                result.addProperty("root", imported.root.getClassName());
                result.addProperty("widgets_imported", imported.beans.size());
                result.addProperty("verified",
                        "Imported " + imported.beans.size()
                                + " widgets into "
                                + SketchwareProjects.screenName(screen)
                                + " and verified read-back.");
                return result;
            } catch (Exception e) {
                throw new ToolException("E_IMPORT_FAILED",
                        "Importing layout XML failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    // =====================================================================
    // Phase 4: Values Resources, Raw res/ XML, Fonts & Sounds
    // =====================================================================

    /**
     * get_values_resources: reads strings.xml, colors.xml, styles.xml,
     * themes.xml, and arrays.xml for base values or any values-<variant>.
     */
    private static final class GetValuesResources implements Tool {
        @Override
        public String name() {
            return "get_values_resources";
        }

        @Override
        public String description() {
            return "Reads the working project's structured values resources "
                    + "(strings.xml, colors.xml, styles.xml, themes.xml, "
                    + "arrays.xml) for the base values folder or a specific "
                    + "variant (e.g. \"night\" for dark theme, \"es\" or "
                    + "\"ar\" for localization), plus lists all existing "
                    + "values variants.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("variant", stringSchema(
                    "Optional values qualifier: \"\" (default), \"night\", "
                            + "\"es\", \"fr\", \"ar\", \"pt-rBR\", etc."));
            properties.add("type", stringSchema(
                    "Optional filter: all (default), strings, colors, "
                            + "styles, themes, or arrays"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String v = stringArg(args, "variant");
            return "Read values"
                    + (v.isEmpty() ? "" : "-" + v.replaceFirst("^-", ""))
                    + " resources";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String suffix = AgentResources.normalizeVariant(
                    stringArg(args, "variant"));
            String filter = stringArg(args, "type").toLowerCase(Locale.ROOT);
            if (filter.isEmpty()) filter = "all";

            JsonObject result = new JsonObject();
            result.addProperty("variant_folder",
                    AgentResources.variantFolderName(suffix));
            result.add("available_variants",
                    namesJsonAll(AgentResources.listValueVariants(scId)));

            if ("all".equals(filter) || "strings".equals(filter)) {
                JsonObject stringsJson = new JsonObject();
                for (Map.Entry<String, AgentResources.StringEntry> e
                        : AgentResources.readStrings(scId, suffix)
                                .entrySet()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("value", e.getValue().value);
                    entry.addProperty("translatable",
                            e.getValue().translatable);
                    stringsJson.add(e.getKey(), entry);
                }
                result.add("strings", stringsJson);
            }
            if ("all".equals(filter) || "colors".equals(filter)) {
                JsonObject colorsJson = new JsonObject();
                for (Map.Entry<String, String> e
                        : AgentResources.readColors(scId, suffix).entrySet()) {
                    colorsJson.addProperty(e.getKey(), e.getValue());
                }
                result.add("colors", colorsJson);
            }
            if ("all".equals(filter) || "styles".equals(filter)) {
                result.add("styles", stylesMapToJson(
                        AgentResources.readStylesFile(scId, suffix, false)));
            }
            if ("all".equals(filter) || "themes".equals(filter)) {
                result.add("themes", stylesMapToJson(
                        AgentResources.readStylesFile(scId, suffix, true)));
            }
            if ("all".equals(filter) || "arrays".equals(filter)) {
                JsonObject arraysJson = new JsonObject();
                for (Map.Entry<String, AgentResources.ArraySpec> e
                        : AgentResources.readArrays(scId, suffix).entrySet()) {
                    JsonObject arrObj = new JsonObject();
                    arrObj.addProperty("type", e.getValue().type);
                    arrObj.add("items", namesJsonAll(e.getValue().items));
                    arraysJson.add(e.getKey(), arrObj);
                }
                result.add("arrays", arraysJson);
            }
            return result;
        }
    }

    private static JsonObject stylesMapToJson(
            Map<String, AgentResources.StyleSpec> styles) {
        JsonObject out = new JsonObject();
        for (AgentResources.StyleSpec s : styles.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("parent", s.parent);
            JsonObject items = new JsonObject();
            for (Map.Entry<String, String> it : s.items.entrySet()) {
                items.addProperty(it.getKey(), it.getValue());
            }
            obj.add("items", items);
            out.add(s.name, obj);
        }
        return out;
    }

    /**
     * edit_values_resource: upserts and/or deletes entries in strings.xml,
     * colors.xml, styles.xml, themes.xml, or arrays.xml for any variant.
     */
    private static final class EditValuesResource implements Tool {
        @Override
        public String name() {
            return "edit_values_resource";
        }

        @Override
        public String description() {
            return "Proposes adding, updating, or deleting entries in "
                    + "strings.xml, colors.xml, styles.xml, themes.xml, or "
                    + "arrays.xml inside values or any values-<variant> "
                    + "(e.g. variant=\"night\" for dark-mode colors/styles, "
                    + "or variant=\"es\" / \"ar\" for localized strings). "
                    + "Widgets can then reference @string/<name> and "
                    + "@color/<name>, and blocks can use {\"res_string\": "
                    + "\"<name>\"} or color \"@color/<name>\".";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("type", stringSchema(
                    "Target values file: strings, colors, styles, themes, "
                            + "or arrays"));
            properties.add("variant", stringSchema(
                    "Optional values qualifier: \"\" (default), \"night\", "
                            + "\"es\", \"fr\", \"ar\", \"pt-rBR\", etc."));
            JsonObject entries = new JsonObject();
            entries.addProperty("type", "object");
            entries.addProperty("description",
                    "Key-value map to add or update. For strings: "
                            + "{\"welcome\":\"Hello\"} (or "
                            + "{\"api_url\":{\"value\":\"...\",\"translatable\":false}}); "
                            + "for colors: {\"surface_card\":\"#1E293B\"}; "
                            + "for styles/themes: "
                            + "{\"CardTitle\":{\"parent\":\"TextAppearance.Material3.TitleMedium\","
                            + "\"items\":{\"android:textColor\":\"@color/brand\"}}}; "
                            + "for arrays: "
                            + "{\"countries\":{\"type\":\"string-array\",\"items\":[\"NG\",\"GH\"]}}.");
            properties.add("entries", entries);
            JsonObject delete = new JsonObject();
            delete.addProperty("type", "array");
            delete.add("items", stringSchema("Resource key name to delete"));
            delete.addProperty("description",
                    "Optional list of entry names to remove");
            properties.add("delete", delete);
            return AgentTools.parameters(properties, "type");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String t = stringArg(args, "type");
            String v = stringArg(args, "variant");
            return "Wants to update " + (t.isEmpty() ? "values" : t) + ".xml"
                    + (v.isEmpty() ? "" : " (" + v + ")");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String type = normalizeValuesType(stringArg(args, "type"));
            String suffix = AgentResources.normalizeVariant(
                    stringArg(args, "variant"));
            String folder = AgentResources.variantFolderName(suffix);
            JsonObject rawEntries = args != null && args.has("entries")
                    && args.get("entries").isJsonObject()
                    ? args.getAsJsonObject("entries") : new JsonObject();
            List<String> deletes = parseStringList(args, "delete");
            if (rawEntries.size() == 0 && deletes.isEmpty()) {
                throw new ToolException("E_EMPTY_EDIT",
                        "Pass at least one entry in \"entries\" or one key "
                                + "in \"delete\".");
            }

            List<String> detailLines = new ArrayList<>();
            switch (type) {
                case "strings" -> {
                    LinkedHashMap<String, AgentResources.StringEntry> upserts =
                            parseStringEntries(rawEntries);
                    AgentResources.validateStringEntries(
                            suffix, upserts, deletes);
                    for (AgentResources.StringEntry se : upserts.values()) {
                        detailLines.add("+ @string/" + se.name + " = \""
                                + se.value + "\""
                                + (se.translatable ? ""
                                        : " (non-translatable)"));
                    }
                }
                case "colors" -> {
                    LinkedHashMap<String, String> upserts =
                            parseColorEntries(rawEntries);
                    AgentResources.validateColorEntries(
                            suffix, upserts, deletes);
                    for (Map.Entry<String, String> ce : upserts.entrySet()) {
                        detailLines.add("+ @color/" + ce.getKey() + " = "
                                + ce.getValue());
                    }
                }
                case "styles", "themes" -> {
                    boolean themesFile = "themes".equals(type);
                    List<AgentResources.StyleSpec> upserts =
                            parseStyleEntries(rawEntries);
                    AgentResources.validateStylesUpdate(
                            suffix, themesFile, upserts, deletes);
                    for (AgentResources.StyleSpec ss : upserts) {
                        detailLines.add("+ @style/" + ss.name
                                + (ss.parent.isEmpty() ? ""
                                        : " (parent " + ss.parent + ")")
                                + " [" + ss.items.size() + " item"
                                + (ss.items.size() == 1 ? "" : "s") + "]");
                    }
                }
                case "arrays" -> {
                    List<AgentResources.ArraySpec> upserts =
                            parseArrayEntries(rawEntries);
                    AgentResources.validateArraysUpdate(upserts);
                    for (AgentResources.ArraySpec as : upserts) {
                        detailLines.add("+ " + as.type + " " + as.name
                                + " (" + as.items.size() + " item"
                                + (as.items.size() == 1 ? "" : "s") + ")");
                    }
                }
                default -> throw new ToolException("E_BAD_TYPE",
                        "Unsupported values type: " + type);
            }
            for (String d : deletes) {
                detailLines.add("- delete " + d);
            }

            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("type", type);
            clean.addProperty("variant", suffix);
            clean.add("entries", rawEntries.deepCopy());
            clean.add("delete", namesJsonAll(deletes));
            String fileLabel = folder + "/" + type + ".xml";
            String summary = "Update " + fileLabel + " in " + project.name
                    + " (" + rawEntries.size() + " upserted"
                    + (deletes.isEmpty() ? ""
                            : ", " + deletes.size() + " deleted")
                    + ").";
            return new AgentProposal(name(), clean, scId,
                    "Update " + fileLabel,
                    summary,
                    String.join("\n", detailLines));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String type = normalizeValuesType(
                    stringArg(proposal.args, "type"));
            String suffix = AgentResources.normalizeVariant(
                    stringArg(proposal.args, "variant"));
            String folder = AgentResources.variantFolderName(suffix);
            JsonObject rawEntries = proposal.args.has("entries")
                    && proposal.args.get("entries").isJsonObject()
                    ? proposal.args.getAsJsonObject("entries")
                    : new JsonObject();
            List<String> deletes = parseStringList(proposal.args, "delete");
            try {
                int totalAfter;
                switch (type) {
                    case "strings" -> totalAfter = AgentResources.writeStrings(
                            scId, suffix, parseStringEntries(rawEntries),
                            deletes).size();
                    case "colors" -> totalAfter = AgentResources.writeColors(
                            scId, suffix, parseColorEntries(rawEntries),
                            deletes).size();
                    case "styles", "themes" -> totalAfter =
                            AgentResources.writeStylesFile(
                                    scId, suffix, "themes".equals(type),
                                    parseStyleEntries(rawEntries),
                                    deletes).size();
                    case "arrays" -> totalAfter = AgentResources.writeArrays(
                            scId, suffix, parseArrayEntries(rawEntries),
                            deletes).size();
                    default -> throw new ToolException("E_BAD_TYPE",
                            "Unsupported values type: " + type);
                }
                JsonObject result = new JsonObject();
                result.addProperty("file", folder + "/" + type + ".xml");
                result.addProperty("total_entries", totalAfter);
                result.addProperty("verified",
                        "Wrote and verified res/" + folder + "/" + type
                                + ".xml (" + totalAfter + " entries).");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_VALUES_WRITE_FAILED",
                        "Updating " + folder + "/" + type + ".xml failed: "
                                + String.valueOf(e.getMessage()));
            }
        }

        private static String normalizeValuesType(String raw)
                throws ToolException {
            String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (t.endsWith(".xml")) {
                t = t.substring(0, t.length() - 4);
            }
            return switch (t) {
                case "string", "strings" -> "strings";
                case "color", "colors", "colour", "colours" -> "colors";
                case "style", "styles" -> "styles";
                case "theme", "themes" -> "themes";
                case "array", "arrays" -> "arrays";
                default -> throw new ToolException("E_BAD_TYPE",
                        "Unknown values resource type \"" + raw
                                + "\". Use strings, colors, styles, themes, "
                                + "or arrays.");
            };
        }

        private static LinkedHashMap<String, AgentResources.StringEntry>
                parseStringEntries(JsonObject obj) {
            LinkedHashMap<String, AgentResources.StringEntry> out =
                    new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().trim();
                JsonElement v = e.getValue();
                if (v == null || v.isJsonNull()) continue;
                if (v.isJsonObject()) {
                    JsonObject vo = v.getAsJsonObject();
                    String val = stringArg(vo, "value");
                    boolean trans = !vo.has("translatable")
                            || vo.get("translatable").isJsonNull()
                            || vo.get("translatable").getAsBoolean();
                    out.put(key,
                            new AgentResources.StringEntry(key, val, trans));
                } else {
                    out.put(key, new AgentResources.StringEntry(
                            key, v.getAsString(), true));
                }
            }
            return out;
        }

        private static LinkedHashMap<String, String> parseColorEntries(
                JsonObject obj) {
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().trim();
                JsonElement v = e.getValue();
                if (v == null || v.isJsonNull()) continue;
                String val = v.isJsonObject()
                        ? stringArg(v.getAsJsonObject(), "value")
                        : v.getAsString();
                out.put(key, val);
            }
            return out;
        }

        private static List<AgentResources.StyleSpec> parseStyleEntries(
                JsonObject obj) throws ToolException {
            List<AgentResources.StyleSpec> out = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String name = e.getKey() == null ? "" : e.getKey().trim();
                if (e.getValue() == null || !e.getValue().isJsonObject()) {
                    throw new ToolException("E_BAD_STYLE",
                            "Style \"" + name + "\" must be an object with "
                                    + "optional \"parent\" and \"items\" map.");
                }
                JsonObject so = e.getValue().getAsJsonObject();
                String parent = stringArg(so, "parent");
                boolean replace = so.has("replace_items")
                        && !so.get("replace_items").isJsonNull()
                        && so.get("replace_items").getAsBoolean();
                LinkedHashMap<String, String> items = new LinkedHashMap<>();
                if (so.has("items") && so.get("items").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> it
                            : so.getAsJsonObject("items").entrySet()) {
                        String val = it.getValue() == null
                                || it.getValue().isJsonNull() ? ""
                                : it.getValue().getAsString();
                        items.put(it.getKey().trim(), val);
                    }
                }
                out.add(new AgentResources.StyleSpec(
                        name, parent, items, replace));
            }
            return out;
        }

        private static List<AgentResources.ArraySpec> parseArrayEntries(
                JsonObject obj) throws ToolException {
            List<AgentResources.ArraySpec> out = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String name = e.getKey() == null ? "" : e.getKey().trim();
                JsonElement v = e.getValue();
                if (v == null || v.isJsonNull()) continue;
                String tag = "string-array";
                List<String> items = new ArrayList<>();
                if (v.isJsonArray()) {
                    for (JsonElement item : v.getAsJsonArray()) {
                        if (item != null && !item.isJsonNull()) {
                            items.add(item.getAsString());
                        }
                    }
                } else if (v.isJsonObject()) {
                    JsonObject ao = v.getAsJsonObject();
                    String askedTag = stringArg(ao, "type");
                    if (!askedTag.isEmpty()) {
                        tag = AgentResources.normalizeArrayTag(askedTag);
                    }
                    if (ao.has("items") && ao.get("items").isJsonArray()) {
                        for (JsonElement item
                                : ao.getAsJsonArray("items")) {
                            if (item != null && !item.isJsonNull()) {
                                items.add(item.getAsString());
                            }
                        }
                    }
                } else {
                    throw new ToolException("E_BAD_ARRAY",
                            "Array \"" + name + "\" must be a JSON array of "
                                    + "items or {\"type\":\"string-array\","
                                    + "\"items\":[...]}.");
                }
                out.add(new AgentResources.ArraySpec(tag, name, items));
            }
            return out;
        }
    }

    private static List<String> parseStringList(JsonObject args, String key) {
        List<String> out = new ArrayList<>();
        if (args == null || !args.has(key)) return out;
        JsonElement el = args.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item != null && !item.isJsonNull()) {
                    String s = item.getAsString().trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * read_resource_xml: lists custom res/ folders & files or reads one XML
     * file under files/resource/<folder>/<file>.xml.
     */
    private static final class ReadResourceXml implements Tool {
        @Override
        public String name() {
            return "read_resource_xml";
        }

        @Override
        public String description() {
            return "Lists custom Android XML resource files in res/ (when "
                    + "\"file\" is omitted), or reads the full XML content of "
                    + "res/<folder>/<file>.xml (e.g. menu/main_menu.xml, "
                    + "anim/fade_in.xml, animator/pulse.xml, "
                    + "xml/file_paths.xml, color/nav_tint.xml).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("folder", stringSchema(
                    "Optional Android resource folder, e.g. menu, anim, "
                            + "animator, xml, color, drawable, values-night"));
            properties.add("file", stringSchema(
                    "Optional XML file name inside folder (e.g. "
                            + "main_menu.xml) to read its full XML content"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String f = stringArg(args, "file");
            String d = stringArg(args, "folder");
            return f.isEmpty()
                    ? "Listed res/" + d + " files"
                    : "Read res/" + d + "/" + f;
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String folder = stringArg(args, "folder");
            String file = stringArg(args, "file");
            JsonObject result = new JsonObject();
            if (file.isEmpty()) {
                JsonArray files =
                        AgentResources.listRawResourceFiles(scId, folder);
                result.add("files", files);
                result.addProperty("count", files.size());
                return result;
            }
            if (folder.isEmpty()) {
                throw new ToolException("E_MISSING_FOLDER",
                        "Pass \"folder\" (e.g. \"menu\", \"anim\", \"xml\") "
                                + "alongside \"file\".");
            }
            String cleanFolder = AgentResources.normalizeResourceFolder(folder);
            String cleanFile = AgentResources.normalizeXmlFileName(file);
            result.addProperty("path",
                    "res/" + cleanFolder + "/" + cleanFile);
            result.addProperty("xml",
                    AgentResources.readRawResourceXml(
                            scId, cleanFolder, cleanFile));
            return result;
        }
    }

    /**
     * write_resource_xml: validates and writes an arbitrary Android XML
     * resource file under files/resource/<folder>/<file>.xml.
     */
    private static final class WriteResourceXml implements Tool {
        @Override
        public String name() {
            return "write_resource_xml";
        }

        @Override
        public String description() {
            return "Proposes creating or updating an Android XML resource "
                    + "file under res/<folder>/<file>.xml (such as "
                    + "menu/main_menu.xml for menu_inflate, anim/fade_in.xml "
                    + "for start_xml_animation, animator/*.xml, "
                    + "xml/network_security_config.xml, xml/shortcuts.xml, "
                    + "color/*.xml color-state-lists, etc.). XML is "
                    + "validated for well-formedness before saving.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("folder", stringSchema(
                    "Android resource folder, e.g. menu, anim, animator, xml, "
                            + "color, drawable, transition, navigation, "
                            + "values-v31"));
            properties.add("file", stringSchema(
                    "XML file name, e.g. main_menu.xml or fade_in.xml"));
            properties.add("xml", stringSchema(
                    "Well-formed Android resource XML content"));
            return AgentTools.parameters(properties, "folder", "file", "xml");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to write res/" + stringArg(args, "folder") + "/"
                    + stringArg(args, "file");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String folder = AgentResources.normalizeResourceFolder(
                    stringArg(args, "folder"));
            String file = AgentResources.normalizeXmlFileName(
                    stringArg(args, "file"));
            String xml = stringArg(args, "xml");
            org.w3c.dom.Document doc = AgentResources.parseXmlDocument(
                    xml, "res/" + folder + "/" + file);
            String rootTag = doc.getDocumentElement() != null
                    ? doc.getDocumentElement().getTagName() : "xml";
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("folder", folder);
            clean.addProperty("file", file);
            clean.addProperty("xml", xml);
            return new AgentProposal(name(), clean, scId,
                    "Write res/" + folder + "/" + file,
                    "Save res/" + folder + "/" + file + " (<" + rootTag
                            + ">) in " + project.name + ".",
                    xml);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String folder = stringArg(proposal.args, "folder");
            String file = stringArg(proposal.args, "file");
            String xml = stringArg(proposal.args, "xml");
            try {
                java.io.File saved = AgentResources.writeRawResourceXml(
                        scId, folder, file, xml);
                JsonObject result = new JsonObject();
                result.addProperty("path", "res/" + folder + "/" + file);
                result.addProperty("bytes", saved.length());
                result.addProperty("verified",
                        "Wrote and verified res/" + folder + "/" + file
                                + " (" + saved.length() + " bytes).");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_WRITE_RESOURCE_FAILED",
                        "Writing res/" + folder + "/" + file + " failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * delete_resource_xml: deletes a custom XML file from files/resource/.
     */
    private static final class DeleteResourceXml implements Tool {
        @Override
        public String name() {
            return "delete_resource_xml";
        }

        @Override
        public String description() {
            return "Proposes deleting a custom XML resource file from "
                    + "res/<folder>/<file>.xml.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("folder", stringSchema(
                    "Android resource folder, e.g. menu, anim, xml, color"));
            properties.add("file", stringSchema(
                    "XML file name to delete, e.g. main_menu.xml"));
            return AgentTools.parameters(properties, "folder", "file");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to delete res/" + stringArg(args, "folder") + "/"
                    + stringArg(args, "file");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String folder = AgentResources.normalizeResourceFolder(
                    stringArg(args, "folder"));
            String file = AgentResources.normalizeXmlFileName(
                    stringArg(args, "file"));
            // Verify it exists before proposing deletion.
            AgentResources.readRawResourceXml(scId, folder, file);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("folder", folder);
            clean.addProperty("file", file);
            return new AgentProposal(name(), clean, scId,
                    "Delete res/" + folder + "/" + file,
                    "Remove res/" + folder + "/" + file + " from "
                            + project.name + ".",
                    "A project backup is saved before deletion.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String folder = stringArg(proposal.args, "folder");
            String file = stringArg(proposal.args, "file");
            try {
                AgentResources.deleteRawResourceXml(scId, folder, file);
                JsonObject result = new JsonObject();
                result.addProperty("deleted", "res/" + folder + "/" + file);
                result.addProperty("verified",
                        "Deleted res/" + folder + "/" + file + ".");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_DELETE_RESOURCE_FAILED",
                        "Deleting res/" + folder + "/" + file + " failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * list_font_and_sound_sources: lists project fonts/sounds alongside all
     * available bundled fonts, synthesized sound presets, and user collections.
     */
    private static final class ListFontAndSoundSources implements Tool {
        @Override
        public String name() {
            return "list_font_and_sound_sources";
        }

        @Override
        public String description() {
            return "Lists the working project's imported fonts and sounds, "
                    + "plus all bundled fonts (gilroy, jetbrains_mono, "
                    + "droid_sans_mono), synthesized sound presets (click, "
                    + "pop, success, complete, error, notification, alarm), "
                    + "and user Font/Sound Collections available for "
                    + "import_project_font and import_project_sound.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Listed project fonts, sounds & collections";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            JsonObject result = new JsonObject();
            JsonArray projFonts = new JsonArray();
            for (com.besome.sketch.beans.ProjectResourceBean b
                    : AgentResources.projectFonts(scId)) {
                JsonObject o = new JsonObject();
                o.addProperty("name", b.resName);
                o.addProperty("file", b.resFullName);
                projFonts.add(o);
            }
            JsonArray collFonts = new JsonArray();
            for (com.besome.sketch.beans.ProjectResourceBean b
                    : AgentResources.collectionFonts()) {
                collFonts.add(b.resName);
            }
            JsonArray projSounds = new JsonArray();
            for (com.besome.sketch.beans.ProjectResourceBean b
                    : AgentResources.projectSounds(scId)) {
                JsonObject o = new JsonObject();
                o.addProperty("name", b.resName);
                o.addProperty("file", b.resFullName);
                projSounds.add(o);
            }
            JsonArray collSounds = new JsonArray();
            for (com.besome.sketch.beans.ProjectResourceBean b
                    : AgentResources.collectionSounds()) {
                collSounds.add(b.resName);
            }
            result.add("project_fonts", projFonts);
            result.add("bundled_fonts",
                    namesJsonAll(AgentResources.BUNDLED_FONTS));
            result.add("collection_fonts", collFonts);
            result.add("project_sounds", projSounds);
            result.add("sound_presets",
                    namesJsonAll(AgentResources.SOUND_PRESETS));
            result.add("collection_sounds", collSounds);
            return result;
        }
    }

    /**
     * import_project_font: imports a .ttf/.otf font into the working project.
     */
    private static final class ImportProjectFont implements Tool {
        @Override
        public String name() {
            return "import_project_font";
        }

        @Override
        public String description() {
            return "Proposes importing a .ttf or .otf font into the working "
                    + "project's Font Manager from bundled_font: gilroy, "
                    + "jetbrains_mono, droid_sans_mono; collection_name "
                    + "(from list_font_and_sound_sources); or file_path on "
                    + "device. Once imported, apply it to widgets via "
                    + "edit_screen's set_font or dynamically via "
                    + "set_typeface.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("name", stringSchema(
                    "Project font resource name (lowercase letters, digits, "
                            + "underscores, min 3 chars, e.g. heading_sans "
                            + "or code_mono)"));
            properties.add("bundled_font", stringSchema(
                    "Optional built-in font: gilroy, jetbrains_mono, or "
                            + "droid_sans_mono"));
            properties.add("collection_name", stringSchema(
                    "Optional font name from the user's Sketchware Font "
                            + "Collection"));
            properties.add("file_path", stringSchema(
                    "Optional path to a local .ttf or .otf file on device"));
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to import font " + stringArg(args, "name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String fontName = stringArg(args, "name").trim()
                    .toLowerCase(Locale.ROOT);
            AgentResources.validateFontName(fontName);
            String bundled = stringArg(args, "bundled_font");
            String collection = stringArg(args, "collection_name");
            String filePath = stringArg(args, "file_path");
            String[] ext = new String[]{".ttf"};
            byte[] bytes = AgentResources.loadFontBytes(
                    bundled, collection, filePath, ext);

            String sourceDesc = !bundled.isEmpty()
                    ? "bundled font \"" + bundled + "\""
                    : (!collection.isEmpty()
                            ? "collection font \"" + collection + "\""
                            : "file " + filePath);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("name", fontName);
            clean.addProperty("bundled_font", bundled);
            clean.addProperty("collection_name", collection);
            clean.addProperty("file_path", filePath);
            return new AgentProposal(name(), clean, scId,
                    "Import font " + fontName,
                    "Add font \"" + fontName + ext[0] + "\" ("
                            + (bytes.length / 1024) + " KB) from "
                            + sourceDesc + " to " + project.name + ".",
                    "Saved into Sketchware's Font Manager (assets/fonts/"
                            + fontName + ext[0]
                            + ") so widgets can use set_font=\"" + fontName
                            + "\" and blocks can use set_typeface.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String fontName = stringArg(proposal.args, "name");
            String bundled = stringArg(proposal.args, "bundled_font");
            String collection = stringArg(proposal.args, "collection_name");
            String filePath = stringArg(proposal.args, "file_path");
            String[] ext = new String[]{".ttf"};
            byte[] bytes = AgentResources.loadFontBytes(
                    bundled, collection, filePath, ext);
            try {
                com.besome.sketch.beans.ProjectResourceBean saved =
                        AgentResources.importProjectFont(
                                scId, fontName, bytes, ext[0]);
                JsonObject result = new JsonObject();
                result.addProperty("font", saved.resName);
                result.addProperty("file", saved.resFullName);
                result.addProperty("bytes", bytes.length);
                result.addProperty("verified",
                        "Imported font \"" + saved.resName + "\" ("
                                + saved.resFullName
                                + ") and verified read-back.");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_FONT_IMPORT_FAILED",
                        "Importing font \"" + fontName + "\" failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * delete_project_font: removes a font from the working project.
     */
    private static final class DeleteProjectFont implements Tool {
        @Override
        public String name() {
            return "delete_project_font";
        }

        @Override
        public String description() {
            return "Proposes deleting an imported font from the working "
                    + "project, checking widget and block references first.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("name", stringSchema(
                    "Project font name to delete"));
            JsonObject clear = new JsonObject();
            clear.addProperty("type", "boolean");
            clear.addProperty("description",
                    "When true, resets widgets using this font back to "
                            + "default_font before deleting");
            properties.add("clear_references", clear);
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to delete font " + stringArg(args, "name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String fontName = stringArg(args, "name").trim();
            boolean exists = false;
            for (com.besome.sketch.beans.ProjectResourceBean b
                    : AgentResources.projectFonts(scId)) {
                if (b.resName.equals(fontName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                throw new ToolException("E_FONT_NOT_FOUND",
                        "Project has no font named \"" + fontName + "\".");
            }
            boolean clear = args != null && args.has("clear_references")
                    && !args.get("clear_references").isJsonNull()
                    && args.get("clear_references").getAsBoolean();
            List<String> refs = AgentResources.fontReferences(scId, fontName);
            if (!refs.isEmpty() && !clear) {
                throw new ToolException("E_FONT_IN_USE",
                        "Font \"" + fontName + "\" is still used by: "
                                + String.join("; ", refs)
                                + ". Pass clear_references=true or update "
                                + "those widgets/blocks first.");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("name", fontName);
            clean.addProperty("clear_references", clear);
            return new AgentProposal(name(), clean, scId,
                    "Delete font " + fontName,
                    "Remove font \"" + fontName + "\" from " + project.name
                            + (refs.isEmpty() ? "."
                                    : " (resets " + refs.size()
                                            + " reference(s))."),
                    refs.isEmpty() ? "No widgets reference this font."
                            : "References cleared:\n"
                                    + String.join("\n", refs));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String fontName = stringArg(proposal.args, "name");
            boolean clear = proposal.args.has("clear_references")
                    && proposal.args.get("clear_references").getAsBoolean();
            try {
                List<String> cleared = AgentResources.deleteProjectFont(
                        scId, fontName, clear);
                JsonObject result = new JsonObject();
                result.addProperty("deleted_font", fontName);
                result.addProperty("cleared_references", cleared.size());
                result.addProperty("verified",
                        "Deleted font \"" + fontName + "\".");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_FONT_DELETE_FAILED",
                        "Deleting font \"" + fontName + "\" failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * import_project_sound: imports or synthesizes a sound effect into the
     * working project's Sound Manager (res/raw/).
     */
    private static final class ImportProjectSound implements Tool {
        @Override
        public String name() {
            return "import_project_sound";
        }

        @Override
        public String description() {
            return "Proposes adding a sound resource into the working "
                    + "project's Sound Manager (res/raw/<name>) from a "
                    + "built-in synthesized preset (click, pop, success, "
                    + "complete, error, notification, alarm), a custom tone "
                    + "(frequency_hz, duration_ms, waveform), "
                    + "collection_name, or a local .mp3/.ogg/.wav/.m4a "
                    + "file_path. Ready for MediaPlayer (mp_create) and "
                    + "SoundPool (load_sound).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("name", stringSchema(
                    "Project sound resource name (lowercase letters, digits, "
                            + "underscores, e.g. sfx_click or chime_done)"));
            properties.add("preset", stringSchema(
                    "Optional synthesized sound preset: click, pop, success, "
                            + "complete, error, notification, or alarm"));
            JsonObject freq = new JsonObject();
            freq.addProperty("type", "number");
            freq.addProperty("description",
                    "Optional custom tone frequency in Hz (40-8000, e.g. "
                            + "440 for A4)");
            properties.add("frequency_hz", freq);
            JsonObject dur = new JsonObject();
            dur.addProperty("type", "integer");
            dur.addProperty("description",
                    "Optional custom tone duration in ms (30-5000, default "
                            + "220)");
            properties.add("duration_ms", dur);
            properties.add("waveform", stringSchema(
                    "Optional custom tone waveform: sine (default), "
                            + "triangle, or square"));
            properties.add("collection_name", stringSchema(
                    "Optional sound name from the user's Sketchware Sound "
                            + "Collection"));
            properties.add("file_path", stringSchema(
                    "Optional path to a local .mp3, .ogg, .wav, or .m4a "
                            + "audio file on device"));
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to import sound " + stringArg(args, "name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String soundName = stringArg(args, "name").trim()
                    .toLowerCase(Locale.ROOT);
            AgentResources.validateSoundName(soundName);
            String preset = stringArg(args, "preset");
            Double freq = args != null && args.has("frequency_hz")
                    && !args.get("frequency_hz").isJsonNull()
                    ? args.get("frequency_hz").getAsDouble() : null;
            Integer dur = args != null && args.has("duration_ms")
                    && !args.get("duration_ms").isJsonNull()
                    ? args.get("duration_ms").getAsInt() : null;
            String waveform = stringArg(args, "waveform");
            String collection = stringArg(args, "collection_name");
            String filePath = stringArg(args, "file_path");
            String[] ext = new String[]{".wav"};
            byte[] bytes = AgentResources.loadSoundBytes(
                    preset, freq, dur, waveform, collection, filePath, ext);

            String sourceDesc = !preset.isEmpty()
                    ? "synthesized preset \"" + preset + "\""
                    : (freq != null
                            ? "synthesized " + freq + " Hz tone"
                            : (!collection.isEmpty()
                                    ? "collection sound \"" + collection + "\""
                                    : "file " + filePath));
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("name", soundName);
            clean.addProperty("preset", preset);
            if (freq != null) clean.addProperty("frequency_hz", freq);
            if (dur != null) clean.addProperty("duration_ms", dur);
            clean.addProperty("waveform", waveform);
            clean.addProperty("collection_name", collection);
            clean.addProperty("file_path", filePath);
            return new AgentProposal(name(), clean, scId,
                    "Add sound " + soundName,
                    "Add sound \"" + soundName + ext[0] + "\" ("
                            + Math.max(1, bytes.length / 1024)
                            + " KB) from " + sourceDesc + " to "
                            + project.name + ".",
                    "Saved into Sketchware's Sound Manager (res/raw/"
                            + soundName + ext[0]
                            + ") for MediaPlayer (mp_create) and SoundPool "
                            + "(load_sound).");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            String soundName = stringArg(a, "name");
            String preset = stringArg(a, "preset");
            Double freq = a.has("frequency_hz")
                    && !a.get("frequency_hz").isJsonNull()
                    ? a.get("frequency_hz").getAsDouble() : null;
            Integer dur = a.has("duration_ms")
                    && !a.get("duration_ms").isJsonNull()
                    ? a.get("duration_ms").getAsInt() : null;
            String waveform = stringArg(a, "waveform");
            String collection = stringArg(a, "collection_name");
            String filePath = stringArg(a, "file_path");
            String[] ext = new String[]{".wav"};
            byte[] bytes = AgentResources.loadSoundBytes(
                    preset, freq, dur, waveform, collection, filePath, ext);
            try {
                com.besome.sketch.beans.ProjectResourceBean saved =
                        AgentResources.importProjectSound(
                                scId, soundName, bytes, ext[0]);
                JsonObject result = new JsonObject();
                result.addProperty("sound", saved.resName);
                result.addProperty("file", saved.resFullName);
                result.addProperty("bytes", bytes.length);
                result.addProperty("verified",
                        "Added sound \"" + saved.resName + "\" ("
                                + saved.resFullName
                                + ") and verified read-back.");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_SOUND_IMPORT_FAILED",
                        "Importing sound \"" + soundName + "\" failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * delete_project_sound: removes a sound from the working project.
     */
    private static final class DeleteProjectSound implements Tool {
        @Override
        public String name() {
            return "delete_project_sound";
        }

        @Override
        public String description() {
            return "Proposes deleting a sound from the working project's "
                    + "Sound Manager, checking MediaPlayer and SoundPool "
                    + "block references first.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("name", stringSchema(
                    "Project sound name to delete"));
            JsonObject clear = new JsonObject();
            clear.addProperty("type", "boolean");
            clear.addProperty("description",
                    "When true, deletes the sound even if blocks still "
                            + "name it");
            properties.add("clear_references", clear);
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to delete sound " + stringArg(args, "name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String soundName = stringArg(args, "name").trim();
            boolean exists = false;
            for (com.besome.sketch.beans.ProjectResourceBean b
                    : AgentResources.projectSounds(scId)) {
                if (b.resName.equals(soundName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                throw new ToolException("E_SOUND_NOT_FOUND",
                        "Project has no sound named \"" + soundName + "\".");
            }
            boolean clear = args != null && args.has("clear_references")
                    && !args.get("clear_references").isJsonNull()
                    && args.get("clear_references").getAsBoolean();
            List<String> refs =
                    AgentResources.soundReferences(scId, soundName);
            if (!refs.isEmpty() && !clear) {
                throw new ToolException("E_SOUND_IN_USE",
                        "Sound \"" + soundName + "\" is still used by: "
                                + String.join("; ", refs)
                                + ". Pass clear_references=true or update "
                                + "those blocks first.");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("name", soundName);
            clean.addProperty("clear_references", clear);
            return new AgentProposal(name(), clean, scId,
                    "Delete sound " + soundName,
                    "Remove sound \"" + soundName + "\" from "
                            + project.name + ".",
                    refs.isEmpty() ? "No blocks reference this sound."
                            : "Referenced by:\n" + String.join("\n", refs));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String soundName = stringArg(proposal.args, "name");
            boolean clear = proposal.args.has("clear_references")
                    && proposal.args.get("clear_references").getAsBoolean();
            try {
                AgentResources.deleteProjectSound(scId, soundName, clear);
                JsonObject result = new JsonObject();
                result.addProperty("deleted_sound", soundName);
                result.addProperty("verified",
                        "Deleted sound \"" + soundName + "\".");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_SOUND_DELETE_FAILED",
                        "Deleting sound \"" + soundName + "\" failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    // =====================================================================
    // Phase 5: Java / Kotlin Manager, Assets Manager & Native Libraries
    // =====================================================================

    /**
     * read_source_files: lists or reads custom .java and .kt classes under
     * files/java/ (plus AndroidManifest component registrations).
     */
    private static final class ReadSourceFiles implements Tool {
        @Override
        public String name() {
            return "read_source_files";
        }

        @Override
        public String description() {
            return "Lists custom Java (.java) and Kotlin (.kt) source files "
                    + "in the working project's Java Manager (when \"path\" "
                    + "is omitted), including package names, FQCNs, and "
                    + "AndroidManifest roles (activity, service, receiver, "
                    + "none), or reads the full source code of one file.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("path", stringSchema(
                    "Optional relative path under files/java/ (e.g. "
                            + "NoteModel.java or adapters/FeedAdapter.kt) to "
                            + "read its full source code"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String p = stringArg(args, "path");
            return p.isEmpty() ? "Listed Java & Kotlin source files"
                    : "Read source file " + p;
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String path = stringArg(args, "path");
            JsonObject result = new JsonObject();
            if (path.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (AgentSources.SourceFileInfo info
                        : AgentSources.listSourceFiles(scId)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("path", info.relativePath);
                    o.addProperty("class_name", info.className);
                    o.addProperty("package", info.packageName);
                    o.addProperty("fqcn", info.fqcn);
                    o.addProperty("language", info.language);
                    o.addProperty("manifest_role", info.manifestRole);
                    o.addProperty("bytes", info.bytes);
                    arr.add(o);
                }
                result.add("files", arr);
                result.addProperty("count", arr.size());
                result.add("kotlin_compiler_plugins",
                        namesJsonAll(
                                AgentSources.listKotlinCompilerPlugins(scId)));
                return result;
            }
            String cleanRel = AgentSources.normalizeSourceRelPath(path);
            String code = AgentSources.readSourceFile(scId, cleanRel);
            String expectedPkg =
                    AgentSources.expectedPackageForRelPath(scId, cleanRel);
            String pkg = AgentSources.extractPackageName(code, expectedPkg);
            String leaf = cleanRel.contains("/")
                    ? cleanRel.substring(cleanRel.lastIndexOf('/') + 1)
                    : cleanRel;
            String cls = leaf.substring(0, leaf.lastIndexOf('.'));
            result.addProperty("path", cleanRel);
            result.addProperty("fqcn", pkg + "." + cls);
            result.addProperty("language",
                    cleanRel.endsWith(".kt") ? "kotlin" : "java");
            result.addProperty("code", code);
            return result;
        }
    }

    /**
     * write_source_file: creates or updates a .java or .kt class in
     * files/java/ and optionally registers it in AndroidManifest.xml.
     */
    private static final class WriteSourceFile implements Tool {
        @Override
        public String name() {
            return "write_source_file";
        }

        @Override
        public String description() {
            return "Proposes creating or updating a Java (.java) or Kotlin "
                    + "(.kt) source file in the working project's Java "
                    + "Manager (files/java/<path>). Kotlin stdlib is added "
                    + "automatically whenever .kt files exist. Pass "
                    + "register_in_manifest as \"activity\", \"service\", or "
                    + "\"receiver\" when the class is a custom Activity, "
                    + "Service, or BroadcastReceiver that must be declared "
                    + "in AndroidManifest.xml.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("path", stringSchema(
                    "Relative file path ending in .java or .kt, e.g. "
                            + "NoteModel.java, adapters/FeedAdapter.kt, "
                            + "services/SyncService.java"));
            properties.add("code", stringSchema(
                    "Complete Java or Kotlin source code (package line is "
                            + "auto-added from project package + subfolder "
                            + "when omitted)"));
            properties.add("register_in_manifest", stringSchema(
                    "Optional AndroidManifest registration: none (default "
                            + "for new files), activity, service, receiver, "
                            + "or keep (preserves existing role on update)"));
            return AgentTools.parameters(properties, "path", "code");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to write " + stringArg(args, "path");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String cleanRel = AgentSources.normalizeSourceRelPath(
                    stringArg(args, "path"));
            String rawCode = stringArg(args, "code");
            if (rawCode.trim().isEmpty()) {
                throw new ToolException("E_EMPTY_SOURCE",
                        "Pass non-empty Java or Kotlin source in \"code\".");
            }
            boolean isKt = cleanRel.endsWith(".kt");
            String expectedPkg =
                    AgentSources.expectedPackageForRelPath(scId, cleanRel);
            String finalCode = AgentSources.ensurePackageDeclaration(
                    rawCode, expectedPkg, isKt);
            String pkg = AgentSources.extractPackageName(
                    finalCode, expectedPkg);
            String leaf = cleanRel.contains("/")
                    ? cleanRel.substring(cleanRel.lastIndexOf('/') + 1)
                    : cleanRel;
            String cls = leaf.substring(0, leaf.lastIndexOf('.'));
            String fqcn = pkg + "." + cls;
            AgentSources.checkNoScreenClassCollision(scId, cleanRel, fqcn);
            String role = AgentSources.normalizeManifestRole(
                    stringArg(args, "register_in_manifest"));

            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("path", cleanRel);
            clean.addProperty("code", finalCode);
            clean.addProperty("register_in_manifest", role);
            String roleNote = role.isEmpty() || "none".equals(role)
                    ? ""
                    : " and register as <" + role + "> in AndroidManifest";
            return new AgentProposal(name(), clean, scId,
                    "Write " + (isKt ? "Kotlin" : "Java") + " file " + leaf,
                    "Save files/java/" + cleanRel + " (" + fqcn + ") in "
                            + project.name + roleNote + ".",
                    finalCode);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String path = stringArg(proposal.args, "path");
            String code = stringArg(proposal.args, "code");
            String role = stringArg(proposal.args, "register_in_manifest");
            try {
                AgentSources.SourceFileInfo saved =
                        AgentSources.writeSourceFile(scId, path, code, role);
                JsonObject result = new JsonObject();
                result.addProperty("path", saved.relativePath);
                result.addProperty("fqcn", saved.fqcn);
                result.addProperty("language", saved.language);
                result.addProperty("manifest_role", saved.manifestRole);
                result.addProperty("bytes", saved.bytes);
                result.addProperty("verified",
                        "Wrote files/java/" + saved.relativePath + " ("
                                + saved.fqcn + ", manifest_role="
                                + saved.manifestRole + ").");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_WRITE_SOURCE_FAILED",
                        "Writing files/java/" + path + " failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * delete_source_file: deletes a custom .java or .kt file and cleans its
     * AndroidManifest component entry if registered.
     */
    private static final class DeleteSourceFile implements Tool {
        @Override
        public String name() {
            return "delete_source_file";
        }

        @Override
        public String description() {
            return "Proposes deleting a custom .java or .kt source file from "
                    + "files/java/<path> and removing any matching "
                    + "AndroidManifest <activity>/<service>/<receiver> entry.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("path", stringSchema(
                    "Relative path under files/java/, e.g. NoteModel.java"));
            return AgentTools.parameters(properties, "path");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to delete " + stringArg(args, "path");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String cleanRel = AgentSources.normalizeSourceRelPath(
                    stringArg(args, "path"));
            AgentSources.readSourceFile(scId, cleanRel);
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("path", cleanRel);
            return new AgentProposal(name(), clean, scId,
                    "Delete " + cleanRel,
                    "Remove files/java/" + cleanRel + " from " + project.name
                            + ".",
                    "Also cleans any AndroidManifest component registration "
                            + "for this class.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String path = stringArg(proposal.args, "path");
            try {
                AgentSources.deleteSourceFile(scId, path);
                JsonObject result = new JsonObject();
                result.addProperty("deleted", "files/java/" + path);
                result.addProperty("verified",
                        "Deleted files/java/" + path + ".");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_DELETE_SOURCE_FAILED",
                        "Deleting files/java/" + path + " failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * read_assets: lists or reads files in the working project's Assets
     * Manager (files/assets/).
     */
    private static final class ReadAssets implements Tool {
        @Override
        public String name() {
            return "read_assets";
        }

        @Override
        public String description() {
            return "Lists packaged asset files in assets/ (when \"path\" is "
                    + "omitted or names a folder), or reads the content of "
                    + "assets/<path> (JSON, HTML, CSS, JS, SVG, CSV, shaders, "
                    + "or small binary files).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("path", stringSchema(
                    "Optional relative asset file or folder path, e.g. "
                            + "data/items.json or web/index.html"));
            return AgentTools.parameters(properties);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String p = stringArg(args, "path");
            return p.isEmpty() ? "Listed project assets"
                    : "Read assets/" + p;
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String rawPath = stringArg(args, "path");
            if (rawPath.isEmpty()) {
                JsonArray files = AgentSources.listAssets(scId, "");
                JsonObject res = new JsonObject();
                res.add("assets", files);
                res.addProperty("count", files.size());
                return res;
            }
            String cleanRel = AgentSources.normalizeAssetRelPath(
                    rawPath, false);
            java.io.File target = new java.io.File(
                    new pro.sketchware.utility.FilePathUtil()
                            .getPathAssets(scId),
                    cleanRel);
            if (target.isDirectory()) {
                JsonArray files = AgentSources.listAssets(scId, cleanRel);
                JsonObject res = new JsonObject();
                res.addProperty("folder", cleanRel);
                res.add("assets", files);
                res.addProperty("count", files.size());
                return res;
            }
            return AgentSources.readAsset(scId, cleanRel);
        }
    }

    /**
     * write_asset: creates or updates a file in files/assets/<path>.
     */
    private static final class WriteAsset implements Tool {
        @Override
        public String name() {
            return "write_asset";
        }

        @Override
        public String description() {
            return "Proposes creating or updating a file in the project's "
                    + "Assets Manager (assets/<path>, accessible at runtime "
                    + "via getAssets().open(\"<path>\"), open_asset / "
                    + "asset_text blocks, or WebView "
                    + "file:///android_asset/<path>). JSON assets are "
                    + "validated automatically.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("path", stringSchema(
                    "Relative path inside assets/, e.g. data/questions.json "
                            + "or web/index.html"));
            properties.add("content", stringSchema(
                    "Optional UTF-8 text content (HTML, JSON, JS, CSS, CSV, "
                            + "SVG, etc.)"));
            properties.add("base64", stringSchema(
                    "Optional base64-encoded binary content"));
            properties.add("source_file_path", stringSchema(
                    "Optional local file path on device to copy into assets/"));
            return AgentTools.parameters(properties, "path");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to write assets/" + stringArg(args, "path");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String cleanRel = AgentSources.normalizeAssetRelPath(
                    stringArg(args, "path"), false);
            String textContent = args != null && args.has("content")
                    && !args.get("content").isJsonNull()
                    ? args.get("content").getAsString() : null;
            String b64 = stringArg(args, "base64");
            String srcPath = stringArg(args, "source_file_path");
            byte[] data = AgentSources.prepareAssetBytes(
                    cleanRel, textContent, b64, srcPath);

            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("path", cleanRel);
            if (textContent != null) clean.addProperty("content", textContent);
            if (!b64.isEmpty()) clean.addProperty("base64", b64);
            if (!srcPath.isEmpty()) {
                clean.addProperty("source_file_path", srcPath);
            }
            String preview = textContent != null
                    ? (textContent.length() > 1200
                            ? textContent.substring(0, 1200) + "\n..."
                            : textContent)
                    : ("Binary asset (" + data.length + " bytes)");
            return new AgentProposal(name(), clean, scId,
                    "Write assets/" + cleanRel,
                    "Save assets/" + cleanRel + " (" + data.length
                            + " bytes) in " + project.name + ".",
                    preview);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            String cleanRel = stringArg(a, "path");
            String textContent = a.has("content") && !a.get("content").isJsonNull()
                    ? a.get("content").getAsString() : null;
            String b64 = stringArg(a, "base64");
            String srcPath = stringArg(a, "source_file_path");
            byte[] data = AgentSources.prepareAssetBytes(
                    cleanRel, textContent, b64, srcPath);
            try {
                java.io.File saved =
                        AgentSources.writeAsset(scId, cleanRel, data);
                JsonObject result = new JsonObject();
                result.addProperty("path", cleanRel);
                result.addProperty("bytes", saved.length());
                result.addProperty("verified",
                        "Wrote and verified assets/" + cleanRel + " ("
                                + saved.length() + " bytes).");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_WRITE_ASSET_FAILED",
                        "Writing assets/" + cleanRel + " failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * delete_asset: removes an asset file or folder from files/assets/.
     */
    private static final class DeleteAsset implements Tool {
        @Override
        public String name() {
            return "delete_asset";
        }

        @Override
        public String description() {
            return "Proposes deleting a file or folder from the project's "
                    + "Assets Manager (assets/<path>).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("path", stringSchema(
                    "Relative asset path to delete, e.g. data/old.json"));
            return AgentTools.parameters(properties, "path");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to delete assets/" + stringArg(args, "path");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String cleanRel = AgentSources.normalizeAssetRelPath(
                    stringArg(args, "path"), false);
            java.io.File target = new java.io.File(
                    new pro.sketchware.utility.FilePathUtil()
                            .getPathAssets(scId),
                    cleanRel);
            if (!target.exists()) {
                throw new ToolException("E_ASSET_NOT_FOUND",
                        "Asset assets/" + cleanRel + " does not exist.");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("path", cleanRel);
            return new AgentProposal(name(), clean, scId,
                    "Delete assets/" + cleanRel,
                    "Remove assets/" + cleanRel + " from " + project.name
                            + ".",
                    "A project backup is saved before deletion.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String cleanRel = stringArg(proposal.args, "path");
            try {
                AgentSources.deleteAsset(scId, cleanRel);
                JsonObject result = new JsonObject();
                result.addProperty("deleted", "assets/" + cleanRel);
                result.addProperty("verified",
                        "Deleted assets/" + cleanRel + ".");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_DELETE_ASSET_FAILED",
                        "Deleting assets/" + cleanRel + " failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * inspect_native_libs: inspects .so JNI binaries across Android ABIs.
     */
    private static final class InspectNativeLibs implements Tool {
        @Override
        public String name() {
            return "inspect_native_libs";
        }

        @Override
        public String description() {
            return "Inspects native JNI .so libraries in the working "
                    + "project's Native Libraries Manager across ABIs "
                    + "(arm64-v8a, armeabi-v7a, x86_64, x86), checks ELF "
                    + "headers, and reports missing 64-bit or cross-ABI "
                    + "mismatch warnings.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Inspected native .so libraries";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            return AgentSources.inspectNativeLibraries(scId);
        }
    }

    /**
     * import_native_lib: imports an ELF .so binary into native_libs/<abi>/.
     */
    private static final class ImportNativeLib implements Tool {
        @Override
        public String name() {
            return "import_native_lib";
        }

        @Override
        public String description() {
            return "Proposes importing a compiled ELF .so JNI library into "
                    + "native_libs/<abi>/lib<name>.so, verifying ELF magic "
                    + "and machine architecture against the target ABI.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("abi", stringSchema(
                    "Target Android ABI: arm64-v8a, armeabi-v7a, x86_64, "
                            + "x86, or armeabi"));
            properties.add("source_file_path", stringSchema(
                    "Local path to the .so file on device"));
            properties.add("file_name", stringSchema(
                    "Optional target filename matching lib<name>.so "
                            + "(defaults to source filename)"));
            return AgentTools.parameters(
                    properties, "abi", "source_file_path");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to import native library for "
                    + stringArg(args, "abi");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String abi = AgentSources.normalizeAbi(stringArg(args, "abi"));
            String srcPath = stringArg(args, "source_file_path");
            String targetName = stringArg(args, "file_name");
            if (targetName.isEmpty() && !srcPath.isEmpty()) {
                targetName = new java.io.File(srcPath.trim()).getName();
            }
            AgentSources.validateNativeLibImport(abi, srcPath, targetName);
            java.io.File src = new java.io.File(srcPath.trim());
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("abi", abi);
            clean.addProperty("source_file_path", srcPath);
            clean.addProperty("file_name", targetName);
            return new AgentProposal(name(), clean, scId,
                    "Import " + abi + "/" + targetName,
                    "Add native library " + targetName + " ("
                            + (src.length() / 1024) + " KB) for ABI " + abi
                            + " to " + project.name + ".",
                    "ELF architecture verified as matching " + abi + ".");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String abi = stringArg(proposal.args, "abi");
            String srcPath = stringArg(proposal.args, "source_file_path");
            String fileName = stringArg(proposal.args, "file_name");
            try {
                java.io.File saved = AgentSources.importNativeLibrary(
                        scId, abi, srcPath, fileName);
                JsonObject result = new JsonObject();
                result.addProperty("abi", abi);
                result.addProperty("file", saved.getName());
                result.addProperty("bytes", saved.length());
                result.addProperty("verified",
                        "Imported native_libs/" + abi + "/" + saved.getName()
                                + ".");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_IMPORT_NATIVE_LIB_FAILED",
                        "Importing native library failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * delete_native_lib: removes a .so binary from native_libs/<abi>/.
     */
    private static final class DeleteNativeLib implements Tool {
        @Override
        public String name() {
            return "delete_native_lib";
        }

        @Override
        public String description() {
            return "Proposes deleting a native .so library from "
                    + "native_libs/<abi>/<file_name>.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("abi", stringSchema(
                    "Target Android ABI, e.g. arm64-v8a or armeabi-v7a"));
            properties.add("file_name", stringSchema(
                    "Native library filename, e.g. libcrypto.so"));
            return AgentTools.parameters(properties, "abi", "file_name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to delete " + stringArg(args, "abi") + "/"
                    + stringArg(args, "file_name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String abi = AgentSources.normalizeAbi(stringArg(args, "abi"));
            String fileName = AgentSources.normalizeSoFileName(
                    stringArg(args, "file_name"));
            java.io.File target = new java.io.File(
                    new pro.sketchware.utility.FilePathUtil()
                            .getPathNativelibs(scId),
                    abi + "/" + fileName);
            if (!target.exists()) {
                throw new ToolException("E_NATIVE_LIB_NOT_FOUND",
                        "Native library native_libs/" + abi + "/" + fileName
                                + " does not exist.");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("abi", abi);
            clean.addProperty("file_name", fileName);
            return new AgentProposal(name(), clean, scId,
                    "Delete " + abi + "/" + fileName,
                    "Remove native_libs/" + abi + "/" + fileName + " from "
                            + project.name + ".",
                    "A project backup is saved before deletion.");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            String abi = stringArg(proposal.args, "abi");
            String fileName = stringArg(proposal.args, "file_name");
            try {
                AgentSources.deleteNativeLibrary(scId, abi, fileName);
                JsonObject result = new JsonObject();
                result.addProperty("deleted",
                        "native_libs/" + abi + "/" + fileName);
                result.addProperty("verified",
                        "Deleted native_libs/" + abi + "/" + fileName + ".");
                return result;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_DELETE_NATIVE_LIB_FAILED",
                        "Deleting native_libs/" + abi + "/" + fileName
                                + " failed: " + String.valueOf(e.getMessage()));
            }
        }
    }

    // =====================================================================
    // Phase 6: Custom Blocks, Custom Components/Events & XML Command Blocks
    // =====================================================================

    /**
     * list_custom_definitions: inspects Custom Blocks, Custom Components,
     * Custom Events/Listeners, and the working project's XML Command rules.
     */
    private static final class ListCustomDefinitions implements Tool {
        @Override
        public String name() {
            return "list_custom_definitions";
        }

        @Override
        public String description() {
            return "Lists user Custom Blocks (My Block/block.json and "
                    + "project custom_blocks), Custom Components "
                    + "(component.json), Custom Events and Listeners "
                    + "(events.json and listeners.json), and, when a "
                    + "working project is active, its XML Command rules "
                    + "and available XML targets.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Listed custom blocks, components, events & XML commands";
        }

        @Override
        public JsonObject run(Session session, JsonObject args) {
            String scId = session.workingProjectId == null
                    ? "" : session.workingProjectId.trim();
            JsonObject result = new JsonObject();
            result.add("custom_blocks",
                    AgentExtensibility.listCustomBlocksJson(scId));
            result.add("custom_components",
                    AgentExtensibility.listCustomComponentsJson());
            result.add("custom_events_and_listeners",
                    AgentExtensibility.listCustomEventsAndListenersJson());
            if (!scId.isEmpty()) {
                result.add("xml_commands",
                        AgentExtensibility.listXmlCommandsJson(scId));
            }
            return result;
        }
    }

    /**
     * manage_custom_block: creates, updates, or deletes a reusable Custom
     * Block in My Block/block.json (and pins to the working project).
     */
    private static final class ManageCustomBlock implements Tool {
        @Override
        public String name() {
            return "manage_custom_block";
        }

        @Override
        public String description() {
            return "Proposes creating, updating, or deleting a reusable "
                    + "Sketchware Pro Custom Block (My Block/block.json "
                    + "and project custom_blocks). Once created, invoke it "
                    + "in set_event_handler with {\"block\":\"<name>\","
                    + "\"args\":[...]} or in expressions with "
                    + "{\"custom_block\":\"<name>\",\"args\":[...]}.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("action", stringSchema(
                    "Operation: upsert (default) or delete"));
            properties.add("name", stringSchema(
                    "Unique custom block identifier, e.g. logDebug"));
            properties.add("type", stringSchema(
                    "Block shape: \" \" (statement, default), \"s\" "
                            + "(String), \"d\" (number), \"b\" (boolean), "
                            + "\"a\" (Map), \"v\" (View), \"c\" (container "
                            + "with blocks), \"e\" (if/else container), "
                            + "or \"f\" (terminator)"));
            properties.add("typeName", stringSchema(
                    "Optional typeName for %m.* or custom types"));
            properties.add("spec", stringSchema(
                    "Block spec with %s, %d, %b, or %m.* slots, e.g. "
                            + "\"log tag %s message %s\""));
            properties.add("spec2", stringSchema(
                    "Optional second header spec for \"e\" (if/else) "
                            + "blocks, e.g. \"else\""));
            properties.add("code", stringSchema(
                    "Java code template formatted with String.format "
                            + "(e.g. \"android.util.Log.d(%1$s, %2$s);\")"));
            properties.add("imports", stringSchema(
                    "Optional newline-separated Java import statements"));
            properties.add("color", stringSchema(
                    "Optional hex color, e.g. #8A55D7"));
            return AgentTools.parameters(properties, "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String act = stringArg(args, "action");
            String nm = stringArg(args, "name");
            return "delete".equalsIgnoreCase(act)
                    ? "Wants to delete custom block " + nm
                    : "Wants to save custom block " + nm;
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = session.workingProjectId == null
                    ? "" : session.workingProjectId.trim();
            String action = stringArg(args, "action").trim().toLowerCase();
            if (action.isEmpty()) action = "upsert";
            String nm = stringArg(args, "name").trim();
            if ("delete".equals(action)) {
                if (AgentExtensibility.findCustomBlock(scId, nm) == null) {
                    throw new ToolException("E_BLOCK_NOT_FOUND",
                            "Custom block \"" + nm + "\" does not exist.");
                }
                JsonObject clean = new JsonObject();
                clean.addProperty("action", "delete");
                clean.addProperty("name", nm);
                return new AgentProposal(name(), clean, scId,
                        "Delete custom block " + nm,
                        "Remove custom block \"" + nm + "\" from My Block.",
                        "Existing events not using it are unaffected.");
            }
            String type = args != null && args.has("type")
                    && !args.get("type").isJsonNull()
                    ? args.get("type").getAsString() : " ";
            String spec = stringArg(args, "spec");
            String code = stringArg(args, "code");
            String color = stringArg(args, "color");
            AgentExtensibility.validateCustomBlock(
                    nm, type, spec, code, color);
            JsonObject clean = new JsonObject();
            clean.addProperty("action", "upsert");
            clean.addProperty("name", nm);
            clean.addProperty("type", type);
            clean.addProperty("typeName", stringArg(args, "typeName"));
            clean.addProperty("spec", spec);
            clean.addProperty("spec2", stringArg(args, "spec2"));
            clean.addProperty("code", code);
            clean.addProperty("imports", stringArg(args, "imports"));
            clean.addProperty("color", color);
            return new AgentProposal(name(), clean, scId,
                    "Save custom block " + nm,
                    "Register custom block \"" + nm + "\" (" + spec + ").",
                    code);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = proposal.workingProjectId == null
                    ? "" : proposal.workingProjectId.trim();
            JsonObject a = proposal.args;
            String action = stringArg(a, "action");
            String nm = stringArg(a, "name");
            try {
                if ("delete".equals(action)) {
                    AgentExtensibility.deleteCustomBlock(scId, nm);
                    JsonObject res = new JsonObject();
                    res.addProperty("deleted", nm);
                    res.addProperty("verified",
                            "Deleted custom block " + nm + ".");
                    return res;
                }
                String type = a.has("type") && !a.get("type").isJsonNull()
                        ? a.get("type").getAsString() : " ";
                AgentExtensibility.CustomBlockDef saved =
                        AgentExtensibility.upsertCustomBlock(
                                scId,
                                nm,
                                type,
                                stringArg(a, "typeName"),
                                stringArg(a, "spec"),
                                stringArg(a, "spec2"),
                                stringArg(a, "code"),
                                stringArg(a, "imports"),
                                stringArg(a, "color"),
                                "9",
                                !scId.isEmpty());
                JsonObject res = new JsonObject();
                res.addProperty("name", saved.name);
                res.addProperty("type", saved.type);
                res.addProperty("spec", saved.spec);
                res.addProperty("verified",
                        "Saved and loaded custom block \"" + saved.name
                                + "\".");
                return res;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_CUSTOM_BLOCK_FAILED",
                        "Custom block operation failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /**
     * manage_custom_component_or_event: creates, updates, or deletes a
     * Custom Component (component.json) or Custom Event/Listener
     * (events.json and listeners.json).
     */
    private static final class ManageCustomComponentOrEvent implements Tool {
        @Override
        public String name() {
            return "manage_custom_component_or_event";
        }

        @Override
        public String description() {
            return "Proposes creating, updating, or deleting a Sketchware "
                    + "Pro Custom Component (kind=\"component\") or Custom "
                    + "Event + Listener (kind=\"event\") and refreshes "
                    + "ComponentsHandler / EventsHandler caches immediately.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("kind", stringSchema(
                    "Target kind: component or event"));
            properties.add("action", stringSchema(
                    "Operation: upsert (default) or delete"));
            properties.add("name", stringSchema(
                    "Display name (for component) or event method name "
                            + "(for event, e.g. onKeyDown)"));
            properties.add("typeName", stringSchema(
                    "For kind=component: Java/Sketchware type identifier "
                            + "(e.g. ExoPlayerComponent)"));
            properties.add("varName", stringSchema(
                    "For kind=component: default variable prefix"));
            properties.add("buildClass", stringSchema(
                    "For kind=component: Java field type emitted in "
                            + "Activities"));
            properties.add("imports", stringSchema(
                    "Newline-separated Java imports"));
            properties.add("additionalVar", stringSchema(
                    "For kind=component: optional extra field declaration "
                            + "template (uses ### or $name)"));
            properties.add("defineAdditionalVar", stringSchema(
                    "For kind=component: optional initialize() statement "
                            + "template (uses ### or $name)"));
            properties.add("target_class", stringSchema(
                    "For kind=event: empty string \"\" for Activity events, "
                            + "or widget/component class name"));
            properties.add("listener_name", stringSchema(
                    "For kind=event on a widget/component: listener name"));
            properties.add("event_parameters", stringSchema(
                    "For kind=event: slot tokens like \"%d.keyCode\""));
            properties.add("header_spec", stringSchema(
                    "For kind=event: header spec like "
                            + "\"onKeyDown %d.keyCode\""));
            properties.add("code", stringSchema(
                    "For kind=event: Java method template containing %s "
                            + "for the wired block body"));
            properties.add("listener_code", stringSchema(
                    "For kind=event with listener_name: Java listener "
                            + "registration template containing %s"));
            properties.add("description", stringSchema(
                    "Optional human-readable description"));
            return AgentTools.parameters(properties, "kind", "name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to update custom " + stringArg(args, "kind")
                    + " " + stringArg(args, "name");
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = session.workingProjectId == null
                    ? "" : session.workingProjectId.trim();
            String kind = stringArg(args, "kind").trim().toLowerCase();
            if (!"component".equals(kind) && !"event".equals(kind)) {
                throw new ToolException("E_BAD_KIND",
                        "Pass kind=\"component\" or kind=\"event\".");
            }
            String action = stringArg(args, "action").trim().toLowerCase();
            if (action.isEmpty()) action = "upsert";
            String nm = stringArg(args, "name").trim();
            if ("component".equals(kind) && "upsert".equals(action)) {
                String tn = stringArg(args, "typeName").trim();
                if (tn.isEmpty()) tn = nm;
                if (tn.isEmpty()) {
                    throw new ToolException("E_BAD_COMPONENT_TYPE",
                            "Provide component \"typeName\" or \"name\".");
                }
            } else if ("event".equals(kind) && "upsert".equals(action)) {
                String code = stringArg(args, "code");
                if (!code.contains("%s")) {
                    throw new ToolException("E_BAD_EVENT_CODE",
                            "Custom event \"code\" must contain \"%s\" "
                                    + "where the event's blocks go.");
                }
            }
            JsonObject clean = args.deepCopy();
            clean.addProperty("kind", kind);
            clean.addProperty("action", action);
            return new AgentProposal(name(), clean, scId,
                    ("delete".equals(action) ? "Delete " : "Save ")
                            + "custom " + kind + " " + nm,
                    ("delete".equals(action) ? "Remove " : "Register ")
                            + "custom " + kind + " \"" + nm + "\".",
                    stringArg(args, "code"));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            JsonObject a = proposal.args;
            String kind = stringArg(a, "kind");
            String action = stringArg(a, "action");
            String nm = stringArg(a, "name");
            JsonObject res = new JsonObject();
            if ("component".equals(kind)) {
                if ("delete".equals(action)) {
                    String q = stringArg(a, "typeName").isEmpty()
                            ? nm : stringArg(a, "typeName");
                    AgentExtensibility.deleteCustomComponent(q);
                    res.addProperty("deleted_component", q);
                    res.addProperty("verified",
                            "Deleted custom component " + q + ".");
                    return res;
                }
                String tn = stringArg(a, "typeName").isEmpty()
                        ? nm : stringArg(a, "typeName");
                AgentExtensibility.CustomComponentDef saved =
                        AgentExtensibility.upsertCustomComponent(
                                nm, tn, -1,
                                stringArg(a, "varName"),
                                stringArg(a, "buildClass"),
                                tn,
                                stringArg(a, "description"),
                                stringArg(a, "imports"),
                                stringArg(a, "additionalVar"),
                                stringArg(a, "defineAdditionalVar"));
                res.addProperty("id", saved.id);
                res.addProperty("typeName", saved.typeName);
                res.addProperty("verified",
                        "Registered custom component " + saved.typeName
                                + " (id=" + saved.id + ").");
                return res;
            }
            if ("delete".equals(action)) {
                AgentExtensibility.deleteCustomEvent(nm);
                res.addProperty("deleted_event", nm);
                res.addProperty("verified",
                        "Deleted custom event " + nm + ".");
                return res;
            }
            AgentExtensibility.upsertCustomEvent(
                    nm,
                    stringArg(a, "target_class"),
                    stringArg(a, "listener_name"),
                    stringArg(a, "event_parameters"),
                    stringArg(a, "header_spec"),
                    stringArg(a, "description"),
                    stringArg(a, "code"),
                    stringArg(a, "listener_code"),
                    stringArg(a, "imports"));
            res.addProperty("event", nm);
            res.addProperty("verified",
                    "Registered custom event \"" + nm + "\".");
            return res;
        }
    }

    /**
     * manage_xml_commands: manages compile-time XML transformation rules
     * (.sketchware/data/<scId>/command + SETTING_NEW_XML_COMMAND).
     */
    private static final class ManageXmlCommands implements Tool {
        @Override
        public String name() {
            return "manage_xml_commands";
        }

        @Override
        public String description() {
            return "Proposes adding, updating, deleting, or clearing "
                    + "Sketchware Pro XML Command rules on generated XML "
                    + "files (AndroidManifest.xml, layout XMLs, styles.xml, "
                    + "colors.xml, strings.xml, xml/provider_paths.xml) and "
                    + "automatically enables SETTING_NEW_XML_COMMAND.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("action", stringSchema(
                    "Operation: add (default), update, delete, or clear"));
            properties.add("index", intSchema(
                    "0-based command index (required for update and "
                            + "delete)"));
            properties.add("xml_name", stringSchema(
                    "Target XML filename, e.g. AndroidManifest.xml, "
                            + "main.xml, or styles.xml"));
            properties.add("command", stringSchema(
                    "Patch command: insert, add, replace, find-replace, "
                            + "find-replace-first, or find-replace-all"));
            properties.add("reference", stringSchema(
                    "Anchor line or text/regex to match in target XML"));
            properties.add("changes", stringSchema(
                    "XML text to insert, add, or replace with"));
            properties.add("distance", intSchema(
                    "Optional line offset from reference (default 0)"));
            properties.add("before", intSchema(
                    "Optional lines before anchor (backend, default 0)"));
            properties.add("after", intSchema(
                    "Optional lines after anchor (frontend, default 0)"));
            return AgentTools.parameters(properties, "action");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String act = stringArg(args, "action");
            String xml = stringArg(args, "xml_name");
            return xml.isEmpty()
                    ? "Wants to " + act + " XML command rules"
                    : "Wants to patch " + xml + " via XML Command";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String action = stringArg(args, "action").trim().toLowerCase();
            if (action.isEmpty()) action = "add";
            if (!"add".equals(action) && !"update".equals(action)
                    && !"delete".equals(action)
                    && !"clear".equals(action)) {
                throw new ToolException("E_BAD_ACTION",
                        "Use action: add, update, delete, or clear.");
            }
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(scId);
            JsonObject clean = new JsonObject();
            clean.addProperty("action", action);
            if ("clear".equals(action)) {
                return new AgentProposal(name(), clean, scId,
                        "Clear XML commands in " + project.name,
                        "Remove all XML Command rules in " + project.name
                                + ".",
                        "A project backup is saved first.");
            }
            int idx = intArg(args, "index", -1);
            if ("delete".equals(action)) {
                if (idx < 0) {
                    throw new ToolException("E_MISSING_INDEX",
                            "Pass 0-based \"index\" to delete.");
                }
                clean.addProperty("index", idx);
                return new AgentProposal(name(), clean, scId,
                        "Delete XML command #" + idx,
                        "Remove XML Command #" + idx + " from "
                                + project.name + ".",
                        "A project backup is saved first.");
            }
            if ("update".equals(action) && idx < 0) {
                throw new ToolException("E_MISSING_INDEX",
                        "Pass 0-based \"index\" to update.");
            }
            String xmlName = stringArg(args, "xml_name").trim();
            if (xmlName.isEmpty()) {
                throw new ToolException("E_BAD_XML_TARGET",
                        "Pass \"xml_name\" (e.g. AndroidManifest.xml or "
                                + "main.xml).");
            }
            String cmd = AgentExtensibility.normalizeCommandAction(
                    stringArg(args, "command").isEmpty()
                            ? "add" : stringArg(args, "command"));
            String ref = stringArg(args, "reference");
            if (ref.isEmpty()) {
                throw new ToolException("E_BAD_COMMAND_REFERENCE",
                        "Pass non-empty \"reference\" to match in "
                                + xmlName + ".");
            }
            String changes = args != null && args.has("changes")
                    && !args.get("changes").isJsonNull()
                    ? args.get("changes").getAsString() : "";
            clean.addProperty("index", "update".equals(action) ? idx : -1);
            clean.addProperty("xml_name", xmlName);
            clean.addProperty("command", cmd);
            clean.addProperty("reference", ref);
            clean.addProperty("changes", changes);
            clean.addProperty("distance", intArg(args, "distance", 0));
            clean.addProperty("before", intArg(args, "before", 0));
            clean.addProperty("after", intArg(args, "after", 0));
            return new AgentProposal(name(), clean, scId,
                    ("update".equals(action) ? "Update " : "Add ")
                            + "XML command on " + xmlName,
                    cmd + " around \"" + ref + "\" in " + xmlName + " ("
                            + project.name + ").",
                    changes);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            String action = stringArg(a, "action");
            try {
                JsonObject res = new JsonObject();
                if ("clear".equals(action)) {
                    AgentExtensibility.clearXmlCommands(scId);
                    res.addProperty("cleared", true);
                    res.addProperty("verified",
                            "Cleared all XML commands.");
                    return res;
                }
                if ("delete".equals(action)) {
                    int idx = intArg(a, "index", -1);
                    AgentExtensibility.deleteXmlCommand(scId, idx);
                    res.addProperty("deleted_index", idx);
                    res.addProperty("verified",
                            "Deleted XML command #" + idx + ".");
                    return res;
                }
                int savedIdx = AgentExtensibility.upsertXmlCommand(
                        scId,
                        intArg(a, "index", -1),
                        stringArg(a, "xml_name"),
                        stringArg(a, "command"),
                        stringArg(a, "reference"),
                        stringArg(a, "changes"),
                        intArg(a, "distance", 0),
                        intArg(a, "before", 0),
                        intArg(a, "after", 0));
                res.addProperty("index", savedIdx);
                res.addProperty("xml_name", stringArg(a, "xml_name"));
                res.addProperty("command", stringArg(a, "command"));
                res.addProperty("verified",
                        "Saved XML command #" + savedIdx
                                + " and enabled XML Command compilation.");
                return res;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_XML_COMMAND_FAILED",
                        "XML command operation failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 7: AndroidManifest, AppCompat Injection, ProjectSettings,
    //          ProGuard/R8 & StringFog
    // ------------------------------------------------------------------

    /** inspect_manifest_and_settings: read-only Phase 7 state dump. */
    private static final class InspectManifestAndSettings implements Tool {
        @Override
        public String name() {
            return "inspect_manifest_and_settings";
        }

        @Override
        public String description() {
            return "Reads the project's AndroidManifest configuration "
                    + "(launcher activity, <application> attributes, "
                    + "<activity> attributes, <intent-filter> deep links, "
                    + "and <application> XML components), per-screen "
                    + "AppCompat XML attribute injections, project metadata "
                    + "(project_name, app_name, package_name, version_code, "
                    + "version_name), Pro ProjectSettings (min_sdk, "
                    + "target_sdk, application_class, ViewBinding, "
                    + "bridgeless themes, disable_old_methods), ProGuard/R8 "
                    + "rules, and StringFog status.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Inspected AndroidManifest, AppCompat & project settings";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            return AgentManifestAndSettings.inspectAll(scId);
        }
    }

    /** manage_manifest: launcher override, attrs, intent-filters & components. */
    private static final class ManageManifest implements Tool {
        @Override
        public String name() {
            return "manage_manifest";
        }

        @Override
        public String description() {
            return "Configures AndroidManifest.xml via Sketchware Pro's "
                    + "AndroidManifestInjector: change launcher_activity "
                    + "(e.g. SplashActivity), inject <application> "
                    + "attributes (e.g. android:usesCleartextTraffic=\"true\"), "
                    + "inject attributes on one or all <activity> tags "
                    + "(e.g. android:launchMode=\"singleTop\", "
                    + "android:exported=\"true\", android:configChanges="
                    + "\"orientation|screenSize\"), inject <intent-filter> "
                    + "deep links or share targets inside an <activity> "
                    + "(activity_components_xml), or inject <meta-data>, "
                    + "<provider>, <service>, or <receiver> XML inside "
                    + "<application> (app_components_xml).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("launcher_activity", stringSchema(
                    "Optional visual Activity screen to launch on app start "
                            + "(e.g. SplashActivity or MainActivity)."));
            p.add("application_attributes", stringArraySchema(
                    "Optional <application> attribute pairs like "
                            + "[\"android:usesCleartextTraffic=\\\"true\\\"\", "
                            + "\"android:largeHeap=\\\"true\\\"\"]."));
            p.add("replace_application_attributes", booleanSchema(
                    "When true, replaces existing <application> attributes "
                            + "instead of merging by attribute key."));
            p.add("all_activities_attributes", stringArraySchema(
                    "Optional attribute pairs applied to every <activity>."));
            p.add("replace_all_activities_attributes", booleanSchema(
                    "When true, replaces existing all-activities attributes."));
            p.add("activity", stringSchema(
                    "Target Activity screen (e.g. MainActivity) when setting "
                            + "activity_attributes or activity_components_xml."));
            p.add("activity_attributes", stringArraySchema(
                    "Optional attribute pairs for \"activity\" (e.g. "
                            + "[\"android:launchMode=\\\"singleTop\\\"\", "
                            + "\"android:exported=\\\"true\\\"\"])."));
            p.add("replace_activity_attributes", booleanSchema(
                    "When true, replaces existing attributes on \"activity\"."));
            p.add("activity_components_xml", stringSchema(
                    "Optional child XML placed inside <activity> (such as "
                            + "<intent-filter> deep links or <meta-data/>). "
                            + "Pass empty string \"\" to clear."));
            p.add("app_components_xml", stringSchema(
                    "Optional child XML placed inside <application> (such as "
                            + "<provider>, <meta-data>, <service>, or "
                            + "<receiver>)."));
            p.add("replace_app_components_xml", booleanSchema(
                    "When true, replaces app_components.txt instead of "
                            + "appending."));
            return AgentTools.parameters(p);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to update AndroidManifest configuration";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String launcher = stringArg(args, "launcher_activity");
            List<String> appAttrs = AgentManifestAndSettings
                    .jsonArrayStrings(args, "application_attributes");
            List<String> allActAttrs = AgentManifestAndSettings
                    .jsonArrayStrings(args, "all_activities_attributes");
            String act = stringArg(args, "activity");
            List<String> actAttrs = AgentManifestAndSettings
                    .jsonArrayStrings(args, "activity_attributes");
            String actXml = args != null && args.has("activity_components_xml")
                    && !args.get("activity_components_xml").isJsonNull()
                    ? args.get("activity_components_xml").getAsString() : null;
            String appXml = args != null && args.has("app_components_xml")
                    && !args.get("app_components_xml").isJsonNull()
                    ? args.get("app_components_xml").getAsString() : null;

            if (launcher.isEmpty() && appAttrs == null && allActAttrs == null
                    && actAttrs == null && actXml == null && appXml == null) {
                throw new ToolException("E_NOTHING_TO_CHANGE",
                        "Provide at least one manifest setting to update "
                                + "(launcher_activity, application_attributes, "
                                + "activity_attributes, activity_components_xml, "
                                + "or app_components_xml).");
            }
            if (!launcher.isEmpty()) {
                ProjectFileBean bean =
                        SketchwareProjects.findActivity(scId, launcher);
                if (bean == null || SketchwareProjects.isFragment(bean)) {
                    throw new ToolException("E_BAD_LAUNCHER_ACTIVITY",
                            "Launcher activity \"" + launcher
                                    + "\" must be an existing visual Activity.");
                }
            }
            if (appAttrs != null) {
                for (String s : appAttrs) {
                    AgentManifestAndSettings.validateAttributePair(s);
                }
            }
            if (allActAttrs != null) {
                for (String s : allActAttrs) {
                    AgentManifestAndSettings.validateAttributePair(s);
                }
            }
            if (actAttrs != null) {
                if (act.isEmpty()) {
                    throw new ToolException("E_MISSING_ACTIVITY",
                            "Pass \"activity\" alongside activity_attributes.");
                }
                for (String s : actAttrs) {
                    AgentManifestAndSettings.validateAttributePair(s);
                }
            }
            if (actXml != null) {
                if (act.isEmpty()) {
                    throw new ToolException("E_MISSING_ACTIVITY",
                            "Pass \"activity\" alongside "
                                    + "activity_components_xml.");
                }
                AgentManifestAndSettings.validateXmlSnippet(
                        actXml, "activity_components_xml");
            }
            if (appXml != null) {
                AgentManifestAndSettings.validateXmlSnippet(
                        appXml, "app_components_xml");
            }

            List<String> details = new ArrayList<>();
            if (!launcher.isEmpty()) {
                details.add("Launcher activity -> " + launcher);
            }
            if (appAttrs != null) {
                details.add("<application> attributes: " + appAttrs.size());
            }
            if (allActAttrs != null) {
                details.add("All-activities attributes: " + allActAttrs.size());
            }
            if (actAttrs != null) {
                details.add(act + " attributes: " + actAttrs.size());
            }
            if (actXml != null) {
                details.add(act + " child XML updated");
            }
            if (appXml != null) {
                details.add("<application> child XML updated");
            }
            JsonObject clean = args == null ? new JsonObject() : args.deepCopy();
            return new AgentProposal(name(), clean, scId,
                    "Update AndroidManifest",
                    String.join(" · ", details),
                    String.join("\n", details));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            String actXml = a != null && a.has("activity_components_xml")
                    && !a.get("activity_components_xml").isJsonNull()
                    ? a.get("activity_components_xml").getAsString() : null;
            String appXml = a != null && a.has("app_components_xml")
                    && !a.get("app_components_xml").isJsonNull()
                    ? a.get("app_components_xml").getAsString() : null;
            try {
                JsonObject res = AgentManifestAndSettings.applyManifestChanges(
                        scId,
                        stringArg(a, "launcher_activity"),
                        AgentManifestAndSettings.jsonArrayStrings(
                                a, "application_attributes"),
                        booleanArg(a, "replace_application_attributes"),
                        AgentManifestAndSettings.jsonArrayStrings(
                                a, "all_activities_attributes"),
                        booleanArg(a, "replace_all_activities_attributes"),
                        stringArg(a, "activity"),
                        AgentManifestAndSettings.jsonArrayStrings(
                                a, "activity_attributes"),
                        booleanArg(a, "replace_activity_attributes"),
                        actXml,
                        appXml,
                        booleanArg(a, "replace_app_components_xml"));
                res.addProperty("verified",
                        "Updated AndroidManifest injection settings.");
                return res;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_MANIFEST_UPDATE_FAILED",
                        "Failed to update AndroidManifest: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** manage_appcompat_injection: toolbar/appbar/coordinator/drawer XML attrs. */
    private static final class ManageAppCompatInjection implements Tool {
        @Override
        public String name() {
            return "manage_appcompat_injection";
        }

        @Override
        public String description() {
            return "Customizes or resets AppCompat shell XML attributes on "
                    + "an Activity screen's generated Toolbar, AppBarLayout, "
                    + "CoordinatorLayout, DrawerLayout, or NavigationDrawer "
                    + "(stored in injection/appcompat/<fileName>).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("activity", stringSchema(
                    "Target Activity screen (e.g. MainActivity)."));
            p.add("type", stringSchema(
                    "AppCompat shell target: \"toolbar\", \"appbarlayout\", "
                            + "\"coordinatorlayout\", \"drawerlayout\", or "
                            + "\"navigationdrawer\"."));
            p.add("attributes", stringArraySchema(
                    "XML attribute pairs like [\"app:elevation=\\\"0dp\\\"\", "
                            + "\"android:background=\\\"?attr/colorSurface\\\"\"]"));
            p.add("replace_type", booleanSchema(
                    "When true, replaces all existing attributes for \"type\" "
                            + "on this Activity instead of merging by key."));
            p.add("reset", booleanSchema(
                    "When true, restores Sketchware Pro's default AppCompat "
                            + "injections for this Activity."));
            return AgentTools.parameters(p, "activity");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String act = stringArg(args, "activity");
            return act.isEmpty()
                    ? "Wants to update AppCompat XML injection"
                    : "Wants to update AppCompat injection on " + act;
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            String act = stringArg(args, "activity");
            if (SketchwareProjects.findActivity(scId, act) == null) {
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "Activity \"" + act + "\" does not exist.");
            }
            boolean reset = booleanArg(args, "reset");
            String type = stringArg(args, "type");
            List<String> attrs = AgentManifestAndSettings
                    .jsonArrayStrings(args, "attributes");
            if (!reset) {
                if (type.isEmpty()) {
                    throw new ToolException("E_MISSING_TYPE",
                            "Pass \"type\" (toolbar, appbarlayout, "
                                    + "coordinatorlayout, drawerlayout, or "
                                    + "navigationdrawer) or reset=true.");
                }
                if (attrs == null || attrs.isEmpty()) {
                    throw new ToolException("E_EMPTY_ATTRIBUTES",
                            "Pass \"attributes\" array or reset=true.");
                }
                for (String a : attrs) {
                    AgentManifestAndSettings.validateAttributePair(a);
                }
            }
            String title = reset
                    ? "Reset AppCompat injection on " + act
                    : "Update " + type + " injection on " + act;
            List<String> details = new ArrayList<>();
            if (reset) {
                details.add("Restore default AppCompat shell XML attributes");
            } else {
                details.addAll(attrs);
            }
            JsonObject clean = args == null ? new JsonObject() : args.deepCopy();
            return new AgentProposal(name(), clean, scId, title,
                    reset ? "Restore defaults" : attrs.size() + " attributes",
                    String.join("\n", details));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            try {
                JsonObject res = AgentManifestAndSettings
                        .updateAppCompatInjection(
                                scId,
                                stringArg(a, "activity"),
                                booleanArg(a, "reset"),
                                stringArg(a, "type"),
                                AgentManifestAndSettings.jsonArrayStrings(
                                        a, "attributes"),
                                booleanArg(a, "replace_type"));
                res.addProperty("verified",
                        "Updated AppCompat XML injection and refreshed cache.");
                return res;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_APPCOMPAT_INJECTION_FAILED",
                        "Failed to update AppCompat injection: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** edit_project_settings: metadata + Pro ProjectSettings. */
    private static final class EditProjectSettings implements Tool {
        @Override
        public String name() {
            return "edit_project_settings";
        }

        @Override
        public String description() {
            return "Updates project metadata (project_name, app_name, "
                    + "package_name, version_code, version_name) and "
                    + "Sketchware Pro ProjectSettings (min_sdk, target_sdk, "
                    + "application_class, enable_viewbinding, "
                    + "enable_bridgeless_themes, disable_old_methods).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("project_name", stringSchema(
                    "Optional workspace title shown inside Sketchware Pro."));
            p.add("app_name", stringSchema(
                    "Optional user-visible Android application label "
                            + "(also synced to @string/app_name)."));
            p.add("package_name", stringSchema(
                    "Optional Android applicationId / package name "
                            + "(e.g. com.acme.notes)."));
            p.add("version_code", intSchema(
                    "Optional integer Android versionCode (>= 1)."));
            p.add("version_name", stringSchema(
                    "Optional Android versionName string (e.g. \"1.2.0\")."));
            p.add("min_sdk", intSchema(
                    "Optional minimum Android SDK level (21..36)."));
            p.add("target_sdk", intSchema(
                    "Optional target Android SDK level (23..37)."));
            p.add("application_class", stringSchema(
                    "Optional android:name class for <application> "
                            + "(default \".SketchApplication\")."));
            p.add("enable_viewbinding", booleanSchema(
                    "Optional toggle for Android ViewBinding generation."));
            p.add("enable_bridgeless_themes", booleanSchema(
                    "Optional toggle for pure MaterialComponents bridgeless "
                            + "themes."));
            p.add("disable_old_methods", booleanSchema(
                    "Optional toggle to omit deprecated legacy helper "
                            + "methods in generated Activities."));
            return AgentTools.parameters(p);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to update project metadata & settings";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            List<String> details = new ArrayList<>();
            if (!stringArg(args, "project_name").isEmpty()) {
                details.add("Project name -> "
                        + stringArg(args, "project_name"));
            }
            if (!stringArg(args, "app_name").isEmpty()) {
                details.add("App name -> " + stringArg(args, "app_name"));
            }
            if (!stringArg(args, "package_name").isEmpty()) {
                details.add("Package -> " + stringArg(args, "package_name"));
            }
            Integer vc = optionalIntArg(args, "version_code");
            if (vc != null) details.add("versionCode -> " + vc);
            if (!stringArg(args, "version_name").isEmpty()) {
                details.add("versionName -> "
                        + stringArg(args, "version_name"));
            }
            Integer minSdk = optionalIntArg(args, "min_sdk");
            if (minSdk != null) details.add("minSdk -> " + minSdk);
            Integer targetSdk = optionalIntArg(args, "target_sdk");
            if (targetSdk != null) details.add("targetSdk -> " + targetSdk);
            if (!stringArg(args, "application_class").isEmpty()) {
                details.add("Application class -> "
                        + stringArg(args, "application_class"));
            }
            Boolean vb = optionalBooleanArg(args, "enable_viewbinding");
            if (vb != null) details.add("ViewBinding -> " + vb);
            Boolean bt = optionalBooleanArg(args, "enable_bridgeless_themes");
            if (bt != null) details.add("Bridgeless themes -> " + bt);
            Boolean dom = optionalBooleanArg(args, "disable_old_methods");
            if (dom != null) details.add("Disable old methods -> " + dom);

            if (details.isEmpty()) {
                throw new ToolException("E_NOTHING_TO_CHANGE",
                        "Provide at least one metadata or ProjectSettings "
                                + "field to update.");
            }
            JsonObject clean = args == null ? new JsonObject() : args.deepCopy();
            return new AgentProposal(name(), clean, scId,
                    "Update project settings",
                    String.join(" · ", details),
                    String.join("\n", details));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            try {
                JsonObject res = AgentManifestAndSettings
                        .updateProjectSettings(
                                scId,
                                emptyToNull(stringArg(a, "project_name")),
                                emptyToNull(stringArg(a, "app_name")),
                                emptyToNull(stringArg(a, "package_name")),
                                optionalIntArg(a, "version_code"),
                                emptyToNull(stringArg(a, "version_name")),
                                optionalIntArg(a, "min_sdk"),
                                optionalIntArg(a, "target_sdk"),
                                emptyToNull(stringArg(a, "application_class")),
                                optionalBooleanArg(a, "enable_viewbinding"),
                                optionalBooleanArg(
                                        a, "enable_bridgeless_themes"),
                                optionalBooleanArg(
                                        a, "disable_old_methods"));
                res.addProperty("verified",
                        "Saved project metadata and ProjectSettings.");
                return res;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_PROJECT_SETTINGS_FAILED",
                        "Failed to update project settings: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** configure_proguard_and_stringfog: shrinking, R8, rules & StringFog. */
    private static final class ConfigureProguardAndStringFog implements Tool {
        @Override
        public String name() {
            return "configure_proguard_and_stringfog";
        }

        @Override
        public String description() {
            return "Configures ProGuard / R8 code shrinking, custom "
                    + "proguard-rules.pro rules, per-local-library full "
                    + "mode shrinking, debug mapping output, and StringFog "
                    + "compile-time string encryption.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("proguard_enabled", booleanSchema(
                    "Enable or disable code shrinking (ProGuard/R8)."));
            p.add("r8_enabled", booleanSchema(
                    "When shrinking is enabled, use R8 instead of ProGuard."));
            p.add("debug_files_enabled", booleanSchema(
                    "Emit seeds.txt, usage.txt, and mapping.txt during "
                            + "shrinking."));
            p.add("proguard_rules", stringSchema(
                    "Custom proguard-rules.pro content (e.g. -keep rules)."));
            p.add("full_mode_local_libs", stringArraySchema(
                    "Optional list of local library names whose classes "
                            + "should also be shrunk in full mode."));
            p.add("stringfog_enabled", booleanSchema(
                    "Enable or disable StringFog string encryption."));
            return AgentTools.parameters(p);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to configure ProGuard/R8 & StringFog";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            Boolean pg = optionalBooleanArg(args, "proguard_enabled");
            Boolean r8 = optionalBooleanArg(args, "r8_enabled");
            Boolean dbg = optionalBooleanArg(args, "debug_files_enabled");
            String rules = args != null && args.has("proguard_rules")
                    && !args.get("proguard_rules").isJsonNull()
                    ? args.get("proguard_rules").getAsString() : null;
            List<String> fm = AgentManifestAndSettings
                    .jsonArrayStrings(args, "full_mode_local_libs");
            Boolean sf = optionalBooleanArg(args, "stringfog_enabled");

            List<String> details = new ArrayList<>();
            if (pg != null) details.add("Shrinking -> " + pg);
            if (r8 != null) details.add("R8 mode -> " + r8);
            if (dbg != null) details.add("Debug mapping files -> " + dbg);
            if (rules != null) {
                details.add("Custom proguard-rules.pro ("
                        + rules.length() + " chars)");
            }
            if (fm != null) {
                details.add("Full-mode local libs: " + fm.size());
            }
            if (sf != null) details.add("StringFog -> " + sf);

            if (details.isEmpty()) {
                throw new ToolException("E_NOTHING_TO_CHANGE",
                        "Provide at least one ProGuard/R8 or StringFog "
                                + "option to configure.");
            }
            JsonObject clean = args == null ? new JsonObject() : args.deepCopy();
            return new AgentProposal(name(), clean, scId,
                    "Configure ProGuard / R8 & StringFog",
                    String.join(" · ", details),
                    String.join("\n", details));
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            String rules = a != null && a.has("proguard_rules")
                    && !a.get("proguard_rules").isJsonNull()
                    ? a.get("proguard_rules").getAsString() : null;
            try {
                JsonObject res = AgentManifestAndSettings
                        .configureShrinkingAndObfuscation(
                                scId,
                                optionalBooleanArg(a, "proguard_enabled"),
                                optionalBooleanArg(a, "r8_enabled"),
                                optionalBooleanArg(a, "debug_files_enabled"),
                                rules,
                                AgentManifestAndSettings.jsonArrayStrings(
                                        a, "full_mode_local_libs"),
                                optionalBooleanArg(a, "stringfog_enabled"));
                res.addProperty("verified",
                        "Saved ProGuard/R8 and StringFog configuration.");
                return res;
            } catch (Exception e) {
                throw new ToolException("E_SHRINKING_CONFIG_FAILED",
                        "Failed to configure ProGuard/R8 or StringFog: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Phase 8: Source Inspector, Runtime Crash Logcat Reader,
    //          Collections Manager & Release Packaging (.aab / APK / .swb)
    // ------------------------------------------------------------------

    /** inspect_generated_source: read-only SrcViewerActivity source dump. */
    private static final class InspectGeneratedSource implements Tool {
        @Override
        public String name() {
            return "inspect_generated_source";
        }

        @Override
        public String description() {
            return "Generates and reads the exact Java, layout XML, and "
                    + "AndroidManifest.xml source files Sketchware Pro's "
                    + "compiler produces (without running a full build). "
                    + "Omit file_name to list all generated files, or pass "
                    + "file_name (e.g. MainActivity.java, main.xml, or "
                    + "AndroidManifest.xml) with start_line / max_lines to "
                    + "inspect numbered lines and pinpoint compile errors.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("file_name", stringSchema(
                    "Optional generated file name or substring (e.g. "
                            + "MainActivity.java, main.xml, AndroidManifest.xml)."));
            p.add("start_line", intSchema(
                    "Optional 1-based starting line number (default 1)."));
            p.add("max_lines", intSchema(
                    "Optional maximum lines to return (default 250, max 600)."));
            return AgentTools.parameters(p);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String fn = stringArg(args, "file_name");
            return fn.isEmpty()
                    ? "Listed generated Java & XML source files"
                    : "Inspected generated source for " + fn;
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            return AgentPackagingAndDiagnostics.inspectGeneratedSource(
                    session.context, scId,
                    stringArg(args, "file_name"),
                    intArg(args, "start_line", 1),
                    intArg(args, "max_lines", 250));
        }
    }

    /** read_app_logs: live runtime crash/logcat reader + last compile log. */
    private static final class ReadAppLogs implements Tool {
        @Override
        public String name() {
            return "read_app_logs";
        }

        @Override
        public String description() {
            return "Reads live runtime logs and AndroidRuntime crash stack "
                    + "traces captured from the installed project app "
                    + "(SketchLogger broadcast stream + exported logcat "
                    + "files) as well as the last saved compile error log.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("package_name", stringSchema(
                    "Optional package name override (defaults to the "
                            + "working project's package name)."));
            p.add("level", stringSchema(
                    "Optional log level filter: E, W, I, D, V, or A."));
            p.add("filter", stringSchema(
                    "Optional case-insensitive text search (e.g. "
                            + "\"Exception\" or \"MainActivity\")."));
            p.add("limit", intSchema(
                    "Maximum recent log entries to return (default 120)."));
            p.add("clear", booleanSchema(
                    "When true, clears the in-memory log buffer after reading."));
            return AgentTools.parameters(p);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Read runtime app logs & crash traces";
        }

        @Override
        public JsonObject run(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            return AgentPackagingAndDiagnostics.readAppLogs(
                    scId,
                    stringArg(args, "package_name"),
                    stringArg(args, "level"),
                    stringArg(args, "filter"),
                    intArg(args, "limit", 120),
                    booleanArg(args, "clear"));
        }
    }

    /** list_collections: lists Widget, MoreBlock & Block collections. */
    private static final class ListCollections implements Tool {
        @Override
        public String name() {
            return "list_collections";
        }

        @Override
        public String description() {
            return "Lists all saved reusable Widget Collections, MoreBlock "
                    + "Collections, and Block Collections stored on device.";
        }

        @Override
        public JsonObject parameters() {
            return AgentTools.parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Listed saved Widget, MoreBlock & Block collections";
        }

        @Override
        public JsonObject run(Session session, JsonObject args) {
            return AgentPackagingAndDiagnostics.listCollections();
        }
    }

    /** manage_collections: save/import/delete Widget, MoreBlock & Block collections. */
    private static final class ManageCollections implements Tool {
        @Override
        public String name() {
            return "manage_collections";
        }

        @Override
        public String description() {
            return "Saves project widgets, MoreBlocks, or event block chains "
                    + "into Sketchware Pro Collections for cross-project "
                    + "reuse, imports saved Widget or MoreBlock Collections "
                    + "into the working project (auto-provisioning missing "
                    + "variables and resources), or deletes a collection item.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("action", stringSchema(
                    "Operation: \"save_widget\", \"import_widget\", "
                            + "\"save_more_block\", \"import_more_block\", "
                            + "\"save_blocks\", or \"delete\"."));
            p.add("collection_name", stringSchema(
                    "Name of the collection item."));
            p.add("kind", stringSchema(
                    "Collection kind when action=\"delete\": \"widget\", "
                            + "\"more_block\", or \"block\"."));
            p.add("activity", stringSchema(
                    "Screen or custom view name (required for save/import)."));
            p.add("widget_id", stringSchema(
                    "Widget ID on \"activity\" to save when "
                            + "action=\"save_widget\"."));
            p.add("parent_id", stringSchema(
                    "Optional parent container ID when "
                            + "action=\"import_widget\" (default \"root\")."));
            p.add("index", intSchema(
                    "Optional child insertion index when "
                            + "action=\"import_widget\" (default append)."));
            p.add("more_block_name", stringSchema(
                    "MoreBlock name on \"activity\" when "
                            + "action=\"save_more_block\"."));
            p.add("target", stringSchema(
                    "Event target ID (or \"activity\") when "
                            + "action=\"save_blocks\"."));
            p.add("event", stringSchema(
                    "Event name (e.g. onClick or initializeLogic) when "
                            + "action=\"save_blocks\"."));
            return AgentTools.parameters(p, "action", "collection_name");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String act = stringArg(args, "action");
            String col = stringArg(args, "collection_name");
            return "Wants to " + act + " collection \"" + col + "\"";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String action = stringArg(args, "action").trim().toLowerCase();
            String colName = stringArg(args, "collection_name").trim();
            if (colName.isEmpty()) {
                throw new ToolException("E_MISSING_COLLECTION_NAME",
                        "Pass non-empty \"collection_name\".");
            }
            String scId = "delete".equals(action)
                    ? (session.workingProjectId == null
                            ? "" : session.workingProjectId)
                    : requiredProject(session.workingProjectId);
            if (!"save_widget".equals(action)
                    && !"import_widget".equals(action)
                    && !"save_more_block".equals(action)
                    && !"import_more_block".equals(action)
                    && !"save_blocks".equals(action)
                    && !"delete".equals(action)) {
                throw new ToolException("E_BAD_ACTION",
                        "Unsupported action \"" + action + "\".");
            }
            JsonObject clean = args == null ? new JsonObject() : args.deepCopy();
            return new AgentProposal(name(), clean, scId,
                    "Collection: " + action + " \"" + colName + "\"",
                    "Perform " + action + " on collection \"" + colName + "\".",
                    "Collection item: " + colName);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            JsonObject a = proposal.args;
            String action = stringArg(a, "action").trim().toLowerCase();
            String colName = stringArg(a, "collection_name").trim();
            try {
                JsonObject res;
                switch (action) {
                    case "save_widget" -> res = AgentPackagingAndDiagnostics
                            .saveWidgetToCollection(
                                    requiredProject(proposal.workingProjectId),
                                    stringArg(a, "activity"),
                                    stringArg(a, "widget_id"),
                                    colName);
                    case "import_widget" -> res = AgentPackagingAndDiagnostics
                            .importWidgetFromCollection(
                                    requiredProject(proposal.workingProjectId),
                                    stringArg(a, "activity"),
                                    colName,
                                    stringArg(a, "parent_id"),
                                    intArg(a, "index", -1));
                    case "save_more_block" -> res = AgentPackagingAndDiagnostics
                            .saveMoreBlockToCollection(
                                    requiredProject(proposal.workingProjectId),
                                    stringArg(a, "activity"),
                                    stringArg(a, "more_block_name"),
                                    colName);
                    case "import_more_block" -> res =
                            AgentPackagingAndDiagnostics
                                    .importMoreBlockFromCollection(
                                            requiredProject(
                                                    proposal.workingProjectId),
                                            stringArg(a, "activity"),
                                            colName);
                    case "save_blocks" -> res = AgentPackagingAndDiagnostics
                            .saveEventBlocksToCollection(
                                    requiredProject(proposal.workingProjectId),
                                    stringArg(a, "activity"),
                                    stringArg(a, "target"),
                                    stringArg(a, "event"),
                                    colName);
                    case "delete" -> {
                        AgentPackagingAndDiagnostics.deleteCollectionItem(
                                stringArg(a, "kind"), colName);
                        res = new JsonObject();
                        res.addProperty("deleted", colName);
                    }
                    default -> throw new ToolException("E_BAD_ACTION",
                            "Unsupported action \"" + action + "\".");
                }
                res.addProperty("verified",
                        "Completed " + action + " for \"" + colName + "\".");
                return res;
            } catch (ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new ToolException("E_COLLECTION_OP_FAILED",
                        "Collection operation failed: "
                                + String.valueOf(e.getMessage()));
            }
        }
    }

    /** build_release_package: Play Store .aab or signed release .apk. */
    private static final class BuildReleasePackage implements Tool {
        @Override
        public String name() {
            return "build_release_package";
        }

        @Override
        public String description() {
            return "Builds and signs a Play Store Android App Bundle (.aab) "
                    + "or a release APK (.apk) using Sketchware Pro's "
                    + "AppBundleCompiler + ApkSigner/ZipSigner pipeline "
                    + "(with ViewBinding, Kotlin, StringFog, and ProGuard/R8 "
                    + "when enabled). Signs with the user's .jks/.keystore "
                    + "when keystore_path is provided, or testkey otherwise.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("format", stringSchema(
                    "Package format: \"aab\" (Play Store App Bundle, default) "
                            + "or \"apk\" (signed release APK)."));
            p.add("keystore_path", stringSchema(
                    "Optional path to a .jks or .keystore file for release "
                            + "signing."));
            p.add("keystore_password", stringSchema(
                    "Keystore password when keystore_path is provided."));
            p.add("key_alias", stringSchema(
                    "Key alias when keystore_path is provided."));
            p.add("key_password", stringSchema(
                    "Optional key password (defaults to keystore_password)."));
            return AgentTools.parameters(p);
        }

        @Override
        public String activityLabel(JsonObject args) {
            String fmt = stringArg(args, "format");
            return "apk".equalsIgnoreCase(fmt)
                    ? "Wants to build a signed release APK"
                    : "Wants to build a Play Store App Bundle (.aab)";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            SketchwareProjects.ProjectInfo proj =
                    SketchwareProjects.project(scId);
            String fmt = stringArg(args, "format").trim().toLowerCase();
            if (fmt.isEmpty()) fmt = "aab";
            if (!"aab".equals(fmt) && !"apk".equals(fmt)
                    && !"bundle".equals(fmt) && !"app_bundle".equals(fmt)) {
                throw new ToolException("E_BAD_FORMAT",
                        "Use format=\"aab\" or format=\"apk\".");
            }
            boolean isBundle = !"apk".equals(fmt);
            String ks = stringArg(args, "keystore_path").trim();
            if (!ks.isEmpty()) {
                if (!new File(ks).isFile()) {
                    throw new ToolException("E_KEYSTORE_NOT_FOUND",
                            "Keystore file not found: " + ks);
                }
                if (stringArg(args, "keystore_password").isEmpty()
                        || stringArg(args, "key_alias").isEmpty()) {
                    throw new ToolException("E_MISSING_KEYSTORE_CREDENTIALS",
                            "Pass keystore_password and key_alias alongside "
                                    + "keystore_path.");
                }
            }
            JsonObject clean = args == null ? new JsonObject() : args.deepCopy();
            return new AgentProposal(name(), clean, scId,
                    "Build " + proj.name + (isBundle
                            ? " App Bundle (.aab)" : " Release APK"),
                    "Compile and sign “" + proj.appName + "” as a "
                            + (isBundle ? "Play Store .aab bundle"
                                    : "signed release .apk")
                            + (ks.isEmpty() ? " (testkey)."
                                    : " (custom keystore)."),
                    isBundle ? "Output folder: /sdcard/sketchware/signed_aab/"
                            : "Output folder: /sdcard/sketchware/signed_apk/");
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            String fmt = stringArg(a, "format").isEmpty()
                    ? "aab" : stringArg(a, "format");
            return AgentBuild.buildReleasePackage(
                    session.context,
                    scId,
                    fmt,
                    emptyToNull(stringArg(a, "keystore_path")),
                    emptyToNull(stringArg(a, "keystore_password")),
                    emptyToNull(stringArg(a, "key_alias")),
                    emptyToNull(stringArg(a, "key_password")));
        }
    }

    /** export_project_backup: exports a .swb Sketchware Pro project archive. */
    private static final class ExportProjectBackup implements Tool {
        @Override
        public String name() {
            return "export_project_backup";
        }

        @Override
        public String description() {
            return "Exports a shareable .swb Sketchware Pro backup archive "
                    + "for the working project (optionally bundling used "
                    + "local libraries and custom blocks).";
        }

        @Override
        public JsonObject parameters() {
            JsonObject p = new JsonObject();
            p.add("include_local_libraries", booleanSchema(
                    "Include local libraries used by the project (default true)."));
            p.add("include_custom_blocks", booleanSchema(
                    "Include custom blocks used by the project (default true)."));
            return AgentTools.parameters(p);
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Wants to export a .swb project backup";
        }

        @Override
        public boolean mutates() {
            return true;
        }

        @Override
        public AgentProposal propose(Session session, JsonObject args)
                throws ToolException {
            String scId = requiredProject(session.workingProjectId);
            SketchwareProjects.ProjectInfo proj =
                    SketchwareProjects.project(scId);
            Boolean libs = optionalBooleanArg(args, "include_local_libraries");
            Boolean cbs = optionalBooleanArg(args, "include_custom_blocks");
            boolean incLibs = libs == null || libs;
            boolean incCbs = cbs == null || cbs;
            JsonObject clean = new JsonObject();
            clean.addProperty("include_local_libraries", incLibs);
            clean.addProperty("include_custom_blocks", incCbs);
            return new AgentProposal(name(), clean, scId,
                    "Export .swb backup for " + proj.name,
                    "Create a Sketchware Pro .swb backup archive of “"
                            + proj.appName + "”.",
                    "Includes local libraries: " + incLibs
                            + " · Includes custom blocks: " + incCbs);
        }

        @Override
        public JsonObject execute(Session session, AgentProposal proposal)
                throws ToolException {
            String scId = requiredProject(proposal.workingProjectId);
            JsonObject a = proposal.args;
            Boolean libs = optionalBooleanArg(a, "include_local_libraries");
            Boolean cbs = optionalBooleanArg(a, "include_custom_blocks");
            JsonObject res = AgentPackagingAndDiagnostics.exportProjectBackup(
                    session.context, scId,
                    libs == null || libs,
                    cbs == null || cbs);
            res.addProperty("verified",
                    "Exported .swb backup -> "
                            + res.get("swb_path").getAsString());
            return res;
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
