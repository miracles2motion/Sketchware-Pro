package pro.sketchware.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ProjectResourceBean;
import com.besome.sketch.beans.ViewBean;
import com.caverock.androidsvg.SVG;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import a.a.a.KB;
import a.a.a.jC;
import a.a.a.kC;
import a.a.a.lC;
import a.a.a.oB;
import a.a.a.wq;
import a.a.a.yB;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileUtil;
import pro.sketchware.utility.SvgUtils;

/**
 * Phase 2 asset engine for Agent Mode:
 * <ul>
 *   <li>Built-in 2,000+ Material Icon Pack search &amp; tinted vector import
 *       ({@code icon_pack.zip} + {@link SvgUtils} + {@link jC#d(String)})</li>
 *   <li>Chat-image-to-Project-Image-Manager PNG import ({@code drawable-xhdpi})</li>
 *   <li>Custom Android XML drawables ({@code <vector>}, {@code <shape>},
 *       {@code <ripple>}, {@code <selector>}, {@code <layer-list>}) in
 *       {@code files/resource/drawable/}</li>
 *   <li>Standard &amp; Android 8+ Adaptive Launcher Icons across all 5
 *       {@code mipmap-*} buckets ({@code IconCreatorActivity} / {@code lC})</li>
 * </ul>
 */
public final class AgentAssets {

    private static final Pattern RES_NAME_PATTERN =
            Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final Pattern HEX_COLOR_PATTERN =
            Pattern.compile("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");

    private static final String[] ICON_STYLES = {
            "round", "outline", "baseline", "sharp", "twotone"
    };

    private static final Set<String> VALID_DRAWABLE_ROOTS = new HashSet<>(
            Arrays.asList(
                    "vector", "shape", "selector", "ripple", "layer-list",
                    "inset", "clip", "scale", "rotate", "level-list",
                    "transition", "animated-vector", "bitmap", "nine-patch"));

    private static final String[][] MIPMAP_BUCKETS = {
            {"mipmap-mdpi", "48", "108"},
            {"mipmap-hdpi", "72", "162"},
            {"mipmap-xhdpi", "96", "216"},
            {"mipmap-xxhdpi", "144", "324"},
            {"mipmap-xxxhdpi", "192", "432"}
    };

    private static volatile List<String> cachedBuiltinIcons;

    private AgentAssets() {
    }

    /** Specification for importing one built-in Material icon. */
    public static final class IconImportSpec {
        public final String iconName;
        public final String resName;
        public final String style;
        public final String colorHex;

        public IconImportSpec(String iconName, String resName,
                              String style, String colorHex) {
            this.iconName = iconName;
            this.resName = resName;
            this.style = style;
            this.colorHex = colorHex;
        }
    }

    /** Specification for writing one custom XML drawable. */
    public static final class DrawableSpec {
        public final String name;
        public final String xml;
        public final String rootTag;

        public DrawableSpec(String name, String xml, String rootTag) {
            this.name = name;
            this.xml = xml;
            this.rootTag = rootTag;
        }
    }

    /** Specification for generating standard + adaptive launcher icons. */
    public static final class AppIconSpec {
        public String attachmentId = "";
        public String builtinIcon = "";
        public String builtinStyle = "round";
        public String monogramText = "";
        public String foregroundColorHex = "#FFFFFF";
        public String backgroundColorHex = "#4F46E5";
        public String gradientStartHex = "";
        public String gradientEndHex = "";
        public String gradientDirection = "TL_BR";
        public boolean adaptive = true;
        public boolean fullBleed = false;
        public float cornerRadiusRatio = 0.22f;
    }

    /** Forces {@link jC#d(String)} to re-hydrate from permanent disk on next access. */
    public static void resetResourceManager(String scId) {
        synchronized (jC.class) {
            if (jC.c != null && (scId == null || scId.isEmpty()
                    || scId.equals(jC.c.i))) {
                try {
                    jC.e();
                } catch (Exception ignored) {
                    jC.c = null;
                }
            }
        }
    }

    /** Validates an Android drawable resource name compatible with {@code edit_screen}. */
    public static String validateResName(String rawName)
            throws AgentTools.ToolException {
        return validateResName(rawName, "res_name");
    }

    /** Validates an Android drawable resource name compatible with {@code edit_screen}. */
    public static String validateResName(String rawName, String label)
            throws AgentTools.ToolException {
        String name = rawName == null
                ? "" : rawName.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml") || name.endsWith(".png")
                || name.endsWith(".svg") || name.endsWith(".jpg")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        if (name.isEmpty() || !RES_NAME_PATTERN.matcher(name).matches()) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    label + " must start with a lowercase letter and use only "
                            + "lowercase letters, digits, and underscores "
                            + "(max 40 chars): got \"" + rawName + "\".");
        }
        if ("app_icon".equals(name) || "default_image".equals(name)
                || "ic_launcher".equals(name)
                || "ic_launcher_foreground".equals(name)
                || "ic_launcher_background".equals(name)
                || "ic_launcher_monochrome".equals(name)) {
            throw new AgentTools.ToolException("E_RESERVED_NAME",
                    "\"" + name + "\" is reserved by Android/Sketchware. "
                            + "Choose a descriptive drawable name like "
                            + "\"ic_home_round\" or \"bg_primary_pill\".");
        }
        return name;
    }

    /** Normalizes icon style aliases ("outlined" -> "outline", "rounded" -> "round", etc.). */
    public static String normalizeIconStyle(String rawStyle)
            throws AgentTools.ToolException {
        if (rawStyle == null || rawStyle.trim().isEmpty()) {
            return "round";
        }
        String s = rawStyle.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(" ", "_");
        return switch (s) {
            case "round", "rounded" -> "round";
            case "outline", "outlined" -> "outline";
            case "baseline", "filled", "fill", "default", "regular" ->
                    "baseline";
            case "sharp" -> "sharp";
            case "twotone", "two_tone" -> "twotone";
            default -> throw new AgentTools.ToolException("E_BAD_STYLE",
                    "Unknown icon style \"" + rawStyle + "\". Choose one of: "
                            + "round, outline, baseline, sharp, twotone.");
        };
    }

    /** Validates a #RRGGBB or #AARRGGBB hex colour string. */
    public static String normalizeHexColor(String rawHex, String fallback)
            throws AgentTools.ToolException {
        if (rawHex == null || rawHex.trim().isEmpty()) {
            return fallback;
        }
        String trimmed = rawHex.trim();
        if (!trimmed.startsWith("#")) {
            trimmed = "#" + trimmed;
        }
        if (!HEX_COLOR_PATTERN.matcher(trimmed).matches()) {
            throw new AgentTools.ToolException("E_BAD_COLOR",
                    "Colour must be #RRGGBB or #AARRGGBB (got \""
                            + rawHex + "\").");
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    /** Ensures {@code assets/icons/icon_pack.zip} is extracted and returns the {@code svg/} directory. */
    public static synchronized File ensureIconPackExtracted(Context context)
            throws AgentTools.ToolException {
        String store = wq.getExtractedIconPackStoreLocation();
        File svgRoot = new File(store, "svg");
        File[] existing = svgRoot.listFiles();
        if (!svgRoot.isDirectory() || existing == null || existing.length == 0) {
            try {
                new oB().f(store);
                KB.a(context, "icons" + File.separator + "icon_pack.zip", store);
            } catch (Throwable e) {
                throw new AgentTools.ToolException("E_ICON_PACK",
                        "Could not extract built-in icon pack: "
                                + e.getMessage());
            }
            existing = svgRoot.listFiles();
            if (!svgRoot.isDirectory() || existing == null
                    || existing.length == 0) {
                throw new AgentTools.ToolException("E_ICON_PACK",
                        "Built-in icon pack directory is empty at "
                                + svgRoot.getAbsolutePath());
            }
            cachedBuiltinIcons = null;
        }
        if (cachedBuiltinIcons == null) {
            List<String> names = new ArrayList<>(existing.length);
            for (File dir : existing) {
                if (dir.isDirectory()) {
                    names.add(dir.getName());
                }
            }
            Collections.sort(names);
            cachedBuiltinIcons = Collections.unmodifiableList(names);
        }
        return svgRoot;
    }

    /** Searches the built-in Material icon pack by keyword(s) and optional style. */
    public static JsonObject searchBuiltinIcons(Context context,
                                                String query,
                                                String styleFilter,
                                                int limit)
            throws AgentTools.ToolException {
        String normalizedStyle = (styleFilter == null
                || styleFilter.trim().isEmpty())
                ? null : normalizeIconStyle(styleFilter);
        File svgRoot = ensureIconPackExtracted(context);
        List<String> all = cachedBuiltinIcons;
        int cappedLimit = Math.max(1, Math.min(limit <= 0 ? 25 : limit, 60));
        String q = query == null ? ""
                : query.trim().toLowerCase(Locale.ROOT)
                        .replace('-', '_');
        List<String> tokens = new ArrayList<>();
        if (!q.isEmpty()) {
            for (String part : q.split("[\\s_]+")) {
                if (!part.isEmpty()) {
                    tokens.add(part);
                }
            }
        }

        List<String> chosen = new ArrayList<>();
        if (tokens.isEmpty()) {
            for (int i = 0; i < all.size() && chosen.size() < cappedLimit; i++) {
                String cand = all.get(i);
                if (normalizedStyle == null
                        || new File(new File(svgRoot, cand),
                                normalizedStyle + ".svg").isFile()) {
                    chosen.add(cand);
                }
            }
        } else {
            String joined = String.join("_", tokens);
            List<int[]> scored = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                String origName = all.get(i);
                if (normalizedStyle != null
                        && !new File(new File(svgRoot, origName),
                                normalizedStyle + ".svg").isFile()) {
                    continue;
                }
                String name = origName.toLowerCase(Locale.ROOT);
                int score = 0;
                if (name.equals(joined)) {
                    score += 120;
                } else if (name.startsWith(joined + "_")
                        || name.startsWith(joined)) {
                    score += 75;
                } else if (name.contains("_" + joined + "_")
                        || name.endsWith("_" + joined)) {
                    score += 55;
                } else if (name.contains(joined)) {
                    score += 40;
                }
                int matchedTokens = 0;
                for (String token : tokens) {
                    if (name.equals(token)
                            || name.startsWith(token + "_")
                            || name.endsWith("_" + token)
                            || name.contains("_" + token + "_")) {
                        score += 18;
                        matchedTokens++;
                    } else if (name.contains(token)) {
                        score += 9;
                        matchedTokens++;
                    }
                }
                if (matchedTokens == tokens.size() && tokens.size() > 1) {
                    score += 30;
                }
                if (score > 0) {
                    scored.add(new int[]{i, score, name.length()});
                }
            }
            scored.sort((a, b) -> {
                if (b[1] != a[1]) {
                    return Integer.compare(b[1], a[1]);
                }
                return Integer.compare(a[2], b[2]);
            });
            for (int i = 0; i < Math.min(cappedLimit, scored.size()); i++) {
                chosen.add(all.get(scored.get(i)[0]));
            }
        }

        JsonArray matches = new JsonArray();
        for (String iconName : chosen) {
            JsonObject item = new JsonObject();
            item.addProperty("icon", iconName);
            JsonArray styles = new JsonArray();
            File folder = new File(svgRoot, iconName);
            for (String st : ICON_STYLES) {
                if (new File(folder, st + ".svg").isFile()) {
                    styles.add(st);
                }
            }
            item.add("styles", styles);
            matches.add(item);
        }
        JsonObject out = new JsonObject();
        out.addProperty("total_pack_icons", all.size());
        out.addProperty("query", query == null ? "" : query.trim());
        out.addProperty("count", matches.size());
        out.add("icons", matches);
        return out;
    }

    /** Resolves an icon SVG file inside the extracted built-in icon pack. */
    public static File resolveBuiltinIconSvg(Context context,
                                             String iconName,
                                             String style)
            throws AgentTools.ToolException {
        File svgRoot = ensureIconPackExtracted(context);
        String normalizedIcon = iconName == null
                ? "" : iconName.trim().toLowerCase(Locale.ROOT)
                        .replace('-', '_').replace(' ', '_');
        if (normalizedIcon.startsWith("ic_")
                && !new File(svgRoot, normalizedIcon).isDirectory()
                && new File(svgRoot, normalizedIcon.substring(3)).isDirectory()) {
            normalizedIcon = normalizedIcon.substring(3);
        }
        File folder = new File(svgRoot, normalizedIcon);
        if (!folder.isDirectory()) {
            throw new AgentTools.ToolException("E_ICON_NOT_FOUND",
                    "Built-in icon \"" + iconName + "\" was not found in the "
                            + "Material icon pack. Call search_builtin_icons "
                            + "first to find exact icon names.");
        }
        String normalizedStyle = normalizeIconStyle(style);
        File svgFile = new File(folder, normalizedStyle + ".svg");
        if (!svgFile.isFile()) {
            for (String fallback : ICON_STYLES) {
                File alt = new File(folder, fallback + ".svg");
                if (alt.isFile()) {
                    return alt;
                }
            }
            throw new AgentTools.ToolException("E_ICON_NOT_FOUND",
                    "Built-in icon \"" + normalizedIcon
                            + "\" has no SVG files.");
        }
        return svgFile;
    }

    /** Default valid drawable resource name for a built-in icon. */
    public static String defaultIconResName(String iconName, String style)
            throws AgentTools.ToolException {
        String clean = iconName == null ? ""
                : iconName.trim().toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_]", "_");
        if (clean.startsWith("ic_")) {
            clean = clean.substring(3);
        }
        String st = normalizeIconStyle(style);
        String candidate = "ic_" + clean + ("round".equals(st) ? "" : "_" + st);
        if (candidate.length() > 40) {
            candidate = candidate.substring(0, 40);
        }
        while (candidate.endsWith("_")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return validateResName(candidate, "res_name");
    }

    /**
     * Imports one or more built-in Material icons into {@code jC.d(scId)}
     * using Sketchware Pro's exact {@link SvgUtils#convert} pipeline.
     */
    public static JsonArray importBuiltinIcons(Context context,
                                               String scId,
                                               List<IconImportSpec> specs,
                                               boolean overwrite)
            throws AgentTools.ToolException {
        if (specs == null || specs.isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_ARGS",
                    "Provide at least one icon to import.");
        }
        try {
            SketchwareActions.backupProjectOnce(scId);
        } catch (IOException e) {
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "Could not back up project before importing icons: "
                            + e.getMessage());
        }

        resetResourceManager(scId);
        kC resMgr = jC.d(scId);
        String projectImagesDir = resMgr.l();
        new oB().f(projectImagesDir);

        FilePathUtil fpu = new FilePathUtil();
        File svgDestDir = new File(fpu.getPathSvg(scId));
        //noinspection ResultOfMethodCallIgnored
        svgDestDir.mkdirs();

        ArrayList<ProjectResourceBean> images = new ArrayList<>();
        if (resMgr.b != null) {
            for (ProjectResourceBean existing : resMgr.b) {
                images.add(existing.clone());
            }
        }

        SvgUtils svgUtils = new SvgUtils(context);
        JsonArray imported = new JsonArray();

        for (IconImportSpec spec : specs) {
            File srcSvg = resolveBuiltinIconSvg(
                    context, spec.iconName, spec.style);
            int existingIdx = -1;
            for (int i = 0; i < images.size(); i++) {
                if (spec.resName.equals(images.get(i).resName)) {
                    existingIdx = i;
                    break;
                }
            }
            if (existingIdx >= 0 && !overwrite) {
                throw new AgentTools.ToolException("E_ALREADY_DONE",
                        "Project already has an image named \""
                                + spec.resName
                                + "\". Pass overwrite: true to replace it.");
            }
            // Remove any conflicting custom XML drawable with the same name when overwriting
            File customXml = new File(customDrawablePath(scId, spec.resName));
            if (customXml.exists()) {
                if (!overwrite) {
                    throw new AgentTools.ToolException("E_NAME_CONFLICT",
                            "A custom XML drawable named \"" + spec.resName
                                    + "\" already exists in res/drawable/. "
                                    + "Choose another res_name or pass overwrite: true.");
                }
                //noinspection ResultOfMethodCallIgnored
                customXml.delete();
            }

            String svgCopyPath = fpu.getSvgFullPath(scId, spec.resName);
            try {
                copyFileBytes(srcSvg, new File(svgCopyPath));
                // Clean any previous .png with the same stem in temp dir
                File oldPng = new File(projectImagesDir, spec.resName + ".png");
                if (oldPng.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    oldPng.delete();
                }
                svgUtils.convert(svgCopyPath, projectImagesDir, spec.colorHex);
            } catch (Exception e) {
                throw new AgentTools.ToolException("E_ICON_CONVERT",
                        "Failed converting icon \"" + spec.iconName + "\": "
                                + e.getMessage());
            }

            File generatedXml = new File(
                    projectImagesDir, spec.resName + ".xml");
            if (!generatedXml.isFile() || generatedXml.length() == 0) {
                throw new AgentTools.ToolException("E_ICON_CONVERT",
                        "Converted vector file was not produced for \""
                                + spec.resName + "\".");
            }

            ProjectResourceBean bean = new ProjectResourceBean(
                    ProjectResourceBean.PROJECT_RES_TYPE_FILE,
                    spec.resName,
                    spec.resName + ".xml");
            if (existingIdx >= 0) {
                images.set(existingIdx, bean);
            } else {
                images.add(bean);
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("icon", spec.iconName);
            entry.addProperty("style", normalizeIconStyle(spec.style));
            entry.addProperty("res_name", spec.resName);
            entry.addProperty("color", spec.colorHex);
            imported.add(entry);
        }

        resMgr.b(images);
        resMgr.y();
        jC.a(scId).k();
        SketchwareActions.commitProject(scId);
        kC.z();
        resetResourceManager(scId);

        // Read-back verification against permanent storage & resource manager
        List<String> persistedNames = SketchwareProjects.resources(scId).images;
        for (IconImportSpec spec : specs) {
            File permFile = new File(
                    wq.g() + File.separator + scId, spec.resName + ".xml");
            if (!permFile.isFile() || !persistedNames.contains(spec.resName)) {
                throw new AgentTools.ToolException("E_VERIFY_FAILED",
                        "Icon \"" + spec.resName
                                + "\" was imported but failed read-back verification.");
            }
        }
        return imported;
    }

    /**
     * Imports a user-attached chat image into Project Image Manager ({@code drawable-xhdpi}).
     */
    public static JsonObject importChatImage(Context context,
                                             String scId,
                                             String attachmentId,
                                             String resName,
                                             int maxEdgePx,
                                             boolean overwrite)
            throws AgentTools.ToolException {
        String validName = validateResName(resName, "res_name");
        File src = AgentMedia.findAttachmentFile(scId, attachmentId);
        if (src == null || !src.isFile()) {
            throw new AgentTools.ToolException("E_NO_ATTACHMENT",
                    attachmentId == null || attachmentId.trim().isEmpty()
                            ? "No attached chat image was found. Ask the user "
                                    + "to attach the image in chat first."
                            : "Chat attachment \"" + attachmentId
                                    + "\" was not found.");
        }

        int cappedEdge = Math.max(64, Math.min(
                maxEdgePx <= 0 ? 1024 : maxEdgePx, 2048));
        Bitmap decoded = decodeScaledFile(src, cappedEdge);
        if (decoded == null) {
            throw new AgentTools.ToolException("E_BAD_IMAGE",
                    "Could not decode attached image \"" + src.getName() + "\".");
        }

        try {
            SketchwareActions.backupProjectOnce(scId);
        } catch (IOException e) {
            decoded.recycle();
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "Could not back up project: " + e.getMessage());
        }

        resetResourceManager(scId);
        kC resMgr = jC.d(scId);
        String projectImagesDir = resMgr.l();
        new oB().f(projectImagesDir);

        ArrayList<ProjectResourceBean> images = new ArrayList<>();
        if (resMgr.b != null) {
            for (ProjectResourceBean existing : resMgr.b) {
                images.add(existing.clone());
            }
        }

        int existingIdx = -1;
        for (int i = 0; i < images.size(); i++) {
            if (validName.equals(images.get(i).resName)) {
                existingIdx = i;
                break;
            }
        }
        if (existingIdx >= 0 && !overwrite) {
            decoded.recycle();
            throw new AgentTools.ToolException("E_ALREADY_DONE",
                    "Project already has an image named \"" + validName
                            + "\". Pass overwrite: true to replace it.");
        }

        File outPng = new File(projectImagesDir, validName + ".png");
        File oldXml = new File(projectImagesDir, validName + ".xml");
        if (oldXml.exists()) {
            //noinspection ResultOfMethodCallIgnored
            oldXml.delete();
        }
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        try (FileOutputStream fos = new FileOutputStream(outPng)) {
            if (!decoded.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new IOException("PNG compression failed.");
            }
            fos.flush();
        } catch (IOException e) {
            decoded.recycle();
            throw new AgentTools.ToolException("E_WRITE_FAILED",
                    "Could not write project image: " + e.getMessage());
        }
        decoded.recycle();

        ProjectResourceBean bean = new ProjectResourceBean(
                ProjectResourceBean.PROJECT_RES_TYPE_FILE,
                validName,
                validName + ".png");
        if (existingIdx >= 0) {
            images.set(existingIdx, bean);
        } else {
            images.add(bean);
        }

        resMgr.b(images);
        resMgr.y();
        jC.a(scId).k();
        SketchwareActions.commitProject(scId);
        kC.z();
        resetResourceManager(scId);

        File permFile = new File(
                wq.g() + File.separator + scId, validName + ".png");
        if (!permFile.isFile()
                || !SketchwareProjects.resources(scId).images.contains(validName)) {
            throw new AgentTools.ToolException("E_VERIFY_FAILED",
                    "Image \"" + validName
                            + "\" was written but failed read-back verification.");
        }

        JsonObject out = new JsonObject();
        out.addProperty("res_name", validName);
        out.addProperty("source_attachment", src.getName());
        out.addProperty("width", width);
        out.addProperty("height", height);
        out.addProperty("bytes", permFile.length());
        return out;
    }

    /** Lists custom XML drawables stored in {@code files/resource/drawable/*.xml}. */
    public static List<String> listCustomDrawables(String scId) {
        Set<String> names = new LinkedHashSet<>();
        if (scId == null || scId.isEmpty()) {
            return new ArrayList<>();
        }
        File resRoot = new File(new FilePathUtil().getPathResource(scId));
        File[] subDirs = resRoot.listFiles();
        if (subDirs == null) {
            return new ArrayList<>();
        }
        for (File sub : subDirs) {
            if (!sub.isDirectory() || !sub.getName().startsWith("drawable")) {
                continue;
            }
            File[] files = sub.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                String fn = f.getName();
                if (f.isFile() && fn.endsWith(".xml") && fn.length() > 4) {
                    names.add(fn.substring(0, fn.length() - 4));
                }
            }
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        return sorted;
    }

    /** Reads one custom XML drawable from {@code files/resource/drawable/<name>.xml}. */
    public static String readCustomDrawableXml(String scId, String rawName)
            throws AgentTools.ToolException {
        String name = validateResName(rawName, "drawable");
        File file = new File(customDrawablePath(scId, name));
        if (!file.isFile()) {
            throw new AgentTools.ToolException("E_NOT_FOUND",
                    "No custom XML drawable named \"" + name
                            + "\" exists in res/drawable/.");
        }
        return FileUtil.readFile(file.getAbsolutePath());
    }

    /**
     * Validates an Android drawable XML document and ensures {@code xmlns:android}
     * is declared on the root element. Returns normalized XML + root tag name.
     */
    public static DrawableSpec validateDrawableXml(String rawName,
                                                   String rawXml)
            throws AgentTools.ToolException {
        String name = validateResName(rawName, "name");
        if (rawXml == null || rawXml.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_XML",
                    "Drawable \"" + name + "\" requires non-empty XML content.");
        }
        String xml = rawXml.trim();
        // Auto-inject xmlns:android on the first start tag if omitted
        Matcher firstTag = Pattern.compile("<([a-zA-Z][a-zA-Z0-9_-]*)(\\s|>|/)")
                .matcher(xml);
        if (firstTag.find() && !xml.contains("xmlns:android=")) {
            int insertPos = firstTag.end(1);
            xml = xml.substring(0, insertPos)
                    + " xmlns:android=\"http://schemas.android.com/apk/res/android\""
                    + xml.substring(insertPos);
        }
        if (!xml.startsWith("<?xml")) {
            xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + xml;
        }

        String rootTag = null;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && rootTag == null) {
                    rootTag = parser.getName();
                }
                event = parser.next();
            }
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_BAD_XML",
                    "Drawable \"" + name + "\" is not valid XML: "
                            + e.getMessage());
        }
        if (rootTag == null || !VALID_DRAWABLE_ROOTS.contains(rootTag)) {
            throw new AgentTools.ToolException("E_BAD_XML",
                    "Drawable \"" + name + "\" has unsupported root tag <"
                            + rootTag + ">. Supported Android drawable roots: "
                            + "shape, vector, ripple, selector, layer-list, "
                            + "inset, clip, scale, rotate.");
        }
        return new DrawableSpec(name, xml, rootTag);
    }

    /** Writes one or more validated custom XML drawables to {@code files/resource/drawable/}. */
    public static JsonArray upsertCustomDrawables(String scId,
                                                  List<DrawableSpec> specs)
            throws AgentTools.ToolException {
        if (specs == null || specs.isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_ARGS",
                    "Provide at least one drawable to save.");
        }
        try {
            SketchwareActions.backupProjectOnce(scId);
        } catch (IOException e) {
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "Could not back up project: " + e.getMessage());
        }

        File drawableDir = new File(
                new FilePathUtil().getPathResource(scId), "drawable");
        //noinspection ResultOfMethodCallIgnored
        drawableDir.mkdirs();

        JsonArray saved = new JsonArray();
        for (DrawableSpec spec : specs) {
            File outFile = new File(drawableDir, spec.name + ".xml");
            FileUtil.writeFile(outFile.getAbsolutePath(), spec.xml);
            if (!outFile.isFile() || outFile.length() == 0) {
                throw new AgentTools.ToolException("E_WRITE_FAILED",
                        "Failed to write drawable \"" + spec.name + ".xml\".");
            }
            JsonObject item = new JsonObject();
            item.addProperty("name", spec.name);
            item.addProperty("root_tag", spec.rootTag);
            item.addProperty("path", "res/drawable/" + spec.name + ".xml");
            saved.add(item);
        }
        return saved;
    }

    /** Deletes a custom XML drawable or Project Image Manager entry, guarding active widget references. */
    public static JsonObject deleteDrawableOrImage(String scId,
                                                   String rawName,
                                                   boolean force)
            throws AgentTools.ToolException {
        String name = validateResName(rawName, "name");
        if (!force) {
            List<String> refs = findDrawableReferences(scId, name);
            if (!refs.isEmpty()) {
                throw new AgentTools.ToolException("E_IN_USE",
                        "Drawable \"" + name + "\" is still used on: "
                                + String.join(", ", refs)
                                + ". Change those widgets first or pass force: true.");
            }
        }
        try {
            SketchwareActions.backupProjectOnce(scId);
        } catch (IOException e) {
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "Could not back up project: " + e.getMessage());
        }

        boolean removedCustomXml = false;
        File customXml = new File(customDrawablePath(scId, name));
        if (customXml.exists()) {
            removedCustomXml = customXml.delete();
        }

        boolean removedProjectImage = false;
        resetResourceManager(scId);
        kC resMgr = jC.d(scId);
        if (resMgr.b != null) {
            ArrayList<ProjectResourceBean> kept = new ArrayList<>();
            for (ProjectResourceBean bean : resMgr.b) {
                if (name.equals(bean.resName)) {
                    removedProjectImage = true;
                    File tempImg = new File(resMgr.l(), bean.resFullName);
                    if (tempImg.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        tempImg.delete();
                    }
                    File svgCopy = new File(
                            new FilePathUtil().getSvgFullPath(scId, name));
                    if (svgCopy.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        svgCopy.delete();
                    }
                } else {
                    kept.add(bean.clone());
                }
            }
            if (removedProjectImage) {
                resMgr.b(kept);
                resMgr.y();
                jC.a(scId).k();
                SketchwareActions.commitProject(scId);
                kC.z();
                resetResourceManager(scId);
            }
        }

        if (!removedCustomXml && !removedProjectImage) {
            throw new AgentTools.ToolException("E_NOT_FOUND",
                    "No project image or custom XML drawable named \""
                            + name + "\" exists.");
        }
        JsonObject out = new JsonObject();
        out.addProperty("deleted", name);
        out.addProperty("removed_custom_xml", removedCustomXml);
        out.addProperty("removed_project_image", removedProjectImage);
        return out;
    }

    /** Finds any screen/custom-view widgets referencing {@code resName}. */
    public static List<String> findDrawableReferences(String scId,
                                                      String resName) {
        List<String> refs = new ArrayList<>();
        List<ProjectFileBean> allScreens = new ArrayList<>();
        allScreens.addAll(SketchwareProjects.activities(scId));
        allScreens.addAll(SketchwareProjects.customViews(scId));
        for (ProjectFileBean screen : allScreens) {
            for (ViewBean bean : SketchwareProjects.screenBeans(scId, screen)) {
                if (bean.image != null
                        && resName.equals(bean.image.resName)) {
                    refs.add(screen.fileName + "." + bean.id + " (image)");
                }
                if (bean.layout != null
                        && resName.equals(bean.layout.backgroundResource)) {
                    refs.add(screen.fileName + "." + bean.id
                            + " (background)");
                }
            }
            if (screen.fileType == ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
                try {
                    ViewBean fab = jC.a(scId).h(screen.getXmlName());
                    if (fab != null && fab.image != null
                            && resName.equals(fab.image.resName)) {
                        refs.add(screen.fileName + "._fab (icon)");
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return refs;
    }

    /**
     * Generates and persists standard + optional Android Adaptive Launcher
     * Icons across all {@code mipmap-*} densities, {@code icon.png},
     * {@code app-icon/data.json}, and {@link lC} project metadata.
     */
    public static JsonObject setAppIcon(Context context,
                                        String scId,
                                        AppIconSpec spec)
            throws AgentTools.ToolException {
        boolean hasAtt = spec.attachmentId != null
                && !spec.attachmentId.trim().isEmpty();
        boolean hasIcon = spec.builtinIcon != null
                && !spec.builtinIcon.trim().isEmpty();
        boolean hasText = spec.monogramText != null
                && !spec.monogramText.trim().isEmpty();
        if (!hasAtt && !hasIcon && !hasText) {
            throw new AgentTools.ToolException("E_BAD_ARGS",
                    "set_app_icon needs one foreground source: "
                            + "\"attachment_id\" (attached chat image), "
                            + "\"builtin_icon\" (Material icon name), or "
                            + "\"text\" (1-3 monogram characters).");
        }

        int fgColor = Color.parseColor(
                normalizeHexColor(spec.foregroundColorHex, "#FFFFFF"));
        int bgColor = Color.parseColor(
                normalizeHexColor(spec.backgroundColorHex, "#4F46E5"));
        boolean useGradient = spec.gradientStartHex != null
                && !spec.gradientStartHex.trim().isEmpty()
                && spec.gradientEndHex != null
                && !spec.gradientEndHex.trim().isEmpty();
        int gradStart = useGradient
                ? Color.parseColor(
                        normalizeHexColor(spec.gradientStartHex, "#4F46E5"))
                : bgColor;
        int gradEnd = useGradient
                ? Color.parseColor(
                        normalizeHexColor(spec.gradientEndHex, "#7C3AED"))
                : bgColor;

        // Render master foreground glyph on a 432x432 transparent canvas
        Bitmap masterGlyph = null;
        String sourceSummary;
        if (hasAtt) {
            File attFile = AgentMedia.findAttachmentFile(
                    scId, spec.attachmentId);
            if (attFile == null || !attFile.isFile()) {
                throw new AgentTools.ToolException("E_NO_ATTACHMENT",
                        "Chat attachment \"" + spec.attachmentId
                                + "\" was not found.");
            }
            masterGlyph = decodeScaledFile(attFile, 512);
            if (masterGlyph == null) {
                throw new AgentTools.ToolException("E_BAD_IMAGE",
                        "Could not decode attachment image \""
                                + attFile.getName() + "\".");
            }
            sourceSummary = "chat image " + attFile.getName();
        } else if (hasIcon) {
            File svgFile = resolveBuiltinIconSvg(
                    context, spec.builtinIcon, spec.builtinStyle);
            masterGlyph = renderSvgToBitmap(svgFile, 432, fgColor);
            if (masterGlyph == null) {
                throw new AgentTools.ToolException("E_ICON_RENDER",
                        "Could not render built-in icon \""
                                + spec.builtinIcon + "\" to bitmap.");
            }
            sourceSummary = "Material icon " + spec.builtinIcon
                    + " (" + normalizeIconStyle(spec.builtinStyle) + ")";
        } else {
            String label = spec.monogramText.trim();
            if (label.length() > 3) {
                label = label.substring(0, 3);
            }
            masterGlyph = renderMonogramBitmap(label, 432, fgColor);
            sourceSummary = "monogram \"" + label + "\"";
        }

        try {
            SketchwareActions.backupProjectOnce(scId);
        } catch (IOException e) {
            masterGlyph.recycle();
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "Could not back up project: " + e.getMessage());
        }

        String iconsRoot = wq.e() + File.separator + scId;
        String mipmapsRoot = iconsRoot + File.separator + "mipmaps";
        FileUtil.deleteFile(mipmapsRoot);
        new oB().f(iconsRoot);

        try {
            for (String[] bucket : MIPMAP_BUCKETS) {
                String folder = bucket[0];
                int legacySize = Integer.parseInt(bucket[1]);
                int adaptiveSize = Integer.parseInt(bucket[2]);
                File bucketDir = new File(mipmapsRoot, folder);
                //noinspection ResultOfMethodCallIgnored
                bucketDir.mkdirs();

                Bitmap legacyBmp = composeLegacyLauncherBitmap(
                        masterGlyph, legacySize, spec.cornerRadiusRatio,
                        useGradient, bgColor, gradStart, gradEnd,
                        spec.gradientDirection, hasAtt && spec.fullBleed);
                writePng(legacyBmp, new File(bucketDir, "ic_launcher.png"));
                if ("mipmap-xxxhdpi".equals(folder)) {
                    writePng(legacyBmp, new File(iconsRoot, "icon.png"));
                    saveIconCreatorMetadata(scId, legacyBmp, spec,
                            bgColor, gradStart, gradEnd, fgColor, useGradient);
                }
                legacyBmp.recycle();

                if (spec.adaptive) {
                    Bitmap bgBmp = composeAdaptiveBackground(
                            adaptiveSize, useGradient, bgColor,
                            gradStart, gradEnd, spec.gradientDirection);
                    Bitmap fgBmp = composeAdaptiveForeground(
                            masterGlyph, adaptiveSize,
                            hasAtt && spec.fullBleed, false);
                    Bitmap monoBmp = composeAdaptiveForeground(
                            masterGlyph, adaptiveSize,
                            hasAtt && spec.fullBleed, true);
                    writePng(bgBmp, new File(
                            bucketDir, "ic_launcher_background.png"));
                    writePng(fgBmp, new File(
                            bucketDir, "ic_launcher_foreground.png"));
                    writePng(monoBmp, new File(
                            bucketDir, "ic_launcher_monochrome.png"));
                    bgBmp.recycle();
                    fgBmp.recycle();
                    monoBmp.recycle();
                }
            }
        } catch (IOException e) {
            masterGlyph.recycle();
            throw new AgentTools.ToolException("E_WRITE_FAILED",
                    "Failed writing launcher icon mipmaps: "
                            + e.getMessage());
        }
        masterGlyph.recycle();

        HashMap<String, Object> metadata = lC.b(scId);
        if (metadata == null) {
            throw new AgentTools.ToolException("E_NO_PROJECT",
                    "Could not load project registry metadata for " + scId);
        }
        metadata.put("custom_icon", true);
        metadata.put("isIconAdaptive", spec.adaptive);
        lC.b(scId, metadata);

        HashMap<String, Object> verifyMeta = lC.b(scId);
        File verifyIcon = new File(iconsRoot, "icon.png");
        if (!verifyIcon.isFile() || verifyIcon.length() == 0
                || verifyMeta == null
                || !yB.a(verifyMeta, "custom_icon")) {
            throw new AgentTools.ToolException("E_VERIFY_FAILED",
                    "App icon files were written, but registry verification failed.");
        }

        JsonObject out = new JsonObject();
        out.addProperty("custom_icon", true);
        out.addProperty("adaptive", spec.adaptive);
        out.addProperty("source", sourceSummary);
        out.addProperty("densities_written", MIPMAP_BUCKETS.length);
        return out;
    }

    /** Renders a raw SVG file onto an ARGB_8888 Bitmap using AndroidSVG, applying optional fill tint. */
    public static Bitmap renderSvgToBitmap(File svgFile, int sizePx,
                                           Integer tintColor) {
        if (svgFile == null || !svgFile.isFile()) {
            return null;
        }
        try {
            String raw = new String(
                    Files.readAllBytes(svgFile.toPath()),
                    StandardCharsets.UTF_8);
            SVG svg = SVG.getFromString(raw);
            svg.setDocumentWidth("100%");
            svg.setDocumentHeight("100%");
            Bitmap rawBmp = Bitmap.createBitmap(
                    sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(rawBmp);
            svg.renderToCanvas(canvas);
            if (tintColor == null) {
                return rawBmp;
            }
            Bitmap tinted = Bitmap.createBitmap(
                    sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas tintCanvas = new Canvas(tinted);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColorFilter(new PorterDuffColorFilter(
                    tintColor, PorterDuff.Mode.SRC_IN));
            tintCanvas.drawBitmap(rawBmp, 0f, 0f, paint);
            rawBmp.recycle();
            return tinted;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap renderMonogramBitmap(String text, int sizePx,
                                               int textColor) {
        Bitmap bmp = Bitmap.createBitmap(
                sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(textColor);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        float fontRatio = text.length() == 1 ? 0.54f
                : (text.length() == 2 ? 0.42f : 0.33f);
        paint.setTextSize(sizePx * fontRatio);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float centerY = (sizePx / 2f) - ((fm.ascent + fm.descent) / 2f);
        canvas.drawText(text, sizePx / 2f, centerY, paint);
        return bmp;
    }

    private static Bitmap composeLegacyLauncherBitmap(
            Bitmap glyph, int sizePx, float radiusRatio,
            boolean useGradient, int bgColor, int gradStart, int gradEnd,
            String gradDirection, boolean fullBleed) {
        Bitmap out = Bitmap.createBitmap(
                sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float inset = Math.max(1f, sizePx * 0.04f);
        RectF card = new RectF(
                inset, inset, sizePx - inset, sizePx - inset);
        float radius = card.width()
                * Math.max(0f, Math.min(radiusRatio, 0.5f));

        Path clip = new Path();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        Paint bgPaint = buildBackgroundPaint(
                sizePx, useGradient, bgColor, gradStart, gradEnd, gradDirection);
        canvas.drawRect(card, bgPaint);

        RectF dst;
        if (fullBleed) {
            dst = card;
        } else {
            float glyphBox = card.width() * 0.62f;
            dst = fitInsideSquare(
                    glyph.getWidth(), glyph.getHeight(),
                    sizePx / 2f, sizePx / 2f, glyphBox);
        }
        Paint imgPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(glyph, null, dst, imgPaint);
        canvas.restore();
        return out;
    }

    private static Bitmap composeAdaptiveBackground(
            int sizePx, boolean useGradient, int bgColor,
            int gradStart, int gradEnd, String gradDirection) {
        Bitmap out = Bitmap.createBitmap(
                sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint bgPaint = buildBackgroundPaint(
                sizePx, useGradient, bgColor, gradStart, gradEnd, gradDirection);
        canvas.drawRect(0f, 0f, sizePx, sizePx, bgPaint);
        return out;
    }

    private static Bitmap composeAdaptiveForeground(
            Bitmap glyph, int sizePx, boolean fullBleed, boolean monochrome) {
        Bitmap out = Bitmap.createBitmap(
                sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        // Android 108dp adaptive icon spec: inner 66dp circle is the guaranteed safe zone (66/108 = 0.611)
        float ratio = fullBleed ? 0.66f : 0.48f;
        float glyphBox = sizePx * ratio;
        RectF dst = fitInsideSquare(
                glyph.getWidth(), glyph.getHeight(),
                sizePx / 2f, sizePx / 2f, glyphBox);
        Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (monochrome) {
            paint.setColorFilter(new PorterDuffColorFilter(
                    Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        canvas.drawBitmap(glyph, null, dst, paint);
        return out;
    }

    private static Paint buildBackgroundPaint(
            int sizePx, boolean useGradient, int bgColor,
            int gradStart, int gradEnd, String direction) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (!useGradient) {
            paint.setColor(bgColor);
            return paint;
        }
        String dir = direction == null ? "TL_BR"
                : direction.trim().toUpperCase(Locale.ROOT);
        float x0 = 0f, y0 = 0f, x1 = sizePx, y1 = sizePx;
        switch (dir) {
            case "TOP_BOTTOM" -> {
                x0 = sizePx / 2f;
                y0 = 0f;
                x1 = sizePx / 2f;
                y1 = sizePx;
            }
            case "BOTTOM_TOP" -> {
                x0 = sizePx / 2f;
                y0 = sizePx;
                x1 = sizePx / 2f;
                y1 = 0f;
            }
            case "LEFT_RIGHT" -> {
                x0 = 0f;
                y0 = sizePx / 2f;
                x1 = sizePx;
                y1 = sizePx / 2f;
            }
            case "RIGHT_LEFT" -> {
                x0 = sizePx;
                y0 = sizePx / 2f;
                x1 = 0f;
                y1 = sizePx / 2f;
            }
            case "BL_TR" -> {
                x0 = 0f;
                y0 = sizePx;
                x1 = sizePx;
                y1 = 0f;
            }
            case "TR_BL" -> {
                x0 = sizePx;
                y0 = 0f;
                x1 = 0f;
                y1 = sizePx;
            }
            default -> {
                // TL_BR
            }
        }
        paint.setShader(new LinearGradient(
                x0, y0, x1, y1, gradStart, gradEnd, Shader.TileMode.CLAMP));
        return paint;
    }

    private static RectF fitInsideSquare(int srcW, int srcH,
                                         float cx, float cy, float maxBox) {
        if (srcW <= 0 || srcH <= 0) {
            float half = maxBox / 2f;
            return new RectF(cx - half, cy - half, cx + half, cy + half);
        }
        float scale = Math.min(maxBox / srcW, maxBox / srcH);
        float w = srcW * scale;
        float h = srcH * scale;
        return new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    private static void saveIconCreatorMetadata(
            String scId, Bitmap previewBmp, AppIconSpec spec,
            int bgColor, int gradStart, int gradEnd, int fgColor,
            boolean useGradient) {
        try {
            String baseDir = wq.b(scId) + File.separator + "files"
                    + File.separator + "app-icon" + File.separator;
            new File(baseDir).mkdirs();
            writePng(previewBmp, new File(baseDir, "app_icon.png"));
            Map<String, Object> data = new HashMap<>();
            data.put("gradient_background_type", useGradient);
            data.put("background_color", bgColor);
            data.put("gradient_color1", gradStart);
            data.put("gradient_color2", gradEnd);
            data.put("gradient_direction",
                    spec.gradientDirection == null
                            ? "TL_BR" : spec.gradientDirection);
            data.put("text",
                    spec.monogramText == null ? "" : spec.monogramText);
            data.put("text_color", fgColor);
            data.put("adaptive_icon", spec.adaptive);
            FileUtil.writeFile(baseDir + "data.json", new Gson().toJson(data));
        } catch (Exception ignored) {
        }
    }

    private static void writePng(Bitmap bitmap, File target)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(target)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new IOException("PNG compress failed for "
                        + target.getAbsolutePath());
            }
            fos.flush();
        }
    }

    private static Bitmap decodeScaledFile(File file, int maxEdgePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = AgentMedia.computeSampleSize(
                    bounds.outWidth, bounds.outHeight, maxEdgePx * 2);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(
                    file.getAbsolutePath(), opts);
            if (decoded == null) {
                return null;
            }
            Bitmap scaled = AgentMedia.scaleDown(decoded, maxEdgePx);
            if (scaled != decoded) {
                decoded.recycle();
            }
            return scaled;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void copyFileBytes(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String customDrawablePath(String scId, String name) {
        return new FilePathUtil().getPathResource(scId)
                + File.separator + "drawable"
                + File.separator + name + ".xml";
    }
}
