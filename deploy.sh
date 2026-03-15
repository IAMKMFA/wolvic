#!/usr/bin/env bash
set -euo pipefail

# Framatome VR Pro (Wolvic Fork) — Deploy to Quest 3
#
# Usage:
#   ./deploy.sh [debug|release]
#   Default: debug

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_TYPE="${1:-debug}"

if [ "$BUILD_TYPE" = "release" ]; then
    APK="$SCRIPT_DIR/release/Framatome_VR_Pro_wolvic_release.apk"
else
    APK="$SCRIPT_DIR/release/Framatome_VR_Pro_wolvic_debug.apk"
fi

if [ ! -f "$APK" ]; then
    echo "APK not found: $APK"
    echo "Run the build first:"
    echo "  ./gradlew assembleOculusvrArm64GeckoGeneric$(echo $BUILD_TYPE | sed 's/.*/\u&/')"
    exit 1
fi

echo "=== Framatome VR Pro (Wolvic Fork) Deploy ==="
echo "Build: $BUILD_TYPE"
echo "APK:   $APK"
echo ""

# Check ADB
if ! command -v adb &>/dev/null; then
    export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
fi

echo ">>> Checking connected devices..."
adb devices -l

echo ""
echo ">>> Installing APK..."
adb install -r "$APK"

echo ""
echo ">>> Creating tour directories on device..."
adb shell mkdir -p /sdcard/FramatomeVR/Tours/
adb shell mkdir -p /sdcard/FramatomeVRPro/Tours/

echo ""
echo ">>> Launching Framatome VR Pro..."
adb shell monkey -p com.framatome.vr.pro 1

echo ""
echo "=== Deploy complete ==="
echo ""
echo "Test checklist:"
echo "  1. App launches into VR space with Framatome VR tour list"
echo "  2. No browser chrome (no URL bar, no tabs, no bookmarks)"
echo "  3. Tour Scan button reloads the tour list"
echo "  4. Drop a .zip into /sdcard/FramatomeVR/Tours/ and rescan"
echo "  5. Tap a tour card to load it in the VR browser"
echo "  6. Tap the VR goggles button (in the tour) for WebXR immersion"
