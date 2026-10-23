package pro.sketchware.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import a.a.a.GB;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.nB;
import a.a.a.oB;
import a.a.a.wq;
import com.besome.sketch.beans.ComponentBean;
import com.besome.sketch.beans.EventBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ProjectLibraryBean;
import com.besome.sketch.beans.ViewBean;
import mod.hey.studios.project.ProjectSettings;
import mod.hey.studios.util.ProjectFile;
import pro.sketchware.SketchApplication;
import pro.sketchware.managers.inject.InjectRootLayoutManager;
import pro.sketchware.tools.ViewBeanParser;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.InvokeUtil;
import pro.sketchware.utility.PropertiesUtil;
import pro.sketchware.utility.relativelayout.CircularDependencyDetector;

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
        ProjectFileBean template = new ProjectFileBean(
                ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY, fileName);
        createScreen(scId, fileName, template.options, template.orientation,
                false);
    }

    /**
     * Adds an Activity, Fragment ({@code _fragment}), DialogFragment
     * ({@code _dialog_fragment}), or BottomSheetDialogFragment
     * ({@code _bottomdialog_fragment}) with explicit options and
     * orientation, matching AddViewActivity + ManageViewActivity.
     */
    public static void createScreen(String scId, String fileName, int options,
                                    int orientation, boolean needsAppCompat)
            throws Exception {
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
        boolean isFragment = fileName.toLowerCase(Locale.ROOT)
                .contains("_fragment");
        int effectiveOptions = isFragment
                ? (options & ~(ProjectFileBean.OPTION_ACTIVITY_TOOLBAR
                        | ProjectFileBean.OPTION_ACTIVITY_DRAWER))
                : options;
        ProjectFileBean created = new ProjectFileBean(
                ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY, fileName,
                orientation, 0, effectiveOptions);
        activities.add(created);
        boolean hasDrawer = !isFragment
                && (effectiveOptions
                        & ProjectFileBean.OPTION_ACTIVITY_DRAWER) != 0;
        if (hasDrawer) {
            String drawerName = ProjectFileBean.getDrawerName(fileName);
            boolean exists = false;
            for (ProjectFileBean view : customViews) {
                if (view.fileName.equalsIgnoreCase(drawerName)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                customViews.add(new ProjectFileBean(
                        ProjectFileBean.PROJECT_FILE_TYPE_DRAWER,
                        drawerName));
            }
        }
        if (needsAppCompat || hasDrawer
                || (effectiveOptions
                        & ProjectFileBean.OPTION_ACTIVITY_FAB) != 0
                || fileName.toLowerCase(Locale.ROOT)
                        .endsWith("_bottomdialog_fragment")) {
            enableAppCompat(scId);
        }
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
        String drawerName = ProjectFileBean.getDrawerName(fileName);
        if (target.hasActivityOption(
                ProjectFileBean.OPTION_ACTIVITY_DRAWER)) {
            customViews.removeIf(file ->
                    file.fileName.equalsIgnoreCase(drawerName));
        }
        removeRootEntry(scId, target.getXmlName());
        removeRootEntry(scId, ProjectFileBean.getXmlName(drawerName));
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
     * Deletes a custom view layout (never a {@code _drawer_*} layout),
     * optionally clearing {@code customView} / {@code include} references
     * across other screens first.
     */
    public static List<String> deleteCustomView(String scId, String fileName,
                                                boolean clearReferences)
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
        for (ProjectFileBean view : customViews) {
            if (view.fileName.equalsIgnoreCase(fileName)) {
                target = view;
                break;
            }
        }
        if (target == null) {
            throw new ActionException("That custom view no longer exists.");
        }
        if (SketchwareProjects.isDrawer(target)) {
            throw new ActionException("Navigation drawer layouts ("
                    + target.fileName
                    + ") are managed by edit_activity (drawer: false).");
        }
        List<String> refs =
                SketchwareProjects.customViewReferences(scId, target.fileName);
        if (!refs.isEmpty() && !clearReferences) {
            throw new ActionException("Custom view \"" + target.fileName
                    + "\" is still referenced by: "
                    + String.join("; ", refs)
                    + ". Pass clear_references=true or update those widgets "
                    + "first.");
        }
        backupProjectOnce(scId);
        if (!refs.isEmpty()) {
            List<ProjectFileBean> allScreens = new ArrayList<>(activities);
            allScreens.addAll(customViews);
            for (ProjectFileBean screen : allScreens) {
                if (screen.fileName.equalsIgnoreCase(target.fileName)) {
                    continue;
                }
                ArrayList<ViewBean> beans =
                        SketchwareProjects.screenBeans(scId, screen);
                boolean touched = false;
                ArrayList<ViewBean> includeRemovals = new ArrayList<>();
                for (ViewBean bean : beans) {
                    if (bean == null) {
                        continue;
                    }
                    if (bean.customView != null
                            && bean.customView.equalsIgnoreCase(
                                    target.fileName)) {
                        bean.customView = "none";
                        touched = true;
                    } else if ("include".equals(bean.convert)
                            && bean.id != null
                            && bean.id.equalsIgnoreCase(target.fileName)) {
                        includeRemovals.add(bean);
                    }
                }
                for (ViewBean inc : includeRemovals) {
                    deleteWidgetLikeEditor(scId, screen, inc);
                    beans.remove(inc);
                    touched = true;
                }
                if (touched) {
                    saveScreen(scId, screen, beans);
                }
            }
        }
        customViews.remove(target);
        removeRootEntry(scId, target.getXmlName());
        jC.b(scId).a(activities);
        jC.b(scId).b(customViews);
        jC.b(scId).l();
        jC.b(scId).j();
        jC.a(scId).a(jC.b(scId));
        commitProject(scId);
        if (SketchwareProjects.findCustomView(scId, target.fileName)
                != null) {
            throw new ActionException("The custom view was deleted but can "
                    + "still be read back.");
        }
        return refs;
    }

    /**
     * Removes one component and its component events from an activity or
     * fragment screen through Sketchware's component delete path
     * ({@code br.java:168}: {@code eC.b(javaName, componentBean)}).
     */
    public static int removeComponent(String scId, ProjectFileBean activity,
                                      String componentId) throws Exception {
        eC store = jC.a(scId);
        String javaName = activity.getJavaName();
        ArrayList<ComponentBean> components = store.e(javaName);
        ComponentBean target = null;
        if (components != null) {
            for (ComponentBean component : components) {
                if (component != null
                        && componentId.equals(component.componentId)) {
                    target = component;
                    break;
                }
            }
        }
        if (target == null) {
            throw new ActionException("Component \"" + componentId
                    + "\" no longer exists on "
                    + SketchwareProjects.activityName(activity) + ".");
        }
        backupProjectOnce(scId);
        int removedEvents = 0;
        ArrayList<EventBean> events = store.g(javaName);
        if (events != null) {
            ArrayList<EventBean> toDelete = new ArrayList<>();
            for (EventBean ev : events) {
                if (ev != null
                        && ev.eventType == EventBean.EVENT_TYPE_COMPONENT
                        && componentId.equals(ev.targetId)) {
                    toDelete.add(ev);
                }
            }
            for (EventBean ev : toDelete) {
                EventBean.deleteEvent(scId, ev, activity);
                removedEvents++;
            }
        }
        store.b(javaName, target);
        commitProject(scId);
        ArrayList<ComponentBean> after = store.e(javaName);
        if (after != null) {
            for (ComponentBean component : after) {
                if (component != null
                        && componentId.equals(component.componentId)) {
                    throw new ActionException("Component \"" + componentId
                            + "\" was deleted but can still be read back.");
                }
            }
        }
        return removedEvents;
    }

    /**
     * Adds one empty custom view, persisting through the same call
     * sequence ManageViewActivity uses when a custom view is added:
     * both file lists written back exactly as loaded, the new bean in
     * the custom-view list. A one-time backup is taken first.
     */
    public static void createCustomView(String scId, String fileName)
            throws Exception {
        ArrayList<ProjectFileBean> activities = jC.b(scId).b();
        if (activities == null) {
            activities = new ArrayList<>();
        }
        ArrayList<ProjectFileBean> customViews = jC.b(scId).c();
        if (customViews == null) {
            customViews = new ArrayList<>();
        }
        for (ProjectFileBean view : customViews) {
            if (view.fileName.equalsIgnoreCase(fileName)) {
                throw new ActionException("That custom view already "
                        + "exists.");
            }
        }
        backupProjectOnce(scId);
        customViews.add(new ProjectFileBean(
                ProjectFileBean.PROJECT_FILE_TYPE_CUSTOM_VIEW, fileName));
        jC.b(scId).a(activities);
        jC.b(scId).b(customViews);
        jC.b(scId).l();
        jC.b(scId).j();
        jC.a(scId).a(jC.b(scId));
        commitProject(scId);
        boolean found = false;
        for (ProjectFileBean view : SketchwareProjects.customViews(scId)) {
            if (view.fileName.equalsIgnoreCase(fileName)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new ActionException("The custom view was added but "
                    + "cannot be read back.");
        }
    }

    /**
     * Changes one activity's toolbar/fullscreen/drawer options and
     * orientation, persisting through the same file-manager save
     * sequence as screen creation. A drawer bit carries a file with it
     * ({@code _drawer_<activity>}), which this call creates or removes
     * exactly as the screen manager does, so the screen never ends up
     * with a drawer that has no layout.
     */
    public static void editActivityOptions(String scId, String fileName,
                                           int options, int orientation)
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
        target.options = options;
        target.orientation = orientation;
        if ((options & ProjectFileBean.OPTION_ACTIVITY_FAB) != 0) {
            // The screen manager enables the design library the moment
            // a floating button is switched on (Fw's option save).
            enableAppCompat(scId);
        }
        // A drawer is a screen of its own — `_drawer_<activity>` —
        // which the screen manager creates when the option goes on and
        // deletes when it goes off (Fw.b() → ManageViewActivity.b()/c()).
        // Setting the bit alone would give the screen a drawer that has
        // no layout, so the file is kept in step here.
        String drawerName = ProjectFileBean.getDrawerName(fileName);
        boolean hasDrawer = (options
                & ProjectFileBean.OPTION_ACTIVITY_DRAWER) != 0;
        ProjectFileBean drawerFile = null;
        for (ProjectFileBean view : customViews) {
            if (view.fileName.equalsIgnoreCase(drawerName)) {
                drawerFile = view;
                break;
            }
        }
        if (hasDrawer) {
            if (drawerFile == null) {
                customViews.add(new ProjectFileBean(
                        ProjectFileBean.PROJECT_FILE_TYPE_DRAWER,
                        drawerName));
            }
            // The screen manager switches the design library on for a
            // drawer too (Fw.b()), and the drawer layout is AndroidX.
            enableAppCompat(scId);
        } else if (drawerFile != null) {
            customViews.remove(drawerFile);
            removeRootEntry(scId, ProjectFileBean.getXmlName(drawerName));
        }
        jC.b(scId).a(activities);
        jC.b(scId).b(customViews);
        jC.b(scId).l();
        jC.b(scId).j();
        jC.a(scId).a(jC.b(scId));
        commitProject(scId);
        ProjectFileBean readBack =
                SketchwareProjects.findActivity(scId, fileName);
        if (readBack == null || readBack.options != options
                || readBack.orientation != orientation) {
            throw new ActionException("The change was saved but cannot "
                    + "be read back as made.");
        }
    }

    /**
     * Switches AppCompat / Material on, the way the screen manager does
     * when a drawer or a floating button is added (Fw.b(): it sets the
     * compat bean's {@code useYn} to "Y" itself) and the way the
     * Library Manager saves it. A project that never opened the Library
     * Manager has no bean yet, so one is created first — the editor
     * guards against the same null (ManageLibraryActivity).
     *
     * <p>This is not the agent deciding for the user: without it, the
     * screen it just made cannot compile, because the build only
     * includes the AndroidX libraries when this switch is on.
     */
    private static void enableAppCompat(String scId) {
        try {
            ProjectLibraryBean compat = jC.c(scId).c();
            if (compat == null) {
                compat = new ProjectLibraryBean(
                        ProjectLibraryBean.PROJECT_LIB_TYPE_COMPAT);
            }
            compat.useYn = ProjectLibraryBean.LIB_USE_Y;
            jC.c(scId).b(compat);
            jC.c(scId).k();
        } catch (Exception ignored) {
            // A library switch that cannot be written is reported by
            // get_project_setup; it must not fail the screen change.
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
     * Changes attributes of a screen's root layout through
     * {@link pro.sketchware.managers.inject.InjectRootLayoutManager} —
     * the same store the editor's root editing and the XML generator
     * read. Returns the previous root so a failed save can put it back.
     */
    public static pro.sketchware.managers.inject.InjectRootLayoutManager.Root
            setRootAttributes(String scId, ProjectFileBean activity,
                              java.util.Map<String, String> updates) {
        return setRootLayout(scId, activity, null, updates, false);
    }

    /**
     * Resolves a human or full-package ViewGroup name to the exact XML tag
     * Sketchware Pro's {@link InjectRootLayoutManager} and {@code Ox} write.
     * Returns {@code null} when unknown or not a {@link ViewGroup}.
     */
    public static String resolveRootClassName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String trimmed = raw.trim();
        String key = trimmed.toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "");
        String mapped = switch (key) {
            case "linearlayout", "linear" -> "LinearLayout";
            case "relativelayout", "relative" -> "RelativeLayout";
            case "framelayout", "frame" -> "FrameLayout";
            case "scrollview", "vscrollview", "verticalscrollview" ->
                    "ScrollView";
            case "horizontalscrollview", "hscrollview" ->
                    "HorizontalScrollView";
            case "constraintlayout", "constraint" ->
                    "androidx.constraintlayout.widget.ConstraintLayout";
            case "coordinatorlayout", "coordinator" ->
                    "androidx.coordinatorlayout.widget.CoordinatorLayout";
            case "nestedscrollview", "nestedscroll" ->
                    "androidx.core.widget.NestedScrollView";
            case "cardview", "materialcardview" ->
                    "com.google.android.material.card.MaterialCardView";
            case "swiperefreshlayout", "swiperefresh" ->
                    "androidx.swiperefreshlayout.widget.SwipeRefreshLayout";
            case "radiogroup" -> "RadioGroup";
            case "gridlayout" -> "GridLayout";
            case "tablelayout" -> "TableLayout";
            default -> trimmed;
        };
        if (mapped.equals(trimmed)
                && !mapped.contains(".")
                && !mapped.matches("[A-Z][A-Za-z0-9]*")) {
            return null;
        }
        Object ok = runOnMainLooperWithTimeout(() -> {
            View created = InvokeUtil.createView(
                    SketchApplication.getContext(), mapped);
            return created instanceof ViewGroup;
        });
        return Boolean.TRUE.equals(ok) ? mapped : null;
    }

    /**
     * Updates a screen's root ViewGroup class and/or attributes through
     * {@link InjectRootLayoutManager} and keeps top-level children's
     * {@code parentType} in sync. Returns the previous root for rollback.
     */
    public static InjectRootLayoutManager.Root setRootLayout(
            String scId, ProjectFileBean screen, String newClassName,
            Map<String, String> updates, boolean replaceAttributes) {
        InjectRootLayoutManager manager = new InjectRootLayoutManager(scId);
        String xmlName = screen.getXmlName();
        InjectRootLayoutManager.Root previous =
                manager.getLayoutByFileName(xmlName);
        String targetClass = newClassName != null && !newClassName.isEmpty()
                ? newClassName : previous.getClassName();
        Map<String, String> attributes = replaceAttributes
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(previous.getAttributes() != null
                        ? previous.getAttributes() : new LinkedHashMap<>());
        if (!attributes.containsKey("android:layout_width")) {
            attributes.put("android:layout_width", "match_parent");
        }
        if (!attributes.containsKey("android:layout_height")) {
            attributes.put("android:layout_height", "match_parent");
        }
        if (newClassName != null && !"LinearLayout".equals(targetClass)
                && !"RadioGroup".equals(targetClass)
                && (updates == null
                        || !updates.containsKey("android:orientation"))) {
            attributes.remove("android:orientation");
        } else if ("LinearLayout".equals(targetClass)
                && !attributes.containsKey("android:orientation")) {
            attributes.put("android:orientation", "vertical");
        }
        if (updates != null) {
            for (Map.Entry<String, String> entry : updates.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    attributes.remove(entry.getKey());
                } else {
                    attributes.put(entry.getKey(), entry.getValue());
                }
            }
        }
        manager.set(xmlName,
                new InjectRootLayoutManager.Root(targetClass, attributes));
        if (newClassName != null && !newClassName.isEmpty()) {
            Object rootTypeObj = runOnMainLooperWithTimeout(
                    () -> ViewBeanParser.getViewTypeByClassName(targetClass));
            int resolvedType = rootTypeObj instanceof Integer
                    ? (Integer) rootTypeObj : ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
            ArrayList<ViewBean> beans =
                    SketchwareProjects.screenBeans(scId, screen);
            if (beans != null && !beans.isEmpty()) {
                boolean touched = false;
                for (ViewBean bean : beans) {
                    if (bean != null
                            && ("root".equals(bean.parent)
                                    || bean.parent == null
                                    || bean.parent.isEmpty())
                            && bean.parentType != resolvedType) {
                        bean.parent = "root";
                        bean.parentType = resolvedType;
                        touched = true;
                    }
                }
                if (touched) {
                    saveScreen(scId, screen, beans);
                }
            }
            if (targetClass.startsWith("androidx.")
                    || targetClass.startsWith("com.google.android.material.")) {
                enableAppCompat(scId);
            }
        }
        return previous;
    }

    /** Result of parsing and validating raw Android layout XML. */
    public static final class ParsedLayoutXml {
        public final InjectRootLayoutManager.Root root;
        public final ArrayList<ViewBean> beans;

        public ParsedLayoutXml(InjectRootLayoutManager.Root root,
                               ArrayList<ViewBean> beans) {
            this.root = root;
            this.beans = beans;
        }
    }

    /**
     * Parses and validates raw Android layout XML on the main looper using
     * {@link ViewBeanParser} + {@link CircularDependencyDetector} +
     * {@code eC.a(...)} (matching {@code ViewCodeEditorActivity:167-212}).
     */
    public static ParsedLayoutXml parseLayoutXml(String xml) throws Exception {
        if (xml == null || xml.trim().isEmpty()) {
            throw new ActionException("Layout XML is empty.");
        }
        final String cleaned = xml.startsWith("\uFEFF")
                ? xml.substring(1).trim() : xml.trim();
        AtomicReference<ParsedLayoutXml> outRef = new AtomicReference<>();
        AtomicReference<Exception> errRef = new AtomicReference<>();
        Runnable task = () -> {
            try {
                ViewBeanParser parser = new ViewBeanParser(cleaned);
                parser.setSkipRoot(true);
                ArrayList<ViewBean> parsed = parser.parse();
                Pair<String, Map<String, String>> rootPair =
                        parser.getRootAttributes();
                if (rootPair == null || rootPair.first == null
                        || rootPair.first.isEmpty()) {
                    throw new ActionException(
                            "Layout XML must start with a ViewGroup root tag "
                                    + "(e.g. LinearLayout, FrameLayout, "
                                    + "ConstraintLayout).");
                }
                for (ViewBean viewBean : parsed) {
                    if (viewBean.parentAttributes == null
                            || viewBean.parentAttributes.isEmpty()) {
                        continue;
                    }
                    CircularDependencyDetector detector =
                            new CircularDependencyDetector(parsed, viewBean);
                    for (Map.Entry<String, String> entry
                            : viewBean.parentAttributes.entrySet()) {
                        String targetId = PropertiesUtil.parseReferName(
                                entry.getValue(), "/");
                        if (!detector.isLegalAttribute(
                                targetId, entry.getKey())) {
                            throw new ActionException(
                                    "Circular RelativeLayout dependency on "
                                            + viewBean.id + " ("
                                            + entry.getKey() + " -> "
                                            + entry.getValue() + ").");
                        }
                    }
                }
                ArrayList<ViewBean> normalized = eC.a(parsed);
                outRef.set(new ParsedLayoutXml(
                        InjectRootLayoutManager.toRoot(rootPair),
                        normalized != null ? normalized : parsed));
            } catch (Exception e) {
                errRef.set(e);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new ActionException(
                        "Timed out while parsing layout XML on main thread.");
            }
        }
        if (errRef.get() != null) {
            throw errRef.get();
        }
        return outRef.get();
    }

    /**
     * Imports validated Android layout XML into {@code screen}, cleaning up
     * events/block references for any removed widgets and updating both
     * {@link InjectRootLayoutManager} and {@code eC}.
     */
    public static ParsedLayoutXml importLayoutXml(String scId,
                                                  ProjectFileBean screen,
                                                  String xml)
            throws Exception {
        ParsedLayoutXml parsed = parseLayoutXml(xml);
        ArrayList<ViewBean> current =
                SketchwareProjects.screenBeans(scId, screen);
        ArrayList<ViewBean> snapshot = AgentEdits.deepCopy(current);
        InjectRootLayoutManager rootManager =
                new InjectRootLayoutManager(scId);
        InjectRootLayoutManager.Root previousRoot =
                rootManager.getLayoutByFileName(screen.getXmlName());
        java.util.Set<String> keepIds = new java.util.HashSet<>();
        for (ViewBean b : parsed.beans) {
            if (b != null && b.id != null) {
                keepIds.add(b.id);
            }
        }
        backupProjectOnce(scId);
        try {
            for (ViewBean old : current) {
                if (old != null && !keepIds.contains(old.id)) {
                    deleteWidgetLikeEditor(scId, screen, old);
                }
            }
            rootManager.set(screen.getXmlName(), parsed.root);
            saveScreen(scId, screen, parsed.beans);
            String rootClass = parsed.root.getClassName();
            if (rootClass != null
                    && (rootClass.startsWith("androidx.")
                            || rootClass.startsWith(
                                    "com.google.android.material."))) {
                enableAppCompat(scId);
            }
            commitProject(scId);
            ArrayList<ViewBean> readBack =
                    SketchwareProjects.screenBeans(scId, screen);
            if (readBack.size() != parsed.beans.size()) {
                throw new ActionException("Imported " + parsed.beans.size()
                        + " widgets, but read back " + readBack.size() + ".");
            }
            return parsed;
        } catch (Exception e) {
            try {
                restoreRoot(scId, screen, previousRoot);
                saveScreen(scId, screen, snapshot);
                commitProject(scId);
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private static void removeRootEntry(String scId, String xmlName) {
        if (scId == null || xmlName == null || xmlName.isEmpty()) {
            return;
        }
        try {
            String path = wq.b(scId) + "/view_root";
            if (!FileUtil.isExistFile(path)) {
                return;
            }
            InjectRootLayoutManager manager =
                    new InjectRootLayoutManager(scId);
            Map<String, InjectRootLayoutManager.Root> map = manager.get();
            if (map != null && map.remove(xmlName) != null) {
                FileUtil.writeFile(path,
                        new com.google.gson.Gson().toJson(map));
            }
        } catch (Exception ignored) {
        }
    }

    private static Object runOnMainLooperWithTimeout(
            java.util.concurrent.Callable<Object> callable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return callable.call();
            } catch (Exception ignored) {
                return null;
            }
        }
        AtomicReference<Object> ref = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ref.set(callable.call());
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }

    /**
     * Changes a screen's floating button through the data manager's own
     * fab store ({@code eC.h} returns — creating if needed — the live
     * bean the editor edits). Returns a snapshot of the previous bean so
     * a failed save can put it back with {@link #restoreFab}.
     */
    public static ViewBean setFab(String scId, ProjectFileBean activity,
                                  String icon, Integer background) {
        return setFab(scId, activity, icon, background, null);
    }

    public static ViewBean setFab(String scId, ProjectFileBean activity,
                                  String icon, Integer background,
                                  String backgroundRes) {
        ViewBean fab = jC.a(scId).h(activity.getXmlName());
        ViewBean snapshot = new ViewBean();
        snapshot.copy(fab);
        if (icon != null) {
            fab.image.resName = icon;
        }
        if (background != null) {
            fab.layout.backgroundColor = background;
            fab.layout.backgroundResColor = backgroundRes;
        } else if (backgroundRes != null) {
            fab.layout.backgroundResColor = backgroundRes;
        }
        return snapshot;
    }

    /** Puts back a floating-button bean captured by {@link #setFab}. */
    public static void restoreFab(String scId, ProjectFileBean activity,
                                  ViewBean snapshot) {
        if (snapshot == null) {
            return;
        }
        jC.a(scId).h(activity.getXmlName()).copy(snapshot);
    }

    /** Puts back a root layout captured by {@link #setRootAttributes}. */
    public static void restoreRoot(String scId, ProjectFileBean activity,
            pro.sketchware.managers.inject.InjectRootLayoutManager.Root
                    previous) {
        if (previous == null) {
            return;
        }
        new pro.sketchware.managers.inject.InjectRootLayoutManager(scId)
                .set(activity.getXmlName(), previous);
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
