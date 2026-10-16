package com.rakyatid.apkcompiler.engine;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GitRepoCloner {

    public interface CloneListener {
        void onProgress(String message, int percentage);
        void onSuccess(File projectDir);
        void onError(String error);
    }

    public static void cloneGitHub(String repoUrl, String branchOverride, File outputDir, CloneListener listener) {
        new Thread(() -> {
            try {
                String cleanUrl = repoUrl.trim();
                if (cleanUrl.endsWith(".git")) {
                    cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 4);
                }
                if (cleanUrl.startsWith("http://")) {
                    cleanUrl = "https://" + cleanUrl.substring(7);
                } else if (!cleanUrl.startsWith("https://")) {
                    cleanUrl = "https://github.com/" + cleanUrl;
                }

                // Parse owner and repo
                String path = cleanUrl.replace("https://github.com/", "");
                String[] parts = path.split("/");
                if (parts.length < 2) {
                    listener.onError("Format URL GitHub tidak valid. Contoh: https://github.com/owner/repo");
                    return;
                }

                String owner = parts[0];
                String repo = parts[1];
                String branch = branchOverride != null && !branchOverride.trim().isEmpty() ? branchOverride.trim() : "main";

                listener.onProgress("Menyiapkan koneksi ke GitHub...", 5);

                String zipUrl = "https://github.com/" + owner + "/" + repo + "/archive/refs/heads/" + branch + ".zip";
                HttpURLConnection conn = openConnectionWithRedirects(zipUrl);

                if (conn.getResponseCode() == 404 && branch.equals("main")) {
                    // Coba fallback ke master
                    listener.onProgress("Branch 'main' tidak ditemukan, mencoba 'master'...", 10);
                    zipUrl = "https://github.com/" + owner + "/" + repo + "/archive/refs/heads/master.zip";
                    conn = openConnectionWithRedirects(zipUrl);
                }

                int respCode = conn.getResponseCode();
                if (respCode < 200 || respCode >= 300) {
                    listener.onError("Gagal mengunduh repository dari GitHub (HTTP " + respCode + ")");
                    return;
                }

                long totalSize = conn.getContentLengthLong();
                File targetDir = new File(outputDir, repo);
                if (targetDir.exists()) {
                    deleteRecursive(targetDir);
                }
                targetDir.mkdirs();

                listener.onProgress("Mengunduh arsip proyek...", 20);

                InputStream in = new BufferedInputStream(conn.getInputStream(), 16384);
                ZipInputStream zis = new ZipInputStream(in);
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                int entryCount = 0;

                String rootPrefix = null;

                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (rootPrefix == null) {
                        int slash = name.indexOf('/');
                        if (slash > 0) {
                            rootPrefix = name.substring(0, slash + 1);
                        }
                    }

                    String relativePath = name;
                    if (rootPrefix != null && relativePath.startsWith(rootPrefix)) {
                        relativePath = relativePath.substring(rootPrefix.length());
                    }

                    if (relativePath.isEmpty()) {
                        zis.closeEntry();
                        continue;
                    }

                    File destFile = new File(targetDir, relativePath);

                    // Zip Slip security check
                    String canonicalDest = destFile.getCanonicalPath();
                    String canonicalTarget = targetDir.getCanonicalPath();
                    if (!canonicalDest.startsWith(canonicalTarget + File.separator) && !canonicalDest.equals(canonicalTarget)) {
                        throw new SecurityException("Zip Slip detected: " + name);
                    }

                    if (entry.isDirectory()) {
                        destFile.mkdirs();
                    } else {
                        File parent = destFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs();
                        }

                        FileOutputStream fos = new FileOutputStream(destFile);
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                        fos.flush();
                        fos.close();
                    }
                    entryCount++;
                    if (entryCount % 10 == 0) {
                        listener.onProgress("Mengekstrak file (" + entryCount + ")...", 30 + (entryCount % 50));
                    }
                    zis.closeEntry();
                }

                zis.close();
                in.close();
                conn.disconnect();

                listener.onProgress("Selesai mengekstrak " + entryCount + " file.", 100);
                listener.onSuccess(targetDir);

            } catch (Exception e) {
                listener.onError("Error saat clone repository: " + e.getMessage());
            }
        }).start();
    }

    private static HttpURLConnection openConnectionWithRedirects(String initialUrl) throws IOException {
        String currentUrl = initialUrl;
        int redirects = 0;
        while (redirects < 6) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "ApkCompiler-Android/1.0");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == 307 || status == 308) {
                String newLocation = conn.getHeaderField("Location");
                conn.disconnect();
                if (newLocation != null && !newLocation.isEmpty()) {
                    currentUrl = newLocation;
                    redirects++;
                    continue;
                }
            }
            return conn;
        }
        throw new IOException("Terlalu banyak pengalihan (redirect loop)");
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }
}
