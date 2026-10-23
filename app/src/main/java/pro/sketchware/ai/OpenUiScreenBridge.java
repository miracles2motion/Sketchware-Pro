package pro.sketchware.ai;

import com.besome.sketch.beans.LayoutBean;
import com.besome.sketch.beans.TextBean;
import com.besome.sketch.beans.ViewBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import mod.agus.jcoderz.beans.ViewBeans;

/**
 * Translates between compact OpenUI Lang 1.0 screen programs (including the
 * full OpenUI high-level component &amp; TextView role library) and Sketchware
 * Pro {@link ViewBean} screen layouts / {@link AgentEdits} operations.
 *
 * <p>Preserves 100% compatibility with Sketchware Pro's native View/Logic editors,
 * customizable OpenUI Design System tokens ({@code @color/openui_*}, {@code openui_*}
 * drawables), per-widget {@code ViewBean.inject} validation, Material 3
 * {@code <Button>} tint/padding safety, and the native Approval Card flow:
 * <ul>
 *   <li>{@link #toOpenUi(List)} renders any screen's {@link ViewBean} tree as
 *       compact OpenUI Lang statements (~60-65% fewer tokens than verbose JSON).</li>
 *   <li>{@link #compileToOps(String, List, String, boolean)} compiles either a full
 *       OpenUI screen definition or a 2-line incremental patch
 *       ({@link OpenUiLang#mergeStatements}) into canonical {@link AgentEdits} JSON
 *       operations — updating existing widgets in place so wired {@code BlockBean}
 *       events are never disturbed.</li>
 * </ul>
 */
public final class OpenUiScreenBridge {

    /** Maximum compiled micro-operations permitted for a single OpenUI screen build/patch. */
    public static final int MAX_COMPILED_OPS = 300;

    private static final Set<String> ROOT_ENTRY_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "Screen", "Column", "VStack", "LinearV", "Row", "HStack", "LinearH",
                    "Stack", "Form", "LinearLayout", "RelativeLayout", "ScrollView",
                    "Scroll", "NestedScrollView", "HorizontalScrollView", "HScroll",
                    "CardView", "Card", "SwipeRefreshLayout", "SwipeRefresh")));

    private OpenUiScreenBridge() {
    }

    // ---------------------------------------------------------------------
    // ViewBean Tree -> Canonical OpenUI Lang 1.0 Serializer
    // ---------------------------------------------------------------------

    /**
     * Serializes a screen's {@link ViewBean} list into compact 1-line-per-widget
     * OpenUI Lang 1.0 statements with {@code root = Screen([...])} at the top.
     */
    public static String toOpenUi(List<ViewBean> beans) {
        if (beans == null || beans.isEmpty()) {
            return "root = Screen([])";
        }
        Set<String> allIds = new HashSet<>();
        for (ViewBean b : beans) {
            if (b != null && b.id != null) {
                allIds.add(b.id);
            }
        }
        Map<String, List<ViewBean>> childrenByParent = new LinkedHashMap<>();
        List<ViewBean> topLevel = new ArrayList<>();
        for (ViewBean b : beans) {
            if (b == null || b.id == null) {
                continue;
            }
            String p = b.parent;
            if (p == null || p.isEmpty() || "root".equalsIgnoreCase(p) || !allIds.contains(p)) {
                topLevel.add(b);
            } else {
                List<ViewBean> kids = childrenByParent.get(p);
                if (kids == null) {
                    kids = new ArrayList<>();
                    childrenByParent.put(p, kids);
                }
                kids.add(b);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("root = Screen([");
        for (int i = 0; i < topLevel.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(topLevel.get(i).id);
        }
        sb.append("])");

        for (ViewBean rootBean : topLevel) {
            emitBeanStatements(rootBean, childrenByParent, sb);
        }
        return sb.toString();
    }

    private static void emitBeanStatements(ViewBean bean,
                                           Map<String, List<ViewBean>> childrenByParent,
                                           StringBuilder sb) {
        sb.append('\n').append(bean.id).append(" = ");
        String compName = compactComponentName(bean);
        sb.append(compName).append('(');

        boolean container = AgentEdits.isContainer(bean.type);
        List<ViewBean> kids = childrenByParent.get(bean.id);
        String propsLiteral = formatPropsObject(bean, compName);

        if (container) {
            sb.append('[');
            if (kids != null) {
                for (int i = 0; i < kids.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(kids.get(i).id);
                }
            }
            sb.append(']');
            if (!propsLiteral.isEmpty()) {
                sb.append(", ").append(propsLiteral);
            }
        } else {
            String primaryArg = primaryScalarArg(bean);
            if (primaryArg != null) {
                sb.append(OpenUiLang.quoteString(primaryArg));
                if (!propsLiteral.isEmpty()) {
                    sb.append(", ").append(propsLiteral);
                }
            } else if (!propsLiteral.isEmpty()) {
                sb.append("\"\", ").append(propsLiteral);
            }
        }
        sb.append(')');

        if (kids != null) {
            for (ViewBean kid : kids) {
                emitBeanStatements(kid, childrenByParent, sb);
            }
        }
    }

    private static String compactComponentName(ViewBean bean) {
        if (bean.type == ViewBean.VIEW_TYPE_LAYOUT_LINEAR) {
            boolean horiz = bean.layout != null
                    && bean.layout.orientation == LayoutBean.ORIENTATION_HORIZONTAL;
            return horiz ? "Row" : "Column";
        }
        return AgentEdits.typeName(bean.type);
    }

    private static String primaryScalarArg(ViewBean bean) {
        if (bean.type == ViewBean.VIEW_TYPE_WIDGET_EDITTEXT
                || bean.type == ViewBeans.VIEW_TYPE_WIDGET_AUTOCOMPLETETEXTVIEW
                || bean.type == ViewBeans.VIEW_TYPE_WIDGET_MULTIAUTOCOMPLETETEXTVIEW) {
            if (bean.text != null && bean.text.hint != null && !bean.text.hint.isEmpty()) {
                return bean.text.hint;
            }
            return "";
        }
        if (bean.type == ViewBean.VIEW_TYPE_WIDGET_IMAGEVIEW
                || bean.type == ViewBeans.VIEW_TYPE_WIDGET_CIRCLEIMAGEVIEW) {
            if (bean.image != null && bean.image.resName != null
                    && !bean.image.resName.isEmpty()
                    && !"default_image".equals(bean.image.resName)) {
                return bean.image.resName;
            }
            return null;
        }
        if (bean.type == ViewBean.VIEW_TYPE_WIDGET_LISTVIEW
                || bean.type == ViewBeans.VIEW_TYPE_WIDGET_GRIDVIEW
                || bean.type == ViewBeans.VIEW_TYPE_WIDGET_RECYCLERVIEW
                || bean.type == ViewBeans.VIEW_TYPE_LAYOUT_VIEWPAGER
                || bean.type == ViewBean.VIEW_TYPE_WIDGET_SPINNER) {
            if (bean.customView != null && !bean.customView.isEmpty()) {
                return bean.customView;
            }
            return null;
        }
        if (bean.text != null && bean.text.text != null && !bean.text.text.isEmpty()) {
            return bean.text.text;
        }
        return null;
    }

    private static String formatPropsObject(ViewBean bean, String compName) {
        List<String> pairs = new ArrayList<>();
        if (bean.layout != null) {
            LayoutBean l = bean.layout;
            int defW = AgentEdits.isContainer(bean.type)
                    ? LayoutBean.LAYOUT_MATCH_PARENT : LayoutBean.LAYOUT_WRAP_CONTENT;
            if (l.width != defW) {
                pairs.add("w: " + formatDim(l.width));
            }
            if (l.height != LayoutBean.LAYOUT_WRAP_CONTENT) {
                pairs.add("h: " + formatDim(l.height));
            }
            if (l.weight > 0) {
                pairs.add("weight: " + l.weight);
            }
            if (!"Row".equals(compName) && !"Column".equals(compName)
                    && bean.type == ViewBean.VIEW_TYPE_LAYOUT_LINEAR) {
                pairs.add("orient: " + (l.orientation == LayoutBean.ORIENTATION_HORIZONTAL
                        ? "\"horizontal\"" : "\"vertical\""));
            }
            if (l.paddingLeft != 0 || l.paddingTop != 0
                    || l.paddingRight != 0 || l.paddingBottom != 0) {
                pairs.add("p: " + formatBox(l.paddingLeft, l.paddingTop,
                        l.paddingRight, l.paddingBottom));
            }
            if (l.marginLeft != 0 || l.marginTop != 0
                    || l.marginRight != 0 || l.marginBottom != 0) {
                pairs.add("m: " + formatBox(l.marginLeft, l.marginTop,
                        l.marginRight, l.marginBottom));
            }
            if (l.backgroundResColor != null && !l.backgroundResColor.isEmpty()) {
                pairs.add("bg: " + OpenUiLang.quoteString(l.backgroundResColor));
            } else if (l.backgroundColor != 0 && l.backgroundColor != 0x00FFFFFF) {
                pairs.add("bg: " + OpenUiLang.quoteString(formatColorHex(l.backgroundColor)));
            }
            if (l.backgroundResource != null && !l.backgroundResource.isEmpty()) {
                pairs.add("bgImg: " + OpenUiLang.quoteString(l.backgroundResource));
            }
        }
        if (bean.text != null) {
            TextBean t = bean.text;
            if ((bean.type == ViewBean.VIEW_TYPE_WIDGET_EDITTEXT
                    || bean.type == ViewBeans.VIEW_TYPE_WIDGET_AUTOCOMPLETETEXTVIEW)
                    && t.text != null && !t.text.isEmpty()) {
                pairs.add("text: " + OpenUiLang.quoteString(t.text));
            }
            if (t.resTextColor != null && !t.resTextColor.isEmpty()) {
                pairs.add("color: " + OpenUiLang.quoteString(t.resTextColor));
            } else if (t.textColor != 0 && t.textColor != 0xFF000000) {
                pairs.add("color: " + OpenUiLang.quoteString(formatColorHex(t.textColor)));
            }
            if (t.resHintColor != null && !t.resHintColor.isEmpty()) {
                pairs.add("hintColor: " + OpenUiLang.quoteString(t.resHintColor));
            }
            if (t.textSize > 0 && t.textSize != 12 && t.textSize != 14) {
                pairs.add("size: " + t.textSize);
            }
            if (t.textType == TextBean.TEXT_TYPE_BOLD) {
                pairs.add("style: \"bold\"");
            } else if (t.textType == TextBean.TEXT_TYPE_ITALIC) {
                pairs.add("style: \"italic\"");
            } else if (t.textType == TextBean.TEXT_TYPE_BOLDITALIC) {
                pairs.add("style: \"bold_italic\"");
            }
        }
        if (bean.inject != null && !bean.inject.trim().isEmpty()) {
            Map<String, String> attrs = parseInjectMap(bean.inject);
            if (!attrs.isEmpty()) {
                StringBuilder ab = new StringBuilder("{");
                int idx = 0;
                for (Map.Entry<String, String> e : attrs.entrySet()) {
                    if (idx++ > 0) {
                        ab.append(", ");
                    }
                    ab.append(OpenUiLang.quoteString(e.getKey()))
                            .append(": ")
                            .append(OpenUiLang.quoteString(e.getValue()));
                }
                ab.append('}');
                pairs.add("attrs: " + ab);
            }
        }
        if (pairs.isEmpty()) {
            return "";
        }
        return "{" + String.join(", ", pairs) + "}";
    }

    private static String formatDim(int dim) {
        if (dim == LayoutBean.LAYOUT_MATCH_PARENT) {
            return "\"match\"";
        }
        if (dim == LayoutBean.LAYOUT_WRAP_CONTENT) {
            return "\"wrap\"";
        }
        return Integer.toString(dim);
    }

    private static String formatBox(int l, int t, int r, int b) {
        if (l == t && t == r && r == b) {
            return Integer.toString(l);
        }
        return "[" + l + ", " + t + ", " + r + ", " + b + "]";
    }

    private static String formatColorHex(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0xFF) {
            return String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF);
        }
        return String.format(Locale.ROOT, "#%08X", argb);
    }

    private static Map<String, String> parseInjectMap(String rawInject) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawInject == null || rawInject.trim().isEmpty()) {
            return map;
        }
        for (String line : rawInject.replace("\r\n", "\n").split("\n")) {
            String t = line.trim();
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = t.substring(0, eq).trim();
            String v = t.substring(eq + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            if (!k.isEmpty()) {
                map.put(k, v);
            }
        }
        return map;
    }

    // ---------------------------------------------------------------------
    // OpenUI Lang 1.0 -> Canonical AgentEdits JSON Operations Compiler
    // ---------------------------------------------------------------------

    public static JsonArray compileToOps(List<ViewBean> existingBeans,
                                         String rawOpenUi,
                                         boolean patchMode)
            throws AgentTools.ToolException {
        return compileToOps("", existingBeans, rawOpenUi, patchMode);
    }

    /**
     * Compiles an OpenUI Lang 1.0 screen specification (or incremental 2-line patch)
     * against {@code existingBeans} into a validated list of {@link AgentEdits} operations.
     */
    public static JsonArray compileToOps(String scId,
                                         List<ViewBean> existingBeans,
                                         String rawOpenUi,
                                         boolean patchMode)
            throws AgentTools.ToolException {
        if (rawOpenUi == null || rawOpenUi.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_EMPTY_OPENUI",
                    "Provide non-empty OpenUI Lang statements in "
                            + (patchMode ? "\"openui_patch\"" : "\"openui\"") + ".");
        }

        String safeScId = scId == null ? "" : scId.trim();
        JsonObject dsObj = !safeScId.isEmpty()
                ? AgentJournal.designSystem(safeScId)
                : AgentJournal.draftOrEmptyDesignSystem();
        OpenUiThemeEngine.ResolvedTheme activeTheme =
                OpenUiThemeEngine.resolveTheme(dsObj);
        boolean hasOpenUiTheme = !safeScId.isEmpty()
                && (AgentJournal.hasDesignSystem(safeScId)
                        || AgentResources.resolveColorInt(safeScId, "openui_bg") != null);

        String cleanedInput = stripOptionalFence(rawOpenUi);
        List<ViewBean> safeExisting = existingBeans != null
                ? existingBeans : Collections.emptyList();

        String effectiveSource;
        Set<String> explicitDeletions = new HashSet<>();
        if (patchMode && !safeExisting.isEmpty()) {
            OpenUiLang.Program rawPatch = OpenUiLang.parse(cleanedInput, false);
            explicitDeletions.addAll(rawPatch.deletions);
            String baseOpenUi = toOpenUi(safeExisting);
            effectiveSource = OpenUiLang.mergeStatements(baseOpenUi, cleanedInput);
        } else {
            effectiveSource = cleanedInput;
        }

        OpenUiLang.Program program = OpenUiLang.parse(effectiveSource, false);
        if (program.statements.isEmpty() && explicitDeletions.isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_OPENUI",
                    "Could not parse any valid OpenUI Lang widget statements "
                            + "(expected lines like: root = Screen([header, card])).");
        }

        OpenUiLang.RuntimeState runtime = new OpenUiLang.RuntimeState(program, null);
        OpenUiLang.ComponentNode rootNode = runtime.evaluateRoot(ROOT_ENTRY_TYPES);
        if (rootNode == null && !explicitDeletions.isEmpty()) {
            JsonArray delOnly = new JsonArray();
            for (String delId : explicitDeletions) {
                JsonObject rm = new JsonObject();
                rm.addProperty("op", "remove_widget");
                rm.addProperty("id", delId);
                delOnly.add(rm);
            }
            return delOnly;
        }
        if (rootNode == null) {
            throw new AgentTools.ToolException("E_NO_ROOT",
                    "OpenUI screen layout needs a 'root = Screen([...])', "
                            + "'root = Stack([...])', or 'root = Form([...])' entry statement.");
        }

        Map<String, ViewBean> existingById = new LinkedHashMap<>();
        for (ViewBean b : safeExisting) {
            if (b != null && b.id != null) {
                existingById.put(b.id, b);
            }
        }

        JsonArray ops = new JsonArray();
        Set<String> allocatedIds = new HashSet<>(existingById.keySet());
        List<PlannedWidget> targetList = new ArrayList<>();
        Set<String> targetIds = new LinkedHashSet<>();
        CompileCtx ctx = new CompileCtx(safeScId, activeTheme, hasOpenUiTheme,
                allocatedIds, existingById, targetList, targetIds);

        if ("Screen".equalsIgnoreCase(rootNode.type)) {
            emitScreenRootOps(rootNode, safeExisting.isEmpty() && hasOpenUiTheme, ops);
            List<OpenUiLang.ComponentNode> topWidgets = rootNode.argChildren(0);
            for (OpenUiLang.ComponentNode child : topWidgets) {
                collectNode(child, "root", null, ctx);
            }
        } else {
            if (safeExisting.isEmpty() && hasOpenUiTheme) {
                JsonObject rootOp = new JsonObject();
                rootOp.addProperty("op", "set_root");
                rootOp.addProperty("background", "@color/openui_bg");
                ops.add(rootOp);
            }
            ResolvedType rt = resolveWidgetType(rootNode.type);
            String chosenId = rootNode.id;
            if (chosenId == null || chosenId.isEmpty() || "root".equalsIgnoreCase(chosenId)) {
                chosenId = findReusableMainContainerId(safeExisting, rt.sketchwareType, allocatedIds);
            }
            OpenUiLang.ComponentNode remapped = new OpenUiLang.ComponentNode(
                    chosenId, rootNode.type, rootNode.args,
                    rootNode.namedArgs, rootNode.argBindings);
            collectNode(remapped, "root", null, ctx);
        }

        // 1. Handle removed widgets
        Set<String> toRemove = new LinkedHashSet<>();
        for (String existingId : existingById.keySet()) {
            if (!targetIds.contains(existingId)) {
                toRemove.add(existingId);
            }
        }
        toRemove.addAll(explicitDeletions);
        for (String remId : toRemove) {
            ViewBean eb = existingById.get(remId);
            if (eb == null || hasRemovedAncestor(eb, existingById, toRemove)) {
                continue;
            }
            JsonObject rm = new JsonObject();
            rm.addProperty("op", "remove_widget");
            rm.addProperty("id", remId);
            ops.add(rm);
        }

        Set<String> recreatedIds = new HashSet<>();
        for (PlannedWidget pw : targetList) {
            ViewBean existing = existingById.get(pw.id);
            if (existing != null && !toRemove.contains(pw.id)) {
                Integer desiredTypeInt = resolveTypeCode(pw.sketchwareType);
                if (desiredTypeInt != null && desiredTypeInt != existing.type) {
                    JsonObject rm = new JsonObject();
                    rm.addProperty("op", "remove_widget");
                    rm.addProperty("id", pw.id);
                    ops.add(rm);
                    recreatedIds.add(pw.id);
                }
            }
        }

        // 2. Emit add_widget / move_widget + property set_* operations in top-down order.
        Map<String, List<String>> currentOrderByParent = buildChildOrderMap(safeExisting, toRemove);
        Map<String, String> prevSiblingByParent = new HashMap<>();

        for (PlannedWidget pw : targetList) {
            ViewBean existing = recreatedIds.contains(pw.id) ? null : existingById.get(pw.id);
            String prevSib = prevSiblingByParent.get(pw.parentId);

            if (existing == null) {
                JsonObject add = new JsonObject();
                add.addProperty("op", "add_widget");
                add.addProperty("type", pw.sketchwareType);
                add.addProperty("id", pw.id);
                add.addProperty("parent", pw.parentId);
                if (pw.token != null && !pw.token.isEmpty()) {
                    add.addProperty("token", pw.token);
                }
                if (pw.text != null && !pw.text.isEmpty()) {
                    add.addProperty("text", pw.text);
                }
                if (pw.background != null && !pw.background.isEmpty()
                        && !isDrawableRef(pw.background)) {
                    add.addProperty("background", pw.background);
                }
                if (pw.textColor != null && !pw.textColor.isEmpty()) {
                    add.addProperty("text_color", pw.textColor);
                }
                ops.add(add);

                List<String> curSibs = currentOrderByParent.computeIfAbsent(
                        pw.parentId, k -> new ArrayList<>());
                if (prevSib == null && !curSibs.isEmpty()) {
                    JsonObject mv = new JsonObject();
                    mv.addProperty("op", "move_widget");
                    mv.addProperty("id", pw.id);
                    mv.addProperty("before", curSibs.get(0));
                    ops.add(mv);
                    curSibs.add(0, pw.id);
                } else if (prevSib != null) {
                    int idx = curSibs.indexOf(prevSib);
                    if (idx >= 0 && idx < curSibs.size() - 1) {
                        JsonObject mv = new JsonObject();
                        mv.addProperty("op", "move_widget");
                        mv.addProperty("id", pw.id);
                        mv.addProperty("after", prevSib);
                        ops.add(mv);
                        curSibs.add(idx + 1, pw.id);
                    } else {
                        curSibs.add(pw.id);
                    }
                } else {
                    curSibs.add(pw.id);
                }

                emitWidgetPropertyOps(pw, null, ops);
            } else {
                String existingParent = (existing.parent == null || existing.parent.isEmpty())
                        ? "root" : existing.parent;
                List<String> curSibs = currentOrderByParent.computeIfAbsent(
                        pw.parentId, k -> new ArrayList<>());
                boolean parentChanged = !existingParent.equalsIgnoreCase(pw.parentId);
                boolean orderChanged = false;
                if (!parentChanged) {
                    int myIdx = curSibs.indexOf(pw.id);
                    int prevIdx = prevSib == null ? -1 : curSibs.indexOf(prevSib);
                    if (prevSib == null && myIdx > 0) {
                        orderChanged = true;
                    } else if (prevSib != null && myIdx != prevIdx + 1) {
                        orderChanged = true;
                    }
                }
                if (parentChanged || orderChanged) {
                    if (parentChanged) {
                        List<String> oldSibs = currentOrderByParent.get(existingParent);
                        if (oldSibs != null) {
                            oldSibs.remove(pw.id);
                        }
                    } else {
                        curSibs.remove(pw.id);
                    }
                    JsonObject mv = new JsonObject();
                    mv.addProperty("op", "move_widget");
                    mv.addProperty("id", pw.id);
                    mv.addProperty("parent", pw.parentId);
                    if (prevSib == null && !curSibs.isEmpty()) {
                        mv.addProperty("before", curSibs.get(0));
                        curSibs.add(0, pw.id);
                    } else if (prevSib != null && curSibs.contains(prevSib)) {
                        mv.addProperty("after", prevSib);
                        curSibs.add(curSibs.indexOf(prevSib) + 1, pw.id);
                    } else {
                        curSibs.add(pw.id);
                    }
                    ops.add(mv);
                }
                emitWidgetPropertyOps(pw, existing, ops);
            }

            prevSiblingByParent.put(pw.parentId, pw.id);
        }

        if (ops.size() == 0) {
            throw new AgentTools.ToolException("E_NO_CHANGES",
                    "The OpenUI screen statements match the current screen — "
                            + "nothing changed.");
        }
        return ops;
    }

    private static final class CompileCtx {
        final String scId;
        final OpenUiThemeEngine.ResolvedTheme theme;
        final boolean hasTheme;
        final Set<String> allocatedIds;
        final Map<String, ViewBean> existingById;
        final List<PlannedWidget> out;
        final Set<String> targetIds;
        boolean insideScrollView;

        CompileCtx(String scId,
                   OpenUiThemeEngine.ResolvedTheme theme,
                   boolean hasTheme,
                   Set<String> allocatedIds,
                   Map<String, ViewBean> existingById,
                   List<PlannedWidget> out,
                   Set<String> targetIds) {
            this.scId = scId;
            this.theme = theme;
            this.hasTheme = hasTheme;
            this.allocatedIds = allocatedIds;
            this.existingById = existingById;
            this.out = out;
            this.targetIds = targetIds;
        }
    }

    private static String stripOptionalFence(String raw) {
        String trimmed = raw.trim();
        if (OpenUiLang.containsOpenUi(trimmed)) {
            List<OpenUiLang.Segment> segs = OpenUiLang.splitSegments(trimmed);
            StringBuilder sb = new StringBuilder();
            for (OpenUiLang.Segment s : segs) {
                if (s.kind == OpenUiLang.Segment.Kind.OPENUI) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(s.content);
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) {
                trimmed = trimmed.substring(firstNl + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private static String findReusableMainContainerId(List<ViewBean> existing,
                                                      String desiredType,
                                                      Set<String> allocatedIds) {
        for (ViewBean b : existing) {
            if (b == null || b.id == null) {
                continue;
            }
            boolean top = b.parent == null || b.parent.isEmpty()
                    || "root".equalsIgnoreCase(b.parent);
            if (top && AgentEdits.typeName(b.type).equalsIgnoreCase(desiredType)) {
                return b.id;
            }
        }
        String candidate = "main_layout";
        int idx = 1;
        while (allocatedIds.contains(candidate)) {
            candidate = "main_layout_" + (idx++);
        }
        allocatedIds.add(candidate);
        return candidate;
    }

    private static boolean hasRemovedAncestor(ViewBean bean,
                                              Map<String, ViewBean> byId,
                                              Set<String> removed) {
        String p = bean.parent;
        int guard = 0;
        while (p != null && !p.isEmpty() && !"root".equalsIgnoreCase(p) && guard++ < 64) {
            if (removed.contains(p)) {
                return true;
            }
            ViewBean pb = byId.get(p);
            p = pb == null ? null : pb.parent;
        }
        return false;
    }

    private static Map<String, List<String>> buildChildOrderMap(List<ViewBean> existing,
                                                                Set<String> removed) {
        Map<String, List<String>> map = new HashMap<>();
        for (ViewBean b : existing) {
            if (b == null || b.id == null || removed.contains(b.id)) {
                continue;
            }
            String p = (b.parent == null || b.parent.isEmpty()) ? "root" : b.parent;
            if (removed.contains(p)) {
                continue;
            }
            map.computeIfAbsent(p, k -> new ArrayList<>()).add(b.id);
        }
        return map;
    }

    // ---------------------------------------------------------------------
    // High-Level OpenUI Component + Role-Based TextView Expander
    // ---------------------------------------------------------------------

    private static final class ResolvedType {
        final String sketchwareType;
        final String impliedOrientation; // "vertical", "horizontal", or null

        ResolvedType(String sketchwareType, String impliedOrientation) {
            this.sketchwareType = sketchwareType;
            this.impliedOrientation = impliedOrientation;
        }
    }

    private static ResolvedType resolveWidgetType(String rawType) {
        if (rawType == null || rawType.isEmpty()) {
            return new ResolvedType("LinearLayout", "vertical");
        }
        switch (rawType) {
            case "Column":
            case "VStack":
            case "LinearV":
            case "Stack":
            case "Buttons":
            case "ListBlock":
            case "Stats":
                return new ResolvedType("LinearLayout", "vertical");
            case "Row":
            case "HStack":
            case "LinearH":
                return new ResolvedType("LinearLayout", "horizontal");
            case "Card":
            case "MaterialCardView":
                return new ResolvedType("CardView", null);
            case "Scroll":
            case "NestedScrollView":
            case "Form":
                return new ResolvedType("ScrollView", null);
            case "HScroll":
                return new ResolvedType("HorizontalScrollView", null);
            case "SwipeRefresh":
                return new ResolvedType("SwipeRefreshLayout", null);
            case "Text":
            case "BoldText":
            case "TextContent":
            case "Heading":
            case "Title":
            case "Subtitle":
            case "Body":
            case "Caption":
            case "Label":
            case "Tag":
            case "Badge":
                return new ResolvedType("TextView", null);
            case "Input":
            case "TextInput":
            case "TextArea":
                return new ResolvedType("EditText", null);
            case "Select":
                return new ResolvedType("Spinner", null);
            case "DatePicker":
            case "Chip":
                return new ResolvedType("Button", null);
            case "CheckBoxItem":
                return new ResolvedType("CheckBox", null);
            case "PrimaryButton":
                return new ResolvedType("MaterialButton", null);
            case "RecyclerList":
                return new ResolvedType("RecyclerView", null);
            case "BottomNav":
                return new ResolvedType("BottomNavigationView", null);
            default:
                return new ResolvedType(rawType, null);
        }
    }

    private static Integer resolveTypeCode(String typeName) {
        switch (typeName) {
            case "LinearLayout":
                return ViewBean.VIEW_TYPE_LAYOUT_LINEAR;
            case "RelativeLayout":
                return ViewBean.VIEW_TYPE_LAYOUT_RELATIVE;
            case "HorizontalScrollView":
                return ViewBean.VIEW_TYPE_LAYOUT_HSCROLLVIEW;
            case "ScrollView":
                return ViewBean.VIEW_TYPE_LAYOUT_VSCROLLVIEW;
            case "Button":
                return ViewBean.VIEW_TYPE_WIDGET_BUTTON;
            case "TextView":
                return ViewBean.VIEW_TYPE_WIDGET_TEXTVIEW;
            case "EditText":
                return ViewBean.VIEW_TYPE_WIDGET_EDITTEXT;
            case "ImageView":
                return ViewBean.VIEW_TYPE_WIDGET_IMAGEVIEW;
            case "WebView":
                return ViewBean.VIEW_TYPE_WIDGET_WEBVIEW;
            case "ProgressBar":
                return ViewBean.VIEW_TYPE_WIDGET_PROGRESSBAR;
            case "ListView":
                return ViewBean.VIEW_TYPE_WIDGET_LISTVIEW;
            case "Spinner":
                return ViewBean.VIEW_TYPE_WIDGET_SPINNER;
            case "CheckBox":
                return ViewBean.VIEW_TYPE_WIDGET_CHECKBOX;
            case "Switch":
                return ViewBean.VIEW_TYPE_WIDGET_SWITCH;
            case "SeekBar":
                return ViewBean.VIEW_TYPE_WIDGET_SEEKBAR;
            case "CalendarView":
                return ViewBean.VIEW_TYPE_WIDGET_CALENDARVIEW;
            case "AdView":
                return ViewBean.VIEW_TYPE_WIDGET_ADVIEW;
            case "MapView":
                return ViewBean.VIEW_TYPE_WIDGET_MAPVIEW;
            default: {
                int ext = ViewBeans.getViewTypeByTypeName(typeName);
                if (ViewBeans.getViewTypeName(ext).equalsIgnoreCase(typeName)) {
                    return ext;
                }
                return null;
            }
        }
    }

    private static final class PlannedWidget {
        String id;
        String parentId;
        String sketchwareType;
        String token;
        String orientation;
        String width;
        String height;
        Integer weight;
        Object padding;
        Object margin;
        String gravity;
        String layoutGravity;
        String text;
        String hint;
        String hintColor;
        String background;
        String backgroundImage;
        String textColor;
        Integer textSize;
        String textStyle;
        String font;
        String inputType;
        String image;
        String scaleType;
        String customView;
        Boolean checked;
        Boolean enabled;
        Boolean clickable;
        String visibility;
        Integer progress;
        Integer max;
        Boolean indeterminate;
        Double alpha;
        Map<String, String> attributes;

        void putAttr(String k, String v) {
            if (attributes == null) {
                attributes = new LinkedHashMap<>();
            }
            attributes.put(k, v);
        }
    }

    private static void emitScreenRootOps(OpenUiLang.ComponentNode screenNode,
                                          boolean applyDefaultThemeBg,
                                          JsonArray ops) {
        Map<String, Object> props = extractPropsMap(screenNode, 1);
        JsonObject rootOp = new JsonObject();
        rootOp.addProperty("op", "set_root");
        boolean hasRootChange = false;
        String bg = propStr(props, "bg", "background");
        if (bg != null) {
            rootOp.addProperty("background", bg);
            hasRootChange = true;
        } else if (applyDefaultThemeBg) {
            rootOp.addProperty("background", "@color/openui_bg");
            hasRootChange = true;
        }
        String orient = propStr(props, "orient", "orientation");
        if (orient != null) {
            rootOp.addProperty("orientation", normalizeOrientation(orient));
            hasRootChange = true;
        }
        Object rawAttrs = props.containsKey("attrs") ? props.get("attrs") : props.get("attributes");
        if (rawAttrs instanceof Map<?, ?> && !((Map<?, ?>) rawAttrs).isEmpty()) {
            JsonObject ao = new JsonObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rawAttrs).entrySet()) {
                ao.addProperty(String.valueOf(e.getKey()), OpenUiLang.stringify(e.getValue()));
            }
            rootOp.add("attributes", ao);
            hasRootChange = true;
        }
        if (hasRootChange) {
            ops.add(rootOp);
        }

        String fabIcon = propStr(props, "fab_icon", "fabIcon");
        String fabBg = propStr(props, "fab_bg", "fabBackground");
        if (fabIcon != null || fabBg != null) {
            JsonObject fabOp = new JsonObject();
            fabOp.addProperty("op", "set_fab");
            if (fabIcon != null) {
                fabOp.addProperty("icon", fabIcon);
            }
            if (fabBg != null) {
                fabOp.addProperty("background", fabBg);
            }
            ops.add(fabOp);
        }
    }

    private static void collectNode(OpenUiLang.ComponentNode node,
                                    String parentId,
                                    int[] inheritedGapMargin,
                                    CompileCtx ctx)
            throws AgentTools.ToolException {
        if (node == null) {
            return;
        }
        String type = node.type == null ? "Column" : node.type.trim();
        switch (type) {
            case "Form":
                expandForm(node, parentId, inheritedGapMargin, ctx);
                return;
            case "FormControl":
                expandFormControl(node, parentId, inheritedGapMargin, ctx);
                return;
            case "InlineHeader":
            case "CardHeader":
                expandHeaderPair(node, parentId, inheritedGapMargin, ctx);
                return;
            case "TextCallout":
            case "Callout":
                expandCallout(node, parentId, inheritedGapMargin, ctx);
                return;
            case "Text":
            case "BoldText":
                if (node.args != null && node.args.size() >= 2
                        && !(node.arg(1) instanceof Map<?, ?>)
                        && node.arg(1) != null
                        && !OpenUiLang.stringify(node.arg(1)).trim().isEmpty()) {
                    expandTextWithSubtext(node, "BoldText".equals(type),
                            parentId, inheritedGapMargin, ctx);
                    return;
                }
                break;
            case "Chips":
            case "SegmentedControl":
                expandChipsGroup(node, parentId, inheritedGapMargin, ctx);
                return;
            case "ColorSwatches":
                expandColorSwatches(node, parentId, inheritedGapMargin, ctx);
                return;
            case "SwitchItem":
                expandSwitchItem(node, parentId, inheritedGapMargin, ctx);
                return;
            case "Slider":
                expandSlider(node, parentId, inheritedGapMargin, ctx);
                return;
            case "SearchBar":
                expandSearchBar(node, parentId, inheritedGapMargin, ctx);
                return;
            case "EmptyState":
                expandEmptyState(node, parentId, inheritedGapMargin, ctx);
                return;
            case "ListItem":
                expandListItem(node, parentId, inheritedGapMargin, ctx);
                return;
            case "Stat":
            case "KpiCard":
                expandStatCard(node, parentId, inheritedGapMargin, ctx);
                return;
            case "Separator":
            case "Divider":
                expandSeparator(node, parentId, inheritedGapMargin, ctx);
                return;
            case "CodeBlock":
                expandCodeBlock(node, parentId, inheritedGapMargin, ctx);
                return;
            default:
                break;
        }
        collectStandardOrRoleWidget(node, parentId, inheritedGapMargin, ctx);
    }

    private static void expandForm(OpenUiLang.ComponentNode node,
                                   String parentId,
                                   int[] inheritedGapMargin,
                                   CompileCtx ctx)
            throws AgentTools.ToolException {
        Map<String, Object> props = extractPropsMap(node, 2);
        if (props.isEmpty() && node.arg(1) instanceof Map<?, ?>) {
            props = extractPropsMap(node, 1);
        }
        String baseId = sanitizeWidgetId(node.id, "form", ctx.allocatedIds);
        boolean scrollable = propBool(props, "scrollable") == null
                || Boolean.TRUE.equals(propBool(props, "scrollable"));
        String columnParent = parentId;
        String colId = baseId;

        if (scrollable && !ctx.insideScrollView) {
            String scrollId = reserveDerivedId(baseId + "_scroll", ctx.allocatedIds);
            registerTargetId(scrollId, ctx);
            PlannedWidget scroll = new PlannedWidget();
            scroll.id = scrollId;
            scroll.parentId = parentId;
            scroll.sketchwareType = "ScrollView";
            scroll.width = "match_parent";
            scroll.height = "match_parent";
            scroll.weight = "root".equalsIgnoreCase(parentId) ? 1 : null;
            scroll.putAttr("android:fillViewport", "true");
            if (inheritedGapMargin != null) {
                scroll.margin = inheritedGapMargin;
            }
            ctx.out.add(scroll);
            columnParent = scrollId;
        }

        registerTargetId(colId, ctx);
        PlannedWidget col = new PlannedWidget();
        col.id = colId;
        col.parentId = columnParent;
        col.sketchwareType = "LinearLayout";
        col.orientation = "vertical";
        col.width = "match_parent";
        col.height = "wrap_content";
        int pad = OpenUiThemeEngine.densityScreenPadding(ctx.theme.density);
        col.padding = props.containsKey("p") ? props.get("p")
                : (props.containsKey("padding") ? props.get("padding") : pad);
        populateFromPropsMap(col, props);
        ctx.out.add(col);

        boolean prevScroll = ctx.insideScrollView;
        ctx.insideScrollView = prevScroll || scrollable;
        int gapDp = parseGapDp(props.get("gap"), OpenUiThemeEngine.densityGap(ctx.theme.density));
        List<OpenUiLang.ComponentNode> fields = node.argChildren(0);
        List<OpenUiLang.ComponentNode> allKids = new ArrayList<>(fields);
        Object secondArg = node.arg(1);
        if (secondArg instanceof OpenUiLang.ComponentNode) {
            allKids.add((OpenUiLang.ComponentNode) secondArg);
        } else if (secondArg instanceof List<?>) {
            allKids.addAll(node.argChildren(1));
        }
        for (int i = 0; i < allKids.size(); i++) {
            int[] childGap = (i < allKids.size() - 1 && gapDp > 0)
                    ? new int[]{0, 0, 0, gapDp} : null;
            collectNode(allKids.get(i), colId, childGap, ctx);
        }
        ctx.insideScrollView = prevScroll;
    }

    private static void expandFormControl(OpenUiLang.ComponentNode node,
                                          String parentId,
                                          int[] inheritedGapMargin,
                                          CompileCtx ctx)
            throws AgentTools.ToolException {
        String baseId = sanitizeWidgetId(node.id, "field_group", ctx.allocatedIds);
        registerTargetId(baseId, ctx);
        Map<String, Object> props = extractPropsMap(node, 3);
        if (props.isEmpty() && node.arg(2) instanceof Map<?, ?>) {
            props = extractPropsMap(node, 2);
        }

        PlannedWidget group = new PlannedWidget();
        group.id = baseId;
        group.parentId = parentId;
        group.sketchwareType = "LinearLayout";
        group.orientation = "vertical";
        group.width = "match_parent";
        group.height = "wrap_content";
        group.margin = inheritedGapMargin != null ? inheritedGapMargin : new int[]{0, 0, 0, 12};
        populateFromPropsMap(group, props);
        ctx.out.add(group);

        String labelText = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
        if (!labelText.isEmpty()) {
            String lblId = reserveDerivedId(baseId + "_label", ctx.allocatedIds);
            registerTargetId(lblId, ctx);
            PlannedWidget lbl = new PlannedWidget();
            lbl.id = lblId;
            lbl.parentId = baseId;
            lbl.sketchwareType = "TextView";
            lbl.text = labelText;
            lbl.textSize = 13;
            lbl.textStyle = "bold";
            lbl.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
            lbl.margin = new int[]{0, 0, 0, 6};
            ctx.out.add(lbl);
        }

        Object inputObj = node.arg(1);
        if (inputObj instanceof OpenUiLang.ComponentNode) {
            collectNode((OpenUiLang.ComponentNode) inputObj, baseId, null, ctx);
        } else if (inputObj instanceof List<?>) {
            for (OpenUiLang.ComponentNode child : node.argChildren(1)) {
                collectNode(child, baseId, null, ctx);
            }
        }

        Object helperObj = node.arg(2);
        if (helperObj != null && !(helperObj instanceof Map<?, ?>)) {
            String helperText = OpenUiLang.stringify(helperObj).trim();
            if (!helperText.isEmpty()) {
                String hlpId = reserveDerivedId(baseId + "_hint", ctx.allocatedIds);
                registerTargetId(hlpId, ctx);
                PlannedWidget hlp = new PlannedWidget();
                hlp.id = hlpId;
                hlp.parentId = baseId;
                hlp.sketchwareType = "TextView";
                hlp.text = helperText;
                hlp.textSize = 12;
                hlp.textColor = ctx.hasTheme ? "@color/openui_text_tertiary" : "#737373";
                hlp.margin = new int[]{0, 4, 0, 0};
                ctx.out.add(hlp);
            }
        }
    }

    private static void expandHeaderPair(OpenUiLang.ComponentNode node,
                                         String parentId,
                                         int[] inheritedGapMargin,
                                         CompileCtx ctx)
            throws AgentTools.ToolException {
        String heading = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
        String sub = (node.arg(1) != null && !(node.arg(1) instanceof Map<?, ?>))
                ? OpenUiLang.stringify(node.arg(1)).trim() : "";
        Map<String, Object> props = extractPropsMap(
                node, (node.arg(1) instanceof Map<?, ?>) ? 1 : 2);
        if (sub.isEmpty()) {
            String id = sanitizeWidgetId(node.id, "tv_header", ctx.allocatedIds);
            registerTargetId(id, ctx);
            PlannedWidget tv = new PlannedWidget();
            tv.id = id;
            tv.parentId = parentId;
            tv.sketchwareType = "TextView";
            tv.text = heading;
            tv.textSize = "CardHeader".equals(node.type) ? 17 : 20;
            tv.textStyle = "bold";
            tv.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
            if (!ctx.theme.fontHeading.isEmpty()) {
                tv.font = ctx.theme.fontHeading;
            }
            if (inheritedGapMargin != null) {
                tv.margin = inheritedGapMargin;
            }
            populateFromPropsMap(tv, props);
            ctx.out.add(tv);
            return;
        }
        String boxId = sanitizeWidgetId(node.id, "hdr_box", ctx.allocatedIds);
        registerTargetId(boxId, ctx);
        PlannedWidget box = new PlannedWidget();
        box.id = boxId;
        box.parentId = parentId;
        box.sketchwareType = "LinearLayout";
        box.orientation = "vertical";
        box.width = "match_parent";
        box.height = "wrap_content";
        if (inheritedGapMargin != null) {
            box.margin = inheritedGapMargin;
        }
        populateFromPropsMap(box, props);
        ctx.out.add(box);

        String tId = reserveDerivedId(boxId + "_title", ctx.allocatedIds);
        registerTargetId(tId, ctx);
        PlannedWidget t = new PlannedWidget();
        t.id = tId;
        t.parentId = boxId;
        t.sketchwareType = "TextView";
        t.text = heading;
        t.textSize = "CardHeader".equals(node.type) ? 17 : 20;
        t.textStyle = "bold";
        t.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
        if (!ctx.theme.fontHeading.isEmpty()) {
            t.font = ctx.theme.fontHeading;
        }
        ctx.out.add(t);

        String sId = reserveDerivedId(boxId + "_subtitle", ctx.allocatedIds);
        registerTargetId(sId, ctx);
        PlannedWidget s = new PlannedWidget();
        s.id = sId;
        s.parentId = boxId;
        s.sketchwareType = "TextView";
        s.text = sub;
        s.textSize = 13;
        s.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
        s.margin = new int[]{0, 2, 0, 0};
        ctx.out.add(s);
    }

    private static void expandTextWithSubtext(OpenUiLang.ComponentNode node,
                                              boolean boldMain,
                                              String parentId,
                                              int[] inheritedGapMargin,
                                              CompileCtx ctx)
            throws AgentTools.ToolException {
        String mainText = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
        String subText = OpenUiLang.stringify(node.arg(1)).trim();
        Map<String, Object> props = extractPropsMap(node, 2);

        String boxId = sanitizeWidgetId(node.id, "txt_pair", ctx.allocatedIds);
        registerTargetId(boxId, ctx);
        PlannedWidget box = new PlannedWidget();
        box.id = boxId;
        box.parentId = parentId;
        box.sketchwareType = "LinearLayout";
        box.orientation = "vertical";
        box.width = "wrap_content";
        box.height = "wrap_content";
        if (inheritedGapMargin != null) {
            box.margin = inheritedGapMargin;
        }
        populateFromPropsMap(box, props);
        ctx.out.add(box);

        String variant = propStr(props, "variant");
        String sizeKey = propStr(props, "size");
        int mainSp = "number".equalsIgnoreCase(variant) ? 24 : mapShortTextSize(sizeKey, 15);

        String mId = reserveDerivedId(boxId + "_main", ctx.allocatedIds);
        registerTargetId(mId, ctx);
        PlannedWidget m = new PlannedWidget();
        m.id = mId;
        m.parentId = boxId;
        m.sketchwareType = "TextView";
        m.text = mainText;
        m.textSize = mainSp;
        m.textStyle = (boldMain || "number".equalsIgnoreCase(variant)) ? "bold" : "normal";
        m.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
        ctx.out.add(m);

        String sId = reserveDerivedId(boxId + "_sub", ctx.allocatedIds);
        registerTargetId(sId, ctx);
        PlannedWidget s = new PlannedWidget();
        s.id = sId;
        s.parentId = boxId;
        s.sketchwareType = "TextView";
        s.text = subText;
        s.textSize = 12;
        String subVariant = propStr(props, "subtextVariant", "subtext_variant");
        if ("metric".equalsIgnoreCase(subVariant) && subText.startsWith("+")) {
            s.textColor = ctx.hasTheme ? "@color/openui_success_text" : "#15803D";
            s.textStyle = "bold";
        } else if ("metric".equalsIgnoreCase(subVariant) && subText.startsWith("-")) {
            s.textColor = ctx.hasTheme ? "@color/openui_danger_text" : "#B91C1C";
            s.textStyle = "bold";
        } else {
            s.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
        }
        s.margin = new int[]{0, 2, 0, 0};
        ctx.out.add(s);
    }

    private static void expandCallout(OpenUiLang.ComponentNode node,
                                      String parentId,
                                      int[] inheritedGapMargin,
                                      CompileCtx ctx)
            throws AgentTools.ToolException {
        boolean isCalloutFirstVariant = "Callout".equals(node.type)
                && node.args != null && node.args.size() >= 2
                && isStatusVariantWord(OpenUiLang.stringify(node.arg(0)));
        String variant;
        String title;
        String desc = "";
        Map<String, Object> props;
        if (isCalloutFirstVariant) {
            variant = normalizeStatusVariant(OpenUiLang.stringify(node.arg(0)));
            title = OpenUiLang.stringify(node.arg(1));
            if (node.arg(2) != null && !(node.arg(2) instanceof Map<?, ?>)) {
                desc = OpenUiLang.stringify(node.arg(2));
                props = extractPropsMap(node, 3);
            } else {
                props = extractPropsMap(node, 2);
            }
        } else {
            title = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
            if (node.arg(1) != null && !(node.arg(1) instanceof Map<?, ?>)) {
                desc = OpenUiLang.stringify(node.arg(1));
                props = extractPropsMap(node, 2);
            } else {
                props = extractPropsMap(node, 1);
            }
            variant = normalizeStatusVariant(propStr(props, "variant"));
        }

        String boxId = sanitizeWidgetId(node.id, "callout", ctx.allocatedIds);
        registerTargetId(boxId, ctx);
        PlannedWidget box = new PlannedWidget();
        box.id = boxId;
        box.parentId = parentId;
        box.sketchwareType = "LinearLayout";
        box.orientation = "vertical";
        box.width = "match_parent";
        box.height = "wrap_content";
        box.padding = 12;
        if (ctx.hasTheme) {
            box.backgroundImage = "openui_callout_" + variant;
        } else {
            box.background = "#EFF6FF";
        }
        if (inheritedGapMargin != null) {
            box.margin = inheritedGapMargin;
        }
        populateFromPropsMap(box, props);
        ctx.out.add(box);

        String textColor = calloutTextColorRef(variant, ctx.hasTheme);
        if (!title.isEmpty()) {
            String tId = reserveDerivedId(boxId + "_title", ctx.allocatedIds);
            registerTargetId(tId, ctx);
            PlannedWidget t = new PlannedWidget();
            t.id = tId;
            t.parentId = boxId;
            t.sketchwareType = "TextView";
            t.text = title;
            t.textSize = 14;
            t.textStyle = "bold";
            t.textColor = textColor;
            ctx.out.add(t);
        }
        if (!desc.isEmpty()) {
            String dId = reserveDerivedId(boxId + "_desc", ctx.allocatedIds);
            registerTargetId(dId, ctx);
            PlannedWidget d = new PlannedWidget();
            d.id = dId;
            d.parentId = boxId;
            d.sketchwareType = "TextView";
            d.text = desc;
            d.textSize = 13;
            d.textColor = textColor;
            if (!title.isEmpty()) {
                d.margin = new int[]{0, 4, 0, 0};
            }
            ctx.out.add(d);
        }
    }

    private static void expandChipsGroup(OpenUiLang.ComponentNode node,
                                         String parentId,
                                         int[] inheritedGapMargin,
                                         CompileCtx ctx)
            throws AgentTools.ToolException {
        Map<String, Object> props = extractPropsMap(node, 1);
        String baseId = sanitizeWidgetId(node.id, "chips", ctx.allocatedIds);
        boolean wrapInHScroll = !"SegmentedControl".equals(node.type)
                && !Boolean.FALSE.equals(propBool(props, "scrollable"));
        String rowParent = parentId;
        String rowId = baseId;

        if (wrapInHScroll) {
            String hsId = reserveDerivedId(baseId + "_scroll", ctx.allocatedIds);
            registerTargetId(hsId, ctx);
            PlannedWidget hs = new PlannedWidget();
            hs.id = hsId;
            hs.parentId = parentId;
            hs.sketchwareType = "HorizontalScrollView";
            hs.width = "match_parent";
            hs.height = "wrap_content";
            if (inheritedGapMargin != null) {
                hs.margin = inheritedGapMargin;
            }
            ctx.out.add(hs);
            rowParent = hsId;
        }

        registerTargetId(rowId, ctx);
        PlannedWidget row = new PlannedWidget();
        row.id = rowId;
        row.parentId = rowParent;
        row.sketchwareType = "LinearLayout";
        row.orientation = "horizontal";
        row.width = wrapInHScroll ? "wrap_content" : "match_parent";
        row.height = "wrap_content";
        if (!wrapInHScroll && inheritedGapMargin != null) {
            row.margin = inheritedGapMargin;
        }
        populateFromPropsMap(row, props);
        ctx.out.add(row);

        Integer selectedIndex = propInt(props, "selectedIndex", "selected_index", "selected");
        List<?> rawItems = (node.arg(0) instanceof List<?>)
                ? (List<?>) node.arg(0) : Collections.emptyList();
        List<?> explicitIds = (props.get("ids") instanceof List<?>)
                ? (List<?>) props.get("ids") : Collections.emptyList();

        for (int i = 0; i < rawItems.size(); i++) {
            Object item = rawItems.get(i);
            if (item instanceof OpenUiLang.ComponentNode) {
                OpenUiLang.ComponentNode cn = (OpenUiLang.ComponentNode) item;
                collectNode(cn, rowId, i < rawItems.size() - 1 ? new int[]{0, 0, 8, 0} : null, ctx);
                continue;
            }
            String label = OpenUiLang.stringify(item);
            String desiredChipId = i < explicitIds.size()
                    ? OpenUiLang.stringify(explicitIds.get(i))
                    : baseId + "_" + i;
            String chipId = sanitizeWidgetId(desiredChipId, "chip", ctx.allocatedIds);
            registerTargetId(chipId, ctx);
            PlannedWidget chip = new PlannedWidget();
            chip.id = chipId;
            chip.parentId = rowId;
            chip.sketchwareType = "Button";
            chip.token = "chip";
            chip.text = label;
            chip.width = "SegmentedControl".equals(node.type) ? "0" : "wrap_content";
            chip.weight = "SegmentedControl".equals(node.type) ? 1 : null;
            chip.height = "40";
            chip.padding = new int[]{16, 0, 16, 0};
            if (i < rawItems.size() - 1) {
                chip.margin = new int[]{0, 0, 8, 0};
            }
            if (ctx.hasTheme) {
                chip.backgroundImage = "openui_chip_selector";
                chip.textColor = "@color/openui_chip_text";
            }
            if (selectedIndex != null && selectedIndex == i) {
                chip.putAttr("android:selected", "true");
            }
            ctx.out.add(chip);
        }
    }

    private static void expandColorSwatches(OpenUiLang.ComponentNode node,
                                            String parentId,
                                            int[] inheritedGapMargin,
                                            CompileCtx ctx)
            throws AgentTools.ToolException {
        Map<String, Object> props = extractPropsMap(node, 1);
        String rowId = sanitizeWidgetId(node.id, "color_swatches", ctx.allocatedIds);
        registerTargetId(rowId, ctx);

        PlannedWidget row = new PlannedWidget();
        row.id = rowId;
        row.parentId = parentId;
        row.sketchwareType = "LinearLayout";
        row.orientation = "horizontal";
        row.width = "match_parent";
        row.height = "wrap_content";
        row.gravity = "center_vertical";
        if (inheritedGapMargin != null) {
            row.margin = inheritedGapMargin;
        }
        populateFromPropsMap(row, props);
        ctx.out.add(row);

        Integer selectedIndex = propInt(props, "selectedIndex", "selected_index", "selected");
        List<?> rawColors = (node.arg(0) instanceof List<?>)
                ? (List<?>) node.arg(0) : Collections.emptyList();
        List<?> explicitIds = (props.get("ids") instanceof List<?>)
                ? (List<?>) props.get("ids") : Collections.emptyList();

        for (int i = 0; i < rawColors.size(); i++) {
            String hex = OpenUiThemeEngine.normalizeHex(
                    OpenUiLang.stringify(rawColors.get(i)), "#3B82F6");
            String hex6 = hex.substring(1).toLowerCase(Locale.ROOT);
            String drawName = "openui_swatch_" + hex6;
            String selDrawName = "openui_swatch_sel_" + hex6;
            if (!ctx.scId.isEmpty()) {
                ensureSwatchDrawables(ctx.scId, drawName, selDrawName, hex);
            }

            String desiredId = i < explicitIds.size()
                    ? OpenUiLang.stringify(explicitIds.get(i))
                    : rowId + "_" + i;
            String swId = sanitizeWidgetId(desiredId, "swatch", ctx.allocatedIds);
            registerTargetId(swId, ctx);
            PlannedWidget sw = new PlannedWidget();
            sw.id = swId;
            sw.parentId = rowId;
            sw.sketchwareType = "Button";
            sw.text = (selectedIndex != null && selectedIndex == i) ? "✓" : "";
            sw.textColor = "#FFFFFF";
            sw.textStyle = "bold";
            sw.width = "40";
            sw.height = "40";
            sw.padding = 0;
            if (i < rawColors.size() - 1) {
                sw.margin = new int[]{0, 0, 10, 0};
            }
            if (!ctx.scId.isEmpty()) {
                sw.backgroundImage = (selectedIndex != null && selectedIndex == i)
                        ? selDrawName : drawName;
            } else {
                sw.background = hex;
            }
            ctx.out.add(sw);
        }
    }

    private static void ensureSwatchDrawables(String scId,
                                              String normalName,
                                              String selectedName,
                                              String hex) {
        try {
            String normalXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                    + "android:shape=\"oval\">\n"
                    + "    <solid android:color=\"" + hex + "\"/>\n"
                    + "</shape>\n";
            String selectedXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                    + "android:shape=\"oval\">\n"
                    + "    <solid android:color=\"" + hex + "\"/>\n"
                    + "    <stroke android:width=\"3dp\" "
                    + "android:color=\"@color/openui_text_primary\"/>\n"
                    + "</shape>\n";
            AgentAssets.upsertCustomDrawables(scId, List.of(
                    AgentAssets.validateDrawableXml(normalName, normalXml),
                    AgentAssets.validateDrawableXml(selectedName, selectedXml)));
        } catch (Throwable ignored) {
        }
    }

    private static void expandSwitchItem(OpenUiLang.ComponentNode node,
                                         String parentId,
                                         int[] inheritedGapMargin,
                                         CompileCtx ctx)
            throws AgentTools.ToolException {
        // Keep the user/model's exact node.id on the Switch itself so event wiring hits Switch!
        String switchId = sanitizeWidgetId(node.id, "sw_toggle", ctx.allocatedIds);
        String rowId = reserveDerivedId(switchId + "_row", ctx.allocatedIds);
        registerTargetId(rowId, ctx);

        String label = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
        String desc = (node.arg(1) != null && !(node.arg(1) instanceof Map<?, ?>))
                ? OpenUiLang.stringify(node.arg(1)).trim() : "";
        Map<String, Object> props = extractPropsMap(
                node, (node.arg(1) instanceof Map<?, ?>) ? 1 : 2);

        PlannedWidget row = new PlannedWidget();
        row.id = rowId;
        row.parentId = parentId;
        row.sketchwareType = "LinearLayout";
        row.orientation = "horizontal";
        row.width = "match_parent";
        row.height = "wrap_content";
        row.padding = new int[]{14, 12, 14, 12};
        row.gravity = "center_vertical";
        if (ctx.hasTheme) {
            row.backgroundImage = "openui_card";
        }
        if (inheritedGapMargin != null) {
            row.margin = inheritedGapMargin;
        }
        ctx.out.add(row);

        String colId = reserveDerivedId(switchId + "_labels", ctx.allocatedIds);
        registerTargetId(colId, ctx);
        PlannedWidget col = new PlannedWidget();
        col.id = colId;
        col.parentId = rowId;
        col.sketchwareType = "LinearLayout";
        col.orientation = "vertical";
        col.width = "0";
        col.height = "wrap_content";
        col.weight = 1;
        ctx.out.add(col);

        String tId = reserveDerivedId(switchId + "_title", ctx.allocatedIds);
        registerTargetId(tId, ctx);
        PlannedWidget t = new PlannedWidget();
        t.id = tId;
        t.parentId = colId;
        t.sketchwareType = "TextView";
        t.text = label;
        t.textSize = 15;
        t.textStyle = "bold";
        t.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
        ctx.out.add(t);

        if (!desc.isEmpty()) {
            String dId = reserveDerivedId(switchId + "_desc", ctx.allocatedIds);
            registerTargetId(dId, ctx);
            PlannedWidget d = new PlannedWidget();
            d.id = dId;
            d.parentId = colId;
            d.sketchwareType = "TextView";
            d.text = desc;
            d.textSize = 12;
            d.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
            d.margin = new int[]{0, 2, 0, 0};
            ctx.out.add(d);
        }

        registerTargetId(switchId, ctx);
        PlannedWidget sw = new PlannedWidget();
        sw.id = switchId;
        sw.parentId = rowId;
        sw.sketchwareType = "Switch";
        sw.text = "";
        sw.checked = propBool(props, "checked", "defaultChecked");
        ctx.out.add(sw);
    }

    private static void expandSlider(OpenUiLang.ComponentNode node,
                                     String parentId,
                                     int[] inheritedGapMargin,
                                     CompileCtx ctx)
            throws AgentTools.ToolException {
        String seekId = sanitizeWidgetId(node.id, "seek_bar", ctx.allocatedIds);
        String boxId = reserveDerivedId(seekId + "_group", ctx.allocatedIds);
        registerTargetId(boxId, ctx);

        String label = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
        Map<String, Object> props = extractPropsMap(node, 1);

        PlannedWidget box = new PlannedWidget();
        box.id = boxId;
        box.parentId = parentId;
        box.sketchwareType = "LinearLayout";
        box.orientation = "vertical";
        box.width = "match_parent";
        box.height = "wrap_content";
        if (inheritedGapMargin != null) {
            box.margin = inheritedGapMargin;
        }
        ctx.out.add(box);

        if (!label.isEmpty()) {
            String lblId = reserveDerivedId(seekId + "_label", ctx.allocatedIds);
            registerTargetId(lblId, ctx);
            PlannedWidget lbl = new PlannedWidget();
            lbl.id = lblId;
            lbl.parentId = boxId;
            lbl.sketchwareType = "TextView";
            lbl.text = label;
            lbl.textSize = 13;
            lbl.textStyle = "bold";
            lbl.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
            lbl.margin = new int[]{0, 0, 0, 4};
            ctx.out.add(lbl);
        }

        registerTargetId(seekId, ctx);
        PlannedWidget sb = new PlannedWidget();
        sb.id = seekId;
        sb.parentId = boxId;
        sb.sketchwareType = "SeekBar";
        sb.width = "match_parent";
        sb.height = "wrap_content";
        sb.max = propInt(props, "max");
        sb.progress = propInt(props, "progress", "defaultValue", "value");
        ctx.out.add(sb);
    }

    private static void expandSearchBar(OpenUiLang.ComponentNode node,
                                        String parentId,
                                        int[] inheritedGapMargin,
                                        CompileCtx ctx)
            throws AgentTools.ToolException {
        String etId = sanitizeWidgetId(node.id, "et_search", ctx.allocatedIds);
        String barId = reserveDerivedId(etId + "_bar", ctx.allocatedIds);
        registerTargetId(barId, ctx);

        String hint = node.arg(0) == null ? "Search..." : OpenUiLang.stringify(node.arg(0));
        Map<String, Object> props = extractPropsMap(node, 1);

        PlannedWidget bar = new PlannedWidget();
        bar.id = barId;
        bar.parentId = parentId;
        bar.sketchwareType = "LinearLayout";
        bar.orientation = "horizontal";
        bar.width = "match_parent";
        bar.height = "48";
        bar.padding = new int[]{14, 0, 14, 0};
        bar.gravity = "center_vertical";
        if (ctx.hasTheme) {
            bar.backgroundImage = "openui_sunk_input";
        }
        if (inheritedGapMargin != null) {
            bar.margin = inheritedGapMargin;
        }
        ctx.out.add(bar);

        registerTargetId(etId, ctx);
        PlannedWidget et = new PlannedWidget();
        et.id = etId;
        et.parentId = barId;
        et.sketchwareType = "EditText";
        et.width = "0";
        et.height = "match_parent";
        et.weight = 1;
        et.hint = hint;
        et.background = "#00000000";
        et.textSize = 15;
        et.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
        et.hintColor = ctx.hasTheme ? "@color/openui_text_tertiary" : "#737373";
        et.putAttr("android:singleLine", "true");
        populateFromPropsMap(et, props);
        ctx.out.add(et);
    }

    private static void expandEmptyState(OpenUiLang.ComponentNode node,
                                         String parentId,
                                         int[] inheritedGapMargin,
                                         CompileCtx ctx)
            throws AgentTools.ToolException {
        String boxId = sanitizeWidgetId(node.id, "empty_state", ctx.allocatedIds);
        registerTargetId(boxId, ctx);

        String title = node.arg(0) == null ? "Nothing here yet" : OpenUiLang.stringify(node.arg(0));
        String desc = (node.arg(1) != null && !(node.arg(1) instanceof Map<?, ?>))
                ? OpenUiLang.stringify(node.arg(1)).trim() : "";
        Map<String, Object> props = extractPropsMap(
                node, (node.arg(1) instanceof Map<?, ?>) ? 1 : 2);

        PlannedWidget box = new PlannedWidget();
        box.id = boxId;
        box.parentId = parentId;
        box.sketchwareType = "LinearLayout";
        box.orientation = "vertical";
        box.width = "match_parent";
        box.height = "wrap_content";
        box.padding = 28;
        box.gravity = "center";
        if (inheritedGapMargin != null) {
            box.margin = inheritedGapMargin;
        }
        populateFromPropsMap(box, props);
        ctx.out.add(box);

        String icon = propStr(props, "icon", "img", "image");
        if (icon != null && !icon.isEmpty()) {
            String icId = reserveDerivedId(boxId + "_icon", ctx.allocatedIds);
            registerTargetId(icId, ctx);
            PlannedWidget ic = new PlannedWidget();
            ic.id = icId;
            ic.parentId = boxId;
            ic.sketchwareType = "ImageView";
            ic.width = "48";
            ic.height = "48";
            ic.image = icon;
            if (ctx.hasTheme) {
                ic.putAttr("app:tint", "@color/openui_text_tertiary");
            }
            ic.margin = new int[]{0, 0, 0, 12};
            ctx.out.add(ic);
        }

        String tId = reserveDerivedId(boxId + "_title", ctx.allocatedIds);
        registerTargetId(tId, ctx);
        PlannedWidget t = new PlannedWidget();
        t.id = tId;
        t.parentId = boxId;
        t.sketchwareType = "TextView";
        t.text = title;
        t.textSize = 17;
        t.textStyle = "bold";
        t.gravity = "center";
        t.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
        ctx.out.add(t);

        if (!desc.isEmpty()) {
            String dId = reserveDerivedId(boxId + "_desc", ctx.allocatedIds);
            registerTargetId(dId, ctx);
            PlannedWidget d = new PlannedWidget();
            d.id = dId;
            d.parentId = boxId;
            d.sketchwareType = "TextView";
            d.text = desc;
            d.textSize = 14;
            d.gravity = "center";
            d.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
            d.margin = new int[]{0, 6, 0, 0};
            ctx.out.add(d);
        }

        Object actionObj = props.get("action");
        if (actionObj instanceof OpenUiLang.ComponentNode) {
            collectNode((OpenUiLang.ComponentNode) actionObj, boxId, new int[]{0, 16, 0, 0}, ctx);
        }
    }

    private static void expandListItem(OpenUiLang.ComponentNode node,
                                       String parentId,
                                       int[] inheritedGapMargin,
                                       CompileCtx ctx)
            throws AgentTools.ToolException {
        String rowId = sanitizeWidgetId(node.id, "list_item", ctx.allocatedIds);
        registerTargetId(rowId, ctx);
        String title = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
        String subtitle = (node.arg(1) != null && !(node.arg(1) instanceof Map<?, ?>))
                ? OpenUiLang.stringify(node.arg(1)).trim() : "";
        Map<String, Object> props = extractPropsMap(
                node, (node.arg(1) instanceof Map<?, ?>) ? 1 : 2);

        PlannedWidget row = new PlannedWidget();
        row.id = rowId;
        row.parentId = parentId;
        row.sketchwareType = "LinearLayout";
        row.orientation = "horizontal";
        row.width = "match_parent";
        row.height = "wrap_content";
        row.padding = 14;
        row.gravity = "center_vertical";
        row.clickable = true;
        if (ctx.hasTheme) {
            row.backgroundImage = "openui_card";
        }
        if (inheritedGapMargin != null) {
            row.margin = inheritedGapMargin;
        }
        populateFromPropsMap(row, props);
        ctx.out.add(row);

        String colId = reserveDerivedId(rowId + "_content", ctx.allocatedIds);
        registerTargetId(colId, ctx);
        PlannedWidget col = new PlannedWidget();
        col.id = colId;
        col.parentId = rowId;
        col.sketchwareType = "LinearLayout";
        col.orientation = "vertical";
        col.width = "0";
        col.height = "wrap_content";
        col.weight = 1;
        ctx.out.add(col);

        String tId = reserveDerivedId(rowId + "_title", ctx.allocatedIds);
        registerTargetId(tId, ctx);
        PlannedWidget t = new PlannedWidget();
        t.id = tId;
        t.parentId = colId;
        t.sketchwareType = "TextView";
        t.text = title;
        t.textSize = 15;
        t.textStyle = "bold";
        t.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
        ctx.out.add(t);

        if (!subtitle.isEmpty()) {
            String sId = reserveDerivedId(rowId + "_subtitle", ctx.allocatedIds);
            registerTargetId(sId, ctx);
            PlannedWidget s = new PlannedWidget();
            s.id = sId;
            s.parentId = colId;
            s.sketchwareType = "TextView";
            s.text = subtitle;
            s.textSize = 13;
            s.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
            s.margin = new int[]{0, 2, 0, 0};
            ctx.out.add(s);
        }

        String trailing = propStr(props, "trailing", "actionLabel", "meta");
        if (trailing != null && !trailing.isEmpty()) {
            String trId = reserveDerivedId(rowId + "_meta", ctx.allocatedIds);
            registerTargetId(trId, ctx);
            PlannedWidget tr = new PlannedWidget();
            tr.id = trId;
            tr.parentId = rowId;
            tr.sketchwareType = "TextView";
            tr.text = trailing;
            tr.textSize = 13;
            tr.textStyle = "bold";
            tr.textColor = ctx.hasTheme ? "@color/openui_accent" : "#3B82F6";
            ctx.out.add(tr);
        }
    }

    private static void expandStatCard(OpenUiLang.ComponentNode node,
                                       String parentId,
                                       int[] inheritedGapMargin,
                                       CompileCtx ctx)
            throws AgentTools.ToolException {
        String boxId = sanitizeWidgetId(node.id, "stat_card", ctx.allocatedIds);
        registerTargetId(boxId, ctx);
        String label = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
        String value = node.arg(1) == null ? "0" : OpenUiLang.stringify(node.arg(1));
        String delta = (node.arg(2) != null && !(node.arg(2) instanceof Map<?, ?>))
                ? OpenUiLang.stringify(node.arg(2)).trim() : "";
        Map<String, Object> props = extractPropsMap(
                node, (node.arg(2) instanceof Map<?, ?>) ? 2 : 3);

        PlannedWidget box = new PlannedWidget();
        box.id = boxId;
        box.parentId = parentId;
        box.sketchwareType = "LinearLayout";
        box.orientation = "vertical";
        box.width = "match_parent";
        box.height = "wrap_content";
        box.padding = OpenUiThemeEngine.densityCardPadding(ctx.theme.density);
        if (ctx.hasTheme) {
            box.backgroundImage = "openui_card";
        }
        if (inheritedGapMargin != null) {
            box.margin = inheritedGapMargin;
        }
        populateFromPropsMap(box, props);
        ctx.out.add(box);

        String lId = reserveDerivedId(boxId + "_label", ctx.allocatedIds);
        registerTargetId(lId, ctx);
        PlannedWidget l = new PlannedWidget();
        l.id = lId;
        l.parentId = boxId;
        l.sketchwareType = "TextView";
        l.text = label;
        l.textSize = 12;
        l.textStyle = "bold";
        l.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
        ctx.out.add(l);

        String vId = reserveDerivedId(boxId + "_value", ctx.allocatedIds);
        registerTargetId(vId, ctx);
        PlannedWidget v = new PlannedWidget();
        v.id = vId;
        v.parentId = boxId;
        v.sketchwareType = "TextView";
        v.text = value;
        v.textSize = 24;
        v.textStyle = "bold";
        v.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
        v.margin = new int[]{0, 4, 0, 0};
        ctx.out.add(v);

        if (!delta.isEmpty()) {
            String dId = reserveDerivedId(boxId + "_delta", ctx.allocatedIds);
            registerTargetId(dId, ctx);
            PlannedWidget d = new PlannedWidget();
            d.id = dId;
            d.parentId = boxId;
            d.sketchwareType = "TextView";
            d.text = delta;
            d.textSize = 12;
            d.textStyle = "bold";
            d.textColor = delta.startsWith("-")
                    ? (ctx.hasTheme ? "@color/openui_danger_text" : "#B91C1C")
                    : (ctx.hasTheme ? "@color/openui_success_text" : "#15803D");
            d.margin = new int[]{0, 2, 0, 0};
            ctx.out.add(d);
        }
    }

    private static void expandSeparator(OpenUiLang.ComponentNode node,
                                        String parentId,
                                        int[] inheritedGapMargin,
                                        CompileCtx ctx)
            throws AgentTools.ToolException {
        String id = sanitizeWidgetId(node.id, "divider", ctx.allocatedIds);
        registerTargetId(id, ctx);
        Map<String, Object> props = extractPropsMap(node, 0);
        boolean vert = "vertical".equalsIgnoreCase(propStr(props, "orientation", "orient"));
        PlannedWidget sep = new PlannedWidget();
        sep.id = id;
        sep.parentId = parentId;
        sep.sketchwareType = "LinearLayout";
        sep.orientation = vert ? "vertical" : "horizontal";
        sep.width = vert ? "1" : "match_parent";
        sep.height = vert ? "match_parent" : "1";
        sep.padding = 0;
        sep.background = ctx.hasTheme ? "@color/openui_border_default" : "#E5E5E5";
        if (inheritedGapMargin != null) {
            sep.margin = inheritedGapMargin;
        }
        populateFromPropsMap(sep, props);
        ctx.out.add(sep);
    }

    private static void expandCodeBlock(OpenUiLang.ComponentNode node,
                                        String parentId,
                                        int[] inheritedGapMargin,
                                        CompileCtx ctx)
            throws AgentTools.ToolException {
        String boxId = sanitizeWidgetId(node.id, "code_box", ctx.allocatedIds);
        registerTargetId(boxId, ctx);
        String code = node.arg(0) == null ? "" : OpenUiLang.stringify(node.arg(0));
        Map<String, Object> props = extractPropsMap(node, 1);

        PlannedWidget box = new PlannedWidget();
        box.id = boxId;
        box.parentId = parentId;
        box.sketchwareType = "LinearLayout";
        box.orientation = "vertical";
        box.width = "match_parent";
        box.height = "wrap_content";
        box.padding = 12;
        if (ctx.hasTheme) {
            box.backgroundImage = "openui_card_sunk";
        } else {
            box.background = "#F5F5F5";
        }
        if (inheritedGapMargin != null) {
            box.margin = inheritedGapMargin;
        }
        populateFromPropsMap(box, props);
        ctx.out.add(box);

        String tvId = reserveDerivedId(boxId + "_code", ctx.allocatedIds);
        registerTargetId(tvId, ctx);
        PlannedWidget tv = new PlannedWidget();
        tv.id = tvId;
        tv.parentId = boxId;
        tv.sketchwareType = "TextView";
        tv.text = code;
        tv.textSize = 13;
        tv.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
        tv.putAttr("android:typeface", "monospace");
        ctx.out.add(tv);
    }

    private static void collectStandardOrRoleWidget(OpenUiLang.ComponentNode node,
                                                    String parentId,
                                                    int[] inheritedGapMargin,
                                                    CompileCtx ctx)
            throws AgentTools.ToolException {
        String rawType = node.type == null ? "Column" : node.type.trim();
        ResolvedType rt = resolveWidgetType(rawType);
        String id = sanitizeWidgetId(node.id, rt.sketchwareType, ctx.allocatedIds);
        registerTargetId(id, ctx);

        Integer typeCode = resolveTypeCode(rt.sketchwareType);
        boolean isContainer = typeCode != null && AgentEdits.isContainer(typeCode);

        PlannedWidget pw = new PlannedWidget();
        pw.id = id;
        pw.parentId = parentId;
        pw.sketchwareType = rt.sketchwareType;
        pw.orientation = rt.impliedOrientation;

        Map<String, Object> props;
        List<OpenUiLang.ComponentNode> childNodes = Collections.emptyList();

        if (isContainer || (node.arg(0) instanceof List<?>)) {
            childNodes = node.argChildren(0);
            props = extractPropsMap(node, 1);
        } else {
            Object firstArg = node.arg(0);
            if (firstArg instanceof Map<?, ?>) {
                props = extractPropsMap(node, 0);
            } else {
                String primary = firstArg == null ? null : OpenUiLang.stringify(firstArg);
                assignPrimaryArg(pw, rt.sketchwareType, primary);
                props = extractPropsMap(node, 1);
            }
        }

        // Apply OpenUI component defaults BEFORE explicit user props so props always win
        applyRoleDefaults(rawType, pw, props, ctx);

        if (inheritedGapMargin != null && pw.margin == null
                && !props.containsKey("m") && !props.containsKey("margin")) {
            pw.margin = inheritedGapMargin;
        }

        populateFromPropsMap(pw, props);

        if (!ctx.existingById.containsKey(id)
                && "LinearLayout".equalsIgnoreCase(pw.sketchwareType)
                && !"root".equalsIgnoreCase(parentId)) {
            if (pw.width == null && (pw.weight == null || pw.weight == 0)) {
                pw.width = "match_parent";
            }
            if (pw.height == null && (pw.weight == null || pw.weight == 0)) {
                pw.height = "wrap_content";
            }
        }

        ctx.out.add(pw);

        boolean prevScroll = ctx.insideScrollView;
        if ("ScrollView".equalsIgnoreCase(pw.sketchwareType)) {
            ctx.insideScrollView = true;
        }

        // If Card / CardView has > 1 direct child, wrap inside an inner vertical LinearLayout
        // so FrameLayout-based CardView never overlaps children on top of each other!
        String childParentId = id;
        if ("CardView".equalsIgnoreCase(pw.sketchwareType) && childNodes.size() > 1) {
            String innerId = reserveDerivedId(id + "_body", ctx.allocatedIds);
            registerTargetId(innerId, ctx);
            PlannedWidget inner = new PlannedWidget();
            inner.id = innerId;
            inner.parentId = id;
            inner.sketchwareType = "LinearLayout";
            inner.orientation = "vertical";
            inner.width = "match_parent";
            inner.height = "wrap_content";
            inner.padding = props.containsKey("p") ? props.get("p")
                    : OpenUiThemeEngine.densityCardPadding(ctx.theme.density);
            ctx.out.add(inner);
            childParentId = innerId;
        }

        boolean horizChildren = "horizontal".equalsIgnoreCase(pw.orientation);
        int gapDp = parseGapDp(props.get("gap"),
                "Buttons".equals(rawType) ? 8 : 0);
        for (int i = 0; i < childNodes.size(); i++) {
            int[] childGap = null;
            if (i < childNodes.size() - 1 && gapDp > 0) {
                childGap = horizChildren
                        ? new int[]{0, 0, gapDp, 0}
                        : new int[]{0, 0, 0, gapDp};
            }
            collectNode(childNodes.get(i), childParentId, childGap, ctx);
        }
        ctx.insideScrollView = prevScroll;
    }

    private static void applyRoleDefaults(String rawType,
                                          PlannedWidget pw,
                                          Map<String, Object> props,
                                          CompileCtx ctx) {
        switch (rawType) {
            case "Stack": {
                String dir = propStr(props, "direction", "dir");
                if (dir != null) {
                    pw.orientation = ("row".equalsIgnoreCase(dir)
                            || "horizontal".equalsIgnoreCase(dir))
                            ? "horizontal" : "vertical";
                }
                break;
            }
            case "Buttons": {
                String dir = propStr(props, "direction", "dir");
                pw.orientation = (dir == null || "row".equalsIgnoreCase(dir)
                        || "horizontal".equalsIgnoreCase(dir))
                        ? "horizontal" : "vertical";
                break;
            }
            case "Card": {
                String variant = propStr(props, "variant");
                if ("clear".equalsIgnoreCase(variant)) {
                    pw.sketchwareType = "LinearLayout";
                    pw.orientation = "vertical";
                } else if (ctx.hasTheme) {
                    pw.token = "sunk".equalsIgnoreCase(variant) ? "sunk_card" : "card";
                }
                pw.width = "match_parent";
                pw.height = "wrap_content";
                break;
            }
            case "TextContent": {
                String sz = propStr(props, "size");
                if (sz == null) {
                    sz = "default";
                }
                switch (sz.toLowerCase(Locale.ROOT)) {
                    case "small":
                        pw.textSize = 12;
                        pw.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
                        break;
                    case "small-heavy":
                        pw.textSize = 12;
                        pw.textStyle = "bold";
                        pw.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
                        break;
                    case "large":
                        pw.textSize = 18;
                        pw.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
                        break;
                    case "large-heavy":
                        pw.textSize = 20;
                        pw.textStyle = "bold";
                        pw.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
                        if (!ctx.theme.fontHeading.isEmpty()) {
                            pw.font = ctx.theme.fontHeading;
                        }
                        break;
                    default:
                        pw.textSize = 14;
                        pw.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
                        break;
                }
                break;
            }
            case "Heading":
            case "Title":
                if (ctx.hasTheme) {
                    pw.token = "title";
                } else {
                    pw.textSize = 22;
                    pw.textStyle = "bold";
                }
                if (!ctx.theme.fontHeading.isEmpty()) {
                    pw.font = ctx.theme.fontHeading;
                }
                break;
            case "Subtitle":
            case "Body":
                if (ctx.hasTheme) {
                    pw.token = "body";
                } else {
                    pw.textSize = 14;
                }
                if (!ctx.theme.fontBody.isEmpty()) {
                    pw.font = ctx.theme.fontBody;
                }
                break;
            case "Caption":
                if (ctx.hasTheme) {
                    pw.token = "caption";
                } else {
                    pw.textSize = 12;
                    pw.textColor = "#525252";
                }
                break;
            case "Label":
                pw.textSize = 13;
                pw.textStyle = "bold";
                pw.textColor = ctx.hasTheme ? "@color/openui_text_secondary" : "#525252";
                pw.margin = new int[]{0, 0, 0, 6};
                break;
            case "Text":
            case "BoldText": {
                String variant = propStr(props, "variant");
                pw.textSize = "number".equalsIgnoreCase(variant)
                        ? 24 : mapShortTextSize(propStr(props, "size"), 14);
                if ("BoldText".equals(rawType) || "number".equalsIgnoreCase(variant)) {
                    pw.textStyle = "bold";
                }
                pw.textColor = ctx.hasTheme ? "@color/openui_text_primary" : "#171717";
                break;
            }
            case "Tag":
            case "Badge": {
                String variant = normalizeStatusVariant(propStr(props, "variant"));
                pw.textSize = 12;
                pw.textStyle = "bold";
                pw.padding = new int[]{10, 4, 10, 4};
                if (ctx.hasTheme) {
                    pw.backgroundImage = "openui_tag_" + variant;
                    pw.textColor = calloutTextColorRef(variant, true);
                }
                break;
            }
            case "Input":
            case "TextInput":
                pw.width = "match_parent";
                if (ctx.hasTheme) {
                    pw.token = "input";
                } else {
                    pw.height = "48";
                    pw.padding = new int[]{14, 0, 14, 0};
                }
                break;
            case "TextArea": {
                int rows = propInt(props, "rows") != null ? propInt(props, "rows") : 4;
                pw.width = "match_parent";
                pw.height = Integer.toString(Math.max(88, rows * 22 + 24));
                pw.gravity = "top|left";
                pw.padding = 12;
                if (ctx.hasTheme) {
                    pw.backgroundImage = "openui_sunk_input";
                    pw.textColor = "@color/openui_text_primary";
                    pw.hintColor = "@color/openui_text_tertiary";
                }
                break;
            }
            case "Select":
                pw.width = "match_parent";
                pw.height = "48";
                if (ctx.hasTheme) {
                    pw.backgroundImage = "openui_sunk_input";
                }
                break;
            case "DatePicker":
                pw.width = "match_parent";
                pw.height = "48";
                pw.gravity = "center_vertical|left";
                pw.padding = new int[]{14, 0, 14, 0};
                if (ctx.hasTheme) {
                    pw.backgroundImage = "openui_sunk_input";
                    pw.textColor = "@color/openui_text_primary";
                }
                break;
            case "Chip": {
                pw.height = "40";
                pw.padding = new int[]{16, 0, 16, 0};
                if (ctx.hasTheme) {
                    pw.token = "chip";
                }
                if (Boolean.TRUE.equals(propBool(props, "selected"))) {
                    pw.putAttr("android:selected", "true");
                }
                break;
            }
            case "Button": {
                String variant = propStr(props, "variant");
                if (variant != null && ctx.hasTheme) {
                    switch (variant.toLowerCase(Locale.ROOT)) {
                        case "secondary":
                        case "outline":
                            pw.token = "secondary_button";
                            break;
                        case "destructive":
                        case "danger":
                            pw.token = "destructive_button";
                            break;
                        case "tertiary":
                        case "ghost":
                            pw.background = "#00000000";
                            pw.textColor = "@color/openui_accent";
                            pw.textStyle = "bold";
                            break;
                        default:
                            pw.token = "primary_button";
                            break;
                    }
                } else if (ctx.hasTheme
                        && !props.containsKey("bg")
                        && !props.containsKey("background")
                        && !props.containsKey("bgImg")
                        && !props.containsKey("background_image")
                        && !props.containsKey("token")) {
                    pw.token = "primary_button";
                }
                break;
            }
            case "CheckBoxItem":
                pw.width = "match_parent";
                if (ctx.hasTheme) {
                    pw.textColor = "@color/openui_text_primary";
                }
                break;
            case "RecyclerList":
                pw.width = "match_parent";
                if (!props.containsKey("h") && !props.containsKey("height")) {
                    pw.height = "0";
                    pw.weight = 1;
                }
                break;
            case "BottomNav":
                pw.width = "match_parent";
                pw.height = "wrap_content";
                if (ctx.hasTheme) {
                    pw.background = "@color/openui_fg";
                }
                String menu = propStr(props, "menu");
                if (menu != null && !menu.isEmpty()) {
                    pw.putAttr("app:menu", menu.startsWith("@menu/") ? menu : "@menu/" + menu);
                }
                break;
            default:
                break;
        }
    }

    private static void registerTargetId(String id, CompileCtx ctx)
            throws AgentTools.ToolException {
        if (!ctx.targetIds.add(id)) {
            throw new AgentTools.ToolException("E_DUPLICATE_ID",
                    "Widget '" + id + "' is placed in two parents at once in the OpenUI layout.");
        }
    }

    private static String reserveDerivedId(String candidate, Set<String> allocated) {
        String clean = candidate.replace('-', '_');
        if (!allocated.contains(clean)) {
            allocated.add(clean);
            return clean;
        }
        int idx = 2;
        while (allocated.contains(clean + "_" + idx)) {
            idx++;
        }
        String fresh = clean + "_" + idx;
        allocated.add(fresh);
        return fresh;
    }

    private static int parseGapDp(Object rawGap, int fallback) {
        if (rawGap == null) {
            return fallback;
        }
        if (rawGap instanceof Number) {
            return Math.max(0, ((Number) rawGap).intValue());
        }
        String s = String.valueOf(rawGap).trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "none":
            case "0":
                return 0;
            case "2xs":
                return 2;
            case "xs":
                return 4;
            case "s":
            case "sm":
                return 8;
            case "m":
            case "md":
                return 12;
            case "l":
            case "lg":
                return 16;
            case "xl":
                return 24;
            case "2xl":
                return 32;
            default:
                try {
                    return Math.max(0, Integer.parseInt(s.replace("dp", "").trim()));
                } catch (NumberFormatException e) {
                    return fallback;
                }
        }
    }

    private static int mapShortTextSize(String sizeKey, int defSp) {
        if (sizeKey == null) {
            return defSp;
        }
        switch (sizeKey.trim().toLowerCase(Locale.ROOT)) {
            case "xs":
                return 12;
            case "sm":
            case "s":
                return 14;
            case "md":
            case "m":
                return 16;
            case "lg":
            case "l":
                return 20;
            case "xl":
                return 24;
            default:
                try {
                    return Integer.parseInt(sizeKey.replace("sp", "").trim());
                } catch (NumberFormatException e) {
                    return defSp;
                }
        }
    }

    private static boolean isStatusVariantWord(String s) {
        if (s == null) {
            return false;
        }
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "neutral":
            case "info":
            case "success":
            case "warning":
            case "alert":
            case "danger":
            case "error":
                return true;
            default:
                return false;
        }
    }

    private static String normalizeStatusVariant(String s) {
        if (s == null) {
            return "info";
        }
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "neutral":
                return "neutral";
            case "success":
                return "success";
            case "warning":
            case "alert":
                return "warning";
            case "danger":
            case "error":
                return "danger";
            default:
                return "info";
        }
    }

    private static String calloutTextColorRef(String variant, boolean hasTheme) {
        if (!hasTheme) {
            return "#171717";
        }
        switch (variant) {
            case "success":
                return "@color/openui_success_text";
            case "warning":
                return "@color/openui_alert_text";
            case "danger":
                return "@color/openui_danger_text";
            case "info":
                return "@color/openui_info_text";
            default:
                return "@color/openui_text_primary";
        }
    }

    private static void assignPrimaryArg(PlannedWidget pw, String typeName, String primary) {
        if (primary == null) {
            return;
        }
        switch (typeName) {
            case "EditText":
            case "AutoCompleteTextView":
            case "MultiAutoCompleteTextView":
                pw.hint = primary;
                break;
            case "ImageView":
            case "CircleImageView":
                if (!primary.isEmpty()) {
                    pw.image = primary;
                }
                break;
            case "RecyclerView":
            case "ListView":
            case "GridView":
            case "ViewPager":
            case "Spinner":
                if (!primary.isEmpty()) {
                    pw.customView = primary;
                }
                break;
            default:
                pw.text = primary;
                break;
        }
    }

    private static Map<String, Object> extractPropsMap(OpenUiLang.ComponentNode node,
                                                       int mapArgIndex) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object argObj = node.arg(mapArgIndex);
        if (argObj instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) argObj).entrySet()) {
                merged.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        if (node.namedArgs != null) {
            merged.putAll(node.namedArgs);
        }
        return merged;
    }

    private static void populateFromPropsMap(PlannedWidget pw, Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return;
        }
        String tok = propStr(props, "token");
        if (tok != null && !tok.isEmpty()) {
            pw.token = tok;
        }
        String orient = propStr(props, "orient", "orientation");
        if (orient != null) {
            pw.orientation = normalizeOrientation(orient);
        }
        String w = propDim(props, "w", "width");
        if (w != null) {
            pw.width = w;
        }
        String h = propDim(props, "h", "height");
        if (h != null) {
            pw.height = h;
        }
        Integer weight = propInt(props, "weight");
        if (weight != null) {
            pw.weight = weight;
        }
        if (props.containsKey("p")) {
            pw.padding = props.get("p");
        } else if (props.containsKey("padding")) {
            pw.padding = props.get("padding");
        }
        if (props.containsKey("m")) {
            pw.margin = props.get("m");
        } else if (props.containsKey("margin")) {
            pw.margin = props.get("margin");
        }
        String grav = propStr(props, "gravity");
        if (grav != null) {
            pw.gravity = grav;
        }
        String lgrav = propStr(props, "layout_gravity", "layoutGravity");
        if (lgrav != null) {
            pw.layoutGravity = lgrav;
        }
        if (props.containsKey("text")) {
            pw.text = OpenUiLang.stringify(props.get("text"));
        }
        if (props.containsKey("hint")) {
            pw.hint = OpenUiLang.stringify(props.get("hint"));
        }
        String hc = propStr(props, "hintColor", "hint_color");
        if (hc != null) {
            pw.hintColor = hc;
        }
        String bg = propStr(props, "bg", "background");
        if (bg != null) {
            if (isDrawableRef(bg)) {
                pw.backgroundImage = bg;
            } else {
                pw.background = bg;
            }
        }
        String bgImg = propStr(props, "bgImg", "background_image", "backgroundImage");
        if (bgImg != null) {
            pw.backgroundImage = bgImg;
        }
        String tc = propStr(props, "color", "text_color", "textColor");
        if (tc != null) {
            pw.textColor = tc;
        }
        Integer ts = propInt(props, "size", "text_size", "textSize");
        if (ts != null) {
            pw.textSize = ts;
        }
        String tstyle = propStr(props, "style", "text_style", "textStyle");
        if (tstyle != null) {
            pw.textStyle = tstyle;
        }
        String font = propStr(props, "font");
        if (font != null) {
            pw.font = font;
        }
        String inputType = propStr(props, "input_type", "inputType", "type");
        if (inputType != null && ("EditText".equals(pw.sketchwareType)
                || "AutoCompleteTextView".equals(pw.sketchwareType))) {
            pw.inputType = inputType;
        }
        String img = propStr(props, "img", "src", "image");
        if (img != null) {
            pw.image = img;
        }
        String scale = propStr(props, "scale", "scale_type", "scaleType");
        if (scale != null) {
            pw.scaleType = scale;
        }
        String cv = propStr(props, "item_view", "custom_view", "customView");
        if (cv != null) {
            pw.customView = cv;
        }
        Boolean checked = propBool(props, "checked");
        if (checked != null) {
            pw.checked = checked;
        }
        Boolean enabled = propBool(props, "enabled");
        if (enabled != null) {
            pw.enabled = enabled;
        }
        Boolean clickable = propBool(props, "clickable");
        if (clickable != null) {
            pw.clickable = clickable;
        }
        String vis = propStr(props, "visibility", "vis");
        if (vis != null && !vis.isEmpty()) {
            pw.visibility = vis;
        } else {
            Boolean visibleBool = propBool(props, "visible");
            if (visibleBool != null) {
                pw.visibility = visibleBool ? "visible" : "gone";
            }
        }
        Integer prog = propInt(props, "progress");
        if (prog != null) {
            pw.progress = prog;
        }
        Integer max = propInt(props, "max");
        if (max != null) {
            pw.max = max;
        }
        Boolean indet = propBool(props, "indeterminate");
        if (indet != null) {
            pw.indeterminate = indet;
        }
        if (props.containsKey("alpha")) {
            pw.alpha = OpenUiLang.toDouble(props.get("alpha"), 1.0);
        }
        Object rawAttrs = props.containsKey("attrs") ? props.get("attrs") : props.get("attributes");
        if (rawAttrs instanceof Map<?, ?>) {
            if (pw.attributes == null) {
                pw.attributes = new LinkedHashMap<>();
            }
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rawAttrs).entrySet()) {
                pw.attributes.put(String.valueOf(e.getKey()),
                        OpenUiLang.stringify(e.getValue()));
            }
        }
    }

    private static void emitWidgetPropertyOps(PlannedWidget pw,
                                              ViewBean existing,
                                              JsonArray ops) {
        if (existing != null) {
            if (pw.token != null && !pw.token.isEmpty()) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_token");
                op.addProperty("id", pw.id);
                op.addProperty("token", pw.token);
                ops.add(op);
            }
            if (pw.text != null) {
                String oldText = existing.text == null || existing.text.text == null
                        ? "" : existing.text.text;
                if (!pw.text.equals(oldText)) {
                    JsonObject op = new JsonObject();
                    op.addProperty("op", "set_text");
                    op.addProperty("id", pw.id);
                    op.addProperty("text", pw.text);
                    ops.add(op);
                }
            }
            if (pw.background != null && !pw.background.isEmpty()) {
                String oldBg = currentBgString(existing);
                if (!pw.background.equalsIgnoreCase(oldBg)) {
                    JsonObject op = new JsonObject();
                    op.addProperty("op", "set_background");
                    op.addProperty("id", pw.id);
                    op.addProperty("color", pw.background);
                    ops.add(op);
                }
            }
            if (pw.textColor != null && !pw.textColor.isEmpty()) {
                String oldTc = currentTextColorString(existing);
                if (!pw.textColor.equalsIgnoreCase(oldTc)) {
                    JsonObject op = new JsonObject();
                    op.addProperty("op", "set_text_color");
                    op.addProperty("id", pw.id);
                    op.addProperty("color", pw.textColor);
                    ops.add(op);
                }
            }
        }

        if (pw.hintColor != null && !pw.hintColor.isEmpty()) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_hint_color");
            op.addProperty("id", pw.id);
            op.addProperty("color", pw.hintColor);
            ops.add(op);
        }

        if (pw.orientation != null && "LinearLayout".equalsIgnoreCase(pw.sketchwareType)) {
            boolean need = existing == null
                    ? "horizontal".equals(pw.orientation)
                    : ((existing.layout == null
                            ? LayoutBean.ORIENTATION_VERTICAL : existing.layout.orientation)
                            != ("horizontal".equals(pw.orientation)
                                    ? LayoutBean.ORIENTATION_HORIZONTAL
                                    : LayoutBean.ORIENTATION_VERTICAL));
            if (need) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_orientation");
                op.addProperty("id", pw.id);
                op.addProperty("orientation", pw.orientation);
                ops.add(op);
            }
        }

        if (pw.width != null || pw.height != null) {
            boolean widthDiff = pw.width != null
                    && (existing == null || existing.layout == null
                            || !dimMatches(existing.layout.width, pw.width));
            boolean heightDiff = pw.height != null
                    && (existing == null || existing.layout == null
                            || !dimMatches(existing.layout.height, pw.height));
            if (widthDiff || heightDiff) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_size");
                op.addProperty("id", pw.id);
                if (widthDiff) {
                    putDimProp(op, "width", pw.width);
                }
                if (heightDiff) {
                    putDimProp(op, "height", pw.height);
                }
                ops.add(op);
            }
        }

        if (pw.weight != null) {
            int oldW = existing != null && existing.layout != null ? existing.layout.weight : 0;
            if (existing == null || oldW != pw.weight) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_weight");
                op.addProperty("id", pw.id);
                op.addProperty("weight", pw.weight);
                ops.add(op);
            }
        }

        if (pw.padding != null) {
            int[] box = parseBox(pw.padding);
            if (box != null && (existing == null || existing.layout == null
                    || existing.layout.paddingLeft != box[0]
                    || existing.layout.paddingTop != box[1]
                    || existing.layout.paddingRight != box[2]
                    || existing.layout.paddingBottom != box[3])) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_padding");
                op.addProperty("id", pw.id);
                op.addProperty("left", box[0]);
                op.addProperty("top", box[1]);
                op.addProperty("right", box[2]);
                op.addProperty("bottom", box[3]);
                ops.add(op);
            }
        }

        if (pw.margin != null) {
            int[] box = parseBox(pw.margin);
            if (box != null && (existing == null || existing.layout == null
                    || existing.layout.marginLeft != box[0]
                    || existing.layout.marginTop != box[1]
                    || existing.layout.marginRight != box[2]
                    || existing.layout.marginBottom != box[3])) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_margin");
                op.addProperty("id", pw.id);
                op.addProperty("left", box[0]);
                op.addProperty("top", box[1]);
                op.addProperty("right", box[2]);
                op.addProperty("bottom", box[3]);
                ops.add(op);
            }
        }

        if (pw.gravity != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_gravity");
            op.addProperty("id", pw.id);
            op.addProperty("gravity", pw.gravity);
            ops.add(op);
        }

        if (pw.layoutGravity != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_gravity");
            op.addProperty("id", pw.id);
            op.addProperty("target", "layout");
            op.addProperty("gravity", pw.layoutGravity);
            ops.add(op);
        }

        if (pw.hint != null) {
            String oldHint = existing != null && existing.text != null && existing.text.hint != null
                    ? existing.text.hint : "";
            if (existing == null ? !pw.hint.isEmpty() : !pw.hint.equals(oldHint)) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_hint");
                op.addProperty("id", pw.id);
                op.addProperty("hint", pw.hint);
                ops.add(op);
            }
        }

        if (pw.textSize != null) {
            int oldSz = existing != null && existing.text != null ? existing.text.textSize : -1;
            if (existing == null || oldSz != pw.textSize) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_text_size");
                op.addProperty("id", pw.id);
                op.addProperty("size", pw.textSize);
                ops.add(op);
            }
        }

        if (pw.textStyle != null) {
            String curStyle = existing == null || existing.text == null ? ""
                    : (existing.text.textType == TextBean.TEXT_TYPE_BOLD ? "bold"
                            : existing.text.textType == TextBean.TEXT_TYPE_ITALIC ? "italic"
                            : existing.text.textType == TextBean.TEXT_TYPE_BOLDITALIC ? "bold_italic"
                            : "normal");
            if (existing == null || !pw.textStyle.equalsIgnoreCase(curStyle)) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_text_style");
                op.addProperty("id", pw.id);
                op.addProperty("style", pw.textStyle);
                ops.add(op);
            }
        }

        if (pw.font != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_font");
            op.addProperty("id", pw.id);
            op.addProperty("font", pw.font);
            ops.add(op);
        }

        if (pw.inputType != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_input_type");
            op.addProperty("id", pw.id);
            op.addProperty("input_type", pw.inputType);
            ops.add(op);
        }

        if (pw.backgroundImage != null) {
            String oldBgImg = existing != null && existing.layout != null
                    && existing.layout.backgroundResource != null
                    ? existing.layout.backgroundResource : "";
            String clean = stripDrawable(pw.backgroundImage);
            if (!clean.equals(oldBgImg)) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_background_image");
                op.addProperty("id", pw.id);
                op.addProperty("image", clean);
                ops.add(op);
            }
        }

        if (pw.image != null) {
            String oldImg = existing != null && existing.image != null
                    && existing.image.resName != null ? existing.image.resName : "";
            String clean = stripDrawable(pw.image);
            if (!clean.equals(oldImg)) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_image");
                op.addProperty("id", pw.id);
                op.addProperty("image", clean);
                ops.add(op);
            }
        }

        if (pw.scaleType != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_scale_type");
            op.addProperty("id", pw.id);
            op.addProperty("scale_type", pw.scaleType);
            ops.add(op);
        }

        if (pw.customView != null) {
            String oldCv = existing != null && existing.customView != null ? existing.customView : "";
            if (!pw.customView.equals(oldCv)) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_list_item_view");
                op.addProperty("id", pw.id);
                op.addProperty("custom_view", pw.customView);
                ops.add(op);
            }
        }

        if (pw.checked != null) {
            boolean oldChk = existing != null && existing.checked == 1;
            if (existing == null || oldChk != pw.checked) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_checked");
                op.addProperty("id", pw.id);
                op.addProperty("checked", pw.checked);
                ops.add(op);
            }
        }

        if (pw.enabled != null) {
            boolean oldEn = existing == null || existing.enabled != 0;
            if (existing == null || oldEn != pw.enabled) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_enabled");
                op.addProperty("id", pw.id);
                op.addProperty("enabled", pw.enabled);
                ops.add(op);
            }
        }

        if (pw.clickable != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_clickable");
            op.addProperty("id", pw.id);
            op.addProperty("clickable", pw.clickable);
            ops.add(op);
        }

        if (pw.visibility != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_visibility");
            op.addProperty("id", pw.id);
            op.addProperty("visibility", pw.visibility);
            ops.add(op);
        }

        if (pw.progress != null || pw.max != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_progress");
            op.addProperty("id", pw.id);
            if (pw.progress != null) {
                op.addProperty("progress", pw.progress);
            }
            if (pw.max != null) {
                op.addProperty("max", pw.max);
            }
            ops.add(op);
        }

        if (pw.indeterminate != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_indeterminate");
            op.addProperty("id", pw.id);
            op.addProperty("indeterminate", pw.indeterminate);
            ops.add(op);
        }

        if (pw.alpha != null) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "set_alpha");
            op.addProperty("id", pw.id);
            op.addProperty("alpha", pw.alpha);
            ops.add(op);
        }

        if (pw.attributes != null && !pw.attributes.isEmpty()) {
            Map<String, String> oldAttrs = existing != null
                    ? parseInjectMap(existing.inject) : Collections.emptyMap();
            if (!pw.attributes.equals(oldAttrs)) {
                JsonObject ao = new JsonObject();
                for (Map.Entry<String, String> e : pw.attributes.entrySet()) {
                    ao.addProperty(e.getKey(), e.getValue());
                }
                JsonObject op = new JsonObject();
                op.addProperty("op", "set_attributes");
                op.addProperty("id", pw.id);
                op.add("attributes", ao);
                ops.add(op);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Value Normalization Helpers
    // ---------------------------------------------------------------------

    private static String sanitizeWidgetId(String rawId, String typeName, Set<String> allocated) {
        if (rawId != null && !rawId.isEmpty() && !"root".equalsIgnoreCase(rawId)) {
            String normalized = rawId.replace('-', '_');
            if (normalized.matches("[A-Za-z][A-Za-z0-9_]{0,39}")) {
                allocated.add(normalized);
                return normalized;
            }
        }
        String base = typeName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (base.isEmpty()) {
            base = "view";
        }
        int n = 1;
        while (allocated.contains(base + n)) {
            n++;
        }
        String fresh = base + n;
        allocated.add(fresh);
        return fresh;
    }

    private static String propStr(Map<String, Object> props, String... keys) {
        if (props == null) {
            return null;
        }
        for (String k : keys) {
            if (props.containsKey(k) && props.get(k) != null) {
                return OpenUiLang.stringify(props.get(k));
            }
        }
        return null;
    }

    private static Integer propInt(Map<String, Object> props, String... keys) {
        if (props == null) {
            return null;
        }
        for (String k : keys) {
            if (props.containsKey(k) && props.get(k) != null) {
                return (int) Math.round(OpenUiLang.toDouble(props.get(k), 0.0));
            }
        }
        return null;
    }

    private static Boolean propBool(Map<String, Object> props, String... keys) {
        if (props == null) {
            return null;
        }
        for (String k : keys) {
            if (props.containsKey(k) && props.get(k) != null) {
                return OpenUiLang.toBoolean(props.get(k));
            }
        }
        return null;
    }

    private static String propDim(Map<String, Object> props, String... keys) {
        if (props == null) {
            return null;
        }
        for (String k : keys) {
            if (!props.containsKey(k) || props.get(k) == null) {
                continue;
            }
            Object raw = props.get(k);
            if (raw instanceof Number) {
                int v = ((Number) raw).intValue();
                if (v == -1) {
                    return "match_parent";
                }
                if (v == -2) {
                    return "wrap_content";
                }
                return Integer.toString(v);
            }
            String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            switch (s) {
                case "match":
                case "match_parent":
                case "fill":
                case "full":
                case "-1":
                    return "match_parent";
                case "wrap":
                case "wrap_content":
                case "auto":
                case "-2":
                    return "wrap_content";
                default:
                    if (s.endsWith("dp")) {
                        s = s.substring(0, s.length() - 2).trim();
                    }
                    return s;
            }
        }
        return null;
    }

    private static void putDimProp(JsonObject op, String field, String normalizedDim) {
        if ("match_parent".equals(normalizedDim) || "wrap_content".equals(normalizedDim)) {
            op.addProperty(field, normalizedDim);
            return;
        }
        try {
            op.addProperty(field, Integer.parseInt(normalizedDim));
        } catch (NumberFormatException e) {
            op.addProperty(field, normalizedDim);
        }
    }

    private static boolean dimMatches(int currentBeanDim, String normalizedDim) {
        if ("match_parent".equals(normalizedDim)) {
            return currentBeanDim == LayoutBean.LAYOUT_MATCH_PARENT;
        }
        if ("wrap_content".equals(normalizedDim)) {
            return currentBeanDim == LayoutBean.LAYOUT_WRAP_CONTENT;
        }
        try {
            return currentBeanDim == Integer.parseInt(normalizedDim);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int[] parseBox(Object raw) {
        if (raw instanceof int[]) {
            int[] arr = (int[]) raw;
            if (arr.length >= 4) {
                return new int[]{arr[0], arr[1], arr[2], arr[3]};
            }
        }
        if (raw instanceof Number) {
            int v = ((Number) raw).intValue();
            return new int[]{v, v, v, v};
        }
        if (raw instanceof List<?>) {
            List<?> list = (List<?>) raw;
            if (list.size() == 1) {
                int v = (int) Math.round(OpenUiLang.toDouble(list.get(0), 0));
                return new int[]{v, v, v, v};
            }
            if (list.size() == 2) {
                int h = (int) Math.round(OpenUiLang.toDouble(list.get(0), 0));
                int v = (int) Math.round(OpenUiLang.toDouble(list.get(1), 0));
                return new int[]{h, v, h, v};
            }
            if (list.size() >= 4) {
                return new int[]{
                        (int) Math.round(OpenUiLang.toDouble(list.get(0), 0)),
                        (int) Math.round(OpenUiLang.toDouble(list.get(1), 0)),
                        (int) Math.round(OpenUiLang.toDouble(list.get(2), 0)),
                        (int) Math.round(OpenUiLang.toDouble(list.get(3), 0))
                };
            }
        }
        if (raw instanceof String) {
            try {
                int v = Integer.parseInt(((String) raw).replace("dp", "").trim());
                return new int[]{v, v, v, v};
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String normalizeOrientation(String s) {
        String low = s.trim().toLowerCase(Locale.ROOT);
        return (low.startsWith("h") || "row".equals(low)) ? "horizontal" : "vertical";
    }

    private static boolean isDrawableRef(String s) {
        return s != null && s.trim().startsWith("@drawable/");
    }

    private static String stripDrawable(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.startsWith("@drawable/") ? t.substring("@drawable/".length()) : t;
    }

    private static String currentBgString(ViewBean bean) {
        if (bean == null || bean.layout == null) {
            return "";
        }
        if (bean.layout.backgroundResColor != null && !bean.layout.backgroundResColor.isEmpty()) {
            return bean.layout.backgroundResColor;
        }
        if (bean.layout.backgroundColor == 0) {
            return "";
        }
        return formatColorHex(bean.layout.backgroundColor);
    }

    private static String currentTextColorString(ViewBean bean) {
        if (bean == null || bean.text == null) {
            return "";
        }
        if (bean.text.resTextColor != null && !bean.text.resTextColor.isEmpty()) {
            return bean.text.resTextColor;
        }
        if (bean.text.textColor == 0) {
            return "";
        }
        return formatColorHex(bean.text.textColor);
    }
}
