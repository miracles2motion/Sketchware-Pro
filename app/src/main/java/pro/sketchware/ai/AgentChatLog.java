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
            if (message.role != ChatMessage.Role.USER
                    && message.role != ChatMessage.Role.ASSISTANT) {
                continue;
            }
            if (message.text == null || message.text.trim().isEmpty()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("role",
                    message.role == ChatMessage.Role.USER
                            ? "user" : "assistant");
            entry.addProperty("text", message.text);
            if (message.thinking != null && !message.thinking.isEmpty()) {
                entry.addProperty("thinking", message.thinking);
            }
            stored.add(entry);
            while (stored.size() > MAX_MESSAGES) {
                stored.remove(0);
            }
        }
        try (FileWriter writer = new FileWriter(file(scId))) {
            GSON.toJson(stored, writer);
        } catch (Exception ignored) {
            // Losing the chat copy must never break the chat itself.
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
        try (FileReader reader = new FileReader(file)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonArray()) {
                return messages;
            }
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String text = entry.has("text")
                        ? entry.get("text").getAsString() : "";
                if (text.trim().isEmpty()) {
                    continue;
                }
                boolean user = entry.has("role")
                        && "user".equals(entry.get("role").getAsString());
                ChatMessage message = new ChatMessage(
                        user ? ChatMessage.Role.USER
                                : ChatMessage.Role.ASSISTANT, text);
                message.state = ChatMessage.State.COMPLETE;
                if (entry.has("thinking")) {
                    message.thinking =
                            entry.get("thinking").getAsString();
                }
                messages.add(message);
            }
        } catch (Exception ignored) {
            // A damaged copy reads as an empty chat, never a crash.
        }
        return messages;
    }
}
