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
        Thread supervisor = new Thread(this::supervise, "agent-board");
        supervisor.setDaemon(true);
        supervisor.start();
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
        running = true;
        note("Rebuilding: " + part.title);
        Thread supervisor = new Thread(this::supervise, "agent-board");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    // ---- internals -----------------------------------------------------

    private void supervise() {
        // Restartable board: only parts still queued are scheduled.
        List<Part> queue = new ArrayList<>();
        synchronized (this) {
            for (Part part : parts) {
                if (part.state == PartState.QUEUED) {
                    queue.add(part);
                }
            }
        }
        List<Thread> workers = new ArrayList<>();
        while (!queue.isEmpty() && running) {
            Part next = null;
            synchronized (this) {
                for (Part part : queue) {
                    if (disjoint(part.claims, activeClaims)) {
                        next = part;
                        break;
                    }
                }
            }
            if (next == null) {
                try {
                    // All queued parts are blocked by running claims;
                    // wait for a worker to finish and free its claims.
                    Thread.sleep(1200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                boolean anyActive = false;
                synchronized (this) {
                    for (Part part : parts) {
                        if (part.state == PartState.BUILDING) {
                            anyActive = true;
                        }
                    }
                }
                if (!anyActive) {
                    // Nothing running and nothing startable: deadlock
                    // guard — start the first queued part anyway.
                    synchronized (this) {
                        next = queue.get(0);
                    }
                }
                continue;
            }
            queue.remove(next);
            final Part part = next;
            synchronized (this) {
                activeClaims.addAll(part.claims);
                part.state = PartState.BUILDING;
                part.beforeSnapshot = AgentSnapshots.snapshot(scId);
            }
            changed();
            Thread worker = new Thread(() -> runWorker(part),
                    "agent-part-" + part.index);
            workers.add(worker);
            worker.start();
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Wait for the last workers, then close the board.
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (running) {
                running = false;
                changed();
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
        brief.append("You are one of several parallel builders working ")
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
                        finishPart(part, PartState.DONE, text.trim());
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        finishPart(part, PartState.FAILED,
                                String.valueOf(error.message));
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

    private void finishPart(Part part, PartState state, String detail) {
        synchronized (this) {
            part.state = state;
            if (state == PartState.DONE) {
                part.summary = detail;
            } else {
                part.error = detail;
            }
            activeClaims.removeAll(part.claims);
        }
        note(state == PartState.DONE
                ? "Part ready: " + part.title
                : "Part failed: " + part.title);
        changed();
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
