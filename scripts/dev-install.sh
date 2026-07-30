#!/usr/bin/env bash
#
# Build the dev variant and get it onto the phone, bypassing Play review entirely.
#
#   ./scripts/dev-install.sh          build + install over adb
#   ./scripts/dev-install.sh --push   build + drop the APK in /sdcard/Download to tap
#   ./scripts/dev-install.sh --logs   install, then tail the app's logcat
#
# The dev build is `app.greenforrest.yap.debug` — a SEPARATE app from the Play build, on
# the same Firebase project. Same notes, same account, same functions. Installing it never
# touches the Play copy or its recordings. See docs/LOCAL_BUILDS.md.
set -euo pipefail

cd "$(dirname "$0")/.."

PUSH_ONLY=false
TAIL_LOGS=false
for arg in "$@"; do
  case "$arg" in
    --push) PUSH_ONLY=true ;;
    --logs) TAIL_LOGS=true ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="app.greenforrest.yap.debug"

echo "==> Building $PKG"
./gradlew :app:assembleDebug -q

[ -f "$APK" ] || { echo "build produced no APK at $APK" >&2; exit 1; }
echo "==> Built $(du -h "$APK" | cut -f1)"

if ! adb get-state >/dev/null 2>&1; then
  echo "No device. Connect USB, or pair wireless debugging:" >&2
  echo "  adb pair <phone-ip>:<pair-port>   then   adb connect <phone-ip>:<port>" >&2
  exit 1
fi

if [ "$PUSH_ONLY" = true ]; then
  # MSYS_NO_PATHCONV stops Git Bash rewriting /sdcard/... into a Windows path.
  MSYS_NO_PATHCONV=1 adb push "$APK" /sdcard/Download/yap-dev.apk
  echo "==> Pushed to Downloads. Open Files › Download › yap-dev.apk on the phone."
  exit 0
fi

echo "==> Installing"
if ! adb install -r "$APK"; then
  # MIUI/HyperOS refuses adb installs unless "Install via USB" is on in Developer options,
  # and reports it as INSTALL_FAILED_USER_RESTRICTED. Falling back beats failing.
  echo "==> adb install refused by the device; pushing to Downloads instead" >&2
  MSYS_NO_PATHCONV=1 adb push "$APK" /sdcard/Download/yap-dev.apk
  echo "==> Open Files › Download › yap-dev.apk on the phone to install." >&2
  exit 0
fi

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
echo "==> Installed and launched."

if [ "$TAIL_LOGS" = true ]; then
  echo "==> Tailing logcat (Ctrl-C to stop)"
  adb logcat --pid="$(adb shell pidof -s "$PKG")" 2>/dev/null
fi
