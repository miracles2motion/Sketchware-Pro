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
import java.util.ArrayList;
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
    private static final int MAX_STEPS = 240;
    /** Timeline entries kept per project (the oldest fall off). */
    private static final int MAX_LOG = 1200;
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
        root.add("log", new JsonArray());
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
                // Keep every stored field (styleBriefAt, step
                // fingerprints, anything a later version adds): building
                // a fresh root and copying a fixed field list silently
                // wiped fields on the next save — the style's saved
                // date was lost exactly that way.
                for (java.util.Map.Entry<String, JsonElement> entry
                        : stored.entrySet()) {
                    root.add(entry.getKey(), entry.getValue());
                }
                if (!root.has("styleBrief")) {
                    root.addProperty("styleBrief", "");
                }
                if (!root.has("tasks") || !root.get("tasks").isJsonArray()) {
                    root.add("tasks", new JsonArray());
                }
                if (!root.has("plan") || !root.get("plan").isJsonObject()) {
                    root.add("plan", new JsonObject());
                }
                if (!root.has("log") || !root.get("log").isJsonArray()) {
                    root.add("log", new JsonArray());
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
        // A new turn on a project whose plan step sits paused counts
        // as the work resuming: the paused step becomes the current
        // one again, so the Plan never leaves a step marked paused
        // while work is happening under it.
        resumePlan(scId);
    }

    /**
     * Wakes a paused plan step: what was stopped becomes the step in
     * hand again. Called when work resumes — a plan step is only ever
     * paused while nothing is happening, never after the user has
     * asked for the work to continue.
     */
    public static synchronized void resumePlan(String scId) {
        if (scId == null || scId.isEmpty() || !hasPlan(scId)) {
            return;
        }
        JsonObject root = load(scId);
        JsonObject plan = root.getAsJsonObject("plan");
        JsonArray steps = plan.getAsJsonArray("steps");
        for (JsonElement element : steps) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            if ("stopped".equals(text(step, "state"))) {
                step.addProperty("state", "current");
                plan.addProperty("updatedAt", System.currentTimeMillis());
                save(scId, root);
                return;
            }
            if ("current".equals(text(step, "state"))) {
                return;
            }
        }
        // Nothing paused and nothing in hand: the next unfinished
        // step becomes current, as it does while work is landing.
        touchPlanCurrent(scId);
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
        task.addProperty("title",
                meaningfulTitle(root, pendingTitles.getOrDefault(scId, "")));
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
        recordStep(scId, tool, label, outcome, detail, "");
    }

    private static void recordStep(String scId, String tool, String label,
                                   String outcome, String detail,
                                   String fingerprint) {
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
        if (fingerprint != null && !fingerprint.isEmpty()) {
            step.addProperty("fp", fingerprint);
        }
        step.addProperty("at", System.currentTimeMillis());
        steps.add(step);
        while (steps.size() > MAX_STEPS) {
            steps.remove(0);
        }
        save(scId, root);
    }

    // ------------------------------------------------------------------
    // The timeline: everything, in order, as it happened

    /**
     * Appends one event to the project's timeline.
     *
     * <p>This is the record the export is built on, and it exists
     * because a summary cannot be debugged. A step that says "wanted to
     * wire AddnoteActivity — failed" tells you nothing about WHY: not
     * which component was named, not which block, not what the screen
     * had. So the timeline carries the whole thing, in order: what the
     * user asked, what the model was given, what the model said, every
     * tool call with its arguments, every refusal with the exact
     * message, every block that was built and every block that was
     * refused, what the user approved or turned down, what read back
     * afterwards, and every provider error.
     *
     * <p>Nothing here is written by the model — it is written by app
     * code as things actually happen, so it is evidence, not a story.
     */
    public static synchronized void record(String scId, String kind,
                                           JsonObject payload) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        JsonElement existing = root.get("log");
        JsonArray log = existing != null && existing.isJsonArray()
                ? existing.getAsJsonArray() : new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("kind", kind == null ? "" : kind);
        entry.addProperty("at", System.currentTimeMillis());
        entry.addProperty("at_text",
                stamp(System.currentTimeMillis()));
        if (payload != null) {
            for (Map.Entry<String, JsonElement> field
                    : payload.entrySet()) {
                // A copy, so a caller reusing its payload object can
                // never change a record that was already written.
                entry.add(field.getKey(), field.getValue().deepCopy());
            }
        }
        log.add(entry);
        while (log.size() > MAX_LOG) {
            log.remove(0);
        }
        if (!root.has("log")) {
            root.add("log", log);
        }
        save(scId, root);
    }

    /** A timeline entry with one field. */
    public static void record(String scId, String kind, String field,
                              String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(field, value == null ? "" : value);
        record(scId, kind, payload);
    }

    /** "19 Oct, 8:14 pm" — a time a person can read. */
    private static String stamp(long at) {
        return new SimpleDateFormat("d MMM, h:mm a", Locale.US)
                .format(new Date(at));
    }

    /** Shortens a value that could be enormous (a tool result). */
    public static String cap(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.trim();
        if (flat.length() <= max) {
            return flat;
        }
        return flat.substring(0, max) + " …[cut "
                + (flat.length() - max) + " chars]";
    }

    /**
     * A task name worth reading: the user's own words; failing that
     * (a canned "please continue" resume carries no subject) the
     * plan's name; last of all a plain fallback.
     */
    private static String meaningfulTitle(JsonObject root, String title) {
        String candidate = title == null ? "" : title.trim();
        if (!candidate.isEmpty()
                && !candidate.regionMatches(true, 0,
                        "please continue the unfinished task", 0,
                        "please continue the unfinished task".length())) {
            return truncate(candidate, 90);
        }
        JsonObject plan = root.getAsJsonObject("plan");
        if (plan != null && !text(plan, "title").isEmpty()) {
            return truncate(text(plan, "title"), 90);
        }
        return candidate.isEmpty() ? "Work in progress"
                : truncate(candidate, 90);
    }

    /** A change the user approved and the app applied. */
    public static synchronized void mutationDone(String scId, String tool,
                                                 String label) {
        recordStep(scId, tool, label, "done", "");
    }

    /** A change the user approved and the app applied. */
    public static synchronized void mutationDone(String scId, String tool,
                                                 String label,
                                                 String fingerprint) {
        recordStep(scId, tool, label, "done", "", fingerprint);
    }

    /**
     * True when this exact change (same tool, same arguments) has
     * already landed in this project — the app-side memory that
     * stops any model from proposing finished work all over again.
     */
    public static synchronized boolean hasDoneFingerprint(String scId,
                                                          String fingerprint) {
        if (scId == null || scId.isEmpty()
                || fingerprint == null || fingerprint.isEmpty()) {
            return false;
        }
        JsonObject root = load(scId);
        for (JsonElement task : root.getAsJsonArray("tasks")) {
            if (!task.isJsonObject()) {
                continue;
            }
            for (JsonElement step
                    : task.getAsJsonObject().getAsJsonArray("steps")) {
                if (step.isJsonObject()
                        && "done".equals(text(step.getAsJsonObject(),
                                "outcome"))
                        && fingerprint.equals(text(step.getAsJsonObject(),
                                "fp"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A read-only check, recorded with the exact thing the agent
     * looked at — so the Task log shows the inspecting as well as
     * the changing, and nothing important vanishes when the small
     * live activity text does.
     */
    public static synchronized void noteCheck(String scId, String tool,
                                              String label) {
        recordStep(scId, tool, label, "checked", "");
    }

    /** A change the user turned down on its approval card. */
    public static synchronized void mutationRejected(String scId,
                                                     String tool,
                                                     String label) {
        recordStep(scId, tool, label, "rejected", "");
    }

    /** A turned-down change, with what the card actually proposed. */
    public static synchronized void mutationRejected(String scId,
                                                     String tool,
                                                     String label,
                                                     String detail) {
        recordStep(scId, tool, label, "rejected", detail);
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
        // Read-only checks recorded along the way do not make it a
        // task on their own either.
        if (done) {
            boolean anyChange = false;
            for (JsonElement element : task.getAsJsonArray("steps")) {
                if (element.isJsonObject()
                        && !"checked".equals(
                                text(element.getAsJsonObject(), "outcome"))) {
                    anyChange = true;
                    break;
                }
            }
            if (!anyChange) {
                tasks.remove(tasks.size() - 1);
                save(scId, root);
                return;
            }
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
    /**
     * Saves the plan — under the contract the user settled: once a
     * plan exists, its steps are the agreement. A later call can
     * only append genuinely new steps (never edit, delete or
     * reorder settled ones); the app enforces that here, whatever
     * the model sends. Returns how many steps were appended (the
     * full count for a first plan).
     */
    public static synchronized int setPlan(String scId, String title,
                                           String stepsText, int doneSteps,
                                           int currentStep) {
        if (scId == null || scId.isEmpty()) {
            return 0;
        }
        ArrayList<String> incoming = new ArrayList<>();
        if (stepsText != null) {
            for (String line : stepsText.split("\\n")) {
                String text = line.trim();
                if (!text.isEmpty() && incoming.size() < 24) {
                    incoming.add(truncate(text, 140));
                }
            }
        }
        JsonObject root = load(scId);
        JsonObject existing = root.getAsJsonObject("plan");
        if (existing != null && existing.has("steps")
                && existing.getAsJsonArray("steps").size() > 0) {
            JsonArray kept = existing.getAsJsonArray("steps");
            java.util.Set<String> known = new java.util.HashSet<>();
            for (JsonElement element : kept) {
                if (element.isJsonObject()) {
                    known.add(text(element.getAsJsonObject(), "text")
                            .toLowerCase(java.util.Locale.ROOT));
                }
            }
            int added = 0;
            StringBuilder addedText = new StringBuilder();
            for (String text : incoming) {
                if (known.contains(text.toLowerCase(
                        java.util.Locale.ROOT))) {
                    continue;
                }
                JsonObject step = new JsonObject();
                step.addProperty("text", text);
                step.addProperty("state", "pending");
                kept.add(step);
                known.add(text.toLowerCase(java.util.Locale.ROOT));
                added++;
                if (addedText.length() > 0) {
                    addedText.append("; ");
                }
                addedText.append(text);
            }
            if (text(existing, "title").isEmpty()
                    && title != null && !title.trim().isEmpty()) {
                existing.addProperty("title", truncate(title.trim(), 90));
            }
            existing.addProperty("updatedAt", System.currentTimeMillis());
            save(scId, root);
            if (added > 0) {
                recordStep(scId, "set_plan",
                        "Plan grew by " + added + " step"
                                + (added == 1 ? "" : "s"),
                        "checked", addedText.toString());
            }
            return added;
        }
        JsonArray steps = new JsonArray();
        for (String text : incoming) {
            JsonObject step = new JsonObject();
            step.addProperty("text", text);
            step.addProperty("state", "pending");
            steps.add(step);
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
        root.add("plan", plan);
        save(scId, root);
        return steps.size();
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

    /**
     * While work is landing, the plan's step in hand is kept honest:
     * if no step is marked current (or paused), the first unfinished
     * step becomes current. Called as changes land, so the Plan page
     * and the strip reflect the work while it happens — completion
     * still only ever comes from set_plan against recorded changes.
     */
    public static synchronized void touchPlanCurrent(String scId) {
        if (scId == null || scId.isEmpty() || !hasPlan(scId)) {
            return;
        }
        JsonObject root = load(scId);
        JsonObject plan = root.getAsJsonObject("plan");
        JsonArray steps = plan.getAsJsonArray("steps");
        for (JsonElement element : steps) {
            String state = text(element.getAsJsonObject(), "state");
            if ("current".equals(state) || "stopped".equals(state)) {
                return;
            }
        }
        for (JsonElement element : steps) {
            JsonObject step = element.getAsJsonObject();
            if ("pending".equals(text(step, "state"))) {
                step.addProperty("state", "current");
                plan.addProperty("updatedAt", System.currentTimeMillis());
                save(scId, root);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Drafts from a conversation that has no project yet

    private static String draftStyleBrief = "";
    private static String draftPlanTitle = "";
    private static String draftPlanSteps = "";

    /**
     * Whether a style direction has been agreed for this work: saved
     * on the project, or held as a draft for a project that does not
     * exist yet. This is what the "design before you build" gate asks —
     * a builder must not start an app the user has never been shown a
     * look for.
     */
    public static synchronized boolean hasStyleBriefOrDraft(String scId) {
        return !draftStyleBrief.isEmpty()
                || !styleBrief(scId == null ? "" : scId).trim().isEmpty();
    }

    /**
     * Whether an implementation plan has been agreed for this work:
     * saved on the project, or held as a draft.
     */
    public static synchronized boolean hasPlanOrDraft(String scId) {
        return !draftPlanSteps.isEmpty()
                || hasPlan(scId == null ? "" : scId);
    }

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
                String outcome = text(step, "outcome");
                // What the agent looked at is part of the record too,
                // but it reads as inspecting, not as work done.
                String mark = "done".equals(outcome) ? "✓ "
                        : "checked".equals(outcome) ? "· "
                        : "rejected".equals(outcome) ? "✕ "
                        : "failed".equals(outcome) ? "! " : "· ";
                out.append("\n ").append(mark)
                        .append(text(step, "label"));
                if (!"checked".equals(outcome)) {
                    out.append(" [").append(outcome).append("]");
                }
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
                    // Reading the project is not a step of the work:
                    // progress counts the changes, not the looks.
                    if ("checked".equals(text(element.getAsJsonObject(),
                            "outcome"))) {
                        continue;
                    }
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
