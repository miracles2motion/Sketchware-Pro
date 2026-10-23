package pro.sketchware.activities.main.fragments.agent;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import pro.sketchware.R;
import pro.sketchware.ai.AiChatService;
import pro.sketchware.ai.AiProvider;
import pro.sketchware.ai.AiProviderConfig;
import pro.sketchware.ai.ModelCatalog;
import pro.sketchware.databinding.AgentKeyRowBinding;
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
        binding.settingsBackButton.setOnClickListener(v -> finish());

        config = AiProviderConfig.load(this);

        List<String> names = new ArrayList<>();
        for (AiProvider provider : AiProvider.values()) {
            names.add(provider.displayName);
        }
        binding.providerInput.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, names));
        binding.providerInput.setOnItemClickListener((parent, view, position, id) ->
                applyProvider(AiProvider.values()[position], true));

        binding.addKeyButton.setOnClickListener(v -> addTypedKey());
        binding.fetchModelsButton.setOnClickListener(v -> fetchModels(false));
        binding.showAllModelsSwitch.setOnCheckedChangeListener((button, checked) -> {
            config.settings(selected).showAllModels = checked;
            config.settings(selected).autoApproveSecondsSet = true;
            config.save(this);
            refreshModelAdapter();
        });
        AiProviderConfig.Settings initialSettings = config.settings(selected);
        binding.autoApproveSwitch.setChecked(initialSettings.autoApproveSeconds > 0);
        binding.autoApproveSwitch.setOnCheckedChangeListener((button, checked) -> {
            AiProviderConfig.Settings settings = config.settings(selected);
            settings.autoApproveSeconds = checked ? 5 : 0;
            settings.autoApproveSecondsSet = true;
            config.save(this);
        });
        binding.allowAgentModifySwitch.setChecked(config.allowAgentModify());
        binding.allowAgentModifySwitch.setOnCheckedChangeListener((button, checked) -> {
            config.setAllowAgentModify(checked);
            config.save(this);
        });
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
        binding.apiKeyInput.setText("");
        renderKeys();
        binding.modelInput.setText(settings.model, false);
        // The fetched list is cached per provider: it is simply here,
        // reopening Settings must never demand a fresh query first.
        // The same chat-model filter as the chat picker applies.
        binding.modelInput.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1,
                new ArrayList<>(ModelCatalog.visibleModels(
                        provider.name(), settings.models,
                        settings.showAllModels))));
        updateFetchButtonLabel();
        binding.keyHint.setText(provider.keyPage.isEmpty()
                ? getString(R.string.agent_settings_key_hint_custom)
                : getString(R.string.agent_settings_where_key, provider.keyPage));
        hideStatus();
    }

    /** Draws the selected provider's key list, one row per key. */
    private void renderKeys() {
        if (!config.keys(selected).isEmpty()) {
            hideStatus();
        }
        binding.keysContainer.removeAllViews();
        java.util.List<AiProviderConfig.KeyEntry> entries = config.keys(selected);
        binding.keysHint.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        int activeIndex = config.activeKeyIndex(selected);
        long now = System.currentTimeMillis();
        for (int i = 0; i < entries.size(); i++) {
            AiProviderConfig.KeyEntry entry = entries.get(i);
            AgentKeyRowBinding row = AgentKeyRowBinding.inflate(
                    getLayoutInflater(), binding.keysContainer, false);
            row.keyLabel.setText(entry.label + " · " + AiProviderConfig.mask(entry));
            if (i == activeIndex) {
                row.keyStatus.setText(R.string.agent_key_in_use);
                android.util.TypedValue primary = new android.util.TypedValue();
                getTheme().resolveAttribute(
                        androidx.appcompat.R.attr.colorPrimary, primary, true);
                row.keyStatus.setTextColor(primary.data);
            } else if (entry.isExhausted(now)) {
                row.keyStatus.setText(getString(R.string.agent_key_exhausted_until,
                        DateFormat.format("MMM d, h:mm a",
                                new java.util.Date(entry.exhaustedUntil)).toString()));
            } else {
                row.keyStatus.setText(R.string.agent_key_ready);
            }
            final int index = i;
            row.getRoot().setOnClickListener(v -> {
                if (config.activeKeyIndex(selected) == index) {
                    showKeyActions(index);
                } else {
                    config.setActiveKey(selected, index);
                    config.save(this);
                    renderKeys();
                }
            });
            row.deleteButton.setOnClickListener(v -> confirmRemoveKey(index, entry));
            binding.keysContainer.addView(row.getRoot());
        }
    }

    /**
     * Everything that can happen to the key in use, labelled in one place:
     * rename it, replace the secret, or remove it entirely.
     */
    private void showKeyActions(int index) {
        AiProviderConfig.KeyEntry entry = config.keys(selected).get(index);
        String[] actions = {
                getString(R.string.agent_key_rename),
                getString(R.string.agent_key_replace),
                getString(R.string.agent_key_remove)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(entry.label + " · " + AiProviderConfig.mask(entry))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        showRenameDialog(index);
                    } else if (which == 1) {
                        showReplaceDialog(index);
                    } else {
                        confirmRemoveKey(index, entry);
                    }
                })
                .show();
    }

    /** Swaps the secret under an existing name; the key starts fresh. */
    private void showReplaceDialog(int index) {
        AiProviderConfig.KeyEntry entry = config.keys(selected).get(index);
        pro.sketchware.databinding.DialogKeyReplaceBinding dialogBinding =
                pro.sketchware.databinding.DialogKeyReplaceBinding.inflate(
                        getLayoutInflater());
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.agent_key_replace) + " · "
                        + AiProviderConfig.mask(entry))
                .setView(dialogBinding.getRoot())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String typed = dialogBinding.keyValueInput.getText() == null
                            ? "" : dialogBinding.keyValueInput.getText().toString().trim();
                    if (!typed.isEmpty()) {
                        entry.key = typed;
                        // A new secret has never hit a limit.
                        entry.exhaustedUntil = 0L;
                        config.save(this);
                        renderKeys();
                        showStatus(getString(R.string.agent_key_replaced), false);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRenameDialog(int index) {
        AiProviderConfig.KeyEntry entry = config.keys(selected).get(index);
        pro.sketchware.databinding.DialogKeyRenameBinding dialogBinding =
                pro.sketchware.databinding.DialogKeyRenameBinding.inflate(
                        getLayoutInflater());
        dialogBinding.keyNameInput.setText(entry.label);
        dialogBinding.keyNameInput.setSelectAllOnFocus(true);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.agent_key_rename) + " · "
                        + AiProviderConfig.mask(entry))
                .setView(dialogBinding.getRoot())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = dialogBinding.keyNameInput.getText() == null
                            ? "" : dialogBinding.keyNameInput.getText().toString().trim();
                    if (!name.isEmpty()) {
                        entry.label = name;
                        config.save(this);
                        renderKeys();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemoveKey(int index, AiProviderConfig.KeyEntry entry) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.agent_key_remove_confirm,
                        entry.label + " (" + AiProviderConfig.mask(entry) + ")"))
                .setPositiveButton(R.string.agent_key_remove, (dialog, which) -> {
                    config.removeKey(selected, index);
                    config.save(this);
                    renderKeys();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Adds whatever key is pasted in the field to this provider's list. */
    private void addTypedKey() {
        String typed = text(binding.apiKeyInput);
        if (typed.isEmpty()) {
            showStatus(getString(R.string.agent_settings_need_key), true);
            return;
        }
        boolean exists = false;
        for (AiProviderConfig.KeyEntry entry : config.keys(selected)) {
            if (entry.key.equals(typed)) {
                exists = true;
                break;
            }
        }
        config.addKey(selected, typed);
        config.save(this);
        binding.apiKeyInput.setText("");
        renderKeys();
        showStatus(getString(exists
                ? R.string.agent_key_already : R.string.agent_key_added), false);
    }

    /** Asks the provider which models this key may call. */
    private void fetchModels(boolean fromSave) {
        String baseUrl = text(binding.baseUrlInput);
        String typed = text(binding.apiKeyInput);
        if (!typed.isEmpty()) {
            // Fetching with a fresh key adds it first, so proving it and
            // keeping it are the same gesture.
            config.addKey(selected, typed);
            binding.apiKeyInput.setText("");
            renderKeys();
        }
        String key = config.apiKey(selected);
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
                // Remember everything the provider reported; show only
                // the chat-capable, non-hidden ones (user can flip
                // "Show all models" to see the rest).
                config.settings(selected).models = new ArrayList<>(models);
                binding.modelInput.setAdapter(new ArrayAdapter<>(
                        AgentSettingsActivity.this, android.R.layout.simple_list_item_1,
                        new ArrayList<>(ModelCatalog.visibleModels(
                                selected.name(), models,
                                config.settings(selected).showAllModels))));
                List<String> visible = ModelCatalog.visibleModels(
                        selected.name(), models,
                        config.settings(selected).showAllModels);
                if (text(binding.modelInput).isEmpty() && !visible.isEmpty()) {
                    binding.modelInput.setText(visible.get(0), false);
                }
                showStatus(getString(R.string.agent_settings_key_ok, visible.size()), false);
                updateFetchButtonLabel();
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

    /** Fills the model dropdown through the chat-model filter. */
    private void refreshModelAdapter() {
        AiProviderConfig.Settings settings = config.settings(selected);
        binding.modelInput.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1,
                new ArrayList<>(ModelCatalog.visibleModels(
                        selected.name(), settings.models,
                        settings.showAllModels))));
    }

    /** Once a key has a model list, fetching again is a refresh. */
    private void updateFetchButtonLabel() {
        boolean hasModels = !config.settings(selected).models.isEmpty();
        binding.fetchModelsButton.setText(hasModels
                ? R.string.agent_settings_refresh_models
                : R.string.agent_settings_fetch_models);
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
        // Saving judges the key in use from the list, never the paste
        // field: the field is only for adding the next key. fetchModels
        // adopts a typed key first, then validates what remains.
        fetchModels(true);
    }

    /** Persists the whole form: provider, base URL, key, model, model cache. */
    private void persistAll() {
        config.setActiveProvider(selected);
        AiProviderConfig.Settings settings = config.settings(selected);
        settings.baseUrl = text(binding.baseUrlInput);
        settings.model = text(binding.modelInput);
        // Keys persist the moment they are added, switched or removed;
        // nothing about them is read back from the form here.
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
            case UNSUPPORTED:
                return getString(R.string.agent_error_unsupported_generic);
            case MODEL_GONE:
                return getString(R.string.agent_error_model_gone);
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
