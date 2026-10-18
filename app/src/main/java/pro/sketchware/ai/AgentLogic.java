package pro.sketchware.ai;

import android.content.Context;

import com.besome.sketch.beans.BlockBean;
import com.besome.sketch.beans.EventBean;
import com.besome.sketch.beans.ProjectFileBean;
import com.besome.sketch.beans.ViewBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import a.a.a.Fx;
import a.a.a.eC;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.jq;
import a.a.a.kq;
import a.a.a.lq;
import a.a.a.oq;
import a.a.a.xB;

/**
 * The {@code set_event_handler} engine: turns the model's short block
 * list into real {@link BlockBean}s and appends them to one event of
 * one activity, through the same data-manager calls the logic editor
 * itself uses.
 *
 * <p>Everything load-bearing comes from the app's own tables, read at
 * runtime: the parameter slots of every block ({@code lq.a}), the
 * canonical spec text ({@code xB}), the palette colour ({@code kq}),
 * the events a widget class raises ({@code oq}). The model-facing
 * vocabulary is deliberately small — statement blocks with literal
 * values, plus variable assignment/arithmetic and variable values
 * fed into text and messages (the one reporter shape the editor
 * stores: a {@code getVar} bean whose spec is the variable name,
 * referenced from a slot as "@id"), appended after whatever the
 * event already does. No control flow, no raw code.
 *
 * <p>Stored shape, from the editor's load path and the code
 * generator: an event's blocks are one flat list keyed by
 * {@code targetId + "_" + eventName}; the first bean is the chain
 * head; {@code nextBlock}/{@code subStack1}/{@code subStack2} link
 * by integer id; {@code Fx} starts generating at the head. New block
 * ids continue from the largest id already present, exactly how the
 * canvas counter advances.
 */
public final class AgentLogic {

    /** At most this many blocks per handler call. */
    public static final int MAX_BLOCKS = 8;

    /** Slot token separators, for finding editable values. */
    private static final java.util.regex.Pattern SLOT_PATTERN =
            java.util.regex.Pattern.compile("%[a-zA-Z]+(?:\\.[a-zA-Z]+)?");

    /** Widget kinds whose generated field has a setText method. */
    private static final List<String> TEXT_TYPES = Arrays.asList(
            "TextView", "Button", "MaterialButton", "EditText",
            "AutoCompleteTextView", "CheckBox", "RadioButton", "Switch");

    private AgentLogic() {
    }

    /** One translated statement. */
    private static final class Stmt {
        String opCode;
        String type;
        final ArrayList<String> params = new ArrayList<>();
        String sentence;
        /** Fragment the generated code must contain if this ran. */
        String expectCode;
    }

    /** A validated handler change; also the frozen proposal payload. */
    public static final class Plan {
        public String scId;
        public ProjectFileBean activity;
        public String targetId;
        public String eventName;
        public boolean activityEvent;
        public boolean eventExists;
        public int existingCount;
        public String header;
        public final ArrayList<String> lines = new ArrayList<>();
        final ArrayList<Stmt> statements = new ArrayList<>();
        final ArrayList<BlockBean> fresh = new ArrayList<>();
        /** Statement beans in chain order (what {@link #verify} walks). */
        final ArrayList<BlockBean> chain = new ArrayList<>();
        /** Reporter beans (e.g. a variable's value) referenced from a
         * statement's parameters as "@id"; they sit in the same stored
         * list but never on the statement chain. */
        final ArrayList<BlockBean> extras = new ArrayList<>();
    }

    /**
     * Validates a handler request and builds the beans it would add.
     * Pure: reads the project, changes nothing.
     */
    public static Plan plan(Context context, String scId,
                            ProjectFileBean activity, String target,
                            String event, JsonArray blocks)
            throws AgentTools.ToolException {
        if (activity.fileType != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            throw new AgentTools.ToolException("E_NOT_ACTIVITY",
                    "Custom views have no logic to wire. Pick an activity "
                            + "screen instead.");
        }
        if (blocks == null || blocks.isEmpty()
                || blocks.size() > MAX_BLOCKS) {
            throw new AgentTools.ToolException("E_BLOCKS_LIMIT",
                    "Give between 1 and " + MAX_BLOCKS
                            + " blocks per handler.");
        }
        String javaName = activity.getJavaName();
        eC store = jC.a(scId);

        Plan plan = new Plan();
        plan.scId = scId;
        plan.activity = activity;
        plan.eventName = event == null ? "" : event.trim();

        ArrayList<ViewBean> beans =
                SketchwareProjects.screenBeans(scId, activity);
        Map<String, ViewBean> byId = new LinkedHashMap<>();
        for (ViewBean bean : beans) {
            byId.put(bean.id, bean);
        }
        // The variables this activity declares (name to editor type
        // code), so variable blocks are checked before they run.
        LinkedHashMap<String, Integer> variables =
                variablesOf(store, javaName);

        if ("activity".equalsIgnoreCase(target)) {
            plan.activityEvent = true;
            plan.targetId = plan.eventName;
            if (!Arrays.asList(oq.getAllActivityEvents())
                    .contains(plan.eventName)) {
                throw new AgentTools.ToolException("E_BAD_EVENT",
                        "\"" + plan.eventName + "\" is not an activity "
                                + "event. Activity events are: "
                                + String.join(", ", oq.getAllActivityEvents())
                                + ".");
            }
            plan.header = "When " + SketchwareProjects.activityName(activity)
                    + " runs " + plan.eventName + ":";
        } else {
            ViewBean widget = findWidget(byId, target);
            if (widget == null) {
                String wanted = target == null ? "" : target.trim();
                if (Arrays.asList(oq.getAllActivityEvents())
                        .contains(wanted)) {
                    throw new AgentTools.ToolException("E_BAD_TARGET",
                            "\"" + wanted + "\" is an activity event, "
                                    + "not a widget. For screen-level "
                                    + "behaviour, call again with target "
                                    + "\"activity\" and event \"" + wanted
                                    + "\".");
                }
                throw new AgentTools.ToolException("E_NO_WIDGET",
                        "There is no widget with id \"" + target
                                + "\" on this screen. Use get_screen to "
                                + "see the widget ids.");
            }
            plan.targetId = widget.id;
            String[] events = oq.getEventsForClass(new ViewBean(
                    widget.id, widget.type).buildClassInfo(widget.type));
            if (events == null || !Arrays.asList(events)
                    .contains(plan.eventName)) {
                throw new AgentTools.ToolException("E_BAD_EVENT",
                        widget.id + " (" + AgentEdits.typeName(widget.type)
                                + ") has no \"" + plan.eventName
                                + "\" event. Its events are: "
                                + (events == null ? "none"
                                        : String.join(", ", events))
                                + ".");
            }
            String widgetText = widget.text == null
                    ? "" : widget.text.text;
            String label = widgetText != null && !widgetText.isEmpty()
                    ? " (“" + widgetText + "”)" : "";
            plan.header = "When " + widget.id + label + " "
                    + plan.eventName + ":";
        }

        // The event this handler appends to, and what it already does.
        plan.eventExists = eventRegistered(store, javaName, plan);
        ArrayList<BlockBean> existing = readBlocks(store, javaName, plan);
        plan.existingCount = existing.size();
        int nextId = nextFreeId(existing);

        for (JsonElement element : blocks) {
            if (!element.isJsonObject()) {
                throw new AgentTools.ToolException("E_BAD_BLOCK",
                        "Every block must be an object like "
                                + "{\"block\":\"toast\",\"message\":\"Hi\"}.");
            }
            Stmt stmt = translate(context, element.getAsJsonObject(), byId,
                    variables);
            plan.statements.add(stmt);
            BlockBean bean = new BlockBean(String.valueOf(nextId),
                    specFor(context, stmt.opCode), stmt.type, "",
                    stmt.opCode);
            try {
                bean.color = kq.a(stmt.opCode, stmt.type);
            } catch (Exception ignored) {
                // Colour is display-only; the palette default stands.
            }
            bean.parameters.addAll(stmt.params);
            plan.fresh.add(bean);
            plan.chain.add(bean);
            plan.lines.add(stmt.sentence);
            nextId++;
        }
        // Resolve variable-value reporters: each "@var:" placeholder
        // in a statement's parameters becomes an "@id" reference to a
        // getVar bean (spec = the variable name, the shape the editor
        // stores) appended after the statement beans, unchained.
        for (BlockBean bean : plan.chain) {
            for (int i = 0; i < bean.parameters.size(); i++) {
                String param = bean.parameters.get(i);
                if (!param.startsWith("@var:")) {
                    continue;
                }
                String name = param.substring("@var:".length());
                BlockBean reporter = new BlockBean(
                        String.valueOf(nextId), name,
                        variableTypeLetter(variables.get(name)), "",
                        "getVar");
                try {
                    reporter.color = kq.a("getVar", reporter.type);
                } catch (Exception ignored) {
                    // Colour is display-only.
                }
                plan.extras.add(reporter);
                plan.fresh.add(reporter);
                bean.parameters.set(i, "@" + nextId);
                nextId++;
            }
        }
        // Chain the fresh beans among themselves; the applier joins
        // them to the existing chain's tail.
        for (int i = 0; i + 1 < plan.chain.size(); i++) {
            plan.chain.get(i).nextBlock =
                    Integer.parseInt(plan.chain.get(i + 1).id);
        }

        // Prove with the app's own code generator that the combined
        // event still generates, and that each new statement lands.
        // The existing beans are the store's live objects, so the
        // dry run joins clones — a proposal must not touch anything.
        ArrayList<BlockBean> dryExisting = new ArrayList<>();
        for (BlockBean bean : existing) {
            dryExisting.add(bean.clone());
        }
        String combined = generate(context, scId, activity,
                joined(dryExisting, plan.fresh));
        if (combined != null) {
            for (Stmt stmt : plan.statements) {
                if (!combined.contains(stmt.expectCode)) {
                    throw new AgentTools.ToolException("E_GENERATE",
                            "The new blocks did not survive Sketchware's "
                                    + "code generator (\"" + stmt.sentence
                                    + "\"). Nothing was changed.");
                }
            }
        }
        return plan;
    }

    /**
     * Applies an approved plan: registers the event when missing,
     * appends the new chain, saves through the data manager, commits,
     * and verifies by reading the event back. Any failure restores
     * the blocks as they were.
     */
    public static JsonObject apply(Context context, Plan plan)
            throws AgentTools.ToolException {
        String javaName = plan.activity.getJavaName();
        eC store = jC.a(plan.scId);
        String key = plan.targetId + "_" + plan.eventName;

        ArrayList<BlockBean> current = readBlocks(store, javaName, plan);
        if (current.size() != plan.existingCount) {
            throw new AgentTools.ToolException("E_LOGIC_CHANGED",
                    "This event's blocks changed since the handler was "
                            + "proposed, so it was not applied. Look at "
                            + "the screen again and propose a fresh one.");
        }
        ArrayList<BlockBean> previous = new ArrayList<>();
        for (BlockBean bean : current) {
            previous.add(bean.clone());
        }
        boolean registeredHere = false;
        try {
            SketchwareActions.backupProjectOnce(plan.scId);
            if (!plan.eventExists
                    && !eventRegistered(store, javaName, plan)) {
                if (plan.activityEvent) {
                    store.a(javaName, EventBean.EVENT_TYPE_ACTIVITY, 0,
                            plan.targetId, plan.eventName);
                } else {
                    store.a(javaName, EventBean.EVENT_TYPE_VIEW,
                            targetTypeOf(plan), plan.targetId,
                            plan.eventName);
                }
                registeredHere = true;
            }
            ArrayList<BlockBean> combined = joined(current, plan.fresh);
            store.a(javaName, key, combined);
            SketchwareActions.commitProject(plan.scId);

            ArrayList<BlockBean> readBack =
                    store.a(javaName, key);
            String problem = verify(readBack, combined, plan);
            if (problem != null) {
                throw new IllegalStateException(problem);
            }
            JsonObject result = new JsonObject();
            result.addProperty("applied", true);
            result.addProperty("blocks_added", plan.chain.size());
            result.addProperty("blocks_total", readBack.size());
            result.addProperty("verified",
                    plan.header + " now runs "
                            + readBack.size() + " block"
                            + (readBack.size() == 1 ? "" : "s")
                            + " (read back and checked).");
            return result;
        } catch (Exception e) {
            // Restore the blocks as they were; an event registered by
            // this call may stay behind, empty — the editor simply
            // shows an unwired event, and the user can delete it.
            try {
                store.a(javaName, key, previous);
                SketchwareActions.commitProject(plan.scId);
            } catch (Exception ignored) {
                // The restore is best-effort; the error below is what
                // the user needs to see.
            }
            if (e instanceof AgentTools.ToolException toolException) {
                throw toolException;
            }
            throw new AgentTools.ToolException("E_APPLY_FAILED",
                    "The handler could not be applied (" + e.getMessage()
                            + "); the event's blocks were restored as "
                            + "they were."
                            + (registeredHere
                                    ? " An empty " + plan.eventName
                                            + " event may remain."
                                    : ""));
        }
    }

    // -- translation --------------------------------------------------------

    private static Stmt translate(Context context, JsonObject op,
                                  Map<String, ViewBean> byId,
                                  Map<String, Integer> variables)
            throws AgentTools.ToolException {
        String kind = stringOf(op, "block").toLowerCase();
        Stmt stmt = new Stmt();
        switch (kind) {
            case "toast" -> {
                String fromVariable = stringOf(op, "from_variable");
                stmt.opCode = "doToast";
                stmt.type = " ";
                if (!fromVariable.isEmpty()) {
                    requireVariable(variables, fromVariable);
                    requireVariableKind(variables, fromVariable, 2,
                            "A message can only show a string variable; \""
                                    + fromVariable + "\" is a "
                                    + SketchwareProjects.variableTypeName(
                                            variables.get(fromVariable))
                                    + " variable.");
                    stmt.params.add("@var:" + fromVariable);
                    stmt.sentence = "Show the value of variable “"
                            + fromVariable + "” as a message";
                    stmt.expectCode =
                            "showMessage(getApplicationContext(), "
                                    + fromVariable;
                    break;
                }
                String message = stringOf(op, "message");
                if (message.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "A toast block needs a \"message\".");
                }
                if (message.length() > 200) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Toast messages are capped at 200 characters.");
                }
                stmt.params.add(message);
                stmt.sentence = "Show message “" + message + "”";
                stmt.expectCode = "SketchwareUtil.showMessage";
            }
            case "set_text" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!TEXT_TYPES.contains(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ", which has no text to set.");
                }
                String fromVariable = stringOf(op, "from_variable");
                stmt.opCode = "setText";
                stmt.type = " ";
                stmt.params.add(widget.id);
                if (!fromVariable.isEmpty()) {
                    requireVariable(variables, fromVariable);
                    requireVariableKind(variables, fromVariable, 2,
                            "Text can only come from a string variable; \""
                                    + fromVariable + "\" is a "
                                    + SketchwareProjects.variableTypeName(
                                            variables.get(fromVariable))
                                    + " variable.");
                    stmt.params.add("@var:" + fromVariable);
                    stmt.sentence = "Set " + widget.id
                            + " text to the value of variable “"
                            + fromVariable + "”";
                    stmt.expectCode = widget.id + ".setText(" + fromVariable;
                    break;
                }
                String text = stringOf(op, "text");
                stmt.params.add(text);
                stmt.sentence = "Set " + widget.id + " text to “"
                        + text + "”";
                stmt.expectCode = widget.id + ".setText(";
            }
            case "set_visibility" -> {
                ViewBean widget = requireWidget(op, byId);
                String state = stringOf(op, "state");
                if (state.isEmpty() && op.has("visible")) {
                    state = boolOf(op, "visible", true)
                            ? "VISIBLE" : "GONE";
                }
                state = state.toUpperCase();
                if (!state.equals("VISIBLE") && !state.equals("GONE")
                        && !state.equals("INVISIBLE")) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_visibility needs \"visible\": true/false "
                                    + "or a state of visible, gone or "
                                    + "invisible.");
                }
                stmt.opCode = "setVisible";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(state);
                stmt.sentence = (state.equals("VISIBLE") ? "Show "
                        : state.equals("GONE") ? "Hide " : "Make ")
                        + widget.id
                        + (state.equals("INVISIBLE")
                                ? " invisible (keeps its space)" : "");
                stmt.expectCode = widget.id + ".setVisibility(View.";
            }
            case "set_enabled" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!op.has("enabled") || !op.get("enabled").isJsonPrimitive()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_enabled needs \"enabled\": true or false.");
                }
                boolean enabled = boolOf(op, "enabled", true);
                stmt.opCode = "setEnable";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(String.valueOf(enabled));
                stmt.sentence = (enabled ? "Enable " : "Disable ")
                        + widget.id;
                stmt.expectCode = widget.id + ".setEnabled(";
            }
            case "set_text_color" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!TEXT_TYPES.contains(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ", which has no text colour to set.");
                }
                String colour = colourParam(op);
                stmt.opCode = "setTextColor";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(colour);
                stmt.sentence = "Set " + widget.id + " text colour to "
                        + colour;
                stmt.expectCode = widget.id + ".setTextColor(";
            }
            case "set_image_url" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"ImageView".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Only an ImageView loads an image from a "
                                    + "URL; " + widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ".");
                }
                String url = stringOf(op, "url");
                if (url.isEmpty()) {
                    url = stringOf(op, "text");
                }
                if (url.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_image_url needs \"url\".");
                }
                stmt.opCode = "setImageUrl";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(url);
                stmt.sentence = "Load the image at " + url + " into "
                        + widget.id;
                // Fx emits the app's image loader call; the dry run
                // itself proves the shape, this just anchors it.
                stmt.expectCode = "Glide.with(getApplicationContext())"
                        + ".load(Uri.parse(";
            }
            case "set_hint" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"EditText".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Only an EditText has a hint to set; "
                                    + widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ".");
                }
                String hint = stringOf(op, "text");
                if (hint.isEmpty() && op.has("message")) {
                    hint = stringOf(op, "message");
                }
                stmt.opCode = "setHint";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(hint);
                stmt.sentence = "Set " + widget.id + " hint to \""
                        + hint + "\"";
                stmt.expectCode = widget.id + ".setHint(";
            }
            case "set_clickable" -> {
                ViewBean widget = requireWidget(op, byId);
                boolean clickable = boolOf(op, "enabled", true);
                if (op.has("clickable")) {
                    clickable = boolOf(op, "clickable", true);
                }
                stmt.opCode = "setClickable";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(String.valueOf(clickable));
                stmt.sentence = (clickable ? "Make " : "Make un-clickable ")
                        + widget.id + (clickable ? " clickable" : "");
                stmt.expectCode = widget.id + ".setClickable(";
            }
            case "copy_to_clipboard" -> {
                String text = stringOf(op, "text");
                if (text.isEmpty()) {
                    text = stringOf(op, "message");
                }
                if (text.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "copy_to_clipboard needs \"text\" to copy.");
                }
                stmt.opCode = "copyToClipboard";
                stmt.type = " ";
                stmt.params.add(text);
                stmt.sentence = "Copy \"" + text
                        + "\" to the clipboard";
                stmt.expectCode = "setPrimaryClip(ClipData.newPlainText(";
            }
            case "set_screen_title" -> {
                String text = stringOf(op, "text");
                if (text.isEmpty()) {
                    text = stringOf(op, "message");
                }
                if (text.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_screen_title needs \"text\".");
                }
                stmt.opCode = "setTitle";
                stmt.type = " ";
                stmt.params.add(text);
                stmt.sentence = "Set the screen title to \"" + text
                        + "\"";
                stmt.expectCode = "setTitle(";
            }
            case "open_drawer", "close_drawer" -> {
                boolean open = "open_drawer".equals(kind);
                stmt.opCode = open ? "openDrawer" : "closeDrawer";
                stmt.type = " ";
                stmt.sentence = open ? "Open the navigation drawer"
                        : "Close the navigation drawer";
                stmt.expectCode = open ? "openDrawer(GravityCompat.START)"
                        : "closeDrawer(GravityCompat.START)";
            }
            case "load_url" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"WebView".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Only a WebView loads a URL; " + widget.id
                                    + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ".");
                }
                String url = stringOf(op, "url");
                if (url.isEmpty()) {
                    url = stringOf(op, "text");
                }
                if (url.isEmpty()) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "load_url needs \"url\".");
                }
                stmt.opCode = "webViewLoadUrl";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(url);
                stmt.sentence = "Load " + url + " in " + widget.id;
                stmt.expectCode = widget.id + ".loadUrl(";
            }
            case "set_progress" -> {
                ViewBean widget = requireWidget(op, byId);
                if (!"SeekBar".equals(AgentEdits.typeName(widget.type))) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "Only a SeekBar has progress to set; "
                                    + widget.id + " is a "
                                    + AgentEdits.typeName(widget.type)
                                    + ".");
                }
                String value = stringOf(op, "value");
                try {
                    Double.parseDouble(value);
                } catch (Exception e) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "set_progress needs a numeric \"value\".");
                }
                stmt.opCode = "seekBarSetProgress";
                stmt.type = " ";
                stmt.params.add(widget.id);
                stmt.params.add(value);
                stmt.sentence = "Set " + widget.id + " progress to "
                        + value;
                // Fx emits "setProgress((int)%s)" — literal numbers
                // come straight through, so just the call is anchored.
                stmt.expectCode = widget.id + ".setProgress(";
            }
            case "finish" -> {
                stmt.opCode = "finishActivity";
                stmt.type = "f";
                stmt.sentence = "Close this screen";
                stmt.expectCode = "finish();";
            }
            case "set_variable" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                int kind2 = variables.get(name);
                if (kind2 == 3) {
                    throw new AgentTools.ToolException("E_BAD_BLOCK",
                            "\"" + name + "\" is a map variable; map "
                                    + "variables can't be set this way "
                                    + "yet — only boolean, number and "
                                    + "string variables.");
                }
                stmt.type = " ";
                String copyFrom = stringOf(op, "from_variable");
                if (!copyFrom.isEmpty()) {
                    requireVariable(variables, copyFrom);
                    if (variables.get(copyFrom) != kind2) {
                        throw new AgentTools.ToolException("E_BAD_BLOCK",
                                "Types don't match: \"" + copyFrom
                                        + "\" is a "
                                        + SketchwareProjects
                                                .variableTypeName(
                                                        variables.get(
                                                                copyFrom))
                                        + " variable, but \"" + name
                                        + "\" is a "
                                        + SketchwareProjects
                                                .variableTypeName(kind2)
                                        + " variable.");
                    }
                    stmt.opCode = setVarOpCode(kind2);
                    stmt.params.add(name);
                    stmt.params.add("@var:" + copyFrom);
                    stmt.sentence = "Set variable “" + name
                            + "” to the value of “" + copyFrom + "”";
                    stmt.expectCode = name + " = ";
                    break;
                }
                stmt.opCode = setVarOpCode(kind2);
                stmt.params.add(name);
                if (kind2 == 1) {
                    String value = stringOf(op, "value");
                    try {
                        Double.parseDouble(value);
                    } catch (Exception e) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "Variable \"" + name + "\" is a number "
                                        + "variable, so \"value\" must be "
                                        + "a number (got \"" + value
                                        + "\").");
                    }
                    stmt.params.add(value);
                    stmt.sentence = "Set variable “" + name + "” to "
                            + value;
                } else if (kind2 == 0) {
                    boolean value = boolOf(op, "value", false);
                    stmt.params.add(String.valueOf(value));
                    stmt.sentence = "Set variable “" + name + "” to "
                            + (value ? "true" : "false");
                } else {
                    String value = stringOf(op, "value");
                    if (value.length() > 1000) {
                        throw new AgentTools.ToolException(
                                "E_BAD_BLOCK",
                                "Variable text is capped at 1000 "
                                        + "characters.");
                    }
                    stmt.params.add(value);
                    stmt.sentence = "Set variable “" + name + "” to “"
                            + value + "”";
                }
                stmt.expectCode = name + " = ";
            }
            case "increase", "decrease" -> {
                String name = stringOf(op, "variable");
                requireVariable(variables, name);
                requireVariableKind(variables, name, 1,
                        "Increase/decrease only work on number variables; "
                                + "\"" + name + "\" is a "
                                + SketchwareProjects.variableTypeName(
                                        variables.get(name)) + " variable.");
                boolean up = "increase".equals(kind);
                stmt.opCode = up ? "increaseInt" : "decreaseInt";
                stmt.type = " ";
                stmt.params.add(name);
                stmt.sentence = (up ? "Add 1 to" : "Subtract 1 from")
                        + " variable “" + name + "”";
                stmt.expectCode = name + (up ? "++;" : "--;");
            }
            default -> throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "Unknown block \"" + kind + "\". Blocks are: toast, "
                            + "set_text, set_visibility, set_enabled, "
                            + "finish, set_variable, increase, decrease.");
        }
        // The app's own slot table is the shape authority: what the
        // editor would store for this block has exactly these slots.
        ArrayList<String> slots;
        try {
            slots = lq.a(stmt.opCode);
        } catch (Exception e) {
            slots = null;
        }
        if (slots == null || slots.size() != stmt.params.size()) {
            throw new AgentTools.ToolException("E_INTERNAL",
                    "Sketchware's block table disagrees about \""
                            + stmt.opCode + "\"; refusing to guess its "
                            + "shape.");
        }
        return stmt;
    }

    // -- stored-shape helpers ----------------------------------------------

    /**
     * The variables one activity declares: name to the editor's type
     * code (0 boolean, 1 number, 2 string, 3 map), read through the
     * same accessor ({@code eC.k}) the logic editor and the code
     * generator use. Other stored entries (raw fields, imports) are
     * not variables and stay invisible here.
     */
    static LinkedHashMap<String, Integer> variablesOf(eC store,
                                                      String javaName) {
        LinkedHashMap<String, Integer> variables = new LinkedHashMap<>();
        try {
            ArrayList<android.util.Pair<Integer, String>> stored =
                    store.k(javaName);
            if (stored != null) {
                for (android.util.Pair<Integer, String> pair : stored) {
                    if (pair.first >= 0 && pair.first <= 3
                            && pair.second != null) {
                        variables.put(pair.second, pair.first);
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable logic reads as "no variables declared".
        }
        return variables;
    }

    /** The statement opCode that assigns to a variable of this kind. */
    private static String setVarOpCode(int kind) {
        return switch (kind) {
            case 0 -> "setVarBoolean";
            case 2 -> "setVarString";
            default -> "setVarInt";
        };
    }

    /** The palette type letter for a variable's value block. */
    private static String variableTypeLetter(Integer kind) {
        if (kind == null) {
            return "s";
        }
        return switch (kind) {
            case 0 -> "b";
            case 1 -> "d";
            case 3 -> "a";
            default -> "s";
        };
    }

    /** Fails unless the activity declares a variable with this name. */
    private static void requireVariable(Map<String, Integer> variables,
                                        String name)
            throws AgentTools.ToolException {
        if (name == null || name.isEmpty()
                || !variables.containsKey(name)) {
            StringBuilder declared = new StringBuilder();
            for (Map.Entry<String, Integer> entry : variables.entrySet()) {
                if (declared.length() > 0) {
                    declared.append(", ");
                }
                declared.append(entry.getKey()).append(" (")
                        .append(SketchwareProjects.variableTypeName(
                                entry.getValue())).append(")");
            }
            throw new AgentTools.ToolException("E_NO_VARIABLE",
                    "This screen has no variable named \"" + name
                            + "\"."
                            + (variables.isEmpty()
                                    ? " It declares no variables yet — "
                                            + "declare one with "
                                            + "create_variable first."
                                    : " Its variables are: "
                                            + declared + "."));
        }
    }

    /** Fails unless the named variable is of the wanted editor kind. */
    private static void requireVariableKind(
            Map<String, Integer> variables, String name, int kind,
            String message) throws AgentTools.ToolException {
        Integer actual = variables.get(name);
        if (actual == null || actual != kind) {
            throw new AgentTools.ToolException("E_BAD_BLOCK", message);
        }
    }

    /** The event's blocks, or an empty list when it has none. */
    private static ArrayList<BlockBean> readBlocks(eC store,
                                                   String javaName,
                                                   Plan plan) {
        ArrayList<BlockBean> blocks = store.a(javaName,
                plan.targetId + "_" + plan.eventName);
        return blocks == null ? new ArrayList<>() : blocks;
    }

    private static boolean eventRegistered(eC store, String javaName,
                                           Plan plan) {
        ArrayList<EventBean> events = store.g(javaName);
        if (events == null) {
            return false;
        }
        for (EventBean event : events) {
            if (plan.activityEvent) {
                if (event.eventType == EventBean.EVENT_TYPE_ACTIVITY
                        && plan.eventName.equals(event.eventName)) {
                    return true;
                }
            } else if (event.eventType == EventBean.EVENT_TYPE_VIEW
                    && plan.targetId.equals(event.targetId)
                    && plan.eventName.equals(event.eventName)) {
                return true;
            }
        }
        return false;
    }

    /** The widget's numeric type, for registering a view event. */
    private static int targetTypeOf(Plan plan) {
        ArrayList<ViewBean> beans =
                SketchwareProjects.screenBeans(plan.scId, plan.activity);
        for (ViewBean bean : beans) {
            if (plan.targetId.equals(bean.id)) {
                return bean.type;
            }
        }
        return 0;
    }

    /** Existing blocks plus the fresh chain joined to the tail. */
    private static ArrayList<BlockBean> joined(
            ArrayList<BlockBean> existing, ArrayList<BlockBean> fresh) {
        ArrayList<BlockBean> combined = new ArrayList<>(existing);
        if (!fresh.isEmpty() && !combined.isEmpty()) {
            Map<Integer, BlockBean> byId = new HashMap<>();
            for (BlockBean bean : combined) {
                byId.put(parseId(bean.id), bean);
            }
            // Walk the main chain from the head; its tail is where
            // new statements belong.
            BlockBean cursor = combined.get(0);
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            while (cursor != null && seen.add(parseId(cursor.id))) {
                int next = cursor.nextBlock;
                if (next < 0 || !byId.containsKey(next)) {
                    break;
                }
                cursor = byId.get(next);
            }
            if (cursor != null) {
                cursor.nextBlock = Integer.parseInt(fresh.get(0).id);
            }
        }
        combined.addAll(fresh);
        return combined;
    }

    /** Structural read-back check against what was meant to be stored. */
    private static String verify(ArrayList<BlockBean> readBack,
                                 ArrayList<BlockBean> combined,
                                 Plan plan) {
        if (readBack == null || readBack.size() != combined.size()) {
            return "the event's blocks did not read back";
        }
        Map<Integer, BlockBean> byId = new HashMap<>();
        for (BlockBean bean : readBack) {
            byId.put(parseId(bean.id), bean);
        }
        int expectedFirst = Integer.parseInt(plan.chain.get(0).id);
        // The chain from the head must pass through every fresh bean,
        // in proposal order, and end after the last one.
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        BlockBean cursor = readBack.get(0);
        List<Integer> chain = new ArrayList<>();
        while (cursor != null && seen.add(parseId(cursor.id))) {
            chain.add(parseId(cursor.id));
            cursor = cursor.nextBlock >= 0
                    ? byId.get(cursor.nextBlock) : null;
        }
        int at = chain.indexOf(expectedFirst);
        if (at < 0) {
            return "the new blocks are not on the event's chain";
        }
        for (int i = 0; i < plan.chain.size(); i++) {
            if (at + i >= chain.size()
                    || chain.get(at + i)
                            != Integer.parseInt(plan.chain.get(i).id)) {
                return "the new blocks are out of order";
            }
        }
        for (BlockBean extra : plan.extras) {
            BlockBean stored = byId.get(parseId(extra.id));
            if (stored == null
                    || !"getVar".equals(stored.opCode)
                    || !extra.spec.equals(stored.spec)) {
                return "a variable value (\"" + extra.spec
                        + "\") did not read back";
            }
        }
        return null;
    }

    /**
     * Runs Sketchware's own code generator over a candidate event, the
     * same preview the logic editor makes. Null when generation is not
     * possible (e.g. older blocks it cannot walk) — callers then fall
     * back to the structural checks.
     */
    private static String generate(Context context, String scId,
                                   ProjectFileBean activity,
                                   ArrayList<BlockBean> blocks) {
        if (blocks.isEmpty()) {
            return "";
        }
        try {
            jq config = new jq();
            config.sc_id = scId;
            return new Fx(activity.getActivityName(), config, blocks,
                    false).a();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The editor's canonical spec text for a block, or a synthesis. */
    private static String specFor(Context context, String opCode) {
        try {
            String spec = xB.b().a(context, opCode);
            if (spec != null && !spec.isEmpty()) {
                return spec;
            }
        } catch (Throwable ignored) {
            // Fall through to the synthesis below.
        }
        // Slots from the app's table with the block's own words between
        // them; the generator only reads the slot tokens, and the
        // editor re-derives the display from the same tokens.
        ArrayList<String> slots = lq.a(opCode);
        String label = "";
        try {
            label = lq.d(opCode).replace('_', ' ');
        } catch (Exception ignored) {
            // The opCode itself is the label.
        }
        if (label.isEmpty()) {
            label = opCode;
        }
        if (slots == null || slots.isEmpty()) {
            return label;
        }
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                spec.append(' ');
            }
            spec.append(slots.get(i));
            if (i == 0 && slots.size() > 1) {
                spec.append(' ').append(label);
            }
        }
        return spec.toString();
    }

    private static int nextFreeId(ArrayList<BlockBean> existing) {
        int max = 0;
        for (BlockBean bean : existing) {
            max = Math.max(max, parseId(bean.id));
        }
        return max + 1;
    }

    private static int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (Exception e) {
            return -1;
        }
    }

    private static ViewBean findWidget(Map<String, ViewBean> byId,
                                       String target) {
        if (target == null) {
            return null;
        }
        ViewBean exact = byId.get(target.trim());
        if (exact != null) {
            return exact;
        }
        for (ViewBean bean : byId.values()) {
            if (bean.id.equalsIgnoreCase(target.trim())) {
                return bean;
            }
        }
        return null;
    }

    private static ViewBean requireWidget(JsonObject op,
                                          Map<String, ViewBean> byId)
            throws AgentTools.ToolException {
        String target = stringOf(op, "target");
        ViewBean widget = findWidget(byId, target);
        if (widget == null) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "A block targets widget \"" + target
                            + "\", which is not on this screen. Use "
                            + "get_screen to see the widget ids.");
        }
        return widget;
    }

    /** Colour literals in the editor's stored form ("0xAARRGGBB"). */
    private static String colourParam(JsonObject op)
            throws AgentTools.ToolException {
        String colour = stringOf(op, "color");
        if (colour.isEmpty()) colour = stringOf(op, "colour");
        if (colour.isEmpty()
                || !colour.matches("(?i)#([0-9a-f]{6}|[0-9a-f]{8})")) {
            throw new AgentTools.ToolException("E_BAD_BLOCK",
                    "A colour block needs \"color\" as #RRGGBB or "
                            + "#AARRGGBB (opaque when the alpha part is "
                            + "left out).");
        }
        String hex = colour.substring(1).toUpperCase(Locale.ROOT);
        if (hex.length() == 6) hex = "FF" + hex;
        return "0x" + hex;
    }

    private static String stringOf(JsonObject object, String key) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean boolOf(JsonObject object, String key,
                                  boolean fallback) {
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    // -- block editing -----------------------------------------------------

    /** Editable value kinds the replace path understands. */
    public static final String EDIT_TEXT = "text";
    public static final String EDIT_MESSAGE = "message";
    public static final String EDIT_VALUE = "value";
    public static final String EDIT_NUMBER = "number";

    /** One editable value on one stored block. */
    public static final class EditableValue {
        public int beanIndex;
        public String kind;
        public String currentValue;
        String newValue;
        int paramIdx;
    }

    /** A validated edit (replace or remove) against one event. */
    public static final class EditPlan {
        public String scId;
        public ProjectFileBean activity;
        public String targetId;
        public String eventName;
        public boolean activityEvent;
        public String header;
        public boolean remove;
        public String blockOpCode;
        public int[] targetPath;
        public int targetBeanIndex;
        public final ArrayList<EditableValue> values = new ArrayList<>();
        public int existingCount;
        public String blockDescription;
        public String eventRendering;
    }

    /**
     * Read-only view of one event: its numbered statement rendering,
     * with no edit proposed. Backs {@code get_event_logic}.
     */
    public static EditPlan describeEvent(String scId,
                                         ProjectFileBean activity,
                                         String target, String event)
            throws AgentTools.ToolException {
        if (activity.fileType != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            throw new AgentTools.ToolException("E_NOT_ACTIVITY",
                    "Custom views have no logic to read. Pick an "
                            + "activity screen instead.");
        }
        String javaName = activity.getJavaName();
        eC store = jC.a(scId);
        Plan probe = probeEvent(scId, activity, target, event);
        EditPlan plan = new EditPlan();
        plan.scId = scId;
        plan.activity = activity;
        plan.targetId = probe.targetId;
        plan.eventName = probe.eventName;
        plan.activityEvent = probe.activityEvent;
        plan.header = probe.header;
        ArrayList<BlockBean> blocks = store.a(javaName,
                plan.targetId + "_" + plan.eventName);
        if (blocks == null) blocks = new ArrayList<>();
        plan.existingCount = blocks.size();
        plan.eventRendering = renderEvent(blocks, chainIndices(blocks));
        return plan;
    }

    /**
     * Validates a replace or remove against one event's stored blocks.
     * Pure: reads, changes nothing. The target block is identified by
     * its statement path — the 1-based positions the proposal
     * rendering showed ([2], or [1,2] inside a container); a block
     * count drift or a failed generation proof refuses the edit.
     */
    public static EditPlan planEdit(Context context, String scId,
                                    ProjectFileBean activity,
                                    String target, String event,
                                    JsonObject edit)
            throws AgentTools.ToolException {
        if (activity.fileType != ProjectFileBean.PROJECT_FILE_TYPE_ACTIVITY) {
            throw new AgentTools.ToolException("E_NOT_ACTIVITY",
                    "Custom views have no logic to edit. Pick an activity "
                            + "screen instead.");
        }
        String javaName = activity.getJavaName();
        eC store = jC.a(scId);
        Plan probe = probeEvent(scId, activity, target, event);
        EditPlan plan = new EditPlan();
        plan.scId = scId;
        plan.activity = activity;
        plan.targetId = probe.targetId;
        plan.eventName = probe.eventName;
        plan.activityEvent = probe.activityEvent;
        plan.header = probe.header;

        plan.remove = false;
        try {
            plan.remove = edit != null && edit.has("remove")
                    && !edit.get("remove").isJsonNull()
                    && edit.get("remove").getAsBoolean();
        } catch (Exception ignored) {
            // A malformed remove flag reads as "not removing".
        }
        plan.targetPath = intArrayOf(edit, "block_path");
        if (plan.targetPath.length == 0) {
            throw new AgentTools.ToolException("E_BAD_EDIT",
                    "An edit needs \"block_path\": the 1-based position "
                            + "of a statement in the event, e.g. [2], or "
                            + "[1,2] for a statement nested in a "
                            + "container. Read the event with "
                            + "get_event_logic first.");
        }
        ArrayList<BlockBean> blocks = store.a(javaName,
                plan.targetId + "_" + plan.eventName);
        if (blocks == null) blocks = new ArrayList<>();
        plan.existingCount = blocks.size();
        ArrayList<Integer> chain = chainIndices(blocks);
        plan.eventRendering = renderEvent(blocks, chain);
        plan.targetBeanIndex = resolvePath(blocks, chain, plan.targetPath);
        if (plan.targetBeanIndex < 0) {
            throw new AgentTools.ToolException("E_NO_BLOCK",
                    "This event has no statement at position "
                            + pathText(plan.targetPath) + ". Its blocks "
                            + "are:\n" + plan.eventRendering + "\n"
                            + "Nothing was changed.");
        }
        BlockBean block = blocks.get(plan.targetBeanIndex);
        plan.blockOpCode = block.opCode;
        plan.blockDescription = describeBlock(block);

        if (plan.remove) {
            if (plan.targetBeanIndex == 0 && !blocks.isEmpty()) {
                // Fx generates from stored index 0; removing the head
                // bean would orphan every other statement.
                throw new AgentTools.ToolException("E_HEAD_BLOCK",
                        "This block is the event's first statement; "
                                + "removing it outright would break the "
                                + "stored chain Sketchware generates "
                                + "from. It can be changed in place "
                                + "instead. Nothing was changed.");
            }
            return plan;
        }

        JsonElement replacement = edit.get("replace_with");
        if (replacement == null || !replacement.isJsonObject()) {
            throw new AgentTools.ToolException("E_BAD_EDIT",
                    "To change a value, give \"replace_with\" with the "
                            + "new text, message, value or number, e.g. "
                            + "{\"text\":\"Saved\"}.");
        }
        JsonObject with = replacement.getAsJsonObject();
        ArrayList<EditableValue> editable = describeEditableValues(block);
        if (editable.isEmpty()) {
            throw new AgentTools.ToolException("E_NOT_EDITABLE",
                    "The block at " + pathText(plan.targetPath) + " ("
                            + plan.blockOpCode + ") has no value the "
                            + "agent can safely rewrite yet — only a "
                            + "statement's text, message, value or "
                            + "number can be changed in place. Remove "
                            + "it and add the replacement instead.");
        }
        for (EditableValue value : editable) {
            if (with.has(value.kind)
                    && !with.get(value.kind).isJsonNull()) {
                value.newValue = with.get(value.kind).getAsString();
                value.beanIndex = plan.targetBeanIndex;
                plan.values.add(value);
            }
        }
        if (plan.values.isEmpty()) {
            StringBuilder kinds = new StringBuilder();
            for (EditableValue value : editable) {
                if (kinds.length() > 0) kinds.append(", ");
                kinds.append(value.kind);
            }
            throw new AgentTools.ToolException("E_BAD_EDIT",
                    "The block at " + pathText(plan.targetPath)
                            + " can change its " + kinds
                            + ". Give \"replace_with\" with one of "
                            + "those keys and the new value.");
        }
        // Prove the after-state still generates: clone, rewrite, run
        // the app's own generator, require the fresh code to differ.
        ArrayList<BlockBean> clones = new ArrayList<>();
        for (BlockBean bean : blocks) clones.add(bean.clone());
        for (EditableValue value : plan.values) {
            clones.get(value.beanIndex).parameters.set(value.paramIdx,
                    value.newValue);
        }
        String before = generate(context, scId, activity, blocks);
        String after = generate(context, scId, activity, clones);
        if (before != null && after != null && before.equals(after)) {
            throw new AgentTools.ToolException("E_GENERATE",
                    "Sketchware's code generator produced the same "
                            + "code after the change — the new value "
                            + "would have no effect. Nothing was "
                            + "changed.");
        }
        return plan;
    }

    /**
     * Applies an approved edit: backs up, rewrites or removes the
     * statement in a fresh list, saves through the data manager,
     * commits, and verifies by reading the event back. Any failure
     * restores the event's blocks as they were.
     */
    public static JsonObject applyEdit(Context context, EditPlan plan)
            throws AgentTools.ToolException {
        String javaName = plan.activity.getJavaName();
        eC store = jC.a(plan.scId);
        String key = plan.targetId + "_" + plan.eventName;
        ArrayList<BlockBean> current = store.a(javaName, key);
        if (current == null) current = new ArrayList<>();
        if (current.size() != plan.existingCount
                || resolvePath(current, chainIndices(current),
                        plan.targetPath) != plan.targetBeanIndex) {
            throw new AgentTools.ToolException("E_LOGIC_CHANGED",
                    "This event's blocks changed since the edit was "
                            + "proposed, so it was not applied. Look at "
                            + "the event again and propose a fresh edit.");
        }
        ArrayList<BlockBean> previous = new ArrayList<>();
        for (BlockBean bean : current) previous.add(bean.clone());
        BlockBean target = current.get(plan.targetBeanIndex);
        String targetId = target.id;
        try {
            SketchwareActions.backupProjectOnce(plan.scId);
            ArrayList<BlockBean> combined = new ArrayList<>();
            for (BlockBean bean : current) combined.add(bean.clone());
            if (plan.remove) {
                BlockBean removed = combined.get(plan.targetBeanIndex);
                int removedNumId = parseId(removed.id);
                int removedNext = removed.nextBlock;
                for (BlockBean bean : combined) {
                    if (bean.nextBlock == removedNumId) {
                        bean.nextBlock = removedNext;
                    }
                    if (bean.subStack1 == removedNumId) {
                        bean.subStack1 = removedNext;
                    }
                    if (bean.subStack2 == removedNumId) {
                        bean.subStack2 = removedNext;
                    }
                }
                final String removedId = removed.id;
                combined.removeIf(bean -> removedId.equals(bean.id));
            } else {
                for (EditableValue value : plan.values) {
                    combined.get(value.beanIndex).parameters.set(
                            value.paramIdx, value.newValue);
                }
            }
            store.a(javaName, key, combined);
            SketchwareActions.commitProject(plan.scId);

            ArrayList<BlockBean> readBack = store.a(javaName, key);
            if (readBack == null) readBack = new ArrayList<>();
            String problem = null;
            if (plan.remove) {
                for (BlockBean bean : readBack) {
                    if (targetId.equals(bean.id)) {
                        problem = "the removed block is still there";
                        break;
                    }
                }
            } else {
                BlockBean stored = findById(readBack, targetId);
                if (stored == null) {
                    problem = "the edited block did not read back";
                } else {
                    for (EditableValue value : plan.values) {
                        if (value.paramIdx >= stored.parameters.size()
                                || !value.newValue.equals(
                                        stored.parameters.get(
                                                value.paramIdx))) {
                            problem = "the new value did not read back";
                            break;
                        }
                    }
                }
            }
            if (problem != null) {
                throw new IllegalStateException(problem);
            }
            JsonObject result = new JsonObject();
            result.addProperty("applied", true);
            result.addProperty("removed", plan.remove);
            result.addProperty("verified",
                    (plan.remove ? "Removed " : "Changed ")
                            + plan.blockDescription + " — "
                            + plan.header + " (read back and checked).");
            return result;
        } catch (Exception e) {
            try {
                store.a(javaName, key, previous);
                SketchwareActions.commitProject(plan.scId);
            } catch (Exception ignored) {
                // The restore is best-effort.
            }
            if (e instanceof AgentTools.ToolException toolException) {
                throw toolException;
            }
            throw new AgentTools.ToolException("E_APPLY_FAILED",
                    "The edit could not be applied ("
                            + String.valueOf(e.getMessage())
                            + "); the event's blocks were restored as "
                            + "they were.");
        }
    }

    /** Event resolution shared by wiring and edits (target + event). */
    private static Plan probeEvent(String scId, ProjectFileBean activity,
                                   String target, String event)
            throws AgentTools.ToolException {
        Plan plan = new Plan();
        plan.scId = scId;
        plan.activity = activity;
        plan.eventName = event == null ? "" : event.trim();
        if ("activity".equalsIgnoreCase(target)) {
            plan.activityEvent = true;
            plan.targetId = plan.eventName;
            if (!Arrays.asList(oq.getAllActivityEvents())
                    .contains(plan.eventName)) {
                throw new AgentTools.ToolException("E_BAD_EVENT",
                        "\"" + plan.eventName + "\" is not an "
                                + "activity event. Activity events are: "
                                + String.join(", ",
                                        oq.getAllActivityEvents()) + ".");
            }
            plan.header = "When " + SketchwareProjects.activityName(activity)
                    + " runs " + plan.eventName + ":";
            return plan;
        }
        ArrayList<ViewBean> beans =
                SketchwareProjects.screenBeans(scId, activity);
        Map<String, ViewBean> byId = new LinkedHashMap<>();
        for (ViewBean bean : beans) byId.put(bean.id, bean);
        ViewBean widget = findWidget(byId, target);
        if (widget == null) {
            String wanted = target == null ? "" : target.trim();
            if (Arrays.asList(oq.getAllActivityEvents())
                    .contains(wanted)) {
                throw new AgentTools.ToolException("E_BAD_TARGET",
                        "\"" + wanted + "\" is an activity event, not "
                                + "a widget. Call again with target "
                                + "\"activity\" and event \"" + wanted
                                + "\".");
            }
            throw new AgentTools.ToolException("E_NO_WIDGET",
                    "There is no widget with id \"" + target
                            + "\" on this screen. Use get_screen to see "
                            + "the widget ids.");
        }
        plan.targetId = widget.id;
        String text = widget.text == null ? "" : widget.text.text;
        String label = text != null && !text.isEmpty()
                ? " (" + text + ")" : "";
        plan.header = "When " + widget.id + label + " "
                + plan.eventName + ":";
        return plan;
    }

    /** Main-chain bean indices (head first), following nextBlock. */
    public static ArrayList<Integer> chainIndices(
            ArrayList<BlockBean> blocks) {
        ArrayList<Integer> chain = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) return chain;
        Map<Integer, Integer> indexByNumericId = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            indexByNumericId.put(parseId(blocks.get(i).id), i);
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        Integer cursor = 0;
        while (cursor != null && cursor >= 0 && cursor < blocks.size()
                && seen.add(cursor)) {
            chain.add(cursor);
            Integer next = indexByNumericId.get(
                    blocks.get(cursor).nextBlock);
            cursor = next;
        }
        return chain;
    }

    /** index-lookup table for a stored block list. */
    private static Map<Integer, Integer> indexByNumericId(
            ArrayList<BlockBean> blocks) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            map.put(parseId(blocks.get(i).id), i);
        }
        return map;
    }

    /**
     * Walks a statement path to a stored bean index: the first number
     * picks a main-chain statement (1-based), further numbers descend
     * into substacks. -1 when the path doesn't land on a statement.
     */
    static int resolvePath(ArrayList<BlockBean> blocks,
                           ArrayList<Integer> chain, int[] path) {
        if (path.length == 0 || chain.isEmpty()) return -1;
        if (path[0] < 1 || path[0] > chain.size()) return -1;
        int beanIndex = chain.get(path[0] - 1);
        Map<Integer, Integer> byIdNum = indexByNumericId(blocks);
        for (int depth = 1; depth < path.length; depth++) {
            BlockBean parent = blocks.get(beanIndex);
            int headId = depth == 1 ? parent.subStack1
                    : parent.subStack2;
            Integer cursor = headId >= 0 ? byIdNum.get(headId) : null;
            if (cursor == null) return -1;
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            int sibling = 1;
            while (sibling < path[depth]) {
                if (!seen.add(cursor)) return -1;
                Integer next = byIdNum.get(blocks.get(cursor).nextBlock);
                if (next == null) return -1;
                cursor = next;
                sibling++;
            }
            beanIndex = cursor;
        }
        return beanIndex;
    }

    /** Plain-words rendering of an event's statement tree. */
    public static String renderEvent(ArrayList<BlockBean> blocks,
                                     ArrayList<Integer> chain) {
        StringBuilder out = new StringBuilder();
        renderChain(blocks, indexByNumericId(blocks), chain, "", out);
        return out.toString().trim();
    }

    private static void renderChain(ArrayList<BlockBean> blocks,
                                    Map<Integer, Integer> byIdNum,
                                    ArrayList<Integer> chain,
                                    String indent, StringBuilder out) {
        for (int i = 0; i < chain.size(); i++) {
            BlockBean bean = blocks.get(chain.get(i));
            out.append(indent).append(i + 1).append(". ")
                    .append(describeBlock(bean)).append("\n");
            if (bean.subStack1 >= 0) {
                renderChain(blocks, byIdNum,
                        subChain(blocks, byIdNum, bean.subStack1),
                        indent + "  ", out);
            }
            if (bean.subStack2 >= 0) {
                out.append(indent).append("  else:\n");
                renderChain(blocks, byIdNum,
                        subChain(blocks, byIdNum, bean.subStack2),
                        indent + "  ", out);
            }
        }
    }

    private static ArrayList<Integer> subChain(
            ArrayList<BlockBean> blocks,
            Map<Integer, Integer> byIdNum, int headId) {
        ArrayList<Integer> sub = new ArrayList<>();
        Integer cursor = byIdNum.get(headId);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            sub.add(cursor);
            cursor = byIdNum.get(blocks.get(cursor).nextBlock);
        }
        return sub;
    }

    /** One block in plain words, from its stored spec and values. */
    public static String describeBlock(BlockBean bean) {
        String spec = bean.spec == null ? "" : bean.spec;
        ArrayList<String> values = bean.parameters == null
                ? new ArrayList<>() : bean.parameters;
        StringBuilder words = new StringBuilder();
        java.util.regex.Matcher matcher = SLOT_PATTERN.matcher(spec);
        int slotOrdinal = 0;
        int lastEnd = 0;
        while (matcher.find()) {
            words.append(spec, lastEnd, matcher.start());
            String slot = matcher.group();
            String value = slotOrdinal < values.size()
                    ? values.get(slotOrdinal) : "";
            words.append(describeSlotValue(slot, value));
            slotOrdinal++;
            lastEnd = matcher.end();
        }
        words.append(spec.substring(lastEnd));
        String text = words.toString().replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? String.valueOf(bean.opCode) : text;
    }

    private static String describeSlotValue(String slot, String value) {
        if (value == null) value = "";
        if (value.startsWith("@")) {
            // "@42" points at a value block whose spec is its meaning.
            return "(a value)";
        }
        if ("%s".equals(slot) || slot.startsWith("%s")) {
            return "\"" + value + "\"";
        }
        return value;
    }

    /**
     * The editable values of one stored block: the value slots the
     * agent can rewrite without touching structure, matched by the
     * opCodes whose semantics the wiring engine already proves.
     */
    static ArrayList<EditableValue> describeEditableValues(
            BlockBean bean) {
        ArrayList<EditableValue> out = new ArrayList<>();
        List<String> slots = slotsOf(bean.spec);
        int count = Math.min(slots.size(),
                bean.parameters == null ? 0 : bean.parameters.size());
        for (int i = 0; i < count; i++) {
            String slot = slots.get(i);
            String kind = switch (String.valueOf(bean.opCode)) {
                case "doToast" -> i == 0 && "%s".equals(slot)
                        ? EDIT_MESSAGE : null;
                case "setText" -> i == 1 && "%s".equals(slot)
                        ? EDIT_TEXT : null;
                case "setVarString" -> i == 1 && "%s".equals(slot)
                        ? EDIT_TEXT : null;
                case "setVarInt" -> i == 1 && "%d".equals(slot)
                        ? EDIT_NUMBER : null;
                case "setVarBoolean" -> i == 1 && "%b".equals(slot)
                        ? EDIT_VALUE : null;
                default -> null;
            };
            if (kind != null) {
                EditableValue value = new EditableValue();
                value.kind = kind;
                value.paramIdx = i;
                value.currentValue = bean.parameters.get(i);
                out.add(value);
            }
        }
        return out;
    }

    /** Slot tokens of a spec, in order. */
    private static List<String> slotsOf(String spec) {
        ArrayList<String> slots = new ArrayList<>();
        if (spec == null) return slots;
        java.util.regex.Matcher matcher = SLOT_PATTERN.matcher(spec);
        while (matcher.find()) slots.add(matcher.group());
        return slots;
    }

    private static String pathText(int[] path) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < path.length; i++) {
            if (i > 0) text.append(".");
            text.append(path[i]);
        }
        return text.toString();
    }

    // -- project theme -----------------------------------------------------

    /** The project-setting screen's own theme keys, in label order. */
    public static final String[] THEME_KEYS = {
            "color_accent", "color_primary", "color_primary_dark",
            "color_control_highlight", "color_control_normal"};

    /** Plain-words names for the theme slots. */
    public static String themeLabel(String key) {
        return switch (String.valueOf(key)) {
            case "color_primary" -> "Primary colour";
            case "color_primary_dark" -> "Dark primary colour";
            case "color_accent" -> "Accent colour";
            case "color_control_highlight" -> "Control highlight colour";
            case "color_control_normal" -> "Control normal colour";
            default -> String.valueOf(key);
        };
    }

    /** One proposed theme change. */
    public static final class ThemePlan {
        public String scId;
        public final LinkedHashMap<String, Integer> before =
                new LinkedHashMap<>();
        public final LinkedHashMap<String, Integer> after =
                new LinkedHashMap<>();
        public String sentence;
    }

    /**
     * Reads the project's theme colours from the same metadata map
     * the project-settings screen writes ({@code lC.b(scId)}), which
     * the builder turns into colors.xml. {@code colourOrNull} is
     * {@code null} when a key has never been stored.
     */
    public static Integer themeColour(String scId, String key) {
        try {
            HashMap<String, Object> metadata = lC.b(scId);
            if (metadata == null) return null;
            Object value = metadata.get(key);
            return value instanceof Number ? ((Number) value).intValue()
                    : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates a theme change: known colour keys with hex values,
     * before → after read from the live registry. Pure.
     */
    public static ThemePlan planTheme(String scId, JsonObject colours)
            throws AgentTools.ToolException {
        ThemePlan plan = new ThemePlan();
        plan.scId = scId;
        StringBuilder sentence = new StringBuilder();
        if (colours == null || colours.entrySet().isEmpty()) {
            throw new AgentTools.ToolException("E_BAD_THEME",
                    "Give at least one theme colour to change — "
                            + "color_primary, color_primary_dark, "
                            + "color_accent, color_control_highlight or "
                            + "color_control_normal, each #RRGGBB or "
                            + "#AARRGGBB.");
        }
        for (java.util.Map.Entry<String, JsonElement> entry
                : colours.entrySet()) {
            String key = entry.getKey();
            boolean known = false;
            for (String allowed : THEME_KEYS) {
                if (allowed.equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new AgentTools.ToolException("E_BAD_THEME",
                        "\"" + key + "\" is not one of the project's "
                                + "theme colours (color_primary, "
                                + "color_primary_dark, color_accent, "
                                + "color_control_highlight, "
                                + "color_control_normal).");
            }
            if (entry.getValue() == null
                    || entry.getValue().isJsonNull()) {
                throw new AgentTools.ToolException("E_BAD_THEME",
                        "Give a hex colour for " + themeLabel(key) + ".");
            }
            String hex = entry.getValue().getAsString().trim();
            int colour = parseThemeHex(key, hex);
            Integer stored = themeColour(scId, key);
            plan.before.put(key, stored == null ? 0 : stored);
            plan.after.put(key, colour);
            if (sentence.length() > 0) sentence.append("\n");
            sentence.append(themeLabel(key)).append(": ")
                    .append(stored == null
                            ? "(the default colour)"
                            : String.format(Locale.US, "#%06X",
                                    stored & 0xFFFFFF))
                    .append(" → ")
                    .append(String.format(Locale.US, "#%06X",
                            colour & 0xFFFFFF));
        }
        plan.sentence = sentence.toString();
        return plan;
    }

    /** Applies a theme change through the app's own metadata write. */
    public static JsonObject applyTheme(ThemePlan plan)
            throws AgentTools.ToolException {
        HashMap<String, Object> current = lC.b(plan.scId);
        HashMap<String, Object> updated = new HashMap<>();
        if (current != null) updated.putAll(current);
        // Refuse to write over a live registry whose theme values
        // drifted from what the card showed.
        for (java.util.Map.Entry<String, Integer> entry
                : plan.before.entrySet()) {
            Integer live = themeColour(plan.scId, entry.getKey());
            if (live != null
                    && !live.equals(entry.getValue())) {
                throw new AgentTools.ToolException("E_THEME_CHANGED",
                        "The project's theme colours changed since "
                                + "this was proposed; nothing was "
                                + "changed. Ask for a fresh proposal.");
            }
            updated.put(entry.getKey(), plan.after.get(entry.getKey()));
        }
        try {
            SketchwareActions.backupProjectOnce(plan.scId);
        } catch (Exception e) {
            throw new AgentTools.ToolException("E_BACKUP_FAILED",
                    "The safety backup could not be written, so the "
                            + "theme was not changed.");
        }
        lC.b(plan.scId, updated);
        // yq generates colors.xml from this registry at build time
        // (there is no on-disk copy to sync), so read-back here is
        // the whole verification.
        for (java.util.Map.Entry<String, Integer> entry
                : plan.after.entrySet()) {
            Integer live = themeColour(plan.scId, entry.getKey());
            if (!entry.getValue().equals(live)) {
                // Roll back to the pre-change registry copy.
                if (current != null) {
                    lC.b(plan.scId, new HashMap<>(current));
                }
                throw new AgentTools.ToolException("E_VERIFY_FAILED",
                        "The theme change did not survive Sketchware's "
                                + "own save — the previous colours were "
                                + "restored.");
            }
        }
        SketchwareActions.commitProject(plan.scId);
        JsonObject result = new JsonObject();
        result.addProperty("applied", true);
        JsonObject readBack = new JsonObject();
        for (java.util.Map.Entry<String, Integer> entry
                : plan.after.entrySet()) {
            readBack.addProperty(entry.getKey(),
                    String.format(Locale.US, "#%06X",
                            entry.getValue() & 0xFFFFFF));
        }
        result.add("theme", readBack);
        result.addProperty("verified",
                "Theme colours saved and read back: "
                        + plan.sentence.replace("\n", " "));
        return result;
    }

    /** Parses #RRGGBB / #AARRGGBB into a colour int. */
    private static int parseThemeHex(String key, String hex)
            throws AgentTools.ToolException {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            long value = Long.parseLong(digits, 16);
            if ((digits.length() != 6 && digits.length() != 8)
                    || value < 0 || value > 0xFFFFFFFFL) {
                throw new NumberFormatException(hex);
            }
            return (int) (digits.length() == 6
                    ? value | 0xFF000000L : value);
        } catch (NumberFormatException e) {
            throw new AgentTools.ToolException("E_BAD_THEME",
                    "\"" + hex + "\" is not a colour "
                            + (key == null ? "" : "for " + themeLabel(key))
                            + " — use #RRGGBB or #AARRGGBB.");
        }
    }

    private static BlockBean findById(ArrayList<BlockBean> blocks,
                                      String id) {
        for (BlockBean bean : blocks) {
            if (id.equals(bean.id)) return bean;
        }
        return null;
    }

    /** JSON int array from a named argument. */
    private static int[] intArrayOf(JsonObject object, String key) {
        try {
            com.google.gson.JsonArray array =
                    object.getAsJsonArray(key);
            int[] out = new int[array.size()];
            for (int i = 0; i < array.size(); i++) {
                out[i] = array.get(i).getAsInt();
            }
            return out;
        } catch (Exception e) {
            return new int[0];
        }
    }
}
