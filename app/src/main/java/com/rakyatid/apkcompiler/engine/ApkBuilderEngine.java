package com.rakyatid.apkcompiler.engine;

import android.content.Context;
import com.rakyatid.apkcompiler.model.Dependency;
import com.rakyatid.apkcompiler.model.Project;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ApkBuilderEngine {

    public interface BuildListener {
        void onLog(String line);
        void onProgress(int step, int totalSteps, String message);
        void onSuccess(File finalApk, long durationMs);
        void onError(String error);
    }

    private final Context context;
    private final ToolchainManager toolchain;
    private boolean isBuilding = false;

    public ApkBuilderEngine(Context context, ToolchainManager toolchain) {
        this.context = context;
        this.toolchain = toolchain;
    }

    public boolean isBuilding() {
        return isBuilding;
    }

    public void buildProject(Project project, BuildListener listener) {
        if (isBuilding) {
            listener.onError("Proses kompilasi sedang berjalan.");
            return;
        }

        isBuilding = true;
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            try {
                listener.onProgress(1, 6, "[1/6] Menyiapkan direktori build...");
                listener.onLog("=== MEMULAI KOMPILASI PROYEK: " + project.getName() + " ===");
                listener.onLog("[INFO] Package: " + project.getPackageName());

                // Auto-bump targetSdk < 24 for Android 14+ install compatibility
                if (project.getTargetSdk() < 24) {
                    int origTargetSdk = project.getTargetSdk();
                    int adjustedTargetSdk = 24;
                    project.setTargetSdk(adjustedTargetSdk);
                    listener.onLog(String.format(Locale.US, "[SDK] Note: Project targetSdk is %d (< 23). Adjusted to %d for modern Android 14+ install compatibility.", origTargetSdk, adjustedTargetSdk));
                }

                if (project.getMinSdk() < 21) {
                    int origMinSdk = project.getMinSdk();
                    int adjustedMinSdk = 21;
                    project.setMinSdk(adjustedMinSdk);
                    listener.onLog("[SDK] Note: Project minSdk is " + origMinSdk + " (< 21). Adjusted to " + adjustedMinSdk + " for modern Android ART compatibility.");
                }

                listener.onLog("[INFO] Min SDK: " + project.getMinSdk() + ", Target SDK: " + project.getTargetSdk() + ", Compile SDK: " + project.getCompileSdk());

                // Audit Keamanan Proyek & Sandboxing
                listener.onLog("[SECURITY] Menjalankan audit keamanan proyek...");
                SecurityAuditor.AuditResult auditResult = SecurityAuditor.auditProject(project, listener::onLog);
                if (!auditResult.isSafe()) {
                    StringBuilder sb = new StringBuilder("Kompilasi dibatalkan demi keamanan:\n");
                    for (String v : auditResult.getViolations()) {
                        sb.append(" - ").append(v).append("\n");
                    }
                    throw new SecurityException(sb.toString().trim());
                }
                for (String warning : auditResult.getWarnings()) {
                    listener.onLog("[SECURITY WARN] " + warning);
                }

                File buildDir = new File(project.getRootDir(), "build");
                File genDir = new File(buildDir, "gen");
                File objDir = new File(buildDir, "obj");
                File dexDir = new File(buildDir, "dex");
                File distDir = new File(buildDir, "dist");

                deleteDir(genDir);
                deleteDir(objDir);
                deleteDir(dexDir);
                deleteDir(distDir);

                genDir.mkdirs();
                objDir.mkdirs();
                dexDir.mkdirs();
                distDir.mkdirs();

                File toolchainDir = toolchain.getToolchainDir();
                File androidJar = toolchain.getAndroidJarForProject(project);
                if (androidJar == null || !androidJar.exists()) {
                    androidJar = toolchain.getFile("android_jar");
                }
                if (androidJar == null || !androidJar.exists()) {
                    File localJar = new File(toolchainDir, "android.jar");
                    if (localJar.exists()) {
                        androidJar = localJar;
                    }
                }
                if (androidJar == null || !androidJar.exists()) {
                    File sdkJar = new File("/home/hatch/android-sdk/platforms/android-34/android.jar");
                    if (sdkJar.exists()) {
                        androidJar = sdkJar;
                    }
                }
                if (androidJar != null && androidJar.exists()) {
                    listener.onLog("[SDK] Menggunakan Platform SDK: " + androidJar.getName() + " (" + androidJar.getAbsolutePath() + ")");
                }

                File aapt2Binary = toolchain.getFile("aapt2");
                if (aapt2Binary == null || !aapt2Binary.exists()) {
                    File sdkAapt2 = new File("/home/hatch/android-sdk/build-tools/34.0.0/aapt2");
                    if (sdkAapt2.exists()) {
                        aapt2Binary = sdkAapt2;
                    }
                }

                // STEP 1.5: Resolusi Dependensi & AAR Libraries
                listener.onProgress(2, 6, "[2/6] Memeriksa dan Menyelesaikan Dependensi...");
                listener.onLog("[2/6] Menyelesaikan dependensi & pustaka AAR/JAR...");
                List<Dependency> projectDeps = project.getDependencies();
                if (projectDeps == null || projectDeps.isEmpty()) {
                    projectDeps = DependencyManager.detectProjectDependencies(project, listener::onLog);
                    project.setDependencies(projectDeps);
                }

                DependencyManager.ResolutionResult depResult = DependencyManager.resolve(
                        project, projectDeps, toolchainDir, buildDir, aapt2Binary, context, listener::onLog
                );

                // STEP 2: AAPT2 / Resource & Manifest
                listener.onProgress(3, 6, "[3/6] Memproses Resource dan Manifest...");
                listener.onLog("[3/6] Memproses Resource & Manifest...");

                File unsignedApk = new File(buildDir, "unsigned.apk");

                boolean aapt2Success = false;
                if (aapt2Binary != null && aapt2Binary.exists() && androidJar != null && androidJar.exists()) {
                    try {
                        aapt2Binary.setExecutable(true, false);
                    } catch (Throwable ignored) {}
                    aapt2Success = runAapt2(project, buildDir, genDir, unsignedApk, aapt2Binary, androidJar, depResult, listener);
                }

                if (!aapt2Success) {
                    if (aapt2Binary == null || !aapt2Binary.exists()) {
                        throw new IOException("AAPT2 binary tidak ditemukan (" + (aapt2Binary != null ? aapt2Binary.getAbsolutePath() : "null") + "). Pastikan Toolchain telah terpasang.");
                    } else if (androidJar == null || !androidJar.exists()) {
                        throw new IOException("android.jar tidak ditemukan. Unduh platform SDK di tab Toolchain.");
                    } else {
                        throw new IOException("AAPT2 link gagal memproses resource/manifest. Periksa log kesalahan.");
                    }
                }

                // STEP 3: Kompilasi Java (.java -> .class)
                listener.onProgress(4, 6, "[4/6] Mengompilasi Kode Java...");
                listener.onLog("[4/6] Mengompilasi kode Java...");

                List<File> javaFiles = new ArrayList<>();
                if (project.getJavaSrcDir() != null) {
                    javaFiles.addAll(ProjectScanner.listSourceFiles(project.getJavaSrcDir(), ".java"));
                }
                javaFiles.addAll(ProjectScanner.listSourceFiles(genDir, ".java"));

                if (javaFiles.isEmpty()) {
                    throw new IOException("Tidak ada file kode sumber .java yang ditemukan.");
                }

                listener.onLog("[INFO] Ditemukan " + javaFiles.size() + " file sumber Java (termasuk kelas R).");
                boolean javaSuccess = compileJavaSources(project, javaFiles, objDir, androidJar, depResult, listener);
                if (!javaSuccess) {
                    throw new IOException("Kompilasi Java gagal. Periksa error sintaks pada log.");
                }

                // STEP 4: DEX Compilation (.class -> classes.dex)
                listener.onProgress(5, 6, "[5/6] Mengonversi ke Dalvik Executable (classes.dex)...");
                listener.onLog("[5/6] Mengonversi bytecode ke classes.dex via D8...");

                List<File> classFiles = ProjectScanner.listSourceFiles(objDir, ".class");
                if (classFiles.isEmpty()) {
                    throw new IOException("File .class tidak ditemukan setelah kompilasi Java.");
                }

                boolean dexSuccess = compileDex(project, classFiles, dexDir, androidJar, depResult, listener);
                if (!dexSuccess) {
                    throw new IOException("Kompilasi D8/Dex gagal.");
                }

                File classesDex = new File(dexDir, "classes.dex");
                if (!classesDex.exists() || classesDex.length() == 0) {
                    throw new IOException("classes.dex tidak terbuat.");
                }
                listener.onLog("[SUCCESS] classes.dex berhasil dibuat (" + (classesDex.length() / 1024) + " KB).");

                // STEP 5: Package APK (Gabungkan dex + res + assets)
                listener.onProgress(6, 6, "[6/6] Merakit dan Menandatangani Paket APK...");
                listener.onLog("[6/6] Merakit paket APK...");

                File unalignedApk = new File(buildDir, "app-unaligned.apk");
                packageApk(unsignedApk, dexDir, unalignedApk, project, listener);

                File alignedApk = new File(buildDir, "app-aligned.apk");
                ZipAlignUtil.alignZip(unalignedApk, alignedApk);

                // Sign APK
                listener.onLog("[SIGN] Menandatangani APK dengan keystore debug...");

                File outputDir = (context != null && context.getExternalFilesDir(null) != null) ?
                        new File(context.getExternalFilesDir(null), "outputs") :
                        new File(buildDir, "dist");
                outputDir.mkdirs();
                File finalApk = new File(outputDir, project.getName() + "-release.apk");

                File keystoreFile = toolchain.getFile("keystore");
                if (keystoreFile == null || !keystoreFile.exists() || keystoreFile.length() == 0) {
                    toolchain.ensureKeystoreReady();
                    keystoreFile = toolchain.getFile("keystore");
                }
                if (keystoreFile == null || !keystoreFile.exists() || keystoreFile.length() == 0) {
                    keystoreFile = new File(toolchainDir, "debug.keystore");
                }
                if (!keystoreFile.exists() || keystoreFile.length() == 0) {
                    File localKs = new File("debug.keystore");
                    if (!localKs.exists()) localKs = new File("/home/hatch/workspace/apk-compiler/debug.keystore");
                    if (localKs.exists()) keystoreFile = localKs;
                }

                String ksPass = toolchain.getKeystorePassword();
                File apksignerTool = toolchain.getFile("apksigner");
                if (apksignerTool == null || !apksignerTool.exists()) {
                    File sdkSigner = new File("/home/hatch/android-sdk/build-tools/34.0.0/apksigner");
                    if (sdkSigner.exists()) {
                        apksignerTool = sdkSigner;
                    }
                }
                boolean signOk = ApkSignerUtil.signApk(alignedApk, finalApk, keystoreFile,
                        ksPass, "androiddebugkey", ksPass, project.getMinSdk(), apksignerTool, listener::onLog);

                if (!signOk || !finalApk.exists()) {
                    throw new IOException("Penandatanganan APK gagal.");
                }

                long duration = System.currentTimeMillis() - startTime;
                listener.onLog("=== KOMPILASI SELESAI SUKSES (" + String.format("%.2f", duration / 1000.0) + " detik) ===");
                listener.onLog("[OUTPUT] Lokasi APK: " + finalApk.getAbsolutePath());
                listener.onLog("[OUTPUT] Ukuran: " + String.format("%.2f MB", finalApk.length() / (1024.0 * 1024.0)));

                listener.onSuccess(finalApk, duration);

            } catch (Throwable e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                listener.onLog("[FATAL ERROR] " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                StringWriter sw = new StringWriter();
                cause.printStackTrace(new PrintWriter(sw));
                listener.onLog(sw.toString());
                listener.onError("Kompilasi gagal: " + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
            } finally {
                isBuilding = false;
            }
        }).start();
    }

    private boolean runAapt2(Project project, File buildDir, File genDir, File unsignedApk,
                            File aapt2Binary, File androidJar, DependencyManager.ResolutionResult depResult,
                            BuildListener listener) {
        try {
            // Siapkan default fallback resources dengan style stubs untuk AppCompat & MaterialComponents
            File fallbackResDir = new File(buildDir, "fallback_res");
            File fallbackValues = new File(fallbackResDir, "values");
            File fallbackDrawable = new File(fallbackResDir, "drawable");
            File fallbackMipmap = new File(fallbackResDir, "mipmap");
            fallbackValues.mkdirs();
            fallbackDrawable.mkdirs();
            fallbackMipmap.mkdirs();

            // 1. defaults.xml
            String defAppName = project.getName() != null ? SecurityAuditor.sanitizeXmlText(project.getName()) : "App";
            String defaultsXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<resources>\n" +
                    "    <string name=\"app_name\">" + defAppName + "</string>\n" +
                    "    <style name=\"AppTheme\" parent=\"android:Theme.Material.Light.NoActionBar\" />\n" +
                    "</resources>\n";
            File defStrings = new File(fallbackValues, "defaults.xml");
            try (FileOutputStream fos = new FileOutputStream(defStrings)) {
                fos.write(defaultsXml.getBytes("UTF-8"));
            }

            // 2. styles_compat.xml (Synthetic style stubs so AAPT2 linking never fails on AppCompat/Material themes)
            String stylesCompatXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<resources>\n" +
                    "    <!-- AppCompat Themes Fallback Stubs -->\n" +
                    "    <style name=\"Theme.AppCompat\" parent=\"android:Theme.DeviceDefault\" />\n" +
                    "    <style name=\"Theme.AppCompat.Light\" parent=\"android:Theme.DeviceDefault.Light\" />\n" +
                    "    <style name=\"Theme.AppCompat.Light.DarkActionBar\" parent=\"android:Theme.DeviceDefault.Light.DarkActionBar\" />\n" +
                    "    <style name=\"Theme.AppCompat.NoActionBar\" parent=\"android:Theme.DeviceDefault.NoActionBar\" />\n" +
                    "    <style name=\"Theme.AppCompat.Light.NoActionBar\" parent=\"android:Theme.DeviceDefault.Light.NoActionBar\" />\n" +
                    "    <style name=\"Theme.AppCompat.DayNight\" parent=\"android:Theme.DeviceDefault\" />\n" +
                    "    <style name=\"Theme.AppCompat.DayNight.DarkActionBar\" parent=\"android:Theme.DeviceDefault.Light.DarkActionBar\" />\n" +
                    "    <style name=\"Theme.AppCompat.DayNight.NoActionBar\" parent=\"android:Theme.DeviceDefault.NoActionBar\" />\n" +
                    "    <style name=\"Theme.AppCompat.Dialog\" parent=\"android:Theme.DeviceDefault.Dialog\" />\n" +
                    "    <style name=\"Theme.AppCompat.Light.Dialog\" parent=\"android:Theme.DeviceDefault.Light.Dialog\" />\n" +
                    "    <style name=\"Theme.AppCompat.Dialog.Alert\" parent=\"android:Theme.DeviceDefault.Dialog\" />\n" +
                    "    <style name=\"Theme.AppCompat.Light.Dialog.Alert\" parent=\"android:Theme.DeviceDefault.Light.Dialog\" />\n" +
                    "    <style name=\"Theme.AppCompat.DialogWhenLarge\" parent=\"android:Theme.DeviceDefault.DialogWhenLarge\" />\n" +
                    "    <style name=\"Theme.AppCompat.Light.DialogWhenLarge\" parent=\"android:Theme.DeviceDefault.Light.DialogWhenLarge\" />\n" +
                    "    <style name=\"Theme.AppCompat.CompactMenu\" parent=\"android:Theme.DeviceDefault\" />\n" +
                    "\n" +
                    "    <!-- MaterialComponents Themes Fallback Stubs -->\n" +
                    "    <style name=\"Theme.MaterialComponents\" parent=\"android:Theme.DeviceDefault\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.Light\" parent=\"android:Theme.DeviceDefault.Light\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.Light.DarkActionBar\" parent=\"android:Theme.DeviceDefault.Light.DarkActionBar\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.NoActionBar\" parent=\"android:Theme.DeviceDefault.NoActionBar\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.Light.NoActionBar\" parent=\"android:Theme.DeviceDefault.Light.NoActionBar\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.DayNight\" parent=\"android:Theme.DeviceDefault\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.DayNight.DarkActionBar\" parent=\"android:Theme.DeviceDefault.Light.DarkActionBar\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.DayNight.NoActionBar\" parent=\"android:Theme.DeviceDefault.NoActionBar\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.Dialog\" parent=\"android:Theme.DeviceDefault.Dialog\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.Light.Dialog\" parent=\"android:Theme.DeviceDefault.Light.Dialog\" />\n" +
                    "    <style name=\"Theme.MaterialComponents.BottomSheetDialog\" parent=\"android:Theme.DeviceDefault.Dialog\" />\n" +
                    "</resources>\n";
            File defStyles = new File(fallbackValues, "styles_compat.xml");
            try (FileOutputStream fos = new FileOutputStream(defStyles)) {
                fos.write(stylesCompatXml.getBytes("UTF-8"));
            }

            // 3. attrs_compat.xml (Common attributes for AppCompat and Material)
            String attrsCompatXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<resources>\n" +
                    "    <attr name=\"colorPrimary\" format=\"color|reference\" />\n" +
                    "    <attr name=\"colorPrimaryDark\" format=\"color|reference\" />\n" +
                    "    <attr name=\"colorAccent\" format=\"color|reference\" />\n" +
                    "    <attr name=\"colorSecondary\" format=\"color|reference\" />\n" +
                    "    <attr name=\"colorButtonNormal\" format=\"color|reference\" />\n" +
                    "    <attr name=\"colorControlNormal\" format=\"color|reference\" />\n" +
                    "    <attr name=\"colorControlActivated\" format=\"color|reference\" />\n" +
                    "    <attr name=\"colorControlHighlight\" format=\"color|reference\" />\n" +
                    "    <attr name=\"title\" format=\"string|reference\" />\n" +
                    "    <attr name=\"subtitle\" format=\"string|reference\" />\n" +
                    "    <attr name=\"alpha\" format=\"float\" />\n" +
                    "    <attr name=\"showAsAction\">\n" +
                    "        <flag name=\"never\" value=\"0\" />\n" +
                    "        <flag name=\"ifRoom\" value=\"1\" />\n" +
                    "        <flag name=\"always\" value=\"2\" />\n" +
                    "        <flag name=\"withText\" value=\"4\" />\n" +
                    "        <flag name=\"collapseActionView\" value=\"8\" />\n" +
                    "    </attr>\n" +
                    "    <attr name=\"actionLayout\" format=\"reference\" />\n" +
                    "    <attr name=\"actionViewClass\" format=\"string\" />\n" +
                    "    <attr name=\"actionProviderClass\" format=\"string\" />\n" +
                    "    <attr name=\"actionBarStyle\" format=\"reference\" />\n" +
                    "    <attr name=\"actionBarSize\" format=\"dimension\" />\n" +
                    "    <attr name=\"windowActionBar\" format=\"boolean\" />\n" +
                    "    <attr name=\"windowNoTitle\" format=\"boolean\" />\n" +
                    "</resources>\n";
            File defAttrs = new File(fallbackValues, "attrs_compat.xml");
            try (FileOutputStream fos = new FileOutputStream(defAttrs)) {
                fos.write(attrsCompatXml.getBytes("UTF-8"));
            }

            String iconVector = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    android:width=\"24dp\"\n" +
                    "    android:height=\"24dp\"\n" +
                    "    android:viewportWidth=\"24\"\n" +
                    "    android:viewportHeight=\"24\">\n" +
                    "  <path android:fillColor=\"#FF2196F3\" android:pathData=\"M12,2L2,22h20L12,2z\"/>\n" +
                    "</vector>\n";
            File defIcon = new File(fallbackDrawable, "ic_launcher.xml");
            try (FileOutputStream fos = new FileOutputStream(defIcon)) {
                fos.write(iconVector.getBytes("UTF-8"));
            }
            File defMipmapIcon = new File(fallbackMipmap, "ic_launcher.xml");
            try (FileOutputStream fos = new FileOutputStream(defMipmapIcon)) {
                fos.write(iconVector.getBytes("UTF-8"));
            }
            File defMipmapRound = new File(fallbackMipmap, "ic_launcher_round.xml");
            try (FileOutputStream fos = new FileOutputStream(defMipmapRound)) {
                fos.write(iconVector.getBytes("UTF-8"));
            }

            File fallbackZip = new File(buildDir, "fallback_res.zip");
            List<String> cmdCompileFallback = Arrays.asList(
                    aapt2Binary.getAbsolutePath(), "compile",
                    "--dir", fallbackResDir.getAbsolutePath(),
                    "-o", fallbackZip.getAbsolutePath()
            );
            runProcess(cmdCompileFallback, listener);

            File resZip = new File(buildDir, "res.zip");
            if (project.getResDir() != null && project.getResDir().exists()) {
                listener.onLog("[AAPT2] Mengompilasi resource proyek...");
                List<String> cmdCompile = Arrays.asList(
                        aapt2Binary.getAbsolutePath(), "compile",
                        "--dir", project.getResDir().getAbsolutePath(),
                        "-o", resZip.getAbsolutePath()
                );
                int r1 = runProcess(cmdCompile, listener);
                if (r1 != 0) {
                    listener.onLog("[WARN] Kompilasi resource proyek gagal, mencoba overlay fallback...");
                }
            }

            listener.onLog("[AAPT2] Menautkan manifest dan resource...");
            File linkManifest = prepareManifestForLink(project, buildDir);

            List<String> cmdLink = new ArrayList<>();
            cmdLink.add(aapt2Binary.getAbsolutePath());
            cmdLink.add("link");
            cmdLink.add("-o");
            cmdLink.add(unsignedApk.getAbsolutePath());
            cmdLink.add("-I");
            cmdLink.add(androidJar.getAbsolutePath());
            cmdLink.add("--manifest");
            cmdLink.add(linkManifest.getAbsolutePath());
            cmdLink.add("--java");
            cmdLink.add(genDir.getAbsolutePath());
            cmdLink.add("--min-sdk-version");
            cmdLink.add(String.valueOf(project.getMinSdk()));
            cmdLink.add("--target-sdk-version");
            cmdLink.add(String.valueOf(project.getTargetSdk()));
            cmdLink.add("--auto-add-overlay");
            if (project.getPackageName() != null && !project.getPackageName().isEmpty()) {
                cmdLink.add("--rename-manifest-package");
                cmdLink.add(project.getPackageName());
                cmdLink.add("--custom-package");
                cmdLink.add(project.getPackageName());
            }

            // Tambahkan extra packages dari AAR agar library R.java juga di-generate
            if (depResult != null && !depResult.getExtraPackages().isEmpty()) {
                StringBuilder extraPkgs = new StringBuilder();
                for (String ep : depResult.getExtraPackages()) {
                    if (!ep.equals(project.getPackageName())) {
                        if (extraPkgs.length() > 0) extraPkgs.append(":");
                        extraPkgs.append(ep);
                    }
                }
                if (extraPkgs.length() > 0) {
                    cmdLink.add("--extra-packages");
                    cmdLink.add(extraPkgs.toString());
                }
            }

            // Input dasar fallback stubs
            if (fallbackZip.exists() && fallbackZip.length() > 0) {
                cmdLink.add(fallbackZip.getAbsolutePath());
            }

            // Overlay resource dari semua AAR yang dideklarasikan
            if (depResult != null) {
                for (File aarRes : depResult.getAarResZips()) {
                    if (aarRes.exists() && aarRes.length() > 0) {
                        cmdLink.add("-R");
                        cmdLink.add(aarRes.getAbsolutePath());
                    }
                }
            }

            // Overlay resource proyek aplikasi (diletakkan paling akhir agar mengambil prioritas tertinggi)
            if (resZip.exists() && resZip.length() > 0) {
                cmdLink.add("-R");
                cmdLink.add(resZip.getAbsolutePath());
            }

            int r2 = runProcess(cmdLink, listener);
            return r2 == 0 && unsignedApk.exists() && unsignedApk.length() > 0;
        } catch (Exception e) {
            listener.onLog("[AAPT2 ERROR] " + e.getMessage());
            return false;
        }
    }

    private File prepareManifestForLink(Project project, File buildDir) throws IOException {
        File origManifest = project.getManifestFile();
        byte[] bytes = new byte[(int) origManifest.length()];
        try (FileInputStream fis = new FileInputStream(origManifest)) {
            int total = 0;
            while (total < bytes.length) {
                int r = fis.read(bytes, total, bytes.length - total);
                if (r <= 0) break;
                total += r;
            }
        }
        String content = new String(bytes, "UTF-8");

        // Periksa apakah tag <manifest memiliki atribut package
        if (!content.matches("(?s).*<manifest[^>]*\\bpackage\\s*=[^>]*>.*")) {
            String pkg = project.getPackageName() != null && SecurityAuditor.isValidPackageName(project.getPackageName())
                    ? project.getPackageName() : "com.example.app";
            content = content.replaceFirst("<manifest\\b", "<manifest package=\"" + pkg + "\" ");
        }

        // Pastikan uses-sdk sesuai dengan project minSdk dan targetSdk (agar AAPT2 tidak mempertahankan SDK lama)
        if (content.contains("<uses-sdk")) {
            content = content.replaceAll("android:minSdkVersion=\"\\d+\"", "android:minSdkVersion=\"" + project.getMinSdk() + "\"");
            content = content.replaceAll("android:targetSdkVersion=\"\\d+\"", "android:targetSdkVersion=\"" + project.getTargetSdk() + "\"");
        } else {
            String usesSdkTag = "\n    <uses-sdk android:minSdkVersion=\"" + project.getMinSdk() + "\" android:targetSdkVersion=\"" + project.getTargetSdk() + "\" />";
            content = content.replaceFirst("(<manifest[^>]*>)", "$1" + usesSdkTag);
        }

        File linkManifest = new File(buildDir, "AndroidManifest_link.xml");
        try (FileOutputStream fos = new FileOutputStream(linkManifest)) {
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        }
        return linkManifest;
    }

    private boolean compileJavaSources(Project project, List<File> javaFiles, File objDir,
                                      File androidJar, DependencyManager.ResolutionResult depResult,
                                      BuildListener listener) {
        try {
            StringBuilder cpBuilder = new StringBuilder();
            if (androidJar != null && androidJar.exists()) {
                cpBuilder.append(androidJar.getAbsolutePath());
            }

            // Tambahkan classpath dari semua dependency AAR & JAR yang di-resolve
            if (depResult != null) {
                for (File dj : depResult.getClasspathJars()) {
                    if (dj.exists() && dj.length() > 0) {
                        if (cpBuilder.length() > 0) cpBuilder.append(File.pathSeparator);
                        cpBuilder.append(dj.getAbsolutePath());
                    }
                }
            }

            // Tambahkan classpath dari libs jika ada
            File libsDir = new File(project.getRootDir(), "libs");
            if (libsDir.exists() && libsDir.isDirectory()) {
                File[] jars = libsDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    for (File j : jars) {
                        if (cpBuilder.length() > 0) cpBuilder.append(File.pathSeparator);
                        cpBuilder.append(j.getAbsolutePath());
                    }
                }
            }

            String cp = cpBuilder.toString();

            listener.onLog("[ECJ] Menyiapkan parameter kompilasi...");
            List<String> argsList = new ArrayList<>();
            argsList.add("-1.8");
            argsList.add("-nowarn");
            argsList.add("-proc:none");
            argsList.add("-encoding");
            argsList.add("UTF-8");
            if (androidJar != null && androidJar.exists()) {
                argsList.add("-bootclasspath");
                argsList.add(androidJar.getAbsolutePath());
            }
            if (!cp.isEmpty()) {
                argsList.add("-cp");
                argsList.add(cp);
            }
            argsList.add("-d");
            argsList.add(objDir.getAbsolutePath());
            for (File f : javaFiles) {
                argsList.add(f.getAbsolutePath());
            }

            // 1. Cek apakah ECJ tersedia langsung di ClassLoader aplikasi
            Class<?> mainClass = null;
            try {
                mainClass = Class.forName("org.eclipse.jdt.internal.compiler.batch.Main");
            } catch (ClassNotFoundException ignored) {}

            // 2. Jika tidak ada di internal, coba muat dari toolchain ecj via DexClassLoader atau URLClassLoader
            if (mainClass == null) {
                File ecjJar = toolchain.getFile("ecj");
                if (ecjJar == null || !ecjJar.exists()) {
                    File localEcj = new File("ecj.jar");
                    if (!localEcj.exists()) localEcj = new File("/home/hatch/workspace/apk-compiler/ecj.jar");
                    if (localEcj.exists()) ecjJar = localEcj;
                }
                if (ecjJar != null && ecjJar.exists()) {
                    if (context != null) {
                        try {
                            File optDir = context.getCodeCacheDir();
                            dalvik.system.DexClassLoader dcl = new dalvik.system.DexClassLoader(
                                    ecjJar.getAbsolutePath(), optDir.getAbsolutePath(), null, context.getClassLoader());
                            mainClass = dcl.loadClass("org.eclipse.jdt.internal.compiler.batch.Main");
                        } catch (Throwable ignored) {}
                    }
                    if (mainClass == null) {
                        try {
                            URLClassLoader ucl = new URLClassLoader(new URL[]{ ecjJar.toURI().toURL() }, getClass().getClassLoader());
                            mainClass = ucl.loadClass("org.eclipse.jdt.internal.compiler.batch.Main");
                        } catch (Throwable ignored) {}
                    }
                }
            }

            if (mainClass != null) {
                listener.onLog("[ECJ] Mengompilasi via in-process ECJ compiler...");
                initEcjResourceBundle(mainClass.getClassLoader());

                StringWriter outSw = new StringWriter();
                StringWriter errSw = new StringWriter();
                PrintWriter outPw = new PrintWriter(outSw);
                PrintWriter errPw = new PrintWriter(errSw);

                Method compileMethod = mainClass.getMethod("compile", String[].class, PrintWriter.class, PrintWriter.class,
                        Class.forName("org.eclipse.jdt.core.compiler.CompilationProgress", false, mainClass.getClassLoader()));
                String[] argsArr = argsList.toArray(new String[0]);
                Boolean result = (Boolean) compileMethod.invoke(null, argsArr, outPw, errPw, null);

                String outStr = outSw.toString().trim();
                String errStr = errSw.toString().trim();
                if (!outStr.isEmpty()) listener.onLog("[ECJ] " + outStr);
                if (!errStr.isEmpty()) listener.onLog("[ECJ ERR] " + errStr);

                return result != null && result;
            }

            // Fallback: javac CLI jika tersedia di sistem
            listener.onLog("[JAVAC] Fallback ke perintah sistem javac...");
            List<String> cmd = new ArrayList<>();
            cmd.add("javac");
            cmd.add("-source");
            cmd.add("8");
            cmd.add("-target");
            cmd.add("8");
            cmd.add("-nowarn");
            cmd.add("-encoding");
            cmd.add("UTF-8");
            if (!cp.isEmpty()) {
                cmd.add("-cp");
                cmd.add(cp);
            }
            cmd.add("-d");
            cmd.add(objDir.getAbsolutePath());
            for (File f : javaFiles) {
                cmd.add(f.getAbsolutePath());
            }

            int code = runProcess(cmd, listener);
            return code == 0;

        } catch (Throwable e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) ? e.getCause() : e;
            String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            listener.onLog("[JAVA COMPILE ERROR] " + cause.getClass().getSimpleName() + ": " + msg);
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            listener.onLog(sw.toString());
            return false;
        }
    }

    private void initEcjResourceBundle(ClassLoader cl) {
        try {
            Class<?> rbf = cl.loadClass("org.eclipse.jdt.internal.compiler.batch.Main$ResourceBundleFactory");
            java.lang.reflect.Field cacheField = rbf.getDeclaredField("Cache");
            cacheField.setAccessible(true);
            java.util.Map cache = (java.util.Map) cacheField.get(null);
            if (cache != null) {
                InputStream is = cl.getResourceAsStream("org/eclipse/jdt/internal/compiler/batch/messages.properties");
                if (is == null && context != null) {
                    try {
                        is = context.getAssets().open("ecj/messages.properties");
                    } catch (Exception ignored) {}
                }
                if (is == null) {
                    File localProps = new File("app/src/main/assets/ecj/messages.properties");
                    if (!localProps.exists()) localProps = new File("/home/hatch/workspace/apk-compiler/app/src/main/assets/ecj/messages.properties");
                    if (localProps.exists()) {
                        try {
                            is = new FileInputStream(localProps);
                        } catch (Exception ignored) {}
                    }
                }
                if (is != null) {
                    java.util.PropertyResourceBundle bundle = new java.util.PropertyResourceBundle(is);
                    cache.put(Locale.getDefault(), bundle);
                    cache.put(Locale.US, bundle);
                    cache.put(Locale.ROOT, bundle);
                    is.close();
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean compileDex(Project project, List<File> classFiles, File dexDir,
                               File androidJar, DependencyManager.ResolutionResult depResult,
                               BuildListener listener) {
        try {
            List<String> argsList = new ArrayList<>();
            argsList.add("--min-api");
            argsList.add(String.valueOf(project.getMinSdk()));
            argsList.add("--output");
            argsList.add(dexDir.getAbsolutePath());
            if (androidJar != null && androidJar.exists()) {
                argsList.add("--lib");
                argsList.add(androidJar.getAbsolutePath());
            }

            // Tambahkan semua class file proyek
            for (File cf : classFiles) {
                argsList.add(cf.getAbsolutePath());
            }

            // Tambahkan semua jar dependensi ke D8
            if (depResult != null) {
                for (File dj : depResult.getClasspathJars()) {
                    if (dj.exists() && dj.length() > 0) {
                        argsList.add(dj.getAbsolutePath());
                    }
                }
            }

            // 1. Cek apakah D8 tersedia langsung di ClassLoader aplikasi
            Class<?> d8Class = null;
            try {
                d8Class = Class.forName("com.android.tools.r8.D8");
            } catch (ClassNotFoundException ignored) {}

            // 2. Jika tidak ada di internal, coba muat dari toolchain d8 via DexClassLoader atau URLClassLoader
            if (d8Class == null) {
                File d8Jar = toolchain.getFile("d8");
                if (d8Jar == null || !d8Jar.exists()) {
                    File sdkD8 = new File("/home/hatch/android-sdk/build-tools/34.0.0/lib/d8.jar");
                    if (sdkD8.exists()) d8Jar = sdkD8;
                }
                if (d8Jar != null && d8Jar.exists()) {
                    if (context != null) {
                        try {
                            File optDir = context.getCodeCacheDir();
                            dalvik.system.DexClassLoader dcl = new dalvik.system.DexClassLoader(
                                    d8Jar.getAbsolutePath(), optDir.getAbsolutePath(), null, context.getClassLoader());
                            d8Class = dcl.loadClass("com.android.tools.r8.D8");
                        } catch (Throwable ignored) {}
                    }
                    if (d8Class == null) {
                        try {
                            URLClassLoader ucl = new URLClassLoader(new URL[]{ d8Jar.toURI().toURL() }, getClass().getClassLoader());
                            d8Class = ucl.loadClass("com.android.tools.r8.D8");
                        } catch (Throwable ignored) {}
                    }
                }
            }

            if (d8Class != null) {
                listener.onLog("[D8] Mengonversi bytecode via in-process D8 engine...");
                Method mainMethod = d8Class.getMethod("main", String[].class);
                mainMethod.invoke(null, (Object) argsList.toArray(new String[0]));
                return new File(dexDir, "classes.dex").exists();
            }

            // Fallback CLI d8
            File cliD8 = new File("/home/hatch/android-sdk/build-tools/34.0.0/d8");
            List<String> cmd = new ArrayList<>();
            cmd.add(cliD8.exists() ? cliD8.getAbsolutePath() : "d8");
            cmd.addAll(argsList);
            int code = runProcess(cmd, listener);
            return code == 0 && new File(dexDir, "classes.dex").exists();

        } catch (Exception e) {
            listener.onLog("[D8 ERROR] " + e.getMessage());
            return false;
        }
    }

    private void packageApk(File unsignedBaseApk, File dexDir, File destApk,
                            Project project, BuildListener listener) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destApk)));
        byte[] buf = new byte[8192];

        // 1. Masukkan isi unsignedBaseApk
        if (unsignedBaseApk != null && unsignedBaseApk.exists()) {
            ZipFile baseZip = new ZipFile(unsignedBaseApk);
            Enumeration<? extends ZipEntry> entries = baseZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("classes") && entry.getName().endsWith(".dex")) continue;

                ZipEntry newEntry = new ZipEntry(entry.getName());
                if (entry.getMethod() == ZipEntry.STORED) {
                    newEntry.setMethod(ZipEntry.STORED);
                    newEntry.setSize(entry.getSize());
                    newEntry.setCompressedSize(entry.getSize());
                    newEntry.setCrc(entry.getCrc());
                }
                zos.putNextEntry(newEntry);
                try (InputStream is = baseZip.getInputStream(entry)) {
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        zos.write(buf, 0, r);
                    }
                }
                zos.closeEntry();
            }
            baseZip.close();
        }

        // 2. Masukkan semua classes*.dex (multidex support)
        File[] dexFiles = dexDir.listFiles((d, n) -> n.startsWith("classes") && n.endsWith(".dex"));
        if (dexFiles != null && dexFiles.length > 0) {
            Arrays.sort(dexFiles, (f1, f2) -> f1.getName().compareTo(f2.getName()));
            for (File df : dexFiles) {
                ZipEntry dexEntry = new ZipEntry(df.getName());
                zos.putNextEntry(dexEntry);
                try (FileInputStream dexIs = new FileInputStream(df)) {
                    int r;
                    while ((r = dexIs.read(buf)) != -1) {
                        zos.write(buf, 0, r);
                    }
                }
                zos.closeEntry();
            }
        }

        // 3. Masukkan assets jika ada (dengan validasi sandbox agar tidak membocorkan data privat)
        File assetsDir = new File(project.getRootDir(), "app/src/main/assets");
        if (!assetsDir.exists()) assetsDir = new File(project.getRootDir(), "assets");
        if (assetsDir.exists() && assetsDir.isDirectory()) {
            addFolderToZip(assetsDir, "assets", zos, buf, project.getRootDir());
        }

        zos.finish();
        zos.flush();
        zos.close();
    }

    private void addFolderToZip(File folder, String parentPath, ZipOutputStream zos, byte[] buf, File sandboxRoot) throws IOException {
        String canonicalRoot = sandboxRoot.getCanonicalPath();
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            String canonicalFile = f.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalRoot + File.separator) && !canonicalFile.equals(canonicalRoot)) {
                throw new SecurityException("Pelanggaran Sandbox: File '" + f.getName() + "' berada di luar direktori proyek dan tidak boleh dimasukkan ke APK.");
            }

            String entryPath = parentPath + "/" + f.getName();
            if (f.isDirectory()) {
                addFolderToZip(f, entryPath, zos, buf, sandboxRoot);
            } else {
                ZipEntry entry = new ZipEntry(entryPath);
                zos.putNextEntry(entry);
                FileInputStream fis = new FileInputStream(f);
                int len;
                while ((len = fis.read(buf)) != -1) {
                    zos.write(buf, 0, len);
                }
                fis.close();
                zos.closeEntry();
            }
        }
    }

    private int runProcess(List<String> cmd, BuildListener listener) {
        try {
            if (cmd == null || cmd.isEmpty()) {
                throw new IllegalArgumentException("Perintah eksekusi kosong");
            }
            for (String arg : cmd) {
                if (arg == null) {
                    throw new IllegalArgumentException("Argumen eksekusi tidak boleh null");
                }
                if (arg.equals("/bin/sh") || arg.equals("sh") || arg.equals("/bin/bash") ||
                    arg.equals("bash") || arg.equals("su")) {
                    throw new SecurityException("Eksekusi shell interpreter interaktif dilarang demi keamanan");
                }
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                listener.onLog(line);
            }
            return proc.waitFor();
        } catch (Exception e) {
            listener.onLog("[EXEC ERROR] " + e.getMessage());
            return -1;
        }
    }

    private void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteDir(f);
                }
            }
        }
        dir.delete();
    }
}
