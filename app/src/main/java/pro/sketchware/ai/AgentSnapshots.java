package pro.sketchware.ai;

import android.os.Environment;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Point-in-time copies of a project, taken before the agent touches it,
 * with a human-readable diff and one-tap restore. This is the layer that
 * makes the agent fearless: every change is undoable, and the user sees
 * WHAT they are undoing before they tap.
 *
 * <p>A snapshot is a plain mirrored copy of the project folder (minus
 * build outputs, which are regenerated), under
 * {@code .Sketchware/ai/snapshots/<scId>/<timestamp>/}. The newest
 * snapshots are kept (capped per project); everything is best-effort —
 * snapshotting must never disturb a turn.
 */
public final class AgentSnapshots {

    private AgentSnapshots() {
    }

    /** Newest snapshots kept per project; older ones are pruned. */
    private static final int KEEP_PER_PROJECT = 10;

    /** Marker file so restore can recognise our own snapshots. */
    private static final String MARKER = "sketchware-agent-snapshot.json";

    private static File snapshotsRoot() {
        return new File(AiPaths.agentRoot(), "snapshots");
    }

    private static File projectDir(String scId) {
        return new File(Environment.getExternalStorageDirectory(),
                ".sketchware/mysc/" + scId);
    }

    /** Build outputs and caches: regenerated, never snapshotted. */
    private static boolean isExcluded(File file) {
        String name = file.getName();
        return "build".equals(name) || ".gradle".equals(name)
                || ".cxx".equals(name);
    }

    /** One restoreable point in time, newest first in lists. */
    public static final class Snapshot {
        public final File dir;
        public final long time;
        /** Files the snapshot holds. */
        public final int fileCount;

        Snapshot(File dir, long time, int fileCount) {
            this.dir = dir;
            this.time = time;
            this.fileCount = fileCount;
        }

        public String label() {
            String formatted = new SimpleDateFormat("MMM d, h:mm a",
                    Locale.US).format(new Date(time));
            return formatted + " · " + fileCount + " files";
        }
    }

    /**
     * Copies the project as it stands right now into a new snapshot and
     * prunes old ones. Returns the snapshot, or null when there is no
     * project to copy or the copy failed — callers must treat null as
     * "no snapshot this turn", never as an error.
     */
    public static Snapshot snapshot(String scId) {
        try {
            File project = projectDir(scId);
            if (!project.isDirectory()) {
                return null;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss",
                    Locale.US).format(new Date());
            File dir = new File(snapshotsRoot(), scId + "/" + stamp);
            // Same-second re-entrant call: bump until unique.
            while (dir.exists()) {
                dir = new File(dir.getParentFile(), stamp + "_"
                        + System.currentTimeMillis() % 1000);
            }
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            int[] count = {0};
            copyTree(project, dir, count);
            if (count[0] == 0) {
                return null;
            }
            writeMarker(dir, scId);
            prune(scId);
            return new Snapshot(dir, dir.lastModified(), count[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeMarker(File dir, String scId) {
        try {
            com.google.gson.JsonObject marker = new com.google.gson.JsonObject();
            marker.addProperty("format", "sketchware-agent-snapshot/1");
            marker.addProperty("sc_id", scId);
            marker.addProperty("taken_at", System.currentTimeMillis());
            java.io.FileWriter writer = new java.io.FileWriter(
                    new File(dir, MARKER));
            new Gson().toJson(marker, writer);
            writer.close();
        } catch (Exception ignored) {
            // A missing marker only hides the snapshot from restore.
        }
    }

    /** All snapshots of a project, newest first. */
    public static List<Snapshot> list(String scId) {
        List<Snapshot> out = new ArrayList<>();
        try {
            File dir = new File(snapshotsRoot(), scId);
            File[] entries = dir.listFiles();
            if (entries == null) {
                return out;
            }
            Arrays.sort(entries,
                    (File a, File b) -> Long.compare(
                            b.lastModified(), a.lastModified()));
            for (File entry : entries) {
                if (!entry.isDirectory() || !new File(entry, MARKER)
                        .isFile()) {
                    continue;
                }
                int[] count = {0};
                countFiles(entry, count);
                out.add(new Snapshot(entry, entry.lastModified(), count[0]));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Keeps only the newest {@link #KEEP_PER_PROJECT} snapshots. */
    public static void prune(String scId) {
        try {
            List<Snapshot> snapshots = list(scId);
            for (int i = KEEP_PER_PROJECT; i < snapshots.size(); i++) {
                deleteTree(snapshots.get(i).dir);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * The whole change-set between a snapshot and the project as it
     * stands now: human-readable lines, plain words, screens first.
     * Empty list = identical. This is what the user reads before
     * deciding to restore.
     */
    public static List<String> diffLines(String scId, Snapshot snap) {
        List<String> out = new ArrayList<>();
        try {
            File project = projectDir(scId);
            if (!project.isDirectory()) {
                out.add("The project folder is missing.");
                return out;
            }
            java.util.Map<String, Long> nowHash = new java.util.LinkedHashMap<>();
            hashTree(project, "", nowHash);
            java.util.Map<String, Long> thenHash = new java.util.LinkedHashMap<>();
            hashTree(snap.dir, "", thenHash);
            thenHash.remove(MARKER);
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            List<String> changed = new ArrayList<>();
            for (String path : nowHash.keySet()) {
                if (isScreenLogic(path) || isScreenLayout(path)) {
                    continue;
                }
                if (!thenHash.containsKey(path)) {
                    added.add(path);
                } else if (!nowHash.get(path).equals(thenHash.get(path))) {
                    changed.add(path);
                }
            }
            for (String path : thenHash.keySet()) {
                if (isScreenLogic(path) || isScreenLayout(path)) {
                    continue;
                }
                if (!nowHash.containsKey(path)) {
                    removed.add(path);
                }
            }
            // Screens are what the user thinks in — they lead the diff.
            java.util.TreeSet<String> screens = new java.util.TreeSet<>();
            collectScreens(project, screens);
            collectScreens(snap.dir, screens);
            for (String screen : screens) {
                String logicPath = "File/" + screen + "/File";
                String layoutPath = "File/" + screen + "/View";
                boolean logicNow = nowHash.containsKey(logicPath);
                boolean logicThen = thenHash.containsKey(logicPath);
                boolean layoutNow = nowHash.containsKey(layoutPath);
                boolean layoutThen = thenHash.containsKey(layoutPath);
                if (logicNow && !logicThen) {
                    out.add("Screen " + screen
                            + " — ADDED since this snapshot");
                } else if (!logicNow && logicThen) {
                    out.add("Screen " + screen
                            + " — REMOVED since this snapshot");
                } else if (logicNow && logicThen && !nowHash.get(logicPath)
                        .equals(thenHash.get(logicPath))) {
                    long[] lines = lineDelta(project, snap.dir, logicPath);
                    out.add("Screen " + screen + " — logic changed ("
                            + lines[0] + " lines now, " + lines[1]
                            + " at the snapshot)");
                }
                if (layoutNow && layoutThen && !nowHash.getOrDefault(
                        layoutPath, 0L).equals(thenHash.get(layoutPath))) {
                    out.add("Screen " + screen
                            + " — layout/widgets changed");
                }
            }
            for (String path : added) {
                out.add("Added: " + shortPath(path));
            }
            for (String path : changed) {
                out.add("Changed: " + shortPath(path));
            }
            for (String path : removed) {
                out.add("Removed: " + shortPath(path));
            }
            if (out.isEmpty()) {
                out.add("No differences — the project matches this "
                        + "snapshot.");
            }
            int cap = 80;
            if (out.size() > cap) {
                List<String> capped = new ArrayList<>(
                        out.subList(0, cap));
                capped.add("… and " + (out.size() - cap)
                        + " more changes");
                return capped;
            }
        } catch (Throwable t) {
            out.add("Could not compare: " + t.getMessage());
        }
        return out;
    }

    /**
     * Makes the project match the snapshot again: files in the snapshot
     * are copied back, files created after the snapshot are removed.
     * Build outputs are left alone. Returns true on success.
     */
    public static boolean restore(String scId, Snapshot snap) {
        try {
            File project = projectDir(scId);
            if (!snap.dir.isDirectory()) {
                return false;
            }
            // Everything currently in the project that the snapshot
            // does not hold goes away (except build outputs).
            java.util.Map<String, Long> nowHash = new java.util.LinkedHashMap<>();
            hashTree(project, "", nowHash);
            java.util.Map<String, Long> thenHash = new java.util.LinkedHashMap<>();
            hashTree(snap.dir, "", thenHash);
            thenHash.remove(MARKER);
            for (String path : nowHash.keySet()) {
                if (isScreenLogic(path) || isScreenLayout(path)) {
                    continue;
                }
                if (!thenHash.containsKey(path)) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(project, path).delete();
                }
            }
            int[] count = {0};
            copyTree(snap.dir, project, count);
            new File(project, MARKER).delete();
            return count[0] > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Screen-scoped undo: makes only the named screens (their
     * {@code File/<screen>/} subtrees) match the snapshot again —
     * files in them are copied back, files created there after the
     * snapshot are removed. Everything outside those screens keeps
     * whatever the other parts did. Returns true when a screen was
     * restored.
     */
    public static boolean restoreScreens(String scId, Snapshot snap,
                                         List<String> screenDirs) {
        if (snap == null || screenDirs == null
                || screenDirs.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (String screen : screenDirs) {
            if (screen == null || screen.isEmpty()) {
                continue;
            }
            try {
                File project = projectDir(scId);
                File live = new File(new File(project, "File"), screen);
                File saved = new File(new File(snap.dir, "File"), screen);
                if (!saved.isDirectory()) {
                    // The screen did not exist at the snapshot: it was
                    // created by this part, so undo = remove it.
                    if (live.isDirectory()) {
                        deleteTree(live);
                        any = true;
                    }
                    continue;
                }
                // Remove live files created after the snapshot, then
                // copy the snapshot's screen back over.
                java.util.Map<String, Long> liveHash =
                        new java.util.LinkedHashMap<>();
                hashTree(live, "", liveHash);
                java.util.Map<String, Long> savedHash =
                        new java.util.LinkedHashMap<>();
                hashTree(saved, "", savedHash);
                for (String rel : liveHash.keySet()) {
                    if (!savedHash.containsKey(rel)) {
                        //noinspection ResultOfMethodCallIgnored
                        new File(live, rel).delete();
                    }
                }
                int[] count = {0};
                copyTree(saved, live, count);
                any |= count[0] > 0;
            } catch (Throwable ignored) {
            }
        }
        return any;
    }

    // ---- path helpers -------------------------------------------------

    /**
     * Exactly the screen's own logic file: {@code File/<screen>/File}.
     * Deeper paths (custom-view rows also end in /File) must stay in
     * the generic diff — only the top-level per-screen pair gets the
     * plain-language "Screen X" treatment.
     */
    private static boolean isScreenLogic(String path) {
        String[] parts = path.split("/");
        return parts.length == 3 && "File".equals(parts[0])
                && "File".equals(parts[2]);
    }

    private static boolean isScreenLayout(String path) {
        String[] parts = path.split("/");
        return parts.length == 3 && "File".equals(parts[0])
                && "View".equals(parts[2]);
    }

    /** Screen names on one side of the comparison. */
    private static void collectScreens(File root,
                                       java.util.TreeSet<String> out) {
        File[] screens = new File(root, "File").listFiles();
        if (screens == null) {
            return;
        }
        for (File screen : screens) {
            if (screen.isDirectory() && !isExcluded(screen)) {
                out.add(screen.getName());
            }
        }
    }

    /** Short, human path: drop the boilerplate prefix. */
    private static String shortPath(String path) {
        String out = path;
        if (out.startsWith("app/src/main/java/com/")) {
            out = "code/" + out.substring("app/src/main/java/com/"
                    .length());
        } else if (out.startsWith("app/src/main/res/")) {
            out = "res/" + out.substring("app/src/main/res/".length());
        } else if (out.startsWith("app/src/main/assets/")) {
            out = "assets/" + out.substring("app/src/main/assets/"
                    .length());
        }
        return out;
    }

    /** [lines now, lines at the snapshot] for a project text file. */
    private static long[] lineDelta(File project, File snap,
                                    String relPath) {
        return new long[]{countLines(new File(project, relPath)),
                countLines(new File(snap, relPath))};
    }

    private static long countLines(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            long lines = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        lines++;
                    }
                }
            }
            return lines + 1;
        } catch (IOException e) {
            return 0;
        }
    }

    // ---- tree helpers --------------------------------------------------

    private static void hashTree(File dir, String prefix,
                                 java.util.Map<String, Long> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (isExcluded(entry)) {
                continue;
            }
            String rel = prefix.isEmpty() ? entry.getName()
                    : prefix + "/" + entry.getName();
            if (entry.isDirectory()) {
                hashTree(entry, rel, out);
            } else if (entry.isFile()) {
                out.put(rel, entry.lastModified() ^ (entry.length()
                        << 20));
            }
        }
    }

    private static void countFiles(File dir, int[] count) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                countFiles(entry, count);
            } else {
                count[0]++;
            }
        }
    }

    private static void copyTree(File from, File to, int[] count) {
        File[] entries = from.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (isExcluded(entry)) {
                continue;
            }
            File target = new File(to, entry.getName());
            if (entry.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                target.mkdirs();
                copyTree(entry, target, count);
            } else if (entry.isFile()) {
                try (FileInputStream in = new FileInputStream(entry);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                target.setLastModified(entry.lastModified());
                count[0]++;
            }
        }
    }

    private static void deleteTree(File dir) {
        File[] entries = dir.listFiles();
        if (entries != null) {
            for (File entry : entries) {
                if (entry.isDirectory()) {
                    deleteTree(entry);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    entry.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
