package pro.sketchware.ai;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import a.a.a.GB;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.nB;
import a.a.a.oB;
import a.a.a.wq;
import com.besome.sketch.beans.EventBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;
import mod.hey.studios.project.ProjectSettings;
import mod.hey.studios.util.ProjectFile;

/**
 * The agent's hands. Every method performs one mutation by calling the
 * same app classes the UI itself calls, then verifies the result by
 * reading it back through {@link SketchwareProjects}. Nothing here
 * hand-writes a project file.
 *
 * <p>Disk work: call off the main thread.
 */
public final class SketchwareActions {

    private SketchwareActions() {
    }

    /** Thrown when a mutation or its read-back verification fails. */
    public static final class ActionException extends Exception {
        ActionException(String message) {
            super(message);
        }
    }

    private static final String[] THEME_COLOR_KEYS = {
            ProjectFile.COLOR_ACCENT, ProjectFile.COLOR_PRIMARY,
            ProjectFile.COLOR_PRIMARY_DARK,
            ProjectFile.COLOR_CONTROL_HIGHLIGHT,
            ProjectFile.COLOR_CONTROL_NORMAL
    };

    /**
     * Creates a project exactly the way MyProjectSettingActivity does:
     * registry entry through lC, the file skeleton through wq, and the
     * same default project settings. Returns the created project.
     */
    public static SketchwareProjects.ProjectInfo createProject(
            Context context, String appName, String packageName)
            throws Exception {
        String wsName = uniqueWorkspaceName(appName);
        String pkg = (packageName == null || packageName.isEmpty())
                ? "com.my." + wsName.toLowerCase(Locale.ROOT) : packageName;

        String scId = lC.b();
        HashMap<String, Object> data = new HashMap<>();
        data.put("sc_id", scId);
        data.put("my_sc_pkg_name", pkg);
        data.put("my_ws_name", wsName);
        data.put("my_app_name", appName);
        data.put("my_sc_reg_dt", new nB().a("yyyyMMddHHmmss"));
        data.put("custom_icon", false);
        data.put("isIconAdaptive", false);
        data.put("sc_ver_code", "1");
        data.put("sc_ver_name", "1.0");
        data.put("sketchware_ver", GB.d(context));
        for (String key : THEME_COLOR_KEYS) {
            data.put(key, ProjectFile.getDefaultColor(key));
        }
        lC.a(scId, data);
        wq.a(context, scId);
        new oB().b(wq.b(scId));
        ProjectSettings settings = new ProjectSettings(scId);
        settings.setValue(ProjectSettings.SETTING_NEW_XML_COMMAND,
                ProjectSettings.SETTING_GENERIC_VALUE_TRUE);
        settings.setValue(ProjectSettings.SETTING_ENABLE_VIEWBINDING,
                ProjectSettings.SETTING_GENERIC_VALUE_FALSE);

        SketchwareProjects.ProjectInfo created = SketchwareProjects.project(scId);
        if (created == null) {
            throw new ActionException("The project was created but cannot "
                    + "be read back.");
        }
        return created;
    }

    /** A project-folder name that no existing project uses. */
    private static String uniqueWorkspaceName(String appName) {
        String base = appName == null ? "" : appName.replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
            base = "NewProject" + base;
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (SketchwareProjects.ProjectInfo project
                : SketchwareProjects.listProjects()) {
            taken.add(project.name.toLowerCase(Locale.ROOT));
        }
        String candidate = base;
        int suffix = 2;
        while (taken.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    /**
     * Adds one empty activity to a project, persisting through the same
     * call sequence ManageViewActivity uses when a screen is added. A
     * one-time backup of the project is taken first.
     */
    public static void createActivity(String scId, String fileName)
            throws Exception {
        // The file manager keeps two lists, and ManageViewActivity's save
        // writes both back exactly as loaded: activities (b()) and custom
        // views (c()). Both are carried through untouched here — feeding
        // the custom-view list from the activity list (as an earlier
        // version did) would silently drop a project's custom views.
        ArrayList<ProjectFileBean> activities = jC.b(scId).b();
        if (activities == null) {
            activities = new ArrayList<>();
        }
        ArrayList<ProjectFileBean> customViews = jC.b(scId).c();
        if (customViews == null) {
            customViews = new ArrayList<>();
        }
        for (ProjectFileBean activity : activities) {
            if (activity.fileType
                            == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY
                    && activity.fileName.equalsIgnoreCase(fileName)) {
                throw new ActionException("That screen already exists.");
            }
        }
        backupProjectOnce(scId);
        activities.add(new ProjectFileBean(
                ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY, fileName));
        jC.b(scId).a(activities);
        jC.b(scId).b(customViews);
        jC.b(scId).l();
        jC.b(scId).j();
        jC.a(scId).a(jC.b(scId));
        commitProject(scId);
        if (SketchwareProjects.findActivity(scId, fileName) == null) {
            throw new ActionException("The screen was added but cannot "
                    + "be read back.");
        }
    }

    /**
     * Deletes one activity, persisting through the same call sequence
     * ManageViewActivity's save uses after the screen manager removes a
     * screen: both file lists are written back and the data manager
     * reconciles its stores against them ({@code eC.a(hC)}), which is
     * what drops the deleted screen's widgets, events and blocks. An
     * activity with a drawer takes its drawer file with it, as the
     * screen manager's delete does. A one-time backup is taken first.
     */
    public static void deleteActivity(String scId, String fileName)
            throws Exception {
        ArrayList<ProjectFileBean> activities = jC.b(scId).b();
        if (activities == null) {
            activities = new ArrayList<>();
        }
        ArrayList<ProjectFileBean> customViews = jC.b(scId).c();
        if (customViews == null) {
            customViews = new ArrayList<>();
        }
        ProjectFileBean target = null;
        for (ProjectFileBean activity : activities) {
            if (activity.fileType
                            == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY
                    && activity.fileName.equalsIgnoreCase(fileName)) {
                target = activity;
                break;
            }
        }
        if (target == null) {
            throw new ActionException("That screen no longer exists.");
        }
        backupProjectOnce(scId);
        activities.remove(target);
        if (target.hasActivityOption(
                ProjectFileBean.OPTION_ACTIVITY_DRAWER)) {
            String drawerName = ProjectFileBean.getDrawerName(fileName);
            customViews.removeIf(file ->
                    file.fileName.equalsIgnoreCase(drawerName));
        }
        jC.b(scId).a(activities);
        jC.b(scId).b(customViews);
        jC.b(scId).l();
        jC.b(scId).j();
        jC.a(scId).a(jC.b(scId));
        commitProject(scId);
        if (SketchwareProjects.findActivity(scId, fileName) != null) {
            throw new ActionException("The screen was deleted but can "
                    + "still be read back.");
        }
    }

    /**
     * Commits a project the way the app's own save-on-close routine
     * does. The data managers keep working copies; writes through them
     * without this commit leave the project flagged as "previous data
     * was not saved", and Sketchware offers to restore on next open.
     * Every agent mutation must end with this.
     */
    public static void commitProject(String scId) {
        jC.d(scId).a();
        jC.b(scId).m();
        jC.a(scId).j();
        jC.d(scId).x();
        jC.c(scId).l();
        jC.d(scId).h();
    }

    /**
     * Replaces a screen's view beans the way the app itself does: the project
     * data manager's ({@code eC}) live view map IS the store, and screen reads
     * come from it — the app's own undo/redo replaces whole screens via
     * {@code jC.a(sc_id).c.put(xmlName, beans)} ({@code ViewEditorFragment}'s
     * {@code ACTION_TYPE_OVERRIDE} path). (The {@code cC} manager used here
     * previously only records undo history; it never touches the store,
     * which is why saved edits never appeared on re-read.)
     *
     * <p>Before anything is stored, the beans are run through Sketchware's
     * own view normalizer — the same {@code eC.a(...)} the editor applies
     * when it loads a screen and the serializer applies when it writes one.
     * It dereferences {@code bean.parent.equals("root")} for every widget,
     * so a screen the editor would choke on fails here, before the store
     * is touched, instead of inside the editor later. The change is
     * flushed to disk by {@link #commitProject}.
     */
    public static void saveScreen(String scId, ProjectFileBean activity,
                                  ArrayList<ViewBean> beans) {
        eC.a(beans);
        eC store = jC.a(scId);
        if (store == null) throw new IllegalStateException("project data is unavailable");
        store.c.put(activity.getXmlName(), beans);
    }

    /**
     * Gives a newly added Button the empty onClick event the editor itself
     * registers the moment a Button is dropped or preset-added
     * ({@code ViewEditor}'s drop path, {@code ManageViewActivity}'s preset
     * path). Without it the button exists but has no event entry in the
     * screen's logic. Registration is guarded: the manager's own call
     * always appends, so an existing identical event is left alone.
     */
    public static void registerButtonClickEvent(String scId,
                                                ProjectFileBean activity,
                                                ViewBean bean) {
        if (activity.fileType != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            return;
        }
        eC store = jC.a(scId);
        ArrayList<EventBean> events = store.g(activity.getJavaName());
        if (events != null) {
            for (EventBean event : events) {
                if (event.eventType == EventBean.EVENT_TYPE_VIEW
                        && bean.id.equals(event.targetId)
                        && "onClick".equals(event.eventName)) {
                    return;
                }
            }
        }
        store.a(activity.getJavaName(), EventBean.EVENT_TYPE_VIEW,
                bean.type, bean.id, "onClick");
    }

    /**
     * Removes one widget the way the editor's delete does
     * ({@code ViewEditor.deleteWidget} calls this manager method for the
     * widget and everything inside it): besides dropping the bean, it
     * removes the widget's events and scrubs block references to it, so
     * no orphan logic is left pointing at a widget that no longer exists.
     */
    public static void deleteWidgetLikeEditor(String scId,
                                              ProjectFileBean activity,
                                              ViewBean bean) {
        jC.a(scId).a(activity, bean);
    }

    /**
     * Copies the whole project folder beside the data directory the
     * first time the agent is about to change that project. Later calls
     * see the existing backup and leave it untouched.
     */
    public static void backupProjectOnce(String scId) throws IOException {
        File source = new File(wq.b(scId));
        File root = new File(
                new File(source.getParentFile().getParentFile(), "ai-backups"),
                scId);
        if (root.exists() || !source.isDirectory()) {
            return;
        }
        copyDirectory(source, root);
    }

    private static void copyDirectory(File from, File to) throws IOException {
        if (!to.mkdirs() && !to.isDirectory()) {
            throw new IOException("Could not create " + to);
        }
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, target);
            } else {
                copyFile(child, target);
            }
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
