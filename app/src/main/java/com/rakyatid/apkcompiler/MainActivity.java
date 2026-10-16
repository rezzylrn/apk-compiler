package com.rakyatid.apkcompiler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import com.rakyatid.apkcompiler.engine.*;
import com.rakyatid.apkcompiler.model.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ToolchainManager toolchainManager;
    private ApkBuilderEngine builderEngine;

    // Active project state
    private Project activeProject;
    private File lastBuiltApk;

    // UI - Tabs
    private TextView tabProjects, tabBuild, tabToolchain, tabFiles;
    private View viewTabProjects, viewTabBuild, viewTabToolchain, viewTabFiles;

    // UI - Header
    private TextView badgeToolchainHeader;

    // UI - Tab 1 (Projects)
    private TextView tvActiveProjectName, tvActiveProjectPkg, tvActiveSdk, tvActiveJavaCount, tvActiveResCount;
    private LinearLayout containerProjectsList;
    private TextView tvEmptyProjects;

    // UI - Tab 2 (Build)
    private Button btnStartCompile;
    private ProgressBar progressBuild;
    private TextView tvBuildStep;
    private View cardBuildOutput;
    private TextView tvOutputSize, tvOutputPath;
    private Button btnInstallApk, btnShareApk;
    private TextView tvConsoleLog;
    private ScrollView scrollConsole;

    // UI - Tab 3 (Toolchain)
    private LinearLayout containerToolchainItems;
    private Button btnInstallToolchain;
    private ProgressBar progressToolchain;
    private TextView tvToolchainStatus;

    // UI - Tab 4 (Files)
    private TextView tvCurrentDirPath;
    private LinearLayout containerFileList;
    private File currentExplorerDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            mainHandler.post(() -> {
                showCrashDialog("Aplikasi Mengalami Crash", throwable);
            });
        });
        setContentView(R.layout.activity_main);

        toolchainManager = new ToolchainManager(this);
        builderEngine = new ApkBuilderEngine(this, toolchainManager);

        initViews();
        setupTabs();
        setupActions();
        refreshToolchainUI();
        refreshProjectsList();
    }

    private void initViews() {
        tabProjects = findViewById(R.id.tab_projects);
        tabBuild = findViewById(R.id.tab_build);
        tabToolchain = findViewById(R.id.tab_toolchain);
        tabFiles = findViewById(R.id.tab_files);

        viewTabProjects = findViewById(R.id.view_tab_projects);
        viewTabBuild = findViewById(R.id.view_tab_build);
        viewTabToolchain = findViewById(R.id.view_tab_toolchain);
        viewTabFiles = findViewById(R.id.view_tab_files);

        badgeToolchainHeader = findViewById(R.id.badge_toolchain_header);

        tvActiveProjectName = findViewById(R.id.tv_active_project_name);
        tvActiveProjectPkg = findViewById(R.id.tv_active_project_pkg);
        tvActiveSdk = findViewById(R.id.tv_active_sdk);
        tvActiveJavaCount = findViewById(R.id.tv_active_java_count);
        tvActiveResCount = findViewById(R.id.tv_active_res_count);
        containerProjectsList = findViewById(R.id.container_projects_list);
        tvEmptyProjects = findViewById(R.id.tv_empty_projects);

        btnStartCompile = findViewById(R.id.btn_start_compile);
        progressBuild = findViewById(R.id.progress_build);
        tvBuildStep = findViewById(R.id.tv_build_step);
        cardBuildOutput = findViewById(R.id.card_build_output);
        tvOutputSize = findViewById(R.id.tv_output_size);
        tvOutputPath = findViewById(R.id.tv_output_path);
        btnInstallApk = findViewById(R.id.btn_install_apk);
        btnShareApk = findViewById(R.id.btn_share_apk);
        tvConsoleLog = findViewById(R.id.tv_console_log);
        scrollConsole = findViewById(R.id.scroll_console);

        containerToolchainItems = findViewById(R.id.container_toolchain_items);
        btnInstallToolchain = findViewById(R.id.btn_install_toolchain);
        progressToolchain = findViewById(R.id.progress_toolchain);
        tvToolchainStatus = findViewById(R.id.tv_toolchain_status);

        tvCurrentDirPath = findViewById(R.id.tv_current_dir_path);
        containerFileList = findViewById(R.id.container_file_list);
    }

    private void setupTabs() {
        tabProjects.setOnClickListener(v -> switchTab(0));
        tabBuild.setOnClickListener(v -> switchTab(1));
        tabToolchain.setOnClickListener(v -> switchTab(2));
        tabFiles.setOnClickListener(v -> switchTab(3));
    }

    private void switchTab(int index) {
        viewTabProjects.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        viewTabBuild.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        viewTabToolchain.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        viewTabFiles.setVisibility(index == 3 ? View.VISIBLE : View.GONE);

        int primaryColor = getColor(R.color.primary_accent);
        int secondaryColor = getColor(R.color.text_secondary);

        tabProjects.setTextColor(index == 0 ? primaryColor : secondaryColor);
        tabBuild.setTextColor(index == 1 ? primaryColor : secondaryColor);
        tabToolchain.setTextColor(index == 2 ? primaryColor : secondaryColor);
        tabFiles.setTextColor(index == 3 ? primaryColor : secondaryColor);

        if (index == 3 && activeProject != null) {
            loadExplorerDirectory(activeProject.getRootDir());
        }
    }

    private void setupActions() {
        findViewById(R.id.btn_open_clone_dialog).setOnClickListener(v -> showCloneDialog());
        findViewById(R.id.btn_open_folder_picker).setOnClickListener(v -> showFolderPicker());
        findViewById(R.id.btn_create_sample_project).setOnClickListener(v -> createOfflineSampleProject());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> tvConsoleLog.setText(""));
        badgeToolchainHeader.setOnClickListener(v -> switchTab(2));
        View ivLogo = findViewById(R.id.iv_header_logo);
        if (ivLogo != null) {
            ivLogo.setOnClickListener(v -> showAboutDialog());
        }

        btnStartCompile.setOnClickListener(v -> startCompile());

        btnInstallApk.setOnClickListener(v -> {
            if (lastBuiltApk != null && lastBuiltApk.exists()) {
                installApkFile(lastBuiltApk);
            }
        });

        btnShareApk.setOnClickListener(v -> {
            if (lastBuiltApk != null && lastBuiltApk.exists()) {
                shareApkFile(lastBuiltApk);
            }
        });

        btnInstallToolchain.setOnClickListener(v -> installToolchainDependencies());

        findViewById(R.id.btn_file_up).setOnClickListener(v -> {
            if (currentExplorerDir != null && currentExplorerDir.getParentFile() != null) {
                loadExplorerDirectory(currentExplorerDir.getParentFile());
            }
        });
    }

    private void refreshProjectsList() {
        containerProjectsList.removeAllViews();
        File projectsDir = getProjectsDir();
        File[] subdirs = projectsDir.listFiles(File::isDirectory);

        if (subdirs == null || subdirs.length == 0) {
            tvEmptyProjects.setVisibility(View.VISIBLE);
            return;
        }

        tvEmptyProjects.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);

        for (File dir : subdirs) {
            Project p = ProjectScanner.scan(dir);
            if (p == null) continue;

            View itemView = inflater.inflate(R.layout.item_project, containerProjectsList, false);
            TextView tvName = itemView.findViewById(R.id.tv_item_project_name);
            TextView tvPkg = itemView.findViewById(R.id.tv_item_project_pkg);
            TextView tvStats = itemView.findViewById(R.id.tv_item_project_stats);
            Button btnSelect = itemView.findViewById(R.id.btn_item_select);
            Button btnDelete = itemView.findViewById(R.id.btn_item_delete);

            tvName.setText(p.getName());
            tvPkg.setText(p.getPackageName() != null ? p.getPackageName() : "Bukan proyek Android standar");
            tvStats.setText(p.getJavaFilesCount() + " java / " + p.getResFilesCount() + " res");

            btnSelect.setOnClickListener(v -> {
                setActiveProject(p);
                switchTab(1); // Langsung pindah ke tab kompilasi
            });

            btnDelete.setOnClickListener(v -> {
                deleteRecursive(dir);
                refreshProjectsList();
            });

            containerProjectsList.addView(itemView);

            // Auto-select first project jika belum ada
            if (activeProject == null && p.isValid()) {
                setActiveProject(p);
            }
        }
    }

    private void setActiveProject(Project p) {
        this.activeProject = p;
        tvActiveProjectName.setText(p.getName());
        tvActiveProjectPkg.setText(p.getPackageName());
        tvActiveSdk.setText(p.getMinSdk() + " / " + p.getTargetSdk());
        tvActiveJavaCount.setText(String.valueOf(p.getJavaFilesCount()));
        tvActiveResCount.setText(String.valueOf(p.getResFilesCount()));
        logToConsole("[PROYEK] Terpilih: " + p.getName() + " (" + p.getPackageName() + ")");
    }

    private void showCloneDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_clone, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        EditText etUrl = dialogView.findViewById(R.id.et_repo_url);
        EditText etBranch = dialogView.findViewById(R.id.et_branch);
        ProgressBar prog = dialogView.findViewById(R.id.progress_clone);
        TextView tvStatus = dialogView.findViewById(R.id.tv_clone_status);
        Button btnSubmit = dialogView.findViewById(R.id.btn_clone_submit);
        Button btnCancel = dialogView.findViewById(R.id.btn_clone_cancel);
        Button btnPresetFlashlight = dialogView.findViewById(R.id.btn_preset_flashlight);
        Button btnPresetCompass = dialogView.findViewById(R.id.btn_preset_compass);

        if (btnPresetFlashlight != null) {
            btnPresetFlashlight.setOnClickListener(v -> {
                etUrl.setText("https://github.com/sanbeg/flashlight");
                etBranch.setText("studio");
            });
        }
        if (btnPresetCompass != null) {
            btnPresetCompass.setOnClickListener(v -> {
                etUrl.setText("https://github.com/iutinvg/compass");
                etBranch.setText("master");
            });
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSubmit.setOnClickListener(v -> {
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                etUrl.setError("URL wajib diisi");
                return;
            }
            if (!SecurityAuditor.isValidGitUrl(url)) {
                etUrl.setError("URL tidak valid atau tidak aman. Gunakan format https://github.com/owner/repo");
                return;
            }

            String branch = etBranch.getText().toString().trim();
            if (!branch.isEmpty() && !SecurityAuditor.isValidBranch(branch)) {
                etBranch.setError("Nama branch tidak valid!");
                return;
            }

            btnSubmit.setEnabled(false);
            btnCancel.setEnabled(false);
            prog.setVisibility(View.VISIBLE);
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText("Menghubungkan ke GitHub...");

            File outDir = getProjectsDir();

            GitRepoCloner.cloneGitHub(url, branch, outDir, new GitRepoCloner.CloneListener() {
                @Override
                public void onProgress(String message, int percentage) {
                    mainHandler.post(() -> {
                        prog.setProgress(percentage);
                        tvStatus.setText(message);
                    });
                }

                @Override
                public void onSuccess(File projectDir) {
                    mainHandler.post(() -> {
                        dialog.dismiss();
                        refreshProjectsList();
                        Project scanned = ProjectScanner.scan(projectDir);
                        if (scanned != null) {
                            setActiveProject(scanned);
                        }
                        switchTab(1);
                        Toast.makeText(MainActivity.this, "Clone sukses: " + projectDir.getName(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onError(String error) {
                    mainHandler.post(() -> {
                        btnSubmit.setEnabled(true);
                        btnCancel.setEnabled(true);
                        prog.setVisibility(View.GONE);
                        tvStatus.setText("Error: " + error);
                    });
                }
            });
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showFolderPicker() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_picker, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvPath = dialogView.findViewById(R.id.tv_picker_current_path);
        LinearLayout containerDirs = dialogView.findViewById(R.id.container_picker_dirs);
        TextView tvEmpty = dialogView.findViewById(R.id.tv_picker_empty);
        Button btnUp = dialogView.findViewById(R.id.btn_picker_up);
        Button btnChoose = dialogView.findViewById(R.id.btn_picker_choose);
        Button btnCancel = dialogView.findViewById(R.id.btn_picker_cancel);

        File rootStorage = android.os.Environment.getExternalStorageDirectory();
        if (rootStorage == null || !rootStorage.exists()) {
            rootStorage = getProjectsDir();
        }
        File[] currentDirRef = new File[]{rootStorage};

        Runnable updateDirs = new Runnable() {
            @Override
            public void run() {
                File dir = currentDirRef[0];
                tvPath.setText(dir.getAbsolutePath());
                containerDirs.removeAllViews();

                File[] list = dir.listFiles(File::isDirectory);
                if (list != null && list.length > 0) {
                    if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);
                    java.util.Arrays.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

                    LayoutInflater inflater = LayoutInflater.from(MainActivity.this);
                    for (File sub : list) {
                        View row = inflater.inflate(R.layout.item_picker_dir, containerDirs, false);
                        TextView tvDirName = row.findViewById(R.id.tv_picker_dir_name);
                        tvDirName.setText(sub.getName());

                        row.setOnClickListener(v -> {
                            currentDirRef[0] = sub;
                            run();
                        });
                        containerDirs.addView(row);
                    }
                } else {
                    if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
                }
            }
        };

        btnUp.setOnClickListener(v -> {
            File parent = currentDirRef[0].getParentFile();
            if (parent != null) {
                try {
                    String parentCanon = parent.getCanonicalPath();
                    String appData = getApplicationInfo().dataDir;
                    if (appData != null && parentCanon.startsWith(appData)) {
                        Toast.makeText(MainActivity.this, "Akses ke folder internal aplikasi dibatasi", Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (Exception ignored) {}
                currentDirRef[0] = parent;
                updateDirs.run();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnChoose.setOnClickListener(v -> {
            File chosen = currentDirRef[0];
            Project scanned = ProjectScanner.scan(chosen);
            if (scanned != null && scanned.isValid()) {
                setActiveProject(scanned);
                switchTab(1);
                dialog.dismiss();
            } else {
                Toast.makeText(MainActivity.this, "Folder bukan proyek Android yang valid (AndroidManifest.xml tidak ditemukan)", Toast.LENGTH_SHORT).show();
            }
        });

        updateDirs.run();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.92);
            dialog.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showCrashDialog(String title, Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String stackTrace = sw.toString();
        logToConsole("[CRASH] " + stackTrace);
        try {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(title)
                    .setMessage(stackTrace)
                    .setPositiveButton("Tutup", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    private void startCompile() {
        try {
            if (activeProject == null) {
                Toast.makeText(this, "Pilih proyek terlebih dahulu", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!toolchainManager.isAllReady()) {
                Toast.makeText(this, "Toolchain belum lengkap. Membuka tab Toolchain...", Toast.LENGTH_SHORT).show();
                switchTab(2);
                return;
            }

            btnStartCompile.setEnabled(false);
            progressBuild.setVisibility(View.VISIBLE);
            progressBuild.setProgress(0);
            cardBuildOutput.setVisibility(View.GONE);
            tvBuildStep.setText("Sedang mengompilasi...");

            builderEngine.buildProject(activeProject, new ApkBuilderEngine.BuildListener() {
                @Override
                public void onLog(String line) {
                    mainHandler.post(() -> logToConsole(line));
                }

                @Override
                public void onProgress(int step, int totalSteps, String message) {
                    mainHandler.post(() -> {
                        int pct = (step * 100) / totalSteps;
                        progressBuild.setProgress(pct);
                        tvBuildStep.setText(message);
                    });
                }

                @Override
                public void onSuccess(File finalApk, long durationMs) {
                    mainHandler.post(() -> {
                        lastBuiltApk = finalApk;
                        btnStartCompile.setEnabled(true);
                        progressBuild.setProgress(100);
                        tvBuildStep.setText("Selesai dalam " + String.format("%.2f", durationMs / 1000.0) + " detik");

                        cardBuildOutput.setVisibility(View.VISIBLE);
                        tvOutputSize.setText(String.format("%.2f MB", finalApk.length() / (1024.0 * 1024.0)));
                        tvOutputPath.setText(finalApk.getAbsolutePath());

                        Toast.makeText(MainActivity.this, "Kompilasi Sukses!", Toast.LENGTH_LONG).show();
                    });
                }

                @Override
                public void onError(String error) {
                    mainHandler.post(() -> {
                        btnStartCompile.setEnabled(true);
                        progressBuild.setVisibility(View.GONE);
                        tvBuildStep.setText("Gagal: " + error);
                        Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (Throwable t) {
            showCrashDialog("Error Memulai Kompilasi", t);
        }
    }

    private void installApkFile(File apkFile) {
        try {
            Uri apkUri = ApkFileProvider.getUriForFile(this, apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuka installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareApkFile(File apkFile) {
        try {
            Uri apkUri = ApkFileProvider.getUriForFile(this, apkFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/vnd.android.package-archive");
            intent.putExtra(Intent.EXTRA_STREAM, apkUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Bagikan APK"));
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membagikan file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshToolchainUI() {
        containerToolchainItems.removeAllViews();
        List<ToolchainItem> items = toolchainManager.getItems();
        boolean allReady = true;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (ToolchainItem item : items) {
            if (!item.isInstalled()) allReady = false;

            View v = inflater.inflate(R.layout.item_toolchain, containerToolchainItems, false);
            TextView tvName = v.findViewById(R.id.tv_tool_name);
            TextView tvDesc = v.findViewById(R.id.tv_tool_desc);
            TextView tvBadge = v.findViewById(R.id.tv_tool_badge);
            TextView tvSize = v.findViewById(R.id.tv_tool_size);
            TextView tvPath = v.findViewById(R.id.tv_tool_path);

            tvName.setText(item.getName());
            tvDesc.setText(item.getDescription());
            tvSize.setText("Ukuran: " + item.getFormattedSize());
            tvPath.setText(item.getTargetFile().getName());

            if (item.isInstalled()) {
                tvBadge.setText("Siap");
                tvBadge.setBackgroundResource(R.drawable.badge_ready);
                tvBadge.setTextColor(getColor(R.color.accent_green));
            } else {
                tvBadge.setText("Belum Terpasang");
                tvBadge.setBackgroundResource(R.drawable.badge_missing);
                tvBadge.setTextColor(getColor(R.color.accent_red));
            }

            containerToolchainItems.addView(v);
        }

        if (allReady) {
            badgeToolchainHeader.setText("Toolchain Siap");
            badgeToolchainHeader.setBackgroundResource(R.drawable.badge_ready);
            badgeToolchainHeader.setTextColor(getColor(R.color.accent_green));
        } else {
            badgeToolchainHeader.setText("Toolchain Belum Lengkap");
            badgeToolchainHeader.setBackgroundResource(R.drawable.badge_missing);
            badgeToolchainHeader.setTextColor(getColor(R.color.accent_red));
        }
    }

    private void installToolchainDependencies() {
        btnInstallToolchain.setEnabled(false);
        progressToolchain.setVisibility(View.VISIBLE);
        progressToolchain.setProgress(0);
        tvToolchainStatus.setVisibility(View.VISIBLE);
        tvToolchainStatus.setText("Memulai unduhan dependensi...");

        toolchainManager.installAll(new ToolchainManager.InstallListener() {
            @Override
            public void onProgress(String componentName, int percentage, String statusText) {
                mainHandler.post(() -> {
                    progressToolchain.setProgress(percentage);
                    tvToolchainStatus.setText("[" + componentName + "] " + statusText);
                });
            }

            @Override
            public void onComponentInstalled(String componentName, boolean success, String error) {
                mainHandler.post(() -> {
                    logToConsole("[TOOLCHAIN] " + componentName + ": " + (success ? "OK" : "ERROR (" + error + ")"));
                    refreshToolchainUI();
                });
            }

            @Override
            public void onComplete(boolean allSuccess) {
                mainHandler.post(() -> {
                    btnInstallToolchain.setEnabled(true);
                    progressToolchain.setVisibility(View.GONE);
                    tvToolchainStatus.setText(allSuccess ? "Semua dependensi siap digunakan!" : "Beberapa komponen gagal diunduh");
                    refreshToolchainUI();
                    Toast.makeText(MainActivity.this, allSuccess ? "Toolchain Siap!" : "Pemasangan selesai sebagian", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void loadExplorerDirectory(File dir) {
        if (dir == null || !dir.exists()) return;

        // Sandboxing: ensure explorer never navigates outside active project root
        if (activeProject != null && activeProject.getRootDir() != null) {
            try {
                String targetCanon = dir.getCanonicalPath();
                String rootCanon = activeProject.getRootDir().getCanonicalPath();
                if (!targetCanon.startsWith(rootCanon + File.separator) && !targetCanon.equals(rootCanon)) {
                    Toast.makeText(this, "Navigasi di luar direktori proyek diblokir demi keamanan!", Toast.LENGTH_SHORT).show();
                    dir = activeProject.getRootDir();
                }
            } catch (Exception e) {
                dir = activeProject.getRootDir();
            }
        }

        currentExplorerDir = dir;
        tvCurrentDirPath.setText(dir.getAbsolutePath());
        containerFileList.removeAllViews();

        File[] files = dir.listFiles();
        if (files == null) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        for (File f : files) {
            View row = inflater.inflate(R.layout.item_file, containerFileList, false);
            TextView tvType = row.findViewById(R.id.tv_file_type);
            TextView tvName = row.findViewById(R.id.tv_file_name);
            TextView tvSize = row.findViewById(R.id.tv_file_size);

            if (f.isDirectory()) {
                tvType.setText("[DIR]");
                tvType.setTextColor(getColor(R.color.primary_accent));
                tvSize.setText((f.listFiles() != null ? f.listFiles().length : 0) + " item");
                row.setOnClickListener(v -> loadExplorerDirectory(f));
            } else {
                tvType.setText("[FILE]");
                tvType.setTextColor(getColor(R.color.text_muted));
                tvSize.setText((f.length() / 1024) + " KB");
                row.setOnClickListener(v -> openCodeEditorDialog(f));
            }
            tvName.setText(f.getName());
            containerFileList.addView(row);
        }
    }

    private void openCodeEditorDialog(File f) {
        // Sandboxing: ensure file is strictly inside active project directory
        if (activeProject != null && activeProject.getRootDir() != null) {
            try {
                String fCanon = f.getCanonicalPath();
                String rootCanon = activeProject.getRootDir().getCanonicalPath();
                if (!fCanon.startsWith(rootCanon + File.separator)) {
                    Toast.makeText(this, "Akses file di luar direktori proyek diblokir demi keamanan!", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Exception e) {
                return;
            }
        }

        String name = f.getName().toLowerCase();
        boolean isText = name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".gradle") ||
                         name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".properties") ||
                         name.endsWith(".md") || name.endsWith(".cfg") || name.endsWith(".sh");

        if (!isText) {
            Toast.makeText(this, "File biner tidak dapat diedit langsung", Toast.LENGTH_SHORT).show();
            return;
        }

        if (f.length() > 512 * 1024) {
            Toast.makeText(this, "File terlalu besar untuk editor in-app (>512 KB)", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder content = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membaca file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_code_editor, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        TextView tvTitle = dialogView.findViewById(R.id.tv_editor_title);
        TextView tvInfo = dialogView.findViewById(R.id.tv_editor_file_info);
        TextView tvPath = dialogView.findViewById(R.id.tv_editor_file_path);
        EditText etCode = dialogView.findViewById(R.id.et_code_content);
        Button btnClose = dialogView.findViewById(R.id.btn_editor_close);
        Button btnSave = dialogView.findViewById(R.id.btn_editor_save);

        tvTitle.setText(f.getName());
        tvInfo.setText(String.format("%.1f KB", f.length() / 1024.0));
        tvPath.setText(f.getAbsolutePath());
        etCode.setText(content.toString());

        btnClose.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                fos.write(etCode.getText().toString().getBytes("UTF-8"));
                fos.flush();
                Toast.makeText(this, "Perubahan berhasil disimpan!", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                if (currentExplorerDir != null) {
                    loadExplorerDirectory(currentExplorerDir);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Gagal menyimpan: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.94);
            dialog.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void createOfflineSampleProject() {
        File projectsDir = getProjectsDir();
        File sampleDir = new File(projectsDir, "SampleApp");
        if (sampleDir.exists()) {
            deleteRecursive(sampleDir);
        }

        File appMain = new File(sampleDir, "app/src/main");
        File javaDir = new File(appMain, "java/com/rakyatid/sample");
        File resVal = new File(appMain, "res/values");
        File resLayout = new File(appMain, "res/layout");

        javaDir.mkdirs();
        resVal.mkdirs();
        resLayout.mkdirs();

        try {
            // 1. AndroidManifest.xml
            String manifest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    package=\"com.rakyatid.sample\"\n" +
                    "    android:versionCode=\"1\"\n" +
                    "    android:versionName=\"1.0.0\">\n" +
                    "    <uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"34\" />\n" +
                    "    <application\n" +
                    "        android:label=\"@string/app_name\"\n" +
                    "        android:theme=\"@android:style/Theme.Material.NoActionBar\">\n" +
                    "        <activity\n" +
                    "            android:name=\".MainActivity\"\n" +
                    "            android:exported=\"true\">\n" +
                    "            <intent-filter>\n" +
                    "                <action android:name=\"android.intent.action.MAIN\" />\n" +
                    "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
                    "            </intent-filter>\n" +
                    "        </activity>\n" +
                    "    </application>\n" +
                    "</manifest>\n";
            writeFileContent(new File(appMain, "AndroidManifest.xml"), manifest);

            // 2. strings.xml
            String strings = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<resources>\n" +
                    "    <string name=\"app_name\">Sample Counter App</string>\n" +
                    "    <string name=\"welcome\">Halo dari On-Device Compiler!</string>\n" +
                    "</resources>\n";
            writeFileContent(new File(resVal, "strings.xml"), strings);

            // 3. activity_main.xml
            String layout = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                    "    android:layout_width=\"match_parent\"\n" +
                    "    android:layout_height=\"match_parent\"\n" +
                    "    android:orientation=\"vertical\"\n" +
                    "    android:gravity=\"center\"\n" +
                    "    android:background=\"#0D0F12\"\n" +
                    "    android:padding=\"20dp\">\n" +
                    "    <TextView\n" +
                    "        android:id=\"@+id/tv_title\"\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_height=\"wrap_content\"\n" +
                    "        android:text=\"@string/welcome\"\n" +
                    "        android:textColor=\"#38BDF8\"\n" +
                    "        android:textSize=\"18sp\"\n" +
                    "        android:textStyle=\"bold\" />\n" +
                    "    <TextView\n" +
                    "        android:id=\"@+id/tv_counter\"\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_height=\"wrap_content\"\n" +
                    "        android:layout_marginTop=\"12dp\"\n" +
                    "        android:text=\"Klik: 0\"\n" +
                    "        android:textColor=\"#F1F5F9\"\n" +
                    "        android:textSize=\"16sp\" />\n" +
                    "    <Button\n" +
                    "        android:id=\"@+id/btn_increment\"\n" +
                    "        android:layout_width=\"wrap_content\"\n" +
                    "        android:layout_height=\"44dp\"\n" +
                    "        android:layout_marginTop=\"20dp\"\n" +
                    "        android:paddingLeft=\"24dp\"\n" +
                    "        android:paddingRight=\"24dp\"\n" +
                    "        android:text=\"Tambah Angka\"\n" +
                    "        android:textAllCaps=\"false\"\n" +
                    "        android:textColor=\"#FFFFFF\" />\n" +
                    "</LinearLayout>\n";
            writeFileContent(new File(resLayout, "activity_main.xml"), layout);

            // 4. MainActivity.java
            String javaCode = "package com.rakyatid.sample;\n\n" +
                    "import android.app.Activity;\n" +
                    "import android.os.Bundle;\n" +
                    "import android.view.View;\n" +
                    "import android.widget.Button;\n" +
                    "import android.widget.TextView;\n" +
                    "import android.widget.Toast;\n\n" +
                    "public class MainActivity extends Activity {\n" +
                    "    private int counter = 0;\n\n" +
                    "    @Override\n" +
                    "    protected void onCreate(Bundle savedInstanceState) {\n" +
                    "        super.onCreate(savedInstanceState);\n" +
                    "        setContentView(R.layout.activity_main);\n\n" +
                    "        final TextView tvCount = findViewById(R.id.tv_counter);\n" +
                    "        Button btn = findViewById(R.id.btn_increment);\n\n" +
                    "        btn.setOnClickListener(new View.OnClickListener() {\n" +
                    "            @Override\n" +
                    "            public void onClick(View v) {\n" +
                    "                counter++;\n" +
                    "                tvCount.setText(\"Klik: \" + counter);\n" +
                    "                Toast.makeText(MainActivity.this, \"Hitungan ke-\" + counter, Toast.LENGTH_SHORT).show();\n" +
                    "            }\n" +
                    "        });\n" +
                    "    }\n" +
                    "}\n";
            writeFileContent(new File(javaDir, "MainActivity.java"), javaCode);

            refreshProjectsList();
            Project scanned = ProjectScanner.scan(sampleDir);
            if (scanned != null) {
                setActiveProject(scanned);
                switchTab(1); // Langsung pindah ke tab Kompilasi
                Toast.makeText(this, "Proyek contoh siap dikompilasi!", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuat proyek contoh: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void writeFileContent(File file, String content) throws java.io.IOException {
        file.getParentFile().mkdirs();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(content.getBytes("UTF-8"));
            fos.flush();
        }
    }

    private void logToConsole(String line) {
        tvConsoleLog.append(line + "\n");
        scrollConsole.post(() -> scrollConsole.fullScroll(View.FOCUS_DOWN));
    }

    private File getProjectsDir() {
        File dir = new File(getExternalFilesDir(null), "projects");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void showAboutDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("APK Compiler");
        builder.setIcon(R.drawable.ic_app_logo);
        builder.setMessage("⚡ On-Device APK Compiler & Git Cloner\n\n"
                + "Versi: 1.0.0\n"
                + "Target SDK: 34 (Android 14)\n"
                + "Engine: AAPT2 + ECJ + D8 + Apksigner\n\n"
                + "Aplikasi kompilasi native on-device Android tanpa Gradle / PC.");
        builder.setPositiveButton("Tutup", (d, w) -> d.dismiss());
        builder.show();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] subs = f.listFiles();
            if (subs != null) {
                for (File s : subs) deleteRecursive(s);
            }
        }
        f.delete();
    }
}
