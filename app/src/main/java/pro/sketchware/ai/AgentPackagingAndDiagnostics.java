package pro.sketchware.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.util.Pair;

import com.besome.sketch.beans.BlockBean;
import com.besome.sketch.beans.BlockCollectionBean;
import com.besome.sketch.beans.MoreBlockCollectionBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ProjectResourceBean;
import com.besome.sketch.beans.SrcCodeBean;
import com.besome.sketch.beans.ViewBean;
import com.besome.sketch.beans.WidgetCollectionBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.Gx;
import a.a.a.Mp;
import a.a.a.Np;
import a.a.a.Op;
import a.a.a.Pp;
import a.a.a.ProjectBuilder;
import a.a.a.Qp;
import a.a.a.Rp;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.oB;
import a.a.a.wq;
import a.a.a.yB;
import a.a.a.yq;
import mod.hey.studios.moreblock.ReturnMoreblockManager;
import mod.hey.studios.project.backup.BackupFactory;
import mod.jbk.diagnostic.CompileErrorSaver;
import pro.sketchware.utility.FileUtil;

/**
 * Phase 8 backend: generated Java/XML/Manifest source inspector,
 * live runtime Logcat &amp; crash stack-trace reader, Collections manager
 * (Widgets, MoreBlocks, Blocks), and {@code .swb} project backup exporter.
 */
public final class AgentPackagingAndDiagnostics {

    private static final int MAX_RING_PER_PKG = 400;
    private static final int MAX_PACKAGES_TRACKED = 24;
    private static final Pattern LOG_LINE_PATTERN =
            Pattern.compile("^(.*\\d) ([VADEIW]) (.*): (.*)$",
                    Pattern.DOTALL);

    private static final Object LOG_LOCK = new Object();
    private static final Map<String, Deque<LogEntry>> LIVE_LOGS =
            new LinkedHashMap<>();
    private static volatile boolean receiverRegistered = false;

    private static final class LogEntry {
        final long timestampMs;
        final String raw;
        final String date;
        final String level;
        final String tag;
        final String message;

        LogEntry(long ts, String raw, String date, String level,
                 String tag, String message) {
            this.timestampMs = ts;
            this.raw = raw;
            this.date = date;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }
    }

    private AgentPackagingAndDiagnostics() {
    }

    // ------------------------------------------------------------------
    // 1. Live SketchLogger Broadcast Capture & App Log Reader
    // ------------------------------------------------------------------

    public static void registerLogReceiver(Context appContext) {
        if (receiverRegistered || appContext == null) {
            return;
        }
        synchronized (LOG_LOCK) {
            if (receiverRegistered) {
                return;
            }
            try {
                IntentFilter filter = new IntentFilter(
                        "pro.sketchware.ACTION_NEW_DEBUG_LOG");
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent == null) return;
                        String raw = intent.getStringExtra("log");
                        if (raw == null || raw.isEmpty()) return;
                        String pkg = intent.getStringExtra("packageName");
                        if (pkg == null || pkg.trim().isEmpty()) {
                            pkg = "_unknown";
                        } else {
                            pkg = pkg.trim();
                        }
                        appendLiveLog(pkg, raw);
                    }
                };
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(
                            receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appContext.registerReceiver(receiver, filter);
                }
                receiverRegistered = true;
            } catch (Exception ignored) {
            }
        }
    }

    private static void appendLiveLog(String pkg, String raw) {
        String date = "";
        String level = "I";
        String tag = "";
        String body = raw;
        Matcher m = LOG_LINE_PATTERN.matcher(raw);
        if (m.matches()) {
            date = m.group(1).trim();
            level = m.group(2).trim();
            tag = m.group(3).trim();
            body = m.group(4);
        } else if (raw.contains("AndroidRuntime")
                || raw.contains("Exception")
                || raw.contains("FATAL EXCEPTION")) {
            level = "E";
        }
        LogEntry entry = new LogEntry(System.currentTimeMillis(),
                raw, date, level, tag, body);
        synchronized (LOG_LOCK) {
            if (!LIVE_LOGS.containsKey(pkg)
                    && LIVE_LOGS.size() >= MAX_PACKAGES_TRACKED) {
                String oldestKey = LIVE_LOGS.keySet().iterator().next();
                LIVE_LOGS.remove(oldestKey);
            }
            Deque<LogEntry> q = LIVE_LOGS.computeIfAbsent(
                    pkg, k -> new ArrayDeque<>());
            if (q.size() >= MAX_RING_PER_PKG) {
                q.pollFirst();
            }
            q.addLast(entry);
        }
    }

    public static JsonObject readAppLogs(
            String scId, String packageOverride, String levelFilter,
            String textFilter, int limit, boolean clearBuffer) {
        HashMap<String, Object> meta = lC.b(scId);
        String pkg = (packageOverride != null
                && !packageOverride.trim().isEmpty())
                ? packageOverride.trim()
                : (meta != null ? yB.c(meta, "my_sc_pkg_name") : "");
        int cap = limit <= 0 ? 120 : Math.min(limit, MAX_RING_PER_PKG);
        String normLevel = levelFilter == null
                ? "" : levelFilter.trim().toUpperCase(Locale.ROOT);
        String normText = textFilter == null
                ? "" : textFilter.trim().toLowerCase(Locale.ROOT);

        List<LogEntry> snapshot = new ArrayList<>();
        synchronized (LOG_LOCK) {
            Deque<LogEntry> q = LIVE_LOGS.get(pkg);
            if (q != null) {
                snapshot.addAll(q);
                if (clearBuffer) {
                    q.clear();
                }
            }
        }

        JsonArray entries = new JsonArray();
        int start = Math.max(0, snapshot.size() - cap);
        for (int i = start; i < snapshot.size(); i++) {
            LogEntry e = snapshot.get(i);
            if (!normLevel.isEmpty() && !normLevel.equals(e.level)) {
                continue;
            }
            if (!normText.isEmpty()
                    && !e.raw.toLowerCase(Locale.ROOT).contains(normText)) {
                continue;
            }
            JsonObject row = new JsonObject();
            if (!e.date.isEmpty()) row.addProperty("date", e.date);
            row.addProperty("level", e.level);
            if (!e.tag.isEmpty()) row.addProperty("tag", e.tag);
            row.addProperty("message", e.message);
            entries.add(row);
        }

        JsonObject out = new JsonObject();
        out.addProperty("package_name", pkg);
        out.addProperty("live_log_count", entries.size());
        out.add("live_logs", entries);

        // Also include the latest exported logcat file if one exists.
        if (!pkg.isEmpty()) {
            File logDir = new File(Environment.getExternalStorageDirectory(),
                    ".sketchware/logcat/" + pkg);
            if (logDir.isDirectory()) {
                File[] files = logDir.listFiles(
                        f -> f.isFile() && f.getName().endsWith(".txt"));
                if (files != null && files.length > 0) {
                    Arrays.sort(files, (a, b) -> Long.compare(
                            b.lastModified(), a.lastModified()));
                    String diskLog = FileUtil.readFile(
                            files[0].getAbsolutePath());
                    if (diskLog != null && !diskLog.isEmpty()) {
                        out.addProperty("latest_exported_logcat_file",
                                files[0].getName());
                        out.addProperty("latest_exported_logcat_tail",
                                tailChars(diskLog, 4000));
                    }
                }
            }
        }

        // Include last saved compile error log if present.
        CompileErrorSaver saver = new CompileErrorSaver(scId);
        if (saver.logFileExists()) {
            String err = saver.getLogsFromFile();
            if (err != null && !err.trim().isEmpty()) {
                out.addProperty("last_compile_error",
                        tailChars(err.trim(), 5000));
            }
        }
        return out;
    }

    private static String tailChars(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) {
            return s == null ? "" : s;
        }
        return "...\n" + s.substring(s.length() - maxChars);
    }

    // ------------------------------------------------------------------
    // 2. Generated Source Inspector (SrcViewerActivity pipeline)
    // ------------------------------------------------------------------

    public static JsonObject inspectGeneratedSource(
            Context context, String scId, String fileFilter,
            int startLine, int maxLines) throws AgentTools.ToolException {
        try {
            AgentLogic.migrateAllLegacyInitializeLogic(scId);
            yq generator = new yq(context, scId);
            var fileManager = jC.b(scId);
            var dataManager = jC.a(scId);
            var libraryManager = jC.c(scId);
            generator.a(libraryManager, fileManager, dataManager,
                    yq.ExportType.SOURCE_CODE_VIEWING);
            ProjectBuilder builder = new ProjectBuilder(context, generator);
            builder.buildBuiltInLibraryInformation();
            ArrayList<SrcCodeBean> beans = generator.a(
                    fileManager, dataManager,
                    builder.getBuiltInLibraryManager());
            if (beans == null) {
                throw new AgentTools.ToolException("E_SOURCE_GEN_FAILED",
                        "Sketchware source generator returned null.");
            }

            JsonObject out = new JsonObject();
            JsonArray fileList = new JsonArray();
            for (SrcCodeBean b : beans) {
                JsonObject meta = new JsonObject();
                String src = b.source == null ? "" : b.source;
                int lines = src.isEmpty() ? 0 : src.split("\n", -1).length;
                meta.addProperty("file_name", b.srcFileName);
                meta.addProperty("lines", lines);
                meta.addProperty("chars", src.length());
                fileList.add(meta);
            }
            out.addProperty("total_generated_files", beans.size());
            out.add("generated_files", fileList);

            if (fileFilter != null && !fileFilter.trim().isEmpty()) {
                String q = fileFilter.trim().toLowerCase(Locale.ROOT);
                SrcCodeBean exact = null;
                List<SrcCodeBean> matches = new ArrayList<>();
                for (SrcCodeBean b : beans) {
                    if (b.srcFileName == null) continue;
                    String fn = b.srcFileName.toLowerCase(Locale.ROOT);
                    if (fn.equals(q)
                            || fn.equals(q + ".java")
                            || fn.equals(q + ".xml")) {
                        exact = b;
                        break;
                    }
                    if (fn.contains(q)) {
                        matches.add(b);
                    }
                }
                SrcCodeBean picked = exact != null ? exact
                        : (!matches.isEmpty() ? matches.get(0) : null);
                if (picked == null) {
                    throw new AgentTools.ToolException(
                            "E_GENERATED_FILE_NOT_FOUND",
                            "No generated file matched \"" + fileFilter
                                    + "\". Call inspect_generated_source "
                                    + "without file_name to list all "
                                    + "generated files.");
                }
                String src = picked.source == null ? "" : picked.source;
                String[] lines = src.split("\n", -1);
                int from = Math.max(1, startLine <= 0 ? 1 : startLine);
                int count = maxLines <= 0 ? 250 : Math.min(maxLines, 600);
                int to = Math.min(lines.length, from + count - 1);
                StringBuilder numbered = new StringBuilder();
                for (int i = from; i <= to; i++) {
                    numbered.append(i).append(": ")
                            .append(lines[i - 1]).append('\n');
                }
                out.addProperty("file_name", picked.srcFileName);
                out.addProperty("start_line", from);
                out.addProperty("end_line", to);
                out.addProperty("total_lines", lines.length);
                out.addProperty("has_more", to < lines.length);
                out.addProperty("numbered_source", numbered.toString());
            }
            return out;
        } catch (AgentTools.ToolException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_SOURCE_GEN_FAILED",
                    "Failed to generate source code preview: "
                            + String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 3. Collections Manager (Widgets Rp, MoreBlocks Pp, Blocks Mp)
    // ------------------------------------------------------------------

    public static JsonObject listCollections() {
        JsonObject out = new JsonObject();

        JsonArray widgetsArr = new JsonArray();
        ArrayList<WidgetCollectionBean> widgets = Rp.h().f();
        if (widgets != null) {
            for (WidgetCollectionBean w : widgets) {
                if (w == null) continue;
                JsonObject row = new JsonObject();
                row.addProperty("name", w.name);
                row.addProperty("widget_count",
                        w.widgets == null ? 0 : w.widgets.size());
                if (w.widgets != null && !w.widgets.isEmpty()) {
                    row.addProperty("root_id", w.widgets.get(0).id);
                    row.addProperty("root_type", w.widgets.get(0).type);
                }
                widgetsArr.add(row);
            }
        }
        out.add("widget_collections", widgetsArr);

        JsonArray moreBlocksArr = new JsonArray();
        ArrayList<MoreBlockCollectionBean> mbs = Pp.h().f();
        if (mbs != null) {
            for (MoreBlockCollectionBean mb : mbs) {
                if (mb == null) continue;
                JsonObject row = new JsonObject();
                row.addProperty("name", mb.name);
                row.addProperty("spec", mb.spec);
                row.addProperty("block_count",
                        mb.blocks == null ? 0 : mb.blocks.size());
                moreBlocksArr.add(row);
            }
        }
        out.add("more_block_collections", moreBlocksArr);

        JsonArray blocksArr = new JsonArray();
        ArrayList<BlockCollectionBean> bs = Mp.h().f();
        if (bs != null) {
            for (BlockCollectionBean b : bs) {
                if (b == null) continue;
                JsonObject row = new JsonObject();
                row.addProperty("name", b.name);
                row.addProperty("block_count",
                        b.blocks == null ? 0 : b.blocks.size());
                blocksArr.add(row);
            }
        }
        out.add("block_collections", blocksArr);
        return out;
    }

    public static JsonObject saveWidgetToCollection(
            String scId, String rawActivity, String widgetId,
            String collectionName) throws AgentTools.ToolException {
        ProjectFileBean bean = resolveScreenOrCustomView(scId, rawActivity);
        String xmlName = bean.getXmlName();
        ViewBean target = findViewById(jC.a(scId).d(xmlName), widgetId);
        if (target == null) {
            throw new AgentTools.ToolException("E_WIDGET_NOT_FOUND",
                    "Widget \"" + widgetId + "\" was not found on "
                            + bean.fileName + ".");
        }
        ArrayList<ViewBean> subTree = jC.a(scId).b(xmlName, target);
        ArrayList<ViewBean> cloned = new ArrayList<>();
        for (ViewBean vb : subTree) {
            cloned.add(vb.clone());
            String bg = vb.layout.backgroundResource;
            if (bg != null && !"NONE".equals(bg)
                    && jC.d(scId).l(bg) && !Op.g().b(bg)) {
                try {
                    Op.g().a(scId, jC.d(scId).g(bg));
                } catch (Exception ignored) {
                }
            }
            String img = vb.image.resName;
            if (img != null && !"default_image".equals(img)
                    && !"NONE".equals(img)
                    && jC.d(scId).l(img) && !Op.g().b(img)) {
                try {
                    Op.g().a(scId, jC.d(scId).g(img));
                } catch (Exception ignored) {
                }
            }
        }
        if (Rp.h().g().contains(collectionName)) {
            Rp.h().a(collectionName, true);
        }
        Rp.h().a(collectionName, cloned, true);
        JsonObject res = new JsonObject();
        res.addProperty("collection_name", collectionName);
        res.addProperty("saved_widgets", cloned.size());
        return res;
    }

    public static JsonObject importWidgetFromCollection(
            String scId, String rawActivity, String collectionName,
            String parentId, int index)
            throws AgentTools.ToolException, IOException {
        WidgetCollectionBean wcb = Rp.h().a(collectionName);
        if (wcb == null || wcb.widgets == null || wcb.widgets.isEmpty()) {
            throw new AgentTools.ToolException("E_COLLECTION_NOT_FOUND",
                    "Widget collection \"" + collectionName
                            + "\" does not exist or is empty.");
        }
        ProjectFileBean bean = resolveScreenOrCustomView(scId, rawActivity);
        String xmlName = bean.getXmlName();
        String targetParent = (parentId == null || parentId.trim().isEmpty())
                ? "root" : parentId.trim();
        if (!"root".equals(targetParent)
                && findViewById(jC.a(scId).d(xmlName), targetParent) == null) {
            throw new AgentTools.ToolException("E_PARENT_NOT_FOUND",
                    "Parent container \"" + targetParent
                            + "\" does not exist on " + bean.fileName + ".");
        }

        SketchwareActions.backupProjectOnce(scId);
        oB fileUtil = new oB();
        boolean copiedImages = false;
        AgentAssets.resetResourceManager(scId);
        ArrayList<ViewBean> cloned = new ArrayList<>();
        for (ViewBean src : wcb.widgets) {
            ViewBean copy = src.clone();
            cloned.add(copy);
            copiedImages |= maybeCopyCollectionImage(scId, fileUtil,
                    copy.layout.backgroundResource);
            copiedImages |= maybeCopyCollectionImage(scId, fileUtil,
                    copy.image.resName);
        }

        Map<String, String> idMap = new HashMap<>();
        for (int i = 0; i < cloned.size(); i++) {
            ViewBean vb = cloned.get(i);
            String newId = jC.a(scId).h(xmlName, vb.id)
                    ? nextUniqueWidgetId(scId, xmlName, vb)
                    : vb.id;
            idMap.put(vb.id, newId);
            vb.id = newId;
            if (i == 0) {
                vb.parent = targetParent;
                vb.index = index < 0
                        ? jC.a(scId).e(xmlName, targetParent).size()
                        : index;
            } else if (vb.parent != null && idMap.containsKey(vb.parent)) {
                vb.parent = idMap.get(vb.parent);
            }
            jC.a(scId).a(xmlName, vb);
        }
        if (copiedImages) {
            jC.d(scId).y();
            jC.a(scId).a(jC.d(scId));
        }
        SketchwareActions.commitProject(scId);
        if (copiedImages) {
            jC.d(scId).z();
            AgentAssets.resetResourceManager(scId);
        }

        JsonObject res = new JsonObject();
        res.addProperty("collection_name", collectionName);
        res.addProperty("root_widget_id", cloned.get(0).id);
        res.addProperty("imported_widgets", cloned.size());
        return res;
    }

    public static JsonObject saveMoreBlockToCollection(
            String scId, String rawActivity, String moreBlockName,
            String collectionName) throws AgentTools.ToolException {
        ProjectFileBean act = SketchwareProjects.findActivity(
                scId, rawActivity);
        if (act == null) {
            throw new AgentTools.ToolException("E_ACTIVITY_NOT_FOUND",
                    "Activity \"" + rawActivity + "\" was not found.");
        }
        String javaName = act.getJavaName();
        String matchedKey = null;
        String matchedSpec = null;
        for (Pair<String, String> mb : jC.a(scId).i(javaName)) {
            String plain = ReturnMoreblockManager.getMbName(mb.first);
            if (plain.equals(moreBlockName) || mb.first.equals(moreBlockName)) {
                matchedKey = mb.first;
                matchedSpec = mb.second;
                break;
            }
        }
        if (matchedKey == null) {
            throw new AgentTools.ToolException("E_MOREBLOCK_NOT_FOUND",
                    "MoreBlock \"" + moreBlockName + "\" was not found on "
                            + act.fileName + ".");
        }
        ArrayList<BlockBean> blocks = jC.a(scId).a(
                javaName, matchedKey + "_moreBlock");
        ArrayList<BlockBean> cloned = new ArrayList<>();
        if (blocks != null) {
            for (BlockBean b : blocks) {
                cloned.add(b.clone());
            }
        }
        if (Pp.h().g().contains(collectionName)) {
            Pp.h().a(collectionName, false);
            Pp.h().e();
        }
        Pp.h().a(collectionName, matchedSpec, cloned, true);
        JsonObject res = new JsonObject();
        res.addProperty("collection_name", collectionName);
        res.addProperty("spec", matchedSpec);
        res.addProperty("block_count", cloned.size());
        return res;
    }

    public static JsonObject importMoreBlockFromCollection(
            String scId, String rawActivity, String collectionName)
            throws AgentTools.ToolException, IOException {
        MoreBlockCollectionBean mb = Pp.h().a(collectionName);
        if (mb == null || mb.spec == null || mb.spec.isEmpty()) {
            throw new AgentTools.ToolException("E_COLLECTION_NOT_FOUND",
                    "MoreBlock collection \"" + collectionName
                            + "\" does not exist.");
        }
        ProjectFileBean act = SketchwareProjects.findActivity(
                scId, rawActivity);
        if (act == null) {
            throw new AgentTools.ToolException("E_ACTIVITY_NOT_FOUND",
                    "Activity \"" + rawActivity + "\" was not found.");
        }
        String javaName = act.getJavaName();
        String mbNameWithType = ReturnMoreblockManager
                .getMbNameWithTypeFromSpec(mb.spec);
        String plainName = ReturnMoreblockManager.getMbName(mbNameWithType);
        for (Pair<String, String> existing : jC.a(scId).i(javaName)) {
            if (ReturnMoreblockManager.getMbName(existing.first)
                    .equals(plainName)) {
                throw new AgentTools.ToolException("E_DUPLICATE_MOREBLOCK",
                        "Activity " + act.fileName + " already has a "
                                + "MoreBlock named \"" + plainName + "\".");
            }
        }

        SketchwareActions.backupProjectOnce(scId);
        eC store = jC.a(scId);
        oB fileUtil = new oB();
        boolean copiedRes = false;
        AgentAssets.resetResourceManager(scId);
        ArrayList<BlockBean> clonedBlocks = new ArrayList<>();
        if (mb.blocks != null) {
            for (BlockBean src : mb.blocks) {
                BlockBean next = src.clone();
                clonedBlocks.add(next);
                if ("getVar".equals(next.opCode)) {
                    switch (next.type) {
                        case "b" -> ensureVar(store, javaName, 0, next.spec);
                        case "d" -> ensureVar(store, javaName, 1, next.spec);
                        case "s" -> ensureVar(store, javaName, 2, next.spec);
                        case "a" -> ensureVar(store, javaName, 3, next.spec);
                        case "l" -> {
                            if ("List Number".equals(next.typeName)) {
                                ensureList(store, javaName, 1, next.spec);
                            } else if ("List String".equals(next.typeName)) {
                                ensureList(store, javaName, 2, next.spec);
                            } else if ("List Map".equals(next.typeName)) {
                                ensureList(store, javaName, 3, next.spec);
                            }
                        }
                        default -> { }
                    }
                }
                ArrayList<Gx> infos = next.getParamClassInfo();
                for (int i = 0; i < infos.size()
                        && i < next.parameters.size(); i++) {
                    Gx gx = infos.get(i);
                    String val = next.parameters.get(i);
                    if (val == null || val.isEmpty()
                            || val.charAt(0) == '@') {
                        continue;
                    }
                    if (gx.b("boolean.SelectBoolean")) {
                        ensureVar(store, javaName, 0, val);
                    } else if (gx.b("double.SelectDouble")) {
                        ensureVar(store, javaName, 1, val);
                    } else if (gx.b("String.SelectString")) {
                        ensureVar(store, javaName, 2, val);
                    } else if (gx.b("Map")) {
                        ensureVar(store, javaName, 3, val);
                    } else if (gx.b("ListInt")) {
                        ensureList(store, javaName, 1, val);
                    } else if (gx.b("ListString")) {
                        ensureList(store, javaName, 2, val);
                    } else if (gx.b("ListMap")) {
                        ensureList(store, javaName, 3, val);
                    } else if (gx.b("resource") || gx.b("resource_bg")) {
                        copiedRes |= maybeCopyCollectionImage(
                                scId, fileUtil, val);
                    } else if (gx.b("sound")) {
                        copiedRes |= maybeCopyCollectionSound(
                                scId, fileUtil, val);
                    } else if (gx.b("font")) {
                        copiedRes |= maybeCopyCollectionFont(
                                scId, fileUtil, val);
                    }
                }
            }
        }
        store.a(javaName, mbNameWithType, mb.spec);
        store.a(javaName, mbNameWithType + "_moreBlock", clonedBlocks);
        if (copiedRes) {
            jC.d(scId).y();
            store.a(jC.d(scId));
        }
        SketchwareActions.commitProject(scId);
        if (copiedRes) {
            jC.d(scId).z();
            AgentAssets.resetResourceManager(scId);
        }

        JsonObject res = new JsonObject();
        res.addProperty("collection_name", collectionName);
        res.addProperty("imported_more_block", plainName);
        res.addProperty("spec", mb.spec);
        res.addProperty("block_count", clonedBlocks.size());
        return res;
    }

    public static JsonObject saveEventBlocksToCollection(
            String scId, String rawActivity, String target,
            String eventName, String collectionName)
            throws AgentTools.ToolException {
        ProjectFileBean act = SketchwareProjects.findActivity(
                scId, rawActivity);
        if (act == null) {
            throw new AgentTools.ToolException("E_ACTIVITY_NOT_FOUND",
                    "Activity \"" + rawActivity + "\" was not found.");
        }
        String key = resolveEventStorageKey(target, eventName);
        ArrayList<BlockBean> blocks = jC.a(scId).a(act.getJavaName(), key);
        if (blocks == null || blocks.isEmpty()) {
            throw new AgentTools.ToolException("E_EMPTY_EVENT",
                    "Event " + key + " on " + act.fileName
                            + " has no blocks to save.");
        }
        ArrayList<BlockBean> cloned = new ArrayList<>();
        for (BlockBean b : blocks) {
            cloned.add(b.clone());
        }
        if (Mp.h().g().contains(collectionName)) {
            Mp.h().a(collectionName, true);
        }
        Mp.h().a(collectionName, cloned, true);
        JsonObject res = new JsonObject();
        res.addProperty("collection_name", collectionName);
        res.addProperty("saved_blocks", cloned.size());
        return res;
    }

    public static void deleteCollectionItem(String kind, String name)
            throws AgentTools.ToolException {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        switch (k) {
            case "widget" -> {
                if (!Rp.h().g().contains(name)) {
                    throw new AgentTools.ToolException(
                            "E_COLLECTION_NOT_FOUND",
                            "Widget collection \"" + name + "\" not found.");
                }
                Rp.h().a(name, true);
            }
            case "more_block", "moreblock" -> {
                if (!Pp.h().g().contains(name)) {
                    throw new AgentTools.ToolException(
                            "E_COLLECTION_NOT_FOUND",
                            "MoreBlock collection \"" + name
                                    + "\" not found.");
                }
                Pp.h().a(name, false);
                Pp.h().e();
            }
            case "block", "blocks" -> {
                if (!Mp.h().g().contains(name)) {
                    throw new AgentTools.ToolException(
                            "E_COLLECTION_NOT_FOUND",
                            "Block collection \"" + name + "\" not found.");
                }
                Mp.h().a(name, true);
            }
            default -> throw new AgentTools.ToolException("E_BAD_KIND",
                    "Unknown collection kind \"" + kind
                            + "\". Use widget, more_block, or block.");
        }
    }

    // ------------------------------------------------------------------
    // 4. .swb Project Backup Export (BackupFactory)
    // ------------------------------------------------------------------

    public static JsonObject exportProjectBackup(
            Context context, String scId,
            boolean includeLocalLibs, boolean includeCustomBlocks)
            throws AgentTools.ToolException {
        SketchwareProjects.ProjectInfo proj =
                SketchwareProjects.project(scId);
        BackupFactory bf = new BackupFactory(scId);
        bf.setBackupLocalLibs(includeLocalLibs);
        bf.setBackupCustomBlocks(includeCustomBlocks);
        bf.backup(context, proj.name);
        File out = bf.getOutFile();
        if (out == null || !out.exists() || out.length() == 0) {
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "BackupFactory did not produce a .swb archive for "
                            + proj.name + ".");
        }
        JsonObject res = new JsonObject();
        res.addProperty("project", proj.name);
        res.addProperty("swb_path", out.getAbsolutePath());
        res.addProperty("swb_kb", out.length() / 1024);
        res.addProperty("included_local_libs", includeLocalLibs);
        res.addProperty("included_custom_blocks", includeCustomBlocks);
        return res;
    }

    // ------------------------------------------------------------------
    // Internal Helpers
    // ------------------------------------------------------------------

    private static String resolveEventStorageKey(
            String target, String eventName) {
        String t = (target == null || target.trim().isEmpty()
                || "activity".equalsIgnoreCase(target.trim()))
                ? "" : target.trim();
        String ev = (eventName == null || eventName.trim().isEmpty())
                ? "initializeLogic" : eventName.trim();
        if (t.isEmpty()) {
            if ("initializeLogic".equals(ev) || "onCreate".equals(ev)) {
                return "onCreate_initializeLogic";
            }
            return ev + "_" + ev;
        }
        return t + "_" + ev;
    }

    private static ProjectFileBean resolveScreenOrCustomView(
            String scId, String rawName) throws AgentTools.ToolException {
        ProjectFileBean act = SketchwareProjects.findActivity(scId, rawName);
        if (act != null) {
            return act;
        }
        ProjectFileBean cv = SketchwareProjects.findCustomView(scId, rawName);
        if (cv != null) {
            return cv;
        }
        throw new AgentTools.ToolException("E_SCREEN_NOT_FOUND",
                "Screen or custom view \"" + rawName + "\" was not found.");
    }

    private static ViewBean findViewById(
            List<ViewBean> views, String widgetId) {
        if (views == null || widgetId == null) return null;
        for (ViewBean vb : views) {
            if (widgetId.equals(vb.id)) return vb;
        }
        return null;
    }

    private static String nextUniqueWidgetId(
            String scId, String xmlName, ViewBean vb) {
        String prefix = vb.id == null ? "view" : vb.id.replaceAll("\\d+$", "");
        if (prefix.isEmpty()) prefix = "view";
        int idx = 1;
        while (jC.a(scId).h(xmlName, prefix + idx)) {
            idx++;
        }
        return prefix + idx;
    }

    private static boolean maybeCopyCollectionImage(
            String scId, oB fileUtil, String resName) {
        if (resName == null || resName.isEmpty()
                || "NONE".equals(resName)
                || "default_image".equals(resName)
                || jC.d(scId).l(resName)
                || !Op.g().b(resName)) {
            return false;
        }
        try {
            ProjectResourceBean img = Op.g().a(resName);
            fileUtil.a(wq.a() + File.separator + "image" + File.separator
                            + "data" + File.separator + img.resFullName,
                    wq.g() + File.separator + scId + File.separator
                            + img.resFullName);
            jC.d(scId).b.add(img);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean maybeCopyCollectionSound(
            String scId, oB fileUtil, String soundName) {
        if (soundName == null || soundName.isEmpty()
                || jC.d(scId).m(soundName)
                || !Qp.g().b(soundName)) {
            return false;
        }
        try {
            ProjectResourceBean snd = Qp.g().a(soundName);
            fileUtil.a(wq.a() + File.separator + "sound" + File.separator
                            + "data" + File.separator + snd.resFullName,
                    wq.t() + File.separator + scId + File.separator
                            + snd.resFullName);
            jC.d(scId).c.add(snd);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean maybeCopyCollectionFont(
            String scId, oB fileUtil, String fontName) {
        if (fontName == null || fontName.isEmpty()
                || "default_font".equals(fontName)
                || jC.d(scId).k(fontName)
                || !Np.g().b(fontName)) {
            return false;
        }
        try {
            ProjectResourceBean fnt = Np.g().a(fontName);
            fileUtil.a(wq.a() + File.separator + "font" + File.separator
                            + "data" + File.separator + fnt.resFullName,
                    wq.d() + File.separator + scId + File.separator
                            + fnt.resFullName);
            jC.d(scId).d.add(fnt);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void ensureVar(
            eC store, String javaName, int type, String name) {
        if (name == null || name.isEmpty()) return;
        for (Pair<Integer, String> v : store.k(javaName)) {
            if (v.second.equals(name)) return;
        }
        store.c(javaName, type, name);
    }

    private static void ensureList(
            eC store, String javaName, int type, String name) {
        if (name == null || name.isEmpty()) return;
        for (Pair<Integer, String> v : store.j(javaName)) {
            if (v.second.equals(name)) return;
        }
        store.b(javaName, type, name);
    }
}
