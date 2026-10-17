package pro.sketchware.ai;

import java.io.File;

import a.a.a.wq;

/**
 * Filesystem layout used by Agent Mode.
 *
 * <pre>
 * /sdcard/.sketchware/ai/
 *   agent-config.json      provider settings (no secrets: API keys are kept
 *                          in the app's private storage instead)
 *   knowledge/             user-updatable Sketchware knowledge pack
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

    /** Name of the settings file inside the agent root. */
    public static final String CONFIG_FILE_NAME = "agent-config.json";

    /** Directory holding the versioned knowledge pack. */
    public static File knowledgeDir() {
        return ensureDir(new File(agentRoot(), KNOWLEDGE_DIR_NAME));
    }

    private static File ensureDir(File dir) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }
}
