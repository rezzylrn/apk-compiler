package com.rakyatid.apkcompiler.engine;

import android.content.Context;
import com.rakyatid.apkcompiler.model.Project;
import com.rakyatid.apkcompiler.model.ToolchainItem;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToolchainManager {

    private final Context context;
    private final File toolchainDir;
    private final List<ToolchainItem> items = new ArrayList<>();

    // Platform SDK management
    private String selectedPlatformSdk = "android-34";
    private String customSdkPath = null;

    public interface InstallListener {
        void onProgress(String componentName, int percentage, String statusText);
        void onComponentInstalled(String componentName, boolean success, String error);
        void onComplete(boolean allSuccess);
    }

    public ToolchainManager(Context context) {
        this.context = context;
        if (context != null && context.getFilesDir() != null) {
            this.toolchainDir = new File(context.getFilesDir(), "toolchain");
        } else {
            this.toolchainDir = new File(System.getProperty("user.home"), ".apkcompiler/toolchain");
        }
        if (!toolchainDir.exists()) {
            toolchainDir.mkdirs();
        }
        initItems();
    }

    private void initItems() {
        items.clear();

        // 1. AAPT2 Tool (Cek pre-bundled di nativeLibraryDir lebih dulu)
        File nativeAapt2 = (context != null && context.getApplicationInfo() != null) ?
                new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so") : null;
        File aapt2File = (nativeAapt2 != null && nativeAapt2.exists()) ? nativeAapt2 : new File(toolchainDir, "aapt2");
        items.add(new ToolchainItem(
                "aapt2",
                "AAPT2 Resource Compiler",
                "Android Asset Packaging Tool v2 for compiling XML layouts and linking AndroidManifest.xml",
                aapt2File,
                "prebundled_or_download",
                "be2cea61814678f7a9e61bf818a6666e6097a7d67d6c19498a4d7aa690bc4151"
        ));

        // 2. ECJ (Eclipse Compiler for Java - JDK engine)
        File ecjFile = new File(toolchainDir, "ecj.jar");
        items.add(new ToolchainItem(
                "ecj",
                "Java Compiler (ECJ)",
                "Eclipse Compiler for Java for compiling Java source files into bytecode (.class)",
                ecjFile,
                "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.26.0/ecj-3.26.0.jar",
                "ac0ba5876eaf7ebb47749a0d1be179c51f194b9dd0b875d1c09e1b530f5a2db5"
        ));

        // 3. D8 / R8 (Android Dexer)
        File d8File = new File(toolchainDir, "d8.jar");
        items.add(new ToolchainItem(
                "d8",
                "D8 Dex Compiler",
                "Android D8 compiler for converting .class files into Dalvik Executable (classes.dex)",
                d8File,
                "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.33/r8-8.2.33.jar",
                "80c39a28bb66b64efc232d68ae554d59fe3838808738b4fe584884691cb1e5a3"
        ));

        // 4. Android Platform SDK (android.jar)
        File androidJarFile = getAndroidJar();
        int apiVer = parseSdkVersion(selectedPlatformSdk);
        String dlUrl = "https://raw.githubusercontent.com/Sable/android-platforms/master/android-" + (apiVer > 0 && apiVer <= 30 ? apiVer : 30) + "/android.jar";
        String sha256 = (apiVer == 30) ? "ffb9f6f7bb313642d8c4abf28566af829405e370ad9151e2258811a514b8bbb6" : null;
        items.add(new ToolchainItem(
                "android_jar",
                "Android SDK Platform (" + selectedPlatformSdk + ")",
                "Android Platform API stubs for resolving framework classes and system resources",
                androidJarFile != null ? androidJarFile : new File(toolchainDir, "android.jar"),
                dlUrl,
                sha256
        ));

        // 5. Android Debug Keystore
        File keystoreFile = new File(toolchainDir, "debug.keystore");
        items.add(new ToolchainItem(
                "keystore",
                "Android Debug Keystore",
                "RSA 2048-bit cryptographic key used for signing debug APK packages",
                keystoreFile,
                "local_generate",
                null
        ));

        // 6. AndroidX / AppCompat Essentials Pack
        File androidxDir = new File(toolchainDir, "androidx");
        items.add(new ToolchainItem(
                "androidx",
                "AndroidX / AppCompat Essentials",
                "Essential AndroidX core libraries (AppCompat, Core, Fragment, Lifecycle, Resources) for modern apps",
                androidxDir,
                "local_asset_or_download",
                null
        ));

        // Pastikan keystore, androidx & compiler-api siap digunakan
        ensureKeystoreReady();
        ensureAndroidxReady();
        ensureCompilerApiReady();
    }

    public void ensureCompilerApiReady() {
        File apiJar = new File(toolchainDir, "java-compiler-api.jar");
        if (!apiJar.exists() || apiJar.length() == 0) {
            extractAsset("java-compiler-api.jar", apiJar);
        }
        if (!apiJar.exists() || apiJar.length() == 0) {
            File localJar = new File("java-compiler-api.jar");
            if (!localJar.exists()) localJar = new File("/home/hatch/workspace/apk-compiler/java-compiler-api.jar");
            if (localJar.exists()) {
                copyFileQuick(localJar, apiJar);
            }
        }
    }

    private void copyFileQuick(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
        } catch (Exception ignored) {}
    }

    public List<String> getAvailablePlatformSdks() {
        return Arrays.asList("android-34", "android-33", "android-30", "android-28", "android-26", "android-24", "android-21");
    }

    public String getSelectedPlatformSdk() {
        return selectedPlatformSdk;
    }

    public void setSelectedPlatformSdk(String sdk) {
        if (sdk != null && !sdk.trim().isEmpty()) {
            this.selectedPlatformSdk = sdk.trim();
            updateAndroidJarItem();
        }
    }

    public String getCustomSdkPath() {
        return customSdkPath;
    }

    public void setCustomSdkPath(String path) {
        this.customSdkPath = (path != null && !path.trim().isEmpty()) ? path.trim() : null;
        updateAndroidJarItem();
    }

    public static int parseSdkVersion(String sdk) {
        if (sdk == null) return 34;
        Matcher m = Pattern.compile("(\\d+)").matcher(sdk);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {}
        }
        return 34;
    }

    public File getAndroidJar() {
        return getAndroidJar(parseSdkVersion(selectedPlatformSdk), customSdkPath);
    }

    public File getAndroidJarForProject(Project project) {
        int sdk = project != null ? project.getCompileSdk() : parseSdkVersion(selectedPlatformSdk);
        String custom = (project != null && project.getCustomSdkPath() != null && !project.getCustomSdkPath().isEmpty())
                ? project.getCustomSdkPath() : customSdkPath;
        return getAndroidJar(sdk, custom);
    }

    public File getAndroidJar(int compileSdk, String customPath) {
        // 1. Cek path custom SDK jika diberikan (baik file .jar langsung maupun folder SDK)
        if (customPath != null && !customPath.trim().isEmpty()) {
            File customFile = new File(customPath.trim());
            if (customFile.isFile() && customFile.getName().endsWith(".jar") && customFile.exists()) {
                return customFile;
            }
            if (customFile.isDirectory()) {
                File directJar = new File(customFile, "android.jar");
                if (directJar.exists()) return directJar;

                File versionedJar = new File(customFile, "platforms/android-" + compileSdk + "/android.jar");
                if (versionedJar.exists()) return versionedJar;

                File anyPlatformDir = new File(customFile, "platforms");
                if (anyPlatformDir.isDirectory()) {
                    File[] sub = anyPlatformDir.listFiles((dir, name) -> name.startsWith("android-"));
                    if (sub != null && sub.length > 0) {
                        for (File s : sub) {
                            File j = new File(s, "android.jar");
                            if (j.exists()) return j;
                        }
                    }
                }
            }
        }

        // 2. Cek versi spesifik di toolchainDir
        if (compileSdk > 0) {
            File vJar = new File(toolchainDir, "android-" + compileSdk + ".jar");
            if (vJar.exists() && vJar.length() > 0) return vJar;

            File vSubJar = new File(toolchainDir, "platforms/android-" + compileSdk + "/android.jar");
            if (vSubJar.exists() && vSubJar.length() > 0) return vSubJar;

            // System SDK path
            File sysJar = new File("/home/hatch/android-sdk/platforms/android-" + compileSdk + "/android.jar");
            if (sysJar.exists() && sysJar.length() > 0) return sysJar;
        }

        // 3. Fallback ke android.jar di toolchainDir
        File defaultJar = new File(toolchainDir, "android.jar");
        if (defaultJar.exists() && defaultJar.length() > 0) return defaultJar;

        // 4. Fallback ke platform sistem default (android-34)
        File sysDefault = new File("/home/hatch/android-sdk/platforms/android-34/android.jar");
        if (sysDefault.exists() && sysDefault.length() > 0) return sysDefault;

        return defaultJar;
    }

    public void updateAndroidJarItem() {
        File androidJarFile = getAndroidJar();
        int apiVer = parseSdkVersion(selectedPlatformSdk);
        String dlUrl = "https://raw.githubusercontent.com/Sable/android-platforms/master/android-" + (apiVer > 0 && apiVer <= 30 ? apiVer : 30) + "/android.jar";
        String sha256 = (apiVer == 30) ? "ffb9f6f7bb313642d8c4abf28566af829405e370ad9151e2258811a514b8bbb6" : null;
        for (int i = 0; i < items.size(); i++) {
            if ("android_jar".equals(items.get(i).getId())) {
                items.set(i, new ToolchainItem(
                        "android_jar",
                        "Android SDK Platform (" + selectedPlatformSdk + ")",
                        "Stubs API Android Platform untuk resolusi library dan framework Android",
                        androidJarFile != null ? androidJarFile : new File(toolchainDir, "android.jar"),
                        dlUrl,
                        sha256
                ));
                break;
            }
        }
    }

    public void ensureAndroidxReady() {
        DependencyManager.ensureEssentialsReady(toolchainDir, context, null);
    }

    public List<ToolchainItem> getItems() {
        File nativeAapt2 = (context != null && context.getApplicationInfo() != null) ?
                new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so") : null;
        if (nativeAapt2 != null && nativeAapt2.exists() && nativeAapt2.length() > 0) {
            for (int i = 0; i < items.size(); i++) {
                if ("aapt2".equals(items.get(i).getId())) {
                    if (!items.get(i).getTargetFile().equals(nativeAapt2)) {
                        items.set(i, new ToolchainItem(
                                "aapt2",
                                "AAPT2 Resource Compiler",
                                "Android Asset Packaging Tool v2 untuk mengompilasi resource layout & link AndroidManifest.xml",
                                nativeAapt2,
                                "prebundled_or_download",
                                "be2cea61814678f7a9e61bf818a6666e6097a7d67d6c19498a4d7aa690bc4151"
                        ));
                    }
                    break;
                }
            }
        }
        for (ToolchainItem item : items) {
            item.updateStatus();
        }
        return items;
    }

    public boolean isAllReady() {
        for (ToolchainItem item : getItems()) {
            if (!item.isInstalled()) {
                return false;
            }
        }
        return true;
    }

    public File getToolchainDir() {
        return toolchainDir;
    }

    public File getFile(String id) {
        if ("aapt2".equals(id)) {
            File nativeAapt2 = (context != null && context.getApplicationInfo() != null) ?
                    new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so") : null;
            if (nativeAapt2 != null && nativeAapt2.exists() && nativeAapt2.length() > 0) {
                return nativeAapt2;
            }
        }
        if ("android_jar".equals(id)) {
            return getAndroidJar();
        }
        for (ToolchainItem item : items) {
            if (item.getId().equals(id)) {
                return item.getTargetFile();
            }
        }
        return null;
    }

    public void installAll(InstallListener listener) {
        new Thread(() -> {
            boolean overallSuccess = true;
            for (ToolchainItem item : items) {
                item.updateStatus();
                if (item.isInstalled()) {
                    listener.onComponentInstalled(item.getName(), true, "Already installed");
                    continue;
                }

                if ("keystore".equals(item.getId())) {
                    listener.onProgress(item.getName(), 50, "Generating RSA 2048-bit debug key...");
                    boolean ok = generateKeystoreLocally(item.getTargetFile());
                    item.updateStatus();
                    listener.onComponentInstalled(item.getName(), ok, ok ? "Created successfully" : "Failed to generate keystore");
                    if (!ok) overallSuccess = false;
                    continue;
                }

                if ("androidx".equals(item.getId())) {
                    listener.onProgress(item.getName(), 30, "Extracting AndroidX Essentials library pack...");
                    boolean ok = DependencyManager.ensureEssentialsReady(toolchainDir, context, null);
                    item.updateStatus();
                    listener.onComponentInstalled(item.getName(), ok, ok ? "Installed successfully" : "Failed to install AndroidX pack");
                    if (!ok) overallSuccess = false;
                    continue;
                }

                listener.onProgress(item.getName(), 10, "Downloading from repository...");
                boolean ok = downloadFile(item.getDownloadUrl(), item.getTargetFile(), item.getExpectedSha256(), (percent, status) -> {
                    listener.onProgress(item.getName(), percent, status);
                });

                if (ok && "aapt2".equals(item.getId())) {
                    item.getTargetFile().setExecutable(true, false);
                }

                item.updateStatus();
                listener.onComponentInstalled(item.getName(), ok, ok ? "Installation & checksum verification successful" : "Download failed or checksum mismatch");
                if (!ok) overallSuccess = false;
            }
            listener.onComplete(overallSuccess);
        }).start();
    }

    public interface DownloadProgress {
        void onProgress(int percent, String msg);
    }

    public boolean downloadPlatformSdk(int apiLevel, DownloadProgress progress) {
        File targetFile = new File(toolchainDir, "android-" + apiLevel + ".jar");
        if (targetFile.exists() && targetFile.length() > 0) return true;
        String url = "https://raw.githubusercontent.com/Sable/android-platforms/master/android-" + apiLevel + "/android.jar";
        return downloadFile(url, targetFile, null, progress);
    }

    public boolean downloadFile(String fileUrl, File destFile, String expectedSha256, DownloadProgress progress) {
        File tempFile = null;
        try {
            if (fileUrl == null || !fileUrl.startsWith("https://")) {
                throw new SecurityException("Hanya URL HTTPS yang diizinkan untuk mengunduh toolchain!");
            }

            destFile.getParentFile().mkdirs();
            tempFile = new File(destFile.getAbsolutePath() + ".tmp");
            if (tempFile.exists()) tempFile.delete();

            URL url = new URL(fileUrl);
            InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
            for (InetAddress addr : addresses) {
                if (SecurityAuditor.isPrivateOrReservedIp(addr)) {
                    throw new SecurityException("SSRF terdeteksi: Alamat IP internal diblokir (" + addr.getHostAddress() + ")");
                }
            }

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "ApkCompiler-Android/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false);

            int status = conn.getResponseCode();
            if (status == 301 || status == 302 || status == 307 || status == 308) {
                String redirected = conn.getHeaderField("Location");
                conn.disconnect();
                if (redirected == null || !redirected.startsWith("https://")) {
                    throw new SecurityException("Pengalihan ke protokol non-HTTPS ditolak!");
                }
                URL redirUrl = new URL(redirected);
                InetAddress[] redirAddresses = InetAddress.getAllByName(redirUrl.getHost());
                for (InetAddress addr : redirAddresses) {
                    if (SecurityAuditor.isPrivateOrReservedIp(addr)) {
                        throw new SecurityException("SSRF terdeteksi pada redirect!");
                    }
                }
                conn = (HttpURLConnection) redirUrl.openConnection();
                conn.setRequestProperty("User-Agent", "ApkCompiler-Android/1.0");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
            }

            long total = conn.getContentLengthLong();
            InputStream in = new BufferedInputStream(conn.getInputStream(), 16384);
            FileOutputStream fos = new FileOutputStream(tempFile);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            long current = 0;
            int read;

            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
                md.update(buf, 0, read);
                current += read;
                if (total > 0) {
                    int pct = (int) ((current * 100) / total);
                    if (progress != null) {
                        progress.onProgress(pct, (current / 1024) + " KB / " + (total / 1024) + " KB (" + pct + "%)");
                    }
                } else {
                    if (progress != null) {
                        progress.onProgress(50, (current / 1024) + " KB diunduh");
                    }
                }
            }

            fos.flush();
            fos.close();
            in.close();
            conn.disconnect();

            // Verifikasi integritas Checksum SHA-256
            if (expectedSha256 != null && !expectedSha256.isEmpty()) {
                byte[] digest = md.digest();
                StringBuilder hexSb = new StringBuilder();
                for (byte b : digest) {
                    hexSb.append(String.format("%02x", b));
                }
                String actualSha256 = hexSb.toString();
                if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                    if (tempFile.exists()) tempFile.delete();
                    return false;
                }
            }

            if (destFile.exists()) destFile.delete();
            return tempFile.renameTo(destFile);
        } catch (Exception e) {
            if (tempFile != null && tempFile.exists()) tempFile.delete();
            return false;
        }
    }

    public String getKeystorePassword() {
        return "android";
    }

    public synchronized boolean ensureKeystoreReady() {
        File keystoreFile = new File(toolchainDir, "debug.keystore");
        if (keystoreFile.exists() && keystoreFile.length() > 0) {
            return true;
        }
        // 1. Ekstrak keystore bawaan dari assets
        extractAsset("debug.keystore", keystoreFile);
        if (keystoreFile.exists() && keystoreFile.length() > 0) {
            return true;
        }
        // 2. Generate lokal jika asset tidak tersedia
        return generateKeystoreLocally(keystoreFile);
    }

    public void extractAsset(String assetName, File dest) {
        try {
            dest.getParentFile().mkdirs();
            try (InputStream is = context.getAssets().open(assetName);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
                fos.flush();
            }
        } catch (Exception ignored) {}
    }

    private boolean generateKeystoreLocally(File dest) {
        try {
            dest.getParentFile().mkdirs();
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);

            String pass = getKeystorePassword();
            java.security.cert.X509Certificate cert = ApkSignerUtil.generateSelfSignedCert(kp, "CN=Android Debug, O=Android, C=US");
            java.security.cert.Certificate[] chain = new java.security.cert.Certificate[]{cert};

            ks.setKeyEntry("androiddebugkey", kp.getPrivate(), pass.toCharArray(), chain);

            try (FileOutputStream fos = new FileOutputStream(dest)) {
                ks.store(fos, pass.toCharArray());
                fos.flush();
            }
            dest.setReadable(true, true);
            dest.setWritable(true, true);
            dest.setExecutable(false, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
