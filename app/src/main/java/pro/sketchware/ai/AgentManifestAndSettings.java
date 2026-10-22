package pro.sketchware.ai;

import android.os.Environment;

import com.besome.sketch.beans.ProjectFileBean;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import a.a.a.lC;
import a.a.a.wq;
import a.a.a.yB;
import dev.aldi.sayuti.editor.injection.AppCompatInjection;
import mod.hey.studios.project.ProjectSettings;
import mod.hey.studios.project.proguard.ProguardHandler;
import mod.hey.studios.project.stringfog.StringfogHandler;
import mod.hey.studios.util.Helper;
import mod.hilal.saif.android_manifest.AndroidManifestInjector;
import pro.sketchware.utility.FileUtil;

/**
 * Phase 7 backend for AndroidManifest injection, AppCompat XML injection,
 * project metadata &amp; ProjectSettings, ProGuard/R8, and StringFog.
 */
public final class AgentManifestAndSettings {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");
    private static final Pattern ATTR_PAIR_PATTERN = Pattern.compile(
            "^[a-zA-Z_][a-zA-Z0-9_.:-]*\\s*=\\s*\".*\"$",
            Pattern.DOTALL);
    private static final List<String> APPCOMPAT_TYPES = Arrays.asList(
            "toolbar", "appbarlayout", "coordinatorlayout",
            "drawerlayout", "navigationdrawer");

    private AgentManifestAndSettings() {
    }

    // ------------------------------------------------------------------
    // 1. Read Complete Manifest, AppCompat, Metadata, Settings & Shrinking
    // ------------------------------------------------------------------

    public static JsonObject inspectAll(String scId) {
        JsonObject out = new JsonObject();
        out.add("manifest", inspectManifest(scId));
        out.add("appcompat_injections", inspectAppCompatInjections(scId));
        out.add("project_metadata", inspectProjectMetadata(scId));
        out.add("project_settings", inspectProjectSettings(scId));
        out.add("proguard_and_r8", inspectProguard(scId));
        out.add("stringfog", inspectStringFog(scId));
        return out;
    }

    private static ArrayList<HashMap<String, Object>> readJsonMapList(
            File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return new ArrayList<>();
        }
        try {
            String raw = FileUtil.readFile(file.getAbsolutePath());
            if (raw == null || raw.trim().isEmpty()) {
                return new ArrayList<>();
            }
            ArrayList<HashMap<String, Object>> list =
                    new Gson().fromJson(raw, Helper.TYPE_MAP_LIST);
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

    public static JsonObject inspectManifest(String scId) {
        JsonObject manifest = new JsonObject();
        String launcherFile = AndroidManifestInjector.getLauncherActivity(scId);
        manifest.addProperty("launcher_file_name", launcherFile);
        ProjectFileBean launcherBean =
                SketchwareProjects.findActivity(scId, launcherFile);
        if (launcherBean != null) {
            manifest.addProperty("launcher_activity",
                    SketchwareProjects.activityName(launcherBean));
        }

        JsonArray appAttrs = new JsonArray();
        JsonArray allActAttrs = new JsonArray();
        JsonArray extraPerms = new JsonArray();
        JsonObject perActivityAttrs = new JsonObject();

        ArrayList<HashMap<String, Object>> attrs = readJsonMapList(
                AndroidManifestInjector
                        .getPathAndroidManifestAttributeInjection(scId));
        for (HashMap<String, Object> row : attrs) {
            String name = mapStr(row, "name");
            String val = mapStr(row, "value");
            if (name.isEmpty() || val.isEmpty()) continue;
            switch (name) {
                case "_application_attrs" -> appAttrs.add(val);
                case "_apply_for_all_activities" -> allActAttrs.add(val);
                case "_application_permissions" -> extraPerms.add(val);
                default -> {
                    JsonArray arr = perActivityAttrs.has(name)
                            ? perActivityAttrs.getAsJsonArray(name)
                            : new JsonArray();
                    arr.add(val);
                    perActivityAttrs.add(name, arr);
                }
            }
        }
        manifest.add("application_attributes", appAttrs);
        manifest.add("all_activities_attributes", allActAttrs);
        manifest.add("custom_permission_attributes", extraPerms);
        manifest.add("activity_attributes", perActivityAttrs);

        JsonObject actComponents = new JsonObject();
        ArrayList<HashMap<String, Object>> actCompRows = readJsonMapList(
                AndroidManifestInjector
                        .getPathAndroidManifestActivitiesComponents(scId));
        for (HashMap<String, Object> row : actCompRows) {
            String name = mapStr(row, "name");
            String val = mapStr(row, "value");
            if (!name.isEmpty() && !val.trim().isEmpty()) {
                actComponents.addProperty(name, val);
            }
        }
        manifest.add("activity_components_xml", actComponents);

        File appCompFile = AndroidManifestInjector
                .getPathAndroidManifestAppComponents(scId);
        String appCompXml = appCompFile.exists()
                ? FileUtil.readFile(appCompFile.getAbsolutePath()) : "";
        manifest.addProperty("app_components_xml",
                appCompXml == null ? "" : appCompXml.trim());
        return manifest;
    }

    public static JsonObject inspectAppCompatInjections(String scId) {
        JsonObject out = new JsonObject();
        File dir = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/injection/appcompat");
        if (!dir.exists() || !dir.isDirectory()) {
            return out;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            if (!f.isFile()) continue;
            JsonArray arr = new JsonArray();
            for (HashMap<String, Object> row : readJsonMapList(f)) {
                JsonObject item = new JsonObject();
                item.addProperty("type", mapStr(row, "type"));
                item.addProperty("value", mapStr(row, "value"));
                arr.add(item);
            }
            out.add(f.getName(), arr);
        }
        return out;
    }

    public static JsonObject inspectProjectMetadata(String scId) {
        JsonObject meta = new JsonObject();
        HashMap<String, Object> raw = lC.b(scId);
        if (raw != null) {
            meta.addProperty("project_name", yB.c(raw, "my_ws_name"));
            meta.addProperty("app_name", yB.c(raw, "my_app_name"));
            meta.addProperty("package_name", yB.c(raw, "my_sc_pkg_name"));
            meta.addProperty("version_code", yB.c(raw, "sc_ver_code"));
            meta.addProperty("version_name", yB.c(raw, "sc_ver_name"));
            meta.addProperty("custom_icon", yB.a(raw, "custom_icon"));
        }
        return meta;
    }

    public static JsonObject inspectProjectSettings(String scId) {
        ProjectSettings ps = new ProjectSettings(scId);
        JsonObject out = new JsonObject();
        out.addProperty("min_sdk", ps.getMinSdkVersion());
        out.addProperty("target_sdk", ps.getValue(
                ProjectSettings.SETTING_TARGET_SDK_VERSION, "28"));
        out.addProperty("application_class", ps.getValue(
                ProjectSettings.SETTING_APPLICATION_CLASS,
                ".SketchApplication"));
        out.addProperty("enable_viewbinding", Boolean.parseBoolean(
                ps.getValue(ProjectSettings.SETTING_ENABLE_VIEWBINDING,
                        ProjectSettings.SETTING_GENERIC_VALUE_FALSE)));
        out.addProperty("enable_bridgeless_themes", Boolean.parseBoolean(
                ps.getValue(ProjectSettings.SETTING_ENABLE_BRIDGELESS_THEMES,
                        ProjectSettings.SETTING_GENERIC_VALUE_FALSE)));
        out.addProperty("disable_old_methods", Boolean.parseBoolean(
                ps.getValue(ProjectSettings.SETTING_DISABLE_OLD_METHODS,
                        ProjectSettings.SETTING_GENERIC_VALUE_FALSE)));
        out.addProperty("new_xml_command", Boolean.parseBoolean(
                ps.getValue(ProjectSettings.SETTING_NEW_XML_COMMAND,
                        ProjectSettings.SETTING_GENERIC_VALUE_FALSE)));
        return out;
    }

    public static JsonObject inspectProguard(String scId) {
        JsonObject out = new JsonObject();
        File configFile = new File(FileUtil.getExternalStorageDir(),
                ".sketchware/data/" + scId + "/proguard");
        boolean enabled = false;
        boolean r8 = false;
        boolean debug = false;
        if (configFile.exists()) {
            try {
                HashMap<String, String> cfg = new Gson().fromJson(
                        FileUtil.readFile(configFile.getAbsolutePath()),
                        Helper.TYPE_STRING_MAP);
                if (cfg != null) {
                    enabled = "true".equals(cfg.get("enabled"));
                    r8 = "true".equals(cfg.get("r8"));
                    debug = "true".equals(cfg.get("debug"));
                }
            } catch (Exception ignored) {
            }
        }
        out.addProperty("enabled", enabled);
        out.addProperty("r8_enabled", r8);
        out.addProperty("debug_files_enabled", debug);

        File rulesFile = new File(FileUtil.getExternalStorageDir(),
                ".sketchware/data/" + scId + "/proguard-rules.pro");
        out.addProperty("proguard_rules", rulesFile.exists()
                ? FileUtil.readFile(rulesFile.getAbsolutePath()) : "");

        JsonArray fmArr = new JsonArray();
        File fmFile = new File(FileUtil.getExternalStorageDir(),
                ".sketchware/data/" + scId + "/proguard_fm");
        if (fmFile.exists()) {
            try {
                ArrayList<String> fm = new Gson().fromJson(
                        FileUtil.readFile(fmFile.getAbsolutePath()),
                        Helper.TYPE_STRING);
                if (fm != null) {
                    for (String lib : fm) {
                        fmArr.add(lib);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        out.add("full_mode_local_libs", fmArr);
        return out;
    }

    public static JsonObject inspectStringFog(String scId) {
        JsonObject out = new JsonObject();
        File sfFile = new File(FileUtil.getExternalStorageDir(),
                ".sketchware/data/" + scId + "/stringfog");
        boolean enabled = false;
        if (sfFile.exists()) {
            try {
                HashMap<String, String> cfg = new Gson().fromJson(
                        FileUtil.readFile(sfFile.getAbsolutePath()),
                        Helper.TYPE_STRING_MAP);
                enabled = cfg != null && "true".equals(cfg.get("enabled"));
            } catch (Exception ignored) {
            }
        }
        out.addProperty("enabled", enabled);
        return out;
    }

    // ------------------------------------------------------------------
    // 2. AndroidManifest Mutations (AndroidManifestInjector)
    // ------------------------------------------------------------------

    public static void validateAttributePair(String attr)
            throws AgentTools.ToolException {
        if (attr == null || !ATTR_PAIR_PATTERN.matcher(attr.trim()).matches()) {
            throw new AgentTools.ToolException("E_BAD_MANIFEST_ATTR",
                    "Manifest/XML attribute \"" + attr
                            + "\" must look like "
                            + "android:name=\"value\" (with quotes).");
        }
    }

    public static void validateXmlSnippet(String snippet, String label)
            throws AgentTools.ToolException {
        if (snippet == null || snippet.trim().isEmpty()) {
            return;
        }
        String wrapped = "<manifest xmlns:android="
                + "\"http://schemas.android.com/apk/res/android\" "
                + "xmlns:tools=\"http://schemas.android.com/tools\">"
                + snippet + "</manifest>";
        try {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            f.setNamespaceAware(false);
            XmlPullParser p = f.newPullParser();
            p.setInput(new StringReader(wrapped));
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                // Validate balanced tags and attribute syntax.
            }
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_INVALID_MANIFEST_XML",
                    "Malformed XML in " + label + ": " + e.getMessage());
        }
    }

    private static String attrKeyOf(String attrPair) {
        int eq = attrPair.indexOf('=');
        return eq > 0 ? attrPair.substring(0, eq).trim() : attrPair.trim();
    }

    private static String resolveActivityManifestKey(
            String scId, String rawActivity)
            throws AgentTools.ToolException {
        ProjectFileBean bean =
                SketchwareProjects.findActivity(scId, rawActivity);
        if (bean != null) {
            String javaName = bean.getJavaName();
            return javaName.endsWith(".java")
                    ? javaName.substring(0, javaName.length() - 5)
                    : javaName;
        }
        String clean = rawActivity == null ? "" : rawActivity.trim();
        if (clean.endsWith(".java")) {
            clean = clean.substring(0, clean.length() - 5);
        }
        if (clean.isEmpty()) {
            throw new AgentTools.ToolException("E_ACTIVITY_NOT_FOUND",
                    "Activity \"" + rawActivity + "\" was not found.");
        }
        return clean;
    }

    public static JsonObject applyManifestChanges(
            String scId, String launcherActivity,
            List<String> appAttrs, boolean replaceAppAttrs,
            List<String> allActAttrs, boolean replaceAllActAttrs,
            String targetActivity, List<String> activityAttrs,
            boolean replaceActivityAttrs,
            String activityComponentsXml,
            String appComponentsXml, boolean replaceAppComponentsXml)
            throws AgentTools.ToolException, IOException {
        SketchwareActions.backupProjectOnce(scId);
        JsonObject summary = new JsonObject();

        if (launcherActivity != null && !launcherActivity.trim().isEmpty()) {
            ProjectFileBean bean = SketchwareProjects.findActivity(
                    scId, launcherActivity.trim());
            if (bean == null
                    || bean.fileType
                            != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY
                    || bean.fileName.contains("_fragment")) {
                throw new AgentTools.ToolException("E_BAD_LAUNCHER_ACTIVITY",
                        "Launcher activity \"" + launcherActivity
                                + "\" must be a visual Activity screen in "
                                + "this project.");
            }
            File lf = AndroidManifestInjector
                    .getPathAndroidManifestLauncherActivity(scId);
            if (lf.getParentFile() != null && !lf.getParentFile().exists()) {
                lf.getParentFile().mkdirs();
            }
            AndroidManifestInjector.setLauncherActivity(scId, bean.fileName);
            summary.addProperty("launcher_activity",
                    SketchwareProjects.activityName(bean));
        }

        if (appAttrs != null || allActAttrs != null
                || (targetActivity != null && !targetActivity.isEmpty()
                        && activityAttrs != null)) {
            File attrFile = AndroidManifestInjector
                    .getPathAndroidManifestAttributeInjection(scId);
            if (attrFile.getParentFile() != null
                    && !attrFile.getParentFile().exists()) {
                attrFile.getParentFile().mkdirs();
            }
            ArrayList<HashMap<String, Object>> rows =
                    readJsonMapList(attrFile);
            if (appAttrs != null) {
                upsertAttrBucket(rows, "_application_attrs",
                        appAttrs, replaceAppAttrs);
            }
            if (allActAttrs != null) {
                upsertAttrBucket(rows, "_apply_for_all_activities",
                        allActAttrs, replaceAllActAttrs);
            }
            if (targetActivity != null && !targetActivity.trim().isEmpty()
                    && activityAttrs != null) {
                String actKey = resolveActivityManifestKey(
                        scId, targetActivity);
                upsertAttrBucket(rows, actKey, activityAttrs,
                        replaceActivityAttrs);
                summary.addProperty("updated_activity_attributes", actKey);
            }
            FileUtil.writeFile(attrFile.getAbsolutePath(),
                    new GsonBuilder().setPrettyPrinting().create()
                            .toJson(rows));
        }

        if (activityComponentsXml != null) {
            if (targetActivity == null || targetActivity.trim().isEmpty()) {
                throw new AgentTools.ToolException("E_MISSING_ACTIVITY",
                        "Pass \"activity\" when setting "
                                + "\"activity_components_xml\" (e.g. an "
                                + "<intent-filter> on MainActivity).");
            }
            validateXmlSnippet(activityComponentsXml,
                    "activity_components_xml");
            String actKey = resolveActivityManifestKey(
                    scId, targetActivity);
            File compFile = AndroidManifestInjector
                    .getPathAndroidManifestActivitiesComponents(scId);
            if (compFile.getParentFile() != null
                    && !compFile.getParentFile().exists()) {
                compFile.getParentFile().mkdirs();
            }
            ArrayList<HashMap<String, Object>> data =
                    readJsonMapList(compFile);
            data.removeIf(r -> actKey.equals(mapStr(r, "name")));
            if (!activityComponentsXml.trim().isEmpty()) {
                HashMap<String, Object> entry = new HashMap<>();
                entry.put("name", actKey);
                entry.put("value", activityComponentsXml.trim());
                data.add(entry);
            }
            FileUtil.writeFile(compFile.getAbsolutePath(),
                    new GsonBuilder().setPrettyPrinting().create()
                            .toJson(data));
            summary.addProperty("updated_activity_components", actKey);
        }

        if (appComponentsXml != null) {
            validateXmlSnippet(appComponentsXml, "app_components_xml");
            File appFile = AndroidManifestInjector
                    .getPathAndroidManifestAppComponents(scId);
            if (appFile.getParentFile() != null
                    && !appFile.getParentFile().exists()) {
                appFile.getParentFile().mkdirs();
            }
            String finalXml;
            if (replaceAppComponentsXml || !appFile.exists()) {
                finalXml = appComponentsXml.trim();
            } else {
                String existing = FileUtil.readFile(
                        appFile.getAbsolutePath());
                finalXml = (existing == null || existing.trim().isEmpty())
                        ? appComponentsXml.trim()
                        : (existing.trim() + "\n" + appComponentsXml.trim());
            }
            FileUtil.writeFile(appFile.getAbsolutePath(), finalXml);
            summary.addProperty("updated_app_components", true);
        }

        return summary;
    }

    private static void upsertAttrBucket(
            ArrayList<HashMap<String, Object>> rows, String bucketName,
            List<String> newAttrs, boolean replaceBucket)
            throws AgentTools.ToolException {
        for (String a : newAttrs) {
            validateAttributePair(a);
        }
        if (replaceBucket) {
            rows.removeIf(r -> bucketName.equals(mapStr(r, "name")));
        }
        for (String a : newAttrs) {
            String clean = a.trim();
            String key = attrKeyOf(clean);
            rows.removeIf(r -> bucketName.equals(mapStr(r, "name"))
                    && key.equals(attrKeyOf(mapStr(r, "value"))));
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("name", bucketName);
            entry.put("value", clean);
            rows.add(entry);
        }
    }

    // ------------------------------------------------------------------
    // 3. AppCompat XML Injection (.sketchware/data/<scId>/injection/appcompat/)
    // ------------------------------------------------------------------

    public static JsonObject updateAppCompatInjection(
            String scId, String rawActivity, boolean resetToDefault,
            String widgetType, List<String> attributes, boolean replaceType)
            throws AgentTools.ToolException, IOException {
        ProjectFileBean bean =
                SketchwareProjects.findActivity(scId, rawActivity);
        if (bean == null) {
            throw new AgentTools.ToolException("E_ACTIVITY_NOT_FOUND",
                    "Activity \"" + rawActivity + "\" does not exist.");
        }
        File target = new File(Environment.getExternalStorageDirectory(),
                ".sketchware/data/" + scId + "/injection/appcompat/"
                        + bean.fileName);
        SketchwareActions.backupProjectOnce(scId);
        if (resetToDefault) {
            if (target.exists()) {
                target.delete();
            }
            AppCompatInjection.refreshInjections();
            JsonObject res = new JsonObject();
            res.addProperty("activity", bean.fileName);
            res.addProperty("reset_to_default", true);
            return res;
        }
        String normType = widgetType == null
                ? "" : widgetType.trim().toLowerCase(Locale.ROOT);
        if (!APPCOMPAT_TYPES.contains(normType)) {
            throw new AgentTools.ToolException("E_BAD_APPCOMPAT_TYPE",
                    "Invalid AppCompat target \"" + widgetType
                            + "\". Use one of: "
                            + String.join(", ", APPCOMPAT_TYPES) + ".");
        }
        if (attributes == null || attributes.isEmpty()) {
            throw new AgentTools.ToolException("E_EMPTY_ATTRIBUTES",
                    "Pass one or more XML attributes like "
                            + "app:elevation=\"0dp\" or reset=true.");
        }
        for (String a : attributes) {
            validateAttributePair(a);
        }

        ArrayList<HashMap<String, Object>> list;
        if (target.exists() && target.length() > 0) {
            list = readJsonMapList(target);
        } else {
            list = new Gson().fromJson(
                    AppCompatInjection.getDefaultActivityInjections(),
                    Helper.TYPE_MAP_LIST);
            if (list == null) list = new ArrayList<>();
        }

        if (replaceType) {
            list.removeIf(r -> normType.equalsIgnoreCase(mapStr(r, "type")));
        }
        for (String a : attributes) {
            String clean = a.trim();
            String key = attrKeyOf(clean);
            list.removeIf(r -> normType.equalsIgnoreCase(mapStr(r, "type"))
                    && key.equals(attrKeyOf(mapStr(r, "value"))));
            HashMap<String, Object> row = new HashMap<>();
            row.put("type", normType);
            row.put("value", clean);
            list.add(row);
        }

        if (target.getParentFile() != null
                && !target.getParentFile().exists()) {
            target.getParentFile().mkdirs();
        }
        FileUtil.writeFile(target.getAbsolutePath(),
                new GsonBuilder().setPrettyPrinting().create()
                        .toJson(list));
        AppCompatInjection.refreshInjections();

        JsonObject res = new JsonObject();
        res.addProperty("activity", bean.fileName);
        res.addProperty("type", normType);
        res.addProperty("total_entries", list.size());
        return res;
    }

    // ------------------------------------------------------------------
    // 4. Project Metadata & ProjectSettings
    // ------------------------------------------------------------------

    public static JsonObject updateProjectSettings(
            String scId, String projectName, String appName,
            String packageName, Integer versionCode, String versionName,
            Integer minSdk, Integer targetSdk, String applicationClass,
            Boolean enableViewBinding, Boolean enableBridgelessThemes,
            Boolean disableOldMethods)
            throws AgentTools.ToolException, IOException {
        if (packageName != null && !packageName.trim().isEmpty()
                && !PACKAGE_PATTERN.matcher(packageName.trim()).matches()) {
            throw new AgentTools.ToolException("E_BAD_PACKAGE_NAME",
                    "Invalid Android package name \"" + packageName
                            + "\" (must have at least two dot-separated "
                            + "Java identifiers, e.g. com.acme.notes).");
        }
        if (versionCode != null
                && (versionCode < 1 || versionCode > 2100000000)) {
            throw new AgentTools.ToolException("E_BAD_VERSION_CODE",
                    "version_code must be between 1 and 2100000000.");
        }
        if (minSdk != null && (minSdk < 21 || minSdk > 36)) {
            throw new AgentTools.ToolException("E_BAD_MIN_SDK",
                    "min_sdk must be between 21 and 36.");
        }
        if (targetSdk != null && (targetSdk < 23 || targetSdk > 37)) {
            throw new AgentTools.ToolException("E_BAD_TARGET_SDK",
                    "target_sdk must be between 23 and 37.");
        }

        SketchwareActions.backupProjectOnce(scId);
        HashMap<String, Object> metadata = lC.b(scId);
        if (metadata != null
                && (projectName != null || appName != null
                        || packageName != null || versionCode != null
                        || versionName != null)) {
            if (projectName != null && !projectName.trim().isEmpty()) {
                metadata.put("my_ws_name", projectName.trim());
            }
            if (appName != null && !appName.trim().isEmpty()) {
                metadata.put("my_app_name", appName.trim());
                String stringsPath = wq.b(scId)
                        + "/files/resource/values/strings.xml";
                if (FileUtil.isExistFile(stringsPath)) {
                    String xml = FileUtil.readFile(stringsPath);
                    String safeName = appName.trim()
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;");
                    xml = xml.replaceAll(
                            "(<string\\s+name=\"app_name\">)(.*?)(</string>)",
                            "$1" + java.util.regex.Matcher
                                    .quoteReplacement(safeName) + "$3");
                    FileUtil.writeFile(stringsPath, xml);
                }
            }
            if (packageName != null && !packageName.trim().isEmpty()) {
                metadata.put("my_sc_pkg_name", packageName.trim());
            }
            if (versionCode != null) {
                metadata.put("sc_ver_code", String.valueOf(versionCode));
            }
            if (versionName != null && !versionName.trim().isEmpty()) {
                metadata.put("sc_ver_name", versionName.trim());
            }
            lC.b(scId, metadata);
        }

        ProjectSettings ps = new ProjectSettings(scId);
        if (minSdk != null) {
            ps.setValue(ProjectSettings.SETTING_MINIMUM_SDK_VERSION,
                    String.valueOf(minSdk));
        }
        if (targetSdk != null) {
            ps.setValue(ProjectSettings.SETTING_TARGET_SDK_VERSION,
                    String.valueOf(targetSdk));
        }
        if (applicationClass != null) {
            ps.setValue(ProjectSettings.SETTING_APPLICATION_CLASS,
                    applicationClass.trim());
        }
        if (enableViewBinding != null) {
            ps.setValue(ProjectSettings.SETTING_ENABLE_VIEWBINDING,
                    String.valueOf(enableViewBinding));
        }
        if (enableBridgelessThemes != null) {
            ps.setValue(ProjectSettings.SETTING_ENABLE_BRIDGELESS_THEMES,
                    String.valueOf(enableBridgelessThemes));
        }
        if (disableOldMethods != null) {
            ps.setValue(ProjectSettings.SETTING_DISABLE_OLD_METHODS,
                    String.valueOf(disableOldMethods));
        }

        JsonObject out = new JsonObject();
        out.add("project_metadata", inspectProjectMetadata(scId));
        out.add("project_settings", inspectProjectSettings(scId));
        return out;
    }

    // ------------------------------------------------------------------
    // 5. ProGuard / R8 & StringFog Configuration
    // ------------------------------------------------------------------

    public static JsonObject configureShrinkingAndObfuscation(
            String scId, Boolean proguardEnabled, Boolean r8Enabled,
            Boolean debugFilesEnabled, String proguardRules,
            List<String> fullModeLibs, Boolean stringFogEnabled)
            throws IOException {
        SketchwareActions.backupProjectOnce(scId);
        ProguardHandler pg = new ProguardHandler(scId);
        if (proguardEnabled != null) {
            pg.setProguardEnabled(proguardEnabled);
        }
        if (r8Enabled != null) {
            pg.setR8Enabled(r8Enabled);
        }
        if (debugFilesEnabled != null) {
            pg.setDebugEnabled(debugFilesEnabled);
        }
        if (proguardRules != null) {
            String rulesPath = FileUtil.getExternalStorageDir()
                    + "/.sketchware/data/" + scId + "/proguard-rules.pro";
            FileUtil.writeFile(rulesPath, proguardRules);
        }
        if (fullModeLibs != null) {
            pg.setProguardFMLibs(new ArrayList<>(fullModeLibs));
        }
        if (stringFogEnabled != null) {
            StringfogHandler sf = new StringfogHandler(scId);
            sf.setStringfogEnabled(stringFogEnabled);
        }

        JsonObject res = new JsonObject();
        res.add("proguard_and_r8", inspectProguard(scId));
        res.add("stringfog", inspectStringFog(scId));
        return res;
    }

    public static List<String> jsonArrayStrings(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = args.get(key);
        ArrayList<String> out = new ArrayList<>();
        if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item != null && !item.isJsonNull()) {
                    out.add(item.getAsString());
                }
            }
            return out;
        }
        if (el.isJsonPrimitive()) {
            String s = el.getAsString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
            return out;
        }
        return null;
    }
}
