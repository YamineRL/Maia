#!/usr/bin/env bash
# Fetches the sherpa-onnx Android AAR into core-audio/libs.
#
# Gradle does not do this. sherpa-onnx publishes no Android artifact to Maven
# Central, so the AAR comes from the upstream GitHub release and is deliberately
# not committed: it is 50 MB of four ABIs, of which we package one.
set -euo pipefail

VERSION="${1:-1.13.8}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$HERE/core-audio/libs"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar"

mkdir -p "$DEST"
if [ -f "$DEST/sherpa-onnx.aar" ]; then
    echo "already present: $DEST/sherpa-onnx.aar"
    echo "delete it to re-fetch"
    exit 0
fi

echo "fetching sherpa-onnx $VERSION"
curl -fsSL --retry 3 -o "$DEST/sherpa-onnx.aar.part" "$URL"
mv "$DEST/sherpa-onnx.aar.part" "$DEST/sherpa-onnx.aar"
echo "wrote $DEST/sherpa-onnx.aar"

# The reason this project can use a prebuilt AAR at all. Pixels with a 16 KB
# kernel page size refuse to load a 4 KB aligned library, and the failure is a
# crash at first JNI call rather than anything the build reports.
READELF="$(find "${ANDROID_HOME:-$HOME/Android/Sdk}/ndk" -name llvm-readelf 2>/dev/null | head -1)"
if [ -n "$READELF" ]; then
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    unzip -q -o "$DEST/sherpa-onnx.aar" 'jni/arm64-v8a/*' -d "$TMP"
    echo "checking 16 KB alignment:"
    for so in "$TMP"/jni/arm64-v8a/*.so; do
        align="$("$READELF" -l "$so" | awk '/LOAD/ {print $NF}' | sort -u | tr '\n' ' ')"
        printf '  %-32s %s\n' "$(basename "$so")" "$align"
        case "$align" in
            *0x1000*) echo "  FAIL: 4 KB aligned, will not load on a 16 KB page device" >&2; exit 1 ;;
        esac
    done
    echo "all arm64-v8a segments are 0x4000 aligned"
else
    echo "no llvm-readelf found, skipping the alignment check" >&2
fi
