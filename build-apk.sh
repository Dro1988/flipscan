#!/bin/bash
# Manual Android APK build for FlipScan (no Gradle: Java TCP is blocked in this sandbox).
# Pattern follows RoRemote's build-apk.sh; only dependency is ZXing core.
set -e
export JAVA_HOME=/home/hatch/workspace/.tools/jdk/jdk-21.0.12.1+1
export PATH=$JAVA_HOME/bin:$PATH
SDK=/home/hatch/workspace/.tools/android-sdk
BT=$SDK/build-tools/35.0.0
AAPT2=$BT/aapt2
ANDROID_JAR=$SDK/platforms/android-36/android.jar
PROJ=/home/hatch/workspace/flipscan
BUILD=$PROJ/build
APPID=com.droapps.flipscan
MINSDK=26
TARGETSDK=36
VERSION_CODE=2
VERSION_NAME=1.1
ZXING_JAR=/home/hatch/workspace/.tools/aar/zxing-core-3.5.3/classes.jar

rm -rf "$BUILD"
mkdir -p "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "== 1. aapt2 compile =="
$AAPT2 compile --dir "$PROJ/app/src/main/res" -o "$BUILD/compiled.zip"

echo "== 2. aapt2 link =="
$AAPT2 link -o "$BUILD/app.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  --min-sdk-version $MINSDK --target-sdk-version $TARGETSDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  --java "$BUILD/gen" \
  "$BUILD/compiled.zip"

echo "== 3. javac =="
find "$BUILD/gen" "$PROJ/app/src/main/java" -name "*.java" > "$BUILD/sources.txt"
javac --release 17 -nowarn \
  -cp "$ANDROID_JAR:$ZXING_JAR" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== 4. d8 (app classes + zxing) =="
$BT/d8 --min-api $MINSDK --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name "*.class") "$ZXING_JAR"

echo "== 5. add dex =="
cp "$BUILD/app.apk" "$BUILD/app-unsigned.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/app-unsigned.apk" classes.dex)

echo "== 6. zipalign + sign =="
KEYSTORE=$PROJ/flipscan-release.keystore
PASSFILE=$PROJ/keystore-pass.txt
if [ ! -f "$KEYSTORE" ]; then
  echo "generating dedicated FlipScan keystore..."
  head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24 > "$PASSFILE"
  chmod 600 "$PASSFILE"
  keytool -genkeypair -keystore "$KEYSTORE" -alias flipscan -keyalg RSA -keysize 2048 \
    -validity 9125 -storepass "$(cat $PASSFILE)" \
    -keypass "$(cat $PASSFILE)" \
    -dname "CN=FlipScan, OU=Mobile, O=DroApps, L=Homerville, ST=GA, C=US"
fi
$BT/zipalign -f 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"
KSPASS=$(cat "$PASSFILE")
$BT/apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KSPASS" \
  --key-pass "pass:$KSPASS" \
  --out "$BUILD/FlipScan.apk" "$BUILD/app-aligned.apk"
unset KSPASS
$BT/apksigner verify --print-certs "$BUILD/FlipScan.apk" | head -3
ls -la "$BUILD/FlipScan.apk"
