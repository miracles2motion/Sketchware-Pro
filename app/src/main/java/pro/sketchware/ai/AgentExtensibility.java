package pro.sketchware.ai;

import android.os.Environment;

import com.besome.sketch.beans.ProjectFileBean;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import a.a.a.Jx;
import a.a.a.eC;
import a.a.a.hC;
import a.a.a.iC;
import a.a.a.jC;
import a.a.a.wq;
import a.a.a.yq;
import dev.aldi.sayuti.block.ExtraBlockFile;
import mod.hey.studios.editor.manage.block.ExtraBlockInfo;
import mod.hey.studios.editor.manage.block.v2.BlockLoader;
import mod.hey.studios.project.ProjectSettings;
import mod.hey.studios.util.Helper;
import mod.hilal.saif.blocks.BlocksHandler;
import mod.hilal.saif.blocks.CommandBlock;
import mod.hilal.saif.components.ComponentsHandler;
import mod.hilal.saif.events.EventsHandler;
import pro.sketchware.SketchApplication;
import pro.sketchware.utility.FileUtil;

/**
 * Phase 6 backend for Sketchware Pro's extensibility system:
 * - User Custom Blocks (.sketchware/resources/block/My Block/block.json and
 *   per-project .sketchware/data/&lt;scId&gt;/custom_blocks)
 * - Custom Components (.sketchware/data/system/component.json via
 *   ComponentsHandler)
 * - Custom Events and Listeners (.sketchware/data/system/events.json and
 *   listeners.json via EventsHandler)
 * - XML Command Manager (.sketchware/data/&lt;scId&gt;/command +
 *   ProjectSettings.SETTING_NEW_XML_COMMAND)
 */
public final class AgentExtensibility {

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final List<String> VALID_BLOCK_TYPES = Arrays.asList(
            " ", "s", "d", "b", "a", "v", "l", "c", "e", "f", "h");
    private static final List<String> VALID_COMMANDS = Arrays.asList(
            "insert", "add", "replace",
            "find-replace", "find-replace-first", "find-replace-all");

    private AgentExtensibility() {
    }

    public static final class CustomBlockDef {
        public final String name;
        public final String type;
        public final String typeName;
        public final String spec;
        public final String spec2;
        public final String code;
        public final String imports;
        public final String color;
        public final String palette;
        public final String source;

        CustomBlockDef(String name, String type, String typeName,
                       String spec, String spec2, String code,
                       String imports, String color, String palette,
                       String source) {
            this.name = name == null ? "" : name;
            this.type = type == null || type.isEmpty() ? " " : type;
            this.typeName = typeName == null ? "" : typeName;
            this.spec = spec == null ? "" : spec;
            this.spec2 = spec2 == null ? "" : spec2;
            this.code = code == null ? "" : code;
            this.imports = imports == null ? "" : imports;
            this.color = color == null ? "" : color;
            this.palette = palette == null ? "" : palette;
            this.source = source == null ? "" : source;
        }
    }

    public static final class CustomComponentDef {
        public final int id;
        public final String name;
        public final String typeName;
        public final String varName;
        public final String className;
        public final String buildClass;
        public final String description;
        public final String imports;

        CustomComponentDef(int id, String name, String typeName,
                           String varName, String className,
                           String buildClass, String description,
                           String imports) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.typeName = typeName == null ? "" : typeName;
            this.varName = varName == null ? "" : varName;
            this.className = className == null ? "" : className;
            this.buildClass = buildClass == null ? "" : buildClass;
            this.description = description == null ? "" : description;
            this.imports = imports == null ? "" : imports;
        }
    }

    // ------------------------------------------------------------------
    // 1. User Custom Blocks & Project-Pinned Custom Blocks
    // ------------------------------------------------------------------

    public static File projectCustomBlocksFile(String scId) {
        return new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/custom_blocks");
    }

    private static ArrayList<HashMap<String, Object>> readUserBlockMaps() {
        try {
            String raw = ExtraBlockFile.getExtraBlockFile();
            ArrayList<HashMap<String, Object>> list =
                    new Gson().fromJson(raw, Helper.TYPE_MAP_LIST);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static ArrayList<ExtraBlockInfo> readProjectCustomBlocks(
            String scId) {
        if (scId == null || scId.isEmpty()) {
            return new ArrayList<>();
        }
        File f = projectCustomBlocksFile(scId);
        if (!f.exists() || !f.isFile()) {
            return new ArrayList<>();
        }
        try {
            ArrayList<ExtraBlockInfo> list = new Gson().fromJson(
                    FileUtil.readFile(f.getAbsolutePath()),
                    new TypeToken<ArrayList<ExtraBlockInfo>>() {
                    }.getType());
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String mapStr(Map<String, Object> m, String key) {
        if (m == null) {
            return "";
        }
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static CustomBlockDef fromMap(
            Map<String, Object> m, String source) {
        String type = mapStr(m, "type");
        if (type.isEmpty()) {
            type = " ";
        }
        return new CustomBlockDef(
                mapStr(m, "name"),
                type,
                mapStr(m, "typeName"),
                mapStr(m, "spec"),
                mapStr(m, "spec2"),
                mapStr(m, "code"),
                mapStr(m, "imports"),
                mapStr(m, "color"),
                mapStr(m, "palette"),
                source);
    }

    public static CustomBlockDef findCustomBlock(
            String scId, String opCode) {
        if (opCode == null || opCode.trim().isEmpty()) {
            return null;
        }
        String clean = opCode.trim();
        for (HashMap<String, Object> m : readUserBlockMaps()) {
            if (clean.equals(mapStr(m, "name"))) {
                return fromMap(m, "user_blocks");
            }
        }
        for (ExtraBlockInfo info : readProjectCustomBlocks(scId)) {
            if (info != null && clean.equals(info.getName())) {
                return new CustomBlockDef(
                        info.getName(),
                        " ",
                        "",
                        info.getSpec(),
                        info.getSpec2(),
                        info.getCode(),
                        "",
                        info.getColor() != 0
                                ? String.format(Locale.US, "#%06X",
                                        0xFFFFFF & info.getColor())
                                : "",
                        "",
                        "project_custom_blocks");
            }
        }
        ArrayList<HashMap<String, Object>> builtIn = new ArrayList<>();
        BlocksHandler.builtInBlocks(builtIn);
        for (HashMap<String, Object> m : builtIn) {
            if (clean.equals(mapStr(m, "name"))) {
                return fromMap(m, "pro_palette");
            }
        }
        return null;
    }

    /**
     * The blocks defined in THIS project (full spec/code). The IDE's
     * global stock More-Block table (~3 000 rows shipped with the app,
     * identical on every install) is intentionally NOT here — it made
     * every export ~2 MB of stock noise; it is summarized separately
     * by {@link #countUserBlockTable()} and
     * {@link #listUserBlockNamesJson(int)}.
     */
    public static JsonArray listProjectCustomBlocksJson(String scId) {
        JsonArray out = new JsonArray();
        LinkedHashMap<String, CustomBlockDef> merged =
                new LinkedHashMap<>();
        for (ExtraBlockInfo info : readProjectCustomBlocks(scId)) {
            if (info != null && info.getName() != null
                    && !info.getName().isEmpty()) {
                merged.put(info.getName(), new CustomBlockDef(
                        info.getName(), " ", "",
                        info.getSpec(), info.getSpec2(), info.getCode(),
                        "", "", "", "project_custom_blocks"));
            }
        }
        for (CustomBlockDef def : merged.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", def.name);
            o.addProperty("type", def.type);
            if (!def.typeName.isEmpty()) {
                o.addProperty("typeName", def.typeName);
            }
            o.addProperty("spec", def.spec);
            if (!def.spec2.isEmpty()) {
                o.addProperty("spec2", def.spec2);
            }
            o.addProperty("code", def.code);
            if (!def.imports.isEmpty()) {
                o.addProperty("imports", def.imports);
            }
            if (!def.color.isEmpty()) {
                o.addProperty("color", def.color);
            }
            if (!def.palette.isEmpty()) {
                o.addProperty("palette", def.palette);
            }
            o.addProperty("source", def.source);
            out.add(o);
        }
        return out;
    }

    /** How many rows the IDE's global More-Block table holds. */
    public static int countUserBlockTable() {
        return readUserBlockMaps().size();
    }

    /**
     * The global table's block names only (first {@code cap}), for
     * exports and tool results that must stay small; full specs stay
     * available through {@link #findCustomBlock(String, String)}.
     */
    public static JsonArray listUserBlockNamesJson(int cap) {
        JsonArray out = new JsonArray();
        int added = 0;
        for (HashMap<String, Object> m : readUserBlockMaps()) {
            String name = mapStr(m, "name");
            if (name.isEmpty()) {
                continue;
            }
            if (cap > 0 && added >= cap) {
                break;
            }
            out.add(name);
            added++;
        }
        return out;
    }

    private static void ensureDefaultCustomPaletteExists() {
        File paletteFile = ExtraBlockFile.EXTRA_BLOCKS_PALETTE_FILE;
        if (paletteFile.exists() && paletteFile.length() > 2) {
            return;
        }
        File parent = paletteFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        JsonArray arr = new JsonArray();
        JsonObject first = new JsonObject();
        first.addProperty("name", "Custom Blocks");
        first.addProperty("color", "#FF8A55D7");
        arr.add(first);
        FileUtil.writeFile(paletteFile.getAbsolutePath(),
                new GsonBuilder().setPrettyPrinting().create().toJson(arr));
    }

    public static void validateCustomBlock(
            String name, String type, String spec, String code,
            String color) throws AgentTools.ToolException {
        if (name == null || !IDENTIFIER_PATTERN.matcher(name.trim()).matches()) {
            throw new AgentTools.ToolException("E_BAD_BLOCK_NAME",
                    "Custom block name \"" + name + "\" must be a valid "
                            + "identifier (letters, digits, underscore).");
        }
        String normType = (type == null || type.isEmpty()) ? " " : type;
        if (!VALID_BLOCK_TYPES.contains(normType)) {
            throw new AgentTools.ToolException("E_BAD_BLOCK_TYPE",
                    "Unsupported custom block type \"" + normType
                            + "\". Use \" \" (statement), \"s\" (String), "
                            + "\"d\" (number), \"b\" (boolean), \"a\" (Map), "
                            + "\"v\" (View), \"c\" (if-style container), "
                            + "\"e\" (if/else container), or \"f\" "
                            + "(terminator).");
        }
        if (spec == null || spec.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_BLOCK_SPEC",
                    "Custom block \"spec\" cannot be empty (e.g. "
                            + "\"log tag %s message %s\").");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_BLOCK_CODE",
                    "Custom block \"code\" cannot be empty (Java template "
                            + "using %1$s / %s placeholders matching spec).");
        }
        if (color != null && !color.trim().isEmpty()) {
            try {
                android.graphics.Color.parseColor(color.trim());
            } catch (IllegalArgumentException e) {
                throw new AgentTools.ToolException("E_BAD_BLOCK_COLOR",
                        "Invalid hex color \"" + color
                                + "\" (use #RRGGBB or #AARRGGBB).");
            }
        }
    }

    public static CustomBlockDef upsertCustomBlock(
            String scId, String name, String type, String typeName,
            String spec, String spec2, String code, String imports,
            String color, String palette, boolean pinToProject)
            throws AgentTools.ToolException, IOException {
        validateCustomBlock(name, type, spec, code, color);
        String cleanName = name.trim();
        String normType = (type == null || type.isEmpty()) ? " " : type;
        String cleanColor = (color == null || color.trim().isEmpty())
                ? "#8A55D7" : color.trim();
        String cleanPalette = (palette == null || palette.trim().isEmpty())
                ? "9" : palette.trim();

        ensureDefaultCustomPaletteExists();
        File dataFile = ExtraBlockFile.EXTRA_BLOCKS_DATA_FILE;
        File parent = dataFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        ArrayList<HashMap<String, Object>> blocks = readUserBlockMaps();
        HashMap<String, Object> entry = new HashMap<>();
        entry.put("name", cleanName);
        entry.put("type", normType);
        entry.put("typeName", typeName == null ? "" : typeName.trim());
        entry.put("spec", spec.trim());
        if (spec2 != null && !spec2.trim().isEmpty()) {
            entry.put("spec2", spec2.trim());
        }
        entry.put("code", code);
        if (imports != null && !imports.trim().isEmpty()) {
            entry.put("imports", imports.trim());
        }
        entry.put("color", cleanColor);
        entry.put("palette", cleanPalette);

        int existingIdx = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (cleanName.equals(mapStr(blocks.get(i), "name"))) {
                existingIdx = i;
                break;
            }
        }
        if (existingIdx >= 0) {
            blocks.set(existingIdx, entry);
        } else {
            blocks.add(entry);
        }
        FileUtil.writeFile(dataFile.getAbsolutePath(),
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(blocks));
        BlockLoader.refresh();

        CustomBlockDef saved = fromMap(entry, "user_blocks");
        if (pinToProject && scId != null && !scId.isEmpty()) {
            SketchwareActions.backupProjectOnce(scId);
            pinSingleBlockToProject(scId, saved);
        }
        return saved;
    }

    public static void pinSingleBlockToProject(
            String scId, CustomBlockDef def) {
        if (scId == null || scId.isEmpty() || def == null) {
            return;
        }
        ArrayList<ExtraBlockInfo> pinned = readProjectCustomBlocks(scId);
        ExtraBlockInfo info = new ExtraBlockInfo();
        info.setName(def.name);
        info.setSpec(def.spec);
        info.setSpec2(def.spec2);
        info.setCode(def.code);
        if (!def.color.isEmpty()) {
            try {
                info.setColor(android.graphics.Color.parseColor(def.color));
            } catch (Exception ignored) {
            }
        }
        boolean replaced = false;
        for (int i = 0; i < pinned.size(); i++) {
            if (pinned.get(i) != null
                    && def.name.equals(pinned.get(i).getName())) {
                pinned.set(i, info);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            pinned.add(info);
        }
        File f = projectCustomBlocksFile(scId);
        if (f.getParentFile() != null && !f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }
        FileUtil.writeFile(f.getAbsolutePath(),
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(pinned));
    }

    public static void deleteCustomBlock(String scId, String name)
            throws AgentTools.ToolException, IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_BLOCK_NAME",
                    "Specify the custom block \"name\" to delete.");
        }
        String clean = name.trim();
        ArrayList<HashMap<String, Object>> blocks = readUserBlockMaps();
        boolean removedUser = blocks.removeIf(
                m -> clean.equals(mapStr(m, "name")));
        if (removedUser) {
            FileUtil.writeFile(
                    ExtraBlockFile.EXTRA_BLOCKS_DATA_FILE.getAbsolutePath(),
                    new GsonBuilder().setPrettyPrinting().create()
                            .toJson(blocks));
            BlockLoader.refresh();
        }
        boolean removedProj = false;
        if (scId != null && !scId.isEmpty()) {
            ArrayList<ExtraBlockInfo> pinned = readProjectCustomBlocks(scId);
            removedProj = pinned.removeIf(
                    b -> b != null && clean.equals(b.getName()));
            if (removedProj) {
                SketchwareActions.backupProjectOnce(scId);
                FileUtil.writeFile(
                        projectCustomBlocksFile(scId).getAbsolutePath(),
                        new GsonBuilder().setPrettyPrinting().create()
                                .toJson(pinned));
            }
        }
        if (!removedUser && !removedProj) {
            throw new AgentTools.ToolException("E_BLOCK_NOT_FOUND",
                    "Custom block \"" + clean + "\" does not exist.");
        }
    }

    // ------------------------------------------------------------------
    // 2. Custom Components (ComponentsHandler -> system/component.json)
    // ------------------------------------------------------------------

    private static ArrayList<HashMap<String, Object>> readCustomComponentMaps() {
        String path = ComponentsHandler.getPath();
        if (!FileUtil.isExistFile(path)) {
            return new ArrayList<>();
        }
        try {
            ArrayList<HashMap<String, Object>> list =
                    new Gson().fromJson(FileUtil.readFile(path),
                            Helper.TYPE_MAP_LIST);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static List<CustomComponentDef> listCustomComponents() {
        ArrayList<CustomComponentDef> out = new ArrayList<>();
        for (HashMap<String, Object> m : readCustomComponentMaps()) {
            if (m == null) continue;
            int id;
            try {
                id = Integer.parseInt(mapStr(m, "id").trim());
            } catch (NumberFormatException e) {
                continue;
            }
            out.add(new CustomComponentDef(
                    id,
                    mapStr(m, "name"),
                    mapStr(m, "typeName"),
                    mapStr(m, "varName"),
                    mapStr(m, "class"),
                    mapStr(m, "buildClass"),
                    mapStr(m, "description"),
                    mapStr(m, "imports")));
        }
        return out;
    }

    public static JsonArray listCustomComponentsJson() {
        JsonArray out = new JsonArray();
        for (CustomComponentDef c : listCustomComponents()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id);
            o.addProperty("name", c.name);
            o.addProperty("typeName", c.typeName);
            o.addProperty("varName", c.varName);
            o.addProperty("class", c.className);
            o.addProperty("buildClass", c.buildClass);
            o.addProperty("description", c.description);
            if (!c.imports.isEmpty()) {
                o.addProperty("imports", c.imports);
            }
            out.add(o);
        }
        return out;
    }

    public static CustomComponentDef findCustomComponent(String query) {
        if (query == null || query.trim().isEmpty()) {
            return null;
        }
        String q = query.trim();
        for (CustomComponentDef c : listCustomComponents()) {
            if (q.equalsIgnoreCase(c.typeName)
                    || q.equalsIgnoreCase(c.name)
                    || q.equals(String.valueOf(c.id))) {
                return c;
            }
        }
        return null;
    }

    public static CustomComponentDef upsertCustomComponent(
            String name, String typeName, int requestedId,
            String varName, String buildClass, String classInfoName,
            String description, String imports, String additionalVar,
            String defineAdditionalVar) throws AgentTools.ToolException {
        if (typeName == null
                || !IDENTIFIER_PATTERN.matcher(typeName.trim()).matches()) {
            throw new AgentTools.ToolException("E_BAD_COMPONENT_TYPE",
                    "Custom component \"typeName\" must be a valid "
                            + "identifier (e.g. \"ExoPlayerComponent\").");
        }
        String cleanType = typeName.trim();
        String cleanName = (name == null || name.trim().isEmpty())
                ? cleanType : name.trim();
        String cleanBuildClass = (buildClass == null
                || buildClass.trim().isEmpty())
                ? cleanType : buildClass.trim();
        String cleanVar = (varName == null || varName.trim().isEmpty())
                ? cleanType.toLowerCase(Locale.ROOT) : varName.trim();

        ArrayList<HashMap<String, Object>> list = readCustomComponentMaps();
        int existingIdx = -1;
        int maxId = 36;
        for (int i = 0; i < list.size(); i++) {
            HashMap<String, Object> m = list.get(i);
            if (m == null) continue;
            try {
                int mid = Integer.parseInt(mapStr(m, "id").trim());
                if (mid > maxId) maxId = mid;
            } catch (NumberFormatException ignored) {
            }
            if (cleanType.equalsIgnoreCase(mapStr(m, "typeName"))) {
                existingIdx = i;
            }
        }

        int finalId;
        if (requestedId >= 37) {
            finalId = requestedId;
        } else if (existingIdx >= 0) {
            try {
                finalId = Integer.parseInt(
                        mapStr(list.get(existingIdx), "id").trim());
            } catch (NumberFormatException e) {
                finalId = maxId + 1;
            }
        } else {
            finalId = maxId + 1;
        }

        HashMap<String, Object> entry = new HashMap<>();
        entry.put("id", String.valueOf(finalId));
        entry.put("name", cleanName);
        entry.put("typeName", cleanType);
        entry.put("icon", "2131165279");
        entry.put("varName", cleanVar);
        entry.put("buildClass", cleanBuildClass);
        entry.put("class", (classInfoName == null
                || classInfoName.trim().isEmpty())
                ? cleanType : classInfoName.trim());
        entry.put("description", description == null
                ? "" : description.trim());
        entry.put("url", "");
        entry.put("additionalVar", additionalVar == null
                ? "" : additionalVar);
        entry.put("defineAdditionalVar", defineAdditionalVar == null
                ? "" : defineAdditionalVar);
        entry.put("imports", imports == null ? "" : imports.trim());

        if (existingIdx >= 0) {
            list.set(existingIdx, entry);
        } else {
            list.add(entry);
        }
        File target = new File(ComponentsHandler.getPath());
        if (target.getParentFile() != null
                && !target.getParentFile().exists()) {
            target.getParentFile().mkdirs();
        }
        FileUtil.writeFile(target.getAbsolutePath(),
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(list));
        ComponentsHandler.refreshCachedCustomComponents();
        return findCustomComponent(cleanType);
    }

    public static void deleteCustomComponent(String typeOrName)
            throws AgentTools.ToolException {
        if (typeOrName == null || typeOrName.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_COMPONENT_TYPE",
                    "Specify custom component \"typeName\" or \"name\" to "
                            + "delete.");
        }
        String q = typeOrName.trim();
        ArrayList<HashMap<String, Object>> list = readCustomComponentMaps();
        boolean removed = list.removeIf(m -> m != null
                && (q.equalsIgnoreCase(mapStr(m, "typeName"))
                        || q.equalsIgnoreCase(mapStr(m, "name"))
                        || q.equals(mapStr(m, "id"))));
        if (!removed) {
            throw new AgentTools.ToolException("E_COMPONENT_NOT_FOUND",
                    "Custom component \"" + q + "\" does not exist.");
        }
        FileUtil.writeFile(ComponentsHandler.getPath(),
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(list));
        ComponentsHandler.refreshCachedCustomComponents();
    }

    // ------------------------------------------------------------------
    // 3. Custom Events & Listeners (EventsHandler -> events.json/listeners.json)
    // ------------------------------------------------------------------

    private static ArrayList<HashMap<String, Object>> readJsonMapList(
            String path) {
        if (!FileUtil.isExistFile(path)) {
            return new ArrayList<>();
        }
        try {
            ArrayList<HashMap<String, Object>> list =
                    new Gson().fromJson(FileUtil.readFile(path),
                            Helper.TYPE_MAP_LIST);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static JsonObject listCustomEventsAndListenersJson() {
        JsonObject out = new JsonObject();
        JsonArray evArr = new JsonArray();
        for (HashMap<String, Object> m
                : readJsonMapList(EventsHandler.CUSTOM_EVENTS_FILE_PATH)) {
            if (m == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", mapStr(m, "name"));
            o.addProperty("target_class", mapStr(m, "var"));
            o.addProperty("listener", mapStr(m, "listener"));
            o.addProperty("parameters", mapStr(m, "parameters"));
            o.addProperty("headerSpec", mapStr(m, "headerSpec"));
            o.addProperty("description", mapStr(m, "description"));
            o.addProperty("code", mapStr(m, "code"));
            evArr.add(o);
        }
        JsonArray liArr = new JsonArray();
        for (HashMap<String, Object> m
                : readJsonMapList(EventsHandler.CUSTOM_LISTENERE_FILE_PATH)) {
            if (m == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", mapStr(m, "name"));
            o.addProperty("code", mapStr(m, "code"));
            o.addProperty("imports", mapStr(m, "imports"));
            liArr.add(o);
        }
        out.add("events", evArr);
        out.add("listeners", liArr);
        return out;
    }

    public static void upsertCustomEvent(
            String eventName, String targetClassVar, String listenerName,
            String parameters, String headerSpec, String description,
            String eventCode, String listenerCode, String listenerImports)
            throws AgentTools.ToolException {
        if (eventName == null
                || !IDENTIFIER_PATTERN.matcher(eventName.trim()).matches()) {
            throw new AgentTools.ToolException("E_BAD_EVENT_NAME",
                    "Custom event \"name\" must be a valid Java identifier "
                            + "(e.g. \"onKeyDown\" or \"onPlaybackReady\").");
        }
        if (eventCode == null || !eventCode.contains("%s")) {
            throw new AgentTools.ToolException("E_BAD_EVENT_CODE",
                    "Custom event \"code\" must contain \"%s\" where the "
                            + "wired blocks will be emitted.");
        }
        String cleanEvent = eventName.trim();
        String cleanVar = targetClassVar == null
                ? "" : targetClassVar.trim();
        String cleanListener = listenerName == null
                ? "" : listenerName.trim();
        if (!cleanVar.isEmpty() && cleanListener.isEmpty()) {
            cleanListener = cleanEvent + "Listener";
        }

        ArrayList<HashMap<String, Object>> events =
                readJsonMapList(EventsHandler.CUSTOM_EVENTS_FILE_PATH);
        HashMap<String, Object> ev = new HashMap<>();
        ev.put("name", cleanEvent);
        ev.put("var", cleanVar);
        ev.put("listener", cleanListener);
        ev.put("icon", "2131165279");
        ev.put("description", (description == null
                || description.trim().isEmpty())
                ? cleanEvent : description.trim());
        ev.put("parameters", parameters == null ? "" : parameters.trim());
        ev.put("headerSpec", (headerSpec == null
                || headerSpec.trim().isEmpty())
                ? ("when ### " + cleanEvent
                        + (parameters == null || parameters.trim().isEmpty()
                                ? "" : " " + parameters.trim()))
                : headerSpec.trim());
        ev.put("code", eventCode);

        int existingEv = -1;
        for (int i = 0; i < events.size(); i++) {
            if (cleanEvent.equals(mapStr(events.get(i), "name"))) {
                existingEv = i;
                break;
            }
        }
        if (existingEv >= 0) {
            events.set(existingEv, ev);
        } else {
            events.add(ev);
        }
        File evFile = new File(EventsHandler.CUSTOM_EVENTS_FILE_PATH);
        if (evFile.getParentFile() != null
                && !evFile.getParentFile().exists()) {
            evFile.getParentFile().mkdirs();
        }
        FileUtil.writeFile(evFile.getAbsolutePath(),
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(events));

        if (!cleanListener.isEmpty() && listenerCode != null
                && !listenerCode.trim().isEmpty()) {
            if (!listenerCode.contains("%s")) {
                throw new AgentTools.ToolException("E_BAD_LISTENER_CODE",
                        "Custom listener \"listener_code\" must contain "
                                + "\"%s\" where event methods are emitted.");
            }
            ArrayList<HashMap<String, Object>> listeners = readJsonMapList(
                    EventsHandler.CUSTOM_LISTENERE_FILE_PATH);
            HashMap<String, Object> li = new HashMap<>();
            li.put("name", cleanListener);
            li.put("code", listenerCode);
            li.put("imports", listenerImports == null
                    ? "" : listenerImports.trim());
            li.put("s", "true");
            int existingLi = -1;
            for (int i = 0; i < listeners.size(); i++) {
                if (cleanListener.equals(mapStr(listeners.get(i), "name"))) {
                    existingLi = i;
                    break;
                }
            }
            if (existingLi >= 0) {
                listeners.set(existingLi, li);
            } else {
                listeners.add(li);
            }
            File liFile = new File(EventsHandler.CUSTOM_LISTENERE_FILE_PATH);
            if (liFile.getParentFile() != null
                    && !liFile.getParentFile().exists()) {
                liFile.getParentFile().mkdirs();
            }
            FileUtil.writeFile(liFile.getAbsolutePath(),
                    new GsonBuilder().setPrettyPrinting().create()
                            .toJson(listeners));
        }

        EventsHandler.refreshCachedCustomEvents();
        EventsHandler.refreshCachedCustomListeners();
    }

    public static void deleteCustomEvent(String eventName)
            throws AgentTools.ToolException {
        if (eventName == null || eventName.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_EVENT_NAME",
                    "Specify custom event \"name\" to delete.");
        }
        String clean = eventName.trim();
        ArrayList<HashMap<String, Object>> events =
                readJsonMapList(EventsHandler.CUSTOM_EVENTS_FILE_PATH);
        boolean removed = events.removeIf(
                m -> m != null && clean.equals(mapStr(m, "name")));
        if (!removed) {
            throw new AgentTools.ToolException("E_EVENT_NOT_FOUND",
                    "Custom event \"" + clean + "\" does not exist.");
        }
        FileUtil.writeFile(EventsHandler.CUSTOM_EVENTS_FILE_PATH,
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(events));
        EventsHandler.refreshCachedCustomEvents();
        EventsHandler.refreshCachedCustomListeners();
    }

    // ------------------------------------------------------------------
    // 4. XML Command Manager (.sketchware/data/<scId>/command)
    // ------------------------------------------------------------------

    public static String xmlCommandPath(String scId) {
        return wq.b(scId) + "/command";
    }

    public static List<String> availableXmlCommandTargets(String scId) {
        ArrayList<String> targets = new ArrayList<>();
        try {
            hC projectFile = jC.b(scId);
            if (projectFile != null && projectFile.e() != null) {
                targets.addAll(projectFile.e());
            }
        } catch (Exception ignored) {
        }
        for (String std : new String[]{
                "AndroidManifest.xml", "strings.xml", "colors.xml",
                "styles.xml", "xml/provider_paths.xml"}) {
            if (!targets.contains(std)) {
                targets.add(std);
            }
        }
        return targets;
    }

    private static void ensureXmlCommandInitialized(String scId) {
        ProjectSettings settings = new ProjectSettings(scId);
        boolean enabled = Boolean.parseBoolean(settings.getValue(
                ProjectSettings.SETTING_NEW_XML_COMMAND,
                ProjectSettings.SETTING_GENERIC_VALUE_FALSE));
        String cmdPath = xmlCommandPath(scId);
        if (!enabled || !FileUtil.isExistFile(cmdPath)) {
            if (!FileUtil.isExistFile(cmdPath)) {
                try {
                    android.content.Context ctx =
                            SketchApplication.getContext();
                    yq gen = new yq(ctx, scId);
                    iC libMgr = jC.c(scId);
                    hC fileMgr = jC.b(scId);
                    eC dataMgr = jC.a(scId);
                    gen.a(libMgr, fileMgr, dataMgr);
                    CommandBlock.x();
                    ArrayList<ProjectFileBean> files =
                            new ArrayList<>(fileMgr.b());
                    files.addAll(new ArrayList<>(fileMgr.c()));
                    for (ProjectFileBean file : files) {
                        CommandBlock.CBForXml(
                                new Jx(gen.N, file, dataMgr)
                                        .generateCode(false, scId));
                    }
                    String tempPath = FileUtil.getExternalStorageDir()
                            .concat("/.sketchware/temp/commands");
                    if (FileUtil.isExistFile(tempPath)) {
                        FileUtil.copyFile(tempPath, cmdPath);
                        CommandBlock.x();
                    } else {
                        FileUtil.writeFile(cmdPath, "[]");
                    }
                } catch (Exception e) {
                    FileUtil.writeFile(cmdPath, "[]");
                }
            }
            settings.setValue(
                    ProjectSettings.SETTING_NEW_XML_COMMAND,
                    ProjectSettings.SETTING_GENERIC_VALUE_TRUE);
        }
    }

    private static ArrayList<HashMap<String, Object>> readXmlCommandList(
            String scId) {
        String path = xmlCommandPath(scId);
        if (!FileUtil.isExistFile(path)) {
            return new ArrayList<>();
        }
        try {
            ArrayList<HashMap<String, Object>> list =
                    new Gson().fromJson(FileUtil.readFile(path),
                            Helper.TYPE_MAP_LIST);
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static int mapInt(Map<String, Object> m, String key) {
        if (m == null || !m.containsKey(key) || m.get(key) == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(m.get(key)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static JsonObject listXmlCommandsJson(String scId) {
        JsonObject out = new JsonObject();
        ProjectSettings settings = new ProjectSettings(scId);
        boolean enabled = Boolean.parseBoolean(settings.getValue(
                ProjectSettings.SETTING_NEW_XML_COMMAND,
                ProjectSettings.SETTING_GENERIC_VALUE_FALSE));
        out.addProperty("enabled", enabled);
        JsonArray arr = new JsonArray();
        ArrayList<HashMap<String, Object>> raw = readXmlCommandList(scId);
        for (int i = 0; i < raw.size(); i++) {
            HashMap<String, Object> m = raw.get(i);
            if (m == null) continue;
            String input = mapStr(m, "input");
            JsonObject item = new JsonObject();
            item.addProperty("index", i);
            item.addProperty("xml_name",
                    CommandBlock.getInputName(input));
            item.addProperty("command", mapStr(m, "command"));
            item.addProperty("reference", mapStr(m, "reference"));
            item.addProperty("distance", mapInt(m, "distance"));
            item.addProperty("after", mapInt(m, "after"));
            item.addProperty("before", mapInt(m, "before"));
            item.addProperty("changes",
                    CommandBlock.getExceptFirstLine(input));
            arr.add(item);
        }
        out.add("commands", arr);
        out.addProperty("count", arr.size());
        JsonArray targets = new JsonArray();
        for (String t : availableXmlCommandTargets(scId)) {
            targets.add(t);
        }
        out.add("available_xml_targets", targets);
        return out;
    }

    public static String normalizeCommandAction(String raw)
            throws AgentTools.ToolException {
        String c = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!VALID_COMMANDS.contains(c)) {
            throw new AgentTools.ToolException("E_BAD_COMMAND_ACTION",
                    "Invalid command action \"" + raw + "\". Use one of: "
                            + String.join(", ", VALID_COMMANDS) + ".");
        }
        return c;
    }

    public static int upsertXmlCommand(
            String scId, int index, String xmlName, String command,
            String reference, String changes, int distance, int before,
            int after) throws AgentTools.ToolException, IOException {
        if (xmlName == null || xmlName.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_XML_TARGET",
                    "Pass \"xml_name\" (e.g. AndroidManifest.xml, main.xml, "
                            + "styles.xml, colors.xml, strings.xml).");
        }
        if (reference == null || reference.isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_COMMAND_REFERENCE",
                    "Pass non-empty \"reference\" text or line anchor to "
                            + "match in " + xmlName + ".");
        }
        String normCmd = normalizeCommandAction(command);
        String cleanXml = xmlName.trim();
        if (!cleanXml.endsWith(".xml")) {
            cleanXml = cleanXml + ".xml";
        }

        SketchwareActions.backupProjectOnce(scId);
        ensureXmlCommandInitialized(scId);
        ArrayList<HashMap<String, Object>> commands =
                readXmlCommandList(scId);

        HashMap<String, Object> map = new HashMap<>();
        map.put("reference", reference);
        map.put("distance", distance);
        map.put("after", after);
        map.put("before", before);
        map.put("command", normCmd);
        map.put("input", ">" + cleanXml + "\n"
                + (changes == null ? "" : changes));

        int savedIndex;
        if (index >= 0 && index < commands.size()) {
            commands.set(index, map);
            savedIndex = index;
        } else {
            commands.add(map);
            savedIndex = commands.size() - 1;
        }
        FileUtil.writeFile(xmlCommandPath(scId),
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(commands));
        return savedIndex;
    }

    public static void deleteXmlCommand(String scId, int index)
            throws AgentTools.ToolException, IOException {
        ArrayList<HashMap<String, Object>> commands =
                readXmlCommandList(scId);
        if (index < 0 || index >= commands.size()) {
            throw new AgentTools.ToolException("E_COMMAND_INDEX_OUT_OF_RANGE",
                    "XML command index " + index + " is out of range (0.."
                            + (commands.size() - 1) + ").");
        }
        SketchwareActions.backupProjectOnce(scId);
        commands.remove(index);
        FileUtil.writeFile(xmlCommandPath(scId),
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(commands));
    }

    public static void clearXmlCommands(String scId) throws IOException {
        SketchwareActions.backupProjectOnce(scId);
        ensureXmlCommandInitialized(scId);
        FileUtil.writeFile(xmlCommandPath(scId), "[]");
    }
}
