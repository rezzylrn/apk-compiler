#!/bin/bash
set -e

PROJ=$HOME/workspace/apk-compiler
SDK=$HOME/android-sdk
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
APP=$PROJ/app/src/main
OUT=$PROJ/build

export JAVA_HOME=$HOME/jdk17/jdk-17.0.11+9
export PATH=$JAVA_HOME/bin:$PATH

echo "=== MEMULAI BUILD APK COMPILER ==="
rm -rf $OUT/gen $OUT/obj $OUT/dex $OUT/*.apk $OUT/app-unaligned.apk $OUT/app-aligned.apk
mkdir -p $OUT/gen $OUT/obj $OUT/dex

echo "[1/7] aapt2 compile resources..."
$BT/aapt2 compile --dir $APP/res -o $OUT/res.zip

echo "[2/7] aapt2 link..."
$BT/aapt2 link -o $OUT/unsigned.apk \
  -I $AJAR \
  --manifest $APP/AndroidManifest.xml \
  --java $OUT/gen \
  -A $APP/assets \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  $OUT/res.zip

# Ensure apksig-clean.jar is generated without Conscrypt / desktop native classes
if [ ! -f $PROJ/apksig-clean.jar ]; then
  echo "[2.5/7] generating apksig-clean.jar..."
  python3 -c '
import zipfile, os
src_jar = "'$SDK'/build-tools/34.0.0/lib/apksigner.jar"
dst_jar = "'$PROJ'/apksig-clean.jar"
with zipfile.ZipFile(src_jar) as src:
    with zipfile.ZipFile(dst_jar, "w") as dst:
        for item in src.infolist():
            if item.filename.startswith("com/android/apksig/") and item.filename.endswith(".class"):
                dst.writestr(item.filename, src.read(item.filename))
'
fi

echo "[3/7] javac compile sources..."
find $OUT/gen -name "*.java" > $OUT/sources.txt
find $APP/java -name "*.java" >> $OUT/sources.txt
cat $OUT/sources.txt
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -cp $AJAR \
  -d $OUT/obj \
  @$OUT/sources.txt

# Ensure java-compiler-api.jar is generated from OpenJDK java.compiler.jmod and java.base.jmod (with lambda stubs)
echo "[3.5/7] generating java-compiler-api.jar..."
python3 -c '
import zipfile, os
base_jmod = "'$JAVA_HOME'/jmods/java.base.jmod"
compiler_jmod = "'$JAVA_HOME'/jmods/java.compiler.jmod"
out_jar = "'$PROJ'/java-compiler-api.jar"
with zipfile.ZipFile(out_jar, "w") as out_z:
    with zipfile.ZipFile(compiler_jmod, "r") as jmod:
        for item in jmod.infolist():
            if item.filename.startswith("classes/") and item.filename.endswith(".class"):
                rel_name = item.filename[len("classes/"):]
                if rel_name == "module-info.class" or rel_name == "javax/lang/model/SourceVersion.class":
                    continue
                out_z.writestr(rel_name, jmod.read(item.filename))
    with zipfile.ZipFile(base_jmod, "r") as jmod:
        for item in jmod.infolist():
            if item.filename in [
                "classes/java/lang/invoke/LambdaMetafactory.class",
                "classes/java/lang/invoke/SerializedLambda.class",
                "classes/java/lang/invoke/SerializedLambda$1.class",
                "classes/java/lang/invoke/TypeDescriptor.class",
                "classes/java/lang/invoke/TypeDescriptor$OfField.class",
                "classes/java/lang/invoke/TypeDescriptor$OfMethod.class"
            ]:
                rel_name = item.filename[len("classes/"):]
                out_z.writestr(rel_name, jmod.read(item.filename))
'
cp $PROJ/java-compiler-api.jar $APP/assets/java-compiler-api.jar

# Ensure apksig-clean.jar is generated without Conscrypt / desktop native classes
if [ ! -f $PROJ/apksig-clean.jar ]; then
  echo "[3.6/7] generating apksig-clean.jar..."
  python3 -c '
import zipfile, os
src_jar = "'$SDK'/build-tools/34.0.0/lib/apksigner.jar"
dst_jar = "'$PROJ'/apksig-clean.jar"
with zipfile.ZipFile(src_jar) as src:
    with zipfile.ZipFile(dst_jar, "w") as dst:
        for item in src.infolist():
            if item.filename.startswith("com/android/apksig/") and item.filename.endswith(".class"):
                dst.writestr(item.filename, src.read(item.filename))
'
fi

echo "[4/7] d8 dex (bundling ECJ + D8 + java.compiler API + apksig into native dex)..."
$BT/d8 --lib $AJAR --min-api 24 --output $OUT/dex \
  $(find $OUT/obj -name "*.class") \
  $PROJ/ecj.jar \
  $PROJ/java-compiler-api.jar \
  $PROJ/apksig-clean.jar \
  $SDK/build-tools/34.0.0/lib/d8.jar

echo "[5/7] inject dex and native libs to apk..."
cp $OUT/unsigned.apk $OUT/app-unaligned.apk
(cd $OUT/dex && zip -q -X ../app-unaligned.apk *.dex)

# Native library (aapt2 for arm64-v8a)
if [ -d $APP/jniLibs/arm64-v8a ]; then
  mkdir -p $OUT/stage/lib/arm64-v8a
  cp $APP/jniLibs/arm64-v8a/*.so $OUT/stage/lib/arm64-v8a/
  chmod 755 $OUT/stage/lib/arm64-v8a/*.so
  (cd $OUT/stage && zip -q -0 -X ../app-unaligned.apk -r lib)
fi

# Extract non-class resources from ecj.jar and inject into app-unaligned.apk
echo "[5.1/7] inject ecj parser resources and property bundles into apk..."
rm -rf $OUT/stage/res_bundle
mkdir -p $OUT/stage/res_bundle
python3 -c '
import zipfile, os
for jar in ["'$PROJ'/ecj.jar", "'$SDK'/build-tools/34.0.0/lib/d8.jar"]:
    if os.path.exists(jar):
        with zipfile.ZipFile(jar) as z:
            for item in z.infolist():
                if not item.filename.endswith(".class") and not item.filename.startswith("META-INF/") and not item.is_dir():
                    z.extract(item, "'$OUT'/stage/res_bundle")
'
(cd $OUT/stage/res_bundle && zip -q -r -X ../../app-unaligned.apk .)

echo "[6/7] zipalign..."
$BT/zipalign -p -f 4 $OUT/app-unaligned.apk $OUT/app-aligned.apk

echo "[7/7] apksigner..."
KEYSTORE=$PROJ/debug.keystore
if [ ! -f $KEYSTORE ]; then
  if [ -f $HOME/workspace/tamago-apk/debug.keystore ]; then
    cp $HOME/workspace/tamago-apk/debug.keystore $KEYSTORE
  else
    keytool -genkeypair -keystore $KEYSTORE -alias androiddebugkey \
      -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
      -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
  fi
fi

$BT/apksigner sign --ks $KEYSTORE \
  --ks-pass pass:android --key-pass pass:android \
  --out $PROJ/ApkCompiler.apk $OUT/app-aligned.apk

echo "=== VERIFIKASI APK ==="
$BT/apksigner verify --print-certs $PROJ/ApkCompiler.apk | head -5
ls -la $PROJ/ApkCompiler.apk
echo "BUILD_SUCCESSFUL"
