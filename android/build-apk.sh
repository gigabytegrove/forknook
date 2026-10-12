#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
[ -n "$ANDROID_HOME" ] || { echo "Set ANDROID_HOME to your Android SDK directory" >&2; exit 1; }
VERSION=8.11.1
SHA=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/recipe-importer/gradle"
mkdir -p "$CACHE"
ZIP="$CACHE/gradle-$VERSION-bin.zip"
BINARY="$CACHE/gradle-$VERSION/bin/gradle"
if [ ! -x "$BINARY" ]; then
  if [ ! -f "$ZIP" ]; then curl --fail --location --show-error --silent "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"; fi
  echo "$SHA  $ZIP" | sha256sum -c -
  unzip -q -o "$ZIP" -d "$CACHE"
fi
"$BINARY" --no-daemon wrapper --gradle-version "$VERSION"
./gradlew --no-daemon :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk recipe-importer-1.1.0-debug.apk
echo "APK: $(pwd)/recipe-importer-1.1.0-debug.apk"
