package com.rakyatid.apkcompiler.engine;

import android.content.Context;
import com.rakyatid.apkcompiler.model.Dependency;
import com.rakyatid.apkcompiler.model.Project;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class DependencyManager {

    public interface LogListener {
        void onLog(String line);
    }

    public static class ResolutionResult {
        private final List<File> classpathJars = new ArrayList<>();
        private final List<File> aarResZips = new ArrayList<>();
        private final Set<String> extraPackages = new LinkedHashSet<>();
        private final List<Dependency> resolvedDependencies = new ArrayList<>();

        public List<File> getClasspathJars() {
            return classpathJars;
        }

        public List<File> getAarResZips() {
            return aarResZips;
        }

        public Set<String> getExtraPackages() {
            return extraPackages;
        }

        public List<Dependency> getResolvedDependencies() {
            return resolvedDependencies;
        }
    }

    public static List<Dependency> detectProjectDependencies(Project project, LogListener log) {
        List<Dependency> deps = new ArrayList<>();
        File rootDir = project.getRootDir();
        if (rootDir == null || !rootDir.exists()) return deps;

        // 1. Scan Gradle files
        File[] gradleFiles = new File[]{
                new File(rootDir, "app/build.gradle"),
                new File(rootDir, "app/build.gradle.kts"),
                new File(rootDir, "build.gradle"),
                new File(rootDir, "build.gradle.kts")
        };

        Set<String> seen = new HashSet<>();

        for (File gf : gradleFiles) {
            if (gf.exists()) {
                String content = readFileQuick(gf);
                if (content != null) {
                    Matcher m = Pattern.compile("(?:implementation|api|compile)\\s*\\(?\\s*['\"]([^:'\"\\s]+):([^:'\"\\s]+):([^:'\"\\s]+)['\"]\\s*\\)?").matcher(content);
                    while (m.find()) {
                        String g = m.group(1).trim();
                        String a = m.group(2).trim();
                        String v = m.group(3).trim();
                        String key = g + ":" + a;
                        if (!seen.contains(key)) {
                            seen.add(key);
                            deps.add(new Dependency(g, a, v, "aar"));
                            if (log != null) log.onLog("[DEP] Detected in Gradle: " + g + ":" + a + ":" + v);
                        }
                    }

                    Matcher mMap = Pattern.compile("(?:implementation|api|compile)\\s+group:\\s*['\"]([^'\"]+)['\"],\\s*name:\\s*['\"]([^'\"]+)['\"],\\s*version:\\s*['\"]([^'\"]+)['\"]").matcher(content);
                    while (mMap.find()) {
                        String g = mMap.group(1).trim();
                        String a = mMap.group(2).trim();
                        String v = mMap.group(3).trim();
                        String key = g + ":" + a;
                        if (!seen.contains(key)) {
                            seen.add(key);
                            deps.add(new Dependency(g, a, v, "aar"));
                            if (log != null) log.onLog("[DEP] Detected in Gradle: " + g + ":" + a + ":" + v);
                        }
                    }
                }
            }
        }

        // 2. Scan local libs/ directories
        File[] libDirs = new File[]{
                new File(rootDir, "libs"),
                new File(rootDir, "app/libs")
        };
        for (File ld : libDirs) {
            if (ld.exists() && ld.isDirectory()) {
                File[] files = ld.listFiles((d, n) -> n.endsWith(".jar") || n.endsWith(".aar"));
                if (files != null) {
                    for (File f : files) {
                        deps.add(new Dependency(f));
                        if (log != null) log.onLog("[DEP] Detected local library: " + f.getName());
                    }
                }
            }
        }

        // 3. AndroidX Auto-detection if not explicitly declared
        boolean hasAppCompat = false;
        for (Dependency d : deps) {
            if ("androidx.appcompat".equals(d.getGroupId()) && "appcompat".equals(d.getArtifactId())) {
                hasAppCompat = true;
                break;
            }
        }

        if (!hasAppCompat) {
            boolean needsAppCompat = false;

            // Check Manifest
            if (project.getManifestFile() != null && project.getManifestFile().exists()) {
                String manContent = readFileQuick(project.getManifestFile());
                if (manContent != null && manContent.contains("Theme.AppCompat")) {
                    needsAppCompat = true;
                }
            }

            // Check Java code imports
            if (!needsAppCompat && project.getJavaSrcDir() != null && project.getJavaSrcDir().exists()) {
                List<File> javaFiles = ProjectScanner.listSourceFiles(project.getJavaSrcDir(), ".java");
                for (File jf : javaFiles) {
                    String jc = readFileQuick(jf);
                    if (jc != null && (jc.contains("androidx.appcompat") || jc.contains("AppCompatActivity"))) {
                        needsAppCompat = true;
                        break;
                    }
                }
            }

            if (needsAppCompat) {
                deps.add(new Dependency("androidx.appcompat", "appcompat", "1.2.0", "aar"));
                if (log != null) log.onLog("[DEP AUTO] Project uses AppCompat, adding dependency androidx.appcompat:appcompat:1.2.0");
            }
        }

        return deps;
    }

    public static boolean ensureEssentialsReady(File toolchainDir, Context context, LogListener log) {
        File androidxDir = new File(toolchainDir, "androidx");
        if (androidxDir.exists() && androidxDir.isDirectory()) {
            File[] files = androidxDir.listFiles((d, n) -> n.endsWith(".aar") || n.endsWith(".jar"));
            if (files != null && files.length >= 20) {
                return true;
            }
        }
        androidxDir.mkdirs();

        InputStream assetIs = null;
        if (context != null) {
            try {
                assetIs = context.getAssets().open("androidx-essentials.zip");
            } catch (Throwable ignored) {}
        }

        if (assetIs == null) {
            File[] candidates = new File[]{
                    new File("app/src/main/assets/androidx-essentials.zip"),
                    new File("/home/hatch/workspace/apk-compiler/app/src/main/assets/androidx-essentials.zip"),
                    new File("/tmp/androidx-essentials.zip")
            };
            for (File c : candidates) {
                if (c.exists() && c.length() > 0) {
                    try {
                        assetIs = new FileInputStream(c);
                        break;
                    } catch (Throwable ignored) {}
                }
            }
        }

        if (assetIs != null) {
            if (log != null) log.onLog("[DEP] Extracting AndroidX Essentials package...");
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(assetIs))) {
                ZipEntry entry;
                byte[] buf = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    File outFile = new File(androidxDir, entry.getName());
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int r;
                        while ((r = zis.read(buf)) != -1) {
                            fos.write(buf, 0, r);
                        }
                    }
                    zis.closeEntry();
                }
                File[] list = androidxDir.listFiles();
                int count = list != null ? list.length : 0;
                if (log != null) log.onLog("[DEP] AndroidX Essentials ready (" + count + " libraries available).");
                return true;
            } catch (Throwable t) {
                if (log != null) log.onLog("[DEP ERR] Failed to extract AndroidX: " + t.getMessage());
            }
        }

        return false;
    }

    public static ResolutionResult resolve(Project project, List<Dependency> requestedDeps,
                                           File toolchainDir, File buildDir, File aapt2Binary,
                                           Context context, LogListener log) {
        ResolutionResult result = new ResolutionResult();

        ensureEssentialsReady(toolchainDir, context, log);

        File androidxDir = new File(toolchainDir, "androidx");
        File repoDir = new File(toolchainDir, "repo");
        File explodedCacheDir = new File(toolchainDir, "cache/exploded");
        repoDir.mkdirs();
        explodedCacheDir.mkdirs();

        List<File> artifactsToProcess = new ArrayList<>();
        Set<String> processedArtifactNames = new HashSet<>();

        // Check if appcompat requested
        boolean appcompatRequested = false;
        for (Dependency d : requestedDeps) {
            if (d.getGroupId() != null && d.getGroupId().contains("appcompat")) {
                appcompatRequested = true;
                break;
            }
        }

        // If AppCompat is requested, include the entire essentials pack
        if (appcompatRequested && androidxDir.exists() && androidxDir.isDirectory()) {
            File[] essentialFiles = androidxDir.listFiles((d, n) -> n.endsWith(".aar") || n.endsWith(".jar"));
            if (essentialFiles != null && essentialFiles.length > 0) {
                if (log != null) log.onLog("[DEP] Activating AndroidX / AppCompat Essentials Pack (" + essentialFiles.length + " libraries)...");
                for (File ef : essentialFiles) {
                    if (!processedArtifactNames.contains(ef.getName())) {
                        processedArtifactNames.add(ef.getName());
                        artifactsToProcess.add(ef);
                    }
                }
            }
        }

        // Resolve other requested dependencies
        for (Dependency dep : requestedDeps) {
            if (dep.getFile() != null && dep.getFile().exists()) {
                // Local file dependency
                if (!processedArtifactNames.contains(dep.getFile().getName())) {
                    processedArtifactNames.add(dep.getFile().getName());
                    artifactsToProcess.add(dep.getFile());
                }
                continue;
            }

            if ("androidx.appcompat".equals(dep.getGroupId()) && "appcompat".equals(dep.getArtifactId())) {
                continue; // Handled by essentials pack
            }

            // Look in androidxDir first
            String aarName = dep.getArtifactId() + "-" + dep.getVersion() + ".aar";
            String jarName = dep.getArtifactId() + "-" + dep.getVersion() + ".jar";
            File inEssentialsAar = new File(androidxDir, aarName);
            File inEssentialsJar = new File(androidxDir, jarName);
            if (inEssentialsAar.exists()) {
                if (!processedArtifactNames.contains(aarName)) {
                    processedArtifactNames.add(aarName);
                    artifactsToProcess.add(inEssentialsAar);
                }
                continue;
            }
            if (inEssentialsJar.exists()) {
                if (!processedArtifactNames.contains(jarName)) {
                    processedArtifactNames.add(jarName);
                    artifactsToProcess.add(inEssentialsJar);
                }
                continue;
            }

            // Look in local repoDir or download
            File targetAar = new File(repoDir, aarName);
            File targetJar = new File(repoDir, jarName);
            File resolvedFile = null;

            if (targetAar.exists() && targetAar.length() > 0) {
                resolvedFile = targetAar;
            } else if (targetJar.exists() && targetJar.length() > 0) {
                resolvedFile = targetJar;
            } else {
                if (log != null) log.onLog("[DEP] Downloading dependency: " + dep.getCoordinates() + "...");
                // Try downloading AAR first, then JAR
                boolean downloadedAar = MavenDownloader.downloadArtifact(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), "aar", targetAar, status -> {
                    if (log != null) log.onLog("[MAVEN] " + status);
                });
                if (downloadedAar) {
                    resolvedFile = targetAar;
                } else {
                    boolean downloadedJar = MavenDownloader.downloadArtifact(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), "jar", targetJar, status -> {
                        if (log != null) log.onLog("[MAVEN] " + status);
                    });
                    if (downloadedJar) {
                        resolvedFile = targetJar;
                    }
                }

                // Resolve POM for transitive dependencies if needed
                if (resolvedFile != null) {
                    File pomFile = MavenDownloader.downloadPom(dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), repoDir);
                    if (pomFile != null) {
                        MavenDownloader.PomInfo pom = MavenDownloader.parsePom(pomFile);
                        for (MavenDownloader.DependencyInfo td : pom.dependencies) {
                            String tAarName = td.artifactId + "-" + td.version + ".aar";
                            String tJarName = td.artifactId + "-" + td.version + ".jar";
                            File tTargetAar = new File(repoDir, tAarName);
                            File tTargetJar = new File(repoDir, tJarName);
                            if (tTargetAar.exists()) {
                                if (!processedArtifactNames.contains(tAarName)) {
                                    processedArtifactNames.add(tAarName);
                                    artifactsToProcess.add(tTargetAar);
                                }
                            } else if (tTargetJar.exists()) {
                                if (!processedArtifactNames.contains(tJarName)) {
                                    processedArtifactNames.add(tJarName);
                                    artifactsToProcess.add(tTargetJar);
                                }
                            } else {
                                boolean tOkAar = MavenDownloader.downloadArtifact(td.groupId, td.artifactId, td.version, td.type, tTargetAar, null);
                                if (tOkAar) {
                                    if (!processedArtifactNames.contains(tAarName)) {
                                        processedArtifactNames.add(tAarName);
                                        artifactsToProcess.add(tTargetAar);
                                    }
                                } else {
                                    boolean tOkJar = MavenDownloader.downloadArtifact(td.groupId, td.artifactId, td.version, "jar", tTargetJar, null);
                                    if (tOkJar) {
                                        if (!processedArtifactNames.contains(tJarName)) {
                                            processedArtifactNames.add(tJarName);
                                            artifactsToProcess.add(tTargetJar);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (resolvedFile != null && !processedArtifactNames.contains(resolvedFile.getName())) {
                processedArtifactNames.add(resolvedFile.getName());
                artifactsToProcess.add(resolvedFile);
            }
        }

        // Process all collected artifacts (AARs and JARs)
        for (File artifact : artifactsToProcess) {
            if (artifact.getName().endsWith(".aar")) {
                processAar(artifact, explodedCacheDir, aapt2Binary, project.getPackageName(), result, log);
            } else if (artifact.getName().endsWith(".jar")) {
                result.getClasspathJars().add(artifact);
            }
        }

        if (log != null) {
            log.onLog("[DEP RES] Successfully processed " + artifactsToProcess.size() + " artifacts.");
            log.onLog("[DEP RES] Classpath Jars: " + result.getClasspathJars().size() + ", AAR Res Zips: " + result.getAarResZips().size() + ", Extra packages: " + result.getExtraPackages().size());
        }

        return result;
    }

    private static void processAar(File aarFile, File explodedBaseDir, File aapt2Binary,
                                   String appPackage, ResolutionResult result, LogListener log) {
        String baseName = aarFile.getName().substring(0, aarFile.getName().length() - 4);
        File destDir = new File(explodedBaseDir, baseName);
        destDir.mkdirs();

        // 1. Unzip AAR contents if not yet unzipped
        File classesJar = new File(destDir, "classes.jar");
        File manifestXml = new File(destDir, "AndroidManifest.xml");
        File resFolder = new File(destDir, "res");

        if (!classesJar.exists() || !manifestXml.exists()) {
            try (ZipFile zf = new ZipFile(aarFile)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                byte[] buf = new byte[8192];
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    File out = new File(destDir, ze.getName());
                    if (ze.isDirectory()) {
                        out.mkdirs();
                    } else {
                        out.getParentFile().mkdirs();
                        try (InputStream is = zf.getInputStream(ze);
                             FileOutputStream fos = new FileOutputStream(out)) {
                            int r;
                            while ((r = is.read(buf)) != -1) {
                                fos.write(buf, 0, r);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (log != null) log.onLog("[WARN] Failed to extract AAR " + aarFile.getName() + ": " + e.getMessage());
                return;
            }
        }

        // 2. Add classes.jar
        if (classesJar.exists() && classesJar.length() > 0) {
            result.getClasspathJars().add(classesJar);
        }

        // 3. Add internal libs/*.jar
        File libsDir = new File(destDir, "libs");
        if (libsDir.isDirectory()) {
            File[] internalJars = libsDir.listFiles((d, n) -> n.endsWith(".jar"));
            if (internalJars != null) {
                for (File ij : internalJars) {
                    if (ij.length() > 0) result.getClasspathJars().add(ij);
                }
            }
        }

        // 4. Parse package name from AndroidManifest.xml
        if (manifestXml.exists()) {
            String mContent = readFileQuick(manifestXml);
            if (mContent != null) {
                Matcher m = Pattern.compile("package\\s*=\\s*[\"']([^\"']+)[\"']").matcher(mContent);
                if (m.find()) {
                    String pkg = m.group(1).trim();
                    if (!pkg.isEmpty() && (appPackage == null || !pkg.equals(appPackage))) {
                        result.getExtraPackages().add(pkg);
                    }
                }
            }
        }

        // 5. Compile res/ directory with AAPT2 if present
        if (resFolder.isDirectory() && resFolder.list() != null && resFolder.list().length > 0) {
            File resZip = new File(destDir, "compiled_res.zip");
            boolean needCompile = !resZip.exists() || resZip.length() == 0 || resZip.lastModified() < aarFile.lastModified();

            if (needCompile && aapt2Binary != null && aapt2Binary.exists()) {
                try {
                    List<String> cmd = Arrays.asList(
                            aapt2Binary.getAbsolutePath(), "compile",
                            "--dir", resFolder.getAbsolutePath(),
                            "-o", resZip.getAbsolutePath()
                    );
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    int code = p.waitFor();
                    if (code != 0) {
                        if (log != null) log.onLog("[WARN] aapt2 compile AAR res failed for " + baseName + " (code " + code + ")");
                    }
                } catch (Exception e) {
                    if (log != null) log.onLog("[WARN] Failed to execute aapt2 compile AAR res: " + e.getMessage());
                }
            }

            if (resZip.exists() && resZip.length() > 0) {
                result.getAarResZips().add(resZip);
            }
        }
    }

    private static String readFileQuick(File f) {
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 32768)];
            try (FileInputStream fis = new FileInputStream(f)) {
                int r = fis.read(buf);
                if (r > 0) return new String(buf, 0, r, "UTF-8");
            }
        } catch (Exception ignored) {}
        return null;
    }
}
