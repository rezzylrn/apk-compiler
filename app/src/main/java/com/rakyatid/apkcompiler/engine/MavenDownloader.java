package com.rakyatid.apkcompiler.engine;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class MavenDownloader {

    private static final String[] MAVEN_REPOS = {
            "https://dl.google.com/dl/android/maven2/",
            "https://repo1.maven.org/maven2/"
    };

    public interface DownloadCallback {
        void onProgress(String status);
    }

    public static class DependencyInfo {
        public String groupId;
        public String artifactId;
        public String version;
        public String type = "jar";
        public String scope = "compile";

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version + "@" + type;
        }
    }

    public static class PomInfo {
        public String packaging = "jar";
        public Map<String, String> properties = new HashMap<>();
        public List<DependencyInfo> dependencies = new ArrayList<>();
    }

    public static boolean downloadArtifact(String groupId, String artifactId, String version,
                                           String type, File destFile, DownloadCallback callback) {
        if (destFile.exists() && destFile.length() > 0) {
            return true;
        }

        String groupPath = groupId.replace('.', '/');
        String fileName = artifactId + "-" + version + "." + type;

        for (String repo : MAVEN_REPOS) {
            String url = repo + groupPath + "/" + artifactId + "/" + version + "/" + fileName;
            if (callback != null) {
                callback.onProgress("Mengunduh " + fileName + " dari " + repo);
            }
            boolean ok = downloadFileWithFallback(url, destFile);
            if (ok && destFile.exists() && destFile.length() > 0) {
                return true;
            }
        }
        return false;
    }

    public static File downloadPom(String groupId, String artifactId, String version, File cacheDir) {
        cacheDir.mkdirs();
        String groupPath = groupId.replace('.', '/');
        String fileName = artifactId + "-" + version + ".pom";
        File pomFile = new File(cacheDir, fileName);
        if (pomFile.exists() && pomFile.length() > 0) {
            return pomFile;
        }

        for (String repo : MAVEN_REPOS) {
            String url = repo + groupPath + "/" + artifactId + "/" + version + "/" + fileName;
            boolean ok = downloadFileWithFallback(url, pomFile);
            if (ok && pomFile.exists() && pomFile.length() > 0) {
                return pomFile;
            }
        }
        return null;
    }

    public static PomInfo parsePom(File pomFile) {
        PomInfo info = new PomInfo();
        if (pomFile == null || !pomFile.exists()) return info;

        String content = readFileContent(pomFile);
        if (content == null || content.isEmpty()) return info;

        // 1. Packaging
        Matcher mPkg = Pattern.compile("<packaging\\b[^>]*>(.*?)</packaging>").matcher(content);
        if (mPkg.find()) {
            info.packaging = mPkg.group(1).trim();
        }

        // 2. Properties
        Matcher mProps = Pattern.compile("<properties\\b[^>]*>(.*?)</properties>", Pattern.DOTALL).matcher(content);
        if (mProps.find()) {
            String propsXml = mProps.group(1);
            Matcher mItem = Pattern.compile("<([a-zA-Z0-9_.-]+)>(.*?)</\\1>").matcher(propsXml);
            while (mItem.find()) {
                info.properties.put(mItem.group(1).trim(), mItem.group(2).trim());
            }
        }

        // 3. Dependencies
        Matcher mDeps = Pattern.compile("<dependencies\\b[^>]*>(.*?)</dependencies>", Pattern.DOTALL).matcher(content);
        if (mDeps.find()) {
            String depsXml = mDeps.group(1);
            Matcher mSingleDep = Pattern.compile("<dependency\\b[^>]*>(.*?)</dependency>", Pattern.DOTALL).matcher(depsXml);
            while (mSingleDep.find()) {
                String depXml = mSingleDep.group(1);
                DependencyInfo di = parseSingleDependency(depXml, info.properties);
                if (di != null && !"test".equalsIgnoreCase(di.scope) && !"provided".equalsIgnoreCase(di.scope)) {
                    info.dependencies.add(di);
                }
            }
        }

        return info;
    }

    private static DependencyInfo parseSingleDependency(String xml, Map<String, String> properties) {
        String gid = extractTag(xml, "groupId");
        String aid = extractTag(xml, "artifactId");
        String ver = extractTag(xml, "version");
        String typ = extractTag(xml, "type");
        String scp = extractTag(xml, "scope");

        if (gid == null || aid == null) return null;

        DependencyInfo di = new DependencyInfo();
        di.groupId = resolveProperty(gid, properties);
        di.artifactId = resolveProperty(aid, properties);
        di.version = resolveProperty(ver != null ? ver : "1.0.0", properties);
        di.type = typ != null ? resolveProperty(typ, properties).toLowerCase() : "jar";
        di.scope = scp != null ? resolveProperty(scp, properties).toLowerCase() : "compile";

        // Auto-detect AndroidX AAR
        if (typ == null && (di.groupId.startsWith("androidx.") || di.groupId.startsWith("com.google.android."))) {
            if (!di.artifactId.endsWith("-common") && !di.artifactId.equals("annotation") && !di.artifactId.equals("collection")) {
                di.type = "aar";
            }
        }

        return di;
    }

    private static String extractTag(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*>(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    private static String resolveProperty(String val, Map<String, String> properties) {
        if (val == null) return null;
        if (val.startsWith("${") && val.endsWith("}")) {
            String key = val.substring(2, val.length() - 1).trim();
            if (properties.containsKey(key)) {
                return properties.get(key);
            }
        }
        return val;
    }

    public static boolean downloadFileWithFallback(String urlStr, File destFile) {
        destFile.getParentFile().mkdirs();
        File tmp = new File(destFile.getAbsolutePath() + ".part");
        if (tmp.exists()) tmp.delete();

        // 1. Try Java HttpURLConnection (native on Android ART via OkHttp)
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "ApkCompiler-Android/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code == 200) {
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                    fos.flush();
                }
                if (tmp.exists() && tmp.length() > 0) {
                    if (destFile.exists()) destFile.delete();
                    return tmp.renameTo(destFile);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (tmp.exists()) tmp.delete();
        }

        // 2. Fallback: curl CLI (reliable in Linux container/proxy environments)
        try {
            ProcessBuilder pb = new ProcessBuilder("curl", "-sSL", urlStr, "-o", tmp.getAbsolutePath());
            int code = pb.start().waitFor();
            if (code == 0 && tmp.exists() && tmp.length() > 0) {
                if (destFile.exists()) destFile.delete();
                return tmp.renameTo(destFile);
            }
        } catch (Throwable ignored) {
        } finally {
            if (tmp.exists()) tmp.delete();
        }

        return false;
    }

    private static String readFileContent(File f) {
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 65536)];
            try (FileInputStream fis = new FileInputStream(f)) {
                int r = fis.read(buf);
                if (r > 0) return new String(buf, 0, r, "UTF-8");
            }
        } catch (Exception ignored) {}
        return null;
    }
}
