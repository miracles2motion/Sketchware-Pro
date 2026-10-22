package pro.sketchware.ai;

import android.content.Context;

import com.google.gson.JsonObject;

import java.io.File;
import java.util.HashMap;

import a.a.a.ProjectBuilder;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.wq;
import a.a.a.yB;
import a.a.a.yq;
import a.a.a.zy;
import mod.hey.studios.compiler.kotlin.KotlinCompilerBridge;
import mod.hey.studios.project.proguard.ProguardHandler;
import mod.hey.studios.project.stringfog.StringfogHandler;
import mod.jbk.build.BuildProgressReceiver;
import mod.jbk.build.BuiltInLibraries;
import mod.jbk.diagnostic.MissingFileException;
import pro.sketchware.utility.FileUtil;

/**
 * The {@code build_project} engine: compiles the working project with
 * Sketchware's own builder, following the same step sequence the
 * Design screen's Build button runs (temp files → source generation →
 * libraries → aapt2 → resources → view binding → Kotlin → javac →
 * stringfog → proguard → dex → APK → sign). Only the UI parts
 * (progress bar, install prompt) are left out.
 *
 * <p>Compiler failures arrive the same way the app surfaces them: a
 * {@code zy} whose message is the compiler output. That text, capped,
 * is the tool result's error so the model can fix the mistake and
 * build again.
 *
 * <p>Building writes only build artefacts (the project's staging and
 * {@code bin} folders, which the app wipes and regenerates on every
 * build anyway); it never touches screens, blocks, or project data.
 */
public final class AgentBuild {

    /** Cap on how much compiler output one build result carries. */
    private static final int MAX_ERROR_CHARS = 6000;

    private AgentBuild() {
    }

    public static JsonObject build(Context context, String scId)
            throws AgentTools.ToolException {
        SketchwareProjects.ProjectInfo project =
                SketchwareProjects.project(scId);
        AgentLogic.migrateAllLegacyInitializeLogic(scId);
        long started = System.currentTimeMillis();
        yq q = new yq(context, scId);
        // Progress steps go nowhere visible: the approval card's
        // working state already tells the user a build is running.
        BuildProgressReceiver receiver = (progress, step) -> { };
        try {
            FileUtil.deleteFile(q.projectMyscPath);
            q.c(context);
            q.a();
            q.a(context, wq.e("600"));
            HashMap<String, Object> metadata = lC.b(scId);
            if (yB.a(metadata, "custom_icon")) {
                q.aa(wq.e() + File.separator + scId
                        + File.separator + "mipmaps");
                if (yB.a(metadata, "isIconAdaptive", false)) {
                    q.createLauncherIconXml(""
                            + "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                            + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\" >\n"
                            + "<background android:drawable=\"@mipmap/ic_launcher_background\"/>\n"
                            + "<foreground android:drawable=\"@mipmap/ic_launcher_foreground\"/>\n"
                            + "<monochrome android:drawable=\"@mipmap/ic_launcher_monochrome\"/>\n"
                            + "</adaptive-icon>");
                } else {
                    q.a(wq.e() + File.separator + scId
                            + File.separator + "icon.png");
                }
            }
            jC.d(scId).b(q.resDirectoryPath
                    + File.separator + "drawable-xhdpi");
            jC.d(scId).c(q.resDirectoryPath + File.separator + "raw");
            jC.d(scId).a(q.assetsPath + File.separator + "fonts");

            ProjectBuilder builder =
                    new ProjectBuilder(receiver, context, q);
            q.a(jC.c(scId), jC.b(scId), jC.a(scId));
            builder.buildBuiltInLibraryInformation();
            q.b(jC.b(scId), jC.a(scId), jC.c(scId),
                    builder.getBuiltInLibraryManager());
            q.f();
            q.e();

            builder.maybeExtractAapt2();
            BuiltInLibraries.extractCompileAssets(receiver);
            builder.compileResources();
            builder.generateViewBinding();
            KotlinCompilerBridge.compileKotlinCodeIfPossible(
                    receiver, builder);
            builder.compileJavaCode();
            new StringfogHandler(scId).start(receiver, builder);
            new ProguardHandler(scId).start(receiver, builder);
            builder.createDexFilesFromClasses();
            builder.getDexFilesReady();
            builder.buildApk();
            builder.signDebugApk();
        } catch (zy e) {
            throw new AgentTools.ToolException("E_COMPILE",
                    "The project did not compile. Fix these errors and "
                            + "build again:\n" + trim(e.getMessage()));
        } catch (MissingFileException e) {
            throw new AgentTools.ToolException("E_BUILD_MISSING",
                    "A file the build needs is missing: "
                            + e.getMissingFile().getAbsolutePath()
                            + ". Building from Sketchware's own Build "
                            + "button once may recreate it.");
        } catch (Throwable e) {
            throw new AgentTools.ToolException("E_BUILD_FAILED",
                    "The build failed: " + trim(String.valueOf(
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : e.toString())));
        }
        File apk = new File(q.finalToInstallApkPath);
        if (!apk.exists() || apk.length() == 0) {
            throw new AgentTools.ToolException("E_BUILD_FAILED",
                    "The builder reported success but no APK was "
                            + "produced at " + apk.getAbsolutePath() + ".");
        }
        long seconds = (System.currentTimeMillis() - started) / 1000;
        JsonObject result = new JsonObject();
        result.addProperty("built", true);
        result.addProperty("project", project.name);
        result.addProperty("apk_kb", apk.length() / 1024);
        result.addProperty("seconds", seconds);
        // Not for the model to act on: the build card keeps these so it
        // can offer Install for this exact APK later, and re-check the
        // file still is this APK before handing it to the installer.
        result.addProperty("apk_path", apk.getAbsolutePath());
        result.addProperty("apk_bytes", apk.length());
        result.addProperty("apk_modified", apk.lastModified());
        result.addProperty("build_sc_id", scId);
        result.addProperty("verified",
                project.name + " compiled cleanly ("
                        + (apk.length() / 1024) + " KB APK, " + seconds
                        + "s). The build card now has an Install button, "
                        + "so the user can install this APK from the "
                        + "card now or later.");
        return result;
    }

    /** Keeps the head of compiler output, where diagnostics start. */
    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        text = text.trim();
        return text.length() <= MAX_ERROR_CHARS
                ? text : text.substring(0, MAX_ERROR_CHARS)
                        + "\n… (further errors not shown)";
    }
}
