package pro.sketchware.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import a.a.a.eC;
import a.a.a.jC;
import a.a.a.lC;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;
import pro.sketchware.managers.inject.InjectRootLayoutManager;

/**
 * The agent's read-only window into Sketchware's own data.
 *
 * <p>Everything here goes through the same classes the app itself uses —
 * {@link lC} for the project registry, {@link jC} for project files and
 * screens — never through hand-parsed project files, so what the agent
 * sees is what the editor sees. This class only ever reads; mutation
 * arrives in later slices behind the approval gates.
 *
 * <p>Methods touch the disk: call them off the main thread.
 */
public final class SketchwareProjects {

    private SketchwareProjects() {
    }

    /** One registered project, as the project list knows it. */
    public static final class ProjectInfo {
        public final String scId;
        public final String name;
        public final String appName;
        public final String packageName;

        ProjectInfo(String scId, String name, String appName, String packageName) {
            this.scId = scId;
            this.name = name;
            this.appName = appName;
            this.packageName = packageName;
        }
    }

    /** Every project Sketchware knows about. */
    public static List<ProjectInfo> listProjects() {
        List<ProjectInfo> projects = new ArrayList<>();
        try {
            for (HashMap<String, Object> map : lC.a()) {
                String scId = text(map, "sc_id");
                if (!scId.isEmpty()) {
                    projects.add(new ProjectInfo(scId,
                            text(map, "my_ws_name"),
                            text(map, "my_app_name"),
                            text(map, "my_sc_pkg_name")));
                }
            }
        } catch (Exception ignored) {
            // A broken registry reads as "no projects", never a crash.
        }
        projects.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return projects;
    }

    /** One project by id, or null when it is not registered. */
    public static ProjectInfo project(String scId) {
        if (scId == null || scId.isEmpty()) {
            return null;
        }
        try {
            HashMap<String, Object> map = lC.b(scId);
            if (map == null) {
                return null;
            }
            return new ProjectInfo(scId,
                    text(map, "my_ws_name"),
                    text(map, "my_app_name"),
                    text(map, "my_sc_pkg_name"));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * One of the project's theme colours by registry key
     * ({@code color_primary}, {@code color_primary_dark},
     * {@code color_accent}); {@link Integer#MIN_VALUE} when unknown.
     */
    public static int projectColor(String scId, String key) {
        if (scId == null || key == null) {
            return Integer.MIN_VALUE;
        }
        try {
            HashMap<String, Object> map = lC.b(scId);
            Object value = map == null ? null : map.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value instanceof String) {
                return (int) Long.parseLong(
                        ((String) value).replace("#", "").trim(), 16);
            }
        } catch (Exception ignored) {
            // An unreadable registry reads as "no colour".
        }
        return Integer.MIN_VALUE;
    }

    /** The activities (screens) of a project, main first. */
    public static List<ProjectFileBean> activities(String scId) {
        List<ProjectFileBean> activities = new ArrayList<>();
        try {
            ArrayList<ProjectFileBean> files = jC.b(scId).b();
            if (files != null) {
                for (ProjectFileBean file : files) {
                    if (file.fileType == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                        activities.add(file);
                    }
                }
            }
        } catch (Exception ignored) {
            // Reads as "no screens" rather than failing the turn.
        }
        activities.sort((a, b) -> {
            if ("main".equals(a.fileName)) {
                return -1;
            }
            if ("main".equals(b.fileName)) {
                return 1;
            }
            return a.fileName.compareToIgnoreCase(b.fileName);
        });
        return activities;
    }

    /** Display name of an activity: "MainActivity". */
    public static String activityName(ProjectFileBean activity) {
        String javaName = activity.getJavaName();
        return javaName.endsWith(".java")
                ? javaName.substring(0, javaName.length() - ".java".length())
                : javaName;
    }

    /** Finds an activity by the name a user or model would use. */
    public static ProjectFileBean findActivity(String scId, String asked) {
        if (asked == null) {
            return null;
        }
        String wanted = asked.trim().toLowerCase(Locale.ROOT);
        for (ProjectFileBean activity : activities(scId)) {
            if (activityName(activity).toLowerCase(Locale.ROOT).equals(wanted)
                    || activity.fileName.toLowerCase(Locale.ROOT).equals(wanted)
                    || activity.getXmlName().toLowerCase(Locale.ROOT).equals(wanted)) {
                return activity;
            }
        }
        return null;
    }

    /** The custom views of a project (reusable layouts, no logic). */
    public static List<ProjectFileBean> customViews(String scId) {
        List<ProjectFileBean> views = new ArrayList<>();
        try {
            ArrayList<ProjectFileBean> files = jC.b(scId).c();
            if (files != null) {
                for (ProjectFileBean file : files) {
                    if (file.fileType
                            == ProjectFileBean.PROJECT_FILE_TYPE_CUSTOM_VIEW) {
                        views.add(file);
                    }
                }
            }
        } catch (Exception ignored) {
            // Reads as "no custom views" rather than failing the turn.
        }
        views.sort((a, b) -> a.fileName.compareToIgnoreCase(b.fileName));
        return views;
    }

    /**
     * What one activity already declares, read through the same data
     * manager accessors the code generator uses ({@code eC.e/k/j/i/g}):
     * component ids, variable names, list names, more-block names, and
     * how many events it has. Activities only; anything unreadable reads
     * as empty rather than failing the turn.
     */
    public static LogicSummary logicSummary(String scId,
                                            ProjectFileBean activity) {
        LogicSummary summary = new LogicSummary();
        if (activity.fileType
                != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            return summary;
        }
        try {
            a.a.a.eC store = jC.a(scId);
            String javaName = activity.getJavaName();
            for (com.besome.sketch.beans.ComponentBean component
                    : store.e(javaName)) {
                summary.components.add(component.componentId);
            }
            for (android.util.Pair<Integer, String> variable
                    : store.k(javaName)) {
                // 6 is a raw field declaration and 9 an import; neither
                // is a variable name.
                if (variable.first != 6 && variable.first != 9) {
                    summary.variables.add(variable.second);
                    summary.variableTypes.put(variable.second,
                            variableTypeName(variable.first));
                }
            }
            for (android.util.Pair<Integer, String> list
                    : store.j(javaName)) {
                summary.lists.add(list.second);
            }
            for (android.util.Pair<String, String> moreBlock
                    : store.i(javaName)) {
                summary.moreBlocks.add(moreBlock.first);
            }
            summary.events = store.g(javaName).size();
        } catch (Exception ignored) {
            // An unreadable logic side reads as "nothing declared".
        }
        return summary;
    }

    /**
     * The project's resource names, through the resource manager's own
     * listings (the same calls the logic editor's pickers use:
     * {@code kC.m()} images, {@code kC.p()} sounds, {@code kC.k()} fonts).
     */
    public static Resources resources(String scId) {
        Resources resources = new Resources();
        try {
            a.a.a.kC store = jC.d(scId);
            resources.images.addAll(store.m());
            resources.sounds.addAll(store.p());
            resources.fonts.addAll(store.k());
        } catch (Exception ignored) {
            // Unreadable resources read as "none".
        }
        return resources;
    }

    /**
     * The events an activity already has, as "widgetId eventName" lines,
     * through the data manager's own event list ({@code eC.g}).
     * Activities only; custom views have no logic.
     */
    public static List<String> eventLines(String scId,
                                          ProjectFileBean activity) {
        List<String> lines = new ArrayList<>();
        if (activity.fileType
                != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            return lines;
        }
        try {
            a.a.a.eC store = jC.a(scId);
            for (com.besome.sketch.beans.EventBean event
                    : store.g(activity.getJavaName())) {
                String line = event.targetId + " " + event.eventName;
                try {
                    java.util.ArrayList<com.besome.sketch.beans.BlockBean>
                            blocks = store.a(activity.getJavaName(),
                                    event.targetId + "_"
                                            + event.eventName);
                    if (blocks != null && !blocks.isEmpty()) {
                        line += " (" + blocks.size() + " block"
                                + (blocks.size() == 1 ? "" : "s") + ")";
                    }
                } catch (Exception ignored) {
                    // The bare event line still tells the model enough.
                }
                lines.add(line);
                if (lines.size() >= 60) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // Unreadable events read as "none wired".
        }
        return lines;
    }

    /** A project's image, sound and font names. */
    public static final class Resources {
        public final List<String> images = new ArrayList<>();
        public final List<String> sounds = new ArrayList<>();
        public final List<String> fonts = new ArrayList<>();
    }

    /** One activity's declared logic-side names. */
    public static final class LogicSummary {
        public final List<String> components = new ArrayList<>();
        public final List<String> variables = new ArrayList<>();
        /** Variable name to its declared kind ("boolean", "number",
         * "string", "map") — parallel to {@link #variables}. */
        public final java.util.LinkedHashMap<String, String> variableTypes =
                new java.util.LinkedHashMap<>();
        public final List<String> lists = new ArrayList<>();
        public final List<String> moreBlocks = new ArrayList<>();
        public int events;
    }

    /** The editor's variable kinds by their stored type code. */
    public static String variableTypeName(int typeCode) {
        return switch (typeCode) {
            case 0 -> "boolean";
            case 1 -> "number";
            case 2 -> "string";
            case 3 -> "map";
            default -> "other";
        };
    }

    /** The stored type code for an editor variable kind name. */
    public static int variableTypeCode(String typeName) {
        if (typeName == null) {
            return -1;
        }
        return switch (typeName.trim().toLowerCase()) {
            case "boolean", "bool" -> 0;
            case "number", "int", "double" -> 1;
            case "string", "text" -> 2;
            case "map" -> 3;
            default -> -1;
        };
    }

    /**
     * How a screen's own root layout reads, from the root manager the
     * editor and XML generator share: its class, orientation and
     * background when set.
     */
    public static String rootDescription(String scId,
                                         ProjectFileBean activity) {
        try {
            InjectRootLayoutManager manager =
                    new InjectRootLayoutManager(scId);
            InjectRootLayoutManager.Root root =
                    manager.getLayoutByFileName(activity.getXmlName());
            StringBuilder text = new StringBuilder(root.getClassName());
            java.util.Map<String, String> attributes = root.getAttributes();
            if (attributes != null) {
                String orientation = attributes.get("android:orientation");
                if (orientation != null) {
                    text.append(", ").append(orientation);
                }
                String background = attributes.get("android:background");
                if (background != null) {
                    text.append(", background ").append(background);
                }
            }
            return text.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Display name of any editable screen file. */
    public static String screenName(ProjectFileBean file) {
        return file.fileType == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY
                ? activityName(file) : file.fileName;
    }

    /**
     * Finds any screen the view editor can open — an activity or a custom
     * view — by the name a user or model would use. Activities win ties.
     */
    public static ProjectFileBean findScreen(String scId, String asked) {
        ProjectFileBean activity = findActivity(scId, asked);
        if (activity != null || asked == null) {
            return activity;
        }
        String wanted = asked.trim().toLowerCase(Locale.ROOT);
        for (ProjectFileBean view : customViews(scId)) {
            if (view.fileName.toLowerCase(Locale.ROOT).equals(wanted)
                    || view.getXmlName().toLowerCase(Locale.ROOT)
                            .equals(wanted)) {
                return view;
            }
        }
        return null;
    }

    /**
     * The widget type of a screen's own root layout. The root is not a
     * stored view bean; {@link InjectRootLayoutManager} owns it (default:
     * a vertical LinearLayout), and widgets placed directly on the screen
     * record it as their {@code parentType} — the editor's drop path does
     * exactly this in {@code ViewPane.updateViewBeanProperties}.
     */
    public static int rootParentType(String scId, ProjectFileBean activity) {
        try {
            ViewBean root = new InjectRootLayoutManager(scId)
                    .toBean(activity.getXmlName());
            if (root != null) {
                return root.type;
            }
        } catch (Exception ignored) {
            // Fall through to Sketchware's default root.
        }
        return ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
    }

    /**
     * The beans of one screen, as the editor presents them: the data
     * manager's stored list run through Sketchware's own view normalizer
     * ({@code eC.a(...)} — parents before children, siblings in index
     * order), which is what {@code ViewEditorFragment} hands the editor.
     * Data the normalizer cannot handle (a hand-made screen with a null
     * parent, say) falls back to the stored order rather than vanishing.
     */
    public static ArrayList<ViewBean> screenBeans(String scId, ProjectFileBean activity) {
        try {
            ArrayList<ViewBean> beans = jC.a(scId).d(activity.getXmlName());
            if (beans == null) {
                return new ArrayList<>();
            }
            try {
                ArrayList<ViewBean> ordered = eC.a(beans);
                return ordered == null ? beans : ordered;
            } catch (Exception ignored) {
                return beans;
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /**
     * One condensed line per widget — the only shape of a screen the model
     * ever sees. Capped, so a huge screen summarizes instead of flooding.
     */
    public static List<String> screenLines(String scId, ProjectFileBean activity,
                                           int cap) {
        ArrayList<ViewBean> beans = screenBeans(scId, activity);
        List<String> lines = new ArrayList<>();
        int shown = Math.min(beans.size(), cap);
        for (int i = 0; i < shown; i++) {
            lines.add(describe(beans.get(i)));
        }
        if (beans.size() > shown) {
            lines.add("… and " + (beans.size() - shown) + " more widgets");
        }
        return lines;
    }

    private static String describe(ViewBean bean) {
        StringBuilder line = new StringBuilder();
        line.append(bean.id).append(" · ").append(typeName(bean.type));
        if (bean.text != null && bean.text.text != null
                && !bean.text.text.trim().isEmpty()) {
            line.append(" · \"").append(bean.text.text.trim()).append("\"");
        }
        if (bean.layout != null && bean.layout.backgroundColor != 0) {
            line.append(String.format(Locale.ROOT, " · bg=#%08X",
                    bean.layout.backgroundColor));
        }
        if (bean.layout != null) {
            if (bean.layout.width
                    == com.besome.sketch.beans.LayoutBean.LAYOUT_MATCH_PARENT) {
                line.append(" · width match_parent");
            } else if (bean.layout.width
                    != com.besome.sketch.beans.LayoutBean.LAYOUT_WRAP_CONTENT) {
                line.append(" · width ").append(bean.layout.width)
                        .append("dp");
            }
            if (bean.layout.height
                    == com.besome.sketch.beans.LayoutBean.LAYOUT_MATCH_PARENT) {
                line.append(" · height match_parent");
            } else if (bean.layout.height
                    != com.besome.sketch.beans.LayoutBean.LAYOUT_WRAP_CONTENT) {
                line.append(" · height ").append(bean.layout.height)
                        .append("dp");
            }
            if (bean.layout.orientation
                    == com.besome.sketch.beans.LayoutBean.ORIENTATION_VERTICAL) {
                line.append(" · vertical");
            } else if (bean.layout.orientation
                    == com.besome.sketch.beans.LayoutBean.ORIENTATION_HORIZONTAL) {
                line.append(" · horizontal");
            }
        }
        if (bean.parent != null && !bean.parent.isEmpty()) {
            line.append(" · in ").append(bean.parent);
        }
        return line.toString();
    }

    /** Widget type names in {@link ViewBean} type order. */
    private static String typeName(int type) {
        return switch (type) {
            case ViewBean.VIEW_TYPE_LAYOUT_LINEAR -> "LinearLayout";
            case ViewBean.VIEW_TYPE_LAYOUT_RELATIVE -> "RelativeLayout";
            case ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW -> "HorizontalScrollView";
            case ViewBean.VIEW_TYPE_WIDGET_BUTTON -> "Button";
            case ViewBean.VIEW_TYPE_WIDGET_TEXTVIEW -> "TextView";
            case ViewBean.VIEW_TYPE_WIDGET_EDITTEXT -> "EditText";
            case ViewBean.VIEW_TYPE_WIDGET_IMAGEVIEW -> "ImageView";
            case ViewBean.VIEW_TYPE_WIDGET_WEBVIEW -> "WebView";
            case ViewBean.VIEW_TYPE_WIDGET_PROGRESSBAR -> "ProgressBar";
            case ViewBean.VIEW_TYPE_WIDGET_LISTVIEW -> "ListView";
            case ViewBean.VIEW_TYPE_WIDGET_SPINNER -> "Spinner";
            case ViewBean.VIEW_TYPE_WIDGET_CHECKBOX -> "CheckBox";
            case ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW -> "ScrollView";
            case ViewBean.VIEW_TYPE_WIDGET_SWITCH -> "Switch";
            case ViewBean.VIEW_TYPE_WIDGET_SEEKBAR -> "SeekBar";
            case ViewBean.VIEW_TYPE_WIDGET_CALENDARVIEW -> "CalendarView";
            case ViewBean.VIEW_TYPE_WIDGET_FAB -> "FloatingActionButton";
            case ViewBean.VIEW_TYPE_WIDGET_ADVIEW -> "AdView";
            case ViewBean.VIEW_TYPE_WIDGET_MAPVIEW -> "MapView";
            default -> {
                String extended = mod.agus.jcoderz.beans.ViewBeans
                        .getViewTypeName(type);
                yield extended.isEmpty() ? "View(" + type + ")" : extended;
            }
        };
    }

    private static String text(HashMap<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
