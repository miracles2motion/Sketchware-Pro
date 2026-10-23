package pro.sketchware.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The visible conversation for one project, kept on disk (in the
 * agent root, next to the task journal) so picking the project again
 * brings its chat back — closing the app never wipes it.
 *
 * <p>Only the conversation text is stored: user and assistant
 * messages with any (collapsed) thinking. Approval cards, build
 * cards and notes are moments of a live turn, not conversation, and
 * are not resurrected — the task journal is the record of what was
 * actually done. The log holds at most the most recent messages.
 */
public final class AgentChatLog {

    private AgentChatLog() {
    }

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_MESSAGES = 100;

    private static File file(String scId) {
        File dir = new File(AiPaths.agentRoot(), "chats");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, scId + ".json");
    }

    /** Stores the conversation belonging to a project. */
    public static synchronized void save(String scId,
                                         List<ChatMessage> messages) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        JsonArray stored = new JsonArray();
        for (ChatMessage message : messages) {
            // Approval and build cards are kept too: reopening a
            // project must show the decisions that were made, and an
            // export has to be able to prove them. A card with no
            // headline (nothing was ever proposed) is still skipped.
            boolean isCard = message.role == ChatMessage.Role.APPROVAL;
            if (message.role != ChatMessage.Role.USER
                    && message.role != ChatMessage.Role.ASSISTANT
                    && !isCard) {
                continue;
            }
            if (isCard && (message.approvalTitle == null
                    || message.approvalTitle.trim().isEmpty())) {
                continue;
            }
            boolean hasImages = message.hasAttachments();
            if (!isCard && !hasImages
                    && (message.text == null
                            || message.text.trim().isEmpty())) {
                continue;
            }
            JsonObject entry = new JsonObject();
            if (isCard) {
                entry.addProperty("role", "approval");
                entry.addProperty("title", message.approvalTitle);
                if (message.approvalBody != null
                        && !message.approvalBody.isEmpty()) {
                    entry.addProperty("body", message.approvalBody);
                }
                if (message.approvalDetails != null
                        && !message.approvalDetails.isEmpty()) {
                    entry.addProperty("details", message.approvalDetails);
                }
                entry.addProperty("approvalState",
                        String.valueOf(message.approvalState));
                if (message.approvalOutcome != null
                        && !message.approvalOutcome.isEmpty()) {
                    entry.addProperty("outcome",
                            message.approvalOutcome);
                }
                if (message.buildApkPath != null
                        && !message.buildApkPath.isEmpty()) {
                    entry.addProperty("apk", message.buildApkPath);
                }
                entry.addProperty("timestamp", message.timestamp);
                stored.add(entry);
                while (stored.size() > MAX_MESSAGES) {
                    stored.remove(0);
                }
                continue;
            }
            entry.addProperty("role",
                    message.role == ChatMessage.Role.USER
                            ? "user" : "assistant");
            entry.addProperty("text",
                    message.text == null ? "" : message.text);
            if (message.thinking != null && !message.thinking.isEmpty()) {
                entry.addProperty("thinking", message.thinking);
            }
            if (message.timestamp > 0) {
                entry.addProperty("timestamp", message.timestamp);
            }
            if (hasImages) {
                JsonArray imgs = new JsonArray();
                for (ChatMessage.Attachment att : message.attachments) {
                    if (att == null || att.path == null
                            || att.path.isEmpty()) {
                        continue;
                    }
                    JsonObject obj = new JsonObject();
                    obj.addProperty("id", att.id == null ? "" : att.id);
                    obj.addProperty("path", att.path);
                    obj.addProperty("mimeType",
                            att.mimeType == null ? "image/jpeg" : att.mimeType);
                    obj.addProperty("width", att.width);
                    obj.addProperty("height", att.height);
                    obj.addProperty("bytes", att.bytes);
                    imgs.add(obj);
                }
                if (imgs.size() > 0) {
                    entry.add("attachments", imgs);
                }
            }
            if (message.openUiState != null && message.openUiState.size() > 0) {
                entry.add("openUiState", message.openUiState.deepCopy());
            }
            stored.add(entry);
            while (stored.size() > MAX_MESSAGES) {
                stored.remove(0);
            }
        }
        JsonObject wrapper = new JsonObject();
        try {
            SketchwareProjects.ProjectInfo info =
                    SketchwareProjects.project(scId);
            if (info != null && !info.packageName.isEmpty()) {
                wrapper.addProperty("projectPackage", info.packageName);
            }
        } catch (Exception ignored) {
        }
        wrapper.add("messages", stored);
        try (FileWriter writer = new FileWriter(file(scId))) {
            GSON.toJson(wrapper, writer);
        } catch (Exception ignored) {
            // Losing the chat copy must never break the chat itself.
        }
    }

    /** Deletes any stored conversation for {@code scId} (e.g. on project creation). */
    public static synchronized void clear(String scId) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        File f = file(scId);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** True when a project has a stored conversation to come back to. */
    public static synchronized boolean hasChat(String scId) {
        return !load(scId).isEmpty();
    }

    /** The stored conversation for a project (empty when none). */
    public static synchronized List<ChatMessage> load(String scId) {
        List<ChatMessage> messages = new ArrayList<>();
        if (scId == null || scId.isEmpty()) {
            return messages;
        }
        File file = file(scId);
        if (!file.exists()) {
            return messages;
        }
        SketchwareProjects.ProjectInfo info = SketchwareProjects.project(scId);
        try (FileReader reader = new FileReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null) {
                return messages;
            }
            JsonArray arr = null;
            if (parsed.isJsonArray()) {
                arr = parsed.getAsJsonArray();
            } else if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                String storedPkg = obj.has("projectPackage")
                        && !obj.get("projectPackage").isJsonNull()
                        ? obj.get("projectPackage").getAsString() : "";
                if (info != null && !info.packageName.isEmpty()
                        && !storedPkg.isEmpty()
                        && !storedPkg.equals(info.packageName)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    return messages;
                }
                if (obj.has("messages") && obj.get("messages").isJsonArray()) {
                    arr = obj.getAsJsonArray("messages");
                }
            }
            if (arr == null) {
                return messages;
            }
            for (JsonElement element : arr) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String role = entry.has("role")
                        ? entry.get("role").getAsString() : "";
                if ("approval".equals(role)) {
                    ChatMessage card =
                            new ChatMessage(ChatMessage.Role.APPROVAL, "");
                    card.approvalTitle = entry.has("title")
                            ? entry.get("title").getAsString() : "";
                    if (card.approvalTitle.trim().isEmpty()) {
                        continue;
                    }
                    card.approvalBody = entry.has("body")
                            ? entry.get("body").getAsString() : "";
                    card.approvalDetails = entry.has("details")
                            ? entry.get("details").getAsString() : "";
                    card.approvalState = entry.has("approvalState")
                            ? entry.get("approvalState").getAsString()
                            : ChatMessage.APPROVAL_DONE;
                    card.approvalOutcome = entry.has("outcome")
                            ? entry.get("outcome").getAsString() : "";
                    card.buildApkPath = entry.has("apk")
                            ? entry.get("apk").getAsString() : "";
                    if (entry.has("timestamp")) {
                        try {
                            card.timestamp =
                                    entry.get("timestamp").getAsLong();
                        } catch (Exception ignored) {
                            // Falls back to the creation time.
                        }
                    }
                    card.state = ChatMessage.State.COMPLETE;
                    messages.add(card);
                    continue;
                }
                String text = entry.has("text")
                        ? entry.get("text").getAsString() : "";
                List<ChatMessage.Attachment> loadedAttachments =
                        new ArrayList<>();
                if (entry.has("attachments")
                        && entry.get("attachments").isJsonArray()) {
                    for (JsonElement attEl
                            : entry.getAsJsonArray("attachments")) {
                        if (!attEl.isJsonObject()) {
                            continue;
                        }
                        JsonObject attObj = attEl.getAsJsonObject();
                        String path = attObj.has("path")
                                ? attObj.get("path").getAsString() : "";
                        if (path.isEmpty()) {
                            continue;
                        }
                        ChatMessage.Attachment att =
                                new ChatMessage.Attachment(
                                        attObj.has("id")
                                                ? attObj.get("id").getAsString()
                                                : "",
                                        path,
                                        attObj.has("mimeType")
                                                ? attObj.get("mimeType")
                                                        .getAsString()
                                                : "image/jpeg",
                                        attObj.has("width")
                                                ? attObj.get("width").getAsInt()
                                                : 0,
                                        attObj.has("height")
                                                ? attObj.get("height")
                                                        .getAsInt()
                                                : 0,
                                        attObj.has("bytes")
                                                ? attObj.get("bytes")
                                                        .getAsLong()
                                                : 0L);
                        loadedAttachments.add(att);
                    }
                }
                if (text.trim().isEmpty() && loadedAttachments.isEmpty()) {
                    continue;
                }
                boolean user = "user".equals(role);
                ChatMessage message = new ChatMessage(
                        user ? ChatMessage.Role.USER
                                : ChatMessage.Role.ASSISTANT, text);
                message.attachments.addAll(loadedAttachments);
                if (entry.has("timestamp")) {
                    try {
                        message.timestamp =
                                entry.get("timestamp").getAsLong();
                    } catch (Exception ignored) {
                    }
                }
                message.state = ChatMessage.State.COMPLETE;
                if (entry.has("thinking")) {
                    message.thinking =
                            entry.get("thinking").getAsString();
                }
                if (entry.has("openUiState")
                        && entry.get("openUiState").isJsonObject()) {
                    message.openUiState =
                            entry.getAsJsonObject("openUiState").deepCopy();
                }
                messages.add(message);
            }
        } catch (Exception ignored) {
            // A damaged copy reads as an empty chat, never a crash.
        }
        return messages;
    }
}
