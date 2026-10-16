package pro.sketchware.activities.main.fragments.agent;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import a.a.a.qA;
import pro.sketchware.R;
import pro.sketchware.ai.AiModelConfig;
import pro.sketchware.ai.AiModelManager;
import pro.sketchware.ai.AiPaths;
import pro.sketchware.ai.ChatAdapter;
import pro.sketchware.ai.ChatMessage;
import pro.sketchware.databinding.AgentChatBinding;

/**
 * Agent Mode: a chat with a model that runs on the device.
 *
 * <p>The fragment owns the chat UI only. The model lifecycle lives in
 * {@link AiModelManager}, which is shared so that the engine is loaded once
 * and torn down when the app needs the memory for a build.
 */
public class AgentFragment extends qA implements AiModelManager.Listener {

    private final List<ChatMessage> messages = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AgentChatBinding binding;
    private ChatAdapter adapter;
    private AiModelManager modelManager;
    private AiModelConfig config;
    private ChatMessage streamingMessage;

    private final ActivityResultLauncher<String[]> pickModel =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onModelPicked);

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
        handleInsetts(binding.getRoot());

        adapter = new ChatAdapter();
        binding.chatList.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.chatList.setAdapter(adapter);

        binding.sendButton.setOnClickListener(v -> {
            if (modelManager != null && modelManager.isGenerating()) {
                modelManager.stop();
                return;
            }
            sendCurrentInput();
        });

        binding.chatInput.addTextChangedListener(new SimpleTextWatcher(text -> {
            boolean hasText = !text.trim().isEmpty();
            binding.sendButton.setEnabled(hasText || isGenerating());
            binding.sendButton.setIconResource(
                    isGenerating() ? R.drawable.ic_mtrl_close : R.drawable.ic_mtrl_email_sent);
        }));

        binding.chooseModelButton.setOnClickListener(v -> chooseModel());

        config = AiModelConfig.load();
        modelManager = AiModelManager.getInstance();
        modelManager.addListener(this);

        showCurrentState();
    }

    @Override
    public void onDestroyView() {
        if (modelManager != null) {
            modelManager.removeListener(this);
        }
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        showCurrentState();
    }

    // region chat

    private void sendCurrentInput() {
        String text = binding.chatInput.getText() == null ? "" : binding.chatInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        binding.chatInput.setText("");

        AiModelConfig.ModelEntry model = config.activeModel();
        if (model == null || model.file() == null || !model.file().exists()) {
            appendMessage(ChatMessage.error(getString(R.string.agent_model_missing)));
            return;
        }

        appendMessage(ChatMessage.user(text));
        ChatMessage reply = ChatMessage.assistant("");
        streamingMessage = reply;
        appendMessage(reply);
        modelManager.send(text);
    }

    private void appendMessage(ChatMessage message) {
        messages.add(message);
        adapter.submitList(new ArrayList<>(messages));
        if (binding != null) {
            binding.chatList.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    /** Replaces the transcript with the current model state. */
    private void showCurrentState() {
        if (binding == null) {
            return;
        }
        messages.clear();
        AiModelConfig.ModelEntry model = config == null ? null : config.activeModel();
        boolean hasModel = model != null && model.file() != null && model.file().exists();
        if (!hasModel) {
            messages.add(new ChatMessage(ChatMessage.Role.SYSTEM, getString(R.string.agent_empty_title)));
            messages.add(new ChatMessage(ChatMessage.Role.SYSTEM, getString(R.string.agent_empty_body)));
        }
        binding.chooseModelButton.setVisibility(hasModel ? View.GONE : View.VISIBLE);
        adapter.submitList(new ArrayList<>(messages));
        updateActionButton();

        if (hasModel && modelManager.state() == AiModelManager.State.IDLE) {
            // The engine is loaded lazily: a 500 MB model must not be resident
            // while the user is just looking at the project list.
            modelManager.loadModel(model.file(), config.activeBackend(), engineCacheDir());
        }
    }

    private File engineCacheDir() {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        File cache = new File(context.getCacheDir(), "litertlm");
        if (!cache.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cache.mkdirs();
        }
        return cache;
    }

    private boolean isGenerating() {
        return modelManager != null && modelManager.isGenerating();
    }

    private void updateActionButton() {
        if (binding == null) {
            return;
        }
        boolean generating = isGenerating();
        binding.sendButton.setIconResource(
                generating ? R.drawable.ic_mtrl_close : R.drawable.ic_mtrl_email_sent);
        binding.sendButton.setContentDescription(
                getString(generating ? R.string.agent_action_stop : R.string.agent_action_send));
    }

    // endregion

    // region model import

    /** Opens the system picker so the user can point at a {@code .litertlm} file. */
    public void chooseModel() {
        try {
            pickModel.launch(new String[]{"*/*"});
        } catch (Exception e) {
            // Some file providers do not support MIME filtering; fall back to all.
            pickModel.launch(new String[]{"application/octet-stream", "application/zip"});
        }
    }

    private void onModelPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        // The resolver has to be resolved while the fragment is still attached.
        Context context = getContext();
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        io.execute(() -> {
            try {
                AiModelConfig.ModelEntry entry = importModel(appContext, uri);
                config.putModel(entry);
                config.setActiveModel(entry.id);
                config.save();
                if (binding != null) {
                    binding.chatList.post(() -> showCurrentState());
                }
            } catch (IOException e) {
                if (binding != null) {
                    binding.chatList.post(() ->
                            appendMessage(ChatMessage.error(getString(R.string.agent_model_invalid))));
                }
            }
        });
    }

    /**
     * Copies the picked file into the agent model directory so LiteRT-LM can be
     * pointed at a plain filesystem path (it cannot read a {@code content://}
     * URI).
     */
    private AiModelConfig.ModelEntry importModel(Context context, Uri uri) throws IOException {
        String name = queryDisplayName(context, uri);
        String id = AiPaths.sanitizeId(stripExtension(name));
        File target = AiPaths.modelFile(id);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("Cannot open " + uri);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        AiModelConfig.ModelEntry entry = new AiModelConfig.ModelEntry(id, target.getAbsolutePath());
        entry.sizeBytes = target.length();
        entry.importedAt = System.currentTimeMillis();
        entry.backend = config.activeBackend().name();
        return entry;
    }

    private String queryDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to the last path segment.
        }
        String name = uri.getLastPathSegment();
        return name == null ? "model" : name;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // endregion

    // region AiModelManager.Listener

    @Override
    public void onEngineStateChanged(AiModelManager.State state) {
        if (binding == null) {
            return;
        }
        updateActionButton();
        if (state == AiModelManager.State.READY) {
            setStreamingComplete();
        }
    }

    @Override
    public void onToken(String text) {
        if (binding == null || streamingMessage == null) {
            return;
        }
        streamingMessage.text += text;
        streamingMessage.state = ChatMessage.State.STREAMING;
        int index = messages.indexOf(streamingMessage);
        if (index >= 0) {
            adapter.submitList(new ArrayList<>(messages));
        }
    }

    @Override
    public void onTurnComplete() {
        setStreamingComplete();
    }

    @Override
    public void onError(Throwable error) {
        if (binding == null) {
            return;
        }
        setStreamingComplete();
        appendMessage(ChatMessage.error(getString(R.string.agent_state_error)));
    }

    private void setStreamingComplete() {
        if (streamingMessage != null) {
            streamingMessage.state = ChatMessage.State.COMPLETE;
            if (streamingMessage.text.isEmpty()) {
                streamingMessage.text = "…";
            }
            streamingMessage = null;
            if (binding != null) {
                adapter.submitList(new ArrayList<>(messages));
            }
        }
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
