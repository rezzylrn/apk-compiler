package com.rakyatid.apkcompiler.engine;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ApkFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.rakyatid.apkcompiler.fileprovider";

    public static Uri getUriForFile(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .path(file.getAbsolutePath())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File validateAndGetAllowedFile(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) {
            throw new FileNotFoundException("URI path is null");
        }

        File file = new File(path);
        Context ctx = getContext();
        if (ctx == null) {
            throw new SecurityException("Provider context not available");
        }

        File allowedExternal = new File(ctx.getExternalFilesDir(null), "outputs");
        File allowedInternal = new File(ctx.getFilesDir(), "outputs");

        try {
            String canonicalTarget = file.getCanonicalPath();
            String canonicalExt = allowedExternal.getCanonicalPath();
            String canonicalInt = allowedInternal.getCanonicalPath();

            boolean isWithinAllowed = canonicalTarget.startsWith(canonicalExt + File.separator) ||
                                     canonicalTarget.startsWith(canonicalInt + File.separator);

            if (!isWithinAllowed) {
                throw new SecurityException("Akses direktori ditolak: Path traversal terdeteksi!");
            }

            if (!file.getName().endsWith(".apk")) {
                throw new SecurityException("Akses ditolak: Hanya file .apk yang diizinkan!");
            }

            if (!file.exists()) {
                throw new FileNotFoundException("File tidak ditemukan: " + file.getName());
            }

            return file;
        } catch (IOException e) {
            throw new SecurityException("Gagal validasi canonical path: " + e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new SecurityException("Hanya mode read-only (r) yang diizinkan");
        }
        File file = validateAndGetAllowedFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String path = uri.getPath();
        if (path != null && path.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = validateAndGetAllowedFile(uri);
        } catch (Exception e) {
            return null;
        }

        String[] cols = projection != null ? projection : new String[]{
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
        };
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert not supported");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Delete not supported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update not supported");
    }
}
