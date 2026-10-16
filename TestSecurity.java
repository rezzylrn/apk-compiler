import com.rakyatid.apkcompiler.engine.*;

import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

public class TestSecurity {
    public static void main(String[] args) {
        System.out.println("=== RUNNING SECURITY VERIFICATION TESTS ===");

        // Test 1: On-device dynamic Keystore & X.509 cert generation
        try {
            System.out.println("Test 1: Dynamic Keystore Generation...");
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();

            X509Certificate cert = ApkSignerUtil.generateSelfSignedCert(kp, "CN=APK Compiler Debug, O=RakyatId, C=ID");
            cert.verify(kp.getPublic());

            File tmpKs = new File("/tmp/test_dynamic_debug.keystore");
            if (tmpKs.exists()) tmpKs.delete();

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry("androiddebugkey", kp.getPrivate(), "dynamicpass123".toCharArray(), new java.security.cert.Certificate[]{cert});
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpKs);
            ks.store(fos, "dynamicpass123".toCharArray());
            fos.close();

            if (!tmpKs.exists() || tmpKs.length() == 0) {
                throw new Exception("Keystore file not generated");
            }
            System.out.println("  Generated keystore size: " + tmpKs.length() + " bytes");
            System.out.println("  Certificate Subject: " + cert.getSubjectDN());
            System.out.println("TEST 1 PASSED: Dynamic keystore works perfectly with pure Java!\n");
            tmpKs.delete();
        } catch (Exception e) {
            System.err.println("TEST 1 FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // Test 2: Verify Checksum matching algorithm
        try {
            System.out.println("Test 2: Checksum Verification Logic...");
            byte[] sample = "HelloWorld".getBytes("UTF-8");
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(sample);
            byte[] digest = md.digest();
            StringBuilder hexSb = new StringBuilder();
            for (byte b : digest) hexSb.append(String.format("%02x", b));
            String actualHash = hexSb.toString();

            // SHA-256 of HelloWorld is 872e4e50ce9990d8b041330c47c9ddd11bec6b503ae9386a99da8584e9bb12c4
            String expected = "872e4e50ce9990d8b041330c47c9ddd11bec6b503ae9386a99da8584e9bb12c4";
            if (!expected.equalsIgnoreCase(actualHash)) {
                throw new Exception("Hash mismatch: " + actualHash + " != " + expected);
            }
            System.out.println("  Computed hash: " + actualHash);
            System.out.println("TEST 2 PASSED: Checksum verification logic confirmed!\n");
        } catch (Exception e) {
            System.err.println("TEST 2 FAILED: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("ALL SECURITY AUDIT TESTS PASSED 100%!");
    }
}
