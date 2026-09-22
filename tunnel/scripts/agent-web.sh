#!/usr/bin/env bash
# Serve OpenCode on 127.0.0.1:4096, for the phone to reach through the tailcat
# forward named "oc-fusion".
#
# One server, every project. OpenCode resolves the project per request, from
# ?directory= or the x-opencode-directory header, falling back to this process's
# cwd. So do not run this from a project: run it anywhere neutral and let the
# caller name the directory. A real project as cwd means a request that forgets
# the parameter silently lands in it.
#
# This script exists for one reason: OpenCode's basic auth is OFF entirely when
# OPENCODE_SERVER_PASSWORD is unset, and on Android any app on the phone can
# open a loopback port. A passwordless server on that forward is a shell on this
# machine for every app on the phone. So: no password, no server.
set -euo pipefail

# Prefer a mode-600 file over an exported variable, so the secret is not in a
# shell history, a process environment that other local users can read, or a
# unit file. Same path works as a systemd EnvironmentFile=.
CRED="${OPENCODE_AGENT_ENV:-$HOME/.config/opencode/agent-web.env}"
if [[ -z "${OPENCODE_SERVER_PASSWORD:-}" && -r "$CRED" ]]; then
    perms=$(stat -c '%a' "$CRED")
    if [[ "$perms" != "600" && "$perms" != "400" ]]; then
        printf 'agent-web: %s is mode %s; needs 600. Run: chmod 600 %s\n' \
            "$CRED" "$perms" "$CRED" >&2
        exit 1
    fi
    set -a
    # shellcheck disable=SC1090
    . "$CRED"
    set +a
fi

if [[ -z "${OPENCODE_SERVER_PASSWORD:-}" ]]; then
    cat >&2 <<'MSG'
agent-web: refusing to start without a password.

OpenCode serves no auth at all when OPENCODE_SERVER_PASSWORD is unset, and this
port is reachable by every app on the paired phone.

    mkdir -p ~/.config/opencode
    printf 'OPENCODE_SERVER_PASSWORD=%s\n' "$(openssl rand -base64 24)" \
        > ~/.config/opencode/agent-web.env
    chmod 600 ~/.config/opencode/agent-web.env
MSG
    exit 1
fi

# The username is only the other half of the basic auth pair, not a secret. The
# default matches the one compiled into OpenCode, so the phone can assume it.
export OPENCODE_SERVER_USERNAME="${OPENCODE_SERVER_USERNAME:-opencode}"

# Run unattended. The operator is not at the phone to approve tool calls one by
# one, so every tool is pre-approved. OpenCode's own default is already
# {"*":"allow"} with a few holes punched in it; this closes the holes that would
# otherwise stop an agent mid-task with nobody there to answer.
#
# Understand what this costs. With permissions off, the HTTP basic auth below is
# the ONLY thing standing between a request to 127.0.0.1:4096 and arbitrary code
# execution on this machine, and on Android loopback is device-wide, so "a
# request" includes one from any other app on the phone. The password is not a
# formality here, it is the whole lock.
#
# Two holes are left open on purpose, because they stop an agent rather than
# nagging one:
#   doom_loop         a runaway loop halts instead of burning the box unattended
#   external_directory an agent that wanders outside the project halts
# Both surface as permission.asked, which is exactly the event worth a phone
# notification. Set OPENCODE_PERMISSION yourself before calling this to change
# that.
#
# OpenCode merges this JSON over the harness's own permission block and skips it
# with only a log line if it fails to parse, so confirm with GET /config once.
export OPENCODE_PERMISSION="${OPENCODE_PERMISSION:-$(cat <<'JSON'
{"*":"allow","bash":"allow","edit":"allow","read":"allow","glob":"allow",
 "grep":"allow","list":"allow","task":"allow","todowrite":"allow",
 "webfetch":"allow","websearch":"allow","skill":"allow","lsp":"allow"}
JSON
)}"

# Fusion is NOT installed globally: ~/.config/opencode/opencode.jsonc declares
# no plugin and no agent. The harness travels in OPENCODE_CONFIG, which the
# `oc` launcher exports. Running plain `opencode` here starts a server with no
# Fusion at all, which is the opposite of the point.
OC="${FUSION_OC:-/home/user/projects/agentharnessfork/bin/oc}"
if [[ ! -x "$OC" ]]; then
    printf 'agent-web: no Fusion launcher at %s. Set FUSION_OC to it.\n' "$OC" >&2
    exit 1
fi

# Pin which opencode the launcher gets, because leaving it to PATH silently
# served a stale build for three and a half days.
#
# bin/oc ends in `exec opencode "$@"`, resolved from PATH. Under systemd that
# PATH is the user manager's, which puts /home/linuxbrew/.linuxbrew/bin ahead of
# ~/.local/bin. An interactive shell resolves the other way. So the unit ran
# whatever Homebrew had while every terminal on the box ran something newer, and
# nothing anywhere said so: the only symptom was a server-side defect that had
# already been fixed in the build the operator thought was running.
#
# Resolution order, first executable wins:
#   OPENCODE_BIN        an explicit override, same spirit as FUSION_OC above
#   ~/.local/bin        where the npm install puts it, and what a shell picks
#   PATH                whatever is left, so a box without the above still runs
#
# Its directory goes on the front of PATH so bin/oc's bare `exec opencode` finds
# this one and not the first match in the inherited PATH. The harness's own bin
# is prepended after this by bin/oc and holds no opencode, so it does not
# shadow the choice.
if [[ -n "${OPENCODE_BIN:-}" ]]; then
    if [[ ! -x "$OPENCODE_BIN" ]]; then
        printf 'agent-web: OPENCODE_BIN=%s is not executable.\n' "$OPENCODE_BIN" >&2
        exit 1
    fi
else
    for candidate in "$HOME/.local/bin/opencode" "$(command -v opencode || true)"; do
        if [[ -n "$candidate" && -x "$candidate" ]]; then
            OPENCODE_BIN="$candidate"
            break
        fi
    done
fi
if [[ -z "${OPENCODE_BIN:-}" ]]; then
    printf 'agent-web: no opencode on PATH and no OPENCODE_BIN set.\n' >&2
    exit 1
fi
export PATH="$(dirname "$OPENCODE_BIN"):$PATH"

# Say the version out loud at startup. Correct is not enough: the whole failure
# above was a version being wrong with nothing in the log to notice it by. This
# line is what `journalctl --user -u agent-web` should be checked against before
# anyone blames the server for a defect.
printf 'agent-web: opencode %s from %s (-> %s)\n' \
    "$("$OPENCODE_BIN" --version 2>/dev/null | head -1)" \
    "$OPENCODE_BIN" "$(readlink -f "$OPENCODE_BIN")" >&2
printf 'agent-web: fusion launcher %s\n' "$OC" >&2

# --hostname 127.0.0.1 is OpenCode's default and is restated on purpose: tailcat
# proxies to the same port on localhost, so the server never needs to listen on
# a real interface, and must not.
exec "$OC" web --hostname 127.0.0.1 --port 4096
