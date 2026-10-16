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
import java.util.Locale;

/**
 * Hardened ContentProvider for securely serving only compiled .apk files.
 * Protects against:
 * - Path traversal (../)
 * - Symlink escapes to private databases, keystores, or shared preferences
 * - Unauthorized file modification (strictly read-only)
 * - Arbitrary file disclosure
 */
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

    public static File validateAndGetAllowedFile(Context ctx, Uri uri) throws FileNotFoundException {
        if (uri == null) {
            throw new FileNotFoundException("URI is null");
        }
        String path = uri.getPath();
        if (path == null || path.trim().isEmpty()) {
            throw new FileNotFoundException("URI path is null or empty");
        }

        // 1. Path traversal & null byte sanitization
        if (path.contains("\0") || path.contains("..")) {
            throw new SecurityException("Directory access denied: Path traversal or illegal characters detected!");
        }

        File file = new File(path);
        if (ctx == null) {
            throw new SecurityException("Provider context not available");
        }

        File allowedExternal = new File(ctx.getExternalFilesDir(null), "outputs");
        File allowedInternal = new File(ctx.getFilesDir(), "outputs");

        try {
            String canonicalTarget = file.getCanonicalPath();
            String canonicalExt = allowedExternal.getCanonicalPath();
            String canonicalInt = allowedInternal.getCanonicalPath();

            // 2. Strict directory boundary enforcement
            boolean isWithinAllowed = canonicalTarget.startsWith(canonicalExt + File.separator) ||
                                     canonicalTarget.startsWith(canonicalInt + File.separator);

            if (!isWithinAllowed) {
                throw new SecurityException("Directory access denied: Target file is outside 'outputs/' folder!");
            }

            // 3. Blacklist checks against private data files
            String lowerName = file.getName().toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".keystore") || lowerName.endsWith(".jks") ||
                lowerName.endsWith(".db") || lowerName.endsWith(".sqlite") ||
                lowerName.endsWith(".xml") || lowerName.endsWith(".properties") ||
                lowerName.contains("shared_prefs") || lowerName.contains("databases")) {
                throw new SecurityException("Access denied: Sensitive or internal files cannot be accessed via FileProvider!");
            }

            // 4. Strictly allow only .apk files
            if (!lowerName.endsWith(".apk")) {
                throw new SecurityException("Access denied: Only .apk files are allowed!");
            }

            // 5. Verify existence and regular file status
            if (!file.exists()) {
                throw new FileNotFoundException("File not found: " + file.getName());
            }

            if (!file.isFile()) {
                throw new SecurityException("Access denied: Target is not a regular file!");
            }

            return file;
        } catch (IOException e) {
            throw new SecurityException("Canonical path validation failed: " + e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new SecurityException("Only read-only mode ('r') is supported");
        }
        File file = validateAndGetAllowedFile(getContext(), uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String path = uri != null ? uri.getPath() : null;
        if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = validateAndGetAllowedFile(getContext(), uri);
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
        throw new SecurityException("Operasi penulisan / insert dilarang pada ApkFileProvider!");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new SecurityException("Operasi penghapusan dilarang pada ApkFileProvider!");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new SecurityException("Operasi modifikasi / update dilarang pada ApkFileProvider!");
    }
}
