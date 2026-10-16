package com.rakyatid.apkcompiler.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Dependency {
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String type; // "aar" or "jar"
    private File file;
    private File extractedDir;
    private File compiledResZip;
    private String manifestPackage;

    public Dependency(String groupId, String artifactId, String version, String type) {
        this.groupId = groupId != null ? groupId.trim() : "";
        this.artifactId = artifactId != null ? artifactId.trim() : "";
        this.version = version != null ? version.trim() : "";
        this.type = (type != null && !type.trim().isEmpty()) ? type.trim().toLowerCase() : "jar";
    }

    public Dependency(File localFile) {
        this.file = localFile;
        String name = localFile.getName();
        if (name.endsWith(".aar")) {
            this.type = "aar";
            name = name.substring(0, name.length() - 4);
        } else if (name.endsWith(".jar")) {
            this.type = "jar";
            name = name.substring(0, name.length() - 4);
        } else {
            this.type = "jar";
        }

        int lastHyphen = name.lastIndexOf('-');
        if (lastHyphen > 0 && lastHyphen < name.length() - 1 && Character.isDigit(name.charAt(lastHyphen + 1))) {
            this.artifactId = name.substring(0, lastHyphen);
            this.version = name.substring(lastHyphen + 1);
        } else {
            this.artifactId = name;
            this.version = "1.0.0";
        }
        this.groupId = "local";
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getType() {
        return type;
    }

    public boolean isAar() {
        return "aar".equalsIgnoreCase(type);
    }

    public boolean isJar() {
        return "jar".equalsIgnoreCase(type);
    }

    public String getCoordinates() {
        return groupId + ":" + artifactId + ":" + version;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public File getExtractedDir() {
        return extractedDir;
    }

    public void setExtractedDir(File extractedDir) {
        this.extractedDir = extractedDir;
    }

    public File getCompiledResZip() {
        return compiledResZip;
    }

    public void setCompiledResZip(File compiledResZip) {
        this.compiledResZip = compiledResZip;
    }

    public String getManifestPackage() {
        return manifestPackage;
    }

    public void setManifestPackage(String manifestPackage) {
        this.manifestPackage = manifestPackage;
    }

    public List<File> getClasspathJars() {
        List<File> jars = new ArrayList<>();
        if (isJar() && file != null && file.exists()) {
            jars.add(file);
        } else if (isAar() && extractedDir != null && extractedDir.exists()) {
            File cj = new File(extractedDir, "classes.jar");
            if (cj.exists()) {
                jars.add(cj);
            }
            File libs = new File(extractedDir, "libs");
            if (libs.isDirectory()) {
                File[] list = libs.listFiles((d, n) -> n.endsWith(".jar"));
                if (list != null) {
                    for (File f : list) {
                        jars.add(f);
                    }
                }
            }
        }
        return jars;
    }

    @Override
    public String toString() {
        return getCoordinates() + "@" + type;
    }
}
