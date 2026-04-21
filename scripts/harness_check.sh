#!/usr/bin/env bash
# Harness self-check — end-of-session verification (EL-11).
#
# Checks:
#   1. No uncommitted working-tree changes (informational)
#   2. .work/state.json is valid JSON and has required keys
#   3. docs/progress.md was updated today (warn if not)
#   4. docs/lessons.md was updated in the last 7 days (warn if not)
#   5. Active exec-plans mentioned (informational)
#   6. Commits since last session have exec-plan or round-report evidence
#   7. `./gradlew yorkie:testDebugUnitTest` passes (optional: set YORKIE_SKIP_TESTS=1)

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

WARN=0
FAIL=0
say()  { echo "[harness-check] $*"; }
fail() { say "FAIL: $*"; FAIL=$((FAIL + 1)); }
warn() { say "warn: $*"; WARN=$((WARN + 1)); }
ok()   { say "ok:   $*"; }

today=$(date +%F)

# 1. Working tree state (informational)
if git rev-parse --git-dir >/dev/null 2>&1; then
    dirty=$(git status --porcelain | wc -l | tr -d ' ')
    if [[ "$dirty" != "0" ]]; then
        say "info: $dirty uncommitted change(s) in working tree"
    else
        ok "working tree clean"
    fi
fi

# 2. state.json
STATE=".work/state.json"
if [[ -f $STATE ]]; then
    if command -v jq >/dev/null 2>&1; then
        if jq -e '.phase and (has("active_ticket")) and (has("session_count"))' "$STATE" >/dev/null 2>&1; then
            ok "$STATE valid"
        else
            fail "$STATE missing required keys (phase, active_ticket, session_count)"
        fi
        phase=$(jq -r '.phase // "?"' "$STATE")
        active=$(jq -r '.active_ticket // "null"' "$STATE")
        say "info: state.phase=$phase  active_ticket=$active"
    else
        ok "$STATE exists (jq not installed — skipping shape check)"
    fi
else
    fail "$STATE missing"
fi

# 3. progress.md freshness
if [[ -f docs/progress.md ]]; then
    mtime=$(date -r docs/progress.md +%F 2>/dev/null || echo "?")
    if [[ "$mtime" == "$today" ]]; then
        ok "docs/progress.md updated today"
    else
        warn "docs/progress.md last updated $mtime (today=$today)"
    fi
else
    fail "docs/progress.md missing"
fi

# 4. lessons.md recency (7 days)
if [[ -f docs/lessons.md ]]; then
    age_days=$(( ( $(date +%s) - $(stat -f %m docs/lessons.md 2>/dev/null || stat -c %Y docs/lessons.md) ) / 86400 ))
    if (( age_days <= 7 )); then
        ok "docs/lessons.md updated within 7 days (age=${age_days}d)"
    else
        warn "docs/lessons.md stale (age=${age_days}d) — capture a lesson if this session surfaced one"
    fi
else
    fail "docs/lessons.md missing"
fi

# 5. active exec-plans
active_count=0
if [[ -d docs/exec-plans/active ]]; then
    active_count=$(find docs/exec-plans/active -type f -name '*.md' | wc -l | tr -d ' ')
fi
say "info: $active_count active exec-plan(s)"

# 6. Tracking enforcement
if git rev-parse --git-dir >/dev/null 2>&1; then
    since_ref="HEAD@{1.day.ago}"
    commits_today=$(git log --oneline --since=yesterday 2>/dev/null | wc -l | tr -d ' ')
    completed_count=0
    rounds_count=0
    [[ -d docs/exec-plans/completed ]] && completed_count=$(find docs/exec-plans/completed -type f -name '*.md' | wc -l | tr -d ' ')
    [[ -d docs/round-reports ]] && rounds_count=$(find docs/round-reports -type f -name '*.md' | wc -l | tr -d ' ')

    if (( commits_today > 0 )) && (( completed_count == 0 )) && (( rounds_count == 0 )) && (( active_count == 0 )); then
        warn "$commits_today commit(s) since yesterday but no exec-plans or round reports — create tickets for traceability"
    else
        ok "tracking: $commits_today commit(s), $completed_count completed, $rounds_count round-reports, $active_count active"
    fi
fi

# 7. Unit tests (optional)
if [[ "${YORKIE_SKIP_TESTS:-0}" == "1" ]]; then
    say "info: YORKIE_SKIP_TESTS=1 — skipping unit tests"
else
    echo
    say "running: ./gradlew yorkie:testDebugUnitTest --no-daemon --console=plain"
    if ./gradlew yorkie:testDebugUnitTest --no-daemon --console=plain >/tmp/yorkie-harness-check.log 2>&1; then
        ok "unit tests passed"
    else
        fail "unit tests failed — see /tmp/yorkie-harness-check.log"
    fi
fi

echo
if (( FAIL > 0 )); then
    say "FAILED — $FAIL fail, $WARN warn"
    exit 1
fi
say "PASSED — $WARN warning(s)"
exit 0
