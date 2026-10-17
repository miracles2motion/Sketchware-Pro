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
