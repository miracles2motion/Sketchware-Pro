package pro.sketchware.ai;

import android.content.Context;
import android.net.Uri;

import com.google.gson.JsonObject;

import org.spongycastle.jce.provider.BouncyCastleProvider;

import java.io.File;
import java.security.Security;
import java.util.HashMap;
import java.util.Locale;

import a.a.a.ProjectBuilder;
import a.a.a.jC;
import a.a.a.lC;
import a.a.a.wq;
import a.a.a.yB;
import a.a.a.yq;
import a.a.a.zy;
import kellinwood.security.zipsigner.ZipSigner;
import kellinwood.security.zipsigner.optional.CustomKeySigner;
import mod.alucard.tn.apksigner.ApkSigner;
import mod.hey.studios.compiler.kotlin.KotlinCompilerBridge;
import mod.hey.studios.project.proguard.ProguardHandler;
import mod.hey.studios.project.stringfog.StringfogHandler;
import mod.jbk.build.BuildProgressReceiver;
import mod.jbk.build.BuiltInLibraries;
import mod.jbk.build.compiler.bundle.AppBundleCompiler;
import mod.jbk.diagnostic.CompileErrorSaver;
import mod.jbk.diagnostic.MissingFileException;
import mod.jbk.util.TestkeySignBridge;
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
            new CompileErrorSaver(scId).deleteSavedLogs();
        } catch (zy e) {
            try {
                new CompileErrorSaver(scId).writeLogsToFile(
                        String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
            String raw = trim(e.getMessage());
            // javac speaks in generated fields; the model thinks in
            // variables. Translate the one error class that cost five
            // blind build attempts in the field.
            java.util.LinkedHashSet<String> unresolved =
                    new java.util.LinkedHashSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(_[A-Za-z][A-Za-z0-9_]*)\\s+cannot"
                            + "\\s+be\\s+resolved").matcher(raw);
            while (m.find()) {
                unresolved.add(m.group(1));
            }
            StringBuilder teach = new StringBuilder();
            for (String field : unresolved) {
                String name = field.substring(1);
                teach.append(" \u2022 `").append(field)
                        .append("` is the generated field for the "
                                + "screen's variable or list \u201c")
                        .append(name)
                        .append("\u201d \u2014 it is NOT declared on "
                                + "the screen named in the error. "
                                + "Declare it (create_variable, right "
                                + "type \u2014 lists of maps are "
                                + "list_map) on THAT screen, or remove "
                                + "the blocks that use it.\n");
            }
            throw new AgentTools.ToolException("E_COMPILE",
                    "The project did not compile. Fix these errors and "
                            + "build again:\n" + raw
                    + (teach.length() == 0 ? ""
                    : "\nWhat this means:\n" + teach));
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

    /**
     * Phase 8 release packaging: builds either a Play Store {@code .aab}
     * Android App Bundle or a signed release {@code .apk}, signed with a
     * user {@code .jks}/{@code .keystore} when provided or {@code testkey}
     * otherwise.
     */
    public static JsonObject buildReleasePackage(
            Context context, String scId, String format,
            String keystorePath, String keystorePassword,
            String keyAlias, String keyPassword)
            throws AgentTools.ToolException {
        boolean isBundle = "aab".equalsIgnoreCase(format)
                || "bundle".equalsIgnoreCase(format)
                || "app_bundle".equalsIgnoreCase(format);
        boolean hasKeystore = keystorePath != null
                && !keystorePath.trim().isEmpty();
        if (hasKeystore) {
            File ks = new File(keystorePath.trim());
            if (!ks.isFile()) {
                throw new AgentTools.ToolException("E_KEYSTORE_NOT_FOUND",
                        "Keystore file does not exist: " + keystorePath);
            }
            if (keystorePassword == null || keystorePassword.isEmpty()
                    || keyAlias == null || keyAlias.trim().isEmpty()) {
                throw new AgentTools.ToolException(
                        "E_MISSING_KEYSTORE_CREDENTIALS",
                        "Provide keystore_password and key_alias alongside "
                                + "keystore_path.");
            }
        }

        SketchwareProjects.ProjectInfo project =
                SketchwareProjects.project(scId);
        AgentLogic.migrateAllLegacyInitializeLogic(scId);
        long started = System.currentTimeMillis();
        yq q = new yq(context, scId);
        BuildProgressReceiver receiver = (progress, step) -> { };
        File outFile;
        String signedMode = hasKeystore ? "custom_keystore" : "testkey";

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
                            + "<adaptive-icon xmlns:android="
                            + "\"http://schemas.android.com/apk/res/android\">\n"
                            + "<background android:drawable="
                            + "\"@mipmap/ic_launcher_background\"/>\n"
                            + "<foreground android:drawable="
                            + "\"@mipmap/ic_launcher_foreground\"/>\n"
                            + "<monochrome android:drawable="
                            + "\"@mipmap/ic_launcher_monochrome\"/>\n"
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
            builder.setBuildAppBundle(isBundle);
            q.a(jC.c(scId), jC.b(scId), jC.a(scId),
                    isBundle ? yq.ExportType.AAB : yq.ExportType.SIGN_APP);
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

            String effectiveKeyPwd = (keyPassword == null
                    || keyPassword.isEmpty())
                    ? keystorePassword : keyPassword;

            if (isBundle) {
                AppBundleCompiler compiler = new AppBundleCompiler(builder);
                compiler.createModuleMainArchive();
                compiler.buildBundle();
                String rawBundle = AppBundleCompiler
                        .getDefaultAppBundleOutputFile(q).getAbsolutePath();
                String signedDir = FileUtil.getExternalStorageDir()
                        + File.separator + "sketchware"
                        + File.separator + "signed_aab";
                FileUtil.makeDir(signedDir);
                String outPath = signedDir + File.separator
                        + Uri.fromFile(new File(rawBundle))
                                .getLastPathSegment();
                if (hasKeystore) {
                    Security.addProvider(new BouncyCastleProvider());
                    CustomKeySigner.signZip(
                            new ZipSigner(),
                            keystorePath.trim(),
                            keystorePassword.toCharArray(),
                            keyAlias.trim(),
                            effectiveKeyPwd.toCharArray(),
                            "SHA256withRSA",
                            rawBundle,
                            outPath);
                } else {
                    ZipSigner signer = new ZipSigner();
                    signer.setKeymode(ZipSigner.KEY_TESTKEY);
                    signer.signZip(rawBundle, outPath);
                }
                outFile = new File(outPath);
            } else {
                builder.buildApk();
                builder.runZipalign(q.unsignedUnalignedApkPath,
                        q.unsignedAlignedApkPath);
                String signedDir = FileUtil.getExternalStorageDir()
                        + File.separator + "sketchware"
                        + File.separator + "signed_apk";
                FileUtil.makeDir(signedDir);
                String safeName = project.name.replaceAll(
                        "[^A-Za-z0-9._-]", "_");
                String outPath = signedDir + File.separator
                        + safeName + "_release.apk";
                if (hasKeystore) {
                    final StringBuilder signErr = new StringBuilder();
                    new ApkSigner().signWithKeyStore(
                            q.unsignedAlignedApkPath,
                            outPath,
                            keystorePath.trim(),
                            keystorePassword,
                            keyAlias.trim(),
                            effectiveKeyPwd,
                            line -> {
                                if (line != null && line.contains("Failed")) {
                                    signErr.append(line).append('\n');
                                }
                            });
                    if (signErr.length() > 0) {
                        throw new IllegalStateException(
                                signErr.toString().trim());
                    }
                } else {
                    TestkeySignBridge.signWithTestkey(
                            q.unsignedAlignedApkPath, outPath);
                }
                outFile = new File(outPath);
            }
            new CompileErrorSaver(scId).deleteSavedLogs();
        } catch (zy e) {
            try {
                new CompileErrorSaver(scId).writeLogsToFile(
                        String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
            throw new AgentTools.ToolException("E_COMPILE",
                    "Release build did not compile:\n"
                            + trim(e.getMessage()));
        } catch (MissingFileException e) {
            throw new AgentTools.ToolException("E_BUILD_MISSING",
                    "Missing build dependency: "
                            + e.getMissingFile().getAbsolutePath());
        } catch (Throwable e) {
            throw new AgentTools.ToolException("E_RELEASE_BUILD_FAILED",
                    "Release packaging failed: " + trim(String.valueOf(
                            e.getMessage() != null
                                    ? e.getMessage() : e.toString())));
        }

        if (!outFile.exists() || outFile.length() == 0) {
            throw new AgentTools.ToolException("E_RELEASE_BUILD_FAILED",
                    "No output package was created at "
                            + outFile.getAbsolutePath());
        }
        long seconds = (System.currentTimeMillis() - started) / 1000;
        JsonObject res = new JsonObject();
        res.addProperty("built", true);
        res.addProperty("project", project.name);
        res.addProperty("format",
                isBundle ? "aab" : "apk");
        res.addProperty("signed_with", signedMode);
        res.addProperty("output_path", outFile.getAbsolutePath());
        res.addProperty("size_kb", outFile.length() / 1024);
        res.addProperty("seconds", seconds);
        if (!isBundle) {
            res.addProperty("apk_path", outFile.getAbsolutePath());
            res.addProperty("apk_bytes", outFile.length());
            res.addProperty("apk_modified", outFile.lastModified());
            res.addProperty("build_sc_id", scId);
        }
        res.addProperty("verified",
                "Built and signed " + project.name + " "
                        + (isBundle ? "App Bundle (.aab)" : "release APK")
                        + " (" + (outFile.length() / 1024) + " KB, "
                        + seconds + "s) -> " + outFile.getAbsolutePath());
        return res;
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
