#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
test -n "$ANDROID_HOME" || { echo "Set ANDROID_HOME to your Android SDK" >&2; exit 1; }
./gradlew --no-daemon --console=plain clean testDebugUnitTest assembleDebug
version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle | head -n 1)"
test -n "$version" || { echo "Missing versionName in app/build.gradle" >&2; exit 1; }
mkdir -p app/build/outputs/versioned
cp app/build/outputs/apk/debug/app-debug.apk "app/build/outputs/versioned/RecipeCove-v${version}-debug.apk"
(
 cd app/build/outputs/versioned
 sha256sum "RecipeCove-v${version}-debug.apk" > SHA256SUMS.txt
)
echo "APK: $(pwd)/app/build/outputs/versioned/RecipeCove-v${version}-debug.apk"
