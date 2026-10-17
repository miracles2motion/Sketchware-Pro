package pro.sketchware.activities.main.fragments.agent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import a.a.a.qA;
import pro.sketchware.R;
import pro.sketchware.ai.AgentKnowledgePack;
import pro.sketchware.ai.AiChatService;
import pro.sketchware.ai.AiPaths;
import pro.sketchware.ai.AiProviderConfig;
import pro.sketchware.ai.ChatAdapter;
import pro.sketchware.ai.ChatMessage;
import pro.sketchware.databinding.AgentChatBinding;

/**
 * Agent Mode: a chat with an online AI provider (Gemini, OpenRouter, NVIDIA
 * or any OpenAI-compatible endpoint), using the user's own API key.
 *
 * <p>The fragment owns the chat UI only; {@link AiChatService} owns the
 * HTTP conversation and {@link AiProviderConfig} the key and model choice.
 */
public class AgentFragment extends qA {

    private final List<ChatMessage> messages = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Tokens that arrived but are not in the list yet. */
    private final StringBuilder pendingToken = new StringBuilder();
    /** How long the collected tokens are batched before the list is updated. */
    private static final long TOKEN_FLUSH_MS = 60L;
    private volatile boolean flushScheduled;
    /** Padding that was taken off because the screen reached below the display. */
    private int appliedBottomPadding;
    /** Watches the layout to keep the chat inside the visible area. */
    private android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private AgentChatBinding binding;
    private ChatAdapter adapter;
    private AiChatService chatService;
    private AiProviderConfig providerConfig;
    private ChatMessage streamingMessage;
    /** The reply bubble of a turn that failed on quota, kept for a retry. */
    private ChatMessage failedBubble;
    private String lastUserText = "";
    private volatile boolean generating;
    /** True while the soft keyboard is up (measured, not guessed). */
    private boolean keyboardOpen;
    /** True once the current turn has produced (invisible) reasoning. */
    private boolean thinkingSeen;
    /** When the current turn was sent, for the waiting clock. */
    private long requestStartedAt;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = AgentChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        /*
         * The chat screen lives in a container that the coordinator layout
         * pushes below the app bar. The container is not always shortened by
         * the height of the app bar, so the screen can reach below the
         * display and the input bar would be out of sight. The overflow is
         * measured and taken off as padding, so the input bar always sits
         * just above the navigation bar.
         */
        layoutListener = () -> fitToVisibleArea(binding == null ? null : binding.getRoot());
        view.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);

        adapter = new ChatAdapter(messages);
        adapter.setOnMessageClickListener(this::onMessageClicked);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        // A short conversation sits above the input bar, not at the top of an
        // empty screen.
        layoutManager.setStackFromEnd(true);
        binding.chatList.setLayoutManager(layoutManager);
        binding.chatList.setAdapter(adapter);

        binding.scrollDownButton.setOnClickListener(v -> scrollToEndAndDismissKeyboard());
        binding.chatList.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView,
                                   int dx, int dy) {
                updateScrollButtonVisibility();
            }
        });

        binding.sendButton.setOnClickListener(v -> {
            if (generating) {
                stopGeneration();
                return;
            }
            sendCurrentInput();
        });

        binding.chatInput.addTextChangedListener(new SimpleTextWatcher(text -> {
            boolean hasText = !text.trim().isEmpty();
            binding.sendButton.setEnabled(hasText || generating);
            binding.sendButton.setIconResource(
                    generating ? R.drawable.ic_mtrl_close : R.drawable.agent_ic_send);
        }));

        binding.chooseModelButton.setOnClickListener(v -> openSettings());
        binding.settingsButton.setOnClickListener(v -> openSettings());
        binding.modelPickerArea.setOnClickListener(v -> showModelPicker());
        binding.historyButton.setOnClickListener(v ->
                // Reserved for the history phase: saved conversations will
                // live behind this icon.
                android.widget.Toast.makeText(getContext(),
                        R.string.agent_history_soon, android.widget.Toast.LENGTH_SHORT).show());

        chatService = new AiChatService();
        providerConfig = AiProviderConfig.load(requireContext());

        showCurrentState();
        ensureKnowledgePack();
    }

    /**
     * Writes the block catalog the model needs into the knowledge directory.
     *
     * <p>The file is only written when it is missing, so a pack the user has
     * edited by hand is never overwritten.
     */
    private void ensureKnowledgePack() {
        File blocks = new File(AiPaths.knowledgeDir(), AgentKnowledgePack.BLOCKS_FILE_NAME);
        if (blocks.exists()) {
            return;
        }
        io.execute(AgentKnowledgePack::exportBlocks);
    }

    @Override
    public void onDestroyView() {
        if (chatService != null) {
            chatService.cancel();
        }
        if (layoutListener != null && binding != null) {
            binding.getRoot().getViewTreeObserver()
                    .removeOnGlobalLayoutListener(layoutListener);
        }
        layoutListener = null;
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning from the provider settings: pick up the new key/model.
        if (getContext() != null) {
            providerConfig = AiProviderConfig.load(requireContext());
        }
        dropSetupSeedIfConfigured();
        updateProviderUi();
        updateActionButton();
    }

    /**
     * The "connect a provider" notes are seeded into an empty chat. Once a
     * provider exists they have served their purpose and leave the chat.
     */
    private void dropSetupSeedIfConfigured() {
        if (providerConfig == null || !providerConfig.isConfigured()
                || streamingMessage != null) {
            return;
        }
        boolean removed = messages.removeIf(message ->
                message.role == ChatMessage.Role.SYSTEM
                        && message.state != ChatMessage.State.ERROR);
        if (removed && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * A tap on a bubble: the retry line resends a failed turn at once;
     * anything else offers to copy the text.
     */
    private void onMessageClicked(View anchor, ChatMessage message) {
        if (getContext() == null) {
            return;
        }
        boolean failed = message.state == ChatMessage.State.ERROR
                && message == failedBubble;
        if (failed && anchor.getId() == R.id.retry_text) {
            retryFailedTurn();
            return;
        }
        boolean hasText = message.text != null && !message.text.isEmpty();
        if (!hasText && !failed) {
            return;
        }
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        if (hasText) {
            menu.getMenu().add(0, 1, 0, R.string.agent_action_copy);
        }
        if (failed) {
            menu.getMenu().add(0, 2, 1, R.string.agent_action_retry);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                ClipboardManager clipboard = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(
                            ClipData.newPlainText("message", message.text));
                }
                Toast.makeText(requireContext(), R.string.agent_copied,
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            if (item.getItemId() == 2) {
                retryFailedTurn();
                return true;
            }
            return false;
        });
        menu.show();
    }

    // region chat

    private void sendCurrentInput() {
        String text = binding.chatInput.getText() == null
                ? "" : binding.chatInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        binding.chatInput.setText("");

        if (providerConfig == null || !providerConfig.isConfigured()) {
            appendMessage(ChatMessage.error(getString(R.string.agent_provider_missing)));
            return;
        }

        appendMessage(ChatMessage.user(text));
        lastUserText = text;
        ChatMessage reply = ChatMessage.assistant("");
        streamingMessage = reply;
        appendMessage(reply);
        startRequest();
    }

    /** Sends the current history (ending in {@link #lastUserText}) off. */
    private void startRequest() {
        if (providerConfig == null || streamingMessage == null) {
            return;
        }
        generating = true;
        thinkingSeen = false;
        requestStartedAt = SystemClock.elapsedRealtime();
        streamingMessage.statusText = getString(R.string.agent_status_waiting, 0);
        int bubbleIndex = messages.indexOf(streamingMessage);
        if (bubbleIndex >= 0) {
            adapter.notifyItemChanged(bubbleIndex);
        }
        binding.chatList.removeCallbacks(waitTicker);
        binding.chatList.postDelayed(waitTicker, 1000L);
        updateActionButton();
        List<ChatMessage> history = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == streamingMessage || message.role == ChatMessage.Role.SYSTEM) {
                continue;
            }
            if (message.role == ChatMessage.Role.ASSISTANT && message.text.trim().isEmpty()) {
                continue;
            }
            history.add(message);
        }
        AiProviderConfig.Settings settings = providerConfig.activeSettings();
        chatService.chat(settings.baseUrl, providerConfig.apiKey(providerConfig.activeProvider()),
                settings.model, history, new AiChatService.ChatListener() {
                    @Override
                    public void onThinking() {
                        thinkingSeen = true;
                    }

                    @Override
                    public void onToken(String text) {
                        appendToken(text);
                    }

                    @Override
                    public void onComplete() {
                        generating = false;
                        setStreamingComplete();
                        updateActionButton();
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        generating = false;
                        handleError(error);
                        updateActionButton();
                    }
                });
    }

    private void stopGeneration() {
        if (chatService != null) {
            chatService.cancel();
        }
        generating = false;
        setStreamingComplete();
        updateActionButton();
    }

    private void handleError(AiChatService.AiError error) {
        if (binding == null) {
            return;
        }
        flushPendingTokens();
        // Every failure keeps its bubble, marked as failed: a subtle
        // tap-to-retry line appears under it instead of a dead end.
        failedBubble = streamingMessage;
        if (failedBubble != null) {
            failedBubble.state = ChatMessage.State.ERROR;
            streamingMessage = null;
            adapter.notifyDataSetChanged();
        }
        if (error.kind == AiChatService.ErrorKind.QUOTA) {
            showQuotaDialog(error);
            return;
        }
        appendMessage(ChatMessage.error(errorTitle(error)
                + (error.message.isEmpty() ? "" : "\n" + error.message)));
    }

    private String errorTitle(AiChatService.AiError error) {
        switch (error.kind) {
            case AUTH:
                return getString(R.string.agent_error_auth);
            case NETWORK:
                return getString(R.string.agent_error_network);
            case EMPTY:
                return getString(R.string.agent_error_empty);
            default:
                return getString(R.string.agent_error_generic) + " (" + error.httpCode + ")";
        }
    }

    /**
     * The quota popup: the provider just said this model is out of quota, so
     * offer another model from the same key instead of a dead end.
     */
    private void showQuotaDialog(AiChatService.AiError error) {
        if (getContext() == null) {
            return;
        }
        String message = error.message.isEmpty()
                ? getString(R.string.agent_quota_body) : error.message;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.agent_quota_title)
                .setMessage(message)
                .setPositiveButton(R.string.agent_action_switch_model,
                        (dialog, which) -> fetchModelsAndPick())
                .setNegativeButton(R.string.agent_action_open_settings,
                        (dialog, which) -> openSettings())
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Tapping the model name offers every model this key can call, neatly
     * listed. The list comes from the settings cache (fetched when the key
     * was saved) and is refreshed in the background for next time.
     */
    private void showModelPicker() {
        if (providerConfig == null || !providerConfig.isConfigured()) {
            openSettings();
            return;
        }
        AiProviderConfig.Settings settings = providerConfig.activeSettings();
        if (settings.models != null && !settings.models.isEmpty()) {
            pickModel(settings, settings.models);
            refreshModelCache(settings);
            return;
        }
        binding.modelLabel.setText(R.string.agent_settings_checking);
        chatService.fetchModels(settings.baseUrl,
                providerConfig.apiKey(providerConfig.activeProvider()),
                new AiChatService.ModelsListener() {
                    @Override
                    public void onModels(List<String> models) {
                        settings.models = models;
                        providerConfig.save(requireContext());
                        updateProviderUi();
                        if (!models.isEmpty()) {
                            pickModel(settings, models);
                        }
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        updateProviderUi();
                        appendMessage(ChatMessage.error(errorTitle(error)
                                + (error.message.isEmpty() ? "" : "\n" + error.message)));
                    }
                });
    }

    private void pickModel(AiProviderConfig.Settings settings, List<String> models) {
        if (getContext() == null) {
            return;
        }
        String[] items = models.toArray(new String[0]);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.agent_pick_model)
                .setItems(items, (dialog, which) -> {
                    settings.model = items[which];
                    providerConfig.save(requireContext());
                    updateProviderUi();
                    appendMessage(new ChatMessage(ChatMessage.Role.SYSTEM,
                            getString(R.string.agent_switched_model, items[which])));
                })
                .show();
    }

    private void refreshModelCache(AiProviderConfig.Settings settings) {
        chatService.fetchModels(settings.baseUrl,
                providerConfig.apiKey(providerConfig.activeProvider()),
                new AiChatService.ModelsListener() {
                    @Override
                    public void onModels(List<String> models) {
                        settings.models = models;
                        if (getContext() != null) {
                            providerConfig.save(requireContext());
                        }
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        // The cached list stays; nothing to tell the user.
                    }
                });
    }

    private void fetchModelsAndPick() {
        AiProviderConfig.Settings settings = providerConfig.activeSettings();
        chatService.fetchModels(settings.baseUrl,
                providerConfig.apiKey(providerConfig.activeProvider()),
                new AiChatService.ModelsListener() {
                    @Override
                    public void onModels(List<String> models) {
                        settings.models = models;
                        providerConfig.save(requireContext());
                        if (binding == null || models.isEmpty()) {
                            appendMessage(ChatMessage.error(
                                    getString(R.string.agent_error_no_models)));
                            return;
                        }
                        String[] items = models.toArray(new String[0]);
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.agent_action_switch_model)
                                .setItems(items, (dialog, which) -> {
                                    settings.model = items[which];
                                    providerConfig.save(requireContext());
                                    updateProviderUi();
                                    retryFailedTurn();
                                })
                                .show();
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        appendMessage(ChatMessage.error(errorTitle(error)
                                + (error.message.isEmpty() ? "" : "\n" + error.message)));
                    }
                });
    }

    /** Re-runs the quota-failed turn on the newly chosen model. */
    private void retryFailedTurn() {
        if (failedBubble == null) {
            return;
        }
        streamingMessage = failedBubble;
        failedBubble = null;
        streamingMessage.text = "";
        streamingMessage.state = ChatMessage.State.STREAMING;
        adapter.notifyDataSetChanged();
        startRequest();
    }

    private void appendToken(String text) {
        if (binding == null || streamingMessage == null) {
            return;
        }
        // Tokens can arrive faster than the list can be diffed, so they are
        // collected and flushed to the list in batches.
        synchronized (pendingToken) {
            pendingToken.append(text);
        }
        if (!flushScheduled) {
            flushScheduled = true;
            binding.chatList.postDelayed(flushTokens, TOKEN_FLUSH_MS);
        }
    }

    /**
     * Once a second, refreshes the empty streaming bubble with how long the
     * model has been working. Stops by itself when the turn ends.
     */
    private final Runnable waitTicker = new Runnable() {
        @Override
        public void run() {
            if (binding == null || streamingMessage == null || !generating) {
                return;
            }
            if (streamingMessage.text.isEmpty()) {
                long seconds = (SystemClock.elapsedRealtime() - requestStartedAt) / 1000L;
                streamingMessage.statusText = getString(thinkingSeen
                        ? R.string.agent_status_thinking
                        : R.string.agent_status_waiting, seconds);
                int index = messages.indexOf(streamingMessage);
                if (index >= 0) {
                    adapter.notifyItemChanged(index);
                }
            }
            binding.chatList.postDelayed(this, 1000L);
        }
    };

    /** Moves the collected tokens into the streaming message. */
    private final Runnable flushTokens = () -> {
        flushScheduled = false;
        flushPendingTokens();
    };

    private void flushPendingTokens() {
        if (binding == null || streamingMessage == null) {
            synchronized (pendingToken) {
                pendingToken.setLength(0);
            }
            return;
        }
        synchronized (pendingToken) {
            if (pendingToken.length() > 0) {
                streamingMessage.text += pendingToken;
                pendingToken.setLength(0);
            }
        }
        streamingMessage.state = ChatMessage.State.STREAMING;
        int index = messages.indexOf(streamingMessage);
        if (index >= 0) {
            adapter.notifyItemChanged(index);
            scrollToNewest();
        }
    }

    private void setStreamingComplete() {
        if (streamingMessage == null) {
            return;
        }
        if (binding != null) {
            binding.chatList.removeCallbacks(flushTokens);
            binding.chatList.removeCallbacks(waitTicker);
        }
        flushScheduled = false;
        streamingMessage.statusText = null;
        synchronized (pendingToken) {
            if (pendingToken.length() > 0) {
                streamingMessage.text += pendingToken;
                pendingToken.setLength(0);
            }
        }
        streamingMessage.state = ChatMessage.State.COMPLETE;
        if (streamingMessage.text.isEmpty()) {
            streamingMessage.text = "…";
        }
        streamingMessage = null;
        if (binding != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /**
     * Takes off the part of the screen that reaches below the display.
     *
     * <p>Whatever the container was given, the chat ends exactly at the
     * bottom of the visible area, so the input bar is never hidden below
     * the navigation bar.
     */
    private void fitToVisibleArea(View root) {
        if (root == null || root.getHeight() == 0 || !isAdded() || getActivity() == null) {
            return;
        }
        Rect visible = new Rect();
        // Both the visible frame and the position of the screen are in the
        // coordinates of the window, so they can be compared.
        root.getWindowVisibleDisplayFrame(visible);
        int[] location = new int[2];
        root.getLocationInWindow(location);
        int overflow = location[1] + root.getHeight() - visible.bottom;
        if (overflow < 0) {
            overflow = 0;
        }
        if (overflow != appliedBottomPadding) {
            appliedBottomPadding = overflow;
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), overflow);
        }
        // When the keyboard opens, the visible frame shrinks by much more
        // than the navigation bar ever could.
        boolean open = overflow > root.getHeight() * 0.15f;
        if (open != keyboardOpen) {
            keyboardOpen = open;
            updateScrollButtonVisibility();
        }
    }

    /** Smoothly returns to the newest message and puts the keyboard away. */
    private void scrollToEndAndDismissKeyboard() {
        if (binding == null) {
            return;
        }
        int last = adapter.getItemCount() - 1;
        if (last >= 0) {
            binding.chatList.smoothScrollToPosition(last);
        }
        if (getContext() != null) {
            InputMethodManager manager = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(binding.chatInput.getWindowToken(), 0);
            }
        }
    }

    /**
     * The scroll button only earns its place when the newest message is out
     * of view, or while the keyboard is up (when it doubles as dismiss).
     */
    private void updateScrollButtonVisibility() {
        if (binding == null) {
            return;
        }
        boolean awayFromEnd = binding.chatList.canScrollVertically(1);
        boolean show = awayFromEnd || keyboardOpen;
        int target = show ? View.VISIBLE : View.GONE;
        if (binding.scrollDownButton.getVisibility() != target) {
            binding.scrollDownButton.setVisibility(target);
        }
    }

    /**
     * Scrolls to the newest message, but only when the user is already
     * looking at the end of the conversation.
     */
    private void scrollToNewest() {
        if (binding == null) {
            return;
        }
        LinearLayoutManager manager =
                (LinearLayoutManager) binding.chatList.getLayoutManager();
        if (manager == null) {
            return;
        }
        int last = adapter.getItemCount() - 1;
        if (last < 0) {
            return;
        }
        int first = manager.findFirstVisibleItemPosition();
        if (first == androidx.recyclerview.widget.RecyclerView.NO_POSITION || last - first < 3) {
            binding.chatList.scrollToPosition(last);
        }
    }

    private void appendMessage(ChatMessage message) {
        messages.add(message);
        adapter.notifyItemInserted(messages.size() - 1);
        if (binding != null) {
            binding.chatList.scrollToPosition(messages.size() - 1);
        }
    }

    /** Replaces the transcript with the current provider state. */
    private void showCurrentState() {
        if (binding == null) {
            return;
        }
        messages.clear();
        streamingMessage = null;
        if (providerConfig == null || !providerConfig.isConfigured()) {
            messages.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                    getString(R.string.agent_provider_empty_title)));
            messages.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                    getString(R.string.agent_provider_empty_body)));
        }
        adapter.notifyDataSetChanged();
        updateProviderUi();
        updateActionButton();
    }

    private void updateProviderUi() {
        if (binding == null || providerConfig == null) {
            return;
        }
        boolean configured = providerConfig.isConfigured();
        binding.chooseModelButton.setVisibility(configured ? View.GONE : View.VISIBLE);
        binding.modelLabel.setText(configured
                ? providerConfig.activeSettings().model
                : getString(R.string.agent_no_provider));
    }

    private void openSettings() {
        if (getContext() != null) {
            startActivity(new Intent(getContext(), AgentSettingsActivity.class));
        }
    }

    private void updateActionButton() {
        if (binding == null) {
            return;
        }
        binding.sendButton.setIconResource(
                generating ? R.drawable.ic_mtrl_close : R.drawable.agent_ic_send);
        binding.sendButton.setContentDescription(
                getString(generating ? R.string.agent_action_stop : R.string.agent_action_send));
        boolean hasText = binding.chatInput.getText() != null
                && !binding.chatInput.getText().toString().trim().isEmpty();
        binding.sendButton.setEnabled(hasText || generating);
    }

    // endregion

    /** Minimal text watcher so the fragment does not need a separate class. */
    private static class SimpleTextWatcher implements android.text.TextWatcher {
        interface OnChanged {
            void onChanged(String text);
        }

        private final OnChanged callback;

        SimpleTextWatcher(OnChanged callback) {
            this.callback = callback;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            callback.onChanged(s == null ? "" : s.toString());
        }
    }
}
