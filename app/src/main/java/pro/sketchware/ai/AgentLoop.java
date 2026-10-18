package pro.sketchware.ai;

import android.content.Context;

import com.besome.sketch.beans.ProjectFileBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The agent loop: the model proposes tool calls, the app executes them,
 * results go back, and the model continues — until it answers in plain
 * text. Hard-capped (about twenty calls per message), and stopped early
 * when the same failure repeats, instead of spinning against the user's
 * key.
 *
 * <p>Runs on a background executor; listener callbacks already arrive on
 * the main thread. When the provider cannot do tool calling at all, the
 * loop reports it as an unsupported error and the chat falls back to a
 * plain conversation — the user is never stuck.
 */
public class AgentLoop {

    /** Tool calls allowed for one user message. */
    private static final int MAX_TOOL_CALLS = 20;

    private static final String AGENT_GUIDANCE =
            "\n\nYou are connected to the user's Sketchware Pro app and can "
                    + "look at the project they are working on with your "
                    + "tools. Use the tools when a question is about their "
                    + "project instead of guessing. You can also create "
                    + "projects and screens, and edit screens widget by "
                    + "widget: every change is shown to the "
                    + "user on an approval card first and only runs if "
                    + "they approve it, so propose changes freely but "
                    + "never claim a change happened before the tool "
                    + "confirms it — and if the user rejects one, do not "
                    + "claim the app itself lacks a feature your tools "
                    + "don't have yet: Sketchware is a full app builder "
                    + "and can do far more than your current tools. When "
                    + "asked for something your tools can't do (components, "
                    + "map or list variables, reading files, and the "
                    + "like), say plainly that YOU "
                    + "can't do that yet — never invent manual "
                    + "instructions or walkthrough steps to cover the "
                    + "gap, and offer the closest thing you can do. "
                    + "retry it. When a widget is not visible because "
                    + "of where it sits in a layout, fix the placement: "
                    + "move it into the screen's main container, or "
                    + "adjust sizes and weights — do not restructure "
                    + "the screen (for example swapping its main "
                    + "container for a ScrollView) unless the user asks "
                    + "for scrolling. When a tool confirms a change "
                    + "succeeded, that change is DONE — never propose "
                    + "the same create or edit again in the same turn. "
                    + "If a create then reports the name is already "
                    + "taken, that is your own earlier success coming "
                    + "back: treat the thing as existing, continue with "
                    + "the rest of the task, and do not ask the user "
                    + "about a duplicate. The user's newest message "
                    + "always wins: "
                    + "if an earlier step failed, stopped, or was "
                    + "rejected, do not bring it back on top of a new "
                    + "instruction — only pick it up again if the user "
                    + "themselves returns to that subject. Do not build "
                    + "after every change: wiring and edits are already "
                    + "checked before they run. Build only when the "
                    + "user asks to build, install or run, or to check "
                    + "a fix for errors a previous build reported — "
                    + "never more than one build per user request "
                    + "unless the build itself reported errors. Do not "
                    + "chain a build onto routine edits unprompted. "
                    + "Always check a project's "
                    + "current screens and widgets with the tools, even if "
                    + "earlier messages mention them — the user may have "
                    + "changed the project since, and remembered details go "
                    + "stale. Nearly everything the "
                    + "tools tell you is invisible to the user — answer in "
                    + "plain words and never show raw tool output, ids, or "
                    + "JSON. If a tool reports that no working project is "
                    + "chosen, relay its guidance to the user. When the user "
                    + "leaves a choice open or says they don't know the "
                    + "options (\"show me\", \"I don't know many…\", \"not "
                    + "sure\"), never silently pick a taste choice for them "
                    + "and never hide it inside an approval card: offer "
                    + "three to five named options in plain words (for "
                    + "colours, friendly names with their hex), say which "
                    + "you'd suggest in one short line, and end your turn "
                    + "so they can pick. Only trivial cosmetics needed to "
                    + "finish a build you've already been asked for "
                    + "(spacing, placeholder text) may be chosen quietly. "
                    + "When the user asks to agree or discuss first (\"let's "
                    + "agree first\", \"no coding yet\", \"what do you "
                    + "think\"), do not call any tool: talk it through and "
                    + "rest the agreement in their own words, exactly as "
                    + "they meant them — a \"shade of the primary\" is a "
                    + "lighter tint of it, not the primary itself, and a "
                    + "later \"yes\" only ever approves the interpretation "
                    + "you stated back, never a different one you quietly "
                    + "substituted. Vague amounts (\"a shade\", \"a bit "
                    + "bigger\", \"a few\") are choices to offer, not "
                    + "values to invent.";

    public interface Listener {
        /** A quiet activity line, e.g. "Looked at MainActivity". */
        void onToolNote(String line);

        /** The turn finished: answer text, plus any model reasoning. */
        void onAnswer(String text, String thinking);

        /** The turn failed (or tool calling is unsupported). */
        void onError(AiChatService.AiError error);

        /**
         * A change needs the user's decision. Called on the loop thread
         * and expected to block until the user decides; false rejects.
         */
        boolean onApproval(AgentProposal proposal);

        /**
         * The agent asks the user to pick a colour in Sketchware's own
         * picker. Called on the loop thread and expected to block until
         * the user picks or closes it. Returns {"picked": "#AARRGGBB",
         * optionally "name": "..."}, or null when no colour was picked.
         */
        JsonObject onPickColor(String question, String initialHex);

        /** The decision was acted on; the card can show the outcome. */
        void onApprovalResolved(AgentProposal proposal, JsonObject result);

        /** A created project just became the working project. */
        void onWorkingProjectChanged(String scId);
    }

    private final Context context;
    private final AiChatService service;
    private final Executor io;
    private volatile boolean cancelled;

    public AgentLoop(Context context, AiChatService service, Executor io) {
        this.context = context.getApplicationContext();
        this.service = service;
        this.io = io;
    }

    /** Stops the in-flight turn. */
    public void cancel() {
        cancelled = true;
        service.cancel();
    }

    /**
     * Runs one user turn. {@code history} is the visible conversation
     * (user/assistant text); the loop takes it from there.
     */
    public void run(final String baseUrl, final String apiKey, final String model,
                    final List<ChatMessage> history, String workingProjectId,
                    final boolean allowModify, final Listener listener) {
        cancelled = false;
        io.execute(() -> runTurn(baseUrl, apiKey, model, history,
                workingProjectId, allowModify, listener));
    }

    private void runTurn(String baseUrl, String apiKey, String model,
                         List<ChatMessage> history, String workingProjectId,
                         boolean allowModify, Listener listener) {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(systemMessage(workingProjectId));
        int kept = 0;
        for (int i = history.size() - 1; i >= 0 && kept < 40; i--) {
            ChatMessage message = history.get(i);
            if (message.state == ChatMessage.State.STREAMING
                    || message.role == ChatMessage.Role.SYSTEM
                    || message.role == ChatMessage.Role.APPROVAL) {
                continue;
            }
            if (message.role == ChatMessage.Role.ASSISTANT
                    && message.text.trim().isEmpty()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("role",
                    message.role == ChatMessage.Role.USER ? "user" : "assistant");
            entry.addProperty("content", message.text);
            messages.add(1, entry);
            kept++;
        }

        int calls = 0;
        String lastFailedCall = "";
        int sameFailure = 0;
        while (!cancelled) {
            AiChatService.AgentReply reply;
            try {
                reply = service.agentTurnSync(baseUrl, apiKey, model, messages,
                        AgentTools.definitions());
            } catch (AiChatService.AgentRequestException e) {
                if (!cancelled) {
                    dispatch(listener, () -> listener.onError(e.error));
                }
                return;
            }
            if (reply.toolCalls.isEmpty()) {
                String answer = reply.content == null ? "" : reply.content.trim();
                if (answer.isEmpty()) {
                    dispatch(listener, () -> listener.onError(new AiChatService.AiError(
                            AiChatService.ErrorKind.EMPTY, 200, "empty reply")));
                } else {
                    String thinking = reply.thinking == null ? "" : reply.thinking;
                    dispatch(listener, () -> listener.onAnswer(answer, thinking));
                }
                return;
            }
            if (reply.rawMessage != null) {
                messages.add(reply.rawMessage);
            }
            for (AiChatService.ToolCallData call : reply.toolCalls) {
                if (cancelled) {
                    return;
                }
                if (++calls > MAX_TOOL_CALLS) {
                    dispatch(listener, () -> listener.onError(new AiChatService.AiError(
                            AiChatService.ErrorKind.HTTP, 0,
                            "The agent stopped after " + MAX_TOOL_CALLS
                                    + " tool calls for one message.")));
                    return;
                }
                AgentTools.Session session = new AgentTools.Session(
                        context, workingProjectId, allowModify);
                JsonObject result;
                if (AgentTools.isMutation(call.name)) {
                    result = proposeAndExecute(call, session, listener);
                    if (cancelled) {
                        return;
                    }
                    if (result.has("bind_project")) {
                        workingProjectId = result.get("bind_project").getAsString();
                        result.remove("bind_project");
                        String bound = workingProjectId;
                        dispatch(listener,
                                () -> listener.onWorkingProjectChanged(bound));
                    }
                } else if (AgentTools.isInteractive(call.name)) {
                    String label = AgentTools.labelFor(call.name, call.arguments);
                    dispatch(listener, () -> listener.onToolNote(label));
                    result = askUser(call, listener);
                } else {
                    String label = AgentTools.labelFor(call.name, call.arguments);
                    dispatch(listener, () -> listener.onToolNote(label));
                    result = AgentTools.dispatch(call.name, call.arguments, session);
                }
                boolean failed = result.has("error")
                        && !"E_REJECTED".equals(result.get("error").getAsString());
                if (failed) {
                    String signature = call.name + "|" + result.get("error").getAsString();
                    sameFailure = signature.equals(lastFailedCall)
                            ? sameFailure + 1 : 1;
                    lastFailedCall = signature;
                    if (sameFailure >= 2) {
                        String reason = result.has("message")
                                ? result.get("message").getAsString()
                                : "";
                        String stopText = "The agent stopped: the same step "
                                + "failed twice (" + call.name + ")."
                                + (reason.isEmpty() ? ""
                                        : " Last problem: " + reason)
                                + " Tell me how you'd like to proceed.";
                        dispatch(listener, () -> listener.onError(
                                new AiChatService.AiError(
                                        AiChatService.ErrorKind.HTTP, 0,
                                        stopText)));
                        return;
                    }
                } else {
                    lastFailedCall = "";
                    sameFailure = 0;
                }
                JsonObject toolMessage = new JsonObject();
                toolMessage.addProperty("role", "tool");
                toolMessage.addProperty("tool_call_id", call.id);
                toolMessage.addProperty("content", result.toString());
                messages.add(toolMessage);
            }
        }
    }

    /**
     * Interactive tools: the turn pauses while the user acts in the
     * app's own UI. Their action (or their closing the UI) is the tool
     * result — a cancellation is information, not a failure.
     */
    private JsonObject askUser(AiChatService.ToolCallData call,
                               Listener listener) {
        JsonObject args = new JsonObject();
        try {
            args = com.google.gson.JsonParser.parseString(
                    call.arguments == null ? "{}" : call.arguments)
                    .getAsJsonObject();
        } catch (Exception ignored) {
            // An unparseable call still deserves the picker, unfilled.
        }
        String question = args.has("question")
                ? args.get("question").getAsString() : "a colour";
        String initial = args.has("initial")
                ? args.get("initial").getAsString() : "";
        JsonObject picked = listener.onPickColor(question, initial);
        JsonObject result = new JsonObject();
        if (picked == null) {
            result.addProperty("cancelled", true);
            result.addProperty("message",
                    "The user closed the colour picker without picking. "
                            + "Ask what they would like in words, or "
                            + "continue without that colour change — do "
                            + "not pick a colour for them instead.");
            return result;
        }
        result.add("picked_colour", picked);
        result.addProperty("message",
                "The user picked this colour themselves in Sketchware's "
                        + "colour picker. Use the hex exactly, and propose "
                        + "the change on an approval card right away — the "
                        + "card is the confirmation, so do not ask again "
                        + "in chat first.");
        return result;
    }

    /**
     * The mutation path: validate into a proposal, let the user decide
     * on the card, and only then execute. Any refusal along the way is
     * reported to the model as a normal tool result.
     */
    private JsonObject proposeAndExecute(AiChatService.ToolCallData call,
                                         AgentTools.Session session,
                                         Listener listener) {
        AgentTools.ProposalResult proposed =
                AgentTools.propose(call.name, call.arguments, session);
        if (proposed.error != null) {
            return proposed.error;
        }
        AgentProposal proposal = proposed.proposal;
        boolean approved = listener.onApproval(proposal);
        if (!approved) {
            JsonObject rejected = new JsonObject();
            rejected.addProperty("error", "E_REJECTED");
            rejected.addProperty("message",
                    "The user rejected this change. Do not retry it or "
                            + "argue; ask what they would like instead.");
            dispatch(listener, () -> listener.onApprovalResolved(proposal, rejected));
            return rejected;
        }
        JsonObject result = AgentTools.execute(proposal,
                new AgentTools.Session(context, proposal.workingProjectId,
                        session.allowModify));
        dispatch(listener, () -> listener.onApprovalResolved(proposal, result));
        return result;
    }

    private JsonObject systemMessage(String workingProjectId) {
        StringBuilder prompt = new StringBuilder(AiChatService.SYSTEM_PROMPT)
                .append(AGENT_GUIDANCE);
        if (workingProjectId != null && !workingProjectId.isEmpty()) {
            SketchwareProjects.ProjectInfo project =
                    SketchwareProjects.project(workingProjectId);
            if (project != null) {
                prompt.append("\n\nThe user is working on the Sketchware "
                        + "project \"").append(project.name)
                        .append("\" (app \"").append(project.appName)
                        .append("\", package ").append(project.packageName)
                        .append(").");
                // Per-turn snapshot: the bound project's screens as they
                // are right now, so a basic "what's in my project" never
                // has to be answered from conversation memory — which is
                // exactly how the agent once described the previous
                // project after the user switched mid-chat.
                StringBuilder screens = new StringBuilder();
                for (ProjectFileBean activity
                        : SketchwareProjects.activities(workingProjectId)) {
                    if (screens.length() > 0) {
                        screens.append(", ");
                    }
                    screens.append(SketchwareProjects.activityName(activity))
                            .append(" (")
                            .append(SketchwareProjects
                                    .screenBeans(workingProjectId, activity)
                                    .size())
                            .append(" widgets)");
                }
                if (screens.length() > 0) {
                    prompt.append(" Its screens right now: ")
                            .append(screens).append(".");
                }
                prompt.append(" Widget details are available through your "
                        + "tools. If earlier replies in this conversation "
                        + "describe a different project or different "
                        + "screens, the user switched projects since; trust "
                        + "this snapshot and your tools over them.");
            }
        } else {
            prompt.append("\n\nNo working project is chosen right now. If "
                    + "the user asks about any project's screens or "
                    + "widgets, do not answer from earlier conversation: "
                    + "ask them to choose the project with the \"Working "
                    + "on\" card at the top of the chat first.");
        }
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", prompt.toString());
        return system;
    }

    /** Delivers a callback on the main thread. */
    private void dispatch(Listener listener, Runnable action) {
        // The loop runs on the background executor; AgentFragment's
        // listener posts to the UI itself.
        action.run();
    }
}
