package pro.sketchware.ai;

import android.util.Base64;

import com.besome.sketch.beans.ProjectFileBean;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileResConfig;
import pro.sketchware.utility.FileUtil;

/**
 * Phase 5 backend for Sketchware Pro's Java &amp; Kotlin Manager
 * ({@code ManageJavaActivity}, {@code KotlinCompilerBridge}, and manifest
 * registrations in {@code Ix}), Assets Manager ({@code ManageAssetsActivity}),
 * and Native Libraries Manager ({@code ManageNativelibsActivity}).
 */
public final class AgentSources {

    private static final Gson GSON = new Gson();
    private static final Pattern PACKAGE_DECL_PATTERN =
            Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9_.]*)\\s*;?");
    private static final Pattern JAVA_SEG_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final Pattern CLASS_FILE_PATTERN =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*\\.(java|kt)$");
    private static final Pattern ASSET_SEG_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_][a-zA-Z0-9_.-]*$");
    private static final Pattern SO_FILE_PATTERN =
            Pattern.compile("^lib[a-zA-Z0-9_.-]+\\.so$");

    public static final List<String> SUPPORTED_ABIS = List.of(
            "arm64-v8a",
            "armeabi-v7a",
            "x86_64",
            "x86",
            "armeabi");

    private static final Set<String> TEXT_ASSET_EXTENSIONS = Set.of(
            "txt", "json", "html", "htm", "css", "js", "mjs", "xml",
            "svg", "csv", "tsv", "md", "yaml", "yml", "properties",
            "ini", "cfg", "sql", "glsl", "vert", "frag", "shader");

    private AgentSources() {
    }

    // =================================================================
    // 1. Java & Kotlin Source Files + Manifest Component Registration
    // =================================================================

    public static final class SourceFileInfo {
        public final String relativePath;
        public final String fileName;
        public final String className;
        public final String packageName;
        public final String fqcn;
        public final String language;
        public final String manifestRole;
        public final long bytes;

        public SourceFileInfo(String relativePath, String fileName,
                              String className, String packageName,
                              String fqcn, String language,
                              String manifestRole, long bytes) {
            this.relativePath = relativePath;
            this.fileName = fileName;
            this.className = className;
            this.packageName = packageName;
            this.fqcn = fqcn;
            this.language = language;
            this.manifestRole = manifestRole;
            this.bytes = bytes;
        }
    }

    public static String normalizeSourceRelPath(String rawPath)
            throws AgentTools.ToolException {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_PATH",
                    "Pass \"path\" ending in .java or .kt (e.g. "
                            + "\"NoteModel.java\", \"adapters/FeedAdapter.kt\").");
        }
        String cleaned = rawPath.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.startsWith("files/java/")) {
            cleaned = cleaned.substring("files/java/".length());
        } else if (cleaned.startsWith("java/")) {
            cleaned = cleaned.substring("java/".length());
        }
        if (cleaned.contains("..") || cleaned.contains("//")) {
            throw new AgentTools.ToolException("E_BAD_PATH",
                    "Invalid source path \"" + rawPath + "\".");
        }
        String[] parts = cleaned.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (!JAVA_SEG_PATTERN.matcher(parts[i]).matches()) {
                throw new AgentTools.ToolException("E_BAD_PATH",
                        "Invalid package directory segment \"" + parts[i]
                                + "\" in \"" + rawPath + "\".");
            }
        }
        String leaf = parts[parts.length - 1];
        if (!CLASS_FILE_PATTERN.matcher(leaf).matches()) {
            throw new AgentTools.ToolException("E_BAD_FILE_NAME",
                    "Source file \"" + leaf
                            + "\" must end in .java or .kt and use valid "
                            + "Java/Kotlin identifier characters.");
        }
        return String.join("/", parts);
    }

    public static String normalizeManifestRole(String raw)
            throws AgentTools.ToolException {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)
                .replace("-", "_")) {
            case "keep", "" -> "";
            case "none", "class", "plain" -> "none";
            case "activity" -> "activity";
            case "service" -> "service";
            case "receiver", "broadcast", "broadcast_receiver" -> "receiver";
            default -> throw new AgentTools.ToolException("E_BAD_MANIFEST_ROLE",
                    "Unknown register_in_manifest value \"" + raw
                            + "\". Use none, activity, service, or receiver.");
        };
    }

    public static String expectedPackageForRelPath(String scId,
                                                   String cleanRelPath) {
        SketchwareProjects.ProjectInfo info =
                SketchwareProjects.project(scId);
        String basePkg = info != null && info.packageName != null
                && !info.packageName.isEmpty()
                ? info.packageName : "com.my.newproject";
        int slash = cleanRelPath.lastIndexOf('/');
        if (slash < 0) {
            return basePkg;
        }
        String dirPart = cleanRelPath.substring(0, slash);
        String dottedDir = dirPart.replace('/', '.');
        if (dottedDir.equals(basePkg)
                || dottedDir.startsWith(basePkg + ".")) {
            return dottedDir;
        }
        return basePkg + "." + dottedDir;
    }

    public static String extractPackageName(String code,
                                            String fallbackPkg) {
        if (code != null) {
            Matcher m = PACKAGE_DECL_PATTERN.matcher(code);
            if (m.find()) {
                return m.group(1);
            }
        }
        return fallbackPkg;
    }

    public static String ensurePackageDeclaration(String code,
                                                  String expectedPkg,
                                                  boolean isKotlin) {
        String trimmed = code == null ? "" : code.trim();
        if (PACKAGE_DECL_PATTERN.matcher(trimmed).find()) {
            return trimmed + "\n";
        }
        String header = isKotlin
                ? ("package " + expectedPkg + "\n\n")
                : ("package " + expectedPkg + ";\n\n");
        return header + trimmed + "\n";
    }

    public static void checkNoScreenClassCollision(String scId,
                                                   String cleanRelPath,
                                                   String fqcn)
            throws AgentTools.ToolException {
        SketchwareProjects.ProjectInfo info =
                SketchwareProjects.project(scId);
        String basePkg = info != null && info.packageName != null
                ? info.packageName : "";
        String leaf = cleanRelPath.contains("/")
                ? cleanRelPath.substring(cleanRelPath.lastIndexOf('/') + 1)
                : cleanRelPath;
        String simpleName = leaf.substring(0, leaf.lastIndexOf('.'));
        for (ProjectFileBean act : SketchwareProjects.activities(scId)) {
            String actClass = SketchwareProjects.activityName(act);
            String actFqcn = basePkg.isEmpty()
                    ? actClass : (basePkg + "." + actClass);
            if (actFqcn.equals(fqcn)
                    || (!cleanRelPath.contains("/")
                            && actClass.equals(simpleName))) {
                throw new AgentTools.ToolException("E_CLASS_COLLISION",
                        "Class \"" + simpleName + "\" collides with the "
                                + "project's visual screen " + actClass
                                + " (" + act.fileName + "). Give the custom "
                                + "source class a distinct name or subpackage.");
            }
        }
    }

    public static List<SourceFileInfo> listSourceFiles(String scId) {
        List<SourceFileInfo> out = new ArrayList<>();
        File root = new File(new FilePathUtil().getPathJava(scId));
        if (!root.exists() || !root.isDirectory()) {
            return out;
        }
        FileResConfig frc = new FileResConfig(scId);
        Set<String> actSet = new LinkedHashSet<>(frc.getJavaManifestList());
        Set<String> svcSet = new LinkedHashSet<>(frc.getServiceManifestList());
        Set<String> rcvSet = new LinkedHashSet<>(
                frc.getBroadcastManifestList());
        List<File> files = new ArrayList<>();
        collectFilesRecursive(root, files);
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".java") && !name.endsWith(".kt")) {
                continue;
            }
            String rel = relativePathFrom(root, f);
            String fallbackPkg = expectedPackageForRelPath(scId, rel);
            String code = FileUtil.readFile(f.getAbsolutePath());
            String pkg = extractPackageName(code, fallbackPkg);
            String cls = name.substring(0, name.lastIndexOf('.'));
            String fqcn = pkg == null || pkg.isEmpty()
                    ? cls : (pkg + "." + cls);
            String role = actSet.contains(fqcn) ? "activity"
                    : (svcSet.contains(fqcn) ? "service"
                            : (rcvSet.contains(fqcn) ? "receiver" : "none"));
            out.add(new SourceFileInfo(
                    rel, name, cls, pkg, fqcn,
                    name.endsWith(".kt") ? "kotlin" : "java",
                    role, f.length()));
        }
        out.sort((a, b) -> a.relativePath.compareTo(b.relativePath));
        return out;
    }

    public static String readSourceFile(String scId, String rawRelPath)
            throws AgentTools.ToolException {
        String cleanRel = normalizeSourceRelPath(rawRelPath);
        File target = new File(new FilePathUtil().getPathJava(scId), cleanRel);
        if (!target.exists() || !target.isFile()) {
            throw new AgentTools.ToolException("E_SOURCE_NOT_FOUND",
                    "Source file files/java/" + cleanRel
                            + " does not exist.");
        }
        return FileUtil.readFile(target.getAbsolutePath());
    }

    public static SourceFileInfo writeSourceFile(
            String scId, String rawRelPath, String rawCode,
            String manifestRoleRaw) throws Exception {
        String cleanRel = normalizeSourceRelPath(rawRelPath);
        if (rawCode == null || rawCode.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_EMPTY_SOURCE",
                    "Source code for " + cleanRel + " cannot be empty.");
        }
        boolean isKt = cleanRel.endsWith(".kt");
        String expectedPkg = expectedPackageForRelPath(scId, cleanRel);
        String finalCode = ensurePackageDeclaration(
                rawCode, expectedPkg, isKt);
        String pkg = extractPackageName(finalCode, expectedPkg);
        String fileName = cleanRel.contains("/")
                ? cleanRel.substring(cleanRel.lastIndexOf('/') + 1) : cleanRel;
        String cls = fileName.substring(0, fileName.lastIndexOf('.'));
        String fqcn = pkg + "." + cls;
        checkNoScreenClassCollision(scId, cleanRel, fqcn);
        String requestedRole = normalizeManifestRole(manifestRoleRaw);

        SketchwareActions.backupProjectOnce(scId);
        FilePathUtil fpu = new FilePathUtil();
        File root = new File(fpu.getPathJava(scId));
        File target = new File(root, cleanRel);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory " + parent);
        }
        FileUtil.writeFile(target.getAbsolutePath(), finalCode);
        if (!target.exists() || target.length() == 0) {
            throw new IOException("Failed to write files/java/" + cleanRel);
        }

        String effectiveRole = updateManifestRole(scId, fqcn, requestedRole);
        return new SourceFileInfo(
                cleanRel, fileName, cls, pkg, fqcn,
                isKt ? "kotlin" : "java", effectiveRole, target.length());
    }

    public static String updateManifestRole(String scId, String fqcn,
                                            String requestedRole) {
        FilePathUtil fpu = new FilePathUtil();
        FileResConfig frc = new FileResConfig(scId);
        ArrayList<String> acts = new ArrayList<>(frc.getJavaManifestList());
        ArrayList<String> svcs = new ArrayList<>(frc.getServiceManifestList());
        ArrayList<String> rcvs = new ArrayList<>(
                frc.getBroadcastManifestList());

        if (requestedRole == null || requestedRole.isEmpty()) {
            if (acts.contains(fqcn)) return "activity";
            if (svcs.contains(fqcn)) return "service";
            if (rcvs.contains(fqcn)) return "receiver";
            return "none";
        }

        boolean changedActs = acts.remove(fqcn);
        boolean changedSvcs = svcs.remove(fqcn);
        boolean changedRcvs = rcvs.remove(fqcn);
        switch (requestedRole) {
            case "activity" -> {
                acts.add(fqcn);
                changedActs = true;
            }
            case "service" -> {
                svcs.add(fqcn);
                changedSvcs = true;
            }
            case "receiver" -> {
                rcvs.add(fqcn);
                changedRcvs = true;
            }
            default -> {
            }
        }
        if (changedActs) {
            FileUtil.writeFile(fpu.getManifestJava(scId), GSON.toJson(acts));
        }
        if (changedSvcs) {
            FileUtil.writeFile(
                    fpu.getManifestService(scId), GSON.toJson(svcs));
        }
        if (changedRcvs) {
            FileUtil.writeFile(
                    fpu.getManifestBroadcast(scId), GSON.toJson(rcvs));
        }
        return "none".equals(requestedRole) ? "none" : requestedRole;
    }

    public static void deleteSourceFile(String scId, String rawRelPath)
            throws Exception {
        String cleanRel = normalizeSourceRelPath(rawRelPath);
        FilePathUtil fpu = new FilePathUtil();
        File root = new File(fpu.getPathJava(scId));
        File target = new File(root, cleanRel);
        if (!target.exists() || !target.isFile()) {
            throw new AgentTools.ToolException("E_SOURCE_NOT_FOUND",
                    "Source file files/java/" + cleanRel
                            + " does not exist.");
        }
        String code = FileUtil.readFile(target.getAbsolutePath());
        String pkg = extractPackageName(
                code, expectedPackageForRelPath(scId, cleanRel));
        String fileName = target.getName();
        String cls = fileName.substring(0, fileName.lastIndexOf('.'));
        String fqcn = pkg + "." + cls;

        SketchwareActions.backupProjectOnce(scId);
        FileUtil.deleteFile(target.getAbsolutePath());
        if (target.exists()) {
            throw new IOException("Failed to delete files/java/" + cleanRel);
        }
        updateManifestRole(scId, fqcn, "none");
        pruneEmptyParents(root, target.getParentFile());
    }

    /** Resolves a custom manifest-registered Activity by simple or full name. */
    public static String findManifestActivityFqcn(String scId, String asked) {
        return findInManifestList(
                new FileResConfig(scId).getJavaManifestList(), asked);
    }

    /** Resolves a custom manifest-registered Service by simple or full name. */
    public static String findManifestServiceFqcn(String scId, String asked) {
        return findInManifestList(
                new FileResConfig(scId).getServiceManifestList(), asked);
    }

    /** Resolves a custom manifest-registered BroadcastReceiver by name. */
    public static String findManifestReceiverFqcn(String scId, String asked) {
        return findInManifestList(
                new FileResConfig(scId).getBroadcastManifestList(), asked);
    }

    private static String findInManifestList(List<String> fqcns,
                                             String asked) {
        if (fqcns == null || asked == null || asked.trim().isEmpty()) {
            return null;
        }
        String wanted = asked.trim();
        if (wanted.endsWith(".java")) {
            wanted = wanted.substring(0, wanted.length() - 5);
        } else if (wanted.endsWith(".kt")) {
            wanted = wanted.substring(0, wanted.length() - 3);
        } else if (wanted.endsWith(".class")) {
            wanted = wanted.substring(0, wanted.length() - 6);
        }
        for (String fqcn : fqcns) {
            if (fqcn == null) continue;
            if (fqcn.equals(wanted) || fqcn.equalsIgnoreCase(wanted)) {
                return fqcn;
            }
            String simple = fqcn.contains(".")
                    ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn;
            if (simple.equals(wanted) || simple.equalsIgnoreCase(wanted)) {
                return fqcn;
            }
        }
        return null;
    }

    public static List<String> listKotlinCompilerPlugins(String scId) {
        List<String> out = new ArrayList<>();
        File dir = new File(
                new FilePathUtil().getPathKotlinCompilerPlugins(scId));
        File[] jars = dir.listFiles(
                c -> c.isFile() && c.getName().endsWith(".jar"));
        if (jars != null) {
            for (File j : jars) {
                out.add(j.getName());
            }
            Collections.sort(out);
        }
        return out;
    }

    // =================================================================
    // 2. Assets Manager (files/assets/<relPath>)
    // =================================================================

    public static String normalizeAssetRelPath(String rawPath,
                                               boolean allowEmpty)
            throws AgentTools.ToolException {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            if (allowEmpty) return "";
            throw new AgentTools.ToolException("E_BAD_ASSET_PATH",
                    "Pass \"path\" relative to assets/ (e.g. "
                            + "\"data/items.json\", \"web/index.html\").");
        }
        String cleaned = rawPath.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.startsWith("files/assets/")) {
            cleaned = cleaned.substring("files/assets/".length());
        } else if (cleaned.startsWith("assets/")) {
            cleaned = cleaned.substring("assets/".length());
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty()) {
            if (allowEmpty) return "";
            throw new AgentTools.ToolException("E_BAD_ASSET_PATH",
                    "Asset path cannot be empty.");
        }
        if (cleaned.contains("..") || cleaned.contains("//")) {
            throw new AgentTools.ToolException("E_BAD_ASSET_PATH",
                    "Invalid asset path \"" + rawPath + "\".");
        }
        String[] parts = cleaned.split("/");
        for (String seg : parts) {
            if (!ASSET_SEG_PATTERN.matcher(seg).matches()) {
                throw new AgentTools.ToolException("E_BAD_ASSET_PATH",
                        "Invalid asset path segment \"" + seg + "\" in \""
                                + rawPath + "\".");
            }
        }
        return String.join("/", parts);
    }

    public static boolean isTextAssetName(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TEXT_ASSET_EXTENSIONS.contains(ext);
    }

    public static JsonArray listAssets(String scId, String filterSubdir)
            throws AgentTools.ToolException {
        JsonArray out = new JsonArray();
        File root = new File(new FilePathUtil().getPathAssets(scId));
        if (!root.exists() || !root.isDirectory()) {
            return out;
        }
        String sub = normalizeAssetRelPath(filterSubdir, true);
        File start = sub.isEmpty() ? root : new File(root, sub);
        if (!start.exists()) {
            return out;
        }
        List<File> files = new ArrayList<>();
        collectFilesRecursive(start, files);
        files.sort((a, b) -> a.getAbsolutePath()
                .compareTo(b.getAbsolutePath()));
        for (File f : files) {
            String rel = relativePathFrom(root, f);
            JsonObject item = new JsonObject();
            item.addProperty("path", rel);
            item.addProperty("bytes", f.length());
            item.addProperty("kind",
                    isTextAssetName(f.getName()) ? "text" : "binary");
            out.add(item);
        }
        return out;
    }

    public static JsonObject readAsset(String scId, String rawRelPath)
            throws AgentTools.ToolException {
        String cleanRel = normalizeAssetRelPath(rawRelPath, false);
        File target = new File(
                new FilePathUtil().getPathAssets(scId), cleanRel);
        if (!target.exists() || !target.isFile()) {
            throw new AgentTools.ToolException("E_ASSET_NOT_FOUND",
                    "Asset file assets/" + cleanRel + " does not exist.");
        }
        JsonObject res = new JsonObject();
        res.addProperty("path", cleanRel);
        res.addProperty("bytes", target.length());
        boolean textKind = isTextAssetName(target.getName());
        res.addProperty("kind", textKind ? "text" : "binary");
        if (textKind && target.length() <= 512L * 1024L) {
            res.addProperty("content",
                    FileUtil.readFile(target.getAbsolutePath()));
        } else if (!textKind && target.length() <= 64L * 1024L) {
            try {
                byte[] bytes = readBytes(target);
                res.addProperty("base64",
                        Base64.encodeToString(bytes, Base64.NO_WRAP));
            } catch (IOException ignored) {
            }
        } else {
            res.addProperty("note",
                    "File exceeds inline read threshold; inspect size/path.");
        }
        return res;
    }

    public static byte[] prepareAssetBytes(String cleanRelPath,
                                           String textContent,
                                           String base64Content,
                                           String sourceFilePath)
            throws AgentTools.ToolException {
        if (textContent != null) {
            if (cleanRelPath.toLowerCase(Locale.ROOT).endsWith(".json")) {
                try {
                    JsonParser.parseString(textContent);
                } catch (Exception e) {
                    throw new AgentTools.ToolException("E_BAD_JSON",
                            "Asset \"" + cleanRelPath
                                    + "\" is not valid JSON: "
                                    + String.valueOf(e.getMessage()));
                }
            }
            return textContent.getBytes(StandardCharsets.UTF_8);
        }
        if (base64Content != null && !base64Content.trim().isEmpty()) {
            try {
                return Base64.decode(base64Content.trim(), Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                throw new AgentTools.ToolException("E_BAD_BASE64",
                        "Invalid base64 payload for asset \""
                                + cleanRelPath + "\".");
            }
        }
        if (sourceFilePath != null && !sourceFilePath.trim().isEmpty()) {
            File src = new File(sourceFilePath.trim());
            if (!src.exists() || !src.isFile()) {
                throw new AgentTools.ToolException("E_SOURCE_FILE_MISSING",
                        "Source file does not exist: " + sourceFilePath);
            }
            if (src.length() > 50L * 1024L * 1024L) {
                throw new AgentTools.ToolException("E_FILE_TOO_LARGE",
                        "Asset file exceeds 50 MB limit.");
            }
            try {
                return readBytes(src);
            } catch (IOException e) {
                throw new AgentTools.ToolException("E_READ_FAILED",
                        "Failed reading " + sourceFilePath + ": "
                                + String.valueOf(e.getMessage()));
            }
        }
        throw new AgentTools.ToolException("E_MISSING_ASSET_CONTENT",
                "Pass \"content\" (UTF-8 text), \"base64\", or "
                        + "\"source_file_path\" to write assets/"
                        + cleanRelPath + ".");
    }

    public static File writeAsset(String scId, String rawRelPath,
                                  byte[] data) throws Exception {
        String cleanRel = normalizeAssetRelPath(rawRelPath, false);
        SketchwareActions.backupProjectOnce(scId);
        File root = new File(new FilePathUtil().getPathAssets(scId));
        File target = new File(root, cleanRel);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create assets directory "
                    + parent);
        }
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(data);
        }
        if (!target.exists() || target.length() != data.length) {
            throw new IOException("Asset write verification failed for "
                    + cleanRel);
        }
        return target;
    }

    public static void deleteAsset(String scId, String rawRelPath)
            throws Exception {
        String cleanRel = normalizeAssetRelPath(rawRelPath, false);
        File root = new File(new FilePathUtil().getPathAssets(scId));
        File target = new File(root, cleanRel);
        if (!target.exists()) {
            throw new AgentTools.ToolException("E_ASSET_NOT_FOUND",
                    "Asset assets/" + cleanRel + " does not exist.");
        }
        SketchwareActions.backupProjectOnce(scId);
        FileUtil.deleteFile(target.getAbsolutePath());
        if (target.exists()) {
            throw new IOException("Failed to delete assets/" + cleanRel);
        }
        pruneEmptyParents(root, target.getParentFile());
    }

    // =================================================================
    // 3. Native Libraries Manager (files/native_libs/<abi>/lib*.so)
    // =================================================================

    public static String normalizeAbi(String rawAbi)
            throws AgentTools.ToolException {
        String abi = rawAbi == null ? "" : rawAbi.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ABIS.contains(abi)) {
            throw new AgentTools.ToolException("E_BAD_ABI",
                    "Invalid Android ABI \"" + rawAbi
                            + "\". Supported ABIs: "
                            + String.join(", ", SUPPORTED_ABIS) + ".");
        }
        return abi;
    }

    public static String normalizeSoFileName(String rawName)
            throws AgentTools.ToolException {
        String name = rawName == null ? "" : rawName.trim();
        if (!SO_FILE_PATTERN.matcher(name).matches()) {
            throw new AgentTools.ToolException("E_BAD_SO_NAME",
                    "Native library filename \"" + rawName
                            + "\" must match lib<name>.so (e.g. "
                            + "libcrypto.so).");
        }
        return name;
    }

    public static String detectElfArch(File soFile) {
        if (soFile == null || !soFile.isFile() || soFile.length() < 20) {
            return "invalid";
        }
        byte[] hdr = new byte[20];
        try (FileInputStream fis = new FileInputStream(soFile)) {
            if (fis.read(hdr) < 20) return "invalid";
        } catch (IOException e) {
            return "unreadable";
        }
        if (hdr[0] != 0x7F || hdr[1] != 'E' || hdr[2] != 'L'
                || hdr[3] != 'F') {
            return "not_elf";
        }
        boolean littleEndian = hdr[5] == 1;
        int eMachine = littleEndian
                ? ((hdr[18] & 0xFF) | ((hdr[19] & 0xFF) << 8))
                : (((hdr[18] & 0xFF) << 8) | (hdr[19] & 0xFF));
        return switch (eMachine) {
            case 183 -> "arm64-v8a";
            case 40 -> "armeabi-v7a";
            case 62 -> "x86_64";
            case 3 -> "x86";
            default -> "elf_machine_" + eMachine;
        };
    }

    private static boolean elfMatchesAbi(String detectedArch, String abi) {
        if (detectedArch.equals(abi)) return true;
        return "armeabi".equals(abi) && "armeabi-v7a".equals(detectedArch);
    }

    public static JsonObject inspectNativeLibraries(String scId) {
        JsonObject out = new JsonObject();
        File root = new File(new FilePathUtil().getPathNativelibs(scId));
        JsonObject byAbi = new JsonObject();
        Map<String, Set<String>> abiToLibs = new LinkedHashMap<>();
        Set<String> allLibNames = new LinkedHashSet<>();

        if (root.exists() && root.isDirectory()) {
            File[] abiDirs = root.listFiles();
            if (abiDirs != null) {
                Arrays.sort(abiDirs,
                        (a, b) -> a.getName().compareTo(b.getName()));
                for (File abiDir : abiDirs) {
                    if (abiDir == null || !abiDir.isDirectory()) continue;
                    String abi = abiDir.getName();
                    File[] soFiles = abiDir.listFiles(
                            f -> f.isFile() && f.getName().endsWith(".so"));
                    if (soFiles == null || soFiles.length == 0) continue;
                    Arrays.sort(soFiles,
                            (a, b) -> a.getName().compareTo(b.getName()));
                    JsonArray arr = new JsonArray();
                    Set<String> libSet = new LinkedHashSet<>();
                    for (File so : soFiles) {
                        String detected = detectElfArch(so);
                        JsonObject item = new JsonObject();
                        item.addProperty("name", so.getName());
                        item.addProperty("bytes", so.length());
                        item.addProperty("elf_arch", detected);
                        arr.add(item);
                        libSet.add(so.getName());
                        allLibNames.add(so.getName());
                    }
                    byAbi.add(abi, arr);
                    abiToLibs.put(abi, libSet);
                }
            }
        }
        out.add("abis", byAbi);
        out.addProperty("total_unique_libraries", allLibNames.size());

        JsonArray warnings = new JsonArray();
        if (!abiToLibs.isEmpty()) {
            if (!abiToLibs.containsKey("arm64-v8a")
                    && (abiToLibs.containsKey("armeabi-v7a")
                            || abiToLibs.containsKey("armeabi"))) {
                warnings.add("32-bit ARM native libraries are present "
                        + "without arm64-v8a counterparts; 64-bit-only "
                        + "Android devices and Google Play require "
                        + "arm64-v8a.");
            }
            for (Map.Entry<String, Set<String>> e : abiToLibs.entrySet()) {
                for (String lib : allLibNames) {
                    if (!e.getValue().contains(lib)) {
                        warnings.add("ABI \"" + e.getKey()
                                + "\" is missing " + lib
                                + " (present in another ABI folder).");
                    }
                }
            }
        }
        out.add("warnings", warnings);
        return out;
    }

    public static void validateNativeLibImport(String abi,
                                               String sourceFilePath,
                                               String targetName)
            throws AgentTools.ToolException {
        normalizeAbi(abi);
        normalizeSoFileName(targetName);
        if (sourceFilePath == null || sourceFilePath.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_MISSING_FILE",
                    "Pass \"source_file_path\" to a local .so file.");
        }
        File src = new File(sourceFilePath.trim());
        if (!src.exists() || !src.isFile()) {
            throw new AgentTools.ToolException("E_FILE_NOT_FOUND",
                    "Native library file does not exist: " + sourceFilePath);
        }
        String detected = detectElfArch(src);
        if ("invalid".equals(detected) || "not_elf".equals(detected)
                || "unreadable".equals(detected)) {
            throw new AgentTools.ToolException("E_NOT_ELF_SO",
                    "File " + src.getName()
                            + " is not a valid Android ELF .so binary.");
        }
        if (!elfMatchesAbi(detected, abi)) {
            throw new AgentTools.ToolException("E_ABI_MISMATCH",
                    "ELF header of " + src.getName() + " targets \""
                            + detected + "\", which does not match ABI \""
                            + abi + "\".");
        }
    }

    public static File importNativeLibrary(String scId, String rawAbi,
                                           String sourceFilePath,
                                           String rawTargetName)
            throws Exception {
        String abi = normalizeAbi(rawAbi);
        File src = new File(sourceFilePath.trim());
        String targetName = rawTargetName == null
                || rawTargetName.trim().isEmpty()
                ? src.getName() : rawTargetName.trim();
        validateNativeLibImport(abi, sourceFilePath, targetName);

        SketchwareActions.backupProjectOnce(scId);
        File abiDir = new File(
                new FilePathUtil().getPathNativelibs(scId), abi);
        if (!abiDir.exists() && !abiDir.mkdirs()) {
            throw new IOException("Could not create native_libs/" + abi);
        }
        File dest = new File(abiDir, targetName);
        byte[] bytes = readBytes(src);
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(bytes);
        }
        if (!dest.exists() || dest.length() != bytes.length) {
            throw new IOException("Failed writing native_libs/" + abi + "/"
                    + targetName);
        }
        return dest;
    }

    public static void deleteNativeLibrary(String scId, String rawAbi,
                                           String rawFileName)
            throws Exception {
        String abi = normalizeAbi(rawAbi);
        String fileName = normalizeSoFileName(rawFileName);
        File target = new File(
                new FilePathUtil().getPathNativelibs(scId),
                abi + "/" + fileName);
        if (!target.exists() || !target.isFile()) {
            throw new AgentTools.ToolException("E_NATIVE_LIB_NOT_FOUND",
                    "Native library native_libs/" + abi + "/" + fileName
                            + " does not exist.");
        }
        SketchwareActions.backupProjectOnce(scId);
        FileUtil.deleteFile(target.getAbsolutePath());
        if (target.exists()) {
            throw new IOException("Failed deleting native_libs/" + abi
                    + "/" + fileName);
        }
    }

    // =================================================================
    // File tree helpers
    // =================================================================

    private static void collectFilesRecursive(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                collectFilesRecursive(child, out);
            } else if (child.isFile()) {
                out.add(child);
            }
        }
    }

    private static String relativePathFrom(File root, File child) {
        String rootPath = root.getAbsolutePath().replace('\\', '/');
        if (!rootPath.endsWith("/")) {
            rootPath = rootPath + "/";
        }
        String childPath = child.getAbsolutePath().replace('\\', '/');
        return childPath.startsWith(rootPath)
                ? childPath.substring(rootPath.length()) : child.getName();
    }

    private static void pruneEmptyParents(File stopAtRoot, File dir) {
        File cur = dir;
        while (cur != null && !cur.equals(stopAtRoot) && cur.isDirectory()) {
            File[] list = cur.listFiles();
            if (list != null && list.length == 0) {
                File parent = cur.getParentFile();
                cur.delete();
                cur = parent;
            } else {
                break;
            }
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (FileInputStream fis = new FileInputStream(f)) {
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }
}
