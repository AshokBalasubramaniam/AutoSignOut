#!/usr/bin/env bash
# Installs the signed release APK on a phone connected by USB.
#
# Installing over USB (ADB) is not subject to Play Protect's "App blocked to protect your device"
# check, which blocks sideloaded apps that use an Accessibility Service when they are opened
# from a file manager, browser or chat app.
#
# Phone setup (once): Settings › About phone › Software information › tap "Build number" 7 times,
# then Settings › Developer options › USB debugging ON. Connect the cable and tap "Allow".
set -euo pipefail
cd "$(dirname "$0")"

ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
APK="app/build/outputs/apk/release/app-release.apk"
PKG="com.autoattendance.app"

[ -x "$ADB" ] || { echo "adb not found at $ADB"; exit 1; }

if [ ! -f "$APK" ] || [ "${1:-}" = "--build" ]; then
    echo "Building the signed release APK…"
    ./gradlew --console=plain -q assembleRelease
fi

state=$("$ADB" get-state 2>/dev/null || true)
if [ "$state" != "device" ]; then
    "$ADB" devices
    echo
    echo "No phone ready. Turn on USB debugging (see the top of this script), reconnect the cable,"
    echo "and tap \"Allow USB debugging\" on the phone. Then run this script again."
    exit 1
fi

echo "Installing on $("$ADB" shell getprop ro.product.model | tr -d '\r')…"
if out=$("$ADB" install -r "$APK" 2>&1); then
    echo "$out"
    echo "Installed. Open Auto Attendance and complete Setup (accessibility, notifications, battery)."
    exit 0
fi

echo "$out"
if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$out"; then
    echo
    echo "An older Auto Attendance signed with a different key is installed."
    read -r -p "Uninstall it (its settings and logs will be deleted) and install the new one? [y/N] " ans
    if [ "${ans,,}" = "y" ]; then
        "$ADB" uninstall "$PKG"
        "$ADB" install "$APK"
        echo "Installed. Open Auto Attendance and complete Setup again."
        exit 0
    fi
fi
exit 1
