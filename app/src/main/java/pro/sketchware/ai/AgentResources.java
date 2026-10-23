package pro.sketchware.ai;

import android.content.Context;
import android.graphics.Typeface;

import com.besome.sketch.beans.BlockBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ProjectResourceBean;
import com.besome.sketch.beans.ViewBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import a.a.a.Np;
import a.a.a.Qp;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.kC;
import a.a.a.uq;
import a.a.a.wq;
import a.a.a.yq;
import pro.sketchware.R;
import pro.sketchware.SketchApplication;
import pro.sketchware.activities.resourceseditor.ResourcesEditorActivity;
import pro.sketchware.utility.FilePathUtil;
import pro.sketchware.utility.FileUtil;

/**
 * Phase 4 backend for Sketchware Pro's Resources Editor
 * ({@code values} and {@code values-<variant>} XML files: {@code strings.xml},
 * {@code colors.xml}, {@code styles.xml}, {@code themes.xml},
 * {@code arrays.xml}), Raw {@code res/} XML Resource Manager
 * ({@code ManageResourceActivity}: {@code menu}, {@code anim}, {@code animator},
 * {@code xml}, {@code color}, {@code drawable}, etc.), Font Manager
 * ({@code ManageFontActivity} / {@code kC.d}), and Sound Manager
 * ({@code ManageSoundActivity} / {@code kC.c}).
 *
 * <p>Avoids instantiating {@code ColorsEditorManager} directly because its
 * constructor reads static {@code DesignActivity.sc_id}, which is {@code null}
 * when Agent Mode runs from {@code MainActivity}. Emits standard Android
 * {@code <item>...</item>} children in {@code arrays.xml} so both
 * {@code aapt2 compile} and {@code ArraysEditorManager.parseArraysFile}
 * succeed cleanly.
 */
public final class AgentResources {

    private static final Pattern RES_KEY_PATTERN =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.]*$");
    private static final Pattern STRICT_ID_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Pattern VARIANT_SUFFIX_PATTERN =
            Pattern.compile("^-[a-zA-Z0-9-]+$");
    private static final Pattern FOLDER_NAME_PATTERN =
            Pattern.compile(
                    "^(anim|animator|color|drawable|font|interpolator|layout"
                            + "|menu|mipmap|navigation|raw|transition|values|xml)"
                            + "(-[a-zA-Z0-9-]+)?$");
    private static final Pattern XML_FILE_NAME_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_]*\\.xml$");
    private static final Pattern HEX_COLOR_PATTERN =
            Pattern.compile("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}"
                    + "|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$");

    private static final Set<String> DEFAULT_COLOR_KEYS = Set.of(
            "colorPrimary",
            "colorPrimaryDark",
            "colorAccent",
            "colorControlHighlight",
            "colorControlNormal");

    private static final Set<String> PROTECTED_STYLE_NAMES = Set.of(
            "AppTheme",
            "AppTheme.FullScreen",
            "AppTheme.AppBarOverlay",
            "AppTheme.PopupOverlay",
            "AppTheme.DebugActivity");

    public static final List<String> BUNDLED_FONTS = List.of(
            "gilroy",
            "jetbrains_mono",
            "droid_sans_mono");

    public static final List<String> SOUND_PRESETS = List.of(
            "click",
            "pop",
            "success",
            "complete",
            "error",
            "notification",
            "alarm");

    private AgentResources() {
    }

    // -----------------------------------------------------------------
    // Values variant paths & XML helpers
    // -----------------------------------------------------------------

    /**
     * Normalizes a values qualifier to the suffix Sketchware Pro appends
     * after {@code "values"}: {@code ""} for base {@code values/},
     * {@code "-night"} for dark mode, {@code "-es"} or {@code "-pt-rBR"}
     * for localized variants.
     */
    public static String normalizeVariant(String raw)
            throws AgentTools.ToolException {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()
                || "default".equalsIgnoreCase(trimmed)
                || "base".equalsIgnoreCase(trimmed)
                || "values".equalsIgnoreCase(trimmed)) {
            return "";
        }
        if ("dark".equalsIgnoreCase(trimmed)) {
            return "-night";
        }
        String suffix;
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("values-")) {
            suffix = trimmed.substring("values".length());
        } else if (trimmed.startsWith("-")) {
            suffix = trimmed;
        } else {
            suffix = "-" + trimmed;
        }
        if (!VARIANT_SUFFIX_PATTERN.matcher(suffix).matches()
                || suffix.contains("--")) {
            throw new AgentTools.ToolException("E_BAD_VARIANT",
                    "Invalid values variant \"" + raw
                            + "\". Examples: \"\" (default), \"night\", "
                            + "\"es\", \"fr\", \"ar\", \"pt-rBR\", "
                            + "\"values-night-v31\".");
        }
        return suffix;
    }

    public static String variantFolderName(String normalizedSuffix) {
        return "values" + (normalizedSuffix == null ? "" : normalizedSuffix);
    }

    public static String valuesFilePath(String scId, String normalizedSuffix,
                                        String fileName) {
        return wq.b(scId) + "/files/resource/values"
                + (normalizedSuffix == null ? "" : normalizedSuffix)
                + "/" + fileName;
    }

    /** Lists all {@code values*} folders currently present in {@code files/resource/}. */
    public static List<String> listValueVariants(String scId) {
        List<String> out = new ArrayList<>();
        out.add("values");
        File resDir = new File(new FilePathUtil().getPathResource(scId));
        File[] children = resDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child != null && child.isDirectory()) {
                    String name = child.getName();
                    if (name.startsWith("values-") && !out.contains(name)) {
                        out.add(name);
                    }
                }
            }
        }
        Collections.sort(out, (a, b) -> {
            if ("values".equals(a)) return -1;
            if ("values".equals(b)) return 1;
            return a.compareTo(b);
        });
        return out;
    }

    private static yq newProjectYq(String scId) {
        Context ctx = SketchApplication.getContext();
        yq helper = new yq(ctx, scId);
        helper.a(jC.c(scId), jC.b(scId), jC.a(scId));
        return helper;
    }

    /** Parses and validates XML well-formedness, disallowing XXE/DOCTYPE. */
    public static Document parseXmlDocument(String xml, String label)
            throws AgentTools.ToolException {
        if (xml == null || xml.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_XML",
                    label + " XML cannot be empty.");
        }
        String cleaned = xml.startsWith("\uFEFF")
                ? xml.substring(1).trim() : xml.trim();
        try {
            DocumentBuilderFactory factory =
                    DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            try {
                factory.setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true);
            } catch (Exception ignored) {
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(cleaned)));
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_BAD_XML",
                    label + " is not valid XML: "
                            + String.valueOf(e.getMessage()));
        }
    }

    private static String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeAndroidStringValue(String value) {
        if (value == null) return "";
        String normalized = value.replace("\\'", "'");
        return ResourcesEditorActivity.escapeXml(normalized);
    }

    private static String unescapeAndroidStringValue(String text) {
        if (text == null) return "";
        return text.replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\n", "\n");
    }

    // -----------------------------------------------------------------
    // 1. strings.xml
    // -----------------------------------------------------------------

    public static final class StringEntry {
        public final String name;
        public final String value;
        public final boolean translatable;

        public StringEntry(String name, String value, boolean translatable) {
            this.name = name;
            this.value = value == null ? "" : value;
            this.translatable = translatable;
        }
    }

    public static LinkedHashMap<String, StringEntry> readStrings(
            String scId, String variantSuffix) throws AgentTools.ToolException {
        String path = valuesFilePath(scId, variantSuffix, "strings.xml");
        String xml;
        if (FileUtil.isExistFile(path)) {
            xml = FileUtil.readFile(path);
        } else if (variantSuffix == null || variantSuffix.isEmpty()) {
            xml = newProjectYq(scId).getXMLString();
        } else {
            return new LinkedHashMap<>();
        }
        return parseStringsXml(xml);
    }

    private static LinkedHashMap<String, StringEntry> parseStringsXml(
            String xml) throws AgentTools.ToolException {
        LinkedHashMap<String, StringEntry> map = new LinkedHashMap<>();
        if (xml == null || xml.trim().isEmpty()) {
            return map;
        }
        Document doc = parseXmlDocument(xml, "strings.xml");
        NodeList nodes = doc.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String name = el.getAttribute("name").trim();
            if (name.isEmpty()) continue;
            String transAttr = el.getAttribute("translatable").trim();
            boolean translatable = !"false".equalsIgnoreCase(transAttr);
            String val = unescapeAndroidStringValue(el.getTextContent());
            map.put(name, new StringEntry(name, val, translatable));
        }
        return map;
    }

    public static String serializeStringsXml(
            Map<String, StringEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<resources>\n");
        for (StringEntry entry : entries.values()) {
            sb.append("    <string name=\"")
                    .append(escapeAttr(entry.name))
                    .append("\"");
            if (!entry.translatable) {
                sb.append(" translatable=\"false\"");
            }
            sb.append(">")
                    .append(escapeAndroidStringValue(entry.value))
                    .append("</string>\n");
        }
        sb.append("</resources>\n");
        return sb.toString();
    }

    public static void validateStringEntries(
            String variantSuffix,
            Map<String, StringEntry> upserts,
            List<String> deletes) throws AgentTools.ToolException {
        if (upserts != null) {
            for (String key : upserts.keySet()) {
                if (!STRICT_ID_PATTERN.matcher(key).matches()) {
                    throw new AgentTools.ToolException("E_BAD_NAME",
                            "String resource name \"" + key
                                    + "\" must start with a lowercase letter "
                                    + "and use lowercase letters, digits, and "
                                    + "underscores.");
                }
            }
        }
        if (deletes != null) {
            boolean isBase = variantSuffix == null || variantSuffix.isEmpty();
            for (String key : deletes) {
                if (isBase && "app_name".equals(key)) {
                    throw new AgentTools.ToolException("E_PROTECTED_RESOURCE",
                            "Cannot delete \"app_name\" from base "
                                    + "values/strings.xml (update its value "
                                    + "instead).");
                }
            }
        }
    }

    public static LinkedHashMap<String, StringEntry> writeStrings(
            String scId, String variantSuffix,
            Map<String, StringEntry> upserts,
            List<String> deletes) throws Exception {
        validateStringEntries(variantSuffix, upserts, deletes);
        LinkedHashMap<String, StringEntry> current =
                readStrings(scId, variantSuffix);
        if ((variantSuffix == null || variantSuffix.isEmpty())
                && !current.containsKey("app_name")) {
            SketchwareProjects.ProjectInfo info =
                    SketchwareProjects.project(scId);
            String appName = info != null && info.name != null
                    ? info.name : "App";
            current.put("app_name",
                    new StringEntry("app_name", appName, false));
        }
        if (deletes != null) {
            for (String del : deletes) {
                current.remove(del);
            }
        }
        if (upserts != null) {
            for (Map.Entry<String, StringEntry> e : upserts.entrySet()) {
                current.put(e.getKey(), e.getValue());
            }
        }
        String xml = serializeStringsXml(current);
        parseXmlDocument(xml, "strings.xml");
        SketchwareActions.backupProjectOnce(scId);
        String path = valuesFilePath(scId, variantSuffix, "strings.xml");
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileUtil.writeFile(path, xml);
        LinkedHashMap<String, StringEntry> verify = parseStringsXml(
                FileUtil.readFile(path));
        if (verify.size() != current.size()) {
            throw new IOException("Wrote " + current.size()
                    + " strings, but read back " + verify.size() + ".");
        }
        return verify;
    }

    // -----------------------------------------------------------------
    // 2. colors.xml
    // -----------------------------------------------------------------

    public static LinkedHashMap<String, String> readColors(
            String scId, String variantSuffix) throws AgentTools.ToolException {
        String path = valuesFilePath(scId, variantSuffix, "colors.xml");
        String xml;
        if (FileUtil.isExistFile(path)) {
            xml = FileUtil.readFile(path);
        } else if (variantSuffix == null || variantSuffix.isEmpty()) {
            xml = newProjectYq(scId).getXMLColor();
        } else {
            return new LinkedHashMap<>();
        }
        return parseColorsXml(xml);
    }

    private static LinkedHashMap<String, String> parseColorsXml(String xml)
            throws AgentTools.ToolException {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (xml == null || xml.trim().isEmpty()) {
            return map;
        }
        Document doc = parseXmlDocument(xml, "colors.xml");
        NodeList nodes = doc.getElementsByTagName("color");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String name = el.getAttribute("name").trim();
            String value = el.getTextContent() == null ? ""
                    : el.getTextContent().trim();
            if (!name.isEmpty() && !value.isEmpty()) {
                map.put(name, normalizeColorXmlValue(value));
            }
        }
        return map;
    }

    private static String normalizeColorXmlValue(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.startsWith("#")) {
            return "#" + trimmed.substring(1).toUpperCase(Locale.ROOT);
        }
        return trimmed;
    }

    public static void validateColorEntries(
            String variantSuffix,
            Map<String, String> upserts,
            List<String> deletes) throws AgentTools.ToolException {
        if (upserts != null) {
            for (Map.Entry<String, String> e : upserts.entrySet()) {
                String key = e.getKey();
                if (!RES_KEY_PATTERN.matcher(key).matches()
                        || key.contains(".")) {
                    throw new AgentTools.ToolException("E_BAD_NAME",
                            "Color resource name \"" + key
                                    + "\" must start with a letter and use "
                                    + "letters, digits, and underscores.");
                }
                String val = e.getValue() == null ? "" : e.getValue().trim();
                boolean validHex = HEX_COLOR_PATTERN.matcher(val).matches();
                boolean validRef = val.matches(
                        "^@(android:)?color/[a-zA-Z0-9_]+$");
                if (!validHex && !validRef) {
                    throw new AgentTools.ToolException("E_BAD_COLOR",
                            "Invalid color value \"" + val + "\" for \""
                                    + key + "\". Use #RRGGBB, #AARRGGBB, or "
                                    + "@color/other_color.");
                }
            }
        }
        if (deletes != null
                && (variantSuffix == null || variantSuffix.isEmpty())) {
            for (String del : deletes) {
                if (DEFAULT_COLOR_KEYS.contains(del)) {
                    throw new AgentTools.ToolException("E_PROTECTED_RESOURCE",
                            "Cannot delete base theme color \"" + del
                                    + "\" from values/colors.xml (update its "
                                    + "hex value instead).");
                }
            }
        }
    }

    public static String serializeColorsXml(Map<String, String> colors) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<resources>\n");
        for (Map.Entry<String, String> entry : colors.entrySet()) {
            sb.append("    <color name=\"")
                    .append(escapeAttr(entry.getKey()))
                    .append("\">")
                    .append(escapeAttr(normalizeColorXmlValue(
                            entry.getValue())))
                    .append("</color>\n");
        }
        sb.append("</resources>\n");
        return sb.toString();
    }

    public static LinkedHashMap<String, String> writeColors(
            String scId, String variantSuffix,
            Map<String, String> upserts,
            List<String> deletes) throws Exception {
        validateColorEntries(variantSuffix, upserts, deletes);
        LinkedHashMap<String, String> current =
                readColors(scId, variantSuffix);
        if (deletes != null) {
            for (String del : deletes) {
                current.remove(del);
            }
        }
        if (upserts != null) {
            for (Map.Entry<String, String> e : upserts.entrySet()) {
                current.put(e.getKey(), normalizeColorXmlValue(e.getValue()));
            }
        }
        String xml = serializeColorsXml(current);
        parseXmlDocument(xml, "colors.xml");
        SketchwareActions.backupProjectOnce(scId);
        String path = valuesFilePath(scId, variantSuffix, "colors.xml");
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileUtil.writeFile(path, xml);
        LinkedHashMap<String, String> verify = parseColorsXml(
                FileUtil.readFile(path));
        if (verify.size() != current.size()) {
            throw new IOException("Wrote " + current.size()
                    + " colors, but read back " + verify.size() + ".");
        }
        return verify;
    }

    /**
     * Resolves {@code @color/<name>} against the working project's
     * {@code values/colors.xml} (or generated default colors), returning
     * {@code null} when the color name does not exist.
     */
    public static Integer resolveColorInt(String scId, String colorName) {
        if (scId == null || scId.isEmpty()
                || colorName == null || colorName.isEmpty()) {
            return null;
        }
        try {
            LinkedHashMap<String, String> colors = readColors(scId, "");
            return resolveColorFromMap(colors, colorName, 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer resolveColorFromMap(
            Map<String, String> colors, String name, int depth) {
        if (depth > 6 || colors == null) {
            return null;
        }
        String raw = colors.get(name);
        if (raw == null) {
            return null;
        }
        if (raw.startsWith("@color/")) {
            return resolveColorFromMap(
                    colors, raw.substring("@color/".length()), depth + 1);
        }
        if (raw.startsWith("@android:color/")) {
            return switch (raw.substring("@android:color/".length())) {
                case "white" -> 0xFFFFFFFF;
                case "black" -> 0xFF000000;
                case "transparent" -> 0x00000000;
                default -> 0xFF808080;
            };
        }
        return parseHexToArgbInt(raw);
    }

    private static Integer parseHexToArgbInt(String hexValue) {
        if (hexValue == null || !hexValue.startsWith("#")) {
            return null;
        }
        String h = hexValue.substring(1);
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
            int parsed = (int) Long.parseLong(h, 16);
            if (parsed == 0 || parsed == 0xFFFFFF) {
                return 0xFF808080;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    // 3. styles.xml & themes.xml
    // -----------------------------------------------------------------

    public static final class StyleSpec {
        public final String name;
        public String parent;
        public final LinkedHashMap<String, String> items;
        public final boolean replaceItems;

        public StyleSpec(String name, String parent,
                         Map<String, String> items, boolean replaceItems) {
            this.name = name;
            this.parent = parent == null ? "" : parent.trim();
            this.items = new LinkedHashMap<>();
            if (items != null) {
                this.items.putAll(items);
            }
            this.replaceItems = replaceItems;
        }
    }

    public static LinkedHashMap<String, StyleSpec> readStylesFile(
            String scId, String variantSuffix, boolean themesFile)
            throws AgentTools.ToolException {
        String fileName = themesFile ? "themes.xml" : "styles.xml";
        String path = valuesFilePath(scId, variantSuffix, fileName);
        String xml;
        if (FileUtil.isExistFile(path)) {
            xml = FileUtil.readFile(path);
        } else if (!themesFile
                && (variantSuffix == null || variantSuffix.isEmpty())) {
            xml = newProjectYq(scId).getXMLStyle();
        } else {
            return new LinkedHashMap<>();
        }
        return parseStylesXml(xml, fileName);
    }

    private static LinkedHashMap<String, StyleSpec> parseStylesXml(
            String xml, String label) throws AgentTools.ToolException {
        LinkedHashMap<String, StyleSpec> map = new LinkedHashMap<>();
        if (xml == null || xml.trim().isEmpty()) {
            return map;
        }
        Document doc = parseXmlDocument(xml, label);
        NodeList styles = doc.getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            Node n = styles.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element styleEl = (Element) n;
            String name = styleEl.getAttribute("name").trim();
            if (name.isEmpty()) continue;
            String parent = styleEl.getAttribute("parent").trim();
            LinkedHashMap<String, String> items = new LinkedHashMap<>();
            NodeList itemNodes = styleEl.getElementsByTagName("item");
            for (int j = 0; j < itemNodes.getLength(); j++) {
                Node itemNode = itemNodes.item(j);
                if (itemNode.getNodeType() != Node.ELEMENT_NODE) continue;
                Element itemEl = (Element) itemNode;
                String itemName = itemEl.getAttribute("name").trim();
                String itemVal = itemEl.getTextContent() == null ? ""
                        : itemEl.getTextContent().trim();
                if (!itemName.isEmpty()) {
                    items.put(itemName, itemVal);
                }
            }
            map.put(name, new StyleSpec(name, parent, items, false));
        }
        return map;
    }

    public static void validateStylesUpdate(
            String variantSuffix, boolean themesFile,
            List<StyleSpec> upserts, List<String> deletes)
            throws AgentTools.ToolException {
        if (upserts != null) {
            for (StyleSpec s : upserts) {
                if (s.name == null || !RES_KEY_PATTERN.matcher(s.name).matches()) {
                    throw new AgentTools.ToolException("E_BAD_NAME",
                            "Style name \"" + s.name
                                    + "\" must start with a letter and use "
                                    + "letters, digits, underscores, or dots.");
                }
                for (Map.Entry<String, String> item : s.items.entrySet()) {
                    String attr = item.getKey();
                    if (attr == null
                            || !attr.matches("^[a-zA-Z][a-zA-Z0-9_.:]*$")) {
                        throw new AgentTools.ToolException("E_BAD_ATTR",
                                "Invalid style item attribute \"" + attr
                                        + "\" in style \"" + s.name + "\".");
                    }
                }
            }
        }
        if (deletes != null && !themesFile
                && (variantSuffix == null || variantSuffix.isEmpty())) {
            for (String del : deletes) {
                if (PROTECTED_STYLE_NAMES.contains(del)) {
                    throw new AgentTools.ToolException("E_PROTECTED_RESOURCE",
                            "Cannot delete base style \"" + del
                                    + "\" from values/styles.xml (modify its "
                                    + "items or parent instead).");
                }
            }
        }
    }

    public static String serializeStylesXml(Map<String, StyleSpec> styles) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<resources>\n");
        for (StyleSpec s : styles.values()) {
            sb.append("    <style name=\"")
                    .append(escapeAttr(s.name))
                    .append("\"");
            if (s.parent != null && !s.parent.isEmpty()) {
                sb.append(" parent=\"")
                        .append(escapeAttr(s.parent))
                        .append("\"");
            }
            if (s.items.isEmpty()) {
                sb.append(" />\n");
            } else {
                sb.append(">\n");
                for (Map.Entry<String, String> item : s.items.entrySet()) {
                    sb.append("        <item name=\"")
                            .append(escapeAttr(item.getKey()))
                            .append("\">")
                            .append(escapeAttr(item.getValue()))
                            .append("</item>\n");
                }
                sb.append("    </style>\n");
            }
        }
        sb.append("</resources>\n");
        return sb.toString();
    }

    public static LinkedHashMap<String, StyleSpec> writeStylesFile(
            String scId, String variantSuffix, boolean themesFile,
            List<StyleSpec> upserts, List<String> deletes) throws Exception {
        validateStylesUpdate(variantSuffix, themesFile, upserts, deletes);
        LinkedHashMap<String, StyleSpec> current =
                readStylesFile(scId, variantSuffix, themesFile);
        if (deletes != null) {
            for (String del : deletes) {
                current.remove(del);
            }
        }
        if (upserts != null) {
            for (StyleSpec incoming : upserts) {
                StyleSpec existing = current.get(incoming.name);
                if (existing == null || incoming.replaceItems) {
                    String resolvedParent = incoming.parent != null
                            && !incoming.parent.isEmpty()
                            ? incoming.parent
                            : (existing != null ? existing.parent : "");
                    LinkedHashMap<String, String> merged =
                            new LinkedHashMap<>();
                    for (Map.Entry<String, String> it
                            : incoming.items.entrySet()) {
                        if (it.getValue() != null && !it.getValue().isEmpty()) {
                            merged.put(it.getKey(), it.getValue());
                        }
                    }
                    current.put(incoming.name, new StyleSpec(
                            incoming.name, resolvedParent, merged, false));
                } else {
                    if (incoming.parent != null && !incoming.parent.isEmpty()) {
                        existing.parent = incoming.parent;
                    }
                    for (Map.Entry<String, String> it
                            : incoming.items.entrySet()) {
                        if (it.getValue() == null || it.getValue().isEmpty()) {
                            existing.items.remove(it.getKey());
                        } else {
                            existing.items.put(it.getKey(), it.getValue());
                        }
                    }
                }
            }
        }
        String fileName = themesFile ? "themes.xml" : "styles.xml";
        String xml = serializeStylesXml(current);
        parseXmlDocument(xml, fileName);
        SketchwareActions.backupProjectOnce(scId);
        String path = valuesFilePath(scId, variantSuffix, fileName);
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileUtil.writeFile(path, xml);
        LinkedHashMap<String, StyleSpec> verify = parseStylesXml(
                FileUtil.readFile(path), fileName);
        if (verify.size() != current.size()) {
            throw new IOException("Wrote " + current.size()
                    + " styles, but read back " + verify.size() + ".");
        }
        return verify;
    }

    // -----------------------------------------------------------------
    // 4. arrays.xml (string-array, integer-array, array)
    // -----------------------------------------------------------------

    public static final class ArraySpec {
        public final String type;
        public final String name;
        public final List<String> items;

        public ArraySpec(String type, String name, List<String> items) {
            this.type = type;
            this.name = name;
            this.items = new ArrayList<>(
                    items != null ? items : Collections.emptyList());
        }
    }

    public static String normalizeArrayTag(String raw)
            throws AgentTools.ToolException {
        if (raw == null || raw.trim().isEmpty()) {
            return "string-array";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)
                .replace("_", "-")) {
            case "string-array", "string", "strings" -> "string-array";
            case "integer-array", "int-array", "integer", "int",
                    "integers" -> "integer-array";
            case "array", "typed-array" -> "array";
            default -> throw new AgentTools.ToolException("E_BAD_ARRAY_TYPE",
                    "Unknown array type \"" + raw
                            + "\". Use string-array, integer-array, or array.");
        };
    }

    public static LinkedHashMap<String, ArraySpec> readArrays(
            String scId, String variantSuffix) throws AgentTools.ToolException {
        String path = valuesFilePath(scId, variantSuffix, "arrays.xml");
        if (!FileUtil.isExistFile(path)) {
            return new LinkedHashMap<>();
        }
        return parseArraysXml(FileUtil.readFile(path));
    }

    private static LinkedHashMap<String, ArraySpec> parseArraysXml(String xml)
            throws AgentTools.ToolException {
        LinkedHashMap<String, ArraySpec> map = new LinkedHashMap<>();
        if (xml == null || xml.trim().isEmpty()) {
            return map;
        }
        Document doc = parseXmlDocument(xml, "arrays.xml");
        Element root = doc.getDocumentElement();
        if (root == null) return map;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String tag = el.getTagName();
            if (!"string-array".equals(tag)
                    && !"integer-array".equals(tag)
                    && !"array".equals(tag)) {
                continue;
            }
            String name = el.getAttribute("name").trim();
            if (name.isEmpty()) continue;
            List<String> items = new ArrayList<>();
            NodeList itemNodes = el.getElementsByTagName("item");
            for (int j = 0; j < itemNodes.getLength(); j++) {
                Node itemNode = itemNodes.item(j);
                if (itemNode.getNodeType() != Node.ELEMENT_NODE) continue;
                String rawVal = itemNode.getTextContent();
                items.add("string-array".equals(tag)
                        ? unescapeAndroidStringValue(rawVal)
                        : (rawVal == null ? "" : rawVal.trim()));
            }
            map.put(name, new ArraySpec(tag, name, items));
        }
        return map;
    }

    public static void validateArraysUpdate(List<ArraySpec> upserts)
            throws AgentTools.ToolException {
        if (upserts == null) return;
        for (ArraySpec spec : upserts) {
            normalizeArrayTag(spec.type);
            if (spec.name == null
                    || !STRICT_ID_PATTERN.matcher(spec.name).matches()) {
                throw new AgentTools.ToolException("E_BAD_NAME",
                        "Array name \"" + spec.name
                                + "\" must start with a lowercase letter and "
                                + "use lowercase letters, digits, and "
                                + "underscores.");
            }
            if ("integer-array".equals(spec.type)) {
                for (String item : spec.items) {
                    String v = item == null ? "" : item.trim();
                    if (!v.startsWith("@integer/")) {
                        try {
                            Integer.parseInt(v);
                        } catch (NumberFormatException e) {
                            throw new AgentTools.ToolException("E_BAD_VALUE",
                                    "integer-array \"" + spec.name
                                            + "\" item \"" + v
                                            + "\" is not an integer.");
                        }
                    }
                }
            }
        }
    }

    public static String serializeArraysXml(Map<String, ArraySpec> arrays) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<resources>\n");
        for (ArraySpec spec : arrays.values()) {
            sb.append("    <").append(spec.type)
                    .append(" name=\"")
                    .append(escapeAttr(spec.name))
                    .append("\">\n");
            for (String item : spec.items) {
                String encoded = "string-array".equals(spec.type)
                        ? escapeAndroidStringValue(item)
                        : escapeAttr(item == null ? "" : item.trim());
                sb.append("        <item>")
                        .append(encoded)
                        .append("</item>\n");
            }
            sb.append("    </").append(spec.type).append(">\n");
        }
        sb.append("</resources>\n");
        return sb.toString();
    }

    public static LinkedHashMap<String, ArraySpec> writeArrays(
            String scId, String variantSuffix,
            List<ArraySpec> upserts, List<String> deletes) throws Exception {
        validateArraysUpdate(upserts);
        LinkedHashMap<String, ArraySpec> current =
                readArrays(scId, variantSuffix);
        if (deletes != null) {
            for (String del : deletes) {
                current.remove(del);
            }
        }
        if (upserts != null) {
            for (ArraySpec spec : upserts) {
                current.put(spec.name, spec);
            }
        }
        String xml = serializeArraysXml(current);
        parseXmlDocument(xml, "arrays.xml");
        SketchwareActions.backupProjectOnce(scId);
        String path = valuesFilePath(scId, variantSuffix, "arrays.xml");
        File parent = new File(path).getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        FileUtil.writeFile(path, xml);
        LinkedHashMap<String, ArraySpec> verify = parseArraysXml(
                FileUtil.readFile(path));
        if (verify.size() != current.size()) {
            throw new IOException("Wrote " + current.size()
                    + " arrays, but read back " + verify.size() + ".");
        }
        return verify;
    }

    // -----------------------------------------------------------------
    // 5. Raw res/<folder>/<name>.xml Resource Manager
    // -----------------------------------------------------------------

    public static String normalizeResourceFolder(String rawFolder)
            throws AgentTools.ToolException {
        String folder = rawFolder == null ? "" : rawFolder.trim();
        if (folder.startsWith("res/")) {
            folder = folder.substring("res/".length());
        }
        if (folder.endsWith("/")) {
            folder = folder.substring(0, folder.length() - 1);
        }
        if (!FOLDER_NAME_PATTERN.matcher(folder).matches()) {
            throw new AgentTools.ToolException("E_BAD_FOLDER",
                    "Invalid Android resource folder \"" + rawFolder
                            + "\". Examples: menu, anim, animator, xml, "
                            + "color, drawable, values, values-night, "
                            + "drawable-v24.");
        }
        return folder;
    }

    public static String normalizeXmlFileName(String rawName)
            throws AgentTools.ToolException {
        String name = rawName == null ? "" : rawName.trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xml")) {
            name = name + ".xml";
        }
        if (!XML_FILE_NAME_PATTERN.matcher(name).matches()) {
            throw new AgentTools.ToolException("E_BAD_FILE_NAME",
                    "Resource XML file name \"" + rawName
                            + "\" must start with a lowercase letter and use "
                            + "lowercase letters, digits, and underscores "
                            + "(e.g. main_menu.xml, fade_in.xml).");
        }
        return name;
    }

    public static JsonArray listRawResourceFiles(String scId,
                                                 String filterFolder)
            throws AgentTools.ToolException {
        JsonArray out = new JsonArray();
        File root = new File(new FilePathUtil().getPathResource(scId));
        if (!root.exists() || !root.isDirectory()) {
            return out;
        }
        String wantedFolder = filterFolder == null || filterFolder.trim().isEmpty()
                ? null : normalizeResourceFolder(filterFolder);
        File[] folders = root.listFiles();
        if (folders == null) {
            return out;
        }
        Arrays.sort(folders, (a, b) -> a.getName().compareTo(b.getName()));
        for (File dir : folders) {
            if (dir == null || !dir.isDirectory()) continue;
            if (wantedFolder != null && !wantedFolder.equals(dir.getName())) {
                continue;
            }
            File[] files = dir.listFiles();
            if (files == null) continue;
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                JsonObject item = new JsonObject();
                item.addProperty("folder", dir.getName());
                item.addProperty("file", f.getName());
                item.addProperty("path", dir.getName() + "/" + f.getName());
                item.addProperty("bytes", f.length());
                out.add(item);
            }
        }
        return out;
    }

    public static String readRawResourceXml(String scId, String folder,
                                            String fileName)
            throws AgentTools.ToolException {
        String cleanFolder = normalizeResourceFolder(folder);
        String cleanFile = normalizeXmlFileName(fileName);
        File target = new File(new FilePathUtil().getPathResource(scId),
                cleanFolder + "/" + cleanFile);
        if (!target.exists() || !target.isFile()) {
            throw new AgentTools.ToolException("E_RESOURCE_NOT_FOUND",
                    "Resource file res/" + cleanFolder + "/" + cleanFile
                            + " does not exist.");
        }
        return FileUtil.readFile(target.getAbsolutePath());
    }

    public static File writeRawResourceXml(String scId, String folder,
                                           String fileName, String xml)
            throws Exception {
        String cleanFolder = normalizeResourceFolder(folder);
        String cleanFile = normalizeXmlFileName(fileName);
        Document doc = parseXmlDocument(
                xml, "res/" + cleanFolder + "/" + cleanFile);
        if (doc.getDocumentElement() == null) {
            throw new AgentTools.ToolException("E_BAD_XML",
                    "res/" + cleanFolder + "/" + cleanFile
                            + " has no root XML element.");
        }
        String trimmed = xml.startsWith("\uFEFF")
                ? xml.substring(1).trim() : xml.trim();
        if (!trimmed.startsWith("<?xml")) {
            trimmed = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + trimmed;
        }
        SketchwareActions.backupProjectOnce(scId);
        File dir = new File(new FilePathUtil().getPathResource(scId),
                cleanFolder);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create res/" + cleanFolder);
        }
        File target = new File(dir, cleanFile);
        FileUtil.writeFile(target.getAbsolutePath(), trimmed + "\n");
        if (!target.exists() || target.length() == 0) {
            throw new IOException("Failed to write res/" + cleanFolder
                    + "/" + cleanFile);
        }
        return target;
    }

    public static void deleteRawResourceXml(String scId, String folder,
                                            String fileName)
            throws Exception {
        String cleanFolder = normalizeResourceFolder(folder);
        String cleanFile = normalizeXmlFileName(fileName);
        File target = new File(new FilePathUtil().getPathResource(scId),
                cleanFolder + "/" + cleanFile);
        if (!target.exists() || !target.isFile()) {
            throw new AgentTools.ToolException("E_RESOURCE_NOT_FOUND",
                    "Resource file res/" + cleanFolder + "/" + cleanFile
                            + " does not exist.");
        }
        SketchwareActions.backupProjectOnce(scId);
        FileUtil.deleteFile(target.getAbsolutePath());
        if (target.exists()) {
            throw new IOException("Failed to delete res/" + cleanFolder
                    + "/" + cleanFile);
        }
    }

    // -----------------------------------------------------------------
    // 6. Font Manager (ManageFontActivity / kC.d)
    // -----------------------------------------------------------------

    public static void validateFontName(String resName)
            throws AgentTools.ToolException {
        if (resName == null || resName.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    "Pass \"name\" for the font resource (lowercase letters, "
                            + "digits, underscores, min 3 chars).");
        }
        String name = resName.trim();
        if (name.length() < 3 || name.length() > 70) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    "Font resource name \"" + name
                            + "\" must be between 3 and 70 characters.");
        }
        if ("default_image".equals(name)
                || "default_font".equals(name)
                || "app_icon".equals(name)
                || "none".equalsIgnoreCase(name)) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    "Font name \"" + name + "\" is reserved by Sketchware.");
        }
        for (String kw : uq.b) {
            if (kw.equals(name)) {
                throw new AgentTools.ToolException("E_BAD_NAME",
                        "Font name \"" + name
                                + "\" is a reserved Java/Android keyword.");
            }
        }
        if (!STRICT_ID_PATTERN.matcher(name).matches()) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    "Font name \"" + name
                            + "\" must start with a lowercase letter and use "
                            + "only lowercase letters, digits, and underscores.");
        }
    }

    public static List<ProjectResourceBean> projectFonts(String scId) {
        List<ProjectResourceBean> out = new ArrayList<>();
        try {
            ArrayList<ProjectResourceBean> list = jC.d(scId).d;
            if (list != null) {
                for (ProjectResourceBean b : list) {
                    if (b != null && b.resName != null) {
                        out.add(b);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static List<ProjectResourceBean> collectionFonts() {
        List<ProjectResourceBean> out = new ArrayList<>();
        try {
            ArrayList<ProjectResourceBean> list = Np.g().f();
            if (list != null) {
                for (ProjectResourceBean b : list) {
                    if (b != null && b.resName != null) {
                        out.add(b);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static byte[] loadFontBytes(String bundledKey,
                                       String collectionName,
                                       String filePath,
                                       String[] extOut)
            throws AgentTools.ToolException {
        Context ctx = SketchApplication.getContext();
        if (bundledKey != null && !bundledKey.trim().isEmpty()) {
            String key = bundledKey.trim().toLowerCase(Locale.ROOT)
                    .replace("-", "_").replace(" ", "_");
            try {
                InputStream in = switch (key) {
                    case "gilroy", "sans_modern", "modern_sans" ->
                            ctx.getResources().openRawResource(R.font.gilroy);
                    case "jetbrains_mono", "jetbrainsmono", "code_mono" ->
                            ctx.getAssets().open(
                                    "fonts/jetbrainsmono-regular.ttf");
                    case "droid_sans_mono", "droidsansmono", "mono" ->
                            ctx.getAssets().open(
                                    "fonts/droid_sans_mono.ttf");
                    default -> null;
                };
                if (in == null) {
                    throw new AgentTools.ToolException("E_UNKNOWN_BUNDLED_FONT",
                            "Unknown bundled font \"" + bundledKey
                                    + "\". Available bundled fonts: "
                                    + String.join(", ", BUNDLED_FONTS) + ".");
                }
                try (InputStream stream = in) {
                    extOut[0] = ".ttf";
                    return readAllBytes(stream);
                }
            } catch (AgentTools.ToolException e) {
                throw e;
            } catch (Exception e) {
                throw new AgentTools.ToolException("E_FONT_READ_FAILED",
                        "Failed reading bundled font \"" + bundledKey + "\": "
                                + String.valueOf(e.getMessage()));
            }
        }
        if (collectionName != null && !collectionName.trim().isEmpty()) {
            String wanted = collectionName.trim();
            for (ProjectResourceBean bean : collectionFonts()) {
                if (bean.resName.equalsIgnoreCase(wanted)) {
                    File f = new File(wq.a() + "/font/data/"
                            + bean.resFullName);
                    if (!f.exists() || !f.isFile()) {
                        throw new AgentTools.ToolException(
                                "E_FONT_FILE_MISSING",
                                "Collection font \"" + wanted
                                        + "\" file is missing on disk.");
                    }
                    extOut[0] = bean.resFullName != null
                            && bean.resFullName.toLowerCase(Locale.ROOT)
                                    .endsWith(".otf") ? ".otf" : ".ttf";
                    return readFileBytes(f, "font");
                }
            }
            throw new AgentTools.ToolException("E_COLLECTION_FONT_NOT_FOUND",
                    "No font named \"" + wanted
                            + "\" in Sketchware's font collection.");
        }
        if (filePath != null && !filePath.trim().isEmpty()) {
            File f = new File(filePath.trim());
            if (!f.exists() || !f.isFile()) {
                throw new AgentTools.ToolException("E_FONT_FILE_MISSING",
                        "Font file does not exist: " + filePath);
            }
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
                throw new AgentTools.ToolException("E_BAD_FONT_FORMAT",
                        "Font file must end in .ttf or .otf.");
            }
            byte[] bytes = readFileBytes(f, "font");
            if (!looksLikeFontBytes(bytes)) {
                throw new AgentTools.ToolException("E_BAD_FONT_FORMAT",
                        "File is not a valid TrueType (.ttf) or OpenType "
                                + "(.otf) font.");
            }
            try {
                Typeface tf = Typeface.createFromFile(f);
                if (tf == null || tf.equals(Typeface.DEFAULT)) {
                    throw new AgentTools.ToolException("E_BAD_FONT_FORMAT",
                            "Android Typeface could not load font file "
                                    + f.getName() + ".");
                }
            } catch (AgentTools.ToolException e) {
                throw e;
            } catch (Exception ignored) {
            }
            extOut[0] = lower.endsWith(".otf") ? ".otf" : ".ttf";
            return bytes;
        }
        throw new AgentTools.ToolException("E_MISSING_FONT_SOURCE",
                "Pass one of: bundled_font ("
                        + String.join(", ", BUNDLED_FONTS)
                        + "), collection_name, or file_path (.ttf/.otf).");
    }

    private static boolean looksLikeFontBytes(byte[] b) {
        if (b == null || b.length < 12) return false;
        // 0x00010000 (TrueType), 'OTTO' (OpenType CFF), 'true', 'ttcf'
        int magic = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        return magic == 0x00010000
                || magic == 0x4F54544F
                || magic == 0x74727565
                || magic == 0x74746366;
    }

    public static ProjectResourceBean importProjectFont(
            String scId, String resName, byte[] fontBytes, String ext)
            throws Exception {
        validateFontName(resName);
        String cleanExt = ".otf".equalsIgnoreCase(ext) ? ".otf" : ".ttf";
        String fullName = resName + cleanExt;
        SketchwareActions.backupProjectOnce(scId);
        AgentAssets.resetResourceManager(scId);
        kC resMgr = jC.d(scId);
        File fontDir = new File(resMgr.j());
        if (!fontDir.exists() && !fontDir.mkdirs()) {
            throw new IOException("Could not create temporary font dir: "
                    + fontDir);
        }
        // Remove stale .ttf/.otf with the same base name if overwriting.
        new File(fontDir, resName + ".ttf").delete();
        new File(fontDir, resName + ".otf").delete();
        File target = new File(fontDir, fullName);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(fontBytes);
        }
        ArrayList<ProjectResourceBean> fonts = resMgr.d != null
                ? new ArrayList<>(resMgr.d) : new ArrayList<>();
        fonts.removeIf(b -> b != null && resName.equals(b.resName));
        ProjectResourceBean bean = new ProjectResourceBean(
                ProjectResourceBean.PROJECT_RES_TYPE_FILE, resName, fullName);
        bean.savedPos = 1;
        bean.isNew = true;
        fonts.add(bean);
        resMgr.a(fonts);
        resMgr.y();
        jC.a(scId).a(resMgr);
        jC.a(scId).k();
        SketchwareActions.commitProject(scId);
        resMgr.z();
        AgentAssets.resetResourceManager(scId);

        boolean found = false;
        for (ProjectResourceBean rb : projectFonts(scId)) {
            if (resName.equals(rb.resName)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IOException("Font \"" + resName
                    + "\" was saved, but was not found on read-back.");
        }
        return bean;
    }

    public static List<String> fontReferences(String scId, String resName) {
        List<String> refs = new ArrayList<>();
        if (scId == null || resName == null || resName.isEmpty()) {
            return refs;
        }
        List<ProjectFileBean> screens = new ArrayList<>(
                SketchwareProjects.activities(scId));
        screens.addAll(SketchwareProjects.customViews(scId));
        for (ProjectFileBean screen : screens) {
            for (ViewBean bean : SketchwareProjects.screenBeans(scId, screen)) {
                if (bean != null && bean.text != null
                        && resName.equals(bean.text.textFont)) {
                    refs.add(SketchwareProjects.screenName(screen)
                            + " -> widget " + bean.id + " (font)");
                }
            }
        }
        try {
            eC store = jC.a(scId);
            for (ProjectFileBean act : SketchwareProjects.activities(scId)) {
                Map<String, ArrayList<BlockBean>> map =
                        store.b(act.getJavaName());
                if (map == null) continue;
                for (Map.Entry<String, ArrayList<BlockBean>> e
                        : map.entrySet()) {
                    if (e.getValue() == null) continue;
                    for (BlockBean bb : e.getValue()) {
                        if (bb != null && "setTypeface".equals(bb.opCode)
                                && bb.parameters != null
                                && bb.parameters.contains(resName)) {
                            refs.add(SketchwareProjects.activityName(act)
                                    + " -> block in " + e.getKey());
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return refs;
    }

    public static List<String> deleteProjectFont(String scId, String resName,
                                                 boolean clearReferences)
            throws Exception {
        ProjectResourceBean existing = null;
        for (ProjectResourceBean b : projectFonts(scId)) {
            if (b.resName.equals(resName)) {
                existing = b;
                break;
            }
        }
        if (existing == null) {
            throw new AgentTools.ToolException("E_FONT_NOT_FOUND",
                    "Project has no font named \"" + resName + "\".");
        }
        List<String> refs = fontReferences(scId, resName);
        if (!refs.isEmpty() && !clearReferences) {
            throw new AgentTools.ToolException("E_FONT_IN_USE",
                    "Font \"" + resName + "\" is still used by: "
                            + String.join("; ", refs)
                            + ". Pass clear_references=true or update those "
                            + "widgets/blocks first.");
        }
        SketchwareActions.backupProjectOnce(scId);
        if (!refs.isEmpty()) {
            List<ProjectFileBean> screens = new ArrayList<>(
                    SketchwareProjects.activities(scId));
            screens.addAll(SketchwareProjects.customViews(scId));
            for (ProjectFileBean screen : screens) {
                ArrayList<ViewBean> beans =
                        SketchwareProjects.screenBeans(scId, screen);
                boolean touched = false;
                for (ViewBean bean : beans) {
                    if (bean != null && bean.text != null
                            && resName.equals(bean.text.textFont)) {
                        bean.text.textFont = "default_font";
                        touched = true;
                    }
                }
                if (touched) {
                    SketchwareActions.saveScreen(scId, screen, beans);
                }
            }
        }
        AgentAssets.resetResourceManager(scId);
        kC resMgr = jC.d(scId);
        ArrayList<ProjectResourceBean> fonts = resMgr.d != null
                ? new ArrayList<>(resMgr.d) : new ArrayList<>();
        fonts.removeIf(b -> b != null && resName.equals(b.resName));
        if (existing.resFullName != null) {
            new File(resMgr.j(), existing.resFullName).delete();
        }
        resMgr.a(fonts);
        resMgr.y();
        jC.a(scId).a(resMgr);
        jC.a(scId).k();
        SketchwareActions.commitProject(scId);
        resMgr.z();
        AgentAssets.resetResourceManager(scId);
        return refs;
    }

    // -----------------------------------------------------------------
    // 7. Sound Manager (ManageSoundActivity / kC.c + PCM .wav synthesis)
    // -----------------------------------------------------------------

    public static void validateSoundName(String resName)
            throws AgentTools.ToolException {
        if (resName == null || resName.trim().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    "Pass \"name\" for the sound resource (lowercase letters, "
                            + "digits, underscores).");
        }
        String name = resName.trim();
        if (name.length() > 70) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    "Sound name \"" + name
                            + "\" must be 70 characters or fewer.");
        }
        if ("default_image".equals(name)
                || "app_icon".equals(name)
                || "none".equalsIgnoreCase(name)) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    "Sound name \"" + name + "\" is reserved by Sketchware.");
        }
        for (String kw : uq.b) {
            if (kw.equals(name)) {
                throw new AgentTools.ToolException("E_BAD_NAME",
                        "Sound name \"" + name
                                + "\" is a reserved Java/Android keyword.");
            }
        }
        if (!STRICT_ID_PATTERN.matcher(name).matches()) {
            throw new AgentTools.ToolException("E_BAD_NAME",
                    "Sound name \"" + name
                            + "\" must start with a lowercase letter and use "
                            + "only lowercase letters, digits, and underscores.");
        }
    }

    public static List<ProjectResourceBean> projectSounds(String scId) {
        List<ProjectResourceBean> out = new ArrayList<>();
        try {
            ArrayList<ProjectResourceBean> list = jC.d(scId).c;
            if (list != null) {
                for (ProjectResourceBean b : list) {
                    if (b != null && b.resName != null) {
                        out.add(b);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static List<ProjectResourceBean> collectionSounds() {
        List<ProjectResourceBean> out = new ArrayList<>();
        try {
            ArrayList<ProjectResourceBean> list = Qp.g().f();
            if (list != null) {
                for (ProjectResourceBean b : list) {
                    if (b != null && b.resName != null) {
                        out.add(b);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static byte[] loadSoundBytes(String preset,
                                        Double customFreqHz,
                                        Integer customDurationMs,
                                        String waveform,
                                        String collectionName,
                                        String filePath,
                                        String[] extOut)
            throws AgentTools.ToolException {
        if (preset != null && !preset.trim().isEmpty()) {
            extOut[0] = ".wav";
            return synthesizePresetWav(preset.trim());
        }
        if (customFreqHz != null && customFreqHz > 0) {
            double hz = Math.max(40.0, Math.min(8000.0, customFreqHz));
            int durMs = customDurationMs != null
                    ? Math.max(30, Math.min(5000, customDurationMs)) : 220;
            String wf = waveform == null || waveform.trim().isEmpty()
                    ? "sine" : waveform.trim().toLowerCase(Locale.ROOT);
            extOut[0] = ".wav";
            return synthesizeToneWav(
                    new double[]{hz}, new int[]{durMs}, wf);
        }
        if (collectionName != null && !collectionName.trim().isEmpty()) {
            String wanted = collectionName.trim();
            for (ProjectResourceBean bean : collectionSounds()) {
                if (bean.resName.equalsIgnoreCase(wanted)) {
                    File f = new File(wq.a() + "/sound/data/"
                            + bean.resFullName);
                    if (!f.exists() || !f.isFile()) {
                        throw new AgentTools.ToolException(
                                "E_SOUND_FILE_MISSING",
                                "Collection sound \"" + wanted
                                        + "\" file is missing on disk.");
                    }
                    extOut[0] = audioExtOf(bean.resFullName);
                    return readFileBytes(f, "sound");
                }
            }
            throw new AgentTools.ToolException("E_COLLECTION_SOUND_NOT_FOUND",
                    "No sound named \"" + wanted
                            + "\" in Sketchware's sound collection.");
        }
        if (filePath != null && !filePath.trim().isEmpty()) {
            File f = new File(filePath.trim());
            if (!f.exists() || !f.isFile()) {
                throw new AgentTools.ToolException("E_SOUND_FILE_MISSING",
                        "Sound file does not exist: " + filePath);
            }
            String ext = audioExtOf(f.getName());
            if (ext == null) {
                throw new AgentTools.ToolException("E_BAD_SOUND_FORMAT",
                        "Sound file must end in .mp3, .ogg, .wav, or .m4a.");
            }
            extOut[0] = ext;
            return readFileBytes(f, "sound");
        }
        throw new AgentTools.ToolException("E_MISSING_SOUND_SOURCE",
                "Pass one of: preset (" + String.join(", ", SOUND_PRESETS)
                        + "), frequency_hz, collection_name, or file_path "
                        + "(.mp3/.ogg/.wav/.m4a).");
    }

    private static String audioExtOf(String fileName) {
        if (fileName == null) return ".wav";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) return ".mp3";
        if (lower.endsWith(".ogg")) return ".ogg";
        if (lower.endsWith(".wav")) return ".wav";
        if (lower.endsWith(".m4a")) return ".m4a";
        return null;
    }

    /** Synthesizes clean 44.1 kHz 16-bit mono PCM .wav UI/game sound presets. */
    public static byte[] synthesizePresetWav(String presetRaw)
            throws AgentTools.ToolException {
        String key = presetRaw.toLowerCase(Locale.ROOT)
                .replace("-", "_").replace(" ", "_");
        return switch (key) {
            case "click", "tap", "tick" ->
                    synthesizeToneWav(new double[]{1150.0}, new int[]{42},
                            "triangle");
            case "pop", "bubble" ->
                    synthesizeToneWav(new double[]{420.0, 680.0},
                            new int[]{45, 55}, "sine");
            case "success", "coin", "ding" ->
                    synthesizeToneWav(new double[]{523.25, 783.99},
                            new int[]{95, 190}, "sine");
            case "complete", "level_up", "fanfare" ->
                    synthesizeToneWav(
                            new double[]{523.25, 659.25, 783.99, 1046.50},
                            new int[]{90, 90, 90, 240}, "triangle");
            case "error", "wrong", "buzz" ->
                    synthesizeToneWav(new double[]{311.13, 233.08},
                            new int[]{120, 210}, "square");
            case "notification", "bell", "chime" ->
                    synthesizeToneWav(new double[]{659.25, 987.77},
                            new int[]{120, 240}, "sine");
            case "alarm", "timer_beep", "beep" ->
                    synthesizeToneWav(
                            new double[]{880.0, 0.0, 880.0, 0.0, 880.0},
                            new int[]{130, 60, 130, 60, 180}, "sine");
            default -> throw new AgentTools.ToolException("E_UNKNOWN_PRESET",
                    "Unknown sound preset \"" + presetRaw
                            + "\". Available presets: "
                            + String.join(", ", SOUND_PRESETS) + ".");
        };
    }

    public static byte[] synthesizeToneWav(double[] freqsHz,
                                           int[] durationsMs,
                                           String waveform) {
        final int sampleRate = 44100;
        int totalSamples = 0;
        int count = Math.min(freqsHz.length, durationsMs.length);
        int[] noteSamples = new int[count];
        for (int i = 0; i < count; i++) {
            noteSamples[i] = Math.max(1,
                    (int) ((durationsMs[i] / 1000.0) * sampleRate));
            totalSamples += noteSamples[i];
        }
        int dataSize = totalSamples * 2;
        byte[] wav = new byte[44 + dataSize];
        writeAscii(wav, 0, "RIFF");
        writeLeInt(wav, 4, 36 + dataSize);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeLeInt(wav, 16, 16);
        writeLeShort(wav, 20, (short) 1);
        writeLeShort(wav, 22, (short) 1);
        writeLeInt(wav, 24, sampleRate);
        writeLeInt(wav, 28, sampleRate * 2);
        writeLeShort(wav, 32, (short) 2);
        writeLeShort(wav, 34, (short) 16);
        writeAscii(wav, 36, "data");
        writeLeInt(wav, 40, dataSize);

        int offset = 44;
        for (int n = 0; n < count; n++) {
            double freq = freqsHz[n];
            int samples = noteSamples[n];
            int attack = Math.min(samples / 5, (int) (0.006 * sampleRate));
            for (int s = 0; s < samples; s++) {
                double sampleVal = 0.0;
                if (freq > 0.0) {
                    double t = (double) s / sampleRate;
                    double phase = (t * freq) % 1.0;
                    double osc = switch (waveform) {
                        case "square" -> (phase < 0.5 ? 0.55 : -0.55);
                        case "triangle" -> (phase < 0.5
                                ? (4.0 * phase - 1.0)
                                : (3.0 - 4.0 * phase)) * 0.85;
                        default -> Math.sin(2.0 * Math.PI * freq * t)
                                + 0.18 * Math.sin(4.0 * Math.PI * freq * t);
                    };
                    double env = 1.0;
                    if (attack > 0 && s < attack) {
                        env = (double) s / attack;
                    } else {
                        double progress = (double) s / samples;
                        env = Math.exp(-3.2 * progress);
                    }
                    sampleVal = Math.max(-1.0, Math.min(1.0, osc * env * 0.65));
                }
                short pcm = (short) Math.round(sampleVal * 32767.0);
                writeLeShort(wav, offset, pcm);
                offset += 2;
            }
        }
        return wav;
    }

    private static void writeAscii(byte[] buf, int pos, String s) {
        for (int i = 0; i < s.length(); i++) {
            buf[pos + i] = (byte) s.charAt(i);
        }
    }

    private static void writeLeInt(byte[] buf, int pos, int v) {
        buf[pos] = (byte) (v & 0xFF);
        buf[pos + 1] = (byte) ((v >>> 8) & 0xFF);
        buf[pos + 2] = (byte) ((v >>> 16) & 0xFF);
        buf[pos + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    private static void writeLeShort(byte[] buf, int pos, short v) {
        buf[pos] = (byte) (v & 0xFF);
        buf[pos + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    public static ProjectResourceBean importProjectSound(
            String scId, String resName, byte[] soundBytes, String ext)
            throws Exception {
        validateSoundName(resName);
        String cleanExt = audioExtOf("x" + ext);
        if (cleanExt == null) cleanExt = ".wav";
        String fullName = resName + cleanExt;
        SketchwareActions.backupProjectOnce(scId);
        AgentAssets.resetResourceManager(scId);
        kC resMgr = jC.d(scId);
        File soundDir = new File(resMgr.o());
        if (!soundDir.exists() && !soundDir.mkdirs()) {
            throw new IOException("Could not create temporary sound dir: "
                    + soundDir);
        }
        for (String oldExt : new String[]{".mp3", ".ogg", ".wav", ".m4a"}) {
            new File(soundDir, resName + oldExt).delete();
        }
        File target = new File(soundDir, fullName);
        try (FileOutputStream fos = new FileOutputStream(target)) {
            fos.write(soundBytes);
        }
        ArrayList<ProjectResourceBean> sounds = resMgr.c != null
                ? new ArrayList<>(resMgr.c) : new ArrayList<>();
        sounds.removeIf(b -> b != null && resName.equals(b.resName));
        ProjectResourceBean bean = new ProjectResourceBean(
                ProjectResourceBean.PROJECT_RES_TYPE_FILE, resName, fullName);
        bean.savedPos = 1;
        bean.isNew = true;
        sounds.add(bean);
        resMgr.c(sounds);
        resMgr.y();
        jC.a(scId).c(resMgr);
        jC.a(scId).k();
        SketchwareActions.commitProject(scId);
        resMgr.z();
        AgentAssets.resetResourceManager(scId);

        boolean found = false;
        for (ProjectResourceBean rb : projectSounds(scId)) {
            if (resName.equals(rb.resName)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IOException("Sound \"" + resName
                    + "\" was saved, but was not found on read-back.");
        }
        return bean;
    }

    public static List<String> soundReferences(String scId, String resName) {
        List<String> refs = new ArrayList<>();
        if (scId == null || resName == null || resName.isEmpty()) {
            return refs;
        }
        try {
            eC store = jC.a(scId);
            for (ProjectFileBean act : SketchwareProjects.activities(scId)) {
                Map<String, ArrayList<BlockBean>> map =
                        store.b(act.getJavaName());
                if (map == null) continue;
                for (Map.Entry<String, ArrayList<BlockBean>> e
                        : map.entrySet()) {
                    if (e.getValue() == null) continue;
                    for (BlockBean bb : e.getValue()) {
                        if (bb == null || bb.parameters == null) continue;
                        if (("mediaplayerCreate".equals(bb.opCode)
                                || "soundpoolLoad".equals(bb.opCode))
                                && bb.parameters.contains(resName)) {
                            refs.add(SketchwareProjects.activityName(act)
                                    + " -> block in " + e.getKey());
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return refs;
    }

    public static List<String> deleteProjectSound(String scId, String resName,
                                                  boolean clearReferences)
            throws Exception {
        ProjectResourceBean existing = null;
        for (ProjectResourceBean b : projectSounds(scId)) {
            if (b.resName.equals(resName)) {
                existing = b;
                break;
            }
        }
        if (existing == null) {
            throw new AgentTools.ToolException("E_SOUND_NOT_FOUND",
                    "Project has no sound named \"" + resName + "\".");
        }
        List<String> refs = soundReferences(scId, resName);
        if (!refs.isEmpty() && !clearReferences) {
            throw new AgentTools.ToolException("E_SOUND_IN_USE",
                    "Sound \"" + resName + "\" is still used by: "
                            + String.join("; ", refs)
                            + ". Pass clear_references=true or update those "
                            + "blocks first.");
        }
        SketchwareActions.backupProjectOnce(scId);
        AgentAssets.resetResourceManager(scId);
        kC resMgr = jC.d(scId);
        ArrayList<ProjectResourceBean> sounds = resMgr.c != null
                ? new ArrayList<>(resMgr.c) : new ArrayList<>();
        sounds.removeIf(b -> b != null && resName.equals(b.resName));
        if (existing.resFullName != null) {
            new File(resMgr.o(), existing.resFullName).delete();
        }
        resMgr.c(sounds);
        resMgr.y();
        jC.a(scId).c(resMgr);
        jC.a(scId).k();
        SketchwareActions.commitProject(scId);
        resMgr.z();
        AgentAssets.resetResourceManager(scId);
        return refs;
    }

    // -----------------------------------------------------------------
    // Byte helpers
    // -----------------------------------------------------------------

    private static byte[] readFileBytes(File f, String kind)
            throws AgentTools.ToolException {
        if (f.length() <= 0 || f.length() > 25L * 1024L * 1024L) {
            throw new AgentTools.ToolException("E_BAD_FILE_SIZE",
                    "Invalid " + kind + " file size (" + f.length()
                            + " bytes).");
        }
        try (FileInputStream fis = new FileInputStream(f)) {
            return readAllBytes(fis);
        } catch (IOException e) {
            throw new AgentTools.ToolException("E_READ_FAILED",
                    "Failed reading " + kind + " file " + f.getName() + ": "
                            + String.valueOf(e.getMessage()));
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static final Pattern RES_PLUS_ID_PATTERN =
            Pattern.compile("@\\+?id/([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern RES_ITEM_ID_PATTERN =
            Pattern.compile("<item[^>]*type\\s*=\\s*\"id\"[^>]*name\\s*=\\s*\"([A-Za-z_][A-Za-z0-9_]*)\"");

    /**
     * Collects all {@code @+id/<name>} and {@code <item type="id" name="<name>">}
     * identifiers across project XML resources (menus, custom layouts, navigation,
     * values) plus all Sketchware activity/custom-view widget IDs so {@code R.id.<name>}
     * references in block logic can be validated accurately.
     */
    public static Set<String> collectDeclaredResourceIds(String scId) {
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add("_toolbar");
        out.add("_app_bar");
        out.add("_coordinator");
        out.add("_fab");
        out.add("_drawer");
        if (scId == null || scId.trim().isEmpty()) {
            return out;
        }
        try {
            File root = new File(new FilePathUtil().getPathResource(scId));
            if (root.exists() && root.isDirectory()) {
                File[] dirs = root.listFiles();
                if (dirs != null) {
                    for (File dir : dirs) {
                        if (dir == null || !dir.isDirectory()) continue;
                        File[] files = dir.listFiles();
                        if (files == null) continue;
                        for (File f : files) {
                            if (f == null || !f.isFile()
                                    || !f.getName().toLowerCase(Locale.ROOT).endsWith(".xml")
                                    || f.length() > 512_000L) {
                                continue;
                            }
                            String xml = FileUtil.readFile(f.getAbsolutePath());
                            if (xml == null || xml.isEmpty()) continue;
                            java.util.regex.Matcher m1 = RES_PLUS_ID_PATTERN.matcher(xml);
                            while (m1.find()) {
                                out.add(m1.group(1));
                            }
                            java.util.regex.Matcher m2 = RES_ITEM_ID_PATTERN.matcher(xml);
                            while (m2.find()) {
                                out.add(m2.group(1));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            List<ProjectFileBean> screens = new ArrayList<>();
            List<ProjectFileBean> activities = SketchwareProjects.activities(scId);
            if (activities != null) {
                screens.addAll(activities);
            }
            List<ProjectFileBean> customViews = SketchwareProjects.customViews(scId);
            if (customViews != null) {
                screens.addAll(customViews);
            }
            for (ProjectFileBean screen : screens) {
                if (screen == null) continue;
                List<ViewBean> views =
                        SketchwareProjects.screenBeans(scId, screen);
                if (views == null) continue;
                for (ViewBean vb : views) {
                    if (vb != null && vb.id != null && !vb.id.isEmpty()) {
                        out.add(vb.id);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Maps {@code res/menu/<name>.xml} base names to their declared {@code @+id/*} item IDs
     * for compact project indexing and menu handler diagnostics.
     */
    public static Map<String, List<String>> collectMenuResourceIdsByFile(String scId) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (scId == null || scId.trim().isEmpty()) {
            return out;
        }
        try {
            File menuDir = new File(new FilePathUtil().getPathResource(scId), "menu");
            if (!menuDir.exists() || !menuDir.isDirectory()) {
                return out;
            }
            File[] files = menuDir.listFiles();
            if (files == null) {
                return out;
            }
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : files) {
                if (f == null || !f.isFile()
                        || !f.getName().toLowerCase(Locale.ROOT).endsWith(".xml")
                        || f.length() > 256_000L) {
                    continue;
                }
                String xml = FileUtil.readFile(f.getAbsolutePath());
                if (xml == null) continue;
                Set<String> ids = new java.util.LinkedHashSet<>();
                java.util.regex.Matcher m = RES_PLUS_ID_PATTERN.matcher(xml);
                while (m.find()) {
                    ids.add(m.group(1));
                }
                String base = f.getName().substring(0, f.getName().length() - 4);
                out.put(base, new ArrayList<>(ids));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
