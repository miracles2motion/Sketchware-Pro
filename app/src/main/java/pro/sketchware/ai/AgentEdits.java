package pro.sketchware.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import a.a.a.Gx;
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
    public static final int MAX_OPS = 20;

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
        /** Plain-words placement problems in the resulting screen. */
        public final ArrayList<String> warnings = new ArrayList<>();
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
        // Rebuild guard: a batch that removes several widgets and
        // re-adds one of them by name is remaking the screen, not
        // editing it — the re-added widget loses its text, sizes
        // and other widgets inside it. One-for-one replacement in
        // a small edit stays allowed (the card spells it out).
        java.util.Set<String> removedIds = new java.util.HashSet<>();
        for (JsonElement element : ops) {
            if (element != null && element.isJsonObject()) {
                JsonObject candidate = element.getAsJsonObject();
                if ("remove_widget".equals(normalizeKind(candidate))) {
                    String removedId = widgetIdOf(candidate);
                    if (!removedId.isEmpty()) {
                        removedIds.add(removedId);
                    }
                }
            }
        }
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
                    if (mainContainerId == null && scroll
                            && !hasExplicitContainerChildInOps(
                                    ops, stringOf(op, "id"))) {
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
                    String requestedId = stringOf(op, "id");
                    if (removedIds.size() >= 2
                            && removedIds.contains(requestedId)) {
                        throw new AgentTools.ToolException("E_REBUILD",
                                "This edit removes " + removedIds.size()
                                        + " widgets and re-adds "
                                        + requestedId + ". Re-adding "
                                        + "a removed widget loses its "
                                        + "text and settings, so "
                                        + "rebuilds like this are "
                                        + "refused. To change order, "
                                        + "use move_widget with "
                                        + "\"before\" or \"after\"; "
                                        + "to replace one widget, remove "
                                        + "and re-add just that one.");
                    }
                    if (!preHandled) {
                        if (!buildingFromEmpty && !frozen
                                && stringOf(op, "parent").isEmpty()) {
                            // A screen that already has a main layout
                            // keeps new widgets inside it: adding at
                            // the top level parks them after the
                            // full-screen layout, off the screen.
                            // Frozen replays keep the parent frozen
                            // into the op at proposal time.
                            String home = mainContainerOf(work);
                            if (home != null) {
                                op.addProperty("parent", home);
                            }
                        }
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
                    String raw = stringOf(op, "color");
                    ResColor rc = resolveResColor(scId, raw, frozen);
                    if (rc != null) {
                        bean.layout.backgroundResColor = rc.resRef;
                        bean.layout.backgroundColor = rc.fallbackInt;
                        lines.add("Set " + bean.id + " background to "
                                + rc.resRef);
                    } else {
                        int color = colorOf(scId, op);
                        bean.layout.backgroundResColor = null;
                        bean.layout.backgroundColor = color;
                        lines.add("Set " + bean.id + " background to "
                                + colorWord(scId, op, color));
                    }
                }
                case "set_text_color" -> {
                    ViewBean bean = requireWidget(op, byId);
                    String raw = stringOf(op, "color");
                    ResColor rc = resolveResColor(scId, raw, frozen);
                    if (rc != null) {
                        bean.text.resTextColor = rc.resRef;
                        bean.text.textColor = rc.fallbackInt;
                        lines.add("Set " + bean.id + " text colour to "
                                + rc.resRef);
                    } else {
                        int color = colorOf(scId, op);
                        bean.text.resTextColor = null;
                        bean.text.textColor = color;
                        lines.add("Set " + bean.id + " text colour to "
                                + colorWord(scId, op, color));
                    }
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
                    String image = stripDrawablePrefix(stringOf(op, "image"));
                    if (!ID_PATTERN.matcher(image).matches()) {
                        throw new AgentTools.ToolException("E_BAD_VALUE",
                                "An image name uses letters, numbers and "
                                        + "underscores, like my_photo.");
                    }
                    requireDrawableExists(scId, image, bean.id, frozen);
                    op.addProperty("image", image);
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
                    String image = stripDrawablePrefix(stringOf(op, "image"));
                    if (image.isEmpty() || "none".equalsIgnoreCase(image)) {
                        bean.layout.backgroundResource = null;
                        op.addProperty("image", "none");
                        lines.add("Clear " + bean.id + " background image");
                    } else {
                        if (!ID_PATTERN.matcher(image).matches()) {
                            throw new AgentTools.ToolException("E_BAD_VALUE",
                                    "An image name uses letters, numbers and "
                                            + "underscores, like my_photo.");
                        }
                        requireDrawableExists(scId, image, bean.id, frozen);
                        op.addProperty("image", image);
                        bean.layout.backgroundResource = image;
                        lines.add("Set " + bean.id + " background image to "
                                + image);
                    }
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
                                    != ViewBeans.VIEW_TYPE_LAYOUT_VIEWPAGER
                            && bean.type
                                    != ViewBean.VIEW_TYPE_WIDGET_SPINNER) {
                        throw new AgentTools.ToolException("E_WRONG_WIDGET",
                                "Only a ListView, GridView, RecyclerView, "
                                        + "ViewPager or Spinner uses an item "
                                        + "layout: " + bean.id + " is a "
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
                case "set_attributes" -> {
                    // The editor's Custom attributes (Inject) field: raw
                    // XML attributes written onto the widget's tag. This
                    // is where corner radius, elevation, stroke, ripple
                    // and the card attributes live as PROPERTIES — the
                    // list of them is the editor's own
                    // (PropertyInputItem.populateAttributes).
                    ViewBean bean = requireWidget(op, byId);
                    String attributes = customAttributes(op, bean);
                    bean.inject = attributes;
                    lines.add("Set " + bean.id + " custom attributes: "
                            + attributes.replace("\n", ", "));
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
                case "set_visibility" -> {
                    ViewBean bean = requireWidget(op, byId);
                    String vis = stringOf(op, "visibility");
                    if (vis.isEmpty()) {
                        vis = stringOf(op, "state");
                    }
                    if (vis.isEmpty() && op.has("visible")) {
                        vis = boolOf(op, "visible") ? "visible" : "gone";
                    }
                    vis = vis.trim().toLowerCase(Locale.ROOT);
                    if (!vis.equals("visible") && !vis.equals("invisible")
                            && !vis.equals("gone")) {
                        throw new AgentTools.ToolException("E_BAD_VALUE",
                                "Visibility is \"visible\", \"invisible\" "
                                        + "or \"gone\".");
                    }
                    bean.inject = upsertInjectAttr(bean.inject,
                            "android:visibility",
                            "visible".equals(vis) ? null : vis);
                    lines.add("Set " + bean.id + " visibility to " + vis);
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
                    String askedClass = stringOf(op, "class_name");
                    if (askedClass.isEmpty()) {
                        askedClass = stringOf(op, "layout_type");
                    }
                    if (askedClass.isEmpty()) {
                        askedClass = stringOf(op, "type");
                    }
                    if (!askedClass.isEmpty()) {
                        String resolved = frozen
                                && !stringOf(op, "resolved_class_name")
                                        .isEmpty()
                                ? stringOf(op, "resolved_class_name")
                                : SketchwareActions
                                        .resolveRootClassName(askedClass);
                        if (resolved == null || resolved.isEmpty()) {
                            throw new AgentTools.ToolException(
                                    "E_BAD_VALUE",
                                    "\"" + askedClass + "\" is not a valid "
                                            + "root ViewGroup. Use "
                                            + "LinearLayout, RelativeLayout, "
                                            + "FrameLayout, "
                                            + "ConstraintLayout, "
                                            + "CoordinatorLayout, "
                                            + "ScrollView, NestedScrollView, "
                                            + "HorizontalScrollView, "
                                            + "MaterialCardView, or "
                                            + "SwipeRefreshLayout.");
                        }
                        op.addProperty("resolved_class_name", resolved);
                        rootParentType = rootTypeForClassName(resolved);
                        for (ViewBean child : work) {
                            if (child != null
                                    && ROOT_PARENT.equals(child.parent)) {
                                child.parentType = rootParentType;
                            }
                        }
                        lines.add("Convert root layout to " + resolved);
                        changed = true;
                    }
                    if (op.has("background")) {
                        String raw = stringOf(op, "background");
                        ResColor rc = resolveResColor(scId, raw, frozen);
                        if (rc != null) {
                            op.addProperty("background_res", rc.resRef);
                            op.addProperty("background_hex",
                                    String.format(Locale.ROOT, "#%08X",
                                            rc.fallbackInt));
                            lines.add("Set the screen background to "
                                    + rc.resRef);
                        } else {
                            int color = parseColor(scId, raw);
                            op.addProperty("background_hex",
                                    String.format(Locale.ROOT, "#%08X",
                                            color));
                            lines.add("Set the screen background to "
                                    + colorWord(scId, raw, color));
                        }
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
                    if (op.has("attributes")
                            && op.get("attributes").isJsonObject()) {
                        JsonObject rawAttrs =
                                op.getAsJsonObject("attributes");
                        JsonObject cleanAttrs = new JsonObject();
                        for (Map.Entry<String, JsonElement> entry
                                : rawAttrs.entrySet()) {
                            String attrKey = entry.getKey() == null ? ""
                                    : entry.getKey().trim();
                            if (!attrKey.matches(
                                    "[A-Za-z_][A-Za-z0-9_.:-]*")
                                    || attrKey.startsWith("xmlns")) {
                                throw new AgentTools.ToolException(
                                        "E_BAD_VALUE",
                                        "Invalid root XML attribute name: \""
                                                + attrKey + "\".");
                            }
                            String attrVal = entry.getValue() == null
                                    || entry.getValue().isJsonNull() ? ""
                                    : entry.getValue().getAsString().trim();
                            if (attrVal.contains("\"")
                                    || attrVal.contains("\n")
                                    || attrVal.contains("\r")) {
                                throw new AgentTools.ToolException(
                                        "E_BAD_VALUE",
                                        "Invalid value for root attribute "
                                                + attrKey + ".");
                            }
                            cleanAttrs.addProperty(attrKey, attrVal);
                        }
                        if (cleanAttrs.size() > 0) {
                            op.add("resolved_attributes", cleanAttrs);
                            lines.add("Set " + cleanAttrs.size()
                                    + " root XML attribute"
                                    + (cleanAttrs.size() == 1 ? "" : "s"));
                            changed = true;
                        }
                    }
                    if (!changed) {
                        throw new AgentTools.ToolException("E_BAD_OP",
                                "set_root needs class_name/layout_type, "
                                        + "background, orientation, and/or "
                                        + "attributes.");
                    }
                }
                case "set_fab" -> {
                    // The floating button is not a screen widget either:
                    // it is the activity's own bean (eC's fab store).
                    // Values are validated and frozen here; the execute
                    // path writes them to that bean.
                    boolean changed = false;
                    if (op.has("icon")) {
                        String icon = stripDrawablePrefix(stringOf(op, "icon"));
                        if (!ID_PATTERN.matcher(icon).matches()) {
                            throw new AgentTools.ToolException("E_BAD_VALUE",
                                    "An icon name uses letters, numbers "
                                            + "and underscores, like "
                                            + "ic_add.");
                        }
                        requireDrawableExists(
                                scId, icon, "the floating button", frozen);
                        op.addProperty("icon", icon);
                        lines.add("Set the floating button's icon to "
                                + icon);
                        changed = true;
                    }
                    if (op.has("background")) {
                        String raw = stringOf(op, "background");
                        ResColor rc = resolveResColor(scId, raw, frozen);
                        if (rc != null) {
                            op.addProperty("background_res", rc.resRef);
                            op.addProperty("background_hex",
                                    String.format(Locale.ROOT, "#%08X",
                                            rc.fallbackInt));
                            lines.add("Set the floating button's background to "
                                    + rc.resRef);
                        } else {
                            int color = parseColor(scId, raw);
                            op.addProperty("background_hex",
                                    String.format(Locale.ROOT, "#%08X",
                                            color));
                            lines.add("Set the floating button's background to "
                                    + colorWord(scId, raw, color));
                        }
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
                    // Position next to a sibling ("before"/"after"
                    // a widget id) is how order inside one layout
                    // changes; without one, a move lands at the end.
                    String anchorId = stringOf(op, "before");
                    boolean before = !anchorId.isEmpty();
                    if (!before) {
                        anchorId = stringOf(op, "after");
                    }
                    ViewBean anchor = anchorId.isEmpty() ? null
                            : resolveWidget(byId, anchorId);
                    if (!anchorId.isEmpty() && anchor == null) {
                        throw new AgentTools.ToolException(
                                "E_NO_WIDGET",
                                "There is no widget called " + anchorId
                                        + " to move next to. Widgets "
                                        + "here: " + idList(byId)
                                        + ".");
                    }
                    if (anchor == bean) {
                        throw new AgentTools.ToolException("E_BAD_OP",
                                "A widget cannot be moved next to "
                                        + "itself.");
                    }
                    String parentId = stringOf(op, "parent");
                    if (parentId.isEmpty() && anchor != null) {
                        // Next to a sibling means: its layout.
                        parentId = anchor.parent == null
                                ? ROOT_PARENT : anchor.parent;
                    }
                    boolean toScreen = parentId.isEmpty()
                            || ROOT_PARENT.equalsIgnoreCase(parentId);
                    ViewBean parent = toScreen ? null
                            : resolveWidget(byId, parentId);
                    if (!toScreen && parent == null) {
                        throw new AgentTools.ToolException("E_NO_PARENT",
                                "There is no widget called " + parentId
                                        + " to move into. Widgets here: "
                                        + idList(byId) + ".");
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
                    String destName = toScreen ? "the screen" : parent.id;
                    if (anchor == null) {
                        bean.index = nextIndex(work, bean.parent,
                                bean.id);
                        lines.add(toScreen
                                ? "Move " + bean.id + " to the screen"
                                : "Move " + bean.id + " into "
                                        + parent.id);
                    } else {
                        String anchorParent = anchor.parent == null
                                ? ROOT_PARENT : anchor.parent;
                        if (!anchorParent.equals(bean.parent)) {
                            throw new AgentTools.ToolException(
                                    "E_BAD_OP",
                                    anchor.id + " is not inside "
                                            + destName + ", so "
                                            + bean.id + " cannot be "
                                            + "placed next to it "
                                            + "there.");
                        }
                        // Reorder the destination group: stored
                        // order is sibling index order, so the
                        // group is resequenced around the anchor.
                        ArrayList<ViewBean> group = new ArrayList<>();
                        for (ViewBean other : work) {
                            if (other != bean && bean.parent.equals(
                                    other.parent)) {
                                group.add(other);
                            }
                        }
                        group.sort(java.util.Comparator.comparingInt(
                                b -> b.index));
                        int insert = group.indexOf(anchor)
                                + (before ? 0 : 1);
                        group.add(insert, bean);
                        for (int i = 0; i < group.size(); i++) {
                            group.get(i).index = i;
                        }
                        lines.add("Move " + bean.id + " to just "
                                + (before ? "before " : "after ")
                                + anchor.id + " in " + destName);
                    }
                }
                case "set_card_radius", "set_card_elevation",
                        "set_corner_radius", "set_elevation",
                        "set_stroke" -> throw new AgentTools.ToolException(
                        "E_BAD_OP",
                        "\"" + kind + "\" is a runtime logic block for "
                                + "set_event_handler, not an edit_screen "
                                + "operation. On edit_screen, set XML "
                                + "attributes with set_attributes — e.g. "
                                + "{\"op\":\"set_attributes\",\"id\":\"...\","
                                + "\"attributes\":{\"app:cardCornerRadius\":"
                                + "\"16dp\",\"app:cardElevation\":\"4dp\"}} "
                                + "on a CardView, or {\"android:elevation\":"
                                + "\"4dp\"} on any widget.");
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
                                + "set_attributes, set_clickable, "
                                + "set_visibility, set_root, set_fab, "
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

        // Design is made real here, not merely advised: the common
        // layout mistakes an agent makes when it drops widgets are
        // corrected on the copy before anything is saved, and the
        // proposal lines say so — the user sees the same result on
        // the card and gets it on screen.
        applyDesignDefaults(work, byId, frozenOps, lines);

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
            if (bean.image != null && bean.image.resName == null) {
                // Screens saved by older agent versions can carry an
                // ImageView with no image name; the editor's view
                // loader dereferences it and crashes the whole app.
                // Any edit through here heals the screen on save.
                bean.image.resName = "default_image";
            }
        }
        Outcome outcome = new Outcome(work, lines, frozenOps);
        outcome.warnings.addAll(visibilityWarnings(work));
        outcome.warnings.addAll(designWarnings(work));
        return outcome;
    }

    /**
     * The design rules the agent was only advised about before, now
     * applied to the widgets it just added: inputs fill a form, a
     * form's action button spans it and is tall enough to tap, and
     * no widget is left showing the palette's own placeholder
     * ("EditText") where a real label belongs. Values the agent set
     * explicitly in this same batch are never overwritten.
     */
    private static void applyDesignDefaults(ArrayList<ViewBean> work,
                                            Map<String, ViewBean> byId,
                                            JsonArray ops,
                                            List<String> lines) {
        java.util.Set<String> added = new java.util.HashSet<>();
        java.util.Set<String> sized = new java.util.HashSet<>();
        java.util.Set<String> labelled = new java.util.HashSet<>();
        for (JsonElement element : ops) {
            if (!element.isJsonObject()) {
                continue;
            }
            // The ops as they were replayed: only there do agent-named
            // widgets carry the ids the app allocated for them.
            JsonObject op = element.getAsJsonObject().deepCopy();
            String kind = normalizeKind(op);
            String id = widgetIdOf(op);
            if (id.isEmpty()) {
                continue;
            }
            if ("add_widget".equals(kind)) {
                added.add(id);
            } else if ("set_size".equals(kind) || "set_weight".equals(kind)
                    || "set_padding".equals(kind)) {
                sized.add(id);
            } else if ("set_text".equals(kind) || "set_hint".equals(kind)) {
                labelled.add(id);
            }
        }
        if (added.isEmpty()) {
            return;
        }
        for (String id : added) {
            ViewBean bean = byId.get(id);
            if (bean == null || bean.layout == null || bean.text == null) {
                continue;
            }
            ViewBean parent = byId.get(bean.parent);
            boolean parentLinear = parent == null
                    || parent.type == ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
            boolean parentVertical = parent == null
                    || (parent.type == ViewBean.VIEW_TYPE_LAYOUT_LINEAR
                            && (parent.layout == null
                                    || parent.layout.orientation
                                            != LayoutBean
                                                    .ORIENTATION_HORIZONTAL));
            // A readable label: an input's hint or a button's text
            // should say what it is for, not repeat its type name.
            if (!labelled.contains(id)) {
                String placeholder = typeName(bean.type);
                if (bean.type == ViewBean.VIEW_TYPE_WIDGET_EDITTEXT) {
                    String hint = bean.text.hint == null
                            ? "" : bean.text.hint.trim();
                    if (hint.isEmpty() || hint.equals(placeholder)) {
                        String readable = readableLabel(id, placeholder);
                        bean.text.hint = readable;
                        lines.add("Labelled " + id + " “" + readable
                                + "”");
                    }
                } else if (isTextCarrier(bean.type)) {
                    String text = bean.text.text == null
                            ? "" : bean.text.text.trim();
                    if (text.isEmpty() || text.equals(placeholder)) {
                        String readable = readableLabel(id, placeholder);
                        bean.text.text = readable;
                        lines.add("Labelled " + id + " “" + readable
                                + "”");
                    }
                }
            }
            if (sized.contains(id) || !parentLinear || !parentVertical) {
                continue;
            }
            // Inputs fill the form's width: a text box as narrow as
            // its own hint looks unfinished and is hard to type in.
            if (bean.type == ViewBean.VIEW_TYPE_WIDGET_EDITTEXT
                    && bean.layout.width == LayoutBean.LAYOUT_WRAP_CONTENT
                    && bean.layout.weight == 0) {
                bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                lines.add("Made " + id + " fill the form's width");
            }
            boolean button = bean.type == ViewBean.VIEW_TYPE_WIDGET_BUTTON
                    || bean.type
                            == ViewBeans.VIEW_TYPE_WIDGET_MATERIALBUTTON;
            // A form's action button spans it and is tall enough to
            // tap — the single most common thing agents get wrong.
            if (button && formContainer(work, bean.parent)) {
                if (bean.layout.width != LayoutBean.LAYOUT_MATCH_PARENT) {
                    bean.layout.width = LayoutBean.LAYOUT_MATCH_PARENT;
                    lines.add("Made " + id
                            + " span the form's width");
                }
                if (bean.layout.height >= 0
                        && bean.layout.height < 48) {
                    bean.layout.height = 48;
                    lines.add("Made " + id + " 48dp tall so it is "
                            + "easy to tap");
                }
            }
        }
    }

    /** Widgets whose on-screen words are their label. */
    private static boolean isTextCarrier(int type) {
        return type == ViewBean.VIEW_TYPE_WIDGET_TEXTVIEW
                || type == ViewBean.VIEW_TYPE_WIDGET_BUTTON
                || type == ViewBean.VIEW_TYPE_WIDGET_CHECKBOX
                || type == ViewBean.VIEW_TYPE_WIDGET_SWITCH
                || type == ViewBeans.VIEW_TYPE_WIDGET_MATERIALBUTTON
                || type == ViewBeans.VIEW_TYPE_WIDGET_RADIOBUTTON;
    }

    /** True when a layout holds a text input: a form. */
    private static boolean hasExplicitContainerChildInOps(JsonArray ops,
                                                          String parentId) {
        if (parentId == null || parentId.isEmpty()) {
            return false;
        }
        for (JsonElement el : ops) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject candidate = el.getAsJsonObject();
            if (!"add_widget".equals(normalizeKind(candidate.deepCopy()))) {
                continue;
            }
            if (parentId.equals(stringOf(candidate, "parent"))) {
                Integer t = typeByName(stringOf(candidate, "type"));
                if (t != null && isContainer(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean formContainer(ArrayList<ViewBean> work,
                                         String parentId) {
        if (parentId == null || ROOT_PARENT.equals(parentId)) {
            return false;
        }
        for (ViewBean bean : work) {
            if (parentId.equals(bean.parent)
                    && bean.type == ViewBean.VIEW_TYPE_WIDGET_EDITTEXT) {
                return true;
            }
        }
        return false;
    }

    /** "email_input" -> "Email input"; "button1" -> "Button 1". */
    private static String readableLabel(String id, String fallback) {
        if (id == null || id.trim().isEmpty()) {
            return fallback;
        }
        StringBuilder out = new StringBuilder();
        boolean capitalize = true;
        for (char character : id.trim().toCharArray()) {
            if (character == '_') {
                out.append(' ');
                capitalize = true;
                continue;
            }
            if (character >= '0' && character <= '9' && out.length() == 0) {
                capitalize = false;
            }
            out.append(capitalize
                    ? Character.toUpperCase(character) : character);
            capitalize = false;
        }
        String label = out.toString().trim();
        return label.isEmpty() ? fallback : label;
    }

    /**
     * Deterministic design checks on a resulting screen, advisory
     * only (the proposal card shows them; nothing is refused): the
     * sizing mistakes an agent makes when it places widgets without
     * thinking about how a screen is laid out. Each warning names the
     * widget and the plain fix. At most a handful are reported, so a
     * card never drowns in them.
     */
    public static List<String> designWarnings(ArrayList<ViewBean> beans) {
        Map<String, ViewBean> byId = new HashMap<>();
        for (ViewBean bean : beans) {
            byId.put(bean.id, bean);
        }
        ArrayList<String> warnings = new ArrayList<>();
        for (ViewBean bean : beans) {
            if (warnings.size() >= 6) {
                break;
            }
            if (bean.layout == null) {
                continue;
            }
            ViewBean parent = byId.get(bean.parent);
            boolean parentLinear = parent == null
                    || parent.type == ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
            boolean parentVertical = parent == null
                    || parent.layout == null
                    || parent.layout.orientation
                            != LayoutBean.ORIENTATION_HORIZONTAL;
            if (!parentLinear) {
                continue;
            }
            int axisExtent = parentVertical
                    ? bean.layout.height : bean.layout.width;
            if (bean.layout.weight > 0 && axisExtent != 0) {
                warnings.add(bean.id + " has weight " + bean.layout.weight
                        + " but its " + (parentVertical ? "height" : "width")
                        + " is not 0dp, so the weight cannot share space "
                        + "properly. Set that side to 0dp and let the "
                        + "weight divide the leftover space.");
                continue;
            }
            if (bean.type == ViewBean.VIEW_TYPE_WIDGET_EDITTEXT
                    && parentVertical
                    && bean.layout.width == LayoutBean.LAYOUT_WRAP_CONTENT
                    && bean.layout.weight == 0) {
                warnings.add(bean.id + " is a text input only as wide as "
                        + "its content. Inputs normally fill the form: "
                        + "give it match_parent width, or weight 1 "
                        + "beside a button.");
            }
            boolean button = bean.type == ViewBean.VIEW_TYPE_WIDGET_BUTTON
                    || bean.type
                            == ViewBeans.VIEW_TYPE_WIDGET_MATERIALBUTTON;
            if (button && parentVertical
                    && bean.layout.width == LayoutBean.LAYOUT_MATCH_PARENT) {
                warnings.add(bean.id + " spans the full width. That "
                        + "suits a primary action; for an ordinary "
                        + "button, wrap_content keeps it honest.");
            }
            if (button && bean.layout.height > 0
                    && bean.layout.height < 40) {
                warnings.add(bean.id + " is only " + bean.layout.height
                        + "dp tall — a small touch target. Buttons are "
                        + "easier to tap at 48dp or taller.");
            }
            if ((bean.type == ViewBean.VIEW_TYPE_WIDGET_TEXTVIEW || button
                    || bean.type == ViewBean.VIEW_TYPE_WIDGET_EDITTEXT)
                    && bean.text != null && bean.text.textSize > 0
                    && bean.text.textSize < 12) {
                warnings.add(bean.id + " has text of "
                        + bean.text.textSize
                        + "sp, which is very small to read. Body text "
                        + "is normally 14sp or more.");
            }
            if (bean.layout.width > 360) {
                warnings.add(bean.id + " has a fixed width of "
                        + bean.layout.width
                        + "dp, which can overflow smaller screens. "
                        + "match_parent, wrap_content or weight "
                        + "usually fits better.");
            }
            if (bean.type == ViewBean.VIEW_TYPE_WIDGET_PROGRESSBAR) {
                // There is no stored "hidden" property a spinner can
                // start with: it shows until logic hides it. A
                // spinner left visible is a spinner sitting on the
                // screen for good.
                warnings.add(bean.id + " is a spinner and will stay "
                        + "visible on screen until something hides it. "
                        + "Hide it in the screen's onCreate (set_visibility "
                        + "gone) and show it only while it is loading — "
                        + "unless it is meant to be on screen all the "
                        + "time.");
            }
            if (bean.type == ViewBean.VIEW_TYPE_WIDGET_EDITTEXT
                    && bean.text != null
                    && (bean.text.hint == null
                            || bean.text.hint.trim().isEmpty())
                    && (bean.text.text == null
                            || bean.text.text.trim().isEmpty())) {
                warnings.add(bean.id + " has no hint, so users see an "
                        + "empty box with no clue what to type. Give it "
                        + "a hint like \"Email\".");
            }
        }
        return warnings;
    }

    /**
     * The screen's main container: where a new widget belongs when
     * the model does not name a parent. The first top-level layout
     * that fills the screen wins; a screen whose only top-level
     * widget is one layout uses that. Anything else (several loose
     * top-level widgets, no layout) has no obvious home and keeps
     * the top level — existing screens are never restructured.
     */
    private static String mainContainerOf(ArrayList<ViewBean> work) {
        ViewBean firstTopContainer = null;
        int topCount = 0;
        for (ViewBean bean : work) {
            if (!ROOT_PARENT.equals(bean.parent)) {
                continue;
            }
            topCount++;
            if (!isContainer(bean.type)) {
                continue;
            }
            if (bean.type == ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW
                    || bean.type == ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW) {
                // A ScrollView holds exactly one child and it is
                // already placed: widgets belong inside that child,
                // never beside it. No child yet, no home to offer.
                String inner = singleContainerChild(work, bean.id);
                if (inner != null) {
                    return inner;
                }
                continue;
            }
            if (firstTopContainer == null) {
                firstTopContainer = bean;
            }
            if (bean.layout != null
                    && bean.layout.width
                            == LayoutBean.LAYOUT_MATCH_PARENT
                    && bean.layout.height
                            == LayoutBean.LAYOUT_MATCH_PARENT) {
                return bean.id;
            }
        }
        return topCount == 1 && firstTopContainer != null
                ? firstTopContainer.id : null;
    }

    /** The id of a container's single container child, if it has one. */
    private static String singleContainerChild(ArrayList<ViewBean> work,
                                               String parentId) {
        String found = null;
        for (ViewBean bean : work) {
            if (parentId.equals(bean.parent)) {
                if (found != null || !isContainer(bean.type)) {
                    return null;
                }
                found = bean.id;
            }
        }
        return found;
    }

    /**
     * Deterministic placement check on a resulting screen: in a
     * bounded vertical LinearLayout (the screen root included), a
     * match_parent-height child leaves no room for anything after
     * it, and the same holds sideways in a horizontal one. Widgets
     * parked that way exist but can never be seen — the generated
     * app has no scrolling there to reveal them. ScrollView
     * subtrees are exempt: their content is meant to overflow.
     * Only the outermost stranded widget of a subtree is reported.
     */
    public static List<String> visibilityWarnings(
            ArrayList<ViewBean> beans) {
        Map<String, ArrayList<ViewBean>> children = new HashMap<>();
        Map<String, ViewBean> byId = new HashMap<>();
        for (ViewBean bean : beans) {
            byId.put(bean.id, bean);
            String parent = bean.parent == null || bean.parent.isEmpty()
                    ? ROOT_PARENT : bean.parent;
            children.computeIfAbsent(parent, k -> new ArrayList<>())
                    .add(bean);
        }
        ArrayList<String> warnings = new ArrayList<>();
        warnChildren(ROOT_PARENT, null, false, children, byId, warnings);
        return warnings;
    }

    private static void warnChildren(String parentId, ViewBean parent,
                                     boolean scrollable,
                                     Map<String, ArrayList<ViewBean>> children,
                                     Map<String, ViewBean> byId,
                                     List<String> warnings) {
        ArrayList<ViewBean> kids = children.get(parentId);
        if (kids == null) {
            return;
        }
        boolean linear = parent == null
                || parent.type == ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
        boolean vertical = parent == null || parent.layout == null
                || parent.layout.orientation
                        != LayoutBean.ORIENTATION_HORIZONTAL;
        boolean bounded = true;
        if (parent != null && parent.layout != null) {
            int extent = vertical ? parent.layout.height
                    : parent.layout.width;
            bounded = extent == LayoutBean.LAYOUT_MATCH_PARENT
                    || extent > 0;
        }
        boolean seenFull = false;
        String fullId = null;
        for (ViewBean kid : kids) {
            boolean kidScrollable = scrollable
                    || kid.type == ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW
                    || kid.type == ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW;
            if (linear && bounded && seenFull && !scrollable) {
                warnings.add(kid.id + " would not be visible: it comes "
                        + "after " + fullId + ", which takes the full "
                        + (vertical ? "height" : "width")
                        + " of the layout, so no space is left for it. "
                        + "Move " + kid.id + " before " + fullId
                        + ", or shrink " + fullId + " to leave room.");
                continue;
            }
            if (linear && kid.layout != null) {
                int extent = vertical ? kid.layout.height
                        : kid.layout.width;
                if (extent == LayoutBean.LAYOUT_MATCH_PARENT
                        && !seenFull) {
                    seenFull = true;
                    fullId = kid.id;
                }
            }
            warnChildren(kid.id, kid, kidScrollable, children, byId,
                    warnings);
        }
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
            String frameHint = "framelayout".equalsIgnoreCase(typeName)
                    || (typeName != null && typeName.endsWith(".FrameLayout"))
                    ? " (for a stacked container like FrameLayout, use "
                            + "RelativeLayout or CardView)"
                    : "";
            throw new AgentTools.ToolException("E_BAD_TYPE",
                    "Unknown widget type \"" + typeName + "\"" + frameHint
                            + ". Widget types are the ones in Sketchware's "
                            + "own palette: " + availableTypeNames()
                            + ". Pick the closest one from that list — "
                            + "there is no other kind of widget to add.");
        }
        // The editor itself refuses these until their library is
        // switched on (ViewEditor: AdView needs AdMob, MapView needs
        // Google Maps). The agent has to say the same thing, instead
        // of building a screen that can never work.
        String needed = AgentSetup.requirementForWidget(type);
        if (needed != null) {
            JsonObject libraries = AgentSetup.libraries(scId);
            // Only a library the app reports as OFF stops the change:
            // settings that could not be read are not refused, so a
            // check can never block work it cannot see.
            if (libraries.has(needed)
                    && !libraries.get(needed).getAsBoolean()) {
                throw new AgentTools.ToolException("E_LIBRARY_REQUIRED",
                        AgentSetup.requirementMessage(needed,
                                "a " + typeName + " widget", false));
            }
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
            parent = resolveWidget(byId, parentId);
            if (parent == null) {
                throw new AgentTools.ToolException("E_NO_PARENT",
                        "There is no widget called " + parentId
                                + ". A widget can only be added to a layout "
                                + "that exists or was added earlier in this "
                                + "same edit. Widgets here: "
                                + idList(byId) + ".");
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
            ResColor rc = resolveResColor(scId, background, frozen);
            if (rc != null) {
                bean.layout.backgroundResColor = rc.resRef;
                bean.layout.backgroundColor = rc.fallbackInt;
                colorNote = ", background " + rc.resRef;
            } else {
                int color = parseColor(scId, background);
                bean.layout.backgroundColor = color;
                colorNote = ", background "
                        + colorWord(scId, background, color);
            }
        }
        String textColor = stringOf(op, "text_color");
        if (!textColor.isEmpty()) {
            ResColor rc = resolveResColor(scId, textColor, frozen);
            if (rc != null) {
                bean.text.resTextColor = rc.resRef;
                bean.text.textColor = rc.fallbackInt;
            } else {
                bean.text.textColor = parseColor(scId, textColor);
            }
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
            case "setroot", "rootbackground", "setrootbackground",
                    "convertroot", "convertrootlayout", "setrootlayout" ->
                    "set_root";
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
            case "setvisibility", "visibility" -> "set_visibility";
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
        // Models often name a widget by its Android class
        // ("androidx.recyclerview.widget.RecyclerView"): the palette
        // knows it by its simple name, so a package is not a reason
        // to fail — resolve the class's own name instead.
        if (name != null && name.indexOf('.') >= 0) {
            String simple = name.substring(name.lastIndexOf('.') + 1)
                    .trim();
            if (!simple.isEmpty()) {
                Integer bySimple = typeByName(simple);
                if (bySimple != null) {
                    return bySimple;
                }
            }
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

    /**
     * Every widget type the palette offers, for an error that has to
     * end the guessing: a model asked for "FrameLayout" (an Android
     * class Sketchware has no palette entry for) and kept trying.
     */
    private static String availableTypeNames() {
        java.util.LinkedHashSet<String> unique =
                new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : TYPE_BY_NAME.entrySet()) {
            unique.add(entry.getKey());
        }
        for (int candidate = 19; candidate <= 48; candidate++) {
            String name = ViewBeans.getViewTypeName(candidate);
            if (!name.isEmpty()) {
                unique.add(name);
            }
        }
        unique.add("FloatingActionButton");
        ArrayList<String> names = new ArrayList<>(unique);
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", names);
    }

    private static String upsertInjectAttr(
            String existing, String attrName, String newValue) {
        StringBuilder out = new StringBuilder();
        if (existing != null && !existing.isEmpty()) {
            for (String line : existing.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                String key = eq > 0 ? trimmed.substring(0, eq).trim() : "";
                if (attrName.equals(key)) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append("\n");
                }
                out.append(trimmed);
            }
        }
        if (newValue != null && !newValue.isEmpty()) {
            if (out.length() > 0) {
                out.append("\n");
            }
            out.append(attrName).append("=\"").append(newValue).append("\"");
        }
        return out.toString();
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
    private static final class ResColor {
        final String resRef;
        final int fallbackInt;

        ResColor(String resRef, int fallbackInt) {
            this.resRef = resRef;
            this.fallbackInt = fallbackInt;
        }
    }

    /**
     * Resolves {@code @color/<name>} or {@code ?attr/<name>} for widgets so
     * {@code Ox} writes the real XML resource/theme reference while keeping a
     * valid preview integer on {@code ViewBean}.
     */
    private static ResColor resolveResColor(String scId, String raw,
                                            boolean frozen)
            throws AgentTools.ToolException {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("@color/")) {
            String name = trimmed.substring("@color/".length()).trim();
            if (!name.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
                throw new AgentTools.ToolException("E_BAD_COLOR",
                        "Invalid @color reference \"" + trimmed + "\".");
            }
            Integer resolved = AgentResources.resolveColorInt(scId, name);
            if (resolved == null) {
                if (frozen) {
                    resolved = 0xFF808080;
                } else {
                    throw new AgentTools.ToolException("E_BAD_COLOR",
                            "Unknown color resource \"@color/" + name
                                    + "\". Define it in colors.xml with "
                                    + "edit_values_resource first.");
                }
            }
            return new ResColor("@color/" + name, resolved);
        }
        if (trimmed.startsWith("?attr/")
                || trimmed.startsWith("?android:attr/")) {
            if (!trimmed.matches("^\\?(android:)?attr/[a-zA-Z][a-zA-Z0-9_]*$")) {
                throw new AgentTools.ToolException("E_BAD_COLOR",
                        "Invalid theme attribute reference \"" + trimmed
                                + "\". Example: ?attr/colorSurface.");
            }
            return new ResColor(trimmed, 0xFF808080);
        }
        return null;
    }

    public static int parseColor(String scId, String value)
            throws AgentTools.ToolException {
        String color = value == null ? "" : value.trim();
        if (color.startsWith("@color/")) {
            String name = color.substring("@color/".length()).trim();
            Integer resolved = AgentResources.resolveColorInt(scId, name);
            if (resolved == null) {
                throw new AgentTools.ToolException("E_BAD_COLOR",
                        "Unknown color resource \"@color/" + name
                                + "\". Define it in colors.xml with "
                                + "edit_values_resource first.");
            }
            return resolved;
        }
        if (color.startsWith("@")) {
            String word = color.toLowerCase(Locale.ROOT);
            // Derived tints: a base token lightened toward white by a
            // fixed step, computed here from the project's real colour —
            // "a lighter shade of the primary" is arithmetic, not a
            // guess the model makes up.
            float tint = -1f;
            String base = word;
            if (word.endsWith("_tint")) {
                tint = 0.40f;
                base = word.substring(0, word.length() - "_tint".length());
            } else if (word.endsWith("_light")) {
                tint = 0.65f;
                base = word.substring(0, word.length() - "_light".length());
            } else if (word.endsWith("_pale")) {
                tint = 0.85f;
                base = word.substring(0, word.length() - "_pale".length());
            }
            int token = SketchwareProjects.projectColor(scId,
                    switch (base) {
                        case "@primary" -> "color_primary";
                        case "@primary_dark" -> "color_primary_dark";
                        case "@accent" -> "color_accent";
                        default -> null;
                    });
            if (token == Integer.MIN_VALUE) {
                throw new AgentTools.ToolException("E_BAD_COLOR",
                        "Unknown colour token \"" + color + "\". Use "
                                + "@primary, @primary_dark, @accent, a "
                                + "lighter tint of them (@primary_tint, "
                                + "@primary_light, @primary_pale, "
                                + "@accent_tint, @accent_light, "
                                + "@accent_pale), or a hex colour like "
                                + "#3F51B5.");
            }
            return tint < 0f ? token : lighten(token, tint);
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

    /** Mixes a colour toward white; 0 is unchanged, 1 is white. */
    private static int lighten(int color, float fraction) {
        int alpha = (color >> 24) & 0xFF;
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        red += (int) ((255 - red) * fraction);
        green += (int) ((255 - green) * fraction);
        blue += (int) ((255 - blue) * fraction);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
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
            case "@primary_tint" -> "a tint of Primary (" + hex + ")";
            case "@primary_light" -> "a light tint of Primary (" + hex + ")";
            case "@primary_pale" -> "a pale tint of Primary (" + hex + ")";
            case "@accent_tint" -> "a tint of Accent (" + hex + ")";
            case "@accent_light" -> "a light tint of Accent (" + hex + ")";
            case "@accent_pale" -> "a pale tint of Accent (" + hex + ")";
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
            if (word.equals("match_parent")
                    || word.equals("fill_parent")) {
                return LayoutBean.LAYOUT_MATCH_PARENT;
            }
            if (word.equals("wrap_content")) {
                return LayoutBean.LAYOUT_WRAP_CONTENT;
            }
            // "120dp" and "120.5" mean 120 to the property editor.
            String digits = word.replace("dp", "")
                    .replace("sp", "").replace("px", "");
            try {
                int dp = (int) Math.round(Double.parseDouble(digits));
                if (dp >= 0 && dp <= 2000) {
                    return dp;
                }
            } catch (Exception ignored) {
                // Falls through to the checked integer read below.
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

    /**
     * Finds a widget by the id the model wrote. Models copy the
     * user's words ("linear 1") as readily as the real id
     * ("linear1"), so after an exact miss, ids are compared with
     * spaces and underscores removed, case aside. Sketchware ids
     * never contain spaces, so this cannot confuse two real ids.
     */
    private static ViewBean resolveWidget(Map<String, ViewBean> byId,
                                          String raw) {
        if (raw == null) {
            return null;
        }
        ViewBean bean = byId.get(raw.trim());
        if (bean != null) {
            return bean;
        }
        String wanted = raw.replaceAll("[\\s_]+", "")
                .toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ViewBean> entry : byId.entrySet()) {
            if (entry.getKey().replaceAll("[\\s_]+", "")
                    .toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Widget ids on the screen, for error messages that teach. */
    private static String idList(Map<String, ViewBean> byId) {
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (String id : byId.keySet()) {
            if (shown == 24) {
                list.append(", …");
                break;
            }
            if (list.length() > 0) {
                list.append(", ");
            }
            list.append(id);
            shown++;
        }
        return list.toString();
    }

    private static ViewBean requireWidget(JsonObject op,
                                          Map<String, ViewBean> byId)
            throws AgentTools.ToolException {
        String id = widgetIdOf(op);
        ViewBean bean = resolveWidget(byId, id);
        if (bean == null) {
            throw new AgentTools.ToolException("E_NO_WIDGET",
                    "There is no widget called " + id + " on this screen. "
                            + "Widgets here: " + idList(byId) + ".");
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

    /** Maps a resolved ViewGroup class name to its Sketchware parentType. */
    private static int rootTypeForClassName(String className) {
        if (className == null || className.isEmpty()) {
            return ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
        }
        String simple = className.contains(".")
                ? className.substring(className.lastIndexOf('.') + 1)
                : className;
        return switch (simple) {
            case "RelativeLayout" -> ViewBean.VIEW_TYPE_LAYOUT_RELATIVE;
            case "ScrollView", "VScrollView", "NestedScrollView" ->
                    ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW;
            case "HorizontalScrollView", "HScrollView" ->
                    ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW;
            case "CardView", "MaterialCardView" ->
                    ViewBeans.VIEW_TYPE_LAYOUT_CARDVIEW;
            case "SwipeRefreshLayout" ->
                    ViewBeans.VIEW_TYPE_LAYOUT_SWIPEREFRESHLAYOUT;
            case "RadioGroup" -> ViewBeans.VIEW_TYPE_LAYOUT_RADIOGROUP;
            default -> ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
        };
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
            case ViewBean.VIEW_TYPE_WIDGET_IMAGEVIEW -> {
                // The editor's own palette writes "default_image" here;
                // a null resName crashes its view loader
                // (resName.equals(...)) when the screen is reopened.
                bean.image.resName = "default_image";
            }
            default -> {
                // Anything else starts from the plain bean, exactly as
                // its palette icon does.
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
                bean.image.resName = "default_image";
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

    // Custom attributes are per widget. These are the editor's own lists
    // (PropertyInputItem.populateAttributes), kept here so the agent and
    // the property panel tell the model the same story.

    private static final Set<String> ATTRS_VIEW = attrs(
            "android:background", "android:backgroundTint", "android:alpha",
            "android:visibility", "android:padding", "android:layout_margin",
            "android:elevation", "android:rotation", "android:scaleX",
            "android:scaleY", "android:translationX",
            "android:translationY");

    private static final Set<String> ATTRS_TEXT_VIEW = attrs(
            "android:text", "android:textSize", "android:textColor",
            "android:textStyle", "android:fontFamily", "android:typeface",
            "android:lines", "android:maxLines", "android:singleLine",
            "android:gravity", "android:ellipsize", "android:hint",
            "android:textColorHint", "android:inputType",
            "android:imeOptions", "android:drawableLeft",
            "android:drawableTop", "android:drawableRight",
            "android:drawableBottom", "android:drawablePadding",
            "android:drawableTint");

    private static final Set<String> ATTRS_IMAGE_VIEW = attrs(
            "android:src", "android:scaleType", "android:tint",
            "android:adjustViewBounds", "android:cropToPadding");

    private static final Set<String> ATTRS_LINEAR_LAYOUT = attrs(
            "android:orientation", "android:gravity", "android:weightSum",
            "android:baselineAligned");

    private static final Set<String> ATTRS_VIEW_GROUP = attrs(
            "android:clipChildren", "android:clipToPadding",
            "android:animateLayoutChanges");

    private static final Set<String> ATTRS_PROGRESS_BAR = attrs(
            "android:max", "android:progress", "android:indeterminate",
            "android:indeterminateTint", "android:progressTint");

    private static final Set<String> ATTRS_COMPOUND_BUTTON = attrs(
            "android:checked", "android:button", "android:buttonTint");

    private static final Set<String> ATTRS_CARD_VIEW = attrs(
            "app:cardBackgroundColor", "app:cardCornerRadius",
            "app:cardElevation", "app:cardMaxElevation",
            "app:cardUseCompatPadding", "app:cardPreventCornerOverlap",
            "app:contentPadding", "app:strokeColor", "app:strokeWidth");

    private static final Set<String> ATTRS_MATERIAL_BUTTON = attrs(
            "app:icon", "app:iconTint", "app:iconGravity", "app:iconPadding",
            "app:strokeColor", "app:strokeWidth", "app:cornerRadius",
            "app:rippleColor");

    /** What a plain name means, when the model uses it on the wrong thing. */
    private static final Map<String, String> ATTR_OWNERS =
            new HashMap<>();

    static {
        ATTR_OWNERS.put("app:cardCornerRadius", "a CardView");
        ATTR_OWNERS.put("app:cardElevation", "a CardView");
        ATTR_OWNERS.put("app:contentPadding", "a CardView");
        ATTR_OWNERS.put("app:cornerRadius", "a MaterialButton");
        ATTR_OWNERS.put("app:rippleColor", "a MaterialButton");
        ATTR_OWNERS.put("android:weightSum", "a LinearLayout");
        ATTR_OWNERS.put("android:baselineAligned", "a LinearLayout");
        ATTR_OWNERS.put("android:src", "an ImageView");
        ATTR_OWNERS.put("android:scaleType", "an ImageView");
        ATTR_OWNERS.put("android:checked", "a CompoundButton such as CheckBox or Switch");
        ATTR_OWNERS.put("android:buttonTint", "a CompoundButton such as CheckBox or Switch");
        ATTR_OWNERS.put("android:progress", "a ProgressBar or SeekBar");
        ATTR_OWNERS.put("android:indeterminate", "a ProgressBar");
    }

    private static LinkedHashSet<String> attrs(String... names) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Collections.addAll(set, names);
        return set;
    }

    /**
     * The custom attributes one widget accepts — the editor's own rule,
     * using the same class tests it does ({@code a} = is or extends,
     * {@code b} = exactly this class), so the two agree.
     */
    private static Set<String> allowedAttributes(ViewBean bean) {
        LinkedHashSet<String> set = new LinkedHashSet<>(ATTRS_VIEW);
        Gx classInfo = bean.getClassInfo();
        if (classInfo.a("TextView")) {
            set.addAll(ATTRS_TEXT_VIEW);
        }
        if (classInfo.a("ImageView")) {
            set.addAll(ATTRS_IMAGE_VIEW);
        }
        if (classInfo.a("LinearLayout")) {
            set.addAll(ATTRS_LINEAR_LAYOUT);
        }
        if (classInfo.a("ViewGroup")) {
            set.addAll(ATTRS_VIEW_GROUP);
        }
        if (classInfo.a("ProgressBar")) {
            set.addAll(ATTRS_PROGRESS_BAR);
        }
        if (classInfo.a("CompoundButton")) {
            set.addAll(ATTRS_COMPOUND_BUTTON);
        }
        if (classInfo.b("CardView")) {
            set.addAll(ATTRS_CARD_VIEW);
        }
        if (classInfo.b("MaterialButton")) {
            set.addAll(ATTRS_MATERIAL_BUTTON);
        }
        switch (bean.type) {
            case ViewBeans.VIEW_TYPE_WIDGET_CIRCLEIMAGEVIEW ->
                    Collections.addAll(set, "app:civ_border_width",
                            "app:civ_border_color",
                            "app:civ_circle_background_color",
                            "app:civ_border_overlay");
            case ViewBeans.VIEW_TYPE_WIDGET_LOTTIEANIMATIONVIEW ->
                    Collections.addAll(set, "android:scaleType",
                            "android:layout_centerInParent",
                            "app:lottie_fileName", "app:lottie_loop",
                            "app:lottie_autoPlay");
            case ViewBeans.VIEW_TYPE_LAYOUT_TABLAYOUT ->
                    Collections.addAll(set, "app:tabGravity",
                            "app:tabMode", "app:tabIndicatorHeight",
                            "app:tabIndicatorColor",
                            "app:tabSelectedTextColor",
                            "app:tabTextColor", "app:tabTextAppearance");
            case ViewBeans.VIEW_TYPE_WIDGET_YOUTUBEPLAYERVIEW ->
                    Collections.addAll(set, "app:autoPlay",
                            "app:useWebUi", "app:showYouTubeButton",
                            "app:showFullScreenButton",
                            "app:showVideoCurrentTime",
                            "app:showVideoDuration", "app:showSeekBar",
                            "app:handleNetworkEvents",
                            "app:enableAutomaticInitialization");
            case ViewBeans.VIEW_TYPE_LAYOUT_COLLAPSINGTOOLBARLAYOUT ->
                    Collections.addAll(set, "android:fitsSystemWindows",
                            "app:layout_scrollFlags", "app:titleEnabled",
                            "app:contentScrim");
            case ViewBeans.VIEW_TYPE_WIDGET_WAVESIDEBAR ->
                    Collections.addAll(set, "app:sidebar_text_alignment",
                            "app:sidebar_position",
                            "app:sidebar_max_offset");
            case ViewBeans.VIEW_TYPE_WIDGET_GRIDVIEW ->
                    Collections.addAll(set, "android:numColumns",
                            "android:stretchMode",
                            "android:horizontalSpacing",
                            "android:verticalSpacing");
            case ViewBeans.VIEW_TYPE_LAYOUT_TEXTINPUTLAYOUT ->
                    Collections.addAll(set, "app:boxBackgroundMode",
                            "app:boxBackgroundColor",
                            "app:boxStrokeColor",
                            "app:boxCornerRadiusTopStart",
                            "app:boxCornerRadiusTopEnd",
                            "app:boxCornerRadiusBottomStart",
                            "app:boxCornerRadiusBottomEnd",
                            "app:hintEnabled", "app:counterEnabled",
                            "app:counterMaxLength", "app:endIconMode");
            default -> {
            }
        }
        return set;
    }

    /**
     * The editor's Custom attributes (Inject) field, built from what
     * the model sent: either an object of attribute to value, or a raw
     * string of newline-separated attributes.
     */
    private static String customAttributes(JsonObject op,
            ViewBean bean)
            throws AgentTools.ToolException {
        StringBuilder out = new StringBuilder();
        if (op.has("attributes") && op.get("attributes").isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> field
                    : op.getAsJsonObject("attributes").entrySet()) {
                String value = field.getValue().isJsonPrimitive()
                        ? field.getValue().getAsString().trim() : "";
                if (value.isEmpty()) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append("\n");
                }
                out.append(checkAttribute(bean, field.getKey(), value));
            }
        } else {
            String raw = stringOf(op, "attributes");
            if (raw.isEmpty()) {
                raw = stringOf(op, "value");
            }
            for (String line : raw.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    throw new AgentTools.ToolException("E_BAD_VALUE",
                            "A custom attribute looks like "
                                    + "app:cardCornerRadius=8dp — a name, "
                                    + "then =, then the value.");
                }
                if (out.length() > 0) {
                    out.append("\n");
                }
                out.append(checkAttribute(bean,
                        trimmed.substring(0, eq).trim(),
                        trimmed.substring(eq + 1).trim()));
            }
        }
        if (out.length() == 0) {
            throw new AgentTools.ToolException("E_BAD_VALUE",
                    "set_attributes needs \"attributes\": either an "
                            + "object like {\"app:cardCornerRadius\":"
                            + "\"8dp\"} or a string of lines like "
                            + "app:cardCornerRadius=8dp.");
        }
        return out.toString();
    }

    /**
     * One custom attribute, checked. The layout writer strips spaces
     * itself (Ox: inject.replace(" ", "")), so a value with a space in
     * it would arrive wrong on screen — refuse it here instead.
     */
    private static String checkAttribute(ViewBean bean, String name,
            String value) throws AgentTools.ToolException {
        String cleanName = name.trim();
        String cleanValue = value.trim().replace(" ", "")
                .replace("\"", "");
        if (!cleanName.matches("[a-zA-Z]+:[a-zA-Z][a-zA-Z0-9_]*")) {
            throw new AgentTools.ToolException("E_BAD_VALUE",
                    "\"" + cleanName + "\" is not a custom attribute "
                            + "name: it must look like "
                            + "app:cardCornerRadius or android:elevation.");
        }
        if (cleanValue.isEmpty()
                || cleanValue.contains("<") || cleanValue.contains(">")) {
            throw new AgentTools.ToolException("E_BAD_VALUE",
                    "The custom attribute " + cleanName + " needs a plain "
                            + "value with no spaces or angle brackets "
                            + "(e.g. 8dp, 4, #FF8A3D) — it is written "
                            + "straight into the widget's XML tag.");
        }
        Set<String> allowed = allowedAttributes(bean);
        if (!allowed.contains(cleanName)) {
            StringBuilder message = new StringBuilder();
            message.append(bean.id).append(" is a ")
                    .append(classWord(bean)).append(", and it does not take ")
                    .append(cleanName).append(". ");
            String owner = ATTR_OWNERS.get(cleanName);
            if (owner != null) {
                message.append("That one belongs to ").append(owner)
                        .append(". ");
            }
            message.append("Custom attributes for a ").append(classWord(bean))
                    .append(": ").append(joinNames(allowed)).append(".");
            throw new AgentTools.ToolException("E_BAD_VALUE",
                    message.toString());
        }
        return cleanName + "=\"" + cleanValue + "\"";
    }

    /** The widget's own class name, the way the editor words it. */
    private static String classWord(ViewBean bean) {
        String name = bean.getClassInfo().getClassName();
        return name == null || name.isEmpty() ? typeName(bean.type) : name;
    }

    /** A readable, bounded list of attribute names. */
    private static String joinNames(Set<String> names) {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (String name : sorted) {
            if (shown >= 14) {
                out.append(" and ").append(sorted.size() - shown)
                        .append(" more");
                break;
            }
            if (shown > 0) {
                out.append(", ");
            }
            out.append(name);
            shown++;
        }
        return out.toString();
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

    private static String stripDrawablePrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("@drawable/")) {
            s = s.substring("@drawable/".length()).trim();
        }
        if (s.endsWith(".png") || s.endsWith(".xml") || s.endsWith(".svg")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }

    private static void requireDrawableExists(String scId,
                                              String image,
                                              String targetDesc,
                                              boolean frozen)
            throws AgentTools.ToolException {
        if (frozen || scId == null || scId.isEmpty()) {
            return;
        }
        if ("default_image".equals(image)
                || "app_icon".equals(image)
                || "ic_add_white".equals(image)) {
            return;
        }
        SketchwareProjects.Resources res =
                SketchwareProjects.resources(scId);
        if (res.images.contains(image)
                || res.customDrawables.contains(image)) {
            return;
        }
        throw new AgentTools.ToolException("E_MISSING_DRAWABLE",
                "Drawable \"" + image + "\" does not exist in this project "
                        + "yet, so it cannot be set on " + targetDesc
                        + ". Import or create it first with "
                        + "import_builtin_icon (Material icon), "
                        + "import_chat_image (user-attached photo/logo), "
                        + "or manage_drawable (custom XML shape/ripple/"
                        + "vector), then call edit_screen.");
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 24 ? text : text.substring(0, 24) + "…";
    }
}
