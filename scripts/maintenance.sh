#!/usr/bin/env bash
# maintenance — weekly hygiene check.
# Reports (does not fix) doc freshness, stale deps, untested modules, and TODO debt.

set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

say() { echo "[maintenance] $*"; }
section() { echo; echo "== $* =="; }

section "Doc freshness (> ${YORKIE_DOC_STALE_DAYS:-30} days)"
threshold="${YORKIE_DOC_STALE_DAYS:-30}"
stale=$(find docs -type f -name '*.md' -mtime +"$threshold" 2>/dev/null)
if [[ -n "$stale" ]]; then
    echo "$stale" | sed 's/^/  /'
else
    echo "  no stale docs"
fi

section "TODO / FIXME debt (yorkie/src/main)"
if command -v rg >/dev/null 2>&1; then
    rg -n --no-heading --type kotlin -g '!**/generated/**' 'TODO|FIXME' yorkie/src/main 2>/dev/null | head -n 50 | sed 's/^/  /' || echo "  none"
else
    grep -rn --include='*.kt' --exclude-dir='generated' -E 'TODO|FIXME' yorkie/src/main 2>/dev/null | head -n 50 | sed 's/^/  /' || echo "  none"
fi

section "Dependency versions (gradle/libs.versions.toml)"
if [[ -f gradle/libs.versions.toml ]]; then
    # Print plugin and library versions for a human to review against upstream.
    awk '/^\[versions\]/,/^\[/{ if (/^\[versions\]/) next; if (/^\[/) exit; print "  " $0 }' gradle/libs.versions.toml | grep -v '^$' | head -n 60
else
    echo "  libs.versions.toml not found"
fi

section "Active exec-plans"
if [[ -d docs/exec-plans/active ]]; then
    find docs/exec-plans/active -type f -name '*.md' -printf '  %p\n' 2>/dev/null || \
        find docs/exec-plans/active -type f -name '*.md' -exec echo "  {}" \;
fi

section "Round reports count"
n=0
[[ -d docs/round-reports ]] && n=$(find docs/round-reports -type f -name '*.md' | wc -l | tr -d ' ')
echo "  $n file(s)"

section "git log since last week"
git log --oneline --since='1 week ago' 2>/dev/null | head -n 30 | sed 's/^/  /'

echo
say "done — review the sections above"
