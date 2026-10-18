package pro.sketchware.ai;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

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
        return new AssistantHolder(AgentChatItemAssistantBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.owner = this;
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
            binding.messageText.setText(message.text);
            if (owner != null) {
                owner.attachClicks(this, itemView, binding.messageText);
            }
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
            binding.thinkingHeader.setVisibility(
                    hasThinking ? View.VISIBLE : View.GONE);
            binding.thinkingText.setVisibility(hasThinking
                    && message.thinkingExpanded ? View.VISIBLE : View.GONE);
            if (hasThinking) {
                markwon(binding.messageText).setMarkdown(
                        binding.thinkingText, message.thinking);
                updateThinkingHeader(binding, message.thinkingExpanded);
                binding.thinkingHeader.setOnClickListener(v -> {
                    message.thinkingExpanded = !message.thinkingExpanded;
                    binding.thinkingText.setVisibility(
                            message.thinkingExpanded ? View.VISIBLE : View.GONE);
                    updateThinkingHeader(binding, message.thinkingExpanded);
                });
            }
            if (message.state == ChatMessage.State.STREAMING
                    || message.state == ChatMessage.State.PENDING) {
                // Still arriving: raw text now, formatting at completion.
                binding.messageText.setText(message.text);
            } else {
                markwon(binding.messageText).setMarkdown(
                        binding.messageText, message.text);
            }
            // No words yet: the dots pill speaks alone, no empty shell.
            binding.messageCard.setVisibility(
                    message.text.isEmpty() ? View.GONE : View.VISIBLE);
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
            binding.messageText.setText(message.text);
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
            }
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
}
