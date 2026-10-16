import com.rakyatid.apkcompiler.engine.*;
import com.rakyatid.apkcompiler.model.*;

import java.io.File;

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

        System.out.println("\nALL ENGINE TESTS PASSED SUCCESSFULLY!");
    }
}
