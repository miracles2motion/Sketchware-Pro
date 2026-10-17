package pro.sketchware.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.besome.sketch.beans.ProjectFileBean;

/**
 * The agent's toolbox. Every tool is a validated JSON-form contract: the
 * model proposes a call, this class validates it against the schema it
 * declared, and only then does app code run.
 *
 * <p>Slice 1 ships read-only tools only — nothing here can change a
 * project. That rule is structural: no mutation tool exists to be
 * approved yet.
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

    /** One callable tool. */
    public interface Tool {
        String name();

        String description();

        JsonObject parameters();

        /** Activity lines the user sees while this tool runs. */
        String activityLabel(JsonObject args);

        /** Executes and returns the result object handed back to the model. */
        JsonObject run(String workingProjectId, JsonObject args) throws ToolException;
    }

    private static final Map<String, Tool> TOOLS = new LinkedHashMap<>();

    static {
        register(new ProjectSummary());
        register(new ListActivities());
        register(new GetScreen());
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
     * Validates and runs one proposed call, returning the result object.
     * Unknown tools and malformed arguments fail as tool results — the
     * model is told what went wrong and may try again.
     */
    public static JsonObject dispatch(String name, String argumentsJson,
                                      String workingProjectId) {
        Tool tool = TOOLS.get(name);
        if (tool == null) {
            return error("E_UNKNOWN_TOOL", "There is no tool named " + name + ".");
        }
        JsonObject args;
        try {
            args = parseArgs(argumentsJson);
        } catch (Exception e) {
            return error("E_BAD_ARGS", "The tool arguments were not valid JSON.");
        }
        try {
            return tool.run(workingProjectId, args);
        } catch (ToolException e) {
            return error(e.code, e.getMessage());
        } catch (Exception e) {
            return error("E_TOOL_FAILED",
                    "The tool failed: " + String.valueOf(e.getMessage()));
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

    private static String stringArg(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return "";
        }
        return args.get(key).getAsString().trim();
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
            return parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Read the project summary";
        }

        @Override
        public JsonObject run(String workingProjectId, JsonObject args)
                throws ToolException {
            String scId = requiredProject(workingProjectId);
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
                screens.add(screen);
            }
            result.add("screens", screens);
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
            return parameters(new JsonObject());
        }

        @Override
        public String activityLabel(JsonObject args) {
            return "Listed the project's screens";
        }

        @Override
        public JsonObject run(String workingProjectId, JsonObject args)
                throws ToolException {
            String scId = requiredProject(workingProjectId);
            JsonArray activities = new JsonArray();
            for (ProjectFileBean activity : SketchwareProjects.activities(scId)) {
                activities.add(SketchwareProjects.activityName(activity));
            }
            JsonObject result = new JsonObject();
            result.add("activities", activities);
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
            return "Reads one screen of the working project as a condensed "
                    + "widget list: id, type, visible text, background colour, "
                    + "and parent container.";
        }

        @Override
        public JsonObject parameters() {
            JsonObject properties = new JsonObject();
            properties.add("activity", stringSchema(
                    "The activity name, e.g. MainActivity"));
            return parameters(properties, "activity");
        }

        @Override
        public String activityLabel(JsonObject args) {
            String activity = stringArg(args, "activity");
            return activity.isEmpty() ? "Looked at a screen"
                    : "Looked at " + activity;
        }

        @Override
        public JsonObject run(String workingProjectId, JsonObject args)
                throws ToolException {
            String scId = requiredProject(workingProjectId);
            String asked = stringArg(args, "activity");
            ProjectFileBean activity =
                    SketchwareProjects.findActivity(scId, asked);
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
                throw new ToolException("E_ACTIVITY_NOT_FOUND",
                        "There is no screen named \"" + asked + "\". "
                                + "Available screens: " + names);
            }
            JsonObject result = new JsonObject();
            result.addProperty("activity",
                    SketchwareProjects.activityName(activity));
            JsonArray widgets = new JsonArray();
            for (String line : SketchwareProjects.screenLines(scId, activity, 120)) {
                widgets.add(line);
            }
            result.add("widgets", widgets);
            return result;
        }
    }
}
