package pro.sketchware.ai;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

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

    public ChatAdapter(List<ChatMessage> items) {
        this.items = items;
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
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
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
            binding.messageText.setText(message.text);
            boolean streaming = message.state == ChatMessage.State.STREAMING
                    || message.state == ChatMessage.State.PENDING;
            binding.streamingIndicator.setVisibility(streaming ? View.VISIBLE : View.GONE);
        }
    }
}
