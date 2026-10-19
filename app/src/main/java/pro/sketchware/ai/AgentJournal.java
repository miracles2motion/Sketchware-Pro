package pro.sketchware.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The app's own memory of agent work on one project, kept on disk per
 * project (in the agent root, next to the settings): the tasks that
 * were run, step by step, the style brief agreed for the project, and
 * where an interrupted task stopped and why.
 *
 * <p>The record is written by app code as changes actually land —
 * never by the model — so it survives the chat, the app closing, and
 * a change of provider key or model. A later turn (any model) is
 * briefed from it: what is done, what is next, what was decided. The
 * file lives at {@code tasks/<scId>.json} and holds at most the most
 * recent tasks; it is only ever cleared by the user.
 */
public final class AgentJournal {

    private AgentJournal() {
    }

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_TASKS = 40;
    private static final int MAX_STEPS = 80;
    /** Turn titles waiting for their first change, per project. */
    private static final Map<String, String> pendingTitles =
            new HashMap<>();

    // ------------------------------------------------------------------
    // Storage

    private static File file(String scId) {
        File dir = new File(AiPaths.agentRoot(), "tasks");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, scId + ".json");
    }

    /** The whole record for a project (never null). */
    public static synchronized JsonObject load(String scId) {
        JsonObject root = new JsonObject();
        root.addProperty("styleBrief", "");
        root.add("tasks", new JsonArray());
        root.add("plan", new JsonObject());
        if (scId == null || scId.isEmpty()) {
            return root;
        }
        File file = file(scId);
        if (!file.exists()) {
            return root;
        }
        try (FileReader reader = new FileReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject stored = parsed.getAsJsonObject();
                if (stored.has("styleBrief")) {
                    root.addProperty("styleBrief",
                            stored.get("styleBrief").getAsString());
                }
                if (stored.has("tasks") && stored.get("tasks").isJsonArray()) {
                    root.add("tasks", stored.get("tasks").getAsJsonArray());
                }
                if (stored.has("plan") && stored.get("plan").isJsonObject()) {
                    root.add("plan", stored.get("plan").getAsJsonObject());
                }
            }
        } catch (Exception ignored) {
            // A damaged record reads as empty rather than failing work.
        }
        return root;
    }

    private static void save(String scId, JsonObject root) {
        try (FileWriter writer = new FileWriter(file(scId))) {
            GSON.toJson(root, writer);
        } catch (Exception ignored) {
            // Losing the record must never break the work itself.
        }
    }

    // ------------------------------------------------------------------
    // Recording (called by the agent loop as things actually happen)

    /**
     * Notes what the user just asked, to title the next task. The
     * task entry is written to disk right away, not when the first
     * change lands: closing the app while an approval card is still
     * waiting must still leave a task to come back to.
     */
    public static synchronized void beginTurn(String scId, String userText) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        pendingTitles.put(scId, userText == null ? "" : userText.trim());
        JsonObject root = load(scId);
        openTask(scId, root);
        save(scId, root);
    }

    private static JsonObject openTask(String scId, JsonObject root) {
        JsonArray tasks = root.getAsJsonArray("tasks");
        if (tasks.size() > 0) {
            JsonElement last = tasks.get(tasks.size() - 1);
            if (last.isJsonObject()
                    && "open".equals(text(last.getAsJsonObject(), "state"))) {
                return last.getAsJsonObject();
            }
        }
        JsonObject task = new JsonObject();
        task.addProperty("id", tasks.size() + 1);
        String title = pendingTitles.getOrDefault(scId, "");
        task.addProperty("title",
                title.isEmpty() ? "Work in progress" : truncate(title, 90));
        task.addProperty("startedAt", System.currentTimeMillis());
        task.addProperty("state", "open");
        task.addProperty("stopReason", "");
        task.add("steps", new JsonArray());
        tasks.add(task);
        while (tasks.size() > MAX_TASKS) {
            tasks.remove(0);
        }
        return task;
    }

    private static void recordStep(String scId, String tool, String label,
                                   String outcome, String detail) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        JsonObject task = openTask(scId, root);
        JsonArray steps = task.getAsJsonArray("steps");
        JsonObject step = new JsonObject();
        step.addProperty("tool", tool == null ? "" : tool);
        step.addProperty("label", truncate(
                label == null ? "" : label, 140));
        step.addProperty("outcome", outcome);
        step.addProperty("detail", truncate(
                detail == null ? "" : detail, 200));
        step.addProperty("at", System.currentTimeMillis());
        steps.add(step);
        while (steps.size() > MAX_STEPS) {
            steps.remove(0);
        }
        save(scId, root);
    }

    /** A change the user approved and the app applied. */
    public static synchronized void mutationDone(String scId, String tool,
                                                 String label) {
        recordStep(scId, tool, label, "done", "");
    }

    /** A change the user turned down on its approval card. */
    public static synchronized void mutationRejected(String scId,
                                                     String tool,
                                                     String label) {
        recordStep(scId, tool, label, "rejected", "");
    }

    /** A change that could not be made (validation or apply failure). */
    public static synchronized void mutationFailed(String scId, String tool,
                                                   String label,
                                                   String detail) {
        recordStep(scId, tool, label, "failed", detail);
    }

    /**
     * Closes the open task, if any: finished, or stopped with a plain
     * reason ("the provider's limit was hit", "you stopped it"...).
     */
    public static synchronized void endTurn(String scId, boolean done,
                                            String stopReason) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        JsonArray tasks = root.getAsJsonArray("tasks");
        if (tasks.size() == 0) {
            return;
        }
        JsonElement last = tasks.get(tasks.size() - 1);
        if (!last.isJsonObject()
                || !"open".equals(text(last.getAsJsonObject(), "state"))) {
            return;
        }
        JsonObject task = last.getAsJsonObject();
        // A finished turn in which nothing was actually changed was
        // conversation, not a task: it leaves no entry behind. (A
        // stopped one stays even with no steps — the request was
        // never answered, and the user may want to resume it.)
        if (done && task.getAsJsonArray("steps").size() == 0) {
            tasks.remove(tasks.size() - 1);
            save(scId, root);
            return;
        }
        task.addProperty("state", done ? "done" : "stopped");
        task.addProperty("stopReason",
                done ? "" : (stopReason == null ? "" : stopReason));
        task.addProperty("endedAt", System.currentTimeMillis());
        if (!done) {
            // A stopped turn leaves the plan's current step honestly
            // paused, so the Plan page and the next brief agree.
            JsonObject plan = root.getAsJsonObject("plan");
            if (plan != null && plan.has("steps")) {
                for (JsonElement element : plan.getAsJsonArray("steps")) {
                    if (element.isJsonObject() && "current".equals(
                            text(element.getAsJsonObject(), "state"))) {
                        element.getAsJsonObject()
                                .addProperty("state", "stopped");
                    }
                }
            }
        }
        save(scId, root);
    }

    // ------------------------------------------------------------------
    // Style brief

    public static synchronized void setStyleBrief(String scId, String brief) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        root.addProperty("styleBrief",
                truncate(brief == null ? "" : brief.trim(), 600));
        root.addProperty("styleBriefAt", System.currentTimeMillis());
        save(scId, root);
    }

    public static synchronized String styleBrief(String scId) {
        return text(load(scId), "styleBrief");
    }

    /**
     * When a turn creates the project it was designing for, a style
     * brief agreed during that same turn was agreed for the new
     * project — it was only parked under the previously selected one
     * because that is where the conversation started. So it moves:
     * the new project takes it and the old one lets it go. A brief
     * the old project has held since before the turn is its own and
     * stays put.
     */
    public static synchronized void moveStyleBriefIfAgreedSince(
            String fromScId, String toScId, long sinceMillis) {
        if (fromScId == null || fromScId.isEmpty()
                || toScId == null || toScId.isEmpty()
                || fromScId.equals(toScId)) {
            return;
        }
        JsonObject from = load(fromScId);
        String brief = text(from, "styleBrief");
        if (brief.isEmpty() || !styleBrief(toScId).isEmpty()
                || number(from, "styleBriefAt") < sinceMillis) {
            return;
        }
        JsonObject to = load(toScId);
        to.addProperty("styleBrief", brief);
        to.addProperty("styleBriefAt", System.currentTimeMillis());
        save(toScId, to);
        from.addProperty("styleBrief", "");
        from.addProperty("styleBriefAt", 0);
        save(fromScId, from);
    }

    // ------------------------------------------------------------------
    // Implementation plan

    /**
     * Saves the agreed implementation plan: a title and ordered steps,
     * the first {@code doneSteps} of them finished and step
     * {@code currentStep} (0-based) the one in hand. Done steps can
     * never run ahead of the changes the journal has actually
     * recorded — a plan step is a milestone over real work, and the
     * app will not let it claim work that never landed.
     */
    public static synchronized void setPlan(String scId, String title,
                                            String stepsText, int doneSteps,
                                            int currentStep) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonArray steps = new JsonArray();
        if (stepsText != null) {
            for (String line : stepsText.split("\\n")) {
                String text = line.trim();
                if (!text.isEmpty() && steps.size() < 24) {
                    JsonObject step = new JsonObject();
                    step.addProperty("text", truncate(text, 140));
                    step.addProperty("state", "pending");
                    steps.add(step);
                }
            }
        }
        int done = Math.max(0, Math.min(doneSteps,
                Math.min(steps.size(), doneStepCount(scId))));
        for (int i = 0; i < done; i++) {
            steps.get(i).getAsJsonObject().addProperty("state", "done");
        }
        if (currentStep >= done && currentStep < steps.size()) {
            steps.get(currentStep).getAsJsonObject()
                    .addProperty("state", "current");
        }
        JsonObject plan = new JsonObject();
        plan.addProperty("title",
                truncate(title == null ? "" : title.trim(), 90));
        plan.add("steps", steps);
        plan.addProperty("updatedAt", System.currentTimeMillis());
        JsonObject root = load(scId);
        root.add("plan", plan);
        save(scId, root);
    }

    /** The saved plan object (title, steps, updatedAt); never null. */
    public static synchronized JsonObject plan(String scId) {
        JsonObject plan = load(scId).getAsJsonObject("plan");
        return plan == null ? new JsonObject() : plan;
    }

    public static synchronized boolean hasPlan(String scId) {
        JsonObject plan = plan(scId);
        return plan.has("steps") && plan.getAsJsonArray("steps").size() > 0;
    }

    /** Total changes the journal has recorded as landed, all tasks. */
    public static synchronized int doneStepCount(String scId) {
        int count = 0;
        for (JsonElement task : load(scId).getAsJsonArray("tasks")) {
            if (!task.isJsonObject()) {
                continue;
            }
            for (JsonElement step
                    : task.getAsJsonObject().getAsJsonArray("steps")) {
                if (step.isJsonObject() && "done".equals(
                        text(step.getAsJsonObject(), "outcome"))) {
                    count++;
                }
            }
        }
        return count;
    }

    /** The plan in plain lines for the Plan page. */
    public static synchronized String planText(String scId) {
        JsonObject plan = plan(scId);
        if (!plan.has("steps")
                || plan.getAsJsonArray("steps").size() == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement element : plan.getAsJsonArray("steps")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            String state = text(step, "state");
            String mark = "done".equals(state) ? "✓ "
                    : "current".equals(state) ? "● "
                    : "stopped".equals(state) ? "❚❚ " : "○ ";
            if (out.length() > 0) {
                out.append("\n");
            }
            out.append(mark).append(text(step, "text"));
        }
        return out.toString();
    }

    /**
     * A plan agreed in the same turn that creates its project was
     * written under the previously selected one only because that is
     * where the conversation started: it moves, like the style brief.
     */
    public static synchronized void movePlanIfAgreedSince(
            String fromScId, String toScId, long sinceMillis) {
        if (fromScId == null || fromScId.isEmpty()
                || toScId == null || toScId.isEmpty()
                || fromScId.equals(toScId)
                || !hasPlan(fromScId) || hasPlan(toScId)) {
            return;
        }
        JsonObject from = load(fromScId);
        JsonObject plan = from.getAsJsonObject("plan");
        if (number(plan, "updatedAt") < sinceMillis) {
            return;
        }
        JsonObject to = load(toScId);
        to.add("plan", plan);
        save(toScId, to);
        from.add("plan", new JsonObject());
        save(fromScId, from);
    }

    // ------------------------------------------------------------------
    // Drafts from a conversation that has no project yet

    private static String draftStyleBrief = "";
    private static String draftPlanTitle = "";
    private static String draftPlanSteps = "";

    /** Remembers a style agreed before any project was chosen. */
    public static synchronized void setDraftStyleBrief(String brief) {
        draftStyleBrief = brief == null ? "" : brief.trim();
    }

    /** Remembers a plan agreed before any project was chosen. */
    public static synchronized void setDraftPlan(String title,
                                                 String stepsText) {
        draftPlanTitle = title == null ? "" : title.trim();
        draftPlanSteps = stepsText == null ? "" : stepsText;
    }

    public static synchronized boolean hasDrafts() {
        return !draftStyleBrief.isEmpty() || !draftPlanSteps.isEmpty();
    }

    /**
     * When a conversation creates its project, the style and plan it
     * agreed on the way are deposited into the new project (only
     * where the project has none of its own yet) and the drafts are
     * spent.
     */
    public static synchronized void depositDrafts(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        if (!draftStyleBrief.isEmpty() && styleBrief(scId).isEmpty()) {
            setStyleBrief(scId, draftStyleBrief);
        }
        if (!draftPlanSteps.isEmpty() && !hasPlan(scId)) {
            setPlan(scId, draftPlanTitle, draftPlanSteps, 0, 0);
        }
        draftStyleBrief = "";
        draftPlanTitle = "";
        draftPlanSteps = "";
    }

    /** Empties the project's record (the user's own choice, only). */
    public static synchronized void clear(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("styleBrief", "");
        root.add("tasks", new JsonArray());
        save(scId, root);
    }

    /**
     * Reopens the latest stopped task so the work continues in the
     * same entry, under its original title, with its steps intact —
     * the Resume button's path. No-op when nothing is stopped.
     */
    public static synchronized void reopenLatest(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        JsonArray tasks = root.getAsJsonArray("tasks");
        if (tasks.size() == 0) {
            return;
        }
        JsonObject last = tasks.get(tasks.size() - 1).getAsJsonObject();
        if (!"stopped".equals(text(last, "state"))) {
            return;
        }
        last.addProperty("state", "open");
        last.addProperty("stopReason", "");
        save(scId, root);
    }

    /** The title of the task currently open, or "" when none is. */
    public static synchronized String openTitle(String scId) {
        JsonObject root = load(scId);
        JsonArray tasks = root.getAsJsonArray("tasks");
        if (tasks.size() == 0) {
            return "";
        }
        JsonObject last = tasks.get(tasks.size() - 1).getAsJsonObject();
        return "open".equals(text(last, "state"))
                ? text(last, "title") : "";
    }

    // ------------------------------------------------------------------
    // Reading

    /**
     * The short brief a fresh turn is given: the style brief, any
     * unfinished task with the steps already done, and the recent
     * task titles. Empty when there is nothing worth saying.
     */
    public static synchronized String brief(String scId) {
        if (scId == null || scId.isEmpty()) {
            return "";
        }
        JsonObject root = load(scId);
        JsonArray tasks = root.getAsJsonArray("tasks");
        StringBuilder out = new StringBuilder();
        String style = text(root, "styleBrief");
        if (!style.isEmpty()) {
            out.append("\n\nStyle brief for this project (agreed with "
                    + "the user; keep to it): ").append(style);
        }
        JsonObject plan = root.getAsJsonObject("plan");
        boolean havePlan = plan != null && plan.has("steps")
                && plan.getAsJsonArray("steps").size() > 0;
        if (havePlan) {
            int done = 0;
            String current = "";
            for (JsonElement element : plan.getAsJsonArray("steps")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject step = element.getAsJsonObject();
                if ("done".equals(text(step, "state"))) {
                    done++;
                } else if (current.isEmpty()) {
                    current = text(step, "text");
                }
            }
            out.append("\n\nImplementation plan \"")
                    .append(text(plan, "title")).append("\": ")
                    .append(done).append(" of ")
                    .append(plan.getAsJsonArray("steps").size())
                    .append(" steps done.");
            if (!current.isEmpty()) {
                out.append(" Next step: ").append(current).append(".");
            }
            out.append(" Keep the plan updated with set_plan as "
                    + "milestones land.");
        } else if (!style.isEmpty()) {
            out.append("\n\nThis project has a style but no saved "
                    + "implementation plan yet. For substantial work, "
                    + "agree the plan and save it with set_plan before "
                    + "building further.");
        }
        JsonObject open = null;
        JsonObject lastStopped = null;
        for (JsonElement element : tasks) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject task = element.getAsJsonObject();
            String state = text(task, "state");
            if ("open".equals(state)) {
                open = task;
            } else if ("stopped".equals(state)) {
                lastStopped = task;
            }
        }
        JsonObject unfinished = open != null ? open : lastStopped;
        if (unfinished != null) {
            out.append("\n\nUnfinished task: \"")
                    .append(text(unfinished, "title")).append("\"");
            String reason = "open".equals(text(unfinished, "state"))
                    ? "the app closed while it was running"
                    : text(unfinished, "stopReason");
            if (!reason.isEmpty()) {
                out.append(" — stopped because ").append(reason).append(".");
            }
            StringBuilder done = new StringBuilder();
            int count = 0;
            for (JsonElement element
                    : unfinished.getAsJsonArray("steps")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject step = element.getAsJsonObject();
                if (!"done".equals(text(step, "outcome"))) {
                    continue;
                }
                count++;
                if (count <= 12) {
                    if (done.length() > 0) {
                        done.append("; ");
                    }
                    done.append(count).append(". ")
                            .append(text(step, "label"));
                }
            }
            if (count > 0) {
                out.append("\nAlready done in it (do not redo): ")
                        .append(done);
                if (count > 12) {
                    out.append("; …and ").append(count - 12).append(" more");
                }
                out.append(".");
            }
            out.append("\nIf the user's message continues this task, "
                    + "first re-check the project's current state with "
                    + "your tools, then pick up at the next step — do "
                    + "not start it over or repeat finished steps.");
        }
        StringBuilder recent = new StringBuilder();
        int shown = 0;
        for (int i = tasks.size() - 1; i >= 0 && shown < 4; i--) {
            JsonObject task = tasks.get(i).getAsJsonObject();
            if (task == unfinished) {
                continue;
            }
            if (recent.length() > 0) {
                recent.append("; ");
            }
            recent.append("\"").append(text(task, "title")).append("\" (")
                    .append(text(task, "state")).append(")");
            shown++;
        }
        if (recent.length() > 0) {
            out.append("\nRecent work on this project: ").append(recent)
                    .append(".");
        }
        String brief = out.toString();
        return brief.length() > 2400 ? brief.substring(0, 2400) : brief;
    }

    /** The full record in plain lines, newest first (log + UI). */
    public static synchronized String logText(String scId) {
        JsonObject root = load(scId);
        JsonArray tasks = root.getAsJsonArray("tasks");
        if (tasks.size() == 0) {
            return "Nothing has been done on this project yet.";
        }
        SimpleDateFormat format =
                new SimpleDateFormat("d MMM, h:mm a", Locale.US);
        StringBuilder out = new StringBuilder();
        for (int i = tasks.size() - 1; i >= 0; i--) {
            JsonObject task = tasks.get(i).getAsJsonObject();
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append("“").append(text(task, "title")).append("” — ")
                    .append(text(task, "state"));
            long started = number(task, "startedAt");
            if (started > 0) {
                out.append(" · ").append(format.format(new Date(started)));
            }
            String reason = text(task, "stopReason");
            if (!reason.isEmpty()) {
                out.append("\nStopped: ").append(reason);
            }
            for (JsonElement element : task.getAsJsonArray("steps")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject step = element.getAsJsonObject();
                out.append("\n · ").append(text(step, "label"))
                        .append(" [").append(text(step, "outcome"))
                        .append("]");
                String detail = text(step, "detail");
                if (!detail.isEmpty()) {
                    out.append(" — ").append(detail);
                }
            }
        }
        return out.toString();
    }

    /** One line for the strip above the composer, or "" for none. */
    public static synchronized String stripText(String scId) {
        JsonObject root = load(scId);
        JsonArray tasks = root.getAsJsonArray("tasks");
        for (int i = tasks.size() - 1; i >= 0; i--) {
            JsonObject task = tasks.get(i).getAsJsonObject();
            String state = text(task, "state");
            if ("done".equals(state)) {
                return "";
            }
            int done = 0;
            int total = 0;
            for (JsonElement element : task.getAsJsonArray("steps")) {
                if (element.isJsonObject()) {
                    total++;
                    if ("done".equals(text(element.getAsJsonObject(),
                            "outcome"))) {
                        done++;
                    }
                }
            }
            String reason = "open".equals(state)
                    ? "the app closed" : text(task, "stopReason");
            return "“" + truncate(text(task, "title"), 40) + "” stopped"
                    + (total > 0 ? " after " + done + " of " + total
                            + " steps" : "")
                    + (reason.isEmpty() ? "" : " — " + reason);
        }
        return "";
    }

    // ------------------------------------------------------------------

    private static String text(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long number(JsonObject object, String key) {
        try {
            return object.has(key) && object.get(key).isJsonPrimitive()
                    ? object.get(key).getAsLong() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String truncate(String value, int max) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= max
                ? trimmed : trimmed.substring(0, max - 1) + "…";
    }
}
