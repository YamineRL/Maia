#!/usr/bin/env bash
# Pushes the on-device conversational model onto a plugged-in phone.
#
# The model is deliberately not downloaded by the app: it is 3.1 GB, it is a
# deliberate install, and litert-community publishes it ungated so no
# credential ever touches the phone for it. This script fetches it once on
# the devbox with `hf`, then writes it into the app's private storage via
# run-as, which requires a debug build installed (ours is).
#
#   scripts/provision-gemma.sh                 # download if needed, then push
#   MAIA_SERIAL=... scripts/provision-gemma.sh # pick a device
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="litert-community/gemma-4-E2B-it-litert-lm"
FILE="gemma-4-E2B-it_Google_Tensor_G5.litertlm"
PKG="dev.maia.app"
ADB="${ADB:-adb}"
[ -x "$ADB" ] || ADB="$HOME/Android/Sdk/platform-tools/adb"

SERIAL_ARG=()
[ -n "${MAIA_SERIAL:-}" ] && SERIAL_ARG=(-s "$MAIA_SERIAL")

# Local copy, via the HF cache so an interrupted download resumes.
# Newer hf prints `path=<file>`, older ones the bare path.
LOCAL="$(hf download "$REPO" "$FILE" 2>/dev/null | tail -1)"
LOCAL="${LOCAL#path=}"
[ -f "$LOCAL" ] || { echo "hf download did not produce $FILE" >&2; exit 1; }
echo "local: $LOCAL"

REMOTE_DIR="files/models-gemma"
"$ADB" "${SERIAL_ARG[@]}" shell "run-as $PKG mkdir -p $REMOTE_DIR"

# .part first, renamed into place: a truncated push is never a half model
# LiteRtConverser would try to open.
echo "pushing 3.1 GB to $PKG:$REMOTE_DIR/$FILE (this takes a while over USB)"
"$ADB" "${SERIAL_ARG[@]}" push "$LOCAL" "/data/local/tmp/$FILE.part"
"$ADB" "${SERIAL_ARG[@]}" shell "run-as $PKG cp /data/local/tmp/$FILE.part $REMOTE_DIR/$FILE.part && run-as $PKG mv $REMOTE_DIR/$FILE.part $REMOTE_DIR/$FILE && rm /data/local/tmp/$FILE.part"
"$ADB" "${SERIAL_ARG[@]}" shell "run-as $PKG ls -l $REMOTE_DIR/$FILE"
echo "provisioned. LiteRtConverser picks it up on the next unreachable ask."
