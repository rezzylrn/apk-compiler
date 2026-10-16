package com.rakyatid.apkcompiler.engine;

import com.rakyatid.apkcompiler.model.Project;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProjectScanner {

    public static Project scan(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return null;
        }

        File manifest = findManifest(dir);
        if (manifest == null) {
            return new Project(dir.getName(), dir, null, null, null, null, 21, 34, 0, 0, false);
        }

        File javaSrc = findJavaDir(manifest.getParentFile(), dir);
        File resDir = findResDir(manifest.getParentFile(), dir);

        String pkg = "com.example.app";
        int minSdk = 21;
        int targetSdk = 34;

        try {
            byte[] bytes = new byte[(int) manifest.length()];
            FileInputStream fis = new FileInputStream(manifest);
            int totalRead = 0;
            while (totalRead < bytes.length) {
                int read = fis.read(bytes, totalRead, bytes.length - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            fis.close();
            String content = new String(bytes, 0, totalRead, "UTF-8");

            Matcher mPkg = Pattern.compile("package\\s*=\\s*\"([^\"]+)\"").matcher(content);
            if (mPkg.find()) {
                pkg = mPkg.group(1);
            } else {
                // Cari namespace / applicationId di build.gradle
                String gradlePkg = findPackageInGradle(dir);
                if (gradlePkg != null) {
                    pkg = gradlePkg;
                } else if (javaSrc != null) {
                    // Cari package statement di file .java pertama
                    String javaPkg = findPackageInJavaSources(javaSrc);
                    if (javaPkg != null) {
                        pkg = javaPkg;
                    }
                }
            }

            Matcher mMin = Pattern.compile("minSdkVersion\\s*=\\s*\"(\\d+)\"").matcher(content);
            if (mMin.find()) {
                try {
                    minSdk = Integer.parseInt(mMin.group(1));
                } catch (Exception ignored) {}
            }

            Matcher mTgt = Pattern.compile("targetSdkVersion\\s*=\\s*\"(\\d+)\"").matcher(content);
            if (mTgt.find()) {
                try {
                    targetSdk = Integer.parseInt(mTgt.group(1));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        int javaCount = javaSrc != null ? countFiles(javaSrc, ".java") : 0;
        int resCount = resDir != null ? countAllFiles(resDir) : 0;

        Project p = new Project(dir.getName(), dir, manifest, javaSrc, resDir, pkg, minSdk, targetSdk, javaCount, resCount, true);
        try {
            p.setDependencies(DependencyManager.detectProjectDependencies(p, null));
        } catch (Throwable ignored) {}
        return p;
    }

    private static File findManifest(File root) {
        // 1. app/src/main/AndroidManifest.xml
        File f1 = new File(root, "app/src/main/AndroidManifest.xml");
        if (f1.exists()) return f1;

        // 2. src/main/AndroidManifest.xml
        File f2 = new File(root, "src/main/AndroidManifest.xml");
        if (f2.exists()) return f2;

        // 3. AndroidManifest.xml (flat)
        File f3 = new File(root, "AndroidManifest.xml");
        if (f3.exists()) return f3;

        // 4. Recursive search 2 level
        File[] sub = root.listFiles();
        if (sub != null) {
            for (File s : sub) {
                if (s.isDirectory()) {
                    File m1 = new File(s, "src/main/AndroidManifest.xml");
                    if (m1.exists()) return m1;
                    File m2 = new File(s, "AndroidManifest.xml");
                    if (m2.exists()) return m2;
                }
            }
        }
        return null;
    }

    private static File findJavaDir(File manifestParent, File root) {
        if (manifestParent != null) {
            File j1 = new File(manifestParent, "java");
            if (j1.exists() && j1.isDirectory()) return j1;
            File s1 = new File(manifestParent, "src");
            if (s1.exists() && s1.isDirectory()) return s1;
        }

        File j2 = new File(root, "app/src/main/java");
        if (j2.exists()) return j2;
        File j3 = new File(root, "src/main/java");
        if (j3.exists()) return j3;
        File j4 = new File(root, "src");
        if (j4.exists()) return j4;
        File j5 = new File(root, "java");
        if (j5.exists()) return j5;

        return null;
    }

    private static File findResDir(File manifestParent, File root) {
        if (manifestParent != null) {
            File r1 = new File(manifestParent, "res");
            if (r1.exists() && r1.isDirectory()) return r1;
        }
        File r2 = new File(root, "app/src/main/res");
        if (r2.exists()) return r2;
        File r3 = new File(root, "src/main/res");
        if (r3.exists()) return r3;
        File r4 = new File(root, "res");
        if (r4.exists()) return r4;

        return null;
    }

    public static int countFiles(File dir, String extension) {
        if (dir == null || !dir.exists()) return 0;
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    count += countFiles(f, extension);
                } else if (f.getName().endsWith(extension)) {
                    count++;
                }
            }
        }
        return count;
    }

    public static int countAllFiles(File dir) {
        if (dir == null || !dir.exists()) return 0;
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    count += countAllFiles(f);
                } else {
                    count++;
                }
            }
        }
        return count;
    }

    public static List<File> listSourceFiles(File dir, String extension) {
        List<File> result = new ArrayList<>();
        collectFiles(dir, extension, result);
        return result;
    }

    private static String findPackageInGradle(File rootDir) {
        File[] candidates = new File[]{
                new File(rootDir, "app/build.gradle"),
                new File(rootDir, "app/build.gradle.kts"),
                new File(rootDir, "build.gradle"),
                new File(rootDir, "build.gradle.kts")
        };
        for (File f : candidates) {
            if (f.exists()) {
                String c = readFileQuick(f);
                if (c != null) {
                    Matcher m = Pattern.compile("(?:namespace|applicationId)\\s*=?\\s*[\"']([a-zA-Z0-9_.]+)[\"']").matcher(c);
                    if (m.find()) {
                        return m.group(1);
                    }
                }
            }
        }
        return null;
    }

    private static String findPackageInJavaSources(File javaSrcDir) {
        List<File> javaFiles = listSourceFiles(javaSrcDir, ".java");
        for (File f : javaFiles) {
            String c = readFileQuick(f);
            if (c != null) {
                Matcher m = Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE).matcher(c);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    private static String readFileQuick(File f) {
        try {
            int len = (int) Math.min(f.length(), 32768);
            byte[] buf = new byte[len];
            try (FileInputStream fis = new FileInputStream(f)) {
                int r = fis.read(buf);
                if (r > 0) return new String(buf, 0, r, "UTF-8");
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void collectFiles(File dir, String extension, List<File> out) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    collectFiles(f, extension, out);
                } else if (f.getName().endsWith(extension)) {
                    out.add(f);
                }
            }
        }
    }
}
