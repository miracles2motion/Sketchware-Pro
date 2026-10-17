package pro.sketchware.ai;

/**
 * One message in the agent chat.
 *
 * <p>Plain Java bean so it can be serialised straight into the chat history
 * with Gson.
 */
public class ChatMessage {

    /** Who produced the message. */
    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    /** Lifecycle of the message, used to drive the streaming UI. */
    public enum State {
        /** Queued, not sent to the model yet. */
        PENDING,
        /** Tokens are still arriving. */
        STREAMING,
        /** The turn finished. */
        COMPLETE,
        /** The turn failed. */
        ERROR
    }

    /** When the reply finished (assistant messages only). */
    public long repliedAt;

    /** How long the reply took to generate, in milliseconds. */
    public long durationMs;

    public Role role = Role.USER;
    public String text = "";
    /** Reasoning the model showed before answering ("" = it did not). */
    public String thinking = "";
    /** Whether the thinking block is expanded in the transcript. */
    public boolean thinkingExpanded;
    public State state = State.COMPLETE;
    public long timestamp;

    public ChatMessage() {
    }

    public ChatMessage(Role role, String text) {
        this.role = role;
        this.text = text == null ? "" : text;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isFromUser() {
        return role == Role.USER;
    }

    /** True for a message the app itself wrote, not the model. */
    public boolean isFromApp() {
        return role == Role.SYSTEM;
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    public static ChatMessage assistant(String text) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, text);
        message.state = State.STREAMING;
        return message;
    }

    public static ChatMessage error(String text) {
        // An error comes from the app, not from the model, so it is shown as
        // a quiet note instead of a reply.
        ChatMessage message = new ChatMessage(Role.SYSTEM, text);
        message.state = State.ERROR;
        return message;
    }
}
