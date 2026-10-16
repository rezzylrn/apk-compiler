package com.rakyatid.apkcompiler.engine;

import android.content.Context;
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
                listener.onProgress(1, 6, "Menyiapkan direktori build...");
                listener.onLog("=== MEMULAI KOMPILASI PROYEK: " + project.getName() + " ===");
                listener.onLog("[INFO] Package: " + project.getPackageName());
                listener.onLog("[INFO] Min SDK: " + project.getMinSdk() + ", Target SDK: " + project.getTargetSdk());

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

                File androidJar = toolchain.getFile("android_jar");
                if (androidJar == null || !androidJar.exists()) {
                    // Coba cek aset atau platform default
                    File localJar = new File(context.getFilesDir(), "toolchain/android.jar");
                    if (localJar.exists()) {
                        androidJar = localJar;
                    }
                }

                // STEP 1: AAPT2 / Resource & Manifest
                listener.onProgress(2, 6, "[1/5] Memproses Resource dan Manifest...");
                listener.onLog("[1/5] Memproses Resource & Manifest...");

                File unsignedApk = new File(buildDir, "unsigned.apk");
                File aapt2Binary = toolchain.getFile("aapt2");

                boolean aapt2Success = false;
                if (aapt2Binary != null && aapt2Binary.exists() && aapt2Binary.canExecute() && androidJar != null && androidJar.exists()) {
                    aapt2Success = runAapt2(project, buildDir, genDir, unsignedApk, aapt2Binary, androidJar, listener);
                }

                if (!aapt2Success) {
                    listener.onLog("[WARN] AAPT2 biner tidak aktif/fallback: menghasilkan R.java dan base APK...");
                    generateFallbackResources(project, genDir, unsignedApk, listener);
                }

                // STEP 2: Kompilasi Java (.java -> .class)
                listener.onProgress(3, 6, "[2/5] Mengompilasi Kode Java...");
                listener.onLog("[2/5] Mengompilasi kode Java...");

                List<File> javaFiles = new ArrayList<>();
                if (project.getJavaSrcDir() != null) {
                    javaFiles.addAll(ProjectScanner.listSourceFiles(project.getJavaSrcDir(), ".java"));
                }
                javaFiles.addAll(ProjectScanner.listSourceFiles(genDir, ".java"));

                if (javaFiles.isEmpty()) {
                    throw new IOException("Tidak ada file kode sumber .java yang ditemukan.");
                }

                listener.onLog("[INFO] Ditemukan " + javaFiles.size() + " file sumber Java.");
                boolean javaSuccess = compileJavaSources(project, javaFiles, objDir, androidJar, listener);
                if (!javaSuccess) {
                    throw new IOException("Kompilasi Java gagal. Periksa error sintaks pada log.");
                }

                // STEP 3: DEX Compilation (.class -> classes.dex)
                listener.onProgress(4, 6, "[3/5] Mengonversi ke Dalvik Executable (classes.dex)...");
                listener.onLog("[3/5] Mengonversi bytecode ke classes.dex via D8...");

                List<File> classFiles = ProjectScanner.listSourceFiles(objDir, ".class");
                if (classFiles.isEmpty()) {
                    throw new IOException("File .class tidak ditemukan setelah kompilasi Java.");
                }

                boolean dexSuccess = compileDex(project, classFiles, dexDir, androidJar, listener);
                if (!dexSuccess) {
                    throw new IOException("Kompilasi D8/Dex gagal.");
                }

                File classesDex = new File(dexDir, "classes.dex");
                if (!classesDex.exists() || classesDex.length() == 0) {
                    throw new IOException("classes.dex tidak terbuat.");
                }
                listener.onLog("[SUCCESS] classes.dex berhasil dibuat (" + (classesDex.length() / 1024) + " KB).");

                // STEP 4: Package APK (Gabungkan dex + res)
                listener.onProgress(5, 6, "[4/5] Merakit paket APK...");
                listener.onLog("[4/5] Merakit paket APK...");

                File unalignedApk = new File(buildDir, "app-unaligned.apk");
                packageApk(unsignedApk, classesDex, unalignedApk, project, listener);

                File alignedApk = new File(buildDir, "app-aligned.apk");
                ZipAlignUtil.alignZip(unalignedApk, alignedApk);

                // STEP 5: Sign APK
                listener.onProgress(6, 6, "[5/5] Menandatangani APK...");
                listener.onLog("[5/5] Menandatangani APK dengan keystore debug...");

                File outputDir = new File(context.getExternalFilesDir(null), "outputs");
                outputDir.mkdirs();
                File finalApk = new File(outputDir, project.getName() + "-release.apk");

                File keystoreFile = toolchain.getFile("keystore");
                if (keystoreFile == null || !keystoreFile.exists() || keystoreFile.length() == 0) {
                    toolchain.ensureKeystoreReady();
                    keystoreFile = toolchain.getFile("keystore");
                }
                if (keystoreFile == null || !keystoreFile.exists() || keystoreFile.length() == 0) {
                    keystoreFile = new File(context.getFilesDir(), "toolchain/debug.keystore");
                }
                if (!keystoreFile.exists() || keystoreFile.length() == 0) {
                    toolchain.extractAsset("debug.keystore", keystoreFile);
                }

                String ksPass = toolchain.getKeystorePassword();
                File apksignerTool = toolchain.getFile("apksigner");
                boolean signOk = ApkSignerUtil.signApk(alignedApk, finalApk, keystoreFile,
                        ksPass, "androiddebugkey", ksPass, apksignerTool, listener::onLog);

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
                            File aapt2Binary, File androidJar, BuildListener listener) {
        try {
            File resZip = new File(buildDir, "res.zip");
            if (project.getResDir() != null && project.getResDir().exists()) {
                listener.onLog("[AAPT2] Mengompilasi resource layout...");
                List<String> cmdCompile = Arrays.asList(
                        aapt2Binary.getAbsolutePath(), "compile",
                        "--dir", project.getResDir().getAbsolutePath(),
                        "-o", resZip.getAbsolutePath()
                );
                int r1 = runProcess(cmdCompile, listener);
                if (r1 != 0) return false;
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
            if (resZip.exists()) {
                cmdLink.add(resZip.getAbsolutePath());
            }

            int r2 = runProcess(cmdLink, listener);
            return r2 == 0;
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
            String pkg = project.getPackageName() != null ? project.getPackageName() : "com.example.app";
            content = content.replaceFirst("<manifest\\b", "<manifest package=\"" + pkg + "\" ");
        }

        File linkManifest = new File(buildDir, "AndroidManifest_link.xml");
        try (FileOutputStream fos = new FileOutputStream(linkManifest)) {
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        }
        return linkManifest;
    }

    private void generateFallbackResources(Project project, File genDir, File unsignedApk, BuildListener listener) throws IOException {
        // Generate R.java dasar untuk paket aplikasi
        String pkg = project.getPackageName();
        String pkgPath = pkg.replace('.', '/');
        File pkgDir = new File(genDir, pkgPath);
        pkgDir.mkdirs();

        File rJava = new File(pkgDir, "R.java");
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n");
        sb.append("public final class R {\n");
        sb.append("    public static final class layout {\n");
        sb.append("        public static final int activity_main = 0x7f030000;\n");
        sb.append("    }\n");
        sb.append("    public static final class id {\n");
        sb.append("        public static final int main = 0x7f040000;\n");
        sb.append("    }\n");
        sb.append("    public static final class string {\n");
        sb.append("        public static final int app_name = 0x7f050000;\n");
        sb.append("    }\n");
        sb.append("}\n");

        FileOutputStream fos = new FileOutputStream(rJava);
        fos.write(sb.toString().getBytes("UTF-8"));
        fos.close();
        listener.onLog("[FALLBACK] R.java berhasil dibuat di " + rJava.getName());

        // Buat minimal unsigned APK base zip dengan AndroidManifest
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(unsignedApk));
        ZipEntry manifestEntry = new ZipEntry("AndroidManifest.xml");
        zos.putNextEntry(manifestEntry);
        FileInputStream fis = new FileInputStream(project.getManifestFile());
        byte[] buf = new byte[4096];
        int len;
        while ((len = fis.read(buf)) != -1) {
            zos.write(buf, 0, len);
        }
        fis.close();
        zos.closeEntry();
        zos.close();
    }

    private boolean compileJavaSources(Project project, List<File> javaFiles, File objDir,
                                      File androidJar, BuildListener listener) {
        try {
            String cp = androidJar != null && androidJar.exists() ? androidJar.getAbsolutePath() : "";

            // Tambahkan classpath dari libs jika ada
            File libsDir = new File(project.getRootDir(), "libs");
            if (libsDir.exists() && libsDir.isDirectory()) {
                File[] jars = libsDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    for (File j : jars) {
                        cp += (cp.isEmpty() ? "" : File.pathSeparator) + j.getAbsolutePath();
                    }
                }
            }

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

            // 2. Jika tidak ada di internal, coba muat dari toolchain ecj via DexClassLoader
            if (mainClass == null) {
                File ecjJar = toolchain.getFile("ecj");
                if (ecjJar != null && ecjJar.exists()) {
                    try {
                        File optDir = context.getCodeCacheDir();
                        dalvik.system.DexClassLoader dcl = new dalvik.system.DexClassLoader(
                                ecjJar.getAbsolutePath(), optDir.getAbsolutePath(), null, context.getClassLoader());
                        mainClass = dcl.loadClass("org.eclipse.jdt.internal.compiler.batch.Main");
                    } catch (Throwable ignored) {}
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
                if (is == null) {
                    try {
                        is = context.getAssets().open("ecj/messages.properties");
                    } catch (Exception ignored) {}
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
                               File androidJar, BuildListener listener) {
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
            for (File cf : classFiles) {
                argsList.add(cf.getAbsolutePath());
            }

            // 1. Cek apakah D8 tersedia langsung di ClassLoader aplikasi
            Class<?> d8Class = null;
            try {
                d8Class = Class.forName("com.android.tools.r8.D8");
            } catch (ClassNotFoundException ignored) {}

            // 2. Jika tidak ada di internal, coba muat dari toolchain d8 via DexClassLoader
            if (d8Class == null) {
                File d8Jar = toolchain.getFile("d8");
                if (d8Jar != null && d8Jar.exists()) {
                    try {
                        File optDir = context.getCodeCacheDir();
                        dalvik.system.DexClassLoader dcl = new dalvik.system.DexClassLoader(
                                d8Jar.getAbsolutePath(), optDir.getAbsolutePath(), null, context.getClassLoader());
                        d8Class = dcl.loadClass("com.android.tools.r8.D8");
                    } catch (Throwable ignored) {}
                }
            }

            if (d8Class != null) {
                listener.onLog("[D8] Mengonversi bytecode via in-process D8 engine...");
                Method mainMethod = d8Class.getMethod("main", String[].class);
                mainMethod.invoke(null, (Object) argsList.toArray(new String[0]));
                return new File(dexDir, "classes.dex").exists();
            }

            // Fallback CLI d8
            List<String> cmd = new ArrayList<>();
            cmd.add("d8");
            cmd.addAll(argsList);
            int code = runProcess(cmd, listener);
            return code == 0 && new File(dexDir, "classes.dex").exists();

        } catch (Exception e) {
            listener.onLog("[D8 ERROR] " + e.getMessage());
            return false;
        }
    }

    private void packageApk(File unsignedBaseApk, File classesDex, File destApk,
                            Project project, BuildListener listener) throws IOException {
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destApk)));
        byte[] buf = new byte[8192];

        // 1. Masukkan isi unsignedBaseApk
        if (unsignedBaseApk != null && unsignedBaseApk.exists()) {
            ZipFile baseZip = new ZipFile(unsignedBaseApk);
            Enumeration<? extends ZipEntry> entries = baseZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("classes.dex")) continue;

                ZipEntry newEntry = new ZipEntry(entry.getName());
                zos.putNextEntry(newEntry);
                InputStream is = baseZip.getInputStream(entry);
                int r;
                while ((r = is.read(buf)) != -1) {
                    zos.write(buf, 0, r);
                }
                is.close();
                zos.closeEntry();
            }
            baseZip.close();
        }

        // 2. Masukkan classes.dex
        ZipEntry dexEntry = new ZipEntry("classes.dex");
        zos.putNextEntry(dexEntry);
        FileInputStream dexIs = new FileInputStream(classesDex);
        int r;
        while ((r = dexIs.read(buf)) != -1) {
            zos.write(buf, 0, r);
        }
        dexIs.close();
        zos.closeEntry();

        // 3. Masukkan assets jika ada
        File assetsDir = new File(project.getRootDir(), "app/src/main/assets");
        if (!assetsDir.exists()) assetsDir = new File(project.getRootDir(), "assets");
        if (assetsDir.exists() && assetsDir.isDirectory()) {
            addFolderToZip(assetsDir, "assets", zos, buf);
        }

        zos.finish();
        zos.flush();
        zos.close();
    }

    private void addFolderToZip(File folder, String parentPath, ZipOutputStream zos, byte[] buf) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            String entryPath = parentPath + "/" + f.getName();
            if (f.isDirectory()) {
                addFolderToZip(f, entryPath, zos, buf);
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
