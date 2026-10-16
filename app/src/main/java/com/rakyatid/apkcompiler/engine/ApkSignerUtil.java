package com.rakyatid.apkcompiler.engine;

import android.util.Base64;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ApkSignerUtil {

    public interface LogCallback {
        void log(String message);
    }

    public static boolean signApk(File inputApk, File outputApk, File keystoreFile,
                                  String ksPass, String keyAlias, String keyPass,
                                  File apksignerTool, LogCallback callback) {
        return signApk(inputApk, outputApk, keystoreFile, ksPass, keyAlias, keyPass, 21, apksignerTool, callback);
    }

    public static boolean signApk(File inputApk, File outputApk, File keystoreFile,
                                  String ksPass, String keyAlias, String keyPass,
                                  int minSdk,
                                  File apksignerTool, LogCallback callback) {
        try {
            // 1. In-process official Google apksig (Signature Scheme v1 + v2 + v3)
            Class<?> apkSignerClass = null;
            try {
                apkSignerClass = Class.forName("com.android.apksig.ApkSigner");
            } catch (ClassNotFoundException ignored) {}

            if (apkSignerClass != null && keystoreFile != null && keystoreFile.exists()) {
                callback.log("[SIGN] Membaca kunci dari keystore: " + keystoreFile.getName());
                KeyStore ks = loadKeyStore(keystoreFile, ksPass);
                if (!ks.containsAlias(keyAlias)) {
                    Enumeration<String> aliases = ks.aliases();
                    if (aliases.hasMoreElements()) {
                        keyAlias = aliases.nextElement();
                    } else {
                        callback.log("[SIGN ERROR] Keystore tidak memiliki alias kunci.");
                        return false;
                    }
                }

                PrivateKey privateKey = (PrivateKey) ks.getKey(keyAlias, keyPass.toCharArray());
                Certificate cert = ks.getCertificate(keyAlias);
                if (privateKey == null || !(cert instanceof X509Certificate)) {
                    callback.log("[SIGN ERROR] Gagal memuat kunci privat / sertifikat X509.");
                    return false;
                }

                int effectiveMinSdk = minSdk > 0 ? minSdk : 21;
                callback.log("[SIGN] Menandatangani APK via Google apksig (v1 + v2 + v3, minSdk=" + effectiveMinSdk + ")...");
                com.android.apksig.ApkSigner.SignerConfig signerConfig =
                        new com.android.apksig.ApkSigner.SignerConfig.Builder(
                                keyAlias,
                                privateKey,
                                Collections.singletonList((X509Certificate) cert)
                        ).build();

                com.android.apksig.ApkSigner signer = new com.android.apksig.ApkSigner.Builder(Collections.singletonList(signerConfig))
                        .setInputApk(inputApk)
                        .setOutputApk(outputApk)
                        .setMinSdkVersion(effectiveMinSdk)
                        .setV1SigningEnabled(true)
                        .setV2SigningEnabled(true)
                        .setV3SigningEnabled(true)
                        .build();

                signer.sign();

                if (outputApk.exists() && outputApk.length() > 0) {
                    callback.log("[SIGN] Penandatanganan resmi apksig selesai (v1 + v2 + v3 sukses).");
                    return true;
                }
            }

            // 2. Cek apakah ada tool apksigner binary eksternal / CLI
            if (apksignerTool != null && apksignerTool.exists()) {
                callback.log("[SIGN] Menjalankan apksigner CLI...");
                List<String> cmd = new ArrayList<>();
                cmd.add(apksignerTool.getAbsolutePath());
                cmd.add("sign");
                cmd.add("--ks");
                cmd.add(keystoreFile.getAbsolutePath());
                cmd.add("--ks-pass");
                cmd.add("pass:" + ksPass);
                cmd.add("--key-pass");
                cmd.add("pass:" + keyPass);
                cmd.add("--ks-key-alias");
                cmd.add(keyAlias);
                cmd.add("--out");
                cmd.add(outputApk.getAbsolutePath());
                cmd.add(inputApk.getAbsolutePath());

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    callback.log("[apksigner] " + line);
                }
                int code = proc.waitFor();
                if (code == 0 && outputApk.exists() && outputApk.length() > 0) {
                    callback.log("[SIGN] apksigner selesai.");
                    return true;
                }
            }

            // 3. Fallback: Pure Java JAR / v1 Signer
            callback.log("[SIGN] Menjalankan Java v1 Signer bawaan...");
            return signApkV1(inputApk, outputApk, keystoreFile, ksPass, keyAlias, keyPass, callback);

        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            callback.log("[SIGN ERROR] " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            StringWriter sw = new StringWriter();
            cause.printStackTrace(new PrintWriter(sw));
            callback.log(sw.toString());
            return false;
        }
    }

    public static boolean signApkV1(File inputApk, File outputApk, File keystoreFile,
                                   String ksPass, String keyAlias, String keyPass,
                                   LogCallback callback) {
        try {
            KeyStore ks = loadKeyStore(keystoreFile, ksPass);
            if (!ks.containsAlias(keyAlias)) {
                Enumeration<String> aliases = ks.aliases();
                if (aliases.hasMoreElements()) {
                    keyAlias = aliases.nextElement();
                } else {
                    callback.log("[SIGN ERROR] Keystore tidak memiliki alias kunci.");
                    return false;
                }
            }

            PrivateKey privateKey = (PrivateKey) ks.getKey(keyAlias, keyPass.toCharArray());
            Certificate cert = ks.getCertificate(keyAlias);
            if (privateKey == null || cert == null) {
                callback.log("[SIGN ERROR] Gagal memuat kunci privat / sertifikat.");
                return false;
            }

            ZipFile zipFile = new ZipFile(inputApk);
            FileOutputStream fos = new FileOutputStream(outputApk);
            ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos));

            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // 1. Hitung hash semua file untuk MANIFEST.MF
            Manifest manifest = new Manifest();
            Attributes mainAttr = manifest.getMainAttributes();
            mainAttr.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            mainAttr.put(new Attributes.Name("Created-By"), "1.0 (ApkCompiler)");

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buf = new byte[8192];

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF/")) {
                    continue; // Skip file meta lama
                }

                // Copy file ke output zip
                ZipEntry newEntry = new ZipEntry(name);
                newEntry.setTime(entry.getTime());
                zos.putNextEntry(newEntry);

                InputStream is = zipFile.getInputStream(entry);
                md.reset();
                int read;
                while ((read = is.read(buf)) != -1) {
                    md.update(buf, 0, read);
                    zos.write(buf, 0, read);
                }
                is.close();
                zos.closeEntry();

                byte[] digest = md.digest();
                String b64Digest = java.util.Base64.getEncoder().encodeToString(digest);

                Attributes entryAttr = new Attributes();
                entryAttr.put(new Attributes.Name("SHA-256-Digest"), b64Digest);
                manifest.getEntries().put(name, entryAttr);
            }

            // 2. Tulis META-INF/MANIFEST.MF
            ByteArrayOutputStream manifestBaos = new ByteArrayOutputStream();
            manifest.write(manifestBaos);
            byte[] manifestBytes = manifestBaos.toByteArray();

            ZipEntry manifestEntry = new ZipEntry("META-INF/MANIFEST.MF");
            zos.putNextEntry(manifestEntry);
            zos.write(manifestBytes);
            zos.closeEntry();

            // 3. Buat CERT.SF
            ByteArrayOutputStream sfBaos = new ByteArrayOutputStream();
            PrintStream sfPs = new PrintStream(sfBaos);
            sfPs.print("Signature-Version: 1.0\r\n");
            sfPs.print("Created-By: 1.0 (ApkCompiler)\r\n");
            md.reset();
            byte[] manifestDigest = md.digest(manifestBytes);
            sfPs.print("SHA-256-Digest-Manifest: " + java.util.Base64.getEncoder().encodeToString(manifestDigest) + "\r\n\r\n");

            for (Map.Entry<String, Attributes> e : manifest.getEntries().entrySet()) {
                sfPs.print("Name: " + e.getKey() + "\r\n");
                String d = e.getValue().getValue("SHA-256-Digest");
                sfPs.print("SHA-256-Digest: " + d + "\r\n\r\n");
            }
            sfPs.flush();
            byte[] sfBytes = sfBaos.toByteArray();

            ZipEntry sfEntry = new ZipEntry("META-INF/CERT.SF");
            zos.putNextEntry(sfEntry);
            zos.write(sfBytes);
            zos.closeEntry();

            // 4. Buat signature CERT.RSA (PKCS#7 block)
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(sfBytes);
            byte[] signatureBytes = sig.sign();

            // Minimal PKCS#7 block encoding
            byte[] rsaBlock = buildPkcs7Block(cert.getEncoded(), signatureBytes);
            ZipEntry rsaEntry = new ZipEntry("META-INF/CERT.RSA");
            zos.putNextEntry(rsaEntry);
            zos.write(rsaBlock);
            zos.closeEntry();

            zos.finish();
            zos.flush();
            zos.close();
            zipFile.close();

            callback.log("[SIGN] Penandatanganan APK v1 berhasil.");
            return true;

        } catch (Exception e) {
            callback.log("[SIGN ERROR] " + e.getMessage());
            return false;
        }
    }

    private static KeyStore loadKeyStore(File file, String password) throws Exception {
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("File keystore tidak ditemukan di " + (file != null ? file.getAbsolutePath() : "null"));
        }
        KeyStore ks;
        String[] passwords = new String[]{password, "android"};
        Exception lastEx = null;

        for (String pass : passwords) {
            if (pass == null) continue;
            // 1. Coba PKCS12
            try {
                ks = KeyStore.getInstance("PKCS12");
                try (FileInputStream fis = new FileInputStream(file)) {
                    ks.load(fis, pass.toCharArray());
                }
                return ks;
            } catch (Exception e) {
                lastEx = e;
            }

            // 2. Coba BKS (BouncyCastle keystore di Android)
            try {
                ks = KeyStore.getInstance("BKS");
                try (FileInputStream fis = new FileInputStream(file)) {
                    ks.load(fis, pass.toCharArray());
                }
                return ks;
            } catch (Exception e) {
                lastEx = e;
            }
        }
        throw new Exception("Gagal memuat keystore: " + (lastEx != null ? lastEx.getMessage() : "format tidak didukung"));
    }

    private static byte[] buildPkcs7Block(byte[] certDer, byte[] signature) throws IOException {
        // Simple ASN.1 DER ContentInfo for PKCS#7 signedData
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30); // SEQUENCE
        byte[] content = buildSignedData(certDer, signature);
        writeLength(out, content.length);
        out.write(content);
        return out.toByteArray();
    }

    private static byte[] buildSignedData(byte[] certDer, byte[] signature) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // OID 1.2.840.113549.1.7.2 (signedData)
        out.write(new byte[]{0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x07, 0x02});

        // [0] EXPLICIT SignedData
        ByteArrayOutputStream sd = new ByteArrayOutputStream();
        // Version = 1
        sd.write(new byte[]{0x02, 0x01, 0x01});
        // DigestAlgorithms: SET of AlgorithmIdentifier (SHA-256: 2.16.840.1.101.3.4.2.1)
        sd.write(new byte[]{0x31, 0x0D, 0x30, 0x0B, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01});
        // ContentInfo: contentType data (1.2.840.113549.1.7.1)
        sd.write(new byte[]{0x30, 0x0B, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x07, 0x01});

        // Certificates [0] IMPLICIT
        sd.write(0xA0);
        writeLength(sd, certDer.length);
        sd.write(certDer);

        // SignerInfos: SET of SignerInfo
        ByteArrayOutputStream si = new ByteArrayOutputStream();
        si.write(new byte[]{0x02, 0x01, 0x01}); // version 1
        // IssuerAndSerialNumber dummy/minimal
        si.write(new byte[]{0x30, 0x08, 0x30, 0x03, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01});
        // DigestAlgorithm (SHA-256)
        si.write(new byte[]{0x30, 0x0B, 0x06, 0x09, 0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01});
        // DigestEncryptionAlgorithm (RSA: 1.2.840.113549.1.1.1)
        si.write(new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00});
        // EncryptedDigest (OCTET STRING)
        si.write(0x04);
        writeLength(si, signature.length);
        si.write(signature);

        byte[] siBytes = si.toByteArray();
        sd.write(0x31);
        writeLength(sd, siBytes.length + 2);
        sd.write(0x30);
        writeLength(sd, siBytes.length);
        sd.write(siBytes);

        byte[] sdBytes = sd.toByteArray();
        out.write(0xA0);
        writeLength(out, sdBytes.length);
        out.write(sdBytes);

        return out.toByteArray();
    }

    private static void writeLength(OutputStream out, int length) throws IOException {
        if (length < 128) {
            out.write(length);
        } else if (length < 256) {
            out.write(0x81);
            out.write(length);
        } else if (length < 65536) {
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    public static java.security.cert.X509Certificate generateSelfSignedCert(KeyPair kp, String dname) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 86400000L);
        Date notAfter = new Date(now + 10000L * 86400000L); // ~27 tahun

        ByteArrayOutputStream tbs = new ByteArrayOutputStream();
        // Version 3: [0] EXPLICIT INTEGER 2
        tbs.write(new byte[]{(byte) 0xA0, 0x03, 0x02, 0x01, 0x02});

        // Serial Number: INTEGER (positive)
        byte[] serial = java.math.BigInteger.valueOf(Math.abs(now)).toByteArray();
        tbs.write(0x02);
        writeLength(tbs, serial.length);
        tbs.write(serial);

        // Signature Algorithm: SHA256withRSA OID: 1.2.840.113549.1.1.11
        byte[] sigAlg = new byte[]{0x30, 0x0D, 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B, 0x05, 0x00};
        tbs.write(sigAlg);

        // Issuer / Subject Name: RDNSequence
        byte[] name = encodeDName(dname);
        tbs.write(name); // Issuer

        // Validity: SEQUENCE of UTCTime
        ByteArrayOutputStream val = new ByteArrayOutputStream();
        writeUtcTime(val, notBefore);
        writeUtcTime(val, notAfter);
        byte[] valBytes = val.toByteArray();
        tbs.write(0x30);
        writeLength(tbs, valBytes.length);
        tbs.write(valBytes);

        // Subject: same as Issuer
        tbs.write(name);

        // SubjectPublicKeyInfo: DER from public key
        tbs.write(kp.getPublic().getEncoded());

        byte[] tbsBytes = tbs.toByteArray();
        ByteArrayOutputStream tbsSeq = new ByteArrayOutputStream();
        tbsSeq.write(0x30);
        writeLength(tbsSeq, tbsBytes.length);
        tbsSeq.write(tbsBytes);
        byte[] fullTbs = tbsSeq.toByteArray();

        // Sign TBS
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(fullTbs);
        byte[] signatureBytes = sig.sign();

        // Build final Certificate: SEQUENCE { tbs, sigAlg, BIT STRING signature }
        ByteArrayOutputStream certOut = new ByteArrayOutputStream();
        certOut.write(fullTbs);
        certOut.write(sigAlg);

        // BIT STRING for signature (0 unused bits prefix)
        certOut.write(0x03);
        writeLength(certOut, signatureBytes.length + 1);
        certOut.write(0x00);
        certOut.write(signatureBytes);

        byte[] certBytes = certOut.toByteArray();
        ByteArrayOutputStream fullCert = new ByteArrayOutputStream();
        fullCert.write(0x30);
        writeLength(fullCert, certBytes.length);
        fullCert.write(certBytes);

        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        return (java.security.cert.X509Certificate) cf.generateCertificate(new ByteArrayInputStream(fullCert.toByteArray()));
    }

    private static void writeUtcTime(OutputStream out, Date d) throws IOException {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] b = (sdf.format(d) + "Z").getBytes("US-ASCII");
        out.write(0x17); // UTCTime
        writeLength(out, b.length);
        out.write(b);
    }

    private static byte[] encodeDName(String dname) throws IOException {
        ByteArrayOutputStream cnAtv = new ByteArrayOutputStream();
        byte[] cnOid = new byte[]{0x06, 0x03, 0x55, 0x04, 0x03}; // OID 2.5.4.3 commonName
        byte[] strBytes = dname.getBytes("UTF-8");

        cnAtv.write(cnOid);
        cnAtv.write(0x0C); // UTF8String
        writeLength(cnAtv, strBytes.length);
        cnAtv.write(strBytes);

        byte[] atvBytes = cnAtv.toByteArray();
        ByteArrayOutputStream atvSeq = new ByteArrayOutputStream();
        atvSeq.write(0x30);
        writeLength(atvSeq, atvBytes.length);
        atvSeq.write(atvBytes);

        byte[] setBytes = atvSeq.toByteArray();
        ByteArrayOutputStream setSeq = new ByteArrayOutputStream();
        setSeq.write(0x31); // SET
        writeLength(setSeq, setBytes.length);
        setSeq.write(setBytes);

        byte[] rdnBytes = setSeq.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30); // SEQUENCE
        writeLength(out, rdnBytes.length);
        out.write(rdnBytes);
        return out.toByteArray();
    }
}
