package pro.sketchware.ai;

import com.besome.sketch.beans.ProjectLibraryBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.List;

import a.a.a.jC;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileResConfig;

/**
 * What a project has been <em>set up</em> for — the part of a project
 * that lives outside its screens: which libraries are switched on in
 * the Library Manager, which permissions are declared, and which
 * extra files (assets, Java, resources, native libraries) the project
 * carries.
 *
 * <p>This matters because Sketchware refuses some widgets and
 * components until their library is configured. The editor says so
 * itself ({@code design_library_admob_widget_setup_first}: "In order
 * to use this widget, you have to setup the settings in the Library
 * Manager first") and checks it in its own add paths —
 * {@code ViewEditor} for AdView and MapView,
 * {@code InnerAddComponentBottomSheet} for Firebase, AdMob and
 * FragmentAdapter components. The agent used to skip that check
 * entirely, and produced screens that could never work.
 *
 * <p>Everything here is read through the app's own classes
 * ({@code jC.c(sc_id)}, {@code FileResConfig}, {@code FilePathUtil}) —
 * the same objects the editor reads — so this cannot drift from what
 * the app itself believes.
 */
public final class AgentSetup {

    private AgentSetup() {
    }

    /** The four libraries the editor gates behaviour on. */
    private static final String ADMOB = "admob";
    private static final String APPCOMPAT = "appcompat";
    private static final String FIREBASE = "firebase";
    private static final String GOOGLE_MAPS = "google_maps";

    /**
     * Which libraries this project has switched on, read the way the
     * Library Manager reads them ({@code jC.c(sc_id)}: AdMob
     * {@code .b()}, AppCompat {@code .c()}, Firebase {@code .d()},
     * Google Maps {@code .e()} — the mapping ManageLibraryActivity
     * uses at lines 355-373). Empty when the project's library data
     * cannot be read; a check must never block the work it checks.
     */
    public static JsonObject libraries(String scId) {
        JsonObject result = new JsonObject();
        if (scId == null || scId.isEmpty()) {
            return result;
        }
        try {
            // The editor guards these beans against null itself
            // (ManageLibraryActivity: a project that never opened the
            // Library Manager has none yet), so we do too: a missing
            // bean reads as "not switched on".
            result.addProperty(ADMOB, enabled(jC.c(scId).b()));
            result.addProperty(APPCOMPAT, enabled(jC.c(scId).c()));
            ProjectLibraryBean firebase = jC.c(scId).d();
            result.addProperty(FIREBASE, enabled(firebase));
            // Firebase Auth additionally needs its own settings: the
            // editor refuses the component with a separate message
            // when the API key slot is empty.
            result.addProperty("firebase_auth_configured",
                    firebase.isEnabled()
                            && firebase.reserved2 != null
                            && !firebase.reserved2.trim().isEmpty());
            result.addProperty(GOOGLE_MAPS, enabled(jC.c(scId).e()));
        } catch (Throwable ignored) {
            // Unreadable library data reads as "not configured", and
            // the report says so rather than pretending.
        }
        return result;
    }

    /** A library bean counts as on only when it exists and says so. */
    private static boolean enabled(ProjectLibraryBean bean) {
        return bean != null && bean.isEnabled();
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
            // Unreadable permissions read as "none declared".
        }
        return array;
    }

    /**
     * The extra files the project carries: assets, Java/Kotlin,
     * resources, native libraries and imports — the things the Asset,
     * Java, Resource and Native Library managers put there.
     */
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
            // An unreadable folder reads as "nothing there".
        }
        return result;
    }

    /**
     * The tail of the project's last compile log, if there is one:
     * what the compiler said the last time this project was built,
     * whether by the agent or by the Build button.
     */
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
            try (java.io.RandomAccessFile reader =
                         new java.io.RandomAccessFile(file, "r")) {
                reader.seek(skip);
                byte[] buffer = new byte[(int) (file.length() - skip)];
                int read = reader.read(buffer);
                String text = new String(buffer, 0, Math.max(0, read),
                        java.nio.charset.StandardCharsets.UTF_8);
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

    /**
     * What must be switched on before a widget of this type can work,
     * or null when nothing is required. Only the two the editor's own
     * view editor refuses: AdView and MapView.
     */
    public static String requirementForWidget(int type) {
        if (type == com.besome.sketch.beans.ViewBean
                .VIEW_TYPE_WIDGET_ADVIEW) {
            return ADMOB;
        }
        if (type == com.besome.sketch.beans.ViewBean
                .VIEW_TYPE_WIDGET_MAPVIEW) {
            return GOOGLE_MAPS;
        }
        return null;
    }

    /**
     * What must be switched on before a component of this type can
     * work, or null when nothing is required. Mirrors the editor's
     * own component picker: Firebase types on Firebase, ad types on
     * AdMob, FragmentAdapter on AppCompat.
     */
    public static String requirementForComponent(int componentType) {
        return switch (componentType) {
            case com.besome.sketch.beans.ComponentBean.COMPONENT_TYPE_FIREBASE,
                    com.besome.sketch.beans.ComponentBean
                            .COMPONENT_TYPE_FIREBASE_STORAGE,
                    com.besome.sketch.beans.ComponentBean
                            .COMPONENT_TYPE_FIREBASE_AUTH,
                    com.besome.sketch.beans.ComponentBean
                            .COMPONENT_TYPE_FIREBASE_AUTH_PHONE,
                    com.besome.sketch.beans.ComponentBean
                            .COMPONENT_TYPE_FIREBASE_CLOUD_MESSAGE,
                    com.besome.sketch.beans.ComponentBean
                            .COMPONENT_TYPE_FIREBASE_AUTH_GOOGLE_LOGIN ->
                    FIREBASE;
            case com.besome.sketch.beans.ComponentBean
                    .COMPONENT_TYPE_INTERSTITIAL_AD,
                    com.besome.sketch.beans.ComponentBean
                            .COMPONENT_TYPE_REWARDED_VIDEO_AD -> ADMOB;
            case com.besome.sketch.beans.ComponentBean
                    .COMPONENT_TYPE_FRAGMENT_ADAPTER -> APPCOMPAT;
            default -> null;
        };
    }

    /**
     * Widgets that live in the Material/AndroidX family — CardView,
     * RecyclerView, TabLayout and the rest. The editor lists them in
     * its AppCompat check ({@code ViewEditor.e()}), and the build
     * includes the AndroidX dependencies only when the AppCompat
     * switch is on ({@code yq}: {@code N.g = true}), so using one
     * with the switch off compiles into missing classes. The editor
     * does NOT refuse them on the drawing desk, so neither do we —
     * this is a warning, not a block (see {@link #APPCOMPAT}).
     */
    public static boolean needsAppCompat(int viewType) {
        return switch (viewType) {
            case mod.agus.jcoderz.beans.ViewBeans
                    .VIEW_TYPE_WIDGET_MATERIALBUTTON,
                    mod.agus.jcoderz.beans.ViewBeans
                            .VIEW_TYPE_WIDGET_RECYCLERVIEW,
                    mod.agus.jcoderz.beans.ViewBeans
                            .VIEW_TYPE_LAYOUT_BOTTOMNAVIGATIONVIEW,
                    mod.agus.jcoderz.beans.ViewBeans
                            .VIEW_TYPE_LAYOUT_TABLAYOUT,
                    mod.agus.jcoderz.beans.ViewBeans
                            .VIEW_TYPE_LAYOUT_VIEWPAGER,
                    mod.agus.jcoderz.beans.ViewBeans
                            .VIEW_TYPE_LAYOUT_COLLAPSINGTOOLBARLAYOUT,
                    mod.agus.jcoderz.beans.ViewBeans
                            .VIEW_TYPE_LAYOUT_TEXTINPUTLAYOUT,
                    mod.agus.jcoderz.beans.ViewBeans
                            .VIEW_TYPE_LAYOUT_SWIPEREFRESHLAYOUT,
                    mod.agus.jcoderz.beans.ViewBeans
                            .VIEW_TYPE_LAYOUT_CARDVIEW -> true;
            default -> false;
        };
    }

    /** Firebase Auth's own extra step: its API key slot. */
    public static boolean needsFirebaseAuthSetup(int componentType) {
        return componentType == com.besome.sketch.beans.ComponentBean
                .COMPONENT_TYPE_FIREBASE_AUTH
                || componentType == com.besome.sketch.beans.ComponentBean
                        .COMPONENT_TYPE_FIREBASE_AUTH_PHONE
                || componentType == com.besome.sketch.beans.ComponentBean
                        .COMPONENT_TYPE_FIREBASE_AUTH_GOOGLE_LOGIN;
    }

    /** Plain name of a library key, for messages. */
    public static String libraryName(String key) {
        return switch (String.valueOf(key)) {
            case ADMOB -> "AdMob";
            case APPCOMPAT -> "AppCompat / Material";
            case FIREBASE -> "Firebase";
            case GOOGLE_MAPS -> "Google Maps";
            default -> String.valueOf(key);
        };
    }

    /**
     * The refusal the editor itself shows, in words the user can act
     * on: which switch in the Library Manager has to be turned on.
     */
    public static String requirementMessage(String key, String what,
                                            boolean firebaseAuth) {
        if (FIREBASE.equals(key) && firebaseAuth) {
            return "In order to use " + what + ", Firebase must be set "
                    + "up in the Library Manager AND its API key must "
                    + "be filled in Firebase Settings — the app refuses "
                    + "this component with only one of the two. Ask the "
                    + "user to finish that setup, or choose something "
                    + "that does not need Firebase.";
        }
        return "In order to use " + what + ", " + libraryName(key)
                + " has to be set up in the Library Manager first — "
                + "Sketchware's own editor refuses it until then. Tell "
                + "the user which switch to turn on (Library Manager → "
                + libraryName(key) + "), or build it another way that "
                + "does not need this library.";
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
            // Stored lists carry whole paths; the name is the part
            // after the last separator.
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
