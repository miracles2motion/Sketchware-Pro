package pro.sketchware.activities.main.fragments.agent;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
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
import pro.sketchware.ai.AgentKnowledgePack;
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

    private static final String TAG = "AgentFragment";

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
        /*
         * The chat screen lives in a container that the coordinator layout
         * pushes below the app bar. The container is not always shortened by
         * the height of the app bar, so the screen can reach below the
         * display and the input bar would be out of sight. The overflow is
         * measured and taken off as padding, so the input bar always sits
         * just above the navigation bar.
         */
        layoutListener = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                fitToVisibleArea(binding == null ? null : binding.getRoot());
            }
        };
        view.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);

        adapter = new ChatAdapter(messages);
        androidx.recyclerview.widget.LinearLayoutManager layoutManager =
                new androidx.recyclerview.widget.LinearLayoutManager(getContext());
        // A short conversation sits above the input bar, not at the top of an
        // empty screen.
        layoutManager.setStackFromEnd(true);
        binding.chatList.setLayoutManager(layoutManager);
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
        if (modelManager != null) {
            modelManager.removeListener(this);
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
        // The transcript survives; only the model state is refreshed.
        updateActionButton();
    }

    // region chat

    private void sendCurrentInput() {
        String text = binding.chatInput.getText() == null ? "" : binding.chatInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        binding.chatInput.setText("");

        AiModelConfig.ModelEntry model = config == null ? null : config.activeModel();
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
        getActivity().getWindow().getVisibleDisplayFrame(visible);
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
    }

    /**
     * Scrolls to the newest message, but only when the user is already
     * looking at the end of the conversation.
     */
    private void scrollToNewest() {
        if (binding == null) {
            return;
        }
        androidx.recyclerview.widget.LinearLayoutManager manager =
                (androidx.recyclerview.widget.LinearLayoutManager) binding.chatList.getLayoutManager();
        if (manager == null) {
            return;
        }
        int last = adapter.getItemCount() - 1;
        if (last < 0) {
            return;
        }
        int first = manager.findFirstVisibleItemPosition();
        if (first == androidx.recyclerview.widget.RecyclerView.NO_POSITION
                || last - first < 3) {
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

    /** Replaces the transcript with the current model state. */
    private void showCurrentState() {
        if (binding == null) {
            return;
        }
        messages.clear();
        streamingMessage = null;
        AiModelConfig.ModelEntry model = config == null ? null : config.activeModel();
        boolean hasModel = model != null && model.file() != null && model.file().exists();
        if (!hasModel) {
            messages.add(new ChatMessage(ChatMessage.Role.SYSTEM, getString(R.string.agent_empty_title)));
            messages.add(new ChatMessage(ChatMessage.Role.SYSTEM, getString(R.string.agent_empty_body)));
        }
        binding.chooseModelButton.setVisibility(hasModel ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
        updateActionButton();

        if (hasModel && modelManager.state() == AiModelManager.State.IDLE) {
            // The engine is loaded lazily: a 500 MB model must not be resident
            // while the user is just looking at the project list.
            modelManager.loadModel(model.file(), config.activeBackend(), engineCacheDir(),
                    nativeLibraryDir());
        }
    }

    /** Refreshes the model state without touching the transcript. */
    private void refreshModelState() {
        if (binding == null || config == null) {
            return;
        }
        AiModelConfig.ModelEntry model = config.activeModel();
        boolean hasModel = model != null && model.file() != null && model.file().exists();
        binding.chooseModelButton.setVisibility(hasModel ? View.GONE : View.VISIBLE);
        if (hasModel && modelManager.state() == AiModelManager.State.IDLE) {
            modelManager.loadModel(model.file(), config.activeBackend(), engineCacheDir(),
                    nativeLibraryDir());
        }
    }

    /**
     * The directory that holds the NPU libraries. LiteRT-LM needs it for the
     * NPU backend, see {@code Backend.NPU}.
     */
    private String nativeLibraryDir() {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        return context.getApplicationInfo().nativeLibraryDir;
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
                AiModelConfig registry = config == null ? AiModelConfig.load() : config;
                registry.putModel(entry);
                registry.setActiveModel(entry.id);
                registry.save();
                if (binding != null) {
                    binding.chatList.post(() -> {
                        refreshModelState();
                        appendMessage(new ChatMessage(ChatMessage.Role.SYSTEM,
                                getString(R.string.agent_model_imported)));
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Importing the model failed", e);
                boolean noStorage = !isStorageWritable(appContext);
                if (binding != null) {
                    // The string is read on the main thread, the fragment may
                    // be detached by the time the copy failed.
                    binding.chatList.post(() -> appendMessage(ChatMessage.error(getString(
                            noStorage ? R.string.agent_model_no_storage
                                    : R.string.agent_model_invalid))));
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
        // Read the metadata without loading the weights, so a file that is not
        // a usable model is rejected here and not after a long load.
        entry.description = AiModelManager.describeModel(target);
        return entry;
    }

    /**
     * Tells whether the app may write into the agent directory.
     *
     * <p>On Android 11 and later that needs the "all files access" permission,
     * which the user can refuse. Without it the model cannot be copied into
     * the app and the reason has to be said, not guessed.
     */
    private boolean isStorageWritable(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                && !android.os.Environment.isExternalStorageManager()) {
            return false;
        }
        File dir = AiPaths.agentRoot();
        return dir.exists() && dir.canWrite();
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
        // A model on a phone emits tokens faster than the list can be
        // diffed, so the tokens are collected and flushed to the list in
        // batches.
        synchronized (pendingToken) {
            pendingToken.append(text);
        }
        if (!flushScheduled) {
            flushScheduled = true;
            binding.chatList.postDelayed(flushTokens, TOKEN_FLUSH_MS);
        }
    }

    /** Moves the collected tokens into the streaming message. */
    private final Runnable flushTokens = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
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
    };

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
        if (streamingMessage == null) {
            return;
        }
        if (binding != null) {
            binding.chatList.removeCallbacks(flushTokens);
        }
        flushScheduled = false;
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
