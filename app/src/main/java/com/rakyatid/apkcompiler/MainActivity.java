package com.rakyatid.apkcompiler;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import com.rakyatid.apkcompiler.engine.*;
import com.rakyatid.apkcompiler.model.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREF_NAME = "apk_compiler_prefs";
    private static final String PREF_KEY_TOUR_COMPLETED = "pref_tour_completed";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ToolchainManager toolchainManager;
    private ApkBuilderEngine builderEngine;

    // Active project state
    private Project activeProject;
    private File lastBuiltApk;

    // UI - Tabs
    private TextView tabProjects, tabBuild, tabToolchain, tabFiles;
    private View viewTabProjects, viewTabBuild, viewTabToolchain, viewTabFiles;
    private View[] tabIndicators;

    // UI - Build pipeline stepper
    private View[] pipelineDots;
    private TextView[] pipelineLabels;

    // UI - Header
    private TextView badgeToolchainHeader;

    // UI - Tab 1 (Projects)
    private TextView tvActiveProjectName, tvActiveProjectPkg, tvActiveSdk, tvActiveJavaCount, tvActiveResCount;
    private LinearLayout containerProjectsList;
    private TextView tvEmptyProjects;

    // UI - Tab 2 (Build)
    private View cardSdkSettings;
    private TextView tvSdkConfigSummary;
    private Button btnOpenSdkSettings;
    private Button btnStartCompile;
    private ProgressBar progressBuild;
    private TextView tvBuildStep;
    private View cardBuildOutput;
    private TextView tvOutputSize, tvOutputPath;
    private Button btnInstallApk, btnShareApk;
    private TextView tvConsoleLog;
    private ScrollView scrollConsole;
    private String lastLogLine = null;
    private boolean isInstallingToolchain = false;

    // UI - Tab 3 (Toolchain)
    private LinearLayout containerToolchainItems;
    private Button btnInstallToolchain;
    private ProgressBar progressToolchain;
    private TextView tvToolchainStatus;

    // UI - Tab 4 (Files)
    private TextView tvCurrentDirPath;
    private LinearLayout containerFileList;
    private File currentExplorerDir;

    // Background build updates from BuildService (also shown in the notification).
    private final BroadcastReceiver buildReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String status = intent.getStringExtra(BuildService.EXTRA_STATUS);
            int pct = intent.getIntExtra(BuildService.EXTRA_PROGRESS, 0);
            String step = intent.getStringExtra(BuildService.EXTRA_STEP);
            if (intent.hasExtra(BuildService.EXTRA_LOG)) {
                String line = intent.getStringExtra(BuildService.EXTRA_LOG);
                if (line != null) logToConsole(line);
            }
            if (BuildService.STATUS_RUNNING.equals(status)) {
                btnStartCompile.setEnabled(false);
                progressBuild.setVisibility(View.VISIBLE);
                progressBuild.setProgress(pct);
                if (step != null) tvBuildStep.setText(step);
                updatePipeline(pct, false, false);
            } else if (BuildService.STATUS_SUCCESS.equals(status)) {
                String apkPath = intent.getStringExtra(BuildService.EXTRA_APK_PATH);
                long durationMs = intent.getLongExtra(BuildService.EXTRA_DURATION_MS, 0);
                btnStartCompile.setEnabled(true);
                progressBuild.setProgress(100);
                tvBuildStep.setText("Completed in " + String.format("%.2f", durationMs / 1000.0) + "s");
                updatePipeline(100, true, false);
                if (apkPath != null && !apkPath.isEmpty()) {
                    File apk = new File(apkPath);
                    if (apk.exists()) {
                        lastBuiltApk = apk;
                        cardBuildOutput.setVisibility(View.VISIBLE);
                        tvOutputSize.setText(String.format("%.2f MB", apk.length() / (1024.0 * 1024.0)));
                        tvOutputPath.setText(apk.getAbsolutePath());
                    }
                }
                Toast.makeText(MainActivity.this, "Compilation Succeeded!", Toast.LENGTH_LONG).show();
            } else if (BuildService.STATUS_ERROR.equals(status)) {
                String error = intent.getStringExtra(BuildService.EXTRA_ERROR);
                btnStartCompile.setEnabled(true);
                progressBuild.setVisibility(View.GONE);
                tvBuildStep.setText("Failed: " + error);
                updatePipeline(pct, false, true);
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            }
        }
    };

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
        updateSdkConfigSummary();
        restoreLastBuildState();
        requestNotificationPermission();
        checkFirstLaunchTour();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            IntentFilter filter = new IntentFilter(BuildService.ACTION_BUILD_PROGRESS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(buildReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(buildReceiver, filter);
            }
        } catch (Exception ignored) {}
        restoreLastBuildState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(buildReceiver);
        } catch (Exception ignored) {}
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    private void restoreLastBuildState() {
        if (BuildService.isRunning) {
            btnStartCompile.setEnabled(false);
            progressBuild.setVisibility(View.VISIBLE);
            progressBuild.setProgress(BuildService.progress);
            tvBuildStep.setText(BuildService.stepText);
            updatePipeline(BuildService.progress, false, false);
            return;
        }
        SharedPreferences prefs = getSharedPreferences(BuildService.PREFS_NAME, MODE_PRIVATE);
        String path = prefs.getString(BuildService.KEY_LAST_APK, "");
        if (path != null && !path.isEmpty()) {
            File apk = new File(path);
            if (apk.exists()) {
                lastBuiltApk = apk;
                cardBuildOutput.setVisibility(View.VISIBLE);
                tvOutputSize.setText(String.format("%.2f MB", apk.length() / (1024.0 * 1024.0)));
                tvOutputPath.setText(apk.getAbsolutePath());
                long durationMs = prefs.getLong(BuildService.KEY_LAST_DURATION_MS, 0);
                if (durationMs > 0) {
                    tvBuildStep.setText("Completed in " + String.format("%.2f", durationMs / 1000.0) + "s");
                } else {
                    tvBuildStep.setText("Build ready");
                }
                updatePipeline(100, true, false);
            }
        }
    }

    private void resetPipeline() {
        updatePipeline(0, false, false);
    }

    private void updatePipeline(int percent, boolean done, boolean failed) {
        if (pipelineDots == null || pipelineLabels == null) return;
        int stage;
        if (done) {
            stage = pipelineDots.length;
        } else if (percent < 20) {
            stage = 0;
        } else if (percent < 45) {
            stage = 1;
        } else if (percent < 70) {
            stage = 2;
        } else {
            stage = 3;
        }
        for (int i = 0; i < pipelineDots.length; i++) {
            if (done) {
                pipelineDots[i].setBackgroundResource(R.drawable.pipeline_dot_done);
                pipelineLabels[i].setTextColor(getColor(R.color.text_primary));
            } else if (i < stage) {
                pipelineDots[i].setBackgroundResource(R.drawable.pipeline_dot_done);
                pipelineLabels[i].setTextColor(getColor(R.color.text_primary));
            } else if (i == stage) {
                pipelineDots[i].setBackgroundResource(failed
                        ? R.drawable.pipeline_dot_idle : R.drawable.pipeline_dot_active);
                pipelineLabels[i].setTextColor(failed
                        ? getColor(R.color.accent_red) : getColor(R.color.primary_accent));
            } else {
                pipelineDots[i].setBackgroundResource(R.drawable.pipeline_dot_idle);
                pipelineLabels[i].setTextColor(getColor(R.color.text_muted));
            }
        }
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

        tabIndicators = new View[]{
                findViewById(R.id.tab_indicator_0),
                findViewById(R.id.tab_indicator_1),
                findViewById(R.id.tab_indicator_2),
                findViewById(R.id.tab_indicator_3)
        };

        pipelineDots = new View[]{
                findViewById(R.id.pipeline_dot_1),
                findViewById(R.id.pipeline_dot_2),
                findViewById(R.id.pipeline_dot_3),
                findViewById(R.id.pipeline_dot_4)
        };
        pipelineLabels = new TextView[]{
                findViewById(R.id.pipeline_label_1),
                findViewById(R.id.pipeline_label_2),
                findViewById(R.id.pipeline_label_3),
                findViewById(R.id.pipeline_label_4)
        };

        badgeToolchainHeader = findViewById(R.id.badge_toolchain_header);

        tvActiveProjectName = findViewById(R.id.tv_active_project_name);
        tvActiveProjectPkg = findViewById(R.id.tv_active_project_pkg);
        tvActiveSdk = findViewById(R.id.tv_active_sdk);
        tvActiveJavaCount = findViewById(R.id.tv_active_java_count);
        tvActiveResCount = findViewById(R.id.tv_active_res_count);
        containerProjectsList = findViewById(R.id.container_projects_list);
        tvEmptyProjects = findViewById(R.id.tv_empty_projects);

        cardSdkSettings = findViewById(R.id.card_sdk_settings);
        tvSdkConfigSummary = findViewById(R.id.tv_sdk_config_summary);
        btnOpenSdkSettings = findViewById(R.id.btn_open_sdk_settings);

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

        tabProjects.setTypeface(null, index == 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tabBuild.setTypeface(null, index == 1 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tabToolchain.setTypeface(null, index == 2 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        tabFiles.setTypeface(null, index == 3 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

        if (tabIndicators != null) {
            for (int i = 0; i < tabIndicators.length; i++) {
                if (tabIndicators[i] != null) {
                    tabIndicators[i].setBackgroundColor(i == index ? primaryColor : Color.TRANSPARENT);
                }
            }
        }

        if (index == 3) {
            if (activeProject != null) {
                loadExplorerDirectory(activeProject.getRootDir());
            } else {
                tvCurrentDirPath.setText("-");
            }
        }
    }

    private void setupActions() {
        findViewById(R.id.btn_open_clone_dialog).setOnClickListener(v -> showCloneDialog());
        findViewById(R.id.btn_open_folder_picker).setOnClickListener(v -> showFolderPicker());
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> { tvConsoleLog.setText(""); lastLogLine = null; });        badgeToolchainHeader.setOnClickListener(v -> switchTab(2));

        View btnHeaderHelp = findViewById(R.id.btn_header_help);
        if (btnHeaderHelp != null) {
            btnHeaderHelp.setOnClickListener(v -> showTourGuideDialog());
        }

        View ivLogo = findViewById(R.id.iv_header_logo);
        if (ivLogo != null) {
            ivLogo.setOnClickListener(v -> showTourGuideDialog());
            ivLogo.setOnLongClickListener(v -> {
                showAboutDialog();
                return true;
            });
        }

        if (btnOpenSdkSettings != null) {
            btnOpenSdkSettings.setOnClickListener(v -> showSdkSettingsDialog());
        }
        if (cardSdkSettings != null) {
            cardSdkSettings.setOnClickListener(v -> showSdkSettingsDialog());
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
            View emptyParent = (View) tvEmptyProjects.getParent();
            if (emptyParent != null) emptyParent.setVisibility(View.VISIBLE);
            return;
        }

        tvEmptyProjects.setVisibility(View.GONE);
        View emptyParent = (View) tvEmptyProjects.getParent();
        if (emptyParent != null) emptyParent.setVisibility(View.GONE);
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
            tvPkg.setText(p.getPackageName() != null ? p.getPackageName() : "Non-standard Android project");
            tvStats.setText(p.getJavaFilesCount() + " java / " + p.getResFilesCount() + " res");

            btnSelect.setOnClickListener(v -> {
                setActiveProject(p);
                switchTab(1);
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
        updateSdkConfigSummary();
        logToConsole("[PROJECT] Selected: " + p.getName() + " (" + p.getPackageName() + ")");
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
                Toast.makeText(MainActivity.this, "Selected folder is not a valid Android project (AndroidManifest.xml not found)", Toast.LENGTH_SHORT).show();
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
                    .setPositiveButton("Close", null)
                    .show();
        } catch (Throwable ignored) {}
    }

    private void startCompile() {
        try {
            if (activeProject == null) {
                Toast.makeText(this, "Please select a project first", Toast.LENGTH_SHORT).show();
                return;
            }

            if (BuildService.isRunning) {
                Toast.makeText(this, "Build already running. Check the notification for progress.", Toast.LENGTH_SHORT).show();
                switchTab(1);
                return;
            }

            if (!toolchainManager.isAllReady()) {
                Toast.makeText(this, "Toolchain incomplete. Opening Toolchain tab...", Toast.LENGTH_SHORT).show();
                switchTab(2);
                return;
            }

            // Hand the build to the foreground service so it keeps running
            // with a notification progress bar after the app is closed.
            Intent intent = new Intent(this, BuildService.class);
            intent.setAction(BuildService.ACTION_START_BUILD);
            intent.putExtra(BuildService.EXTRA_PROJECT_PATH, activeProject.getRootDir().getAbsolutePath());
            intent.putExtra(BuildService.EXTRA_MIN_SDK, activeProject.getMinSdk());
            intent.putExtra(BuildService.EXTRA_TARGET_SDK, activeProject.getTargetSdk());
            intent.putExtra(BuildService.EXTRA_COMPILE_SDK, activeProject.getCompileSdk());
            intent.putExtra(BuildService.EXTRA_CUSTOM_SDK_PATH, activeProject.getCustomSdkPath());
            intent.putExtra(BuildService.EXTRA_PLATFORM_SDK, toolchainManager.getSelectedPlatformSdk());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }

            btnStartCompile.setEnabled(false);
            progressBuild.setVisibility(View.VISIBLE);
            progressBuild.setProgress(0);
            cardBuildOutput.setVisibility(View.GONE);
            tvBuildStep.setText("Starting build...");
            resetPipeline();
            logToConsole("[BUILD] Started in background. Closing the app will not stop it.");
        } catch (Throwable t) {
            showCrashDialog("Error Starting Compilation", t);
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
            Toast.makeText(this, "Failed to launch installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareApkFile(File apkFile) {
        try {
            Uri apkUri = ApkFileProvider.getUriForFile(this, apkFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/vnd.android.package-archive");
            intent.putExtra(Intent.EXTRA_STREAM, apkUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share APK"));
        } catch (Exception e) {
            Toast.makeText(this, "Failed to share APK: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            tvSize.setText(item.getFormattedSize());
            tvPath.setText(item.getTargetFile().getName());

            if (item.isInstalled()) {
                tvBadge.setText("Ready");
                tvBadge.setBackgroundResource(R.drawable.badge_ready);
                tvBadge.setTextColor(getColor(R.color.accent_green));
            } else {
                tvBadge.setText("Missing");
                tvBadge.setBackgroundResource(R.drawable.badge_missing);
                tvBadge.setTextColor(getColor(R.color.accent_red));
            }

            containerToolchainItems.addView(v);
        }

        if (allReady) {
            badgeToolchainHeader.setText("Ready");
            badgeToolchainHeader.setBackgroundResource(R.drawable.badge_ready);
            badgeToolchainHeader.setTextColor(getColor(R.color.accent_green));
        } else {
            badgeToolchainHeader.setText("Not ready");
            badgeToolchainHeader.setBackgroundResource(R.drawable.badge_missing);
            badgeToolchainHeader.setTextColor(getColor(R.color.accent_red));
        }
    }

    private void installToolchainDependencies() {
        if (isInstallingToolchain) return;
        isInstallingToolchain = true;
        btnInstallToolchain.setEnabled(false);
        progressToolchain.setVisibility(View.VISIBLE);
        progressToolchain.setProgress(0);
        tvToolchainStatus.setVisibility(View.VISIBLE);
        tvToolchainStatus.setText("Starting dependency download...");

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
                    isInstallingToolchain = false;
                    btnInstallToolchain.setEnabled(true);
                    progressToolchain.setVisibility(View.GONE);
                    tvToolchainStatus.setText(allSuccess ? "All toolchain dependencies are ready!" : "Some components failed to install");
                    refreshToolchainUI();
                    Toast.makeText(MainActivity.this, allSuccess ? "Toolchain Ready!" : "Installation partially complete", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, "Navigation outside project directory is blocked for security!", Toast.LENGTH_SHORT).show();
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
            if (tvType != null) tvType.setVisibility(View.GONE);
            ImageView ivFileIcon = row.findViewById(R.id.iv_file_icon);
            TextView tvName = row.findViewById(R.id.tv_file_name);
            TextView tvSize = row.findViewById(R.id.tv_file_size);

            if (f.isDirectory()) {
                if (ivFileIcon != null) ivFileIcon.setImageResource(R.drawable.ic_nav_folder);
                tvSize.setText((f.listFiles() != null ? f.listFiles().length : 0) + " items");
                row.setOnClickListener(v -> loadExplorerDirectory(f));
            } else {
                if (ivFileIcon != null) {
                    String lower = f.getName().toLowerCase();
                    if (lower.endsWith(".java") || lower.endsWith(".xml")
                            || lower.endsWith(".gradle") || lower.endsWith(".kt")) {
                        ivFileIcon.setImageResource(R.drawable.ic_file_code);
                    } else {
                        ivFileIcon.setImageResource(R.drawable.ic_nav_file);
                    }
                }
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
                    Toast.makeText(this, "File access outside project directory is blocked for security!", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Binary file cannot be opened in text editor", Toast.LENGTH_SHORT).show();
            return;
        }

        if (f.length() > 512 * 1024) {
            Toast.makeText(this, "File too large for in-app editor (>512 KB)", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder content = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to read file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Changes saved successfully!", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                if (currentExplorerDir != null) {
                    loadExplorerDirectory(currentExplorerDir);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Failed to save file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.94);
            dialog.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void updateSdkConfigSummary() {
        if (tvSdkConfigSummary == null) return;
        if (activeProject != null) {
            String platform = toolchainManager.getSelectedPlatformSdk();
            if (activeProject.getCustomSdkPath() != null && !activeProject.getCustomSdkPath().isEmpty()) {
                platform = new File(activeProject.getCustomSdkPath()).getName();
            }
            tvSdkConfigSummary.setText("Min: " + activeProject.getMinSdk() + " • Target: " + activeProject.getTargetSdk() + " • " + platform);
        } else {
            tvSdkConfigSummary.setText("Min: 21 • Target: 34 • " + toolchainManager.getSelectedPlatformSdk());
        }
    }

    private void showSdkSettingsDialog() {
        if (isFinishing()) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sdk_settings, null);
        builder.setView(dialogView);
        AlertDialog dialog = builder.create();

        Spinner spinnerMin = dialogView.findViewById(R.id.spinner_min_sdk);
        Spinner spinnerTarget = dialogView.findViewById(R.id.spinner_target_sdk);
        Spinner spinnerPlatform = dialogView.findViewById(R.id.spinner_platform_sdk);
        LinearLayout layoutCustom = dialogView.findViewById(R.id.layout_custom_sdk_path);
        EditText etCustom = dialogView.findViewById(R.id.et_custom_sdk_path);
        Button btnBrowse = dialogView.findViewById(R.id.btn_browse_custom_sdk);
        Button btnCancel = dialogView.findViewById(R.id.btn_sdk_cancel);
        Button btnApply = dialogView.findViewById(R.id.btn_sdk_apply);

        final Integer[] minSdkOptions = new Integer[]{8, 14, 16, 21, 23, 24, 26, 28, 29, 30, 31, 33, 34};
        final Integer[] targetSdkOptions = new Integer[]{10, 14, 16, 21, 23, 24, 26, 28, 29, 30, 31, 33, 34};
        final String[] platformSdkOptions = new String[]{
                "android-34 (Android 14)",
                "android-33 (Android 13)",
                "android-30 (Android 11)",
                "android-28 (Android 9.0)",
                "android-26 (Android 8.0)",
                "android-24 (Android 7.0)",
                "android-21 (Android 5.0)",
                "Custom Path..."
        };

        ArrayAdapter<Integer> minAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, minSdkOptions);
        spinnerMin.setAdapter(minAdapter);

        ArrayAdapter<Integer> targetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, targetSdkOptions);
        spinnerTarget.setAdapter(targetAdapter);

        ArrayAdapter<String> platformAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, platformSdkOptions);
        spinnerPlatform.setAdapter(platformAdapter);

        int curMin = activeProject != null ? activeProject.getMinSdk() : 21;
        int curTarget = activeProject != null ? activeProject.getTargetSdk() : 34;
        int curCompile = activeProject != null ? activeProject.getCompileSdk() : ToolchainManager.parseSdkVersion(toolchainManager.getSelectedPlatformSdk());

        for (int i = 0; i < minSdkOptions.length; i++) {
            if (minSdkOptions[i] == curMin) {
                spinnerMin.setSelection(i);
                break;
            }
        }
        for (int i = 0; i < targetSdkOptions.length; i++) {
            if (targetSdkOptions[i] == curTarget) {
                spinnerTarget.setSelection(i);
                break;
            }
        }

        String curCustomPath = (activeProject != null && activeProject.getCustomSdkPath() != null)
                ? activeProject.getCustomSdkPath() : toolchainManager.getCustomSdkPath();
        if (curCustomPath != null) {
            etCustom.setText(curCustomPath);
            spinnerPlatform.setSelection(platformSdkOptions.length - 1);
            layoutCustom.setVisibility(View.VISIBLE);
        } else {
            for (int i = 0; i < platformSdkOptions.length - 1; i++) {
                if (platformSdkOptions[i].startsWith("android-" + curCompile)) {
                    spinnerPlatform.setSelection(i);
                    break;
                }
            }
            layoutCustom.setVisibility(View.GONE);
        }

        spinnerPlatform.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == platformSdkOptions.length - 1) {
                    layoutCustom.setVisibility(View.VISIBLE);
                } else {
                    layoutCustom.setVisibility(View.GONE);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnBrowse.setOnClickListener(v -> {
            Toast.makeText(this, "Enter path to android.jar or SDK platforms folder", Toast.LENGTH_SHORT).show();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnApply.setOnClickListener(v -> {
            int selectedMin = (Integer) spinnerMin.getSelectedItem();
            int selectedTarget = (Integer) spinnerTarget.getSelectedItem();
            int platformPos = spinnerPlatform.getSelectedItemPosition();

            String customPath = null;
            int compileSdk = 34;

            if (platformPos == platformSdkOptions.length - 1) {
                customPath = etCustom.getText().toString().trim();
                if (customPath.isEmpty()) customPath = null;
            } else {
                String plat = platformSdkOptions[platformPos].split(" ")[0]; // android-XX
                compileSdk = ToolchainManager.parseSdkVersion(plat);
                toolchainManager.setSelectedPlatformSdk(plat);
            }

            if (activeProject != null) {
                activeProject.setMinSdk(selectedMin);
                activeProject.setTargetSdk(selectedTarget);
                activeProject.setCompileSdk(compileSdk);
                activeProject.setCustomSdkPath(customPath);
                tvActiveSdk.setText(selectedMin + " / " + selectedTarget);
                logToConsole("[SDK CONFIG] Applied to " + activeProject.getName() + ": Min SDK=" + selectedMin + ", Target SDK=" + selectedTarget + ", Compile SDK=" + compileSdk);
                Toast.makeText(this, "SDK settings applied to " + activeProject.getName(), Toast.LENGTH_SHORT).show();
            } else {
                toolchainManager.setCustomSdkPath(customPath);
                logToConsole("[SDK CONFIG] Default SDK updated: Min=" + selectedMin + ", Target=" + selectedTarget);
                Toast.makeText(this, "Default SDK settings updated", Toast.LENGTH_SHORT).show();
            }

            updateSdkConfigSummary();
            dialog.dismiss();
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.94);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        // Skip an exact repeat of the previous line: a double-tapped install
        // must not print the same toolchain result twice.
        if (line != null && line.equals(lastLogLine)) return;
        lastLogLine = line;
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
        builder.setMessage("On-Device APK Compiler & Git Cloner\n\n"
                + "Version: 1.3.1\n"
                + "Target SDK: 34 (Android 14)\n"
                + "Engine: AAPT2 + ECJ + D8 + apksig\n\n"
                + "Builds keep running in the background with a notification progress bar, even after the app is closed.");
        builder.setPositiveButton("Close", (d, w) -> d.dismiss());
        builder.setNeutralButton("Tour Guide", (d, w) -> {
            d.dismiss();
            showTourGuideDialog();
        });
        builder.show();
    }

    private void checkFirstLaunchTour() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean tourCompleted = prefs.getBoolean(PREF_KEY_TOUR_COMPLETED, false);
        if (!tourCompleted) {
            mainHandler.postDelayed(this::showTourGuideDialog, 400);
        }
    }

    private void setTourCompleted() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_KEY_TOUR_COMPLETED, true).apply();
    }

    private static class TourStep {
        final String badge;
        final int iconRes;
        final String title;
        final String subtitle;
        final String desc;
        final String feat1;
        final String feat2;
        final String feat3;

        TourStep(String badge, int iconRes, String title, String subtitle, String desc,
                 String feat1, String feat2, String feat3) {
            this.badge = badge;
            this.iconRes = iconRes;
            this.title = title;
            this.subtitle = subtitle;
            this.desc = desc;
            this.feat1 = feat1;
            this.feat2 = feat2;
            this.feat3 = feat3;
        }
    }

    private final TourStep[] tourSteps = new TourStep[] {
        new TourStep(
            "STEP 1 OF 4",
            R.drawable.ic_tour_welcome,
            "Welcome",
            "Build APKs on your phone",
            "Clone a repo and build it into an APK, right on this device. No PC needed.",
            "Build APKs on-device",
            "No PC or Termux needed",
            "AAPT2, ECJ, D8 and signing built in"
        ),
        new TourStep(
            "STEP 2 OF 4",
            R.drawable.ic_tour_projects,
            "Projects",
            "Clone or open a project",
            "Clone a public GitHub repo, or pick a folder already on your phone.",
            "Clone with a custom branch",
            "Quick samples: Flashlight and Compass",
            "Open a local project folder"
        ),
        new TourStep(
            "STEP 3 OF 4",
            R.drawable.ic_tour_compiler,
            "Build",
            "One tap, four stages",
            "Resources, Java, Dex, then signing. You can close the app: the build keeps running and shows its progress in a notification.",
            "Four stages: AAPT2, ECJ, D8, signing",
            "Min and target SDK can be changed",
            "APK signing and security check built in"
        ),
        new TourStep(
            "STEP 4 OF 4",
            R.drawable.ic_tour_tools,
            "Tools & Editor",
            "Everything is already inside",
            "Toolchain, AndroidX support and a code editor, all in the app.",
            "AndroidX and Maven dependencies",
            "Edit Java and XML files in the app",
            "Logs, install and share after a build"
        )
    };

    public void showTourGuideDialog() {
        if (isFinishing()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_tour_guide);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int maxWidth = (int) (480 * getResources().getDisplayMetrics().density);
            int dialogWidth = Math.min((int) (screenWidth * 0.92f), maxWidth);
            dialog.getWindow().setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        final int[] currentStep = new int[]{0};

        TextView tvBadge = dialog.findViewById(R.id.tv_tour_step_badge);
        View btnSkip = dialog.findViewById(R.id.btn_tour_skip);
        View contentContainer = dialog.findViewById(R.id.container_tour_content);
        ImageView ivIcon = dialog.findViewById(R.id.iv_tour_icon);
        TextView tvTitle = dialog.findViewById(R.id.tv_tour_title);
        TextView tvSubtitle = dialog.findViewById(R.id.tv_tour_subtitle);
        TextView tvDesc = dialog.findViewById(R.id.tv_tour_desc);
        TextView tvFeat1 = dialog.findViewById(R.id.tv_tour_feat_1);
        TextView tvFeat2 = dialog.findViewById(R.id.tv_tour_feat_2);
        TextView tvFeat3 = dialog.findViewById(R.id.tv_tour_feat_3);

        View[] dots = new View[]{
            dialog.findViewById(R.id.dot_tour_0),
            dialog.findViewById(R.id.dot_tour_1),
            dialog.findViewById(R.id.dot_tour_2),
            dialog.findViewById(R.id.dot_tour_3)
        };

        Button btnPrev = dialog.findViewById(R.id.btn_tour_prev);
        Button btnNext = dialog.findViewById(R.id.btn_tour_next);

        Runnable updateUI = new Runnable() {
            @Override
            public void run() {
                int step = currentStep[0];
                TourStep s = tourSteps[step];

                tvBadge.setText(s.badge);
                ivIcon.setImageResource(s.iconRes);
                tvTitle.setText(s.title);
                tvSubtitle.setText(s.subtitle);
                tvDesc.setText(s.desc);
                tvFeat1.setText(s.feat1);
                tvFeat2.setText(s.feat2);
                tvFeat3.setText(s.feat3);

                btnPrev.setVisibility(step > 0 ? View.VISIBLE : View.GONE);
                if (step == tourSteps.length - 1) {
                    btnNext.setText("Get Started");
                    btnSkip.setVisibility(View.GONE);
                } else {
                    btnNext.setText("Next");
                    btnSkip.setVisibility(View.VISIBLE);
                }

                float density = getResources().getDisplayMetrics().density;
                int activeWidth = (int) (18 * density);
                int inactiveWidth = (int) (6 * density);
                int dotHeight = (int) (6 * density);

                for (int i = 0; i < dots.length; i++) {
                    boolean isActive = (i == step);
                    dots[i].setBackgroundResource(isActive ? R.drawable.tour_dot_active : R.drawable.tour_dot_inactive);
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dots[i].getLayoutParams();
                    lp.width = isActive ? activeWidth : inactiveWidth;
                    lp.height = dotHeight;
                    dots[i].setLayoutParams(lp);
                }
            }
        };

        updateUI.run();

        for (int i = 0; i < dots.length; i++) {
            final int targetStep = i;
            dots[i].setOnClickListener(v -> {
                if (currentStep[0] != targetStep) {
                    currentStep[0] = targetStep;
                    if (contentContainer != null) {
                        contentContainer.animate().alpha(0f).setDuration(100).withEndAction(() -> {
                            updateUI.run();
                            contentContainer.animate().alpha(1f).setDuration(140).start();
                        }).start();
                    } else {
                        updateUI.run();
                    }
                }
            });
        }

        btnNext.setOnClickListener(v -> {
            if (currentStep[0] < tourSteps.length - 1) {
                currentStep[0]++;
                if (contentContainer != null) {
                    contentContainer.animate().alpha(0f).setDuration(100).withEndAction(() -> {
                        updateUI.run();
                        contentContainer.animate().alpha(1f).setDuration(140).start();
                    }).start();
                } else {
                    updateUI.run();
                }
            } else {
                setTourCompleted();
                dialog.dismiss();
                Toast.makeText(MainActivity.this, "Tour completed! Re-open anytime via the ? icon.", Toast.LENGTH_SHORT).show();
            }
        });

        btnPrev.setOnClickListener(v -> {
            if (currentStep[0] > 0) {
                currentStep[0]--;
                if (contentContainer != null) {
                    contentContainer.animate().alpha(0f).setDuration(100).withEndAction(() -> {
                        updateUI.run();
                        contentContainer.animate().alpha(1f).setDuration(140).start();
                    }).start();
                } else {
                    updateUI.run();
                }
            }
        });

        btnSkip.setOnClickListener(v -> {
            setTourCompleted();
            dialog.dismiss();
            Toast.makeText(MainActivity.this, "Tour skipped. Re-open anytime via the ? icon.", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
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
