#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
test -n "$ANDROID_HOME" || { echo "ANDROID_HOME is required" >&2; exit 1; }
test -x ./gradlew || chmod +x ./gradlew
./gradlew --no-daemon --console=plain clean testDebugUnitTest assembleDebug
version="$(sed -n "s/^[[:space:]]*versionName[[:space:]]*'\\([^']*\\)'.*/\\1/p" app/build.gradle | head -1)"
test -n "$version"
apk="app/build/outputs/versioned/Recipe-Importer-v${version}-debug.apk"
test -s "$apk" || { echo "Missing versioned APK: $apk" >&2; exit 1; }
(cd app/build/outputs/versioned && sha256sum "$(basename "$apk")" > SHA256SUMS.txt)
echo "APK: $(pwd)/$apk"
