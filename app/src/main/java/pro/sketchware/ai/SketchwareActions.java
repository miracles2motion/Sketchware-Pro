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

import a.a.a.cC;
import a.a.a.GB;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.nB;
import a.a.a.oB;
import a.a.a.wq;
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
        ArrayList<ProjectFileBean> activities = new ArrayList<>();
        ArrayList<ProjectFileBean> otherFiles = new ArrayList<>();
        splitFiles(scId, activities, otherFiles);
        for (ProjectFileBean activity : activities) {
            if (activity.fileName.equalsIgnoreCase(fileName)) {
                throw new ActionException("That screen already exists.");
            }
        }
        backupProjectOnce(scId);
        activities.add(new ProjectFileBean(
                ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY, fileName));
        jC.b(scId).a(activities);
        jC.b(scId).b(otherFiles);
        jC.b(scId).l();
        jC.b(scId).j();
        jC.a(scId).a(jC.b(scId));
        if (SketchwareProjects.findActivity(scId, fileName) == null) {
            throw new ActionException("The screen was added but cannot "
                    + "be read back.");
        }
    }

    /** Saves a whole screen through the same call the view editor uses. */
    public static void saveScreen(String scId, ProjectFileBean activity,
                                  ArrayList<ViewBean> beans) {
        cC.c(scId).b(activity.getXmlName(), beans);
    }

    private static void splitFiles(String scId,
                                   ArrayList<ProjectFileBean> activities,
                                   ArrayList<ProjectFileBean> otherFiles) {
        ArrayList<ProjectFileBean> all = jC.b(scId).b();
        if (all == null) {
            return;
        }
        for (ProjectFileBean file : all) {
            if (file.fileType == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                activities.add(file);
            } else {
                otherFiles.add(file);
            }
        }
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
