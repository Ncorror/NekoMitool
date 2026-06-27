#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

python3 scripts/check_project.py

if [[ -f gradle/wrapper/gradle-wrapper.jar ]]; then
  chmod +x gradlew
  ./gradlew assembleDebug
elif command -v gradle >/dev/null 2>&1; then
  gradle assembleDebug
else
  cat >&2 <<'EOF'
ERROR: neither Gradle Wrapper nor a Gradle executable in PATH was found.

Options:
  1. Check that Java 17+ is installed.
  2. Run: ./gradlew assembleDebug.
  3. To replace the minimal bootstrap with the official wrapper: ./gradlew wrapper --gradle-version 8.4.
EOF
  exit 1
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  VERSION_NAME="$(grep -E 'versionName "' app/build.gradle | sed -E 's/.*versionName "([^"]+)".*/\1/')"
  mkdir -p forum-build
  cp "$APK" "forum-build/NekoFlash-${VERSION_NAME}-debug.apk"
  (cd forum-build && sha256sum "NekoFlash-${VERSION_NAME}-debug.apk" > checksums-sha256.txt)
  echo "Built: forum-build/NekoFlash-${VERSION_NAME}-debug.apk"
fi
