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

    public Role role = Role.USER;
    public String text = "";
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

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text);
    }

    public static ChatMessage assistant(String text) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, text);
        message.state = State.STREAMING;
        return message;
    }

    public static ChatMessage error(String text) {
        ChatMessage message = new ChatMessage(Role.ASSISTANT, text);
        message.state = State.ERROR;
        return message;
    }
}
