package com.rakyatid.apkcompiler.engine;

import java.io.*;
import java.util.*;

/**
 * Pure Java ZipAlign utility.
 * Reads raw compressed/uncompressed payloads directly from ZIP file headers
 * and pads STORED entries to 4-byte boundaries (or 4096-byte boundaries for .so),
 * preserving original entry compression, CRC, timestamps, and without data descriptors.
 * Fully compliant with Android apksig and Android OS package manager.
 */
public class ZipAlignUtil {

    public static void alignZip(File srcZip, File dstZip) throws IOException {
        try (RandomAccessFile src = new RandomAccessFile(srcZip, "r");
             FileOutputStream fos = new FileOutputStream(dstZip);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {

            class Rec {
                String name;
                int method;
                int time;
                long crc;
                long csize;
                long usize;
                long localHeaderOffset;
                byte[] extra;
                byte[] payload;
            }

            List<Rec> records = new ArrayList<>();
            long totalLen = src.length();
            CountingOutputStream cos = new CountingOutputStream(bos);

            while (src.getFilePointer() < totalLen) {
                int sig = readIntLE(src);
                if (sig == 0x04034b50) { // Local File Header
                    Rec r = new Rec();
                    src.skipBytes(4); // version needed + flags
                    r.method = readShortLE(src);
                    r.time = readIntLE(src);
                    r.crc = readIntLE(src) & 0xFFFFFFFFL;
                    r.csize = readIntLE(src) & 0xFFFFFFFFL;
                    r.usize = readIntLE(src) & 0xFFFFFFFFL;
                    int nlen = readShortLE(src);
                    int elen = readShortLE(src);

                    byte[] nameBytes = new byte[nlen];
                    src.readFully(nameBytes);
                    r.name = new String(nameBytes, "UTF-8");

                    byte[] origExtra = new byte[elen];
                    src.readFully(origExtra);

                    r.payload = new byte[(int) r.csize];
                    src.readFully(r.payload);

                    int align = 4;
                    if (r.name.endsWith(".so")) {
                        align = 4096;
                    }

                    int padding = 0;
                    if (r.method == 0) { // STORED (uncompressed)
                        long dataOffset = cos.getCount() + 30 + nlen + origExtra.length;
                        padding = (int) ((align - (dataOffset % align)) % align);
                    }

                    byte[] newExtra = new byte[origExtra.length + padding];
                    System.arraycopy(origExtra, 0, newExtra, 0, origExtra.length);
                    r.extra = newExtra;

                    r.localHeaderOffset = cos.getCount();

                    // Write Local File Header
                    cos.writeIntLE(0x04034b50);
                    cos.writeShortLE(20); // version needed
                    cos.writeShortLE(0);  // flags (No data descriptor)
                    cos.writeShortLE(r.method);
                    cos.writeIntLE(r.time);
                    cos.writeIntLE((int) r.crc);
                    cos.writeIntLE((int) r.csize);
                    cos.writeIntLE((int) r.usize);
                    cos.writeShortLE(nameBytes.length);
                    cos.writeShortLE(r.extra.length);
                    cos.write(nameBytes);
                    cos.write(r.extra);
                    cos.write(r.payload);

                    records.add(r);
                } else if (sig == 0x02014b50) { // Central Directory starts
                    break;
                } else {
                    break;
                }
            }

            long cdOffset = cos.getCount();

            // Write Central Directory Records
            for (Rec r : records) {
                byte[] nameBytes = r.name.getBytes("UTF-8");
                cos.writeIntLE(0x02014b50);
                cos.writeShortLE(20); // version made by
                cos.writeShortLE(20); // version needed
                cos.writeShortLE(0);  // flags
                cos.writeShortLE(r.method);
                cos.writeIntLE(r.time);
                cos.writeIntLE((int) r.crc);
                cos.writeIntLE((int) r.csize);
                cos.writeIntLE((int) r.usize);
                cos.writeShortLE(nameBytes.length);
                cos.writeShortLE(r.extra.length);
                cos.writeShortLE(0); // comment length
                cos.writeShortLE(0); // disk start
                cos.writeShortLE(0); // internal attr
                cos.writeIntLE(0);   // external attr
                cos.writeIntLE((int) r.localHeaderOffset);
                cos.write(nameBytes);
                cos.write(r.extra);
            }

            long cdSize = cos.getCount() - cdOffset;

            // Write End of Central Directory Record (EOCD)
            cos.writeIntLE(0x06054b50);
            cos.writeShortLE(0);
            cos.writeShortLE(0);
            cos.writeShortLE(records.size());
            cos.writeShortLE(records.size());
            cos.writeIntLE((int) cdSize);
            cos.writeIntLE((int) cdOffset);
            cos.writeShortLE(0);
            cos.flush();
        }
    }

    private static int readShortLE(RandomAccessFile raf) throws IOException {
        int b1 = raf.read();
        int b2 = raf.read();
        if ((b1 | b2) < 0) throw new EOFException();
        return (b1 & 0xFF) | ((b2 & 0xFF) << 8);
    }

    private static int readIntLE(RandomAccessFile raf) throws IOException {
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        int b4 = raf.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new EOFException();
        return (b1 & 0xFF) | ((b2 & 0xFF) << 8) | ((b3 & 0xFF) << 16) | ((b4 & 0xFF) << 24);
    }

    private static class CountingOutputStream extends FilterOutputStream {
        private long count = 0;

        public CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        public long getCount() {
            return count;
        }

        public void writeShortLE(int v) throws IOException {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            count += 2;
        }

        public void writeIntLE(int v) throws IOException {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            out.write((v >>> 16) & 0xFF);
            out.write((v >>> 24) & 0xFF);
            count += 4;
        }
    }
}
