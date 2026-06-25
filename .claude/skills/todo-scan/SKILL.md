---
name: todo-scan
description: |
  Scans yorkie/ Kotlin files for TODO/FIXME comments.
  Creates JIRA tickets (RTCOLLABPLATFORM) for actionable items and a GitHub Issue summary.
  Triggered by "scan todos", "todo scan", "find todos", "check todos".
argument-hint: "[--scope changed|all|<module-path>] [--dry-run]"
---

## Overview

Scans source files for TODO/FIXME, evaluates each one, creates JIRA tickets for actionable items
(replacing the comment with a ticket reference), and creates a GitHub Issue as a summary and
wontfix cache.

Skips: `build/` directories and generated protobuf files.

## Phase 1: Collect

| `--scope` | What to scan |
|-----------|--------------|
| `all` (default) | `yorkie/src/main/` |
| `changed` | Files from `git diff main...HEAD --name-only` that fall in the above path |
| `<module-path>` | That path only (e.g. `yorkie/src/main/kotlin/dev/yorkie/document`) |

Scan `*.kt` / `*.kts` only. Use Grep with pattern `TODO\|FIXME` (case-insensitive).
Collect: file path, line number, marker, full comment text, 5 lines of context.
If 0 items found: print "No TODO/FIXME items found." and exit.

## Phase 1.5: Wontfix cache

```bash
gh issue list --label todo-sweep --state open --json number,body --limit 1
```

Parse `<!-- todo-scan-data ... -->` JSON block. Extract `wontfix` items with `judged_at` within 60 days — skip Phase 2 for these.

## Phase 2: Evaluate

| Classification | Action |
|----------------|--------|
| `actionable` | Create JIRA ticket, replace comment |
| `resolved` | Remove comment |
| `wontfix` | Skip, record reason |

Priority for actionable: `high` (bug risk) / `medium` (maintainability) / `low` (style).
When in doubt, classify as `actionable`.

## Phase 3: Act on actionable items (skip if `--dry-run`)

### 3a. Create JIRA ticket

```bash
curl -s -X POST \
  -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  "https://jira.navercorp.com/rest/api/2/issue" \
  -d "{
    \"fields\": {
      \"project\": { \"key\": \"RTCOLLABPLATFORM\" },
      \"summary\": \"{TODO comment text}\",
      \"description\": \"Source: {file}:{line}\\n\\nContext:\\n{surrounding code}\",
      \"issuetype\": { \"name\": \"Task\" },
      \"priority\": { \"name\": \"{High|Medium|Low}\" }
    }
  }"
```

Extract key: `jq -r .key`.

### 3b. Replace comment in source

Before: `// TODO: add null check for empty tree node`
After:  `// RTCOLLABPLATFORM-42: add null check for empty tree node`

## Phase 4: Report and GitHub Issue

Dry-run: print markdown table and exit.

Non-dry-run: create (or reuse existing) GitHub Issue with label `todo-sweep`:

```bash
gh label create todo-sweep --color "#e4e669" --description "TODO scan summary" 2>/dev/null || true
gh issue create --title "TODO Scan $(date +%Y-%m-%d)" --label todo-sweep --body "{body}"
```

Body includes results table + hidden `<!-- todo-scan-data {...} -->` JSON block with `scan_sha`, `scanned_at`, per-item `classification`, `jira_key`, `judged_at`.
