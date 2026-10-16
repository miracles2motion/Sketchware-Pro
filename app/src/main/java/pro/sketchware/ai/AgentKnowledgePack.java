package pro.sketchware.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import mod.hilal.saif.blocks.BlocksHandler;

/**
 * The knowledge pack: the vocabulary the model needs to talk about the app.
 *
 * <p>A 1B model cannot know that Sketchware has a block called
 * {@code setText} with the grammar {@code "set text of %m.TextView to %s"}.
 * It has to be told, and the truth has to come from the app itself, not from
 * a hand written list that goes stale.
 *
 * <p>The block catalog is therefore read from
 * {@link BlocksHandler#builtInBlocks(ArrayList)}, which is the same call the
 * logic editor uses to build its palette. That means the pack always contains
 * exactly the blocks this build of the app supports, including the ones that
 * come from mod libraries.
 *
 * <p>The pack is written to {@code .sketchware/ai/knowledge/blocks.json} and
 * is meant to be pasted into the context of a turn. It is regenerated on
 * demand, and a user may edit it: the file is the contract, not the code.
 */
public final class AgentKnowledgePack {

    /** Version of the pack format. */
    public static final int VERSION = 1;

    /** Name of the file inside the knowledge directory. */
    public static final String BLOCKS_FILE_NAME = "blocks.json";

    private AgentKnowledgePack() {
    }

    /**
     * Writes the block catalog to the knowledge directory.
     *
     * @return the file that was written, or null when it could not be written
     */
    public static File exportBlocks() {
        File file = new File(AiPaths.knowledgeDir(), BLOCKS_FILE_NAME);
        try (Writer writer = new FileWriter(file)) {
            writer.write(blocksJson());
            return file;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Builds the block catalog as JSON.
     *
     * <p>Every entry carries the opcode, the block type, the palette and the
     * grammar. The grammar is also broken down into parameters, because a
     * model that has to fill in {@code %m.TextView} needs to know that this
     * slot takes a widget of type TextView and not a free text.
     */
    public static String blocksJson() {
        List<HashMap<String, Object>> raw = new ArrayList<>();
        BlocksHandler.builtInBlocks(raw);

        JsonArray blocks = new JsonArray();
        for (HashMap<String, Object> map : raw) {
            String opCode = asString(map.get("name"));
            if (opCode == null || opCode.isEmpty()) {
                continue;
            }
            String spec = asString(map.get("spec"));

            JsonObject block = new JsonObject();
            block.addProperty("opCode", opCode);
            block.addProperty("type", asString(map.get("type")));
            block.addProperty("typeName", asString(map.get("typeName")));
            block.addProperty("palette", asString(map.get("palette")));
            block.addProperty("kind", kindOf(asString(map.get("type"))));
            block.addProperty("spec", spec);
            block.add("parameters", parseSpec(spec));
            blocks.add(block);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("kind", "blocks");
        root.add("blocks", blocks);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /**
     * Reads the widget catalog, when the app has one on disk.
     *
     * <p>The widget palette is not compiled into the app: it lives in
     * {@code /storage/emulated/0/.sketchware/resources/widgets/widgets.json}
     * and can be extended by the user, so it is copied verbatim instead of
     * being reconstructed.
     *
     * @return the widget catalog, or null when the app has none
     */
    public static JsonObject readWidgets() {
        File widgets = new File("/storage/emulated/0/.sketchware/resources/widgets/widgets.json");
        if (!widgets.exists()) {
            return null;
        }
        try (Reader reader = new FileReader(widgets)) {
            return new Gson().fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Turns a block type code into something readable.
     *
     * <p>The codes come from the logic editor: a block either produces a
     * value ({@code b} boolean, {@code d} number, {@code s} text, {@code a}
     * list), is a statement ({@code c}, {@code e}, a space), is a header with
     * children ({@code f}, {@code h}) or holds a variable ({@code v},
     * {@code p}, {@code l}).
     */
    private static String kindOf(String type) {
        if (type == null) {
            return "statement";
        }
        switch (type) {
            case "b":
                return "boolean";
            case "d":
                return "number";
            case "s":
                return "text";
            case "a":
                return "list";
            case "v":
            case "p":
            case "l":
                return "variable";
            case "f":
            case "h":
                return "header";
            case "c":
            case "e":
            case " ":
            case "o":
            default:
                return "statement";
        }
    }

    /**
     * Breaks a grammar into its parameters.
     *
     * <p>Three placeholder kinds exist in the grammars: {@code %s} for a text,
     * {@code %d} for a number and {@code %m.Type} for a member of a given
     * type, for example a widget. Indexed forms such as {@code %1$s} are
     * accepted as well.
     */
    private static JsonArray parseSpec(String spec) {
        JsonArray parameters = new JsonArray();
        if (spec == null || spec.isEmpty()) {
            return parameters;
        }

        int index = 0;
        int cursor = 0;
        while (cursor < spec.length()) {
            int percent = spec.indexOf('%', cursor);
            if (percent < 0) {
                break;
            }
            if (percent + 1 < spec.length() && spec.charAt(percent + 1) == '%') {
                cursor = percent + 2;
                continue;
            }

            int position = percent + 1;
            // An optional index, as in %1$s
            while (position < spec.length() && Character.isDigit(spec.charAt(position))) {
                position++;
            }
            if (position >= spec.length()) {
                break;
            }

            char marker = spec.charAt(position);
            int end = position + 1;
            String memberType = null;

            if (marker == 'm' && position + 1 < spec.length() && spec.charAt(position + 1) == '.') {
                int typeStart = position + 2;
                int typeEnd = typeStart;
                while (typeEnd < spec.length()
                        && (Character.isLetterOrDigit(spec.charAt(typeEnd))
                        || spec.charAt(typeEnd) == '.')) {
                    typeEnd++;
                }
                memberType = spec.substring(typeStart, typeEnd);
                end = typeEnd;
            } else if (marker != 's' && marker != 'd') {
                // Not a parameter we know, treat it as plain text.
                cursor = percent + 1;
                continue;
            }

            JsonObject parameter = new JsonObject();
            parameter.addProperty("index", index++);
            parameter.addProperty("kind",
                    memberType != null ? "member" : (marker == 'd' ? "number" : "text"));
            if (memberType != null) {
                parameter.addProperty("memberType", memberType);
            }
            parameter.addProperty("label", labelBetween(spec, cursor, percent));

            parameters.add(parameter);
            cursor = end;
        }
        return parameters;
    }

    /**
     * The words that introduce a placeholder, used as a human readable label.
     *
     * <p>The grammars read like sentences, so the text in front of a
     * placeholder names it: "set text of %m.TextView to %s" gives the widget
     * the label "text of" and the value the label "to".
     */
    private static String labelBetween(String spec, int from, int to) {
        if (to <= from) {
            return "";
        }
        String text = spec.substring(from, to).trim();
        // Keep the last two words at most, a label is not a sentence.
        String[] words = text.split("\\s+");
        if (words.length <= 2) {
            return text;
        }
        return words[words.length - 2] + " " + words[words.length - 1];
    }

    private static String asString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JsonPrimitive) {
            return ((JsonPrimitive) value).getAsString();
        }
        return String.valueOf(value);
    }
}
