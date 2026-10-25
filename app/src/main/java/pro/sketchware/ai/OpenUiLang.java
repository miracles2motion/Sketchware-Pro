package pro.sketchware.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java implementation of the OpenUI Lang 1.0 specification
 * (MIT open standard — {@code identifier = Expression} streaming UI language).
 *
 * <p>Zero external dependencies beyond {@code java.util} and {@code Gson}:
 * <ul>
 *   <li>Inline fence &amp; stored-message splitter ({@link #splitSegments}) supporting
 *       progressive line-by-line streaming before closing backticks arrive.</li>
 *   <li>Recursive-descent expression &amp; statement parser supporting positional args,
 *       forgiving {@code key: value} recovery, forward references, {@code $variable}
 *       reactive state declarations, {@code @Name(...)} built-ins and actions,
 *       {@code Action([...])} composition, member access with array column-pluck,
 *       ternary/logical/comparison/arithmetic operators, and {@code Query}/{@code Mutation}.</li>
 *   <li>Incremental patching ({@link #mergeStatements}) per OpenUI 1.0 merge rules.</li>
 *   <li>Reactive {@link RuntimeState} evaluator with two-way {@code $variable} bindings,
 *       all built-in {@code @} data/math/iteration functions, and local action execution
 *       ({@code @Set}, {@code @Reset}, {@code @OpenUrl}, {@code @ToAssistant}).</li>
 * </ul>
 */
public final class OpenUiLang {

    private OpenUiLang() {
    }

    // ---------------------------------------------------------------------
    // Inline Mode & Stored Message Segment Extraction
    // ---------------------------------------------------------------------

    /** Fast check before allocating a segment list on plain Markdown messages. */
    public static boolean containsOpenUi(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.US);
        return lower.contains("```openui")
                || lower.contains("]]>openui:");
    }

    /**
     * Tolerant chat-choice recovery: when the model answers a style or
     * direction question by DRAWING an OpenUI choice block (a
     * ChoiceChips/Select fence) instead of calling the ask_choice tool,
     * this pulls the question and the options out of the raw fence text.
     * It deliberately does NOT use the strict grammar — a model that
     * confuses the two syntaxes is exactly the one this must catch.
     * Returns [question, option…] (question first) or null when the text
     * holds no choice block.
     */
    public static List<String> extractChatChoice(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher fence = java.util.regex.Pattern
                .compile("(?is)```\\s*openui[^\\n]*\\n(.*?)(```|\\\\z)")
                .matcher(text);
        while (fence.find()) {
            List<String> out = choiceFromFence(fence.group(1));
            if (out != null) {
                return out;
            }
        }
        return null;
    }

    private static List<String> choiceFromFence(String source) {
        if (source == null) {
            return null;
        }
        String head = source.trim();
        if (!head.startsWith("ChoiceChips")
                && !head.startsWith("Select")
                && !head.startsWith("Choice")) {
            return null;
        }
        List<String> strings = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (escaped) {
                    current.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                    String value = current.toString().trim();
                    // Empty strings and "$var" bindings are arguments,
                    // never something the user could pick or read.
                    if (!value.isEmpty() && !value.startsWith("$")) {
                        strings.add(value);
                    }
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inString = true;
            }
        }
        if (strings.size() < 3) {
            return null;
        }
        List<String> out = new ArrayList<>();
        out.add(strings.get(0));
        int options = 0;
        for (int i = 1; i < strings.size() && options < 6; i++) {
            String value = strings.get(i);
            // A bare hex or a bare number is swatch/argument data.
            if (value.matches("#[0-9A-Fa-f]{6,8}")
                    || value.matches("\\d+(\\.\\d+)?")) {
                continue;
            }
            out.add(value);
            options++;
        }
        if (out.size() < 3) {
            return null;
        }
        return out;
    }

    /** A contiguous slice of an assistant message: Markdown prose or OpenUI Lang code. */
    public static final class Segment {
        public enum Kind {
            MARKDOWN,
            OPENUI
        }

        public final Kind kind;
        public final String content;
        /** True when an {@code openui} fence has opened in a streaming reply and not closed yet. */
        public final boolean streaming;
        /** Optional host context recovered from {@code ]]>openui:context}. */
        public final JsonObject storedContext;

        public Segment(Kind kind, String content, boolean streaming, JsonObject storedContext) {
            this.kind = kind;
            this.content = content == null ? "" : content;
            this.streaming = streaming;
            this.storedContext = storedContext;
        }
    }

    /**
     * Splits an assistant message into interleaved {@link Segment.Kind#MARKDOWN}
     * and {@link Segment.Kind#OPENUI} blocks.
     *
     * <p>Recognises {@code ```openui}, {@code ```openui-lang}, and {@code ```openui_lang}
     * fences (including unclosed fences while tokens are still streaming), as well as
     * OpenUI 1.0 {@code ]]>openui:content} / {@code ]]>openui:context} / {@code ]]>openui:end}
     * stored message markers.
     */
    public static List<Segment> splitSegments(String rawText) {
        List<Segment> out = new ArrayList<>();
        if (rawText == null || rawText.isEmpty()) {
            return out;
        }

        String working = rawText;
        JsonObject extractedContext = null;
        boolean explicitUnfinishedMarker = false;

        // Handle OpenUI 1.0 stored-message markers if present.
        if (working.contains("]]>openui:")) {
            // Per spec: a content marker in the middle of a line is moved onto its own line.
            working = working.replace("]]>openui:content", "\n]]>openui:content");
            String[] rawLines = working.split("\n", -1);
            StringBuilder activeContent = new StringBuilder();
            StringBuilder contextBuf = new StringBuilder();
            int mode = 0; // 0 = before/plain, 1 = content, 2 = context, 3 = ended
            boolean sawContentMarker = false;
            for (String line : rawLines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("]]>openui:content")) {
                    // With several content sections, the last one wins.
                    activeContent.setLength(0);
                    sawContentMarker = true;
                    mode = 1;
                    explicitUnfinishedMarker = true;
                    continue;
                }
                if (line.startsWith("]]>openui:context")) {
                    contextBuf.setLength(0);
                    mode = 2;
                    continue;
                }
                if (line.startsWith("]]>openui:end")) {
                    explicitUnfinishedMarker = false;
                    mode = 3;
                    continue;
                }
                if (mode == 1 || (!sawContentMarker && mode == 0)) {
                    if (activeContent.length() > 0) {
                        activeContent.append('\n');
                    }
                    activeContent.append(line);
                } else if (mode == 2) {
                    if (contextBuf.length() > 0) {
                        contextBuf.append('\n');
                    }
                    contextBuf.append(line);
                }
            }
            working = activeContent.toString();
            String ctxStr = contextBuf.toString().trim();
            if (!ctxStr.isEmpty()) {
                try {
                    JsonElement parsed = JsonParser.parseString(ctxStr);
                    if (parsed != null && parsed.isJsonObject()) {
                        extractedContext = parsed.getAsJsonObject();
                    }
                } catch (Exception ignored) {
                }
            }
            // If it came from ]]>openui:content without fences and looks like statements,
            // treat the whole body as an OPENUI segment.
            if (sawContentMarker && !working.toLowerCase(Locale.US).contains("```openui")) {
                out.add(new Segment(Segment.Kind.OPENUI, working.trim(),
                        explicitUnfinishedMarker, extractedContext));
                return out;
            }
        }

        String[] lines = working.split("\n", -1);
        StringBuilder proseBuf = new StringBuilder();
        StringBuilder codeBuf = new StringBuilder();
        boolean inOpenUiFence = false;
        boolean inOtherFence = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (!inOpenUiFence && !inOtherFence) {
                if (isOpenUiFenceStart(trimmed)) {
                    flushMarkdown(out, proseBuf);
                    codeBuf.setLength(0);
                    inOpenUiFence = true;
                    continue;
                }
                if (trimmed.startsWith("```")) {
                    inOtherFence = true;
                }
                appendLine(proseBuf, line);
            } else if (inOpenUiFence) {
                if (trimmed.equals("```")) {
                    out.add(new Segment(Segment.Kind.OPENUI, codeBuf.toString(),
                            false, extractedContext));
                    codeBuf.setLength(0);
                    inOpenUiFence = false;
                } else {
                    appendLine(codeBuf, line);
                }
            } else {
                appendLine(proseBuf, line);
                if (trimmed.equals("```")) {
                    inOtherFence = false;
                }
            }
        }

        if (inOpenUiFence) {
            out.add(new Segment(Segment.Kind.OPENUI, codeBuf.toString(),
                    true, extractedContext));
        }
        flushMarkdown(out, proseBuf);
        return out;
    }

    private static boolean isOpenUiFenceStart(String trimmedLine) {
        if (!trimmedLine.startsWith("```")) {
            return false;
        }
        String tag = trimmedLine.substring(3).trim().toLowerCase(Locale.US);
        return tag.equals("openui")
                || tag.equals("openui-lang")
                || tag.equals("openui_lang")
                || tag.startsWith("openui ")
                || tag.startsWith("openui-lang ");
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(line);
    }

    private static void flushMarkdown(List<Segment> out, StringBuilder proseBuf) {
        if (proseBuf.length() == 0) {
            return;
        }
        String text = proseBuf.toString();
        if (!text.trim().isEmpty()) {
            out.add(new Segment(Segment.Kind.MARKDOWN, text.trim(), false, null));
        }
        proseBuf.setLength(0);
    }

    // ---------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------

    /** Non-fatal parse or runtime issue recorded for lossless export & debugging. */
    public static final class Diagnostic {
        public final String code;
        public final String message;
        public final int line;

        public Diagnostic(String code, String message, int line) {
            this.code = code == null ? "error" : code;
            this.message = message == null ? "" : message;
            this.line = line;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("code", code);
            o.addProperty("message", message);
            if (line > 0) {
                o.addProperty("line", line);
            }
            return o;
        }
    }

    // ---------------------------------------------------------------------
    // AST Nodes
    // ---------------------------------------------------------------------

    public abstract static class Expr {
        abstract void collectReferences(Set<String> refs, Set<String> stateRefs);
    }

    public static final class LiteralExpr extends Expr {
        public final Object value; // String, Double, Boolean, or null

        public LiteralExpr(Object value) {
            this.value = value;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
        }
    }

    public static final class IdentExpr extends Expr {
        public final String name;

        public IdentExpr(String name) {
            this.name = name;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            refs.add(name);
        }
    }

    public static final class StateRefExpr extends Expr {
        /** Variable name WITHOUT the leading {@code $} (e.g. {@code "days"} for {@code $days}). */
        public final String varName;

        public StateRefExpr(String varName) {
            this.varName = varName;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            stateRefs.add(varName);
        }
    }

    public static final class ArrayExpr extends Expr {
        public final List<Expr> items;

        public ArrayExpr(List<Expr> items) {
            this.items = items;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            for (Expr e : items) {
                if (e != null) {
                    e.collectReferences(refs, stateRefs);
                }
            }
        }
    }

    public static final class ObjectExpr extends Expr {
        public final Map<String, Expr> entries;

        public ObjectExpr(Map<String, Expr> entries) {
            this.entries = entries;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            for (Expr e : entries.values()) {
                if (e != null) {
                    e.collectReferences(refs, stateRefs);
                }
            }
        }
    }

    public static final class ComponentExpr extends Expr {
        public final String type;
        public final List<Expr> args;
        public final Map<String, Expr> namedArgs;

        public ComponentExpr(String type, List<Expr> args, Map<String, Expr> namedArgs) {
            this.type = type;
            this.args = args;
            this.namedArgs = namedArgs;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            for (Expr e : args) {
                if (e != null) {
                    e.collectReferences(refs, stateRefs);
                }
            }
            for (Expr e : namedArgs.values()) {
                if (e != null) {
                    e.collectReferences(refs, stateRefs);
                }
            }
        }
    }

    public static final class AtCallExpr extends Expr {
        /** Name WITHOUT the leading {@code @} (e.g. {@code "Count"}, {@code "ToAssistant"}). */
        public final String name;
        public final List<Expr> args;

        public AtCallExpr(String name, List<Expr> args) {
            this.name = name;
            this.args = args;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            if ("Each".equals(name) && args.size() >= 3 && args.get(1) instanceof IdentExpr) {
                String loopVar = ((IdentExpr) args.get(1)).name;
                if (args.get(0) != null) {
                    args.get(0).collectReferences(refs, stateRefs);
                }
                Set<String> subRefs = new HashSet<>();
                if (args.get(2) != null) {
                    args.get(2).collectReferences(subRefs, stateRefs);
                }
                subRefs.remove(loopVar);
                refs.addAll(subRefs);
                return;
            }
            for (Expr e : args) {
                if (e != null) {
                    e.collectReferences(refs, stateRefs);
                }
            }
        }
    }

    public static final class ActionListExpr extends Expr {
        public final List<Expr> actions;

        public ActionListExpr(List<Expr> actions) {
            this.actions = actions;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            for (Expr e : actions) {
                if (e != null) {
                    e.collectReferences(refs, stateRefs);
                }
            }
        }
    }

    public static final class QueryExpr extends Expr {
        public final boolean mutation;
        public final List<Expr> args;

        public QueryExpr(boolean mutation, List<Expr> args) {
            this.mutation = mutation;
            this.args = args;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            for (Expr e : args) {
                if (e != null) {
                    e.collectReferences(refs, stateRefs);
                }
            }
        }
    }

    public static final class MemberExpr extends Expr {
        public final Expr target;
        public final String field;

        public MemberExpr(Expr target, String field) {
            this.target = target;
            this.field = field;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            if (target != null) {
                target.collectReferences(refs, stateRefs);
            }
        }
    }

    public static final class UnaryExpr extends Expr {
        public final String op;
        public final Expr operand;

        public UnaryExpr(String op, Expr operand) {
            this.op = op;
            this.operand = operand;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            if (operand != null) {
                operand.collectReferences(refs, stateRefs);
            }
        }
    }

    public static final class BinaryExpr extends Expr {
        public final String op;
        public final Expr left;
        public final Expr right;

        public BinaryExpr(String op, Expr left, Expr right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            if (left != null) {
                left.collectReferences(refs, stateRefs);
            }
            if (right != null) {
                right.collectReferences(refs, stateRefs);
            }
        }
    }

    public static final class TernaryExpr extends Expr {
        public final Expr condition;
        public final Expr thenExpr;
        public final Expr elseExpr;

        public TernaryExpr(Expr condition, Expr thenExpr, Expr elseExpr) {
            this.condition = condition;
            this.thenExpr = thenExpr;
            this.elseExpr = elseExpr;
        }

        @Override
        void collectReferences(Set<String> refs, Set<String> stateRefs) {
            if (condition != null) {
                condition.collectReferences(refs, stateRefs);
            }
            if (thenExpr != null) {
                thenExpr.collectReferences(refs, stateRefs);
            }
            if (elseExpr != null) {
                elseExpr.collectReferences(refs, stateRefs);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Statements & Program
    // ---------------------------------------------------------------------

    public static final class Statement {
        public enum Kind {
            ASSIGN,
            STATE,
            DELETE
        }

        public final Kind kind;
        /** Statement identifier (without {@code $} for {@link Kind#STATE}). */
        public final String name;
        public final Expr expr;
        public final String rawLine;
        public final int lineNumber;

        public Statement(Kind kind, String name, Expr expr, String rawLine, int lineNumber) {
            this.kind = kind;
            this.name = name;
            this.expr = expr;
            this.rawLine = rawLine;
            this.lineNumber = lineNumber;
        }
    }

    /** Parsed OpenUI Lang 1.0 program. */
    public static final class Program {
        /** Component / value / query statements keyed by name (last definition wins). */
        public final Map<String, Statement> statements = new LinkedHashMap<>();
        /** Reactive {@code $variable} default statements keyed by variable name (without {@code $}). */
        public final Map<String, Statement> stateDefaults = new LinkedHashMap<>();
        /** Explicit {@code id = null} tombstones seen in this source (used by {@link #mergeStatements}). */
        public final Set<String> deletions = new HashSet<>();
        public final List<Diagnostic> diagnostics = new ArrayList<>();
        public final boolean streaming;

        public Program(boolean streaming) {
            this.streaming = streaming;
        }

        /**
         * Resolves the entry statement name per OpenUI 1.0 rules:
         * <ol>
         *   <li>Statement named {@code root}, if present.</li>
         *   <li>Fallback: first statement calling one of {@code rootComponentNames}
         *       (or first {@link ComponentExpr} statement when {@code rootComponentNames} is null/empty),
         *       recording a {@code no-root} warning once streaming completes.</li>
         * </ol>
         */
        public String resolveRootId(Set<String> rootComponentNames) {
            if (statements.containsKey("root")) {
                return "root";
            }
            String fallback = null;
            for (Map.Entry<String, Statement> entry : statements.entrySet()) {
                Expr e = entry.getValue().expr;
                if (e instanceof ComponentExpr) {
                    ComponentExpr comp = (ComponentExpr) e;
                    if (rootComponentNames == null || rootComponentNames.isEmpty()
                            || rootComponentNames.contains(comp.type)) {
                        fallback = entry.getKey();
                        break;
                    }
                }
            }
            if (fallback == null) {
                for (Map.Entry<String, Statement> entry : statements.entrySet()) {
                    if (entry.getValue().expr instanceof ComponentExpr) {
                        fallback = entry.getKey();
                        break;
                    }
                }
            }
            if (!streaming) {
                if (fallback != null) {
                    diagnostics.add(new Diagnostic("no-root",
                            "No 'root' statement found; fell back to '" + fallback + "'.", 0));
                } else if (!statements.isEmpty()) {
                    diagnostics.add(new Diagnostic("no-root",
                            "Program has no 'root' entry statement.", 0));
                }
            }
            return fallback;
        }
    }

    /** Parses an OpenUI Lang program string. Never throws on malformed model output. */
    public static Program parse(String source) {
        return parse(source, false);
    }

    /** Parses an OpenUI Lang program string, suppressing end-of-stream warnings when {@code streaming}. */
    public static Program parse(String source, boolean streaming) {
        Program program = new Program(streaming);
        if (source == null || source.trim().isEmpty()) {
            return program;
        }
        List<LogicalLine> lines = splitLogicalLines(source);
        for (int idx = 0; idx < lines.size(); idx++) {
            LogicalLine ll = lines.get(idx);
            boolean isLastStreamingLine = streaming && idx == lines.size() - 1;
            parseStatementLine(ll.text, ll.lineNumber, isLastStreamingLine, program);
        }
        return program;
    }

    private static final class LogicalLine {
        final String text;
        final int lineNumber;

        LogicalLine(String text, int lineNumber) {
            this.text = text;
            this.lineNumber = lineNumber;
        }
    }

    /**
     * Splits source into logical assignment lines, joining physical lines when
     * brackets {@code (...)}, {@code [...]}, or {@code {...}} are still open.
     */
    private static List<LogicalLine> splitLogicalLines(String source) {
        List<LogicalLine> result = new ArrayList<>();
        String[] rawLines = source.replace("\r\n", "\n").split("\n", -1);
        StringBuilder current = new StringBuilder();
        int startLine = 1;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;

        for (int i = 0; i < rawLines.length; i++) {
            String raw = stripLineComment(rawLines[i], quote);
            String trimmed = raw.trim();
            if (depth == 0 && quote == 0) {
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("```")) {
                    continue;
                }
                startLine = i + 1;
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(quote == 0 ? trimmed : raw);

            for (int c = 0; c < raw.length(); c++) {
                char ch = raw.charAt(c);
                if (quote != 0) {
                    if (escaped) {
                        escaped = false;
                    } else if (ch == '\\') {
                        escaped = true;
                    } else if (ch == quote) {
                        quote = 0;
                    }
                } else {
                    if (ch == '"' || ch == '\'') {
                        quote = ch;
                    } else if (ch == '(' || ch == '[' || ch == '{') {
                        depth++;
                    } else if (ch == ')' || ch == ']' || ch == '}') {
                        if (depth > 0) {
                            depth--;
                        }
                    }
                }
            }

            if (depth == 0 && quote == 0 && current.length() > 0) {
                result.add(new LogicalLine(current.toString().trim(), startLine));
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            result.add(new LogicalLine(current.toString().trim(), startLine));
        }
        return result;
    }

    private static String stripLineComment(String line, char activeQuote) {
        char quote = activeQuote;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else {
                if (ch == '"' || ch == '\'') {
                    quote = ch;
                } else if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    private static void parseStatementLine(String line, int lineNum,
                                           boolean isLastStreamingLine, Program program) {
        if (line.isEmpty()) {
            return;
        }
        // Locate assignment '=' (not '==', '!=', '<=', '>=').
        int eqIdx = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '"' || c == '\'') {
                break;
            }
            if (c == '=') {
                char prev = i > 0 ? line.charAt(i - 1) : 0;
                char next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
                if (prev != '!' && prev != '<' && prev != '>' && prev != '=' && next != '=') {
                    eqIdx = i;
                    break;
                }
            }
        }
        if (eqIdx <= 0) {
            if (!isLastStreamingLine) {
                program.diagnostics.add(new Diagnostic(
                        "syntax-recovered", "Expected 'identifier = Expression'", lineNum));
            }
            return;
        }

        String lhs = line.substring(0, eqIdx).trim();
        String rhs = line.substring(eqIdx + 1).trim();
        boolean isState = lhs.startsWith("$");
        String ident = isState ? lhs.substring(1).trim() : lhs;
        if (!isValidIdentifier(ident)) {
            if (!isLastStreamingLine) {
                program.diagnostics.add(new Diagnostic(
                        "syntax-recovered", "Invalid statement identifier: " + lhs, lineNum));
            }
            return;
        }
        if (rhs.isEmpty()) {
            if (!isLastStreamingLine) {
                program.diagnostics.add(new Diagnostic(
                        "syntax-recovered", "Missing expression after '=' for " + lhs, lineNum));
            }
            return;
        }

        try {
            Lexer lexer = new Lexer(rhs, isLastStreamingLine, lineNum, program.diagnostics);
            ExprParser parser = new ExprParser(lexer.tokenize(), isLastStreamingLine,
                    lineNum, program.diagnostics);
            Expr expr = parser.parseExpression();
            if (expr == null) {
                return;
            }
            if (isState) {
                Statement st = new Statement(Statement.Kind.STATE, ident, expr, line, lineNum);
                program.stateDefaults.put(ident, st);
            } else if (expr instanceof LiteralExpr && ((LiteralExpr) expr).value == null) {
                program.statements.remove(ident);
                program.deletions.add(ident);
            } else {
                program.deletions.remove(ident);
                Statement st = new Statement(Statement.Kind.ASSIGN, ident, expr, line, lineNum);
                program.statements.put(ident, st);
            }
        } catch (Exception e) {
            if (!isLastStreamingLine) {
                program.diagnostics.add(new Diagnostic(
                        "syntax-recovered", "Skipped malformed line: " + e.getMessage(), lineNum));
            }
        }
    }

    private static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char first = s.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Incremental Editing: mergeStatements (OpenUI 1.0 Spec)
    // ---------------------------------------------------------------------

    /**
     * Merges {@code patchSource} into {@code previousSource} by statement name
     * per the OpenUI Lang 1.0 Incremental Editing specification:
     * <ul>
     *   <li>Same name: patch definition replaces the old one.</li>
     *   <li>New name: statement is added.</li>
     *   <li>Not in patch: statement is kept.</li>
     *   <li>{@code name = null}: statement is deleted.</li>
     *   <li>Removed from parent (unreachable from {@code root}): dropped;
     *       {@code $variables} are always kept.</li>
     * </ul>
     */
    public static String mergeStatements(String previousSource, String patchSource) {
        if (previousSource == null || previousSource.trim().isEmpty()) {
            return patchSource == null ? "" : patchSource.trim();
        }
        if (patchSource == null || patchSource.trim().isEmpty()) {
            return previousSource.trim();
        }
        Program prev = parse(previousSource, false);
        Program patch = parse(patchSource, false);

        Map<String, Statement> mergedStates = new LinkedHashMap<>(prev.stateDefaults);
        mergedStates.putAll(patch.stateDefaults);

        Map<String, Statement> mergedStmts = new LinkedHashMap<>(prev.statements);
        for (String deleted : patch.deletions) {
            mergedStmts.remove(deleted);
        }
        for (Map.Entry<String, Statement> e : patch.statements.entrySet()) {
            mergedStmts.put(e.getKey(), e.getValue());
        }

        // Prune statements unreachable from root when root exists and was updated or existed.
        if (mergedStmts.containsKey("root")) {
            Set<String> reachable = new HashSet<>();
            ArrayList<String> queue = new ArrayList<>();
            queue.add("root");
            reachable.add("root");
            while (!queue.isEmpty()) {
                String cur = queue.remove(queue.size() - 1);
                Statement st = mergedStmts.get(cur);
                if (st == null || st.expr == null) {
                    continue;
                }
                Set<String> refs = new HashSet<>();
                Set<String> stateRefs = new HashSet<>();
                st.expr.collectReferences(refs, stateRefs);
                for (String ref : refs) {
                    if (mergedStmts.containsKey(ref) && reachable.add(ref)) {
                        queue.add(ref);
                    }
                }
            }
            // Also keep Query/Mutation statements referenced by @Run(...) inside reachable nodes
            // (already collected via AtCallExpr.collectReferences).
            mergedStmts.keySet().retainAll(reachable);
        }

        StringBuilder out = new StringBuilder();
        for (Statement st : mergedStates.values()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(st.rawLine);
        }
        for (Statement st : mergedStmts.values()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(st.rawLine);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------
    // Lexer & Recursive-Descent Expression Parser
    // ---------------------------------------------------------------------

    private enum TokKind {
        IDENT, STATE_IDENT, AT_IDENT, STRING, NUMBER,
        LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
        COMMA, COLON, DOT, QUESTION,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        EQEQ, NEQ, LT, LTE, GT, GTE, AND, OR, BANG,
        EOF
    }

    private static final class Token {
        final TokKind kind;
        final String text;
        final double numVal;

        Token(TokKind kind, String text, double numVal) {
            this.kind = kind;
            this.text = text;
            this.numVal = numVal;
        }
    }

    private static final class Lexer {
        private final String s;
        private final int len;
        private final boolean forgivingEof;
        private final int lineNum;
        private final List<Diagnostic> diagnostics;
        private int pos;

        Lexer(String s, boolean forgivingEof, int lineNum, List<Diagnostic> diagnostics) {
            this.s = s;
            this.len = s.length();
            this.forgivingEof = forgivingEof;
            this.lineNum = lineNum;
            this.diagnostics = diagnostics;
        }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (pos < len) {
                char c = s.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                    continue;
                }
                if (c == '/' && pos + 1 < len && s.charAt(pos + 1) == '/') {
                    break;
                }
                switch (c) {
                    case '(':
                        tokens.add(new Token(TokKind.LPAREN, "(", 0));
                        pos++;
                        continue;
                    case ')':
                        tokens.add(new Token(TokKind.RPAREN, ")", 0));
                        pos++;
                        continue;
                    case '[':
                        tokens.add(new Token(TokKind.LBRACKET, "[", 0));
                        pos++;
                        continue;
                    case ']':
                        tokens.add(new Token(TokKind.RBRACKET, "]", 0));
                        pos++;
                        continue;
                    case '{':
                        tokens.add(new Token(TokKind.LBRACE, "{", 0));
                        pos++;
                        continue;
                    case '}':
                        tokens.add(new Token(TokKind.RBRACE, "}", 0));
                        pos++;
                        continue;
                    case ',':
                        tokens.add(new Token(TokKind.COMMA, ",", 0));
                        pos++;
                        continue;
                    case ':':
                        tokens.add(new Token(TokKind.COLON, ":", 0));
                        pos++;
                        continue;
                    case '.':
                        tokens.add(new Token(TokKind.DOT, ".", 0));
                        pos++;
                        continue;
                    case '?':
                        tokens.add(new Token(TokKind.QUESTION, "?", 0));
                        pos++;
                        continue;
                    case '+':
                        tokens.add(new Token(TokKind.PLUS, "+", 0));
                        pos++;
                        continue;
                    case '-':
                        tokens.add(new Token(TokKind.MINUS, "-", 0));
                        pos++;
                        continue;
                    case '*':
                        tokens.add(new Token(TokKind.STAR, "*", 0));
                        pos++;
                        continue;
                    case '/':
                        tokens.add(new Token(TokKind.SLASH, "/", 0));
                        pos++;
                        continue;
                    case '%':
                        tokens.add(new Token(TokKind.PERCENT, "%", 0));
                        pos++;
                        continue;
                    case '=':
                        if (pos + 1 < len && s.charAt(pos + 1) == '=') {
                            tokens.add(new Token(TokKind.EQEQ, "==", 0));
                            pos += 2;
                        } else {
                            // Forgivingly treat single '=' inside object/arg as ':'
                            tokens.add(new Token(TokKind.COLON, ":", 0));
                            pos++;
                        }
                        continue;
                    case '!':
                        if (pos + 1 < len && s.charAt(pos + 1) == '=') {
                            tokens.add(new Token(TokKind.NEQ, "!=", 0));
                            pos += 2;
                        } else {
                            tokens.add(new Token(TokKind.BANG, "!", 0));
                            pos++;
                        }
                        continue;
                    case '<':
                        if (pos + 1 < len && s.charAt(pos + 1) == '=') {
                            tokens.add(new Token(TokKind.LTE, "<=", 0));
                            pos += 2;
                        } else {
                            tokens.add(new Token(TokKind.LT, "<", 0));
                            pos++;
                        }
                        continue;
                    case '>':
                        if (pos + 1 < len && s.charAt(pos + 1) == '=') {
                            tokens.add(new Token(TokKind.GTE, ">=", 0));
                            pos += 2;
                        } else {
                            tokens.add(new Token(TokKind.GT, ">", 0));
                            pos++;
                        }
                        continue;
                    case '&':
                        if (pos + 1 < len && s.charAt(pos + 1) == '&') {
                            tokens.add(new Token(TokKind.AND, "&&", 0));
                            pos += 2;
                        } else {
                            pos++;
                        }
                        continue;
                    case '|':
                        if (pos + 1 < len && s.charAt(pos + 1) == '|') {
                            tokens.add(new Token(TokKind.OR, "||", 0));
                            pos += 2;
                        } else {
                            pos++;
                        }
                        continue;
                    case '"':
                    case '\'':
                        tokens.add(readString(c));
                        continue;
                    case '$':
                        pos++;
                        tokens.add(new Token(TokKind.STATE_IDENT, readIdent(), 0));
                        continue;
                    case '@':
                        pos++;
                        tokens.add(new Token(TokKind.AT_IDENT, readIdent(), 0));
                        continue;
                    default:
                        break;
                }
                if (Character.isDigit(c)) {
                    tokens.add(readNumber());
                } else if (Character.isLetter(c) || c == '_') {
                    tokens.add(new Token(TokKind.IDENT, readIdent(), 0));
                } else {
                    if (!forgivingEof) {
                        diagnostics.add(new Diagnostic(
                                "syntax-recovered", "Unexpected character '" + c + "'", lineNum));
                    }
                    pos++;
                }
            }
            tokens.add(new Token(TokKind.EOF, "", 0));
            return tokens;
        }

        private Token readString(char quote) {
            pos++; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == quote) {
                    return new Token(TokKind.STRING, sb.toString(), 0);
                }
                if (c == '\\' && pos < len) {
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case '"':
                        case '\'':
                        case '\\':
                        case '/':
                            sb.append(esc);
                            break;
                        case 'u':
                            if (pos + 4 <= len) {
                                try {
                                    int code = Integer.parseInt(s.substring(pos, pos + 4), 16);
                                    sb.append((char) code);
                                    pos += 4;
                                } catch (NumberFormatException ignored) {
                                    sb.append("\\u");
                                }
                            }
                            break;
                        default:
                            sb.append(esc);
                            break;
                    }
                } else {
                    sb.append(c);
                }
            }
            // Forgiving while streaming: return partial string literal so live text renders!
            if (!forgivingEof) {
                diagnostics.add(new Diagnostic(
                        "syntax-recovered", "Unterminated string literal", lineNum));
            }
            return new Token(TokKind.STRING, sb.toString(), 0);
        }

        private Token readNumber() {
            int start = pos;
            boolean sawDot = false;
            while (pos < len) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !sawDot
                        && pos + 1 < len && Character.isDigit(s.charAt(pos + 1))) {
                    sawDot = true;
                    pos++;
                } else {
                    break;
                }
            }
            String numStr = s.substring(start, pos);
            double v = 0;
            try {
                v = Double.parseDouble(numStr);
            } catch (NumberFormatException ignored) {
            }
            return new Token(TokKind.NUMBER, numStr, v);
        }

        private String readIdent() {
            int start = pos;
            while (pos < len) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            return s.substring(start, pos);
        }
    }

    private static final class ExprParser {
        private final List<Token> tokens;
        private final boolean forgivingEof;
        private final int lineNum;
        private final List<Diagnostic> diagnostics;
        private int idx;

        ExprParser(List<Token> tokens, boolean forgivingEof,
                   int lineNum, List<Diagnostic> diagnostics) {
            this.tokens = tokens;
            this.forgivingEof = forgivingEof;
            this.lineNum = lineNum;
            this.diagnostics = diagnostics;
        }

        private Token peek() {
            return tokens.get(Math.min(idx, tokens.size() - 1));
        }

        private Token peekAt(int offset) {
            int target = idx + offset;
            return tokens.get(Math.min(target, tokens.size() - 1));
        }

        private Token next() {
            Token t = peek();
            if (idx < tokens.size() - 1) {
                idx++;
            }
            return t;
        }

        private boolean match(TokKind k) {
            if (peek().kind == k) {
                next();
                return true;
            }
            return false;
        }

        Expr parseExpression() {
            if (peek().kind == TokKind.EOF) {
                return null;
            }
            return parseTernary();
        }

        private Expr parseTernary() {
            Expr cond = parseOr();
            if (match(TokKind.QUESTION)) {
                Expr thenBranch = parseExpression();
                Expr elseBranch = new LiteralExpr(null);
                if (match(TokKind.COLON)) {
                    Expr parsedElse = parseExpression();
                    if (parsedElse != null) {
                        elseBranch = parsedElse;
                    }
                }
                return new TernaryExpr(cond,
                        thenBranch != null ? thenBranch : new LiteralExpr(null),
                        elseBranch);
            }
            return cond;
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            while (match(TokKind.OR)) {
                Expr right = parseAnd();
                left = new BinaryExpr("||", left,
                        right != null ? right : new LiteralExpr(false));
            }
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseEquality();
            while (match(TokKind.AND)) {
                Expr right = parseEquality();
                left = new BinaryExpr("&&", left,
                        right != null ? right : new LiteralExpr(true));
            }
            return left;
        }

        private Expr parseEquality() {
            Expr left = parseComparison();
            while (peek().kind == TokKind.EQEQ || peek().kind == TokKind.NEQ) {
                String op = next().text;
                Expr right = parseComparison();
                left = new BinaryExpr(op, left,
                        right != null ? right : new LiteralExpr(null));
            }
            return left;
        }

        private Expr parseComparison() {
            Expr left = parseAdditive();
            while (peek().kind == TokKind.LT || peek().kind == TokKind.LTE
                    || peek().kind == TokKind.GT || peek().kind == TokKind.GTE) {
                String op = next().text;
                Expr right = parseAdditive();
                left = new BinaryExpr(op, left,
                        right != null ? right : new LiteralExpr(0.0));
            }
            return left;
        }

        private Expr parseAdditive() {
            Expr left = parseMultiplicative();
            while (peek().kind == TokKind.PLUS || peek().kind == TokKind.MINUS) {
                String op = next().text;
                Expr right = parseMultiplicative();
                left = new BinaryExpr(op, left,
                        right != null ? right : new LiteralExpr(op.equals("+") ? "" : 0.0));
            }
            return left;
        }

        private Expr parseMultiplicative() {
            Expr left = parseUnary();
            while (peek().kind == TokKind.STAR || peek().kind == TokKind.SLASH
                    || peek().kind == TokKind.PERCENT) {
                String op = next().text;
                Expr right = parseUnary();
                left = new BinaryExpr(op, left,
                        right != null ? right : new LiteralExpr(1.0));
            }
            return left;
        }

        private Expr parseUnary() {
            if (peek().kind == TokKind.BANG || peek().kind == TokKind.MINUS) {
                String op = next().text;
                Expr operand = parseUnary();
                if (op.equals("-") && operand instanceof LiteralExpr
                        && ((LiteralExpr) operand).value instanceof Double) {
                    return new LiteralExpr(-((Double) ((LiteralExpr) operand).value));
                }
                return new UnaryExpr(op,
                        operand != null ? operand : new LiteralExpr(0.0));
            }
            return parsePostfix();
        }

        private Expr parsePostfix() {
            Expr expr = parsePrimary();
            while (expr != null && match(TokKind.DOT)) {
                if (peek().kind == TokKind.IDENT) {
                    expr = new MemberExpr(expr, next().text);
                } else {
                    break;
                }
            }
            return expr;
        }

        private Expr parsePrimary() {
            Token t = peek();
            switch (t.kind) {
                case STRING:
                    next();
                    return new LiteralExpr(t.text);
                case NUMBER:
                    next();
                    return new LiteralExpr(t.numVal);
                case STATE_IDENT:
                    next();
                    return new StateRefExpr(t.text);
                case AT_IDENT: {
                    next();
                    List<Expr> args = new ArrayList<>();
                    if (match(TokKind.LPAREN)) {
                        parseArgList(args, null);
                    }
                    return new AtCallExpr(t.text, args);
                }
                case LBRACKET: {
                    next();
                    List<Expr> items = new ArrayList<>();
                    while (peek().kind != TokKind.RBRACKET && peek().kind != TokKind.EOF) {
                        Expr item = parseExpression();
                        if (item != null) {
                            items.add(item);
                        }
                        if (!match(TokKind.COMMA)) {
                            break;
                        }
                    }
                    match(TokKind.RBRACKET);
                    return new ArrayExpr(items);
                }
                case LBRACE: {
                    next();
                    Map<String, Expr> entries = new LinkedHashMap<>();
                    while (peek().kind != TokKind.RBRACE && peek().kind != TokKind.EOF) {
                        String key = null;
                        if (peek().kind == TokKind.IDENT || peek().kind == TokKind.STRING) {
                            key = next().text;
                        } else {
                            next();
                            continue;
                        }
                        if (match(TokKind.COLON)) {
                            Expr val = parseExpression();
                            entries.put(key, val != null ? val : new LiteralExpr(null));
                        }
                        if (!match(TokKind.COMMA)) {
                            break;
                        }
                    }
                    match(TokKind.RBRACE);
                    return new ObjectExpr(entries);
                }
                case LPAREN: {
                    next();
                    Expr inner = parseExpression();
                    match(TokKind.RPAREN);
                    return inner;
                }
                case IDENT: {
                    next();
                    String ident = t.text;
                    if ("true".equals(ident)) {
                        return new LiteralExpr(Boolean.TRUE);
                    }
                    if ("false".equals(ident)) {
                        return new LiteralExpr(Boolean.FALSE);
                    }
                    if ("null".equals(ident)) {
                        return new LiteralExpr(null);
                    }
                    if (match(TokKind.LPAREN)) {
                        List<Expr> args = new ArrayList<>();
                        Map<String, Expr> named = new LinkedHashMap<>();
                        parseArgList(args, named);
                        if ("Action".equals(ident)) {
                            List<Expr> steps = new ArrayList<>();
                            if (!args.isEmpty() && args.get(0) instanceof ArrayExpr) {
                                flattenActionExprs(((ArrayExpr) args.get(0)).items, steps);
                            } else {
                                flattenActionExprs(args, steps);
                            }
                            return new ActionListExpr(steps);
                        }
                        if ("Query".equals(ident) || "Mutation".equals(ident)) {
                            return new QueryExpr("Mutation".equals(ident), args);
                        }
                        return new ComponentExpr(ident, args, named);
                    }
                    return new IdentExpr(ident);
                }
                default:
                    if (!forgivingEof && t.kind != TokKind.EOF) {
                        diagnostics.add(new Diagnostic(
                                "syntax-recovered", "Unexpected token '" + t.text + "'", lineNum));
                    }
                    if (t.kind != TokKind.EOF) {
                        next();
                    }
                    return null;
            }
        }

        private void flattenActionExprs(List<Expr> raw, List<Expr> out) {
            for (Expr e : raw) {
                if (e instanceof ActionListExpr) {
                    flattenActionExprs(((ActionListExpr) e).actions, out);
                } else if (e != null) {
                    out.add(e);
                }
            }
        }

        private void parseArgList(List<Expr> positional, Map<String, Expr> namedOut) {
            while (peek().kind != TokKind.RPAREN && peek().kind != TokKind.EOF) {
                if (namedOut != null
                        && peek().kind == TokKind.IDENT
                        && peekAt(1).kind == TokKind.COLON) {
                    String key = next().text;
                    next(); // consume ':'
                    Expr val = parseExpression();
                    namedOut.put(key, val != null ? val : new LiteralExpr(null));
                } else {
                    Expr arg = parseExpression();
                    if (arg != null) {
                        positional.add(arg);
                    }
                }
                if (!match(TokKind.COMMA)) {
                    break;
                }
            }
            match(TokKind.RPAREN);
        }
    }

    // ---------------------------------------------------------------------
    // Evaluated Runtime Model: ComponentNode, PendingRef, ActionValue, RuntimeState
    // ---------------------------------------------------------------------

    /** Forward-referenced identifier whose line has not yet streamed in. */
    public static final class PendingRef {
        public final String name;

        public PendingRef(String name) {
            this.name = name;
        }
    }

    /** One executable OpenUI action step (e.g. {@code @Set}, {@code @Reset}, {@code @ToAssistant}). */
    public static final class ActionStep {
        public final String name;
        /** Raw AST arguments so {@code @Set($var, expr)} evaluates {@code expr} when clicked. */
        public final List<Expr> rawArgs;

        public ActionStep(String name, List<Expr> rawArgs) {
            this.name = name;
            this.rawArgs = rawArgs;
        }
    }

    /** Evaluated action expression attached to an interactive component prop (e.g. {@code Button}). */
    public static final class ActionValue {
        public final List<ActionStep> steps;

        public ActionValue(List<ActionStep> steps) {
            this.steps = steps;
        }
    }

    /** Host callback invoked when a rendered OpenUI component fires user actions. */
    public interface ActionHost {
        /** Sends {@code message} (plus optional structured {@code context} and {@code formState}) back to the agent. */
        void onToAssistant(String message, JsonElement context, JsonObject formState);

        /** Opens an external URL in the user's browser. */
        void onOpenUrl(String url);

        /** Optional custom {@code @ActionName(args...)} callback. */
        default void onCustomAction(String actionName, List<Object> args, JsonObject formState) {
        }
    }

    /** Evaluated UI component node ready for Android View rendering or Sketchware ViewBean translation. */
    public static final class ComponentNode {
        public final String id;
        public final String type;
        public final List<Object> args;
        public final Map<String, Object> namedArgs;
        /** Positional index -> {@code varName} (without {@code $}) when bound to a {@code $variable}. */
        public final Map<Integer, String> argBindings;

        public ComponentNode(String id, String type, List<Object> args,
                             Map<String, Object> namedArgs,
                             Map<Integer, String> argBindings) {
            this.id = id == null ? "" : id;
            this.type = type == null ? "" : type;
            this.args = args;
            this.namedArgs = namedArgs;
            this.argBindings = argBindings;
        }

        public Object arg(int index) {
            return index >= 0 && index < args.size() ? args.get(index) : null;
        }

        public Object namedOrPositional(String key, int index) {
            if (namedArgs != null && namedArgs.containsKey(key)) {
                return namedArgs.get(key);
            }
            return arg(index);
        }

        public String argString(int index, String fallback) {
            Object v = arg(index);
            return v == null || v instanceof PendingRef ? fallback : stringify(v);
        }

        public String namedOrString(String key, int index, String fallback) {
            Object v = namedOrPositional(key, index);
            return v == null || v instanceof PendingRef ? fallback : stringify(v);
        }

        public double argDouble(int index, double fallback) {
            return toDouble(arg(index), fallback);
        }

        public int argInt(int index, int fallback) {
            return (int) Math.round(toDouble(arg(index), fallback));
        }

        public boolean argBoolean(int index, boolean fallback) {
            Object v = arg(index);
            if (v == null || v instanceof PendingRef) {
                return fallback;
            }
            return toBoolean(v);
        }

        public ActionValue argAction(int index) {
            Object v = arg(index);
            return v instanceof ActionValue ? (ActionValue) v : null;
        }

        /** Returns flattened child {@link ComponentNode}s from positional argument {@code index}. */
        public List<ComponentNode> argChildren(int index) {
            List<ComponentNode> children = new ArrayList<>();
            collectChildNodes(arg(index), children);
            return children;
        }

        /** Returns both evaluated {@link ComponentNode}s and {@link PendingRef}s for streaming skeletons. */
        public List<Object> argChildItems(int index) {
            List<Object> items = new ArrayList<>();
            collectStreamItems(arg(index), items);
            return items;
        }

        /** Bound {@code $variable} name (without {@code $}) at positional {@code index}, or {@code null}. */
        public String boundVar(int index) {
            return argBindings.get(index);
        }

        private static void collectChildNodes(Object raw, List<ComponentNode> out) {
            if (raw == null) {
                return;
            }
            if (raw instanceof ComponentNode) {
                out.add((ComponentNode) raw);
            } else if (raw instanceof List<?>) {
                for (Object item : (List<?>) raw) {
                    collectChildNodes(item, out);
                }
            }
        }

        private static void collectStreamItems(Object raw, List<Object> out) {
            if (raw == null) {
                return;
            }
            if (raw instanceof ComponentNode || raw instanceof PendingRef) {
                out.add(raw);
            } else if (raw instanceof List<?>) {
                for (Object item : (List<?>) raw) {
                    collectStreamItems(item, out);
                }
            }
        }
    }

    /**
     * Reactive runtime for one OpenUI Lang program instance.
     *
     * <p>Holds {@code $variable} values, evaluates the {@link Program} into a root
     * {@link ComponentNode}, re-evaluates locally when user controls mutate a
     * {@code $variable}, and serializes state to/from {@link JsonObject}.
     */
    public static final class RuntimeState {
        public interface OnStateChangeListener {
            void onStateChanged();
        }

        private Program program;
        private final Map<String, Object> stateVars = new LinkedHashMap<>();
        private final Map<String, Object> defaultVars = new LinkedHashMap<>();
        private OnStateChangeListener changeListener;

        public RuntimeState(Program program, JsonObject persistedVars) {
            bindProgram(program, persistedVars);
        }

        public void setOnStateChangeListener(OnStateChangeListener listener) {
            this.changeListener = listener;
        }

        /** Updates the underlying {@link Program} (e.g. as new lines stream in) while keeping user edits. */
        public void bindProgram(Program nextProgram, JsonObject persistedVars) {
            this.program = nextProgram != null ? nextProgram : new Program(false);
            defaultVars.clear();
            for (Map.Entry<String, Statement> e : this.program.stateDefaults.entrySet()) {
                Object defVal = evalExpr(e.getValue().expr, e.getKey(),
                        Collections.emptyMap(), new HashSet<>());
                defaultVars.put(e.getKey(), defVal);
                if (!stateVars.containsKey(e.getKey())) {
                    stateVars.put(e.getKey(), defVal);
                }
            }
            if (persistedVars != null) {
                for (Map.Entry<String, JsonElement> entry : persistedVars.entrySet()) {
                    stateVars.put(entry.getKey(), fromJsonElement(entry.getValue()));
                }
            }
        }

        public Program getProgram() {
            return program;
        }

        public Object getVar(String varName) {
            return stateVars.get(stripDollar(varName));
        }

        public void setVar(String varName, Object value) {
            setVar(varName, value, true);
        }

        public void setVar(String varName, Object value, boolean notify) {
            String clean = stripDollar(varName);
            if (clean.isEmpty()) {
                return;
            }
            stateVars.put(clean, value);
            if (notify && changeListener != null) {
                changeListener.onStateChanged();
            }
        }

        public void resetVars(List<String> varNames, boolean notify) {
            if (varNames == null || varNames.isEmpty()) {
                stateVars.clear();
                stateVars.putAll(defaultVars);
            } else {
                for (String v : varNames) {
                    String clean = stripDollar(v);
                    if (defaultVars.containsKey(clean)) {
                        stateVars.put(clean, defaultVars.get(clean));
                    } else {
                        stateVars.remove(clean);
                    }
                }
            }
            if (notify && changeListener != null) {
                changeListener.onStateChanged();
            }
        }

        /** Exports current {@code $variable} state for persistence & {@code @ToAssistant} payloads. */
        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, Object> entry : stateVars.entrySet()) {
                out.add(entry.getKey(), toJsonElement(entry.getValue()));
            }
            return out;
        }

        /** Evaluates the entry {@link ComponentNode} of this program, or {@code null} if none yet. */
        public ComponentNode evaluateRoot(Set<String> rootComponentNames) {
            String rootId = program.resolveRootId(rootComponentNames);
            if (rootId == null) {
                return null;
            }
            Statement rootStmt = program.statements.get(rootId);
            if (rootStmt == null) {
                return null;
            }
            Set<String> activeStack = new HashSet<>();
            activeStack.add(rootId);
            Object val = evalExpr(rootStmt.expr, rootId, Collections.emptyMap(), activeStack);
            return val instanceof ComponentNode ? (ComponentNode) val : null;
        }

        /** Evaluates every top-level component statement by ID (used by screen translator). */
        public Object evaluateStatement(String id) {
            Statement st = program.statements.get(id);
            if (st == null) {
                return null;
            }
            Set<String> stack = new HashSet<>();
            stack.add(id);
            return evalExpr(st.expr, id, Collections.emptyMap(), stack);
        }

        /** Executes an {@link ActionValue} locally and/or dispatches to {@code host}. */
        public void executeAction(ActionValue action, ActionHost host) {
            if (action == null || action.steps.isEmpty()) {
                return;
            }
            boolean mutatedVars = false;
            for (ActionStep step : action.steps) {
                if ("Set".equals(step.name)) {
                    if (step.rawArgs.size() >= 2 && step.rawArgs.get(0) instanceof StateRefExpr) {
                        String varName = ((StateRefExpr) step.rawArgs.get(0)).varName;
                        Object nextVal = evalExpr(step.rawArgs.get(1), "",
                                Collections.emptyMap(), new HashSet<>());
                        stateVars.put(varName, nextVal);
                        mutatedVars = true;
                    }
                } else if ("Reset".equals(step.name)) {
                    List<String> vars = new ArrayList<>();
                    for (Expr arg : step.rawArgs) {
                        if (arg instanceof StateRefExpr) {
                            vars.add(((StateRefExpr) arg).varName);
                        } else if (arg instanceof IdentExpr) {
                            vars.add(((IdentExpr) arg).name);
                        }
                    }
                    resetVars(vars, false);
                    mutatedVars = true;
                } else if ("OpenUrl".equals(step.name)) {
                    if (!step.rawArgs.isEmpty() && host != null) {
                        Object u = evalExpr(step.rawArgs.get(0), "",
                                Collections.emptyMap(), new HashSet<>());
                        String url = stringify(u).trim();
                        if (!url.isEmpty()) {
                            host.onOpenUrl(url);
                        }
                    }
                } else if ("ToAssistant".equals(step.name)) {
                    if (!step.rawArgs.isEmpty() && host != null) {
                        Object m = evalExpr(step.rawArgs.get(0), "",
                                Collections.emptyMap(), new HashSet<>());
                        String msg = stringify(m).trim();
                        JsonElement ctxJson = null;
                        if (step.rawArgs.size() >= 2) {
                            Object ctxObj = evalExpr(step.rawArgs.get(1), "",
                                    Collections.emptyMap(), new HashSet<>());
                            ctxJson = toJsonElement(ctxObj);
                        }
                        if (!msg.isEmpty()) {
                            host.onToAssistant(msg, ctxJson, toJson());
                        }
                    }
                } else if ("Run".equals(step.name)) {
                    // Local Query/Mutation no-op unless wired to a custom host.
                    if (host != null && !step.rawArgs.isEmpty()) {
                        List<Object> evaluated = new ArrayList<>();
                        for (Expr a : step.rawArgs) {
                            evaluated.add(a instanceof IdentExpr
                                    ? ((IdentExpr) a).name
                                    : evalExpr(a, "", Collections.emptyMap(), new HashSet<>()));
                        }
                        host.onCustomAction("Run", evaluated, toJson());
                    }
                } else if (host != null) {
                    List<Object> evaluated = new ArrayList<>();
                    for (Expr a : step.rawArgs) {
                        evaluated.add(evalExpr(a, "", Collections.emptyMap(), new HashSet<>()));
                    }
                    host.onCustomAction(step.name, evaluated, toJson());
                }
            }
            if (mutatedVars && changeListener != null) {
                changeListener.onStateChanged();
            }
        }

        private Object evalExpr(Expr expr, String assignId,
                                Map<String, Object> locals, Set<String> activeRefs) {
            if (expr == null) {
                return null;
            }
            if (expr instanceof LiteralExpr) {
                return ((LiteralExpr) expr).value;
            }
            if (expr instanceof StateRefExpr) {
                return stateVars.get(((StateRefExpr) expr).varName);
            }
            if (expr instanceof IdentExpr) {
                String name = ((IdentExpr) expr).name;
                if (locals.containsKey(name)) {
                    return locals.get(name);
                }
                Statement target = program.statements.get(name);
                if (target == null) {
                    if (program.streaming) {
                        return new PendingRef(name);
                    }
                    program.diagnostics.add(new Diagnostic(
                            "dangling-ref", "Undefined reference '" + name + "'", 0));
                    return null;
                }
                if (!activeRefs.add(name)) {
                    program.diagnostics.add(new Diagnostic(
                            "circular-ref", "Circular reference involving '" + name + "'",
                            target.lineNumber));
                    return null;
                }
                try {
                    return evalExpr(target.expr, name, locals, activeRefs);
                } finally {
                    activeRefs.remove(name);
                }
            }
            if (expr instanceof ArrayExpr) {
                List<Object> out = new ArrayList<>();
                for (Expr item : ((ArrayExpr) expr).items) {
                    Object v = evalExpr(item, "", locals, activeRefs);
                    if (v != null) {
                        out.add(v);
                    } else if (item instanceof LiteralExpr) {
                        out.add(null);
                    }
                }
                return out;
            }
            if (expr instanceof ObjectExpr) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Map.Entry<String, Expr> e : ((ObjectExpr) expr).entries.entrySet()) {
                    map.put(e.getKey(), evalExpr(e.getValue(), "", locals, activeRefs));
                }
                return map;
            }
            if (expr instanceof ComponentExpr) {
                ComponentExpr comp = (ComponentExpr) expr;
                List<Object> evaluatedArgs = new ArrayList<>(comp.args.size());
                Map<Integer, String> bindings = new HashMap<>();
                for (int i = 0; i < comp.args.size(); i++) {
                    Expr argExpr = comp.args.get(i);
                    if (argExpr instanceof StateRefExpr) {
                        bindings.put(i, ((StateRefExpr) argExpr).varName);
                    }
                    evaluatedArgs.add(evalExpr(argExpr, "", locals, activeRefs));
                }
                Map<String, Object> evaluatedNamed = new LinkedHashMap<>();
                for (Map.Entry<String, Expr> ne : comp.namedArgs.entrySet()) {
                    evaluatedNamed.put(ne.getKey(),
                            evalExpr(ne.getValue(), "", locals, activeRefs));
                }
                return new ComponentNode(assignId, comp.type,
                        evaluatedArgs, evaluatedNamed, bindings);
            }
            if (expr instanceof ActionListExpr) {
                List<ActionStep> steps = new ArrayList<>();
                for (Expr stepExpr : ((ActionListExpr) expr).actions) {
                    if (stepExpr instanceof AtCallExpr) {
                        AtCallExpr ac = (AtCallExpr) stepExpr;
                        steps.add(new ActionStep(ac.name, ac.args));
                    }
                }
                return new ActionValue(steps);
            }
            if (expr instanceof AtCallExpr) {
                AtCallExpr ac = (AtCallExpr) expr;
                if (isBuiltInFunction(ac.name)) {
                    return evalBuiltInFunction(ac, locals, activeRefs);
                }
                // Any @ActionName(...) in OpenUI 1.0 is a standalone ActionExpression!
                List<ActionStep> single = new ArrayList<>(1);
                single.add(new ActionStep(ac.name, ac.args));
                return new ActionValue(single);
            }
            if (expr instanceof QueryExpr) {
                QueryExpr qe = (QueryExpr) expr;
                // Return defaults object (arg index 2) when present so static/offline UI renders cleanly.
                if (!qe.mutation && qe.args.size() >= 3) {
                    return evalExpr(qe.args.get(2), "", locals, activeRefs);
                }
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("rows", Collections.emptyList());
                return fallback;
            }
            if (expr instanceof MemberExpr) {
                MemberExpr me = (MemberExpr) expr;
                Object target = evalExpr(me.target, "", locals, activeRefs);
                return resolveMember(target, me.field);
            }
            if (expr instanceof UnaryExpr) {
                UnaryExpr ue = (UnaryExpr) expr;
                Object val = evalExpr(ue.operand, "", locals, activeRefs);
                if ("!".equals(ue.op)) {
                    return !toBoolean(val);
                }
                if ("-".equals(ue.op)) {
                    return -toDouble(val, 0.0);
                }
                return val;
            }
            if (expr instanceof BinaryExpr) {
                BinaryExpr be = (BinaryExpr) expr;
                if ("&&".equals(be.op)) {
                    Object l = evalExpr(be.left, "", locals, activeRefs);
                    return toBoolean(l) && toBoolean(evalExpr(be.right, "", locals, activeRefs));
                }
                if ("||".equals(be.op)) {
                    Object l = evalExpr(be.left, "", locals, activeRefs);
                    return toBoolean(l) || toBoolean(evalExpr(be.right, "", locals, activeRefs));
                }
                Object l = evalExpr(be.left, "", locals, activeRefs);
                Object r = evalExpr(be.right, "", locals, activeRefs);
                return evalBinaryOp(be.op, l, r);
            }
            if (expr instanceof TernaryExpr) {
                TernaryExpr te = (TernaryExpr) expr;
                Object cond = evalExpr(te.condition, "", locals, activeRefs);
                return toBoolean(cond)
                        ? evalExpr(te.thenExpr, assignId, locals, activeRefs)
                        : evalExpr(te.elseExpr, assignId, locals, activeRefs);
            }
            return null;
        }

        private static boolean isBuiltInFunction(String name) {
            switch (name) {
                case "Count":
                case "Sum":
                case "Avg":
                case "Min":
                case "Max":
                case "First":
                case "Last":
                case "Filter":
                case "Sort":
                case "Round":
                case "Abs":
                case "Floor":
                case "Ceil":
                case "Percent":
                case "Each":
                    return true;
                default:
                    return false;
            }
        }

        private Object evalBuiltInFunction(AtCallExpr call,
                                           Map<String, Object> locals,
                                           Set<String> activeRefs) {
            String fn = call.name;
            if ("Each".equals(fn)) {
                if (call.args.size() < 3) {
                    return Collections.emptyList();
                }
                Object arrObj = evalExpr(call.args.get(0), "", locals, activeRefs);
                if (!(arrObj instanceof List<?>)) {
                    return Collections.emptyList();
                }
                String varName = "item";
                if (call.args.get(1) instanceof IdentExpr) {
                    varName = ((IdentExpr) call.args.get(1)).name;
                } else if (call.args.get(1) instanceof StateRefExpr) {
                    varName = ((StateRefExpr) call.args.get(1)).varName;
                }
                Expr template = call.args.get(2);
                List<Object> rendered = new ArrayList<>();
                for (Object elem : (List<?>) arrObj) {
                    Map<String, Object> nextLocals = new HashMap<>(locals);
                    nextLocals.put(varName, elem);
                    Object itemResult = evalExpr(template, "", nextLocals, activeRefs);
                    if (itemResult != null) {
                        rendered.add(itemResult);
                    }
                }
                return rendered;
            }

            List<Object> args = new ArrayList<>(call.args.size());
            for (Expr e : call.args) {
                args.add(evalExpr(e, "", locals, activeRefs));
            }
            Object first = args.isEmpty() ? null : args.get(0);

            switch (fn) {
                case "Count":
                    return first instanceof List<?> ? (double) ((List<?>) first).size() : 0.0;
                case "Sum": {
                    if (!(first instanceof List<?>)) {
                        return 0.0;
                    }
                    double sum = 0.0;
                    for (Object x : (List<?>) first) {
                        sum += toDouble(x, 0.0);
                    }
                    return sum;
                }
                case "Avg": {
                    if (!(first instanceof List<?>) || ((List<?>) first).isEmpty()) {
                        return 0.0;
                    }
                    List<?> list = (List<?>) first;
                    double sum = 0.0;
                    for (Object x : list) {
                        sum += toDouble(x, 0.0);
                    }
                    return sum / list.size();
                }
                case "Min": {
                    if (!(first instanceof List<?>) || ((List<?>) first).isEmpty()) {
                        return 0.0;
                    }
                    double min = Double.POSITIVE_INFINITY;
                    for (Object x : (List<?>) first) {
                        min = Math.min(min, toDouble(x, min));
                    }
                    return Double.isInfinite(min) ? 0.0 : min;
                }
                case "Max": {
                    if (!(first instanceof List<?>) || ((List<?>) first).isEmpty()) {
                        return 0.0;
                    }
                    double max = Double.NEGATIVE_INFINITY;
                    for (Object x : (List<?>) first) {
                        max = Math.max(max, toDouble(x, max));
                    }
                    return Double.isInfinite(max) ? 0.0 : max;
                }
                case "First":
                    return (first instanceof List<?> && !((List<?>) first).isEmpty())
                            ? ((List<?>) first).get(0) : null;
                case "Last":
                    if (first instanceof List<?> && !((List<?>) first).isEmpty()) {
                        List<?> l = (List<?>) first;
                        return l.get(l.size() - 1);
                    }
                    return null;
                case "Filter": {
                    if (!(first instanceof List<?>) || args.size() < 4) {
                        return Collections.emptyList();
                    }
                    String field = stringify(args.get(1));
                    String op = stringify(args.get(2));
                    Object target = args.get(3);
                    List<Object> filtered = new ArrayList<>();
                    for (Object row : (List<?>) first) {
                        Object fieldVal = field.isEmpty() ? row : resolveMember(row, field);
                        if (matchesFilter(fieldVal, op, target)) {
                            filtered.add(row);
                        }
                    }
                    return filtered;
                }
                case "Sort": {
                    if (!(first instanceof List<?>)) {
                        return Collections.emptyList();
                    }
                    List<Object> copy = new ArrayList<>((List<?>) first);
                    final String field = args.size() >= 2 ? stringify(args.get(1)) : "";
                    final boolean desc = args.size() >= 3
                            && "desc".equalsIgnoreCase(stringify(args.get(2)));
                    Collections.sort(copy, new Comparator<Object>() {
                        @Override
                        public int compare(Object a, Object b) {
                            Object va = field.isEmpty() ? a : resolveMember(a, field);
                            Object vb = field.isEmpty() ? b : resolveMember(b, field);
                            int cmp = compareValues(va, vb);
                            return desc ? -cmp : cmp;
                        }
                    });
                    return copy;
                }
                case "Round": {
                    if (first == null) {
                        return null;
                    }
                    double n = toDouble(first, 0.0);
                    int decimals = args.size() >= 2 ? (int) Math.round(toDouble(args.get(1), 0)) : 0;
                    if (decimals <= 0) {
                        return (double) Math.round(n);
                    }
                    double factor = Math.pow(10, Math.min(decimals, 8));
                    return Math.round(n * factor) / factor;
                }
                case "Abs":
                    return first == null ? null : Math.abs(toDouble(first, 0.0));
                case "Floor":
                    return first == null ? null : Math.floor(toDouble(first, 0.0));
                case "Ceil":
                    return first == null ? null : Math.ceil(toDouble(first, 0.0));
                case "Percent": {
                    if (args.size() < 2) {
                        return 0.0;
                    }
                    double part = toDouble(args.get(0), 0.0);
                    double total = toDouble(args.get(1), 0.0);
                    return total == 0.0 ? 0.0 : Math.round((part / total) * 1000.0) / 10.0;
                }
                default:
                    return null;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Value Coercion, Operators & JSON Conversion Helpers
    // ---------------------------------------------------------------------

    private static String stripDollar(String name) {
        if (name == null) {
            return "";
        }
        String t = name.trim();
        return t.startsWith("$") ? t.substring(1) : t;
    }

    /** Member access supporting OpenUI's array column-pluck ({@code rows.title}). */
    static Object resolveMember(Object target, String field) {
        if (target == null || field == null) {
            return null;
        }
        if (target instanceof Map<?, ?>) {
            return ((Map<?, ?>) target).get(field);
        }
        if (target instanceof List<?>) {
            List<?> list = (List<?>) target;
            if ("length".equals(field) || "size".equals(field)) {
                return (double) list.size();
            }
            List<Object> plucked = new ArrayList<>(list.size());
            for (Object elem : list) {
                plucked.add(resolveMember(elem, field));
            }
            return plucked;
        }
        return null;
    }

    private static Object evalBinaryOp(String op, Object l, Object r) {
        switch (op) {
            case "+":
                if (l instanceof String || r instanceof String) {
                    return stringify(l) + stringify(r);
                }
                return toDouble(l, 0.0) + toDouble(r, 0.0);
            case "-":
                return toDouble(l, 0.0) - toDouble(r, 0.0);
            case "*":
                return toDouble(l, 0.0) * toDouble(r, 0.0);
            case "/": {
                double denom = toDouble(r, 0.0);
                return denom == 0.0 ? 0.0 : toDouble(l, 0.0) / denom;
            }
            case "%": {
                double denom = toDouble(r, 0.0);
                return denom == 0.0 ? 0.0 : toDouble(l, 0.0) % denom;
            }
            case "==":
                return valuesEqual(l, r);
            case "!=":
                return !valuesEqual(l, r);
            case "<":
                return compareValues(l, r) < 0;
            case "<=":
                return compareValues(l, r) <= 0;
            case ">":
                return compareValues(l, r) > 0;
            case ">=":
                return compareValues(l, r) >= 0;
            default:
                return null;
        }
    }

    private static boolean matchesFilter(Object left, String op, Object right) {
        if ("contains".equalsIgnoreCase(op)) {
            return stringify(left).toLowerCase(Locale.US)
                    .contains(stringify(right).toLowerCase(Locale.US));
        }
        Object res = evalBinaryOp(op, left, right);
        return Boolean.TRUE.equals(res);
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        }
        return stringify(a).equals(stringify(b));
    }

    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        return stringify(a).compareToIgnoreCase(stringify(b));
    }

    public static boolean toBoolean(Object v) {
        if (v == null || v instanceof PendingRef) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0.0;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"0".equals(s);
        }
        if (v instanceof List<?>) {
            return !((List<?>) v).isEmpty();
        }
        return true;
    }

    public static double toDouble(Object v, double fallback) {
        if (v == null || v instanceof PendingRef) {
            return fallback;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1.0 : 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String stringify(Object v) {
        if (v == null || v instanceof PendingRef) {
            return "";
        }
        if (v instanceof Double) {
            double d = (Double) v;
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d)
                    && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return String.valueOf(v);
    }

    public static JsonElement toJsonElement(Object value) {
        if (value == null || value instanceof PendingRef || value instanceof ActionValue) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof JsonElement) {
            return (JsonElement) value;
        }
        if (value instanceof Boolean) {
            return new JsonPrimitive((Boolean) value);
        }
        if (value instanceof Double) {
            double d = (Double) value;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return new JsonPrimitive((long) d);
            }
            return new JsonPrimitive(d);
        }
        if (value instanceof Number) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof String) {
            return new JsonPrimitive((String) value);
        }
        if (value instanceof List<?>) {
            JsonArray arr = new JsonArray();
            for (Object item : (List<?>) value) {
                arr.add(toJsonElement(item));
            }
            return arr;
        }
        if (value instanceof Map<?, ?>) {
            JsonObject obj = new JsonObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                obj.add(String.valueOf(e.getKey()), toJsonElement(e.getValue()));
            }
            return obj;
        }
        if (value instanceof ComponentNode) {
            ComponentNode cn = (ComponentNode) value;
            JsonObject obj = new JsonObject();
            obj.addProperty("id", cn.id);
            obj.addProperty("component", cn.type);
            obj.add("args", toJsonElement(cn.args));
            return obj;
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    public static Object fromJsonElement(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                return p.getAsDouble();
            }
            return p.getAsString();
        }
        if (el.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : el.getAsJsonArray()) {
                list.add(fromJsonElement(item));
            }
            return list;
        }
        if (el.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), fromJsonElement(e.getValue()));
            }
            return map;
        }
        return null;
    }

    /** Escapes a Java string into a double-quoted OpenUI Lang string literal. */
    public static String quoteString(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Summarises all OpenUI blocks in an assistant message for {@link AgentExport}. */
    public static JsonObject buildDiagnosticSummary(String text, JsonObject persistedState) {
        if (!containsOpenUi(text)) {
            return null;
        }
        List<Segment> segments = splitSegments(text);
        JsonArray blocks = new JsonArray();
        int totalStatements = 0;
        int totalDiagnostics = 0;
        for (Segment seg : segments) {
            if (seg.kind != Segment.Kind.OPENUI) {
                continue;
            }
            Program prog = parse(seg.content, seg.streaming);
            String rootId = prog.resolveRootId(null);
            JsonObject b = new JsonObject();
            b.addProperty("streaming", seg.streaming);
            b.addProperty("statement_count", prog.statements.size());
            b.addProperty("state_var_count", prog.stateDefaults.size());
            b.addProperty("root_id", rootId == null ? "" : rootId);
            if (rootId != null && prog.statements.containsKey(rootId)
                    && prog.statements.get(rootId).expr instanceof ComponentExpr) {
                b.addProperty("root_component",
                        ((ComponentExpr) prog.statements.get(rootId).expr).type);
            }
            if (!prog.diagnostics.isEmpty()) {
                JsonArray diags = new JsonArray();
                for (Diagnostic d : prog.diagnostics) {
                    diags.add(d.toJson());
                }
                b.add("diagnostics", diags);
                totalDiagnostics += prog.diagnostics.size();
            }
            totalStatements += prog.statements.size();
            blocks.add(b);
        }
        if (blocks.size() == 0) {
            return null;
        }
        JsonObject summary = new JsonObject();
        summary.addProperty("block_count", blocks.size());
        summary.addProperty("total_statements", totalStatements);
        summary.addProperty("diagnostic_count", totalDiagnostics);
        if (persistedState != null && persistedState.size() > 0) {
            summary.add("state", persistedState.deepCopy());
        }
        summary.add("blocks", blocks);
        return summary;
    }
}
