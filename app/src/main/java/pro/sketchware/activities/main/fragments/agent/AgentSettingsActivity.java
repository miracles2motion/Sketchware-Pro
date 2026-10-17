package pro.sketchware.activities.main.fragments.agent;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.R;
import pro.sketchware.ai.AiChatService;
import pro.sketchware.ai.AiProvider;
import pro.sketchware.ai.AiProviderConfig;
import pro.sketchware.databinding.AgentSettingsBinding;

/**
 * Provider settings for Agent Mode: pick a service, paste an API key, then
 * choose from the models that key is actually allowed to call.
 *
 * <p>The model list is fetched from the provider, never hardcoded, so a key
 * immediately proves itself: if the list loads, the key works.
 */
public class AgentSettingsActivity extends AppCompatActivity {

    private AgentSettingsBinding binding;
    private AiProviderConfig config;
    private final AiChatService service = new AiChatService();
    private AiProvider selected = AiProvider.GEMINI;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = AgentSettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        config = AiProviderConfig.load(this);

        List<String> names = new ArrayList<>();
        for (AiProvider provider : AiProvider.values()) {
            names.add(provider.displayName);
        }
        binding.providerInput.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, names));
        binding.providerInput.setOnItemClickListener((parent, view, position, id) ->
                applyProvider(AiProvider.values()[position], true));

        binding.fetchModelsButton.setOnClickListener(v -> fetchModels(false));
        binding.saveButton.setOnClickListener(v -> save());
        binding.modelInput.setOnItemClickListener((parent, view, position, id) -> {
            // Choosing a model is the last step of the setup: persist all.
            binding.modelInput.setText(String.valueOf(parent.getItemAtPosition(position)), false);
            persistAll();
            Toast.makeText(this, R.string.agent_settings_saved, Toast.LENGTH_SHORT).show();
            finish();
        });

        applyProvider(config.activeProvider(), false);
    }

    /** Fills the form from the stored settings of one provider. */
    private void applyProvider(AiProvider provider, boolean fromUser) {
        selected = provider;
        binding.providerInput.setText(provider.displayName, false);
        AiProviderConfig.Settings settings = config.settings(provider);
        binding.baseUrlInput.setText(settings.baseUrl);
        binding.apiKeyInput.setText(config.apiKey(provider));
        binding.modelInput.setText(settings.model, false);
        binding.modelInput.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, new ArrayList<String>()));
        binding.keyHint.setText(provider.keyPage.isEmpty()
                ? getString(R.string.agent_settings_key_hint_custom)
                : getString(R.string.agent_settings_where_key, provider.keyPage));
        hideStatus();
    }

    /** Asks the provider which models this key may call. */
    private void fetchModels(boolean fromSave) {
        String baseUrl = text(binding.baseUrlInput);
        String key = text(binding.apiKeyInput);
        if (baseUrl.isEmpty() || key.isEmpty()) {
            showStatus(getString(R.string.agent_settings_need_key), true);
            return;
        }
        binding.fetchModelsButton.setEnabled(false);
        showStatus(getString(R.string.agent_settings_checking), false);
        service.fetchModels(baseUrl, key, new AiChatService.ModelsListener() {
            @Override
            public void onModels(List<String> models) {
                binding.fetchModelsButton.setEnabled(true);
                binding.modelInput.setAdapter(new ArrayAdapter<>(
                        AgentSettingsActivity.this, android.R.layout.simple_list_item_1, models));
                if (text(binding.modelInput).isEmpty() && !models.isEmpty()) {
                    binding.modelInput.setText(models.get(0), false);
                }
                showStatus(getString(R.string.agent_settings_key_ok, models.size()), false);
                config.settings(selected).models = models;
                if (fromSave) {
                    // The first thing a new key does is fetch its models;
                    // persist the key so the chat can use them too.
                    persistAll();
                    if (text(binding.modelInput).isEmpty()) {
                        showStatus(getString(R.string.agent_settings_pick_model), false);
                        binding.modelInput.post(binding.modelInput::showDropDown);
                    } else {
                        Toast.makeText(AgentSettingsActivity.this,
                                R.string.agent_settings_saved, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                }
                if (selected == AiProvider.OPENROUTER) {
                    fetchQuota(key);
                }
            }

            @Override
            public void onError(AiChatService.AiError error) {
                binding.fetchModelsButton.setEnabled(true);
                showStatus(describe(error), true);
            }
        });
    }

    /** OpenRouter is the only preset that reports remaining credit. */
    private void fetchQuota(String key) {
        service.fetchOpenRouterKeyInfo(key, new AiChatService.KeyInfoListener() {
            @Override
            public void onInfo(String summary) {
                if (!summary.isEmpty()) {
                    binding.statusText.setText(binding.statusText.getText() + "\n" + summary);
                }
            }

            @Override
            public void onError(AiChatService.AiError error) {
                // The quota line is a bonus; the model list already proved the key.
            }
        });
    }

    /**
     * Saving first proves the key: the app fetches the models available for
     * it, and only then asks the user to pick one. With a model already
     * chosen, Save simply persists everything and closes.
     */
    private void save() {
        String baseUrl = text(binding.baseUrlInput);
        String key = text(binding.apiKeyInput);
        if (baseUrl.isEmpty() || key.isEmpty()) {
            showStatus(getString(R.string.agent_settings_need_key), true);
            return;
        }
        fetchModels(true);
    }

    /** Persists the whole form: provider, base URL, key, model, model cache. */
    private void persistAll() {
        config.setActiveProvider(selected);
        AiProviderConfig.Settings settings = config.settings(selected);
        settings.baseUrl = text(binding.baseUrlInput);
        settings.model = text(binding.modelInput);
        config.setApiKey(selected, text(binding.apiKeyInput));
        config.save(this);
    }

    private String describe(AiChatService.AiError error) {
        switch (error.kind) {
            case AUTH:
                return getString(R.string.agent_error_auth);
            case NETWORK:
                return getString(R.string.agent_error_network);
            case QUOTA:
                return getString(R.string.agent_error_quota_now);
            default:
                return getString(R.string.agent_error_generic) + " (" + error.httpCode + "): "
                        + error.message;
        }
    }

    private void showStatus(String text, boolean error) {
        binding.statusText.setVisibility(View.VISIBLE);
        binding.statusText.setText(text);
        binding.statusText.setTextColor(getResources().getColor(
                error ? android.R.color.holo_red_light : android.R.color.holo_green_light,
                getTheme()));
    }

    private void hideStatus() {
        binding.statusText.setVisibility(View.GONE);
    }

    private static String text(android.widget.EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}
