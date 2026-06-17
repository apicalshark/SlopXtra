#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="linextra-builder"

echo "==> Building Docker image..."
docker build -t "$IMAGE_NAME" "$SCRIPT_DIR"

echo ""
echo "==> Building LineXtra..."

# Build debug by default; use "release" as first arg for release build
BUILD_TYPE="${1:-debug}"

if [ "$BUILD_TYPE" = "release" ]; then
    # Release build requires signing config
    if [ -z "${L_STORE_PASSWORD:-}" ] || [ -z "${L_KEY_ALIAS:-}" ] || [ -z "${L_KEY_PASSWORD:-}" ]; then
        echo "ERROR: Release build requires these env vars: L_STORE_PASSWORD, L_KEY_ALIAS, L_KEY_PASSWORD"
        echo "  Usage: L_STORE_PASSWORD=... L_KEY_ALIAS=... L_KEY_PASSWORD=... $0 release"
        exit 1
    fi
    docker run --rm \
        -v "$SCRIPT_DIR:/project" \
        -e L_STORE_PASSWORD \
        -e L_KEY_ALIAS \
        -e L_KEY_PASSWORD \
        "$IMAGE_NAME" \
        ./gradlew assembleRelease --no-daemon
else
    docker run --rm \
        -v "$SCRIPT_DIR:/project" \
        "$IMAGE_NAME" \
        ./gradlew assembleDebug --no-daemon
fi

echo ""
echo "==> Done! APK location:"
if [ "$BUILD_TYPE" = "release" ]; then
    ls -la "$SCRIPT_DIR/app/build/outputs/apk/release/"*.apk 2>/dev/null || echo "  (release APK not found)"
else
    ls -la "$SCRIPT_DIR/app/build/outputs/apk/debug/"*.apk 2>/dev/null || echo "  (debug APK not found)"
fi
