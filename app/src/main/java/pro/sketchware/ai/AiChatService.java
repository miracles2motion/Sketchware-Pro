package pro.sketchware.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.dnsoverhttps.DnsOverHttps;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Talks to an OpenAI-compatible chat API (Gemini's compatibility endpoint,
 * OpenRouter, NVIDIA, or a custom base URL) over HTTPS with streaming.
 *
 * <p>Errors are classified, because the right UI for each is different: a
 * rejected key goes to the settings, an exhausted quota offers a model
 * switch, and a network failure is just "try again".
 */
public class AiChatService {

    private static final String TAG = "AiChatService";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** How many past messages are sent with each request. */
    private static final int MAX_HISTORY_MESSAGES = 40;

    static final String SYSTEM_PROMPT =
            "You are a helpful assistant inside Sketchware Pro, an Android app builder. "
                    + "Answer clearly and completely. Format longer answers with short "
                    + "paragraphs or simple lists when that helps.";

    public enum ErrorKind {
        /** 401/403: the key is wrong, revoked, or lacks access. */
        AUTH,
        /** 402/429 or an explicit quota error: out of credits or rate limited. */
        QUOTA,
        /** No usable connection. */
        NETWORK,
        /** Any other HTTP failure. */
        HTTP,
        /** The model exists but cannot be used the way the chat uses it. */
        UNSUPPORTED,
        /** The provider retired or renamed the model. */
        MODEL_GONE,
        /** The provider accepted the request but produced no text. */
        EMPTY
    }

    public static class AiError {
        public final ErrorKind kind;
        public final int httpCode;
        public final String message;

        AiError(ErrorKind kind, int httpCode, String message) {
            this.kind = kind;
            this.httpCode = httpCode;
            this.message = message == null ? "" : message;
        }

        @Override
        public String toString() {
            return kind + " (" + httpCode + "): " + message;
        }
    }

    public interface ChatListener {
        /** The model is producing reasoning but no visible text yet. */
        void onThinking();

        void onToken(String text);

        void onComplete();

        void onError(AiError error);
    }

    public interface ModelsListener {
        void onModels(List<String> models);

        void onError(AiError error);
    }

    public interface KeyInfoListener {
        void onInfo(String summary);

        void onError(AiError error);
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .dns(new ResilientDns())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Call activeCall;

    /**
     * Sends one turn. {@code history} already ends with the new user message.
     * Tokens are delivered on the main thread.
     */
    public void chat(String baseUrl, String apiKey, String model,
                     List<ChatMessage> history, ChatListener listener) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", true);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", SYSTEM_PROMPT);
        messages.add(system);

        int from = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        int imageTurnsRemaining = 3;
        List<JsonObject> built = new ArrayList<>();
        for (int i = history.size() - 1; i >= from; i--) {
            ChatMessage message = history.get(i);
            if (message.role == ChatMessage.Role.SYSTEM
                    || message.role == ChatMessage.Role.APPROVAL) {
                continue;
            }
            boolean hasText = message.text != null
                    && !message.text.trim().isEmpty();
            boolean hasImages = message.isFromUser()
                    && message.hasAttachments();
            if (!hasText && !hasImages) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("role",
                    message.isFromUser() ? "user" : "assistant");
            if (hasImages) {
                boolean inlineBytes = imageTurnsRemaining > 0
                        && !isModelKnownTextOnly(model);
                if (inlineBytes) {
                    imageTurnsRemaining--;
                }
                entry.add("content", buildUserContent(message, inlineBytes));
            } else {
                entry.addProperty("content", message.text.trim());
            }
            built.add(0, entry);
        }
        for (JsonObject entry : built) {
            messages.add(entry);
        }
        payload.add("messages", messages);

        Request request = new Request.Builder()
                .url(normalize(baseUrl) + "chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                // A compressed stream gets buffered by intermediaries and
                // arrives in one lump at the end; keep the events flowing.
                .header("Accept-Encoding", "identity")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        Call call = client.newCall(request);
        activeCall = call;
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (call.isCanceled()) {
                    return; // The user pressed Stop; the UI already moved on.
                }
                Log.w(TAG, "Chat request failed", e);
                postError(listener, new AiError(ErrorKind.NETWORK, 0, String.valueOf(e.getMessage())));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful() || body == null) {
                        String text = body == null ? "" : body.string();
                        postError(listener, classify(response.code(), text));
                        return;
                    }
                    stream(body, listener);
                } catch (IOException e) {
                    if (!call.isCanceled()) {
                        postError(listener, new AiError(ErrorKind.NETWORK, 0, String.valueOf(e.getMessage())));
                    }
                }
            }
        });
    }

    /** Reads the server-sent event stream and forwards the text deltas. */
    private void stream(ResponseBody body, ChatListener listener) throws IOException {
        BufferedSource source = body.source();
        boolean anyToken = false;
        boolean thinkingNotified = false;
        String line;
        while ((line = source.readUtf8Line()) != null) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String data = trimmed.substring("data:".length()).trim();
            if (data.equals("[DONE]")) {
                break;
            }
            String[] parts = deltaTexts(data);
            if (parts[1] != null && !parts[1].isEmpty() && !thinkingNotified) {
                // The model is reasoning silently. The UI says so instead
                // of looking frozen while no visible text exists yet.
                thinkingNotified = true;
                mainHandler.post(listener::onThinking);
            }
            String text = parts[0];
            if (text != null && !text.isEmpty()) {
                anyToken = true;
                mainHandler.post(() -> listener.onToken(text));
            }
        }
        if (anyToken) {
            mainHandler.post(listener::onComplete);
        } else {
            postError(listener, new AiError(ErrorKind.EMPTY, 200, "The provider returned an empty reply."));
        }
    }

    /**
     * Extracts the visible text and the (invisible) reasoning text from one
     * stream chunk: {@code [0]} is {@code choices[0].delta.content},
     * {@code [1]} is {@code choices[0].delta.reasoning_content}; either may
     * be null. Reasoning is never displayed, it only proves the model is
     * alive while the reply is still wordless.
     */
    private static String[] deltaTexts(String data) {
        String[] parts = new String[2];
        try {
            JsonElement root = JsonParser.parseString(data);
            if (!root.isJsonObject()) {
                return parts;
            }
            JsonArray choices = root.getAsJsonObject().getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return parts;
            }
            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
            if (delta == null) {
                return parts;
            }
            if (delta.has("content") && !delta.get("content").isJsonNull()) {
                parts[0] = delta.get("content").getAsString();
            }
            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                parts[1] = delta.get("reasoning_content").getAsString();
            }
        } catch (Exception ignored) {
            // A malformed chunk is skipped, not fatal to the stream.
        }
        return parts;
    }

    /**
     * System DNS first. When the carrier's resolver fails outright (the
     * classic flaky-network "Unable to resolve host"), names are resolved
     * over HTTPS instead, so a send survives a broken local resolver.
     */
    private static final class ResilientDns implements Dns {

        private final Dns fallback = buildFallback();

        private static Dns buildFallback() {
            try {
                return new DnsOverHttps.Builder()
                        .client(new OkHttpClient())
                        .url(HttpUrl.get("https://dns.google/dns-query"))
                        .bootstrapDnsHosts(
                                InetAddress.getByName("8.8.8.8"),
                                InetAddress.getByName("8.8.4.4"))
                        .build();
            } catch (UnknownHostException impossible) {
                // Numeric addresses never need resolving; unreachable.
                return Dns.SYSTEM;
            }
        }

        @NonNull
        @Override
        public List<InetAddress> lookup(@NonNull String hostname)
                throws UnknownHostException {
            try {
                return Dns.SYSTEM.lookup(hostname);
            } catch (UnknownHostException systemFailed) {
                return fallback.lookup(hostname);
            }
        }
    }

    /** One proposed tool invocation out of an agent turn. */
    public static final class ToolCallData {
        public String id = "";
        public String name = "";
        /** The arguments, as a JSON object string. */
        public String arguments = "{}";
    }

    /** What the model sent back from one agent turn. */
    public static final class AgentReply {
        public String content = "";
        /** The model's reasoning, split out — never part of the answer. */
        public String thinking = "";
        public final List<ToolCallData> toolCalls = new ArrayList<>();
        /** The assistant message verbatim, to echo back next turn. */
        public JsonObject rawMessage;
    }

    /** An agent request that failed before or at the provider. */
    public static final class AgentRequestException extends Exception {
        public final AiError error;

        AgentRequestException(AiError error) {
            super(error.toString());
            this.error = error;
        }
    }

    /**
     * One non-streaming agent turn. When {@code tools} is non-empty the
     * model may answer with tool calls instead of (or besides) text.
     * Blocking: call off the main thread. {@link #cancel()} interrupts it.
     */
    public AgentReply agentTurnSync(String baseUrl, String apiKey, String model,
                                    List<JsonObject> messages, JsonArray tools)
            throws AgentRequestException {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        payload.addProperty("stream", false);
        JsonArray messageArray = new JsonArray();
        for (JsonObject message : messages) {
            messageArray.add(message);
        }
        payload.add("messages", messageArray);
        if (tools != null && tools.size() > 0) {
            payload.add("tools", tools);
            payload.addProperty("tool_choice", "auto");
        }
        Request request = new Request.Builder()
                .url(normalize(baseUrl) + "chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept-Encoding", "identity")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        Call call = client.newCall(request);
        activeCall = call;
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new AgentRequestException(classify(response.code(), text));
            }
            return parseAgentReply(text);
        } catch (IOException e) {
            if (call.isCanceled()) {
                throw new AgentRequestException(new AiError(
                        ErrorKind.NETWORK, 0, "cancelled"));
            }
            throw new AgentRequestException(new AiError(
                    ErrorKind.NETWORK, 0, String.valueOf(e.getMessage())));
        }
    }

    private static AgentReply parseAgentReply(String text) throws AgentRequestException {
        AgentReply reply = new AgentReply();
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                throw new AgentRequestException(new AiError(ErrorKind.EMPTY, 200,
                        "The provider returned an empty reply."));
            }
            JsonObject message = choices.get(0).getAsJsonObject()
                    .getAsJsonObject("message");
            reply.rawMessage = message;
            if (message.has("content") && !message.get("content").isJsonNull()) {
                reply.content = message.get("content").getAsString();
            }
            if (message.has("reasoning_content")
                    && !message.get("reasoning_content").isJsonNull()) {
                reply.thinking = message.get("reasoning_content").getAsString();
            }
            // Some models (Gemma among them) inline their reasoning in the
            // answer text inside <thought> tags. Split it out here, once,
            // so no caller can ever show it as part of the answer.
            StringBuilder thinking = new StringBuilder(reply.thinking);
            reply.content = extractThought(reply.content, thinking);
            reply.thinking = thinking.toString().trim();
            JsonArray calls = message.getAsJsonArray("tool_calls");
            if (calls != null) {
                for (JsonElement element : calls) {
                    JsonObject call = element.getAsJsonObject();
                    ToolCallData data = new ToolCallData();
                    if (call.has("id")) {
                        data.id = call.get("id").getAsString();
                    }
                    JsonObject function = call.getAsJsonObject("function");
                    if (function != null) {
                        if (function.has("name")) {
                            data.name = function.get("name").getAsString();
                        }
                        if (function.has("arguments")
                                && !function.get("arguments").isJsonNull()) {
                            data.arguments = function.get("arguments").getAsString();
                        }
                    }
                    reply.toolCalls.add(data);
                }
            }
        } catch (AgentRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentRequestException(new AiError(ErrorKind.EMPTY, 200,
                    "The provider returned an unreadable reply."));
        }
        return reply;
    }

    /**
     * Moves {@code <thought>}/{@code <thinking>} blocks out of an answer
     * and into {@code thinking}, returning the answer that remains.
     */
    private static String extractThought(String content, StringBuilder thinking) {
        if (content == null) {
            return "";
        }
        String result = content;
        for (String tag : new String[]{"thought", "thinking"}) {
            String open = "<" + tag + ">";
            String close = "</" + tag + ">";
            while (true) {
                String lower = result.toLowerCase(java.util.Locale.ROOT);
                int start = lower.indexOf(open);
                if (start < 0) {
                    break;
                }
                int end = lower.indexOf(close, start);
                if (end < 0) {
                    // Unclosed tag: the rest is reasoning, not answer.
                    appendThought(thinking, result.substring(start + open.length()));
                    result = result.substring(0, start);
                    break;
                }
                appendThought(thinking,
                        result.substring(start + open.length(), end));
                result = result.substring(0, start)
                        + result.substring(end + close.length());
            }
        }
        return result.trim();
    }

    private static void appendThought(StringBuilder thinking, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (thinking.length() > 0) {
            thinking.append("\n");
        }
        thinking.append(text.trim());
    }

    /** Lists the models a key may call: {@code GET <base>/models}. */
    public void fetchModels(String baseUrl, String apiKey, ModelsListener listener) {
        Request request = new Request.Builder()
                .url(normalize(baseUrl) + "models")
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                postModelsError(listener, new AiError(ErrorKind.NETWORK, 0, String.valueOf(e.getMessage())));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    String text = body == null ? "" : body.string();
                    if (!response.isSuccessful()) {
                        postModelsError(listener, classify(response.code(), text));
                        return;
                    }
                    List<String> models = new ArrayList<>();
                    JsonElement root = JsonParser.parseString(text);
                    JsonArray data = root.getAsJsonObject().getAsJsonArray("data");
                    if (data != null) {
                        for (JsonElement element : data) {
                            JsonObject object = element.getAsJsonObject();
                            if (object.has("id")) {
                                models.add(object.get("id").getAsString());
                            }
                        }
                    }
                    Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
                    mainHandler.post(() -> listener.onModels(models));
                } catch (Exception e) {
                    postModelsError(listener, new AiError(ErrorKind.HTTP, response.code(),
                            String.valueOf(e.getMessage())));
                }
            }
        });
    }

    /**
     * OpenRouter reports a key's usage and remaining credit at
     * {@code GET /auth/key}; no other preset offers this, so callers only
     * invoke it for OpenRouter.
     */
    public void fetchOpenRouterKeyInfo(String apiKey, KeyInfoListener listener) {
        Request request = new Request.Builder()
                .url("https://openrouter.ai/api/v1/auth/key")
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> listener.onError(
                        new AiError(ErrorKind.NETWORK, 0, String.valueOf(e.getMessage()))));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (ResponseBody body = response.body()) {
                    String text = body == null ? "" : body.string();
                    if (!response.isSuccessful()) {
                        AiError error = classify(response.code(), text);
                        mainHandler.post(() -> listener.onError(error));
                        return;
                    }
                    JsonObject data = JsonParser.parseString(text).getAsJsonObject()
                            .getAsJsonObject("data");
                    String summary;
                    if (data == null) {
                        summary = "";
                    } else if (data.has("is_free_tier") && data.get("is_free_tier").getAsBoolean()) {
                        summary = "Free-tier key. Usage so far: " + number(data, "usage") + ".";
                    } else {
                        summary = "Credit used: " + number(data, "usage")
                                + " of " + number(data, "limit")
                                + " — remaining: " + number(data, "limit_remaining") + ".";
                    }
                    mainHandler.post(() -> listener.onInfo(summary));
                } catch (Exception e) {
                    mainHandler.post(() -> listener.onError(
                            new AiError(ErrorKind.HTTP, response.code(), String.valueOf(e.getMessage()))));
                }
            }
        });
    }

    private static String number(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString() : "–";
    }

    /** Cancels the in-flight chat, if any. */
    public void cancel() {
        Call call = activeCall;
        if (call != null) {
            call.cancel();
        }
    }

    private void postError(ChatListener listener, AiError error) {
        mainHandler.post(() -> listener.onError(error));
    }

    private void postModelsError(ModelsListener listener, AiError error) {
        mainHandler.post(() -> listener.onError(error));
    }

    /**
     * Builds the OpenAI-compatible {@code content} value for a user message:
     * a plain string when text-only, or a multimodal {@code [text, image_url...]}
     * array when {@code includeImageBytes} is true and readable image files exist.
     */
    public static JsonElement buildUserContent(ChatMessage message,
                                               boolean includeImageBytes) {
        String rawText = message == null || message.text == null
                ? "" : message.text.trim();
        if (message == null || !message.hasAttachments()) {
            return new JsonPrimitive(rawText);
        }
        StringBuilder caption = new StringBuilder(rawText);
        int idx = 1;
        for (ChatMessage.Attachment att : message.attachments) {
            if (att == null) {
                continue;
            }
            if (caption.length() > 0) {
                caption.append("\n");
            }
            caption.append("[Attached chat image ")
                    .append(idx++)
                    .append(": id=\"")
                    .append(att.id == null ? "" : att.id)
                    .append("\"");
            if (att.width > 0 && att.height > 0) {
                caption.append(", ")
                        .append(att.width)
                        .append("x")
                        .append(att.height);
            }
            caption.append("]");
        }
        if (!includeImageBytes) {
            return new JsonPrimitive(caption.toString());
        }
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", caption.length() == 0
                ? "Inspect the attached image(s)." : caption.toString());
        parts.add(textPart);
        int addedImages = 0;
        for (ChatMessage.Attachment att : message.attachments) {
            if (att == null || att.path == null || att.path.isEmpty()) {
                continue;
            }
            String dataUrl = AgentMedia.toVisionDataUrl(att.path);
            if (dataUrl.isEmpty()) {
                continue;
            }
            JsonObject imgPart = new JsonObject();
            imgPart.addProperty("type", "image_url");
            JsonObject urlObj = new JsonObject();
            urlObj.addProperty("url", dataUrl);
            imgPart.add("image_url", urlObj);
            parts.add(imgPart);
            addedImages++;
        }
        if (addedImages == 0) {
            return new JsonPrimitive(caption.toString());
        }
        return parts;
    }

    private static final String PREVIEW_INSPECTION_PREFIX =
            "[System visual render of Sketchware screen ";

    /**
     * Builds a transient multimodal user message carrying a rendered
     * {@code preview_screen} PNG so vision models can visually verify their
     * built layout against the user's screenshot or design system.
     */
    public static JsonObject buildPreviewInspectionMessage(String screenName,
                                                           String previewPath) {
        String dataUrl = AgentMedia.toVisionDataUrl(previewPath);
        if (dataUrl.isEmpty()) {
            return null;
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text",
                PREVIEW_INSPECTION_PREFIX + "\""
                        + screenName
                        + "\" produced by preview_screen. Inspect alignment, "
                        + "spacing, colours, contrast, text clipping, and "
                        + "match against the design system or reference "
                        + "screenshot, then continue.]");
        parts.add(textPart);
        JsonObject imgPart = new JsonObject();
        imgPart.addProperty("type", "image_url");
        JsonObject urlObj = new JsonObject();
        urlObj.addProperty("url", dataUrl);
        imgPart.add("image_url", urlObj);
        parts.add(imgPart);
        entry.add("content", parts);
        return entry;
    }

    /** True when {@code message} is a transient {@code preview_screen} image turn. */
    public static boolean isPreviewInspectionMessage(JsonObject message) {
        if (message == null || !message.has("role")
                || !"user".equals(message.get("role").getAsString())
                || !message.has("content")
                || !message.get("content").isJsonArray()) {
            return false;
        }
        JsonArray content = message.getAsJsonArray("content");
        if (content.size() == 0 || !content.get(0).isJsonObject()) {
            return false;
        }
        JsonObject first = content.get(0).getAsJsonObject();
        return first.has("text") && first.get("text").isJsonPrimitive()
                && first.get("text").getAsString()
                        .startsWith(PREVIEW_INSPECTION_PREFIX);
    }

    private static final java.util.Set<String> KNOWN_TEXT_ONLY_MODELS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Remembers that {@code model} rejected multimodal {@code image_url} input. */
    public static void markModelVisionUnsupported(String model) {
        if (model != null && !model.trim().isEmpty()) {
            KNOWN_TEXT_ONLY_MODELS.add(model.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** True if {@code model} previously rejected multimodal {@code image_url} input. */
    public static boolean isModelKnownTextOnly(String model) {
        return model != null
                && KNOWN_TEXT_ONLY_MODELS.contains(
                        model.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** True if {@code messages} currently contains any {@code image_url} content array. */
    public static boolean hasMultimodalImageParts(java.util.List<JsonObject> messages) {
        if (messages == null) {
            return false;
        }
        for (JsonObject msg : messages) {
            if (msg == null || !msg.has("content") || !msg.get("content").isJsonArray()) {
                continue;
            }
            for (com.google.gson.JsonElement el : msg.getAsJsonArray("content")) {
                if (el != null && el.isJsonObject()
                        && el.getAsJsonObject().has("image_url")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Strips {@code image_url} parts so text-only models receive valid plain-string
     * {@code content} while preserving {@code [Attached chat image ... id="att_..."]}
     * metadata on disk for {@code import_chat_image} and {@code set_app_icon}.
     */
    public static boolean downgradeMultimodalMessagesToText(
            java.util.List<JsonObject> messages, String model) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        boolean changed = messages.removeIf(AiChatService::isPreviewInspectionMessage);
        for (JsonObject msg : messages) {
            if (msg == null || !msg.has("content") || !msg.get("content").isJsonArray()) {
                continue;
            }
            JsonArray arr = msg.getAsJsonArray("content");
            boolean hadImage = false;
            StringBuilder textOut = new StringBuilder();
            for (com.google.gson.JsonElement el : arr) {
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject part = el.getAsJsonObject();
                if (part.has("image_url")) {
                    hadImage = true;
                } else if (part.has("text") && part.get("text").isJsonPrimitive()) {
                    if (textOut.length() > 0) {
                        textOut.append("\n");
                    }
                    textOut.append(part.get("text").getAsString());
                }
            }
            if (hadImage) {
                if (textOut.length() > 0) {
                    textOut.append("\n");
                }
                textOut.append("[System note: The selected model (")
                        .append(model == null ? "text-only" : model)
                        .append(") rejected inline image_url pixels, so pixel ")
                        .append("vision is unavailable in this turn. The attached ")
                        .append("image file(s) ARE saved on disk by id and work ")
                        .append("normally with import_chat_image and set_app_icon. ")
                        .append("Tell the user if they want visual screenshot ")
                        .append("inspection they can switch to a vision model.]");
                msg.addProperty("content", textOut.toString());
                changed = true;
            }
        }
        return changed;
    }

    /** True when the provider rejected multimodal {@code image_url} input. */
    public static boolean isVisionUnsupported(AiError error) {
        if (error == null || error.message == null) {
            return false;
        }
        String m = error.message.toLowerCase(java.util.Locale.ROOT);
        boolean mentionsVision = m.contains("image")
                || m.contains("vision")
                || m.contains("multimodal")
                || m.contains("image_url")
                || m.contains("modality")
                || m.contains("content must be a string");
        boolean mentionsRejection = error.kind == ErrorKind.UNSUPPORTED
                || m.contains("not support")
                || m.contains("unsupported")
                || m.contains("no endpoints")
                || m.contains("only supported")
                || m.contains("cannot")
                || m.contains("invalid content")
                || m.contains("expected string")
                || m.contains("must be a string");
        return mentionsVision && mentionsRejection;
    }

    /** Turns an HTTP failure into the kind the UI should act on. */
    static AiError classify(int httpCode, String body) {
        String message = extractMessage(body);
        if (message.length() > 240) {
            message = message.substring(0, 240) + "…";
        }
        ErrorKind kind;
        String lowered = message.toLowerCase(java.util.Locale.ROOT);
        boolean visionUnsupported = (lowered.contains("image")
                || lowered.contains("vision")
                || lowered.contains("multimodal")
                || lowered.contains("image_url")
                || lowered.contains("modality"))
                && (lowered.contains("not support")
                        || lowered.contains("unsupported")
                        || lowered.contains("no endpoints")
                        || lowered.contains("only supported")
                        || lowered.contains("invalid content")
                        || lowered.contains("expected string"));
        if (httpCode == 401 || httpCode == 403) {
            kind = ErrorKind.AUTH;
        } else if (httpCode == 402 || httpCode == 429
                || lowered.contains("quota") || lowered.contains("insufficient")
                || lowered.contains("resource_exhausted") || lowered.contains("rate limit")) {
            kind = ErrorKind.QUOTA;
        } else if (visionUnsupported) {
            kind = ErrorKind.UNSUPPORTED;
        } else if (httpCode == 404 || lowered.contains("no longer available")
                || (lowered.contains("not found") && lowered.contains("model"))) {
            kind = ErrorKind.MODEL_GONE;
        } else if (lowered.contains("not enabled")
                || lowered.contains("does not support")
                || lowered.contains("not supported")) {
            kind = ErrorKind.UNSUPPORTED;
        } else {
            kind = ErrorKind.HTTP;
        }
        return new AiError(kind, httpCode, message);
    }

    /**
     * Pulls the human sentence out of a provider error body, whatever shape
     * it arrived in: {@code {"error": {"message": ...}}}, the array-wrapped
     * {@code [{"error": ...}]} some Google endpoints return, or plain text.
     * Raw JSON never reaches the user.
     */
    private static String extractMessage(String body) {
        if (body == null) {
            return "";
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonObject object;
            if (root.isJsonArray() && root.getAsJsonArray().size() > 0) {
                object = root.getAsJsonArray().get(0).getAsJsonObject();
            } else {
                object = root.getAsJsonObject();
            }
            JsonObject error = object.getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return error.get("message").getAsString().trim();
            }
            if (object.has("message")) {
                return object.get("message").getAsString().trim();
            }
        } catch (Exception ignored) {
            // Not JSON at all: fall through to the plain-text handling.
        }
        String text = body.trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            return "";
        }
        return text;
    }

    private static String normalize(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
