# Tasks 6–8: Skills

Creates all 5 skills under `.claude/skills/`. Split into three commits by adaptation level.

---

## Task 6: Identical skills (create-jira, todo-scan)

- [ ] **Step 1: Write `.claude/skills/create-jira/SKILL.md`**

```markdown
---
name: create-jira
description: |
  Creates or updates a JIRA issue in the RTCOLLABPLATFORM project.
  Triggered by "create jira", "create issue", "new ticket", "log a bug", "open a ticket",
  "update jira", "update issue".
argument-hint: "[--title title] [--type task|bug|epic|subtask] [--fix-version vX.X.X] [--relates-to N] [--epic N] [--parent N] [--update N] [--description text]"
---

## Overview

Creates or updates an issue in the `RTCOLLABPLATFORM` JIRA project.
API reference: `.claude/reference/jira-confluence-api.md`

## Issue Types

| Type | Flag | Max scope |
|------|------|-----------|
| Task | `task` (default) | Up to 2 weeks |
| Bug | `bug` | — |
| Epic | `epic` | Up to 3 months |
| Subtask | `subtask` | Up to 2 days |

## Phase 1: Parse arguments

1. Parse flags:
   - `--title`: Issue summary (required for new issues)
   - `--type`: `task` (default), `bug`, `epic`, `subtask`
   - `--fix-version`: Release version name (e.g. `v0.6.36`)
   - `--relates-to`: Issue number to link (e.g. `42` → `RTCOLLABPLATFORM-42`)
   - `--epic`: Epic key to link this task under
   - `--parent`: Parent issue key for subtasks (required when `--type subtask`)
   - `--update`: Existing issue number to update (triggers update mode)
   - `--description`: Issue description text

2. Any numeric-only value for `--relates-to`, `--epic`, `--parent`, `--update` is auto-prefixed with `RTCOLLABPLATFORM-`.

3. If required arguments are missing, ask with `AskUserQuestion` (always provide options, not free text).

## Phase 2: Get current user

```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/myself" \
  -o /tmp/jira-myself.json
```

Use the `name` field as the assignee.

## Phase 3: Resolve fix-version ID

If `--fix-version` is specified, fetch project versions and find the matching name.
If not found, create it:
```bash
curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "{version}", "project": "RTCOLLABPLATFORM", "released": false}' \
  "https://jira.navercorp.com/rest/api/2/version" \
  -o /tmp/jira-version.json
```

## Phase 4a: Create new issue (`--update` not specified)

Build the fields object:
```json
{
  "project": {"key": "RTCOLLABPLATFORM"},
  "summary": "{title}",
  "issuetype": {"name": "{Task|Bug|Epic|Sub-task}"},
  "assignee": {"name": "{myself.name}"},
  "description": "{description}"
}
```

Additional fields by condition:
- `--fix-version` → add `"fixVersions": [{"id": "{versionId}"}]`
- `--type epic` → add `"customfield_213535": "{title}"` (Epic Name)
- `--epic` → add `"customfield_213534": "RTCOLLABPLATFORM-{N}"` (Epic Link)
- `--type subtask` + `--parent` → add `"parent": {"key": "RTCOLLABPLATFORM-{N}"}`

```bash
curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fields": {...}}' \
  "https://jira.navercorp.com/rest/api/2/issue" \
  -o /tmp/jira-created.json
```

If `--relates-to` is specified, create an issue link after creation.

## Phase 4b: Update existing issue (`--update` specified)

1. Verify the issue exists: `GET /rest/api/2/issue/RTCOLLABPLATFORM-{N}`
2. Apply only the fields provided.
3. `PUT /rest/api/2/issue/RTCOLLABPLATFORM-{N}` with the update payload.

## Phase 5: Report result

```
RTCOLLABPLATFORM-{N}: {title}
  Type: {Task|Bug|Epic|Subtask}
  Fix version: {version or none}
  Relates to: {key or none}
  URL: https://jira.navercorp.com/browse/RTCOLLABPLATFORM-{N}
```

On API failure: print the error and stop.
```

- [ ] **Step 2: Write `.claude/skills/todo-scan/SKILL.md`**

```markdown
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
```

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/create-jira/ .claude/skills/todo-scan/
git commit -m "chore: add identical skills — create-jira and todo-scan"
```

---

## Task 7: Adapted skills (create-pr, team-review)

- [ ] **Step 4: Write `.claude/skills/create-pr/SKILL.md`**

```markdown
---
name: create-pr
description: |
  Creates a PR for yorkie-android-sdk with English body and JIRA integration.
  Triggered by "create PR", "make PR", "open PR", "pull request", "push and PR".
argument-hint: "[--jira RTCOLLABPLATFORM-N] [--draft] [--base branch] [--skip-ai-review]"
---

## Step 1: Analyze changes

1. If `--base` is specified, verify: `git rev-parse --verify {base}`. Stop with error if not found.
2. Collect: `git log {base}..HEAD --oneline` and `git diff {base}...HEAD` (default base: `develop`).
3. If no commits since base but uncommitted changes exist, commit all changes:
   - Check for sensitive files (`.env`, `*credentials*`, `*secret*`, `*token*`, `local.properties`) — warn and exclude
   - Run `./gradlew formatKotlin` before committing
   - Use the PR title format (from Step 3) as the commit message

## Step 2: Find JIRA ticket

Look for `RTCOLLABPLATFORM-N` in: `--jira` arg → branch name → commit messages.

If found:
```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}" \
  -o /tmp/jira-issue.json && jq -r .fields.summary /tmp/jira-issue.json
```

## Step 3: Write PR title and body

Title: `[RTCOLLABPLATFORM-N] {Jira issue title}` (max 70 chars) or `{type}: {description}` if no ticket.

Body:
```markdown
> **RTCOLLABPLATFORM-N**: [title](https://jira.navercorp.com/browse/RTCOLLABPLATFORM-N)

## Summary
- Bullet point summary

## Changes
- Detailed change descriptions

## Test plan
- [ ] Manual verification steps only

> Items run automatically by CI (lint, unit tests, coverage) are excluded.
```

## Step 4: User approval

Show title and body. Ask "Create PR" (proceed) or "Edit" (revise).

## Step 5: Create PR

1. `git push -u origin HEAD` — stop on failure.
2. ```bash
   gh pr create \
     --title "{title}" \
     --body "{body}" \
     --assignee @me \
     --base develop \
     [--draft] [--base {base}] [--label skip-ai-review]
   ```
3. Auto-assign reviewers from repo contributors (exclude bots and self). Skip in draft mode.
4. Return the PR URL.
```

- [ ] **Step 5: Write `.claude/skills/team-review/SKILL.md`**

```markdown
---
name: team-review
description: |
  Multi-agent review for yorkie-android-sdk. Runs critic-reviewer and test-writer in parallel;
  adds api-compat-checker when Client, Document, JsonObject/Array/Text/Tree, or .proto files are in the diff.
  Triggered by "team review", "review changes", "review this", "review PR", "code review".
argument-hint: "[--target plan|changes|branch] [--method subagent|team] [--base main]"
---

## Step 1: Confirm review target

If `--target` is not specified, ask:
- **plan**: current plan mode content
- **changes**: uncommitted staged + unstaged changes
- **branch**: full diff against `--base` (default: `main`)

## Step 2: Confirm method

If `--method subagent` is specified, skip. Otherwise confirm with the user.

## Step 3: Build review package

```bash
# --target changes:
git diff --name-only
git diff --cached --name-only

# --target branch:
git diff main...HEAD --name-only
```

Pass only the file list. Each agent reads what it needs via `git diff -- <file>`.
For plan mode, pass the plan content and referenced source file paths.

## Step 4: Select agents

Always run: `critic-reviewer`, `test-writer`

Also run `api-compat-checker` if any of these appear in the changed file list:
- `Client.kt`, `Document.kt`
- `JsonObject.kt`, `JsonArray.kt`, `JsonText.kt`, `JsonTree.kt`
- Any file matching `*.proto`

## Step 5: Project context (include in every agent prompt)

```
This is yorkie-android-sdk — an Android CRDT-based real-time collaboration SDK.
Documents are edited via JsonObject/JsonArray/JsonText/JsonTree (user-facing API) backed by
CrdtObject/CrdtArray/CrdtText/CrdtTree. Client manages server sync via Connect-RPC push-pull loop.
Each Client and Document runs on a dedicated single-threaded dispatcher.

Critical invariants:
1. CRDT correctness: TimeTicket uniqueness, tombstone tracking in GC pairs, lamport clock ordering
2. Single-threaded dispatch: Client and Document operations must stay on their dedicated dispatcher
3. Mutex guards: all attachment state mutations in Client must hold the mutex
4. API stability: public methods must not break without prior deprecation

Current review mode: {plan or code}
```

## Step 6: Execute review

Dispatch all selected agents as Task agents simultaneously. Wait for all. If one fails, continue.

## Step 7: Report

```
## Review Results

### Key Findings
- [Severity] Item (`file:line`)

### By Agent
- **critic-reviewer**: summary
- **test-writer**: summary
- **api-compat-checker** (if run): summary

### Recommended Actions
1. Must fix before merge
2. Should review
3. Notes
```
```

- [ ] **Step 6: Commit**

```bash
git add .claude/skills/create-pr/ .claude/skills/team-review/
git commit -m "chore: add adapted skills — create-pr and team-review"
```

---

## Task 8: Adapted skill (solve-jira)

- [ ] **Step 7: Write `.claude/skills/solve-jira/SKILL.md`**

```markdown
---
name: solve-jira
description: |
  Full end-to-end JIRA issue workflow: fetch issue, create branch, write plan, review plan,
  implement, code review, create PR. Triggered when an issue number is mentioned or when
  the current branch matches feat/RTCOLLABPLATFORM-* or fix/RTCOLLABPLATFORM-*.
  Natural language triggers: "solve 42", "work on ticket 42", "RTCOLLABPLATFORM-42".
argument-hint: "[issue-number] [--from baseBranch] [--exec-model opus|sonnet|haiku (default: sonnet)]"
---

## Execution Model

`--exec-model` sets the model for Phase 5 (implementation). Default: `sonnet`.

## Progress Checklist

```
- [ ] Phase 1: Issue confirmed, branch created
- [ ] Phase 2: Prompt written and approved
- [ ] Phase 3: Plan written
- [ ] Phase 4: Plan reviewed
- [ ] Phase 5: Implementation complete
- [ ] Phase 6: PR created, JIRA commented, plan cleaned up
```

## Phase 1: Confirm issue and create branch

1. Fetch issue:
   ```bash
   curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
     "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}" \
     -o /tmp/jira-issue.json
   ```
   Stop if issue does not exist.

2. Check assignee (`GET /rest/api/2/myself`). Stop if assigned to someone else.

3. Transition to "In Progress" if not already (fetch transitions, POST transition).

4. Branch naming: Bug → `fix/RTCOLLABPLATFORM-{N}`, all others → `feat/RTCOLLABPLATFORM-{N}`

5. Create branch from `--from` base (default: `origin/develop`):
   ```bash
   git fetch origin develop
   git checkout -b {branch} --no-track origin/develop
   git push -u origin HEAD
   ```

## Phase 2–3: Prompt and Plan (Planning Subagent)

Both phases run in a single planning subagent (`run_in_background: true`, `mode: "plan"`).

**Phase 2:** Subagent explores codebase and writes a prompt describing what to implement. Show to user, wait for approval.

**Phase 3:** Resume subagent with approved prompt to write detailed plan. Plan must include: absolute file paths, specific locations (class/function/line), exact code snippets, new files, test cases, change order.

Save plan to: `docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md`

## Phase 4: Review plan

Ask user: **Review** (run `team-review --target plan --method subagent`) or **Skip**.
Update plan with feedback. Append post-implementation steps section. Confirm before proceeding.

## Phase 5: Implementation

Delegate to implementation subagent:
- `model`: `--exec-model` value or `sonnet`
- `run_in_background: true`

Prompt must include: full plan content, "Follow CLAUDE.md rules.", commit message format `[RTCOLLABPLATFORM-{N}] {issue title}`, run `./gradlew formatKotlin` before committing, run `./gradlew yorkie:testDebugUnitTest lintKotlin` to verify, return team-review output + Fix/Skip classification.

Debugging budget: max 2 fix attempts per failure. After 2 failures, stop and report.

If budget exhausted: ask user — **Debug myself** / **Continue subagent** / **Stop**.

## Phase 6: Post-implementation

1. Report team-review output and Fix/Skip summary.
2. Invoke `create-pr --jira RTCOLLABPLATFORM-{N} --draft`.
3. Add JIRA comment with approach summary, key fixes, and PR URL.
4. Delete plan file: `rm docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md`
```

- [ ] **Step 8: Commit**

```bash
git add .claude/skills/solve-jira/
git commit -m "chore: add adapted skill — solve-jira"
```
