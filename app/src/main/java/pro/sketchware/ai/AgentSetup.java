package pro.sketchware.ai;

import android.os.Environment;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.besome.sketch.beans.AdTestDeviceBean;
import com.besome.sketch.beans.AdUnitBean;
import com.besome.sketch.beans.ComponentBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ProjectLibraryBean;
import com.besome.sketch.beans.ViewBean;
import com.besome.sketch.editor.manage.library.material3.Material3LibraryManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.cosmic.ide.dependency.resolver.api.Artifact;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.eC;
import a.a.a.jC;
import dev.aldi.sayuti.editor.manage.LocalLibrariesUtil;
import dev.aldi.sayuti.editor.manage.LocalLibrary;
import mod.agus.jcoderz.beans.ViewBeans;
import mod.agus.jcoderz.editor.manage.permission.ListPermission;
import mod.hey.studios.build.BuildSettings;
import mod.jbk.build.BuiltInLibraries;
import mod.pranav.dependency.resolver.DependencyResolver;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileResConfig;
import pro.sketchware.utility.FileUtil;

/**
 * Project-wide setup outside individual screens: built-in libraries
 * (AppCompat, Material 3, Firebase, AdMob, Google Maps), local and
 * Maven libraries, excluded built-in libraries, Android permissions,
 * automatic prerequisite checks, extra project files, and the last
 * compile log.
 *
 * <p>All reads and writes go through Sketchware Pro's own storage
 * classes ({@code jC.c(sc_id)}, {@link Material3LibraryManager},
 * {@link LocalLibrariesUtil}, {@link DependencyResolver},
 * {@link BuiltInLibraries}, {@link FileResConfig}, {@link FilePathUtil})
 * so Agent Mode and the visual managers always share one source of truth.
 */
public final class AgentSetup {

    private AgentSetup() {
    }

    /** Built-in library keys. */
    public static final String ADMOB = "admob";
    public static final String APPCOMPAT = "appcompat";
    public static final String FIREBASE = "firebase";
    public static final String GOOGLE_MAPS = "google_maps";
    public static final String MATERIAL3 = "material3";

    private static final List<String> EXTRA_MODERN_PERMISSIONS = Arrays.asList(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE"
    );

    /**
     * Reads every built-in library setting of the project, preserving
     * top-level boolean keys ({@code admob}, {@code appcompat},
     * {@code firebase}, {@code google_maps}, {@code material3}) while
     * exposing structured configuration details.
     */
    public static JsonObject libraries(String scId) {
        JsonObject result = new JsonObject();
        if (scId == null || scId.isEmpty()) {
            return result;
        }
        try {
            ProjectLibraryBean admob = beanOrNew(
                    jC.c(scId).b(), ProjectLibraryBean.PROJECT_LIB_TYPE_ADMOB);
            ProjectLibraryBean compat = beanOrNew(
                    jC.c(scId).c(), ProjectLibraryBean.PROJECT_LIB_TYPE_COMPAT);
            ProjectLibraryBean firebase = beanOrNew(
                    jC.c(scId).d(), ProjectLibraryBean.PROJECT_LIB_TYPE_FIREBASE);
            ProjectLibraryBean maps = beanOrNew(
                    jC.c(scId).e(), ProjectLibraryBean.PROJECT_LIB_TYPE_GOOGLE_MAP);

            Material3LibraryManager m3 = new Material3LibraryManager(compat);
            boolean compatOn = enabled(compat);
            boolean m3On = compatOn && m3.isMaterial3Enabled();
            boolean m3Dynamic = m3On && m3.isDynamicColorsEnabled();
            String m3Theme = m3.getTheme();
            if (m3Theme == null || m3Theme.isEmpty()) {
                m3Theme = "DayNight";
            }

            result.addProperty(ADMOB, enabled(admob));
            result.addProperty(APPCOMPAT, compatOn);
            result.addProperty(MATERIAL3, m3On);
            result.addProperty("material3_dynamic_colors", m3Dynamic);
            result.addProperty("material3_theme", m3Theme);
            result.addProperty(FIREBASE, enabled(firebase));
            boolean fbAuthConfigured = enabled(firebase)
                    && firebase.reserved2 != null
                    && !firebase.reserved2.trim().isEmpty();
            result.addProperty("firebase_auth_configured", fbAuthConfigured);
            result.addProperty(GOOGLE_MAPS, enabled(maps));

            JsonObject fbDetails = new JsonObject();
            fbDetails.addProperty("enabled", enabled(firebase));
            fbDetails.addProperty("database_url", safeText(firebase.data));
            fbDetails.addProperty("app_id", safeText(firebase.reserved1));
            fbDetails.addProperty("api_key_configured",
                    !safeText(firebase.reserved2).isEmpty());
            fbDetails.addProperty("storage_bucket", safeText(firebase.reserved3));
            result.add("firebase_details", fbDetails);

            JsonObject admobDetails = new JsonObject();
            admobDetails.addProperty("enabled", enabled(admob));
            admobDetails.addProperty("app_id", safeText(admob.appId));
            admobDetails.addProperty("banner_unit", safeText(admob.reserved1));
            admobDetails.addProperty("interstitial_unit",
                    safeText(admob.reserved2));
            admobDetails.addProperty("reward_unit", safeText(admob.reserved3));
            admobDetails.addProperty("ad_units_count",
                    admob.adUnits == null ? 0 : admob.adUnits.size());
            admobDetails.addProperty("test_devices_count",
                    admob.testDevices == null ? 0 : admob.testDevices.size());
            result.add("admob_details", admobDetails);

            JsonObject mapsDetails = new JsonObject();
            mapsDetails.addProperty("enabled", enabled(maps));
            mapsDetails.addProperty("api_key_configured",
                    !safeText(maps.data).isEmpty());
            result.add("google_maps_details", mapsDetails);
        } catch (Throwable ignored) {
            // Unreadable library data reads as empty rather than crashing.
        }
        return result;
    }

    private static ProjectLibraryBean beanOrNew(ProjectLibraryBean bean,
                                                int libType) {
        return bean != null ? bean.clone() : new ProjectLibraryBean(libType);
    }

    private static boolean enabled(ProjectLibraryBean bean) {
        return bean != null && bean.isEnabled();
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Reads downloaded local libraries on device and which ones are
     * enabled in this project ({@code .sketchware/data/<sc_id>/local_library}).
     */
    public static JsonObject localLibraries(String scId) {
        JsonObject out = new JsonObject();
        JsonArray enabledArr = new JsonArray();
        Set<String> enabledNames = new LinkedHashSet<>();
        if (scId != null && !scId.isEmpty()) {
            try {
                ArrayList<HashMap<String, Object>> used =
                        LocalLibrariesUtil.getLocalLibraries(scId);
                if (used != null) {
                    for (HashMap<String, Object> item : used) {
                        if (item == null || item.get("name") == null) {
                            continue;
                        }
                        String name = String.valueOf(item.get("name")).trim();
                        if (name.isEmpty()) {
                            continue;
                        }
                        enabledNames.add(name);
                        JsonObject row = new JsonObject();
                        row.addProperty("name", name);
                        if (item.get("dependency") != null) {
                            row.addProperty("dependency",
                                    String.valueOf(item.get("dependency")));
                        }
                        if (item.get("packageName") != null) {
                            row.addProperty("package_name",
                                    String.valueOf(item.get("packageName"))
                                            .trim());
                        }
                        row.addProperty("has_dex", item.containsKey("dexPath"));
                        row.addProperty("has_jar", item.containsKey("jarPath"));
                        row.addProperty("has_res", item.containsKey("resPath"));
                        enabledArr.add(row);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        out.add("enabled", enabledArr);
        JsonArray availableArr = new JsonArray();
        try {
            for (LocalLibrary lib : LocalLibrariesUtil.getAllLocalLibraries()) {
                JsonObject row = new JsonObject();
                row.addProperty("name", lib.getName());
                row.addProperty("size", lib.getSize());
                row.addProperty("enabled",
                        enabledNames.contains(lib.getName()));
                availableArr.add(row);
            }
        } catch (Throwable ignored) {
        }
        out.add("available_on_device", availableArr);
        return out;
    }

    /**
     * Reads the Excluded Built-in Libraries configuration for a project
     * ({@code .sketchware/data/<sc_id>/excluded_library}).
     */
    public static JsonObject excludedBuiltInLibraries(String scId) {
        JsonObject out = new JsonObject();
        boolean active = false;
        JsonArray excluded = new JsonArray();
        if (scId != null && !scId.isEmpty()) {
            try {
                File file = excludedLibraryConfigFile(scId);
                if (file.isFile()) {
                    Pair<Boolean, List<String>> parsed = new Gson().fromJson(
                            FileUtil.readFile(file.getAbsolutePath()),
                            new TypeToken<Pair<Boolean, List<String>>>() {
                            }.getType());
                    if (parsed != null) {
                        active = Boolean.TRUE.equals(parsed.first);
                        if (parsed.second != null) {
                            for (String name : parsed.second) {
                                if (name != null && !name.trim().isEmpty()) {
                                    excluded.add(name.trim());
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        out.addProperty("excluding_enabled", active);
        out.add("excluded", excluded);
        out.addProperty("known_builtin_count",
                BuiltInLibraries.KNOWN_BUILT_IN_LIBRARIES.length);
        return out;
    }

    public static List<String> knownBuiltInLibraryNames() {
        List<String> names = new ArrayList<>();
        for (BuiltInLibraries.BuiltInLibrary lib
                : BuiltInLibraries.KNOWN_BUILT_IN_LIBRARIES) {
            names.add(lib.getName());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static File excludedLibraryConfigFile(String scId) {
        return new File(Environment.getExternalStorageDirectory(),
                ".sketchware" + File.separator + "data" + File.separator
                        + scId + File.separator + "excluded_library");
    }

    /**
     * The permissions this project declares through the Permission
     * Manager ({@code .sketchware/data/<id>/permission}), read with
     * the app's own {@link FileResConfig}.
     */
    public static JsonArray permissions(String scId) {
        JsonArray array = new JsonArray();
        if (scId == null || scId.isEmpty()) {
            return array;
        }
        try {
            List<String> declared =
                    new FileResConfig(scId).getPermissionList();
            if (declared != null) {
                for (String permission : declared) {
                    if (permission != null && !permission.trim().isEmpty()) {
                        array.add(permission.trim());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return array;
    }

    /** Returns all valid Android Manifest permission strings (optionally filtered). */
    public static List<String> availablePermissions(String filter) {
        Set<String> all = new LinkedHashSet<>(ListPermission.getPermissions());
        all.addAll(EXTRA_MODERN_PERMISSIONS);
        List<String> sorted = new ArrayList<>(all);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        if (filter == null || filter.trim().isEmpty()) {
            return sorted;
        }
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String perm : sorted) {
            if (perm.toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(perm);
            }
        }
        return matched;
    }

    /**
     * Normalizes a permission name ("INTERNET" or
     * "android.permission.INTERNET") and validates it against Android's
     * Manifest permissions. Returns null if unrecognized.
     */
    public static String normalizePermission(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String candidate = trimmed.contains(".")
                ? trimmed
                : "android.permission."
                        + trimmed.toUpperCase(Locale.ROOT);
        for (String known : availablePermissions("")) {
            if (known.equalsIgnoreCase(candidate)
                    || known.equalsIgnoreCase(trimmed)) {
                return known;
            }
        }
        if (candidate.matches("^android\\.permission\\.[A-Z0-9_]{3,80}$")) {
            return candidate;
        }
        return null;
    }

    /**
     * Audits the project's screens, widgets, components, and enabled
     * libraries to detect missing Android permissions or disabled
     * AppCompat when Material/AndroidX widgets are placed.
     */
    public static JsonObject auditPrerequisites(String scId) {
        JsonObject audit = new JsonObject();
        if (scId == null || scId.isEmpty()) {
            return audit;
        }
        JsonObject libs = libraries(scId);
        boolean compatOn = libs.has(APPCOMPAT)
                && libs.get(APPCOMPAT).getAsBoolean();
        Set<String> declared = new LinkedHashSet<>();
        for (JsonElement el : permissions(scId)) {
            declared.add(el.getAsString());
        }
        Map<String, List<String>> permReasons = new LinkedHashMap<>();
        List<String> appCompatWidgets = new ArrayList<>();

        if (libs.has(FIREBASE) && libs.get(FIREBASE).getAsBoolean()) {
            requirePerm(permReasons, "android.permission.INTERNET",
                    "Firebase library enabled");
            requirePerm(permReasons, "android.permission.ACCESS_NETWORK_STATE",
                    "Firebase library enabled");
        }
        if (libs.has(ADMOB) && libs.get(ADMOB).getAsBoolean()) {
            requirePerm(permReasons, "android.permission.INTERNET",
                    "AdMob library enabled");
            requirePerm(permReasons, "android.permission.ACCESS_NETWORK_STATE",
                    "AdMob library enabled");
        }
        if (libs.has(GOOGLE_MAPS) && libs.get(GOOGLE_MAPS).getAsBoolean()) {
            requirePerm(permReasons, "android.permission.INTERNET",
                    "Google Maps library enabled");
            requirePerm(permReasons, "android.permission.ACCESS_NETWORK_STATE",
                    "Google Maps library enabled");
        }

        try {
            List<ProjectFileBean> allLayouts = new ArrayList<>();
            allLayouts.addAll(SketchwareProjects.activities(scId));
            allLayouts.addAll(SketchwareProjects.customViews(scId));
            eC store = jC.a(scId);
            for (ProjectFileBean file : allLayouts) {
                for (ViewBean bean
                        : SketchwareProjects.screenBeans(scId, file)) {
                    if (!compatOn && needsAppCompat(bean.type)) {
                        appCompatWidgets.add(file.fileName + "#" + bean.id);
                    }
                    if (bean.type == ViewBean.VIEW_TYPE_WIDGET_WEBVIEW) {
                        requirePerm(permReasons,
                                "android.permission.INTERNET",
                                "WebView " + file.fileName + "#" + bean.id);
                        requirePerm(permReasons,
                                "android.permission.ACCESS_NETWORK_STATE",
                                "WebView " + file.fileName + "#" + bean.id);
                    }
                }
                if (file.fileType == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                    List<ComponentBean> comps = store.e(file.getJavaName());
                    if (comps != null) {
                        for (ComponentBean comp : comps) {
                            String where = file.getJavaName() + ":"
                                    + comp.componentId;
                            switch (comp.type) {
                                case ComponentBean.COMPONENT_TYPE_REQUEST_NETWORK -> {
                                    requirePerm(permReasons,
                                            "android.permission.INTERNET",
                                            "RequestNetwork " + where);
                                    requirePerm(permReasons,
                                            "android.permission.ACCESS_NETWORK_STATE",
                                            "RequestNetwork " + where);
                                }
                                case ComponentBean.COMPONENT_TYPE_CAMERA ->
                                        requirePerm(permReasons,
                                                "android.permission.CAMERA",
                                                "Camera " + where);
                                case ComponentBean.COMPONENT_TYPE_SPEECH_TO_TEXT ->
                                        requirePerm(permReasons,
                                                "android.permission.RECORD_AUDIO",
                                                "SpeechToText " + where);
                                case ComponentBean.COMPONENT_TYPE_VIBRATOR ->
                                        requirePerm(permReasons,
                                                "android.permission.VIBRATE",
                                                "Vibrator " + where);
                                case ComponentBean.COMPONENT_TYPE_LOCATION_MANAGER -> {
                                    requirePerm(permReasons,
                                            "android.permission.ACCESS_FINE_LOCATION",
                                            "LocationManager " + where);
                                    requirePerm(permReasons,
                                            "android.permission.ACCESS_COARSE_LOCATION",
                                            "LocationManager " + where);
                                }
                                case ComponentBean.COMPONENT_TYPE_BLUETOOTH_CONNECT -> {
                                    requirePerm(permReasons,
                                            "android.permission.BLUETOOTH",
                                            "BluetoothConnect " + where);
                                    requirePerm(permReasons,
                                            "android.permission.BLUETOOTH_ADMIN",
                                            "BluetoothConnect " + where);
                                }
                                default -> {
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        audit.addProperty("appcompat_needed_but_off",
                !appCompatWidgets.isEmpty());
        JsonArray compatArr = new JsonArray();
        for (String w : appCompatWidgets) {
            compatArr.add(w);
        }
        audit.add("widgets_needing_appcompat", compatArr);

        JsonArray missing = new JsonArray();
        JsonObject missingReasons = new JsonObject();
        for (Map.Entry<String, List<String>> entry : permReasons.entrySet()) {
            if (!declared.contains(entry.getKey())) {
                missing.add(entry.getKey());
                missingReasons.addProperty(entry.getKey(),
                        String.join(", ", entry.getValue()));
            }
        }
        audit.add("missing_permissions", missing);
        audit.add("missing_permission_reasons", missingReasons);
        return audit;
    }

    private static void requirePerm(Map<String, List<String>> map,
                                    String perm, String reason) {
        List<String> list = map.computeIfAbsent(perm, k -> new ArrayList<>());
        if (!list.contains(reason)) {
            list.add(reason);
        }
    }

    // ------------------------------------------------------------------
    // Phase 1 Write Helpers (mirroring ManageLibraryActivity,
    // Material3LibraryActivity, ManagePermissionActivity,
    // ManageLocalLibraryActivity, ExcludeBuiltInLibrariesActivity, and
    // LibraryDownloaderDialogFragment)

    /**
     * Saves all four ProjectLibraryBeans using the exact sequence of
     * {@code ManageLibraryActivity.saveLibraryConfiguration()}.
     */
    public static synchronized void saveBuiltInLibraries(
            String scId,
            ProjectLibraryBean compat,
            ProjectLibraryBean firebase,
            ProjectLibraryBean admob,
            ProjectLibraryBean googleMap) throws IOException {
        SketchwareActions.backupProjectOnce(scId);
        jC.c(scId).b(compat);
        jC.c(scId).c(firebase);
        jC.c(scId).a(admob);
        jC.c(scId).d(googleMap);
        jC.c(scId).k();
        jC.b(scId).a(jC.c(scId));
        jC.a(scId).a(jC.b(scId));
        jC.a(scId).a(firebase);
        jC.a(scId).a(admob, jC.b(scId));
        jC.a(scId).b(googleMap, jC.b(scId));
    }

    /** Current cloned library beans {compat, firebase, admob, googleMap}. */
    public static ProjectLibraryBean[] loadLibraryBeans(String scId) {
        ProjectLibraryBean compat = beanOrNew(
                jC.c(scId).c(), ProjectLibraryBean.PROJECT_LIB_TYPE_COMPAT);
        ProjectLibraryBean firebase = beanOrNew(
                jC.c(scId).d(), ProjectLibraryBean.PROJECT_LIB_TYPE_FIREBASE);
        ProjectLibraryBean admob = beanOrNew(
                jC.c(scId).b(), ProjectLibraryBean.PROJECT_LIB_TYPE_ADMOB);
        ProjectLibraryBean maps = beanOrNew(
                jC.c(scId).e(), ProjectLibraryBean.PROJECT_LIB_TYPE_GOOGLE_MAP);
        if (compat.configurations == null) {
            compat.configurations = new HashMap<>();
        }
        if (admob.adUnits == null) {
            admob.adUnits = new ArrayList<>();
        }
        if (admob.testDevices == null) {
            admob.testDevices = new ArrayList<>();
        }
        return new ProjectLibraryBean[]{compat, firebase, admob, maps};
    }

    /**
     * Parses a {@code google-services.json} string into
     * {@code {database_url, app_id, api_key, storage_bucket}}, combining
     * structured JSON parsing with {@code ManageFirebaseActivity}'s regexes.
     */
    public static Map<String, String> parseGoogleServicesJson(String rawJson) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            if (root.has("project_info")
                    && root.get("project_info").isJsonObject()) {
                JsonObject info = root.getAsJsonObject("project_info");
                if (info.has("firebase_url")) {
                    out.put("database_url", stripHttps(
                            info.get("firebase_url").getAsString()));
                } else if (info.has("project_id")) {
                    String pid = info.get("project_id").getAsString().trim();
                    if (!pid.isEmpty()) {
                        out.put("database_url",
                                pid + "-default-rtdb.firebaseio.com");
                    }
                }
                if (info.has("storage_bucket")) {
                    out.put("storage_bucket",
                            info.get("storage_bucket").getAsString().trim());
                }
            }
            if (root.has("client") && root.get("client").isJsonArray()
                    && root.getAsJsonArray("client").size() > 0) {
                JsonObject client = root.getAsJsonArray("client")
                        .get(0).getAsJsonObject();
                if (client.has("client_info")
                        && client.get("client_info").isJsonObject()) {
                    JsonObject ci = client.getAsJsonObject("client_info");
                    if (ci.has("mobilesdk_app_id")) {
                        out.put("app_id",
                                ci.get("mobilesdk_app_id")
                                        .getAsString().trim());
                    }
                }
                if (client.has("api_key")
                        && client.get("api_key").isJsonArray()
                        && client.getAsJsonArray("api_key").size() > 0) {
                    JsonObject keyObj = client.getAsJsonArray("api_key")
                            .get(0).getAsJsonObject();
                    if (keyObj.has("current_key")) {
                        out.put("api_key",
                                keyObj.get("current_key")
                                        .getAsString().trim());
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to ManageFirebaseActivity regexes.
        }
        matchIfMissing(out, "storage_bucket",
                "\"storage_bucket\":\\s*\"([^\"]+)\"", rawJson, false);
        matchIfMissing(out, "app_id",
                "\"mobilesdk_app_id\":\\s*\"([^\"]+)\"", rawJson, false);
        matchIfMissing(out, "api_key",
                "\"current_key\":\\s*\"([^\"]+)\"", rawJson, false);
        matchIfMissing(out, "database_url",
                "\"firebase_url\":\\s*\"([^\"]+)\"", rawJson, true);
        return out;
    }

    private static void matchIfMissing(Map<String, String> out, String key,
                                       String regex, String src,
                                       boolean stripScheme) {
        if (out.containsKey(key) && !out.get(key).isEmpty()) {
            return;
        }
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(src);
        if (m.find()) {
            String v = m.group(1).trim();
            out.put(key, stripScheme ? stripHttps(v) : v);
        }
    }

    public static String stripHttps(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        if (u.startsWith("https://")) {
            u = u.substring("https://".length());
        } else if (u.startsWith("http://")) {
            u = u.substring("http://".length());
        }
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Formats an AdMob unit assignment as {@code "Name : id"} and registers it in {@code adUnits}. */
    public static String registerAdUnit(ProjectLibraryBean admob,
                                        String unitName, String unitId) {
        if (unitId == null || unitId.trim().isEmpty()) {
            return "";
        }
        String raw = unitId.trim();
        String parsedName = unitName == null ? "" : unitName.trim();
        String parsedId = raw;
        if (raw.contains(" : ")) {
            int sep = raw.indexOf(" : ");
            if (parsedName.isEmpty()) {
                parsedName = raw.substring(0, sep).trim();
            }
            parsedId = raw.substring(sep + 3).trim();
        }
        if (parsedName.isEmpty()) {
            parsedName = "AdUnit";
        }
        boolean found = false;
        if (admob.adUnits == null) {
            admob.adUnits = new ArrayList<>();
        }
        for (AdUnitBean existing : admob.adUnits) {
            if (parsedId.equals(existing.id)) {
                existing.name = parsedName;
                found = true;
                break;
            }
        }
        if (!found) {
            admob.adUnits.add(new AdUnitBean(parsedId, parsedName));
        }
        return parsedName + " : " + parsedId;
    }

    public static void replaceTestDevices(ProjectLibraryBean admob,
                                          List<String> deviceIds) {
        if (admob.testDevices == null) {
            admob.testDevices = new ArrayList<>();
        } else {
            admob.testDevices.clear();
        }
        if (deviceIds == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String id : deviceIds) {
            if (id != null && !id.trim().isEmpty() && seen.add(id.trim())) {
                admob.testDevices.add(new AdTestDeviceBean(id.trim()));
            }
        }
    }

    /**
     * Updates the project's permission file
     * ({@code .sketchware/data/<sc_id>/permission}) identically to
     * {@code ManagePermissionActivity}.
     */
    public static synchronized List<String> updatePermissions(
            String scId, List<String> toAdd, List<String> toRemove)
            throws IOException {
        SketchwareActions.backupProjectOnce(scId);
        Set<String> current = new LinkedHashSet<>();
        List<String> existing = new FileResConfig(scId).getPermissionList();
        if (existing != null) {
            for (String p : existing) {
                if (p != null && !p.trim().isEmpty()) {
                    current.add(p.trim());
                }
            }
        }
        if (toRemove != null) {
            for (String p : toRemove) {
                if (p != null) {
                    current.remove(p.trim());
                }
            }
        }
        if (toAdd != null) {
            for (String p : toAdd) {
                if (p != null && !p.trim().isEmpty()) {
                    current.add(p.trim());
                }
            }
        }
        List<String> sorted = new ArrayList<>(current);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        String path = new FilePathUtil().getPathPermission(scId);
        FileUtil.writeFile(path, new Gson().toJson(sorted));
        return sorted;
    }

    /**
     * Enables and/or disables downloaded local libraries for a project
     * identically to {@code ManageLocalLibraryActivity}.
     */
    public static synchronized List<String> updateLocalLibraries(
            String scId, List<String> enableNames, List<String> disableNames)
            throws IOException {
        SketchwareActions.backupProjectOnce(scId);
        ArrayList<HashMap<String, Object>> used =
                LocalLibrariesUtil.getLocalLibraries(scId);
        if (used == null) {
            used = new ArrayList<>();
        }
        if (disableNames != null && !disableNames.isEmpty()) {
            Set<String> off = new LinkedHashSet<>(disableNames);
            used.removeIf(map -> map != null && map.get("name") != null
                    && off.contains(String.valueOf(map.get("name"))));
        }
        if (enableNames != null) {
            for (String name : enableNames) {
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                String clean = name.trim();
                boolean already = false;
                String existingDep = null;
                for (HashMap<String, Object> map : used) {
                    if (map != null
                            && clean.equals(String.valueOf(map.get("name")))) {
                        already = true;
                        if (map.get("dependency") instanceof String dep) {
                            existingDep = dep;
                        }
                        break;
                    }
                }
                if (!already) {
                    used.add(LocalLibrariesUtil.createLibraryMap(
                            clean, existingDep));
                }
            }
        }
        LocalLibrariesUtil.rewriteLocalLibFile(scId, new Gson().toJson(used));
        List<String> enabledNow = new ArrayList<>();
        for (HashMap<String, Object> map : used) {
            if (map != null && map.get("name") != null) {
                enabledNow.add(String.valueOf(map.get("name")));
            }
        }
        return enabledNow;
    }

    /**
     * Updates {@code .sketchware/data/<sc_id>/excluded_library}
     * identically to {@code ExcludeBuiltInLibrariesActivity}.
     */
    public static synchronized Pair<Boolean, List<String>> updateExcludedBuiltInLibraries(
            String scId, Boolean excludingEnabled,
            List<String> addExcluded, List<String> removeExcluded)
            throws IOException {
        SketchwareActions.backupProjectOnce(scId);
        JsonObject current = excludedBuiltInLibraries(scId);
        boolean active = excludingEnabled != null
                ? excludingEnabled
                : (current.has("excluding_enabled")
                        && current.get("excluding_enabled").getAsBoolean());
        Set<String> names = new LinkedHashSet<>();
        if (current.has("excluded")) {
            for (JsonElement el : current.getAsJsonArray("excluded")) {
                names.add(el.getAsString());
            }
        }
        if (removeExcluded != null) {
            for (String r : removeExcluded) {
                if (r != null) {
                    names.remove(r.trim());
                }
            }
        }
        if (addExcluded != null) {
            for (String a : addExcluded) {
                if (a == null || a.trim().isEmpty()) {
                    continue;
                }
                Optional<BuiltInLibraries.BuiltInLibrary> match =
                        BuiltInLibraries.BuiltInLibrary.ofName(a.trim());
                if (match.isPresent()) {
                    names.add(match.get().getName());
                }
            }
        }
        List<String> list = new ArrayList<>(names);
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        Pair<Boolean, List<String>> payload = new Pair<>(active, list);
        File file = excludedLibraryConfigFile(scId);
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
        }
        FileUtil.writeFile(file.getAbsolutePath(), new Gson().toJson(payload));
        return payload;
    }

    /**
     * Downloads, unzips, dexes (via D8), and enables a Maven library
     * ({@code group:artifact:version}) using Sketchware Pro's
     * {@link DependencyResolver}.
     */
    public static JsonObject downloadMavenLibrary(
            String scId, String coordinate, boolean skipSubdependencies,
            boolean enableInProject) throws Exception {
        String[] parts = coordinate.trim().split(":");
        if (parts.length != 3 || parts[0].trim().isEmpty()
                || parts[1].trim().isEmpty() || parts[2].trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid Maven coordinate \"" + coordinate
                            + "\". Expected group:artifact:version "
                            + "(e.g. \"com.airbnb.android:lottie:6.4.0\").");
        }
        if (enableInProject) {
            SketchwareActions.backupProjectOnce(scId);
        }
        BuiltInLibraries.maybeExtractAndroidJar((message, progress) -> {
        });
        BuiltInLibraries.maybeExtractCoreLambdaStubsJar();

        BuildSettings buildSettings = new BuildSettings(scId);
        DependencyResolver resolver = new DependencyResolver(
                parts[0].trim(), parts[1].trim(), parts[2].trim(),
                skipSubdependencies, buildSettings);

        List<String> downloadedNames = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        resolver.resolveDependency(
                new DependencyResolver.DependencyResolverCallback() {
                    @Override
                    public void onArtifactNotFound(@NonNull Artifact artifact) {
                        errors.add("Artifact not found: " + artifact);
                    }

                    @Override
                    public void onVersionNotFound(@NonNull Artifact artifact) {
                        errors.add("Version not available for: " + artifact);
                    }

                    @Override
                    public void onResolutionError(@NonNull Exception error) {
                        errors.add("Resolution error: "
                                + String.valueOf(error.getMessage()));
                    }

                    @Override
                    public void onDownloadError(@NonNull Artifact artifact,
                                                @NonNull Throwable error) {
                        errors.add("Download failed for " + artifact + ": "
                                + String.valueOf(error.getMessage()));
                    }

                    @Override
                    public void dexingFailed(@NonNull Artifact artifact,
                                             @NonNull Exception e) {
                        errors.add("Dexing failed for " + artifact + ": "
                                + String.valueOf(e.getMessage()));
                    }

                    @Override
                    public void invalidPackaging(@NonNull Artifact artifact) {
                        errors.add("Unsupported packaging for: " + artifact);
                    }

                    @Override
                    public void onTaskCompleted(
                            @NonNull List<String> artifacts) {
                        downloadedNames.addAll(artifacts);
                    }
                });

        if (downloadedNames.isEmpty() && !errors.isEmpty()) {
            throw new IOException(String.join("; ", errors));
        }

        if (enableInProject && !downloadedNames.isEmpty()) {
            File localLibFile = LocalLibrariesUtil.getLocalLibFile(scId);
            ArrayList<HashMap<String, Object>> enabledLibs =
                    LocalLibrariesUtil.getLocalLibraries(scId);
            if (enabledLibs == null) {
                enabledLibs = new ArrayList<>();
            }
            Set<String> alreadyEnabled = new LinkedHashSet<>();
            for (HashMap<String, Object> entry : enabledLibs) {
                if (entry != null && entry.get("name") != null) {
                    alreadyEnabled.add(String.valueOf(entry.get("name")));
                }
            }
            for (String libName : downloadedNames) {
                if (!alreadyEnabled.contains(libName)) {
                    enabledLibs.add(LocalLibrariesUtil.createLibraryMap(
                            libName, coordinate.trim()));
                    alreadyEnabled.add(libName);
                }
            }
            FileUtil.writeFile(localLibFile.getAbsolutePath(),
                    new Gson().toJson(enabledLibs));
        }

        JsonObject result = new JsonObject();
        result.addProperty("coordinate", coordinate.trim());
        result.addProperty("skip_subdependencies", skipSubdependencies);
        result.addProperty("enabled_in_project", enableInProject);
        JsonArray libsArr = new JsonArray();
        for (String n : downloadedNames) {
            libsArr.add(n);
        }
        result.add("downloaded_libraries", libsArr);
        if (!errors.isEmpty()) {
            JsonArray warnArr = new JsonArray();
            for (String err : errors) {
                warnArr.add(err);
            }
            result.add("warnings", warnArr);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Files, compile log, and widget/component requirements

    public static JsonObject files(String scId) {
        JsonObject result = new JsonObject();
        if (scId == null || scId.isEmpty()) {
            return result;
        }
        try {
            FileResConfig config = new FileResConfig(scId);
            FilePathUtil paths = new FilePathUtil();
            result.add("assets", names(config.getAssetsFile()));
            result.add("java", names(config.getJavaFile()));
            result.add("resources",
                    names(config.getResourceFile(
                            paths.getPathResource(scId))));
            result.add("native_libs",
                    names(config.getNativelibsFile(
                            paths.getPathNativelibs(scId))));
            result.add("imports",
                    names(config.getImportList()));
            result.addProperty("proguard_rules",
                    new File(paths.getPathProguard(scId)).exists());
        } catch (Throwable ignored) {
        }
        return result;
    }

    public static String compileLogTail(String scId, int maxChars) {
        if (scId == null || scId.isEmpty()) {
            return "";
        }
        try {
            File file = new File(
                    FilePathUtil.getLastCompileLogPath(scId));
            if (!file.exists() || file.length() == 0) {
                return "";
            }
            long skip = Math.max(0, file.length() - 4000);
            try (RandomAccessFile reader = new RandomAccessFile(file, "r")) {
                reader.seek(skip);
                byte[] buffer = new byte[(int) (file.length() - skip)];
                int read = reader.read(buffer);
                String text = new String(buffer, 0, Math.max(0, read),
                        StandardCharsets.UTF_8);
                if (skip > 0) {
                    int cut = text.indexOf('\n');
                    text = cut >= 0 ? text.substring(cut + 1) : text;
                }
                if (text.length() > maxChars) {
                    text = text.substring(text.length() - maxChars);
                }
                return text.trim();
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String requirementForWidget(int type) {
        if (type == ViewBean.VIEW_TYPE_WIDGET_ADVIEW) {
            return ADMOB;
        }
        if (type == ViewBean.VIEW_TYPE_WIDGET_MAPVIEW) {
            return GOOGLE_MAPS;
        }
        return null;
    }

    public static String requirementForComponent(int componentType) {
        return switch (componentType) {
            case ComponentBean.COMPONENT_TYPE_FIREBASE,
                    ComponentBean.COMPONENT_TYPE_FIREBASE_STORAGE,
                    ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH,
                    ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_PHONE,
                    ComponentBean.COMPONENT_TYPE_FIREBASE_CLOUD_MESSAGE,
                    ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_GOOGLE_LOGIN ->
                    FIREBASE;
            case ComponentBean.COMPONENT_TYPE_INTERSTITIAL_AD,
                    ComponentBean.COMPONENT_TYPE_REWARDED_VIDEO_AD -> ADMOB;
            case ComponentBean.COMPONENT_TYPE_FRAGMENT_ADAPTER -> APPCOMPAT;
            default -> null;
        };
    }

    public static boolean needsAppCompat(int viewType) {
        return switch (viewType) {
            case ViewBeans.VIEW_TYPE_WIDGET_MATERIALBUTTON,
                    ViewBeans.VIEW_TYPE_WIDGET_RECYCLERVIEW,
                    ViewBeans.VIEW_TYPE_LAYOUT_BOTTOMNAVIGATIONVIEW,
                    ViewBeans.VIEW_TYPE_LAYOUT_TABLAYOUT,
                    ViewBeans.VIEW_TYPE_LAYOUT_VIEWPAGER,
                    ViewBeans.VIEW_TYPE_LAYOUT_COLLAPSINGTOOLBARLAYOUT,
                    ViewBeans.VIEW_TYPE_LAYOUT_TEXTINPUTLAYOUT,
                    ViewBeans.VIEW_TYPE_LAYOUT_SWIPEREFRESHLAYOUT,
                    ViewBeans.VIEW_TYPE_LAYOUT_CARDVIEW -> true;
            default -> false;
        };
    }

    public static boolean needsFirebaseAuthSetup(int componentType) {
        return componentType == ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH
                || componentType == ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_PHONE
                || componentType == ComponentBean.COMPONENT_TYPE_FIREBASE_AUTH_GOOGLE_LOGIN;
    }

    public static String libraryName(String key) {
        return switch (String.valueOf(key)) {
            case ADMOB -> "AdMob";
            case APPCOMPAT -> "AppCompat / Material";
            case MATERIAL3 -> "Material 3";
            case FIREBASE -> "Firebase";
            case GOOGLE_MAPS -> "Google Maps";
            default -> String.valueOf(key);
        };
    }

    public static String requirementMessage(String key, String what,
                                            boolean firebaseAuth) {
        if (FIREBASE.equals(key) && firebaseAuth) {
            return "In order to use " + what + ", Firebase must be enabled "
                    + "AND its API key configured. Ask the user for their "
                    + "google-services.json or Firebase credentials, then "
                    + "call configure_library with library=\"firebase\".";
        }
        if (APPCOMPAT.equals(key)) {
            return "In order to use " + what + ", AppCompat must be switched "
                    + "on first. Call configure_library with "
                    + "library=\"appcompat\" and enabled=true, then add "
                    + what + ".";
        }
        return "In order to use " + what + ", " + libraryName(key)
                + " must be configured first. Ask the user for any required "
                + libraryName(key) + " IDs/keys if you don't have them, "
                + "then call configure_library with library=\"" + key + "\".";
    }

    private static JsonArray names(List<String> values) {
        JsonArray array = new JsonArray();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            String name = value;
            int slash = Math.max(name.lastIndexOf('/'),
                    name.lastIndexOf(File.separatorChar));
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            if (!name.isEmpty()) {
                array.add(name);
            }
        }
        return array;
    }
}
