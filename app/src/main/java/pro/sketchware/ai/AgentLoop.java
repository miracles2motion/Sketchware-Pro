package pro.sketchware.ai;

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
                    + "project instead of guessing. Nearly everything the "
                    + "tools tell you is invisible to the user — answer in "
                    + "plain words and never show raw tool output, ids, or "
                    + "JSON. If a tool reports that no working project is "
                    + "chosen, relay its guidance to the user.";

    public interface Listener {
        /** A quiet activity line, e.g. "Looked at MainActivity". */
        void onToolNote(String line);

        /** The turn finished with this plain-text answer. */
        void onAnswer(String text);

        /** The turn failed (or tool calling is unsupported). */
        void onError(AiChatService.AiError error);
    }

    private final AiChatService service;
    private final Executor io;
    private volatile boolean cancelled;

    public AgentLoop(AiChatService service, Executor io) {
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
                    final List<ChatMessage> history, final String workingProjectId,
                    final Listener listener) {
        cancelled = false;
        io.execute(() -> {
            dispatch(listener, () -> runTurn(baseUrl, apiKey, model, history,
                    workingProjectId, listener));
        });
    }

    private void runTurn(String baseUrl, String apiKey, String model,
                         List<ChatMessage> history, String workingProjectId,
                         Listener listener) {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(systemMessage(workingProjectId));
        int kept = 0;
        for (int i = history.size() - 1; i >= 0 && kept < 40; i--) {
            ChatMessage message = history.get(i);
            if (message.state == ChatMessage.State.STREAMING
                    || message.role == ChatMessage.Role.SYSTEM) {
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
                    dispatch(listener, () -> listener.onAnswer(answer));
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
                String label = AgentTools.labelFor(call.name, call.arguments);
                dispatch(listener, () -> listener.onToolNote(label));
                JsonObject result = AgentTools.dispatch(call.name, call.arguments,
                        workingProjectId);
                boolean failed = result.has("error");
                if (failed) {
                    String signature = call.name + "|" + result.get("error").getAsString();
                    sameFailure = signature.equals(lastFailedCall)
                            ? sameFailure + 1 : 1;
                    lastFailedCall = signature;
                    if (sameFailure >= 2) {
                        dispatch(listener, () -> listener.onError(
                                new AiChatService.AiError(
                                        AiChatService.ErrorKind.HTTP, 0,
                                        "The agent stopped: the same step "
                                                + "failed twice (" + call.name
                                                + "). Tell me how you'd like "
                                                + "to proceed.")));
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
                        .append("). Its screens and widgets are available "
                                + "through your tools.");
            }
        } else {
            prompt.append("\n\nNo working project is chosen yet. If the user "
                    + "asks about a project, your tools will explain how "
                    + "they can choose one.");
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
