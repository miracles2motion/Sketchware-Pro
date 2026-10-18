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
import com.besome.sketch.beans.LayoutBean;
import com.besome.sketch.beans.ViewBean;
import mod.agus.jcoderz.beans.ViewBeans;

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

    /**
     * The screen itself is a virtual root in Sketchware's data model: it is
     * not one of the stored {@link ViewBean}s. Widgets placed directly on a
     * screen carry this literal parent id (see {@code ViewBeanParser} and
     * the palette drop path in {@code ViewEditor}); Sketchware's own view
     * normalizer dereferences it with {@code bean.parent.equals("root")}.
     */
    private static final String ROOT_PARENT = "root";

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
        return simulate(scId, base, ops, frozen,
                inferRootParentType(base));
    }

    public static Outcome simulate(String scId, ArrayList<ViewBean> base,
                                   JsonArray ops, boolean frozen,
                                   int rootParentType)
            throws AgentTools.ToolException {
        ArrayList<ViewBean> work = deepCopy(base);
        Map<String, ViewBean> byId = new HashMap<>();
        for (ViewBean bean : work) {
            // Older or hand-made data can carry a null/empty parent for a
            // top-level widget. Sketchware's stored form is the literal
            // "root" parent; normalize the copy so the saved screen uses
            // the same shape the editor itself writes.
            if (bean.parent == null || bean.parent.isEmpty()
                    || ROOT_PARENT.equalsIgnoreCase(bean.parent)) {
                bean.parent = ROOT_PARENT;
                if (bean.parentType < 0) {
                    bean.parentType = rootParentType;
                }
            }
            if (bean.convert == null) {
                // The XML generator dereferences convert directly; the
                // empty string is its "use the widget class" form.
                bean.convert = "";
            }
            byId.put(bean.id, bean);
        }
        List<String> lines = new ArrayList<>();
        JsonArray frozenOps = new JsonArray();
        // Container-first standard: building into an EMPTY screen starts
        // with a real main layout the user can select and restyle in the
        // editor (the editor's own root is fixed and untouchable), and
        // everything else is built inside it. The container is inserted
        // and frozen like any other op, so the approval card shows it
        // and execution replays it exactly. Screens that already have
        // widgets are never restructured.
        boolean buildingFromEmpty = work.isEmpty() && !frozen;
        String mainContainerId = null;

        for (JsonElement element : ops) {
            if (!element.isJsonObject()) {
                throw new AgentTools.ToolException("E_BAD_OP",
                        "Every operation must be an object.");
            }
            JsonObject op = element.getAsJsonObject().deepCopy();
            String kind = normalizeKind(op);
            boolean preHandled = false;
            boolean frozenAdded = false;
            if (buildingFromEmpty && "add_widget".equals(kind)) {
                String parentField = stringOf(op, "parent");
                boolean toRoot = parentField.isEmpty()
                        || ROOT_PARENT.equalsIgnoreCase(parentField);
                Integer addedType = typeByName(stringOf(op, "type"));
                if (toRoot && addedType != null
                        && addedType != ViewBean.VIEW_TYPE_WIDGET_FAB) {
                    boolean scroll = addedType
                                    == ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW
                            || addedType
                                    == ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW;
                    if (mainContainerId == null && scroll) {
                        // A scrolling page: the ScrollView can hold only
                        // one child, so the main layout goes inside it.
                        applyAdd(scId, op, work, byId, lines, frozen,
                                rootParentType);
                        frozenOps.add(op);
                        frozenAdded = true;
                        preHandled = true;
                        mainContainerId = synthesizeMainLayout(scId,
                                work.get(work.size() - 1).id, work, byId,
                                lines, frozenOps, rootParentType);
                    } else if (mainContainerId == null
                            && !isContainer(addedType)) {
                        mainContainerId = synthesizeMainLayout(scId, null,
                                work, byId, lines, frozenOps,
                                rootParentType);
                        op.addProperty("parent", mainContainerId);
                    } else if (mainContainerId != null) {
                        op.addProperty("parent", mainContainerId);
                    }
                }
            }
            switch (kind) {
                case "add_widget" -> {
                    if (!preHandled) {
                        applyAdd(scId, op, work, byId, lines, frozen,
                                rootParentType);
                    }
                }
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
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_EDITTEXT
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_AUTOCOMPLETETEXTVIEW
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_MULTIAUTOCOMPLETETEXTVIEW) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only a text input has a hint: " + bean.id
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
                case "set_size" -> {
                    // layout.width/height, the property editor's own
                    // encoding: -1 match_parent, -2 wrap_content, else dp.
                    ViewBean bean = requireWidget(op, byId);
                    if (op.has("width")) {
                        int width = sizeOf(op, "width");
                        bean.layout.width = width;
                        lines.add("Set " + bean.id + " width to "
                                + sizeWord(width));
                    }
                    if (op.has("height")) {
                        int height = sizeOf(op, "height");
                        bean.layout.height = height;
                        lines.add("Set " + bean.id + " height to "
                                + sizeWord(height));
                    }
                    if (!op.has("width") && !op.has("height")) {
                        throw new AgentTools.ToolException("E_BAD_OP",
                                "set_size needs a width and/or height: "
                                        + "\"match_parent\", \"wrap_content\" "
                                        + "or a dp number.");
                    }
                }
                case "set_orientation" -> {
                    ViewBean bean = requireWidget(op, byId);
                    String value = stringOf(op, "orientation")
                            .toLowerCase(Locale.ROOT);
                    int orientation = switch (value) {
                        case "vertical" -> LayoutBean.ORIENTATION_VERTICAL;
                        case "horizontal" -> LayoutBean.ORIENTATION_HORIZONTAL;
                        default -> throw new AgentTools.ToolException(
                                "E_BAD_VALUE",
                                "Orientation is \"vertical\" or \"horizontal\".");
                    };
                    bean.layout.orientation = orientation;
                    lines.add("Make " + bean.id + " stack " + value);
                }
                case "set_padding" -> {
                    ViewBean bean = requireWidget(op, byId);
                    int[] padding = boxOf(op, bean.layout.paddingLeft,
                            bean.layout.paddingTop, bean.layout.paddingRight,
                            bean.layout.paddingBottom);
                    bean.layout.paddingLeft = padding[0];
                    bean.layout.paddingTop = padding[1];
                    bean.layout.paddingRight = padding[2];
                    bean.layout.paddingBottom = padding[3];
                    lines.add("Set " + bean.id + " padding to "
                            + boxWord(padding));
                }
                case "set_margin" -> {
                    ViewBean bean = requireWidget(op, byId);
                    int[] margin = boxOf(op, bean.layout.marginLeft,
                            bean.layout.marginTop, bean.layout.marginRight,
                            bean.layout.marginBottom);
                    bean.layout.marginLeft = margin[0];
                    bean.layout.marginTop = margin[1];
                    bean.layout.marginRight = margin[2];
                    bean.layout.marginBottom = margin[3];
                    lines.add("Set " + bean.id + " margin to "
                            + boxWord(margin));
                }
                case "set_gravity" -> {
                    ViewBean bean = requireWidget(op, byId);
                    int gravity = gravityOf(op);
                    if (op.has("layout_gravity")
                            || "layout".equalsIgnoreCase(
                                    stringOf(op, "target"))) {
                        bean.layout.layoutGravity = gravity;
                        lines.add("Set " + bean.id + " position gravity to "
                                + stringOf(op, "gravity"));
                    } else {
                        bean.layout.gravity = gravity;
                        lines.add("Set " + bean.id + " content gravity to "
                                + stringOf(op, "gravity"));
                    }
                }
                case "set_weight" -> {
                    ViewBean bean = requireWidget(op, byId);
                    int weight = intOf(op, "weight", -1);
                    if (weight < 0 || weight > 100) {
                        throw new AgentTools.ToolException("E_BAD_VALUE",
                                "Weight is a whole number from 0 to 100.");
                    }
                    bean.layout.weight = weight;
                    lines.add("Set " + bean.id + " weight to " + weight);
                }
                case "set_image" -> {
                    // The property editor's image field is image.resName.
                    ViewBean bean = requireWidget(op, byId);
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_IMAGEVIEW
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_CIRCLEIMAGEVIEW) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only an ImageView has an image: " + bean.id
                                        + " is a " + typeName(bean.type)
                                        + ".");
                    }
                    String image = stringOf(op, "image");
                    if (!ID_PATTERN.matcher(image).matches()) {
                        throw new AgentTools.ToolException("E_BAD_VALUE",
                                "An image name uses letters, numbers and "
                                        + "underscores, like my_photo.");
                    }
                    bean.image.resName = image;
                    lines.add("Set " + bean.id + " image to " + image);
                }
                case "set_scale_type" -> {
                    ViewBean bean = requireWidget(op, byId);
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_IMAGEVIEW
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_CIRCLEIMAGEVIEW) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only an ImageView has a scale type: "
                                        + bean.id + " is a "
                                        + typeName(bean.type) + ".");
                    }
                    String value = stringOf(op, "scale_type").trim()
                            .toUpperCase(Locale.ROOT).replace(' ', '_');
                    String scaleType = switch (value) {
                        case "CENTER", "CENTER_CROP", "CENTER_INSIDE",
                                "FIT_CENTER", "FIT_START", "FIT_END",
                                "FIT_XY" -> value;
                        default -> throw new AgentTools.ToolException(
                                "E_BAD_VALUE",
                                "Scale type is one of: center, center_crop, "
                                        + "center_inside, fit_center, "
                                        + "fit_start, fit_end, fit_xy.");
                    };
                    bean.image.scaleType = scaleType;
                    lines.add("Set " + bean.id + " scale type to "
                            + scaleType.toLowerCase(Locale.ROOT));
                }
                case "set_background_image" -> {
                    // layout.backgroundResource, the property editor's
                    // background-image field.
                    ViewBean bean = requireWidget(op, byId);
                    String image = stringOf(op, "image");
                    if (!ID_PATTERN.matcher(image).matches()) {
                        throw new AgentTools.ToolException("E_BAD_VALUE",
                                "An image name uses letters, numbers and "
                                        + "underscores, like my_photo.");
                    }
                    bean.layout.backgroundResource = image;
                    lines.add("Set " + bean.id + " background image to "
                            + image);
                }
                case "set_checked" -> {
                    ViewBean bean = requireWidget(op, byId);
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_CHECKBOX
                            && bean.type != ViewBean.VIEW_TYPE_WIDGET_SWITCH
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_RADIOBUTTON) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only a CheckBox, Switch or RadioButton can "
                                        + "be checked: " + bean.id + " is a "
                                        + typeName(bean.type) + ".");
                    }
                    boolean checked = boolOf(op, "checked");
                    bean.checked = checked ? 1 : 0;
                    lines.add((checked ? "Check " : "Uncheck ") + bean.id);
                }
                case "set_enabled" -> {
                    ViewBean bean = requireWidget(op, byId);
                    boolean enabled = boolOf(op, "enabled");
                    bean.enabled = enabled ? 1 : 0;
                    lines.add((enabled ? "Enable " : "Disable ") + bean.id);
                }
                case "set_text_style" -> {
                    ViewBean bean = requireWidget(op, byId);
                    String value = stringOf(op, "style").trim()
                            .toLowerCase(Locale.ROOT).replace(' ', '_');
                    int textType = switch (value) {
                        case "normal" -> com.besome.sketch.beans.TextBean
                                .TEXT_TYPE_NORMAL;
                        case "bold" -> com.besome.sketch.beans.TextBean
                                .TEXT_TYPE_BOLD;
                        case "italic" -> com.besome.sketch.beans.TextBean
                                .TEXT_TYPE_ITALIC;
                        case "bold_italic" -> com.besome.sketch.beans.TextBean
                                .TEXT_TYPE_BOLDITALIC;
                        default -> throw new AgentTools.ToolException(
                                "E_BAD_VALUE",
                                "Text style is normal, bold, italic or "
                                        + "bold_italic.");
                    };
                    bean.text.textType = textType;
                    lines.add("Make " + bean.id + " text " + value);
                }
                case "set_list_item_view" -> {
                    // bean.customView holds a custom view's file name;
                    // the code generator builds the adapter from it.
                    ViewBean bean = requireWidget(op, byId);
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_LISTVIEW
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_GRIDVIEW
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_RECYCLERVIEW
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_LAYOUT_VIEWPAGER) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only a ListView, GridView, RecyclerView or "
                                        + "ViewPager uses an item layout: "
                                        + bean.id + " is a "
                                        + typeName(bean.type) + ".");
                    }
                    String customView = stringOf(op, "custom_view");
                    if (customView.equals("none") || customView.isEmpty()) {
                        bean.customView = "";
                        lines.add("Clear " + bean.id + "'s item layout");
                    } else {
                        boolean exists = false;
                        for (com.besome.sketch.beans.ProjectFileBean view
                                : SketchwareProjects.customViews(scId)) {
                            if (view.fileName.equals(customView)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            throw new AgentTools.ToolException("E_BAD_VALUE",
                                    "The project has no custom view called "
                                            + customView + ".");
                        }
                        bean.customView = customView;
                        lines.add("Set " + bean.id + " item layout to "
                                + customView);
                    }
                }
                case "set_font" -> {
                    // text.textFont holds a project font name; the
                    // property editor only offers fonts the project has.
                    ViewBean bean = requireWidget(op, byId);
                    String font = stringOf(op, "font");
                    if (!font.equals("default_font")
                            && !SketchwareProjects.resources(scId)
                                    .fonts.contains(font)) {
                        throw new AgentTools.ToolException("E_BAD_VALUE",
                                "The project has no font called " + font
                                        + ". Use list_resources to see "
                                        + "its fonts, or \"default_font\".");
                    }
                    bean.text.textFont = font;
                    lines.add("Set " + bean.id + " font to " + font);
                }
                case "set_progress" -> {
                    // bean.progress / bean.max, the property editor's
                    // own fields for progress widgets.
                    ViewBean bean = requireWidget(op, byId);
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_PROGRESSBAR
                            && bean.type != ViewBean.VIEW_TYPE_WIDGET_SEEKBAR) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only a ProgressBar or SeekBar has "
                                        + "progress: " + bean.id + " is a "
                                        + typeName(bean.type) + ".");
                    }
                    if (op.has("max")) {
                        int max = intOf(op, "max", 0);
                        if (max < 1 || max > 100000) {
                            throw new AgentTools.ToolException("E_BAD_VALUE",
                                    "Max is a whole number from 1 to "
                                            + "100000.");
                        }
                        bean.max = max;
                        lines.add("Set " + bean.id + " maximum to " + max);
                    }
                    if (op.has("progress")) {
                        int progress = intOf(op, "progress", -1);
                        if (progress < 0 || progress > 100000) {
                            throw new AgentTools.ToolException("E_BAD_VALUE",
                                    "Progress is a whole number from 0.");
                        }
                        bean.progress = progress;
                        lines.add("Set " + bean.id + " progress to "
                                + progress);
                    }
                    if (!op.has("max") && !op.has("progress")) {
                        throw new AgentTools.ToolException("E_BAD_OP",
                                "set_progress needs a progress and/or max.");
                    }
                }
                case "set_indeterminate" -> {
                    ViewBean bean = requireWidget(op, byId);
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_PROGRESSBAR) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only a ProgressBar can spin indefinitely: "
                                        + bean.id + " is a "
                                        + typeName(bean.type) + ".");
                    }
                    boolean indeterminate = boolOf(op, "indeterminate");
                    bean.indeterminate = String.valueOf(indeterminate);
                    lines.add(indeterminate
                            ? "Make " + bean.id + " spin indefinitely"
                            : "Make " + bean.id + " show real progress");
                }
                case "set_alpha" -> {
                    ViewBean bean = requireWidget(op, byId);
                    float alpha;
                    try {
                        alpha = Float.parseFloat(
                                stringOf(op, "alpha").trim());
                    } catch (NumberFormatException e) {
                        throw new AgentTools.ToolException("E_BAD_VALUE",
                                "Alpha is a number from 0 (invisible) to 1 "
                                        + "(fully visible).");
                    }
                    if (alpha < 0f || alpha > 1f) {
                        throw new AgentTools.ToolException("E_BAD_VALUE",
                                "Alpha is a number from 0 (invisible) to 1 "
                                        + "(fully visible).");
                    }
                    bean.alpha = alpha;
                    lines.add("Set " + bean.id + " opacity to " + alpha);
                }
                case "set_clickable" -> {
                    ViewBean bean = requireWidget(op, byId);
                    boolean clickable = boolOf(op, "clickable");
                    bean.clickable = clickable ? 1 : 0;
                    lines.add(clickable
                            ? "Make " + bean.id + " clickable"
                            : "Make " + bean.id + " ignore taps");
                }
                case "set_input_type" -> {
                    ViewBean bean = requireWidget(op, byId);
                    if (bean.type != ViewBean.VIEW_TYPE_WIDGET_EDITTEXT
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_AUTOCOMPLETETEXTVIEW
                            && bean.type
                                    != ViewBeans.VIEW_TYPE_WIDGET_MULTIAUTOCOMPLETETEXTVIEW) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only a text input has an input type: "
                                        + bean.id + " is a "
                                        + typeName(bean.type) + ".");
                    }
                    String value = stringOf(op, "input_type").trim()
                            .toLowerCase(Locale.ROOT).replace(' ', '_');
                    int inputType = switch (value) {
                        case "text" -> com.besome.sketch.beans.TextBean
                                .INPUT_TYPE_TEXT;
                        case "number" -> 2; // TYPE_CLASS_NUMBER
                        case "phone" -> com.besome.sketch.beans.TextBean
                                .INPUT_TYPE_PHONE;
                        case "password" -> com.besome.sketch.beans.TextBean
                                .INPUT_TYPE_PASSWORD;
                        case "number_decimal" -> com.besome.sketch.beans
                                .TextBean.INPUT_TYPE_NUMBER_DECIMAL;
                        case "number_signed" -> com.besome.sketch.beans
                                .TextBean.INPUT_TYPE_NUMBER_SIGNED;
                        case "number_signed_decimal" -> com.besome.sketch
                                .beans.TextBean
                                .INPUT_TYPE_NUMBER_SIGNED_DECIMAL;
                        default -> throw new AgentTools.ToolException(
                                "E_BAD_VALUE",
                                "Input type is one of: text, number, phone, "
                                        + "password, number_decimal, "
                                        + "number_signed, "
                                        + "number_signed_decimal.");
                    };
                    bean.text.inputType = inputType;
                    lines.add("Set " + bean.id + " input type to " + value);
                }
                case "set_root" -> {
                    // The screen's own layout is virtual: its class and
                    // attributes live in InjectRootLayoutManager's
                    // view_root data, not in a widget bean. Only the
                    // values are resolved and frozen here; the execute
                    // path writes them through that manager.
                    boolean changed = false;
                    if (op.has("background")) {
                        String raw = stringOf(op, "background");
                        int color = parseColor(scId, raw);
                        op.addProperty("background_hex",
                                String.format(Locale.ROOT, "#%08X", color));
                        lines.add("Set the screen background to "
                                + colorWord(scId, raw, color));
                        changed = true;
                    }
                    if (op.has("orientation")) {
                        if (rootParentType
                                != ViewBean.VIEW_TYPE_LAYOUT_LINEAR) {
                            throw new AgentTools.ToolException("E_BAD_OP",
                                    "The screen's root layout is not a "
                                            + "LinearLayout, so it has no "
                                            + "orientation to set.");
                        }
                        String value = stringOf(op, "orientation")
                                .toLowerCase(Locale.ROOT);
                        if (!value.equals("vertical")
                                && !value.equals("horizontal")) {
                            throw new AgentTools.ToolException("E_BAD_VALUE",
                                    "Orientation is \"vertical\" or "
                                            + "\"horizontal\".");
                        }
                        lines.add("Make the screen stack " + value);
                        changed = true;
                    }
                    if (!changed) {
                        throw new AgentTools.ToolException("E_BAD_OP",
                                "set_root needs a background colour and/or "
                                        + "an orientation.");
                    }
                }
                case "set_fab" -> {
                    // The floating button is not a screen widget either:
                    // it is the activity's own bean (eC's fab store).
                    // Values are validated and frozen here; the execute
                    // path writes them to that bean.
                    boolean changed = false;
                    if (op.has("icon")) {
                        String icon = stringOf(op, "icon");
                        if (!ID_PATTERN.matcher(icon).matches()) {
                            throw new AgentTools.ToolException("E_BAD_VALUE",
                                    "An icon name uses letters, numbers "
                                            + "and underscores, like "
                                            + "ic_add.");
                        }
                        lines.add("Set the floating button's icon to "
                                + icon);
                        changed = true;
                    }
                    if (op.has("background")) {
                        String raw = stringOf(op, "background");
                        int color = parseColor(scId, raw);
                        op.addProperty("background_hex",
                                String.format(Locale.ROOT, "#%08X", color));
                        lines.add("Set the floating button's background to "
                                + colorWord(scId, raw, color));
                        changed = true;
                    }
                    if (!changed) {
                        throw new AgentTools.ToolException("E_BAD_OP",
                                "set_fab needs an icon and/or a background "
                                        + "colour.");
                    }
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
                    boolean toScreen = parentId.isEmpty()
                            || ROOT_PARENT.equalsIgnoreCase(parentId);
                    ViewBean parent = toScreen ? null : byId.get(parentId);
                    if (!toScreen && parent == null) {
                        throw new AgentTools.ToolException("E_NO_PARENT",
                                "There is no widget called " + parentId
                                        + " to move into.");
                    }
                    if (!toScreen && !isContainer(parent.type)) {
                        throw new AgentTools.ToolException("E_BAD_PARENT",
                                typeName(parent.type) + " cannot hold other "
                                        + "widgets.");
                    }
                    if (!toScreen
                            && (parent.type
                                    == ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW
                                    || parent.type
                                    == ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW)
                            && hasChild(work, parent.id, bean.id)) {
                        throw new AgentTools.ToolException("E_BAD_PARENT",
                                "A ScrollView can hold only one widget "
                                        + "directly; add a layout inside it "
                                        + "first.");
                    }
                    if (!toScreen
                            && subtreeIds(bean.id, work).contains(parentId)) {
                        throw new AgentTools.ToolException("E_CYCLE",
                                "A widget cannot be moved inside itself or "
                                        + "its children.");
                    }
                    bean.parent = toScreen ? ROOT_PARENT : parent.id;
                    bean.parentType = toScreen ? rootParentType : parent.type;
                    bean.index = nextIndex(work, bean.parent, bean.id);
                    lines.add(toScreen
                            ? "Move " + bean.id + " to the screen"
                            : "Move " + bean.id + " into " + parent.id);
                }
                default -> throw new AgentTools.ToolException("E_BAD_OP",
                        "Unknown operation \"" + kind + "\". Every "
                                + "operation is an object with an \"op\" "
                                + "field, e.g. {\"op\":\"add_widget\","
                                + "\"type\":\"Button\",\"text\":\"Save\"}. "
                                + "Valid ops: add_widget, set_text, set_hint, "
                                + "set_background, set_text_color, "
                                + "set_text_size, set_size, set_orientation, "
                                + "set_padding, set_margin, set_gravity, "
                                + "set_weight, set_image, set_scale_type, "
                                + "set_background_image, set_checked, "
                                + "set_enabled, set_text_style, "
                                + "set_input_type, set_font, set_list_item_view, "
                                + "set_progress, "
                                + "set_indeterminate, set_alpha, "
                                + "set_clickable, set_root, set_fab, "
                                + "remove_widget, "
                                + "move_widget.");
            }
            if (buildingFromEmpty && "add_widget".equals(kind)
                    && mainContainerId == null && !work.isEmpty()) {
                // The model opened with a container of its own: it
                // becomes the screen's home, no second one is added.
                ViewBean first = work.get(work.size() - 1);
                if (ROOT_PARENT.equals(first.parent)
                        && isContainer(first.type)
                        && first.type
                                != ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW
                        && first.type
                                != ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW) {
                    mainContainerId = first.id;
                }
            }
            if (!frozenAdded) {
                frozenOps.add(op);
            }
        }

        for (ViewBean bean : work) {
            if (bean.id == null || bean.id.isEmpty()
                    || ROOT_PARENT.equals(bean.id)) {
                throw new AgentTools.ToolException("E_TREE",
                        "A widget is missing its id, or uses the reserved "
                                + "screen id.");
            }
            if (bean.parent == null || bean.parent.isEmpty()) {
                throw new AgentTools.ToolException("E_TREE",
                        bean.id + " would be left without the screen "
                                + "parent Sketchware expects.");
            }
            if (!ROOT_PARENT.equals(bean.parent)
                    && !byId.containsKey(bean.parent)) {
                throw new AgentTools.ToolException("E_TREE",
                        bean.id + " would be left without a parent.");
            }
        }
        return new Outcome(work, lines, frozenOps);
    }

    /**
     * The main layout an empty screen is built into: a real vertical
     * LinearLayout filling the screen, which the user can select, pad
     * and restyle in the editor — unlike the editor's fixed, untouchable
     * root. Inserted and frozen like any other op (with the width
     * stretch as a second frozen op), so the approval card shows it and
     * execution replays it exactly. When {@code scrollParentId} is set,
     * the layout is the ScrollView's single child.
     *
     * @return the new layout's id
     */
    private static String synthesizeMainLayout(String scId,
            String scrollParentId, ArrayList<ViewBean> work,
            Map<String, ViewBean> byId, List<String> lines,
            JsonArray frozenOps, int rootParentType)
            throws AgentTools.ToolException {
        lines.add(scrollParentId == null
                ? "First, the main layout to hold the screen"
                : "Put the main layout inside " + scrollParentId
                        + " to hold the screen");
        JsonObject add = new JsonObject();
        add.addProperty("op", "add_widget");
        add.addProperty("type", "LinearLayout");
        if (scrollParentId != null) {
            add.addProperty("parent", scrollParentId);
        }
        applyAdd(scId, add, work, byId, lines, false, rootParentType);
        ViewBean container = work.get(work.size() - 1);
        container.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
        frozenOps.add(add);
        JsonObject stretch = new JsonObject();
        stretch.addProperty("op", "set_size");
        stretch.addProperty("id", container.id);
        stretch.addProperty("width", "match_parent");
        frozenOps.add(stretch);
        lines.add("Stretch " + container.id + " across the whole screen");
        return container.id;
    }

    private static void applyAdd(String scId, JsonObject op,
                                 ArrayList<ViewBean> work,
                                 Map<String, ViewBean> byId,
                                 List<String> lines, boolean frozen,
                                 int rootParentType)
            throws AgentTools.ToolException {
        String typeName = stringOf(op, "type");
        Integer type = typeByName(typeName);
        if (type == null) {
            throw new AgentTools.ToolException("E_BAD_TYPE",
                    "Unknown widget type \"" + typeName + "\".");
        }
        if (type == ViewBean.VIEW_TYPE_WIDGET_FAB) {
            // The editor never stores a FAB as a screen widget: it is the
            // activity's floating button, held separately (eC's fab map,
            // ViewPane.addFab). Storing one in the widget list would make
            // the editor render it as an unknown view.
            throw new AgentTools.ToolException("E_BAD_TYPE",
                    "FloatingActionButton is the screen's floating button, "
                            + "not a widget that can be added this way.");
        }
        String parentId = stringOf(op, "parent");
        boolean onScreen = parentId.isEmpty()
                || ROOT_PARENT.equalsIgnoreCase(parentId);
        ViewBean parent = null;
        if (!onScreen) {
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
            if ((parent.type == ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW
                    || parent.type == ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW)
                    && hasChild(work, parent.id, null)) {
                // Android ScrollViews hold exactly one child; the generated
                // app would crash with more. A layout goes inside first.
                throw new AgentTools.ToolException("E_BAD_PARENT",
                        "A ScrollView can hold only one widget directly; "
                                + "add a layout inside it first.");
            }
        }

        String id = stringOf(op, "id");
        if (ROOT_PARENT.equalsIgnoreCase(id)) {
            throw new AgentTools.ToolException("E_BAD_ID",
                    "\"root\" is Sketchware's reserved name for the screen "
                            + "itself; choose another widget id.");
        }
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
        // The palette beans the editor drops carry the class name here
        // (IconButton/IconLinear*/ViewBeanParser all set it).
        bean.convert = convertFor(type);
        applyPaletteDefaults(bean, type);
        bean.parent = onScreen ? ROOT_PARENT : parent.id;
        bean.parentType = onScreen ? rootParentType : parent.type;
        bean.index = nextIndex(work, bean.parent, null);
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
                + (onScreen ? " to the screen" : " in " + parent.id)
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
            case "setsize", "resize", "setwidth", "setheight" -> "set_size";
            case "setorientation", "orientation" -> "set_orientation";
            case "setpadding", "padding" -> "set_padding";
            case "setmargin", "margin" -> "set_margin";
            case "setgravity", "gravity", "setlayoutgravity" -> "set_gravity";
            case "setweight", "weight" -> "set_weight";
            case "setimage", "image" -> "set_image";
            case "setscaletype", "scaletype" -> "set_scale_type";
            case "setbackgroundimage", "setbackgroundresource",
                    "backgroundimage" -> "set_background_image";
            case "setchecked", "checked" -> "set_checked";
            case "setenabled", "enabled" -> "set_enabled";
            case "settextstyle", "textstyle", "setbold" -> "set_text_style";
            case "setinputtype", "inputtype" -> "set_input_type";
            case "setfont", "font" -> "set_font";
            case "setroot", "rootbackground", "setrootbackground" -> "set_root";
            case "setlistitemview", "setitemlayout", "setcustomview",
                    "listitemview" -> "set_list_item_view";
            case "setfab", "fab" -> "set_fab";
            case "setprogress", "progress" -> "set_progress";
            case "setindeterminate", "indeterminate" -> "set_indeterminate";
            case "setalpha", "alpha", "setopacity" -> "set_alpha";
            case "setclickable", "clickable" -> "set_clickable";
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
        TYPE_BY_NAME.put("AdView", ViewBean.VIEW_TYPE_WIDGET_ADVIEW);
        TYPE_BY_NAME.put("MapView", ViewBean.VIEW_TYPE_WIDGET_MAPVIEW);
        TYPE_BY_NAME.put("FloatingActionButton", ViewBean.VIEW_TYPE_WIDGET_FAB);
    }

    private static Integer typeByName(String name) {
        Integer type = TYPE_BY_NAME.get(name);
        if (type != null) {
            return type;
        }
        for (Map.Entry<String, Integer> entry : TYPE_BY_NAME.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        // The extended widget table is the app's own (ViewBeans); ask it
        // rather than keeping a second copy that can drift. It returns 0
        // for unknown names, so confirm the name round-trips.
        int extended = ViewBeans.getViewTypeByTypeName(name);
        if (ViewBeans.getViewTypeName(extended).equals(name)) {
            return extended;
        }
        for (int candidate = 19; candidate <= 48; candidate++) {
            if (ViewBeans.getViewTypeName(candidate).equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    /** Widget type names in {@link ViewBean} type order. */
    public static String typeName(int type) {
        for (Map.Entry<String, Integer> entry : TYPE_BY_NAME.entrySet()) {
            if (entry.getValue() == type) {
                return entry.getKey();
            }
        }
        String extended = ViewBeans.getViewTypeName(type);
        return extended.isEmpty() ? "View(" + type + ")" : extended;
    }

    public static boolean isContainer(int type) {
        return type == ViewBean.VIEW_TYPE_LAYOUT_LINEAR
                || type == ViewBean.VIEW_TYPE_LAYOUT_RELATIVE
                || type == ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW
                || type == ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW
                // Extended layouts the editor treats as linear parents
                // (ViewPane's own effective-parent mapping), plus CardView.
                || type == ViewBeans.VIEW_TYPE_LAYOUT_CARDVIEW
                || type == ViewBeans.VIEW_TYPE_LAYOUT_COLLAPSINGTOOLBARLAYOUT
                || type == ViewBeans.VIEW_TYPE_LAYOUT_TEXTINPUTLAYOUT
                || type == ViewBeans.VIEW_TYPE_LAYOUT_SWIPEREFRESHLAYOUT
                || type == ViewBeans.VIEW_TYPE_LAYOUT_RADIOGROUP;
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

    /**
     * Parses a size value with the property editor's encoding:
     * {@code "match_parent"} or {@code -1}, {@code "wrap_content"} or
     * {@code -2}, otherwise a dp count from 0 to 2000.
     */
    private static int sizeOf(JsonObject op, String key)
            throws AgentTools.ToolException {
        JsonElement element = op.get(key);
        if (element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            String word = element.getAsString().trim()
                    .toLowerCase(Locale.ROOT).replace(' ', '_');
            if (word.equals("match_parent")) {
                return LayoutBean.LAYOUT_MATCH_PARENT;
            }
            if (word.equals("wrap_content")) {
                return LayoutBean.LAYOUT_WRAP_CONTENT;
            }
        }
        int value = intOf(op, key, Integer.MIN_VALUE);
        if (value == LayoutBean.LAYOUT_MATCH_PARENT
                || value == LayoutBean.LAYOUT_WRAP_CONTENT) {
            return value;
        }
        if (value < 0 || value > 2000) {
            throw new AgentTools.ToolException("E_BAD_SIZE",
                    "A size is \"match_parent\", \"wrap_content\" or a dp "
                            + "number from 0 to 2000.");
        }
        return value;
    }

    private static String sizeWord(int value) {
        if (value == LayoutBean.LAYOUT_MATCH_PARENT) {
            return "match the parent";
        }
        if (value == LayoutBean.LAYOUT_WRAP_CONTENT) {
            return "wrap its content";
        }
        return value + "dp";
    }

    /**
     * Reads a four-sided box: {@code dp} sets all sides, or individual
     * {@code left/top/right/bottom} fields override. Values are dp.
     */
    private static int[] boxOf(
            JsonObject op, int left, int top, int right, int bottom)
            throws AgentTools.ToolException {
        if (op.has("dp")) {
            int all = boxSide(op, "dp");
            left = top = right = bottom = all;
        }
        if (op.has("left")) {
            left = boxSide(op, "left");
        }
        if (op.has("top")) {
            top = boxSide(op, "top");
        }
        if (op.has("right")) {
            right = boxSide(op, "right");
        }
        if (op.has("bottom")) {
            bottom = boxSide(op, "bottom");
        }
        return new int[]{left, top, right, bottom};
    }

    private static int boxSide(JsonObject op, String key)
            throws AgentTools.ToolException {
        int value = intOf(op, key, -1);
        if (value < 0 || value > 500) {
            throw new AgentTools.ToolException("E_BAD_SIZE",
                    "Padding and margin values are dp numbers from 0 to "
                            + "500.");
        }
        return value;
    }

    private static String boxWord(int[] box) {
        if (box[0] == box[1] && box[1] == box[2] && box[2] == box[3]) {
            return box[0] + "dp on all sides";
        }
        return box[0] + "/" + box[1] + "/" + box[2] + "/" + box[3]
                + "dp (left/top/right/bottom)";
    }

    /** Booleans written three ways by models: true, "true", or 1. */
    private static boolean boolOf(JsonObject op, String key)
            throws AgentTools.ToolException {
        JsonElement element = op.get(key);
        if (element != null && element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt() != 0;
            }
            String word = element.getAsString().trim()
                    .toLowerCase(Locale.ROOT);
            if (word.equals("true") || word.equals("yes")) {
                return true;
            }
            if (word.equals("false") || word.equals("no")) {
                return false;
            }
        }
        throw new AgentTools.ToolException("E_BAD_VALUE",
                "\"" + key + "\" must be true or false.");
    }

    /** Android gravity names to {@link LayoutBean} constants. */
    private static int gravityOf(JsonObject op)
            throws AgentTools.ToolException {
        String word = stringOf(op, "gravity").trim()
                .toLowerCase(Locale.ROOT).replace(' ', '_');
        return switch (word) {
            case "", "none", "no_gravity" -> LayoutBean.GRAVITY_NONE;
            case "left" -> LayoutBean.GRAVITY_LEFT;
            case "right" -> LayoutBean.GRAVITY_RIGHT;
            case "top" -> LayoutBean.GRAVITY_TOP;
            case "bottom" -> LayoutBean.GRAVITY_BOTTOM;
            case "center" -> LayoutBean.GRAVITY_CENTER;
            case "center_horizontal" -> LayoutBean.GRAVITY_CENTER_HORIZONTAL;
            case "center_vertical" -> LayoutBean.GRAVITY_CENTER_VERTICAL;
            case "top_left" -> LayoutBean.GRAVITY_TOP
                    | LayoutBean.GRAVITY_LEFT;
            case "top_right" -> LayoutBean.GRAVITY_TOP
                    | LayoutBean.GRAVITY_RIGHT;
            case "bottom_left" -> LayoutBean.GRAVITY_BOTTOM
                    | LayoutBean.GRAVITY_LEFT;
            case "bottom_right" -> LayoutBean.GRAVITY_BOTTOM
                    | LayoutBean.GRAVITY_RIGHT;
            default -> throw new AgentTools.ToolException("E_BAD_VALUE",
                    "Gravity is one of: left, right, top, bottom, center, "
                            + "center_horizontal, center_vertical, "
                            + "top_left, top_right, bottom_left, "
                            + "bottom_right, none.");
        };
    }

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

    /** The screen root's widget type, when a screen already records it. */
    private static int inferRootParentType(List<ViewBean> beans) {
        if (beans != null) {
            for (ViewBean bean : beans) {
                if (bean != null && ROOT_PARENT.equals(bean.parent)
                        && bean.parentType >= 0) {
                    return bean.parentType;
                }
            }
        }
        return ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
    }

    /**
     * The starting values the editor's own palette gives a new widget
     * (the {@code Icon*` classes in the editor's view palette): 8dp
     * padding on most widgets, match-parent on the axes the palette
     * stretches, and the widget's name as its starting text/hint. An
     * operation's explicit text is applied after this, so it wins.
     */
    private static void applyPaletteDefaults(ViewBean bean, int type) {
        switch (type) {
            case ViewBean.VIEW_TYPE_LAYOUT_LINEAR -> {
                // The palette's vertical LinearLayout (the horizontal one
                // differs only in orientation and the stretched axis).
                bean.layout.orientation = LayoutBean.ORIENTATION_VERTICAL;
                bean.layout.height = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            case ViewBean.VIEW_TYPE_LAYOUT_RELATIVE,
                    ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            case ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW -> {
                bean.layout.height = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            case ViewBean.VIEW_TYPE_WIDGET_WEBVIEW -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.layout.height = LayoutBean.LAYOUT_MATCH_PARENT;
            }
            case ViewBean.VIEW_TYPE_WIDGET_PROGRESSBAR,
                    ViewBean.VIEW_TYPE_WIDGET_LISTVIEW,
                    ViewBean.VIEW_TYPE_WIDGET_SEEKBAR -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
                bean.text.text = typeName(type);
            }
            case ViewBean.VIEW_TYPE_WIDGET_SPINNER -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            case ViewBean.VIEW_TYPE_WIDGET_CALENDARVIEW -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.layout.height = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            case ViewBean.VIEW_TYPE_WIDGET_BUTTON,
                    ViewBean.VIEW_TYPE_WIDGET_TEXTVIEW,
                    ViewBean.VIEW_TYPE_WIDGET_CHECKBOX,
                    ViewBean.VIEW_TYPE_WIDGET_SWITCH -> {
                pad(bean);
                bean.text.text = typeName(type);
            }
            case ViewBean.VIEW_TYPE_WIDGET_EDITTEXT -> {
                pad(bean);
                bean.text.hint = typeName(type);
            }
            case ViewBean.VIEW_TYPE_WIDGET_ADVIEW -> {
                // IconAdView: no padding, match width, debug ad unit.
                bean.layout.paddingLeft = 0;
                bean.layout.paddingTop = 0;
                bean.layout.paddingRight = 0;
                bean.layout.paddingBottom = 0;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.adSize = "SMART_BANNER";
                bean.adUnitId = "debug : ca-app-pub-3940256099942544/6300978111";
            }
            case ViewBean.VIEW_TYPE_WIDGET_MAPVIEW -> {
                bean.layout.paddingLeft = 0;
                bean.layout.paddingTop = 0;
                bean.layout.paddingRight = 0;
                bean.layout.paddingBottom = 0;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.layout.height = LayoutBean.LAYOUT_MATCH_PARENT;
            }
            default -> {
                // ImageView and anything else start from the plain bean,
                // exactly as their palette icons do.
                applyExtendedDefaults(bean, type);
            }
        }
    }

    /**
     * The class name a new bean carries in {@code convert}: the simple
     * widget name for the built-in palette, and the fully-qualified class
     * for the extended palette, whose icons write exactly that
     * (e.g. {@code androidx.cardview.widget.CardView}).
     */
    private static String convertFor(int type) {
        return switch (type) {
            case ViewBean.VIEW_TYPE_WIDGET_ADVIEW ->
                    "com.google.android.gms.ads.AdView";
            case ViewBean.VIEW_TYPE_WIDGET_MAPVIEW ->
                    "com.google.android.gms.maps.MapView";
            case ViewBeans.VIEW_TYPE_WIDGET_BADGEVIEW ->
                    "com.allenliu.badgeview.BadgeView";
            case ViewBeans.VIEW_TYPE_LAYOUT_BOTTOMNAVIGATIONVIEW ->
                    "com.google.android.material.bottomnavigation.BottomNavigationView";
            case ViewBeans.VIEW_TYPE_LAYOUT_CARDVIEW ->
                    "androidx.cardview.widget.CardView";
            case ViewBeans.VIEW_TYPE_WIDGET_CIRCLEIMAGEVIEW ->
                    "de.hdodenhof.circleimageview.CircleImageView";
            case ViewBeans.VIEW_TYPE_WIDGET_CODEVIEW ->
                    "br.tiagohm.codeview.CodeView";
            case ViewBeans.VIEW_TYPE_LAYOUT_COLLAPSINGTOOLBARLAYOUT ->
                    "com.google.android.material.appbar.CollapsingToolbarLayout";
            case ViewBeans.VIEW_TYPE_WIDGET_SIGNINBUTTON ->
                    "com.google.android.gms.common.SignInButton";
            case ViewBeans.VIEW_TYPE_WIDGET_LOTTIEANIMATIONVIEW ->
                    "com.airbnb.lottie.LottieAnimationView";
            case ViewBeans.VIEW_TYPE_WIDGET_MATERIALBUTTON ->
                    "com.google.android.material.button.MaterialButton";
            case ViewBeans.VIEW_TYPE_WIDGET_OTPVIEW ->
                    "affan.ahmad.otp.OTPView";
            case ViewBeans.VIEW_TYPE_WIDGET_PATTERNLOCKVIEW ->
                    "com.andrognito.patternlockview.PatternLockView";
            case ViewBeans.VIEW_TYPE_WIDGET_RECYCLERVIEW ->
                    "androidx.recyclerview.widget.RecyclerView";
            case ViewBeans.VIEW_TYPE_LAYOUT_SWIPEREFRESHLAYOUT ->
                    "androidx.swiperefreshlayout.widget.SwipeRefreshLayout";
            case ViewBeans.VIEW_TYPE_LAYOUT_TABLAYOUT ->
                    "com.google.android.material.tabs.TabLayout";
            case ViewBeans.VIEW_TYPE_LAYOUT_TEXTINPUTLAYOUT ->
                    "com.google.android.material.textfield.TextInputLayout";
            case ViewBeans.VIEW_TYPE_LAYOUT_VIEWPAGER ->
                    "androidx.viewpager.widget.ViewPager";
            case ViewBeans.VIEW_TYPE_WIDGET_WAVESIDEBAR ->
                    "com.sayuti.lib.WaveSideBar";
            case ViewBeans.VIEW_TYPE_WIDGET_YOUTUBEPLAYERVIEW ->
                    "com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView";
            default -> typeName(type);
        };
    }

    /**
     * Palette defaults for the extended widget set, transcribed from the
     * extended palette icons' {@code getBean()} (padding, stretched axes,
     * starting text/hint, and the attribute injection string several of
     * them carry).
     */
    private static void applyExtendedDefaults(ViewBean bean, int type) {
        switch (type) {
            case ViewBeans.VIEW_TYPE_WIDGET_RADIOBUTTON,
                    ViewBeans.VIEW_TYPE_WIDGET_RATINGBAR,
                    ViewBeans.VIEW_TYPE_WIDGET_VIDEOVIEW,
                    ViewBeans.VIEW_TYPE_WIDGET_DIGITALCLOCK,
                    ViewBeans.VIEW_TYPE_WIDGET_ANALOGCLOCK -> {
                pad(bean);
                bean.text.text = typeName(type);
            }
            case ViewBeans.VIEW_TYPE_WIDGET_AUTOCOMPLETETEXTVIEW,
                    ViewBeans.VIEW_TYPE_WIDGET_MULTIAUTOCOMPLETETEXTVIEW -> {
                pad(bean);
                bean.text.hint = typeName(type);
            }
            case ViewBeans.VIEW_TYPE_WIDGET_SEARCHVIEW -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
                bean.text.hint = typeName(type);
                bean.text.hintColor = 0xff607d8b;
            }
            case ViewBeans.VIEW_TYPE_WIDGET_DATEPICKER,
                    ViewBeans.VIEW_TYPE_WIDGET_TIMEPICKER -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            case ViewBeans.VIEW_TYPE_WIDGET_GRIDVIEW -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_HORIZONTAL;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
                bean.inject = "android:numColumns=\"3\"\n"
                        + "android:stretchMode=\"columnWidth\"";
            }
            case ViewBeans.VIEW_TYPE_LAYOUT_CARDVIEW -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_VERTICAL;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
                bean.inject = "app:cardElevation=\"2dp\"\n"
                        + "app:cardCornerRadius=\"20dp\"";
            }
            case ViewBeans.VIEW_TYPE_LAYOUT_COLLAPSINGTOOLBARLAYOUT -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_VERTICAL;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
                bean.inject = "android:fitsSystemWindows=\"true\"\n"
                        + "app:layout_scrollFlags=\"scroll|exitUntilCollapsed\"";
            }
            case ViewBeans.VIEW_TYPE_LAYOUT_TEXTINPUTLAYOUT -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_VERTICAL;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.inject = "style=\"@style/Widget.MaterialComponents"
                        + ".TextInputLayout.OutlinedBox\"";
            }
            case ViewBeans.VIEW_TYPE_LAYOUT_SWIPEREFRESHLAYOUT -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_VERTICAL;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.layout.height = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            case ViewBeans.VIEW_TYPE_LAYOUT_RADIOGROUP -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_HORIZONTAL;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            case ViewBeans.VIEW_TYPE_LAYOUT_TABLAYOUT -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.layout.backgroundColor = 0xff008dcd;
                bean.inject = "app:tabGravity=\"fill\"\napp:tabMode=\"fixed\"\n"
                        + "app:tabIndicatorHeight=\"3dp\"\n"
                        + "app:tabIndicatorColor=\"@android:color/white\"\n"
                        + "app:tabSelectedTextColor=\"@android:color/white\"\n"
                        + "app:tabTextColor=\"@android:color/white\"\n"
                        + "app:tabTextAppearance=\"@android:style/TextAppearance"
                        + ".Widget.TabWidget\"";
            }
            case ViewBeans.VIEW_TYPE_LAYOUT_VIEWPAGER -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.layout.height = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
                bean.text.text = typeName(type);
            }
            case ViewBeans.VIEW_TYPE_LAYOUT_BOTTOMNAVIGATIONVIEW -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.text.text = typeName(type);
            }
            case ViewBeans.VIEW_TYPE_WIDGET_BADGEVIEW -> {
                bean.text.text = typeName(type);
            }
            case ViewBeans.VIEW_TYPE_WIDGET_PATTERNLOCKVIEW -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
                bean.text.text = typeName(type);
            }
            case ViewBeans.VIEW_TYPE_WIDGET_WAVESIDEBAR -> {
                bean.layout.height = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.text.text = typeName(type);
                bean.text.textSize = 16;
                bean.text.textColor = 0xff008dcd;
                bean.inject = "app:sidebar_text_alignment=\"center\"\n"
                        + "app:sidebar_position=\"left\"\n"
                        + "app:sidebar_max_offset=\"80dp\"";
            }
            case ViewBeans.VIEW_TYPE_WIDGET_MATERIALBUTTON -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_VERTICAL;
                bean.layout.gravity = LayoutBean.GRAVITY_CENTER;
                pad(bean);
                bean.text.textColor = 0xffffffff;
                bean.text.text = typeName(type);
                bean.inject = "app:cornerRadius=\"8dp\"";
            }
            case ViewBeans.VIEW_TYPE_WIDGET_CIRCLEIMAGEVIEW -> {
                bean.inject = "app:civ_border_width=\"3dp\"\n"
                        + "app:civ_border_color=\"#008DCD\"\n"
                        + "app:civ_circle_background_color=\"#FFFFFF\"\n"
                        + "app:civ_border_overlay=\"true\"";
            }
            case ViewBeans.VIEW_TYPE_WIDGET_LOTTIEANIMATIONVIEW -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_VERTICAL;
                pad(bean);
                bean.inject = "android:scaleType=\"fitCenter\"\n"
                        + "android:layout_centerInParent=\"true\"\n"
                        + "app:lottie_fileName=\"your_file.json\"\n"
                        + "app:lottie_loop=\"true\" \n"
                        + "app:lottie_autoPlay=\"true\"";
            }
            case ViewBeans.VIEW_TYPE_WIDGET_YOUTUBEPLAYERVIEW -> {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                bean.text.text = typeName(type);
                bean.inject = "app:autoPlay=\"false\"\napp:useWebUi=\"false\"\n"
                        + "app:showYouTubeButton=\"true\"\n"
                        + "app:showFullScreenButton=\"true\"\n"
                        + "app:showVideoCurrentTime=\"true\"\n"
                        + "app:showVideoDuration=\"true\"\n"
                        + "app:showSeekBar=\"true\"\n"
                        + "app:handleNetworkEvents=\"false\"\n"
                        + "app:enableAutomaticInitialization=\"true\"";
            }
            case ViewBeans.VIEW_TYPE_WIDGET_OTPVIEW,
                    ViewBeans.VIEW_TYPE_WIDGET_CODEVIEW,
                    ViewBeans.VIEW_TYPE_WIDGET_RECYCLERVIEW -> {
                bean.layout.orientation = LayoutBean.ORIENTATION_VERTICAL;
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                pad(bean);
            }
            default -> {
                // SignInButton and anything unlisted start plain, as
                // their palette icons do.
            }
        }
    }

    private static void pad(ViewBean bean) {
        bean.layout.paddingLeft = 8;
        bean.layout.paddingTop = 8;
        bean.layout.paddingRight = 8;
        bean.layout.paddingBottom = 8;
    }

    private static boolean hasChild(List<ViewBean> beans, String parentId,
                                    String excludeId) {
        for (ViewBean bean : beans) {
            if (bean != null && parentId.equals(bean.parent)
                    && !bean.id.equals(excludeId)) {
                return true;
            }
        }
        return false;
    }

    /** The append position among the widgets sharing one parent. */
    private static int nextIndex(List<ViewBean> beans, String parentId,
                                 String excludeId) {
        int max = -1;
        for (ViewBean bean : beans) {
            if (bean == null || bean.id == null
                    || bean.id.equals(excludeId)) {
                continue;
            }
            if (parentId.equals(bean.parent)) {
                max = Math.max(max, bean.index);
            }
        }
        return max + 1;
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
