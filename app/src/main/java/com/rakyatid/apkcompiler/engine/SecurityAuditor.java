package com.rakyatid.apkcompiler.engine;

import com.rakyatid.apkcompiler.model.Project;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise-grade Security Auditor & Sandboxing Engine.
 * Protects on-device compilation against:
 * 1. Command Injection & Arbitrary Code Execution
 * 2. Zip Slip & Path Traversal Attacks
 * 3. Malicious Symlink Escapes (pointing to private app directories /data/data/...)
 * 4. Malware Injection & Dangerous Android Permissions (SMS fraud, spyware, overlay attacks, boot persistence)
 * 5. Sensitive Data Exposure (Keystore passwords, user tokens)
 * 6. Server-Side Request Forgery (SSRF) during repository cloning
 */
public class SecurityAuditor {

    public interface LogCallback {
        void log(String message);
    }

    // High-risk Android permissions commonly abused by spyware/malware
    public static final Map<String, String> DANGEROUS_PERMISSIONS = new LinkedHashMap<>();
    static {
        DANGEROUS_PERMISSIONS.put("android.permission.SEND_SMS", "Mengirim SMS secara diam-diam (potensi toll fraud / malware perbankan)");
        DANGEROUS_PERMISSIONS.put("android.permission.RECEIVE_SMS", "Mencegat SMS masuk (potensi pencurian OTP / 2FA)");
        DANGEROUS_PERMISSIONS.put("android.permission.READ_SMS", "Membaca pesan SMS pengguna (pencurian data pribadi)");
        DANGEROUS_PERMISSIONS.put("android.permission.RECEIVE_MMS", "Menerima pesan MMS (potensi eksploitasi Stagefright/parser)");
        DANGEROUS_PERMISSIONS.put("android.permission.RECORD_AUDIO", "Merekam mikrofon tanpa izin (potensi spyware/penyadapan suara)");
        DANGEROUS_PERMISSIONS.put("android.permission.CAMERA", "Mengambil foto/video secara diam-diam (potensi spyware)");
        DANGEROUS_PERMISSIONS.put("android.permission.ACCESS_FINE_LOCATION", "Melacak lokasi presisi GPS pengguna (pelanggaran privasi)");
        DANGEROUS_PERMISSIONS.put("android.permission.ACCESS_COARSE_LOCATION", "Melacak perkiraan lokasi pengguna");
        DANGEROUS_PERMISSIONS.put("android.permission.ACCESS_BACKGROUND_LOCATION", "Melacak lokasi di latar belakang terus menerus");
        DANGEROUS_PERMISSIONS.put("android.permission.READ_CONTACTS", "Membaca daftar kontak pengguna (pencurian data pribadi)");
        DANGEROUS_PERMISSIONS.put("android.permission.WRITE_CONTACTS", "Memodifikasi daftar kontak");
        DANGEROUS_PERMISSIONS.put("android.permission.READ_CALL_LOG", "Membaca riwayat panggilan telepon");
        DANGEROUS_PERMISSIONS.put("android.permission.WRITE_CALL_LOG", "Memodifikasi riwayat panggilan telepon");
        DANGEROUS_PERMISSIONS.put("android.permission.PROCESS_OUTGOING_CALLS", "Mencegat panggilan keluar");
        DANGEROUS_PERMISSIONS.put("android.permission.CALL_PHONE", "Melakukan panggilan telepon otomatis");
        DANGEROUS_PERMISSIONS.put("android.permission.SYSTEM_ALERT_WINDOW", "Menampilkan overlay mengambang di atas aplikasi lain (potensi tapjacking/phishing)");
        DANGEROUS_PERMISSIONS.put("android.permission.RECEIVE_BOOT_COMPLETED", "Menjalankan service otomatis saat perangkat menyala (potensi spyware persistent)");
        DANGEROUS_PERMISSIONS.put("android.permission.BIND_ACCESSIBILITY_SERVICE", "Layanan Aksesibilitas (sering disalahgunakan trojan perbankan untuk mengontrol layar)");
        DANGEROUS_PERMISSIONS.put("android.permission.BIND_DEVICE_ADMIN", "Izin Device Administrator (potensi ransomware / penguncian perangkat)");
        DANGEROUS_PERMISSIONS.put("android.permission.REQUEST_INSTALL_PACKAGES", "Meminta instalasi paket aplikasi tambahan (potensi dropper malware)");
        DANGEROUS_PERMISSIONS.put("android.permission.INSTALL_PACKAGES", "Menginstal paket secara otomatis");
        DANGEROUS_PERMISSIONS.put("android.permission.PACKAGE_USAGE_STATS", "Memantau aktivitas aplikasi yang dibuka pengguna");
        DANGEROUS_PERMISSIONS.put("android.permission.QUERY_ALL_PACKAGES", "Menginventarisasi seluruh aplikasi yang terpasang di perangkat");
        DANGEROUS_PERMISSIONS.put("android.permission.WRITE_SETTINGS", "Mengubah pengaturan sistem global");
    }

    public static class AuditResult {
        private boolean safe = true;
        private final List<String> dangerousPermissions = new ArrayList<>();
        private final List<String> suspiciousComponents = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> violations = new ArrayList<>();

        public boolean isSafe() {
            return safe && violations.isEmpty();
        }

        public void setSafe(boolean safe) {
            this.safe = safe;
        }

        public void addViolation(String violation) {
            this.violations.add(violation);
            this.safe = false;
        }

        public void addWarning(String warning) {
            this.warnings.add(warning);
        }

        public void addDangerousPermission(String perm, String description) {
            this.dangerousPermissions.add(perm + ": " + description);
            addWarning("Permisi Berbahaya Terdeteksi: " + perm + " -> " + description);
        }

        public void addSuspiciousComponent(String component) {
            this.suspiciousComponents.add(component);
            addWarning("Komponen Mencurigakan Terdeteksi: " + component);
        }

        public List<String> getDangerousPermissions() {
            return Collections.unmodifiableList(dangerousPermissions);
        }

        public List<String> getSuspiciousComponents() {
            return Collections.unmodifiableList(suspiciousComponents);
        }

        public List<String> getWarnings() {
            return Collections.unmodifiableList(warnings);
        }

        public List<String> getViolations() {
            return Collections.unmodifiableList(violations);
        }
    }

    /**
     * Audits an untrusted project completely:
     * - Validates directory sandbox against symlink escapes
     * - Analyzes AndroidManifest.xml for dangerous permissions & stealth components
     * - Verifies package name integrity
     */
    public static AuditResult auditProject(Project project, LogCallback logger) {
        AuditResult result = new AuditResult();
        if (project == null || project.getRootDir() == null || !project.getRootDir().exists()) {
            result.addViolation("Direktori proyek tidak valid atau tidak ditemukan.");
            return result;
        }

        File rootDir = project.getRootDir();

        // 1. Sandbox Validation: check all files in project for symlinks pointing outside
        try {
            logger.log("[SECURITY AUDIT] 1/3 Memeriksa integritas sandbox direktori & symlinks...");
            validateSandboxFiles(rootDir, rootDir, 0);
            logger.log("[SECURITY AUDIT] Sandbox aman: tidak ada symlink yang keluar dari direktori proyek.");
        } catch (SecurityException se) {
            result.addViolation(se.getMessage());
            logger.log("[SECURITY ALERT] " + se.getMessage());
            return result;
        } catch (Exception e) {
            result.addViolation("Gagal memvalidasi sandbox file: " + e.getMessage());
            return result;
        }

        // 2. Package Name Security Check
        String pkg = project.getPackageName();
        if (pkg != null) {
            if (!isValidPackageName(pkg)) {
                result.addViolation("Format package name tidak aman atau tidak valid: '" + pkg + "'");
            } else if (isSystemPackageSpoof(pkg)) {
                result.addViolation("Package name mencoba meniru identitas sistem Android/Google: '" + pkg + "'");
            }
        }

        // 3. AndroidManifest Security Audit
        File manifest = project.getManifestFile();
        if (manifest != null && manifest.exists()) {
            logger.log("[SECURITY AUDIT] 2/3 Memeriksa AndroidManifest.xml untuk deteksi potensi malware...");
            auditManifest(manifest, result, logger);
        } else {
            logger.log("[SECURITY AUDIT] Manifest tidak ditemukan pada tahap audit.");
        }

        logger.log("[SECURITY AUDIT] 3/3 Audit selesai. Total peringatan: " + result.getWarnings().size() +
                ", Pelanggaran fatal: " + result.getViolations().size());

        return result;
    }

    /**
     * Recursively verifies that every file inside dir resolves canonically within rootDir.
     * Prevents symlink traversal pointing to /data/data/com.rakyatid.apkcompiler or /system.
     */
    public static void validateSandboxFiles(File currentDir, File rootDir, int depth) throws IOException {
        if (depth > 25) {
            throw new SecurityException("Struktur direktori terlalu dalam atau terdapat circular symlink!");
        }

        String canonicalRoot = rootDir.getCanonicalPath();
        File[] files = currentDir.listFiles();
        if (files == null) return;

        for (File f : files) {
            String canonicalFile = f.getCanonicalPath();
            if (!canonicalFile.startsWith(canonicalRoot + File.separator) && !canonicalFile.equals(canonicalRoot)) {
                throw new SecurityException("Pelanggaran Sandbox: File '" + f.getName() +
                        "' merujuk ke luar direktori proyek (" + canonicalFile + ")");
            }

            if (f.isDirectory()) {
                validateSandboxFiles(f, rootDir, depth + 1);
            }
        }
    }

    /**
     * Analyzes AndroidManifest.xml for dangerous permissions and spyware vectors.
     */
    private static void auditManifest(File manifestFile, AuditResult result, LogCallback logger) {
        try {
            int len = (int) Math.min(manifestFile.length(), 512 * 1024);
            byte[] buf = new byte[len];
            int readTotal = 0;
            try (FileInputStream fis = new FileInputStream(manifestFile)) {
                while (readTotal < len) {
                    int r = fis.read(buf, readTotal, len - readTotal);
                    if (r <= 0) break;
                    readTotal += r;
                }
            }
            String content = new String(buf, 0, readTotal, "UTF-8");

            // Check permissions
            Matcher mPerm = Pattern.compile("<uses-permission[^>]+android:name\\s*=\\s*\"([^\"]+)\"").matcher(content);
            while (mPerm.find()) {
                String permName = mPerm.group(1).trim();
                if (DANGEROUS_PERMISSIONS.containsKey(permName)) {
                    result.addDangerousPermission(permName, DANGEROUS_PERMISSIONS.get(permName));
                }
            }

            // Check for BOOT_COMPLETED receiver
            if (content.contains("android.intent.action.BOOT_COMPLETED") || content.contains("BOOT_COMPLETED")) {
                result.addSuspiciousComponent("BroadcastReceiver BOOT_COMPLETED (Aplikasi dapat autostart di latar belakang)");
            }

            // Check for SMS_RECEIVED receiver
            if (content.contains("SMS_RECEIVED") || content.contains("android.provider.Telephony.SMS_RECEIVED")) {
                result.addSuspiciousComponent("BroadcastReceiver SMS_RECEIVED (Aplikasi dapat menyadap SMS / OTP masuk)");
            }

            // Check for Accessibility Service
            if (content.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")) {
                result.addSuspiciousComponent("AccessibilityService terdaftar (potensi trojan perbankan / kontrol antarmuka penuh)");
            }

            // Check for Device Admin
            if (content.contains("android.app.action.DEVICE_ADMIN_ENABLED") || content.contains("BIND_DEVICE_ADMIN")) {
                result.addSuspiciousComponent("Device Admin Receiver (potensi penguncian perangkat / ransomware)");
            }

            // Check for Cleartext Traffic
            if (content.contains("android:usesCleartextTraffic=\"true\"")) {
                result.addWarning("Aplikasi mengizinkan lalu lintas HTTP tidak terenkripsi (usesCleartextTraffic=true)");
            }

        } catch (Exception e) {
            result.addWarning("Gagal membaca manifest untuk audit keamanan: " + e.getMessage());
        }
    }

    /**
     * Validates whether a package name is safe, well-formed, and free from command line flags.
     */
    public static boolean isValidPackageName(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return false;
        pkg = pkg.trim();
        if (pkg.length() > 150) return false;
        if (pkg.startsWith("-")) return false; // Flag injection prevention
        // Standard Java package naming rule
        return pkg.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");
    }

    /**
     * Detects system package spoofing attempts.
     */
    public static boolean isSystemPackageSpoof(String pkg) {
        if (pkg == null) return false;
        String lower = pkg.toLowerCase(Locale.ROOT);
        return lower.equals("android") ||
               lower.startsWith("android.") ||
               lower.equals("com.android") ||
               lower.startsWith("com.android.") ||
               lower.equals("com.google.android.gms") ||
               lower.startsWith("com.google.android.gms.");
    }

    /**
     * Strictly validates Git URLs:
     * - Only allows HTTPS protocol
     * - Rejects file://, content://, javascript:, ftp://
     * - Rejects backticks, pipes, semicolons, quotes, command substitution
     * - Rejects flags starting with -
     */
    public static boolean isValidGitUrl(String url) {
        if (url == null) return false;
        String clean = url.trim();
        if (clean.isEmpty() || clean.length() > 255) return false;

        // Disallow dangerous characters that could be passed to shells or tools
        if (clean.contains("`") || clean.contains("|") || clean.contains(";") ||
            clean.contains("&") || clean.contains("$") || clean.contains("\n") ||
            clean.contains("\r") || clean.contains("\t") || clean.contains("\0") ||
            clean.contains("\\") || clean.contains("\"") || clean.contains("'") ||
            clean.contains(" ") || clean.startsWith("-")) {
            return false;
        }

        // Must be HTTPS GitHub or valid HTTPS URL or owner/repo format
        if (clean.startsWith("https://github.com/")) {
            String path = clean.substring("https://github.com/".length());
            if (path.endsWith(".git")) path = path.substring(0, path.length() - 4);
            return path.matches("^[a-zA-Z0-9_.-]{1,100}/[a-zA-Z0-9_.-]{1,100}$");
        } else if (clean.matches("^[a-zA-Z0-9_.-]{1,100}/[a-zA-Z0-9_.-]{1,100}(\\.git)?$")) {
            return true;
        }

        return false;
    }

    /**
     * Validates a Git branch name.
     */
    public static boolean isValidBranch(String branch) {
        if (branch == null) return false;
        String b = branch.trim();
        if (b.isEmpty() || b.length() > 100) return false;
        if (b.startsWith("-") || b.startsWith("/")) return false;
        if (b.contains("..") || b.contains("//") || b.contains("~") || b.contains("^") ||
            b.contains(":") || b.contains("?") || b.contains("*") || b.contains("[") ||
            b.contains("\\") || b.contains(" ") || b.endsWith("/") || b.endsWith(".lock")) {
            return false;
        }
        return b.matches("^[a-zA-Z0-9_./-]+$");
    }

    /**
     * Checks Zip Entry name for path traversal & Zip Slip vulnerabilities.
     */
    public static void validateZipEntry(String entryName, File targetDir) throws SecurityException, IOException {
        if (entryName == null) {
            throw new SecurityException("Nama file zip tidak boleh null");
        }
        if (entryName.contains("\0")) {
            throw new SecurityException("Karakter null byte terdeteksi pada zip entry: " + entryName);
        }
        if (entryName.contains("..")) {
            throw new SecurityException("Zip Slip path traversal terdeteksi (..): " + entryName);
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw new SecurityException("Zip Slip path absolut terdeteksi: " + entryName);
        }

        File destFile = new File(targetDir, entryName);
        String canonicalDest = destFile.getCanonicalPath();
        String canonicalTarget = targetDir.getCanonicalPath();

        if (!canonicalDest.startsWith(canonicalTarget + File.separator) && !canonicalDest.equals(canonicalTarget)) {
            throw new SecurityException("Zip Slip terdeteksi: file keluar dari target (" + entryName + ")");
        }
    }

    /**
     * Escapes XML special characters to prevent XML injection and XXE attacks.
     */
    public static String sanitizeXmlText(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;"); break;
                case '<':  sb.append("&lt;"); break;
                case '>':  sb.append("&gt;"); break;
                case '\"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // Only allow printable characters and safe whitespace
                    if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Masks authentication tokens or credentials in URLs so they do not leak into logs.
     */
    public static String maskToken(String url) {
        if (url == null) return null;
        return url.replaceAll("https://[^@/]+@", "https://***@");
    }

    /**
     * Checks if an IP address is a private, loopback, link-local, or reserved address (SSRF protection).
     */
    public static boolean isPrivateOrReservedIp(InetAddress address) {
        if (address == null) return true;
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() ||
            address.isLinkLocalAddress() || address.isAnyLocalAddress() ||
            address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // 10.0.0.0/8
            if (b0 == 10) return true;
            // 172.16.0.0/12
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) return true;
            // 169.254.0.0/16 (Link Local / Cloud Metadata 169.254.169.254)
            if (b0 == 169 && b1 == 254) return true;
            // 127.0.0.0/8
            if (b0 == 127) return true;
            // 0.0.0.0/8
            if (b0 == 0) return true;
        }
        return false;
    }
}
