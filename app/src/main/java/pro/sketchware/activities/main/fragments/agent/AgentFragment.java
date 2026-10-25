package pro.sketchware.activities.main.fragments.agent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import a.a.a.lC;
import a.a.a.qA;
import a.a.a.wq;
import a.a.a.yB;
import pro.sketchware.R;
import pro.sketchware.ai.AgentChatLog;
import pro.sketchware.ai.AgentExport;
import pro.sketchware.ai.AgentJournal;
import pro.sketchware.ai.OpenUiLang;
import pro.sketchware.ai.AgentKnowledgePack;
import pro.sketchware.ai.AgentLoop;
import pro.sketchware.ai.AgentMedia;
import pro.sketchware.ai.AgentProposal;
import pro.sketchware.ai.ModelCatalog;
import pro.sketchware.ai.OpenUiThemeEngine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import pro.sketchware.ai.SketchwareProjects;
import pro.sketchware.ai.AiChatService;
import pro.sketchware.ai.AiPaths;
import pro.sketchware.ai.AiProvider;
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
    /** The agent loop gets its own thread: approvals block it by design. */
    private final ExecutorService agentExecutor =
            Executors.newSingleThreadExecutor();
    private AgentLoop agentLoop;
    /** The approval the user is currently deciding on, if any. */
    private volatile CountDownLatch approvalLatch;
    private volatile boolean approvalDecision;
    private ChatMessage approvalCardMessage;
    /** The colour pick the user is currently making, if any. */
    private volatile CountDownLatch colorLatch;
    private volatile com.google.gson.JsonObject pickedColor;
    /** The option the user tapped in the agent's question, if any. */
    private volatile String pickedChoice;
    // True from the moment a choice sheet shows this turn: the tool path
    // marks it, and the turn-end bounce checks it so a choice the model
    // DID ask for properly is never popped twice.
    private boolean choiceSheetShownThisTurn;
    // The currently shown choice sheet (tool or bounce), so a stale one
    // can be cancelled the moment the user starts talking first.
    private androidx.appcompat.app.AlertDialog choiceSheetDialog;
    /** Lets the waiting turn know a question was answered. */
    private volatile CountDownLatch choiceLatch;
    /** Tokens that arrived but are not in the list yet. */
    /** Tokens that have arrived but are not on screen yet. */
    private final StringBuilder pendingReveal = new StringBuilder();
    /** How long the collected tokens are batched before the list is updated. */
    /** How often the smoothing queue paints the next characters. */
    private static final long REVEAL_TICK_MS = 24L;
    private volatile boolean revealScheduled;
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
    /** When the current turn was sent, to time the reply. */
    private long requestStartedAt;
    /** The task-strip line currently shown (dismiss remembers it). */
    private String stripShownText = "";
    private String stripDismissedText = "";
    /** Current live step/tool label for the floating task strip. */
    private String liveStepLabel = "";
    /** Images staged in the composer before the user taps Send. */
    private final List<ChatMessage.Attachment> pendingAttachments =
            new ArrayList<>();
    private volatile boolean attachingImages;
    private final ActivityResultLauncher<String> pickImagesLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.GetMultipleContents(),
                    this::onImagesPicked);
    /** The project whose stored conversation is on screen, if any. */
    private String chatProjectId = "";
    /** Cached display name of the bound project for empty-state copy. */
    private String workingProjectDisplayName = "";
    /**
     * The one live "what the agent is doing" line, replaced in place
     * as the turn moves on and removed once the turn is over. These
     * lines are a heartbeat, not part of the conversation: they must
     * not pile up in the chat or in an export.
     */
    private ChatMessage liveNote;

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
        adapter.setAutoApproveSeconds(providerConfig != null
                && providerConfig.activeSettings() != null
                ? providerConfig.activeSettings().autoApproveSeconds : 5);
        adapter.setOnApprovalListener(this::onApprovalDecision);
        adapter.setOnInstallListener(this::onInstallRequest);
        adapter.setOnOpenUiListener(new ChatAdapter.OnOpenUiListener() {
            @Override
            public void onOpenUiStateChanged(ChatMessage message) {
                persistChat();
            }

            @Override
            public void onOpenUiSendToAssistant(
                    ChatMessage message,
                    String promptText,
                    com.google.gson.JsonElement context,
                    com.google.gson.JsonObject formState) {
                sendOpenUiInteraction(promptText, context, formState);
            }

            @Override
            public void onOpenUiOpenUrl(String url) {
                openExternalUrl(url);
            }
        });
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        // A short conversation sits above the input bar, not at the top of an
        // empty screen.
        layoutManager.setStackFromEnd(true);
        binding.chatList.setLayoutManager(layoutManager);
        binding.chatList.setItemAnimator(null);
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
            updateActionButton();
        }));

        binding.chooseModelButton.setOnClickListener(v -> openSettings());
        binding.quickChipDesign.setOnClickListener(v -> prefillComposer(
                "Let's design and build a new screen "));
        binding.quickChipScreenshot.setOnClickListener(v -> {
            if (binding.chatInput.getText() == null
                    || binding.chatInput.getText().toString().trim().isEmpty()) {
                binding.chatInput.setText("Recreate this screenshot as a screen");
                binding.chatInput.setSelection(binding.chatInput.getText().length());
            }
            pickAttachmentImages();
        });
        binding.quickChipInspect.setOnClickListener(v -> prefillComposer(
                "Inspect this project and suggest improvements"));
        binding.settingsButton.setOnClickListener(v -> openSettings());
        binding.modelPickerArea.setOnClickListener(v -> showModelPicker());
        // History is conversations: the projects that have a saved
        // chat, each one a door back into that conversation. (What
        // was done in a project lives in its task log, in the box.)
        binding.historyButton.setOnClickListener(v -> showChatHistoryDialog());
        binding.exportButton.setOnClickListener(v -> exportConversation());
        binding.attachButton.setOnClickListener(v -> pickAttachmentImages());
        binding.designSystemButton.setOnClickListener(
                v -> showDesignSystemDialog());
        binding.planButton.setOnClickListener(v -> showPlanDialog());
        binding.taskLogButton.setOnClickListener(v -> showTaskLogDialog());
        binding.taskStrip.setOnClickListener(v -> {
            if (providerConfig == null) {
                return;
            }
            String scId = providerConfig.workingProjectId();
            if (scId.isEmpty()) {
                return;
            }
            if (AgentJournal.hasPlan(scId)) {
                showPlanDialog();
            } else {
                showTaskLogDialog();
            }
        });
        binding.taskStripResume.setOnClickListener(v -> resumeTask());
        binding.taskStripDismiss.setOnClickListener(v -> {
            stripDismissedText = stripShownText;
            binding.taskStrip.setVisibility(View.GONE);
        });

        chatService = new AiChatService();
        providerConfig = AiProviderConfig.load(requireContext());
        binding.workingOnCard.setOnClickListener(v -> showProjectPicker());
        agentLoop = new AgentLoop(requireContext(), chatService, agentExecutor);
        updateWorkingOnCard();

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
        if (!blocks.exists()) {
            io.execute(AgentKnowledgePack::exportBlocks);
        }
        File widgets = new File(AiPaths.knowledgeDir(), AgentKnowledgePack.WIDGETS_FILE_NAME);
        if (!widgets.exists()) {
            io.execute(AgentKnowledgePack::exportWidgets);
        }
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
        persistChat();
        closePendingCard(getString(
                R.string.agent_approval_not_applied_failed));
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        releaseApproval(false);
        releasePick();
        io.shutdownNow();
        agentExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning from the provider settings: pick up the new key/model.
        if (getContext() != null) {
            providerConfig = AiProviderConfig.load(requireContext());
            updateWorkingOnCard();
        }
        dropSetupSeedIfConfigured();
        updateProviderUi();
        updateActionButton();
        // Returning from the editor may mean a build card's APK was
        // rebuilt there: its Install row must re-check before offering.
        refreshBuildCards();
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
        updateEmptyState();
    }

    /** 95 seconds reads as "1m 35s"; under a minute it stays "42s". */
    private static String formatDuration(long millis) {
        long seconds = Math.max(1L, millis / 1000L);
        if (seconds < 60L) {
            return seconds + "s";
        }
        return (seconds / 60L) + "m " + (seconds % 60L) + "s";
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
        String time = DateFormat.format("h:mm a",
                new java.util.Date(message.timestamp)).toString();
        String info;
        if (message.role == ChatMessage.Role.ASSISTANT
                && message.repliedAt > 0 && message.durationMs > 0) {
            String replyTime = DateFormat.format("h:mm a",
                    new java.util.Date(message.repliedAt)).toString();
            info = getString(R.string.agent_info_reply,
                    replyTime, formatDuration(message.durationMs));
        } else {
            info = getString(R.string.agent_info_sent, time);
        }
        // Information, not an action: visible but not clickable.
        menu.getMenu().add(0, 0, 0, info).setEnabled(false);
        if (hasText) {
            menu.getMenu().add(0, 1, 1, R.string.agent_action_copy);
        }
        if (failed) {
            menu.getMenu().add(0, 2, 2, R.string.agent_action_retry);
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

    /** Sends a message as if the user had typed it. */
    private void askAgent(String text) {
        if (binding == null) {
            return;
        }
        binding.chatInput.setText(text);
        sendCurrentInput();
    }

    private void pickAttachmentImages() {
        if (generating || attachingImages) {
            return;
        }
        if (pendingAttachments.size() >= AgentMedia.MAX_ATTACHMENTS_PER_MESSAGE) {
            Toast.makeText(requireContext(), R.string.agent_attach_limit,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pickImagesLauncher.launch("image/*");
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.agent_attach_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void onImagesPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || getContext() == null) {
            return;
        }
        int slots = AgentMedia.MAX_ATTACHMENTS_PER_MESSAGE
                - pendingAttachments.size();
        if (slots <= 0) {
            Toast.makeText(requireContext(), R.string.agent_attach_limit,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean capped = uris.size() > slots;
        List<Uri> toImport = new ArrayList<>(
                uris.subList(0, Math.min(uris.size(), slots)));
        Context appContext = requireContext().getApplicationContext();
        attachingImages = true;
        updateActionButton();
        io.execute(() -> {
            List<ChatMessage.Attachment> imported = new ArrayList<>();
            boolean anyFailed = false;
            for (Uri uri : toImport) {
                try {
                    imported.add(AgentMedia.saveAttachment(appContext, uri));
                } catch (Exception e) {
                    anyFailed = true;
                }
            }
            boolean failedFinal = anyFailed;
            runOnUi(() -> {
                attachingImages = false;
                pendingAttachments.addAll(imported);
                refreshAttachmentStrip();
                updateActionButton();
                if (capped && getContext() != null) {
                    Toast.makeText(requireContext(),
                            R.string.agent_attach_limit,
                            Toast.LENGTH_SHORT).show();
                } else if (failedFinal && imported.isEmpty()
                        && getContext() != null) {
                    Toast.makeText(requireContext(),
                            R.string.agent_attach_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void refreshAttachmentStrip() {
        if (binding == null || getContext() == null) {
            return;
        }
        binding.attachmentStrip.removeAllViews();
        if (pendingAttachments.isEmpty()) {
            binding.attachmentStripScroll.setVisibility(View.GONE);
            return;
        }
        binding.attachmentStripScroll.setVisibility(View.VISIBLE);
        Context ctx = requireContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int box = Math.round(60f * density);
        int badgeSize = Math.round(20f * density);
        int gap = Math.round(8f * density);
        float radius = 12f * density;
        for (int i = 0; i < pendingAttachments.size(); i++) {
            final ChatMessage.Attachment att = pendingAttachments.get(i);
            FrameLayout cell = new FrameLayout(ctx);
            LinearLayout.LayoutParams cellParams =
                    new LinearLayout.LayoutParams(box, box);
            if (i > 0) {
                cellParams.leftMargin = gap;
            }
            cell.setLayoutParams(cellParams);

            ImageView thumbView = new ImageView(ctx);
            thumbView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(radius);
            shape.setColor(0x22000000);
            thumbView.setBackground(shape);
            thumbView.setClipToOutline(true);
            Bitmap bmp = AgentMedia.decodeThumbnail(
                    att.path, Math.round(120f * density));
            if (bmp != null) {
                thumbView.setImageBitmap(bmp);
            } else {
                thumbView.setImageResource(R.drawable.ic_mtrl_image);
            }
            cell.addView(thumbView);

            ImageView removeBtn = new ImageView(ctx);
            FrameLayout.LayoutParams badgeParams =
                    new FrameLayout.LayoutParams(
                            badgeSize, badgeSize, Gravity.TOP | Gravity.END);
            int margin = Math.round(3f * density);
            badgeParams.setMargins(margin, margin, margin, margin);
            removeBtn.setLayoutParams(badgeParams);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.OVAL);
            badgeBg.setColor(0xBB000000);
            removeBtn.setBackground(badgeBg);
            int iconPad = Math.round(3f * density);
            removeBtn.setPadding(iconPad, iconPad, iconPad, iconPad);
            removeBtn.setImageResource(R.drawable.ic_mtrl_close);
            removeBtn.setColorFilter(0xFFFFFFFF);
            removeBtn.setOnClickListener(v -> {
                pendingAttachments.remove(att);
                refreshAttachmentStrip();
                updateActionButton();
            });
            cell.addView(removeBtn);
            binding.attachmentStrip.addView(cell);
        }
    }

    private void sendCurrentInput() {
        String text = binding.chatInput.getText() == null
                ? "" : binding.chatInput.getText().toString().trim();
        if (text.isEmpty() && pendingAttachments.isEmpty()) {
            return;
        }

        cancelPendingChoiceSheet();
        if (providerConfig == null || !providerConfig.isConfigured()) {
            appendMessage(ChatMessage.error(getString(R.string.agent_provider_missing)));
            return;
        }

        binding.chatInput.setText("");
        ChatMessage userMsg = ChatMessage.user(text);
        for (ChatMessage.Attachment att : pendingAttachments) {
            userMsg.attachments.add(att.copy());
        }
        pendingAttachments.clear();
        refreshAttachmentStrip();

        appendMessage(userMsg);
        persistChat();
        lastUserText = !text.isEmpty()
                ? text
                : "Attached image (" + userMsg.attachments.size() + ")";
        ChatMessage reply = ChatMessage.assistant("");
        streamingMessage = reply;
        appendMessage(reply);
        startRequest();
    }

    /** Submits an {@code @ToAssistant} action from an interactive OpenUI bubble. */
    private void sendOpenUiInteraction(String promptText,
                                       com.google.gson.JsonElement context,
                                       com.google.gson.JsonObject formState) {
        if (generating) {
            if (getContext() != null) {
                Toast.makeText(getContext(),
                        "Wait for the current reply to finish first.",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String base = promptText == null ? "" : promptText.trim();
        if (base.isEmpty()) {
            return;
        }
        if (providerConfig == null || !providerConfig.isConfigured()) {
            appendMessage(ChatMessage.error(getString(R.string.agent_provider_missing)));
            return;
        }

        StringBuilder full = new StringBuilder(base);
        StringBuilder details = new StringBuilder();
        if (formState != null && formState.size() > 0) {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
                    : formState.entrySet()) {
                com.google.gson.JsonElement v = entry.getValue();
                if (v == null || v.isJsonNull()) {
                    continue;
                }
                String rendered = v.isJsonPrimitive()
                        ? v.getAsString() : v.toString();
                if (rendered.trim().isEmpty()) {
                    continue;
                }
                if (details.length() > 0) {
                    details.append(" · ");
                }
                details.append(entry.getKey()).append(": ").append(rendered);
            }
        }
        if (context != null && !context.isJsonNull()) {
            String ctxText = context.isJsonPrimitive()
                    ? context.getAsString() : context.toString();
            if (!ctxText.trim().isEmpty() && !ctxText.equals("{}")) {
                if (details.length() > 0) {
                    details.append(" · ");
                }
                details.append(ctxText);
            }
        }
        if (details.length() > 0) {
            full.append("\n(").append(details).append(')');
        }

        String finalText = full.toString();
        ChatMessage userMsg = ChatMessage.user(finalText);
        appendMessage(userMsg);
        persistChat();
        lastUserText = finalText;
        ChatMessage reply = ChatMessage.assistant("");
        streamingMessage = reply;
        appendMessage(reply);
        startRequest();
    }

    private void openExternalUrl(String url) {
        if (getContext() == null || url == null || url.trim().isEmpty()) {
            return;
        }
        String clean = url.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://" + clean;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(clean));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(),
                    "Couldn't open link: " + clean,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Sends the current history (ending in {@link #lastUserText}) off. */
    private void startRequest() {
        if (providerConfig == null || streamingMessage == null) {
            return;
        }
        generating = true;
        liveStepLabel = "";
        stripDismissedText = "";
        choiceSheetShownThisTurn = false;
        requestStartedAt = SystemClock.elapsedRealtime();
        updateActionButton();
        refreshStrip();
        // An honest stage line: with no style or no plan saved yet,
        // the turn ahead includes settling them.
        String scId = providerConfig.workingProjectId();
        if (!scId.isEmpty() && (AgentJournal.styleBrief(scId).isEmpty()
                || !AgentJournal.hasPlan(scId))) {
            insertToolNote(getString(R.string.agent_note_planning));
        }
        List<ChatMessage> history = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == streamingMessage || message.role == ChatMessage.Role.SYSTEM
                    || message.role == ChatMessage.Role.APPROVAL) {
                continue;
            }
            if (message.role == ChatMessage.Role.ASSISTANT && message.text.trim().isEmpty()) {
                continue;
            }
            if (message.role == ChatMessage.Role.USER
                    && message.text.trim().isEmpty()
                    && !message.hasAttachments()) {
                continue;
            }
            history.add(message);
        }
        runAgentTurn(history);
    }

    /**
     * One agent turn: the model may use read-only tools to look at the
     * working project before answering. Providers that cannot do tool
     * calling fall back to the plain streaming conversation below.
     */
    private void runAgentTurn(List<ChatMessage> history) {
        AiProviderConfig.Settings settings = providerConfig.activeSettings();
        // ✨ Auto router pool: the active provider's models, for the
        // per-turn pick and mid-turn failover.
        agentLoop.autoModelPool = settings.models == null
                ? new ArrayList<>() : settings.models;
        agentLoop.autoShowAll = settings.showAllModels;
        agentLoop.run(settings.baseUrl,
                providerConfig.apiKey(providerConfig.activeProvider()),
                settings.model, history,
                providerConfig.workingProjectId(),
                providerConfig.allowAgentModify(),
                new AgentLoop.Listener() {
                    @Override
                    public void onToolNote(String line) {
                        runOnUi(() -> {
                            liveStepLabel = line == null ? "" : line.trim();
                            insertToolNote(line);
                            refreshStrip();
                        });
                    }

                    @Override
                    public void onSystemNote(String line) {
                        runOnUi(() -> {
                            if (line != null && !line.trim().isEmpty()) {
                                appendMessage(new ChatMessage(
                                        ChatMessage.Role.SYSTEM,
                                        line.trim()));
                            }
                        });
                    }

                    @Override
                    public void onResolvedModel(String modelId) {
                        runOnUi(() -> {
                            if (modelId == null || modelId.isEmpty()) {
                                return;
                            }
                            // Remember it beyond this turn: the chat
                            // bar keeps naming the model that works,
                            // even after restarts.
                            AiProviderConfig.Settings settings =
                                    providerConfig.activeSettings();
                            settings.lastAutoModel = modelId;
                            providerConfig.save(requireContext());
                            if (binding != null) {
                                binding.modelLabel.setText("✨ Auto · "
                                        + ModelCatalog.displayName(
                                        modelId));
                            }
                        });
                    }

                    @Override
                    public void onAnswer(String text, String thinking) {
                        runOnUi(() -> {
                            liveStepLabel = "";
                            clearLiveNote();
                            generating = false;
                            if (streamingMessage != null) {
                                streamingMessage.thinking = thinking;
                            }
                            appendToken(text);
                            flushPendingTokens();
                            setStreamingComplete();
                            updateActionButton();
                            refreshStrip();
                            persistChat();
                        });
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        runOnUi(() -> {
                            if (isToolUnsupported(error)) {
                                startStreamingTurn(history);
                                return;
                            }
                            liveStepLabel = "";
                            clearLiveNote();
                            generating = false;
                            handleError(error);
                            updateActionButton();
                            refreshStrip();
                            persistChat();
                        });
                    }

                    @Override
                    public boolean onApproval(AgentProposal proposal) {
                        // The card now says what is being proposed;
                        // the heartbeat line has done its job.
                        runOnUi(() -> {
                            clearLiveNote();
                            liveStepLabel = proposal != null
                                    && proposal.title != null
                                    && !proposal.title.isEmpty()
                                    ? "Waiting for approval · " + proposal.title
                                    : "Waiting for approval";
                            refreshStrip();
                        });
                        return awaitApproval(proposal);
                    }

                    @Override
                    public com.google.gson.JsonObject onPickColor(
                            String question, String initialHex) {
                        return awaitColorPick(question, initialHex);
                    }

                    @Override
                    public String onAskChoice(String question,
                                              java.util.List<String> options) {
                        // Marked HERE, not in awaitChoice: the flag must
                        // mean "the model asked for a choice properly"
                        // — the bounce thread must never set it.
                        choiceSheetShownThisTurn = true;
                        return awaitChoice(question, options);
                    }

                    @Override
                    public void onApprovalResolved(AgentProposal proposal,
                                                   com.google.gson.JsonObject result) {
                        runOnUi(() -> {
                            liveStepLabel = "";
                            resolveApprovalCard(proposal, result);
                            if (proposal != null
                                    && "set_app_icon".equals(proposal.tool)) {
                                updateWorkingOnCard();
                            }
                            refreshStrip();
                        });
                    }

                    @Override
                    public void onWorkingProjectChanged(String scId) {
                        runOnUi(() -> {
                            providerConfig.setWorkingProjectId(scId);
                            providerConfig.save(requireContext());
                            // The conversation that created this project
                            // belongs to it from here on.
                            chatProjectId = scId;
                            persistChat();
                            updateWorkingOnCard();
                            refreshStrip();
                        });
                    }

                    @Override
                    public void onStateChanged() {
                        runOnUi(AgentFragment.this::refreshStrip);
                    }
                });
    }

    /** True when the provider rejected the tool definitions themselves. */
    private boolean isToolUnsupported(AiChatService.AiError error) {
        if (error.kind != AiChatService.ErrorKind.HTTP || error.message == null) {
            return false;
        }
        String message = error.message.toLowerCase(java.util.Locale.ROOT);
        return message.contains("tool") || message.contains("function");
    }

    /** The plain streaming conversation, kept as the safety net. */
    private void startStreamingTurn(List<ChatMessage> history) {
        AiProviderConfig.Settings settings = providerConfig.activeSettings();
        chatService.chat(settings.baseUrl, providerConfig.apiKey(providerConfig.activeProvider()),
                settings.model, history, new AiChatService.ChatListener() {
                    @Override
                    public void onThinking() {
                        // The typing dots already show the model is alive;
                        // no status text is painted into the bubble.
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
                        refreshStrip();
                        persistChat();
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        generating = false;
                        handleError(error);
                        updateActionButton();
                        refreshStrip();
                        persistChat();
                    }
                });
    }

    private void stopGeneration() {
        closePendingCard(getString(
                R.string.agent_approval_not_applied_stopped));
        releaseApproval(false);
        if (agentLoop != null) {
            agentLoop.cancel();
        }
        if (chatService != null) {
            chatService.cancel();
        }
        if (providerConfig != null) {
            String scId = providerConfig.workingProjectId();
            if (!scId.isEmpty()) {
                AgentJournal.endTurn(scId, false, "you stopped it");
            }
        }
        liveStepLabel = "";
        clearLiveNote();
        generating = false;
        setStreamingComplete();
        updateActionButton();
        refreshStrip();
        persistChat();
    }

    /** Binds the card to the chosen project, or to the empty state. */
    private void updateWorkingOnCard() {
        if (binding == null || providerConfig == null) {
            return;
        }
        String scId = providerConfig.workingProjectId();
        if (scId.isEmpty()) {
            workingProjectDisplayName = "";
            binding.workingOnOverline.setText(R.string.agent_working_overline_empty);
            binding.workingOnName.setText(R.string.agent_working_on_choose);
            binding.workingOnSubtitle.setText(R.string.agent_working_on_hint);
            binding.workingOnIcon.setImageResource(R.drawable.sketch_app_icon);
            setCardCompact(false);
            updateEmptyState();
            refreshStrip();
            return;
        }
        io.execute(() -> {
            SketchwareProjects.ProjectInfo project = SketchwareProjects.project(scId);
            Bitmap customIcon = null;
            try {
                java.util.HashMap<String, Object> meta = lC.b(scId);
                if (meta != null && yB.a(meta, "custom_icon")) {
                    String iconPath = wq.e() + File.separator + scId
                            + File.separator + "icon.png";
                    customIcon = AgentMedia.decodeThumbnail(iconPath, 128);
                }
            } catch (Exception ignored) {
            }
            final Bitmap resolvedIcon = customIcon;
            runOnUi(() -> {
                if (binding == null) {
                    return;
                }
                if (project == null) {
                    workingProjectDisplayName = "";
                    binding.workingOnOverline.setText(
                            R.string.agent_working_overline_empty);
                    binding.workingOnName.setText(R.string.agent_working_on_choose);
                    binding.workingOnSubtitle.setText(R.string.agent_working_on_hint);
                    binding.workingOnIcon.setImageResource(R.drawable.sketch_app_icon);
                    setCardCompact(false);
                } else {
                    workingProjectDisplayName = project.appName != null
                            && !project.appName.isEmpty()
                            ? project.appName : project.name;
                    binding.workingOnOverline.setText(R.string.agent_working_overline);
                    binding.workingOnName.setText(project.name);
                    binding.workingOnSubtitle.setText(
                            project.appName + " · " + project.packageName);
                    if (resolvedIcon != null) {
                        binding.workingOnIcon.setImageBitmap(resolvedIcon);
                    } else {
                        binding.workingOnIcon.setImageResource(
                                R.drawable.sketch_app_icon);
                    }
                    // Chosen: the card settles into a compact chip so
                    // the chat gets the room.
                    setCardCompact(true);
                }
                updateEmptyState();
                refreshStrip();
            });
        });
    }

    /** Shrinks the project card once a project is chosen. */
    private void setCardCompact(boolean compact) {
        if (binding == null) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int icon = (int) ((compact ? 32 : 40) * density + 0.5f);
        ViewGroup.LayoutParams iconParams =
                binding.workingOnIcon.getLayoutParams();
        if (iconParams.width != icon) {
            iconParams.width = icon;
            iconParams.height = icon;
            binding.workingOnIcon.setLayoutParams(iconParams);
        }
        int padding = (int) ((compact ? 8 : 12) * density + 0.5f);
        binding.workingOnContent.setPadding(
                padding, padding, padding, padding);
    }

    /** The project list behind the Working-On card: the projects the way
     * the app's own project list shows them. */
    private void showProjectPicker() {
        if (generating) {
            Toast.makeText(getContext(),
                    R.string.agent_project_locked_working,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        io.execute(() -> {
            List<SketchwareProjects.ProjectInfo> projects =
                    SketchwareProjects.listProjects();
            runOnUi(() -> {
                if (binding == null) {
                    return;
                }
                View picker = LayoutInflater.from(requireContext())
                        .inflate(R.layout.agent_project_picker, null);
                ListView list = picker.findViewById(R.id.picker_list);
                list.setAdapter(new ProjectPickerAdapter(projects,
                        providerConfig.workingProjectId()));
                androidx.appcompat.app.AlertDialog dialog =
                        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                .setView(picker)
                                .setNegativeButton(android.R.string.cancel, null)
                                .create();
                list.setOnItemClickListener((parent, view, position, id) -> {
                    dialog.dismiss();
                    if (position == 0) {
                        chooseProject("", "");
                    } else {
                        SketchwareProjects.ProjectInfo chosen =
                                projects.get(position - 1);
                        chooseProject(chosen.scId, chosen.name);
                    }
                });
                dialog.show();
            });
        });
    }

    /** Binds (or unbinds) the working project and notes it in the chat. */
    private void chooseProject(String scId, String name) {
        providerConfig.setWorkingProjectId(scId);
        providerConfig.save(requireContext());
        stripDismissedText = "";
        // Each project keeps its own conversation: the old one is
        // stored away and this project's chat comes back on screen.
        persistChat();
        chatProjectId = scId;
        messages.clear();
        if (!scId.isEmpty()) {
            messages.addAll(AgentChatLog.load(scId));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (!messages.isEmpty() && binding != null) {
            binding.chatList.scrollToPosition(messages.size() - 1);
        }
        updateWorkingOnCard();
        if (!scId.isEmpty() && messages.isEmpty()) {
            // Keep the clean welcome card visible when switching into an untouched project
            updateEmptyState();
        } else {
            appendMessage(ChatMessage.error(getString(scId.isEmpty()
                    ? R.string.agent_working_on_cleared
                    : R.string.agent_working_on_set, name)));
        }
    }

    /** Stores the on-screen conversation under its project. */
    private void persistChat() {
        if (!chatProjectId.isEmpty()) {
            AgentChatLog.save(chatProjectId, messages);
        }
    }

    /**
     * The strip above the composer: the one place a task's state shows
     * outside the chat. It speaks only while work runs or sits paused —
     * finished work needs no strip.
     */
    private void refreshStrip() {
        if (binding == null || providerConfig == null) {
            return;
        }
        String scId = providerConfig.workingProjectId();
        String text = "";
        boolean resumable = false;
        if (generating) {
            String stepBadge = !scId.isEmpty()
                    ? currentPlanStepBadge(scId) : "";
            String planLine = !scId.isEmpty()
                    ? currentPlanStepLine(scId) : "";
            if (!stepBadge.isEmpty() && !liveStepLabel.isEmpty()) {
                text = stepBadge + " · " + liveStepLabel;
            } else if (!planLine.isEmpty()) {
                text = planLine;
            } else if (!liveStepLabel.isEmpty()) {
                text = liveStepLabel;
            } else {
                String title = !scId.isEmpty()
                        ? AgentJournal.openTitle(scId) : "";
                if (title.isEmpty()) {
                    title = lastUserText;
                }
                if (!title.isEmpty()) {
                    text = getString(R.string.agent_task_working, title);
                }
            }
        } else if (!scId.isEmpty()) {
            text = AgentJournal.stripText(scId);
            resumable = !text.isEmpty();
        }
        stripShownText = text;
        boolean show = !text.isEmpty()
                && (generating || !text.equals(stripDismissedText));
        binding.taskStrip.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            binding.taskStripText.setText(text);
            binding.taskStripResume.setVisibility(
                    resumable ? View.VISIBLE : View.GONE);
            binding.taskStripDismiss.setVisibility(
                    generating ? View.GONE : View.VISIBLE);
        }
        updatePlanButtonBadge(scId);
    }

    /** Updates the Plan chip label with live X/Y progress when a plan exists. */
    private void updatePlanButtonBadge(String scId) {
        if (binding == null) {
            return;
        }
        if (scId != null && !scId.isEmpty()) {
            int[] progress = AgentJournal.planProgress(scId);
            if (progress[1] > 0) {
                binding.planButton.setText(
                        getString(R.string.agent_plan_button)
                                + " (" + progress[0] + "/" + progress[1] + ")");
                return;
            }
        }
        binding.planButton.setText(R.string.agent_plan_button);
    }

    /** "Step 2/6" badge for the active plan step, or "" when none is active. */
    private String currentPlanStepBadge(String scId) {
        com.google.gson.JsonObject plan = AgentJournal.plan(scId);
        if (!plan.has("steps")) {
            return "";
        }
        com.google.gson.JsonArray steps = plan.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            com.google.gson.JsonObject step = steps.get(i).getAsJsonObject();
            String state = step.has("state")
                    ? step.get("state").getAsString() : "";
            if ("current".equals(state) || "stopped".equals(state)) {
                return "Step " + (i + 1) + "/" + steps.size();
            }
        }
        return "";
    }

    /** "Plan step 3 of 7 · ..." for the step in hand, or "". */
    private String currentPlanStepLine(String scId) {
        com.google.gson.JsonObject plan = AgentJournal.plan(scId);
        if (!plan.has("steps")) {
            return "";
        }
        com.google.gson.JsonArray steps = plan.getAsJsonArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            com.google.gson.JsonObject step =
                    steps.get(i).getAsJsonObject();
            String state = step.has("state")
                    ? step.get("state").getAsString() : "";
            if ("current".equals(state) || "stopped".equals(state)) {
                String stepText = step.has("title")
                        ? step.get("title").getAsString()
                        : step.has("text")
                                ? step.get("text").getAsString()
                                : "";
                return getString(R.string.agent_plan_step_line, i + 1,
                        steps.size(),
                        AgentJournal.cleanStepText(stepText));
            }
        }
        return "";
    }

    /** Continues the paused task cleanly without clobbering any draft in chatInput. */
    private void resumeTask() {
        if (generating || binding == null || providerConfig == null) {
            return;
        }
        if (!providerConfig.isConfigured()) {
            appendMessage(ChatMessage.error(
                    getString(R.string.agent_provider_missing)));
            return;
        }
        stripDismissedText = "";
        cancelPendingChoiceSheet();
        String scId = providerConfig.workingProjectId();
        AgentJournal.reopenLatest(scId);
        String visiblePrompt = "Resume unfinished work";
        appendMessage(ChatMessage.user(visiblePrompt));
        lastUserText = getString(R.string.agent_task_resume_message);
        ChatMessage reply = ChatMessage.assistant("");
        streamingMessage = reply;
        appendMessage(reply);
        startRequest();
    }

    /** The working project, or a nudge to pick one first. */
    private String workingProjectOrToast() {
        String scId = providerConfig == null
                ? "" : providerConfig.workingProjectId();
        if (scId.isEmpty()) {
            Toast.makeText(getContext(), R.string.agent_pick_project_first,
                    Toast.LENGTH_SHORT).show();
        }
        return scId;
    }

    /**
     * Journal home for the Style → Design system → Plan gate: the
     * working project, or the conversation's draft journal when no
     * project exists yet. The gate runs BEFORE creation, so these
     * pages must work before creation too.
     */
    private String journalTarget() {
        String scId = providerConfig == null
                ? "" : providerConfig.workingProjectId();
        return scId.isEmpty() ? AgentJournal.DRAFT_KEY : scId;
    }

    /** The Plan button: the project's agreed style direction. */
    /**
     * The design system: a list of the project's components. Tapping one
     * opens what it is made of — and shows it, drawn with those exact
     * measurements, because a component is something you look at, not
     * something you read.
     */
    private boolean designPreviewDarkMode = false;

    private void showDesignSystemDialog() {
        String scId = journalTarget();
        com.google.gson.JsonObject system = AgentJournal.designSystem(scId);
        if (!AgentJournal.hasDesignSystem(scId)) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.agent_design_system_title)
                    .setMessage(R.string.agent_design_system_empty)
                    .setNeutralButton("Presets / Import JSON",
                            (d, w) -> showDesignPresetsDialog(scId, null))
                    .setPositiveButton(R.string.agent_action_close, null)
                    .show();
            return;
        }

        OpenUiThemeEngine.ResolvedTheme resolved =
                OpenUiThemeEngine.resolveTheme(system);
        com.google.gson.JsonElement stored = system.get("tokens");
        com.google.gson.JsonArray tokens = (stored != null && stored.isJsonArray()
                && stored.getAsJsonArray().size() > 0)
                ? stored.getAsJsonArray()
                : OpenUiThemeEngine.generateStandardComponentTokens(resolved);
        java.util.Map<String, String> activePalette = designPreviewDarkMode
                ? resolved.darkColors : resolved.lightColors;

        android.content.Context context = requireContext();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(16f * density);
        LinearLayout listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(pad, Math.round(8f * density), pad, pad);

        androidx.appcompat.app.AlertDialog[] holder =
                new androidx.appcompat.app.AlertDialog[1];
        Runnable reopen = () -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            showDesignSystemDialog();
        };

        // 1. Light / Dark Mode Preview Toggle + Contrast Badge
        LinearLayout modeRow = new LinearLayout(context);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams modeRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        modeRowLp.bottomMargin = Math.round(10f * density);
        modeRow.setLayoutParams(modeRowLp);

        android.widget.TextView lightChip = buildSmallPillButton(
                context, "☀ Light", !designPreviewDarkMode);
        lightChip.setOnClickListener(v -> {
            if (designPreviewDarkMode) {
                designPreviewDarkMode = false;
                reopen.run();
            }
        });
        modeRow.addView(lightChip);

        android.widget.TextView darkChip = buildSmallPillButton(
                context, "☾ Dark", designPreviewDarkMode);
        LinearLayout.LayoutParams darkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        darkLp.leftMargin = Math.round(6f * density);
        darkChip.setLayoutParams(darkLp);
        darkChip.setOnClickListener(v -> {
            if (!designPreviewDarkMode) {
                designPreviewDarkMode = true;
                reopen.run();
            }
        });
        modeRow.addView(darkChip);

        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        modeRow.addView(spacer);

        int accentInt = parseSafeColor(activePalette.get("openui_accent"), 0xFF3B82F6);
        int onAccentInt = parseSafeColor(activePalette.get("openui_text_on_accent"), 0xFFFFFFFF);
        double ratio = OpenUiThemeEngine.contrastRatio(onAccentInt, accentInt);
        android.widget.TextView wcagBadge = new android.widget.TextView(context);
        wcagBadge.setText(String.format(java.util.Locale.US, "WCAG %.1f:1 ✓", ratio));
        wcagBadge.setTextSize(11.5f);
        wcagBadge.setTypeface(wcagBadge.getTypeface(), android.graphics.Typeface.BOLD);
        wcagBadge.setTextColor(resolveThemeColor(context,
                pro.sketchware.R.attr.colorPrimary, 0xFF008DCD));
        modeRow.addView(wcagBadge);
        listContainer.addView(modeRow);

        // 0. Mood & Audience summary (the retired Style pill, folded in)
        String moodSummary = AgentJournal.designSystemSummary(scId);
        LinearLayout summaryBanner = new LinearLayout(context);
        summaryBanner.setOrientation(LinearLayout.HORIZONTAL);
        summaryBanner.setGravity(Gravity.CENTER_VERTICAL);
        summaryBanner.setPadding(Math.round(14f * density),
                Math.round(10f * density), Math.round(14f * density),
                Math.round(10f * density));
        GradientDrawable summaryBg = new GradientDrawable();
        summaryBg.setCornerRadius(14f * density);
        summaryBg.setColor(resolveThemeColor(context,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                0xFFE6EFF8));
        summaryBg.setStroke(Math.max(1, Math.round(density)),
                resolveThemeColor(context,
                        com.google.android.material.R.attr.colorOutlineVariant,
                        0xFFB1C0CE));
        summaryBanner.setBackground(summaryBg);
        android.widget.TextView summaryText = new android.widget.TextView(context);
        summaryText.setText(moodSummary.isEmpty()
                ? "Mood & Audience: not set — tap to describe who this "
                        + "app is for and how it should feel"
                : "Mood & Audience: " + moodSummary);
        summaryText.setTextSize(12.5f);
        summaryText.setAlpha(moodSummary.isEmpty() ? 0.75f : 1f);
        summaryText.setMaxLines(3);
        summaryText.setTextColor(resolveThemeColor(context,
                pro.sketchware.R.attr.colorOnSurface,
                0xFF1C2B36));
        LinearLayout.LayoutParams summaryTextLp =
                new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        summaryText.setLayoutParams(summaryTextLp);
        summaryBanner.addView(summaryText);
        summaryBanner.setOnClickListener(v -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            showStyleBriefEditor(scId);
        });
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.bottomMargin = Math.round(10f * density);
        summaryBanner.setLayoutParams(summaryLp);
        listContainer.addView(summaryBanner);

        // 2. Live Rendered OpenUI Mini-Screen Preview Card
        listContainer.addView(buildLiveThemeStage(context, resolved, activePalette));

        // 3. One-Tap Theme Controls (100% Customizable)
        android.widget.HorizontalScrollView controlsScroll =
                new android.widget.HorizontalScrollView(context);
        controlsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        csLp.topMargin = Math.round(10f * density);
        csLp.bottomMargin = Math.round(8f * density);
        controlsScroll.setLayoutParams(csLp);

        LinearLayout controlsRow = new LinearLayout(context);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.TextView brandPill = buildSmallPillButton(
                context, "● Brand " + resolved.brandColor, false);
        brandPill.setOnClickListener(v -> showBrandColorDialog(scId, resolved.brandColor, reopen));
        controlsRow.addView(brandPill);

        addSpacingPill(context, controlsRow, "Corners: " + resolved.cornerProfile,
                v -> showOptionPickerDialog(scId, "corner_profile", "Corner profile",
                        new String[]{"sharp", "balanced", "soft", "pill"},
                        resolved.cornerProfile, reopen));
        addSpacingPill(context, controlsRow, "Density: " + resolved.density,
                v -> showOptionPickerDialog(scId, "density", "Spacing density",
                        new String[]{"compact", "comfortable", "spacious"},
                        resolved.density, reopen));
        addSpacingPill(context, controlsRow, "Neutral: " + resolved.neutralScale,
                v -> showOptionPickerDialog(scId, "neutral_scale", "Surface undertone",
                        new String[]{"neutral", "slate", "gray", "zinc", "stone", "warm"},
                        resolved.neutralScale, reopen));
        addSpacingPill(context, controlsRow, "Mode: " + resolved.themeMode,
                v -> showOptionPickerDialog(scId, "theme_mode", "App theme mode",
                        new String[]{"DayNight", "Light", "Dark"},
                        resolved.themeMode, reopen));
        controlsScroll.addView(controlsRow);
        listContainer.addView(controlsScroll);

        // 4. Presets / Custom JSON & Sync to Project Action Row
        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.bottomMargin = Math.round(12f * density);
        actionRow.setLayoutParams(actLp);

        android.widget.TextView presetsBtn = buildSmallPillButton(
                context, "Presets / Custom JSON", false);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pLp.rightMargin = Math.round(6f * density);
        presetsBtn.setLayoutParams(pLp);
        presetsBtn.setGravity(Gravity.CENTER);
        presetsBtn.setOnClickListener(v -> showDesignPresetsDialog(scId, reopen));
        actionRow.addView(presetsBtn);

        android.widget.TextView syncBtn = buildSmallPillButton(
                context, "Sync theme to project", true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        syncBtn.setLayoutParams(sLp);
        syncBtn.setGravity(Gravity.CENTER);
        syncBtn.setOnClickListener(v -> {
            com.google.gson.JsonObject full = OpenUiThemeEngine.buildDesignSystemJson(
                    resolved, tokens);
            AgentJournal.setDesignSystemObject(scId, full);
            syncThemeResourcesWithToast(context, scId, full,
                    "Synced Light/Dark colors.xml, styles.xml & openui_* drawables");
        });
        actionRow.addView(syncBtn);
        listContainer.addView(actionRow);

        // 4b. Theme history: Undo, back to the Original, and the
        // summary editor — trying a preset can never trap the user.
        int undoCount = AgentJournal.designSystemUndoCount(scId);
        boolean hasOriginal = AgentJournal.hasOriginalDesignSystem(scId);
        if (undoCount > 0 || hasOriginal) {
            LinearLayout historyRow = new LinearLayout(context);
            historyRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            histLp.bottomMargin = Math.round(12f * density);
            historyRow.setLayoutParams(histLp);
            if (undoCount > 0) {
                android.widget.TextView undoBtn = buildSmallPillButton(
                        context, "↶ Undo (" + undoCount + ")", false);
                LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                uLp.rightMargin = Math.round(6f * density);
                undoBtn.setLayoutParams(uLp);
                undoBtn.setGravity(Gravity.CENTER);
                undoBtn.setOnClickListener(v -> {
                    com.google.gson.JsonObject restored =
                            AgentJournal.undoDesignSystem(scId);
                    if (restored == null) {
                        Toast.makeText(context,
                                "No earlier theme to undo to",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    syncThemeResourcesWithToast(context, scId, restored,
                            "Theme stepped back ↶");
                    reopen.run();
                });
                historyRow.addView(undoBtn);
            }
            if (hasOriginal) {
                android.widget.TextView originalBtn = buildSmallPillButton(
                        context, "↺ Original theme", false);
                LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                oLp.leftMargin = undoCount > 0
                        ? Math.round(6f * density) : 0;
                originalBtn.setLayoutParams(oLp);
                originalBtn.setGravity(Gravity.CENTER);
                originalBtn.setOnClickListener(v -> {
                    com.google.gson.JsonObject restored =
                            AgentJournal.restoreOriginalDesignSystem(scId);
                    if (restored == null) {
                        Toast.makeText(context,
                                "This project has no original snapshot yet",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    syncThemeResourcesWithToast(context, scId, restored,
                            "Original theme restored ↺");
                    reopen.run();
                });
                historyRow.addView(originalBtn);
            }
            listContainer.addView(historyRow);
        }

        // 5. Standardized Component Tokens List
        for (int i = 0; i < tokens.size(); i++) {
            com.google.gson.JsonElement element = tokens.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject token = element.getAsJsonObject();
            String label = AgentJournal.textOf(token, "label");
            String key = AgentJournal.textOf(token, "key");
            String kind = AgentJournal.textOf(token, "kind");
            String title = label.isEmpty() ? key : label;
            com.google.gson.JsonObject props = token.has("props")
                    && token.get("props").isJsonObject()
                    ? token.getAsJsonObject("props")
                    : new com.google.gson.JsonObject();

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rowPadH = Math.round(14f * density);
            int rowPadV = Math.round(12f * density);
            row.setPadding(rowPadH, rowPadV, rowPadH, rowPadV);
            GradientDrawable rowBg = new GradientDrawable();
            rowBg.setCornerRadius(16f * density);
            rowBg.setColor(resolveThemeColor(context,
                    com.google.android.material.R.attr.colorSurfaceContainerLow,
                    0xFFE6EFF8));
            rowBg.setStroke(Math.max(1, Math.round(density)),
                    resolveThemeColor(context,
                            com.google.android.material.R.attr.colorOutlineVariant,
                            0xFFB1C0CE));
            row.setBackground(rowBg);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = Math.round(8f * density);
            row.setLayoutParams(rowLp);

            View swatch = new View(context);
            int swSize = Math.round(36f * density);
            swatch.setLayoutParams(new LinearLayout.LayoutParams(swSize, swSize));
            GradientDrawable swBg = new GradientDrawable();
            swBg.setCornerRadius(10f * density);
            String swatchSourceKey = "text".equals(kind) ? "text_color" : "background";
            swBg.setColor(designColourWithPalette(
                    props, swatchSourceKey, activePalette, resolved.brandColor));
            swBg.setStroke(Math.max(1, Math.round(density)), 0x33888888);
            swatch.setBackground(swBg);
            row.addView(swatch);

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = Math.round(12f * density);
            textCol.setLayoutParams(colLp);

            android.widget.TextView tvTitle = new android.widget.TextView(context);
            tvTitle.setText(title);
            tvTitle.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodyLarge);
            tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
            textCol.addView(tvTitle);

            String summaryLine = summarizeTokenProps(kind, props);
            if (!summaryLine.isEmpty()) {
                android.widget.TextView tvSub = new android.widget.TextView(context);
                tvSub.setText(summaryLine);
                tvSub.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_BodySmall);
                tvSub.setAlpha(0.78f);
                textCol.addView(tvSub);
            }
            row.addView(textCol);

            row.setOnClickListener(v -> {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                showDesignTokenDialog(scId, token);
            });
            listContainer.addView(row);
        }

        String dialogTitle = resolved.title.isEmpty()
                ? getString(R.string.agent_design_system_title)
                : resolved.title;
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(listContainer);
        holder[0] = new MaterialAlertDialogBuilder(context)
                .setTitle(dialogTitle)
                .setView(scroll)
                .setNegativeButton(R.string.agent_action_close, null)
                .create();
        holder[0].show();
    }

    private View buildLiveThemeStage(Context context,
                                     OpenUiThemeEngine.ResolvedTheme resolved,
                                     java.util.Map<String, String> pal) {
        float d = context.getResources().getDisplayMetrics().density;
        int bgInt = parseSafeColor(pal.get("openui_bg"), 0xFFFCFCFC);
        int fgInt = parseSafeColor(pal.get("openui_fg"), 0xFFFFFFFF);
        int sunkInt = parseSafeColor(pal.get("openui_sunk"), 0xFFF5F5F5);
        int textPrim = parseSafeColor(pal.get("openui_text_primary"), 0xFF171717);
        int textSec = parseSafeColor(pal.get("openui_text_secondary"), 0xFF525252);
        int textTert = parseSafeColor(pal.get("openui_text_tertiary"), 0xFF737373);
        int borderDef = parseSafeColor(pal.get("openui_border_default"), 0xFFE5E5E5);
        int borderInt = parseSafeColor(pal.get("openui_border_interactive"), 0xFFD4D4D4);
        int accent = parseSafeColor(pal.get("openui_accent"), 0xFF3B82F6);
        int onAccent = parseSafeColor(pal.get("openui_text_on_accent"), 0xFFFFFFFF);

        int cardRad = Math.round(Math.min(24, OpenUiThemeEngine.profileRadius(
                resolved.cornerProfile, "card", 16)) * d);
        int ctrlRad = Math.round(Math.min(999, OpenUiThemeEngine.profileRadius(
                resolved.cornerProfile, "button", 12)) * d);
        int chipRad = Math.round(Math.min(999, OpenUiThemeEngine.profileRadius(
                resolved.cornerProfile, "chip", 999)) * d);

        LinearLayout canvas = new LinearLayout(context);
        canvas.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14f * d);
        canvas.setPadding(pad, pad, pad, pad);
        GradientDrawable canvasBg = new GradientDrawable();
        canvasBg.setCornerRadius(18f * d);
        canvasBg.setColor(bgInt);
        canvasBg.setStroke(Math.max(1, Math.round(d)), borderDef);
        canvas.setBackground(canvasBg);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(cardRad);
        cardBg.setColor(fgInt);
        cardBg.setStroke(Math.max(1, Math.round(d)), borderDef);
        card.setBackground(cardBg);

        android.widget.TextView heading = new android.widget.TextView(context);
        heading.setText("Preview Card Heading");
        heading.setTextSize(17f);
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        heading.setTextColor(textPrim);
        card.addView(heading);

        android.widget.TextView sub = new android.widget.TextView(context);
        sub.setText("Paired Light & Dark tokens keep every surface readable.");
        sub.setTextSize(12.5f);
        sub.setTextColor(textSec);
        sub.setPadding(0, Math.round(2f * d), 0, Math.round(10f * d));
        card.addView(sub);

        android.widget.TextView inputBox = new android.widget.TextView(context);
        inputBox.setText("Input placeholder…");
        inputBox.setTextSize(13.5f);
        inputBox.setTextColor(textTert);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setPadding(Math.round(12f * d), 0, Math.round(12f * d), 0);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setCornerRadius(Math.round(OpenUiThemeEngine.profileRadius(
                resolved.cornerProfile, "input", 12) * d));
        inBg.setColor(sunkInt);
        inBg.setStroke(Math.max(1, Math.round(d)), borderInt);
        inputBox.setBackground(inBg);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(40f * d));
        inLp.bottomMargin = Math.round(10f * d);
        inputBox.setLayoutParams(inLp);
        card.addView(inputBox);

        LinearLayout chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.bottomMargin = Math.round(10f * d);
        chipsRow.setLayoutParams(crLp);

        android.widget.TextView c1 = new android.widget.TextView(context);
        c1.setText("Selected");
        c1.setTextSize(12f);
        c1.setTypeface(c1.getTypeface(), android.graphics.Typeface.BOLD);
        c1.setTextColor(onAccent);
        c1.setPadding(Math.round(12f * d), Math.round(5f * d),
                Math.round(12f * d), Math.round(5f * d));
        GradientDrawable c1Bg = new GradientDrawable();
        c1Bg.setCornerRadius(chipRad);
        c1Bg.setColor(accent);
        c1.setBackground(c1Bg);
        chipsRow.addView(c1);

        android.widget.TextView c2 = new android.widget.TextView(context);
        c2.setText("Filter chip");
        c2.setTextSize(12f);
        c2.setTextColor(textPrim);
        c2.setPadding(Math.round(12f * d), Math.round(5f * d),
                Math.round(12f * d), Math.round(5f * d));
        GradientDrawable c2Bg = new GradientDrawable();
        c2Bg.setCornerRadius(chipRad);
        c2Bg.setColor(sunkInt);
        c2Bg.setStroke(Math.max(1, Math.round(d)), borderInt);
        c2.setBackground(c2Bg);
        LinearLayout.LayoutParams c2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        c2Lp.leftMargin = Math.round(6f * d);
        c2.setLayoutParams(c2Lp);
        chipsRow.addView(c2);
        card.addView(chipsRow);

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        android.widget.TextView secBtn = new android.widget.TextView(context);
        secBtn.setText("Secondary");
        secBtn.setTextSize(13f);
        secBtn.setTypeface(secBtn.getTypeface(), android.graphics.Typeface.BOLD);
        secBtn.setTextColor(textPrim);
        secBtn.setGravity(Gravity.CENTER);
        GradientDrawable secBg = new GradientDrawable();
        secBg.setCornerRadius(ctrlRad);
        secBg.setColor(sunkInt);
        secBg.setStroke(Math.max(1, Math.round(d)), borderInt);
        secBtn.setBackground(secBg);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                0, Math.round(40f * d), 1f);
        sbLp.rightMargin = Math.round(8f * d);
        secBtn.setLayoutParams(sbLp);
        btnRow.addView(secBtn);

        android.widget.TextView priBtn = new android.widget.TextView(context);
        priBtn.setText("Primary Action");
        priBtn.setTextSize(13f);
        priBtn.setTypeface(priBtn.getTypeface(), android.graphics.Typeface.BOLD);
        priBtn.setTextColor(onAccent);
        priBtn.setGravity(Gravity.CENTER);
        GradientDrawable priBg = new GradientDrawable();
        priBg.setCornerRadius(ctrlRad);
        priBg.setColor(accent);
        priBtn.setBackground(priBg);
        priBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, Math.round(40f * d), 1.3f));
        btnRow.addView(priBtn);

        card.addView(btnRow);
        canvas.addView(card);
        return canvas;
    }

    private android.widget.TextView buildSmallPillButton(Context context,
                                                         String text,
                                                         boolean active) {
        float d = context.getResources().getDisplayMetrics().density;
        android.widget.TextView tv = new android.widget.TextView(context);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        int padH = Math.round(12f * d);
        int padV = Math.round(7f * d);
        tv.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(999f * d);
        if (active) {
            int prim = resolveThemeColor(context,
                    pro.sketchware.R.attr.colorPrimary, 0xFF008DCD);
            int onPrim = resolveThemeColor(context,
                    com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF);
            bg.setColor(prim);
            tv.setTextColor(onPrim);
        } else {
            bg.setColor(resolveThemeColor(context,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    0xFFE4ECF4));
            bg.setStroke(Math.max(1, Math.round(d)), resolveThemeColor(context,
                    com.google.android.material.R.attr.colorOutlineVariant,
                    0xFFB1C0CE));
            tv.setTextColor(resolveThemeColor(context,
                    com.google.android.material.R.attr.colorOnSurface, 0xFF141C23));
        }
        tv.setBackground(bg);
        return tv;
    }

    private void addSpacingPill(Context context,
                                LinearLayout row,
                                String text,
                                View.OnClickListener listener) {
        float d = context.getResources().getDisplayMetrics().density;
        android.widget.TextView pill = buildSmallPillButton(context, text, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(6f * d);
        pill.setLayoutParams(lp);
        pill.setOnClickListener(listener);
        row.addView(pill);
    }

    private void showOptionPickerDialog(String scId,
                                        String fieldKey,
                                        String title,
                                        String[] options,
                                        String current,
                                        Runnable onDone) {
        int checked = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(current)) {
                checked = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    dialog.dismiss();
                    com.google.gson.JsonObject updated =
                            AgentJournal.updateOpenUiThemeField(
                                    scId, fieldKey, options[which]);
                    syncThemeResourcesWithToast(requireContext(), scId, updated, null);
                    if (onDone != null) {
                        onDone.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showBrandColorDialog(String scId,
                                      String currentHex,
                                      Runnable onDone) {
        Context context = requireContext();
        float d = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(20f * d);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(10f * d), pad, 0);

        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint("#RRGGBB (any custom colour)");
        input.setText(currentHex);
        box.addView(input);

        String[] quickSwatches = {
                "#059669", "#C65D3B", "#2563EB", "#7C3AED",
                "#D97706", "#DC2626", "#0D9488", "#DB2777"
        };
        LinearLayout swRow = new LinearLayout(context);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = Math.round(12f * d);
        swRow.setLayoutParams(srLp);
        for (int i = 0; i < quickSwatches.length; i++) {
            final String hex = quickSwatches[i];
            View circle = new View(context);
            int sz = Math.round(30f * d);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(sz, sz);
            if (i > 0) {
                clp.leftMargin = Math.round(6f * d);
            }
            circle.setLayoutParams(clp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(parseSafeColor(hex, 0xFF3B82F6));
            circle.setBackground(gd);
            circle.setOnClickListener(v -> input.setText(hex));
            swRow.addView(circle);
        }
        box.addView(swRow);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Custom Brand Colour")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.agent_action_save, (dlg, w) -> {
                    String raw = input.getText() == null
                            ? "" : input.getText().toString().trim();
                    String norm = OpenUiThemeEngine.normalizeHex(raw, "");
                    if (norm.isEmpty()) {
                        Toast.makeText(context, "Enter a valid #RRGGBB colour",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    com.google.gson.JsonObject updated =
                            AgentJournal.updateOpenUiThemeField(
                                    scId, "brand_color", norm);
                    syncThemeResourcesWithToast(context, scId, updated, null);
                    if (onDone != null) {
                        onDone.run();
                    }
                })
                .show();
    }

    private void showDesignPresetsDialog(String scId, @Nullable Runnable onDone) {
        Context context = requireContext();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        // User presets from /sdcard/.sketchware/ai/design_systems/*.json
        List<File> userFiles = OpenUiThemeEngine.listUserPresets();
        for (File f : userFiles) {
            String name = f.getName().replace(".json", "");
            labels.add("★ Saved Preset: " + name);
            actions.add(() -> applyPresetJsonObject(
                    scId, OpenUiThemeEngine.loadPresetByNameOrPath(f.getAbsolutePath()),
                    onDone));
        }

        // Starter presets from assets/openui/spec.json
        com.google.gson.JsonObject starters = OpenUiThemeEngine.starterPresets();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e
                : starters.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject presetObj = e.getValue().getAsJsonObject();
            String title = AgentJournal.textOf(presetObj, "title");
            String brand = AgentJournal.textOf(presetObj, "brand_color");
            labels.add("Starter: " + title + " (" + brand + ")");
            actions.add(() -> applyPresetJsonObject(scId, presetObj, onDone));
        }

        labels.add("Import / Paste Custom Design System JSON…");
        actions.add(() -> showImportDesignJsonDialog(scId, onDone));

        if (AgentJournal.hasDesignSystem(scId)) {
            labels.add("Save Current Design System as Preset JSON…");
            actions.add(() -> showSaveDesignPresetDialog(scId));
        }

        // The applied look wears the active ring: the preset whose
        // title matches the project's current design system.
        String currentTitle = "";
        if (AgentJournal.hasDesignSystem(scId)) {
            currentTitle = AgentJournal.textOf(
                    AgentJournal.designSystem(scId), "title");
        }

        // The sheet is captured up front: picking a preset closes it
        // first, so the theme applies with no dialog left hanging.
        final androidx.appcompat.app.AlertDialog[] presetSheet =
                new androidx.appcompat.app.AlertDialog[1];
        // Material 3 cards, with a live brand-colour swatch on every
        // preset — presets are looks, so each row shows its look.
        List<CardRow> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            Runnable action = actions.get(i);
            boolean launcher = label.startsWith("Import / Paste")
                    || label.startsWith("Save Current");
            boolean active = !launcher && currentTitle != null
                    && !currentTitle.isEmpty()
                    && label.contains(currentTitle);
            java.util.regex.Matcher hex = java.util.regex.Pattern
                    .compile("#[0-9A-Fa-f]{6}").matcher(label);
            List<Integer> swatches = new ArrayList<>();
            while (hex.find() && swatches.size() < 4) {
                Integer color = OpenUiThemeEngine.parseHexColor(hex.group());
                if (color != null) {
                    swatches.add(color);
                }
            }
            rows.add(new CardRow(label, "", swatches, null, active,
                    active ? "✓ Applied" : launcher ? "" : "Apply",
                    launcher ? action : () -> {
                        if (presetSheet[0] != null) {
                            presetSheet[0].dismiss();
                        }
                        action.run();
                    }));
        }
        presetSheet[0] = showCardSheet(context,
                "Design System Presets & Custom JSON", rows);
    }

    private void applyPresetJsonObject(String scId,
                                       com.google.gson.JsonObject presetObj,
                                       @Nullable Runnable onDone) {
        if (presetObj == null) {
            Toast.makeText(requireContext(), "Could not load preset JSON",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        com.google.gson.JsonArray customTokens = presetObj.has("tokens")
                && presetObj.get("tokens").isJsonArray()
                ? presetObj.getAsJsonArray("tokens") : null;
        OpenUiThemeEngine.ResolvedTheme resolved =
                OpenUiThemeEngine.resolveTheme(presetObj);
        com.google.gson.JsonObject full =
                OpenUiThemeEngine.buildDesignSystemJson(resolved, customTokens);
        AgentJournal.setDesignSystemObject(scId, full);
        syncThemeResourcesWithToast(requireContext(), scId, full,
                "Applied \"" + resolved.title + "\" & synced resources");
        if (onDone != null) {
            onDone.run();
        } else {
            showDesignSystemDialog();
        }
    }

    private static void syncThemeResourcesWithToast(
            Context context,
            String scId,
            com.google.gson.JsonObject full,
            @Nullable String successMessage) {
        if (scId == null || scId.isEmpty()) {
            return;
        }
        if (AgentJournal.DRAFT_KEY.equals(scId)) {
            // Nothing to materialize into yet — the theme rides into
            // the project automatically when it is created.
            Toast.makeText(context,
                    "Saved. It becomes the project's theme the moment "
                            + "the project is created.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            OpenUiThemeEngine.materializeToProject(scId, full);
            if (successMessage != null && !successMessage.isEmpty()) {
                Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context,
                    "Theme saved, resource sync warning: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showImportDesignJsonDialog(String scId,
                                            @Nullable Runnable onDone) {
        Context context = requireContext();
        float d = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(20f * d);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8f * d), pad, 0);

        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setText("Paste any custom Design System JSON (e.g. {\"title\":\"My Theme\","
                + "\"brand_color\":\"#E11D48\",\"corner_profile\":\"soft\","
                + "\"light_overrides\":{\"bg\":\"#FFF8F6\"}}) or drop .json files in "
                + ".sketchware/ai/design_systems/.");
        hint.setTextSize(12.5f);
        hint.setPadding(0, 0, 0, Math.round(8f * d));
        box.addView(hint);

        EditText et = new EditText(context);
        et.setMinLines(5);
        et.setMaxLines(10);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setHint("{\n  \"title\": \"My Custom Theme\",\n  \"brand_color\": \"#059669\"\n}");
        box.addView(et);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Import Custom Design System JSON")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Apply & Sync", (dlg, w) -> {
                    String raw = et.getText() == null
                            ? "" : et.getText().toString().trim();
                    try {
                        com.google.gson.JsonObject parsed =
                                com.google.gson.JsonParser.parseString(raw)
                                        .getAsJsonObject();
                        applyPresetJsonObject(scId, parsed, onDone);
                    } catch (Exception ex) {
                        Toast.makeText(context,
                                "Invalid JSON: " + ex.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void showSaveDesignPresetDialog(String scId) {
        Context context = requireContext();
        com.google.gson.JsonObject current = AgentJournal.designSystem(scId);
        String defaultName = AgentJournal.textOf(current, "title");
        if (defaultName.isEmpty()) {
            defaultName = "my_custom_theme";
        }
        float d = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(20f * d);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Math.round(8f * d), pad, 0);

        EditText et = new EditText(context);
        et.setSingleLine(true);
        et.setText(defaultName);
        et.setHint("Preset name");
        box.addView(et);

        new MaterialAlertDialogBuilder(context)
                .setTitle("Save Design System Preset")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Copy JSON", (dlg, w) -> {
                    ClipboardManager cm = (ClipboardManager) context
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText(
                                "design_system.json", current.toString()));
                        Toast.makeText(context, "Copied Design System JSON",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton(R.string.agent_action_save, (dlg, w) -> {
                    String name = et.getText() == null
                            ? "" : et.getText().toString().trim();
                    try {
                        File saved = OpenUiThemeEngine.saveUserPreset(name, current);
                        Toast.makeText(context,
                                "Saved preset: " + saved.getName(),
                                Toast.LENGTH_LONG).show();
                    } catch (Exception ex) {
                        Toast.makeText(context,
                                "Could not save preset: " + ex.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private static int parseSafeColor(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) {
            return fallback;
        }
        try {
            return android.graphics.Color.parseColor(hex.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int designColourWithPalette(
            com.google.gson.JsonObject props,
            String key,
            java.util.Map<String, String> palette,
            String fallbackHex) {
        String raw = designValue(props, key);
        if (raw.startsWith("@color/")) {
            String cName = raw.substring("@color/".length()).trim();
            if ("openui_chip_text".equals(cName)) {
                cName = "openui_text_primary";
            }
            if (palette != null && palette.containsKey(cName)) {
                return parseSafeColor(palette.get(cName),
                        parseSafeColor(fallbackHex, 0xFF3B82F6));
            }
        }
        return designColour(props, key, fallbackHex);
    }

    private static String summarizeTokenProps(String kind,
                                              com.google.gson.JsonObject props) {
        StringBuilder sb = new StringBuilder();
        if (kind != null && !kind.isEmpty()) {
            sb.append(kind.replace('_', ' '));
        }
        String bg = designValue(props, "background");
        if (!bg.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(bg);
        }
        String h = designValue(props, "height");
        if (!h.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(h).append(h.endsWith("dp") ? "" : "dp");
        }
        String ts = designValue(props, "text_size");
        if (!ts.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(ts).append(ts.endsWith("sp") ? "" : "sp");
        }
        return sb.toString();
    }

    /** One component: what it is made of, what it looks like, and direct editing. */
    private void showDesignTokenDialog(String scId,
                                       com.google.gson.JsonObject token) {
        android.content.Context context = requireContext();
        String label = AgentJournal.textOf(token, "label");
        String key = AgentJournal.textOf(token, "key");
        OpenUiThemeEngine.ResolvedTheme resolved =
                OpenUiThemeEngine.resolveTheme(AgentJournal.designSystem(scId));
        java.util.Map<String, String> pal = designPreviewDarkMode
                ? resolved.darkColors : resolved.lightColors;

        android.widget.LinearLayout layout =
                new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * context.getResources()
                .getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);
        android.view.View preview = designPreview(context, token, pal);
        if (preview != null) {
            layout.addView(preview);
        }
        android.widget.TextView details =
                new android.widget.TextView(context);
        details.setText(designTokenText(token));
        details.setTextSize(14f);
        details.setPadding(0, pad / 2, 0, 0);
        details.setLineSpacing(4f, 1f);
        layout.addView(details);
        new MaterialAlertDialogBuilder(context)
                .setTitle(label.isEmpty() ? key : label)
                .setView(layout)
                .setNegativeButton(R.string.agent_action_close, (d, w) ->
                        showDesignSystemDialog())
                .setPositiveButton("Edit", (dialog, which) ->
                        showDesignTokenEditor(scId, token))
                .setNeutralButton("Ask agent", (dialog, which) ->
                        askAgent("Change the " + key + " component in "
                                + "the design system."))
                .show();
    }

    /** Direct user-editable form for any component in the Design System. */
    private void showDesignTokenEditor(String scId,
                                       com.google.gson.JsonObject token) {
        Context context = requireContext();
        String key = AgentJournal.textOf(token, "key");
        String label = AgentJournal.textOf(token, "label");
        com.google.gson.JsonObject props = token.has("props")
                && token.get("props").isJsonObject()
                ? token.getAsJsonObject("props").deepCopy()
                : new com.google.gson.JsonObject();

        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(20f * density);
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, Math.round(8f * density), pad, 0);

        java.util.LinkedHashMap<String, EditText> inputs =
                new java.util.LinkedHashMap<>();
        String[] standardKeys = {"background", "text_color", "height",
                "text_size", "corner_radius", "padding", "margin"};
        java.util.LinkedHashSet<String> allKeys = new java.util.LinkedHashSet<>();
        for (MapKeyHelper k : MapKeyHelper.from(props, standardKeys)) {
            allKeys.add(k.key);
        }
        for (String propKey : allKeys) {
            android.widget.TextView cap = new android.widget.TextView(context);
            cap.setText(propKey.replace('_', ' '));
            cap.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_LabelMedium);
            cap.setPadding(0, Math.round(8f * density), 0, Math.round(2f * density));
            form.addView(cap);

            EditText et = new EditText(context);
            et.setSingleLine(true);
            et.setText(designValue(props, propKey));
            form.addView(et);
            inputs.put(propKey, et);
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(form);
        new MaterialAlertDialogBuilder(context)
                .setTitle("Edit " + (label.isEmpty() ? key : label))
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.agent_action_save, (d, w) -> {
                    for (java.util.Map.Entry<String, EditText> e : inputs.entrySet()) {
                        String val = e.getValue().getText() == null
                                ? "" : e.getValue().getText().toString().trim();
                        if (val.isEmpty()) {
                            props.remove(e.getKey());
                        } else {
                            props.addProperty(e.getKey(), val);
                        }
                    }
                    com.google.gson.JsonObject updated = token.deepCopy();
                    updated.add("props", props);
                    AgentJournal.setDesignToken(scId, key, updated);
                    Toast.makeText(context, "Saved " + (label.isEmpty() ? key : label),
                            Toast.LENGTH_SHORT).show();
                    showDesignTokenDialog(scId, updated);
                })
                .show();
    }

    private static final class MapKeyHelper {
        final String key;

        MapKeyHelper(String key) {
            this.key = key;
        }

        static List<MapKeyHelper> from(com.google.gson.JsonObject props,
                                       String[] defaults) {
            List<MapKeyHelper> out = new ArrayList<>();
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e
                    : props.entrySet()) {
                out.add(new MapKeyHelper(e.getKey()));
            }
            for (String d : defaults) {
                if (!props.has(d)) {
                    out.add(new MapKeyHelper(d));
                }
            }
            return out;
        }
    }

    /** A component drawn with its own measurements (including @color/openui_* & corner_radius). */
    private android.view.View designPreview(android.content.Context context,
                                            com.google.gson.JsonObject token,
                                            java.util.Map<String, String> palette) {
        String kind = AgentJournal.textOf(token, "kind")
                .toLowerCase(java.util.Locale.ROOT);
        com.google.gson.JsonObject props = token.has("props")
                && token.get("props").isJsonObject()
                ? token.getAsJsonObject("props") : new com.google.gson.JsonObject();
        float density = context.getResources().getDisplayMetrics().density;
        int backgroundColour = designColourWithPalette(
                props, "background", palette, "#008DCD");
        int textColour = designColourWithPalette(
                props, "text_color", palette, "#FFFFFF");
        int padding = designDp(context, props, "padding", 12);
        int radiusPx = designDp(context, props, "corner_radius",
                "button".equals(kind) ? 14 : "card".equals(kind) ? 16 : 10);

        android.widget.LinearLayout box =
                new android.widget.LinearLayout(context);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        GradientDrawable stageBg = new GradientDrawable();
        stageBg.setCornerRadius(16f * density);
        int stageColor = palette != null && palette.containsKey("openui_bg")
                ? parseSafeColor(palette.get("openui_bg"), 0xFFE6EFF8)
                : resolveThemeColor(context,
                        com.google.android.material.R.attr.colorSurfaceContainer,
                        0xFFE6EFF8);
        stageBg.setColor(stageColor);
        stageBg.setStroke(Math.max(1, Math.round(density)),
                resolveThemeColor(context,
                        com.google.android.material.R.attr.colorOutlineVariant,
                        0xFFB1C0CE));
        box.setBackground(stageBg);
        int stagePad = Math.round(16f * density);
        box.setPadding(stagePad, stagePad, stagePad, stagePad);

        GradientDrawable itemShape = new GradientDrawable();
        itemShape.setCornerRadius(radiusPx);
        itemShape.setColor(backgroundColour);
        String strokeRef = designValue(props, "stroke_color");
        if (!strokeRef.isEmpty()) {
            itemShape.setStroke(Math.max(1, Math.round(density)),
                    designColourWithPalette(props, "stroke_color", palette, "#D4D4D4"));
        }

        switch (kind) {
            case "button" -> {
                android.widget.TextView button =
                        new android.widget.TextView(context);
                button.setText("Button");
                button.setGravity(android.view.Gravity.CENTER);
                button.setTextColor(textColour);
                button.setTypeface(button.getTypeface(),
                        android.graphics.Typeface.BOLD);
                button.setTextSize(designSp(props, "text_size", 15));
                button.setBackground(itemShape);
                int height = designDp(context, props, "height", 48);
                box.addView(button, new android.widget.LinearLayout
                        .LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        height));
            }
            case "card" -> {
                int cardTc = props.has("text_color")
                        ? textColour
                        : parseSafeColor(palette != null
                                ? palette.get("openui_text_primary") : null, 0xFF171717);
                android.widget.TextView card =
                        new android.widget.TextView(context);
                card.setText("Card preview");
                card.setTextColor(cardTc);
                card.setTextSize(15f);
                card.setBackground(itemShape);
                card.setPadding(padding, padding, padding, padding);
                box.addView(card);
            }
            case "list_row" -> {
                int rowTc = parseSafeColor(palette != null
                        ? palette.get("openui_text_primary") : null, 0xFF171717);
                android.widget.TextView title =
                        new android.widget.TextView(context);
                title.setText("List row title");
                title.setTextColor(rowTc);
                title.setTypeface(title.getTypeface(),
                        android.graphics.Typeface.BOLD);
                title.setTextSize(designSp(props, "title_size", 16));
                android.widget.TextView subtitle =
                        new android.widget.TextView(context);
                subtitle.setText("Supporting line");
                subtitle.setTextColor(rowTc);
                subtitle.setAlpha(0.75f);
                subtitle.setTextSize(designSp(props, "subtitle_size", 13));
                android.widget.LinearLayout row =
                        new android.widget.LinearLayout(context);
                row.setOrientation(android.widget.LinearLayout.VERTICAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(itemShape);
                int height = designDp(context, props, "height", 64);
                row.setPadding(padding, padding / 2, padding, padding / 2);
                row.addView(title);
                row.addView(subtitle);
                box.addView(row, new android.widget.LinearLayout
                        .LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        height));
            }
            case "input" -> {
                itemShape.setStroke(Math.max(1, Math.round(density)),
                        designColourWithPalette(props, "stroke_color", palette, "#888888"));
                android.widget.TextView input =
                        new android.widget.TextView(context);
                input.setText("Type here…");
                input.setGravity(Gravity.CENTER_VERTICAL);
                input.setTextSize(designSp(props, "text_size", 15));
                input.setTextColor(designColourWithPalette(
                        props, "hint_color", palette, "#757575"));
                input.setBackground(itemShape);
                input.setPadding(padding, 0, padding, 0);
                int height = designDp(context, props, "height", 48);
                box.addView(input, new android.widget.LinearLayout
                        .LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        height));
            }
            case "screen" -> {
                int scTc = parseSafeColor(palette != null
                        ? palette.get("openui_text_primary") : null, 0xFF171717);
                android.widget.TextView screen =
                        new android.widget.TextView(context);
                screen.setText("Screen surface");
                screen.setGravity(android.view.Gravity.CENTER);
                screen.setTextColor(scTc);
                screen.setBackground(itemShape);
                box.addView(screen, new android.widget.LinearLayout
                        .LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        (int) (110 * density)));
            }
            default -> {
                android.widget.TextView text =
                        new android.widget.TextView(context);
                text.setText("Sample typography Aa");
                text.setTextSize(designSp(props, "text_size", 16));
                text.setTextColor(designColourWithPalette(
                        props, "text_color", palette, "#141C23"));
                box.addView(text);
            }
        }
        return box;
    }

    /** One component's properties, in plain words. */
    private static String designTokenText(com.google.gson.JsonObject token) {
        com.google.gson.JsonObject props = token.has("props")
                && token.get("props").isJsonObject()
                ? token.getAsJsonObject("props") : new com.google.gson.JsonObject();
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> field
                : props.entrySet()) {
            String value = field.getValue().isJsonPrimitive()
                    ? field.getValue().getAsString() : "";
            if (value.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("\n");
            }
            out.append("• ").append(field.getKey().replace('_', ' '))
                    .append(": ").append(value);
        }
        return out.length() > 0 ? out.toString()
                : "No measurements saved for this component.";
    }

    /** A size in the design system, in dp, with a sane fallback. */
    private static int designDp(android.content.Context context,
                                com.google.gson.JsonObject props,
                                String key, int fallback) {
        String raw = designValue(props, key);
        float dp = parseFloatOr(raw, fallback);
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    /** A text size in the design system, in sp. */
    private static float designSp(com.google.gson.JsonObject props,
                                  String key, float fallback) {
        return parseFloatOr(designValue(props, key), fallback);
    }

    private static String designValue(com.google.gson.JsonObject props,
                                      String key) {
        com.google.gson.JsonElement element = props.get(key);
        return element != null && element.isJsonPrimitive()
                ? element.getAsString().trim() : "";
    }

    private static float parseFloatOr(String value, float fallback) {
        try {
            return Float.parseFloat(value.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A colour in the design system, with a fallback when it is not one. */
    private static int designColour(com.google.gson.JsonObject props,
                                    String key, String fallback) {
        String raw = designValue(props, key);
        if (raw.isEmpty()) {
            raw = fallback;
        }
        try {
            return android.graphics.Color.parseColor(raw);
        } catch (Exception e) {
            return android.graphics.Color.parseColor(fallback);
        }
    }

    private void showStyleBriefEditor(String scId) {
        android.widget.EditText input =
                new android.widget.EditText(requireContext());
        input.setText(AgentJournal.styleBrief(scId));
        input.setHint(R.string.agent_style_brief_hint);
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout frame =
                new android.widget.FrameLayout(requireContext());
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.agent_style_brief_title)
                .setView(frame)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.agent_action_save,
                        (dialog, which) -> {
                            AgentJournal.setStyleBrief(scId,
                                    input.getText().toString());
                            Toast.makeText(getContext(),
                                    R.string.agent_style_brief_saved,
                                    Toast.LENGTH_SHORT).show();
                        })
                .show();
    }

    /**
     * The History icon: projects with a saved conversation. Picking
     * one binds it as the working project and brings its chat back —
     * the same thing picking it on the project card does, found from
     * the conversation side.
     */
    private void showChatHistoryDialog() {
        io.execute(() -> {
            List<SketchwareProjects.ProjectInfo> withChats =
                    new ArrayList<>();
            for (SketchwareProjects.ProjectInfo project
                    : SketchwareProjects.listProjects()) {
                if (AgentChatLog.hasChat(project.scId)) {
                    withChats.add(project);
                }
            }
            runOnUi(() -> {
                if (binding == null) {
                    return;
                }
                if (withChats.isEmpty()) {
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.agent_history_title)
                            .setMessage(R.string.agent_history_empty)
                            .setNegativeButton(
                                    R.string.agent_action_close, null)
                            .show();
                    return;
                }
                String currentId = providerConfig.workingProjectId();
                String[] labels = new String[withChats.size()];
                for (int i = 0; i < withChats.size(); i++) {
                    SketchwareProjects.ProjectInfo project =
                            withChats.get(i);
                    labels[i] = project.name
                            + (project.scId.equals(currentId)
                                    ? " · " + getString(
                                            R.string.agent_key_in_use)
                                    : "");
                }
                MaterialAlertDialogBuilder historyDialog =
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.agent_history_title)
                                .setItems(labels, (dialog, which) -> {
                                    SketchwareProjects.ProjectInfo chosen =
                                            withChats.get(which);
                                    if (!chosen.scId.equals(
                                            providerConfig.workingProjectId())) {
                                        chooseProject(chosen.scId, chosen.name);
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null);
                if (!currentId.isEmpty()) {
                    historyDialog.setNeutralButton(
                            R.string.agent_working_on_none,
                            (dialog, which) -> chooseProject("", ""));
                }
                historyDialog.show();
            });
        });
    }

    /**
     * The export icon: one JSON with this project's whole story —
     * the chat and its cards, the Style, the Plan and the Task log —
     * written to .sketchware/ai/exports and offered to share, so the
     * user can hand the conversation to someone helping them.
     */
    private void exportConversation() {
        // Exportable at any time — with or without a project — because
        // it is the diagnostic source of truth, pre-project failures
        // included.
        String scId = journalTarget();
        List<ChatMessage> snapshot = new ArrayList<>(messages);
        String provider = providerConfig.activeProvider().name();
        String model = providerConfig.activeSettings().model;
        boolean draft = AgentJournal.DRAFT_KEY.equals(scId);
        SketchwareProjects.ProjectInfo info =
                draft ? null : SketchwareProjects.project(scId);
        String projectName = draft ? "chat-draft"
                : (info == null ? scId : info.name);
        io.execute(() -> {
            java.io.File file;
            try {
                file = AgentExport.write(
                        AgentExport.build(scId, provider, model, snapshot),
                        projectName);
            } catch (Exception e) {
                runOnUi(() -> Toast.makeText(getContext(),
                        R.string.agent_export_failed,
                        Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUi(() -> shareExport(file));
        });
    }

    private void shareExport(java.io.File file) {
        try {
            android.net.Uri uri =
                    androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            requireContext().getPackageName() + ".provider",
                            file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,
                    getString(R.string.agent_export_share_title)));
        } catch (RuntimeException e) {
            Toast.makeText(getContext(),
                    getString(R.string.agent_export_saved, file.getName()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** The Plan button: the agreed build order and its progress. */
    private void showPlanDialog() {
        String scId = journalTarget();
        com.google.gson.JsonObject plan = AgentJournal.plan(scId);
        String title = plan.has("title")
                ? plan.get("title").getAsString() : "";
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout container =
                new android.widget.LinearLayout(requireContext());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, pad);

        final Runnable[] rebuildRows = new Runnable[1];
        rebuildRows[0] = () -> {
            container.removeAllViews();
            com.google.gson.JsonObject latest = AgentJournal.plan(scId);
            if (!latest.has("steps")
                    || latest.getAsJsonArray("steps").size() == 0) {
                android.widget.TextView empty =
                        new android.widget.TextView(requireContext());
                empty.setText(R.string.agent_plan_empty);
                empty.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_BodyMedium);
                container.addView(empty);
                return;
            }
            com.google.gson.JsonArray steps = latest.getAsJsonArray("steps");
            int[] progress = AgentJournal.planProgress(scId);
            float density = getResources().getDisplayMetrics().density;
            android.widget.TextView summary =
                    new android.widget.TextView(requireContext());
            summary.setText(getString(R.string.agent_plan_progress,
                    progress[0], progress[1])
                    + " · Tap a step to mark or unmark done");
            summary.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_LabelLarge);
            summary.setPadding(0, 0, 0, Math.round(6f * density));
            container.addView(summary);

            // Live Sketchware Primary progress bar
            FrameLayout barTrack = new FrameLayout(requireContext());
            int barH = Math.round(6f * density);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barH);
            trackLp.bottomMargin = Math.round(12f * density);
            barTrack.setLayoutParams(trackLp);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setCornerRadius(999f);
            trackBg.setColor(resolveThemeColor(requireContext(),
                    com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    0xFFDBE3ED));
            barTrack.setBackground(trackBg);
            if (progress[1] > 0 && progress[0] > 0) {
                LinearLayout fillWrap = new LinearLayout(requireContext());
                fillWrap.setOrientation(LinearLayout.HORIZONTAL);
                fillWrap.setWeightSum(progress[1]);
                View fill = new View(requireContext());
                fill.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, progress[0]));
                GradientDrawable fillBg = new GradientDrawable();
                fillBg.setCornerRadius(999f);
                fillBg.setColor(resolveThemeColor(requireContext(),
                        pro.sketchware.R.attr.colorPrimary, 0xFF008DCD));
                fill.setBackground(fillBg);
                fillWrap.addView(fill);
                barTrack.addView(fillWrap, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            container.addView(barTrack);

            int rowPadH = Math.round(12f * density);
            int rowPadV = Math.round(10f * density);
            for (int i = 0; i < steps.size(); i++) {
                final int stepIndex = i;
                com.google.gson.JsonObject step =
                        steps.get(i).getAsJsonObject();
                String state = step.has("state")
                        ? step.get("state").getAsString() : "pending";
                String mark = "done".equals(state) ? "✓ "
                        : "current".equals(state) ? "● "
                        : "stopped".equals(state) ? "❚❚ " : "○ ";
                String rawText = step.has("text")
                        ? step.get("text").getAsString() : "";
                android.widget.TextView row =
                        new android.widget.TextView(requireContext());
                row.setText(mark + (i + 1) + ". "
                        + AgentJournal.cleanStepText(rawText));
                row.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_BodyMedium);
                GradientDrawable stepBg = new GradientDrawable();
                stepBg.setCornerRadius(12f * density);
                if ("current".equals(state)) {
                    stepBg.setColor(resolveThemeColor(requireContext(),
                            pro.sketchware.R.attr.colorPrimaryContainer,
                            0xFFCAE6FF));
                    row.setTypeface(row.getTypeface(),
                            android.graphics.Typeface.BOLD);
                } else {
                    stepBg.setColor(resolveThemeColor(requireContext(),
                            com.google.android.material.R.attr.colorSurfaceContainerLow,
                            0xFFECF4FE));
                }
                row.setBackground(stepBg);
                if ("done".equals(state)) {
                    row.setAlpha(0.68f);
                }
                row.setPadding(rowPadH, rowPadV, rowPadH, rowPadV);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    rlp.topMargin = Math.round(6f * density);
                }
                row.setLayoutParams(rlp);
                row.setOnClickListener(v -> {
                    AgentJournal.togglePlanStepDone(scId, stepIndex);
                    rebuildRows[0].run();
                    refreshStrip();
                });
                container.addView(row);
            }
        };
        rebuildRows[0].run();

        android.widget.ScrollView scroll =
                new android.widget.ScrollView(requireContext());
        scroll.addView(container);
        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(title.isEmpty()
                                ? getString(R.string.agent_plan_title)
                                : title)
                        .setView(scroll)
                        .setNegativeButton(R.string.agent_action_close, null)
                        .create();
        dialog.show();
        if (generating) {
            Runnable[] refresh = new Runnable[1];
            refresh[0] = () -> {
                if (!dialog.isShowing() || binding == null) {
                    return;
                }
                rebuildRows[0].run();
                if (generating) {
                    container.postDelayed(refresh[0], 1200);
                }
            };
            container.postDelayed(refresh[0], 1200);
        }
    }

    /** The Plan page body: progress line plus the marked steps. */
    private String planBodyText(String scId) {
        com.google.gson.JsonObject plan = AgentJournal.plan(scId);
        String steps = AgentJournal.planText(scId);
        if (steps.isEmpty()) {
            return getString(R.string.agent_plan_empty);
        }
        int done = 0;
        int total = plan.getAsJsonArray("steps").size();
        for (com.google.gson.JsonElement element
                : plan.getAsJsonArray("steps")) {
            if (element.isJsonObject() && "done".equals(element
                    .getAsJsonObject().get("state").getAsString())) {
                done++;
            }
        }
        return getString(R.string.agent_plan_progress, done, total)
                + "\n\n" + steps;
    }

    /** The Task log button: the recorded work. */
    private void showTaskLogDialog() {
        String scId = journalTarget();
        android.widget.TextView body =
                new android.widget.TextView(requireContext());
        body.setText(AgentJournal.logText(scId));
        body.setTextAppearance(
                com.google.android.material.R.style
                        .TextAppearance_Material3_BodyMedium);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 2, pad, pad);
        android.widget.ScrollView scroll =
                new android.widget.ScrollView(requireContext());
        scroll.addView(body);
        SketchwareProjects.ProjectInfo projInfo =
                SketchwareProjects.project(scId);
        String logTitle = getString(R.string.agent_task_log_title);
        if (projInfo != null && !projInfo.name.isEmpty()) {
            logTitle = logTitle + " · " + projInfo.name;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(logTitle)
                .setView(scroll)
                .setNegativeButton(R.string.agent_action_close, null)
                .setNeutralButton(R.string.agent_task_log_clear,
                        (dialog, which) -> confirmClearTaskLog(scId))
                .show();
    }

    private void confirmClearTaskLog(String scId) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.agent_task_log_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.agent_action_clear,
                        (dialog, which) -> {
                            AgentJournal.clear(scId);
                            refreshStrip();
                            Toast.makeText(getContext(),
                                    R.string.agent_task_log_cleared,
                                    Toast.LENGTH_SHORT).show();
                        })
                .show();
    }

    /** Rows of the picker: "No project", then one row per project. */
    private class ProjectPickerAdapter extends BaseAdapter {
        private final List<SketchwareProjects.ProjectInfo> projects;
        private final String currentId;

        ProjectPickerAdapter(List<SketchwareProjects.ProjectInfo> projects,
                             String currentId) {
            this.projects = projects;
            this.currentId = currentId;
        }

        @Override
        public int getCount() {
            return projects.size() + 1;
        }

        @Override
        public Object getItem(int position) {
            return position == 0 ? null : projects.get(position - 1);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.agent_project_row, parent, false);
            }
            ImageView icon = row.findViewById(R.id.picker_icon);
            android.widget.TextView name =
                    row.findViewById(R.id.picker_name);
            android.widget.TextView subtitle =
                    row.findViewById(R.id.picker_subtitle);
            View check = row.findViewById(R.id.picker_check);
            if (position == 0) {
                if (icon != null) {
                    icon.setImageResource(R.drawable.sketch_app_icon);
                }
                name.setText(R.string.agent_working_on_none);
                subtitle.setText(R.string.agent_picker_none_hint);
                check.setVisibility(
                        currentId.isEmpty() ? View.VISIBLE : View.GONE);
            } else {
                SketchwareProjects.ProjectInfo project =
                        projects.get(position - 1);
                if (icon != null) {
                    Bitmap custom = loadCustomProjectIcon(project.scId);
                    if (custom != null) {
                        icon.setImageBitmap(custom);
                    } else {
                        icon.setImageResource(R.drawable.sketch_app_icon);
                    }
                }
                name.setText(project.appName.isEmpty()
                        ? project.name : project.appName);
                subtitle.setText(project.name + " · " + project.packageName);
                check.setVisibility(project.scId.equals(currentId)
                        ? View.VISIBLE : View.GONE);
            }
            return row;
        }
    }

    /** One row of a {@link #showCardSheet} card sheet. */
    private static final class CardRow {
        final String title;
        final String subtitle;
        final List<Integer> swatches;
        final int[] gradient;
        final boolean active;
        final String trailing;
        final Runnable onClick;

        CardRow(String title, String subtitle, List<Integer> swatches,
                int[] gradient, boolean active, String trailing,
                Runnable onClick) {
            this.title = title;
            this.subtitle = subtitle;
            this.swatches = swatches;
            this.gradient = gradient;
            this.active = active;
            this.trailing = trailing;
            this.onClick = onClick;
        }
    }

    /**
     * A Material 3 card bottom-sheet: real tappable cards with a bold
     * title, a soft subtitle, live colour swatches and a Light/Dark
     * gradient strip where colours are involved — unmistakably
     * buttons, never a bare text list the user has to guess at.
     */
    private androidx.appcompat.app.AlertDialog showCardSheet(
            Context context, String title, List<CardRow> rows) {
        return showCardSheet(context, title, rows, null);
    }

    private androidx.appcompat.app.AlertDialog showCardSheet(
            Context context, String title,
            List<CardRow> rows,
            @Nullable Runnable onCancel) {
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Math.round(12f * d), Math.round(4f * d),
                Math.round(12f * d), Math.round(12f * d));
        for (CardRow row : rows) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(Math.round(14f * d), Math.round(11f * d),
                    Math.round(14f * d), Math.round(11f * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(16f * d);
            bg.setColor(resolveThemeColor(context,
                    com.google.android.material.R.attr.colorSurfaceContainerLow,
                    0xFFE6EFF8));
            bg.setStroke(Math.max(1, Math.round(row.active ? 1.6f * d : d)),
                    row.active ? resolveThemeColor(context,
                            pro.sketchware.R.attr.colorPrimary, 0xFF008DCD)
                            : resolveThemeColor(context,
                            com.google.android.material.R.attr.colorOutlineVariant,
                            0xFFB1C0CE));
            card.setBackground(bg);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.topMargin = Math.round(7f * d);
            card.setLayoutParams(cardLp);

            // Live colour swatches + Light/Dark preview strip: the
            // colours are the point, so they are drawn, not spelled.
            if (row.gradient != null && row.gradient.length >= 2) {
                View strip = new View(context);
                GradientDrawable stripBg = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        row.gradient);
                stripBg.setCornerRadius(9f * d);
                stripBg.setStroke(Math.max(1, Math.round(d)), 0x33888888);
                strip.setBackground(stripBg);
                strip.setLayoutParams(new LinearLayout.LayoutParams(
                        Math.round(44f * d), Math.round(34f * d)));
                card.addView(strip);
            } else if (row.swatches != null && !row.swatches.isEmpty()) {
                LinearLayout swatchRow = new LinearLayout(context);
                swatchRow.setOrientation(LinearLayout.HORIZONTAL);
                int shown = Math.min(5, row.swatches.size());
                for (int i = 0; i < shown; i++) {
                    View swatch = new View(context);
                    GradientDrawable swBg = new GradientDrawable();
                    swBg.setShape(GradientDrawable.OVAL);
                    swBg.setColor(row.swatches.get(i));
                    swBg.setStroke(Math.max(1, Math.round(d)), 0x33888888);
                    swatch.setBackground(swBg);
                    LinearLayout.LayoutParams swLp =
                            new LinearLayout.LayoutParams(
                                    Math.round(22f * d),
                                    Math.round(22f * d));
                    swLp.rightMargin = i < shown - 1
                            ? Math.round(-6f * d) : 0;
                    swatch.setLayoutParams(swLp);
                    swatchRow.addView(swatch);
                }
                swatchRow.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                card.addView(swatchRow);
            }

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = Math.round(12f * d);
            textCol.setLayoutParams(colLp);
            android.widget.TextView tvTitle =
                    new android.widget.TextView(context);
            tvTitle.setText(row.title);
            tvTitle.setTextAppearance(com.google.android.material.R.style
                    .TextAppearance_Material3_BodyMedium);
            tvTitle.setTypeface(tvTitle.getTypeface(),
                    android.graphics.Typeface.BOLD);
            textCol.addView(tvTitle);
            if (row.subtitle != null && !row.subtitle.isEmpty()) {
                android.widget.TextView tvSub =
                        new android.widget.TextView(context);
                tvSub.setText(row.subtitle);
                tvSub.setTextAppearance(com.google.android.material.R.style
                        .TextAppearance_Material3_BodySmall);
                tvSub.setAlpha(0.8f);
                textCol.addView(tvSub);
            }
            card.addView(textCol);

            if (row.trailing != null && !row.trailing.isEmpty()) {
                android.widget.TextView trailing =
                        new android.widget.TextView(context);
                trailing.setText(row.trailing);
                trailing.setTextSize(13f);
                trailing.setTypeface(trailing.getTypeface(),
                        android.graphics.Typeface.BOLD);
                trailing.setTextColor(resolveThemeColor(context,
                        pro.sketchware.R.attr.colorPrimary, 0xFF008DCD));
                LinearLayout.LayoutParams trLp =
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                trLp.leftMargin = Math.round(10f * d);
                trailing.setLayoutParams(trLp);
                card.addView(trailing);
            }

            card.setOnClickListener(v -> {
                if (row.onClick != null) {
                    row.onClick.run();
                }
            });
            container.addView(card);
        }
        android.widget.ScrollView scroll =
                new android.widget.ScrollView(context);
        scroll.addView(container);
        MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(context)
                        .setTitle(title)
                        .setView(scroll)
                        .setNegativeButton(R.string.agent_action_close, null);
        if (onCancel != null) {
            builder.setOnCancelListener(dialogI -> onCancel.run());
        }
        return builder.show();
    }

    /**
     * Shows the approval card and blocks the agent thread until the
     * user decides. The card is the only way a change can happen.
     */
    private boolean awaitApproval(AgentProposal proposal) {
        CountDownLatch latch = new CountDownLatch(1);
        approvalLatch = latch;
        approvalDecision = false;
        runOnUi(() -> insertApprovalCard(proposal));
        try {
            latch.await();
        } catch (InterruptedException e) {
            return false;
        }
        return approvalDecision;
    }

    /** Puts a pending approval card above the streaming bubble. */
    private void insertApprovalCard(AgentProposal proposal) {
        if (binding == null) {
            releaseApproval(false);
            return;
        }
        ChatMessage card = new ChatMessage(ChatMessage.Role.APPROVAL, "");
        card.approvalTitle = proposal.title;
        card.approvalBody = proposal.body;
        card.approvalDetails = proposal.details;
        card.approvalTool = proposal.tool == null ? "" : proposal.tool;
        // Destructive deletes never count down — a person decides.
        card.approvalManualHold = isManualHoldTool(card.approvalTool);
        card.approvalCountdownLeft = 0;
        proposal.card = card;
        approvalCardMessage = card;
        int index = streamingMessage != null
                ? messages.indexOf(streamingMessage) : messages.size();
        if (index < 0) {
            index = messages.size();
        }
        messages.add(index, card);
        adapter.notifyItemInserted(index);
        binding.chatList.scrollToPosition(messages.size() - 1);
    }

    /** Tools whose cards must always wait for a human decision. */
    private static boolean isManualHoldTool(String tool) {
        return "delete_activity".equals(tool)
                || "delete_custom_view".equals(tool)
                || "remove_component".equals(tool)
                || "delete_source_file".equals(tool)
                || "delete_resource_xml".equals(tool);
    }

    /** The user tapped Approve or Reject on the pending card. */
    private void onApprovalDecision(ChatMessage message, boolean approved) {
        if (message != approvalCardMessage
                || !ChatMessage.APPROVAL_PENDING.equals(message.approvalState)) {
            return;
        }
        message.approvalState = approved
                ? ChatMessage.APPROVAL_WORKING : ChatMessage.APPROVAL_REJECTED;
        adapter.notifyItemChanged(messages.indexOf(message));
        releaseApproval(approved);
    }

    /** Shows the verified outcome on the card after execution. */
    private void resolveApprovalCard(AgentProposal proposal,
                                     com.google.gson.JsonObject result) {
        ChatMessage card = proposal.card;
        if (card == null || binding == null
                || ChatMessage.APPROVAL_REJECTED.equals(card.approvalState)) {
            return;
        }
        if (result.has("error")) {
            card.approvalState = ChatMessage.APPROVAL_FAILED;
            card.approvalOutcome = result.has("message")
                    ? result.get("message").getAsString() : "";
        } else {
            card.approvalState = ChatMessage.APPROVAL_DONE;
            card.approvalOutcome = result.has("verified")
                    ? result.get("verified").getAsString() : "";
            // A build leaves its APK behind: keep its identity on the
            // card so the card can offer Install for it later.
            if (result.has("apk_path")) {
                card.buildApkPath = result.get("apk_path").getAsString();
                card.buildApkBytes = result.has("apk_bytes")
                        ? result.get("apk_bytes").getAsLong() : 0;
                card.buildApkModified = result.has("apk_modified")
                        ? result.get("apk_modified").getAsLong() : 0;
                card.buildProjectId = result.has("build_sc_id")
                        ? result.get("build_sc_id").getAsString() : "";
                supersedeOlderBuildCards(card);
            }
        }
        adapter.notifyItemChanged(messages.indexOf(card));
    }

    /**
     * Closes a card whose turn died around it — stopped, failed, or
     * the view went away — so it never sits there looking tappable
     * after its agent thread is gone.
     */
    private void closePendingCard(String outcome) {
        ChatMessage card = approvalCardMessage;
        if (card == null
                || !(ChatMessage.APPROVAL_PENDING.equals(card.approvalState)
                        || ChatMessage.APPROVAL_WORKING
                                .equals(card.approvalState))) {
            return;
        }
        card.approvalState = ChatMessage.APPROVAL_FAILED;
        card.approvalOutcome = outcome;
        approvalCardMessage = null;
        if (adapter != null && messages != null) {
            adapter.notifyItemChanged(messages.indexOf(card));
        }
    }

    /**
     * A newer build of the same project replaces every earlier build
     * card's APK on disk (same path), so those cards stop offering
     * Install; only the newest build card carries a live button.
     * Cards of other projects are untouched.
     */
    private void supersedeOlderBuildCards(ChatMessage newest) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage other = messages.get(i);
            if (other != newest && !other.buildApkPath.isEmpty()
                    && other.buildProjectId.equals(newest.buildProjectId)) {
                other.buildSuperseded = true;
                adapter.notifyItemChanged(i);
            }
        }
    }

    /**
     * Build cards validate their APK when bound, but nothing rebinds
     * them while this screen is away — the user may have rebuilt in
     * the editor meanwhile. Rebind them whenever the screen shows
     * again so the Install row never serves a stale claim.
     */
    private void refreshBuildCards() {
        if (adapter == null) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.role == ChatMessage.Role.APPROVAL
                    && !message.buildApkPath.isEmpty()) {
                adapter.notifyItemChanged(i);
            }
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            refreshBuildCards();
        }
    }

    /**
     * The user tapped Install on a finished build card: hand the APK the
     * card points at to Android's installer, the same intent the editor
     * sends after its own builds. The file is re-checked here (the row
     * can be showing a stale bind), so a swapped or vanished build can
     * never be installed silently.
     */
    private void onInstallRequest(ChatMessage card) {
        if (card.buildApkPath.isEmpty()) {
            return;
        }
        File apk = new File(card.buildApkPath);
        if (!apk.exists() || apk.length() == 0) {
            adapter.notifyItemChanged(messages.indexOf(card));
            Toast.makeText(requireContext(), R.string.agent_install_gone,
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            android.net.Uri apkUri =
                    androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            requireContext().getPackageName() + ".provider",
                            apk);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.setDataAndType(apkUri,
                    "application/vnd.android.package-archive");
            startActivity(intent);
            Toast.makeText(requireContext(),
                    R.string.agent_install_launched,
                    Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(requireContext(),
                    getString(R.string.agent_install_failed,
                            card.buildApkPath),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Releases a blocked agent thread with the given decision. */
    private void releaseApproval(boolean approved) {
        CountDownLatch latch = approvalLatch;
        if (latch != null) {
            approvalDecision = approved;
            approvalLatch = null;
            latch.countDown();
        }
    }

    /**
     * Opens Sketchware's own colour picker and blocks the loop thread
     * until the user picks a colour or closes it. Returns the pick, or
     * null when closed. Mirrors {@link #awaitApproval}: never leave the
     * loop parked behind a dead dialog.
     */
    private com.google.gson.JsonObject awaitColorPick(String question,
                                                      String initialHex) {
        if (!isAdded() || getActivity() == null) {
            return null;
        }
        // The model may offer a token name or descriptive words; only
        // raw hex can seed the picker.
        int initial = 0xff000000;
        String hex = tokenHex(initialHex);
        hex = hex.isEmpty() ? tokenHex(question) : hex;
        if (!hex.isEmpty()) {
            try {
                initial = android.graphics.Color.parseColor(hex);
            } catch (IllegalArgumentException ignored) {
                // Stays black.
            }
        }
        pickedColor = null;
        CountDownLatch latch = new CountDownLatch(1);
        colorLatch = latch;
        showColorPicker(initial);
        boolean answered;
        try {
            answered = latch.await(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answered = false;
        }
        colorLatch = null;
        return answered ? pickedColor : null;
    }

    /**
     * The agent's brainstorming question, answered on the spot: the
     * options it named, one tap each. Blocking, like the colour picker
     * — the turn waits for the answer it is going to build from.
     */
    private String awaitChoice(String question,
                               java.util.List<String> options) {
        if (!isAdded() || getActivity() == null || options == null
                || options.isEmpty()) {
            return null;
        }
        pickedChoice = null;
        CountDownLatch latch = new CountDownLatch(1);
        choiceLatch = latch;
        showChoiceQuestion(question, options, latch);
        try {
            if (!latch.await(10, TimeUnit.MINUTES)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            choiceLatch = null;
        }
        return pickedChoice;
    }

    /** The question as a list the user taps. */
    private void showChoiceQuestion(String question,
                                    java.util.List<String> options,
                                    CountDownLatch latch) {
        runOnUi(() -> {
            android.app.Activity activity = getActivity();
            if (activity == null || binding == null) {
                latch.countDown();
                return;
            }
            java.util.regex.Pattern hex =
                    java.util.regex.Pattern.compile(
                            "#[0-9A-Fa-f]{8}|#[0-9A-Fa-f]{6}");
            androidx.appcompat.app.AlertDialog[] sheet =
                    new androidx.appcompat.app.AlertDialog[1];
            List<CardRow> rows = new ArrayList<>();
            for (String option : options) {
                java.util.regex.Matcher matcher = hex.matcher(option);
                List<Integer> swatches = new ArrayList<>();
                while (matcher.find() && swatches.size() < 4) {
                    Integer color = OpenUiThemeEngine.parseHexColor(
                            matcher.group());
                    if (color != null && !swatches.contains(color)) {
                        swatches.add(color);
                    }
                }
                if (swatches.isEmpty()) {
                    // The model described colours in words without hexes
                    // — draw approximations anyway; a style choice must
                    // never arrive as bare text.
                    String lower = option.toLowerCase(
                            java.util.Locale.ROOT);
                    String[][] words = {
                            {"soft blue", "B39DDB"}, {"navy", "3949AB"},
                            {"purple", "B39DDB"}, {"lavender", "D1C4E9"},
                            {"violet", "B39DDB"}, {"mint", "B2DFDB"},
                            {"teal", "80CBC4"}, {"pastel green", "C8E6C9"},
                            {"emerald", "81C784"}, {"green", "A5D6A7"},
                            {"burgundy", "A1887F"}, {"maroon", "8D6E63"},
                            {"coral", "FF8A65"}, {"red", "EF9A9A"},
                            {"orange", "FFCC80"}, {"amber", "FFD54F"},
                            {"gold", "FFD54F"}, {"yellow", "FFF59D"},
                            {"pink", "F48FB1"}, {"rose", "F48FB1"},
                            {"brown", "BCAAA4"}, {"grey", "B0BEC5"},
                            {"gray", "B0BEC5"}, {"cream", "FFF8E1"},
                            {"white", "F5F5F5"}, {"charcoal", "455A64"},
                            {"dark", "455A64"}, {"black", "37474F"},
                            {"frost", "E3F2FD"}, {"blue", "90CAF9"}
                    };
                    for (String[] word : words) {
                        if (swatches.size() >= 4) {
                            break;
                        }
                        if (lower.contains(word[0])) {
                            Integer color =
                                    OpenUiThemeEngine.parseHexColor(
                                            "#" + word[1]);
                            if (color != null
                                    && !swatches.contains(color)) {
                                swatches.add(color);
                            }
                        }
                    }
                }
                int[] gradient = swatches.size() >= 2
                        ? toIntArray(swatches) : null;
                String firstLine = option;
                String rest = "";
                int cut = option.indexOf(". ");
                if (cut > 0 && cut < 90) {
                    firstLine = option.substring(0, cut + 1);
                    rest = option.substring(cut + 2);
                } else if (option.length() > 96) {
                    int space = option.lastIndexOf(' ', 96);
                    cut = space > 40 ? space : 96;
                    firstLine = option.substring(0, cut);
                    rest = option.substring(cut).trim();
                }
                rows.add(new CardRow(firstLine, rest,
                        gradient != null ? null : swatches, gradient,
                        false, "Pick",
                        () -> {
                            pickedChoice = option;
                            // First tap wins and the sheet closes — a
                            // second tap must never overwrite the
                            // answer that was already sent.
                            if (sheet[0] != null) {
                                sheet[0].dismiss();
                            }
                            latch.countDown();
                        }));
            }
            // The three universal answers at the bottom of every
            // choice: a real colour wheel, the user's own words, or
            // "the agent decides" — because taste questions never
            // fit a fixed list alone.
            rows.add(new CardRow("🎨 Pick a custom colour…",
                    "Opens Sketchware's colour wheel; the hex goes to "
                            + "the agent", null, null, false, "",
                    () -> {
                        showColorPickerForChoice(latch,
                                () -> {
                                    if (sheet[0] != null) {
                                        sheet[0].dismiss();
                                    }
                                });
                    }));
            rows.add(new CardRow("⌨ Type my own…",
                    "Answer in your own words", null, null, false, "",
                    () -> {
                        showTypedChoice(latch,
                                () -> {
                                    if (sheet[0] != null) {
                                        sheet[0].dismiss();
                                    }
                                });
                    }));
            rows.add(new CardRow("✨ You decide",
                    "The agent picks the best option and says why",
                    null, null, false, "",
                    () -> {
                        pickedChoice = "You decide — pick the best "
                                + "option yourself and say why";
                        if (sheet[0] != null) {
                            sheet[0].dismiss();
                        }
                        latch.countDown();
                    }));
            sheet[0] = showCardSheet(activity,
                    question.isEmpty() ? "Choose one" : question, rows,
                    latch::countDown);
            choiceSheetDialog = sheet[0];
        });
    }

    /**
     * Cancels a pending bounce sheet: the user typing their own message
     * is an answer in itself, and a stale sheet must never fire a pick
     * into a later turn. The tool path is untouched — its sheet only
     * exists while a turn is generating, which this refuses to touch.
     */
    private void cancelPendingChoiceSheet() {
        androidx.appcompat.app.AlertDialog sheet = choiceSheetDialog;
        choiceSheetDialog = null;
        if (sheet != null && sheet.isShowing() && !generating) {
            pickedChoice = null;
            sheet.dismiss();
        }
    }

    /** The custom-colour answer to an ask_choice question. */
    private void showColorPickerForChoice(CountDownLatch latch,
                                          Runnable onAnswered) {
        android.app.Activity activity = getActivity();
        View anchor = binding != null ? binding.getRoot() : null;
        if (activity == null || anchor == null) {
            latch.countDown();
            return;
        }
        try {
            final boolean[] picked = {false};
            com.besome.sketch.lib.ui.ColorPickerDialog dialog =
                    new com.besome.sketch.lib.ui.ColorPickerDialog(
                            activity, 0xFFFF8A3D, false, false);
            dialog.a(new com.besome.sketch.lib.ui.ColorPickerDialog.b() {
                @Override
                public void a(int color) {
                    picked[0] = true;
                    pickedChoice = "#"
                            + String.format("%06X", color & 0xFFFFFF);
                    onAnswered.run();
                    latch.countDown();
                }

                @Override
                public void a(String name, int color) {
                    picked[0] = true;
                    pickedChoice = "#"
                            + String.format("%06X", color & 0xFFFFFF);
                    onAnswered.run();
                    latch.countDown();
                }
            });
            dialog.setOnDismissListener(() -> {
                // A pick already counted down; a bare dismissal is a
                // "go back" — the sheet is gone, so answer nothing
                // rather than hang the agent thread.
                if (!picked[0]) {
                    pickedChoice = "";
                    latch.countDown();
                }
            });
            dialog.showAtLocation(anchor,
                    android.view.Gravity.CENTER, 0, 0);
        } catch (Exception e) {
            latch.countDown();
        }
    }

    /** The free-text answer to an ask_choice question. */
    private void showTypedChoice(CountDownLatch latch,
                                 Runnable onAnswered) {
        android.app.Activity activity = getActivity();
        if (activity == null) {
            latch.countDown();
            return;
        }
        android.widget.EditText input =
                new android.widget.EditText(activity);
        input.setSingleLine(false);
        input.setMaxLines(3);
        int pad = Math.round(20f * getResources()
                .getDisplayMetrics().density);
        android.widget.FrameLayout frame =
                new android.widget.FrameLayout(activity);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Type your answer")
                .setView(frame)
                .setNegativeButton(android.R.string.cancel,
                        (d, w) -> latch.countDown())
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> {
                            String text = input.getText().toString().trim();
                            pickedChoice = text.isEmpty()
                                    ? "You decide — pick the best option "
                                    + "yourself and say why" : text;
                            onAnswered.run();
                            latch.countDown();
                        })
                .setOnCancelListener(d -> latch.countDown())
                .show();
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /**
     * Shows Sketchware's picker over the chat, exactly how the editor's
     * colour property row shows it.
     */
    private void showColorPicker(int initial) {
        runOnUi(() -> {
            android.app.Activity activity = getActivity();
            View anchor = binding != null ? binding.getRoot() : null;
            if (activity == null || anchor == null) {
                deliverPick(null);
                return;
            }
            try {
                com.besome.sketch.lib.ui.ColorPickerDialog dialog =
                        new com.besome.sketch.lib.ui.ColorPickerDialog(
                                activity, initial, false, false);
                dialog.a(new com.besome.sketch.lib.ui.ColorPickerDialog.b() {
                    @Override
                    public void a(int color) {
                        deliverPick("#" + String.format("%08X", color));
                    }

                    @Override
                    public void a(String name, int color) {
                        deliverPick("#" + String.format("%08X", color));
                    }
                });
                dialog.setOnDismissListener(() -> deliverPick(null));
                dialog.showAtLocation(anchor,
                        android.view.Gravity.CENTER, 0, 0);
            } catch (Exception e) {
                deliverPick(null);
            }
        });
    }

    /** Hands the user's pick (or closure) back to the blocked loop. */
    private void deliverPick(String hex) {
        if (hex != null && pickedColor == null) {
            com.google.gson.JsonObject pick = new com.google.gson.JsonObject();
            pick.addProperty("picked", hex);
            pickedColor = pick;
        }
        CountDownLatch latch = colorLatch;
        if (latch != null) {
            colorLatch = null;
            latch.countDown();
        }
    }

    /** Releases a pending colour pick as "no pick". */
    private void releasePick() {
        deliverPick(null);
    }

    /** Extracts a #RRGGBB/#AARRGGBB hex from model text, or "". */
    private static String tokenHex(String text) {
        if (text == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("#[0-9a-fA-F]{6,8}\\b").matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    /** A quiet activity line, slipped in above the streaming bubble. */
    private void insertToolNote(String line) {
        if (binding == null) {
            return;
        }
        int index = streamingMessage != null
                ? messages.indexOf(streamingMessage) : messages.size();
        if (index < 0) {
            index = messages.size();
        }
        if (liveNote != null) {
            int at = messages.indexOf(liveNote);
            if (at >= 0) {
                // The same line is rewritten, not repeated: five
                // checks of five different handlers must read as five
                // different lines, not one line five times.
                liveNote.text = line;
                adapter.notifyItemChanged(at);
                return;
            }
            liveNote = null;
        }
        ChatMessage note = new ChatMessage(ChatMessage.Role.SYSTEM, line);
        note.state = ChatMessage.State.COMPLETE;
        liveNote = note;
        messages.add(index, note);
        adapter.notifyItemInserted(index);
        scrollToNewest();
    }

    /**
     * Removes the live activity line. It is a heartbeat for the turn
     * in progress, so it goes when the turn ends — the work it
     * described is written in the Task log instead.
     */
    private void clearLiveNote() {
        if (liveNote == null || binding == null) {
            liveNote = null;
            return;
        }
        int index = messages.indexOf(liveNote);
        liveNote = null;
        if (index >= 0) {
            messages.remove(index);
            adapter.notifyItemRemoved(index);
        }
    }

    /** Posts to the UI thread when a view is still attached. */
    private void runOnUi(Runnable action) {
        if (binding != null) {
            binding.getRoot().post(action);
        }
    }

    private void handleError(AiChatService.AiError error) {
        if (binding == null) {
            return;
        }
        // The turn is dead: a card still waiting on it must say so,
        // not sit pending with live-looking buttons.
        closePendingCard(getString(
                R.string.agent_approval_not_applied_failed));
        flushPendingTokens();
        // Every failure keeps its bubble, marked as failed: a subtle
        // tap-to-retry line appears under it instead of a dead end.
        failedBubble = streamingMessage;
        if (failedBubble != null) {
            if (failedBubble.text == null
                    || failedBubble.text.trim().isEmpty()
                    || "…".equals(failedBubble.text.trim())) {
                // An empty placeholder says nothing — it becomes the
                // failure itself, so no ghost "…" bubble survives.
                failedBubble.text = explainError(error);
            }
            failedBubble.state = ChatMessage.State.ERROR;
            streamingMessage = null;
            adapter.notifyDataSetChanged();
        }
        if (error.kind == AiChatService.ErrorKind.QUOTA) {
            showQuotaDialog(error);
            return;
        }
        if (AiChatService.isVisionUnsupported(error) && getContext() != null) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.agent_vision_title)
                    .setMessage(R.string.agent_error_unsupported_vision)
                    .setPositiveButton(R.string.agent_action_switch_model,
                            (dialog, which) -> fetchModelsAndPick())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
        String note = explainError(error);
        // Retrying a dead connection must not stack the same note again.
        int lastIndex = messages.size() - 1;
        if (lastIndex < 0 || !note.equals(messages.get(lastIndex).text)) {
            appendMessage(ChatMessage.error(note));
        }
    }

    /**
     * Turns a provider failure into one or two plain sentences. The
     * provider's raw wording only survives when it is already short, clean
     * prose; anything JSON-shaped was stripped long before this point.
     */
    private String explainError(AiChatService.AiError error) {
        switch (error.kind) {
            case AUTH:
                return getString(R.string.agent_error_auth);
            case NETWORK:
                return getString(R.string.agent_error_network);
            case EMPTY:
                return getString(R.string.agent_error_empty);
            case UNSUPPORTED: {
                if (AiChatService.isVisionUnsupported(error)) {
                    return getString(R.string.agent_error_unsupported_vision);
                }
                String detail = error.message.toLowerCase(java.util.Locale.ROOT);
                if (detail.contains("developer instruction")
                        || detail.contains("system instruction")) {
                    return getString(R.string.agent_error_unsupported_dev);
                }
                if (detail.contains("multiturn") || detail.contains("multi-turn")
                        || detail.contains("multi turn")) {
                    return getString(R.string.agent_error_unsupported_multiturn);
                }
                return getString(R.string.agent_error_unsupported_generic);
            }
            case MODEL_GONE: {
                java.util.regex.Matcher suggestion = java.util.regex.Pattern
                        .compile("use (models/[A-Za-z0-9._\\-]+)")
                        .matcher(error.message);
                if (suggestion.find()) {
                    return getString(R.string.agent_error_model_gone_suggest,
                            suggestion.group(1));
                }
                return getString(R.string.agent_error_model_gone);
            }
            case QUOTA:
                return getString(R.string.agent_error_quota_now);
            default:
                if (error.httpCode == 0 && !error.message.isEmpty()) {
                    // Our own stop (loop cap, repeated failure): the
                    // message already says everything, in plain words.
                    return error.message;
                }
                if (error.httpCode >= 500) {
                    return getString(R.string.agent_error_server, error.httpCode);
                }
                String headline = getString(R.string.agent_error_bad_request,
                        error.httpCode);
                return error.message.isEmpty()
                        ? headline : headline + "\n" + error.message;
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
        // The key that just failed rests: briefly for a rate limit,
        // a day for a spent allowance.
        String detail = error.message.toLowerCase(java.util.Locale.ROOT);
        long restMinutes = (error.httpCode == 429 || detail.contains("rate"))
                ? 30L : 24L * 60L;
        providerConfig.markKeyExhausted(providerConfig.activeProvider(),
                System.currentTimeMillis() + restMinutes * 60_000L);
        providerConfig.save(requireContext());

        String message = getString(R.string.agent_quota_body);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.agent_quota_title)
                .setMessage(message)
                .setPositiveButton(R.string.agent_action_switch_model,
                        (dialog, which) -> fetchModelsAndPick())
                .setNegativeButton(R.string.agent_action_open_settings,
                        (dialog, which) -> openSettings())
                .setNeutralButton(android.R.string.cancel, null);
        if (providerConfig.keys(providerConfig.activeProvider()).size() > 1) {
            // With spare keys, switching key replaces Cancel in the
            // neutral slot; the dialog still dismisses by tapping away.
            builder.setNeutralButton(R.string.agent_action_switch_key,
                    (dialog, which) -> showKeyPicker());
        }
        builder.show();
    }

    /** Offers the active provider's other keys after a quota hit. */
    private void showKeyPicker() {
        if (getContext() == null) {
            return;
        }
        AiProvider provider = providerConfig.activeProvider();
        List<AiProviderConfig.KeyEntry> entries = providerConfig.keys(provider);
        int activeIndex = providerConfig.activeKeyIndex(provider);
        long now = System.currentTimeMillis();
        // The sheet is captured up front so a pick can close it before
        // the failed turn retries — the agent must never resume behind
        // an open dialog.
        final androidx.appcompat.app.AlertDialog[] keySheet =
                new androidx.appcompat.app.AlertDialog[1];
        List<CardRow> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            AiProviderConfig.KeyEntry entry = entries.get(i);
            String status = i == activeIndex
                    ? getString(R.string.agent_key_in_use)
                    : entry.isExhausted(now)
                            ? getString(R.string.agent_key_exhausted_until,
                                    android.text.format.DateFormat.format("MMM d, h:mm a",
                                            new java.util.Date(entry.exhaustedUntil)).toString())
                            : getString(R.string.agent_key_ready);
            final int index = i;
            final String label = entry.label;
            rows.add(new CardRow(label + " · " + AiProviderConfig.mask(entry),
                    status, null, null, i == activeIndex,
                    i == activeIndex ? "✓" : "Use",
                    () -> {
                        if (keySheet[0] != null) {
                            keySheet[0].dismiss();
                        }
                        providerConfig.setActiveKey(provider, index);
                        providerConfig.save(requireContext());
                        appendMessage(new ChatMessage(ChatMessage.Role.SYSTEM,
                                getString(R.string.agent_switched_key, label)));
                        retryFailedTurn();
                    }));
        }
        keySheet[0] = showCardSheet(requireContext(),
                getString(R.string.agent_action_switch_key), rows);
    }

    /**
     * Tapping the model name opens the Model Picker sheet: every model
     * this key can call as a tappable card with its capability badge
     * (Tools ✓ · Vision · context · Free · latency), a one-tap Test,
     * and a permanent Hide — no more bare text list.
     */
    private void showModelPicker() {
        showModelPickerSheet(null);
    }

    private void showModelPickerSheet(@Nullable Runnable onChosen) {
        if (binding == null || getContext() == null) {
            return;
        }
        if (providerConfig == null || !providerConfig.isConfigured()) {
            openSettings();
            return;
        }
        AiProviderConfig.Settings settings = providerConfig.activeSettings();
        if (settings.models == null || settings.models.isEmpty()) {
            binding.modelLabel.setText(R.string.agent_settings_checking);
            chatService.fetchModels(settings.baseUrl,
                    providerConfig.apiKey(providerConfig.activeProvider()),
                    new AiChatService.ModelsListener() {
                        @Override
                        public void onModels(List<String> models) {
                            settings.models = models;
                            providerConfig.save(requireContext());
                            updateProviderUi();
                            if (!models.isEmpty() && binding != null) {
                                showModelPickerSheet(onChosen);
                            }
                        }

                        @Override
                        public void onError(AiChatService.AiError error) {
                            if (binding != null) {
                                updateProviderUi();
                            }
                            appendMessage(ChatMessage.error(explainError(error)));
                        }
                    });
            return;
        }
        refreshModelCache(settings);
        Context context = requireContext();
        float d = context.getResources().getDisplayMetrics().density;
        AiProvider provider = providerConfig.activeProvider();
        List<String> visible = ModelCatalog.visibleModels(
                provider.name(), settings.models, settings.showAllModels);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Math.round(12f * d), 0,
                Math.round(12f * d), Math.round(12f * d));
        // Pinned zone: the Auto card and the controls row stay fixed
        // on screen while the model list scrolls underneath — the
        // controls must never sit below 300 model cards.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Math.round(12f * d), Math.round(4f * d),
                Math.round(12f * d), 0);
        androidx.appcompat.app.AlertDialog[] holder =
                new androidx.appcompat.app.AlertDialog[1];
        Runnable reopen = () -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            showModelPickerSheet(onChosen);
        };

        // ✨ Auto card first: the router picks the best healthy model
        // for each turn (vision-capable when the turn has images,
        // fastest Tools ✓ model otherwise) and fails over by itself.
        boolean autoActive = "auto".equalsIgnoreCase(settings.model);
        LinearLayout autoRow = new LinearLayout(context);
        autoRow.setOrientation(LinearLayout.VERTICAL);
        autoRow.setPadding(Math.round(14f * d), Math.round(11f * d),
                Math.round(14f * d), Math.round(11f * d));
        GradientDrawable autoBg = new GradientDrawable();
        autoBg.setCornerRadius(16f * d);
        autoBg.setColor(resolveThemeColor(context,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                0xFFE6EFF8));
        autoBg.setStroke(Math.max(1, Math.round(autoActive ? 1.6f * d : d)),
                autoActive ? resolveThemeColor(context,
                        pro.sketchware.R.attr.colorPrimary, 0xFF008DCD)
                        : resolveThemeColor(context,
                        com.google.android.material.R.attr.colorOutlineVariant,
                        0xFFB1C0CE));
        autoRow.setBackground(autoBg);
        LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        autoLp.topMargin = Math.round(7f * d);
        autoRow.setLayoutParams(autoLp);
        android.widget.TextView autoTitle = new android.widget.TextView(context);
        autoTitle.setText("✨ Auto — best model per turn");
        autoTitle.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        autoTitle.setTypeface(autoTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        autoRow.addView(autoTitle);
        android.widget.TextView autoSub = new android.widget.TextView(context);
        autoSub.setText("Vision + Tools when screenshots need it · "
                + "strongest healthy model otherwise (speed only "
                + "breaks ties) · automatic failover when a provider "
                + "is busy");
        autoSub.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        autoSub.setAlpha(0.8f);
        autoRow.addView(autoSub);
        autoRow.setOnClickListener(v -> {
            settings.model = ModelCatalog.AUTO;
            providerConfig.save(requireContext());
            updateProviderUi();
            appendMessage(new ChatMessage(ChatMessage.Role.SYSTEM,
                    getString(R.string.agent_switched_model,
                            "✨ Auto")));
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            if (onChosen != null) {
                onChosen.run();
            }
        });
        header.addView(autoRow);

        // Rate-limited models sink to the bottom of the list.
        java.util.List<String> ordered = new ArrayList<>(visible);
        ordered.sort(java.util.Comparator.comparingLong(
                id -> ModelCatalog.coolingForMillis(provider.name(),
                        id)));
        for (String id : ordered) {
            container.addView(buildModelCardRow(context, d,
                    provider.name(), id, id.equals(settings.model),
                    settings, holder, reopen, onChosen));
        }

        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = Math.round(10f * d);
        footer.setLayoutParams(footerLp);

        android.widget.TextView verifyBtn = buildSmallPillButton(
                context, "⚡ Verify top models", false);
        verifyBtn.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        verifyBtn.setGravity(Gravity.CENTER);
        verifyBtn.setOnClickListener(v -> {
            List<String> top = visible.subList(0,
                    Math.min(6, visible.size()));
            verifyBtn.setEnabled(false);
            verifyBtn.setText("Probing…");
            probeModelsInBackground(provider.name(), top, () -> {
                if (binding != null && binding.chatList != null) {
                    binding.chatList.post(reopen);
                }
            });
        });
        footer.addView(verifyBtn);

        List<String> hidden = ModelCatalog.hiddenModels(provider.name());
        android.widget.TextView hiddenBtn = buildSmallPillButton(
                context, "Hidden (" + hidden.size() + ")", false);
        LinearLayout.LayoutParams hiddenLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hiddenLp.leftMargin = Math.round(6f * d);
        hiddenBtn.setLayoutParams(hiddenLp);
        hiddenBtn.setGravity(Gravity.CENTER);
        hiddenBtn.setOnClickListener(v -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            showHiddenModelsDialog(provider.name(), reopen);
        });
        footer.addView(hiddenBtn);

        int autoSecs = providerConfig.activeSettings().autoApproveSeconds;
        android.widget.TextView timerBtn = buildSmallPillButton(context,
                autoSecs > 0 ? "⏱ " + autoSecs + "s" : "⏱ off",
                autoSecs > 0);
        LinearLayout.LayoutParams timerLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        timerLp.leftMargin = Math.round(6f * d);
        timerBtn.setLayoutParams(timerLp);
        timerBtn.setGravity(Gravity.CENTER);
        timerBtn.setOnClickListener(v -> {
            AiProviderConfig.Settings s = providerConfig.activeSettings();
            s.autoApproveSeconds = s.autoApproveSeconds > 0 ? 0 : 5;
            s.autoApproveSecondsSet = true;
            providerConfig.save(requireContext());
            if (adapter != null) {
                adapter.setAutoApproveSeconds(s.autoApproveSeconds);
            }
            Toast.makeText(context, s.autoApproveSeconds > 0
                    ? "Auto-approve on · " + s.autoApproveSeconds + "s"
                    : "Auto-approve off", Toast.LENGTH_SHORT).show();
            reopen.run();
        });
        footer.addView(timerBtn);
        header.addView(footer);

        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(container);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(header);
        root.addView(scroll);
        holder[0] = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.agent_pick_model)
                .setView(root)
                .setNegativeButton(R.string.agent_action_close, null)
                .create();
        holder[0].show();
    }

    /** One model's card: name + capability badge + Test + Hide. */
    private View buildModelCardRow(Context context, float d,
                                   String providerName, String id,
                                   boolean active,
                                   AiProviderConfig.Settings settings,
                                   androidx.appcompat.app.AlertDialog[] holder,
                                   Runnable reopen,
                                   @Nullable Runnable onChosen) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Math.round(14f * d);
        int padV = Math.round(11f * d);
        row.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16f * d);
        bg.setColor(resolveThemeColor(context,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                0xFFE6EFF8));
        bg.setStroke(Math.max(1, Math.round(active ? 1.6f * d : d)),
                active ? resolveThemeColor(context,
                        pro.sketchware.R.attr.colorPrimary, 0xFF008DCD)
                        : resolveThemeColor(context,
                        com.google.android.material.R.attr.colorOutlineVariant,
                        0xFFB1C0CE));
        row.setBackground(bg);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Math.round(7f * d);
        row.setLayoutParams(rowLp);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.TextView title = new android.widget.TextView(context);
        title.setText(ModelCatalog.displayName(id));
        title.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        textCol.addView(title);

        String badge = ModelCatalog.badgeText(providerName, id);
        long cooling = ModelCatalog.coolingForMillis(providerName, id);
        if (cooling > 0) {
            long mins = (cooling + 59_999L) / 60_000L;
            badge = "⏳ Rate-limited · " + mins + "m left"
                    + (badge.isEmpty() ? "" : " · " + badge);
        } else if (badge.isEmpty()) {
            badge = "Untested — long-press to test or hide";
        }
        android.widget.TextView subtitle = new android.widget.TextView(context);
        subtitle.setText(badge);
        subtitle.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        subtitle.setAlpha(0.8f);
        textCol.addView(subtitle);
        row.addView(textCol);

        // Clean card: tap = select; the rare actions (test, hide) live
        // on long-press so 300 cards never turn into a workbench.
        row.setOnClickListener(v -> {
            settings.model = id;
            providerConfig.save(requireContext());
            updateProviderUi();
            appendMessage(new ChatMessage(ChatMessage.Role.SYSTEM,
                    getString(R.string.agent_switched_model,
                            ModelCatalog.displayName(id))));
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            if (onChosen != null) {
                onChosen.run();
            }
        });
        row.setOnLongClickListener(v -> {
            List<CardRow> actions = new ArrayList<>();
            actions.add(new CardRow("⚡ Test this model",
                    "1-tool ping + vision check + latency",
                    null, null, false, "",
                    () -> {
                        List<String> one = new ArrayList<>();
                        one.add(id);
                        probeModelsInBackground(providerName, one,
                                () -> {
                                    if (binding != null
                                            && binding.chatList != null) {
                                        binding.chatList.post(reopen);
                                    }
                                });
                    }));
            actions.add(new CardRow("✕ Hide permanently",
                    "Gone from this picker — restore under Hidden",
                    null, null, false, "",
                    () -> {
                        ModelCatalog.setHidden(providerName, id, true);
                        Toast.makeText(context,
                                ModelCatalog.displayName(id)
                                        + " hidden — restore from the "
                                        + "Hidden list",
                                Toast.LENGTH_SHORT).show();
                        reopen.run();
                    }));
            androidx.appcompat.app.AlertDialog[] actionSheet =
                    new androidx.appcompat.app.AlertDialog[1];
            List<CardRow> wrapped = new ArrayList<>();
            for (CardRow actionRow : actions) {
                Runnable run = actionRow.onClick;
                wrapped.add(new CardRow(actionRow.title,
                        actionRow.subtitle, actionRow.swatches,
                        actionRow.gradient, actionRow.active,
                        actionRow.trailing, () -> {
                    // The action sheet closes first: a probe or a hide
                    // must never run behind an open dialog.
                    if (actionSheet[0] != null) {
                        actionSheet[0].dismiss();
                    }
                    run.run();
                }));
            }
            actionSheet[0] = showCardSheet(context,
                    ModelCatalog.displayName(id), wrapped);
            return true;
        });
        return row;
    }

    /**
     * Runs the one-model ping (+1×1-pixel vision check when vision is
     * still unknown) off the UI thread, merges every result into the
     * catalog, then reports back on the UI thread.
     */
    private void probeModelsInBackground(String providerName,
                                         List<String> modelIds,
                                         Runnable onUiDone) {
        String baseUrl = providerConfig.activeSettings().baseUrl;
        String apiKey = providerConfig.apiKey(
                providerConfig.activeProvider());
        List<String> ids = new ArrayList<>(modelIds);
        Thread worker = new Thread(() -> {
            int okCount = 0;
            for (String id : ids) {
                ModelCatalog.ModelInfo known =
                        ModelCatalog.info(providerName, id);
                boolean checkVision = known == null || known.vision == null;
                AiChatService.ProbeResult result = chatService.probeModel(
                        baseUrl, apiKey, id, checkVision);
                ModelCatalog.update(providerName, id, info -> {
                    if (result.ok) {
                        info.tools = result.tools;
                        info.latencyMs = result.latencyMs;
                        info.lastError = "";
                        if (result.visionKnown) {
                            info.vision = result.vision;
                        }
                    } else if (result.httpCode == 404) {
                        info.lastError = "404 retired";
                        info.hidden = true;
                    } else {
                        info.lastError = result.error == null
                                ? "failed" : result.error;
                    }
                });
                if (result.ok) {
                    okCount++;
                }
            }
            final int ok = okCount;
            final int total = ids.size();
            if (binding != null && isAdded()) {
                binding.chatList.post(() -> {
                    Toast.makeText(requireContext(),
                            "Tested " + total + " model"
                                    + (total == 1 ? "" : "s") + ": "
                                    + ok + " reachable", Toast.LENGTH_SHORT)
                            .show();
                    if (onUiDone != null) {
                        onUiDone.run();
                    }
                });
            }
        }, "model-probe");
        worker.setDaemon(true);
        worker.start();
    }

    /** Hidden models of the provider, tap to restore any of them. */
    private void showHiddenModelsDialog(String providerName,
                                        Runnable onDone) {
        Context context = requireContext();
        List<String> hidden = ModelCatalog.hiddenModels(providerName);
        if (hidden.isEmpty()) {
            Toast.makeText(context,
                    "No hidden models — hide one with ✕ on its card",
                    Toast.LENGTH_SHORT).show();
            onDone.run();
            return;
        }
        String[] items = hidden.toArray(new String[0]);
        new MaterialAlertDialogBuilder(context)
                .setTitle("Hidden models — tap to restore")
                .setItems(items, (dialog, which) -> {
                    ModelCatalog.setHidden(providerName, items[which], false);
                    onDone.run();
                })
                .setNegativeButton(R.string.agent_action_close, null)
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
        // Failover path: the current model just failed, so after the
        // cache is verified the picker opens and the failed turn
        // retries on whatever the user picks.
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
                        showModelPickerSheet(() -> retryFailedTurn());
                    }

                    @Override
                    public void onError(AiChatService.AiError error) {
                        appendMessage(ChatMessage.error(explainError(error)));
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
        // Arrived tokens wait here instead of going straight to the screen:
        // on a bursty connection they would otherwise land in lumps and the
        // bubble would grow in jerks.
        synchronized (pendingReveal) {
            pendingReveal.append(text);
        }
        if (!revealScheduled) {
            revealScheduled = true;
            binding.chatList.postDelayed(revealTokens, REVEAL_TICK_MS);
        }
    }

    /**
     * The smoothing queue. Every tick it moves a few characters from the
     * waiting buffer into the bubble — a calm, even pace no matter how the
     * network delivered them — speeding up when a lot is waiting so long
     * answers never lag far behind the wire.
     */
    private final Runnable revealTokens = new Runnable() {
        @Override
        public void run() {
            revealScheduled = false;
            if (binding == null || streamingMessage == null) {
                return;
            }
            boolean pinnedToEnd = !binding.chatList.canScrollVertically(1);
            boolean moreWaiting;
            synchronized (pendingReveal) {
                int waiting = pendingReveal.length();
                if (waiting > 0) {
                    int count = Math.min(waiting, Math.max(4, waiting / 6));
                    streamingMessage.text += pendingReveal.substring(0, count);
                    pendingReveal.delete(0, count);
                }
                moreWaiting = pendingReveal.length() > 0;
            }
            int index = messages.indexOf(streamingMessage);
            if (index >= 0) {
                adapter.notifyTextChanged(index);
            }
            if (pinnedToEnd && index >= 0) {
                // Follow the growing text only while the user is at the end;
                // scrolling up to read must never be yanked back down.
                binding.chatList.scrollToPosition(index);
            }
            if (moreWaiting) {
                revealScheduled = true;
                binding.chatList.postDelayed(this, REVEAL_TICK_MS);
            }
        }
    };

    /** Moves everything still waiting into the bubble at once. */
    private void flushPendingTokens() {
        if (binding == null || streamingMessage == null) {
            synchronized (pendingReveal) {
                pendingReveal.setLength(0);
            }
            return;
        }
        synchronized (pendingReveal) {
            if (pendingReveal.length() > 0) {
                streamingMessage.text += pendingReveal;
                pendingReveal.setLength(0);
            }
        }
        streamingMessage.state = ChatMessage.State.STREAMING;
        int index = messages.indexOf(streamingMessage);
        if (index >= 0) {
            adapter.notifyTextChanged(index);
            scrollToNewest();
        }
    }

    private void setStreamingComplete() {
        if (streamingMessage == null) {
            return;
        }
        if (binding != null) {
            binding.chatList.removeCallbacks(revealTokens);
        }
        revealScheduled = false;
        synchronized (pendingReveal) {
            if (pendingReveal.length() > 0) {
                streamingMessage.text += pendingReveal;
                pendingReveal.setLength(0);
            }
        }
        streamingMessage.state = ChatMessage.State.COMPLETE;
        streamingMessage.repliedAt = System.currentTimeMillis();
        streamingMessage.durationMs =
                SystemClock.elapsedRealtime() - requestStartedAt;
        boolean finishedComplete = true;
        if (streamingMessage.text.trim().isEmpty()) {
            // A turn that ends with NOTHING on screen reads as a
            // freeze — it becomes a visible failed bubble with the
            // same tap-to-retry affordance as any other failure.
            streamingMessage.state = ChatMessage.State.ERROR;
            streamingMessage.text =
                    "The model returned an empty reply — tap to retry.";
            failedBubble = streamingMessage;
            finishedComplete = false;
        }
        final String finishedText = streamingMessage.text;
        streamingMessage = null;
        if (binding != null) {
            adapter.notifyDataSetChanged();
        }
        if (finishedComplete) {
            maybeBounceChatChoice(finishedText);
        }
    }

    /**
     * One-shot bounce: the model was asked to offer design directions
     * through the ask_choice tool but DREW them as an OpenUI block in
     * the bubble instead (observed in the field — the user saw no
     * popup at all). Fires ONLY on the design-question signature — at
     * least two options carrying hex colour codes, which the prompt
     * requires for style directions — so deliberate in-chat ChoiceChips
     * (layout picks, mockups) are never hijacked. When no choice sheet
     * ran this turn, the block's question and options are lifted into
     * the real tappable sheet; the pick is sent as the user's own
     * answer.
     */
    private void maybeBounceChatChoice(String text) {
        if (choiceSheetShownThisTurn || generating || !isAdded()) {
            return;
        }
        final java.util.List<String> choice =
                OpenUiLang.extractChatChoice(text);
        if (choice == null || choice.size() < 3) {
            return;
        }
        final String question = choice.get(0);
        final java.util.List<String> options =
                new ArrayList<>(choice.subList(1, choice.size()));
        int hexOptions = 0;
        for (String option : options) {
            if (option.matches(".*#[0-9A-Fa-f]{6}.*")) {
                hexOptions++;
            }
        }
        if (hexOptions < 2) {
            // Not a design-directions block — leave it as drawn.
            return;
        }
        Thread bounce = new Thread(() -> {
            String picked = awaitChoice(question, options);
            if (picked != null && !picked.trim().isEmpty()) {
                runOnUi(() -> sendChoiceAsUser(picked));
            }
        });
        bounce.setDaemon(true);
        bounce.start();
    }

    /** Sends the bounced choice as if the user had typed it. */
    private void sendChoiceAsUser(String text) {
        if (!isAdded() || binding == null || generating) {
            return;
        }
        appendMessage(ChatMessage.user(text));
        persistChat();
        lastUserText = text;
        ChatMessage reply = ChatMessage.assistant("");
        streamingMessage = reply;
        appendMessage(reply);
        startRequest();
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
            updateEmptyState();
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
        updateEmptyState();
    }

    /** Replaces the transcript with the current provider state. */
    private void showCurrentState() {
        if (binding == null) {
            return;
        }
        messages.clear();
        streamingMessage = null;
        if (providerConfig != null && providerConfig.isConfigured()) {
            restoreWorkingConversation();
        }
        adapter.notifyDataSetChanged();
        updateProviderUi();
        updateEmptyState();
        updateActionButton();
    }

    /**
     * Brings back the conversation of the project that was being worked
     * on when the app was last closed.
     *
     * <p>The working project outlives the process, so its chat has to
     * come back with it — approval cards and build cards included.
     * Until it did, a restart showed an empty chat until the project
     * was picked again by hand, and anything exported from that state
     * carried no cards at all, which made a conversation look like it
     * never proposed or changed anything.
     */
    private void restoreWorkingConversation() {
        String working = providerConfig.workingProjectId();
        if (working == null || working.isEmpty()) {
            return;
        }
        chatProjectId = working;
        List<ChatMessage> saved = AgentChatLog.load(working);
        if (saved.isEmpty()) {
            return;
        }
        messages.addAll(saved);
        if (binding != null) {
            binding.chatList.scrollToPosition(messages.size() - 1);
        }
    }

    private void updateProviderUi() {
        if (binding == null || providerConfig == null) {
            return;
        }
        boolean configured = providerConfig.isConfigured();
        binding.chooseModelButton.setVisibility(configured ? View.GONE : View.VISIBLE);
        String model = configured
                ? providerConfig.activeSettings().model : "";
        String lastAuto = configured
                ? providerConfig.activeSettings().lastAutoModel : "";
        binding.modelLabel.setText(configured
                ? ("auto".equalsIgnoreCase(model)
                ? (lastAuto.isEmpty() ? "✨ Auto"
                : "✨ Auto · " + ModelCatalog.displayName(lastAuto))
                : model)
                : getString(R.string.agent_no_provider));
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (binding == null) {
            return;
        }
        boolean configured = providerConfig != null && providerConfig.isConfigured();
        boolean hasConversation = !messages.isEmpty();
        boolean showHero = !hasConversation && !generating && !keyboardOpen;
        binding.emptyStateContainer.setVisibility(showHero ? View.VISIBLE : View.GONE);
        if (!showHero) {
            return;
        }
        if (!configured) {
            binding.emptyStateTitle.setText(R.string.agent_provider_empty_title);
            binding.emptyStateSubtitle.setText(R.string.agent_provider_empty_body);
            binding.chooseModelButton.setVisibility(View.VISIBLE);
            binding.emptyQuickRow.setVisibility(View.GONE);
        } else {
            binding.emptyStateTitle.setText(R.string.agent_ready_title);
            if (workingProjectDisplayName != null
                    && !workingProjectDisplayName.isEmpty()) {
                binding.emptyStateSubtitle.setText(getString(
                        R.string.agent_ready_subtitle_project,
                        workingProjectDisplayName));
            } else {
                binding.emptyStateSubtitle.setText(
                        R.string.agent_ready_subtitle_no_project);
            }
            binding.chooseModelButton.setVisibility(View.GONE);
            binding.emptyQuickRow.setVisibility(View.VISIBLE);
        }
    }

    private void prefillComposer(String prompt) {
        if (binding == null) {
            return;
        }
        binding.chatInput.setText(prompt);
        binding.chatInput.setSelection(prompt.length());
        binding.chatInput.requestFocus();
        if (getContext() != null) {
            InputMethodManager imm = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(binding.chatInput, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    private static Bitmap loadCustomProjectIcon(String scId) {
        if (scId == null || scId.isEmpty()) {
            return null;
        }
        try {
            File iconFile = new File(wq.e() + File.separator + scId
                    + File.separator + "icon.png");
            if (iconFile.isFile()) {
                return AgentMedia.decodeThumbnail(iconFile.getAbsolutePath(), 96);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static int resolveThemeColor(Context context, int attr, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (context != null && context.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                try {
                    return androidx.core.content.ContextCompat.getColor(
                            context, tv.resourceId);
                } catch (Exception ignored) {
                }
            }
            return tv.data;
        }
        return fallback;
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
                generating ? R.drawable.ic_mtrl_stop : R.drawable.agent_ic_send);
        binding.sendButton.setContentDescription(
                getString(generating ? R.string.agent_action_stop : R.string.agent_action_send));
        boolean hasText = binding.chatInput.getText() != null
                && !binding.chatInput.getText().toString().trim().isEmpty();
        boolean hasReadyInput = (hasText || !pendingAttachments.isEmpty())
                && !attachingImages;
        binding.sendButton.setEnabled(hasReadyInput || generating);
        binding.attachButton.setEnabled(!generating && !attachingImages);
        binding.attachButton.setAlpha(
                (generating || attachingImages) ? 0.5f : 1.0f);
        // The working project is locked to a running turn: switching
        // it mid-flight would mix two projects' context and memory.
        binding.workingOnCard.setEnabled(!generating);
        binding.workingOnCard.setAlpha(generating ? 0.6f : 1.0f);
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
