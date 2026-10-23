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

    private static JsonObject emptyRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("styleBrief", "");
        root.add("tasks", new JsonArray());
        root.add("plan", new JsonObject());
        root.add("log", new JsonArray());
        return root;
    }

    private static String compactAlnum(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean namesMatchProject(
            String candidate, SketchwareProjects.ProjectInfo info) {
        if (candidate == null || info == null) {
            return false;
        }
        String c = compactAlnum(candidate);
        if (c.isEmpty()) {
            return false;
        }
        String ws = compactAlnum(info.name);
        String app = compactAlnum(info.appName);
        return (!ws.isEmpty() && (c.contains(ws) || ws.contains(c)))
                || (!app.isEmpty() && (c.contains(app) || app.contains(c)));
    }

    /**
     * Purges ghost tasks/plans left behind when Sketchware Pro deletes a
     * project and later reuses its numeric {@code sc_id} for a new project.
     * Returns {@code true} if {@code root} was healed and should be re-saved.
     */
    private static boolean healReusedScId(
            String scId, JsonObject root, SketchwareProjects.ProjectInfo info) {
        if (info == null) {
            return false;
        }
        boolean changed = false;
        JsonArray tasks = root.getAsJsonArray("tasks");
        int lastCreateIdx = -1;
        long createStartedAt = 0L;
        for (int i = 0; i < tasks.size(); i++) {
            JsonElement el = tasks.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject task = el.getAsJsonObject();
            JsonElement stepsEl = task.get("steps");
            if (stepsEl != null && stepsEl.isJsonArray()) {
                for (JsonElement sEl : stepsEl.getAsJsonArray()) {
                    if (!sEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject step = sEl.getAsJsonObject();
                    if ("create_project".equals(text(step, "tool"))
                            && "done".equals(text(step, "outcome"))) {
                        lastCreateIdx = i;
                        createStartedAt = number(task, "startedAt");
                        break;
                    }
                }
            }
        }
        String ghostPlanTitle = "";
        JsonObject planObj = root.getAsJsonObject("plan");
        if (planObj != null && planObj.has("title")) {
            String pTitle = text(planObj, "title");
            if (!pTitle.isEmpty() && !namesMatchProject(pTitle, info)) {
                // Check if step 1 explicitly names creating a different project
                // or if an earlier deleted project occupied this sc_id.
                boolean planNamesOtherProject = lastCreateIdx > 0;
                if (!planNamesOtherProject && planObj.has("steps")
                        && planObj.get("steps").isJsonArray()) {
                    JsonArray pSteps = planObj.getAsJsonArray("steps");
                    if (pSteps.size() > 0 && pSteps.get(0).isJsonObject()) {
                        String firstStep = text(
                                pSteps.get(0).getAsJsonObject(), "text");
                        if (firstStep.toLowerCase(Locale.ROOT)
                                .contains("create project")
                                && !namesMatchProject(firstStep, info)) {
                            planNamesOtherProject = true;
                        }
                    }
                }
                if (planNamesOtherProject) {
                    ghostPlanTitle = pTitle;
                    root.add("plan", new JsonObject());
                    changed = true;
                }
            }
        }
        if (lastCreateIdx > 0) {
            JsonArray kept = new JsonArray();
            int nextId = 1;
            for (int i = lastCreateIdx; i < tasks.size(); i++) {
                JsonElement el = tasks.get(i);
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject t = el.getAsJsonObject();
                t.addProperty("id", nextId++);
                if (i == lastCreateIdx && !ghostPlanTitle.isEmpty()
                        && ghostPlanTitle.equals(text(t, "title"))) {
                    String cleanTitle = !info.appName.isEmpty()
                            ? info.appName : info.name;
                    if (!cleanTitle.isEmpty()) {
                        t.addProperty("title", cleanTitle);
                    }
                }
                kept.add(t);
            }
            root.add("tasks", kept);
            long briefAt = number(root, "styleBriefAt");
            if (createStartedAt > 0L && briefAt > 0L
                    && briefAt < createStartedAt - 600_000L) {
                root.addProperty("styleBrief", "");
                root.addProperty("styleBriefAt", 0L);
            }
            changed = true;
        }
        if (!info.packageName.isEmpty()
                && !info.packageName.equals(text(root, "projectPackage"))) {
            root.addProperty("projectPackage", info.packageName);
            changed = true;
        }
        return changed;
    }

    /** Wipes any existing journal record for {@code scId} (e.g. on project creation). */
    public static synchronized void resetProject(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        pendingTitles.remove(scId);
        File f = file(scId);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** The whole record for a project (never null). */
    public static synchronized JsonObject load(String scId) {
        JsonObject root = emptyRoot();
        if (scId == null || scId.isEmpty()) {
            return root;
        }
        File file = file(scId);
        if (!file.exists()) {
            return root;
        }
        SketchwareProjects.ProjectInfo info = SketchwareProjects.project(scId);
        try (FileReader reader = new FileReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject stored = parsed.getAsJsonObject();
                String storedPkg = text(stored, "projectPackage");
                if (info != null && !info.packageName.isEmpty()
                        && !storedPkg.isEmpty()
                        && !storedPkg.equals(info.packageName)) {
                    // Sketchware reused a deleted project's numeric sc_id for
                    // a different package: discard the deleted project's record.
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    return emptyRoot();
                }
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
                if (healReusedScId(scId, root, info)) {
                    save(scId, root);
                }
            }
        } catch (Exception ignored) {
            // A damaged record reads as empty rather than failing work.
        }
        return root;
    }

    private static void save(String scId, JsonObject root) {
        try {
            SketchwareProjects.ProjectInfo info =
                    SketchwareProjects.project(scId);
            if (info != null && !info.packageName.isEmpty()) {
                root.addProperty("projectPackage", info.packageName);
            }
        } catch (Exception ignored) {
        }
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
        // The full text of a refusal lives in the timeline; the task
        // log line only has to be long enough to be readable, and 200
        // cut every validation message mid-sentence.
        step.addProperty("detail", truncate(
                detail == null ? "" : detail, 400));
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
                        "please continue the unfinished task".length())
                && !candidate.regionMatches(true, 0,
                        "resume unfinished work", 0,
                        "resume unfinished work".length())) {
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

    // ------------------------------------------------------------------
    // Read cache: facts a failed turn already learned

    /**
     * Remembers the read-only facts (screens, event logic, resources)
     * a failed turn had already fetched, so a Tap-to-retry — or the
     * next turn — starts from them instead of spending the same tool
     * calls again. Cleared when a mutation lands or a turn succeeds,
     * because both make the facts stale or redundant.
     */
    public static synchronized void setReadCache(String scId,
                                                 String facts) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        root.addProperty("readCache",
                truncate(facts == null ? "" : facts, 2400));
        save(scId, root);
    }

    /** The remembered read-only facts of the last failed turn. */
    public static synchronized String readCache(String scId) {
        return text(load(scId == null ? "" : scId), "readCache");
    }

    /** Forgets the read cache (project changed or turn succeeded). */
    public static synchronized void clearReadCache(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        if (root.has("readCache")) {
            root.addProperty("readCache", "");
            save(scId, root);
        }
    }

    public static synchronized void setStyleBrief(String scId, String brief) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        // The cap is generous on purpose. It used to be 600 characters,
        // which cut every real brief in half mid-sentence — a style
        // with its headings, hexes and sizes is longer than that, and a
        // cut-off style is a style the builder cannot follow.
        root.addProperty("styleBrief",
                truncate(brief == null ? "" : brief.trim(), 4000));
        root.addProperty("styleBriefAt", System.currentTimeMillis());
        save(scId, root);
        // Unified with the Design system: the brief's first lines are
        // the Mood & Audience summary, so the Design system page shows
        // one direction instead of two files that can disagree.
        String summary = summarizeBriefForDesignSystem(brief);
        if (!summary.isEmpty()) {
            setDesignSystemSummary(scId, summary);
        }
    }

    /**
     * The Mood & Audience line of a style brief: the first non-empty
     * line, plus any Colours line's words (hexes already live in the
     * theme itself), capped for a subtitle.
     */
    private static String summarizeBriefForDesignSystem(String brief) {
        if (brief == null) {
            return "";
        }
        for (String line : brief.split("\\r?\\n")) {
            String t = line.replaceFirst("^#+\\s*", "").trim();
            if (!t.isEmpty()) {
                return truncate(t, 160);
            }
        }
        return truncate(brief.trim(), 160);
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
     * update progress ({@code doneSteps} / {@code currentStep}) and
     * append genuinely new steps (never edit, delete or reorder
     * settled ones); the app enforces that here, whatever the model
     * sends. Returns how many steps were appended (the full count
     * for a first plan).
     */
    public static synchronized int setPlan(String scId, String title,
                                           String stepsText, int doneSteps,
                                           int currentStep) {
        if (scId == null || scId.isEmpty()) {
            return 0;
        }
        ArrayList<String> incomingRaw = new ArrayList<>();
        if (stepsText != null) {
            for (String line : stepsText.split("\\n")) {
                String text = line.trim();
                if (!text.isEmpty() && incomingRaw.size() < 40) {
                    incomingRaw.add(truncate(text, 400));
                }
            }
        }
        JsonObject root = load(scId);
        JsonObject existing = root.getAsJsonObject("plan");
        if (existing != null && existing.has("steps")
                && existing.getAsJsonArray("steps").size() > 0) {
            JsonArray kept = dedupePlanSteps(
                    existing.getAsJsonArray("steps"));
            existing.add("steps", kept);
            int baseCount = kept.size();
            java.util.Set<String> knownKeys = new java.util.HashSet<>();
            ArrayList<String> existingClean = new ArrayList<>();
            for (JsonElement element : kept) {
                if (element.isJsonObject()) {
                    String stepText = cleanStepText(
                            text(element.getAsJsonObject(), "text"));
                    knownKeys.add(normalizeStepKey(stepText));
                    existingClean.add(stepText);
                }
            }
            int added = 0;
            StringBuilder addedText = new StringBuilder();
            for (String raw : incomingRaw) {
                int leadNum = parseLeadingStepNumber(raw);
                String cleaned = cleanStepText(raw);
                if (cleaned.isEmpty()) {
                    continue;
                }
                String key = normalizeStepKey(cleaned);
                if (key.isEmpty() || knownKeys.contains(key)) {
                    continue;
                }
                // If the model re-sent its numbered step list ("1. ...",
                // "2. ...", "3. ...") with slightly paraphrased wording
                // while updating done_steps, steps numbered within the
                // settled range 1..baseCount are re-statements of settled
                // steps — never new steps to append at the bottom.
                if (leadNum > 0 && leadNum <= baseCount) {
                    continue;
                }
                if (matchesExistingStepClosely(cleaned, existingClean)) {
                    continue;
                }
                JsonObject step = new JsonObject();
                step.addProperty("text", cleaned);
                step.addProperty("state", "pending");
                kept.add(step);
                knownKeys.add(key);
                existingClean.add(cleaned);
                added++;
                if (addedText.length() > 0) {
                    addedText.append("; ");
                }
                addedText.append(cleaned);
            }
            if (text(existing, "title").isEmpty()
                    && title != null && !title.trim().isEmpty()) {
                existing.addProperty("title", truncate(title.trim(), 90));
            }
            applyPlanProgress(scId, kept, doneSteps, currentStep);
            existing.addProperty("updatedAt", System.currentTimeMillis());
            save(scId, root);
            int[] progress = countPlanProgress(kept);
            JsonObject recorded = new JsonObject();
            recorded.addProperty("done_steps", progress[0]);
            recorded.addProperty("total_steps", progress[1]);
            recorded.addProperty("added_steps", added);
            record(scId, "plan_updated", recorded);
            if (added > 0) {
                recordStep(scId, "set_plan",
                        "Plan grew by " + added + " step"
                                + (added == 1 ? "" : "s"),
                        "checked", addedText.toString());
            }
            return added;
        }
        JsonArray steps = new JsonArray();
        java.util.Set<String> seenKeys = new java.util.HashSet<>();
        for (String raw : incomingRaw) {
            String cleaned = cleanStepText(raw);
            String key = normalizeStepKey(cleaned);
            if (cleaned.isEmpty() || key.isEmpty()
                    || seenKeys.contains(key)) {
                continue;
            }
            seenKeys.add(key);
            JsonObject step = new JsonObject();
            step.addProperty("text", cleaned);
            step.addProperty("state", "pending");
            steps.add(step);
        }
        applyPlanProgress(scId, steps, doneSteps, currentStep);
        JsonObject plan = new JsonObject();
        plan.addProperty("title",
                truncate(title == null ? "" : title.trim(), 90));
        plan.add("steps", steps);
        plan.addProperty("updatedAt", System.currentTimeMillis());
        root.add("plan", plan);
        save(scId, root);
        int[] progress = countPlanProgress(steps);
        JsonObject recorded = new JsonObject();
        recorded.addProperty("title", text(plan, "title"));
        recorded.addProperty("done_steps", progress[0]);
        recorded.addProperty("total_steps", progress[1]);
        record(scId, "plan_saved", recorded);
        return steps.size();
    }

    /** Parses a leading 1-based step number ("2. ...", "Step 3: ..."), or -1. */
    private static int parseLeadingStepNumber(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.trim();
        // Strip leading status glyphs if the model echoed them back.
        while (!s.isEmpty() && ("✓✔●○•-*❚".indexOf(s.charAt(0)) >= 0
                || Character.isWhitespace(s.charAt(0)))) {
            s = s.substring(1).trim();
        }
        if (s.regionMatches(true, 0, "step ", 0, 5)) {
            s = s.substring(5).trim();
        } else if (s.startsWith("[") && s.indexOf(']') > 1) {
            String inside = s.substring(1, s.indexOf(']')).trim();
            if ("x".equalsIgnoreCase(inside) || inside.isEmpty()) {
                s = s.substring(s.indexOf(']') + 1).trim();
            }
        }
        int digits = 0;
        while (digits < s.length() && Character.isDigit(s.charAt(digits))) {
            digits++;
        }
        if (digits > 0 && digits <= 2 && digits < s.length()) {
            char next = s.charAt(digits);
            if (next == '.' || next == ')' || next == ':' || next == '-') {
                try {
                    return Integer.parseInt(s.substring(0, digits));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** Strips leading bullet/checkbox/step-number prefixes so steps stay clean. */
    public static String cleanStepText(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (!s.isEmpty() && ("✓✔●○•-*❚".indexOf(s.charAt(0)) >= 0
                || Character.isWhitespace(s.charAt(0)))) {
            s = s.substring(1).trim();
        }
        if (s.startsWith("[") && s.indexOf(']') > 0 && s.indexOf(']') <= 4) {
            s = s.substring(s.indexOf(']') + 1).trim();
        }
        if (s.regionMatches(true, 0, "step ", 0, 5)) {
            String rest = s.substring(5).trim();
            int d = 0;
            while (d < rest.length() && Character.isDigit(rest.charAt(d))) {
                d++;
            }
            if (d > 0 && d < rest.length()
                    && (rest.charAt(d) == ':' || rest.charAt(d) == '.'
                            || rest.charAt(d) == ')' || rest.charAt(d) == '-')) {
                s = rest.substring(d + 1).trim();
            }
        } else {
            int d = 0;
            while (d < s.length() && Character.isDigit(s.charAt(d))) {
                d++;
            }
            if (d > 0 && d <= 2 && d < s.length()
                    && (s.charAt(d) == '.' || s.charAt(d) == ')')) {
                s = s.substring(d + 1).trim();
            }
        }
        return s.trim();
    }

    private static String normalizeStepKey(String stepText) {
        if (stepText == null) {
            return "";
        }
        String cleaned = cleanStepText(stepText)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return cleaned;
    }

    private static boolean matchesExistingStepClosely(
            String candidate, ArrayList<String> existingClean) {
        java.util.Set<String> candWords = significantWords(candidate);
        if (candWords.size() < 3) {
            return false;
        }
        for (String existing : existingClean) {
            java.util.Set<String> exWords = significantWords(existing);
            if (exWords.isEmpty()) {
                continue;
            }
            int shared = 0;
            for (String w : candWords) {
                if (exWords.contains(w)) {
                    shared++;
                }
            }
            int smaller = Math.min(candWords.size(), exWords.size());
            if (smaller >= 3 && shared * 100 >= smaller * 75) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Set<String> significantWords(String text) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String token : normalizeStepKey(text).split(" ")) {
            if (token.length() >= 4
                    && !"with".equals(token) && !"from".equals(token)
                    && !"that".equals(token) && !"this".equals(token)
                    && !"into".equals(token) && !"screen".equals(token)) {
                out.add(token);
            }
        }
        return out;
    }

    /** Heals plans that previously accumulated duplicate numbered steps. */
    private static JsonArray dedupePlanSteps(JsonArray rawSteps) {
        JsonArray out = new JsonArray();
        if (rawSteps == null) {
            return out;
        }
        java.util.Map<String, Integer> keyToIndex = new java.util.HashMap<>();
        ArrayList<String> cleanTexts = new ArrayList<>();
        int maxNaturalNumber = 0;
        for (int i = 0; i < rawSteps.size(); i++) {
            JsonElement el = rawSteps.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String rawText = text(obj, "text");
            int leadNum = parseLeadingStepNumber(rawText);
            String cleaned = cleanStepText(rawText);
            String key = normalizeStepKey(cleaned);
            if (cleaned.isEmpty() || key.isEmpty()) {
                continue;
            }
            String state = text(obj, "state");
            Integer prevIdx = keyToIndex.get(key);
            if (prevIdx != null) {
                JsonObject prev = out.get(prevIdx).getAsJsonObject();
                prev.addProperty("state",
                        higherStepState(text(prev, "state"), state));
                continue;
            }
            // Detect legacy re-appended numbered steps ("1, 2, 3, 2, 3..."):
            // when leadNum jumps backwards into 1..maxNaturalNumber and
            // shares words with that slot, merge state instead of keeping
            // the duplicate.
            if (leadNum > 0 && leadNum <= maxNaturalNumber
                    && leadNum - 1 < out.size()) {
                JsonObject slot = out.get(leadNum - 1).getAsJsonObject();
                slot.addProperty("state",
                        higherStepState(text(slot, "state"), state));
                continue;
            }
            if (leadNum > maxNaturalNumber) {
                maxNaturalNumber = leadNum;
            } else {
                maxNaturalNumber = Math.max(maxNaturalNumber, out.size() + 1);
            }
            JsonObject copy = new JsonObject();
            copy.addProperty("text", cleaned);
            copy.addProperty("state", state.isEmpty() ? "pending" : state);
            keyToIndex.put(key, out.size());
            cleanTexts.add(cleaned);
            out.add(copy);
        }
        return out;
    }

    private static String higherStepState(String a, String b) {
        return stepRank(b) > stepRank(a) ? b : a;
    }

    private static int stepRank(String state) {
        if ("done".equals(state)) {
            return 4;
        }
        if ("current".equals(state)) {
            return 3;
        }
        if ("stopped".equals(state)) {
            return 2;
        }
        return 1;
    }

    private static void applyPlanProgress(String scId, JsonArray steps,
                                          int doneSteps, int currentStep) {
        if (steps == null || steps.size() == 0) {
            return;
        }
        int existingDone = 0;
        int existingCurrent = -1;
        for (int i = 0; i < steps.size(); i++) {
            String st = text(steps.get(i).getAsJsonObject(), "state");
            if ("done".equals(st)) {
                existingDone++;
            } else if (existingCurrent < 0
                    && ("current".equals(st) || "stopped".equals(st))) {
                existingCurrent = i;
            }
        }
        boolean hasRealWork = doneStepCount(scId) > 0
                || projectHasWorkOnDisk(scId);
        int targetDone = existingDone;
        if (doneSteps >= 0) {
            int requested = Math.min(doneSteps, steps.size());
            if (hasRealWork || requested == 0) {
                targetDone = Math.max(existingDone, requested);
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            JsonObject step = steps.get(i).getAsJsonObject();
            step.addProperty("state", i < targetDone ? "done" : "pending");
        }
        if (targetDone < steps.size()) {
            int active = currentStep >= targetDone
                    && currentStep < steps.size()
                    ? currentStep
                    : (existingCurrent >= targetDone
                            && existingCurrent < steps.size()
                            ? existingCurrent : targetDone);
            steps.get(active).getAsJsonObject()
                    .addProperty("state", "current");
        }
    }

    private static boolean projectHasWorkOnDisk(String scId) {
        try {
            for (com.besome.sketch.beans.ProjectFileBean activity
                    : SketchwareProjects.activities(scId)) {
                if (!SketchwareProjects.screenBeans(scId, activity).isEmpty()
                        || !SketchwareProjects.eventLines(scId, activity)
                                .isEmpty()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int[] countPlanProgress(JsonArray steps) {
        int done = 0;
        int total = steps == null ? 0 : steps.size();
        if (steps != null) {
            for (JsonElement el : steps) {
                if (el.isJsonObject()
                        && "done".equals(
                                text(el.getAsJsonObject(), "state"))) {
                    done++;
                }
            }
        }
        return new int[]{done, total};
    }

    /** Returns {done, total} for the project's plan (0, 0 when none). */
    public static synchronized int[] planProgress(String scId) {
        JsonObject plan = plan(scId);
        if (!plan.has("steps")) {
            return new int[]{0, 0};
        }
        return countPlanProgress(plan.getAsJsonArray("steps"));
    }

    /**
     * Toggles one step of the plan between done and pending/current from
     * the Plan dialog, keeping the saved progress in sync on disk.
     */
    public static synchronized void togglePlanStepDone(String scId,
                                                       int index) {
        if (scId == null || scId.isEmpty() || index < 0) {
            return;
        }
        JsonObject root = load(scId);
        JsonObject plan = root.getAsJsonObject("plan");
        if (plan == null || !plan.has("steps")) {
            return;
        }
        JsonArray steps = dedupePlanSteps(plan.getAsJsonArray("steps"));
        plan.add("steps", steps);
        if (index >= steps.size()) {
            return;
        }
        JsonObject target = steps.get(index).getAsJsonObject();
        boolean wasDone = "done".equals(text(target, "state"));
        target.addProperty("state", wasDone ? "pending" : "done");
        boolean assignedCurrent = false;
        for (int i = 0; i < steps.size(); i++) {
            JsonObject step = steps.get(i).getAsJsonObject();
            String st = text(step, "state");
            if ("done".equals(st)) {
                continue;
            }
            if (!assignedCurrent) {
                step.addProperty("state", "current");
                assignedCurrent = true;
            } else if ("current".equals(st)) {
                step.addProperty("state", "pending");
            }
        }
        plan.addProperty("updatedAt", System.currentTimeMillis());
        save(scId, root);
    }

    /**
     * When {@code build_project} succeeds and only the final build/check
     * milestone of the plan remains, marks that final step done so a green
     * build never leaves the last step hanging at "in progress".
     */
    public static synchronized void completeFinalBuildStepIfReady(
            String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        JsonObject plan = root.getAsJsonObject("plan");
        if (plan == null || !plan.has("steps")) {
            return;
        }
        JsonArray steps = dedupePlanSteps(plan.getAsJsonArray("steps"));
        plan.add("steps", steps);
        if (steps.size() == 0) {
            return;
        }
        int done = 0;
        for (int i = 0; i < steps.size(); i++) {
            if ("done".equals(
                    text(steps.get(i).getAsJsonObject(), "state"))) {
                done++;
            }
        }
        JsonObject last = steps.get(steps.size() - 1).getAsJsonObject();
        String lastLower = text(last, "text").toLowerCase(Locale.ROOT);
        boolean lastIsBuildMilestone = lastLower.contains("build")
                || lastLower.contains("compile")
                || lastLower.contains("apk")
                || lastLower.contains("verify");
        if (done == steps.size() - 1 && (lastIsBuildMilestone
                || !"done".equals(text(last, "state")))) {
            last.addProperty("state", "done");
            plan.addProperty("updatedAt", System.currentTimeMillis());
            save(scId, root);
        }
    }

    /** The saved plan object (title, steps, updatedAt); never null. */
    public static synchronized JsonObject plan(String scId) {
        if (scId == null || scId.isEmpty()) {
            return new JsonObject();
        }
        JsonObject root = load(scId);
        JsonObject plan = root.getAsJsonObject("plan");
        if (plan == null) {
            return new JsonObject();
        }
        if (plan.has("steps")) {
            JsonArray raw = plan.getAsJsonArray("steps");
            JsonArray cleaned = dedupePlanSteps(raw);
            if (cleaned.size() != raw.size()) {
                plan.add("steps", cleaned);
                save(scId, root);
            }
        }
        return plan;
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
        JsonArray steps = plan.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            JsonElement element = steps.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            String state = text(step, "state");
            String mark = "done".equals(state) ? "✓ "
                    : "current".equals(state) ? "● "
                    : "stopped".equals(state) ? "❚❚ " : "○ ";
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(mark).append(i + 1).append(". ")
                    .append(cleanStepText(text(step, "text")));
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
        String pTitle = text(plan, "title");
        SketchwareProjects.ProjectInfo toInfo =
                SketchwareProjects.project(toScId);
        SketchwareProjects.ProjectInfo fromInfo =
                SketchwareProjects.project(fromScId);
        boolean matchesNewProject = !pTitle.isEmpty()
                && namesMatchProject(pTitle, toInfo)
                && !namesMatchProject(pTitle, fromInfo);
        if (number(plan, "updatedAt") < sinceMillis && !matchesNewProject) {
            return;
        }
        JsonObject to = load(toScId);
        to.add("plan", plan);
        save(toScId, to);
        from.add("plan", new JsonObject());
        save(fromScId, from);
    }

    /** Moves a design system agreed for {@code toScId} off {@code fromScId}. */
    public static synchronized void moveDesignSystemIfAgreedSince(
            String fromScId, String toScId, long sinceMillis) {
        if (fromScId == null || fromScId.isEmpty()
                || toScId == null || toScId.isEmpty()
                || fromScId.equals(toScId)
                || !hasDesignSystem(fromScId) || hasDesignSystem(toScId)) {
            return;
        }
        JsonObject from = load(fromScId);
        JsonElement dsEl = from.get("designSystem");
        if (dsEl == null || !dsEl.isJsonObject()) {
            return;
        }
        JsonObject ds = dsEl.getAsJsonObject();
        String dsTitle = text(ds, "title");
        SketchwareProjects.ProjectInfo toInfo =
                SketchwareProjects.project(toScId);
        SketchwareProjects.ProjectInfo fromInfo =
                SketchwareProjects.project(fromScId);
        boolean matchesNewProject = !dsTitle.isEmpty()
                && namesMatchProject(dsTitle, toInfo)
                && !namesMatchProject(dsTitle, fromInfo);
        if (number(ds, "updatedAt") < sinceMillis && !matchesNewProject) {
            return;
        }
        JsonObject to = load(toScId);
        to.add("designSystem", ds);
        save(toScId, to);
        from.remove("designSystem");
        save(fromScId, from);
        try {
            OpenUiThemeEngine.materializeToProject(toScId, ds);
        } catch (Throwable ignored) {
        }
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
    private static String draftDesignTitle = "";
    private static JsonArray draftDesignTokens = new JsonArray();
    private static JsonObject draftDesignSystemObj = null;

    /**
     * Remembers a design system agreed before any project was chosen;
     * it lands in the project this work creates.
     */
    public static synchronized void setDraftDesignSystem(String title,
                                                         JsonArray tokens) {
        draftDesignTitle = title == null ? "" : title.trim();
        draftDesignTokens = tokens == null
                ? new JsonArray() : tokens.deepCopy();
        JsonObject seed = new JsonObject();
        seed.addProperty("title", draftDesignTitle);
        seed.add("tokens", draftDesignTokens);
        OpenUiThemeEngine.ResolvedTheme resolved =
                OpenUiThemeEngine.resolveTheme(seed);
        draftDesignSystemObj = OpenUiThemeEngine.buildDesignSystemJson(
                resolved, draftDesignTokens);
    }

    /** Remembers a full OpenUI design system agreed before project creation. */
    public static synchronized void setDraftDesignSystemObject(
            JsonObject system) {
        if (system == null) {
            draftDesignSystemObj = null;
            draftDesignTitle = "";
            draftDesignTokens = new JsonArray();
            return;
        }
        draftDesignSystemObj = system.deepCopy();
        draftDesignTitle = text(system, "title");
        JsonElement tok = system.get("tokens");
        draftDesignTokens = tok != null && tok.isJsonArray()
                ? tok.getAsJsonArray().deepCopy() : new JsonArray();
    }

    /** Current draft design system (or empty object when none). */
    public static synchronized JsonObject draftOrEmptyDesignSystem() {
        return draftDesignSystemObj != null
                ? draftDesignSystemObj.deepCopy() : new JsonObject();
    }

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

    // ------------------------------------------------------------------
    // The design system: the project's components, by name

    /**
     * The project's design system: one entry per component — primary
     * button, card, list row, input, screen, title, body — each holding
     * the measurements every instance of it uses.
     *
     * <p>A style says "sunny and playful". A design system says what a
     * button IS in that style: 56dp tall, orange #FF8A3D, 12dp corners,
     * 16sp bold white text, 16dp of padding. It is stored as structured
     * entries rather than prose because it exists to be looked up, not
     * read: the same button on the fifth screen has to match the one on
     * the first, and a model asked to remember a number across a dozen
     * tool calls will not.
     */
    public static synchronized JsonObject designSystem(String scId) {
        JsonObject root = load(scId);
        JsonElement element = root.get("designSystem");
        return element != null && element.isJsonObject()
                ? element.getAsJsonObject() : new JsonObject();
    }

    /** Saves the whole design system (a coherent set, replaced as one). */
    public static synchronized void setDesignSystem(String scId,
                                                    String title,
                                                    JsonArray tokens) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject seed = new JsonObject();
        seed.addProperty("title",
                truncate(title == null ? "" : title.trim(), 120));
        seed.add("tokens", tokens == null ? new JsonArray() : tokens);
        OpenUiThemeEngine.ResolvedTheme resolved =
                OpenUiThemeEngine.resolveTheme(seed);
        JsonObject system = OpenUiThemeEngine.buildDesignSystemJson(
                resolved, tokens);
        setDesignSystemObject(scId, system);
    }

    /** Saves a complete OpenUI designSystem object onto {@code scId}. */
    public static synchronized void setDesignSystemObject(String scId,
                                                          JsonObject system) {
        if (scId == null || scId.isEmpty() || system == null) {
            return;
        }
        JsonObject root = load(scId);
        pushDesignHistory(root);
        JsonObject copy = system.deepCopy();
        copy.addProperty("updatedAt", System.currentTimeMillis());
        root.add("designSystem", copy);
        save(scId, root);
        record(scId, "design_system_saved", "title", text(copy, "title"));
    }

    /** How many past themes a project may step back through. */
    private static final int MAX_DESIGN_HISTORY = 10;

    /**
     * Before a theme changes, remembers the outgoing one: the very
     * first theme a project agrees is pinned forever as
     * {@code originalDesignSystem} (so preset experiments can never
     * trap the user away from Day-1 colours), and the last
     * {@value MAX_DESIGN_HISTORY} themes stack up for step-by-step
     * Undo. Callers save {@code root} themselves afterwards.
     */
    private static void pushDesignHistory(JsonObject root) {
        JsonElement currentElement = root.get("designSystem");
        if (currentElement == null || !currentElement.isJsonObject()) {
            return;
        }
        JsonObject current = currentElement.getAsJsonObject();
        boolean hasTheme = current.has("openuiTheme")
                && current.get("openuiTheme").isJsonObject();
        boolean hasTokens = current.has("tokens")
                && current.get("tokens").isJsonArray()
                && current.getAsJsonArray("tokens").size() > 0;
        if (!hasTheme && !hasTokens) {
            return;
        }
        if (!root.has("originalDesignSystem")
                || !root.get("originalDesignSystem").isJsonObject()) {
            root.add("originalDesignSystem", current.deepCopy());
        }
        JsonArray history = root.has("designSystemHistory")
                && root.get("designSystemHistory").isJsonArray()
                ? root.getAsJsonArray("designSystemHistory")
                : new JsonArray();
        history.add(current.deepCopy());
        while (history.size() > MAX_DESIGN_HISTORY) {
            history.remove(0);
        }
        root.add("designSystemHistory", history);
    }

    /** The project's first-ever agreed theme, or an empty object. */
    public static synchronized JsonObject originalDesignSystem(String scId) {
        JsonObject root = load(scId == null ? "" : scId);
        JsonElement element = root.get("originalDesignSystem");
        return element != null && element.isJsonObject()
                ? element.getAsJsonObject() : new JsonObject();
    }

    /** Whether the project still remembers its first theme. */
    public static synchronized boolean hasOriginalDesignSystem(String scId) {
        return originalDesignSystem(scId).size() > 0;
    }

    /** How many undo steps the theme currently has. */
    public static synchronized int designSystemUndoCount(String scId) {
        JsonObject root = load(scId == null ? "" : scId);
        JsonArray history = root.has("designSystemHistory")
                && root.get("designSystemHistory").isJsonArray()
                ? root.getAsJsonArray("designSystemHistory")
                : new JsonArray();
        return history.size();
    }

    /**
     * Steps back one theme: the most recent previous design system
     * becomes active again (and the outgoing one is kept on the same
     * stack, so Undo and Redo walk the same ring). Returns the
     * restored system, or null when there is nothing to undo.
     */
    public static synchronized JsonObject undoDesignSystem(String scId) {
        if (scId == null || scId.isEmpty()) {
            return null;
        }
        JsonObject root = load(scId);
        JsonArray history = root.has("designSystemHistory")
                && root.get("designSystemHistory").isJsonArray()
                ? root.getAsJsonArray("designSystemHistory")
                : new JsonArray();
        if (history.size() == 0) {
            return null;
        }
        JsonObject previous =
                history.get(history.size() - 1).getAsJsonObject();
        history.remove(history.size() - 1);
        JsonElement currentElement = root.get("designSystem");
        if (currentElement != null && currentElement.isJsonObject()
                && (currentElement.getAsJsonObject().has("openuiTheme")
                        || (currentElement.getAsJsonObject().has("tokens")
                                && currentElement.getAsJsonObject()
                                        .get("tokens").isJsonArray()
                                && currentElement.getAsJsonObject()
                                        .getAsJsonArray("tokens")
                                        .size() > 0))) {
            history.add(currentElement.getAsJsonObject().deepCopy());
            while (history.size() > MAX_DESIGN_HISTORY) {
                history.remove(0);
            }
        }
        root.add("designSystemHistory", history);
        JsonObject restored = previous.deepCopy();
        restored.addProperty("updatedAt", System.currentTimeMillis());
        root.add("designSystem", restored);
        save(scId, root);
        record(scId, "design_system_undo", "title", text(restored, "title"));
        return restored;
    }

    /**
     * Puts the project's first-ever theme back as the active one
     * (the current theme is pushed onto the undo stack first, so
     * nothing is lost). Returns the restored original, or null when
     * the project has no original saved.
     */
    public static synchronized JsonObject restoreOriginalDesignSystem(
            String scId) {
        if (scId == null || scId.isEmpty()) {
            return null;
        }
        JsonObject original = originalDesignSystem(scId);
        if (original.size() == 0) {
            return null;
        }
        JsonObject root = load(scId);
        pushDesignHistory(root);
        JsonObject restored = original.deepCopy();
        restored.addProperty("updatedAt", System.currentTimeMillis());
        root.add("designSystem", restored);
        save(scId, root);
        record(scId, "design_system_original_restored", "title",
                text(restored, "title"));
        return restored;
    }

    /** The human Mood & Audience line inside the Design system. */
    public static synchronized String designSystemSummary(String scId) {
        JsonObject system = designSystem(scId == null ? "" : scId);
        return text(system, "summary");
    }

    /** Saves the Mood & Audience line inside the Design system. */
    public static synchronized void setDesignSystemSummary(String scId,
                                                           String summary) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
        JsonObject system = root.has("designSystem")
                && root.get("designSystem").isJsonObject()
                ? root.getAsJsonObject("designSystem") : new JsonObject();
        system.addProperty("summary",
                truncate(summary == null ? "" : summary.trim(), 400));
        root.add("designSystem", system);
        save(scId, root);
    }

    /**
     * Updates a single OpenUI theme setting (e.g. {@code brand_color},
     * {@code corner_profile}, {@code density}, {@code neutral_scale},
     * {@code theme_mode}, {@code light:openui_bg}, {@code dark:openui_bg}),
     * re-runs WCAG contrast pairing, saves, and returns the updated system.
     */
    public static synchronized JsonObject updateOpenUiThemeField(
            String scId, String field, String value) {
        if (scId == null || scId.isEmpty() || field == null || field.isEmpty()) {
            return new JsonObject();
        }
        JsonObject current = designSystem(scId);
        JsonObject themeNode = current.has("openuiTheme")
                && current.get("openuiTheme").isJsonObject()
                ? current.getAsJsonObject("openuiTheme").deepCopy()
                : OpenUiThemeEngine.serializeResolvedTheme(
                        OpenUiThemeEngine.resolveTheme(current));
        String trimmedVal = value == null ? "" : value.trim();
        if (field.startsWith("light:")) {
            String colorKey = field.substring("light:".length());
            JsonObject lc = themeNode.has("light_colors")
                    && themeNode.get("light_colors").isJsonObject()
                    ? themeNode.getAsJsonObject("light_colors")
                    : new JsonObject();
            lc.addProperty(colorKey, trimmedVal);
            themeNode.add("light_colors", lc);
        } else if (field.startsWith("dark:")) {
            String colorKey = field.substring("dark:".length());
            JsonObject dc = themeNode.has("dark_colors")
                    && themeNode.get("dark_colors").isJsonObject()
                    ? themeNode.getAsJsonObject("dark_colors")
                    : new JsonObject();
            dc.addProperty(colorKey, trimmedVal);
            themeNode.add("dark_colors", dc);
        } else {
            themeNode.addProperty(field, trimmedVal);
            // If brand_color changed, drop stale derived accent overrides so
            // both Light & Dark accents re-harmonize cleanly.
            if ("brand_color".equals(field)) {
                if (themeNode.has("light_colors")
                        && themeNode.get("light_colors").isJsonObject()) {
                    JsonObject lc = themeNode.getAsJsonObject("light_colors");
                    lc.remove("openui_accent");
                    lc.remove("openui_accent_pressed");
                    lc.remove("openui_accent_subtle");
                    lc.remove("openui_text_on_accent");
                    lc.remove("openui_text_brand");
                    lc.remove("openui_border_accent");
                }
                if (themeNode.has("dark_colors")
                        && themeNode.get("dark_colors").isJsonObject()) {
                    JsonObject dc = themeNode.getAsJsonObject("dark_colors");
                    dc.remove("openui_accent");
                    dc.remove("openui_accent_pressed");
                    dc.remove("openui_accent_subtle");
                    dc.remove("openui_text_on_accent");
                    dc.remove("openui_text_brand");
                    dc.remove("openui_border_accent");
                }
            }
        }
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("title", text(current, "title"));
        wrapper.add("openuiTheme", themeNode);
        OpenUiThemeEngine.ResolvedTheme resolved =
                OpenUiThemeEngine.resolveTheme(wrapper);
        JsonObject updated = OpenUiThemeEngine.buildDesignSystemJson(
                resolved, null);
        setDesignSystemObject(scId, updated);
        JsonObject logEntry = new JsonObject();
        logEntry.addProperty("field", field);
        logEntry.addProperty("value", trimmedVal);
        record(scId, "design_theme_changed", logEntry);
        return updated;
    }

    /**
     * One component's measurements, replaced on its own. Changing a
     * button's height must not touch a card, and must not silently
     * rewrite the set the user agreed.
     */
    public static synchronized boolean setDesignToken(String scId,
                                                      String key,
                                                      JsonObject token) {
        if (scId == null || scId.isEmpty() || key == null
                || key.isEmpty() || token == null) {
            return false;
        }
        JsonObject root = load(scId);
        JsonElement element = root.get("designSystem");
        if (element == null || !element.isJsonObject()) {
            return false;
        }
        pushDesignHistory(root);
        JsonObject system = element.getAsJsonObject();
        JsonElement tokensElement = system.get("tokens");
        if (tokensElement == null || !tokensElement.isJsonArray()) {
            return false;
        }
        JsonArray tokens = tokensElement.getAsJsonArray();
        for (int i = 0; i < tokens.size(); i++) {
            JsonElement existing = tokens.get(i);
            if (!existing.isJsonObject()) {
                continue;
            }
            JsonObject current = existing.getAsJsonObject();
            if (key.equals(text(current, "key"))) {
                tokens.set(i, token);
                system.addProperty("updatedAt",
                        System.currentTimeMillis());
                save(scId, root);
                JsonObject entry = new JsonObject();
                entry.addProperty("key", key);
                entry.addProperty("label", text(token, "label"));
                entry.addProperty("props",
                        token.has("props")
                                ? token.get("props").toString() : "");
                record(scId, "design_token_changed", entry);
                return true;
            }
        }
        return false;
    }

    /** Whether this project has a design system of its own. */
    public static synchronized boolean hasDesignSystem(String scId) {
        JsonObject system = designSystem(scId == null ? "" : scId);
        if (system.has("openuiTheme") && system.get("openuiTheme").isJsonObject()) {
            return true;
        }
        JsonElement tokens = system.get("tokens");
        return tokens != null && tokens.isJsonArray()
                && tokens.getAsJsonArray().size() > 0;
    }

    /**
     * Whether a design system has been agreed for this work: saved on
     * the project, or held as a draft for a project not yet created.
     */
    public static synchronized boolean hasDesignSystemOrDraft(String scId) {
        return draftDesignSystemObj != null
                || draftDesignTokens.size() > 0
                || hasDesignSystem(scId);
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
        return !draftStyleBrief.isEmpty()
                || !draftPlanSteps.isEmpty()
                || draftDesignSystemObj != null
                || draftDesignTokens.size() > 0;
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
        JsonObject toMaterialize = null;
        if (draftDesignSystemObj != null && !hasDesignSystem(scId)) {
            setDesignSystemObject(scId, draftDesignSystemObj);
            toMaterialize = draftDesignSystemObj;
        } else if (draftDesignTokens.size() > 0 && !hasDesignSystem(scId)) {
            setDesignSystem(scId, draftDesignTitle, draftDesignTokens);
            toMaterialize = designSystem(scId);
        }
        draftStyleBrief = "";
        draftPlanTitle = "";
        draftPlanSteps = "";
        draftDesignTitle = "";
        draftDesignTokens = new JsonArray();
        draftDesignSystemObj = null;
        if (toMaterialize != null) {
            try {
                OpenUiThemeEngine.materializeToProject(scId, toMaterialize);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Empties the project's task log while keeping Style, Design System and Plan. */
    public static synchronized void clear(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonObject root = load(scId);
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
        JsonObject design = root.getAsJsonObject("designSystem");
        if (design != null && style.isEmpty()
                && !text(design, "summary").isEmpty()) {
            out.append("\n\nDesign direction for this project (agreed "
                    + "with the user; keep to it): ")
                    .append(text(design, "summary"));
        }
        if (design != null) {
            if (design.has("openuiTheme")
                    && design.get("openuiTheme").isJsonObject()) {
                JsonObject ot = design.getAsJsonObject("openuiTheme");
                out.append("\n\nActive OpenUI Theme (already materialized in "
                        + "values/colors.xml, values-night/colors.xml, "
                        + "values/styles.xml, and res/drawable/openui_*.xml): ")
                        .append("brand=").append(text(ot, "brand_color"))
                        .append(", neutral=").append(text(ot, "neutral_scale"))
                        .append(", corners=").append(text(ot, "corner_profile"))
                        .append(", density=").append(text(ot, "density"))
                        .append(", mode=").append(text(ot, "theme_mode"));
                if (!text(ot, "font_heading").isEmpty()) {
                    out.append(", font=").append(text(ot, "font_heading"));
                }
                out.append(". Ready-made colours: @color/openui_bg, "
                        + "@color/openui_fg, @color/openui_sunk, "
                        + "@color/openui_accent, @color/openui_text_on_accent, "
                        + "@color/openui_text_primary, "
                        + "@color/openui_text_secondary, "
                        + "@color/openui_text_tertiary, "
                        + "@color/openui_border_default, "
                        + "@color/openui_border_interactive, "
                        + "@color/openui_info_bg/_text, "
                        + "@color/openui_success_bg/_text, "
                        + "@color/openui_alert_bg/_text, "
                        + "@color/openui_danger_bg/_text; drawables: "
                        + "openui_card, openui_card_sunk, openui_sunk_input, "
                        + "openui_btn_primary, openui_btn_secondary, "
                        + "openui_btn_destructive, openui_chip_selector, "
                        + "openui_callout_*, openui_tag_*.");
            }
            if (design.has("tokens")) {
                JsonElement tokens = design.get("tokens");
                if (tokens != null && tokens.isJsonArray()
                        && tokens.getAsJsonArray().size() > 0) {
                    out.append("\nDesign system components (prefer OpenUI "
                            + "screen components or token=\"<key>\" so every "
                            + "screen matches):");
                    for (JsonElement element : tokens.getAsJsonArray()) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject token = element.getAsJsonObject();
                        out.append("\n• ").append(text(token, "key"))
                                .append(" (“")
                                .append(text(token, "label")).append("”)");
                        JsonElement props = token.get("props");
                        if (props != null && props.isJsonObject()) {
                            for (java.util.Map.Entry<String, JsonElement> field
                                    : props.getAsJsonObject().entrySet()) {
                                out.append(" ").append(field.getKey())
                                        .append("=")
                                        .append(field.getValue().isJsonPrimitive()
                                                ? field.getValue().getAsString()
                                                : "");
                            }
                        }
                    }
                }
            }
        }
        JsonObject plan = root.getAsJsonObject("plan");
        boolean havePlan = plan != null && plan.has("steps")
                && plan.getAsJsonArray("steps").size() > 0;
        if (havePlan) {
            JsonArray planSteps = dedupePlanSteps(
                    plan.getAsJsonArray("steps"));
            int done = 0;
            for (int i = 0; i < planSteps.size(); i++) {
                JsonObject step = planSteps.get(i).getAsJsonObject();
                if ("done".equals(text(step, "state"))) {
                    done++;
                }
            }
            out.append("\n\nImplementation plan \"")
                    .append(text(plan, "title")).append("\" (")
                    .append(done).append(" of ")
                    .append(planSteps.size())
                    .append(" steps done):");
            for (int i = 0; i < planSteps.size(); i++) {
                JsonObject step = planSteps.get(i).getAsJsonObject();
                String st = text(step, "state");
                String tag = "done".equals(st) ? "DONE"
                        : "current".equals(st) ? "CURRENT"
                        : "stopped".equals(st) ? "PAUSED" : "PENDING";
                out.append("\n  [").append(i + 1).append("] ")
                        .append(tag).append(": ")
                        .append(cleanStepText(text(step, "text")));
            }
            if (done < planSteps.size()) {
                out.append("\nWhenever you finish a milestone step, call "
                        + "set_plan with done_steps=\"" + (done + 1)
                        + "\" (and current_step=\"" + (done + 1)
                        + "\") — you do NOT need to resend \"steps\" to "
                        + "update progress.");
            }
        } else if (!style.isEmpty()) {
            out.append("\n\nThis project has a style but no saved "
                    + "implementation plan yet. For substantial work, "
                    + "agree the plan and save it with set_plan before "
                    + "building further.");
        }
        JsonObject unfinished = null;
        for (int i = tasks.size() - 1; i >= 0; i--) {
            JsonElement element = tasks.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject task = element.getAsJsonObject();
            String state = text(task, "state");
            if ("done".equals(state)) {
                break;
            }
            if ("open".equals(state) || "stopped".equals(state)) {
                unfinished = task;
                break;
            }
        }
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
        return brief.length() > 3600 ? brief.substring(0, 3600) : brief;
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
        if (scId == null || scId.isEmpty()) {
            return "";
        }
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
            JsonObject plan = root.getAsJsonObject("plan");
            if (plan != null && plan.has("steps")) {
                JsonArray planSteps = dedupePlanSteps(
                        plan.getAsJsonArray("steps"));
                int[] counts = countPlanProgress(planSteps);
                if (counts[1] > 0 && counts[0] < counts[1]) {
                    return "Paused at plan step " + (counts[0] + 1)
                            + " of " + counts[1]
                            + (reason.isEmpty() ? "" : " — " + reason);
                }
            }
            return "“" + truncate(text(task, "title"), 40) + "” paused"
                    + (total > 0 ? " after " + done + " of " + total
                            + " steps" : "")
                    + (reason.isEmpty() ? "" : " — " + reason);
        }
        return "";
    }

    // ------------------------------------------------------------------

    /** A string field of a stored object, empty when absent. */
    public static String textOf(JsonObject object, String key) {
        return text(object, key);
    }

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
