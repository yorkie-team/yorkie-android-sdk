#!/usr/bin/env bash
# init_check — fast smoke test for the yorkie-android-sdk dev environment.
#
# Confirms:
#   - gradlew is executable
#   - JDK 17 available
#   - Docker available (for integration tests)
#   - local.properties has YORKIE_SERVER_URL (warn only)
#   - expected harness dirs exist

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAIL=0
WARN=0
say() { echo "[init-check] $*"; }
fail() { say "FAIL: $*"; FAIL=$((FAIL + 1)); }
warn() { say "warn: $*"; WARN=$((WARN + 1)); }
ok()   { say "ok:   $*"; }

# --- gradlew ---
if [[ -x ./gradlew ]]; then
    ok "./gradlew is executable"
else
    fail "./gradlew missing or not executable"
fi

# --- JDK ---
if command -v java >/dev/null 2>&1; then
    jv=$(java -version 2>&1 | head -n 1)
    ok "java: $jv"
    # require JDK >= 17
    major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n1)
    if [[ -n "$major" && "$major" -lt 17 ]]; then
        fail "JDK 17+ required (found $major)"
    fi
else
    fail "java not on PATH — install JDK 17+"
fi

# --- Docker ---
if command -v docker >/dev/null 2>&1; then
    ok "docker on PATH"
    if docker info >/dev/null 2>&1; then
        ok "docker daemon reachable"
    else
        warn "docker daemon not reachable — integration tests will fail until started"
    fi
else
    warn "docker not on PATH — integration tests will fail"
fi

# --- local.properties ---
if [[ -f local.properties ]]; then
    if grep -q '^YORKIE_SERVER_URL=' local.properties; then
        ok "local.properties has YORKIE_SERVER_URL"
    else
        warn "local.properties is missing YORKIE_SERVER_URL — run scripts/config-yorkie-local-server.sh before instrumented tests"
    fi
else
    warn "local.properties not found — create one (see CLAUDE.md for notes)"
fi

# --- harness layout ---
for d in docs docs/specs docs/exec-plans/active docs/exec-plans/completed docs/round-reports .work; do
    if [[ -d "$d" ]]; then
        ok "dir: $d"
    else
        fail "missing dir: $d"
    fi
done

for f in harness.config.json CLAUDE.local.md docs/GOLDEN_PRINCIPLES.md docs/ARCHITECTURE.md; do
    if [[ -f "$f" ]]; then
        ok "file: $f"
    else
        fail "missing file: $f"
    fi
done

# --- .work/state.json shape ---
if [[ -f .work/state.json ]]; then
    if command -v jq >/dev/null 2>&1; then
        if jq -e 'has("phase")' .work/state.json >/dev/null 2>&1; then
            ok ".work/state.json has 'phase'"
        else
            fail ".work/state.json missing 'phase' key"
        fi
    else
        ok ".work/state.json exists (jq not installed — skipping shape check)"
    fi
else
    fail ".work/state.json missing"
fi

echo
if (( FAIL > 0 )); then
    say "FAILED — $FAIL fail, $WARN warn"
    exit 1
fi
say "PASSED — $WARN warning(s)"
exit 0
