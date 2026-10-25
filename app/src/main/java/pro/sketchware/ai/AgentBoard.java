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
        /** Builds attempted for this part (caps the auto-retry). */
        public volatile int attempts;
        /** Wiring parts run only after every other part settles. */
        public volatile boolean wiringPart;
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

        /**
         * Blueprint mode: called with the drawn blueprint BEFORE
         * anything is built. Blocks the board thread until the user
         * decides. Return null to cancel the whole build (nothing
         * happens), an empty string to build as drawn, or the user's
         * requested adjustments — the blueprint is redrawn with them
         * (at most three rounds) and shown again.
         */
        String onBlueprintReady(String blueprintText);
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
    /** The model the FIRST worker resolved; everyone else is pinned
     * to it so parts land from one AI, not three different ones. */
    private volatile String pinnedModel = "";
    /** A worker stuck longer than this is cancelled, not left spinning. */
    private static final long PART_TIMEOUT_MS = 12 * 60 * 1000;
    /** Rebuilds allowed per part before it is reported failed. */
    private static final int MAX_ATTEMPTS = 2;
    /** 1 = steady (one part at a time); MAX_WORKERS = fast. */
    private volatile int maxConcurrent = 1;
    private final java.util.concurrent.atomic.AtomicBoolean
            supervising =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** Set when the user stops the board: skips the final build gate. */
    private volatile boolean stoppedByUser;
    /** Throttles worker activity lines: one per part per 12s. */
    private final java.util.Map<Integer, Long> lastNoteAt =
            new java.util.HashMap<>();
    /** Blueprint mode: screens from a drawn blueprint + wiring last. */
    private volatile boolean blueprintMode;
    private volatile String blueprint = "";
    /** Resume flow (Wave 66 chat cards): an adjustment to honor on
     * the FIRST draw, and the previous drawing it builds upon. */
    private volatile String pendingFirstAdjustment = "";
    private volatile String pendingPreviousBlueprint = "";
    private final List<String> planSteps = new ArrayList<>();
    private JsonObject planHeld;

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
        if (running || context == null || !available(plan)
                || scId == null || scId.isEmpty()
                || AgentJournal.DRAFT_KEY.equals(scId)) {
            // Never build against no project or the "__draft__"
            // sentinel: parts need a real project to land in.
            return false;
        }
        configure(context, scId, baseUrl, apiKey, model, plan,
                autoModelPool, listener);
        this.maxConcurrent = fastMode ? MAX_WORKERS : 1;
        this.stoppedByUser = false;
        this.blueprintMode = false;
        this.blueprint = "";
        parts.clear();
        activeClaims.clear();

        int n = splitFromSteps();
        if (n == 0) {
            return false;
        }
        running = true;
        note(fastMode
                ? "Building in " + n + " parts, all at the same time — "
                + "each part is a separate conversation (uses more "
                + "data). You can undo any part afterwards."
                : "Building in " + n + " parts, one after another — "
                + "each part is checked before the next starts.");
        kickSupervisor();
        return true;
    }

    /** Shared field setup for every start path. */
    private void configure(Context context, String scId,
            String baseUrl, String apiKey, String model, JsonObject plan,
            List<String> autoModelPool, BoardListener listener) {
        this.context = context.getApplicationContext();
        this.scId = scId == null ? "" : scId;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.autoModelPool = autoModelPool == null
                ? new ArrayList<>() : autoModelPool;
        this.listener = listener;
        this.planHeld = plan;
        planSteps.clear();
        if (plan != null && plan.has("steps")) {
            for (com.google.gson.JsonElement element
                    : plan.getAsJsonArray("steps")) {
                if (element.isJsonObject()) {
                    JsonObject step = element.getAsJsonObject();
                    if (step.has("text")
                            && step.get("text").isJsonPrimitive()) {
                        planSteps.add(step.get("text").getAsString());
                    }
                }
            }
        }
    }

    /**
     * Blueprint mode: one agent first draws EVERY screen (widgets,
     * names, storage keys, wiring), the user approves the drawing,
     * then screen-builders race (screens are independent — the safe
     * parallel case) and ONE wiring agent connects everything
     * afterwards. Reuses every check: render + reviewer per part,
     * per-part undo, the final compile gate.
     */
    public synchronized boolean startBlueprint(Context context,
            String scId, String baseUrl, String apiKey, String model,
            JsonObject plan, List<String> autoModelPool,
            BoardListener listener) {
        if (running || context == null || !available(plan)
                || scId == null || scId.isEmpty()
                || AgentJournal.DRAFT_KEY.equals(scId)) {
            return false;
        }
        configure(context, scId, baseUrl, apiKey, model, plan,
                autoModelPool, listener);
        this.maxConcurrent = MAX_WORKERS;
        this.stoppedByUser = false;
        this.blueprintMode = true;
        this.blueprint = "";
        this.conventions = "";
        this.pinnedModel = "";
        parts.clear();
        activeClaims.clear();
        running = true;
        note("Drawing the blueprint first — every screen, every name. "
                + "You approve it before anything is built.");
        changed();
        kickSupervisor();
        return true;
    }

    /**
     * Wave 66 chat-card flows: either build straight from a SAVED
     * blueprint (no redraw), or redraw once honoring the user's
     * adjustment on top of the previous drawing. Same guards and
     * lifecycle as {@link #startBlueprint}.
     */
    public synchronized boolean startBlueprintFromSaved(Context context,
            String scId, String baseUrl, String apiKey, String model,
            JsonObject plan, List<String> autoModelPool,
            BoardListener listener, String previousBlueprint,
            String firstAdjustment) {
        if (running || context == null || !available(plan)
                || scId == null || scId.isEmpty()
                || AgentJournal.DRAFT_KEY.equals(scId)) {
            return false;
        }
        configure(context, scId, baseUrl, apiKey, model, plan,
                autoModelPool, listener);
        this.maxConcurrent = MAX_WORKERS;
        this.stoppedByUser = false;
        this.blueprintMode = true;
        this.conventions = "";
        this.pinnedModel = "";
        parts.clear();
        activeClaims.clear();
        boolean adjust = firstAdjustment != null
                && !firstAdjustment.trim().isEmpty();
        this.pendingFirstAdjustment = adjust
                ? firstAdjustment.trim() : "";
        this.pendingPreviousBlueprint = previousBlueprint == null
                ? "" : previousBlueprint;
        if (!adjust && pendingPreviousBlueprint.trim().length() >= 60
                && pendingPreviousBlueprint.contains("SCREEN:")) {
            this.blueprint = pendingPreviousBlueprint;
            this.pendingFirstAdjustment = "";
            this.pendingPreviousBlueprint = "";
        } else {
            this.blueprint = "";
        }
        running = true;
        note(this.blueprint.isEmpty()
                ? "Redrawing the blueprint with your changes — every "
                + "screen, every name. You approve it before anything "
                + "is built."
                : "Using your saved blueprint — no redraw. Splitting "
                + "it into screens now.");
        changed();
        kickSupervisor();
        return true;
    }

    /** Splits the plan's steps into up to MAX_WORKERS adjacent parts.
     * Returns the part count, or 0 when the plan is too small. */
    private int splitFromSteps() {
        List<String> steps = new ArrayList<>(planSteps);
        if (steps.size() < 3) {
            return 0;
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
        return parts.size();
    }

    /**
     * Blueprint mode step one: draw the blueprint, get the user's OK,
     * split the work into screen parts plus ONE wiring part. Returns
     * false when the user cancels — nothing gets built. Falls back to
     * the plain step-split when no usable blueprint can be drawn.
     */
    private boolean prepareBlueprint() {
        if (blueprint != null && !blueprint.isEmpty()
                && !parts.isEmpty()) {
            return true; // already drawn and approved (a part rebuild)
        }
        if (blueprint != null && !blueprint.isEmpty()) {
            // Seeded from the chat card's saved drawing: straight to
            // the split, no redraw.
            splitFromBlueprint(blueprint);
            if (!parts.isEmpty()) {
                conventions = headerAndRules(blueprint);
                note("Blueprint approved — building "
                        + (parts.size() - 1) + " screens, then wiring "
                        + "them together.");
                return true;
            }
            // The saved text split into nothing usable: redraw below.
        }
        String firstAdjust = pendingFirstAdjustment;
        String previous = pendingPreviousBlueprint;
        pendingFirstAdjustment = "";
        pendingPreviousBlueprint = "";
        String text = generateBlueprintWithProgress(firstAdjust,
                previous);
        int rounds = 0;
        while (text != null && text.trim().length() >= 60
                && text.contains("SCREEN:") && rounds < 3) {
            BoardListener l = listener;
            if (l == null) {
                break;
            }
            // Wave 66: the drawing is kept in the journal the moment
            // it exists, so nothing can lose it again.
            AgentJournal.saveBlueprint(scId, text, false);
            String adjustment = l.onBlueprintReady(text);
            if (adjustment == null) {
                note("Blueprint not approved — nothing was built.");
                AgentJournal.saveBlueprint(scId, text, true);
                return false;
            }
            if (adjustment.trim().isEmpty()) {
                break; // approved as drawn
            }
            rounds++;
            note("Adjusting the blueprint…");
            text = generateBlueprintWithProgress(adjustment.trim(),
                    text);
        }
        if (text == null || text.trim().length() < 60
                || !text.contains("SCREEN:")) {
            note("No blueprint could be drawn — building straight "
                    + "from the plan instead.");
            splitFromSteps();
            return !parts.isEmpty();
        }
        blueprint = text;
        AgentJournal.saveBlueprint(scId, text, true);
        splitFromBlueprint(text);
        if (parts.isEmpty()) {
            splitFromSteps();
            return !parts.isEmpty();
        }
        conventions = headerAndRules(text);
        note("Blueprint approved — building "
                + (parts.size() - 1) + " screens, then wiring them "
                + "together.");
        return true;
    }

    /**
     * Draws with honest progress: a pre-line, then a 30-second
     * heartbeat so the silence of ONE big request never looks like
     * a hang (field report: "stood there quite some time").
     */
    private String generateBlueprintWithProgress(String adjustment,
            String previous) {
        note("✏️ Drawing every screen now — one big request, usually "
                + "1–3 minutes. The blueprint card appears right here "
                + "when the drawing is done.");
        final long startedAt = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread pulse = new Thread(() -> {
            while (!done.get()) {
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    return;
                }
                if (done.get()) {
                    return;
                }
                long s = (System.currentTimeMillis() - startedAt)
                        / 1000;
                note("✏️ Still drawing… " + (s / 60) + "m" + (s % 60)
                        + "s. Silence is normal — one big request.");
            }
        });
        pulse.setDaemon(true);
        pulse.start();
        try {
            return generateBlueprint(adjustment, previous);
        } finally {
            done.set(true);
        }
    }

    /** The saved design tokens for prompts ("" when none). */
    private String designTokensForPrompt() {
        String sheet = AgentJournal.designTokenSheet(scId);
        return sheet.isEmpty() ? ""
                : "THE PROJECT'S DESIGN SYSTEM — your LOOK lines and "
                + "colors MUST come from these user-chosen tokens:\n"
                + sheet + "\n";
    }

    /** ONE call: the full blueprint drawn from the plan. */
    private String generateBlueprint(String adjustment,
            String previous) {
        StringBuilder planText = new StringBuilder();
        for (String step : planSteps) {
            planText.append("- ").append(step).append('\n');
        }
        StringBuilder existing = new StringBuilder();
        for (String name : existingScreenKeys()) {
            existing.append(name).append(' ');
        }
        String prompt =
                "You are the lead architect. Several builders will "
                + "build this app IN PARALLEL without talking to each "
                + "other, so the blueprint must decide EVERYTHING in "
                + "advance; the builders will only fill in the "
                + "blanks. Write the full blueprint in exactly these "
                + "sections, plain text, no markdown headings:\n"
                + "RULES: widget id style (like btn_save, tv_score), "
                + "variable style, ONE shared preferences file "
                + "name.\n"
                + "Then one block per screen starting EXACTLY with a "
                + "line 'SCREEN: <ScreenName>' followed by that "
                + "screen's widgets, one per line (like '- btn_next "
                + "(Button) opens ResultScreen') and what the screen "
                + "does, then a LOOK: line with HARD numbers for that "
                + "screen: outer padding in dp, space between items in "
                + "dp, card corner radius in dp, title and body text "
                + "sizes in sp, which palette colors go where. Decide "
                + "the looks yourself — builders copy your numbers.\n"
                + "VARIABLES: every variable whose value crosses "
                + "screens, with exact names.\n"
                + "STORAGE: every shared key, like 'app_data.high_score "
                + "(number) written by ResultScreen, read by "
                + "HomeScreen'.\n"
                + "WIRING: every connection between screens (which "
                + "button opens what, which value flows where).\n"
                + "At most 600 words. Screens already in the project: "
                + existing + "\n" + designTokensForPrompt()
                + "\nTHE PLAN:\n" + planText
                + (previous == null || previous.trim().isEmpty()
                ? "" : "\n\nTHE PREVIOUS BLUEPRINT (your earlier "
                + "drawing — keep everything that still applies; "
                + "change only what the user asks):\n"
                + previous.trim())
                + (adjustment == null || adjustment.trim().isEmpty()
                ? "" : "\n\nTHE USER ASKS FOR THESE CHANGES — "
                + "honor them exactly:\n" + adjustment.trim());
        try {
            AiChatService service = new AiChatService();
            List<JsonObject> messages = new ArrayList<>();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content",
                    "You are a precise mobile-app architect. You "
                            + "answer with the blueprint only.");
            messages.add(system);
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", prompt);
            messages.add(user);
            AiChatService.AgentReply reply = service.agentTurnSync(
                    baseUrl, apiKey, effectiveModel(), messages, null);
            return reply == null ? "" : reply.content;
        } catch (Throwable e) {
            return "";
        }
    }

    /** Splits blueprint text into screen parts + ONE wiring part. */
    private void splitFromBlueprint(String text) {
        Set<String> known = existingScreenKeys();
        parts.clear();
        Part current = null;
        StringBuilder tail = new StringBuilder();
        boolean inTail = false;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("SCREEN:")) {
                String name = t.substring("SCREEN:".length()).trim();
                current = new Part(parts.size() + 1,
                        name.isEmpty() ? "Screen" : name);
                current.stepTexts.add("Build this screen EXACTLY as "
                        + "your blueprint section says — nothing "
                        + "more, nothing less:");
                current.stepTexts.add(line);
                collectClaims(t, known, current);
                parts.add(current);
                inTail = false;
                continue;
            }
            if (t.startsWith("VARIABLES:") || t.startsWith("STORAGE:")
                    || t.startsWith("WIRING:")) {
                inTail = true;
            }
            if (inTail) {
                tail.append(line).append('\n');
            } else if (current != null && !t.isEmpty()) {
                current.stepTexts.add(line);
                collectClaims(line, known, current);
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        Part wiring = new Part(parts.size() + 1, "Wiring");
        wiring.wiringPart = true;
        wiring.stepTexts.add("Connect everything per the blueprint:");
        for (String line : tail.toString().split("\n")) {
            if (!line.trim().isEmpty()) {
                wiring.stepTexts.add(line);
            }
        }
        parts.add(wiring);
    }

    /** RULES + VARIABLES/STORAGE/WIRING sections (no screen blocks). */
    private static String headerAndRules(String text) {
        StringBuilder out = new StringBuilder();
        boolean inScreen = false;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("SCREEN:")) {
                inScreen = true;
                continue;
            }
            if (t.startsWith("VARIABLES:") || t.startsWith("STORAGE:")
                    || t.startsWith("WIRING:")) {
                inScreen = false;
            }
            if (!inScreen && !t.isEmpty()) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String shortenForDialog(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.length() > 3800) {
            return clean.substring(0, 3800)
                    + "\n… (shortened here — the full blueprint "
                    + "drives the build)";
        }
        return clean;
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
        stoppedByUser = true;
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
        stoppedByUser = false;
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
        try {
            if (!preflight()) {
                synchronized (this) {
                    running = false;
                }
                changed();
                return;
            }
            if (blueprintMode && !prepareBlueprint()) {
                // The user said no: nothing gets built.
                synchronized (this) {
                    running = false;
                }
                changed();
                return;
            }
            // One cheap architect call when no blueprint already set
            // the shared rules (blueprint mode presets conventions).
            prepareConventions();
            Thread watchdog = new Thread(this::watchdogs,
                    "agent-board-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            while (running) {
                Part next = null;
                Part wiring = null;
                int building = 0;
                int screensBuilding = 0;
                int queued = 0;
                synchronized (this) {
                    for (Part part : parts) {
                        if (part.state == PartState.BUILDING) {
                            building++;
                            if (!part.wiringPart) {
                                screensBuilding++;
                            }
                        } else if (part.state == PartState.QUEUED) {
                            if (part.wiringPart) {
                                wiring = part;
                            } else {
                                queued++;
                                if (next == null
                                        && disjoint(part.claims,
                                        activeClaims)) {
                                    next = part;
                                }
                            }
                        }
                    }
                }
                if (next != null && building < maxConcurrent) {
                    startPart(next);
                    continue;
                }
                if (wiring != null && queued == 0
                        && screensBuilding == 0) {
                    // The wiring agent starts only when every screen
                    // part has settled — integration after, never
                    // during.
                    startPart(wiring);
                    continue;
                }
                if (building == 0 && queued == 0 && wiring == null) {
                    break; // everything finished or failed for good
                }
                if (building == 0 && next == null && wiring == null) {
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
        // The board's last duty: if anything landed, prove the WHOLE
        // app still compiles — parts passing alone is not enough.
        if (!stoppedByUser && anyPartDone()) {
            runFinalBuildGate();
        }
    }

    /**
     * ONE cheap request before any part starts: on a rate-limited key
     * every part would otherwise die silently, one after another.
     */
    private boolean preflight() {
        try {
            AiChatService service = new AiChatService();
            List<JsonObject> messages = new ArrayList<>();
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "Reply with exactly: OK");
            messages.add(user);
            service.agentTurnSync(baseUrl, apiKey,
                    effectiveModel(), messages, null);
            return true;
        } catch (AiChatService.AgentRequestException e) {
            if (e.error.httpCode == 429 || e.error.kind
                    == AiChatService.ErrorKind.QUOTA) {
                note("Your key is rate-limited right now ("
                        + effectiveModel() + "). Nothing was built — "
                        + "wait for the quota window or pick another "
                        + "model, then start the build again.");
                return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** A part died of quota: pause the board instead of burning the
     * rest — queued parts stay queued for a later rebuild. */
    private void pauseForQuota(Part part) {
        synchronized (this) {
            running = false;
        }
        note("Board paused — your key hit its quota during \""
                + part.title + "\". Built parts stay. When the quota "
                + "resets, tap Rebuild on the failed part and the "
                + "rest continues.");
        changed();
    }

    private boolean anyPartDone() {
        synchronized (this) {
            for (Part part : parts) {
                if (part.state == PartState.DONE) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The compile check: render + review verifies each part's screens,
     * but only a real build of the whole project proves the parts fit
     * together (cross-part Java, manifests, resources). Runs once when
     * the board finishes; on failure, ONE bounded repair attempt with
     * the compiler errors, then an honest final report.
     */
    private void runFinalBuildGate() {
        note("All parts finished — test-compiling the whole app…");
        changed();
        String errors;
        try {
            AgentBuild.build(context, scId);
            note("Test build passed — the whole app compiles with "
                    + "every part in it.");
            changed();
            return;
        } catch (AgentTools.ToolException e) {
            errors = String.valueOf(e.getMessage());
        } catch (Throwable t) {
            errors = t.getMessage() == null
                    ? "unknown build error" : t.getMessage();
        }
        note("Test build found problems — one repair attempt "
                + "starting…");
        changed();
        runCompileRepair(errors);
    }

    /** ONE silent repair: fix the named errors, rebuild, report. */
    private void runCompileRepair(String errors) {
        AiChatService service = new AiChatService();
        AgentLoop loop = new AgentLoop(context, service, Runnable::run);
        loop.autoModelPool = autoModelPool;
        String trimmed = errors.length() > 4000
                ? errors.substring(0, 4000) : errors;
        StringBuilder brief = new StringBuilder();
        brief.append("The app failed to COMPILE after a parts build. ")
                .append("Fix ONLY what the errors name — make the ")
                .append("smallest changes that make it compile. Do ")
                .append("not redesign anything, do not add features, ")
                .append("and do not ask the user anything.\n\n")
                .append("BUILD ERRORS:\n").append(trimmed);
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user(brief.toString()));
        loop.run(baseUrl, apiKey, effectiveModel(), history, scId, true,
                new AgentLoop.Listener() {
                    @Override
                    public void onToolNote(String line) {
                    }

                    @Override
                    public void onAnswer(String text, String thinking) {
                        afterRepair();
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        note("Repair builder failed: " + error.message);
                        changed();
                    }

                    @Override
                    public boolean onApproval(AgentProposal proposal) {
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
                    }
                });
    }

    private void afterRepair() {
        try {
            AgentBuild.build(context, scId);
            note("Repaired — the test build now passes.");
        } catch (Throwable t) {
            String why = t instanceof AgentTools.ToolException
                    ? String.valueOf(t.getMessage())
                    : "see the build errors";
            note("The repair did not fully fix the build (" + why
                    + "). Ask me in chat to fix it, or rebuild a part "
                    + "from the board.");
        }
        changed();
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
        if (!conventions.isEmpty()) {
            return; // already agreed for this build — never re-paid
        }
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
                + "be fixed in advance. First, judge the plan: answer "
                + "SPLIT only if the steps are concrete enough for "
                + "separate builders to divide safely; answer SINGLE "
                + "if the plan is too vague or tangled. Your FIRST "
                + "line is exactly SPLIT or SINGLE. Then write the "
                + "shared conventions sheet they must all follow: "
                + "(1) any data two or more parts both read or write "
                + "— give the exact shared preferences file name "
                + "and exact key names; (2) exact variable names for "
                + "values that cross parts; (3) exact More Block "
                + "names used by more than one part; (4) one line per "
                + "screen: its name and which part owns it. Plain "
                + "text, at most 200 words, no markdown headings."
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
                    baseUrl, apiKey, effectiveModel(), messages, null);
            String sheet = reply == null ? "" : reply.content;
            if (sheet != null) {
                sheet = sheet.trim();
            }
            if (sheet == null || sheet.length() < 20) {
                throw new IllegalStateException("empty sheet");
            }
            int newline = sheet.indexOf('\n');
            String head = newline < 0 ? sheet
                    : sheet.substring(0, newline);
            boolean single = head.trim()
                    .toUpperCase(java.util.Locale.US)
                    .startsWith("SINGLE");
            if (single) {
                sheet = newline < 0 ? fallbackSheet()
                        : sheet.substring(newline + 1).trim();
                if (sheet.length() < 20) {
                    sheet = fallbackSheet();
                }
                collapseToOnePart();
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

    /**
     * The clarity gate said SINGLE: the plan is too loose to divide,
     * so all steps become ONE part — a single builder working in
     * order, still under the full check pipeline.
     */
    private void collapseToOnePart() {
        synchronized (this) {
            Part all = new Part(1, "The whole plan");
            for (Part part : parts) {
                all.stepTexts.addAll(part.stepTexts);
                all.claims.addAll(part.claims);
                all.screenDirs.addAll(part.screenDirs);
            }
            parts.clear();
            parts.add(all);
        }
        note("This plan is too loose to split safely — building it "
                + "as one checked sequence instead.");
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

    private String effectiveModel() {
        return pinnedModel.isEmpty() ? model : pinnedModel;
    }

    private void runWorker(Part part) {
        AiChatService service = new AiChatService();
        AgentLoop loop = new AgentLoop(context, service, Runnable::run);
        part.service = service;
        part.loop = loop;
        loop.autoModelPool = autoModelPool;
        StringBuilder brief = new StringBuilder();
        if (part.wiringPart) {
            brief.append("You are the WIRING agent: the screens were ")
                    .append("built by other builders, and your only ")
                    .append("job is connecting them — which button ")
                    .append("opens which screen, shared data, shared ")
                    .append("logic — exactly as your steps say, using ")
                    .append("the exact names below. Edit screens only ")
                    .append("to add connections; never redesign them. ")
                    .append("Do not ask the user anything.")
                    .append("\n\nYour part: ")
                    .append(part.title).append("\n");
        } else {
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
        }
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
        String tokenSheet = AgentJournal.designTokenSheet(scId);
        if (!tokenSheet.isEmpty()) {
            brief.append("\n\nTHE PROJECT'S DESIGN SYSTEM (the ")
                    .append("user's own tokens — use these exact colors ")
                    .append("and @color/openui_* resources; they win ")
                    .append("over any generic defaults):\n")
                    .append(tokenSheet);
        }
        brief.append("\n\nDESIGN CONTRACT (binding — the reviewer ")
                .append("FAILS a cramped screen): outer screen padding ")
                .append("24dp; 16dp between cards/sections; 12dp inside ")
                .append("a card; touch targets at least 48dp tall; ")
                .append("title 20sp, body 14sp, secondary 12sp; use the ")
                .append("design-system palette; your screen's LOOK lines ")
                .append("are the law — copy their numbers. Screens must ")
                .append("look premium: rounded cards on a tinted ")
                .append("background with generous space, never a wall ")
                .append("of default-padding widgets.");
        brief.append("\n\nSPEED CONTRACT: every request costs seconds, ")
                .append("so build in FEW, BIG calls — one edit_screen ")
                .append("call with ALL the add_widget operations for ")
                .append("your screen, then one more with the styling ")
                .append("operations. NEVER one widget per call.");
        brief.append("\n\nWhen your steps are done, stop. End with ")
                .append("exactly two short sentences: WHAT you built, ")
                .append("and HOW to test it on the screen.");

        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user(brief.toString()));
        loop.run(baseUrl, apiKey, effectiveModel(), history, scId, true,
                new AgentLoop.Listener() {
                    @Override
                    public void onToolNote(String line) {
                        // Visible, but throttled: one line per part
                        // per 12s, so the chat shows life without
                        // drowning in worker chatter.
                        long now = System.currentTimeMillis();
                        boolean show;
                        synchronized (AgentBoard.this) {
                            Long last = lastNoteAt.get(part.index);
                            show = last == null
                                    || now - last >= 12000;
                            if (show) {
                                lastNoteAt.put(part.index, now);
                            }
                        }
                        if (show && line != null
                                && !line.trim().isEmpty()) {
                            note("Part " + part.index + " · " + line);
                        }
                    }

                    @Override
                    public void onResolvedModel(String resolvedModel) {
                        // Pin the whole build to the FIRST resolved
                        // model so every part comes from the same AI.
                        if (pinnedModel.isEmpty() && resolvedModel != null
                                && !resolvedModel.isEmpty()) {
                            pinnedModel = resolvedModel;
                        }
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
                        boolean quota =
                                error.kind == AiChatService.ErrorKind.QUOTA
                                || String.valueOf(error.message)
                                .contains("429");
                        if (quota) {
                            pauseForQuota(part);
                        }
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
        // Screens this part created now exist — include them in the
        // render check even though they did not exist at start.
        Set<String> nowKnown = existingScreenKeys();
        for (String token : part.claims) {
            if (nowKnown.contains(token)
                    && !part.screenDirs.contains(capital(token))) {
                part.screenDirs.add(capital(token));
            }
        }
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
                            + "other words. Visual quality counts: a "
                            + "cramped or default-padded screen, missing "
                            + "spacing numbers, ignored LOOK lines, or "
                            + "off-palette colors is PROBLEMS, not "
                            + "PASS.");
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
                    baseUrl, apiKey, effectiveModel(), messages, null);
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
