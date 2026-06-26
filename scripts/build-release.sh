#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

python3 scripts/check_project.py
chmod +x gradlew

./gradlew assembleDebug assembleRelease --no-daemon --stacktrace

VERSION_NAME="$(grep -E 'versionName "' app/build.gradle | sed -E 's/.*versionName "([^"]+)".*/\1/')"
mkdir -p forum-build

if [[ -f app/build/outputs/apk/debug/app-debug.apk ]]; then
  cp app/build/outputs/apk/debug/app-debug.apk "forum-build/NekoFlash-${VERSION_NAME}-debug.apk"
fi
if [[ -f app/build/outputs/apk/release/app-release-unsigned.apk ]]; then
  cp app/build/outputs/apk/release/app-release-unsigned.apk "forum-build/NekoFlash-${VERSION_NAME}-release-unsigned.apk"
fi
(
  cd forum-build
  sha256sum *.apk > checksums-sha256.txt
  ls -lh
  cat checksums-sha256.txt
)
