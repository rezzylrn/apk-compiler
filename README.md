# APK Compiler (Android On-Device Compiler & Git Cloner)

A standalone native Android app to clone Android project repositories from GitHub, pick a local directory, manage build/toolchain dependencies (JDK, ECJ, D8, android.jar, AAPT2) from inside the app, and compile a project into a ready-to-install APK file.

## Preview

<p>
  <img src="screenshots/01-projects.jpg" width="170" alt="Projects tab">
  <img src="screenshots/02-build.jpg" width="170" alt="Build tab, finished build">
  <img src="screenshots/03-tools.jpg" width="170" alt="Tools tab">
  <img src="screenshots/04-files.jpg" width="170" alt="Files tab">
  <img src="screenshots/05-editor.jpg" width="170" alt="In-app code editor">
</p>

## How It Works

![How APK Compiler works illustration](screenshots/how-it-works.png)

1. **Choose a project** — Clone a GitHub repo, or open a local project folder already on your phone.
2. **Project Scanner** — Reads `AndroidManifest.xml`, counts Java & resource files, and detects the SDK versions.
3. **On-device toolchain** — AAPT2, ECJ, D8, apksig, and AndroidX are all built into the app, no root needed.
4. **Four-stage pipeline** — Resources (aapt2) → Java (ECJ) → Dex (D8) → Sign (apksig).
5. **Background build** — Close the app and the build keeps running; progress shows in your phone's notification.
6. **APK ready to install** — The result is an `.apk` file. Install it right away, or Share it anywhere.

## Features

1. **GitHub Git Cloner**:
   - Input a GitHub repository URL (format `https://github.com/owner/repo`, or a custom branch).
   - Downloads the repository zipball automatically, with HTTP 301/302 redirect handling.
   - Safe zip extraction with *Zip Slip* protection.
   - Auto-detects the Android project after extraction.

2. **Project Scanner & Folder Navigator**:
   - Detects Android project structure (Gradle layout `app/src/main` or Eclipse/Flat layout).
   - Automatic `AndroidManifest.xml` parsing (package name, minSdkVersion, targetSdkVersion).
   - Automatic count of Java source files and resource assets.
   - Local folder picker to choose any project folder on device storage.

3. **In-App Toolchain & Dependency Manager**:
   - Real-time check of compiler dependency completeness.
   - One-tap button: **"Install All Dependencies"** to download the compiler components:
     - Eclipse Compiler for Java (`ecj.jar` - JDK bytecode compiler for Android Dalvik/ART).
     - Android D8 Dexer (`d8.jar` - converts .class files to classes.dex).
     - Android SDK Platform (`android.jar` - API definitions).
     - AAPT2 Tool (layout/resource compilation and manifest linking).
     - Android Debug Keystore (RSA 2048-bit cryptographic key for APK signing).

4. **On-Device Compilation Pipeline**:
   - **Step 1: Resource & Manifest** -> Generates `R.java` and the base APK zip.
   - **Step 2: Java Compile** -> Compiles `.java` sources into `.class` files.
   - **Step 3: DEX Compile** -> Converts `.class` files into `classes.dex`.
   - **Step 4: Package & ZipAlign** -> Assembles the APK and aligns the 4-byte boundary.
   - **Step 5: Sign APK** -> Signs the APK with the debug keystore.
   - **Step 6: Output & Action** -> Direct **"Install APK"** (via `FileProvider`) and **"Share File"** buttons.

5. **Terminal Console & UI (v1.3.1)**:
   - Calm dark low-contrast theme: dark slate surfaces, soft gray text (not white), one muted steel-blue accent. No gradients, no glow, no emoji. The bright white v1.3.0 theme was replaced because it was too contrasty.
   - New logo: a single steel-blue `</>` mark on a dark slate square, no white card and no bright blue.
   - Icon tabs (Projects, Build, Tools, Files) with an active tab indicator.
   - Visual pipeline stepper: Resources, Java, Dex, Sign.
   - Icon project and toolchain component cards, text trimmed to what is needed. Fixed placement: fixed-height 56dp header (single-line subtitle), vertical project/SDK cards with stats aligned under the title, main buttons without side icons (centered text).
   - Real-time terminal log with auto-scroll and status markers; an identical line is never printed twice, and the Install all button cannot be double-tapped.
   - **Background build**: compilation runs through a foreground service, keeps running even after the app is closed, with a progress bar in the notification. Reopen the app to see the result; the finished notification can be tapped to return to the app.

## APK Artifact Location
- Build output: `ApkCompiler.apk` (project root directory)
- Size: ~14.9 MB (self-contained: ECJ, D8, apksig, static AAPT2, AndroidX essentials)

## How to Rebuild
```bash
cd ~/workspace/apk-compiler
./build.sh
```
