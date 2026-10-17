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
        if (message.isFromApp()) {
            return TYPE_SYSTEM;
        }
        return TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            return new UserHolder(AgentChatItemUserBinding.inflate(inflater, parent, false));
        }
        if (viewType == TYPE_SYSTEM) {
            return new SystemHolder(AgentChatItemSystemBinding.inflate(inflater, parent, false));
        }
        return new AssistantHolder(AgentChatItemAssistantBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.owner = this;
        holder.bind(items.get(position));
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
            boolean streaming = message.state == ChatMessage.State.STREAMING
                    || message.state == ChatMessage.State.PENDING;
            if (streaming && message.text.isEmpty() && message.statusText != null) {
                // Nothing visible from the model yet: say what it is doing
                // instead of showing an empty bubble.
                binding.messageText.setText(message.statusText);
            } else {
                binding.messageText.setText(message.text);
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

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        if (holder instanceof AssistantHolder assistant) {
            assistant.stopDots();
        }
        super.onViewRecycled(holder);
    }
}
