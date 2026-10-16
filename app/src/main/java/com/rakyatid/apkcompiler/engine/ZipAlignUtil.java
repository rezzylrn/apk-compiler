package com.rakyatid.apkcompiler.engine;

import java.io.*;
import java.util.*;

/**
 * Pure Java ZipAlign utility.
 * Reads entries authoritative metadata directly from Central Directory (supporting
 * data descriptors / bit 3 flags from streaming ZipOutputStream),
 * preserves entry compression, CRC, timestamps, Unix file permissions (external attributes),
 * and aligns STORED entries to 4-byte boundaries (and 4096-byte boundaries for .so).
 * Fully compliant with Android apksig and Android OS package manager.
 */
public class ZipAlignUtil {

    private static class EntryInfo {
        int versionMadeBy;
        int versionNeeded;
        int flags;
        int method;
        int time;
        long crc;
        long csize;
        long usize;
        int diskStart;
        int internalAttr;
        long externalAttr;
        long localHeaderOffset;
        String name;
        byte[] extra;
        byte[] comment;

        // Output fields
        long newLocalHeaderOffset;
        byte[] newExtra;
    }

    public static void alignZip(File srcZip, File dstZip) throws IOException {
        try (RandomAccessFile src = new RandomAccessFile(srcZip, "r");
             FileOutputStream fos = new FileOutputStream(dstZip);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 65536)) {

            long fileLen = src.length();
            if (fileLen < 22) throw new IOException("File too short for ZIP");

            // 1. Locate End of Central Directory (EOCD)
            long maxSearch = Math.min(fileLen, 65535 + 22);
            long searchStart = fileLen - maxSearch;
            long eocdOffset = -1;

            byte[] searchBuf = new byte[(int) maxSearch];
            src.seek(searchStart);
            src.readFully(searchBuf);

            for (int i = searchBuf.length - 22; i >= 0; i--) {
                if (searchBuf[i] == 0x50 && searchBuf[i+1] == 0x4b &&
                    searchBuf[i+2] == 0x05 && searchBuf[i+3] == 0x06) {
                    eocdOffset = searchStart + i;
                    break;
                }
            }

            if (eocdOffset == -1) {
                throw new IOException("EOCD signature not found in " + srcZip.getName());
            }

            src.seek(eocdOffset);
            int eocdSig = readIntLE(src);
            int diskNum = readShortLE(src);
            int cdStartDisk = readShortLE(src);
            int numEntriesDisk = readShortLE(src);
            int totalEntries = readShortLE(src);
            long cdSize = readIntLE(src) & 0xFFFFFFFFL;
            long cdOffset = readIntLE(src) & 0xFFFFFFFFL;
            int commentLen = readShortLE(src);
            byte[] eocdComment = new byte[commentLen];
            src.readFully(eocdComment);

            // 2. Read Central Directory entries
            src.seek(cdOffset);
            List<EntryInfo> entries = new ArrayList<>(totalEntries);

            for (int i = 0; i < totalEntries; i++) {
                int sig = readIntLE(src);
                if (sig != 0x02014b50) {
                    throw new IOException("Expected Central Directory signature at entry " + i);
                }
                EntryInfo e = new EntryInfo();
                e.versionMadeBy = readShortLE(src);
                e.versionNeeded = readShortLE(src);
                e.flags = readShortLE(src);
                e.method = readShortLE(src);
                e.time = readIntLE(src);
                e.crc = readIntLE(src) & 0xFFFFFFFFL;
                e.csize = readIntLE(src) & 0xFFFFFFFFL;
                e.usize = readIntLE(src) & 0xFFFFFFFFL;
                int nlen = readShortLE(src);
                int elen = readShortLE(src);
                int clen = readShortLE(src);
                e.diskStart = readShortLE(src);
                e.internalAttr = readShortLE(src);
                e.externalAttr = readIntLE(src) & 0xFFFFFFFFL;
                e.localHeaderOffset = readIntLE(src) & 0xFFFFFFFFL;

                byte[] nameBytes = new byte[nlen];
                src.readFully(nameBytes);
                e.name = new String(nameBytes, "UTF-8");

                e.extra = new byte[elen];
                src.readFully(e.extra);

                e.comment = new byte[clen];
                src.readFully(e.comment);

                entries.add(e);
            }

            // 3. Write entries sequentially to output
            CountingOutputStream cos = new CountingOutputStream(bos);
            byte[] copyBuf = new byte[65536];

            for (EntryInfo e : entries) {
                // Seek to local file header
                src.seek(e.localHeaderOffset);
                int locSig = readIntLE(src);
                if (locSig != 0x04034b50) {
                    throw new IOException("Invalid local header signature for " + e.name);
                }
                src.skipBytes(22); // version, flags, method, time, crc, csize, usize
                int locNlen = readShortLE(src);
                int locElen = readShortLE(src);
                src.skipBytes(locNlen); // skip name
                src.skipBytes(locElen); // skip local extra

                byte[] nameBytes = e.name.getBytes("UTF-8");

                // Determine alignment
                int align = 4;
                if (e.name.endsWith(".so")) {
                    align = 4096;
                }

                int padding = 0;
                if (e.method == 0) { // STORED (uncompressed)
                    long dataOffset = cos.getCount() + 30 + nameBytes.length + e.extra.length;
                    padding = (int) ((align - (dataOffset % align)) % align);
                }

                byte[] newExtra = new byte[e.extra.length + padding];
                if (e.extra.length > 0) {
                    System.arraycopy(e.extra, 0, newExtra, 0, e.extra.length);
                }
                e.newExtra = newExtra;
                e.newLocalHeaderOffset = cos.getCount();

                // Write Local File Header (clear data descriptor flag)
                cos.writeIntLE(0x04034b50);
                cos.writeShortLE(e.versionNeeded);
                cos.writeShortLE(e.flags & ~0x08); // clear bit 3
                cos.writeShortLE(e.method);
                cos.writeIntLE(e.time);
                cos.writeIntLE((int) e.crc);
                cos.writeIntLE((int) e.csize);
                cos.writeIntLE((int) e.usize);
                cos.writeShortLE(nameBytes.length);
                cos.writeShortLE(e.newExtra.length);
                cos.write(nameBytes);
                cos.write(e.newExtra);

                // Copy payload of exactly csize bytes
                long remaining = e.csize;
                while (remaining > 0) {
                    int toRead = (int) Math.min(copyBuf.length, remaining);
                    src.readFully(copyBuf, 0, toRead);
                    cos.write(copyBuf, 0, toRead);
                    remaining -= toRead;
                }
            }

            long newCdOffset = cos.getCount();

            // 4. Write Central Directory
            for (EntryInfo e : entries) {
                byte[] nameBytes = e.name.getBytes("UTF-8");
                cos.writeIntLE(0x02014b50);
                cos.writeShortLE(e.versionMadeBy);
                cos.writeShortLE(e.versionNeeded);
                cos.writeShortLE(e.flags & ~0x08);
                cos.writeShortLE(e.method);
                cos.writeIntLE(e.time);
                cos.writeIntLE((int) e.crc);
                cos.writeIntLE((int) e.csize);
                cos.writeIntLE((int) e.usize);
                cos.writeShortLE(nameBytes.length);
                cos.writeShortLE(e.newExtra.length);
                cos.writeShortLE(e.comment.length);
                cos.writeShortLE(e.diskStart);
                cos.writeShortLE(e.internalAttr);
                cos.writeIntLE((int) e.externalAttr);
                cos.writeIntLE((int) e.newLocalHeaderOffset);
                cos.write(nameBytes);
                cos.write(e.newExtra);
                cos.write(e.comment);
            }

            long newCdSize = cos.getCount() - newCdOffset;

            // 5. Write EOCD
            cos.writeIntLE(0x06054b50);
            cos.writeShortLE(diskNum);
            cos.writeShortLE(cdStartDisk);
            cos.writeShortLE(entries.size());
            cos.writeShortLE(entries.size());
            cos.writeIntLE((int) newCdSize);
            cos.writeIntLE((int) newCdOffset);
            cos.writeShortLE(eocdComment.length);
            cos.write(eocdComment);
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
