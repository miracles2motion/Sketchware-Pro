package pro.sketchware.ai;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

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
        /** Index of the key in use, within this provider's key list. */
        public int activeKey;
    }

    /**
     * One API key of a provider. Keys live in per-provider lists, so a key
     * can never wander into another provider's list: a Gemini key is only
     * ever read while Gemini is selected.
     */
    public static class KeyEntry {
        /** User-facing name, e.g. "Key 1" or "Work project". */
        public String label = "";
        public String key = "";
        /** When the key may be used again after a quota hit; 0 = ready. */
        public long exhaustedUntil;

        /** True while the key is resting after a quota hit. */
        public boolean isExhausted(long now) {
            return exhaustedUntil > now;
        }
    }

    /** How a key may be shown anywhere: never more than its last 4 chars. */
    public static String mask(KeyEntry entry) {
        String key = entry.key == null ? "" : entry.key;
        return "••••" + key.substring(Math.max(0, key.length() - 4));
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
    private final Map<AiProvider, java.util.List<KeyEntry>> keyLists =
            new EnumMap<>(AiProvider.class);

    private AiProviderConfig() {
        for (AiProvider provider : AiProvider.values()) {
            Settings fresh = new Settings();
            fresh.baseUrl = provider.defaultBaseUrl;
            settings.put(provider, fresh);
            keyLists.put(provider, new java.util.ArrayList<>());
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
                                target.showAllModels = source.showAllModels;
                                target.activeKey = source.activeKey;
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
            try {
                String text = new String(java.nio.file.Files.readAllBytes(
                        keysFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                JsonElement root = JsonParser.parseString(text);
                if (root.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry
                            : root.getAsJsonObject().entrySet()) {
                        java.util.List<KeyEntry> target =
                                config.keyLists.get(AiProvider.fromId(entry.getKey()));
                        if (target == null) {
                            continue;
                        }
                        JsonElement value = entry.getValue();
                        if (value.isJsonArray()) {
                            java.util.List<KeyEntry> parsed = GSON.fromJson(value,
                                    new TypeToken<java.util.List<KeyEntry>>() {}.getType());
                            if (parsed != null) {
                                target.addAll(parsed);
                            }
                        } else if (value.isJsonPrimitive()
                                && !value.getAsString().trim().isEmpty()) {
                            // Vaults written before key lists: the one key
                            // becomes the first entry, untouched.
                            KeyEntry legacy = new KeyEntry();
                            legacy.label = "Key 1";
                            legacy.key = value.getAsString().trim();
                            target.add(legacy);
                        }
                    }
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
        Map<String, java.util.List<KeyEntry>> vault = new HashMap<>();
        for (Map.Entry<AiProvider, java.util.List<KeyEntry>> entry : keyLists.entrySet()) {
            vault.put(entry.getKey().name(), entry.getValue());
        }
        try (FileWriter writer = new FileWriter(new File(context.getFilesDir(), KEYS_FILE_NAME))) {
            GSON.toJson(vault, writer);
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

    /** The key currently in use for a provider, or "" when it has none. */
    public String apiKey(AiProvider provider) {
        java.util.List<KeyEntry> list = keyLists.get(provider);
        if (list == null || list.isEmpty()) {
            return "";
        }
        return list.get(activeKeyIndex(provider)).key;
    }

    /** All keys of a provider, in the order they were added. */
    public java.util.List<KeyEntry> keys(AiProvider provider) {
        java.util.List<KeyEntry> list = keyLists.get(provider);
        return list == null ? new java.util.ArrayList<>() : list;
    }

    /** Which of the provider's keys is in use, clamped to reality. */
    public int activeKeyIndex(AiProvider provider) {
        java.util.List<KeyEntry> list = keyLists.get(provider);
        int size = list == null ? 0 : list.size();
        if (size == 0) {
            return 0;
        }
        return Math.min(Math.max(0, settings(provider).activeKey), size - 1);
    }

    /** Puts one of the provider's keys in use. */
    public void setActiveKey(AiProvider provider, int index) {
        int size = keys(provider).size();
        settings(provider).activeKey = size == 0
                ? 0 : Math.min(Math.max(0, index), size - 1);
    }

    /**
     * Adds a key to a provider's list and puts it in use. Adding a key the
     * provider already has just selects it again. Returns the entry, or
     * null for blank input.
     */
    public KeyEntry addKey(AiProvider provider, String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (key.isEmpty()) {
            return null;
        }
        java.util.List<KeyEntry> list = keyLists.get(provider);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key.equals(key)) {
                settings(provider).activeKey = i;
                return list.get(i);
            }
        }
        KeyEntry entry = new KeyEntry();
        entry.key = key;
        entry.label = nextLabel(list);
        list.add(entry);
        settings(provider).activeKey = list.size() - 1;
        return entry;
    }

    private static String nextLabel(java.util.List<KeyEntry> list) {
        for (int n = 1; ; n++) {
            String candidate = "Key " + n;
            boolean taken = false;
            for (KeyEntry entry : list) {
                if (candidate.equals(entry.label)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
    }

    /** Removes a key; the key in use is re-clamped to its neighbour. */
    public void removeKey(AiProvider provider, int index) {
        java.util.List<KeyEntry> list = keyLists.get(provider);
        if (list == null || index < 0 || index >= list.size()) {
            return;
        }
        list.remove(index);
        Settings current = settings(provider);
        if (current.activeKey >= list.size()) {
            current.activeKey = Math.max(0, list.size() - 1);
        }
    }

    /** Rests the key in use after a quota hit, until the given moment. */
    public void markKeyExhausted(AiProvider provider, long untilMillis) {
        java.util.List<KeyEntry> list = keyLists.get(provider);
        if (list == null || list.isEmpty()) {
            return;
        }
        list.get(activeKeyIndex(provider)).exhaustedUntil = untilMillis;
    }

    /** Replaces a provider's whole list with a single key. */
    public void setApiKey(AiProvider provider, String key) {
        java.util.List<KeyEntry> list = keyLists.get(provider);
        list.clear();
        settings(provider).activeKey = 0;
        if (key != null && !key.trim().isEmpty()) {
            KeyEntry entry = new KeyEntry();
            entry.label = "Key 1";
            entry.key = key.trim();
            list.add(entry);
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
