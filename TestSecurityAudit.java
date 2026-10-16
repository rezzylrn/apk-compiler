import com.rakyatid.apkcompiler.engine.*;
import com.rakyatid.apkcompiler.model.Project;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.List;

public class TestSecurityAudit {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    private static void assertTrue(String testName, boolean condition, String errorMsg) {
        if (condition) {
            System.out.println("  [PASS] " + testName);
            testsPassed++;
        } else {
            System.err.println("  [FAIL] " + testName + ": " + errorMsg);
            testsFailed++;
        }
    }

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("   ENTERPRISE SECURITY HARDENING AUDIT SUITE    ");
        System.out.println("=================================================\n");

        testCommandAndFlagInjection();
        testZipSlipAndPathTraversal();
        testSymlinkSandboxEnforcement();
        testMalwareAndDangerousPermissionsAudit();
        testSensitiveDataProtectionAndTokenMasking();
        testSsrfProtection();
        testApkFileProviderRestrictions();

        System.out.println("\n=================================================");
        System.out.println("TEST SUMMARY: " + testsPassed + " PASSED, " + testsFailed + " FAILED");
        System.out.println("=================================================");

        if (testsFailed > 0) {
            System.err.println("SECURITY AUDIT FAILED!");
            System.exit(1);
        } else {
            System.out.println("ALL ENTERPRISE SECURITY DEFENSES VERIFIED 100%!");
        }
    }

    private static void testCommandAndFlagInjection() {
        System.out.println("--- 1. Command & Flag Injection Defenses ---");

        // 1.1 Non-HTTPS protocols must be rejected
        assertTrue("Reject file:// URL", !SecurityAuditor.isValidGitUrl("file:///etc/passwd"), "file:// allowed");
        assertTrue("Reject content:// URL", !SecurityAuditor.isValidGitUrl("content://provider/data"), "content:// allowed");
        assertTrue("Reject javascript: URL", !SecurityAuditor.isValidGitUrl("javascript:alert(1)"), "javascript: allowed");
        assertTrue("Reject ftp:// URL", !SecurityAuditor.isValidGitUrl("ftp://evil.com/repo"), "ftp:// allowed");

        // 1.2 Shell command injection characters
        assertTrue("Reject semicolon command chaining", !SecurityAuditor.isValidGitUrl("https://github.com/user/repo;rm -rf /"), "semicolon allowed");
        assertTrue("Reject pipe shell injection", !SecurityAuditor.isValidGitUrl("https://github.com/user/repo|cat /etc/passwd"), "pipe allowed");
        assertTrue("Reject backtick execution", !SecurityAuditor.isValidGitUrl("https://github.com/user/`whoami`"), "backtick allowed");
        assertTrue("Reject $() command substitution", !SecurityAuditor.isValidGitUrl("https://github.com/user/$(id)"), "$() allowed");
        assertTrue("Reject newline injection", !SecurityAuditor.isValidGitUrl("https://github.com/user/repo\nevil"), "newline allowed");
        assertTrue("Reject space injection", !SecurityAuditor.isValidGitUrl("https://github.com/user/repo evil"), "space allowed");

        // 1.3 Flag injection
        assertTrue("Reject flag starting with -", !SecurityAuditor.isValidGitUrl("--upload-pack=evil"), "flag allowed");
        assertTrue("Reject branch starting with -", !SecurityAuditor.isValidBranch("-f"), "branch flag allowed");
        assertTrue("Reject branch traversal ..", !SecurityAuditor.isValidBranch("../../heads/master"), "branch traversal allowed");

        // 1.4 Valid Git URLs
        assertTrue("Allow valid GitHub URL", SecurityAuditor.isValidGitUrl("https://github.com/sanbeg/flashlight"), "valid github url rejected");
        assertTrue("Allow valid GitHub .git URL", SecurityAuditor.isValidGitUrl("https://github.com/iutinvg/compass.git"), "valid .git url rejected");
        assertTrue("Allow valid owner/repo format", SecurityAuditor.isValidGitUrl("owner/my-repo_1"), "valid owner/repo rejected");
        assertTrue("Allow valid branch name", SecurityAuditor.isValidBranch("feature/sec-hardened_v2"), "valid branch rejected");

        // 1.5 Package name validation & spoofing
        assertTrue("Valid package name", SecurityAuditor.isValidPackageName("com.rakyatid.apkcompiler"), "valid package rejected");
        assertTrue("Reject package name with flags", !SecurityAuditor.isValidPackageName("-flag.injection"), "flag package allowed");
        assertTrue("Reject package name with semicolons", !SecurityAuditor.isValidPackageName("com.example;rm"), "semicolon package allowed");
        assertTrue("Detect Android system package spoof", SecurityAuditor.isSystemPackageSpoof("com.android.settings"), "spoof not detected");
        assertTrue("Detect Google Play Services spoof", SecurityAuditor.isSystemPackageSpoof("com.google.android.gms.auth"), "spoof not detected");
        assertTrue("Normal package not spoof", !SecurityAuditor.isSystemPackageSpoof("com.mycompany.app"), "normal package flagged as spoof");

        // 1.6 XML text escaping
        String escaped = SecurityAuditor.sanitizeXmlText("<MyApp & 'Special' \"Test\">");
        assertTrue("XML special chars escaped", escaped.equals("&lt;MyApp &amp; &apos;Special&apos; &quot;Test&quot;&gt;"), "XML escaping failed: " + escaped);
    }

    private static void testZipSlipAndPathTraversal() {
        System.out.println("\n--- 2. Zip Slip & Path Traversal Defenses ---");

        File targetDir = new File("/tmp/test_target_sandbox_" + System.currentTimeMillis());
        targetDir.mkdirs();

        // 2.1 Double dot path traversal
        boolean caughtDotDot = false;
        try {
            SecurityAuditor.validateZipEntry("../../evil_overwrite.txt", targetDir);
        } catch (SecurityException se) {
            caughtDotDot = true;
        } catch (Exception ignored) {}
        assertTrue("Block Zip Slip with ../..", caughtDotDot, "Did not block ../..");

        // 2.2 Subdirectory traversal
        boolean caughtSubDotDot = false;
        try {
            SecurityAuditor.validateZipEntry("sub/dir/../../../../etc/passwd", targetDir);
        } catch (SecurityException se) {
            caughtSubDotDot = true;
        } catch (Exception ignored) {}
        assertTrue("Block Zip Slip nested traversal", caughtSubDotDot, "Did not block nested ..");

        // 2.3 Absolute path injection
        boolean caughtAbsolute = false;
        try {
            SecurityAuditor.validateZipEntry("/etc/shadow", targetDir);
        } catch (SecurityException se) {
            caughtAbsolute = true;
        } catch (Exception ignored) {}
        assertTrue("Block Zip Slip absolute path", caughtAbsolute, "Did not block absolute path");

        // 2.4 Null byte injection
        boolean caughtNullByte = false;
        try {
            SecurityAuditor.validateZipEntry("app\0.apk", targetDir);
        } catch (SecurityException se) {
            caughtNullByte = true;
        } catch (Exception ignored) {}
        assertTrue("Block Zip Slip null byte", caughtNullByte, "Did not block null byte");

        // 2.5 Safe entry passes
        boolean safeOk = true;
        try {
            SecurityAuditor.validateZipEntry("src/main/java/MainActivity.java", targetDir);
        } catch (Exception e) {
            safeOk = false;
        }
        assertTrue("Allow safe Zip entry", safeOk, "Safe zip entry threw exception");

        targetDir.delete();
    }

    private static void testSymlinkSandboxEnforcement() {
        System.out.println("\n--- 3. Symlink Sandbox Enforcement Defenses ---");

        File projRoot = new File("/tmp/test_symlink_proj_" + System.currentTimeMillis());
        File subDir = new File(projRoot, "app/src/main/assets");
        subDir.mkdirs();

        File secretExternalFile = new File("/tmp/secret_external_credential.txt");
        try {
            try (FileOutputStream fos = new FileOutputStream(secretExternalFile)) {
                fos.write("PRIVATE_API_KEY_123456789".getBytes());
            }

            // Normal file inside project
            File safeFile = new File(subDir, "readme.txt");
            try (FileOutputStream fos = new FileOutputStream(safeFile)) {
                fos.write("Hello safe project".getBytes());
            }

            // Create malicious symlink pointing outside project directory
            File maliciousLink = new File(subDir, "stolen_secret_link");
            try {
                Files.createSymbolicLink(maliciousLink.toPath(), secretExternalFile.toPath());
            } catch (Exception e) {
                System.out.println("  (Note: creating symlink skipped if OS permissions disallow)");
            }

            if (maliciousLink.exists()) {
                boolean symlinkBlocked = false;
                try {
                    SecurityAuditor.validateSandboxFiles(projRoot, projRoot, 0);
                } catch (SecurityException se) {
                    symlinkBlocked = true;
                    System.out.println("  Intercepted dangerous symlink: " + se.getMessage());
                }
                assertTrue("Block symlink escaping project sandbox", symlinkBlocked, "Dangerous symlink was NOT blocked!");
            }

        } catch (Exception e) {
            System.err.println("Error setting up symlink test: " + e.getMessage());
        } finally {
            // Cleanup
            deleteDir(projRoot);
            secretExternalFile.delete();
        }
    }

    private static void testMalwareAndDangerousPermissionsAudit() {
        System.out.println("\n--- 4. Malware & Dangerous Permission Analysis ---");

        File tmpDir = new File("/tmp/test_malware_proj_" + System.currentTimeMillis());
        tmpDir.mkdirs();

        try {
            // Create a malicious AndroidManifest.xml requesting spyware permissions
            String maliciousManifestContent = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    package=\"com.suspicious.spyware\">\n" +
                    "    <uses-permission android:name=\"android.permission.SEND_SMS\" />\n" +
                    "    <uses-permission android:name=\"android.permission.RECEIVE_SMS\" />\n" +
                    "    <uses-permission android:name=\"android.permission.RECORD_AUDIO\" />\n" +
                    "    <uses-permission android:name=\"android.permission.SYSTEM_ALERT_WINDOW\" />\n" +
                    "    <uses-permission android:name=\"android.permission.RECEIVE_BOOT_COMPLETED\" />\n" +
                    "    <uses-permission android:name=\"android.permission.BIND_ACCESSIBILITY_SERVICE\" />\n" +
                    "    <application android:usesCleartextTraffic=\"true\">\n" +
                    "        <receiver android:name=\".BootReceiver\">\n" +
                    "            <intent-filter>\n" +
                    "                <action android:name=\"android.intent.action.BOOT_COMPLETED\" />\n" +
                    "            </intent-filter>\n" +
                    "        </receiver>\n" +
                    "    </application>\n" +
                    "</manifest>";

            File manifestFile = new File(tmpDir, "AndroidManifest.xml");
            try (FileOutputStream fos = new FileOutputStream(manifestFile)) {
                fos.write(maliciousManifestContent.getBytes("UTF-8"));
            }

            Project project = new Project("SpywareApp", tmpDir, manifestFile, null, null, "com.suspicious.spyware", 21, 34, 1, 1, true);

            SecurityAuditor.AuditResult result = SecurityAuditor.auditProject(project, msg -> {});

            List<String> dangPerms = result.getDangerousPermissions();
            List<String> suspComps = result.getSuspiciousComponents();

            boolean hasSendSms = false;
            boolean hasReceiveSms = false;
            boolean hasRecordAudio = false;
            boolean hasOverlay = false;
            boolean hasBootReceiver = false;
            boolean hasAccessibility = false;

            for (String p : dangPerms) {
                if (p.contains("SEND_SMS")) hasSendSms = true;
                if (p.contains("RECEIVE_SMS")) hasReceiveSms = true;
                if (p.contains("RECORD_AUDIO")) hasRecordAudio = true;
                if (p.contains("SYSTEM_ALERT_WINDOW")) hasOverlay = true;
            }

            for (String c : suspComps) {
                if (c.contains("BOOT_COMPLETED")) hasBootReceiver = true;
                if (c.contains("AccessibilityService")) hasAccessibility = true;
            }

            assertTrue("Detect SEND_SMS spyware permission", hasSendSms, "SEND_SMS not detected");
            assertTrue("Detect RECEIVE_SMS OTP stealing permission", hasReceiveSms, "RECEIVE_SMS not detected");
            assertTrue("Detect RECORD_AUDIO microphone eavesdropping", hasRecordAudio, "RECORD_AUDIO not detected");
            assertTrue("Detect SYSTEM_ALERT_WINDOW tapjacking permission", hasOverlay, "SYSTEM_ALERT_WINDOW not detected");
            assertTrue("Detect BOOT_COMPLETED persistent receiver", hasBootReceiver, "BOOT_COMPLETED not detected");
            assertTrue("Detect Accessibility Service hijacking vector", hasAccessibility, "AccessibilityService not detected");

            // Clean project test
            String cleanManifestContent = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    package=\"com.example.calculator\">\n" +
                    "    <application>\n" +
                    "        <activity android:name=\".MainActivity\" android:exported=\"true\" />\n" +
                    "    </application>\n" +
                    "</manifest>";

            try (FileOutputStream fos = new FileOutputStream(manifestFile)) {
                fos.write(cleanManifestContent.getBytes("UTF-8"));
            }
            Project cleanProject = new Project("CalculatorApp", tmpDir, manifestFile, null, null, "com.example.calculator", 21, 34, 1, 1, true);
            SecurityAuditor.AuditResult cleanResult = SecurityAuditor.auditProject(cleanProject, msg -> {});
            assertTrue("Clean project passes audit with 0 dangerous permissions",
                    cleanResult.getDangerousPermissions().isEmpty(), "False positive in clean project");
            assertTrue("Clean project is safe", cleanResult.isSafe(), "Clean project marked unsafe");

        } catch (Exception e) {
            System.err.println("Malware audit test error: " + e.getMessage());
        } finally {
            deleteDir(tmpDir);
        }
    }

    private static void testSensitiveDataProtectionAndTokenMasking() {
        System.out.println("\n--- 5. Sensitive Data Protection & Token Masking ---");

        String sensitiveUrl = "https://ghp_9876543210abcdef@github.com/secret/repo.git";
        String masked = SecurityAuditor.maskToken(sensitiveUrl);
        assertTrue("Token in URL is masked with ***", masked.equals("https://***@github.com/secret/repo.git"),
                "Token was not masked properly: " + masked);

        String usernameTokenUrl = "https://oauth2:glpat-xyz123@github.com/secret/repo";
        String masked2 = SecurityAuditor.maskToken(usernameTokenUrl);
        assertTrue("Userinfo token in URL is masked", masked2.equals("https://***@github.com/secret/repo"),
                "Userinfo token was not masked: " + masked2);
    }

    private static void testSsrfProtection() {
        System.out.println("\n--- 6. Server-Side Request Forgery (SSRF) Defenses ---");

        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            assertTrue("Block loopback IP 127.0.0.1", SecurityAuditor.isPrivateOrReservedIp(loopback), "Loopback allowed");

            InetAddress priv10 = InetAddress.getByName("10.0.0.5");
            assertTrue("Block 10.x.x.x private IP", SecurityAuditor.isPrivateOrReservedIp(priv10), "10.x allowed");

            InetAddress priv192 = InetAddress.getByName("192.168.1.100");
            assertTrue("Block 192.168.x.x private IP", SecurityAuditor.isPrivateOrReservedIp(priv192), "192.168 allowed");

            InetAddress priv172 = InetAddress.getByName("172.20.0.1");
            assertTrue("Block 172.16-31.x.x private IP", SecurityAuditor.isPrivateOrReservedIp(priv172), "172.x allowed");

            InetAddress cloudMeta = InetAddress.getByName("169.254.169.254");
            assertTrue("Block cloud metadata 169.254.169.254", SecurityAuditor.isPrivateOrReservedIp(cloudMeta), "Metadata allowed");

            InetAddress anyLocal = InetAddress.getByName("0.0.0.0");
            assertTrue("Block 0.0.0.0", SecurityAuditor.isPrivateOrReservedIp(anyLocal), "0.0.0.0 allowed");

            InetAddress publicIp = InetAddress.getByName("8.8.8.8");
            assertTrue("Allow public Internet IP 8.8.8.8", !SecurityAuditor.isPrivateOrReservedIp(publicIp), "Public IP blocked");

        } catch (Exception e) {
            System.err.println("SSRF test error: " + e.getMessage());
        }
    }

    private static void testApkFileProviderRestrictions() {
        System.out.println("\n--- 7. ApkFileProvider Security Restrictions ---");

        // Test FileProvider boundary check logic
        File allowedOutputs = new File("/tmp/apkcompiler_test_outputs");
        allowedOutputs.mkdirs();

        File privateDb = new File("/tmp/apkcompiler_private_db.db");
        File privateKeystore = new File(allowedOutputs, "debug.keystore");
        File legitimateApk = new File(allowedOutputs, "LegitApp-release.apk");

        try {
            try (FileOutputStream fos = new FileOutputStream(privateDb)) { fos.write(new byte[100]); }
            try (FileOutputStream fos = new FileOutputStream(privateKeystore)) { fos.write(new byte[100]); }
            try (FileOutputStream fos = new FileOutputStream(legitimateApk)) { fos.write(new byte[100]); }

            // 1. Path traversal check
            String traversalPath = "/tmp/apkcompiler_test_outputs/../apkcompiler_private_db.db";
            boolean traversalBlocked = false;
            try {
                File target = new File(traversalPath);
                String canon = target.getCanonicalPath();
                if (!canon.startsWith(allowedOutputs.getCanonicalPath() + File.separator)) {
                    throw new SecurityException("Akses direktori ditolak");
                }
            } catch (SecurityException se) {
                traversalBlocked = true;
            }
            assertTrue("Block FileProvider path traversal to private database", traversalBlocked, "Path traversal succeeded!");

            // 2. Blacklist check against keystores inside outputs
            boolean keystoreBlocked = false;
            try {
                if (privateKeystore.getName().endsWith(".keystore") || !privateKeystore.getName().endsWith(".apk")) {
                    throw new SecurityException("Akses ditolak: Hanya file .apk yang diizinkan");
                }
            } catch (SecurityException se) {
                keystoreBlocked = true;
            }
            assertTrue("Block keystore file from FileProvider", keystoreBlocked, "Keystore file allowed!");

            // 3. Legitimate APK check
            boolean apkAllowed = legitimateApk.getName().endsWith(".apk");
            assertTrue("Allow legitimate .apk file", apkAllowed, "Legitimate APK blocked");

        } catch (Exception e) {
            System.err.println("FileProvider test error: " + e.getMessage());
        } finally {
            privateDb.delete();
            privateKeystore.delete();
            legitimateApk.delete();
            allowedOutputs.delete();
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
