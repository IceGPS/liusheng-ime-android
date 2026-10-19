#!/bin/bash
# 手动构建 APK（不依赖 Gradle daemon）。
#
# 背景：某些沙箱环境会拦截 Gradle daemon 的本地回环 TCP 通信，
# 导致 `gradle assembleDebug` 报 "Could not receive a message from the daemon"。
# 本脚本用 kotlinc + aapt2 + d8 + apksigner 手工走完 AGP 的关键步骤，
# 产物与 `gradle :app:assembleDebug` 等价（debug 签名，未做资源压缩/混淆）。
#
# 依赖：JDK 17、Android SDK（platform android-34、build-tools 34.0.0）、
#       官方 kotlinc（https://github.com/JetBrains/kotlin/releases）。
# 注意：本脚本不编译 C++（无 NDK 时 MNN bridge 跳过，运行时自动降级）。
#
# 用法：
#   tools/build-apk-manual.sh <输出APK路径>
# 示例：
#   tools/build-apk-manual.sh dist/liusheng-ime-android-v2.apk
set -euo pipefail

OUT_APK="${1:?用法: $0 <输出APK路径>}"
PROJ_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

: "${JAVA_HOME:?请设置 JAVA_HOME（JDK 17）}"
: "${ANDROID_HOME:?请设置 ANDROID_HOME}"
: "${KOTLINC_HOME:?请设置 KOTLINC_HOME（官方 kotlinc 解压目录，含 bin/kotlinc）}"

BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
APP_ID="com.icegps.voiceime"
VERSION_CODE=10
VERSION_NAME="0.10.0-v10"

export PATH="$JAVA_HOME/bin:$BT:$PATH"
DEPS_DIR="$BUILD_DIR/deps"
mkdir -p "$DEPS_DIR"
echo "== 下载依赖（okhttp/okio/kotlin-stdlib）"
for art in \
  "org/jetbrains/kotlin/kotlin-stdlib/2.0.20/kotlin-stdlib-2.0.20.jar" \
  "com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar" \
  "com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar" \
  "org/jetbrains/annotations/13.0/annotations-13.0.jar" ; do
  f="$DEPS_DIR/$(basename "$art")"
  [ -f "$f" ] || curl -sSL --max-time 120 -o "$f" "https://repo.maven.apache.org/maven2/$art"
done
CP_DEPS="$DEPS_DIR/kotlin-stdlib-2.0.20.jar:$DEPS_DIR/okhttp-4.12.0.jar:$DEPS_DIR/okio-jvm-3.6.0.jar:$DEPS_DIR/annotations-13.0.jar"

echo "== aapt2 compile/link（资源 + R.java + base.apk）"
mkdir -p "$BUILD_DIR/compiled-res" "$BUILD_DIR/gen"
"$BT/aapt2" compile --dir "$PROJ_DIR/app/src/main/res" -o "$BUILD_DIR/compiled-res/"
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="'"$APP_ID"'">|' \
  "$PROJ_DIR/app/src/main/AndroidManifest.xml" > "$BUILD_DIR/AndroidManifest.xml"
"$BT/aapt2" link -o "$BUILD_DIR/base.apk" \
  --java "$BUILD_DIR/gen" \
  --manifest "$BUILD_DIR/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -I "$PLATFORM" "$BUILD_DIR"/compiled-res/*.flat

echo "== kotlinc（全部 Kotlin 源码 + R.java）"
# 生成 BuildInfo.kt（版本/构建日期/git 提交号）
mkdir -p "$PROJ_DIR/app/src/main/java/com/icegps/voiceime/util"
BUILD_DATE="$(date '+%Y-%m-%d %H:%M %Z')"
GIT_SHA="$(git -C "$PROJ_DIR" rev-parse --short HEAD 2>/dev/null || echo "unknown")"
cat > "$PROJ_DIR/app/src/main/java/com/icegps/voiceime/util/BuildInfo.kt" <<EOF
package com.icegps.voiceime.util

/** 构建时自动生成，请勿手改 */
object BuildInfo {
    const val VERSION_NAME = "$VERSION_NAME"
    const val VERSION_CODE = $VERSION_CODE
    const val BUILD_DATE = "$BUILD_DATE"
    const val GIT_SHA = "$GIT_SHA"
}
EOF
mkdir -p "$BUILD_DIR/classes"
"$KOTLINC_HOME/bin/kotlinc" -jvm-target 17 -no-reflect \
  -cp "$PLATFORM:$CP_DEPS" \
  -d "$BUILD_DIR/classes" \
  $(find "$PROJ_DIR/app/src/main/java" -name "*.kt") \
  "$BUILD_DIR/gen/com/icegps/voiceime/R.java"

echo "== d8（dex）"
mkdir -p "$BUILD_DIR/dex"
(cd "$BUILD_DIR/classes" && "$JAVA_HOME/bin/jar" cf "$BUILD_DIR/app-classes.jar" .)
"$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 26 --lib "$PLATFORM" --output "$BUILD_DIR/dex/" \
  "$BUILD_DIR/app-classes.jar" \
  "$DEPS_DIR/kotlin-stdlib-2.0.20.jar" "$DEPS_DIR/okhttp-4.12.0.jar" "$DEPS_DIR/okio-jvm-3.6.0.jar"

echo "== 打包/对齐/签名"
cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/"
(cd "$BUILD_DIR" && "$BT/aapt" add base.apk classes.dex > /dev/null)
"$BT/zipalign" -f 4 "$BUILD_DIR/base.apk" "$BUILD_DIR/aligned.apk"
KEYSTORE="$HOME/.voice-ime-debug.keystore"
[ -f "$KEYSTORE" ] || "$JAVA_HOME/bin/keytool" -genkeypair \
  -keystore "$KEYSTORE" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Android Debug,O=Android,C=US" > /dev/null
"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD_DIR/signed.apk" "$BUILD_DIR/aligned.apk"
"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" verify "$BUILD_DIR/signed.apk"

mkdir -p "$(dirname "$OUT_APK")"
cp "$BUILD_DIR/signed.apk" "$OUT_APK"
echo "== 完成：$OUT_APK ($(stat -c%s "$OUT_APK") bytes)"
"$BT/aapt" dump badging "$OUT_APK" | head -2
