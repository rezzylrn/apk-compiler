package com.rakyatid.apkcompiler.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Project {
    private final String name;
    private final File rootDir;
    private final File manifestFile;
    private final File javaSrcDir;
    private final File resDir;
    private final String packageName;
    private int minSdk;
    private int targetSdk;
    private int compileSdk;
    private String customSdkPath;
    private final int javaFilesCount;
    private final int resFilesCount;
    private final boolean valid;
    private List<Dependency> dependencies = new ArrayList<>();

    public Project(String name, File rootDir, File manifestFile, File javaSrcDir, File resDir,
                   String packageName, int minSdk, int targetSdk, int javaFilesCount,
                   int resFilesCount, boolean valid) {
        this(name, rootDir, manifestFile, javaSrcDir, resDir, packageName, minSdk, targetSdk,
                Math.max(targetSdk, 34), javaFilesCount, resFilesCount, valid);
    }

    public Project(String name, File rootDir, File manifestFile, File javaSrcDir, File resDir,
                   String packageName, int minSdk, int targetSdk, int compileSdk,
                   int javaFilesCount, int resFilesCount, boolean valid) {
        this.name = name;
        this.rootDir = rootDir;
        this.manifestFile = manifestFile;
        this.javaSrcDir = javaSrcDir;
        this.resDir = resDir;
        this.packageName = packageName;
        this.minSdk = minSdk;
        this.targetSdk = targetSdk;
        this.compileSdk = compileSdk > 0 ? compileSdk : Math.max(targetSdk, 34);
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

    public void setMinSdk(int minSdk) {
        this.minSdk = minSdk;
    }

    public int getTargetSdk() {
        return targetSdk;
    }

    public void setTargetSdk(int targetSdk) {
        this.targetSdk = targetSdk;
    }

    public int getCompileSdk() {
        return compileSdk > 0 ? compileSdk : 34;
    }

    public void setCompileSdk(int compileSdk) {
        this.compileSdk = compileSdk;
    }

    public String getCustomSdkPath() {
        return customSdkPath;
    }

    public void setCustomSdkPath(String customSdkPath) {
        this.customSdkPath = customSdkPath;
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

    public List<Dependency> getDependencies() {
        return dependencies;
    }

    public void setDependencies(List<Dependency> dependencies) {
        this.dependencies = dependencies != null ? dependencies : new ArrayList<>();
    }

    public void addDependency(Dependency dependency) {
        if (dependency != null) {
            this.dependencies.add(dependency);
        }
    }
}
