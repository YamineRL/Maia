#!/usr/bin/env bash
# Fetches the Tensor NPU dispatch library into app/src/main/assets/litert.
#
# Gradle does not do this. libLiteRtDispatch_GoogleTensor.so is the half of
# LiteRT that talks to the Pixel's NPU, and it is not inside the litertlm
# Maven AAR: upstream ships it in the LiteRT release zip as a dynamic
# feature module. We take just the .so, because a dynamic feature module is
# Play delivery machinery this app does not have.
#
# The version must track the litertlm-android pin in app/build.gradle.kts:
# the dispatch library and the runtime it plugs into are version-matched.
# litertlm-android 0.16.1 pins LiteRT commit 0ff28117 (2026-08-04), which
# sits just inside v2.2.0.
set -euo pipefail

LITERT_VERSION="${1:-v2.2.0}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$HERE/app/src/main/assets/litert"
ZIP_URL="https://github.com/google-ai-edge/LiteRT/releases/download/${LITERT_VERSION}/litert_npu_runtime_libraries.zip"
INNER="google_tensor_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"

mkdir -p "$DEST"
if [ -f "$DEST/libLiteRtDispatch_GoogleTensor.so" ]; then
    echo "already present: $DEST/libLiteRtDispatch_GoogleTensor.so"
    echo "delete it to re-fetch"
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "fetching litert_npu_runtime_libraries.zip @ $LITERT_VERSION"
curl -fsSL --retry 3 -o "$TMP/npu.zip" "$ZIP_URL"
unzip -q -o "$TMP/npu.zip" "$INNER" -d "$TMP"
mv "$TMP/$INNER" "$DEST/libLiteRtDispatch_GoogleTensor.so"
echo "wrote $DEST/libLiteRtDispatch_GoogleTensor.so"

# The same 16 KB page-size rule fetch-sherpa.sh enforces, for the same
# reason: a 4 KB aligned library fails to load on current Pixels.
READELF="$(find "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk" -name llvm-readelf 2>/dev/null | head -1)"
if [ -n "$READELF" ]; then
    align="$("$READELF" -l "$DEST/libLiteRtDispatch_GoogleTensor.so" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')"
    printf 'alignment: %s\n' "$align"
    case "$align" in
        *0x1000*) echo "FAIL: 4 KB aligned, will not load on a 16 KB page device" >&2; exit 1 ;;
    esac
else
    echo "no llvm-readelf found, skipping the alignment check" >&2
fi
