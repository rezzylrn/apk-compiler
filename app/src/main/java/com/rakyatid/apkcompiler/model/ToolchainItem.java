package com.rakyatid.apkcompiler.model;

import java.io.File;

public class ToolchainItem {
    private final String id;
    private final String name;
    private final String description;
    private final File targetFile;
    private final String downloadUrl;
    private final String expectedSha256;
    private boolean installed;
    private long sizeBytes;

    public ToolchainItem(String id, String name, String description, File targetFile, String downloadUrl, String expectedSha256) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.targetFile = targetFile;
        this.downloadUrl = downloadUrl;
        this.expectedSha256 = expectedSha256;
        updateStatus();
    }

    public void updateStatus() {
        if ("ecj".equals(id)) {
            try {
                Class.forName("org.eclipse.jdt.internal.compiler.batch.Main");
                this.installed = true;
                this.sizeBytes = 3022160;
                return;
            } catch (Throwable ignored) {}
        } else if ("d8".equals(id)) {
            try {
                Class.forName("com.android.tools.r8.D8");
                this.installed = true;
                this.sizeBytes = 7378876;
                return;
            } catch (Throwable ignored) {}
        }

        if (targetFile != null && targetFile.exists()) {
            if (targetFile.isDirectory()) {
                File[] children = targetFile.listFiles();
                if (children != null && children.length > 0) {
                    this.installed = true;
                    long total = 0;
                    for (File c : children) {
                        if (c.isFile()) total += c.length();
                    }
                    this.sizeBytes = total;
                } else {
                    this.installed = false;
                    this.sizeBytes = 0;
                }
            } else if (targetFile.length() > 0) {
                this.installed = true;
                this.sizeBytes = targetFile.length();
            } else {
                this.installed = false;
                this.sizeBytes = 0;
            }
        } else {
            this.installed = false;
            this.sizeBytes = 0;
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public File getTargetFile() {
        return targetFile;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getExpectedSha256() {
        return expectedSha256;
    }

    public boolean isInstalled() {
        return installed;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getFormattedSize() {
        if (sizeBytes <= 0) return "0 B";
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024 * 1024) return String.format("%.1f KB", sizeBytes / 1024.0);
        return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
    }
}
