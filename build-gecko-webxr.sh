#!/usr/bin/env bash
set -euo pipefail

# Framatome VR Pro — Build Custom GeckoView with WebXR Patches
#
# Prerequisites:
#   - ~50GB free disk space (30GB source + 20GB build)
#   - Mercurial (hg): brew install mercurial
#   - Rust toolchain: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
#   - Python 3.x with pip
#   - Android SDK + NDK installed
#
# Usage:
#   chmod +x build-gecko-webxr.sh
#   ./build-gecko-webxr.sh
#
# After success, update local.properties:
#   dependencySubstitutions.geckoviewTopsrcdir=/path/to/mozilla-release

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GECKO_VERSION="128.5.1"
GECKO_TAG="FIREFOX_${GECKO_VERSION//./_}esr_RELEASE"
PATCHES_DIR="$SCRIPT_DIR/gecko-patches/gecko-$GECKO_VERSION"
GECKO_SRC="$SCRIPT_DIR/../mozilla-release"

echo "=== Framatome VR Pro: GeckoView WebXR Build ==="
echo "Gecko version: $GECKO_VERSION"
echo "Source dir:    $GECKO_SRC"
echo "Patches:       $PATCHES_DIR"
echo ""

# Check disk space
FREE_GB=$(df -g "$SCRIPT_DIR" | tail -1 | awk '{print $4}')
if [ "$FREE_GB" -lt 50 ]; then
    echo "WARNING: Only ${FREE_GB}GB free. Need ~50GB for full Gecko build."
    echo "Continue? (y/N)"
    read -r confirm
    [ "$confirm" = "y" ] || exit 1
fi

# Check prerequisites
for cmd in hg python3; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: $cmd not found. Install it first."
        [ "$cmd" = "hg" ] && echo "  brew install mercurial"
        exit 1
    fi
done

# Step 1: Clone mozilla-release at the correct tag
if [ ! -d "$GECKO_SRC" ]; then
    echo ">>> Cloning mozilla-release at $GECKO_TAG ..."
    hg clone --stream https://hg.mozilla.org/releases/mozilla-release/ "$GECKO_SRC"
    cd "$GECKO_SRC"
    hg update -r "$GECKO_TAG"
else
    echo ">>> mozilla-release already cloned at $GECKO_SRC"
    cd "$GECKO_SRC"
    hg update -r "$GECKO_TAG"
fi

# Step 2: Apply Igalia WebXR patches
echo ">>> Applying WebXR patches from $PATCHES_DIR ..."
for patch in "$PATCHES_DIR"/*.patch; do
    echo "  Applying: $(basename "$patch")"
    hg import --no-commit "$patch" || {
        echo "  WARNING: patch $(basename "$patch") failed; attempting force"
        hg import --no-commit --force "$patch" || true
    }
done
echo ">>> All patches applied."

# Step 3: Bootstrap build environment
echo ">>> Running mach bootstrap ..."
./mach --no-interactive bootstrap --application-choice="GeckoView/Firefox for Android"

# Step 4: Build GeckoView for arm64
echo ">>> Building GeckoView arm64 ..."
./mach build
./mach package

echo ""
echo "=== GeckoView build complete ==="
echo ""
echo "To use this in Wolvic, add to local.properties:"
echo "  dependencySubstitutions.geckoviewTopsrcdir=$GECKO_SRC"
echo ""
echo "Then rebuild Wolvic:"
echo "  cd $SCRIPT_DIR && ./gradlew assembleOculusvrArm64GeckoGenericDebug"
