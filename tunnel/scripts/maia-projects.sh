#!/usr/bin/env bash
# Write ~/.config/maia/projects.json, the project registry the phone reads.
#
# The rule, from docs/M8-agents-prd.md section 8: a project is a directory
# directly under /home/user/projects that contains .git. ./active
# holds 33 entries and only 15 of them qualify; the rest are loose .bin, .log,
# .onnx and .zip files, and numbering those would put "send it to twenty-two"
# on a model checkpoint.
#
# The registry is a numbering, not a listing, and that is the whole reason this
# file is generated once and then merged rather than rebuilt. PRD principle B:
# the number is the name. A recogniser mangles "streamzFinal" and
# "agentharnessfork" reliably, so the spoken primary key is a small integer,
# and a small integer is only usable if it means the same thing next week.
# Therefore:
#
#   - A number is allocated once, to a name, and is never reused.
#   - A project that disappears from ./active is marked retired. Its number
#     stays pinned to its name, so "seven" cannot quietly become a different
#     repository after an archive.
#   - A retired project that comes back gets its old number back, not a new one.
#   - A new project gets the next free number, and new projects within one run
#     are numbered in case-folded name order so that two runs on two machines
#     over the same directory agree.
#
# Identity is the directory name. A renamed directory is therefore a retirement
# plus a new number, which is the conservative answer: the alternative is
# guessing that two names are the same project, and guessing is exactly what
# principle B forbids.
#
# The write is atomic (temp file in the same directory, then rename) because
# the phone fetches this file over a tunnel through GET /file/content while the
# box may be rewriting it, and half a JSON document is worse than a stale one.
#
# Output is user config, not repo content: it lives in ~/.config/maia/ and is
# never committed. This script is the committed part.
#
# Usage:
#   tunnel/scripts/maia-projects.sh              # write the registry
#   tunnel/scripts/maia-projects.sh --dry-run    # print it, write nothing
#   MAIA_PROJECTS_ROOT=... MAIA_PROJECTS_FILE=... tunnel/scripts/maia-projects.sh
set -euo pipefail

ROOT="${MAIA_PROJECTS_ROOT:-$HOME/Documents/dev/active}"
OUT="${MAIA_PROJECTS_FILE:-$HOME/.config/maia/projects.json}"
SCHEMA_VERSION=1

DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        -h|--help) sed -n '2,/^set -euo/p' "$0" | sed 's/^# \{0,1\}//;$d'; exit 0 ;;
        *) printf 'maia-projects: unknown argument: %s\n' "$arg" >&2; exit 2 ;;
    esac
done

command -v jq >/dev/null || { printf 'maia-projects: jq is required\n' >&2; exit 1; }
[[ -d "$ROOT" ]] || { printf 'maia-projects: no such root: %s\n' "$ROOT" >&2; exit 1; }

# Case-folded, locale-independent ordering. LC_ALL=C alone puts MemoryClip
# before ai-data-extraction, which is not the order section 8 proposes and not
# an order anyone would say out loud; -f folds case, and LC_ALL=C keeps the
# result the same on a box with a different locale.
present=$(
    for d in "$ROOT"/*/; do
        [[ -e "${d}.git" ]] || continue
        basename "$d"
    done | LC_ALL=C sort -f | jq -R . | jq -s .
)

# An absent registry is the first run, not an error. A registry that is present
# but unreadable as JSON is an error, loudly: silently starting a fresh
# numbering would renumber every project on the phone.
if [[ -e "$OUT" ]]; then
    jq -e . "$OUT" >/dev/null 2>&1 || {
        printf 'maia-projects: %s exists and is not valid JSON. Refusing to renumber.\n' "$OUT" >&2
        exit 1
    }
    previous=$(cat "$OUT")
else
    previous='{"projects":[]}'
fi

now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
today=${now%%T*}

registry=$(
    jq -n \
        --argjson previous "$previous" \
        --argjson present "$present" \
        --arg root "$ROOT" \
        --arg now "$now" \
        --arg today "$today" \
        --argjson version "$SCHEMA_VERSION" '
    ($previous.projects // []) as $old
    | ($old | map({key: .name, value: .}) | from_entries) as $byName
    | ($present | map({key: ., value: true}) | from_entries) as $here

    # Everything already numbered, updated in place. A number is never touched.
    | ($old | map(
        . as $p
        | if $here[$p.name] then
            $p + {state: "active", lastSeen: $today} | del(.retiredAt)
          elif $p.state == "retired" then
            $p
          else
            $p + {state: "retired", retiredAt: $today}
          end
      )) as $carried

    # Names we have never numbered, in the order computed above.
    | ($present | map(select($byName[.] == null))) as $new
    | (($old | map(.number) | max) // 0) as $high
    | ($new | to_entries | map({
        number: ($high + 1 + .key),
        name: .value,
        path: ($root + "/" + .value),
        state: "active",
        firstSeen: $today,
        lastSeen: $today
      })) as $added

    | ($carried + $added | sort_by(.number)) as $projects
    | {
        schema: "maia/projects",
        version: $version,
        generatedAt: $now,
        root: $root,
        nextNumber: ((($projects | map(.number) | max) // 0) + 1),
        projects: $projects
      }
    '
)

if (( DRY_RUN )); then
    printf '%s\n' "$registry"
    exit 0
fi

mkdir -p "$(dirname "$OUT")"
# Same directory, so the rename is on one filesystem and therefore atomic. A
# reader either sees the whole old file or the whole new one, never a prefix.
tmp=$(mktemp "$OUT.XXXXXX")
trap 'rm -f "$tmp"' EXIT
printf '%s\n' "$registry" > "$tmp"
chmod 644 "$tmp"
mv -f "$tmp" "$OUT"
trap - EXIT

active=$(jq '[.projects[] | select(.state == "active")] | length' "$OUT")
retired=$(jq '[.projects[] | select(.state == "retired")] | length' "$OUT")
printf 'maia-projects: %s: %s active, %s retired, next number %s\n' \
    "$OUT" "$active" "$retired" "$(jq .nextNumber "$OUT")"
