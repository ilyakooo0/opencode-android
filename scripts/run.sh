#!/usr/bin/env bash
#
# Build the debug APK, make sure an emulator is running, then install and launch
# the app on it.
#
# Usage:
#   scripts/run.sh                 # build + install + launch on an emulator
#   scripts/run.sh -a Pixel_7      # boot a specific AVD if none is running
#   scripts/run.sh --no-build      # skip the Gradle build, just install + launch
#   scripts/run.sh --release       # build/install the release variant
#
set -euo pipefail

APP_ID="soy.iko.opencode"
ACTIVITY=".MainActivity"
VARIANT="debug"
DO_BUILD=1
AVD=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -a|--avd)     AVD="$2"; shift 2 ;;
        --no-build)   DO_BUILD=0; shift ;;
        --release)    VARIANT="release"; shift ;;
        -h|--help)
            awk 'NR>1 && /^#/ {sub(/^# ?/,""); print; next} NR>1 {exit}' "$0"
            exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

cd "$(dirname "$0")/.."

# --- locate the Android SDK tools ------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
find_tool() {
    local name="$1"
    if command -v "$name" >/dev/null 2>&1; then command -v "$name"; return; fi
    for d in "$SDK/platform-tools" "$SDK/emulator" "$SDK/cmdline-tools/latest/bin"; do
        [[ -x "$d/$name" ]] && { echo "$d/$name"; return; }
    done
    echo ""
}
ADB="$(find_tool adb)"
EMULATOR="$(find_tool emulator)"
[[ -z "$ADB" ]] && { echo "error: 'adb' not found; set ANDROID_HOME / ANDROID_SDK_ROOT" >&2; exit 1; }

# --- ensure an emulator/device is connected --------------------------------
device_ready() {
    "$ADB" devices | awk 'NR>1 && $2=="device" {found=1} END {exit found?0:1}'
}

if ! device_ready; then
    echo ">> No running device/emulator; booting one..."
    [[ -z "$EMULATOR" ]] && { echo "error: 'emulator' not found; start a device manually" >&2; exit 1; }

    if [[ -z "$AVD" ]]; then
        AVD="$("$EMULATOR" -list-avds | head -n1)"
        [[ -z "$AVD" ]] && { echo "error: no AVDs found; create one in Android Studio or pass -a <name>" >&2; exit 1; }
    fi
    echo ">> Starting AVD: $AVD"
    "$EMULATOR" -avd "$AVD" -netdelay none -netspeed full >/dev/null 2>&1 &

    echo ">> Waiting for device..."
    "$ADB" wait-for-device
    # Wait until the OS has finished booting.
    until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
        sleep 2
    done
    echo ">> Emulator booted."
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
echo ">> Installing $APK..."
"$ADB" install -r "$APK"

echo ">> Launching $APP_ID/$ACTIVITY..."
"$ADB" shell am start -n "$APP_ID/$ACTIVITY"

echo ">> Done."
