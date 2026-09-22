#!/usr/bin/env bash
# Builds maiatunnel into an Android .aar and drops it where the Gradle app
# expects it. Run this whenever the Go side changes; Gradle will not do it.
set -euo pipefail

cd "$(dirname "$0")/.."

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.3.13750724}"
export PATH="$HOME/go/bin:$PATH"

ABI="${1:-android/arm64}"
OUT=../android/app/libs/maiatunnel.aar

mkdir -p "$(dirname "$OUT")"

# Two flags, both load bearing.
#
# -s -w halves the result: 15 MB unstripped against 6.9 MB stripped, and
# nothing reads those symbols at runtime.
#
# max-page-size=16384 is not optional on current hardware. Pixel devices run a
# 16 KB kernel page size, and Android refuses to load a shared object whose LOAD
# segments are aligned to the old 4 KB boundary. Without it the first launch
# raises a compatibility warning and future releases will fail outright.
gomobile bind \
  -target="$ABI" \
  -androidapi 26 \
  -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o "$OUT" \
  ./maiatunnel

ls -lh "$OUT"
