package pro.sketchware.ai;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One message in the agent chat.
 *
 * <p>Plain Java bean so it can be serialised straight into the chat history
 * with Gson.
 */
public class ChatMessage {

    /** One image attached to a chat message, stored under {@link AiPaths#attachmentsDir()}. */
    public static final class Attachment {
        public String id = "";
        public String path = "";
        public String mimeType = "image/jpeg";
        public int width;
        public int height;
        public long bytes;

        public Attachment() {
        }

        public Attachment(String id, String path, String mimeType,
                          int width, int height, long bytes) {
            this.id = id == null ? "" : id;
            this.path = path == null ? "" : path;
            this.mimeType = (mimeType == null || mimeType.isEmpty())
                    ? "image/jpeg" : mimeType;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
        }

        public Attachment copy() {
            return new Attachment(id, path, mimeType, width, height, bytes);
        }
    }

    /** Who produced the message. */
    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM,
        /** An approval card for a proposed change. Never sent to a model. */
        APPROVAL
    }

    /** Approval-card lifecycle. */
    public static final String APPROVAL_PENDING = "PENDING";
    public static final String APPROVAL_WORKING = "WORKING";
    public static final String APPROVAL_DONE = "DONE";
    public static final String APPROVAL_REJECTED = "REJECTED";
    public static final String APPROVAL_FAILED = "FAILED";

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
    /** Optional images attached to this message (user screenshots, mockups, logos). */
    public final List<Attachment> attachments = new ArrayList<>();
    /** Reasoning the model showed before answering ("" = it did not). */
    public String thinking = "";
    /** Whether the thinking block is expanded in the transcript. */
    public boolean thinkingExpanded;
    /** Persisted reactive {@code $variable} state for inline OpenUI Lang blocks in this bubble. */
    public JsonObject openUiState;

    // Approval cards (role == APPROVAL): plain words only, never beans.
    public String approvalTitle = "";
    public String approvalBody = "";
    public String approvalDetails = "";
    public String approvalState = APPROVAL_PENDING;
    /** What was seen after the change ran (or why it did not). */
    public String approvalOutcome = "";
    public boolean approvalDetailsExpanded;
    /** The tool this card asks about ("" on older cards). */
    public String approvalTool = "";
    /**
     * Timed auto-approve: seconds left on this pending card's
     * countdown (0 = no timer; -1 = paused because the user opened
     * Details). In-memory only — a restored chat is never pending.
     */
    public transient int approvalCountdownLeft;
    /** Whether this card's tool is one that must never self-approve. */
    public boolean approvalManualHold;

    /**
     * Build cards (role == APPROVAL, tool build_project, once Done):
     * which APK the card can install, and its fingerprint (size and
     * modification time at build end). The card re-stats that file
     * before offering install, so it can never quietly install a
     * different build — an editor build replaces the file at the same
     * path, and the card must say so.
     */
    public String buildApkPath = "";
    public long buildApkBytes;
    public long buildApkModified;
    /** Project this build belongs to: only a newer build of the SAME
     * project can supersede this card. */
    public String buildProjectId = "";
    /** A newer successful build of the same project replaced this
     * card's APK (same path on disk), so this card no longer offers
     * Install — only the newest build card does. */
    public boolean buildSuperseded;
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

    public boolean hasAttachments() {
        return !attachments.isEmpty();
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
