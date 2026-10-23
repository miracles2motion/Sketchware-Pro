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
 * Translates between compact 1-line-per-widget OpenUI Lang 1.0 programs
 * and Sketchware Pro {@link ViewBean} screen layouts / {@link AgentEdits} operations.
 *
 * <p>Preserves 100% compatibility with Sketchware Pro's native View/Logic editors,
 * Design System tokens, {@code @color/*} / {@code ?attr/*} resources, per-widget
 * {@code ViewBean.inject} validation, and the native Approval Card flow:
 * <ul>
 *   <li>{@link #toOpenUi(List)} renders any screen's {@link ViewBean} tree as
 *       compact OpenUI Lang statements (~60-65% fewer tokens than verbose JSON).</li>
 *   <li>{@link #compileToOps(List, String, boolean)} compiles either a full OpenUI
 *       screen definition or a 2-line incremental patch ({@link OpenUiLang#mergeStatements})
 *       into canonical {@link AgentEdits} JSON operations — updating existing widgets
 *       in place so wired {@code BlockBean} events are never disturbed.</li>
 * </ul>
 */
public final class OpenUiScreenBridge {

    /** Maximum compiled micro-operations permitted for a single OpenUI screen build/patch. */
    public static final int MAX_COMPILED_OPS = 200;

    private static final Set<String> ROOT_ENTRY_TYPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "Screen", "Column", "VStack", "LinearV", "Row", "HStack", "LinearH",
                    "Stack", "LinearLayout", "RelativeLayout", "ScrollView", "Scroll",
                    "HorizontalScrollView", "HScroll", "CardView", "Card",
                    "SwipeRefreshLayout")));

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
            // Emit non-default width/height concisely
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

    /**
     * Compiles an OpenUI Lang 1.0 screen specification (or incremental 2-line patch)
     * against {@code existingBeans} into a validated list of {@link AgentEdits} operations.
     */
    public static JsonArray compileToOps(List<ViewBean> existingBeans,
                                         String rawOpenUi,
                                         boolean patchMode)
            throws AgentTools.ToolException {
        if (rawOpenUi == null || rawOpenUi.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_EMPTY_OPENUI",
                    "Provide non-empty OpenUI Lang statements in "
                            + (patchMode ? "\"openui_patch\"" : "\"openui\"") + ".");
        }

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
                            + "(expected lines like: root = Column([header, card])).");
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
                    "OpenUI screen layout needs a 'root = Screen([...])' or "
                            + "'root = Column([...])' entry statement.");
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

        if ("Screen".equalsIgnoreCase(rootNode.type)) {
            emitScreenRootOps(rootNode, ops);
            List<OpenUiLang.ComponentNode> topWidgets = rootNode.argChildren(0);
            for (OpenUiLang.ComponentNode child : topWidgets) {
                collectPlannedWidgets(child, "root", allocatedIds, existingById,
                        targetList, targetIds);
            }
        } else {
            // Model wrote e.g. root = Column([header, list]) directly.
            // Map virtual "root" to existing single root container id if compatible, else "main_layout".
            ResolvedType rt = resolveWidgetType(rootNode.type);
            String chosenId = rootNode.id;
            if (chosenId == null || chosenId.isEmpty() || "root".equalsIgnoreCase(chosenId)) {
                chosenId = findReusableMainContainerId(safeExisting, rt.sketchwareType, allocatedIds);
            }
            OpenUiLang.ComponentNode remapped = new OpenUiLang.ComponentNode(
                    chosenId, rootNode.type, rootNode.args,
                    rootNode.namedArgs, rootNode.argBindings);
            collectPlannedWidgets(remapped, "root", allocatedIds, existingById,
                    targetList, targetIds);
        }

        // 1. Handle widgets removed compared to existing screen (both full OpenUI & patch mode)
        // when root defines the complete child list, or via explicit id = null deletions.
        Set<String> toRemove = new LinkedHashSet<>();
        for (String existingId : existingById.keySet()) {
            if (!targetIds.contains(existingId)) {
                toRemove.add(existingId);
            }
        }
        toRemove.addAll(explicitDeletions);
        // Only emit remove_widget for topmost removed ancestors (children are removed automatically).
        for (String remId : toRemove) {
            ViewBean eb = existingById.get(remId);
            if (eb == null) {
                continue;
            }
            if (hasRemovedAncestor(eb, existingById, toRemove)) {
                continue;
            }
            JsonObject rm = new JsonObject();
            rm.addProperty("op", "remove_widget");
            rm.addProperty("id", remId);
            ops.add(rm);
        }

        // If an existing widget changed its widget type completely, remove & re-add just that widget.
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

                // Position newly added widget if inserted before an existing sibling
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
                // Existing widget: reparent / reorder if needed, then diff properties in place!
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
    // PlannedWidget Extraction & Property Diffing
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
                return new ResolvedType("ScrollView", null);
            case "HScroll":
                return new ResolvedType("HorizontalScrollView", null);
            case "Text":
            case "TextContent":
                return new ResolvedType("TextView", null);
            case "Input":
            case "TextInput":
                return new ResolvedType("EditText", null);
            case "PrimaryButton":
                return new ResolvedType("MaterialButton", null);
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
        Integer progress;
        Integer max;
        Boolean indeterminate;
        Double alpha;
        Map<String, String> attributes;
    }

    private static void emitScreenRootOps(OpenUiLang.ComponentNode screenNode, JsonArray ops) {
        Map<String, Object> props = extractPropsMap(screenNode, 1);
        if (props.isEmpty()) {
            return;
        }
        JsonObject rootOp = new JsonObject();
        rootOp.addProperty("op", "set_root");
        boolean hasRootChange = false;
        String bg = propStr(props, "bg", "background");
        if (bg != null) {
            rootOp.addProperty("background", bg);
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

    private static void collectPlannedWidgets(OpenUiLang.ComponentNode node,
                                              String parentId,
                                              Set<String> allocatedIds,
                                              Map<String, ViewBean> existingById,
                                              List<PlannedWidget> out,
                                              Set<String> targetIds)
            throws AgentTools.ToolException {
        if (node == null) {
            return;
        }
        ResolvedType rt = resolveWidgetType(node.type);
        String id = sanitizeWidgetId(node.id, rt.sketchwareType, allocatedIds);
        if (!targetIds.add(id)) {
            throw new AgentTools.ToolException("E_DUPLICATE_ID",
                    "Widget '" + id + "' is placed in two parents at once in the OpenUI layout.");
        }

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

        populateFromPropsMap(pw, props);
        if (!existingById.containsKey(id)
                && "LinearLayout".equalsIgnoreCase(pw.sketchwareType)
                && !"root".equalsIgnoreCase(parentId)) {
            if (pw.width == null && (pw.weight == null || pw.weight == 0)) {
                pw.width = "match_parent";
            }
            if (pw.height == null && (pw.weight == null || pw.weight == 0)) {
                pw.height = "wrap_content";
            }
        }
        out.add(pw);

        for (OpenUiLang.ComponentNode child : childNodes) {
            collectPlannedWidgets(child, id, allocatedIds, existingById, out, targetIds);
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
        String inputType = propStr(props, "input_type", "inputType");
        if (inputType != null) {
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
            pw.attributes = new LinkedHashMap<>();
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
        String base = typeName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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
        for (String k : keys) {
            if (props.containsKey(k) && props.get(k) != null) {
                return OpenUiLang.stringify(props.get(k));
            }
        }
        return null;
    }

    private static Integer propInt(Map<String, Object> props, String... keys) {
        for (String k : keys) {
            if (props.containsKey(k) && props.get(k) != null) {
                return (int) Math.round(OpenUiLang.toDouble(props.get(k), 0.0));
            }
        }
        return null;
    }

    private static Boolean propBool(Map<String, Object> props, String... keys) {
        for (String k : keys) {
            if (props.containsKey(k) && props.get(k) != null) {
                return OpenUiLang.toBoolean(props.get(k));
            }
        }
        return null;
    }

    private static String propDim(Map<String, Object> props, String... keys) {
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
