package pro.sketchware.ai;

/**
 * AI providers Agent Mode can talk to.
 *
 * <p>Every preset speaks the OpenAI chat-completions format, including
 * Gemini through Google's official compatibility endpoint
 * ({@code https://generativelanguage.googleapis.com/v1beta/openai/}), so a
 * single HTTP client covers all of them. A provider is just a base URL; the
 * user supplies their own API key.
 */
public enum AiProvider {

    GEMINI("Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            "aistudio.google.com/apikey"),
    OPENROUTER("OpenRouter",
            "https://openrouter.ai/api/v1/",
            "openrouter.ai/keys"),
    NVIDIA("NVIDIA",
            "https://integrate.api.nvidia.com/v1/",
            "build.nvidia.com"),
    CUSTOM("Custom (OpenAI-compatible)", "", "");

    /** Human readable name, shown in the settings and the chat header. */
    public final String displayName;
    /** Default endpoint for the preset; the user may override it. */
    public final String defaultBaseUrl;
    /** Where the user can create a key for this provider. */
    public final String keyPage;

    AiProvider(String displayName, String defaultBaseUrl, String keyPage) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.keyPage = keyPage;
    }

    public static AiProvider fromId(String id) {
        if (id != null) {
            for (AiProvider provider : values()) {
                if (provider.name().equals(id)) {
                    return provider;
                }
            }
        }
        return GEMINI;
    }
}
