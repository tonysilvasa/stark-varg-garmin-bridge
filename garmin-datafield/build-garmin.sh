#!/usr/bin/env bash
#
# One-command build for the Stark->Garmin Connect IQ data field.
#
#   ./build-garmin.sh              # build for edge1040 (default)
#   ./build-garmin.sh edge840      # build for a specific Edge model
#
# Valid models (must have device data downloaded via the CIQ SDK manager):
#   edge530 edge830 edge540 edge840 edge1030 edge1030plus edge1040 edge1050
#
# Output: bin/StarkBridge.prg  -> copy to your Edge's GARMIN/APPS/ folder over USB.
#
set -euo pipefail
cd "$(dirname "$0")"

DEVICE="${1:-edge1040}"

# --- Java (Connect IQ compiler needs a JDK; reuse Android Studio's) ---
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  [ -x "$STUDIO_JBR/bin/java" ] && export JAVA_HOME="$STUDIO_JBR"
fi
if [ -z "${JAVA_HOME:-}" ]; then echo "ERROR: no JDK found (set JAVA_HOME)." >&2; exit 1; fi

# --- Locate the newest installed Connect IQ SDK ---
SDK_ROOT="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks"
SDK="$(ls -d "$SDK_ROOT"/connectiq-sdk-mac-* 2>/dev/null | sort | tail -1 || true)"
if [ -z "$SDK" ] || [ ! -x "$SDK/bin/monkeyc" ]; then
  echo "ERROR: no Connect IQ SDK found under $SDK_ROOT" >&2
  echo "Install it via VS Code's Monkey C extension (Monkey C: Install SDK) or the CIQ SDK Manager." >&2
  exit 1
fi
echo "SDK: $(basename "$SDK")"

# --- Developer signing key (generated once; keep it safe) ---
if [ ! -f developer_key ]; then
  echo "Generating developer_key..."
  openssl genrsa -out developer_key.pem 4096 2>/dev/null
  openssl pkcs8 -topk8 -inform PEM -outform DER -in developer_key.pem -out developer_key -nocrypt 2>/dev/null
  rm -f developer_key.pem
fi

# --- Check the device data is present ---
if [ ! -d "$HOME/Library/Application Support/Garmin/ConnectIQ/Devices/$DEVICE" ]; then
  echo "ERROR: device data for '$DEVICE' not downloaded. Get it from the CIQ SDK manager (Manage Devices)." >&2
  exit 1
fi

# --- Build ---
mkdir -p bin
echo "Building for $DEVICE..."
"$SDK/bin/monkeyc" -o bin/StarkBridge.prg -f monkey.jungle -y developer_key -d "$DEVICE" -w
echo
echo "PRG: $(pwd)/bin/StarkBridge.prg"
echo "Sideload: plug the Edge into USB, copy StarkBridge.prg into the GARMIN/APPS/ folder,"
echo "eject, then add the 'Stark Varg Bridge' field to a data screen (Data Screens > add field > Connect IQ)."
