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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The model catalog: what each model of each provider can actually do,
 * learned from the provider's own /models metadata, one-shot active
 * probes, and live traffic — and remembered across restarts so the
 * pickers can label and filter honestly.
 *
 * <p>Per model it keeps: tool-calling support, vision input, context
 * size, free/price tier, last measured latency, and whether the user
 * hid it permanently. Non-chat models (TTS, image, embed, live) are
 * filtered out of pickers by default, and models without tool support
 * are labelled “Chat only · Can't edit project” because Agent Mode's
 * building blocks are tools.
 */
public final class ModelCatalog {

    /** Everything we know about one model, persisted. */
    public static class ModelInfo {
        public String id = "";
        /** null = unknown; known tools capability beats name guessing. */
        public Boolean tools;
        /** null = unknown. */
        public Boolean vision;
        /** 0 = unknown. */
        public long contextLength;
        public boolean free;
        /** Last successful probe latency in ms; 0 = never probed. */
        public long latencyMs;
        /** Hidden permanently by the user; pickers skip it. */
        public boolean hidden;
        /** Short last probe/error note, e.g. "404 retired", "503". */
        public String lastError = "";
        public long updatedAt;
    }

    private static final Gson PRETTY =
            new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static Map<String, Map<String, ModelInfo>> cache;

    private ModelCatalog() {
    }

    private static File file() {
        return new File(AiPaths.agentRoot(), "model-catalog.json");
    }

    private static Map<String, Map<String, ModelInfo>> load() {
        synchronized (LOCK) {
            if (cache != null) {
                return cache;
            }
            Map<String, Map<String, ModelInfo>> read = new LinkedHashMap<>();
            File f = file();
            if (f.exists()) {
                try (FileReader reader = new FileReader(f)) {
                    JsonElement root = JsonParser.parseReader(reader);
                    if (root.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> providerEntry
                                : root.getAsJsonObject().entrySet()) {
                            if (!providerEntry.getValue().isJsonObject()) {
                                continue;
                            }
                            Map<String, ModelInfo> models =
                                    new LinkedHashMap<>();
                            for (Map.Entry<String, JsonElement> modelEntry
                                    : providerEntry.getValue()
                                    .getAsJsonObject().entrySet()) {
                                if (!modelEntry.getValue().isJsonObject()) {
                                    continue;
                                }
                                try {
                                    ModelInfo info = PRETTY.fromJson(
                                            modelEntry.getValue(),
                                            ModelInfo.class);
                                    if (info != null) {
                                        info.id = modelEntry.getKey();
                                        models.put(modelEntry.getKey(), info);
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                            read.put(providerEntry.getKey(), models);
                        }
                    }
                } catch (Exception ignored) {
                    // A corrupt catalog just starts empty again.
                }
            }
            cache = read;
            return cache;
        }
    }

    private static void persist() {
        synchronized (LOCK) {
            try (FileWriter writer = new FileWriter(file())) {
                PRETTY.toJson(cache, writer);
            } catch (IOException ignored) {
                // Losing labels only means they are relearned.
            }
        }
    }

    /** The catalog entry for one model, or null when unknown. */
    public static ModelInfo info(String provider, String modelId) {
        Map<String, Map<String, ModelInfo>> all = load();
        Map<String, ModelInfo> models = all.get(provider);
        return models == null ? null : models.get(modelId);
    }

    /** Merges a fresh probe/live result and persists it. */
    public static void update(String provider, String modelId,
                              java.util.function.Consumer<ModelInfo> edit) {
        synchronized (LOCK) {
            Map<String, Map<String, ModelInfo>> all = load();
            Map<String, ModelInfo> models = all.computeIfAbsent(provider,
                    k -> new LinkedHashMap<>());
            ModelInfo info = models.computeIfAbsent(modelId, k -> {
                ModelInfo fresh = new ModelInfo();
                fresh.id = modelId;
                return fresh;
            });
            edit.accept(info);
            info.updatedAt = System.currentTimeMillis();
            persist();
        }
    }

    /**
     * Merges a /models response's metadata into the catalog. Only fills
     * blanks — a probe result or a live observation already recorded is
     * never downgraded by a bulk refresh.
     */
    public static void mergeFetched(String provider, List<ModelInfo> fetched) {
        synchronized (LOCK) {
            Map<String, Map<String, ModelInfo>> all = load();
            Map<String, ModelInfo> models = all.computeIfAbsent(provider,
                    k -> new LinkedHashMap<>());
            for (ModelInfo fresh : fetched) {
                if (fresh == null || fresh.id == null || fresh.id.isEmpty()) {
                    continue;
                }
                ModelInfo known = models.get(fresh.id);
                if (known == null) {
                    fresh.updatedAt = System.currentTimeMillis();
                    models.put(fresh.id, fresh);
                    continue;
                }
                if (known.tools == null) {
                    known.tools = fresh.tools;
                }
                if (known.vision == null) {
                    known.vision = fresh.vision;
                }
                if (known.contextLength <= 0 && fresh.contextLength > 0) {
                    known.contextLength = fresh.contextLength;
                }
                known.free = known.free || fresh.free;
                known.updatedAt = System.currentTimeMillis();
            }
            persist();
        }
    }

    // ------------------------------------------------------------------
    // /models response parsing

    /**
     * Extracts ids + metadata from any /models response shape:
     * OpenAI/OpenRouter {@code data[]} (with OpenRouter's
     * {@code architecture}, {@code supported_parameters},
     * {@code context_length}, {@code pricing}), Gemini's
     * {@code models[]} ({@code supportedGenerationMethods},
     * {@code inputTokenLimit}), or a bare {@code []}.
     */
    public static List<ModelInfo> parseModelsResponse(String body) {
        List<ModelInfo> out = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) {
            return out;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(body);
        } catch (Exception e) {
            return out;
        }
        if (!root.isJsonObject()) {
            return out;
        }
        JsonObject object = root.getAsJsonObject();
        JsonArray data = object.has("data") && object.get("data").isJsonArray()
                ? object.getAsJsonArray("data") : null;
        JsonArray gemini = object.has("models")
                && object.get("models").isJsonArray()
                ? object.getAsJsonArray("models") : null;
        if (data != null) {
            for (JsonElement element : data) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject model = element.getAsJsonObject();
                if (!model.has("id") || model.get("id").isJsonNull()) {
                    continue;
                }
                ModelInfo info = new ModelInfo();
                info.id = model.get("id").getAsString();
                if (model.has("supported_parameters")
                        && model.get("supported_parameters").isJsonArray()) {
                    info.tools = jsonArrayOf(model, "supported_parameters")
                            .contains("tools");
                }
                JsonArray modalities = null;
                if (model.has("architecture")
                        && model.get("architecture").isJsonObject()
                        && model.getAsJsonObject("architecture")
                        .has("input_modalities")
                        && model.getAsJsonObject("architecture")
                        .get("input_modalities").isJsonArray()) {
                    modalities = model.getAsJsonObject("architecture")
                            .getAsJsonArray("input_modalities");
                }
                if (modalities != null) {
                    info.vision = modalities.toString().toLowerCase(
                            Locale.ROOT).contains("image");
                }
                if (model.has("context_length")
                        && model.get("context_length").isJsonPrimitive()) {
                    try {
                        info.contextLength =
                                model.get("context_length").getAsLong();
                    } catch (Exception ignored) {
                    }
                }
                if (model.has("pricing")
                        && model.get("pricing").isJsonObject()) {
                    JsonObject pricing =
                            model.getAsJsonObject("pricing");
                    info.free = isZeroPrice(pricing, "prompt")
                            && isZeroPrice(pricing, "completion");
                }
                out.add(info);
            }
        } else if (gemini != null) {
            for (JsonElement element : gemini) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject model = element.getAsJsonObject();
                String name = model.has("name")
                        && !model.get("name").isJsonNull()
                        ? model.get("name").getAsString() : "";
                if (name.startsWith("models/")) {
                    name = name.substring("models/".length());
                }
                if (name.isEmpty()) {
                    continue;
                }
                ModelInfo info = new ModelInfo();
                info.id = name;
                if (model.has("supportedGenerationMethods")
                        && model.get("supportedGenerationMethods")
                        .isJsonArray()) {
                    List<String> methods =
                            stringListOf(model, "supportedGenerationMethods");
                    info.tools = methods.contains("generateContent");
                }
                if (model.has("inputTokenLimit")
                        && model.get("inputTokenLimit").isJsonPrimitive()) {
                    try {
                        info.contextLength =
                                model.get("inputTokenLimit").getAsLong();
                    } catch (Exception ignored) {
                    }
                }
                out.add(info);
            }
        }
        return out;
    }

    private static List<String> jsonArrayOf(JsonObject object, String field) {
        List<String> out = new ArrayList<>();
        JsonElement element = object.get(field);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    out.add(item.getAsString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static List<String> stringListOf(JsonObject object,
                                             String field) {
        return jsonArrayOf(object, field);
    }

    private static boolean isZeroPrice(JsonObject pricing, String field) {
        if (!pricing.has(field)
                || !pricing.get(field).isJsonPrimitive()) {
            return false;
        }
        try {
            return pricing.get(field).getAsDouble() <= 0d;
        } catch (Exception e) {
            String raw = pricing.get(field).getAsString().trim();
            return raw.equals("0") || raw.equals("0.0");
        }
    }

    // ------------------------------------------------------------------
    // Filtering

    /**
     * Words that mark a model as not built for text chat, on top of
     * {@link AiProviderConfig#isChatCapable}: image/video generation,
     * speech, embeddings/rerankers, safety classifiers.
     */
    private static final String[] EXTRA_NON_CHAT_MARKERS = {
            "whisper", "imagen", "flux", "dall-e", "sora", "veo",
            "stable-diffusion", "sdxl", "embed", "rerank", "moderation",
            "guard", "ocr", "aqa", "distil"
    };

    /**
     * The ids a picker shows: chat-capable, not permanently hidden, or
     * everything when the user asked for the full list. Never returns
     * an empty list while the provider reported models at all.
     */
    public static List<String> visibleModels(String provider,
                                             List<String> fetched,
                                             boolean showAll) {
        List<String> chat = AiProviderConfig.pickerModels(fetched, showAll);
        List<String> visible = new ArrayList<>();
        for (String id : chat) {
            if (showAll || !isJunkModel(provider, id)) {
                visible.add(id);
            }
        }
        if (!showAll) {
            visible.removeIf(id -> {
                ModelInfo known = info(provider, id);
                return known != null && known.hidden;
            });
        }
        if (visible.isEmpty() && !fetched.isEmpty()) {
            return new ArrayList<>(fetched);
        }
        return visible;
    }

    /** True when the id is image/speech/embed junk by name. */
    public static boolean isJunkModel(String provider, String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : EXTRA_NON_CHAT_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        ModelInfo known = info(provider, id);
        return known != null && known.hidden;
    }

    /** Hides (or unhides) one model permanently. */
    public static void setHidden(String provider, String modelId,
                                 boolean hidden) {
        update(provider, modelId, info -> info.hidden = hidden);
    }

    /** The provider's permanently hidden model ids, sorted. */
    public static List<String> hiddenModels(String provider) {
        Map<String, Map<String, ModelInfo>> all = load();
        Map<String, ModelInfo> models = all.get(provider);
        List<String> out = new ArrayList<>();
        if (models != null) {
            for (ModelInfo info : models.values()) {
                if (info.hidden) {
                    out.add(info.id);
                }
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ------------------------------------------------------------------
    // Badges

    /** Short context size, e.g. "128K" or "1M"; "" when unknown. */
    public static String contextLabel(long contextLength) {
        if (contextLength <= 0) {
            return "";
        }
        if (contextLength >= 1_000_000) {
            long m = Math.round(contextLength / 1_000_000.0);
            return m + "M";
        }
        if (contextLength >= 1000) {
            long k = contextLength / 1000;
            return k + "K";
        }
        return String.valueOf(contextLength);
    }

    /** Short latency, e.g. "1.3s"; "" when never probed. */
    public static String latencyLabel(long latencyMs) {
        if (latencyMs <= 0) {
            return "";
        }
        if (latencyMs < 1000) {
            return latencyMs + "ms";
        }
        return String.format(Locale.US, "%.1fs", latencyMs / 1000.0);
    }

    /**
     * The one-line capability badge under a model's name:
     * {@code Tools ✓ · Vision · 128K · Free · 1.3s}, or the honest
     * {@code Chat only · Can't edit project} for models without tool
     * support, or {@code Text-only} where vision is known absent.
     */
    public static String badgeText(String provider, String modelId) {
        ModelInfo known = info(provider, modelId);
        if (known == null) {
            boolean tools = AiProviderConfig.isChatCapable(modelId);
            return tools ? "" : "Chat only · Can't edit project";
        }
        List<String> parts = new ArrayList<>();
        boolean toolsKnown = known.tools != null;
        if (toolsKnown && !known.tools) {
            return "Chat only · Can't edit project"
                    + (known.free ? " · Free" : "");
        }
        if (!toolsKnown
                && !AiProviderConfig.isChatCapable(modelId)) {
            return "Chat only · Can't edit project"
                    + (known.free ? " · Free" : "");
        }
        parts.add("Tools ✓");
        if (known.vision != null) {
            parts.add(known.vision ? "Vision" : "Text-only");
        }
        String ctx = contextLabel(known.contextLength);
        if (!ctx.isEmpty()) {
            parts.add(ctx + " ctx");
        }
        if (known.free) {
            parts.add("Free");
        }
        String lat = latencyLabel(known.latencyMs);
        if (!lat.isEmpty()) {
            parts.add(lat);
        }
        if (!known.lastError.isEmpty()) {
            parts.add(known.lastError);
        }
        return String.join(" · ", parts);
    }

    /** True when a probe proved the model dead/retired (404). */
    public static boolean isDead(String provider, String modelId) {
        ModelInfo known = info(provider, modelId);
        return known != null && known.lastError.contains("404");
    }

    // ------------------------------------------------------------------
    // Live-learned facts bucket

    /**
     * Facts learned mid-conversation without a provider context (the
     * chat service only knows the model id) live under this key, so
     * e.g. a model that rejected image pixels stays known text-only
     * after a restart.
     */
    private static final String LIVE_BUCKET = "_live";

    /** Bucket key for provider-less live observations. */
    public static String providerForModel(String modelId) {
        return LIVE_BUCKET;
    }

    /** The live-learned entry for one model id, or null. */
    public static ModelInfo liveInfo(String modelId) {
        return info(LIVE_BUCKET, modelId);
    }

    // ------------------------------------------------------------------
    // ✨ Auto smart model router

    /** Model id that asks the router to choose per turn. */
    public static final String AUTO = "auto";

    /** Which provider a baseUrl belongs to (for catalog keys). */
    public static String providerNameForBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.toLowerCase(Locale.ROOT);
        for (AiProvider provider : AiProvider.values()) {
            String known = provider.defaultBaseUrl == null ? ""
                    : provider.defaultBaseUrl.toLowerCase(Locale.ROOT);
            if (!known.isEmpty() && (url.equals(known)
                    || url.contains(known) || known.contains(url))) {
                return provider.name();
            }
        }
        return "";
    }

    /** Cooldowns after a failure: {@code provider|model → until ms}. */
    private static final Map<String, Long> COOLDOWNS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Puts a model on cooldown after a 503/429/timeout. */
    public static void cooldown(String provider, String modelId,
                                long untilMillis) {
        COOLDOWNS.put(provider + "|" + modelId, untilMillis);
    }

    private static boolean cooling(String provider, String modelId) {
        Long until = COOLDOWNS.get(provider + "|" + modelId);
        return until != null && until > System.currentTimeMillis();
    }

    /**
     * The ✨ Auto choice: among the provider's visible chat models that
     * are not cooling down, dead, or the one that just failed — prefer
     * known Tools ✓ models, require known vision when the turn carries
     * images, and among equals prefer the fastest measured model.
     * Returns "" when nothing qualifies.
     */
    public static String pickAutoModel(String provider,
                                       List<String> fetched,
                                       boolean showAll,
                                       boolean needsVision,
                                       String avoidId) {
        List<String> candidates = visibleModels(provider, fetched, showAll);
        String best = "";
        long bestLatency = Long.MAX_VALUE;
        boolean bestToolsKnown = false;
        for (String id : candidates) {
            if (id.equals(avoidId) || cooling(provider, id)
                    || isDead(provider, id)) {
                continue;
            }
            ModelInfo known = info(provider, id);
            boolean tools = known == null || known.tools == null
                    || known.tools;
            boolean toolsKnown = known != null && known.tools != null;
            if (!tools) {
                continue;
            }
            boolean vision = known == null || known.vision == null
                    || known.vision;
            if (needsVision && !vision) {
                continue;
            }
            long latency = known != null && known.latencyMs > 0
                    ? known.latencyMs : Long.MAX_VALUE - 1;
            boolean better;
            if (toolsKnown != bestToolsKnown) {
                better = toolsKnown;
            } else {
                better = latency < bestLatency;
            }
            if (best.isEmpty() || better) {
                best = id;
                bestLatency = latency;
                bestToolsKnown = toolsKnown;
            }
        }
        return best;
    }

    /**
     * Strips everything a different provider/model would reject from
     * live {@code messages} (reasoning remnants such as
     * {@code thought_signature} / {@code reasoning_content}, unknown
     * extensions) and, when the target is known text-only, downgrades
     * image parts to text metadata — so a mid-turn failover hands the
     * completed tool calls and their results over intact.
     */
    public static void sanitizeMessagesForModel(List<JsonObject> messages,
                                                String toModel) {
        if (messages == null) {
            return;
        }
        java.util.Set<String> keep = new java.util.HashSet<>(java.util.Arrays.asList(
                "role", "content", "tool_calls", "tool_call_id", "name"));
        for (JsonObject message : messages) {
            for (String key : new java.util.ArrayList<>(message.keySet())) {
                if (!keep.contains(key)) {
                    message.remove(key);
                }
            }
            if (message.has("tool_calls")
                    && message.get("tool_calls").isJsonArray()) {
                for (JsonElement element
                        : message.getAsJsonArray("tool_calls")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject call = element.getAsJsonObject();
                    for (String key
                            : new java.util.ArrayList<>(call.keySet())) {
                        if (!keep.contains(key)) {
                            call.remove(key);
                        }
                    }
                    if (call.has("function")
                            && call.get("function").isJsonObject()) {
                        JsonObject fn = call.getAsJsonObject("function");
                        for (String key
                                : new java.util.ArrayList<>(fn.keySet())) {
                            if (!"name".equals(key)
                                    && !"arguments".equals(key)) {
                                fn.remove(key);
                            }
                        }
                    }
                }
            }
        }
        if (AiChatService.isModelKnownTextOnly(toModel)) {
            AiChatService.downgradeMultimodalMessagesToText(messages, toModel);
        }
    }
}
