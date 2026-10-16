import com.rakyatid.apkcompiler.engine.*;
import com.rakyatid.apkcompiler.model.*;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestEngine {
    public static void main(String[] args) {
        System.out.println("=== RUNNING ENGINE TESTS ===");

        // Test 1: ProjectScanner on tamago-apk
        File tamagoDir = new File("/home/hatch/workspace/tamago-apk");
        if (tamagoDir.exists()) {
            Project p = ProjectScanner.scan(tamagoDir);
            System.out.println("Test 1: ProjectScanner (tamago-apk)");
            System.out.println("  Name: " + p.getName());
            System.out.println("  Valid: " + p.isValid());
            System.out.println("  Package: " + p.getPackageName());
            System.out.println("  SDK: " + p.getMinSdk() + " -> " + p.getTargetSdk());
            System.out.println("  Java files: " + p.getJavaFilesCount());
            System.out.println("  Res files: " + p.getResFilesCount());

            if (!p.isValid() || p.getPackageName() == null) {
                System.err.println("TEST 1 FAILED: Invalid project scan");
                System.exit(1);
            }
            System.out.println("TEST 1 PASSED!");
        }

        // Test 2: ProjectScanner on jurnal
        File jurnalDir = new File("/home/hatch/workspace/jurnal");
        if (jurnalDir.exists()) {
            Project p2 = ProjectScanner.scan(jurnalDir);
            System.out.println("Test 2: ProjectScanner (jurnal)");
            System.out.println("  Name: " + p2.getName());
            System.out.println("  Valid: " + p2.isValid());
            System.out.println("  Package: " + p2.getPackageName());
            System.out.println("  SDK: " + p2.getMinSdk() + " -> " + p2.getTargetSdk());
            System.out.println("  Java files: " + p2.getJavaFilesCount());

            if (!p2.isValid()) {
                System.err.println("TEST 2 FAILED: Jurnal project scan failed");
                System.exit(1);
            }
            System.out.println("TEST 2 PASSED!");
        }

        // Test 3: ProjectScanner on apk-compiler itself
        File thisDir = new File("/home/hatch/workspace/apk-compiler");
        Project p3 = ProjectScanner.scan(thisDir);
        System.out.println("Test 3: ProjectScanner (apk-compiler itself)");
        System.out.println("  Name: " + p3.getName());
        System.out.println("  Valid: " + p3.isValid());
        System.out.println("  Package: " + p3.getPackageName());
        System.out.println("  SDK: " + p3.getMinSdk() + " -> " + p3.getTargetSdk());
        System.out.println("  Java files: " + p3.getJavaFilesCount());
        System.out.println("  Res files: " + p3.getResFilesCount());

        if (!p3.isValid() || !"com.rakyatid.apkcompiler".equals(p3.getPackageName())) {
            System.err.println("TEST 3 FAILED: Self scan failed");
            System.exit(1);
        }
        System.out.println("TEST 3 PASSED!");

        // Test 4: ProjectScanner & Dependency Detection on compass
        File compassDir = new File("/home/hatch/workspace/compass");
        if (compassDir.exists()) {
            System.out.println("Test 4: ProjectScanner & Dependency Detection (compass)");
            Project p4 = ProjectScanner.scan(compassDir);
            System.out.println("  Name: " + p4.getName());
            System.out.println("  Package: " + p4.getPackageName());
            System.out.println("  Dependencies count: " + p4.getDependencies().size());

            boolean hasAppCompat = false;
            for (Dependency d : p4.getDependencies()) {
                System.out.println("   - " + d.getCoordinates() + " [" + d.getType() + "]");
                if (d.getCoordinates().contains("androidx.appcompat:appcompat")) {
                    hasAppCompat = true;
                }
            }

            if (!p4.isValid() || !"com.sevencrayons.compass".equals(p4.getPackageName()) || !hasAppCompat) {
                System.err.println("TEST 4 FAILED: Compass dependency detection failed");
                System.exit(1);
            }
            System.out.println("TEST 4 PASSED!");

            // Test 5: End-to-end Compilation of compass with AndroidX AppCompat
            System.out.println("Test 5: End-to-End Build of compass (AAPT2 Link + ECJ + D8 + Apksig)");
            ToolchainManager tm = new ToolchainManager(null);
            ApkBuilderEngine engine = new ApkBuilderEngine(null, tm);

            CountDownLatch latch = new CountDownLatch(1);
            boolean[] success = new boolean[1];
            File[] outApk = new File[1];

            engine.buildProject(p4, new ApkBuilderEngine.BuildListener() {
                @Override
                public void onLog(String line) {
                    if (line.startsWith("[DEP") || line.startsWith("[AAPT2") || line.startsWith("[ECJ") || line.startsWith("[D8") || line.startsWith("[SIGN") || line.startsWith("[OUTPUT") || line.startsWith("===")) {
                        System.out.println("  " + line);
                    }
                }

                @Override
                public void onProgress(int step, int totalSteps, String message) {
                    System.out.println("  Progress [" + step + "/" + totalSteps + "] " + message);
                }

                @Override
                public void onSuccess(File finalApk, long durationMs) {
                    System.out.println("  BUILD COMPLETED SUCCESSFULLY in " + durationMs + "ms: " + finalApk.getAbsolutePath());
                    success[0] = true;
                    outApk[0] = finalApk;
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    System.err.println("  BUILD FAILED: " + error);
                    success[0] = false;
                    latch.countDown();
                }
            });

            try {
                boolean completed = latch.await(90, TimeUnit.SECONDS);
                if (!completed || !success[0] || outApk[0] == null || !outApk[0].exists()) {
                    System.err.println("TEST 5 FAILED: Compass build failed or timed out");
                    System.exit(1);
                }
            } catch (InterruptedException e) {
                System.err.println("TEST 5 INTERRUPTED: " + e.getMessage());
                System.exit(1);
            }
            System.out.println("TEST 5 PASSED!");
        }

        System.out.println("\nALL ENGINE TESTS PASSED SUCCESSFULLY 100%!");
    }
}
