#!/usr/bin/env bash
# Architecture linter — yorkie-android-sdk
#
# Enforces:
#   R1. document/crdt/** must not import document/json/**
#   R2. util/** must not import from document/**, core/**, api/**
#   R3. Generated proto types may only be referenced from api/*Converter.kt (and api/* generally).
#       Domain code (document/**, core/**, util/**) must not import dev.yorkie.api.v1.*.
#   R4. yorkie/src/main/** must not import dev.yorkie.examples.** or examples.** or microbenchmark.**
#   R5. No println / System.out / System.err in yorkie/src/main/**
#   R6. Max 300 lines per .kt file in yorkie/src/main/kotlin/** (excluding generated/)
#
# Exit 0 on clean, 1 on any violation.

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN="$ROOT/yorkie/src/main/kotlin"
MAX_LINES="${YORKIE_MAX_FILE_LINES:-300}"

VIOLATIONS=0
report() {
    echo "[arch-lint] $1"
    VIOLATIONS=$((VIOLATIONS + 1))
}

ok() { echo "[arch-lint] ok: $1"; }

# --- Preconditions ---
if [[ ! -d "$MAIN" ]]; then
    echo "[arch-lint] ERROR: $MAIN not found. Run from repo root or a worktree."
    exit 1
fi

have_rg=0
command -v rg >/dev/null 2>&1 && have_rg=1

scan() {
    # scan <path_glob> <pattern>  — prints matching "file:line:line-content"
    local path="$1" pattern="$2"
    if [[ $have_rg -eq 1 ]]; then
        rg -n --no-heading --type kotlin "$pattern" "$path" 2>/dev/null || true
    else
        grep -rn --include='*.kt' -E "$pattern" "$path" 2>/dev/null || true
    fi
}

# --- R1: crdt must not import json ---
R1_HITS=$(scan "$MAIN/dev/yorkie/document/crdt" '^import dev\.yorkie\.document\.json\.')
if [[ -n "$R1_HITS" ]]; then
    report "R1 violation — document/crdt/** imports document/json/**:"
    echo "$R1_HITS" | sed 's/^/  /'
else
    ok "R1 document/crdt/** does not import document/json/**"
fi

# --- R2: util must not import document/core/api ---
R2_HITS=$(scan "$MAIN/dev/yorkie/util" '^import dev\.yorkie\.(document|core|api)\.')
if [[ -n "$R2_HITS" ]]; then
    report "R2 violation — util/** imports higher layer (document/core/api):"
    echo "$R2_HITS" | sed 's/^/  /'
else
    ok "R2 util/** does not import document/core/api"
fi

# --- R3: pure-domain code must not import generated proto (dev.yorkie.api.v1.*) ---
# core/ is the RPC I/O boundary (Connect-RPC client) and IS allowed to hold proto
# types. document/** and util/** must not — proto stays behind converters.
R3_HITS=$(
    { scan "$MAIN/dev/yorkie/document" '^import dev\.yorkie\.api\.v1\.'
      scan "$MAIN/dev/yorkie/util" '^import dev\.yorkie\.api\.v1\.'
    } | sort -u
)
if [[ -n "$R3_HITS" ]]; then
    report "R3 violation — document/util imports generated proto (dev.yorkie.api.v1.*):"
    echo "$R3_HITS" | sed 's/^/  /'
else
    ok "R3 document/util do not import dev.yorkie.api.v1.*"
fi

# --- R4: yorkie/src/main/** must not import examples/microbenchmark ---
R4_HITS=$(scan "$MAIN" '^import (dev\.yorkie\.examples|examples|microbenchmark|dev\.yorkie\.microbenchmark)\.')
if [[ -n "$R4_HITS" ]]; then
    report "R4 violation — yorkie/src/main/** imports examples or microbenchmark:"
    echo "$R4_HITS" | sed 's/^/  /'
else
    ok "R4 yorkie/src/main/** has no imports from examples or microbenchmark"
fi

# --- R5: no println / System.out / System.err in production code ---
# Exclude generated/ explicitly.
R5_HITS=""
if [[ $have_rg -eq 1 ]]; then
    R5_HITS=$(rg -n --no-heading --type kotlin \
        -g '!**/generated/**' \
        '(\bprintln\s*\(|System\.out\.|System\.err\.)' \
        "$MAIN" 2>/dev/null || true)
else
    R5_HITS=$(grep -rn --include='*.kt' --exclude-dir='generated' \
        -E '(\bprintln[[:space:]]*\(|System\.out\.|System\.err\.)' \
        "$MAIN" 2>/dev/null || true)
fi
if [[ -n "$R5_HITS" ]]; then
    report "R5 violation — forbidden console output in production code:"
    echo "$R5_HITS" | sed 's/^/  /'
else
    ok "R5 no println / System.out / System.err in yorkie/src/main/**"
fi

# --- R6: file size ---
R6_VIOLATIONS=""
while IFS= read -r -d '' f; do
    case "$f" in *"/generated/"*) continue ;; esac
    n=$(wc -l < "$f" | tr -d ' ')
    if (( n > MAX_LINES )); then
        R6_VIOLATIONS+="  $f  ($n lines, max $MAX_LINES)"$'\n'
    fi
done < <(find "$MAIN" -type f -name '*.kt' -print0)
if [[ -n "$R6_VIOLATIONS" ]]; then
    report "R6 violation — files exceed ${MAX_LINES}-line limit:"
    printf '%s' "$R6_VIOLATIONS"
else
    ok "R6 no file exceeds ${MAX_LINES} lines"
fi

echo
if (( VIOLATIONS > 0 )); then
    echo "[arch-lint] FAILED — $VIOLATIONS rule(s) violated"
    exit 1
fi
echo "[arch-lint] PASSED"
exit 0
