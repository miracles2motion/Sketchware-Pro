package pro.sketchware.ai;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.noties.markwon.Markwon;
import pro.sketchware.R;

/**
 * Renders OpenUI Lang 1.0 {@code inlineMode} blocks directly inside assistant
 * chat bubbles using real native Material 3 Android views.
 *
 * <p>Supports progressive top-down rendering as lines stream in, forward-reference
 * skeletons ({@link OpenUiLang.PendingRef}), local two-way {@code $variable}
 * reactive controls (zero LLM calls per tap), visual Design System / style cards,
 * KPI/table/breakdown summaries, and {@code @ToAssistant} action buttons.
 */
public final class OpenUiChatRenderer {

    private OpenUiChatRenderer() {
    }

    private static final Set<String> ROOT_COMPONENTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "Stack", "Column", "VStack", "Row", "HStack", "Card", "Form",
                    "StyleCard", "PaletteRow", "KpiRow", "Table")));

    /** Callbacks back into {@link ChatAdapter} / {@code AgentFragment}. */
    public interface Host {
        void onStateChanged(ChatMessage message);

        void onSendToAssistant(ChatMessage message, String promptText,
                               JsonElement context, JsonObject formState);

        void onOpenUrl(String url);

        void onProseClick(View anchor, ChatMessage message);
    }

    /** Cached render state attached to an {@code openui_container} view to avoid streaming jitter. */
    private static final class RenderCache {
        ChatMessage boundMessage;
        String lastStructureKey = "";
        final Map<Integer, OpenUiLang.RuntimeState> runtimes = new LinkedHashMap<>();
        final Map<Integer, LinearLayout> blockHosts = new LinkedHashMap<>();
        final Map<Integer, TextView> proseViews = new LinkedHashMap<>();
    }

    /**
     * Renders an assistant message containing one or more OpenUI Lang blocks
     * (interleaved with optional Markdown prose) into {@code container}.
     */
    public static void render(LinearLayout container,
                              ChatMessage message,
                              boolean streaming,
                              Markwon markwon,
                              Host host) {
        if (container == null || message == null) {
            return;
        }
        List<OpenUiLang.Segment> segments = OpenUiLang.splitSegments(message.text);
        if (segments.isEmpty()) {
            container.removeAllViews();
            container.setTag(null);
            return;
        }

        Object rawTag = container.getTag();
        RenderCache cache = (rawTag instanceof RenderCache) ? (RenderCache) rawTag : null;
        if (cache == null || cache.boundMessage != message) {
            cache = new RenderCache();
            cache.boundMessage = message;
            container.setTag(cache);
            container.removeAllViews();
        }

        String structureKey = computeStructureKey(segments, streaming);
        if (structureKey.equals(cache.lastStructureKey) && container.getChildCount() > 0) {
            // Update only trailing streaming prose if needed so active controls never flicker.
            for (int i = 0; i < segments.size(); i++) {
                OpenUiLang.Segment seg = segments.get(i);
                if (seg.kind == OpenUiLang.Segment.Kind.MARKDOWN) {
                    TextView tv = cache.proseViews.get(i);
                    if (tv != null) {
                        bindProseText(tv, seg.content, streaming, markwon);
                    }
                }
            }
            return;
        }
        cache.lastStructureKey = structureKey;

        Context ctx = container.getContext();
        container.removeAllViews();
        cache.proseViews.clear();
        cache.blockHosts.clear();

        for (int i = 0; i < segments.size(); i++) {
            final int segIndex = i;
            OpenUiLang.Segment seg = segments.get(i);
            if (seg.kind == OpenUiLang.Segment.Kind.MARKDOWN) {
                TextView tv = new TextView(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    lp.topMargin = dp(ctx, 8);
                }
                tv.setLayoutParams(lp);
                tv.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_BodyMedium);
                tv.setTextColor(colorOnSurfaceVariant(ctx));
                tv.setTextIsSelectable(!streaming);
                bindProseText(tv, seg.content, streaming, markwon);
                if (host != null) {
                    tv.setOnClickListener(v -> host.onProseClick(v, message));
                }
                container.addView(tv);
                cache.proseViews.put(i, tv);
                continue;
            }

            // OPENUI segment
            boolean segStreaming = streaming || seg.streaming;
            OpenUiLang.Program program = OpenUiLang.parse(seg.content, segStreaming);
            JsonObject initialVars = extractPersistedSlice(message.openUiState, segIndex, seg.storedContext);
            OpenUiLang.RuntimeState runtime = cache.runtimes.get(segIndex);
            if (runtime == null) {
                runtime = new OpenUiLang.RuntimeState(program, initialVars);
                cache.runtimes.put(segIndex, runtime);
            } else {
                runtime.bindProgram(program, null);
            }

            LinearLayout blockHost = new LinearLayout(ctx);
            blockHost.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                hostLp.topMargin = dp(ctx, 8);
            }
            blockHost.setLayoutParams(hostLp);
            container.addView(blockHost);
            cache.blockHosts.put(segIndex, blockHost);

            final OpenUiLang.RuntimeState boundRuntime = runtime;
            final RenderCache finalCache = cache;
            boundRuntime.setOnStateChangeListener(() -> {
                saveSliceToMessage(message, finalCache.runtimes);
                if (host != null) {
                    host.onStateChanged(message);
                }
                renderProgramInto(blockHost, boundRuntime, message, segStreaming, markwon, host, finalCache.runtimes);
            });

            renderProgramInto(blockHost, boundRuntime, message, segStreaming, markwon, host, cache.runtimes);
        }
    }

    /**
     * Signature of completed OpenUI lines + segment layout so streaming token deltas
     * only rebuild views when a statement line updates, not on every single prose character.
     */
    private static String computeStructureKey(List<OpenUiLang.Segment> segments, boolean streaming) {
        StringBuilder sb = new StringBuilder();
        sb.append(streaming ? "S:" : "C:").append(segments.size());
        for (OpenUiLang.Segment seg : segments) {
            sb.append('|').append(seg.kind.name()).append(':');
            if (seg.kind == OpenUiLang.Segment.Kind.OPENUI) {
                // Re-evaluate OpenUI on newline boundaries (or every ~24 chars on active line)
                int lastNl = seg.content.lastIndexOf('\n');
                int tailBucket = (seg.content.length() - Math.max(0, lastNl)) / 24;
                sb.append(lastNl).append(':').append(tailBucket).append(':')
                        .append(seg.streaming ? '1' : '0');
                if (!streaming && !seg.streaming) {
                    sb.append(':').append(seg.content.hashCode());
                }
            } else {
                sb.append(seg.content.isEmpty() ? 0 : 1);
                if (!streaming) {
                    sb.append(':').append(seg.content.hashCode());
                }
            }
        }
        return sb.toString();
    }

    private static void bindProseText(TextView tv, String markdown,
                                      boolean streaming, Markwon markwon) {
        if (streaming || markwon == null) {
            tv.setText(markdown);
        } else {
            markwon.setMarkdown(tv, markdown);
        }
    }

    private static JsonObject extractPersistedSlice(JsonObject msgState,
                                                    int segIndex,
                                                    JsonObject storedCtx) {
        JsonObject out = new JsonObject();
        if (storedCtx != null && storedCtx.has("formState")
                && storedCtx.get("formState").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e
                    : storedCtx.getAsJsonObject("formState").entrySet()) {
                out.add(e.getKey(), e.getValue());
            }
        }
        if (msgState != null) {
            String key = "seg_" + segIndex;
            if (msgState.has(key) && msgState.get(key).isJsonObject()) {
                for (Map.Entry<String, JsonElement> e
                        : msgState.getAsJsonObject(key).entrySet()) {
                    out.add(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    private static void saveSliceToMessage(ChatMessage message,
                                           Map<Integer, OpenUiLang.RuntimeState> runtimes) {
        JsonObject all = new JsonObject();
        for (Map.Entry<Integer, OpenUiLang.RuntimeState> e : runtimes.entrySet()) {
            JsonObject slice = e.getValue().toJson();
            if (slice.size() > 0) {
                all.add("seg_" + e.getKey(), slice);
            }
        }
        message.openUiState = all.size() > 0 ? all : null;
    }

    private static JsonObject mergedFormState(Map<Integer, OpenUiLang.RuntimeState> runtimes) {
        JsonObject flat = new JsonObject();
        for (OpenUiLang.RuntimeState rt : runtimes.values()) {
            JsonObject s = rt.toJson();
            for (Map.Entry<String, JsonElement> e : s.entrySet()) {
                flat.add(e.getKey(), e.getValue());
            }
        }
        return flat;
    }

    private static void renderProgramInto(LinearLayout blockHost,
                                          OpenUiLang.RuntimeState runtime,
                                          ChatMessage message,
                                          boolean streaming,
                                          Markwon markwon,
                                          Host host,
                                          Map<Integer, OpenUiLang.RuntimeState> allRuntimes) {
        blockHost.removeAllViews();
        Context ctx = blockHost.getContext();
        OpenUiLang.ComponentNode root = runtime.evaluateRoot(ROOT_COMPONENTS);
        if (root == null) {
            if (streaming) {
                blockHost.addView(buildSkeletonView(ctx, "Building interface…"));
            }
            return;
        }
        OpenUiLang.ActionHost actionHost = new OpenUiLang.ActionHost() {
            @Override
            public void onToAssistant(String promptText, JsonElement context, JsonObject localState) {
                saveSliceToMessage(message, allRuntimes);
                if (host != null) {
                    host.onStateChanged(message);
                    host.onSendToAssistant(message, promptText, context,
                            mergedFormState(allRuntimes));
                }
            }

            @Override
            public void onOpenUrl(String url) {
                if (host != null) {
                    host.onOpenUrl(url);
                }
            }
        };

        View rootView = buildNodeView(ctx, root, runtime, message, streaming,
                markwon, host, actionHost, allRuntimes);
        if (rootView != null) {
            blockHost.addView(rootView);
        }
    }

    /** Checks whether any expression outside a single direct binding reads {@code $varName}. */
    private static boolean hasDependentExpressions(OpenUiLang.Program program, String varName) {
        if (program == null || varName == null || varName.isEmpty()) {
            return false;
        }
        int totalUses = 0;
        for (OpenUiLang.Statement st : program.statements.values()) {
            if (st.expr == null) {
                continue;
            }
            Set<String> refs = new HashSet<>();
            Set<String> stateRefs = new HashSet<>();
            st.expr.collectReferences(refs, stateRefs);
            if (stateRefs.contains(varName)) {
                totalUses++;
                if (!(st.expr instanceof OpenUiLang.ComponentExpr)) {
                    return true;
                }
                // Check if used inside binary/ternary/nested expression on this component
                for (OpenUiLang.Expr arg : ((OpenUiLang.ComponentExpr) st.expr).args) {
                    if (arg != null && !(arg instanceof OpenUiLang.StateRefExpr)) {
                        Set<String> subState = new HashSet<>();
                        arg.collectReferences(new HashSet<>(), subState);
                        if (subState.contains(varName)) {
                            return true;
                        }
                    }
                }
            }
        }
        return totalUses > 1;
    }

    // ---------------------------------------------------------------------
    // Component Dispatch
    // ---------------------------------------------------------------------

    private static View buildNodeView(Context ctx,
                                      Object item,
                                      OpenUiLang.RuntimeState runtime,
                                      ChatMessage message,
                                      boolean streaming,
                                      Markwon markwon,
                                      Host host,
                                      OpenUiLang.ActionHost actionHost,
                                      Map<Integer, OpenUiLang.RuntimeState> allRuntimes) {
        if (item == null) {
            return null;
        }
        if (item instanceof OpenUiLang.PendingRef) {
            return streaming ? buildSkeletonView(ctx, ((OpenUiLang.PendingRef) item).name) : null;
        }
        if (!(item instanceof OpenUiLang.ComponentNode)) {
            String str = OpenUiLang.stringify(item).trim();
            if (str.isEmpty()) {
                return null;
            }
            return buildTextView(ctx, str, "body", streaming, markwon);
        }

        OpenUiLang.ComponentNode node = (OpenUiLang.ComponentNode) item;
        String type = node.type;
        switch (type) {
            case "Stack":
            case "Column":
            case "VStack":
            case "Form":
            case "ListBlock":
                return buildStack(ctx, node, runtime, message, streaming, markwon, host, actionHost, allRuntimes);
            case "Row":
            case "HStack":
            case "Buttons":
                return buildRow(ctx, node, runtime, message, streaming, markwon, host, actionHost, allRuntimes);
            case "Card":
                return buildCard(ctx, node, runtime, message, streaming, markwon, host, actionHost, allRuntimes);
            case "Header":
            case "CardHeader":
            case "InlineHeader":
                return buildHeader(ctx, node);
            case "Heading":
            case "Title":
            case "BoldText":
                return buildTextView(ctx,
                        node.namedOrString("text", 0, ""),
                        "heading", streaming, markwon);
            case "Caption":
            case "Label":
                return buildTextView(ctx,
                        node.namedOrString("text", 0, ""),
                        "caption", streaming, markwon);
            case "Subtitle":
            case "Body":
            case "Text":
            case "TextContent":
                return buildTextView(ctx,
                        node.namedOrString("text", 0, ""),
                        node.namedOrString("variant", 1,
                                node.namedOrString("size", 1, "body")),
                        streaming, markwon);
            case "Callout":
            case "TextCallout":
                return buildHeaderViews(ctx,
                        node.namedOrString("title", 0, ""),
                        node.namedOrString("description", 1, ""),
                        node.namedOrString("variant", 2, ""));
            case "Badge":
            case "Tag":
                return buildBadge(ctx,
                        node.namedOrString("label", 0, ""),
                        node.namedOrString("color", 1,
                                node.namedOrString("variant", 1, "")));
            case "Divider":
            case "Separator":
                return buildDivider(ctx);
            case "StyleCard":
                return buildStyleCard(ctx, node, runtime, actionHost);
            case "PaletteRow":
                return buildPaletteRow(ctx, node);
            case "ColorSwatch":
                return buildColorSwatch(ctx,
                        node.namedOrString("label", 0, ""),
                        node.namedOrString("hex", 1, "#008DCD"));
            case "TypePreview":
                return buildTypePreview(ctx, node);
            case "TextInput":
            case "Input":
            case "TextArea":
                return buildTextInput(ctx, node, runtime, message, host, allRuntimes);
            case "Select":
                return buildSelect(ctx, node, runtime);
            case "ChoiceChips":
            case "Chips":
            case "SegmentedControl":
                return buildChoiceChips(ctx, node, runtime);
            case "Switch":
            case "SwitchItem":
                return buildSwitch(ctx, node, runtime, message, host, allRuntimes);
            case "Slider":
                return buildSlider(ctx, node, runtime, message, host, allRuntimes);
            case "KpiRow":
                return buildKpiRow(ctx, node);
            case "Kpi":
            case "Stat":
                return buildKpiTile(ctx,
                        node.namedOrString("label", 0, ""),
                        node.namedOrString("value", 1, ""),
                        node.namedOrString("delta", 2, ""));
            case "Table":
                return buildTable(ctx, node);
            case "ProgressBar":
                return buildProgressRow(ctx, node);
            case "BreakdownBar":
                return buildBreakdownBar(ctx, node);
            case "Button":
                return buildButton(ctx, node, runtime, actionHost);
            default: {
                // Graceful fallback: if an unknown component wraps children, render them in a Stack;
                // otherwise render its first string prop cleanly so nothing ever blanks out.
                List<Object> childItems = node.argChildItems(0);
                if (!childItems.isEmpty()) {
                    return buildStack(ctx, node, runtime, message, streaming,
                            markwon, host, actionHost, allRuntimes);
                }
                String fallbackText = node.argString(0, "");
                if (!fallbackText.isEmpty()) {
                    return buildTextView(ctx, fallbackText, "body", streaming, markwon);
                }
                return null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Layout & Typography Builders
    // ---------------------------------------------------------------------

    private static View buildStack(Context ctx,
                                   OpenUiLang.ComponentNode node,
                                   OpenUiLang.RuntimeState runtime,
                                   ChatMessage message,
                                   boolean streaming,
                                   Markwon markwon,
                                   Host host,
                                   OpenUiLang.ActionHost actionHost,
                                   Map<Integer, OpenUiLang.RuntimeState> allRuntimes) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int gapPx = parseSpaceDp(ctx, node.namedOrPositional("gap", 1), 8);
        int padPx = parseSpaceDp(ctx, node.namedOrPositional("padding", 2), 0);
        if (padPx > 0) {
            layout.setPadding(padPx, padPx, padPx, padPx);
        }

        List<Object> items = node.argChildItems(0);
        int added = 0;
        for (Object child : items) {
            View v = buildNodeView(ctx, child, runtime, message, streaming,
                    markwon, host, actionHost, allRuntimes);
            if (v == null) {
                continue;
            }
            LinearLayout.LayoutParams lp = ensureLinearParams(v,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (added > 0) {
                lp.topMargin = gapPx;
            }
            v.setLayoutParams(lp);
            layout.addView(v);
            added++;
        }
        return layout;
    }

    private static View buildRow(Context ctx,
                                 OpenUiLang.ComponentNode node,
                                 OpenUiLang.RuntimeState runtime,
                                 ChatMessage message,
                                 boolean streaming,
                                 Markwon markwon,
                                 Host host,
                                 OpenUiLang.ActionHost actionHost,
                                 Map<Integer, OpenUiLang.RuntimeState> allRuntimes) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String align = node.namedOrString("align", 2, "center").toLowerCase(Locale.US);
        row.setGravity("top".equals(align) ? Gravity.TOP
                : "bottom".equals(align) ? Gravity.BOTTOM
                : Gravity.CENTER_VERTICAL);

        int gapPx = parseSpaceDp(ctx, node.namedOrPositional("gap", 1), 8);
        List<Object> items = node.argChildItems(0);
        int added = 0;
        boolean wrapScroll = items.size() > 3;
        for (Object child : items) {
            View v = buildNodeView(ctx, child, runtime, message, streaming,
                    markwon, host, actionHost, allRuntimes);
            if (v == null) {
                continue;
            }
            LinearLayout.LayoutParams lp;
            if (!wrapScroll && (v instanceof MaterialCardView
                    || (child instanceof OpenUiLang.ComponentNode
                            && ("Kpi".equals(((OpenUiLang.ComponentNode) child).type)
                                    || "Stat".equals(((OpenUiLang.ComponentNode) child).type))))) {
                lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            } else {
                lp = ensureLinearParams(v,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            if (added > 0) {
                lp.leftMargin = gapPx;
            }
            v.setLayoutParams(lp);
            row.addView(v);
            added++;
        }

        if (wrapScroll) {
            HorizontalScrollView hsv = new HorizontalScrollView(ctx);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            hsv.addView(row);
            return hsv;
        }
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static View buildCard(Context ctx,
                                  OpenUiLang.ComponentNode node,
                                  OpenUiLang.RuntimeState runtime,
                                  ChatMessage message,
                                  boolean streaming,
                                  Markwon markwon,
                                  Host host,
                                  OpenUiLang.ActionHost actionHost,
                                  Map<Integer, OpenUiLang.RuntimeState> allRuntimes) {
        MaterialCardView card = new MaterialCardView(ctx);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setRadius(dp(ctx, 14));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(colorSurface(ctx));
        int accent = parseHexColor(node.namedOrString("accentColor", 3, ""), 0);
        card.setStrokeWidth(dp(ctx, 1));
        card.setStrokeColor(accent != 0 ? withAlpha(accent, 110) : colorOutlineVariant(ctx));

        LinearLayout outer = new LinearLayout(ctx);
        outer.setOrientation(LinearLayout.VERTICAL);

        if (accent != 0) {
            View bar = new View(ctx);
            bar.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4)));
            bar.setBackgroundColor(accent);
            outer.addView(bar);
        }

        LinearLayout inner = new LinearLayout(ctx);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 12);
        inner.setPadding(pad, pad, pad, pad);

        String title = node.namedOrString("title", 1, "");
        String subtitle = node.namedOrString("subtitle", 2, "");
        if (!title.isEmpty() || !subtitle.isEmpty()) {
            View hdr = buildHeaderViews(ctx, title, subtitle, "");
            LinearLayout.LayoutParams hdrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hdrLp.bottomMargin = dp(ctx, 8);
            hdr.setLayoutParams(hdrLp);
            inner.addView(hdr);
        }

        List<Object> items = node.argChildItems(0);
        int added = 0;
        for (Object child : items) {
            View v = buildNodeView(ctx, child, runtime, message, streaming,
                    markwon, host, actionHost, allRuntimes);
            if (v == null) {
                continue;
            }
            LinearLayout.LayoutParams lp = ensureLinearParams(v,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (added > 0) {
                lp.topMargin = dp(ctx, 8);
            }
            v.setLayoutParams(lp);
            inner.addView(v);
            added++;
        }

        outer.addView(inner);
        card.addView(outer);
        return card;
    }

    private static View buildHeader(Context ctx, OpenUiLang.ComponentNode node) {
        return buildHeaderViews(ctx,
                node.namedOrString("title", 0, ""),
                node.namedOrString("subtitle", 1, ""),
                node.namedOrString("badge", 2, ""));
    }

    private static View buildHeaderViews(Context ctx, String title,
                                         String subtitle, String badge) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(ctx);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleTv.setText(title);
        titleTv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        titleTv.setTypeface(titleTv.getTypeface(), Typeface.BOLD);
        titleTv.setTextColor(colorOnSurface(ctx));
        topRow.addView(titleTv);

        if (!badge.isEmpty()) {
            View badgeView = buildBadge(ctx, badge, "");
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(ctx, 8);
            badgeView.setLayoutParams(blp);
            topRow.addView(badgeView);
        }
        box.addView(topRow);

        if (!subtitle.isEmpty()) {
            TextView subTv = new TextView(ctx);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(ctx, 2);
            subTv.setLayoutParams(slp);
            subTv.setText(subtitle);
            subTv.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodySmall);
            subTv.setTextColor(colorOnSurfaceVariant(ctx));
            box.addView(subTv);
        }
        return box;
    }

    private static View buildTextView(Context ctx, String content,
                                      String variant, boolean streaming,
                                      Markwon markwon) {
        TextView tv = new TextView(ctx);
        String v = variant == null ? "body" : variant.toLowerCase(Locale.US);
        switch (v) {
            case "title":
            case "h1":
            case "h2":
                tv.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_TitleMedium);
                tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
                tv.setTextColor(colorOnSurface(ctx));
                tv.setText(content);
                return tv;
            case "subtitle":
            case "h3":
                tv.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_TitleSmall);
                tv.setTextColor(colorOnSurface(ctx));
                tv.setText(content);
                return tv;
            case "caption":
            case "muted":
            case "small":
                tv.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_BodySmall);
                tv.setTextColor(colorOnSurfaceVariant(ctx));
                tv.setText(content);
                return tv;
            case "code":
            case "mono": {
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextColor(colorOnSurface(ctx));
                int p = dp(ctx, 8);
                tv.setPadding(p, p / 2, p, p / 2);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(ctx, 8));
                bg.setColor(withAlpha(colorOnSurface(ctx), 18));
                tv.setBackground(bg);
                tv.setText(content);
                return tv;
            }
            default:
                tv.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_BodyMedium);
                tv.setTextColor(colorOnSurface(ctx));
                bindProseText(tv, content, streaming, markwon);
                return tv;
        }
    }

    private static View buildBadge(Context ctx, String label, String hexColor) {
        TextView pill = new TextView(ctx);
        pill.setText(label);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        pill.setTypeface(pill.getTypeface(), Typeface.BOLD);
        int baseColor = parseHexColor(hexColor, colorPrimary(ctx));
        pill.setTextColor(baseColor);
        int ph = dp(ctx, 9);
        int pv = dp(ctx, 3);
        pill.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 999));
        bg.setColor(withAlpha(baseColor, 34));
        bg.setStroke(dp(ctx, 1), withAlpha(baseColor, 80));
        pill.setBackground(bg);
        return pill;
    }

    private static View buildDivider(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)));
        v.setBackgroundColor(colorOutlineVariant(ctx));
        return v;
    }

    // ---------------------------------------------------------------------
    // Anti-Gravity Visual Design & Style Brainstorming Components
    // ---------------------------------------------------------------------

    /**
     * {@code StyleCard(name, tagline, colors, mood?, action?)}
     * Renders a rich visual aesthetic card with live palette chips, surface/button
     * preview sample, and an optional action button (e.g. {@code @ToAssistant(...)}).
     */
    private static View buildStyleCard(Context ctx,
                                       OpenUiLang.ComponentNode node,
                                       OpenUiLang.RuntimeState runtime,
                                       OpenUiLang.ActionHost actionHost) {
        String name = node.namedOrString("name", 0, "Style Option");
        String tagline = node.namedOrString("tagline", 1, "");
        Object rawColors = node.namedOrPositional("colors", 2);
        String mood = node.namedOrString("mood", 3, "");
        OpenUiLang.ActionValue action = node.argAction(4);
        if (action == null && node.arg(3) instanceof OpenUiLang.ActionValue) {
            action = (OpenUiLang.ActionValue) node.arg(3);
            mood = "";
        }

        List<String[]> swatches = extractSwatches(rawColors);
        int primaryHex = swatches.size() > 0
                ? parseHexColor(swatches.get(0)[1], colorPrimary(ctx))
                : colorPrimary(ctx);
        int bgHex = swatches.size() > 1
                ? parseHexColor(swatches.get(1)[1], colorSurface(ctx))
                : colorSurface(ctx);

        MaterialCardView card = new MaterialCardView(ctx);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setRadius(dp(ctx, 16));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(colorSurface(ctx));
        card.setStrokeWidth(dp(ctx, 1));
        card.setStrokeColor(withAlpha(primaryHex, 120));

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        View topAccent = new View(ctx);
        topAccent.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 5)));
        topAccent.setBackgroundColor(primaryHex);
        root.addView(topAccent);

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 12);
        body.setPadding(pad, pad, pad, pad);

        View header = buildHeaderViews(ctx, name, tagline, mood);
        body.addView(header);

        // Mini live surface + CTA specimen using the style's own palette
        LinearLayout specimen = new LinearLayout(ctx);
        specimen.setOrientation(LinearLayout.HORIZONTAL);
        specimen.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams specLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        specLp.topMargin = dp(ctx, 10);
        specimen.setLayoutParams(specLp);
        int spPad = dp(ctx, 10);
        specimen.setPadding(spPad, spPad, spPad, spPad);
        GradientDrawable specBg = new GradientDrawable();
        specBg.setCornerRadius(dp(ctx, 12));
        specBg.setColor(bgHex);
        specBg.setStroke(dp(ctx, 1), withAlpha(contrastOn(bgHex), 36));
        specimen.setBackground(specBg);

        TextView specLabel = new TextView(ctx);
        specLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        specLabel.setText("Preview surface");
        specLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        specLabel.setTypeface(specLabel.getTypeface(), Typeface.BOLD);
        specLabel.setTextColor(contrastOn(bgHex));
        specimen.addView(specLabel);

        TextView specBtn = new TextView(ctx);
        specBtn.setText("Action");
        specBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        specBtn.setTypeface(specBtn.getTypeface(), Typeface.BOLD);
        specBtn.setTextColor(contrastOn(primaryHex));
        specBtn.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        GradientDrawable btnShape = new GradientDrawable();
        btnShape.setCornerRadius(dp(ctx, 999));
        btnShape.setColor(primaryHex);
        specBtn.setBackground(btnShape);
        specimen.addView(specBtn);
        body.addView(specimen);

        if (!swatches.isEmpty()) {
            HorizontalScrollView hsv = new HorizontalScrollView(ctx);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hsvLp.topMargin = dp(ctx, 10);
            hsv.setLayoutParams(hsvLp);

            LinearLayout swatchRow = new LinearLayout(ctx);
            swatchRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < swatches.size(); i++) {
                String[] sw = swatches.get(i);
                View chip = buildColorSwatch(ctx, sw[0], sw[1]);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    clp.leftMargin = dp(ctx, 6);
                }
                chip.setLayoutParams(clp);
                swatchRow.addView(chip);
            }
            hsv.addView(swatchRow);
            body.addView(hsv);
        }

        if (action != null) {
            final OpenUiLang.ActionValue finalAction = action;
            MaterialButton chooseBtn = new MaterialButton(ctx);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = dp(ctx, 10);
            chooseBtn.setLayoutParams(btnLp);
            chooseBtn.setAllCaps(false);
            chooseBtn.setCornerRadius(dp(ctx, 10));
            chooseBtn.setBackgroundTintList(ColorStateList.valueOf(primaryHex));
            chooseBtn.setTextColor(contrastOn(primaryHex));
            chooseBtn.setText("Choose " + name);
            chooseBtn.setOnClickListener(v -> runtime.executeAction(finalAction, actionHost));
            body.addView(chooseBtn);
        }

        root.addView(body);
        card.addView(root);
        return card;
    }

    private static View buildPaletteRow(Context ctx, OpenUiLang.ComponentNode node) {
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        List<String[]> swatches = extractSwatches(node.arg(0));
        for (int i = 0; i < swatches.size(); i++) {
            String[] sw = swatches.get(i);
            View v = buildColorSwatch(ctx, sw[0], sw[1]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.leftMargin = dp(ctx, 8);
            }
            v.setLayoutParams(lp);
            row.addView(v);
        }
        hsv.addView(row);
        return hsv;
    }

    private static View buildColorSwatch(Context ctx, String label, String hex) {
        String normalizedHex = (hex == null || hex.trim().isEmpty()) ? "#008DCD" : hex.trim();
        int parsed = parseHexColor(normalizedHex, colorPrimary(ctx));

        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(ctx, 7);
        tile.setPadding(p, dp(ctx, 5), dp(ctx, 9), dp(ctx, 5));
        GradientDrawable tileBg = new GradientDrawable();
        tileBg.setCornerRadius(dp(ctx, 10));
        tileBg.setColor(withAlpha(colorOnSurface(ctx), 12));
        tileBg.setStroke(dp(ctx, 1), colorOutlineVariant(ctx));
        tile.setBackground(tileBg);

        View dot = new View(ctx);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        dot.setLayoutParams(dotLp);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setCornerRadius(dp(ctx, 6));
        dotShape.setColor(parsed);
        dotShape.setStroke(dp(ctx, 1), withAlpha(colorOnSurface(ctx), 45));
        dot.setBackground(dotShape);
        tile.addView(dot);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.leftMargin = dp(ctx, 7);
        textCol.setLayoutParams(colLp);

        if (label != null && !label.isEmpty()) {
            TextView lbl = new TextView(ctx);
            lbl.setText(label);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            lbl.setTypeface(lbl.getTypeface(), Typeface.BOLD);
            lbl.setTextColor(colorOnSurface(ctx));
            textCol.addView(lbl);
        }

        TextView hexTv = new TextView(ctx);
        hexTv.setText(normalizedHex.toUpperCase(Locale.US));
        hexTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        hexTv.setTypeface(Typeface.MONOSPACE);
        hexTv.setTextColor(colorOnSurfaceVariant(ctx));
        textCol.addView(hexTv);
        tile.addView(textCol);

        tile.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Color", normalizedHex));
                Toast.makeText(ctx, "Copied " + normalizedHex, Toast.LENGTH_SHORT).show();
            }
        });
        return tile;
    }

    private static View buildTypePreview(Context ctx, OpenUiLang.ComponentNode node) {
        String sample = node.namedOrString("sampleText", 0, "Aa Bb Cc 123");
        float sp = (float) node.argDouble(1, 18.0);
        String weight = node.namedOrString("weight", 2, "bold").toLowerCase(Locale.US);
        int customColor = parseHexColor(node.namedOrString("color", 3, ""), colorOnSurface(ctx));

        TextView tv = new TextView(ctx);
        tv.setText(sample);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(10f, Math.min(34f, sp)));
        tv.setTextColor(customColor);
        if (weight.contains("mono")) {
            tv.setTypeface(Typeface.MONOSPACE,
                    weight.contains("bold") ? Typeface.BOLD : Typeface.NORMAL);
        } else if (weight.contains("bold") || weight.contains("semi")) {
            tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        } else if (weight.contains("italic")) {
            tv.setTypeface(tv.getTypeface(), Typeface.ITALIC);
        }
        return tv;
    }

    // ---------------------------------------------------------------------
    // Two-Way $variable Interactive Controls (Zero LLM Tokens on Tap)
    // ---------------------------------------------------------------------

    private static View buildTextInput(Context ctx,
                                       OpenUiLang.ComponentNode node,
                                       OpenUiLang.RuntimeState runtime,
                                       ChatMessage message,
                                       Host host,
                                       Map<Integer, OpenUiLang.RuntimeState> allRuntimes) {
        String label = node.namedOrString("label", 0, "");
        String currentVal = node.argString(1, "");
        String placeholder = node.namedOrString("placeholder", 2, "");
        final String boundVar = node.boundVar(1);

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);

        if (!label.isEmpty()) {
            TextView lbl = new TextView(ctx);
            lbl.setText(label);
            lbl.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_LabelMedium);
            lbl.setTextColor(colorOnSurfaceVariant(ctx));
            lbl.setPadding(dp(ctx, 2), 0, 0, dp(ctx, 4));
            wrap.addView(lbl);
        }

        EditText edit = new EditText(ctx);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setSingleLine(true);
        edit.setText(currentVal);
        edit.setHint(placeholder);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        edit.setTextColor(colorOnSurface(ctx));
        edit.setHintTextColor(withAlpha(colorOnSurfaceVariant(ctx), 150));
        int ph = dp(ctx, 12);
        int pv = dp(ctx, 10);
        edit.setPadding(ph, pv, ph, pv);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(dp(ctx, 10));
        box.setColor(colorSurface(ctx));
        box.setStroke(dp(ctx, 1), colorOutlineVariant(ctx));
        edit.setBackground(box);

        if (boundVar != null) {
            final boolean shouldRerender = hasDependentExpressions(runtime.getProgram(), boundVar);
            edit.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    String next = s == null ? "" : s.toString();
                    runtime.setVar(boundVar, next, shouldRerender);
                    if (!shouldRerender) {
                        saveSliceToMessage(message, allRuntimes);
                        if (host != null) {
                            host.onStateChanged(message);
                        }
                    }
                }
            });
        }
        wrap.addView(edit);
        return wrap;
    }

    private static View buildSelect(Context ctx,
                                    OpenUiLang.ComponentNode node,
                                    OpenUiLang.RuntimeState runtime) {
        String label = node.namedOrString("label", 0, "");
        String currentVal = node.argString(1, "");
        List<String[]> options = extractOptions(node.arg(2));
        final String boundVar = node.boundVar(1);

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);

        if (!label.isEmpty()) {
            TextView lbl = new TextView(ctx);
            lbl.setText(label);
            lbl.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_LabelMedium);
            lbl.setTextColor(colorOnSurfaceVariant(ctx));
            lbl.setPadding(dp(ctx, 2), 0, 0, dp(ctx, 4));
            wrap.addView(lbl);
        }

        String displayLabel = currentVal;
        for (String[] opt : options) {
            if (opt[0].equals(currentVal)) {
                displayLabel = opt[1];
                break;
            }
        }
        if (displayLabel.isEmpty() && !options.isEmpty()) {
            displayLabel = options.get(0)[1];
        }

        LinearLayout selector = new LinearLayout(ctx);
        selector.setOrientation(LinearLayout.HORIZONTAL);
        selector.setGravity(Gravity.CENTER_VERTICAL);
        selector.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 10));
        bg.setColor(colorSurface(ctx));
        bg.setStroke(dp(ctx, 1), colorOutlineVariant(ctx));
        selector.setBackground(bg);

        TextView valTv = new TextView(ctx);
        valTv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        valTv.setText(displayLabel);
        valTv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        valTv.setTextColor(colorOnSurface(ctx));
        selector.addView(valTv);

        TextView chevron = new TextView(ctx);
        chevron.setText("▾");
        chevron.setTextColor(colorOnSurfaceVariant(ctx));
        selector.addView(chevron);

        if (!options.isEmpty()) {
            selector.setOnClickListener(v -> {
                String[] titles = new String[options.size()];
                int checked = -1;
                String active = boundVar != null
                        ? OpenUiLang.stringify(runtime.getVar(boundVar)) : currentVal;
                for (int i = 0; i < options.size(); i++) {
                    titles[i] = options.get(i)[1];
                    if (options.get(i)[0].equals(active)) {
                        checked = i;
                    }
                }
                new MaterialAlertDialogBuilder(ctx)
                        .setTitle(label.isEmpty() ? "Choose an option" : label)
                        .setSingleChoiceItems(titles, checked, (dialog, which) -> {
                            dialog.dismiss();
                            String pickedValue = options.get(which)[0];
                            valTv.setText(options.get(which)[1]);
                            if (boundVar != null) {
                                runtime.setVar(boundVar, pickedValue, true);
                            }
                        })
                        .show();
            });
        }
        wrap.addView(selector);
        return wrap;
    }

    private static View buildChoiceChips(Context ctx,
                                         OpenUiLang.ComponentNode node,
                                         OpenUiLang.RuntimeState runtime) {
        String label = node.namedOrString("label", 0, "");
        Object rawValue = node.arg(1);
        List<String[]> options = extractOptions(node.arg(2));
        boolean multi = node.argBoolean(3, rawValue instanceof List<?>);
        final String boundVar = node.boundVar(1);

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);

        if (!label.isEmpty()) {
            TextView lbl = new TextView(ctx);
            lbl.setText(label);
            lbl.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_LabelMedium);
            lbl.setTextColor(colorOnSurfaceVariant(ctx));
            lbl.setPadding(dp(ctx, 2), 0, 0, dp(ctx, 4));
            wrap.addView(lbl);
        }

        Set<String> selectedSet = new HashSet<>();
        if (rawValue instanceof List<?>) {
            for (Object item : (List<?>) rawValue) {
                selectedSet.add(OpenUiLang.stringify(item));
            }
        } else if (rawValue != null) {
            selectedSet.add(OpenUiLang.stringify(rawValue));
        }

        ChipGroup group = new ChipGroup(ctx);
        group.setSingleSelection(!multi);
        group.setSelectionRequired(!multi);

        for (String[] opt : options) {
            final String optVal = opt[0];
            final String optLabel = opt[1];
            Chip chip = new Chip(ctx);
            chip.setText(optLabel);
            chip.setCheckable(true);
            chip.setChecked(selectedSet.contains(optVal));
            chip.setOnClickListener(v -> {
                if (boundVar == null) {
                    return;
                }
                if (multi) {
                    Object cur = runtime.getVar(boundVar);
                    List<Object> nextList = new ArrayList<>();
                    if (cur instanceof List<?>) {
                        for (Object x : (List<?>) cur) {
                            String sx = OpenUiLang.stringify(x);
                            if (!sx.equals(optVal)) {
                                nextList.add(sx);
                            }
                        }
                    }
                    if (chip.isChecked()) {
                        nextList.add(optVal);
                    }
                    runtime.setVar(boundVar, nextList, true);
                } else {
                    runtime.setVar(boundVar, optVal, true);
                }
            });
            group.addView(chip);
        }
        wrap.addView(group);
        return wrap;
    }

    private static View buildSwitch(Context ctx,
                                    OpenUiLang.ComponentNode node,
                                    OpenUiLang.RuntimeState runtime,
                                    ChatMessage message,
                                    Host host,
                                    Map<Integer, OpenUiLang.RuntimeState> allRuntimes) {
        String label = node.namedOrString("label", 0, "");
        boolean checked = node.argBoolean(1, false);
        String subtitle = node.namedOrString("subtitle", 2, "");
        final String boundVar = node.boundVar(1);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleTv = new TextView(ctx);
        titleTv.setText(label);
        titleTv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        titleTv.setTextColor(colorOnSurface(ctx));
        texts.addView(titleTv);

        if (!subtitle.isEmpty()) {
            TextView subTv = new TextView(ctx);
            subTv.setText(subtitle);
            subTv.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodySmall);
            subTv.setTextColor(colorOnSurfaceVariant(ctx));
            texts.addView(subTv);
        }
        row.addView(texts);

        MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setChecked(checked);
        if (boundVar != null) {
            final boolean shouldRerender = hasDependentExpressions(runtime.getProgram(), boundVar);
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                runtime.setVar(boundVar, isChecked, shouldRerender);
                if (!shouldRerender) {
                    saveSliceToMessage(message, allRuntimes);
                    if (host != null) {
                        host.onStateChanged(message);
                    }
                }
            });
        }
        row.addView(sw);
        return row;
    }

    private static View buildSlider(Context ctx,
                                    OpenUiLang.ComponentNode node,
                                    OpenUiLang.RuntimeState runtime,
                                    ChatMessage message,
                                    Host host,
                                    Map<Integer, OpenUiLang.RuntimeState> allRuntimes) {
        String label = node.namedOrString("label", 0, "");
        final double min = node.argDouble(2, 0.0);
        final double max = Math.max(min + 1.0, node.argDouble(3, 100.0));
        final double step = Math.max(0.1, node.argDouble(4, 1.0));
        double cur = Math.max(min, Math.min(max, node.argDouble(1, min)));
        final String boundVar = node.boundVar(1);

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView lbl = new TextView(ctx);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lbl.setText(label);
        lbl.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_LabelMedium);
        lbl.setTextColor(colorOnSurfaceVariant(ctx));
        top.addView(lbl);

        final TextView readout = new TextView(ctx);
        readout.setText(OpenUiLang.stringify(cur));
        readout.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_LabelMedium);
        readout.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        readout.setTextColor(colorPrimary(ctx));
        top.addView(readout);
        wrap.addView(top);

        final int totalSteps = Math.max(1, (int) Math.round((max - min) / step));
        SeekBar seekBar = new SeekBar(ctx);
        seekBar.setMax(totalSteps);
        seekBar.setProgress((int) Math.round((cur - min) / step));
        if (boundVar != null) {
            final boolean shouldRerender = hasDependentExpressions(runtime.getProgram(), boundVar);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    double nextVal = min + (progress * step);
                    if (step == Math.rint(step) && min == Math.rint(min)) {
                        nextVal = Math.rint(nextVal);
                    } else {
                        nextVal = Math.round(nextVal * 100.0) / 100.0;
                    }
                    readout.setText(OpenUiLang.stringify(nextVal));
                    if (fromUser) {
                        runtime.setVar(boundVar, nextVal, false);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar sb) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar sb) {
                    if (shouldRerender) {
                        runtime.setVar(boundVar, runtime.getVar(boundVar), true);
                    } else {
                        saveSliceToMessage(message, allRuntimes);
                        if (host != null) {
                            host.onStateChanged(message);
                        }
                    }
                }
            });
        }
        wrap.addView(seekBar);
        return wrap;
    }

    // ---------------------------------------------------------------------
    // Data, KPI, Table & Action Builders
    // ---------------------------------------------------------------------

    private static View buildKpiRow(Context ctx, OpenUiLang.ComponentNode node) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Object raw = node.arg(0);
        List<View> tiles = new ArrayList<>();
        if (raw instanceof List<?>) {
            for (Object item : (List<?>) raw) {
                if (item instanceof OpenUiLang.ComponentNode) {
                    OpenUiLang.ComponentNode cn = (OpenUiLang.ComponentNode) item;
                    tiles.add(buildKpiTile(ctx,
                            cn.namedOrString("label", 0, ""),
                            cn.namedOrString("value", 1, ""),
                            cn.namedOrString("delta", 2, "")));
                } else if (item instanceof Map<?, ?>) {
                    Map<?, ?> m = (Map<?, ?>) item;
                    tiles.add(buildKpiTile(ctx,
                            OpenUiLang.stringify(m.get("label")),
                            OpenUiLang.stringify(m.get("value")),
                            OpenUiLang.stringify(m.get("delta"))));
                }
            }
        }
        for (int i = 0; i < tiles.size(); i++) {
            View t = tiles.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.leftMargin = dp(ctx, 8);
            }
            t.setLayoutParams(lp);
            row.addView(t);
        }
        return row;
    }

    private static View buildKpiTile(Context ctx, String label, String value, String delta) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(ctx, 10);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 12));
        bg.setColor(colorSurface(ctx));
        bg.setStroke(dp(ctx, 1), colorOutlineVariant(ctx));
        box.setBackground(bg);

        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        lbl.setTextColor(colorOnSurfaceVariant(ctx));
        box.addView(lbl);

        TextView val = new TextView(ctx);
        val.setText(value);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        val.setTypeface(val.getTypeface(), Typeface.BOLD);
        val.setTextColor(colorOnSurface(ctx));
        box.addView(val);

        if (delta != null && !delta.isEmpty()) {
            TextView d = new TextView(ctx);
            d.setText(delta);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            boolean neg = delta.trim().startsWith("-");
            d.setTextColor(neg ? 0xFFB05A52 : 0xFF3E8E5A);
            box.addView(d);
        }
        return box;
    }

    private static View buildTable(Context ctx, OpenUiLang.ComponentNode node) {
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        Object first = node.arg(0);
        Object second = node.arg(1);

        if (first instanceof List<?> && !((List<?>) first).isEmpty()
                && ((List<?>) first).get(0) instanceof OpenUiLang.ComponentNode) {
            // OpenUI Col("Header", valuesArray) style
            List<List<String>> colData = new ArrayList<>();
            int maxRows = 0;
            for (Object cObj : (List<?>) first) {
                if (!(cObj instanceof OpenUiLang.ComponentNode)) {
                    continue;
                }
                OpenUiLang.ComponentNode col = (OpenUiLang.ComponentNode) cObj;
                headers.add(col.argString(0, ""));
                List<String> vals = new ArrayList<>();
                Object rawVals = col.arg(1);
                if (rawVals instanceof List<?>) {
                    for (Object v : (List<?>) rawVals) {
                        vals.add(OpenUiLang.stringify(v));
                    }
                } else if (rawVals != null) {
                    vals.add(OpenUiLang.stringify(rawVals));
                }
                maxRows = Math.max(maxRows, vals.size());
                colData.add(vals);
            }
            for (int r = 0; r < maxRows; r++) {
                List<String> row = new ArrayList<>(colData.size());
                for (List<String> col : colData) {
                    row.add(r < col.size() ? col.get(r) : "");
                }
                rows.add(row);
            }
        } else if (first instanceof List<?>) {
            for (Object h : (List<?>) first) {
                headers.add(OpenUiLang.stringify(h));
            }
            if (second instanceof List<?>) {
                for (Object rObj : (List<?>) second) {
                    if (rObj instanceof List<?>) {
                        List<String> row = new ArrayList<>();
                        for (Object cell : (List<?>) rObj) {
                            row.add(OpenUiLang.stringify(cell));
                        }
                        rows.add(row);
                    }
                }
            }
        }

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout table = new LinearLayout(ctx);
        table.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(dp(ctx, 10));
        frame.setColor(colorSurface(ctx));
        frame.setStroke(dp(ctx, 1), colorOutlineVariant(ctx));
        table.setBackground(frame);

        if (!headers.isEmpty()) {
            table.addView(buildTableRow(ctx, headers, true));
        }
        for (int r = 0; r < rows.size(); r++) {
            table.addView(buildDivider(ctx));
            table.addView(buildTableRow(ctx, rows.get(r), false));
        }
        hsv.addView(table);
        return hsv;
    }

    private static View buildTableRow(Context ctx, List<String> cells, boolean isHeader) {
        LinearLayout tr = new LinearLayout(ctx);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        if (isHeader) {
            tr.setBackgroundColor(withAlpha(colorPrimary(ctx), 18));
        }
        int ph = dp(ctx, 12);
        int pv = dp(ctx, 8);
        for (String cell : cells) {
            TextView tv = new TextView(ctx);
            tv.setMinimumWidth(dp(ctx, 88));
            tv.setMaxWidth(dp(ctx, 210));
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setMaxLines(2);
            tv.setPadding(ph, pv, ph, pv);
            tv.setText(cell);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, isHeader ? 12f : 12.5f);
            if (isHeader) {
                tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
                tv.setTextColor(colorOnSurface(ctx));
            } else {
                tv.setTextColor(colorOnSurfaceVariant(ctx));
            }
            tr.addView(tv);
        }
        return tr;
    }

    private static View buildProgressRow(Context ctx, OpenUiLang.ComponentNode node) {
        String label = node.namedOrString("label", 0, "");
        double val = node.argDouble(1, 0.0);
        double max = Math.max(1.0, node.argDouble(2, 100.0));
        int tint = parseHexColor(node.namedOrString("color", 3, ""), colorPrimary(ctx));
        int pct = (int) Math.round(Math.max(0.0, Math.min(100.0, (val / max) * 100.0)));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView lbl = new TextView(ctx);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lbl.setTextColor(colorOnSurface(ctx));
        top.addView(lbl);

        TextView right = new TextView(ctx);
        right.setText(pct + "%");
        right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        right.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        right.setTextColor(tint);
        top.addView(right);
        box.addView(top);

        ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 8));
        pblp.topMargin = dp(ctx, 4);
        pb.setLayoutParams(pblp);
        pb.setMax(100);
        pb.setProgress(pct);
        pb.setProgressTintList(ColorStateList.valueOf(tint));
        box.addView(pb);
        return box;
    }

    private static View buildBreakdownBar(Context ctx, OpenUiLang.ComponentNode node) {
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 10)));
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(dp(ctx, 999));
        clip.setColor(withAlpha(colorOnSurface(ctx), 18));
        bar.setBackground(clip);
        bar.setClipToOutline(true);

        LinearLayout legend = new LinearLayout(ctx);
        legend.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams legLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        legLp.topMargin = dp(ctx, 6);
        legend.setLayoutParams(legLp);

        int[] palette = {0xFF008DCD, 0xFF26A59A, 0xFFFFB055, 0xFF8A55D7, 0xFFFF5955};
        Object raw = node.arg(0);
        if (raw instanceof List<?>) {
            int idx = 0;
            for (Object item : (List<?>) raw) {
                String segLabel = "";
                float weight = 0f;
                int segColor = palette[idx % palette.length];
                if (item instanceof Map<?, ?>) {
                    Map<?, ?> m = (Map<?, ?>) item;
                    segLabel = OpenUiLang.stringify(m.get("label"));
                    weight = (float) OpenUiLang.toDouble(m.get("value"), 0.0);
                    segColor = parseHexColor(OpenUiLang.stringify(m.get("color")), segColor);
                } else if (item instanceof OpenUiLang.ComponentNode) {
                    OpenUiLang.ComponentNode cn = (OpenUiLang.ComponentNode) item;
                    segLabel = cn.argString(0, "");
                    weight = (float) cn.argDouble(1, 0.0);
                    segColor = parseHexColor(cn.argString(2, ""), segColor);
                }
                if (weight <= 0f) {
                    continue;
                }
                View slice = new View(ctx);
                slice.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
                slice.setBackgroundColor(segColor);
                bar.addView(slice);

                LinearLayout legRow = new LinearLayout(ctx);
                legRow.setOrientation(LinearLayout.HORIZONTAL);
                legRow.setGravity(Gravity.CENTER_VERTICAL);
                legRow.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
                View dot = new View(ctx);
                dot.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8)));
                GradientDrawable dg = new GradientDrawable();
                dg.setCornerRadius(dp(ctx, 4));
                dg.setColor(segColor);
                dot.setBackground(dg);
                legRow.addView(dot);

                TextView lt = new TextView(ctx);
                lt.setPadding(dp(ctx, 6), 0, 0, 0);
                lt.setText(segLabel + " (" + OpenUiLang.stringify((double) weight) + ")");
                lt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                lt.setTextColor(colorOnSurfaceVariant(ctx));
                legRow.addView(lt);
                legend.addView(legRow);
                idx++;
            }
        }
        wrap.addView(bar);
        wrap.addView(legend);
        return wrap;
    }

    private static View buildButton(Context ctx,
                                    OpenUiLang.ComponentNode node,
                                    OpenUiLang.RuntimeState runtime,
                                    OpenUiLang.ActionHost actionHost) {
        String label = node.namedOrString("label", 0, "Continue");
        final OpenUiLang.ActionValue action = node.argAction(1);
        String variant = node.namedOrString("variant", 2, "primary").toLowerCase(Locale.US);

        MaterialButton btn = new MaterialButton(ctx);
        btn.setAllCaps(false);
        btn.setCornerRadius(dp(ctx, 10));
        int primary = colorPrimary(ctx);
        if ("secondary".equals(variant) || "outline".equals(variant) || "outlined".equals(variant)) {
            btn.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            btn.setStrokeWidth(dp(ctx, 1));
            btn.setStrokeColor(ColorStateList.valueOf(withAlpha(primary, 130)));
            btn.setTextColor(primary);
            btn.setElevation(0f);
        } else if ("text".equals(variant) || "ghost".equals(variant)) {
            btn.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            btn.setStrokeWidth(0);
            btn.setTextColor(primary);
            btn.setElevation(0f);
        } else {
            btn.setBackgroundTintList(ColorStateList.valueOf(primary));
            btn.setTextColor(contrastOn(primary));
        }
        btn.setText(label);
        if (action != null) {
            btn.setOnClickListener(v -> runtime.executeAction(action, actionHost));
        }
        return btn;
    }

    private static View buildSkeletonView(Context ctx, String hint) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(ctx, 10);
        row.setPadding(p, dp(ctx, 7), p, dp(ctx, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 10));
        bg.setColor(withAlpha(colorOnSurface(ctx), 14));
        row.setBackground(bg);

        TextView tv = new TextView(ctx);
        tv.setText("⋯ " + hint);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(colorOnSurfaceVariant(ctx));
        row.addView(tv);
        return row;
    }

    // ---------------------------------------------------------------------
    // Option / Swatch Extraction & Color Helpers
    // ---------------------------------------------------------------------

    private static List<String[]> extractSwatches(Object raw) {
        List<String[]> list = new ArrayList<>();
        if (raw instanceof List<?>) {
            for (Object item : (List<?>) raw) {
                if (item instanceof OpenUiLang.ComponentNode) {
                    OpenUiLang.ComponentNode cn = (OpenUiLang.ComponentNode) item;
                    list.add(new String[]{
                            cn.namedOrString("label", 0, ""),
                            cn.namedOrString("hex", 1, "#008DCD")
                    });
                } else if (item instanceof Map<?, ?>) {
                    Map<?, ?> m = (Map<?, ?>) item;
                    Object lbl = m.containsKey("label") ? m.get("label") : m.get("name");
                    Object hex = m.containsKey("hex") ? m.get("hex") : m.get("color");
                    list.add(new String[]{
                            OpenUiLang.stringify(lbl),
                            OpenUiLang.stringify(hex)
                    });
                } else if (item instanceof String) {
                    String s = ((String) item).trim();
                    if (s.contains(":")) {
                        int idx = s.indexOf(':');
                        list.add(new String[]{s.substring(0, idx).trim(), s.substring(idx + 1).trim()});
                    } else {
                        list.add(new String[]{"", s});
                    }
                }
            }
        } else if (raw instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                list.add(new String[]{
                        String.valueOf(e.getKey()),
                        OpenUiLang.stringify(e.getValue())
                });
            }
        }
        return list;
    }

    private static List<String[]> extractOptions(Object raw) {
        List<String[]> out = new ArrayList<>();
        if (!(raw instanceof List<?>)) {
            return out;
        }
        for (Object item : (List<?>) raw) {
            if (item instanceof Map<?, ?>) {
                Map<?, ?> m = (Map<?, ?>) item;
                String val = OpenUiLang.stringify(
                        m.containsKey("value") ? m.get("value") : m.get("id"));
                String lbl = OpenUiLang.stringify(
                        m.containsKey("label") ? m.get("label") : m.get("title"));
                if (val.isEmpty()) {
                    val = lbl;
                }
                if (lbl.isEmpty()) {
                    lbl = val;
                }
                if (!val.isEmpty()) {
                    out.add(new String[]{val, lbl});
                }
            } else if (item instanceof OpenUiLang.ComponentNode) {
                OpenUiLang.ComponentNode cn = (OpenUiLang.ComponentNode) item;
                String v = cn.argString(0, "");
                String l = cn.argString(1, v);
                if (!v.isEmpty()) {
                    out.add(new String[]{v, l});
                }
            } else if (item != null) {
                String s = OpenUiLang.stringify(item);
                if (!s.isEmpty()) {
                    out.add(new String[]{s, s});
                }
            }
        }
        return out;
    }

    private static LinearLayout.LayoutParams ensureLinearParams(View v, int defaultW, int defaultH) {
        ViewGroup.LayoutParams existing = v.getLayoutParams();
        if (existing instanceof LinearLayout.LayoutParams) {
            return (LinearLayout.LayoutParams) existing;
        }
        if (existing != null) {
            return new LinearLayout.LayoutParams(existing.width, existing.height);
        }
        return new LinearLayout.LayoutParams(defaultW, defaultH);
    }

    private static int parseSpaceDp(Context ctx, Object val, int defaultDp) {
        if (val == null) {
            return dp(ctx, defaultDp);
        }
        if (val instanceof Number) {
            return dp(ctx, Math.max(0, Math.min(48, ((Number) val).intValue())));
        }
        String s = String.valueOf(val).trim().toLowerCase(Locale.US);
        switch (s) {
            case "none":
            case "0":
                return 0;
            case "xs":
                return dp(ctx, 4);
            case "s":
            case "sm":
            case "small":
                return dp(ctx, 6);
            case "m":
            case "md":
            case "medium":
                return dp(ctx, 10);
            case "l":
            case "lg":
            case "large":
                return dp(ctx, 14);
            case "xl":
                return dp(ctx, 20);
            default:
                try {
                    return dp(ctx, Integer.parseInt(s.replace("dp", "").trim()));
                } catch (NumberFormatException e) {
                    return dp(ctx, defaultDp);
                }
        }
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    private static int parseHexColor(String hex, int fallback) {
        if (hex == null) {
            return fallback;
        }
        String t = hex.trim();
        if (t.isEmpty()) {
            return fallback;
        }
        if (!t.startsWith("#") && (t.length() == 6 || t.length() == 8)) {
            t = "#" + t;
        }
        try {
            return Color.parseColor(t);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int contrastOn(int bg) {
        double lum = (0.299 * Color.red(bg)
                + 0.587 * Color.green(bg)
                + 0.114 * Color.blue(bg)) / 255.0;
        return lum > 0.58 ? 0xFF1A1C1E : 0xFFFFFFFF;
    }

    private static int colorPrimary(Context ctx) {
        return MaterialColors.getColor(ctx, R.attr.colorPrimary, 0xFF008DCD);
    }

    private static int colorSurface(Context ctx) {
        return MaterialColors.getColor(ctx, R.attr.colorSurface, 0xFFFFFFFF);
    }

    private static int colorOnSurface(Context ctx) {
        return MaterialColors.getColor(ctx, R.attr.colorOnSurface, 0xFF1A1C1E);
    }

    private static int colorOnSurfaceVariant(Context ctx) {
        return MaterialColors.getColor(ctx, R.attr.colorOnSurfaceVariant, 0xFF41474D);
    }

    private static int colorOutlineVariant(Context ctx) {
        return MaterialColors.getColor(ctx, R.attr.colorOutlineVariant, 0x33828282);
    }
}
