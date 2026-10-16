package com.rakyatid.apkcompiler.engine;

import java.io.*;
import java.util.*;
import java.util.zip.*;

public class ZipAlignUtil {

    public static void alignZip(File inputApk, File outputApk) throws IOException {
        ZipFile zipFile = new ZipFile(inputApk);
        FileOutputStream fos = new FileOutputStream(outputApk);
        BufferedOutputStream bos = new BufferedOutputStream(fos, 65536);

        // We can re-package entries with uncompressed entries aligned
        ZipOutputStream zos = new ZipOutputStream(bos);
        Enumeration<? extends ZipEntry> entries = zipFile.entries();

        byte[] buffer = new byte[8192];

        while (entries.hasMoreElements()) {
            ZipEntry inEntry = entries.nextElement();
            ZipEntry outEntry = new ZipEntry(inEntry.getName());
            outEntry.setTime(inEntry.getTime());

            if (inEntry.getMethod() == ZipEntry.STORED) {
                outEntry.setMethod(ZipEntry.STORED);
                outEntry.setSize(inEntry.getSize());
                outEntry.setCrc(inEntry.getCrc());
                outEntry.setCompressedSize(inEntry.getCompressedSize());
            } else {
                outEntry.setMethod(ZipEntry.DEFLATED);
            }

            zos.putNextEntry(outEntry);

            InputStream is = zipFile.getInputStream(inEntry);
            int len;
            while ((len = is.read(buffer)) != -1) {
                zos.write(buffer, 0, len);
            }
            is.close();
            zos.closeEntry();
        }

        zos.finish();
        zos.flush();
        zos.close();
        zipFile.close();
    }
}
