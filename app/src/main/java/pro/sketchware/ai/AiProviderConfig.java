package pro.sketchware.ai;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent Mode's provider configuration: which service, which model, and the
 * user's API keys.
 *
 * <p>Non-secret settings (provider, base URL, chosen model) live in the
 * existing {@code .sketchware/ai/agent-config.json} so they stay inspectable.
 * API keys never do: they are stored in the app's private internal storage,
 * where other apps cannot read them.
 */
public class AiProviderConfig {

    /** Per-provider, non-secret choices. */
    public static class Settings {
        public String baseUrl = "";
        public String model = "";
        /** Models the provider last reported for this key. */
        public java.util.List<String> models = new java.util.ArrayList<>();
        /** True when the pickers should also show non-chat models. */
        public boolean showAllModels;
    }

    /**
     * Words that mark a model as not built for text chat: speech, image
     * generation, live audio, managed agents, embeddings. Providers hand
     * back every model a key may call; the chat only offers the ones that
     * can hold a conversation. Matched by capability, never by model name,
     * so new chat models pass untouched and new exotic ones exclude
     * themselves.
     */
    private static final String[] NON_CHAT_MARKERS = {
            "tts", "image", "audio", "antigravity", "aqa", "embed",
            "deep-research", "live"
    };

    /** True when this model id belongs to a text-chat model. */
    public static boolean isChatCapable(String modelId) {
        String id = modelId.toLowerCase(java.util.Locale.ROOT);
        for (String marker : NON_CHAT_MARKERS) {
            if (id.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The models a picker should show: chat-capable ones, or everything
     * when the user asked for the full list. Never returns an empty list
     * while the provider reported models at all.
     */
    public static java.util.List<String> pickerModels(
            java.util.List<String> fetched, boolean showAll) {
        java.util.List<String> visible = new java.util.ArrayList<>();
        for (String model : fetched) {
            if (showAll || isChatCapable(model)) {
                visible.add(model);
            }
        }
        return visible.isEmpty()
                ? new java.util.ArrayList<>(fetched) : visible;
    }

    /** Shape of {@code agent-config.json}. Old model-registry fields are ignored. */
    private static class Stored {
        String activeProvider = AiProvider.GEMINI.name();
        Map<String, Settings> providers = new HashMap<>();
    }

    private static final String KEYS_FILE_NAME = "agent_keys.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AiProvider active = AiProvider.GEMINI;
    private final Map<AiProvider, Settings> settings = new EnumMap<>(AiProvider.class);
    private final Map<String, String> keys = new HashMap<>();

    private AiProviderConfig() {
        for (AiProvider provider : AiProvider.values()) {
            Settings fresh = new Settings();
            fresh.baseUrl = provider.defaultBaseUrl;
            settings.put(provider, fresh);
        }
    }

    /** Reads the configuration; missing or corrupt files yield defaults. */
    public static AiProviderConfig load(Context context) {
        AiProviderConfig config = new AiProviderConfig();

        File file = AiPaths.configFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Stored stored = GSON.fromJson(reader, Stored.class);
                if (stored != null) {
                    config.active = AiProvider.fromId(stored.activeProvider);
                    if (stored.providers != null) {
                        for (Map.Entry<String, Settings> entry : stored.providers.entrySet()) {
                            Settings target = config.settings.get(AiProvider.fromId(entry.getKey()));
                            Settings source = entry.getValue();
                            if (target != null && source != null) {
                                if (source.baseUrl != null && !source.baseUrl.isEmpty()) {
                                    target.baseUrl = source.baseUrl;
                                }
                                if (source.model != null) {
                                    target.model = source.model;
                                }
                                if (source.models != null) {
                                    target.models = source.models;
                                }
                            }
                        }
                    }
                }
            } catch (IOException | JsonSyntaxException ignored) {
                // Defaults are fine; the user can re-enter the settings.
            }
        }

        File keysFile = new File(context.getFilesDir(), KEYS_FILE_NAME);
        if (keysFile.exists()) {
            try (FileReader reader = new FileReader(keysFile)) {
                Map<String, String> stored = GSON.fromJson(reader, Map.class);
                if (stored != null) {
                    config.keys.putAll(stored);
                }
            } catch (IOException | JsonSyntaxException ignored) {
                // Keys can be re-entered in the settings.
            }
        }
        return config;
    }

    /** Persists settings and keys. */
    public void save(Context context) {
        Stored stored = new Stored();
        stored.activeProvider = active.name();
        for (Map.Entry<AiProvider, Settings> entry : settings.entrySet()) {
            stored.providers.put(entry.getKey().name(), entry.getValue());
        }
        try (FileWriter writer = new FileWriter(AiPaths.configFile())) {
            GSON.toJson(stored, writer);
        } catch (IOException ignored) {
            // Losing settings only means re-entering them.
        }
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), KEYS_FILE_NAME))) {
            GSON.toJson(keys, writer);
        } catch (IOException ignored) {
            // Losing keys only means re-entering them.
        }
    }

    public AiProvider activeProvider() {
        return active;
    }

    public void setActiveProvider(AiProvider provider) {
        active = provider;
    }

    public Settings settings(AiProvider provider) {
        return settings.get(provider);
    }

    public Settings activeSettings() {
        return settings.get(active);
    }

    public String apiKey(AiProvider provider) {
        String key = keys.get(provider.name());
        return key == null ? "" : key;
    }

    public void setApiKey(AiProvider provider, String key) {
        if (key == null || key.trim().isEmpty()) {
            keys.remove(provider.name());
        } else {
            keys.put(provider.name(), key.trim());
        }
    }

    /** True when the active provider has everything a chat needs. */
    public boolean isConfigured() {
        Settings current = activeSettings();
        return current != null
                && !current.baseUrl.trim().isEmpty()
                && !current.model.trim().isEmpty()
                && !apiKey(active).isEmpty();
    }
}
