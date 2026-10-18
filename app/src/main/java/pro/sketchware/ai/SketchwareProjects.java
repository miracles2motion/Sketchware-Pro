package pro.sketchware.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import a.a.a.jC;
import a.a.a.lC;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;

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

    /** The beans of one screen, exactly as the editor loads them. */
    public static ArrayList<ViewBean> screenBeans(String scId, ProjectFileBean activity) {
        try {
            ArrayList<ViewBean> beans = jC.a(scId).d(activity.getXmlName());
            return beans == null ? new ArrayList<>() : beans;
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
            default -> "View(" + type + ")";
        };
    }

    private static String text(HashMap<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
