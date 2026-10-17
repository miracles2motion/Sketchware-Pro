package pro.sketchware.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        for (int i = from; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            if (message.role == ChatMessage.Role.SYSTEM || message.text.trim().isEmpty()) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.isFromUser() ? "user" : "assistant");
            entry.addProperty("content", message.text.trim());
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

    /** Turns an HTTP failure into the kind the UI should act on. */
    static AiError classify(int httpCode, String body) {
        String message = extractMessage(body);
        if (message.length() > 240) {
            message = message.substring(0, 240) + "…";
        }
        ErrorKind kind;
        String lowered = message.toLowerCase(java.util.Locale.ROOT);
        if (httpCode == 401 || httpCode == 403) {
            kind = ErrorKind.AUTH;
        } else if (httpCode == 402 || httpCode == 429
                || lowered.contains("quota") || lowered.contains("insufficient")
                || lowered.contains("resource_exhausted") || lowered.contains("rate limit")) {
            kind = ErrorKind.QUOTA;
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
