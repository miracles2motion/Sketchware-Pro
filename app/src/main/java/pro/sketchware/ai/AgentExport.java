package pro.sketchware.ai;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
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
        root.add("plan",
                journal.has("plan")
                        ? journal.get("plan").getAsJsonObject()
                        : new JsonObject());
        root.add("tasks",
                journal.has("tasks")
                        ? journal.get("tasks").getAsJsonArray()
                        : new JsonArray());

        root.add("project_state", projectSnapshot(scId));

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
