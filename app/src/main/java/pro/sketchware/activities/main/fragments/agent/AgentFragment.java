package pro.sketchware.activities.main.fragments.agent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import android.view.inputmethod.InputMethodManager;
import android.widget.PopupMenu;
import android.widget.Toast;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;import java.util.List;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import a.a.a.qA;
import pro.sketchware.R;
import pro.sketchware.ai.AgentChatLog;
import pro.sketchware.ai.AgentExport;
import pro.sketchware.ai.AgentJournal;
import pro.sketchware.ai.AgentKnowledgePack;
import pro.sketchware.ai.AgentLoop;
import pro.sketchware.ai.AgentProposal;

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
    /** The project whose stored conversation is on screen, if any. */
    private String chatProjectId = "";

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
        adapter.setOnApprovalListener(this::onApprovalDecision);
        adapter.setOnInstallListener(this::onInstallRequest);
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
        // History is conversations: the projects that have a saved
        // chat, each one a door back into that conversation. (What
        // was done in a project lives in its task log, in the box.)
        binding.historyButton.setOnClickListener(v -> showChatHistoryDialog());
        binding.exportButton.setOnClickListener(v -> exportConversation());
        binding.styleButton.setOnClickListener(v -> showStyleBriefDialog());
        binding.planButton.setOnClickListener(v -> showPlanDialog());
        binding.taskLogButton.setOnClickListener(v -> showTaskLogDialog());
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
        agentLoop.run(settings.baseUrl,
                providerConfig.apiKey(providerConfig.activeProvider()),
                settings.model, history,
                providerConfig.workingProjectId(),
                providerConfig.allowAgentModify(),
                new AgentLoop.Listener() {
                    @Override
                    public void onToolNote(String line) {
                        runOnUi(() -> insertToolNote(line));
                    }

                    @Override
                    public void onAnswer(String text, String thinking) {
                        runOnUi(() -> {
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
                            generating = false;
                            handleError(error);
                            updateActionButton();
                            refreshStrip();
                            persistChat();
                        });
                    }

                    @Override
                    public boolean onApproval(AgentProposal proposal) {
                        return awaitApproval(proposal);
                    }

                    @Override
                    public com.google.gson.JsonObject onPickColor(
                            String question, String initialHex) {
                        return awaitColorPick(question, initialHex);
                    }

                    @Override
                    public void onApprovalResolved(AgentProposal proposal,
                                                   com.google.gson.JsonObject result) {
                        runOnUi(() -> {
                            resolveApprovalCard(proposal, result);
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
                        });
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
            binding.workingOnName.setText(R.string.agent_working_on_choose);
            binding.workingOnSubtitle.setText(R.string.agent_working_on_hint);
            setCardCompact(false);
            refreshStrip();
            return;
        }
        io.execute(() -> {
            SketchwareProjects.ProjectInfo project = SketchwareProjects.project(scId);
            runOnUi(() -> {
                if (binding == null) {
                    return;
                }
                if (project == null) {
                    binding.workingOnName.setText(R.string.agent_working_on_choose);
                    binding.workingOnSubtitle.setText(R.string.agent_working_on_hint);
                    setCardCompact(false);
                } else {
                    binding.workingOnName.setText(project.name);
                    binding.workingOnSubtitle.setText(
                            project.appName + " · " + project.packageName);
                    // Chosen: the card settles into a compact chip so
                    // the chat gets the room.
                    setCardCompact(true);
                }
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
        appendMessage(ChatMessage.error(getString(scId.isEmpty()
                ? R.string.agent_working_on_cleared
                : R.string.agent_working_on_set, name)));
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
        if (!scId.isEmpty()) {
            if (generating) {
                // While work lands, the strip follows the plan's
                // step in hand; without a plan it names the task.
                String planLine = currentPlanStepLine(scId);
                if (!planLine.isEmpty()) {
                    text = planLine;
                } else {
                    String title = AgentJournal.openTitle(scId);
                    if (title.isEmpty()) {
                        title = lastUserText;
                    }
                    text = getString(R.string.agent_task_working, title);
                }
            } else {
                text = AgentJournal.stripText(scId);
                resumable = !text.isEmpty();
            }
        }
        stripShownText = text;
        boolean show = !text.isEmpty() && !text.equals(stripDismissedText);
        binding.taskStrip.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            binding.taskStripText.setText(text);
            binding.taskStripResume.setVisibility(
                    resumable ? View.VISIBLE : View.GONE);
        }
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
                return getString(R.string.agent_plan_step_line, i + 1,
                        steps.size(),
                        step.has("title")
                                ? step.get("title").getAsString()
                                : step.has("text")
                                        ? step.get("text").getAsString()
                                        : "");
            }
        }
        return "";
    }

    /** Continues the paused task as a normal message in the chat. */
    private void resumeTask() {
        if (generating || binding == null) {
            return;
        }
        stripDismissedText = "";
        // The same task entry continues — its title and finished
        // steps are already recorded; the brief tells the model so.
        AgentJournal.reopenLatest(providerConfig.workingProjectId());
        binding.chatInput.setText(
                getString(R.string.agent_task_resume_message));
        sendCurrentInput();
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

    /** The Plan button: the project's agreed style direction. */
    private void showStyleBriefDialog() {
        String scId = workingProjectOrToast();
        if (scId.isEmpty()) {
            return;
        }
        String brief = AgentJournal.styleBrief(scId);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.agent_style_brief_title)
                .setMessage(brief.isEmpty()
                        ? getString(R.string.agent_style_brief_empty)
                        : styledStyleText(brief))
                .setNegativeButton(R.string.agent_action_close, null)
                .setPositiveButton(R.string.agent_style_brief_edit,
                        (dialog, which) -> showStyleBriefEditor(scId))
                .show();
    }

    /**
     * The style as a small design sheet: its headed sections (Audience,
     * Colours, Text sizes, Buttons and cards, Spacing, Do, Avoid) stand
     * out in bold with air between them. A style saved as one plain
     * paragraph — everything older ones are — shows as it is.
     */
    private static CharSequence styledStyleText(String brief) {
        String[] headings = {"Audience", "Mood", "Colours", "Colors",
                "Text sizes", "Text", "Buttons and cards", "Buttons",
                "Cards", "Spacing", "Layout", "Shapes", "Do", "Avoid"};
        android.text.SpannableStringBuilder out =
                new android.text.SpannableStringBuilder();
        for (String line : brief.split("\\n")) {
            String trimmed = line.trim();
            String heading = null;
            for (String candidate : headings) {
                if (trimmed.regionMatches(true, 0, candidate, 0,
                        candidate.length())
                        && trimmed.length() > candidate.length()
                        && (trimmed.charAt(candidate.length()) == ':'
                                || trimmed.charAt(candidate.length()) == '-'
                                || trimmed.charAt(candidate.length()) == '.')) {
                    heading = candidate;
                    break;
                }
            }
            if (heading != null) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                int start = out.length();
                out.append(trimmed);
                out.setSpan(new android.text.style.StyleSpan(
                                android.graphics.Typeface.BOLD), start,
                        start + heading.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                if (out.length() > 0) {
                    out.append("\n");
                }
                out.append(trimmed);
            }
        }
        return out;
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
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
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
        String scId = workingProjectOrToast();
        if (scId.isEmpty()) {
            return;
        }
        List<ChatMessage> snapshot = new ArrayList<>(messages);
        String provider = providerConfig.activeProvider().name();
        String model = providerConfig.activeSettings().model;
        SketchwareProjects.ProjectInfo info =
                SketchwareProjects.project(scId);
        String projectName = info == null ? scId : info.name;
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
        String scId = workingProjectOrToast();
        if (scId.isEmpty()) {
            return;
        }
        com.google.gson.JsonObject plan = AgentJournal.plan(scId);
        String title = plan.has("title")
                ? plan.get("title").getAsString() : "";
        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(title.isEmpty()
                                ? getString(R.string.agent_plan_title)
                                : title)
                        .setMessage(planBodyText(scId))
                        .setNegativeButton(R.string.agent_action_close, null)
                        .create();
        dialog.show();
        if (generating) {
            // While work is landing, the open Plan keeps itself
            // current instead of freezing at the moment it opened.
            android.widget.TextView bodyView =
                    dialog.findViewById(android.R.id.message);
            if (bodyView != null) {
                Runnable[] refresh = new Runnable[1];
                refresh[0] = () -> {
                    if (!dialog.isShowing()) {
                        return;
                    }
                    bodyView.setText(planBodyText(scId));
                    if (generating) {
                        bodyView.postDelayed(refresh[0], 1500);
                    }
                };
                bodyView.postDelayed(refresh[0], 1500);
            }
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
        String scId = workingProjectOrToast();
        if (scId.isEmpty()) {
            return;
        }
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
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.agent_task_log_title)
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
            android.widget.TextView name =
                    row.findViewById(R.id.picker_name);
            android.widget.TextView subtitle =
                    row.findViewById(R.id.picker_subtitle);
            View check = row.findViewById(R.id.picker_check);
            if (position == 0) {
                name.setText(R.string.agent_working_on_none);
                subtitle.setText(R.string.agent_picker_none_hint);
                check.setVisibility(
                        currentId.isEmpty() ? View.VISIBLE : View.GONE);
            } else {
                SketchwareProjects.ProjectInfo project =
                        projects.get(position - 1);
                name.setText(project.appName.isEmpty()
                        ? project.name : project.appName);
                subtitle.setText(project.name + " · " + project.packageName);
                check.setVisibility(project.scId.equals(currentId)
                        ? View.VISIBLE : View.GONE);
            }
            return row;
        }
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
        messages.add(index, ChatMessage.error(line));
        adapter.notifyItemInserted(index);
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
            failedBubble.state = ChatMessage.State.ERROR;
            streamingMessage = null;
            adapter.notifyDataSetChanged();
        }
        if (error.kind == AiChatService.ErrorKind.QUOTA) {
            showQuotaDialog(error);
            return;
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
        String[] items = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            AiProviderConfig.KeyEntry entry = entries.get(i);
            String suffix = i == activeIndex
                    ? getString(R.string.agent_key_in_use)
                    : entry.isExhausted(now)
                            ? getString(R.string.agent_key_exhausted_until,
                                    android.text.format.DateFormat.format("MMM d, h:mm a",
                                            new java.util.Date(entry.exhaustedUntil)).toString())
                            : getString(R.string.agent_key_ready);
            items[i] = entry.label + " · " + AiProviderConfig.mask(entry)
                    + " — " + suffix;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.agent_action_switch_key)
                .setItems(items, (dialog, which) -> {
                    providerConfig.setActiveKey(provider, which);
                    providerConfig.save(requireContext());
                    appendMessage(new ChatMessage(ChatMessage.Role.SYSTEM,
                            getString(R.string.agent_switched_key,
                                    entries.get(which).label)));
                    retryFailedTurn();
                })
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
                        appendMessage(ChatMessage.error(explainError(error)));
                    }
                });
    }

    private void pickModel(AiProviderConfig.Settings settings, List<String> models) {
        if (getContext() == null) {
            return;
        }
        // Only conversational models are offered; the full fetched list
        // stays cached for anyone who enables "show all" in Settings.
        String[] items = AiProviderConfig.pickerModels(models, settings.showAllModels)
                .toArray(new String[0]);
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
