package pro.sketchware.ai;

import android.content.Context;

import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ProjectLibraryBean;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import a.a.a.jC;
import a.a.a.lC;
import dev.aldi.sayuti.editor.injection.AppCompatInjection;
import pro.sketchware.SketchApplication;
import pro.sketchware.managers.inject.InjectRootLayoutManager;
import pro.sketchware.utility.FileUtil;

/**
 * Customizable OpenUI Design System & Theme Engine for Sketchware Pro.
 *
 * <p>Implements the semantic token, scale, and contrast architecture of
 * {@code @openuidev/react-ui} ({@code defaultTheme.ts} + {@code openuiLibrary})
 * backed by {@code assets/openui/spec.json} and optional user JSON presets in
 * {@link AiPaths#designSystemsDir()}.
 *
 * <p>Every app chooses its own brand/accent palette, surface undertone, corner
 * profile, spacing density, fonts, and Light/Dark overrides while inheriting
 * mathematical WCAG contrast safety and real Android resources
 * ({@code values/colors.xml}, {@code values-night/colors.xml},
 * {@code values/styles.xml}, {@code color/openui_chip_text.xml}, and
 * {@code drawable/openui_*.xml}).
 */
public final class OpenUiThemeEngine {

    public static final String SPEC_ASSET_PATH = "openui/spec.json";
    private static final Gson PRETTY_GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile JsonObject cachedSpec;

    private OpenUiThemeEngine() {
    }

    /** Loads and caches {@code assets/openui/spec.json} (never null). */
    public static JsonObject spec() {
        JsonObject local = cachedSpec;
        if (local != null) {
            return local;
        }
        synchronized (OpenUiThemeEngine.class) {
            if (cachedSpec != null) {
                return cachedSpec;
            }
            try {
                Context ctx = SketchApplication.getContext();
                if (ctx != null && ctx.getAssets() != null) {
                    try (InputStream in = ctx.getAssets().open(SPEC_ASSET_PATH);
                         InputStreamReader reader =
                                 new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        JsonElement parsed = JsonParser.parseReader(reader);
                        if (parsed != null && parsed.isJsonObject()) {
                            cachedSpec = parsed.getAsJsonObject();
                            return cachedSpec;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            cachedSpec = fallbackSpec();
            return cachedSpec;
        }
    }

    // ------------------------------------------------------------------
    // WCAG 2.1 Color & Contrast Math
    // ------------------------------------------------------------------

    /** Parses {@code #RGB}, {@code #RRGGBB}, or {@code #AARRGGBB} into opaque ARGB, or null. */
    public static Integer parseHexColor(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (!s.startsWith("#")) {
            return null;
        }
        String h = s.substring(1);
        try {
            if (h.length() == 3) {
                h = "FF" + h.charAt(0) + h.charAt(0)
                        + h.charAt(1) + h.charAt(1)
                        + h.charAt(2) + h.charAt(2);
            } else if (h.length() == 4) {
                h = "" + h.charAt(0) + h.charAt(0)
                        + h.charAt(1) + h.charAt(1)
                        + h.charAt(2) + h.charAt(2)
                        + h.charAt(3) + h.charAt(3);
            } else if (h.length() == 6) {
                h = "FF" + h;
            } else if (h.length() != 8) {
                return null;
            }
            return (int) Long.parseLong(h, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String toHex6(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static double channelLuminance(int c8) {
        double s = (c8 & 0xFF) / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** WCAG relative luminance in [0.0, 1.0]. */
    public static double relativeLuminance(int rgb) {
        double r = channelLuminance((rgb >> 16) & 0xFF);
        double g = channelLuminance((rgb >> 8) & 0xFF);
        double b = channelLuminance(rgb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG 2.1 contrast ratio in [1.0, 21.0]. */
    public static double contrastRatio(int fg, int bg) {
        double l1 = relativeLuminance(fg);
        double l2 = relativeLuminance(bg);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    public static double contrastRatioHex(String fgHex, String bgHex) {
        Integer fg = parseHexColor(fgHex);
        Integer bg = parseHexColor(bgHex);
        if (fg == null || bg == null) {
            return 21.0;
        }
        return contrastRatio(fg, bg);
    }

    /** Blends {@code fg} onto {@code bg} with {@code ratio} in [0..1]. */
    public static int mix(int bg, int fg, double ratio) {
        double t = Math.max(0.0, Math.min(1.0, ratio));
        int r = (int) Math.round(((bg >> 16) & 0xFF) * (1.0 - t)
                + ((fg >> 16) & 0xFF) * t);
        int g = (int) Math.round(((bg >> 8) & 0xFF) * (1.0 - t)
                + ((fg >> 8) & 0xFF) * t);
        int b = (int) Math.round((bg & 0xFF) * (1.0 - t)
                + (fg & 0xFF) * t);
        return 0xFF000000
                | (clampByte(r) << 16)
                | (clampByte(g) << 8)
                | clampByte(b);
    }

    public static String mixHex(String bgHex, String fgHex, double ratio) {
        Integer bg = parseHexColor(bgHex);
        Integer fg = parseHexColor(fgHex);
        if (bg == null) return fgHex == null ? "#808080" : fgHex;
        if (fg == null) return bgHex;
        return toHex6(mix(bg, fg, ratio));
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Picks crisp white or near-black foreground text for {@code bgHex}. */
    public static String pickOnColorHex(String bgHex) {
        Integer bg = parseHexColor(bgHex);
        if (bg == null) {
            return "#FFFFFF";
        }
        int light = 0xFFFFFFFF;
        int dark = 0xFF0F1115;
        double cLight = contrastRatio(light, bg);
        double cDark = contrastRatio(dark, bg);
        if (cLight >= 4.2 && cLight >= cDark * 0.85) {
            return "#FFFFFF";
        }
        return cDark > cLight ? "#0F1115" : "#FFFFFF";
    }

    /**
     * Ensures {@code fgHex} reaches at least {@code minRatio} contrast against
     * {@code bgHex} by progressively mixing toward white (on dark surfaces) or
     * near-black (on light surfaces).
     */
    public static String ensureContrastHex(String fgHex, String bgHex,
                                           double minRatio) {
        Integer fg = parseHexColor(fgHex);
        Integer bg = parseHexColor(bgHex);
        if (fg == null || bg == null) {
            return fgHex == null ? "#FFFFFF" : fgHex;
        }
        if (contrastRatio(fg, bg) >= minRatio) {
            return toHex6(fg);
        }
        boolean bgIsDark = relativeLuminance(bg) < 0.28;
        int target = bgIsDark ? 0xFFFFFFFF : 0xFF090B0E;
        int best = fg;
        for (int step = 1; step <= 24; step++) {
            int candidate = mix(fg, target, step / 24.0);
            best = candidate;
            if (contrastRatio(candidate, bg) >= minRatio) {
                return toHex6(candidate);
            }
        }
        // If mixing toward the natural pole wasn't enough (mid-luminance bg),
        // pick whichever pole gives stronger contrast.
        int altPole = bgIsDark ? 0xFF090B0E : 0xFFFFFFFF;
        if (contrastRatio(altPole, bg) > contrastRatio(best, bg)) {
            for (int step = 1; step <= 24; step++) {
                int candidate = mix(fg, altPole, step / 24.0);
                if (contrastRatio(candidate, bg) >= minRatio) {
                    return toHex6(candidate);
                }
            }
            return toHex6(altPole);
        }
        return toHex6(best);
    }

    // ------------------------------------------------------------------
    // Resolved Theme Model
    // ------------------------------------------------------------------

    public static final class ResolvedTheme {
        public String title = "OpenUI Design System";
        public String brandColor = "#2563EB";
        public String secondaryColor = "#4F46E5";
        public String neutralScale = "slate";
        public String cornerProfile = "balanced";
        public String density = "comfortable";
        public String themeMode = "DayNight";
        public boolean dynamicColors = false;
        public String fontHeading = "";
        public String fontBody = "";
        public final LinkedHashMap<String, String> lightColors =
                new LinkedHashMap<>();
        public final LinkedHashMap<String, String> darkColors =
                new LinkedHashMap<>();
        public final LinkedHashMap<String, Integer> radiiDp =
                new LinkedHashMap<>();
        public final LinkedHashMap<String, Integer> metricsDp =
                new LinkedHashMap<>();
        public final List<String> contrastNotes = new ArrayList<>();
        /** Extensive-preset support: sections we don't model (type
         * scales, motion, materials, foundations sheets…) ride along
         * instead of being stripped on save. */
        public final JsonObject rawExtras = new JsonObject();
    }

    /**
     * Resolves a complete Light + Dark OpenUI theme from custom parameters,
     * a named preset, or an existing {@code designSystem} object.
     */
    public static ResolvedTheme resolveTheme(JsonObject input) {
        JsonObject root = input == null ? new JsonObject() : input;
        JsonObject themeNode = root.has("openuiTheme")
                && root.get("openuiTheme").isJsonObject()
                ? root.getAsJsonObject("openuiTheme")
                : root;

        // 0. Optional starter/user preset seed
        String presetName = str(themeNode, "preset");
        if (presetName.isEmpty()) {
            presetName = str(root, "preset");
        }
        if (!presetName.isEmpty()) {
            JsonObject preset = findStarterOrSavedPreset(presetName);
            if (preset != null) {
                JsonObject merged = preset.deepCopy();
                for (Map.Entry<String, JsonElement> e : themeNode.entrySet()) {
                    if (!"preset".equals(e.getKey())) {
                        merged.add(e.getKey(), e.getValue());
                    }
                }
                themeNode = merged;
            }
        }

        ResolvedTheme out = new ResolvedTheme();
        out.title = firstNonEmpty(
                str(root, "title"),
                str(themeNode, "title"),
                "Custom OpenUI Theme");

        // Infer brand colour from legacy tokens if not explicitly provided
        String inferredBrand = inferLegacyBrand(root);
        String rawBrand = firstNonEmpty(
                str(themeNode, "brand_color"),
                str(themeNode, "brandColor"),
                str(themeNode, "primary_color"),
                str(themeNode, "accent_color"),
                inferredBrand,
                "#2563EB");
        Integer parsedBrand = parseHexColor(rawBrand);
        out.brandColor = parsedBrand != null ? toHex6(parsedBrand) : "#2563EB";

        String rawSecondary = firstNonEmpty(
                str(themeNode, "secondary_color"),
                str(themeNode, "secondaryColor"),
                "");
        Integer parsedSecondary = parseHexColor(rawSecondary);
        out.secondaryColor = parsedSecondary != null
                ? toHex6(parsedSecondary)
                : mixHex(out.brandColor, "#6366F1", 0.25);

        out.neutralScale = normalizeNeutralScale(firstNonEmpty(
                str(themeNode, "neutral_scale"),
                str(themeNode, "neutralScale"),
                inferNeutralFromBrand(out.brandColor)));
        out.cornerProfile = normalizeCornerProfile(firstNonEmpty(
                str(themeNode, "corner_profile"),
                str(themeNode, "cornerProfile"),
                inferLegacyCornerProfile(root)));
        out.density = normalizeDensity(firstNonEmpty(
                str(themeNode, "density"),
                "comfortable"));
        out.themeMode = normalizeThemeMode(firstNonEmpty(
                str(themeNode, "theme_mode"),
                str(themeNode, "themeMode"),
                "DayNight"));
        out.dynamicColors = bool(themeNode, "dynamic_colors",
                bool(themeNode, "dynamicColors", false));
        out.fontHeading = sanitizeFontName(firstNonEmpty(
                str(themeNode, "font_heading"),
                str(themeNode, "fontHeading"),
                str(themeNode, "font")));
        out.fontBody = sanitizeFontName(firstNonEmpty(
                str(themeNode, "font_body"),
                str(themeNode, "fontBody"),
                out.fontHeading));

        // 1. Resolve radii & density metrics from spec.json
        JsonObject specObj = spec();
        JsonObject profObj = obj(obj(specObj, "corner_profiles"),
                out.cornerProfile);
        out.radiiDp.put("card", intVal(profObj, "card", 14));
        out.radiiDp.put("button", intVal(profObj, "button", 12));
        out.radiiDp.put("input", intVal(profObj, "input", 12));
        out.radiiDp.put("chip", intVal(profObj, "chip", 20));
        out.radiiDp.put("tag", intVal(profObj, "tag", 8));
        out.radiiDp.put("callout", intVal(profObj, "callout", 12));
        JsonObject customRadii = obj(themeNode, "radii_dp");
        if (customRadii.size() == 0) {
            customRadii = obj(themeNode, "radiiDp");
        }
        for (String key : new ArrayList<>(out.radiiDp.keySet())) {
            if (customRadii.has(key)) {
                out.radiiDp.put(key, clampInt(
                        intVal(customRadii, key, out.radiiDp.get(key)), 0, 999));
            }
        }
        if (themeNode.has("corner_radius")) {
            int r = clampInt(intVal(themeNode, "corner_radius", 14), 0, 999);
            out.radiiDp.put("card", r);
            out.radiiDp.put("button", r);
            out.radiiDp.put("input", Math.min(r, 20));
        }

        JsonObject densObj = obj(obj(specObj, "density_profiles"), out.density);
        out.metricsDp.put("screen_pad", intVal(densObj, "screen_pad", 18));
        out.metricsDp.put("section_gap", intVal(densObj, "section_gap", 16));
        out.metricsDp.put("card_pad", intVal(densObj, "card_pad", 16));
        out.metricsDp.put("button_height", intVal(densObj, "button_height", 52));
        out.metricsDp.put("input_height", intVal(densObj, "input_height", 52));
        out.metricsDp.put("chip_height", intVal(densObj, "chip_height", 38));
        out.metricsDp.put("row_height", intVal(densObj, "row_height", 68));
        JsonObject customMetrics = obj(themeNode, "metrics_dp");
        if (customMetrics.size() == 0) {
            customMetrics = obj(themeNode, "metricsDp");
        }
        for (String key : new ArrayList<>(out.metricsDp.keySet())) {
            if (customMetrics.has(key)) {
                out.metricsDp.put(key, clampInt(
                        intVal(customMetrics, key, out.metricsDp.get(key)),
                        4, 160));
            }
        }

        // 2. Build Light & Dark semantic colour sheets from neutral scale + brand
        JsonObject scale = obj(obj(specObj, "neutral_scales"), out.neutralScale);
        if (scale.size() == 0) {
            scale = obj(obj(specObj, "neutral_scales"), "slate");
        }
        buildModeColors(out.lightColors, false, out.brandColor,
                out.secondaryColor, scale,
                obj(obj(specObj, "semantic_status"), "light"));
        buildModeColors(out.darkColors, true, out.brandColor,
                out.secondaryColor, scale,
                obj(obj(specObj, "semantic_status"), "dark"));

        // 3. Apply explicit Light / Dark overrides with WCAG contrast protection
        applyColorOverrides(out.lightColors, false,
                firstObj(themeNode, "light_colors", "lightColors",
                        "light_overrides"),
                out.contrastNotes);
        applyColorOverrides(out.darkColors, true,
                firstObj(themeNode, "dark_colors", "darkColors",
                        "dark_overrides"),
                out.contrastNotes);

        // Shorthand top-level surface overrides (e.g. from screenshot inspection)
        String lightBg = firstNonEmpty(str(themeNode, "light_bg"),
                str(themeNode, "background_light"));
        if (parseHexColor(lightBg) != null) {
            JsonObject o = new JsonObject();
            o.addProperty("openui_bg", toHex6(parseHexColor(lightBg)));
            applyColorOverrides(out.lightColors, false, o, out.contrastNotes);
        }
        String darkBg = firstNonEmpty(str(themeNode, "dark_bg"),
                str(themeNode, "background_dark"));
        if (parseHexColor(darkBg) != null) {
            JsonObject o = new JsonObject();
            o.addProperty("openui_bg", toHex6(parseHexColor(darkBg)));
            applyColorOverrides(out.darkColors, true, o, out.contrastNotes);
        }
        String lightFg = firstNonEmpty(str(themeNode, "light_card"),
                str(themeNode, "surface_light"));
        if (parseHexColor(lightFg) != null) {
            JsonObject o = new JsonObject();
            o.addProperty("openui_fg", toHex6(parseHexColor(lightFg)));
            applyColorOverrides(out.lightColors, false, o, out.contrastNotes);
        }
        String darkFg = firstNonEmpty(str(themeNode, "dark_card"),
                str(themeNode, "surface_dark"));
        if (parseHexColor(darkFg) != null) {
            JsonObject o = new JsonObject();
            o.addProperty("openui_fg", toHex6(parseHexColor(darkFg)));
            applyColorOverrides(out.darkColors, true, o, out.contrastNotes);
        }

        enforceModeReadability(out.lightColors, false, out.contrastNotes);
        enforceModeReadability(out.darkColors, true, out.contrastNotes);
        // Keep everything the engine does not model: an extensive
        // preset (foundations sheets, motion, materials, type scales)
        // is stored with the system instead of being stripped.
        for (String consumed : new String[]{"title", "brand_color",
                "brandColor", "primary_color", "accent_color",
                "secondary_color", "secondaryColor", "neutral_scale",
                "corner_profile", "density", "theme_mode",
                "dynamic_colors", "font_heading", "font_body",
                "preset", "radii_dp", "metrics_dp", "light_colors",
                "dark_colors", "overrides", "light_overrides",
                "dark_overrides"}) {
            out.rawExtras.remove(consumed);
        }
        return out;
    }

    private static void buildModeColors(LinkedHashMap<String, String> target,
                                        boolean dark,
                                        String brandHex,
                                        String secondaryHex,
                                        JsonObject scale,
                                        JsonObject status) {
        String bg = dark
                ? shade(scale, "950", "#090D16")
                : shade(scale, "100", "#F3F4F6");
        String fg = dark
                ? shade(scale, "900", "#111827")
                : shade(scale, "25", "#FFFFFF");
        String sunk = dark
                ? mixHex(bg, shade(scale, "800", "#1F2937"), 0.55)
                : mixHex(fg, shade(scale, "200", "#E5E7EB"), 0.55);
        String elevated = dark
                ? shade(scale, "800", "#1F2937")
                : "#FFFFFF";
        String highlight = dark
                ? mixHex(fg, "#FFFFFF", 0.07)
                : mixHex(fg, "#000000", 0.04);

        // Ensure brand accent is crisp on dark surfaces while keeping hue
        String accent = dark
                ? ensureContrastHex(mixHex(brandHex, "#FFFFFF", 0.14), bg, 3.3)
                : ensureContrastHex(brandHex, "#FFFFFF", 3.0);
        // If brand was near-white in light mode, darken it; if near-black in dark mode, lighten it
        if (!dark && relativeLuminance(parseHexColor(accent)) > 0.72) {
            accent = ensureContrastHex(mixHex(brandHex, "#0F172A", 0.45),
                    fg, 3.2);
        }
        String onAccent = pickOnColorHex(accent);
        String accentPressed = dark
                ? mixHex(accent, "#FFFFFF", 0.15)
                : mixHex(accent, "#000000", 0.14);
        String accentSubtle = mixHex(fg, accent, dark ? 0.20 : 0.12);

        String sec = dark
                ? ensureContrastHex(mixHex(secondaryHex, "#FFFFFF", 0.16), bg, 3.1)
                : secondaryHex;
        String onSec = pickOnColorHex(sec);

        String textPrimary = dark
                ? shade(scale, "50", "#F8FAFC")
                : shade(scale, "950", "#0F172A");
        String textSecondary = dark
                ? shade(scale, "300", "#CBD5E1")
                : shade(scale, "600", "#475569");
        String textTertiary = dark
                ? shade(scale, "400", "#94A3B8")
                : shade(scale, "500", "#64748B");
        String textBrand = ensureContrastHex(accent, fg, 3.5);

        String borderDefault = dark
                ? mixHex(fg, "#FFFFFF", 0.10)
                : shade(scale, "200", "#E2E8F0");
        String borderInteractive = dark
                ? mixHex(fg, "#FFFFFF", 0.20)
                : shade(scale, "300", "#CBD5E1");
        String borderAccent = mixHex(fg, accent, dark ? 0.45 : 0.35);

        target.put("openui_bg", bg);
        target.put("openui_fg", fg);
        target.put("openui_sunk", sunk);
        target.put("openui_elevated", elevated);
        target.put("openui_highlight", highlight);

        target.put("openui_accent", accent);
        target.put("openui_accent_pressed", accentPressed);
        target.put("openui_accent_subtle", accentSubtle);
        target.put("openui_text_on_accent", onAccent);
        target.put("openui_secondary", sec);
        target.put("openui_text_on_secondary", onSec);

        target.put("openui_text_primary", textPrimary);
        target.put("openui_text_secondary", textSecondary);
        target.put("openui_text_tertiary", textTertiary);
        target.put("openui_text_brand", textBrand);

        target.put("openui_border_default", borderDefault);
        target.put("openui_border_interactive", borderInteractive);
        target.put("openui_border_accent", borderAccent);

        target.put("openui_info_bg",
                firstNonEmpty(str(status, "info_bg"),
                        dark ? "#172554" : "#EBF3FF"));
        target.put("openui_info_text",
                firstNonEmpty(str(status, "info_text"),
                        dark ? "#93C5FD" : "#1D4ED8"));
        target.put("openui_info_border",
                firstNonEmpty(str(status, "info_border"),
                        dark ? "#1E3A8A" : "#BFDBFE"));

        target.put("openui_success_bg",
                firstNonEmpty(str(status, "success_bg"),
                        dark ? "#052E16" : "#ECFDF5"));
        target.put("openui_success_text",
                firstNonEmpty(str(status, "success_text"),
                        dark ? "#86EFAC" : "#15803D"));
        target.put("openui_success_border",
                firstNonEmpty(str(status, "success_border"),
                        dark ? "#14532D" : "#BBF7D0"));

        target.put("openui_alert_bg",
                firstNonEmpty(str(status, "alert_bg"),
                        dark ? "#451A03" : "#FFFBEB"));
        target.put("openui_alert_text",
                firstNonEmpty(str(status, "alert_text"),
                        dark ? "#FCD34D" : "#B45309"));
        target.put("openui_alert_border",
                firstNonEmpty(str(status, "alert_border"),
                        dark ? "#78350F" : "#FDE68A"));

        target.put("openui_danger_bg",
                firstNonEmpty(str(status, "danger_bg"),
                        dark ? "#450A0A" : "#FEF2F2"));
        target.put("openui_danger_text",
                firstNonEmpty(str(status, "danger_text"),
                        dark ? "#FCA5A5" : "#B91C1C"));
        target.put("openui_danger_border",
                firstNonEmpty(str(status, "danger_border"),
                        dark ? "#7F1D1D" : "#FECACA"));
    }

    private static void applyColorOverrides(LinkedHashMap<String, String> map,
                                            boolean dark,
                                            JsonObject overrides,
                                            List<String> notes) {
        if (overrides == null || overrides.size() == 0) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : overrides.entrySet()) {
            if (e.getValue() == null || !e.getValue().isJsonPrimitive()) {
                continue;
            }
            Integer parsed = parseHexColor(e.getValue().getAsString());
            if (parsed == null) {
                continue;
            }
            String canonical = canonicalColorKey(e.getKey());
            if (!canonical.isEmpty()) {
                map.put(canonical, toHex6(parsed));
            }
        }
        // Re-derive dependent subtle/on-accent colours if bg/fg/accent changed
        String fg = map.get("openui_fg");
        String accent = map.get("openui_accent");
        if (!overrides.has("openui_text_on_accent")
                && !overrides.has("on_primary")) {
            map.put("openui_text_on_accent", pickOnColorHex(accent));
        }
        if (!overrides.has("openui_accent_subtle")) {
            map.put("openui_accent_subtle",
                    mixHex(fg, accent, dark ? 0.22 : 0.12));
        }
    }

    private static void enforceModeReadability(LinkedHashMap<String, String> map,
                                               boolean dark,
                                               List<String> notes) {
        String modeLabel = dark ? "Dark" : "Light";
        String bg = map.get("openui_bg");
        String fg = map.get("openui_fg");
        String sunk = map.get("openui_sunk");
        String accent = map.get("openui_accent");

        checkAndFixContrast(map, "openui_text_primary", bg, 6.0, modeLabel, notes);
        checkAndFixContrast(map, "openui_text_primary", fg, 5.5, modeLabel, notes);
        checkAndFixContrast(map, "openui_text_primary", sunk, 4.5, modeLabel, notes);
        checkAndFixContrast(map, "openui_text_secondary", bg, 4.5, modeLabel, notes);
        checkAndFixContrast(map, "openui_text_secondary", fg, 4.2, modeLabel, notes);
        checkAndFixContrast(map, "openui_text_tertiary", fg, 3.0, modeLabel, notes);
        checkAndFixContrast(map, "openui_text_tertiary", sunk, 3.0, modeLabel, notes);
        checkAndFixContrast(map, "openui_text_on_accent", accent, 4.0, modeLabel, notes);
        checkAndFixContrast(map, "openui_text_brand", fg, 3.2, modeLabel, notes);
    }

    private static void checkAndFixContrast(LinkedHashMap<String, String> map,
                                            String fgKey,
                                            String surfaceHex,
                                            double minRatio,
                                            String modeLabel,
                                            List<String> notes) {
        String current = map.get(fgKey);
        if (current == null || surfaceHex == null) {
            return;
        }
        double ratio = contrastRatioHex(current, surfaceHex);
        if (ratio >= minRatio) {
            return;
        }
        String adjusted = ensureContrastHex(current, surfaceHex, minRatio);
        if (!adjusted.equalsIgnoreCase(current)) {
            map.put(fgKey, adjusted);
            notes.add(modeLabel + " " + fgKey + " adjusted " + current + " -> "
                    + adjusted + " for readable contrast.");
        }
    }

    // ------------------------------------------------------------------
    // Full Design System JSON Builder (OpenUI Theme + Standard Components)
    // ------------------------------------------------------------------

    /**
     * Builds the complete {@code designSystem} object saved in
     * {@link AgentJournal}: contains both {@code openuiTheme} (full Light/Dark
     * semantic token sheets + scales) and {@code tokens} (12 standard component
     * specs merged with any explicit component overrides).
     */
    public static JsonObject buildDesignSystemJson(ResolvedTheme theme,
                                                   JsonArray customTokens) {
        JsonObject system = new JsonObject();
        system.addProperty("title", theme.title);
        system.addProperty("specVersion", "openui-1.0");
        system.add("openuiTheme", serializeResolvedTheme(theme));
        system.add("tokens", buildComponentTokens(theme, customTokens));
        system.addProperty("updatedAt", System.currentTimeMillis());
        return system;
    }

    public static JsonObject serializeResolvedTheme(ResolvedTheme theme) {
        JsonObject t = new JsonObject();
        t.addProperty("title", theme.title);
        t.addProperty("brand_color", theme.brandColor);
        t.addProperty("secondary_color", theme.secondaryColor);
        t.addProperty("neutral_scale", theme.neutralScale);
        t.addProperty("corner_profile", theme.cornerProfile);
        t.addProperty("density", theme.density);
        t.addProperty("theme_mode", theme.themeMode);
        t.addProperty("dynamic_colors", theme.dynamicColors);
        if (!theme.fontHeading.isEmpty()) {
            t.addProperty("font_heading", theme.fontHeading);
        }
        if (!theme.fontBody.isEmpty()) {
            t.addProperty("font_body", theme.fontBody);
        }
        JsonObject radii = new JsonObject();
        for (Map.Entry<String, Integer> e : theme.radiiDp.entrySet()) {
            radii.addProperty(e.getKey(), e.getValue());
        }
        t.add("radii_dp", radii);

        JsonObject metrics = new JsonObject();
        for (Map.Entry<String, Integer> e : theme.metricsDp.entrySet()) {
            metrics.addProperty(e.getKey(), e.getValue());
        }
        t.add("metrics_dp", metrics);

        JsonObject light = new JsonObject();
        for (Map.Entry<String, String> e : theme.lightColors.entrySet()) {
            light.addProperty(e.getKey(), e.getValue());
        }
        t.add("light_colors", light);

        JsonObject dark = new JsonObject();
        for (Map.Entry<String, String> e : theme.darkColors.entrySet()) {
            dark.addProperty(e.getKey(), e.getValue());
        }
        t.add("dark_colors", dark);
        for (Map.Entry<String, JsonElement> e : theme.rawExtras
                .entrySet()) {
            if (!t.has(e.getKey())) {
                t.add(e.getKey(), e.getValue());
            }
        }
        return t;
    }

    /** Generates the 12 standardized OpenUI component tokens, merging user overrides. */
    public static JsonArray buildComponentTokens(ResolvedTheme theme,
                                                 JsonArray overrides) {
        LinkedHashMap<String, JsonObject> map = new LinkedHashMap<>();
        int screenPad = metric(theme, "screen_pad", 18);
        int gap = metric(theme, "section_gap", 16);
        int cardPad = metric(theme, "card_pad", 16);
        int btnH = metric(theme, "button_height", 52);
        int inputH = metric(theme, "input_height", 52);
        int chipH = metric(theme, "chip_height", 38);
        int rowH = metric(theme, "row_height", 68);

        int rCard = radius(theme, "card", 14);
        int rBtn = radius(theme, "button", 12);
        int rInput = radius(theme, "input", 12);
        int rChip = radius(theme, "chip", 20);
        int rCallout = radius(theme, "callout", 12);

        map.put("screen", tokenObj("screen", "Screen surface", "screen",
                "background", "@color/openui_bg",
                "padding", String.valueOf(screenPad),
                "gap", String.valueOf(gap)));

        map.put("card", tokenObj("card", "Elevated surface card", "card",
                "background", "@color/openui_fg",
                "background_drawable", "openui_card",
                "stroke_color", "@color/openui_border_default",
                "corner_radius", String.valueOf(rCard),
                "padding", String.valueOf(cardPad),
                "margin", "8"));

        map.put("sunk_card", tokenObj("sunk_card", "Sunk / grouped well", "card",
                "background", "@color/openui_sunk",
                "background_drawable", "openui_card_sunk",
                "corner_radius", String.valueOf(rCard),
                "padding", String.valueOf(Math.max(12, cardPad - 2)),
                "margin", "6"));

        map.put("primary_button", tokenObj("primary_button",
                "Primary action button", "button",
                "height", String.valueOf(btnH),
                "background", "@color/openui_accent",
                "background_drawable", "openui_btn_primary",
                "text_color", "@color/openui_text_on_accent",
                "text_size", "16",
                "text_style", "bold",
                "corner_radius", String.valueOf(rBtn),
                "padding", "16",
                "margin", "6"));

        map.put("secondary_button", tokenObj("secondary_button",
                "Secondary outline button", "button",
                "height", String.valueOf(Math.max(44, btnH - 4)),
                "background", "@color/openui_fg",
                "background_drawable", "openui_btn_secondary",
                "text_color", "@color/openui_text_primary",
                "stroke_color", "@color/openui_border_interactive",
                "text_size", "15",
                "text_style", "bold",
                "corner_radius", String.valueOf(rBtn),
                "padding", "16",
                "margin", "6"));

        map.put("destructive_button", tokenObj("destructive_button",
                "Destructive / delete button", "button",
                "height", String.valueOf(Math.max(44, btnH - 4)),
                "background", "@color/openui_danger_bg",
                "background_drawable", "openui_btn_destructive",
                "text_color", "@color/openui_danger_text",
                "text_size", "15",
                "text_style", "bold",
                "corner_radius", String.valueOf(rBtn),
                "padding", "16",
                "margin", "6"));

        map.put("input", tokenObj("input", "Sunk text input field", "input",
                "height", String.valueOf(inputH),
                "background", "@color/openui_sunk",
                "background_drawable", "openui_sunk_input",
                "text_color", "@color/openui_text_primary",
                "hint_color", "@color/openui_text_tertiary",
                "stroke_color", "@color/openui_border_interactive",
                "text_size", "16",
                "corner_radius", String.valueOf(rInput),
                "padding", "14",
                "margin", "6"));

        map.put("chip", tokenObj("chip", "Selection pill / filter chip", "button",
                "height", String.valueOf(chipH),
                "background", "@color/openui_sunk",
                "background_drawable", "openui_chip_selector",
                "text_color", "@color/openui_text_primary",
                "text_size", "14",
                "text_style", "bold",
                "corner_radius", String.valueOf(rChip),
                "padding", "14",
                "margin", "4"));

        map.put("list_row", tokenObj("list_row", "Standard list item row",
                "list_row",
                "height", String.valueOf(rowH),
                "background", "@color/openui_fg",
                "background_drawable", "openui_card",
                "corner_radius", String.valueOf(rCard),
                "padding", "16",
                "margin", "6",
                "title_size", "16",
                "subtitle_size", "13"));

        map.put("callout", tokenObj("callout", "Status banner / callout", "card",
                "background", "@color/openui_info_bg",
                "background_drawable", "openui_callout_info",
                "text_color", "@color/openui_info_text",
                "corner_radius", String.valueOf(rCallout),
                "padding", "14",
                "margin", "6"));

        map.put("title", tokenObj("title", "Screen / section heading", "text",
                "text_size", "24",
                "text_color", "@color/openui_text_primary",
                "text_style", "bold",
                "margin", "4"));

        map.put("body", tokenObj("body", "Body paragraph text", "text",
                "text_size", "16",
                "text_color", "@color/openui_text_primary",
                "text_style", "normal",
                "margin", "2"));

        map.put("caption", tokenObj("caption", "Secondary label / metadata",
                "text",
                "text_size", "13",
                "text_color", "@color/openui_text_secondary",
                "text_style", "normal",
                "margin", "2"));

        if (overrides != null) {
            for (JsonElement el : overrides) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject ov = el.getAsJsonObject();
                String key = str(ov, "key");
                if (key.isEmpty()) {
                    continue;
                }
                JsonObject base = map.get(key);
                if (base == null) {
                    map.put(key, ov.deepCopy());
                } else {
                    if (!str(ov, "label").isEmpty()) {
                        base.addProperty("label", str(ov, "label"));
                    }
                    if (!str(ov, "kind").isEmpty()) {
                        base.addProperty("kind", str(ov, "kind"));
                    }
                    JsonObject baseProps = obj(base, "props");
                    JsonObject ovProps = obj(ov, "props");
                    for (Map.Entry<String, JsonElement> pe : ovProps.entrySet()) {
                        if (pe.getValue() == null || !pe.getValue().isJsonPrimitive()) {
                            continue;
                        }
                        String k = pe.getKey();
                        String v = pe.getValue().getAsString().trim();
                        // Guard against raw hardcoded light/dark-hostile text colours
                        // on adaptive surfaces unless the background itself is fixed hex.
                        if (("text_color".equals(k) || "hint_color".equals(k))
                                && v.startsWith("#")) {
                            String bgVal = ovProps.has("background")
                                    ? str(ovProps, "background")
                                    : str(baseProps, "background");
                            if (bgVal.startsWith("?") || bgVal.startsWith("@color/")) {
                                continue;
                            }
                        }
                        baseProps.addProperty(k, v);
                    }
                    base.add("props", baseProps);
                }
            }
        }

        JsonArray arr = new JsonArray();
        for (JsonObject token : map.values()) {
            arr.add(token);
        }
        return arr;
    }

    private static JsonObject tokenObj(String key, String label, String kind,
                                       String... kv) {
        JsonObject t = new JsonObject();
        t.addProperty("key", key);
        t.addProperty("label", label);
        t.addProperty("kind", kind);
        JsonObject props = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            props.addProperty(kv[i], kv[i + 1]);
        }
        t.add("props", props);
        return t;
    }

    // ------------------------------------------------------------------
    // Materialize Theme to Sketchware Project Resources
    // ------------------------------------------------------------------

    /**
     * Materializes the project's OpenUI Design System into real Sketchware Pro
     * Android resources:
     * <ul>
     *   <li>{@code values/colors.xml} + {@code values-night/colors.xml}</li>
     *   <li>{@code values/styles.xml} (wiring Material 3 {@code AppTheme} so
     *       Toolbars, FABs, Dialogs, Switches, Tabs &amp; BottomNav use the
     *       custom palette instead of Material baseline purple)</li>
     *   <li>{@code color/openui_chip_text.xml} selector</li>
     *   <li>{@code drawable/openui_*.xml} standard theme-linked shapes</li>
     *   <li>AppCompat + Material 3 library configuration (disabling wallpaper
     *       {@code dynamic_colors} unless explicitly requested)</li>
     *   <li>Default screen &amp; custom-row root backgrounds ({@code @color/openui_bg})</li>
     * </ul>
     */
    /**
     * The user picked a colour and chose "use everywhere": re-brand
     * the design system with it — every token regenerates from this
     * one colour — and re-materialize the Android resources, so the
     * pick genuinely becomes THE colour of the app. Works on the live
     * project when {@code scId} is one, otherwise on the agreed draft
     * (which pours into the project at create_project). Throws only
     * when a real project cannot be materialized.
     */
    public static JsonObject applyUserColor(String scId, String hex)
            throws AgentTools.ToolException {
        JsonObject current = scId == null || scId.isEmpty()
                ? AgentJournal.draftOrEmptyDesignSystem()
                : AgentJournal.designSystem(scId);
        JsonObject input = new JsonObject();
        if (current != null) {
            for (String key : new String[]{"title", "theme_mode",
                    "secondary_color", "neutral_scale", "corner_profile",
                    "density", "light_overrides", "dark_overrides"}) {
                if (current.has(key)) {
                    input.add(key, current.get(key));
                }
            }
        }
        if (!input.has("title")) {
            input.addProperty("title", "App Design System");
        }
        input.addProperty("brand_color", hex);
        ResolvedTheme resolved = resolveTheme(input);
        JsonObject full = buildDesignSystemJson(resolved, null);
        JsonObject out = new JsonObject();
        if (scId == null || scId.isEmpty()) {
            AgentJournal.setDraftDesignSystemObject(full);
            AgentJournal.setDesignSystemObject(
                    AgentJournal.DRAFT_KEY, full);
            out.addProperty("saved", "draft");
        } else {
            AgentJournal.setDesignSystemObject(scId, full);
            out.add("materialized", materializeToProject(scId, full));
            out.addProperty("saved", true);
        }
        out.addProperty("brand_color", hex);
        return out;
    }

    public static JsonObject materializeToProject(String scId,
                                                  JsonObject designSystemJson)
            throws AgentTools.ToolException {
        if (scId == null || scId.isEmpty()) {
            throw new AgentTools.ToolException("E_NO_PROJECT",
                    "No working project selected.");
        }
        ResolvedTheme theme = resolveTheme(designSystemJson);
        try {
            SketchwareActions.backupProjectOnce(scId);
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "Could not create safety backup: " + e.getMessage());
        }

        try {
            // 1. Configure AppCompat + Material 3 cleanly (dynamic_colors = theme.dynamicColors)
            ProjectLibraryBean compat = jC.c(scId).c();
            ProjectLibraryBean firebase = jC.c(scId).d();
            ProjectLibraryBean admob = jC.c(scId).b();
            ProjectLibraryBean maps = jC.c(scId).e();
            compat.useYn = "Y";
            if (compat.configurations == null) {
                compat.configurations = new HashMap<>();
            }
            compat.configurations.put("material3", true);
            compat.configurations.put("dynamic_colors", theme.dynamicColors);
            compat.configurations.put("theme", theme.themeMode);
            AgentSetup.saveBuiltInLibraries(scId, compat, firebase, admob, maps);

            // 2. Sync legacy project metadata colors (color_primary, color_accent, etc.)
            Integer primaryInt = parseHexColor(
                    theme.lightColors.get("openui_accent"));
            Integer primaryDarkInt = parseHexColor(mixHex(
                    theme.lightColors.get("openui_accent"), "#000000", 0.18));
            Integer secondaryInt = parseHexColor(
                    theme.lightColors.get("openui_secondary"));
            HashMap<String, Object> meta = lC.b(scId);
            if (meta != null && primaryInt != null) {
                HashMap<String, Object> updated = new HashMap<>(meta);
                updated.put("color_primary", primaryInt);
                if (primaryDarkInt != null) {
                    updated.put("color_primary_dark", primaryDarkInt);
                }
                if (secondaryInt != null) {
                    updated.put("color_accent", secondaryInt);
                }
                lC.b(scId, updated);
            }

            // 3. Write values/colors.xml and values-night/colors.xml
            LinkedHashMap<String, String> lightXmlColors =
                    buildXmlColorMap(theme.lightColors, false);
            LinkedHashMap<String, String> darkXmlColors =
                    buildXmlColorMap(theme.darkColors, true);
            AgentResources.writeColors(scId, "", lightXmlColors, null);
            AgentResources.writeColors(scId, "-night", darkXmlColors, null);

            // 4. Write values/styles.xml wiring AppTheme to @color/openui_*
            List<AgentResources.StyleSpec> styles = buildMaterial3Styles(theme);
            AgentResources.writeStylesFile(scId, "", false, styles, null);

            // 5. Write res/color/openui_chip_text.xml state list
            String chipTextXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                    + "    <item android:state_selected=\"true\" android:color=\"@color/openui_text_on_accent\"/>\n"
                    + "    <item android:state_checked=\"true\" android:color=\"@color/openui_text_on_accent\"/>\n"
                    + "    <item android:state_activated=\"true\" android:color=\"@color/openui_text_on_accent\"/>\n"
                    + "    <item android:color=\"@color/openui_text_primary\"/>\n"
                    + "</selector>\n";
            AgentResources.writeRawResourceXml(scId, "color",
                    "openui_chip_text.xml", chipTextXml);

            // 6. Write standard theme-linked drawables in res/drawable/
            List<AgentAssets.DrawableSpec> drawables = buildThemeDrawables(theme);
            AgentAssets.upsertCustomDrawables(scId, drawables);

            // 7. Theme default unstyled Activity / Drawer / CustomView roots
            int themedRoots = syncDefaultLayoutRoots(scId);
            AppCompatInjection.refreshInjections();

            JsonObject summary = new JsonObject();
            summary.addProperty("materialized", true);
            summary.addProperty("brand_color", theme.brandColor);
            summary.addProperty("neutral_scale", theme.neutralScale);
            summary.addProperty("corner_profile", theme.cornerProfile);
            summary.addProperty("density", theme.density);
            summary.addProperty("theme_mode", theme.themeMode);
            summary.addProperty("dynamic_colors", theme.dynamicColors);
            summary.addProperty("light_colors_count", lightXmlColors.size());
            summary.addProperty("dark_colors_count", darkXmlColors.size());
            summary.addProperty("drawables_count", drawables.size());
            summary.addProperty("themed_roots", themedRoots);
            if (!theme.contrastNotes.isEmpty()) {
                JsonArray notes = new JsonArray();
                for (String n : theme.contrastNotes) {
                    notes.add(n);
                }
                summary.add("contrast_adjustments", notes);
            }
            return summary;
        } catch (AgentTools.ToolException te) {
            throw te;
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_THEME_MATERIALIZE_FAILED",
                    "Failed to write OpenUI theme resources: "
                            + String.valueOf(e.getMessage()));
        }
    }

    private static LinkedHashMap<String, String> buildXmlColorMap(
            LinkedHashMap<String, String> modeColors, boolean dark) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        String accent = modeColors.get("openui_accent");
        String accentPressed = modeColors.get("openui_accent_pressed");
        String secondary = modeColors.get("openui_secondary");
        String bg = modeColors.get("openui_bg");
        String fg = modeColors.get("openui_fg");
        String sunk = modeColors.get("openui_sunk");
        String elevated = modeColors.get("openui_elevated");
        String textPri = modeColors.get("openui_text_primary");
        String textSec = modeColors.get("openui_text_secondary");
        String textTer = modeColors.get("openui_text_tertiary");
        String onAccent = modeColors.get("openui_text_on_accent");
        String onSec = modeColors.get("openui_text_on_secondary");
        String borderDef = modeColors.get("openui_border_default");
        String borderInt = modeColors.get("openui_border_interactive");
        String dangerText = modeColors.get("openui_danger_text");

        if (!dark) {
            out.put("colorPrimary", accent);
            out.put("colorPrimaryDark", accentPressed);
            out.put("colorAccent", secondary);
            out.put("colorControlHighlight",
                    modeColors.get("openui_highlight"));
            out.put("colorControlNormal", textSec);
        } else {
            out.put("colorPrimary", accent);
            out.put("colorPrimaryDark", bg);
            out.put("colorAccent", secondary);
            out.put("colorControlHighlight",
                    modeColors.get("openui_highlight"));
            out.put("colorControlNormal", textSec);
        }

        // OpenUI semantic tokens
        out.putAll(modeColors);

        // Standard Material / Android aliases so @color/background, @color/surface,
        // @color/on_surface, @color/primary, etc. always resolve cleanly in both modes
        out.put("background", bg);
        out.put("on_background", textPri);
        out.put("surface", fg);
        out.put("surface_variant", sunk);
        out.put("surface_container", sunk);
        out.put("surface_container_low", mixHex(bg, fg, 0.5));
        out.put("surface_container_high", elevated);
        out.put("on_surface", textPri);
        out.put("on_surface_variant", textSec);
        out.put("text_disabled", textTer);
        out.put("primary", accent);
        out.put("primary_variant", accentPressed);
        out.put("on_primary", onAccent);
        out.put("primary_container", modeColors.get("openui_accent_subtle"));
        out.put("on_primary_container", textPri);
        out.put("secondary", secondary);
        out.put("on_secondary", onSec);
        out.put("secondary_container", sunk);
        out.put("on_secondary_container", textPri);
        out.put("outline", borderInt);
        out.put("outline_variant", borderDef);
        out.put("divider", borderDef);
        out.put("card_background", fg);
        out.put("error", dangerText);
        out.put("on_error", pickOnColorHex(dangerText));
        return out;
    }

    private static List<AgentResources.StyleSpec> buildMaterial3Styles(
            ResolvedTheme theme) {
        List<AgentResources.StyleSpec> list = new ArrayList<>();
        String m3Parent = "Theme.Material3." + theme.themeMode + ".NoActionBar";

        LinkedHashMap<String, String> appItems = new LinkedHashMap<>();
        appItems.put("colorPrimary", "@color/openui_accent");
        appItems.put("colorOnPrimary", "@color/openui_text_on_accent");
        appItems.put("colorPrimaryContainer", "@color/openui_accent_subtle");
        appItems.put("colorOnPrimaryContainer", "@color/openui_text_primary");
        appItems.put("colorSecondary", "@color/openui_secondary");
        appItems.put("colorOnSecondary", "@color/openui_text_on_secondary");
        appItems.put("colorSecondaryContainer", "@color/openui_sunk");
        appItems.put("colorOnSecondaryContainer", "@color/openui_text_primary");
        appItems.put("android:colorBackground", "@color/openui_bg");
        appItems.put("colorOnBackground", "@color/openui_text_primary");
        appItems.put("colorSurface", "@color/openui_fg");
        appItems.put("colorOnSurface", "@color/openui_text_primary");
        appItems.put("colorSurfaceVariant", "@color/openui_sunk");
        appItems.put("colorOnSurfaceVariant", "@color/openui_text_secondary");
        appItems.put("colorSurfaceContainer", "@color/openui_sunk");
        appItems.put("colorSurfaceContainerLow", "@color/surface_container_low");
        appItems.put("colorSurfaceContainerHigh", "@color/openui_elevated");
        appItems.put("colorOutline", "@color/openui_border_interactive");
        appItems.put("colorOutlineVariant", "@color/openui_border_default");
        appItems.put("colorError", "@color/openui_danger_text");
        appItems.put("colorOnError", "@color/on_error");
        appItems.put("android:statusBarColor", "@color/openui_bg");
        appItems.put("android:navigationBarColor", "@color/openui_bg");
        appItems.put("android:windowLightStatusBar", "?attr/isLightTheme");
        list.add(new AgentResources.StyleSpec(
                "AppTheme", m3Parent, appItems, true));

        LinkedHashMap<String, String> fullItems = new LinkedHashMap<>();
        fullItems.put("android:windowFullscreen", "true");
        fullItems.put("android:windowContentOverlay", "@null");
        list.add(new AgentResources.StyleSpec(
                "AppTheme.FullScreen", "AppTheme", fullItems, true));

        // Toolbar has android:background="?attr/colorPrimary" in AppCompatInjection,
        // so AppBarOverlay sets title/icon colours to @color/openui_text_on_accent!
        LinkedHashMap<String, String> appBarItems = new LinkedHashMap<>();
        appBarItems.put("colorOnSurface", "@color/openui_text_on_accent");
        appBarItems.put("colorOnSurfaceVariant", "@color/openui_text_on_accent");
        appBarItems.put("colorControlNormal", "@color/openui_text_on_accent");
        appBarItems.put("android:textColorPrimary", "@color/openui_text_on_accent");
        list.add(new AgentResources.StyleSpec(
                "AppTheme.AppBarOverlay", "ThemeOverlay.Material3",
                appBarItems, true));

        LinkedHashMap<String, String> popupItems = new LinkedHashMap<>();
        popupItems.put("colorSurface", "@color/openui_elevated");
        popupItems.put("colorOnSurface", "@color/openui_text_primary");
        list.add(new AgentResources.StyleSpec(
                "AppTheme.PopupOverlay", "ThemeOverlay.Material3",
                popupItems, true));

        LinkedHashMap<String, String> debugItems = new LinkedHashMap<>();
        debugItems.put("windowActionBar", "true");
        debugItems.put("windowNoTitle", "false");
        list.add(new AgentResources.StyleSpec(
                "AppTheme.DebugActivity", "AppTheme", debugItems, true));
        return list;
    }

    private static List<AgentAssets.DrawableSpec> buildThemeDrawables(
            ResolvedTheme theme) throws AgentTools.ToolException {
        List<AgentAssets.DrawableSpec> specs = new ArrayList<>();
        int rCard = radius(theme, "card", 14);
        int rBtn = radius(theme, "button", 12);
        int rInput = radius(theme, "input", 12);
        int rChip = radius(theme, "chip", 20);
        int rTag = radius(theme, "tag", 8);
        int rCallout = radius(theme, "callout", 12);

        specs.add(AgentAssets.validateDrawableXml("openui_card",
                rectShape("@color/openui_fg", "@color/openui_border_default",
                        1, rCard)));
        specs.add(AgentAssets.validateDrawableXml("openui_card_sunk",
                rectShape("@color/openui_sunk", null, 0, rCard)));
        specs.add(AgentAssets.validateDrawableXml("openui_sunk_input",
                rectShape("@color/openui_sunk",
                        "@color/openui_border_interactive", 1, rInput)));

        specs.add(AgentAssets.validateDrawableXml("openui_btn_primary",
                rippleRect("@color/openui_highlight", "@color/openui_accent",
                        null, 0, rBtn)));
        specs.add(AgentAssets.validateDrawableXml("openui_btn_secondary",
                rippleRect("@color/openui_highlight", "@color/openui_fg",
                        "@color/openui_border_interactive", 1, rBtn)));
        specs.add(AgentAssets.validateDrawableXml("openui_btn_tertiary",
                rippleRect("@color/openui_highlight",
                        "@android:color/transparent", null, 0, rBtn)));
        specs.add(AgentAssets.validateDrawableXml("openui_btn_destructive",
                rippleRect("@color/openui_highlight",
                        "@color/openui_danger_bg",
                        "@color/openui_danger_border", 1, rBtn)));

        specs.add(AgentAssets.validateDrawableXml("openui_chip_selected",
                rectShape("@color/openui_accent", null, 0, rChip)));
        specs.add(AgentAssets.validateDrawableXml("openui_chip_unselected",
                rectShape("@color/openui_sunk",
                        "@color/openui_border_interactive", 1, rChip)));
        specs.add(AgentAssets.validateDrawableXml("openui_chip_selector",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                        + "    <item android:state_selected=\"true\" android:drawable=\"@drawable/openui_chip_selected\"/>\n"
                        + "    <item android:state_checked=\"true\" android:drawable=\"@drawable/openui_chip_selected\"/>\n"
                        + "    <item android:state_activated=\"true\" android:drawable=\"@drawable/openui_chip_selected\"/>\n"
                        + "    <item android:drawable=\"@drawable/openui_chip_unselected\"/>\n"
                        + "</selector>\n"));

        specs.add(AgentAssets.validateDrawableXml("openui_swatch_ring",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\">\n"
                        + "    <solid android:color=\"@android:color/transparent\"/>\n"
                        + "    <stroke android:width=\"3dp\" android:color=\"@color/openui_text_primary\"/>\n"
                        + "</shape>\n"));

        specs.add(AgentAssets.validateDrawableXml("openui_callout_neutral",
                rectShape("@color/openui_sunk",
                        "@color/openui_border_default", 1, rCallout)));
        specs.add(AgentAssets.validateDrawableXml("openui_callout_info",
                rectShape("@color/openui_info_bg",
                        "@color/openui_info_border", 1, rCallout)));
        specs.add(AgentAssets.validateDrawableXml("openui_callout_success",
                rectShape("@color/openui_success_bg",
                        "@color/openui_success_border", 1, rCallout)));
        specs.add(AgentAssets.validateDrawableXml("openui_callout_alert",
                rectShape("@color/openui_alert_bg",
                        "@color/openui_alert_border", 1, rCallout)));
        specs.add(AgentAssets.validateDrawableXml("openui_callout_danger",
                rectShape("@color/openui_danger_bg",
                        "@color/openui_danger_border", 1, rCallout)));

        specs.add(AgentAssets.validateDrawableXml("openui_tag_neutral",
                rectShape("@color/openui_sunk", null, 0, rTag)));
        specs.add(AgentAssets.validateDrawableXml("openui_tag_brand",
                rectShape("@color/openui_accent_subtle", null, 0, rTag)));
        specs.add(AgentAssets.validateDrawableXml("openui_tag_info",
                rectShape("@color/openui_info_bg", null, 0, rTag)));
        specs.add(AgentAssets.validateDrawableXml("openui_tag_success",
                rectShape("@color/openui_success_bg", null, 0, rTag)));
        specs.add(AgentAssets.validateDrawableXml("openui_tag_alert",
                rectShape("@color/openui_alert_bg", null, 0, rTag)));
        specs.add(AgentAssets.validateDrawableXml("openui_tag_danger",
                rectShape("@color/openui_danger_bg", null, 0, rTag)));
        return specs;
    }

    private static String rectShape(String solidColor, String strokeColor,
                                    int strokeDp, int radiusDp) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">\n");
        sb.append("    <solid android:color=\"").append(solidColor).append("\"/>\n");
        if (strokeColor != null && strokeDp > 0) {
            sb.append("    <stroke android:width=\"").append(strokeDp)
                    .append("dp\" android:color=\"").append(strokeColor)
                    .append("\"/>\n");
        }
        if (radiusDp > 0) {
            sb.append("    <corners android:radius=\"").append(radiusDp)
                    .append("dp\"/>\n");
        }
        sb.append("</shape>\n");
        return sb.toString();
    }

    private static String rippleRect(String rippleColor, String solidColor,
                                     String strokeColor, int strokeDp,
                                     int radiusDp) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<ripple xmlns:android=\"http://schemas.android.com/apk/res/android\" android:color=\"")
                .append(rippleColor).append("\">\n");
        sb.append("    <item>\n");
        sb.append("        <shape android:shape=\"rectangle\">\n");
        sb.append("            <solid android:color=\"").append(solidColor)
                .append("\"/>\n");
        if (strokeColor != null && strokeDp > 0) {
            sb.append("            <stroke android:width=\"").append(strokeDp)
                    .append("dp\" android:color=\"").append(strokeColor)
                    .append("\"/>\n");
        }
        if (radiusDp > 0) {
            sb.append("            <corners android:radius=\"").append(radiusDp)
                    .append("dp\"/>\n");
        }
        sb.append("        </shape>\n");
        sb.append("    </item>\n");
        sb.append("</ripple>\n");
        return sb.toString();
    }

    /**
     * Applies {@code @color/openui_bg} to any Activity/Drawer root that has no
     * background yet (or had a stale resolved light hex like {@code #FFFFFBF8}),
     * and {@code @android:color/transparent} on custom row roots if unstyled.
     */
    public static int syncDefaultLayoutRoots(String scId) {
        int updated = 0;
        try {
            InjectRootLayoutManager rootMgr = new InjectRootLayoutManager(scId);
            ArrayList<ProjectFileBean> activities = jC.b(scId).b();
            if (activities != null) {
                for (ProjectFileBean act : activities) {
                    if (act == null) continue;
                    if (ensureRootBackground(rootMgr, act.getXmlName(),
                            "@color/openui_bg")) {
                        updated++;
                    }
                }
            }
            ArrayList<ProjectFileBean> customs = jC.b(scId).c();
            if (customs != null) {
                for (ProjectFileBean cv : customs) {
                    if (cv == null) continue;
                    String targetBg = cv.fileType
                            == ProjectFileBean.PROJECT_FILE_TYPE_DRAWER
                            ? "@color/openui_bg"
                            : "@android:color/transparent";
                    if (ensureRootBackground(rootMgr, cv.getXmlName(),
                            targetBg)) {
                        updated++;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return updated;
    }

    private static boolean ensureRootBackground(InjectRootLayoutManager rootMgr,
                                                String xmlName,
                                                String defaultBg) {
        InjectRootLayoutManager.Root root = rootMgr.getLayoutByFileName(xmlName);
        if (root == null) {
            return false;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        if (root.getAttributes() != null) {
            attrs.putAll(root.getAttributes());
        }
        String existing = attrs.get("android:background");
        if (existing == null || existing.trim().isEmpty()
                || "#FFFFFBF8".equalsIgnoreCase(existing.trim())
                || "#FFFFFF".equalsIgnoreCase(existing.trim())) {
            attrs.put("android:background", defaultBg);
            rootMgr.set(xmlName,
                    new InjectRootLayoutManager.Root(root.getClassName(), attrs));
            return true;
        }
        return false;
    }

    /** Applies the active OpenUI root background to a newly created screen or custom view. */
    public static void applyActiveThemeToNewLayout(String scId,
                                                   String xmlName,
                                                   boolean isCustomRow) {
        if (scId == null || scId.isEmpty() || !AgentJournal.hasDesignSystem(scId)) {
            return;
        }
        try {
            InjectRootLayoutManager rootMgr = new InjectRootLayoutManager(scId);
            ensureRootBackground(rootMgr, xmlName,
                    isCustomRow ? "@android:color/transparent"
                            : "@color/openui_bg");
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // User & Starter JSON Presets (.sketchware/ai/design_systems/*.json)
    // ------------------------------------------------------------------

    public static final class PresetEntry {
        public final String id;
        public final String title;
        public final String brandColor;
        public final boolean userSaved;
        public final File file;

        public PresetEntry(String id, String title, String brandColor,
                           boolean userSaved, File file) {
            this.id = id;
            this.title = title;
            this.brandColor = brandColor;
            this.userSaved = userSaved;
            this.file = file;
        }
    }

    /** Lists user-saved presets first, followed by built-in starter presets. */
    public static List<PresetEntry> listPresets() {
        List<PresetEntry> out = new ArrayList<>();
        try {
            File dir = AiPaths.designSystemsDir();
            File[] files = dir.listFiles((d, name) ->
                    name != null && name.toLowerCase(Locale.ROOT).endsWith(".json"));
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) ->
                        Long.compare(b.lastModified(), a.lastModified()));
                for (File f : files) {
                    try {
                        JsonObject parsed = JsonParser.parseString(
                                FileUtil.readFile(f.getAbsolutePath()))
                                .getAsJsonObject();
                        ResolvedTheme rt = resolveTheme(parsed);
                        String id = f.getName().substring(0,
                                f.getName().length() - ".json".length());
                        out.add(new PresetEntry(id, rt.title, rt.brandColor,
                                true, f));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        JsonObject starters = obj(spec(), "starter_presets");
        for (Map.Entry<String, JsonElement> e : starters.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject st = e.getValue().getAsJsonObject();
            out.add(new PresetEntry(
                    e.getKey(),
                    firstNonEmpty(str(st, "title"), e.getKey()),
                    firstNonEmpty(str(st, "brand_color"), "#2563EB"),
                    false,
                    null));
        }
        return out;
    }

    /** Saves a Design System JSON preset to {@code .sketchware/ai/design_systems/<slug>.json}. */
    public static File saveUserPreset(String presetName,
                                      JsonObject designSystemJson)
            throws AgentTools.ToolException {
        if (designSystemJson == null) {
            throw new AgentTools.ToolException("E_BAD_ARGS",
                    "Nothing to save as preset.");
        }
        ResolvedTheme resolved = resolveTheme(designSystemJson);
        String label = presetName == null || presetName.trim().isEmpty()
                ? resolved.title : presetName.trim();
        String slug = label.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            slug = "custom_theme";
        }
        JsonObject normalized = buildDesignSystemJson(
                resolved,
                designSystemJson.has("tokens")
                        && designSystemJson.get("tokens").isJsonArray()
                        ? designSystemJson.getAsJsonArray("tokens") : null);
        normalized.addProperty("title", label);
        File out = new File(AiPaths.designSystemsDir(), slug + ".json");
        FileUtil.writeFile(out.getAbsolutePath(), PRETTY_GSON.toJson(normalized));
        if (!out.isFile() || out.length() == 0) {
            throw new AgentTools.ToolException("E_WRITE_FAILED",
                    "Could not write preset file " + out.getAbsolutePath());
        }
        return out;
    }

    /** Loads a preset by slug/name or from an explicit JSON file path or raw JSON text. */
    public static JsonObject loadPresetOrJson(String rawInput)
            throws AgentTools.ToolException {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_ARGS",
                    "Provide a preset name, JSON file path, or JSON object.");
        }
        String trimmed = rawInput.trim();
        JsonObject parsed = null;
        if (trimmed.startsWith("{")) {
            try {
                parsed = JsonParser.parseString(trimmed).getAsJsonObject();
            } catch (Exception e) {
                throw new AgentTools.ToolException("E_BAD_JSON",
                        "Invalid Design System JSON: " + e.getMessage());
            }
        } else {
            File maybeFile = new File(trimmed);
            if (maybeFile.isFile()) {
                try {
                    parsed = JsonParser.parseString(
                            FileUtil.readFile(maybeFile.getAbsolutePath()))
                            .getAsJsonObject();
                } catch (Exception e) {
                    throw new AgentTools.ToolException("E_BAD_JSON",
                            "File is not valid Design System JSON: "
                                    + e.getMessage());
                }
            } else {
                parsed = findStarterOrSavedPreset(trimmed);
            }
        }
        if (parsed == null) {
            throw new AgentTools.ToolException("E_NOT_FOUND",
                    "Design system preset \"" + trimmed + "\" was not found.");
        }
        ResolvedTheme resolved = resolveTheme(parsed);
        JsonArray tokens = parsed.has("tokens") && parsed.get("tokens").isJsonArray()
                ? parsed.getAsJsonArray("tokens") : null;
        return buildDesignSystemJson(resolved, tokens);
    }

    public static String toPrettyJson(JsonObject designSystemJson) {
        ResolvedTheme resolved = resolveTheme(designSystemJson);
        JsonArray tokens = designSystemJson != null
                && designSystemJson.has("tokens")
                && designSystemJson.get("tokens").isJsonArray()
                ? designSystemJson.getAsJsonArray("tokens") : null;
        return PRETTY_GSON.toJson(buildDesignSystemJson(resolved, tokens));
    }

    /** Convenience alias returning the standard 16 OpenUI component tokens for UI sheets. */
    public static JsonArray generateStandardComponentTokens(ResolvedTheme theme) {
        return buildComponentTokens(theme != null ? theme : resolveTheme(null), null);
    }

    /** Normalizes a hex string to {@code #RRGGBB}, or returns {@code fallback} when invalid. */
    public static String normalizeHex(String raw, String fallback) {
        Integer rgb = parseHexColor(raw);
        return rgb != null ? toHex6(rgb) : fallback;
    }

    /** Looks up a corner radius (in dp) for {@code cornerProfile} and {@code key} (card/button/input/chip). */
    public static int profileRadius(String cornerProfile, String key, int def) {
        String norm = normalizeCornerProfile(cornerProfile);
        JsonObject prof = obj(obj(spec(), "corner_profiles"), norm);
        return intVal(prof, key, def);
    }

    /** Returns screen root padding (in dp) for a density profile. */
    public static int densityScreenPadding(String density) {
        String norm = normalizeDensity(density);
        JsonObject dens = obj(obj(spec(), "density_profiles"), norm);
        return intVal(dens, "screen_padding", 16);
    }

    /** Returns vertical stack/form gap (in dp) for a density profile. */
    public static int densityGap(String density) {
        String norm = normalizeDensity(density);
        JsonObject dens = obj(obj(spec(), "density_profiles"), norm);
        return intVal(dens, "gap", 12);
    }

    /** Returns card inner padding (in dp) for a density profile. */
    public static int densityCardPadding(String density) {
        String norm = normalizeDensity(density);
        JsonObject dens = obj(obj(spec(), "density_profiles"), norm);
        return intVal(dens, "card_padding", 16);
    }

    /** Lists user-saved {@code .json} preset files under {@code .sketchware/ai/design_systems/}. */
    public static List<File> listUserPresets() {
        List<File> out = new ArrayList<>();
        try {
            File dir = AiPaths.designSystemsDir();
            File[] files = dir.listFiles((d, name) ->
                    name != null && name.toLowerCase(Locale.ROOT).endsWith(".json"));
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) ->
                        Long.compare(b.lastModified(), a.lastModified()));
                java.util.Collections.addAll(out, files);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Loads a preset by slug/title/path without throwing checked exceptions (returns null on failure). */
    public static JsonObject loadPresetByNameOrPath(String nameOrPath) {
        try {
            return loadPresetOrJson(nameOrPath);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Returns the starter presets dictionary from {@code assets/openui/spec.json}. */
    public static JsonObject starterPresets() {
        return obj(spec(), "starter_presets");
    }

    private static JsonObject findStarterOrSavedPreset(String name) {
        String key = name.trim();
        String slug = key.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        try {
            File saved = new File(AiPaths.designSystemsDir(), slug + ".json");
            if (saved.isFile()) {
                return JsonParser.parseString(
                        FileUtil.readFile(saved.getAbsolutePath()))
                        .getAsJsonObject();
            }
        } catch (Throwable ignored) {
        }
        JsonObject starters = obj(spec(), "starter_presets");
        if (starters.has(slug) && starters.get(slug).isJsonObject()) {
            return starters.getAsJsonObject(slug);
        }
        for (Map.Entry<String, JsonElement> e : starters.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject st = e.getValue().getAsJsonObject();
            if (key.equalsIgnoreCase(str(st, "title"))) {
                return st;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Lookup & Legacy Migration Helpers
    // ------------------------------------------------------------------

    /** Looks up a single component token (e.g. "primary_button", "card", "input") for {@code scId}. */
    public static JsonObject findComponentToken(String scId, String tokenKey) {
        if (tokenKey == null || tokenKey.trim().isEmpty()) {
            return null;
        }
        JsonObject ds = scId == null || scId.isEmpty()
                ? AgentJournal.draftOrEmptyDesignSystem()
                : AgentJournal.designSystem(scId);
        if (ds == null || ds.size() == 0) {
            return null;
        }
        ResolvedTheme theme = resolveTheme(ds);
        JsonArray tokens = ds.has("tokens") && ds.get("tokens").isJsonArray()
                ? ds.getAsJsonArray("tokens")
                : buildComponentTokens(theme, null);
        String wanted = tokenKey.trim().toLowerCase(Locale.ROOT);
        for (JsonElement el : tokens) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            if (wanted.equals(str(t, "key").toLowerCase(Locale.ROOT))) {
                return t;
            }
        }
        // Fallback against freshly generated OpenUI tokens if ds was legacy
        for (JsonElement el : buildComponentTokens(theme, null)) {
            JsonObject t = el.getAsJsonObject();
            if (wanted.equals(str(t, "key").toLowerCase(Locale.ROOT))) {
                return t;
            }
        }
        return null;
    }

    private static String inferLegacyBrand(JsonObject ds) {
        if (ds == null || !ds.has("tokens") || !ds.get("tokens").isJsonArray()) {
            return "";
        }
        for (JsonElement el : ds.getAsJsonArray("tokens")) {
            if (!el.isJsonObject()) continue;
            JsonObject t = el.getAsJsonObject();
            String key = str(t, "key");
            if ("primary_button".equals(key) || "accent".equals(key)) {
                String bg = str(obj(t, "props"), "background");
                if (parseHexColor(bg) != null) {
                    return toHex6(parseHexColor(bg));
                }
            }
        }
        return "";
    }

    private static String inferLegacyCornerProfile(JsonObject ds) {
        if (ds == null || !ds.has("tokens") || !ds.get("tokens").isJsonArray()) {
            return "balanced";
        }
        for (JsonElement el : ds.getAsJsonArray("tokens")) {
            if (!el.isJsonObject()) continue;
            JsonObject props = obj(el.getAsJsonObject(), "props");
            int r = intVal(props, "corner_radius", -1);
            if (r >= 0) {
                if (r <= 8) return "sharp";
                if (r <= 14) return "balanced";
                if (r <= 24) return "soft";
                return "pill";
            }
        }
        return "balanced";
    }

    private static String inferNeutralFromBrand(String brandHex) {
        Integer rgb = parseHexColor(brandHex);
        if (rgb == null) return "slate";
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (r > b + 35 && r > g + 15) {
            return "warm";
        }
        if (g > r + 20 && g >= b) {
            return "stone";
        }
        if (b > r + 25) {
            return "slate";
        }
        return "zinc";
    }

    private static String normalizeNeutralScale(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "neutral", "slate", "gray", "zinc", "stone", "warm" -> s;
            default -> "slate";
        };
    }

    private static String normalizeCornerProfile(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "sharp", "square", "minimal" -> "sharp";
            case "soft", "rounded", "generous" -> "soft";
            case "pill", "full", "capsule" -> "pill";
            default -> "balanced";
        };
    }

    private static String normalizeDensity(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "compact", "dense", "tight" -> "compact";
            case "spacious", "relaxed", "airy" -> "spacious";
            default -> "comfortable";
        };
    }

    private static String normalizeThemeMode(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "light" -> "Light";
            case "dark" -> "Dark";
            default -> "DayNight";
        };
    }

    private static String canonicalColorKey(String rawKey) {
        if (rawKey == null) return "";
        String k = rawKey.trim().toLowerCase(Locale.ROOT);
        if (k.startsWith("openui_")) return k;
        return switch (k) {
            case "background", "bg" -> "openui_bg";
            case "foreground", "fg", "surface", "card" -> "openui_fg";
            case "sunk", "input_bg", "surface_variant" -> "openui_sunk";
            case "elevated" -> "openui_elevated";
            case "accent", "primary", "brand" -> "openui_accent";
            case "on_accent", "on_primary" -> "openui_text_on_accent";
            case "secondary" -> "openui_secondary";
            case "text_primary", "on_surface" -> "openui_text_primary";
            case "text_secondary", "on_surface_variant" ->
                    "openui_text_secondary";
            case "text_tertiary", "hint" -> "openui_text_tertiary";
            case "border", "outline" -> "openui_border_interactive";
            case "border_subtle", "divider" -> "openui_border_default";
            default -> "";
        };
    }

    private static String sanitizeFontName(String raw) {
        if (raw == null) return "";
        String f = raw.trim().toLowerCase(Locale.ROOT);
        if (f.endsWith(".ttf") || f.endsWith(".otf")) {
            f = f.substring(0, f.length() - 4);
        }
        return f.matches("[a-z][a-z0-9_]*") ? f : "";
    }

    private static int metric(ResolvedTheme t, String key, int def) {
        Integer v = t.metricsDp.get(key);
        return v != null ? v : def;
    }

    private static int radius(ResolvedTheme t, String key, int def) {
        Integer v = t.radiiDp.get(key);
        return v != null ? v : def;
    }

    private static String shade(JsonObject scale, String key, String fallback) {
        String s = str(scale, key);
        return parseHexColor(s) != null ? toHex6(parseHexColor(s)) : fallback;
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()
                || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString().trim();
    }

    private static boolean bool(JsonObject obj, String key, boolean def) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return def;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    private static int intVal(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return def;
        }
        try {
            return Integer.parseInt(obj.get(key).getAsString().trim()
                    .replace("dp", "").replace("sp", "").replace("px", ""));
        } catch (Exception e) {
            return def;
        }
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static JsonObject obj(JsonObject parent, String key) {
        if (parent != null && parent.has(key)
                && parent.get(key).isJsonObject()) {
            return parent.getAsJsonObject(key);
        }
        return new JsonObject();
    }

    private static JsonObject firstObj(JsonObject parent, String... keys) {
        if (parent == null) return new JsonObject();
        for (String k : keys) {
            if (parent.has(k) && parent.get(k).isJsonObject()) {
                return parent.getAsJsonObject(k);
            }
        }
        return new JsonObject();
    }

    private static String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return "";
    }

    private static JsonObject fallbackSpec() {
        JsonObject fb = new JsonObject();
        fb.addProperty("spec_version", "1.0.0");
        JsonObject scales = new JsonObject();
        JsonObject slate = new JsonObject();
        slate.addProperty("25", "#FCFDFF");
        slate.addProperty("50", "#F8FAFC");
        slate.addProperty("100", "#F1F5F9");
        slate.addProperty("200", "#E2E8F0");
        slate.addProperty("300", "#CBD5E1");
        slate.addProperty("400", "#94A3B8");
        slate.addProperty("500", "#64748B");
        slate.addProperty("600", "#475569");
        slate.addProperty("700", "#334155");
        slate.addProperty("800", "#1E293B");
        slate.addProperty("900", "#0F172A");
        slate.addProperty("950", "#080D1A");
        slate.addProperty("1000", "#040711");
        scales.add("slate", slate);
        fb.add("neutral_scales", scales);

        JsonObject corners = new JsonObject();
        JsonObject bal = new JsonObject();
        bal.addProperty("card", 14);
        bal.addProperty("button", 12);
        bal.addProperty("input", 12);
        bal.addProperty("chip", 20);
        bal.addProperty("tag", 8);
        bal.addProperty("callout", 12);
        corners.add("balanced", bal);
        fb.add("corner_profiles", corners);

        JsonObject densities = new JsonObject();
        JsonObject comf = new JsonObject();
        comf.addProperty("screen_pad", 18);
        comf.addProperty("section_gap", 16);
        comf.addProperty("card_pad", 16);
        comf.addProperty("button_height", 52);
        comf.addProperty("input_height", 52);
        comf.addProperty("chip_height", 38);
        comf.addProperty("row_height", 68);
        densities.add("comfortable", comf);
        fb.add("density_profiles", densities);
        fb.add("semantic_status", new JsonObject());
        fb.add("starter_presets", new JsonObject());
        return fb;
    }
}
