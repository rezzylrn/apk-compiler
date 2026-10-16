package com.rakyatid.apkcompiler.model;

import java.io.File;

public class Project {
    private final String name;
    private final File rootDir;
    private final File manifestFile;
    private final File javaSrcDir;
    private final File resDir;
    private final String packageName;
    private final int minSdk;
    private final int targetSdk;
    private final int javaFilesCount;
    private final int resFilesCount;
    private final boolean valid;

    public Project(String name, File rootDir, File manifestFile, File javaSrcDir, File resDir,
                   String packageName, int minSdk, int targetSdk, int javaFilesCount,
                   int resFilesCount, boolean valid) {
        this.name = name;
        this.rootDir = rootDir;
        this.manifestFile = manifestFile;
        this.javaSrcDir = javaSrcDir;
        this.resDir = resDir;
        this.packageName = packageName;
        this.minSdk = minSdk;
        this.targetSdk = targetSdk;
        this.javaFilesCount = javaFilesCount;
        this.resFilesCount = resFilesCount;
        this.valid = valid;
    }

    public String getName() {
        return name;
    }

    public File getRootDir() {
        return rootDir;
    }

    public File getManifestFile() {
        return manifestFile;
    }

    public File getJavaSrcDir() {
        return javaSrcDir;
    }

    public File getResDir() {
        return resDir;
    }

    public String getPackageName() {
        return packageName;
    }

    public int getMinSdk() {
        return minSdk;
    }

    public int getTargetSdk() {
        return targetSdk;
    }

    public int getJavaFilesCount() {
        return javaFilesCount;
    }

    public int getResFilesCount() {
        return resFilesCount;
    }

    public boolean isValid() {
        return valid;
    }
}
