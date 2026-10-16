# APK Compiler (Android On-Device Compiler & Git Cloner)

Aplikasi Android native mandiri untuk meng-clone repository proyek Android dari GitHub, memilih direktori lokal, mengelola dependensi build/toolchain (JDK, ECJ, D8, android.jar, AAPT2) langsung dari dalam aplikasi, dan mengompilasi proyek menjadi file APK siap install.

## Fitur Utama

1. **GitHub Git Cloner**:
   - Input URL repository GitHub (format `https://github.com/owner/repo` atau branch custom).
   - Mengunduh zipball repositori secara otomatis dengan penanganan HTTP 301/302 redirects.
   - Pengekstrak zip aman dengan perlindungan *Zip Slip*.
   - Auto-detect proyek Android setelah diekstrak.

2. **Project Scanner & Folder Navigator**:
   - Mendeteksi struktur proyek Android (Gradle layout `app/src/main` maupun Eclipse/Flat layout).
   - Parsing otomatis `AndroidManifest.xml` (package name, minSdkVersion, targetSdkVersion).
   - Penghitungan otomatis file sumber Java dan resource assets.
   - Folder picker lokal untuk memilih folder proyek mana saja di penyimpanan perangkat.

3. **In-App Toolchain & Dependency Manager**:
   - Memeriksa kelengkapan dependensi compiler secara real-time.
   - Tombol satu klik: **"Pasang Semua Dependensi"** untuk mengunduh komponen compiler:
     - Eclipse Compiler for Java (`ecj.jar` - JDK bytecode compiler untuk Android Dalvik/ART).
     - Android D8 Dexer (`d8.jar` - konversi .class ke classes.dex).
     - Android SDK Platform (`android.jar` - API definitions).
     - AAPT2 Tool (kompilasi layout, resources, dan link manifest).
     - Android Debug Keystore (kunci kriptografi RSA 2048-bit untuk penandatanganan APK).

4. **Pipeline Kompilasi On-Device**:
   - **Step 1: Resource & Manifest** -> Menghasilkan `R.java` dan base APK zip.
   - **Step 2: Java Compile** -> Mengompilasi source `.java` menjadi `.class`.
   - **Step 3: DEX Compile** -> Mengonversi `.class` menjadi `classes.dex`.
   - **Step 4: Package & ZipAlign** -> Merakit APK dan merapikan 4-byte boundary.
   - **Step 5: Sign APK** -> Menandatangani APK dengan keystore debug.
   - **Step 6: Output & Action** -> Tombol langsung **"Install APK"** (via `FileProvider`) dan **"Bagikan File"**.

5. **Terminal Console & UI**:
   - Antarmuka Material 3 Dark theme (obsidian `#0D0F12`, surface `#161920`, accent `#38BDF8` & `#22C55E`).
   - Log terminal real-time dengan auto-scroll dan penanda status.
   - Tanpa emoji dan tanpa AI-slop styling.

## Lokasi Artifact APK
- Output build: `ApkCompiler.apk` (Root direktori proyek)
- Ukuran: ~63 KB (Sangat ringan, modular, efisien)

## Cara Build Ulang
```bash
cd ~/workspace/apk-compiler
./build.sh
```
