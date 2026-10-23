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

    /** Directory holding the knowledge pack. */
    public static final String KNOWLEDGE_DIR_NAME = "knowledge";

    /** Directory holding user-attached chat images. */
    public static final String ATTACHMENTS_DIR_NAME = "attachments";

    /** Directory holding offscreen ViewPane preview renders. */
    public static final String PREVIEWS_DIR_NAME = "previews";

    /** Directory holding user-saved and user-imported Design System JSON presets. */
    public static final String DESIGN_SYSTEMS_DIR_NAME = "design_systems";

    /** Root of the Agent Mode storage tree. */
    public static File agentRoot() {
        return ensureDir(new File(wq.getAbsolutePathOf(wq.q)));
    }

    /** The settings file with the provider choices. */
    public static File configFile() {
        return new File(agentRoot(), CONFIG_FILE_NAME);
    }

    /** Directory holding the versioned knowledge pack. */
    public static File knowledgeDir() {
        return ensureDir(new File(agentRoot(), KNOWLEDGE_DIR_NAME));
    }

    /** Directory holding user-attached chat images (hidden from MediaStore). */
    public static File attachmentsDir() {
        return ensureHiddenDir(new File(agentRoot(), ATTACHMENTS_DIR_NAME));
    }

    /** Directory holding offscreen ViewPane preview PNGs (hidden from MediaStore). */
    public static File previewsDir() {
        return ensureHiddenDir(new File(agentRoot(), PREVIEWS_DIR_NAME));
    }

    /** Directory holding user-saved and imported Design System JSON presets. */
    public static File designSystemsDir() {
        return ensureDir(new File(agentRoot(), DESIGN_SYSTEMS_DIR_NAME));
    }

    private static File ensureHiddenDir(File dir) {
        ensureDir(dir);
        File noMedia = new File(dir, ".nomedia");
        if (!noMedia.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                noMedia.createNewFile();
            } catch (Exception ignored) {
            }
        }
        return dir;
    }

    private static File ensureDir(File dir) {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }
}
