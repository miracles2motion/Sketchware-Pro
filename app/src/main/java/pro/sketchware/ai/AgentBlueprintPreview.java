package pro.sketchware.ai;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Turns a blueprint's {@code SCREEN:} sections into REAL rendered
 * Android views — so "approve the blueprint" means LOOKING at your
 * app before it is built, not parsing a text wall. Every widget keeps
 * its blueprint id as its visible text, because the names ARE the
 * contract the wiring agent will use. Colours come from the saved
 * design system (the OpenUI token sheet: brand colour + theme mode),
 * so the drawing previews in the app's own palette.
 */
public final class AgentBlueprintPreview {

    /** One parsed screen: its name plus its raw blueprint lines. */
    public static final class ScreenDraft {
        public final String name;
        public final List<String> lines = new ArrayList<>();

        ScreenDraft(String name) {
            this.name = name;
        }
    }

    /** Widget lines show as drawn; beyond this the card says "+N". */
    private static final int MAX_WIDGETS_SHOWN = 9;

    private AgentBlueprintPreview() {
    }

    /** Parses {@code SCREEN:} blocks in order; empty when none. */
    public static List<ScreenDraft> parse(String blueprint) {
        List<ScreenDraft> out = new ArrayList<>();
        if (blueprint == null) {
            return out;
        }
        ScreenDraft current = null;
        for (String raw : blueprint.split("\\n")) {
            String line = raw.trim();
            if (line.startsWith("SCREEN:")) {
                String name = line.substring("SCREEN:".length())
                        .trim();
                current = new ScreenDraft(
                        name.isEmpty() ? "Screen" : name);
                out.add(current);
                continue;
            }
            if (line.startsWith("RULES:")
                    || line.startsWith("VARIABLES:")
                    || line.startsWith("STORAGE:")
                    || line.startsWith("WIRING:")) {
                current = null;
                continue;
            }
            if (current != null && !line.isEmpty()) {
                current.lines.add(line);
            }
        }
        return out;
    }

    /**
     * Renders one screen draft as a real view (~300dp wide). Never
     * throws: any surprise becomes a plain caption line, because a
     * missing picture must never block an approval.
     */
    public static View renderScreen(Context ctx, ScreenDraft draft,
                                    JsonObject designSystem) {
        float d = ctx.getResources().getDisplayMetrics().density;
        boolean dark = designSystem != null && "Dark".equalsIgnoreCase(
                str(designSystem, "theme_mode", ""));
        int accent = hex(str(designSystem, "brand_color", ""),
                themeColor(ctx, com.google.android.material.R.attr
                        .colorPrimary, 0xFF008DCD));
        int surface = dark ? 0xFF15171B : 0xFFFFFFFF;
        int ink = dark ? 0xFFECEFF4 : 0xFF1B1F24;
        int faint = dark ? 0xFF9AA3AD : 0xFF6B7280;
        int border = dark ? 0xFF2A2E35 : 0xFFE3E6EA;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(round(14, d), round(12, d), round(14, d),
                round(14, d));
        card.setBackground(rounded(surface, 16, border));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                round(300, d), ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.rightMargin = round(12, d);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(ctx);
        title.setText(draft.name);
        title.setTextColor(ink);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(16);
        card.addView(title);
        View bar = new View(ctx);
        bar.setBackground(rounded(accent, 2, 0));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                round(44, d), round(3, d));
        barLp.topMargin = round(6, d);
        barLp.bottomMargin = round(10, d);
        bar.setLayoutParams(barLp);
        card.addView(bar);

        int shown = Math.min(draft.lines.size(), MAX_WIDGETS_SHOWN);
        for (int i = 0; i < shown; i++) {
            View widget = widgetFor(ctx, draft.lines.get(i), accent,
                    ink, faint, border, d);
            card.addView(widget);
        }
        if (draft.lines.size() > shown) {
            TextView more = new TextView(ctx);
            more.setText("+" + (draft.lines.size() - shown)
                    + " more widgets (in the full blueprint)");
            more.setTextColor(faint);
            more.setTextSize(11);
            more.setPadding(0, round(6, d), 0, 0);
            card.addView(more);
        }
        return card;
    }

    /** The blueprint minus its SCREEN blocks (rules + wiring text). */
    public static String withoutScreens(String blueprint) {
        StringBuilder out = new StringBuilder();
        boolean inScreen = false;
        for (String raw : String.valueOf(blueprint).split("\\n")) {
            String line = raw.trim();
            if (line.startsWith("SCREEN:")) {
                inScreen = true;
                continue;
            }
            if (line.startsWith("RULES:")
                    || line.startsWith("VARIABLES:")
                    || line.startsWith("STORAGE:")
                    || line.startsWith("WIRING:")) {
                inScreen = false;
            }
            if (!inScreen && !line.isEmpty()) {
                out.append(line).append('\n');
            }
        }
        String text = out.toString().trim();
        return text.isEmpty()
                ? "No extra rules — the screens say it all." : text;
    }

    // ---- one blueprint line -> one real view ---------------------------

    private static final Pattern WIDGET_LINE = Pattern.compile(
            "^-\\s*([A-Za-z][A-Za-z0-9_]*)\\s*\\(\\s*([A-Za-z]+)\\s*\\)");

    private static View widgetFor(Context ctx, String line, int accent,
            int ink, int faint, int border, float d) {
        Matcher m = WIDGET_LINE.matcher(line);
        String id = null;
        String type = "";
        if (m.find()) {
            id = m.group(1);
            type = m.group(2).toLowerCase(java.util.Locale.US);
        }
        if (id == null) {
            return caption(ctx, line, faint, d);
        }
        View out;
        switch (type) {
            case "button":
                out = button(ctx, id, accent, d);
                break;
            case "edittext":
            case "textfield":
            case "input":
                out = input(ctx, id, ink, faint, border, d);
                break;
            case "checkbox":
                CheckBox check = new CheckBox(ctx);
                check.setText(id);
                check.setTextColor(ink);
                out = check;
                break;
            case "switch":
            case "switchcompat":
                Switch sw = new Switch(ctx);
                sw.setText(id);
                sw.setTextColor(ink);
                out = sw;
                break;
            case "progressbar":
                ProgressBar bar = new ProgressBar(ctx, null,
                        android.R.attr.progressBarStyleHorizontal);
                bar.setProgress(60);
                out = wrap(bar, round(44, d));
                break;
            case "imageview":
            case "image":
                out = placeholderBox(ctx, id, "img", faint, border, d,
                        round(56, d));
                break;
            case "recyclerview":
            case "listview":
            case "gridview":
                out = listPlaceholder(ctx, id, ink, faint, border, d);
                break;
            case "cardview":
            case "linearlayout":
            case "constraintlayout":
            case "relativelayout":
                out = placeholderBox(ctx, id, type, faint, border, d,
                        round(64, d));
                break;
            default:
                TextView text = new TextView(ctx);
                text.setText(id);
                text.setTextColor(ink);
                text.setTextSize(14);
                out = text;
                break;
        }
        return pad(out, d);
    }

    private static View button(Context ctx, String id, int accent,
                               float d) {
        Button button = new Button(ctx);
        button.setText(id);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(accent));
        button.setTextColor(Color.WHITE);
        button.setPadding(round(16, d), round(8, d), round(16, d),
                round(8, d));
        return button;
    }

    private static View input(Context ctx, String id, int ink,
                              int faint, int border, float d) {
        EditText input = new EditText(ctx);
        input.setHint(id);
        input.setEnabled(false);
        input.setTextColor(ink);
        input.setHintTextColor(faint);
        input.setTextSize(14);
        input.setBackground(rounded(
                withAlpha(border, 0x30), 10, border));
        input.setPadding(round(12, d), round(10, d), round(12, d),
                round(10, d));
        return input;
    }

    private static View listPlaceholder(Context ctx, String id,
            int ink, int faint, int border, float d) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(Color.TRANSPARENT, 10, border));
        box.setPadding(round(8, d), round(8, d), round(8, d),
                round(8, d));
        TextView label = new TextView(ctx);
        label.setText(id + " (list)");
        label.setTextColor(faint);
        label.setTextSize(11);
        box.addView(label);
        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(withAlpha(faint, 0x22), 8, 0));
            row.setPadding(round(8, d), round(7, d), round(8, d),
                    round(7, d));
            LinearLayout.LayoutParams rowLp =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = round(6, d);
            row.setLayoutParams(rowLp);
            View dot = new View(ctx);
            dot.setBackground(rounded(withAlpha(faint, 0x55), 20, 0));
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    round(18, d), round(18, d));
            dotLp.rightMargin = round(8, d);
            dot.setLayoutParams(dotLp);
            row.addView(dot);
            View bars = new View(ctx);
            bars.setBackground(rounded(withAlpha(faint, 0x33), 4, 0));
            LinearLayout.LayoutParams barsLp =
                    new LinearLayout.LayoutParams(0, round(10, d), 1f);
            bars.setLayoutParams(barsLp);
            row.addView(bars);
            box.addView(row);
        }
        return box;
    }

    private static View placeholderBox(Context ctx, String id,
            String label, int faint, int border, float d, int heightDp) {
        FrameLayout box = new FrameLayout(ctx);
        box.setBackground(rounded(withAlpha(faint, 0x14), 10, border));
        TextView text = new TextView(ctx);
        text.setText(label + " · " + id);
        text.setTextColor(faint);
        text.setTextSize(11);
        text.setGravity(Gravity.CENTER);
        box.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightDp);
        lp.topMargin = round(8, d);
        box.setLayoutParams(lp);
        return box;
    }

    private static View wrap(View child, int heightPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
        child.setLayoutParams(lp);
        return child;
    }

    private static View pad(View child, float d) {
        Object lp = child.getLayoutParams();
        if (!(lp instanceof LinearLayout.LayoutParams)) {
            LinearLayout.LayoutParams fresh =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            child.setLayoutParams(fresh);
            lp = fresh;
        }
        ((LinearLayout.LayoutParams) lp).topMargin = round(9, d);
        return child;
    }

    private static View caption(Context ctx, String text, int faint,
                                float d) {
        TextView view = new TextView(ctx);
        view.setText(text);
        view.setTextColor(faint);
        view.setTextSize(11);
        view.setPadding(0, round(6, d), 0, 0);
        return view;
    }

    // ---- small helpers --------------------------------------------------

    private static int themeColor(Context ctx, int attr, int fallback) {
        // Same technique as the fragment's resolveThemeColor: no
        // MaterialColors version dependency, resolves any theme attr.
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            if (ctx != null && ctx.getTheme().resolveAttribute(attr, tv,
                    true)) {
                if (tv.resourceId != 0) {
                    try {
                        return androidx.core.content.ContextCompat
                                .getColor(ctx, tv.resourceId);
                    } catch (Exception ignored) {
                    }
                }
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int hex(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String clean = value.trim();
        if (!clean.startsWith("#")) {
            clean = "#" + clean;
        }
        try {
            return Color.parseColor(clean);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static GradientDrawable rounded(int fill, float radiusDp,
                                            int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radiusDp);
        if (stroke != 0) {
            drawable.setStroke(1, stroke);
        }
        return drawable;
    }

    private static GradientDrawable rounded(int fill, int radiusDp,
                                            int stroke) {
        return rounded(fill, (float) radiusDp, stroke);
    }

    private static int round(int dp, float d) {
        return Math.round(dp * d);
    }

    private static String str(JsonObject object, String key,
                              String fallback) {
        try {
            if (object != null && object.has(key)
                    && object.get(key).isJsonPrimitive()) {
                return object.get(key).getAsString();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
