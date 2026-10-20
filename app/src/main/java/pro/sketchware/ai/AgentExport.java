package pro.sketchware.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;

/**
 * One JSON file holding everything about a project's conversation
 * with the agent: the chat itself (user and assistant messages,
 * notes, approval cards with their outcomes, build cards), the
 * saved Style, the implementation Plan and the Task log, plus which
 * provider and model were in use.
 *
 * <p>The point is that the user can hand the file to someone (or
 * some agent) helping them and be understood without screenshots:
 * it is the whole story of the conversation, not a fragment of it.
 * API keys are never included — only the provider and model names.
 */
public final class AgentExport {

    private AgentExport() {
    }

    /** Builds the export document for one project conversation. */
    public static JsonObject build(String scId, String provider,
                                   String model,
                                   List<ChatMessage> messages) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "sketchware-agent-export/1");
        long now = System.currentTimeMillis();
        root.addProperty("exported_at", now);
        root.addProperty("exported_at_text",
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(new Date(now)));
        root.addProperty("note",
                "API keys are never included in this file.");

        JsonObject project = new JsonObject();
        project.addProperty("sc_id", scId == null ? "" : scId);
        SketchwareProjects.ProjectInfo info =
                scId == null ? null : SketchwareProjects.project(scId);
        if (info != null) {
            project.addProperty("name", info.name);
            project.addProperty("app_name", info.appName);
            project.addProperty("package", info.packageName);
        }
        root.add("project", project);
        root.addProperty("provider", provider == null ? "" : provider);
        root.addProperty("model", model == null ? "" : model);

        JsonObject journal = AgentJournal.load(scId);
        JsonObject style = new JsonObject();
        style.addProperty("brief",
                journal.has("styleBrief")
                        ? journal.get("styleBrief").getAsString() : "");
        style.addProperty("saved_at",
                journal.has("styleBriefAt")
                        ? journal.get("styleBriefAt").getAsLong() : 0);
        root.add("style", style);
        root.add("design_system",
                journal.has("designSystem")
                        ? journal.get("designSystem").getAsJsonObject()
                        : new JsonObject());
        root.add("plan",
                journal.has("plan")
                        ? journal.get("plan").getAsJsonObject()
                        : new JsonObject());
        root.add("tasks",
                journal.has("tasks")
                        ? journal.get("tasks").getAsJsonArray()
                        : new JsonArray());

        root.add("project_state", projectSnapshot(scId));

        root.add("reading_guide", readingGuide());

        // The timeline: everything that happened, in order — what the
        // model was told, what it said, every tool call with its
        // arguments and result, every card and decision, every block
        // and every refusal. Everything else in this file is a summary
        // of this.
        JsonArray log = journal.has("log")
                ? journal.get("log").getAsJsonArray() : new JsonArray();
        root.add("log", log);
        root.add("log_stats", logStats(log));
        root.add("block_activity", blockActivity(log));
        root.add("environment", environment(scId));

        JsonArray chat = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role",
                    message.role == null ? "" : message.role.name());
            entry.addProperty("text",
                    message.text == null ? "" : message.text);
            if (message.thinking != null && !message.thinking.isEmpty()) {
                entry.addProperty("thinking", message.thinking);
            }
            entry.addProperty("state", message.state.name());
            entry.addProperty("at", message.timestamp);
            if (message.repliedAt > 0) {
                entry.addProperty("replied_at", message.repliedAt);
            }
            if (message.durationMs > 0) {
                entry.addProperty("duration_ms", message.durationMs);
            }
            if (message.role == ChatMessage.Role.APPROVAL) {
                JsonObject approval = new JsonObject();
                approval.addProperty("title", message.approvalTitle);
                approval.addProperty("body", message.approvalBody);
                approval.addProperty("details", message.approvalDetails);
                approval.addProperty("state", message.approvalState);
                approval.addProperty("outcome", message.approvalOutcome);
                entry.add("approval", approval);
            }
            if (message.buildApkPath != null
                    && !message.buildApkPath.isEmpty()) {
                JsonObject build = new JsonObject();
                build.addProperty("apk_path", message.buildApkPath);
                build.addProperty("apk_bytes", message.buildApkBytes);
                build.addProperty("apk_modified", message.buildApkModified);
                build.addProperty("project_id", message.buildProjectId);
                build.addProperty("superseded", message.buildSuperseded);
                entry.add("build", build);
            }
            chat.add(entry);
        }
        root.add("chat", chat);
        return root;
    }

    /**
     * How to read the file. A log this size is only useful if the next
     * person (or model) knows where to look first, so the file explains
     * itself instead of leaving them to guess.
     */
    private static JsonObject readingGuide() {
        JsonObject guide = new JsonObject();
        guide.addProperty("chat",
                "What was said: user and assistant messages, and every "
                        + "approval or build card with its outcome.");
        guide.addProperty("log",
                "EVERYTHING, in the order it happened — read this to "
                        + "diagnose. Start with log_stats, then read the "
                        + "tool_call entries whose outcome is error.");
        guide.addProperty("log.kinds",
                "user_message (what was asked) · system_prompt (exactly "
                        + "what the model was told) · model_reply (its "
                        + "answer, its reasoning, the tool calls it chose "
                        + "with their arguments) · tool_call (every call "
                        + "with arguments, result and error) · card_shown "
                        + "/ card_approved / card_rejected (each proposal "
                        + "and the user's decision) · applied (what "
                        + "landed, read back from the project) · "
                        + "apply_failed · broken_references · model_error "
                        + "(provider failures) · turn_end (done or stopped,"
                        + " and why).");
        guide.addProperty("log_stats",
                "Counts per tool: attempts, ok, failed, applied. A tool "
                        + "with many attempts and no applied is where the "
                        + "conversation was stuck.");
        guide.addProperty("block_activity",
                "Which blocks the agent tried to use "
                        + "(operations_asked_for), which were refused "
                        + "(operations_refused), and every failure with "
                        + "its error message and arguments.");
        guide.addProperty("project_state",
                "The project as it stands now: screens, widgets, "
                        + "variables, components, events. Compare it with "
                        + "what the agent claimed it made.");
        guide.addProperty("tasks",
                "The task log: step by step, with the outcome and the "
                        + "exact refusal when a step failed.");
        guide.addProperty("environment",
                "Device, Android version, app version, and which "
                        + "libraries this project has switched on.");
        return guide;
    }

    /**
     * What the timeline adds up to: how many of each kind of event,
     * how every tool fared (attempted / ok / failed / rejected), and
     * how long the model took. The numbers that say where a
     * conversation went wrong without reading every line.
     */
    private static JsonObject logStats(JsonArray log) {
        JsonObject kinds = new JsonObject();
        JsonObject tools = new JsonObject();
        long modelMs = 0;
        int modelCalls = 0;
        for (JsonElement element : log) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String kind = entry.has("kind")
                    ? entry.get("kind").getAsString() : "?";
            kinds.addProperty(kind,
                    (kinds.has(kind) ? kinds.get(kind).getAsInt() : 0) + 1);
            if ("model_reply".equals(kind) && entry.has("took_ms")) {
                modelMs += entry.get("took_ms").getAsLong();
                modelCalls++;
            }
            if (!"tool_call".equals(kind) && !"applied".equals(kind)
                    && !"apply_failed".equals(kind)) {
                continue;
            }
            String tool = entry.has("tool")
                    ? entry.get("tool").getAsString() : "?";
            JsonObject stats = tools.has(tool)
                    ? tools.getAsJsonObject(tool) : new JsonObject();
            if (!tools.has(tool)) {
                stats.addProperty("attempts", 0);
                stats.addProperty("ok", 0);
                stats.addProperty("failed", 0);
                tools.add(tool, stats);
            }
            if ("tool_call".equals(kind)) {
                stats.addProperty("attempts",
                        stats.get("attempts").getAsInt() + 1);
                if ("ok".equals(entry.has("outcome")
                        ? entry.get("outcome").getAsString() : "")) {
                    stats.addProperty("ok", stats.get("ok").getAsInt() + 1);
                } else {
                    stats.addProperty("failed",
                            stats.get("failed").getAsInt() + 1);
                }
            } else if ("applied".equals(kind)) {
                stats.addProperty("applied",
                        (stats.has("applied")
                                ? stats.get("applied").getAsInt() : 0) + 1);
            }
        }
        JsonObject summary = new JsonObject();
        summary.add("events", kinds);
        summary.add("tools", tools);
        summary.addProperty("model_calls", modelCalls);
        summary.addProperty("model_total_ms", modelMs);
        return summary;
    }

    /**
     * Every block the agent tried to use, and what came of it. Read
     * from the arguments the model sent and the results it got: which
     * operations were asked for, how often, and how many of those
     * attempts were refused. "The agent says it saved the note" is
     * checkable here.
     */
    private static JsonObject blockActivity(JsonArray log) {
        JsonObject asked = new JsonObject();
        JsonObject refused = new JsonObject();
        JsonArray failures = new JsonArray();
        for (JsonElement element : log) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!"tool_call".equals(entry.has("kind")
                    ? entry.get("kind").getAsString() : "")) {
                continue;
            }
            String tool = entry.has("tool")
                    ? entry.get("tool").getAsString() : "";
            if (!tool.contains("event") && !tool.contains("screen")
                    && !tool.contains("block")) {
                continue;
            }
            String arguments = entry.has("arguments")
                    ? entry.get("arguments").getAsString() : "";
            for (String name : opNames(arguments)) {
                asked.addProperty(name,
                        (asked.has(name) ? asked.get(name).getAsInt() : 0)
                                + 1);
            }
            boolean failed = !"ok".equals(entry.has("outcome")
                    ? entry.get("outcome").getAsString() : "");
            if (failed) {
                for (String name : opNames(arguments)) {
                    refused.addProperty(name,
                            (refused.has(name)
                                    ? refused.get(name).getAsInt() : 0) + 1);
                }
                JsonObject failure = new JsonObject();
                failure.addProperty("tool", tool);
                failure.addProperty("error", entry.has("error")
                        ? entry.get("error").getAsString() : "");
                failure.addProperty("message", entry.has("error_message")
                        ? entry.get("error_message").getAsString() : "");
                failure.addProperty("arguments",
                        arguments.length() > 1500
                                ? arguments.substring(0, 1500) + " …"
                                : arguments);
                failures.add(failure);
            }
        }
        JsonObject result = new JsonObject();
        result.add("operations_asked_for", asked);
        result.add("operations_refused", refused);
        result.add("failures", failures);
        return result;
    }

    /** The "op"/"block" names inside a tool-call argument string. */
    private static java.util.List<String> opNames(String arguments) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String key : new String[]{"\"op\"", "\"block\""}) {
            int from = 0;
            while (true) {
                int at = arguments.indexOf(key, from);
                if (at < 0) {
                    break;
                }
                int colon = arguments.indexOf(':', at + key.length());
                if (colon < 0) {
                    break;
                }
                int start = arguments.indexOf('"', colon);
                if (start < 0) {
                    break;
                }
                int end = arguments.indexOf('"', start + 1);
                if (end < 0) {
                    break;
                }
                String name = arguments.substring(start + 1, end).trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
                from = end + 1;
            }
        }
        return names;
    }

    /** What the app was running on, so a report can be reproduced. */
    private static JsonObject environment(String scId) {
        JsonObject env = new JsonObject();
        env.addProperty("android", android.os.Build.VERSION.RELEASE);
        env.addProperty("sdk", android.os.Build.VERSION.SDK_INT);
        env.addProperty("device", android.os.Build.MANUFACTURER + " "
                + android.os.Build.MODEL);
        try {
            android.content.Context app =
                    pro.sketchware.SketchApplication.getContext();
            if (app != null) {
                String version = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0).versionName;
                env.addProperty("app_version",
                        version == null ? "" : version);
            }
        } catch (Exception ignored) {
            // The version is a nicety, never a requirement.
        }
        JsonObject setup = AgentSetup.libraries(scId);
        if (setup.size() > 0) {
            env.add("libraries", setup);
        }
        return env;
    }

    /**
     * The project as it stands now: every screen with its widgets,
     * its variables and components, and what its events do. Without
     * this, an export can show a whole conversation and still leave
     * the reader unable to tell whether any of it is really in the
     * project.
     */
    private static JsonObject projectSnapshot(String scId) {
        JsonObject state = new JsonObject();
        if (scId == null || scId.isEmpty()) {
            return state;
        }
        JsonArray screens = new JsonArray();
        try {
            for (ProjectFileBean activity
                    : SketchwareProjects.activities(scId)) {
                JsonObject screen = new JsonObject();
                screen.addProperty("activity",
                        SketchwareProjects.activityName(activity));
                SketchwareProjects.LogicSummary logic =
                        SketchwareProjects.logicSummary(scId, activity);
                // Variables are per screen in Sketchware: recording
                // which screen declares which is what makes a
                // "missing variable" failure diagnosable afterwards.
                JsonObject variables = new JsonObject();
                for (String name : logic.variables) {
                    variables.addProperty(name,
                            String.valueOf(logic.variableTypes.get(name)));
                }
                screen.add("variables", variables);
                JsonObject lists = new JsonObject();
                for (String name : logic.lists) {
                    lists.addProperty(name,
                            String.valueOf(logic.listTypes.get(name)));
                }
                screen.add("lists", lists);
                JsonArray components = new JsonArray();
                for (String name : logic.components) {
                    components.add(name);
                }
                screen.add("components", components);
                screen.addProperty("events", logic.events);
                JsonArray widgets = new JsonArray();
                for (ViewBean bean
                        : SketchwareProjects.screenBeans(scId, activity)) {
                    if (bean == null) {
                        continue;
                    }
                    JsonObject widget = new JsonObject();
                    widget.addProperty("id", bean.id);
                    widget.addProperty("type",
                            AgentEdits.typeName(bean.type));
                    widget.addProperty("parent", bean.parent);
                    if (bean.text != null) {
                        if (bean.text.text != null
                                && !bean.text.text.isEmpty()) {
                            widget.addProperty("text", bean.text.text);
                        }
                        if (bean.text.hint != null
                                && !bean.text.hint.isEmpty()) {
                            widget.addProperty("hint", bean.text.hint);
                        }
                    }
                    if (bean.layout != null) {
                        widget.addProperty("width", bean.layout.width);
                        widget.addProperty("height", bean.layout.height);
                        widget.addProperty("weight", bean.layout.weight);
                    }
                    widgets.add(widget);
                }
                screen.add("widgets", widgets);
                JsonArray events = new JsonArray();
                for (String line
                        : SketchwareProjects.eventLines(scId, activity)) {
                    events.add(line);
                }
                screen.add("event_list", events);
                screens.add(screen);
            }
        } catch (Exception ignored) {
            // A snapshot is an aid; a damaged project still exports.
        }
        state.add("screens", screens);
        JsonArray broken = new JsonArray();
        try {
            for (String finding
                    : AgentLogic.danglingWidgetReferences(scId)) {
                broken.add(finding);
            }
        } catch (Exception ignored) {
            // Never let the check stop the export.
        }
        state.add("broken_references", broken);
        JsonArray unused = new JsonArray();
        try {
            for (String finding : AgentLogic.unusedVariables(scId)) {
                unused.add(finding);
            }
            for (String finding : AgentLogic.unreadInputs(scId)) {
                unused.add(finding);
            }
        } catch (Exception ignored) {
            // Never let the check stop the export.
        }
        state.add("unused_variables", unused);
        return state;
    }

    /** Writes the export under the agent root and returns the file. */
    public static File write(JsonObject root, String projectName)
            throws Exception {
        File dir = new File(AiPaths.agentRoot(), "exports");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String safe = (projectName == null ? "" : projectName)
                .replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (safe.isEmpty()) {
            safe = "project";
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                .format(new Date());
        File file = new File(dir, safe + "-agent-export-" + stamp + ".json");
        try (FileWriter writer = new FileWriter(file)) {
            new GsonBuilder().setPrettyPrinting().create()
                    .toJson(root, writer);
        }
        return file;
    }
}
