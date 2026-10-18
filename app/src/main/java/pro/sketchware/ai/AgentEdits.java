package pro.sketchware.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import a.a.a.wq;
import com.besome.sketch.beans.ViewBean;

/**
 * The screen-editing engine. A list of small operations is first
 * <em>simulated</em> against a copy of the screen — every operation
 * validated against the tree as the earlier operations leave it — and
 * only a fully valid simulation can become an approval card, and later
 * the saved screen. The functions here never touch the disk.
 */
public final class AgentEdits {

    /** Hard cap on operations in one edit, per the risk register. */
    public static final int MAX_OPS = 12;

    private static final Pattern ID_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Pattern COLOR_PATTERN =
            Pattern.compile("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?");
    private static final Gson GSON = new Gson();

    private AgentEdits() {
    }

    /** One simulated edit: the resulting screen and its description. */
    public static final class Outcome {
        public final ArrayList<ViewBean> beans;
        public final List<String> lines;
        /** The ops with app-allocated ids frozen in. */
        public final JsonArray frozenOps;

        Outcome(ArrayList<ViewBean> beans, List<String> lines,
                JsonArray frozenOps) {
            this.beans = beans;
            this.lines = lines;
            this.frozenOps = frozenOps;
        }
    }

    /** A stable fingerprint of a screen, for the changed-since gate. */
    public static String revisionOf(ArrayList<ViewBean> beans) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(GSON.toJson(beans).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** The canonical form used for save verification. */
    public static String canonical(ArrayList<ViewBean> beans) {
        return GSON.toJson(beans);
    }

    public static ArrayList<ViewBean> deepCopy(ArrayList<ViewBean> beans) {
        return GSON.fromJson(GSON.toJson(beans),
                new TypeToken<ArrayList<ViewBean>>() {
                }.getType());
    }

    /**
     * Validates {@code ops} against {@code base} and returns the result.
     * When {@code frozen} is true, add operations must already carry the
     * ids allocated by the proposal simulation (execution path).
     */
    public static Outcome simulate(String scId, ArrayList<ViewBean> base,
                                   JsonArray ops, boolean frozen)
            throws AgentTools.ToolException {
        ArrayList<ViewBean> work = deepCopy(base);
        Map<String, ViewBean> byId = new HashMap<>();
        for (ViewBean bean : work) {
            byId.put(bean.id, bean);
        }
        List<String> lines = new ArrayList<>();
        JsonArray frozenOps = new JsonArray();

        for (JsonElement element : ops) {
            if (!element.isJsonObject()) {
                throw new AgentTools.ToolException("E_BAD_OP",
                        "Every operation must be an object.");
            }
            JsonObject op = element.getAsJsonObject().deepCopy();
            String kind = normalizeKind(op);
            if ("add_widget".equals(kind)) {
                prepareRoot(op, work, byId, lines, frozenOps, frozen);
            }
            switch (kind) {
                case "add_widget" -> applyAdd(scId, op, work, byId, lines, frozen);
                case "set_text" -> {
                    ViewBean bean = requireWidget(op, byId);
                    String text = stringOf(op, "text");
                    String old = bean.text.text == null ? "" : bean.text.text;
                    bean.text.text = text;
                    lines.add("Change " + bean.id + " text from “"
                            + shorten(old) + "” to “" + shorten(text) + "”");
                }
                case "set_hint" -> {
                    ViewBean bean = requireWidget(op, byId);
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_EDITTEXT) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only an EditText has a hint: " + bean.id
                                        + " is a " + typeName(bean.type) + ".");
                    }
                    bean.text.hint = stringOf(op, "hint");
                    lines.add("Set " + bean.id + " hint to “"
                            + shorten(bean.text.hint) + "”");
                }
                case "set_background" -> {
                    ViewBean bean = requireWidget(op, byId);
                    int color = colorOf(scId, op);
                    bean.layout.backgroundColor = color;
                    lines.add("Set " + bean.id + " background to "
                            + colorWord(scId, op, color));
                }
                case "set_text_color" -> {
                    ViewBean bean = requireWidget(op, byId);
                    int color = colorOf(scId, op);
                    bean.text.textColor = color;
                    lines.add("Set " + bean.id + " text colour to "
                            + colorWord(scId, op, color));
                }
                case "set_text_size" -> {
                    ViewBean bean = requireWidget(op, byId);
                    int size = intOf(op, "size", 0);
                    if (size < 6 || size > 72) {
                        throw new AgentTools.ToolException("E_BAD_SIZE",
                                "Text size must be between 6 and 72.");
                    }
                    bean.text.textSize = size;
                    lines.add("Set " + bean.id + " text size to " + size);
                }
                case "remove_widget" -> {
                    ViewBean bean = requireWidget(op, byId);
                    Set<String> removed = subtreeIds(bean.id, work);
                    work.removeIf(candidate -> removed.contains(candidate.id));
                    for (String id : removed) {
                        byId.remove(id);
                    }
                    lines.add(removed.size() > 1
                            ? "Remove " + bean.id + " and "
                                    + (removed.size() - 1)
                                    + " widget(s) inside it"
                            : "Remove " + bean.id);
                }
                case "move_widget" -> {
                    ViewBean bean = requireWidget(op, byId);
                    String parentId = stringOf(op, "parent");
                    if ("root".equalsIgnoreCase(parentId)) {
                        ViewBean root = rootOf(work);
                        parentId = root == null ? parentId : root.id;
                    }
                    ViewBean parent = byId.get(parentId);
                    if (parent == null) {
                        throw new AgentTools.ToolException("E_NO_PARENT",
                                "There is no widget called " + parentId
                                        + " to move into.");
                    }
                    if (!isContainer(parent.type)) {
                        throw new AgentTools.ToolException("E_BAD_PARENT",
                                typeName(parent.type) + " cannot hold other "
                                        + "widgets.");
                    }
                    if (subtreeIds(bean.id, work).contains(parentId)) {
                        throw new AgentTools.ToolException("E_CYCLE",
                                "A widget cannot be moved inside itself or "
                                        + "its children.");
                    }
                    bean.parent = parentId;
                    bean.parentType = parent.type;
                    lines.add("Move " + bean.id + " into " + parentId);
                }
                default -> throw new AgentTools.ToolException("E_BAD_OP",
                        "Unknown operation \"" + kind + "\". Every "
                                + "operation is an object with an \"op\" "
                                + "field, e.g. {\"op\":\"add_widget\","
                                + "\"type\":\"Button\",\"text\":\"Save\"}. "
                                + "Valid ops: add_widget, set_text, set_hint, "
                                + "set_background, set_text_color, "
                                + "set_text_size, remove_widget, move_widget.");
            }
            frozenOps.add(op);
        }

        if (!work.isEmpty()) {
            int roots = 0;
            for (ViewBean bean : work) {
                if (bean.parent == null || bean.parent.isEmpty()) {
                    roots++;
                }
                if (bean.parent != null && !bean.parent.isEmpty()
                        && !byId.containsKey(bean.parent)) {
                    throw new AgentTools.ToolException("E_TREE",
                            bean.id + " would be left without a parent.");
                }
            }
            if (roots != 1) {
                throw new AgentTools.ToolException("E_TREE",
                        "A screen needs exactly one top-level layout; this "
                                + "edit would leave " + roots + ".");
            }
        }
        return new Outcome(work, lines, frozenOps);
    }

    /**
     * Screens always have one root layout; the editor simply shows it.
     * A brand-new screen has none saved yet, so the first widget that
     * needs one plants it — as its own approved line, frozen into the
     * ops so execution replays exactly what was approved.
     */
    private static void prepareRoot(JsonObject op, ArrayList<ViewBean> work,
                                    Map<String, ViewBean> byId,
                                    List<String> lines,
                                    JsonArray frozenOps, boolean frozen)
            throws AgentTools.ToolException {
        if (rootOf(work) != null) {
            return;
        }
        String parentId = stringOf(op, "parent");
        if (!parentId.isEmpty() && !"root".equalsIgnoreCase(parentId)) {
            return;
        }
        String typeName = stringOf(op, "type");
        Integer type = typeByName(typeName);
        if (type != null && isContainer(type) && parentId.isEmpty()) {
            return; // the widget itself becomes the root
        }
        if (frozen) {
            // The proposal's first frozen op plants the root; when it is
            // replayed this add sees it and plants nothing more.
            return;
        }
        String rootId = allocateId(ViewBean.VIEW_TYPE_LAYOUT_LINEAR,
                byId.keySet());
        JsonObject rootOp = new JsonObject();
        rootOp.addProperty("op", "add_widget");
        rootOp.addProperty("type", "LinearLayout");
        rootOp.addProperty("id", rootId);
        frozenOps.add(rootOp);
        ViewBean root = new ViewBean(rootId, ViewBean.VIEW_TYPE_LAYOUT_LINEAR);
        work.add(root);
        byId.put(rootId, root);
        lines.add("Add LinearLayout as the screen's layout");
    }

    private static void applyAdd(String scId, JsonObject op,
                                 ArrayList<ViewBean> work,
                                 Map<String, ViewBean> byId,
                                 List<String> lines, boolean frozen)
            throws AgentTools.ToolException {
        String typeName = stringOf(op, "type");
        Integer type = typeByName(typeName);
        if (type == null) {
            throw new AgentTools.ToolException("E_BAD_TYPE",
                    "Unknown widget type \"" + typeName + "\".");
        }
        String parentId = stringOf(op, "parent");
        ViewBean parent = null;
        if (parentId.isEmpty() || "root".equalsIgnoreCase(parentId)) {
            parent = rootOf(work);
            if (parent == null && !isContainer(type)) {
                throw new AgentTools.ToolException("E_NEEDS_LAYOUT",
                        "The screen is empty: add a layout (e.g. "
                                + "LinearLayout) before adding widgets.");
            }
        } else {
            parent = byId.get(parentId);
            if (parent == null) {
                throw new AgentTools.ToolException("E_NO_PARENT",
                        "There is no widget called " + parentId
                                + ". A widget can only be added to a layout "
                                + "that exists or was added earlier in this "
                                + "same edit.");
            }
            if (!isContainer(parent.type)) {
                throw new AgentTools.ToolException("E_BAD_PARENT",
                        typeName(parent.type) + " cannot hold other widgets; "
                                + "add to a layout instead.");
            }
        }

        String id = stringOf(op, "id");
        if (frozen) {
            if (id.isEmpty() || byId.containsKey(id)) {
                throw new AgentTools.ToolException("E_DUPLICATE_ID",
                        "The widget id " + id + " is no longer free.");
            }
        } else if (!id.isEmpty()) {
            if (!ID_PATTERN.matcher(id).matches()) {
                throw new AgentTools.ToolException("E_BAD_ID",
                        "Widget ids start with a letter and use letters, "
                                + "numbers and underscores.");
            }
            if (byId.containsKey(id)) {
                throw new AgentTools.ToolException("E_DUPLICATE_ID",
                        "There is already a widget called " + id + ".");
            }
        } else {
            id = allocateId(type, byId.keySet());
            op.addProperty("id", id);
        }

        ViewBean bean = new ViewBean(id, type);
        bean.parent = parent == null ? null : parent.id;
        bean.parentType = parent == null ? -1 : parent.type;
        String text = stringOf(op, "text");
        if (!text.isEmpty()) {
            bean.text.text = text;
        }
        String background = stringOf(op, "background");
        String colorNote = "";
        if (!background.isEmpty()) {
            int color = parseColor(scId, background);
            bean.layout.backgroundColor = color;
            colorNote = ", background " + colorWord(scId, background, color);
        }
        work.add(bean);
        byId.put(id, bean);
        lines.add("Add " + typeName(type)
                + (text.isEmpty() ? "" : " “" + shorten(text) + "”")
                + (parent == null ? " as the screen layout"
                        : " in " + parent.id)
                + colorNote);
    }

    /**
     * Models do not always hit the exact envelope. Where the intent is
     * unambiguous — an aliased op name, an action/operation key, a bare
     * widget description — the app normalizes instead of failing.
     */
    private static String normalizeKind(JsonObject op) {
        String kind = stringOf(op, "op");
        if (kind.isEmpty()) {
            kind = stringOf(op, "operation");
        }
        if (kind.isEmpty()) {
            kind = stringOf(op, "action");
        }
        if (kind.isEmpty() && op.has("type")) {
            op.addProperty("op", "add_widget");
            return "add_widget";
        }
        String key = kind.toLowerCase(Locale.ROOT)
                .replace("_", "").replace(" ", "");
        String canonical = switch (key) {
            case "addwidget", "add", "createwidget", "addview",
                    "insertwidget", "newwidget" -> "add_widget";
            case "settext", "changetext", "updatetext" -> "set_text";
            case "sethint" -> "set_hint";
            case "setbackground", "setbackgroundcolor",
                    "setbackgroundcolour" -> "set_background";
            case "settextcolor", "settextcolour" -> "set_text_color";
            case "settextsize" -> "set_text_size";
            case "removewidget", "deletewidget", "remove", "delete" ->
                    "remove_widget";
            case "movewidget", "move", "reparent" -> "move_widget";
            default -> kind;
        };
        if (!canonical.equals(kind)) {
            op.addProperty("op", canonical);
        }
        return canonical;
    }

    /** The target widget of an op: "id", or the common "widget" alias. */
    private static String widgetIdOf(JsonObject op) {
        String id = stringOf(op, "id");
        return id.isEmpty() ? stringOf(op, "widget") : id;
    }

    // -- vocabulary --------------------------------------------------------

    private static final Map<String, Integer> TYPE_BY_NAME = new HashMap<>();

    static {
        TYPE_BY_NAME.put("LinearLayout", ViewBean.VIEW_TYPE_LAYOUT_LINEAR);
        TYPE_BY_NAME.put("RelativeLayout", ViewBean.VIEW_TYPE_LAYOUT_RELATIVE);
        TYPE_BY_NAME.put("HorizontalScrollView",
                ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW);
        TYPE_BY_NAME.put("ScrollView", ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW);
        TYPE_BY_NAME.put("Button", ViewBean.VIEW_TYPE_WIDGET_BUTTON);
        TYPE_BY_NAME.put("TextView", ViewBean.VIEW_TYPE_WIDGET_TEXTVIEW);
        TYPE_BY_NAME.put("EditText", ViewBean.VIEW_TYPE_WIDGET_EDITTEXT);
        TYPE_BY_NAME.put("ImageView", ViewBean.VIEW_TYPE_WIDGET_IMAGEVIEW);
        TYPE_BY_NAME.put("WebView", ViewBean.VIEW_TYPE_WIDGET_WEBVIEW);
        TYPE_BY_NAME.put("ProgressBar", ViewBean.VIEW_TYPE_WIDGET_PROGRESSBAR);
        TYPE_BY_NAME.put("ListView", ViewBean.VIEW_TYPE_WIDGET_LISTVIEW);
        TYPE_BY_NAME.put("Spinner", ViewBean.VIEW_TYPE_WIDGET_SPINNER);
        TYPE_BY_NAME.put("CheckBox", ViewBean.VIEW_TYPE_WIDGET_CHECKBOX);
        TYPE_BY_NAME.put("Switch", ViewBean.VIEW_TYPE_WIDGET_SWITCH);
        TYPE_BY_NAME.put("SeekBar", ViewBean.VIEW_TYPE_WIDGET_SEEKBAR);
        TYPE_BY_NAME.put("CalendarView", ViewBean.VIEW_TYPE_WIDGET_CALENDARVIEW);
        TYPE_BY_NAME.put("FloatingActionButton", ViewBean.VIEW_TYPE_WIDGET_FAB);
    }

    private static Integer typeByName(String name) {
        return TYPE_BY_NAME.get(name);
    }

    /** Widget type names in {@link ViewBean} type order. */
    public static String typeName(int type) {
        for (Map.Entry<String, Integer> entry : TYPE_BY_NAME.entrySet()) {
            if (entry.getValue() == type) {
                return entry.getKey();
            }
        }
        return "View(" + type + ")";
    }

    public static boolean isContainer(int type) {
        return type == ViewBean.VIEW_TYPE_LAYOUT_LINEAR
                || type == ViewBean.VIEW_TYPE_LAYOUT_RELATIVE
                || type == ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW
                || type == ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW;
    }

    /** The same prefix-plus-counter naming the app itself uses. */
    private static String allocateId(int type, Set<String> used) {
        String prefix = wq.b(type);
        int counter = 1;
        while (used.contains(prefix + counter)) {
            counter++;
        }
        return prefix + counter;
    }

    // -- colours -----------------------------------------------------------

    /**
     * Resolves a colour argument: {@code #RRGGBB}/{@code #AARRGGBB}, or a
     * project token — {@code @primary}, {@code @primary_dark},
     * {@code @accent} — resolved from the project's own theme colours.
     */
    public static int parseColor(String scId, String value)
            throws AgentTools.ToolException {
        String color = value == null ? "" : value.trim();
        if (color.startsWith("@")) {
            int token = SketchwareProjects.projectColor(scId,
                    switch (color.toLowerCase(Locale.ROOT)) {
                        case "@primary" -> "color_primary";
                        case "@primary_dark" -> "color_primary_dark";
                        case "@accent" -> "color_accent";
                        default -> null;
                    });
            if (token == Integer.MIN_VALUE) {
                throw new AgentTools.ToolException("E_BAD_COLOR",
                        "Unknown colour token \"" + color + "\". Use "
                                + "@primary, @primary_dark, @accent or a hex "
                                + "colour like #3F51B5.");
            }
            return token;
        }
        if (!COLOR_PATTERN.matcher(color).matches()) {
            throw new AgentTools.ToolException("E_BAD_COLOR",
                    "Colours look like #3F51B5, #FF3F51B5, @primary, "
                            + "@primary_dark or @accent.");
        }
        String hex = color.substring(1);
        if (hex.length() == 6) {
            hex = "FF" + hex;
        }
        return (int) Long.parseLong(hex, 16);
    }

    private static int colorOf(String scId, JsonObject op)
            throws AgentTools.ToolException {
        return parseColor(scId, stringOf(op, "color"));
    }

    /** How a colour reads on an approval card: token name or hex. */
    private static String colorWord(String scId, JsonObject op, int color) {
        return colorWord(scId, stringOf(op, "color"), color);
    }

    private static String colorWord(String scId, String raw, int color) {
        String hex = String.format(Locale.ROOT, "#%08X", color);
        return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
            case "@primary" -> "Primary (" + hex + ")";
            case "@primary_dark" -> "Primary dark (" + hex + ")";
            case "@accent" -> "Accent (" + hex + ")";
            default -> "colour " + hex;
        };
    }

    // -- small helpers ------------------------------------------------------

    private static ViewBean requireWidget(JsonObject op,
                                          Map<String, ViewBean> byId)
            throws AgentTools.ToolException {
        String id = widgetIdOf(op);
        ViewBean bean = byId.get(id);
        if (bean == null) {
            throw new AgentTools.ToolException("E_NO_WIDGET",
                    "There is no widget called " + id + " on this screen.");
        }
        return bean;
    }

    private static ViewBean rootOf(List<ViewBean> beans) {
        for (ViewBean bean : beans) {
            if (bean.parent == null || bean.parent.isEmpty()) {
                return bean;
            }
        }
        return null;
    }

    /** The widget and everything inside it, transitively. */
    private static Set<String> subtreeIds(String id, List<ViewBean> beans) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(id);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ViewBean bean : beans) {
                if (bean.parent != null && ids.contains(bean.parent)
                        && ids.add(bean.id)) {
                    grew = true;
                }
            }
        }
        return ids;
    }

    private static String stringOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString().trim();
    }

    private static int intOf(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 24 ? text : text.substring(0, 24) + "…";
    }
}
