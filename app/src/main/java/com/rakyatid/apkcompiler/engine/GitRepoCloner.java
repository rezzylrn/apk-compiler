package com.rakyatid.apkcompiler.engine;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class GitRepoCloner {

    public interface CloneListener {
        void onProgress(String message, int percentage);
        void onSuccess(File projectDir);
        void onError(String error);
    }

    // Security constraints against zip bombs and denial of service
    public static final int MAX_ZIP_ENTRIES = 10000;
    public static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 250L * 1024L * 1024L; // 250 MB
    public static final long MAX_SINGLE_FILE_BYTES = 50L * 1024L * 1024L; // 50 MB

    public static void cloneGitHub(String repoUrl, String branchOverride, File outputDir, CloneListener listener) {
        new Thread(() -> {
            File targetDir = null;
            try {
                if (repoUrl == null || repoUrl.trim().isEmpty()) {
                    listener.onError("URL repository tidak boleh kosong.");
                    return;
                }

                String rawUrl = repoUrl.trim();

                // 1. Validate against protocol injection (file://, content://, javascript:, ftp://)
                String lowerUrl = rawUrl.toLowerCase();
                if (lowerUrl.startsWith("file:") || lowerUrl.startsWith("content:") ||
                    lowerUrl.startsWith("javascript:") || lowerUrl.startsWith("ftp:") ||
                    lowerUrl.startsWith("jar:") || lowerUrl.startsWith("data:")) {
                    listener.onError("Protokol tidak diizinkan! Hanya HTTPS yang diperbolehkan.");
                    return;
                }

                // 2. Reject command injection characters, flags, and shell metacharacters
                if (!SecurityAuditor.isValidGitUrl(rawUrl)) {
                    listener.onError("Format URL GitHub tidak valid atau mengandung karakter berbahaya.");
                    return;
                }

                // 3. Normalize to clean GitHub URL
                String cleanUrl = rawUrl;
                if (cleanUrl.endsWith(".git")) {
                    cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 4);
                }
                if (!cleanUrl.startsWith("https://")) {
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

                // Strict validation for owner and repo names
                if (!owner.matches("^[a-zA-Z0-9_.-]{1,100}$") || owner.equals(".") || owner.equals("..") ||
                    !repo.matches("^[a-zA-Z0-9_.-]{1,100}$") || repo.equals(".") || repo.equals("..")) {
                    listener.onError("Nama pemilik atau repositori tidak valid (path traversal dicegah).");
                    return;
                }

                // 4. Validate branch
                String branch = branchOverride != null && !branchOverride.trim().isEmpty() ? branchOverride.trim() : "main";
                if (!SecurityAuditor.isValidBranch(branch)) {
                    listener.onError("Nama branch tidak valid atau mengandung karakter berbahaya.");
                    return;
                }

                // Ensure output directory is safe
                String canonicalOutput = outputDir.getCanonicalPath();
                targetDir = new File(outputDir, repo);
                String canonicalTarget = targetDir.getCanonicalPath();
                if (!canonicalTarget.startsWith(canonicalOutput + File.separator)) {
                    listener.onError("Percobaan path traversal pada target direktori terdeteksi!");
                    return;
                }

                listener.onProgress("Menghubungkan ke GitHub (" + owner + "/" + repo + "#" + branch + ")...", 5);

                String zipUrl = "https://github.com/" + owner + "/" + repo + "/archive/refs/heads/" + branch + ".zip";
                HttpURLConnection conn = openConnectionWithRedirects(zipUrl);

                if (conn.getResponseCode() == 404 && branch.equals("main")) {
                    // Fallback to master if main does not exist
                    listener.onProgress("Branch 'main' tidak ditemukan, mencoba 'master'...", 10);
                    zipUrl = "https://github.com/" + owner + "/" + repo + "/archive/refs/heads/master.zip";
                    conn = openConnectionWithRedirects(zipUrl);
                }

                int respCode = conn.getResponseCode();
                if (respCode < 200 || respCode >= 300) {
                    listener.onError("Gagal mengunduh repository dari GitHub (HTTP " + respCode + ")");
                    return;
                }

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
                long totalBytes = 0;

                String rootPrefix = null;

                while ((entry = zis.getNextEntry()) != null) {
                    entryCount++;
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw new SecurityException("Zip bomb terdeteksi: jumlah entry melebihi " + MAX_ZIP_ENTRIES);
                    }

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

                    // Zip Slip & Path Traversal Security Verification
                    SecurityAuditor.validateZipEntry(relativePath, targetDir);

                    File destFile = new File(targetDir, relativePath);
                    String canonicalDest = destFile.getCanonicalPath();
                    if (!canonicalDest.startsWith(canonicalTarget + File.separator) && !canonicalDest.equals(canonicalTarget)) {
                        throw new SecurityException("Zip Slip terdeteksi: " + name);
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
                        long fileBytes = 0;
                        while ((read = zis.read(buffer)) != -1) {
                            fileBytes += read;
                            totalBytes += read;

                            if (fileBytes > MAX_SINGLE_FILE_BYTES) {
                                fos.close();
                                throw new SecurityException("Ukuran satu file melebihi batas " + (MAX_SINGLE_FILE_BYTES / (1024 * 1024)) + " MB: " + name);
                            }
                            if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                fos.close();
                                throw new SecurityException("Zip bomb terdeteksi: total ekstraksi melebihi batas " + (MAX_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)) + " MB");
                            }

                            fos.write(buffer, 0, read);
                        }
                        fos.flush();
                        fos.close();
                    }

                    if (entryCount % 15 == 0) {
                        listener.onProgress("Mengekstrak file (" + entryCount + ")...", 30 + (entryCount % 50));
                    }
                    zis.closeEntry();
                }

                zis.close();
                in.close();
                conn.disconnect();

                // Post-extraction sandbox & symlink validation
                listener.onProgress("Memverifikasi integritas file hasil ekstraksi...", 90);
                SecurityAuditor.validateSandboxFiles(targetDir, targetDir, 0);

                listener.onProgress("Selesai mengekstrak " + entryCount + " file secara aman.", 100);
                listener.onSuccess(targetDir);

            } catch (Throwable e) {
                // In case of any security violation or error, clean up targetDir to prevent partial compromised files
                if (targetDir != null && targetDir.exists()) {
                    deleteRecursive(targetDir);
                }
                listener.onError("Error saat clone repository: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Opens an HTTP connection following redirects with strict HTTPS and SSRF protection.
     */
    private static HttpURLConnection openConnectionWithRedirects(String initialUrl) throws IOException {
        String currentUrl = initialUrl;
        int redirects = 0;
        while (redirects < 6) {
            URL url = new URL(currentUrl);

            // Enforce HTTPS
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new SecurityException("Hanya koneksi HTTPS yang diizinkan untuk keamanan data!");
            }

            // SSRF Protection: Ensure target hostname does not resolve to local/private IP
            try {
                InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
                for (InetAddress addr : addresses) {
                    if (SecurityAuditor.isPrivateOrReservedIp(addr)) {
                        throw new SecurityException("SSRF terdeteksi: Akses ke alamat internal/lokal diblokir (" + addr.getHostAddress() + ")");
                    }
                }
            } catch (SecurityException se) {
                throw se;
            } catch (Exception ignored) {
                // DNS error will fail at connection time
            }

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
                    if (!newLocation.startsWith("https://")) {
                        throw new SecurityException("Pengalihan ke protokol non-HTTPS ditolak!");
                    }
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
