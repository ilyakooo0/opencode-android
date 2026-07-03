#!/usr/bin/env bash
#
# Build the debug APK, then install and launch the app on a physically
# connected device (USB or wireless). Unlike scripts/run.sh, this never boots
# an emulator — it targets real hardware only.
#
# Usage:
#   scripts/run-device.sh                    # build + install + launch on the connected device
#   scripts/run-device.sh -s <serial>        # target a specific device (see `adb devices`)
#   scripts/run-device.sh -c 192.168.1.5     # adb connect over Wi-Fi first (host or host:port)
#   scripts/run-device.sh --no-build         # skip the Gradle build, just install + launch
#   scripts/run-device.sh --release          # build/install the release variant
#
set -euo pipefail

APP_ID="soy.iko.opencode"
ACTIVITY=".MainActivity"
VARIANT="debug"
DO_BUILD=1
SERIAL=""
CONNECT=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial)   SERIAL="$2"; shift 2 ;;
        -c|--connect)  CONNECT="$2"; shift 2 ;;
        --no-build)    DO_BUILD=0; shift ;;
        --release)     VARIANT="release"; shift ;;
        -h|--help)
            awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"
            exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

cd "$(dirname "$0")/.."

# --- locate adb -------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
find_tool() {
    local name="$1"
    if command -v "$name" >/dev/null 2>&1; then command -v "$name"; return; fi
    for d in "$SDK/platform-tools" "$SDK/cmdline-tools/latest/bin"; do
        [[ -x "$d/$name" ]] && { echo "$d/$name"; return; }
    done
    echo ""
}
ADB="$(find_tool adb)"
[[ -z "$ADB" ]] && { echo "error: 'adb' not found; set ANDROID_HOME / ANDROID_SDK_ROOT" >&2; exit 1; }

# --- optional wireless connect ---------------------------------------------
if [[ -n "$CONNECT" ]]; then
    [[ "$CONNECT" == *:* ]] || CONNECT="$CONNECT:5555"
    echo ">> Connecting to $CONNECT..."
    "$ADB" connect "$CONNECT"
    SERIAL="${SERIAL:-$CONNECT}"
fi

# --- pick a physical device (exclude emulators) -----------------------------
# Lists serials of connected, ready devices whose serial is NOT emulator-*.
physical_devices() {
    "$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1}'
}

if [[ -z "$SERIAL" ]]; then
    DEVICES=()
    while IFS= read -r line; do [[ -n "$line" ]] && DEVICES+=("$line"); done < <(physical_devices)
    case "${#DEVICES[@]}" in
        0) echo "error: no physical device connected (enable USB debugging, or use -c for Wi-Fi)" >&2; exit 1 ;;
        1) SERIAL="${DEVICES[0]}" ;;
        *) echo "error: multiple devices connected; choose one with -s <serial>:" >&2
           printf '  %s\n' "${DEVICES[@]}" >&2
           exit 1 ;;
    esac
fi
echo ">> Target device: $SERIAL"

# Confirm the chosen device is actually ready.
if ! "$ADB" -s "$SERIAL" get-state 2>/dev/null | grep -q '^device$'; then
    echo "error: device '$SERIAL' is not ready (unauthorized or offline?)" >&2
    exit 1
fi

# --- build ------------------------------------------------------------------
if [[ "$VARIANT" == "release" ]]; then
    GRADLE_TASK="assembleRelease"
    APK="app/build/outputs/apk/release/app-release.apk"
else
    GRADLE_TASK="assembleDebug"
    APK="app/build/outputs/apk/debug/app-debug.apk"
fi

if [[ "$DO_BUILD" -eq 1 ]]; then
    echo ">> Building ($GRADLE_TASK)..."
    ./gradlew "$GRADLE_TASK"
fi
[[ -f "$APK" ]] || { echo "error: APK not found at $APK (did the build run?)" >&2; exit 1; }

# --- install + launch -------------------------------------------------------
echo ">> Installing $APK on $SERIAL..."
"$ADB" -s "$SERIAL" install -r "$APK"

echo ">> Launching $APP_ID/$ACTIVITY..."
"$ADB" -s "$SERIAL" shell am start -n "$APP_ID/$ACTIVITY"

echo ">> Done."
