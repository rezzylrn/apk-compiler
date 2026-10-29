package com.rakyatid.apkcompiler.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import com.rakyatid.apkcompiler.MainActivity;
import com.rakyatid.apkcompiler.R;
import com.rakyatid.apkcompiler.model.Project;

import java.io.File;

/**
 * Foreground service that runs the APK build after the UI is closed.
 *
 * The build engine already runs on its own worker thread and only needs a
 * Context, so the service can own it with the application context. While a
 * build is running the service stays in the foreground with an ongoing
 * notification that carries the progress bar, so swiping the app away,
 * pressing Home, or locking the phone does not stop the build. Force-stopping
 * the app from Settings still kills the process; that is an Android limit,
 * not something a service can survive.
 */
public class BuildService extends Service {

    public static final String ACTION_BUILD_PROGRESS =
            "com.rakyatid.apkcompiler.BUILD_PROGRESS";
    public static final String ACTION_START_BUILD =
            "com.rakyatid.apkcompiler.START_BUILD";

    public static final String EXTRA_PROJECT_PATH = "extra_project_path";
    public static final String EXTRA_MIN_SDK = "extra_min_sdk";
    public static final String EXTRA_TARGET_SDK = "extra_target_sdk";
    public static final String EXTRA_COMPILE_SDK = "extra_compile_sdk";
    public static final String EXTRA_CUSTOM_SDK_PATH = "extra_custom_sdk_path";
    public static final String EXTRA_PLATFORM_SDK = "extra_platform_sdk";

    public static final String EXTRA_PROGRESS = "extra_progress";
    public static final String EXTRA_STEP = "extra_step";
    public static final String EXTRA_LOG = "extra_log";
    public static final String EXTRA_STATUS = "extra_status";
    public static final String EXTRA_APK_PATH = "extra_apk_path";
    public static final String EXTRA_ERROR = "extra_error";
    public static final String EXTRA_DURATION_MS = "extra_duration_ms";

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_ERROR = "error";

    public static final String PREFS_NAME = "apk_compiler_build_state";
    public static final String KEY_LAST_APK = "last_apk_path";
    public static final String KEY_LAST_PROJECT = "last_project_name";
    public static final String KEY_LAST_SIZE = "last_apk_size";
    public static final String KEY_LAST_DURATION_MS = "last_duration_ms";

    private static final String CHANNEL_ID = "apk_compiler_build";
    private static final int NOTIFICATION_ID = 1001;

    public interface BuildProgressListener {
        void onProgress(int percent, String step);
        void onLog(String line);
        void onFinished(boolean success, File apk, String error, long durationMs);
    }

    private static BuildProgressListener listener;

    public static void setListener(BuildProgressListener l) {
        listener = l;
    }

    public static void clearListener(BuildProgressListener l) {
        if (listener == l) {
            listener = null;
        }
    }

    public static volatile boolean isRunning = false;
    public static volatile int progress = 0;
    public static volatile String stepText = "Ready";
    public static volatile String projectName = "";
    public static volatile String apkPath = "";
    public static volatile String errorText = "";

    private NotificationManager notificationManager;
    private int lastNotifiedProgress = -1;
    private String lastNotifiedStep = "";

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        if (isRunning) {
            return START_NOT_STICKY;
        }

        String projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH);
        if (projectPath == null || projectPath.isEmpty()) {
            return START_NOT_STICKY;
        }
        File projectDir = new File(projectPath);
        Project project = ProjectScanner.scan(projectDir);
        if (project == null) {
            return START_NOT_STICKY;
        }

        if (intent.hasExtra(EXTRA_MIN_SDK)) {
            project.setMinSdk(intent.getIntExtra(EXTRA_MIN_SDK, project.getMinSdk()));
        }
        if (intent.hasExtra(EXTRA_TARGET_SDK)) {
            project.setTargetSdk(intent.getIntExtra(EXTRA_TARGET_SDK, project.getTargetSdk()));
        }
        if (intent.hasExtra(EXTRA_COMPILE_SDK)) {
            project.setCompileSdk(intent.getIntExtra(EXTRA_COMPILE_SDK, project.getCompileSdk()));
        }
        if (intent.hasExtra(EXTRA_CUSTOM_SDK_PATH)) {
            project.setCustomSdkPath(intent.getStringExtra(EXTRA_CUSTOM_SDK_PATH));
        }

        ToolchainManager toolchainManager = new ToolchainManager(getApplicationContext());
        String platformSdk = intent.getStringExtra(EXTRA_PLATFORM_SDK);
        if (platformSdk != null && !platformSdk.isEmpty()) {
            toolchainManager.setSelectedPlatformSdk(platformSdk);
        }
        String customSdkPath = intent.getStringExtra(EXTRA_CUSTOM_SDK_PATH);
        if (customSdkPath != null && !customSdkPath.isEmpty()) {
            toolchainManager.setCustomSdkPath(customSdkPath);
        }

        isRunning = true;
        progress = 0;
        stepText = "Starting build";
        projectName = project.getName();
        apkPath = "";
        errorText = "";
        lastNotifiedProgress = -1;
        lastNotifiedStep = "";

        startForeground(NOTIFICATION_ID, buildNotification(0, stepText, true));
        broadcast(STATUS_RUNNING, 0, stepText, null, null, null, 0);

        ApkBuilderEngine engine = new ApkBuilderEngine(getApplicationContext(), toolchainManager);
        engine.buildProject(project, new ApkBuilderEngine.BuildListener() {
            @Override
            public void onLog(String line) {
                broadcast(STATUS_RUNNING, progress, stepText, line, null, null, 0);
                BuildProgressListener l = listener;
                if (l != null) {
                    l.onLog(line);
                }
            }

            @Override
            public void onProgress(int step, int totalSteps, String message) {
                int pct = totalSteps > 0 ? (step * 100) / totalSteps : 0;
                if (pct < 0) pct = 0;
                if (pct > 100) pct = 100;
                progress = pct;
                stepText = message != null ? message : stepText;
                updateProgressNotification(pct, stepText);
                broadcast(STATUS_RUNNING, pct, stepText, null, null, null, 0);
                BuildProgressListener l = listener;
                if (l != null) {
                    l.onProgress(pct, stepText);
                }
            }

            @Override
            public void onSuccess(File finalApk, long durationMs) {
                isRunning = false;
                progress = 100;
                stepText = "Build complete";
                apkPath = finalApk != null ? finalApk.getAbsolutePath() : "";
                saveLastBuild(finalApk, durationMs);
                broadcast(STATUS_SUCCESS, 100, stepText, null, apkPath, null, durationMs);
                BuildProgressListener l = listener;
                if (l != null) {
                    l.onFinished(true, finalApk, null, durationMs);
                }
                finishWithNotification(true, finalApk, null, durationMs);
            }

            @Override
            public void onError(String error) {
                isRunning = false;
                errorText = error != null ? error : "Build failed";
                stepText = "Build failed";
                broadcast(STATUS_ERROR, progress, stepText, null, null, errorText, 0);
                BuildProgressListener l = listener;
                if (l != null) {
                    l.onFinished(false, null, errorText, 0);
                }
                finishWithNotification(false, null, errorText, 0);
            }
        });

        return START_NOT_STICKY;
    }

    private void saveLastBuild(File apk, long durationMs) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_LAST_APK, apk != null ? apk.getAbsolutePath() : "")
                    .putString(KEY_LAST_PROJECT, projectName)
                    .putLong(KEY_LAST_SIZE, apk != null ? apk.length() : 0)
                    .putLong(KEY_LAST_DURATION_MS, durationMs)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void updateProgressNotification(int pct, String step) {
        if (pct == lastNotifiedProgress && step.equals(lastNotifiedStep)) {
            return;
        }
        lastNotifiedProgress = pct;
        lastNotifiedStep = step;
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(pct, step, true));
        } catch (Exception ignored) {
        }
    }

    private void finishWithNotification(boolean success, File apk, String error, long durationMs) {
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, buildDoneNotification(success, apk, error, durationMs));
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Builds", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("APK build progress");
        channel.setShowBadge(false);
        try {
            notificationManager.createNotificationChannel(channel);
        } catch (Exception ignored) {
        }
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private Notification buildNotification(int pct, String step, boolean ongoing) {
        String title = "Building " + projectName;
        String text = step + "  •  " + pct + "%";
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        builder.setSmallIcon(R.drawable.ic_stat_build)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, pct, false)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent())
                .setCategory(Notification.CATEGORY_PROGRESS);
        return builder.build();
    }

    private Notification buildDoneNotification(boolean success, File apk, String error, long durationMs) {
        String title;
        String text;
        if (success) {
            title = "Build complete: " + projectName;
            String size = apk != null
                    ? String.format(java.util.Locale.US, "%.2f MB", apk.length() / (1024.0 * 1024.0))
                    : "";
            String seconds = String.format(java.util.Locale.US, "%.1fs", durationMs / 1000.0);
            text = "Tap to open. " + size + "  •  " + seconds;
        } else {
            title = "Build failed: " + projectName;
            text = error != null ? error : "Unknown error";
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_DEFAULT);
        }
        builder.setSmallIcon(R.drawable.ic_stat_build)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(openAppIntent());
        return builder.build();
    }

    private void broadcast(String status, int pct, String step, String log,
                           String apk, String error, long durationMs) {
        try {
            Intent intent = new Intent(ACTION_BUILD_PROGRESS);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_STATUS, status);
            intent.putExtra(EXTRA_PROGRESS, pct);
            intent.putExtra(EXTRA_STEP, step);
            if (log != null) {
                intent.putExtra(EXTRA_LOG, log);
            }
            if (apk != null) {
                intent.putExtra(EXTRA_APK_PATH, apk);
            }
            if (error != null) {
                intent.putExtra(EXTRA_ERROR, error);
            }
            intent.putExtra(EXTRA_DURATION_MS, durationMs);
            sendBroadcast(intent);
        } catch (Exception ignored) {
        }
    }
}
