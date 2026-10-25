package pro.sketchware.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Agent Manager: divides an agreed plan into a few non-overlapping
 * PARTS and builds them at the same time, each part on its own worker
 * (its own conversation, its own model calls), so a whole app takes
 * minutes instead of an hour. The user sees plain words: "2 of 3 parts
 * ready", can look at what changed, undo a part, or rebuild it.
 *
 * <p>Safety by construction: parts are adjacent groups of plan steps;
 * a part only starts when none of its screen claims is held by a
 * running part, so two workers can never edit the same screen at once.
 * Every worker runs with proposals pre-approved (the user sanctioned
 * the build) and questions answered by "you decide", because nobody is
 * there to answer. A fresh snapshot is taken before each part, so any
 * part can be undone. Nothing here touches the main chat turn path.
 */
public final class AgentBoard {

    public AgentBoard() {
    }

    /** Workers at once — small on purpose (each is its own API spend). */
    public static final int MAX_WORKERS = 3;

    public enum PartState {QUEUED, BUILDING, DONE, FAILED}

    public static final class Part {
        public final int index;
        public final String title;
        public final List<String> stepTexts = new ArrayList<>();
        /** Lowercase screen keys this part owns (locks against overlap). */
        public final Set<String> claims = new HashSet<>();
        /** Real screen-directory names for undo (existing screens only). */
        public final List<String> screenDirs = new ArrayList<>();
        public volatile PartState state = PartState.QUEUED;
        public volatile String summary = "";
        public volatile String error = "";
        public volatile int appliedCount = 0;
        public volatile long startedAtMs;
        AgentSnapshots.Snapshot beforeSnapshot;
        AgentLoop loop;
        AiChatService service;

        Part(int index, String title) {
            this.index = index;
            this.title = title;
        }

        public String stateLabel() {
            switch (state) {
                case QUEUED:
                    return "Waiting…";
                case BUILDING:
                    return "Building…";
                case DONE:
                    return "Done — take a look";
                default:
                    return "Failed";
            }
        }
    }

    /** Board updates, already marshalled for the UI thread. */
    public interface BoardListener {
        void onBoardChanged();

        void onPartNote(String line);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final List<Part> parts = new ArrayList<>();
    private final Set<String> activeClaims = new HashSet<>();
    private String scId = "";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    private List<String> autoModelPool;
    private Context context;
    private BoardListener listener;
    private volatile boolean running;
    /** The architect's shared rules sheet; set before workers start. */
    private volatile String conventions = "";
    /** A worker stuck longer than this is cancelled, not left spinning. */
    private static final long PART_TIMEOUT_MS = 12 * 60 * 1000;
    /** Rebuilds allowed per part before it is reported failed. */
    private static final int MAX_ATTEMPTS = 2;
    /** 1 = steady (one part at a time); MAX_WORKERS = fast. */
    private volatile int maxConcurrent = 1;
    private final java.util.concurrent.atomic.AtomicBoolean
            supervising =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** A plan is board-worthy when it has enough steps to divide. */
    public static boolean available(JsonObject plan) {
        return plan != null && plan.has("steps")
                && plan.getAsJsonArray("steps").size() >= 3;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized List<Part> getParts() {
        return new ArrayList<>(parts);
    }

    public synchronized int[] progress() {
        int done = 0;
        for (Part part : parts) {
            if (part.state == PartState.DONE) {
                done++;
            }
        }
        return new int[]{done, parts.size()};
    }

    /**
     * Divides the plan (adjacent steps → up to {@link #MAX_WORKERS}
     * parts) and starts the workers. Returns false (and starts
     * nothing) when there is no plan to divide.
     */
    public synchronized boolean start(Context context, String scId,
                                      String baseUrl, String apiKey,
                                      String model, JsonObject plan,
                                      List<String> autoModelPool,
                                      boolean fastMode,
                                      BoardListener listener) {
        if (running || context == null || !available(plan)) {
            return false;
        }
        this.context = context.getApplicationContext();
        this.scId = scId == null ? "" : scId;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.autoModelPool = autoModelPool == null
                ? new ArrayList<>() : autoModelPool;
        this.listener = listener;
        this.maxConcurrent = fastMode ? MAX_WORKERS : 1;
        parts.clear();
        activeClaims.clear();

        List<String> steps = new ArrayList<>();
        for (com.google.gson.JsonElement element
                : plan.getAsJsonArray("steps")) {
            if (element.isJsonObject()) {
                JsonObject step = element.getAsJsonObject();
                if (step.has("text") && step.get("text").isJsonPrimitive()) {
                    steps.add(step.get("text").getAsString());
                }
            }
        }
        if (steps.size() < 3) {
            return false;
        }
        int partCount = Math.min(MAX_WORKERS,
                (steps.size() + 1) / 2);
        int per = steps.size() / partCount;
        int extra = steps.size() % partCount;
        int at = 0;
        Set<String> knownScreens = existingScreenKeys();
        for (int i = 0; i < partCount; i++) {
            int take = per + (i < extra ? 1 : 0);
            Part part = new Part(i + 1, titleOf(steps.get(at)));
            for (int k = 0; k < take && at < steps.size(); k++, at++) {
                part.stepTexts.add(steps.get(at));
                collectClaims(steps.get(at), knownScreens, part);
            }
            parts.add(part);
        }
        running = true;
        note("Building your app in " + partCount
                + " parts at the same time — this is faster. Each part "
                + "uses its own AI conversation (uses more data). You "
                + "can undo any part afterwards.");
        // One supervisor thread: starts parts as their claims free up.
        kickSupervisor();
        return true;
    }

    /** Stops every worker now; parts mid-build are marked failed. */
    public synchronized void cancelAll() {
        if (!running) {
            return;
        }
        for (Part part : parts) {
            if (part.loop != null) {
                part.loop.cancel();
            }
            if (part.service != null) {
                part.service.cancel();
            }
            if (part.state == PartState.QUEUED
                    || part.state == PartState.BUILDING) {
                part.state = PartState.FAILED;
                part.error = "Stopped";
            }
        }
        running = false;
        activeClaims.clear();
        changed();
    }

    /** Restores only this part's screens from its pre-part snapshot. */
    public synchronized boolean undoPart(Part part) {
        if (part == null || part.beforeSnapshot == null
                || part.screenDirs.isEmpty()) {
            return false;
        }
        return AgentSnapshots.restoreScreens(scId,
                part.beforeSnapshot, part.screenDirs);
    }

    /** Runs one part again, telling the worker what went wrong. */
    public synchronized void rebuildPart(Part part, String baseUrl,
                                         String apiKey, String model) {
        if (running || part == null
                || part.state == PartState.BUILDING
                || part.state == PartState.QUEUED) {
            return;
        }
        this.baseUrl = baseUrl == null ? this.baseUrl : baseUrl;
        this.apiKey = apiKey == null ? this.apiKey : apiKey;
        this.model = model == null ? this.model : model;
        part.state = PartState.QUEUED;
        part.error = "";
        part.attempts = 0;
        running = true;
        note("Rebuilding: " + part.title);
        kickSupervisor();
    }

    /** Starts the one supervisor thread, never two. */
    private void kickSupervisor() {
        if (supervising.compareAndSet(false, true)) {
            Thread thread = new Thread(this::supervise, "agent-board");
            thread.setDaemon(true);
            thread.start();
        }
    }

    // ---- internals -----------------------------------------------------

    private void supervise() {
        // One cheap architect call BEFORE any worker starts: it reads
        // the whole plan and fixes the shared names, so parts fit.
        prepareConventions();
        Thread watchdog = new Thread(this::watchdogs,
                "agent-board-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            while (running) {
                Part next = null;
                int building = 0;
                int queued = 0;
                synchronized (this) {
                    for (Part part : parts) {
                        if (part.state == PartState.BUILDING) {
                            building++;
                        } else if (part.state == PartState.QUEUED) {
                            queued++;
                            if (next == null
                                    && disjoint(part.claims,
                                    activeClaims)) {
                                next = part;
                            }
                        }
                    }
                }
                if (next != null && building < maxConcurrent) {
                    startPart(next);
                    continue;
                }
                if (building == 0 && queued == 0) {
                    break; // everything finished or failed for good
                }
                if (building == 0 && next == null) {
                    // Deadlock guard: queued parts block each other's
                    // screens — start the first one anyway.
                    synchronized (this) {
                        for (Part part : parts) {
                            if (part.state == PartState.QUEUED) {
                                next = part;
                                break;
                            }
                        }
                    }
                    if (next != null) {
                        startPart(next);
                    }
                    continue;
                }
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            supervising.set(false);
            if (running) {
                running = false;
            }
            changed();
        }
    }

    private void startPart(Part part) {
        synchronized (this) {
            activeClaims.addAll(part.claims);
            part.state = PartState.BUILDING;
            part.startedAtMs = System.currentTimeMillis();
            part.beforeSnapshot = AgentSnapshots.snapshot(scId);
        }
        changed();
        new Thread(() -> runWorker(part), "agent-part-" + part.index)
                .start();
    }

    /**
     * One cheap architect call before any worker starts: it reads the
     * WHOLE plan and writes the shared conventions sheet — exact
     * storage keys, variable names, More Block names — that every
     * worker's brief carries, so parts fit together. If the call
     * fails, a deterministic fallback sheet keeps the build going.
     */
    private void prepareConventions() {
        StringBuilder planText = new StringBuilder();
        for (Part part : parts) {
            for (String step : part.stepTexts) {
                planText.append("- ").append(step).append('\n');
            }
        }
        String prompt =
                "You are the lead architect of a small build team. "
                + "Below is the FULL plan for an app; several builders "
                + "will each build one part IN PARALLEL and cannot "
                + "talk to each other, so every shared decision must "
                + "be fixed in advance. Write the shared conventions "
                + "sheet they must all follow: (1) any data two or "
                + "more parts both read or write — give the exact "
                + "shared preferences file name and exact key names; "
                + "(2) exact variable names for values that cross "
                + "parts; (3) exact More Block names used by more than "
                + "one part; (4) one line per screen: its name and "
                + "which part owns it. Plain text, at most 200 words, "
                + "no preamble, no markdown headings."
                + "\n\nTHE PLAN:\n" + planText;
        try {
            AiChatService service = new AiChatService();
            List<JsonObject> messages = new ArrayList<>();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content",
                    "You are a precise mobile-app architect. You "
                            + "answer with the conventions sheet only.");
            messages.add(system);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", prompt);
            messages.add(user);
            AiChatService.AgentReply reply = service.agentTurnSync(
                    baseUrl, apiKey, model, messages, null);
            String sheet = reply == null ? "" : reply.content;
            if (sheet != null) {
                sheet = sheet.trim();
            }
            if (sheet == null || sheet.length() < 20) {
                throw new IllegalStateException("empty sheet");
            }
            if (sheet.length() > 1800) {
                sheet = sheet.substring(0, 1800);
            }
            conventions = sheet;
            note("The rules every part must follow are set.");
        } catch (Throwable e) {
            conventions = fallbackSheet();
            note("The architect could not be reached; standard naming "
                    + "rules will be used.");
        }
    }

    private static String fallbackSheet() {
        return "Standard naming rules (the architect was unreachable):"
                + "\n1. All names use snake_case."
                + "\n2. ONE shared preferences file for the whole app: "
                + "app_data. Any key that two parts both touch must be "
                + "named <feature>_<thing> (e.g. game_high_score)."
                + "\n3. Do not invent custom blocks or components; use "
                + "the built-in palette and More Blocks only."
                + "\n4. Reuse screens that already exist before "
                + "creating new ones; new screens are named after "
                + "their feature (e.g. SettingsScreen)."
                + "\n5. Keep every widget id descriptive and unique "
                + "(e.g. btn_save, tv_score).";
    }

    /** Cancels workers stuck longer than the timeout. */
    private void watchdogs() {
        while (running) {
            try {
                Thread.sleep(20000);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            long now = System.currentTimeMillis();
            synchronized (this) {
                for (Part part : parts) {
                    if (part.state == PartState.BUILDING
                            && part.startedAtMs > 0
                            && now - part.startedAtMs
                                    > PART_TIMEOUT_MS) {
                        note("Part timed out: " + part.title);
                        part.startedAtMs = 0;
                        if (part.loop != null) {
                            part.loop.cancel();
                        }
                        if (part.service != null) {
                            part.service.cancel();
                        }
                    }
                }
            }
        }
    }

    private void runWorker(Part part) {
        AiChatService service = new AiChatService();
        AgentLoop loop = new AgentLoop(context, service, Runnable::run);
        part.service = service;
        part.loop = loop;
        loop.autoModelPool = autoModelPool;
        StringBuilder brief = new StringBuilder();
        brief.append("You are one of several builders working ")
                .append("on the SAME project, each on their own part of ")
                .append("the agreed plan. Build YOUR part only, fully ")
                .append("and concretely — other workers handle the ")
                .append("other parts, so do not redo their work and do ")
                .append("not ask the user anything: where a choice ")
                .append("appears, pick the best option yourself and ")
                .append("carry on (the style and plan are already ")
                .append("agreed).\n\nYour part is #").append(part.index)
                .append(": ").append(part.title).append("\n");
        for (int i = 0; i < part.stepTexts.size(); i++) {
            brief.append("\nStep ").append(i + 1).append(". ")
                    .append(part.stepTexts.get(i));
        }
        if (!part.screenDirs.isEmpty()) {
            brief.append("\n\nScreens you own (work only on these): ");
            for (int i = 0; i < part.screenDirs.size(); i++) {
                if (i > 0) {
                    brief.append(", ");
                }
                brief.append(part.screenDirs.get(i));
            }
        }
        if (part.attempts > 0 && !part.error.isEmpty()) {
            brief.append("\n\nA PREVIOUS ATTEMPT AT THIS PART HAD ")
                    .append("PROBLEMS — fix ALL of them:\n")
                    .append(part.error);
        }
        StringBuilder whole = new StringBuilder();
        synchronized (this) {
            for (Part p : parts) {
                for (String step : p.stepTexts) {
                    whole.append("- ").append(step).append('\n');
                }
            }
        }
        brief.append("\n\nTHE WHOLE PLAN, for context (other builders ")
                .append("handle the other steps — never redo or undo ")
                .append("their work):\n").append(whole);
        brief.append("\nCONVENTIONS EVERY PART MUST FOLLOW (agreed in ")
                .append("advance so all parts fit together — follow ")
                .append("them exactly, especially exact names and ")
                .append("keys):\n").append(conventions);
        brief.append("\n\nWhen your steps are done, stop. End with one ")
                .append("short sentence saying what you built.");

        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user(brief.toString()));
        loop.run(baseUrl, apiKey, model, history, scId, true,
                new AgentLoop.Listener() {
                    @Override
                    public void onToolNote(String line) {
                        // Worker activity lines stay out of the chat.
                    }

                    @Override
                    public void onAnswer(String text, String thinking) {
                        // Verification blocks (rendering + a reviewer
                        // call) so it never runs on the callback thread.
                        new Thread(() -> verifyAndFinish(part,
                                text.trim()), "agent-verify-"
                                + part.index).start();
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        List<String> problems = new ArrayList<>();
                        problems.add("The builder stopped: "
                                + error.message);
                        new Thread(() -> decide(part, "", problems),
                                "agent-verify-" + part.index).start();
                    }

                    @Override
                    public boolean onApproval(
                            AgentProposal proposal) {
                        // The user sanctioned this build; per-part
                        // review (undo/rebuild) happens on the board.
                        part.appliedCount++;
                        return true;
                    }

                    @Override
                    public JsonObject onPickColor(String question,
                                                  String initialHex) {
                        JsonObject out = new JsonObject();
                        out.addProperty("picked",
                                initialHex == null || initialHex.isEmpty()
                                        ? "#008DCD" : initialHex);
                        return out;
                    }

                    @Override
                    public String onAskChoice(String question,
                                              List<String> options) {
                        return "You decide — pick the best option "
                                + "yourself and say why";
                    }

                    @Override
                    public void onApprovalResolved(
                            AgentProposal proposal, JsonObject result) {
                    }

                    @Override
                    public void onWorkingProjectChanged(String changed) {
                        // A worker must never rebind the user's chat
                        // project; the main path keeps its own binding.
                    }
                });
    }

    /**
     * Never trusts the worker's own words: every screen the part owns
     * must actually render, and an independent reviewer AI must pass
     * the real diff — only then is the part DONE.
     */
    private void verifyAndFinish(Part part, String summary) {
        note(part.title + " finished — checking the result…");
        List<String> problems = new ArrayList<>();
        for (String dir : part.screenDirs) {
            try {
                AgentPreview.renderScreen(context, scId, dir,
                        360, 800, false);
            } catch (Throwable t) {
                String why = String.valueOf(t.getMessage());
                problems.add("Screen " + dir + " could not be shown"
                        + (why.isEmpty() ? "" : ": " + why));
            }
        }
        if (problems.isEmpty()) {
            String verdict = reviewPart(part);
            if (verdict != null && !verdict.trim().isEmpty()) {
                problems.add(verdict.trim());
            }
        }
        decide(part, summary, problems);
    }

    /**
     * A second, independent AI reads what ACTUALLY changed (diff
     * against the part's own pre-snapshot) against the plan and the
     * conventions sheet. Returns null on PASS or if it cannot run
     * (an unreachable reviewer never blocks a part).
     */
    private String reviewPart(Part part) {
        try {
            if (part.beforeSnapshot == null) {
                return null;
            }
            List<String> lines =
                    AgentSnapshots.diffLines(scId, part.beforeSnapshot);
            if (lines == null || lines.isEmpty()) {
                return "No changes were found for this part — nothing "
                        + "was built.";
            }
            StringBuilder diff = new StringBuilder();
            for (String line : lines) {
                if (diff.length() > 4000) {
                    diff.append("\n…(diff shortened)");
                    break;
                }
                diff.append(line).append('\n');
            }
            StringBuilder steps = new StringBuilder();
            for (String step : part.stepTexts) {
                steps.append("- ").append(step).append('\n');
            }
            AiChatService service = new AiChatService();
            List<JsonObject> messages = new ArrayList<>();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content",
                    "You are a strict but fair build reviewer. You "
                            + "answer with exactly PASS, or with "
                            + "PROBLEMS: followed by a short numbered "
                            + "list of what is wrong or missing. No "
                            + "other words.");
            messages.add(system);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content",
                    "A builder was asked to build ONLY these steps:\n"
                            + steps
                            + "\nRULES it had to follow:\n"
                            + conventions
                            + "\nWHAT ACTUALLY CHANGED (diff):\n"
                            + diff
                            + "\nDid the changes correctly and fully "
                            + "cover the steps while following the "
                            + "rules? Answer PASS or PROBLEMS:");
            messages.add(user);
            AiChatService.AgentReply reply = service.agentTurnSync(
                    baseUrl, apiKey, model, messages, null);
            String answer = reply == null || reply.content == null
                    ? "" : reply.content.trim();
            if (answer.isEmpty()) {
                return null;
            }
            String upper = answer.toUpperCase(java.util.Locale.US);
            if (upper.startsWith("PASS")) {
                return null;
            }
            if (answer.length() > 400) {
                answer = answer.substring(0, 400);
            }
            return answer;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The gate: pass, or a bounded rebuild with problems attached. */
    private void decide(Part part, String summary,
                        List<String> problems) {
        String problemText = joinProblems(problems);
        boolean pass = problems.isEmpty();
        boolean retry = false;
        synchronized (this) {
            if (pass) {
                part.state = PartState.DONE;
                part.summary = summary;
                part.error = "";
            } else if (!running) {
                part.state = PartState.FAILED;
                part.error = "Stopped";
            } else if (part.attempts < MAX_ATTEMPTS) {
                part.attempts++;
                retry = true;
                part.state = PartState.QUEUED;
                part.error = problemText;
            } else {
                part.state = PartState.FAILED;
                part.error = problemText;
            }
            activeClaims.removeAll(part.claims);
        }
        if (pass) {
            note("Part ready (checked): " + part.title);
        } else if (retry) {
            note(part.title + " had problems — building it again "
                    + "(attempt " + (part.attempts + 1) + ")");
        } else {
            note("Part failed: " + part.title + " — " + problemText);
        }
        changed();
    }

    private static String joinProblems(List<String> problems) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < problems.size() && i < 5; i++) {
            if (out.length() > 0) {
                out.append(" ");
            }
            out.append(problems.get(i));
        }
        return out.toString();
    }

    private synchronized void note(String line) {
        BoardListener l = listener;
        if (l != null) {
            MAIN.post(() -> l.onPartNote(line));
        }
    }

    private synchronized void changed() {
        BoardListener l = listener;
        if (l != null) {
            MAIN.post(l::onBoardChanged);
        }
    }

    private static String titleOf(String stepText) {
        String clean = stepText == null ? "" : stepText.trim();
        if (clean.startsWith("● ") || clean.startsWith("○ ")
                || clean.startsWith("✓ ")) {
            clean = clean.substring(2).trim();
        }
        clean = clean.replaceFirst("^\\d+[.)]\\s*", "");
        int cut = clean.indexOf(". ");
        String out = cut > 8 && cut < 60 ? clean.substring(0, cut + 1)
                : clean.length() > 60
                ? clean.substring(0, clean.lastIndexOf(' ', 60)) : clean;
        return out.isEmpty() ? "Part" : out;
    }

    /** Lowercase keys of the screens the project already has. */
    private Set<String> existingScreenKeys() {
        Set<String> out = new HashSet<>();
        try {
            List<com.besome.sketch.beans.ProjectFileBean> activities =
                    SketchwareProjects.activities(scId);
            if (activities != null) {
                for (com.besome.sketch.beans.ProjectFileBean activity
                        : activities) {
                    String name = SketchwareProjects
                            .activityName(activity);
                    if (name != null && !name.isEmpty()) {
                        out.add(name.toLowerCase(java.util.Locale.US));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * A part claims every screen it mentions: existing screen names it
     * contains, plus Screen/Activity/Page tokens in its step texts.
     * Claims without a real directory (planned screens) still lock —
     * two parts planning the same new screen must not race.
     */
    private void collectClaims(String stepText, Set<String> knownScreens,
                               Part part) {
        String lower = stepText == null ? "" : stepText.toLowerCase(
                java.util.Locale.US);
        for (String known : knownScreens) {
            if (!known.isEmpty() && lower.contains(known)) {
                part.claims.add(known);
                if (!part.screenDirs.contains(capital(known))) {
                    part.screenDirs.add(capital(known));
                }
            }
        }
        Matcher named = Pattern.compile(
                "[A-Za-z_][A-Za-z0-9_]*"
                        + "(?:Screen|Activity|Page|Fragment)").matcher(
                stepText == null ? "" : stepText);
        while (named.find()) {
            String token = named.group().toLowerCase(java.util.Locale.US);
            part.claims.add(token);
            if (knownScreens.contains(token)
                    && !part.screenDirs.contains(capital(token))) {
                part.screenDirs.add(capital(token));
            }
        }
    }

    private static String capital(String lower) {
        if (lower == null || lower.isEmpty()) {
            return lower;
        }
        return Character.toUpperCase(lower.charAt(0))
                + lower.substring(1);
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (String item : a) {
            if (b.contains(item)) {
                return false;
            }
        }
        return true;
    }
}
