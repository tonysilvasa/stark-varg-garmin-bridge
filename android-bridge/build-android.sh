#!/usr/bin/env bash
#
# One-command build (and optional install) for the Stark->Garmin Android bridge.
#
#   ./build-android.sh           # just build the debug APK
#   ./build-android.sh install   # build, then install + launch on a plugged-in phone
#
set -euo pipefail
cd "$(dirname "$0")"

# --- Java: prefer Android Studio's bundled JDK (no system Java needed) ---
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  if [ -x "$STUDIO_JBR/bin/java" ]; then
    export JAVA_HOME="$STUDIO_JBR"
  else
    echo "ERROR: No JDK found. Install Android Studio or set JAVA_HOME." >&2
    exit 1
  fi
fi
echo "Using JAVA_HOME=$JAVA_HOME"

# --- SDK location for Gradle ---
SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ ! -d "$SDK_DIR" ]; then
  echo "ERROR: Android SDK not found at $SDK_DIR (set ANDROID_HOME)." >&2
  exit 1
fi
echo "sdk.dir=$SDK_DIR" > local.properties

# --- Build ---
./gradlew assembleDebug --console=plain
APK="app/build/outputs/apk/debug/app-debug.apk"
echo
echo "APK: $(pwd)/$APK"

# --- Optional install + launch ---
if [ "${1:-}" = "install" ]; then
  ADB="$SDK_DIR/platform-tools/adb"
  if [ ! -x "$ADB" ]; then echo "adb not found at $ADB" >&2; exit 1; fi
  if [ -z "$("$ADB" devices | sed '1d' | grep -w device || true)" ]; then
    echo "No phone detected. Enable USB debugging and plug it in, then re-run with 'install'." >&2
    exit 1
  fi
  echo "Installing..."
  "$ADB" install -r "$APK"
  "$ADB" shell am start -n com.tony.starkbridge/.MainActivity
  echo "Launched Stark Bridge on the phone."
fi
