package pro.sketchware.ai;

import java.io.File;

import a.a.a.wq;

/**
 * Filesystem layout used by Agent Mode.
 *
 * <pre>
 * /sdcard/.sketchware/ai/
 *   agent-config.json      registry: model paths, backend, checksum, import date
 *   knowledge/             user-updatable Sketchware knowledge pack
 *   models/&lt;model-id&gt;/
 *       model.litertlm      the only required file
 *       extras/             companion files kept next to the model but unused
 * </pre>
 *
 * <p>The root is a hidden dot-folder next to {@code mysc/} so it survives an
 * uninstall/reinstall of the app and is never visible in the Downloads folder.
 * It carries the same "deleted together with the projects" risk as the rest of
 * {@code .sketchware}.
 */
public final class AiPaths {

    private AiPaths() {
    }

    /** Name of the registry file inside the agent root. */
    public static final String CONFIG_FILE_NAME = "agent-config.json";

    /** Directory holding imported models. */
    public static final String MODELS_DIR_NAME = "models";

    /** Directory holding the knowledge pack. */
    public static final String KNOWLEDGE_DIR_NAME = "knowledge";

    /** The single file LiteRT-LM is pointed at. */
    public static final String MODEL_FILE_NAME = "model.litertlm";

    /** Directory for companion files that ship next to the model. */
    public static final String EXTRAS_DIR_NAME = "extras";

    /** Root of the Agent Mode storage tree. */
    public static File agentRoot() {
        return ensureDir(new File(wq.getAbsolutePathOf(wq.q)));
    }

    /** The registry file that lists the configured models. */
    public static File configFile() {
        return new File(agentRoot(), CONFIG_FILE_NAME);
    }

    /** Directory that holds every imported model, keyed by model id. */
    public static File modelsDir() {
        return ensureDir(new File(agentRoot(), MODELS_DIR_NAME));
    }

    /** Directory for one specific model. */
    public static File modelDir(String modelId) {
        return ensureDir(new File(modelsDir(), sanitizeId(modelId)));
    }

    /** The {@code .litertlm} file of a given model. */
    public static File modelFile(String modelId) {
        return new File(modelDir(modelId), MODEL_FILE_NAME);
    }

    /** Directory holding companion files that were imported next to the model. */
    public static File extrasDir(String modelId) {
        return ensureDir(new File(modelDir(modelId), EXTRAS_DIR_NAME));
    }

    /** Directory holding the versioned knowledge pack. */
    public static File knowledgeDir() {
        return ensureDir(new File(agentRoot(), KNOWLEDGE_DIR_NAME));
    }

    /**
     * Turns an arbitrary model name into a safe directory name. Keeps the id
     * readable while making sure it can never escape the models directory.
     */
    public static String sanitizeId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "model";
        }
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isEmpty() ? "model" : cleaned;
    }

    private static File ensureDir(File dir) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }
}
