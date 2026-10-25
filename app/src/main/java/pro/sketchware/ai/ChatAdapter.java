package pro.sketchware.ai;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.widget.EditText;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.noties.markwon.Markwon;

import pro.sketchware.R;
import pro.sketchware.databinding.AgentChatItemApprovalBinding;
import pro.sketchware.databinding.AgentChatItemAssistantBinding;
import pro.sketchware.databinding.AgentChatItemSystemBinding;
import pro.sketchware.databinding.AgentChatItemUserBinding;

/**
 * Renders the agent chat. User messages are right-aligned bubbles, assistant
 * messages are left-aligned and show a spinner while tokens are still
 * streaming in.
 *
 * <p>The adapter deliberately does not use {@code ListAdapter}. The chat
 * message is a mutable object and the streaming answer is updated in place,
 * so a diff would always compare an object with itself and would never
 * report a change: the answer would stay empty. The list is small and the
 * adapter is told exactly which row changed.
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {

    private static final int TYPE_USER = 0;
    private static final int TYPE_ASSISTANT = 1;
    private static final int TYPE_SYSTEM = 2;
    private static final int TYPE_APPROVAL = 3;
    private static final int TYPE_CARD = 4;

    /** The list the fragment owns; the adapter never copies it. */
    private final List<ChatMessage> items;

    private OnMessageClickListener clickListener;

    public ChatAdapter(List<ChatMessage> items) {
        this.items = items;
    }

    /** Taps on a bubble: retry for a failed turn, copy for the rest. */
    public interface OnMessageClickListener {
        void onMessageClick(View view, ChatMessage message);
    }

    public void setOnMessageClickListener(OnMessageClickListener listener) {
        this.clickListener = listener;
    }

    /** Approve/Reject taps on an approval card. */
    public interface OnApprovalListener {
        void onApprovalDecision(ChatMessage message, boolean approved);
    }

    private OnApprovalListener approvalListener;

    public void setOnApprovalListener(OnApprovalListener listener) {
        this.approvalListener = listener;
    }

    /**
     * Timed auto-approve, in seconds (0 = off). Pending approval cards
     * count down on their Approve button and self-approve at zero;
     * cards pause the timer when the user opens Details or touches
     * them, and manual-hold tools never count down at all.
     */
    private int autoApproveSeconds;

    public void setAutoApproveSeconds(int seconds) {
        this.autoApproveSeconds = Math.max(0, seconds);
    }

    /** Install taps on a finished build card. */
    public interface OnInstallListener {
        void onInstall(ChatMessage message);
    }

    private OnInstallListener installListener;

    public void setOnInstallListener(OnInstallListener listener) {
        this.installListener = listener;
    }

    /** Button taps on an app card (build choice, blueprint, board). */
    public interface OnCardListener {
        /** which: "blueprint" | "steady" | "fast" */
        void onBuildChoice(ChatMessage card, String which);

        /** verdict: "" build as drawn, text = adjustments, null = cancel */
        void onBlueprintDecision(ChatMessage card, String verdict);

        /** action: "stop" | "undo" | "rebuild" | "keep" (partIndex >= 1) */
        void onBoardAction(ChatMessage card, String action, int partIndex);
    }

    private OnCardListener cardListener;

    public void setOnCardListener(OnCardListener listener) {
        this.cardListener = listener;
    }

    /** Interactive OpenUI Lang bubble events (state save, @ToAssistant, @OpenUrl). */
    public interface OnOpenUiListener {
        void onOpenUiStateChanged(ChatMessage message);

        void onOpenUiSendToAssistant(ChatMessage message, String promptText,
                                     JsonElement context, JsonObject formState);

        void onOpenUiOpenUrl(String url);
    }

    private OnOpenUiListener openUiListener;

    public void setOnOpenUiListener(OnOpenUiListener listener) {
        this.openUiListener = listener;
    }

    private void fireClick(View view, int position) {
        if (clickListener != null && position != RecyclerView.NO_POSITION) {
            clickListener.onMessageClick(view, items.get(position));
        }
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage message = items.get(position);
        if (message.isFromUser()) {
            return TYPE_USER;
        }
        if (message.role == ChatMessage.Role.APPROVAL) {
            return TYPE_APPROVAL;
        }
        if (message.role == ChatMessage.Role.CARD) {
            return TYPE_CARD;
        }
        if (message.isFromApp()) {
            return TYPE_SYSTEM;
        }
        return TYPE_ASSISTANT;
    }

    @NonNull
    /** Renders model answers (bold, lists, code) instead of raw symbols. */
    private static Markwon sharedMarkwon;

    private static Markwon markwon(View view) {
        if (sharedMarkwon == null) {
            sharedMarkwon = Markwon.create(view.getContext());
        }
        return sharedMarkwon;
    }

    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            return new UserHolder(AgentChatItemUserBinding.inflate(inflater, parent, false));
        }
        if (viewType == TYPE_SYSTEM) {
            return new SystemHolder(AgentChatItemSystemBinding.inflate(inflater, parent, false));
        }
        if (viewType == TYPE_APPROVAL) {
            return new ApprovalHolder(
                    AgentChatItemApprovalBinding.inflate(inflater, parent, false));
        }
        if (viewType == TYPE_CARD) {
            return new CardHolder(new LinearLayout(parent.getContext()));
        }
        return new AssistantHolder(AgentChatItemAssistantBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.owner = this;
        if (holder instanceof CardHolder cardHolder) {
            cardHolder.bind(items.get(position), this);
            return;
        }
        holder.bind(items.get(position));
    }

    private static final String PAYLOAD_TEXT = "text";

    /**
     * Repaints only the text of a streaming bubble. A full rebind on every
     * token makes the list re-measure the row each time and the bubble
     * staggers; this is the light touch used while text is flowing.
     */
    public void notifyTextChanged(int position) {
        notifyItemChanged(position, PAYLOAD_TEXT);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && holder instanceof AssistantHolder assistant) {
            assistant.updateText(items.get(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    /** Wires taps on the given views to the message click listener. */
    void attachClicks(Holder holder, View... views) {
        for (View view : views) {
            view.setOnClickListener(v ->
                    fireClick(v, holder.getAbsoluteAdapterPosition()));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        ChatAdapter owner;

        Holder(@NonNull View itemView) {
            super(itemView);
        }

        void bind(ChatMessage message) {
        }
    }

    static class UserHolder extends Holder {
        private final AgentChatItemUserBinding binding;

        UserHolder(@NonNull AgentChatItemUserBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        void bind(ChatMessage message) {
            boolean hasText = message.text != null
                    && !message.text.trim().isEmpty();
            binding.messageText.setText(hasText ? message.text : "");
            binding.messageText.setVisibility(
                    hasText ? View.VISIBLE : View.GONE);

            binding.messageImages.removeAllViews();
            if (message.hasAttachments()) {
                Context ctx = itemView.getContext();
                float density = ctx.getResources().getDisplayMetrics().density;
                int padH = Math.round(8f * density);
                int padTop = Math.round(8f * density);
                int padBot = hasText
                        ? Math.round(2f * density)
                        : Math.round(8f * density);
                binding.messageImages.setPadding(padH, padTop, padH, padBot);
                boolean single = message.attachments.size() == 1;
                int cardW = Math.round((single ? 176f : 112f) * density);
                int cardH = Math.round((single ? 176f : 112f) * density);
                int gap = Math.round(6f * density);
                float radius = 12f * density;
                int idx = 0;
                for (ChatMessage.Attachment att : message.attachments) {
                    if (att == null || att.path == null
                            || att.path.isEmpty()) {
                        continue;
                    }
                    ImageView iv = new ImageView(ctx);
                    LinearLayout.LayoutParams lp =
                            new LinearLayout.LayoutParams(cardW, cardH);
                    if (idx > 0) {
                        lp.leftMargin = gap;
                    }
                    iv.setLayoutParams(lp);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    GradientDrawable clipShape = new GradientDrawable();
                    clipShape.setCornerRadius(radius);
                    clipShape.setColor(0x22000000);
                    iv.setBackground(clipShape);
                    iv.setClipToOutline(true);
                    Bitmap thumb = AgentMedia.decodeThumbnail(
                            att.path, Math.round(220f * density));
                    if (thumb != null) {
                        iv.setImageBitmap(thumb);
                    } else {
                        iv.setImageResource(R.drawable.ic_mtrl_image);
                    }
                    final String previewPath = att.path;
                    iv.setOnClickListener(
                            v -> showImagePreview(v.getContext(), previewPath));
                    binding.messageImages.addView(iv);
                    idx++;
                }
                binding.messageImagesScroll.setVisibility(
                        idx > 0 ? View.VISIBLE : View.GONE);
            } else {
                binding.messageImagesScroll.setVisibility(View.GONE);
            }

            if (owner != null) {
                owner.attachClicks(this, itemView, binding.messageText);
            }
        }

        private static void showImagePreview(Context context, String path) {
            if (context == null || path == null || path.isEmpty()) {
                return;
            }
            Bitmap full = AgentMedia.decodeThumbnail(path, 1280);
            if (full == null) {
                return;
            }
            ImageView preview = new ImageView(context);
            int pad = Math.round(
                    12f * context.getResources().getDisplayMetrics().density);
            preview.setPadding(pad, pad, pad, pad);
            preview.setAdjustViewBounds(true);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setImageBitmap(full);
            new MaterialAlertDialogBuilder(context)
                    .setView(preview)
                    .setPositiveButton(R.string.agent_action_close, null)
                    .show();
        }
    }

    static class SystemHolder extends Holder {
        private final AgentChatItemSystemBinding binding;

        SystemHolder(@NonNull AgentChatItemSystemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        void bind(ChatMessage message) {
            binding.messageText.setText(message.text);
            if (owner != null) {
                owner.attachClicks(this, itemView, binding.messageText);
            }
        }
    }

    static class AssistantHolder extends Holder {
        private final AgentChatItemAssistantBinding binding;

        AssistantHolder(@NonNull AgentChatItemAssistantBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        void bind(ChatMessage message) {
            binding.retryText.setVisibility(
                    message.state == ChatMessage.State.ERROR ? View.VISIBLE : View.GONE);
            setTyping(message.state == ChatMessage.State.STREAMING
                    || message.state == ChatMessage.State.PENDING);
            if (owner != null) {
                owner.attachClicks(this, itemView, binding.messageText,
                        binding.retryText);
            }
            boolean hasThinking = message.thinking != null
                    && !message.thinking.trim().isEmpty();
            binding.thinkingBox.setVisibility(
                    hasThinking ? View.VISIBLE : View.GONE);
            binding.thinkingHeader.setVisibility(
                    hasThinking ? View.VISIBLE : View.GONE);
            binding.thinkingText.setVisibility(hasThinking
                    && message.thinkingExpanded ? View.VISIBLE : View.GONE);
            if (hasThinking) {
                markwon(binding.messageText).setMarkdown(
                        binding.thinkingText, message.thinking);
                updateThinkingHeader(binding, message.thinkingExpanded);
                View.OnClickListener toggleThinking = v -> {
                    message.thinkingExpanded = !message.thinkingExpanded;
                    binding.thinkingText.setVisibility(
                            message.thinkingExpanded ? View.VISIBLE : View.GONE);
                    updateThinkingHeader(binding, message.thinkingExpanded);
                };
                binding.thinkingBox.setOnClickListener(toggleThinking);
                binding.thinkingHeader.setOnClickListener(toggleThinking);
            }
            renderBody(message);
            // No words yet: the dots pill speaks alone, no empty shell.
            binding.messageCard.setVisibility(
                    message.text.isEmpty() ? View.GONE : View.VISIBLE);
        }

        private void renderBody(ChatMessage message) {
            boolean streaming = message.state == ChatMessage.State.STREAMING
                    || message.state == ChatMessage.State.PENDING;
            boolean hasOpenUi = OpenUiLang.containsOpenUi(message.text);
            int targetWidth = hasOpenUi
                    ? ViewGroup.LayoutParams.MATCH_PARENT
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            ViewGroup.LayoutParams cardLp = binding.messageCard.getLayoutParams();
            if (cardLp != null && cardLp.width != targetWidth) {
                cardLp.width = targetWidth;
                binding.messageCard.setLayoutParams(cardLp);
            }
            ViewGroup.LayoutParams boxLp = binding.messageContentBox.getLayoutParams();
            if (boxLp != null && boxLp.width != targetWidth) {
                boxLp.width = targetWidth;
                binding.messageContentBox.setLayoutParams(boxLp);
            }

            if (!hasOpenUi) {
                binding.openuiContainer.setVisibility(View.GONE);
                if (binding.openuiContainer.getChildCount() > 0) {
                    binding.openuiContainer.removeAllViews();
                    binding.openuiContainer.setTag(null);
                }
                binding.messageText.setVisibility(View.VISIBLE);
                if (streaming) {
                    // Still arriving: raw text now, formatting at completion.
                    binding.messageText.setText(message.text);
                } else {
                    markwon(binding.messageText).setMarkdown(
                            binding.messageText, message.text);
                }
                return;
            }

            binding.messageText.setVisibility(View.GONE);
            binding.openuiContainer.setVisibility(View.VISIBLE);
            OpenUiChatRenderer.render(
                    binding.openuiContainer,
                    message,
                    streaming,
                    markwon(binding.messageText),
                    new OpenUiChatRenderer.Host() {
                        @Override
                        public void onStateChanged(ChatMessage msg) {
                            if (owner != null && owner.openUiListener != null) {
                                owner.openUiListener.onOpenUiStateChanged(msg);
                            }
                        }

                        @Override
                        public void onSendToAssistant(ChatMessage msg, String promptText,
                                                      JsonElement context, JsonObject formState) {
                            if (owner != null && owner.openUiListener != null) {
                                owner.openUiListener.onOpenUiSendToAssistant(
                                        msg, promptText, context, formState);
                            }
                        }

                        @Override
                        public void onOpenUrl(String url) {
                            if (owner != null && owner.openUiListener != null) {
                                owner.openUiListener.onOpenUiOpenUrl(url);
                            }
                        }

                        @Override
                        public void onProseClick(View anchor, ChatMessage msg) {
                            if (owner != null) {
                                owner.fireClick(anchor,
                                        AssistantHolder.this.getAbsoluteAdapterPosition());
                            }
                        }
                    });
        }

        /** The header label, with its open/closed chevron. */
        private void updateThinkingHeader(
                AgentChatItemAssistantBinding binding, boolean expanded) {
            binding.thinkingHeader.setText(binding.getRoot().getContext()
                    .getString(R.string.agent_thinking_label)
                    + (expanded ? " ▴" : " ▾"));
        }

        /** Text-only update while streaming; everything else stays put. */
        void updateText(ChatMessage message) {
            renderBody(message);
            if (!message.text.isEmpty()) {
                binding.messageCard.setVisibility(View.VISIBLE);
            }
        }

        private final List<ObjectAnimator> dotAnimations = new ArrayList<>();

        /** Starts or stops the three-dot typing pulse. */
        void setTyping(boolean typing) {
            stopDots();
            binding.typingIndicator.setVisibility(typing ? View.VISIBLE : View.GONE);
            if (!typing) {
                return;
            }
            View[] dots = {binding.dot1, binding.dot2, binding.dot3};
            for (int i = 0; i < dots.length; i++) {
                ObjectAnimator animator = ObjectAnimator.ofFloat(
                        dots[i], View.ALPHA, 0.25f, 1f, 0.25f);
                animator.setDuration(900L);
                animator.setStartDelay(i * 160L);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.start();
                dotAnimations.add(animator);
            }
        }

        void stopDots() {
            for (ObjectAnimator animator : dotAnimations) {
                animator.cancel();
            }
            dotAnimations.clear();
            binding.dot1.setAlpha(1f);
            binding.dot2.setAlpha(1f);
            binding.dot3.setAlpha(1f);
        }
    }

    static class ApprovalHolder extends Holder {
        private final AgentChatItemApprovalBinding binding;
        private final List<ObjectAnimator> dotAnimations = new ArrayList<>();

        ApprovalHolder(@NonNull AgentChatItemApprovalBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /** The same three-dot pulse as the chat's typing indicator. */
        void startDots() {
            stopDots();
            View[] dots = {binding.approvalDot1, binding.approvalDot2,
                    binding.approvalDot3};
            for (int i = 0; i < dots.length; i++) {
                ObjectAnimator animator = ObjectAnimator.ofFloat(
                        dots[i], View.ALPHA, 0.25f, 1f, 0.25f);
                animator.setDuration(900L);
                animator.setStartDelay(i * 160L);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.start();
                dotAnimations.add(animator);
            }
        }

        void stopDots() {
            for (ObjectAnimator animator : dotAnimations) {
                animator.cancel();
            }
            dotAnimations.clear();
            binding.approvalDot1.setAlpha(1f);
            binding.approvalDot2.setAlpha(1f);
            binding.approvalDot3.setAlpha(1f);
        }

        @Override
        void bind(ChatMessage message) {
            android.content.Context context = itemView.getContext();
            binding.approvalTitle.setText(message.approvalTitle);
            binding.approvalBody.setText(message.approvalBody);
            binding.approvalDetails.setText(message.approvalDetails);
            binding.approvalDetails.setVisibility(
                    message.approvalDetailsExpanded ? View.VISIBLE : View.GONE);
            binding.approvalDetailsToggle.setText(
                    context.getString(R.string.agent_approval_details)
                            + (message.approvalDetailsExpanded ? " ▴" : " ▾"));
            binding.approvalDetailsToggle.setOnClickListener(v -> {
                if (message.approvalCountdownLeft > 0) {
                    // Opening Details pauses the timer: reading comes first.
                    message.approvalCountdownLeft = -1;
                }
                message.approvalDetailsExpanded = !message.approvalDetailsExpanded;
                bind(message);
            });
            boolean pending = ChatMessage.APPROVAL_PENDING.equals(message.approvalState);
            boolean working = ChatMessage.APPROVAL_WORKING.equals(message.approvalState);
            binding.approvalButtons.setVisibility(pending ? View.VISIBLE : View.GONE);
            binding.approvalStateRow.setVisibility(pending ? View.GONE : View.VISIBLE);
            // While the approved change is being applied, the card dims
            // (the readable form of a blur) and the three dots pulse —
            // the same signal as the chat's typing indicator — until the
            // final outcome replaces them.
            float contentAlpha = working ? 0.45f : 1f;
            binding.approvalTitle.setAlpha(contentAlpha);
            binding.approvalBody.setAlpha(contentAlpha);
            binding.approvalDetailsToggle.setAlpha(contentAlpha);
            binding.approvalDetails.setAlpha(contentAlpha);
            int dotsVisibility = working ? View.VISIBLE : View.GONE;
            binding.approvalDot1.setVisibility(dotsVisibility);
            binding.approvalDot2.setVisibility(dotsVisibility);
            binding.approvalDot3.setVisibility(dotsVisibility);
            binding.approvalState.setText(working
                    ? context.getString(R.string.agent_approval_working)
                    : stateText(context, message));
            if (working) {
                startDots();
            } else {
                stopDots();
            }
            if (pending && owner != null) {
                binding.approvalApprove.setOnClickListener(v -> {
                    if (owner.approvalListener != null) {
                        owner.approvalListener.onApprovalDecision(message, true);
                    }
                });
                binding.approvalReject.setOnClickListener(v -> {
                    if (owner.approvalListener != null) {
                        owner.approvalListener.onApprovalDecision(message, false);
                    }
                });
                startCountdown(message);
            } else {
                cancelCountdown();
            }
            bindInstall(message);
        }

        /**
         * The timed auto-approve: a live "Approve (5s)… → (1s)…" count
         * on the green button that approves itself at zero — unless the
         * user touched the card (paused) or the tool is one that must
         * never self-approve (destructive deletes).
         */
        private void startCountdown(ChatMessage message) {
            cancelCountdown();
            if (owner == null || owner.autoApproveSeconds <= 0
                    || message.approvalManualHold) {
                return;
            }
            if (message.approvalCountdownLeft == 0) {
                message.approvalCountdownLeft = owner.autoApproveSeconds;
            }
            final Runnable[] tick = new Runnable[1];
            // Any touch on the card pauses the countdown: a user who
            // is reading always outranks a timer.
            itemView.setOnTouchListener((v, event) -> {
                if (message.approvalCountdownLeft > 0) {
                    message.approvalCountdownLeft = -1;
                    binding.approvalApprove.setText(
                            binding.approvalApprove.getContext()
                                    .getString(R.string.agent_approval_approve));
                }
                return false;
            });
            tick[0] = new Runnable() {
                @Override
                public void run() {
                    if (!ChatMessage.APPROVAL_PENDING.equals(
                            message.approvalState)) {
                        return;
                    }
                    int left = message.approvalCountdownLeft;
                    if (left < 0) {
                        // Paused via Details/touch — the button keeps
                        // its plain label until the user decides.
                        return;
                    }
                    android.content.Context ctx =
                            binding.approvalApprove.getContext();
                    if (left <= 0) {
                        binding.approvalApprove.setText(
                                ctx.getString(R.string.agent_approval_approve));
                        if (owner != null && owner.approvalListener != null) {
                            owner.approvalListener.onApprovalDecision(
                                    message, true);
                        }
                        return;
                    }
                    binding.approvalApprove.setText(
                            ctx.getString(R.string.agent_approval_approve_in,
                                    left));
                    message.approvalCountdownLeft = left - 1;
                    itemView.postDelayed(this, 1000L);
                }
            };
            countdownTick = tick[0];
            itemView.postDelayed(tick[0], 600L);
        }

        /** Stops the countdown for whatever was bound here before. */
        private void cancelCountdown() {
            if (countdownTick != null) {
                itemView.removeCallbacks(countdownTick);
                countdownTick = null;
            }
            itemView.setOnTouchListener(null);
        }

        /** The pending card's live countdown tick, for removal on rebind. */
        private Runnable countdownTick;

        /**
         * The build card's Install row. The finished APK stays on disk,
         * but it may no longer be the APK this card built: the editor
         * rebuilds into the same file, and a new agent build wipes it.
         * So the row re-stats the file on every bind and only ever
         * offers what is provably there: this card's build, a clearly
         * labelled different one, or nothing.
         */
        private void bindInstall(ChatMessage message) {
            boolean buildCard = message.buildApkPath != null
                    && !message.buildApkPath.isEmpty()
                    && ChatMessage.APPROVAL_DONE.equals(message.approvalState);
            binding.approvalInstallRow.setVisibility(
                    buildCard ? View.VISIBLE : View.GONE);
            if (!buildCard) {
                binding.approvalInstall.setOnClickListener(null);
                return;
            }
            android.content.Context context = itemView.getContext();
            if (message.buildSuperseded) {
                // A newer build of this project owns the APK path now;
                // only its card offers Install.
                binding.approvalInstall.setVisibility(View.GONE);
                binding.approvalInstall.setOnClickListener(null);
                binding.approvalInstallNote.setText(
                        R.string.agent_install_superseded);
                return;
            }
            binding.approvalInstall.setVisibility(View.VISIBLE);
            File apk = new File(message.buildApkPath);
            if (!apk.exists() || apk.length() == 0) {
                binding.approvalInstall.setText(R.string.agent_install);
                binding.approvalInstall.setEnabled(false);
                binding.approvalInstall.setOnClickListener(null);
                binding.approvalInstallNote.setText(
                        R.string.agent_install_gone);
                return;
            }
            boolean sameApk = apk.length() == message.buildApkBytes
                    && apk.lastModified() == message.buildApkModified;
            binding.approvalInstall.setEnabled(true);
            binding.approvalInstall.setText(sameApk
                    ? R.string.agent_install : R.string.agent_install_latest);
            binding.approvalInstallNote.setText(context.getString(
                    sameApk ? R.string.agent_install_built
                            : R.string.agent_install_replaced,
                    android.text.format.DateFormat.format("HH:mm",
                            new java.util.Date(apk.lastModified())).toString(),
                    humanSize(apk.length())));
            binding.approvalInstall.setOnClickListener(v -> {
                if (owner != null && owner.installListener != null) {
                    owner.installListener.onInstall(message);
                }
            });
        }

        /** "100 KB" / "1.2 MB", the way the card already quotes sizes. */
        private String humanSize(long bytes) {
            long kb = Math.max(1, bytes / 1024);
            return kb < 1024 ? kb + " KB"
                    : String.format(Locale.ROOT, "%.1f MB", kb / 1024.0);
        }

        /** The quiet status line once the decision has been made. */
        private String stateText(android.content.Context context,
                                 ChatMessage message) {
            switch (message.approvalState) {
                case ChatMessage.APPROVAL_WORKING:
                    return context.getString(R.string.agent_approval_working);
                case ChatMessage.APPROVAL_DONE:
                    return "✓ " + context.getString(R.string.agent_approval_done)
                            + (message.approvalOutcome.isEmpty() ? ""
                                    : " — " + message.approvalOutcome);
                case ChatMessage.APPROVAL_REJECTED:
                    return "✕ " + context.getString(R.string.agent_approval_rejected);
                case ChatMessage.APPROVAL_FAILED:
                    return context.getString(R.string.agent_approval_failed)
                            + (message.approvalOutcome.isEmpty() ? ""
                                    : " — " + message.approvalOutcome);
                default:
                    return "";
            }
        }
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        if (holder instanceof AssistantHolder assistant) {
            assistant.stopDots();
        }
        if (holder instanceof ApprovalHolder approval) {
            approval.stopDots();
        }
        super.onViewRecycled(holder);
    }

    /**
     * An app card on the chat surface — the retired popups, reborn as
     * part of the conversation. Rendered programmatically (no XML) so
     * each kind composes its own content; buttons route through
     * {@link OnCardListener} and the card marks itself used, so a
     * restored history shows the decisions that were made.
     */
    static class CardHolder extends Holder {
        final LinearLayout root;

        CardHolder(LinearLayout root) {
            super(root);
            this.root = root;
        }

        void bind(ChatMessage m, ChatAdapter adapter) {
            Context ctx = root.getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            root.removeAllViews();
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(14f * d);
            root.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Math.round(6f * d), Math.round(48f * d),
                    Math.round(6f * d));
            root.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(16f * d);
            bg.setColor(AgentTheme.surfaceLow(ctx));
            bg.setStroke(1, AgentTheme.border(ctx));
            root.setBackground(bg);

            TextView title = new TextView(ctx);
            title.setTextColor(AgentTheme.ink(ctx));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextSize(15);
            root.addView(title);
            TextView body = new TextView(ctx);
            body.setTextColor(AgentTheme.faint(ctx));
            body.setTextSize(13);
            body.setPadding(0, Math.round(4f * d), 0, 0);
            root.addView(body);

            String kind = m.cardKind == null ? "" : m.cardKind;
            boolean pending = ChatMessage.CARD_PENDING.equals(m.cardState);
            if ("build_choice".equals(kind)) {
                title.setText(m.approvalTitle == null || m.approvalTitle
                        .isEmpty() ? "Build it — how?" : m.approvalTitle);
                body.setText(m.text == null ? "" : m.text);
                String choice = m.cardData == null || !m.cardData
                        .has("choice") ? "" :
                        m.cardData.get("choice").getAsString();
                if (pending) {
                    root.addView(button(ctx, d,
                            "Blueprint first (recommended)", () -> {
                                mark(m, ChatMessage.CARD_USED, "blueprint");
                                if (adapter.cardListener != null) {
                                    adapter.cardListener.onBuildChoice(m,
                                            "blueprint");
                                }
                            }));
                    root.addView(button(ctx, d, "Steady parts", () -> {
                        mark(m, ChatMessage.CARD_USED, "steady");
                        if (adapter.cardListener != null) {
                            adapter.cardListener.onBuildChoice(m, "steady");
                        }
                    }));
                    root.addView(button(ctx, d, "Fast parts", () -> {
                        mark(m, ChatMessage.CARD_USED, "fast");
                        if (adapter.cardListener != null) {
                            adapter.cardListener.onBuildChoice(m, "fast");
                        }
                    }));
                    root.addView(button(ctx, d, "Not now", () -> {
                        mark(m, ChatMessage.CARD_CANCELLED, "");
                        if (adapter.cardListener != null) {
                            adapter.cardListener.onBuildChoice(m, "cancel");
                        }
                    }));
                } else {
                    TextView done = new TextView(ctx);
                    done.setTextSize(13);
                    done.setPadding(0, Math.round(8f * d), 0, 0);
                    if (ChatMessage.CARD_CANCELLED.equals(m.cardState)) {
                        done.setText("Cancelled.");
                        done.setTextColor(AgentTheme.faint(ctx));
                    } else {
                        done.setText("Choice: "
                                + ("blueprint".equals(choice) ? "Blueprint"
                                : "steady".equals(choice) ? "Steady parts"
                                : "fast".equals(choice) ? "Fast parts"
                                : choice));
                        done.setTextColor(AgentTheme.primary(ctx));
                        done.setTypeface(Typeface.DEFAULT_BOLD);
                    }
                    root.addView(done);
                }
                return;
            }
            if ("blueprint".equals(kind)) {
                title.setText(m.cardData != null
                        && m.cardData.has("recovered")
                        ? "Your saved blueprint — build this?"
                        : "The blueprint — build this?");
                String scId = m.cardData != null && m.cardData.has("scId")
                        ? m.cardData.get("scId").getAsString() : "";
                String text = m.cardData != null && m.cardData
                        .has("blueprint_text")
                        ? m.cardData.get("blueprint_text").getAsString()
                        : "";
                java.util.List<AgentBlueprintPreview.ScreenDraft>
                        drafts = AgentBlueprintPreview.parse(text);
                if (!drafts.isEmpty()) {
                    android.widget.HorizontalScrollView gallery =
                            new android.widget.HorizontalScrollView(ctx);
                    gallery.setFillViewport(true);
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(0, Math.round(10f * d), 0,
                            Math.round(4f * d));
                    com.google.gson.JsonObject palette = scId.isEmpty()
                            ? null : AgentJournal.designSystem(scId);
                    for (AgentBlueprintPreview.ScreenDraft draft
                            : drafts) {
                        row.addView(AgentBlueprintPreview.renderScreen(
                                ctx, draft, palette));
                    }
                    gallery.addView(row);
                    root.addView(gallery);
                }
                TextView rules = new TextView(ctx);
                rules.setText(AgentBlueprintPreview.withoutScreens(text));
                rules.setTextColor(AgentTheme.faint(ctx));
                rules.setTextSize(12);
                rules.setMaxLines(8);
                rules.setEllipsize(android.text.TextUtils.TruncateAt.END);
                rules.setPadding(0, Math.round(8f * d), 0, 0);
                root.addView(rules);
                if (pending) {
                    final EditText adjustInput = new EditText(ctx);
                    adjustInput.setHint("Anything to change? (optional)");
                    adjustInput.setTextSize(13);
                    adjustInput.setVisibility(View.GONE);
                    root.addView(adjustInput);
                    final TextView[] input = {adjustInput};
                    root.addView(button(ctx, d, "Build it", () -> {
                        mark(m, ChatMessage.CARD_USED, "build");
                        if (adapter.cardListener != null) {
                            adapter.cardListener.onBlueprintDecision(m, "");
                        }
                    }));
                    root.addView(button(ctx, d, "Adjust it (redraw)",
                            () -> {
                                if (input[0].getVisibility()
                                        == View.GONE) {
                                    input[0].setVisibility(View.VISIBLE);
                                    return;
                                }
                                String wish = input[0].getText() == null
                                        ? "" : input[0].getText()
                                        .toString().trim();
                                mark(m, ChatMessage.CARD_USED,
                                        wish.isEmpty() ? "adjusted"
                                                : "adjusted");
                                if (adapter.cardListener != null) {
                                    adapter.cardListener
                                            .onBlueprintDecision(m, wish);
                                }
                            }));
                    root.addView(button(ctx, d, "Cancel — build nothing",
                            () -> {
                                mark(m, ChatMessage.CARD_CANCELLED, "");
                                if (adapter.cardListener != null) {
                                    adapter.cardListener
                                            .onBlueprintDecision(m, null);
                                }
                            }));
                } else if (ChatMessage.CARD_CANCELLED.equals(
                        m.cardState)) {
                    TextView done = new TextView(ctx);
                    done.setText("Cancelled — nothing was built.");
                    done.setTextColor(AgentTheme.faint(ctx));
                    done.setTextSize(13);
                    done.setPadding(0, Math.round(8f * d), 0, 0);
                    root.addView(done);
                } else {
                    TextView done = new TextView(ctx);
                    String choice = m.cardData != null
                            && m.cardData.has("choice")
                            ? m.cardData.get("choice").getAsString() : "";
                    done.setText("adjusted".equals(choice)
                            ? "Sent back for a redraw — see the next "
                            + "card." : "Approved — building.");
                    done.setTextColor(AgentTheme.primary(ctx));
                    done.setTypeface(Typeface.DEFAULT_BOLD);
                    done.setTextSize(13);
                    done.setPadding(0, Math.round(8f * d), 0, 0);
                    root.addView(done);
                }
                return;
            }
            if ("board".equals(kind)) {
                boolean live = pending;
                int done = m.cardData != null && m.cardData.has("done")
                        ? m.cardData.get("done").getAsInt() : 0;
                int total = m.cardData != null && m.cardData.has("total")
                        ? m.cardData.get("total").getAsInt() : 0;
                title.setText(live ? "🛠 Parts board — " + done + " of "
                        + total + " ready" : "Parts board — finished");
                body.setText(live ? "Live. Stop anytime; tap a ready "
                        + "part to review it." : "Tap a part to review "
                        + "or rebuild it.");
                java.util.List<com.google.gson.JsonObject> rows =
                        new java.util.ArrayList<>();
                if (m.cardData != null && m.cardData.has("parts")
                        && m.cardData.get("parts").isJsonArray()) {
                    for (com.google.gson.JsonElement el : m.cardData
                            .getAsJsonArray("parts")) {
                        if (el.isJsonObject()) {
                            rows.add(el.getAsJsonObject());
                        }
                    }
                }
                final int[] expanded = {m.cardData != null
                        && m.cardData.has("open")
                        ? m.cardData.get("open").getAsInt() : -1};
                if (live) {
                    root.addView(button(ctx, d, "⏹ Stop all workers",
                            () -> {
                                if (adapter.cardListener != null) {
                                    adapter.cardListener.onBoardAction(m,
                                            "stop", -1);
                                }
                            }));
                }
                for (final com.google.gson.JsonObject part : rows) {
                    final int idx = part.has("index")
                            ? part.get("index").getAsInt() : 0;
                    TextView rowView = new TextView(ctx);
                    rowView.setTextSize(13);
                    rowView.setPadding(Math.round(10f * d),
                            Math.round(8f * d), Math.round(10f * d),
                            Math.round(8f * d));
                    LinearLayout.LayoutParams rlp =
                            new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    rlp.topMargin = Math.round(6f * d);
                    rowView.setLayoutParams(rlp);
                    GradientDrawable rowBg = new GradientDrawable();
                    rowBg.setCornerRadius(10f * d);
                    rowBg.setColor(withAlphaCompat(AgentTheme.ink(ctx),
                            0x0D));
                    rowView.setBackground(rowBg);
                    String icon = "🕒";
                    String st = part.has("state")
                            ? part.get("state").getAsString() : "";
                    if ("DONE".equals(st)) {
                        icon = "✅";
                    } else if ("FAILED".equals(st)) {
                        icon = "⚠";
                    } else if ("BUILDING".equals(st)) {
                        icon = "🔧";
                    }
                    String titleText = part.has("title")
                            ? part.get("title").getAsString()
                            : "Part " + idx;
                    String label = part.has("label")
                            ? part.get("label").getAsString() : "";
                    rowView.setText(icon + "  " + idx + ". "
                            + titleText
                            + (label.isEmpty() ? "" : " — " + label));
                    final boolean open = expanded[0] == idx;
                    root.addView(rowView);
                    rowView.setOnClickListener(v -> {
                        expanded[0] = open ? -1 : idx;
                        if (m.cardData == null) {
                            m.cardData = new JsonObject();
                        }
                        m.cardData.addProperty("open", expanded[0]);
                        adapter.notifyDataSetChanged();
                    });
                    if (!open) {
                        continue;
                    }
                    LinearLayout panel = new LinearLayout(ctx);
                    panel.setOrientation(LinearLayout.VERTICAL);
                    panel.setPadding(Math.round(12f * d),
                            Math.round(6f * d), Math.round(12f * d), 0);
                    TextView detail = new TextView(ctx);
                    String detailText = part.has("summary")
                            && !part.get("summary").getAsString()
                            .isEmpty() ? part.get("summary")
                            .getAsString() : part.has("error")
                            && !part.get("error").getAsString().isEmpty()
                            ? part.get("error").getAsString()
                            : "Still waiting…";
                    detail.setText(detailText);
                    detail.setTextColor(AgentTheme.faint(ctx));
                    detail.setTextSize(12);
                    panel.addView(detail);
                    if ("DONE".equals(st)) {
                        panel.addView(button(ctx, d, "Undo this part",
                                () -> adapter.cardListener.onBoardAction(
                                        m, "undo", idx)));
                        panel.addView(button(ctx, d, "Rebuild this part",
                                () -> adapter.cardListener.onBoardAction(
                                        m, "rebuild", idx)));
                        panel.addView(button(ctx, d, "Keep it", () -> {
                            expanded[0] = -1;
                            m.cardData.addProperty("open", -1);
                            adapter.notifyDataSetChanged();
                        }));
                    } else if ("FAILED".equals(st)) {
                        panel.addView(button(ctx, d, "Rebuild this part",
                                () -> adapter.cardListener.onBoardAction(
                                        m, "rebuild", idx)));
                    }
                    root.addView(panel);
                }
                return;
            }
            // Unknown/placeholder kinds render their text at least.
            title.setText(m.approvalTitle == null ? "Card"
                    : m.approvalTitle);
            body.setText(m.text == null ? "" : m.text);
        }

        private static void mark(ChatMessage m, String state,
                                 String choice) {
            m.cardState = state;
            if (m.cardData == null) {
                m.cardData = new JsonObject();
            }
            if (!choice.isEmpty()) {
                m.cardData.addProperty("choice", choice);
            }
        }

        private com.google.android.material.button.MaterialButton button(
                Context ctx, float d, String label, Runnable action) {
            com.google.android.material.button.MaterialButton b =
                    new com.google.android.material.button.MaterialButton(
                            ctx);
            b.setText(label);
            b.setTextSize(13);
            b.setAllCaps(false);
            b.setInsetTop(Math.round(6f * d));
            b.setInsetBottom(Math.round(6f * d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(8f * d);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> action.run());
            return b;
        }
    }
}
