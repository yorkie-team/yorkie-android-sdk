#!/usr/bin/env bash
# Verify lanes for yorkie-android-sdk.
#
# Usage: bash scripts/verify.sh [fast|full|entropy|session]
#
# fast    — ktlint + architecture lint (no gradle task graph build)
# full    — fast + android lint + unit tests + coverage
# entropy — unused dependencies, stale docs, untested modules
# session — full + harness_check

set -u

LANE="${1:-fast}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAILED=0
run() {
    # run "label" "command..."
    local label="$1"; shift
    echo
    echo "==> $label"
    echo "    \$ $*"
    if "$@"; then
        echo "    [ok] $label"
    else
        local rc=$?
        echo "    [fail rc=$rc] $label"
        FAILED=$((FAILED + 1))
    fi
}

run_lane_fast() {
    run "ktlint (kotlinter)" ./gradlew lintKotlin --no-daemon --console=plain
    run "architecture lint" bash scripts/lint_architecture.sh
}

run_lane_full() {
    run_lane_fast
    run "android lint"       ./gradlew lint --no-daemon --console=plain
    run "unit tests"         ./gradlew yorkie:testDebugUnitTest --no-daemon --console=plain
    run "jacoco report"      ./gradlew yorkie:jacocoDebugTestReport --no-daemon --console=plain
}

run_lane_entropy() {
    # Gradle has no bundled dead-code detector. These checks are best-effort.
    echo
    echo "==> entropy checks (best-effort)"

    # Stale docs (>30 days) warning
    local threshold_days="${YORKIE_DOC_STALE_DAYS:-30}"
    local stale
    stale=$(find docs -type f -name '*.md' -mtime +"$threshold_days" 2>/dev/null)
    if [[ -n "$stale" ]]; then
        echo "    [warn] docs older than $threshold_days days:"
        echo "$stale" | sed 's/^/      /'
    else
        echo "    [ok] no stale docs (> $threshold_days days)"
    fi

    # Orphan TODO/FIXME scan under yorkie/src/main
    if command -v rg >/dev/null 2>&1; then
        local todos
        todos=$(rg -n --no-heading --type kotlin -g '!**/generated/**' 'TODO|FIXME' yorkie/src/main 2>/dev/null | wc -l | tr -d ' ')
    else
        local todos
        todos=$(grep -rn --include='*.kt' --exclude-dir='generated' -E 'TODO|FIXME' yorkie/src/main 2>/dev/null | wc -l | tr -d ' ')
    fi
    echo "    [info] $todos TODO/FIXME markers in yorkie/src/main (target: 0)"
}

run_lane_session() {
    run_lane_full
    if [[ -x scripts/harness_check.sh ]]; then
        run "harness self-check" bash scripts/harness_check.sh
    else
        echo "    [skip] scripts/harness_check.sh not found or not executable"
    fi
}

case "$LANE" in
    fast)    run_lane_fast ;;
    full)    run_lane_full ;;
    entropy) run_lane_entropy ;;
    session) run_lane_session ;;
    *)
        echo "Unknown lane: $LANE"
        echo "Valid: fast | full | entropy | session"
        exit 2
        ;;
esac

echo
if (( FAILED > 0 )); then
    echo "[verify] FAILED — $FAILED check(s) failed (lane=$LANE)"
    exit 1
fi
echo "[verify] PASSED (lane=$LANE)"
exit 0
