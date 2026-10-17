package pro.sketchware.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.ModelInfo;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Owns the on-device model: its engine, its conversation and its lifetime.
 *
 * <h3>Why this is written the way it is</h3>
 *
 * <p><b>One engine, one conversation, one thread.</b> LiteRT-LM keeps a single
 * native engine per process and every turn shares the same KV cache. More than
 * one generation at a time corrupts that state and fights for the same
 * thermal and memory budget, so all engine work runs on a single background
 * thread and only one turn may be in flight.
 *
 * <p><b>Memory is the runtime's job; configuration is ours.</b> Each turn
 * sends only the user's new message and the reused conversation carries
 * the history, which is how Google's AI Edge Gallery uses this API. Two
 * configuration details of that app matter and were originally wrong
 * here: the engine is given the model's own context size
 * ({@code maxNumTokens}), and the conversation's {@code channels} are left
 * at the model default instead of being disabled. The transcript is still
 * recorded by the app, but only so a conversation whose context fills up
 * can be recreated and re-seeded with it - the Gallery
 * {@code resetConversation} pattern. (An earlier build pasted the whole
 * transcript into every message as plain text; on device the model began
 * answering the previous message and copying its own bad replies, so that
 * approach was removed.)
 *
 * <p><b>A watchdog closes turns that go quiet.</b> A turn is also
 * considered finished when no token has arrived for
 * {@link #IDLE_TIMEOUT_SECONDS}. Without it a lost terminal callback
 * would hang the chat on a spinner forever.
 *
 * <p><b>Nothing here touches the main thread.</b> Initialising a model takes
 * seconds and closing a stuck conversation can block indefinitely, so both
 * happen on the engine thread. ANRs are fatal on Android.
 */
public class AiModelManager {

    private static final String TAG = "AiModelManager";

    /** How long a turn may stay silent before it is treated as finished. */
    private static final int IDLE_TIMEOUT_SECONDS = 20;

    /** Native token count at which the conversation is recreated. */
    private static final int RESET_TOKEN_LIMIT = 3000;

    /** How many transcript messages are re-sent to the model each turn. */
    private static final int MAX_HISTORY_MESSAGES = 16;

    /** Character budget for the re-sent transcript (roughly 2k tokens). */
    private static final int MAX_HISTORY_CHARS = 8000;

    /**
     * What the model is told about itself.
     *
     * <p>Kept short on purpose: a 1B model has a small context and every
     * token here is a token that is not available for the conversation.
     */
    private static final String SYSTEM_INSTRUCTION =
            "You are the assistant of Sketchware Pro, an Android app builder for people who do "
                    + "not write code. You answer in the language of the user, you are brief, and "
                    + "you never invent APIs. You answer general questions directly, and you only "
                    + "talk about building an app when the user asks about one. When you are asked "
                    + "to change something in a project, you describe the change first and wait for "
                    + "the user to approve it.";

    /** State of the model. */
    public enum State {
        /** No model is loaded. */
        IDLE,
        /** The engine is being created. */
        LOADING,
        /** The engine is ready to accept turns. */
        READY,
        /** A turn is in flight. */
        GENERATING,
        /** The engine failed and was torn down. */
        ERROR
    }

    /** Callbacks for the chat UI. */
    public interface Listener {
        void onEngineStateChanged(State state);

        void onToken(String text);

        void onTurnComplete();

        void onError(Throwable error);
    }

    private static volatile AiModelManager instance;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /**
     * The listeners are UI objects, so every callback is delivered on the
     * main thread even though the engine works on its own thread.
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService engineThread = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(@NonNull Runnable r) {
            Thread thread = new Thread(r, "ai-engine");
            // Never compete with the compiler or the UI for CPU time.
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    });
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(@NonNull Runnable r) {
                    Thread thread = new Thread(r, "ai-watchdog");
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
            });

    private final Object engineLock = new Object();

    private Engine engine;
    private Conversation conversation;
    /**
     * The recent conversation, owned by the app and re-sent to the model on
     * every turn. Guarded by {@link #engineLock}.
     */
    private final List<ChatMessage> transcript = new ArrayList<>();
    /** Text of the reply currently streaming in. */
    private final StringBuilder currentReply = new StringBuilder();
    /** A message sent before the engine was ready. Guarded by engineLock. */
    private String pendingPrompt;
    /** History to seed the next conversation with. Guarded by engineLock. */
    private List<Message> pendingSeed = new ArrayList<>();
    private File modelFile;
    private AiModelConfig.Backend backend = AiModelConfig.Backend.CPU;
    private volatile State state = State.IDLE;
    private volatile boolean generating;
    private ScheduledFuture<?> idleWatchdog;

    private AiModelManager() {
    }

    public static AiModelManager getInstance() {
        if (instance == null) {
            synchronized (AiModelManager.class) {
                if (instance == null) {
                    instance = new AiModelManager();
                }
            }
        }
        return instance;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public State state() {
        return state;
    }

    public boolean isGenerating() {
        return generating;
    }

    // region engine lifecycle

    /**
     * Loads a model, replacing whatever was loaded before.
     *
     * @param file     the {@code .litertlm} file
     * @param backend  the backend to use
     * @param cacheDir writable directory for the runtime cache, may be null
     */
    public void loadModel(File file, AiModelConfig.Backend backend, @Nullable File cacheDir,
                          @Nullable String nativeLibraryDir) {
        engineThread.execute(() -> {
            try {
                unloadInternal();
                setState(State.LOADING);
                try {
                    startEngine(file, backend, cacheDir, nativeLibraryDir);
                } catch (Throwable t) {
                    if (backend == AiModelConfig.Backend.GPU) {
                        // The GPU backend is not available on every device and
                        // a failed initialisation can also leave the native
                        // runtime in a bad state. Fall back to the CPU once
                        // and remember the verdict, so the app does not keep
                        // retrying a backend that cannot work here.
                        Log.w(TAG, "GPU backend failed, falling back to the CPU", t);
                        rememberGpuFailure();
                        startEngine(file, AiModelConfig.Backend.CPU, cacheDir, nativeLibraryDir);
                    } else {
                        throw t;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load model", t);
                setState(State.ERROR);
                notifyError(t);
            }
        });
    }

    private void startEngine(File file, AiModelConfig.Backend backend, @Nullable File cacheDir,
                             @Nullable String nativeLibraryDir) {
        EngineConfig engineConfig = new EngineConfig(
                file.getAbsolutePath(),
                /* backend        */ toLiteRtBackend(backend, nativeLibraryDir),
                /* visionBackend  */ null,
                /* audioBackend   */ null,
                /* maxNumTokens   */ maxContextTokens(file),
                /* maxNumImages   */ null,
                /* cacheDir       */ cacheDir == null ? null : cacheDir.getAbsolutePath());

        Engine newEngine = new Engine(engineConfig);
        newEngine.initialize();

        synchronized (engineLock) {
            engine = newEngine;
            modelFile = file;
            this.backend = backend;
            conversation = null;
        }
        setState(State.READY);
        // A message sent while no model was loaded is delivered now.
        String pending;
        synchronized (engineLock) {
            pending = pendingPrompt;
            pendingPrompt = null;
        }
        if (pending != null && !pending.isEmpty()) {
            send(pending);
        }
    }

    /** Stores the verdict that the GPU backend does not work on this device. */
    private void rememberGpuFailure() {
        AiModelConfig config = AiModelConfig.load();
        if (!config.registry().gpuFailed) {
            config.registry().gpuFailed = true;
            config.registry().backend = AiModelConfig.Backend.CPU.name();
            config.save();
        }
    }

    /**
     * Creates (or reuses) the conversation.
     *
     * <p>Reuse is deliberate: it is the call shape whose terminal callback
     * is reliably delivered, see the class comment.
     */
    @Nullable
    private Conversation ensureConversation() {
        synchronized (engineLock) {
            if (engine == null) {
                return null;
            }
            if (conversation != null) {
                return conversation;
            }
            List<Message> seed = pendingSeed;
            pendingSeed = new ArrayList<>();
            SamplerConfig samplerConfig = new SamplerConfig(64, 0.95, 1.0f, 0);
            ConversationConfig config = new ConversationConfig(
                    /* systemInstruction    */ Contents.Companion.of(SYSTEM_INSTRUCTION),
                    /* initialMessages      */ seed,
                    /* tools                */ Collections.emptyList(),
                    /* samplerConfig        */ samplerConfig,
                    /* automaticToolCalling */ false,
                    /* channels             */ null);
            conversation = engine.createConversation(config);
            return conversation;
        }
    }

    /**
     * Drops the current conversation once the native context is nearly
     * full, so the next turn starts a fresh one. The text transcript is
     * unaffected, so the model keeps its memory across the reset.
     */
    private void resetConversationIfFull() {
        synchronized (engineLock) {
            if (conversation == null) {
                return;
            }
            boolean full;
            try {
                full = conversation.getTokenCount() > RESET_TOKEN_LIMIT;
            } catch (Throwable t) {
                // An unreadable token count is treated as a full context:
                // a fresh conversation is always safe.
                full = true;
            }
            if (!full) {
                return;
            }
            try {
                conversation.close();
            } catch (Throwable t) {
                Log.w(TAG, "Closing the full conversation failed", t);
            }
            conversation = null;
            // The replacement starts on an empty KV cache, so it is seeded
            // with the transcript, the way AI Edge Gallery restores a chat.
            pendingSeed = new ArrayList<>();
            for (ChatMessage message : transcript) {
                pendingSeed.add(message.isFromUser()
                        ? Message.Companion.user(message.text)
                        : Message.Companion.model(message.text));
            }
        }
    }

    /**
     * Trims the transcript to the newest exchanges that fit the budget.
     * Messages are dropped in user/model pairs so the roles stay balanced.
     * Caller must hold {@link #engineLock}.
     */
    private void trimTranscriptLocked() {
        int chars = 0;
        for (ChatMessage message : transcript) {
            chars += message.text.length();
        }
        while ((transcript.size() > MAX_HISTORY_MESSAGES || chars > MAX_HISTORY_CHARS)
                && transcript.size() >= 2) {
            chars -= transcript.remove(0).text.length();
            chars -= transcript.remove(0).text.length();
        }
    }

    /**
     * Releases the engine and the conversation.
     *
     * <p>Must run on the engine thread: closing a conversation that never
     * received its terminal callback can block forever.
     */
    public void unload() {
        engineThread.execute(this::unloadInternal);
    }

    /**
     * Releases the engine and waits for it, so that the memory is really free
     * before a memory hungry operation (AAPT2, D8) starts.
     *
     * <p>Never blocks the main thread: on the UI thread the unload is only
     * requested, because waiting there would be an ANR.
     *
     * @param timeoutMs how long to wait for the engine to go away
     * @return true when the engine was released within the timeout
     */
    public boolean unloadBlocking(long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            unload();
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        engineThread.execute(() -> {
            try {
                unloadInternal();
            } finally {
                latch.countDown();
            }
        });
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void unloadInternal() {
        cancelWatchdog();
        synchronized (engineLock) {
            Conversation toClose = conversation;
            conversation = null;
            Engine toCloseEngine = engine;
            engine = null;
            modelFile = null;
            transcript.clear();

            if (toClose != null) {
                try {
                    toClose.close();
                } catch (Throwable t) {
                    Log.w(TAG, "Closing the conversation failed", t);
                }
            }
            if (toCloseEngine != null) {
                try {
                    toCloseEngine.close();
                } catch (Throwable t) {
                    Log.w(TAG, "Closing the engine failed", t);
                }
            }
        }
        generating = false;
        setState(State.IDLE);
    }

    // endregion

    // region turns

    /** Sends a prompt to the model and streams the answer back. */
    public void send(String prompt) {
        engineThread.execute(() -> {
            synchronized (engineLock) {
                if (generating) {
                    return;
                }
                if (engine == null) {
                    // Sent too early: hold the message; it is delivered when
                    // the engine becomes ready instead of failing the turn.
                    pendingPrompt = prompt;
                    return;
                }
                generating = true;
            }
            Conversation active;
            try {
                resetConversationIfFull();
                active = ensureConversation();
            } catch (Throwable t) {
                synchronized (engineLock) {
                    generating = false;
                }
                Log.e(TAG, "Creating the conversation failed", t);
                notifyError(t);
                return;
            }
            if (active == null) {
                synchronized (engineLock) {
                    generating = false;
                }
                notifyError(new IllegalStateException("No model is loaded"));
                return;
            }
            setState(State.GENERATING);
            synchronized (currentReply) {
                currentReply.setLength(0);
            }
            armWatchdog();

            try {
                Message message = Message.Companion.user(prompt);
                active.sendMessageAsync(message, new MessageCallback() {
                    @Override
                    public void onMessage(@NonNull Message response) {
                        try {
                            cancelWatchdog();
                            String text = response.toString();
                            if (text != null && !text.isEmpty()) {
                                synchronized (currentReply) {
                                    currentReply.append(text);
                                }
                                notifyToken(text);
                            }
                            armWatchdog();
                        } catch (Throwable t) {
                            // This runs inside the native callback: an
                            // exception here would abort the process.
                            Log.e(TAG, "Failed to handle a token", t);
                        }
                    }

                    @Override
                    public void onDone() {
                        rememberExchange(prompt);
                        finishTurn();
                    }

                    @Override
                    public void onError(@NonNull Throwable t) {
                        Log.e(TAG, "Generation failed", t);
                        finishTurn();
                        notifyError(t);
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "Sending the message failed", t);
                finishTurn();
                notifyError(t);
            }
        });
    }

    /** Stores a finished exchange in the transcript, trimmed to budget. */
    private void rememberExchange(String prompt) {
        String reply;
        synchronized (currentReply) {
            reply = currentReply.toString().trim();
            currentReply.setLength(0);
        }
        if (reply.isEmpty()) {
            return;
        }
        synchronized (engineLock) {
            transcript.add(ChatMessage.user(prompt));
            transcript.add(new ChatMessage(ChatMessage.Role.ASSISTANT, reply));
            trimTranscriptLocked();
        }
    }

    /** Asks for the current turn to stop as soon as possible. */
    public void stop() {
        Conversation active;
        synchronized (engineLock) {
            active = conversation;
        }
        if (active != null) {
            try {
                active.cancelProcess();
            } catch (Throwable t) {
                Log.w(TAG, "Cancelling the generation failed", t);
            }
        }
        finishTurn();
    }

    private void finishTurn() {
        cancelWatchdog();
        synchronized (engineLock) {
            if (!generating) {
                return;
            }
            generating = false;
        }
        setState(State.READY);
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onTurnComplete();
            }
        });
    }

    private void armWatchdog() {
        cancelWatchdog();
        idleWatchdog = watchdog.schedule(() -> {
            // The terminal callback never arrived; close the turn ourselves.
            Log.w(TAG, "No token for " + IDLE_TIMEOUT_SECONDS + "s, closing the turn");
            finishTurn();
        }, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void cancelWatchdog() {
        ScheduledFuture<?> future = idleWatchdog;
        if (future != null) {
            future.cancel(false);
            idleWatchdog = null;
        }
    }

    // endregion

    // region model inspection

    /**
     * Reads the metadata of a model file without loading the weights.
     *
     * <p>Used to reject a file that is not a usable LiteRT-LM model before it
     * is copied into the model directory.
     *
     * @param file the candidate {@code .litertlm} file
     * @return a short description, or null when the file cannot be read
     */
    /** The context size the model file declares, or null when unreadable. */
    @Nullable
    private static Integer maxContextTokens(File file) {
        try (ModelInfo info = ModelInfo.from(file.getAbsolutePath())) {
            int tokens = info.maxContextTokens();
            return tokens > 0 ? tokens : null;
        } catch (Throwable t) {
            Log.w(TAG, "Cannot read the context size of " + file, t);
            return null;
        }
    }

    @Nullable
    public static String describeModel(File file) {
        try (ModelInfo info = ModelInfo.from(file.getAbsolutePath())) {
            StringBuilder builder = new StringBuilder();
            builder.append("maxContextTokens=").append(info.maxContextTokens());
            String runtime = info.minRuntimeVersion();
            if (runtime != null) {
                builder.append(", minRuntime=").append(runtime);
            }
            return builder.toString();
        } catch (Throwable t) {
            Log.w(TAG, "Cannot read the model info of " + file, t);
            return null;
        }
    }

    // endregion

    // region notifications

    private void setState(State newState) {
        state = newState;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onEngineStateChanged(newState);
            }
        });
    }

    private void notifyToken(String text) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onToken(text);
            }
        });
    }

    private void notifyError(Throwable error) {
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onError(error);
            }
        });
    }

    // endregion

    private static Backend toLiteRtBackend(AiModelConfig.Backend backend,
                                            @Nullable String nativeLibraryDir) {
        switch (backend) {
            case GPU:
                return new Backend.GPU();
            case NPU:
                return new Backend.NPU(nativeLibraryDir == null ? "" : nativeLibraryDir);
            case CPU:
            default:
                return new Backend.CPU();
        }
    }
}
