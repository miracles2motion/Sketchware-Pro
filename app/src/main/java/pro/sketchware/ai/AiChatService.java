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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
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
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** How many past messages are sent with each request. */
    private static final int MAX_HISTORY_MESSAGES = 40;

    private static final String SYSTEM_PROMPT =
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
            String text = deltaText(data);
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

    /** Extracts {@code choices[0].delta.content} from one stream chunk. */
    private static String deltaText(String data) {
        try {
            JsonElement root = JsonParser.parseString(data);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonArray choices = root.getAsJsonObject().getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return null;
            }
            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
            if (delta == null || !delta.has("content") || delta.get("content").isJsonNull()) {
                return null;
            }
            return delta.get("content").getAsString();
        } catch (Exception e) {
            return null;
        }
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
        String message = "";
        try {
            JsonElement root = JsonParser.parseString(body);
            JsonObject error = root.getAsJsonObject().getAsJsonObject("error");
            if (error != null && error.has("message")) {
                message = error.get("message").getAsString();
            }
        } catch (Exception ignored) {
            message = body == null ? "" : body.trim();
        }
        if (message.length() > 240) {
            message = message.substring(0, 240) + "…";
        }
        ErrorKind kind;
        String lowered = message.toLowerCase();
        if (httpCode == 401 || httpCode == 403) {
            kind = ErrorKind.AUTH;
        } else if (httpCode == 402 || httpCode == 429
                || lowered.contains("quota") || lowered.contains("insufficient")
                || lowered.contains("resource_exhausted") || lowered.contains("rate limit")) {
            kind = ErrorKind.QUOTA;
        } else {
            kind = ErrorKind.HTTP;
        }
        return new AiError(kind, httpCode, message);
    }

    private static String normalize(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }
}
