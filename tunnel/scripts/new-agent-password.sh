#!/usr/bin/env bash
# Write a fresh passphrase into the file agent-web.sh reads.
#
# Words rather than base64, because this one gets typed on a phone keyboard,
# into a browser basic-auth box, with no password manager in the loop. A string
# you retype wrong three times is a string you end up shortening.
#
# 833 words, so 9.7 bits each: six words is 58 bits. That is the right budget
# here. This password is not internet-facing (tailcat's --allow gets there
# first), so it is not being brute-forced; it exists to stop other apps on the
# phone, which get one guess each and no rate limit to speak of.
#
# The server reads the file once at startup, so restart agent-web.sh after
# running this or the old password stays live.
set -euo pipefail

CRED="${OPENCODE_AGENT_ENV:-$HOME/.config/opencode/agent-web.env}"
WORDS="$(dirname "$(readlink -f "$0")")/words.txt"
COUNT="${1:-6}"

[[ -r "$WORDS" ]] || { printf 'no wordlist at %s\n' "$WORDS" >&2; exit 1; }

mkdir -p "$(dirname "$CRED")"
python3 - "$WORDS" "$COUNT" "$CRED" <<'PY'
import re, secrets, sys, os
words = sorted(set(re.findall(r'[a-z]+', open(sys.argv[1]).read())))
if len(words) < 500:
    sys.exit('wordlist too small to be worth using: %d words' % len(words))
pw = '-'.join(secrets.choice(words) for _ in range(int(sys.argv[2])))
with open(sys.argv[3], 'w') as f:
    f.write('OPENCODE_SERVER_PASSWORD=%s\n' % pw)
os.chmod(sys.argv[3], 0o600)
import math
print('wrote %d words, %.0f bits, to %s' % (
    int(sys.argv[2]), int(sys.argv[2]) * math.log2(len(words)), sys.argv[3]))
PY

printf 'Not printed. To read it:\n    grep -o "=.*" %s | cut -c2-\n' "$CRED"
