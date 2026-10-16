package pro.sketchware.ai;

import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * <p><b>The conversation is reused, never recreated per turn.</b> On Android,
 * {@code MessageCallback.onDone()} is not delivered for a conversation that is
 * used exactly once (google-ai-edge/LiteRT-LM#3266, still open as of
 * 0.18.0). The turn then never closes, the engine is dead for the rest of the
 * process and closing the conversation blocks forever. Reusing one
 * conversation across turns is the only call shape that reliably delivers
 * {@code onDone()}, so the conversation is created once and kept.
 *
 * <p><b>A watchdog closes turns that never get a callback.</b> Because
 * {@code onDone()} can be lost, a turn is also considered finished when no
 * token has arrived for {@link #IDLE_TIMEOUT_SECONDS}. Without it the chat
 * would hang on a spinner forever.
 *
 * <p><b>Nothing here touches the main thread.</b> Initialising a model takes
 * seconds and closing a stuck conversation can block indefinitely, so both
 * happen on the engine thread. ANRs are fatal on Android.
 */
public class AiModelManager {

    private static final String TAG = "AiModelManager";

    /** How long a turn may stay silent before it is treated as finished. */
    private static final int IDLE_TIMEOUT_SECONDS = 20;

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
    private File modelFile;
    private AiModelConfig.Backend backend = AiModelConfig.Backend.CPU;
    private volatile State state = State.IDLE;
    private volatile boolean generating;
    private ScheduledFuture<?> idleWatchdog;
    private volatile boolean stopRequested;

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
     * @param cacheDir writable directory for the runtime cache
     */
    public void loadModel(File file, AiModelConfig.Backend backend, File cacheDir) {
        engineThread.execute(() -> {
            try {
                unloadInternal();
                setState(State.LOADING);

                Backend litertBackend = toLiteRtBackend(backend);
                EngineConfig engineConfig = new EngineConfig(
                        file.getAbsolutePath(),
                        litertBackend,
                        cacheDir == null ? null : cacheDir.getAbsolutePath());

                Engine newEngine = new Engine(engineConfig);
                newEngine.initialize();

                synchronized (engineLock) {
                    engine = newEngine;
                    modelFile = file;
                    this.backend = backend;
                    conversation = null;
                }
                setState(State.READY);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load model", t);
                setState(State.ERROR);
                notifyError(t);
            }
        });
    }

    /**
     * Creates (or reuses) the conversation.
     *
     * <p>Reuse is deliberate: see the class comment.
     */
    private Conversation ensureConversation() {
        synchronized (engineLock) {
            if (engine == null) {
                return null;
            }
            if (conversation != null) {
                return conversation;
            }
            SamplerConfig samplerConfig = new SamplerConfig(64, 0.95, 1.0f, 0);
            ConversationConfig config = new ConversationConfig(
                    /* systemInstruction    */ null,
                    /* initialMessages      */ Collections.emptyList(),
                    /* tools                */ Collections.emptyList(),
                    samplerConfig,
                    /* automaticToolCalling */ false,
                    /* channels             */ Collections.emptyList());
            conversation = engine.createConversation(config);
            return conversation;
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
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
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
            Conversation active = ensureConversation();
            if (active == null) {
                notifyError(new IllegalStateException("No model is loaded"));
                return;
            }
            generating = true;
            stopRequested = false;
            setState(State.GENERATING);

            try {
                Message message = Message.Companion.user(prompt);
                active.sendMessageAsync(message, new MessageCallback() {
                    @Override
                    public void onMessage(@NonNull Message response) {
                        cancelWatchdog();
                        String text = response.toString();
                        if (text != null && !text.isEmpty()) {
                            notifyToken(text);
                        }
                        armWatchdog();
                    }

                    @Override
                    public void onDone() {
                        finishTurn();
                    }

                    @Override
                    public void onError(@NonNull Throwable t) {
                        Log.e(TAG, "Generation failed", t);
                        finishTurn();
                        notifyError(t);
                    }
                }, Collections.emptyMap());
            } catch (Throwable t) {
                Log.e(TAG, "Sending the message failed", t);
                finishTurn();
                notifyError(t);
            }
        });
    }

    /** Asks for the current turn to stop as soon as possible. */
    public void stop() {
        stopRequested = true;
        cancelWatchdog();
        finishTurn();
    }

    private void finishTurn() {
        cancelWatchdog();
        if (generating) {
            generating = false;
            setState(State.READY);
        }
        for (Listener listener : listeners) {
            listener.onTurnComplete();
        }
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

    // region notifications

    private void setState(State newState) {
        state = newState;
        for (Listener listener : listeners) {
            listener.onEngineStateChanged(newState);
        }
    }

    private void notifyToken(String text) {
        for (Listener listener : listeners) {
            listener.onToken(text);
        }
    }

    private void notifyError(Throwable error) {
        for (Listener listener : listeners) {
            listener.onError(error);
        }
    }

    // endregion

    private static Backend toLiteRtBackend(AiModelConfig.Backend backend) {
        switch (backend) {
            case GPU:
                return Backend.GPU();
            case NPU:
                return Backend.NPU();
            case CPU:
            default:
                return Backend.CPU();
        }
    }
}
