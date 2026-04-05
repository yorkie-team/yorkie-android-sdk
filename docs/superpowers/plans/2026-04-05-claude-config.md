# Claude Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the full `.claude/` configuration for `yorkie-android-sdk` — agents, rules, skills, hooks, and settings — mirroring `zero-plugins-android` with yorkie-domain adaptations.

**Architecture:** All files live under `.claude/` in the repo root. Identical files are copied verbatim from `/Users/FXHHHJFJQ0/Workspace/Android/zero-plugins-android/.claude/`; adapted files are modified copies; new files are written from scratch. No source code changes.

**Tech Stack:** Markdown (agents, rules, skills, references), Bash (hooks), JSON (settings).

---

## Task 1: Scaffold directories + settings files

**Files:**
- Create: `.claude/settings.json`
- Create: `.claude/settings.local.json`
- Modify: `.gitignore`

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p .claude/hooks .claude/agents .claude/rules .claude/skills/solve-jira .claude/skills/team-review .claude/skills/create-jira .claude/skills/create-pr .claude/skills/todo-scan .claude/reference
```

- [ ] **Step 2: Write `.claude/settings.json`**

```json
{
  "SessionStart": [
    {
      "matcher": "*",
      "hooks": [
        {
          "type": "command",
          "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/check-jira-token.sh"
        },
        {
          "type": "command",
          "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/check-docker-yorkie.sh"
        }
      ]
    }
  ],
  "PreToolUse": [
    {
      "matcher": "Bash",
      "hooks": [
        {
          "type": "command",
          "command": "command=$(echo \"$CLAUDE_TOOL_INPUT\" | jq -r '.command // empty') && if echo \"$command\" | grep -q 'jira.navercorp.com'; then if [ -z \"$JIRA_PERSONAL_TOKEN\" ]; then echo 'BLOCK: JIRA_PERSONAL_TOKEN is not set. Set it with: export JIRA_PERSONAL_TOKEN=<your-token>' >&2; exit 2; fi; fi"
        }
      ]
    }
  ]
}
```

- [ ] **Step 3: Write `.claude/settings.local.json`**

```json
{
  "permissions": {
    "allow": [
      "Bash(git status*)",
      "Bash(git log*)",
      "Bash(git diff*)",
      "Bash(git branch*)",
      "Bash(git show*)",
      "Bash(git fetch*)",
      "Bash(git pull*)",
      "Bash(git add*)",
      "Bash(git commit*)",
      "Bash(git checkout*)",
      "Bash(git stash*)",
      "Bash(git merge-base*)",
      "Bash(git rev-parse*)",
      "Bash(git worktree*)",
      "Bash(./gradlew*)",
      "Bash(ls*)",
      "Bash(cat*)",
      "Bash(head*)",
      "Bash(tail*)",
      "Bash(find*)",
      "Bash(grep*)",
      "Bash(echo*)",
      "Bash(pwd)",
      "Bash(mkdir*)",
      "Bash(cp*)",
      "Bash(mv*)",
      "Bash(chmod*)",
      "Bash(which*)",
      "Bash(jq*)",
      "Bash(gh pr*)",
      "Bash(gh issue*)",
      "Bash(gh api*)",
      "Bash(gh auth status*)",
      "Bash(docker*)",
      "Edit"
    ]
  }
}
```

- [ ] **Step 4: Ensure `settings.local.json` is gitignored**

Open `.gitignore` and add this line if not already present:

```
.claude/settings.local.json
```

- [ ] **Step 5: Verify**

```bash
ls .claude/
# Expected: agents  hooks  reference  rules  settings.json  settings.local.json  skills
jq . .claude/settings.json
# Expected: valid JSON printed with no errors
```

- [ ] **Step 6: Commit**

```bash
git add .claude/settings.json .gitignore
git commit -m "chore: scaffold .claude/ config directory and settings"
```

---

## Task 2: Identical rules (kotlin-style, kdoc, changelog)

**Files:**
- Create: `.claude/rules/kotlin-style.md`
- Create: `.claude/rules/kdoc-rule.md`
- Create: `.claude/rules/changelog-rule.md`

- [ ] **Step 1: Write `.claude/rules/kotlin-style.md`**

```markdown
---
paths:
  - "**/*.kt"
---

# Kotlin Style Conventions

Baseline: https://kotlinlang.org/docs/reference/coding-conventions.html

## Naming

This project uses PascalCase for all constant-like declarations. Callers should not need to know
whether `Foo` is an `object`, `enum` entry, `const val`, or top-level `val` — naming is consistent
regardless of implementation.

| Type                            | Convention | Example             |
|---------------------------------|------------|---------------------|
| Constants (`const val`)         | PascalCase | `DefaultKeyName`    |
| Immutable vals (singleton-like) | PascalCase | `StructurallyEqual` |
| Enum values                     | PascalCase | `Status.Idle`       |
| Sealed class objects            | PascalCase | `Result.Success`    |
| Singleton objects               | PascalCase | `ReferenceEqual`    |

```kotlin
// ✅ Do
const val DefaultKeyName = "__defaultKey"
enum class Status { Idle, Busy }

// ❌ Don't
const val DEFAULT_KEY_NAME = "__defaultKey"
enum class Status { IDLE, BUSY }
```

**Exception**: External API constants (e.g., `Typeface.BOLD`, `AnnotationTarget.CLASS`) keep their
original naming — only rename declarations owned by this project.
```

- [ ] **Step 2: Write `.claude/rules/kdoc-rule.md`**

```markdown
---
paths:
  - "**/*.{kt,kts}"
---

# KDoc Style Guide

## Core Rules

| Rule | Description | Example |
|------|-------------|---------|
| Language | English throughout | `Returns the current selection state.` |
| Tone | Declarative, neutral — no polite or passive constructions | ❌ `This method returns` → ✅ `Returns` |
| Endings | Declarative sentence or noun phrase, with period | `Converts upstream operations.` / `Manager for document sync.` |
| Annotation order | KDoc → annotations → declaration | KDoc must appear above `@Stable`, `@JvmOverloads` |
| Tags | `@param`, `@return`, `@throws` in same declarative style | `@param dispatcher Dispatcher used for Yorkie operations.` |
| Class references | Brackets, not backticks | ❌ `` `Client` `` → ✅ `[Client]` |

## Checklist

- [ ] Written in English
- [ ] No passive or polite constructions ("This returns", "You can use", "Please note")
- [ ] Ends with a period
- [ ] KDoc placed above annotations, not between annotation and declaration
- [ ] Class/function references use `[ClassName]`, not backticks
```

- [ ] **Step 3: Write `.claude/rules/changelog-rule.md`**

```markdown
---
paths:
  - "CHANGELOG.md"
---

# CHANGELOG Rule

- Follow the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format.
- Write in English.
- Each entry must include a JIRA ticket reference: `[RTCOLLABPLATFORM-N]`.
- Use noun phrases — no verb sentences. (✅ `CRDT tree node split fix` / ❌ `Fix CRDT tree node split`)
- Breaking changes must use the `**Breaking**:` prefix.
- Use backticks for code references (e.g., `Document`, `JsonTree`).
- One entry per line, starting with `-`.
- Record only the final change relative to `main` — do not include intermediate branch history.

## Example

```markdown
## [Unreleased]

### Added
- [RTCOLLABPLATFORM-42] `JsonTree` node move operation support
- [RTCOLLABPLATFORM-43] `Client.syncMode` configuration at attach time

### Fixed
- [RTCOLLABPLATFORM-44] **Breaking**: `Document.updateAsync` — callback now receives `JsonObject` root directly
```
```

- [ ] **Step 4: Verify**

```bash
ls .claude/rules/
# Expected: changelog-rule.md  kdoc-rule.md  kotlin-style.md
```

- [ ] **Step 5: Commit**

```bash
git add .claude/rules/kotlin-style.md .claude/rules/kdoc-rule.md .claude/rules/changelog-rule.md
git commit -m "chore: add identical rules — kotlin-style, kdoc, changelog"
```

---

## Task 3: Adapted rules (testing-patterns, git-workflow)

**Files:**
- Create: `.claude/rules/testing-patterns.md`
- Create: `.claude/rules/git-workflow.md`

- [ ] **Step 1: Write `.claude/rules/testing-patterns.md`**

```markdown
---
paths:
  - "**/*Test.kt"
  - "**/test/**/*.kt"
  - "**/androidTest/**/*.kt"
---

# Testing Patterns

## Core Rules

| Rule | Description |
|------|-------------|
| Test names | English backticks, descriptive and imperative |
| Assertions | Standard JUnit (`assertEquals`, `assertTrue`, `assertNull`) |
| Structure | Given-When-Then |
| Mocking | MockK (`mockk`, `every`, `verify`, `coEvery`, `coVerify`) |
| JUnit version | JUnit 4 only (`@Test`, `@Before`, `@After`, `@get:Rule`) |
| Coroutine testing | `CoroutineRule` + `runTest` + `advanceUntilIdle()` |
| Debugging code | Remove `println()` and temporary logs before committing |

## Test Name Examples

```kotlin
@Test
fun `document update applies operation to crdt root`() { ... }

@Test
fun `client attach fails when document already attached`() { ... }

@Test
fun `json tree node split preserves parent child references`() { ... }
```

## CoroutineRule Setup

```kotlin
class MyTest {
    @get:Rule
    val coroutineRule = CoroutineRule()

    @Test
    fun `example`() = runTest {
        // test body
        advanceUntilIdle()
    }
}
```

## Given-When-Then Structure

```kotlin
@Test
fun `document returns correct root after update`() {
    // given
    val document = mockk<Document>(relaxed = true)
    coEvery { document.updateAsync(any()) } just Runs

    // when
    document.updateAsync { root -> root["key"] = "value" }
    advanceUntilIdle()

    // then
    coVerify { document.updateAsync(any()) }
}
```

## MockK Patterns

```kotlin
val document = mockk<Document>(relaxed = true)
val client = mockk<Client>(relaxed = true)
coEvery { document.updateAsync(any()) } just Runs
coVerify { document.updateAsync(any()) }
coVerify(exactly = 0) { client.activateAsync() }
```

## Two Test Targets

### Unit tests — `src/test/kotlin/dev/yorkie/`

- JVM only, no emulator or Docker required
- Run with: `./gradlew yorkie:testDebugUnitTest`
- Run single class: `./gradlew yorkie:testDebugUnitTest --tests "dev.yorkie.document.DocumentTest"`
- Focus: CRDT data structures, converters, utility classes
- Use MockK to mock `YorkieService` and coroutine collaborators

### Instrumented tests — `src/androidTest/kotlin/dev/yorkie/`

- Require Android emulator/device AND a running Yorkie Docker server
- Start server: `docker compose -f docker/docker-compose.yml up --build -d`
- Run with: `./gradlew yorkie:connectedDebugAndroidTest`
- Focus: document sync, presence, schema validation, full client lifecycle
- Use `TestUtils`, `ApiUtils`, `JsonTestUtils` helpers from the same test directory
- Always create one `Client` per test; always call `detach()` and `deactivate()` in `@After`

## What to prioritize

1. CRDT edge cases: empty documents, concurrent edits, tombstone collection
2. Concurrency: verify operations run on the correct dispatcher
3. Client lifecycle: activate → attach → detach → deactivate teardown
4. API boundary null safety
```

- [ ] **Step 2: Write `.claude/rules/git-workflow.md`**

```markdown
# Git Workflow

## Branching

- Always branch from `develop` (not `main`)
- Branch naming:
  - Feature: `feat/RTCOLLABPLATFORM-{N}-{short-desc}`
  - Bug fix: `fix/RTCOLLABPLATFORM-{N}-{short-desc}`
  - Chore / tooling: `chore/{short-desc}`
- Use `--no-track` when creating branches: `git checkout -b {branch} --no-track origin/develop`
- Never commit directly to `main` or `develop`

## Commits

Format: `{type}: {description}`

| Type | When |
|------|------|
| `feat` | New functionality |
| `fix` | Bug fix |
| `chore` | Tooling, config, dependencies |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `refactor` | No behaviour change |
| `task` | Migration, cleanup, non-feature work |

- Lowercase after the colon, no period at the end
- Run `./gradlew formatKotlin` before every commit
- Run `./gradlew yorkie:testDebugUnitTest lintKotlin` before pushing

## Pull Requests

- All PRs target `develop`
- Title format: `[RTCOLLABPLATFORM-N] {ticket title}` (if ticket exists), otherwise `{type}: {description}`
- Use `/create-pr` skill to create PRs

## Release Flow

1. Cut `release/vX.X.X` from `develop`:
   ```bash
   git checkout -b release/vX.X.X --no-track origin/develop
   git push -u origin release/vX.X.X
   ```
2. Bump version in `gradle.properties`, stabilise (bug fixes only)
3. Run full quality gate: `./gradlew clean lintKotlin yorkie:testDebugUnitTest yorkie:jacocoDebugTestReport`
4. PR `release/vX.X.X` → `main`, title: `release: vX.X.X`
5. Tag after merge: `git tag vX.X.X && git push origin vX.X.X`
6. Sync back: merge `main` → `develop`, bump `develop` to next snapshot

## Hotfix Flow

1. Branch `hotfix/{desc}` from `main` (not `develop`)
2. Fix, test, PR to `main`
3. Tag patch version: `vX.X.{N+1}`
4. PR (or cherry-pick) to `develop` to keep it in sync

## Full workflow reference

See `.claude/reference/git-strategy.md` for diagrams and detailed commands.
```

- [ ] **Step 3: Verify**

```bash
ls .claude/rules/
# Expected: changelog-rule.md  git-workflow.md  kdoc-rule.md  kotlin-style.md  testing-patterns.md
```

- [ ] **Step 4: Commit**

```bash
git add .claude/rules/testing-patterns.md .claude/rules/git-workflow.md
git commit -m "chore: add adapted rules — testing-patterns and git-workflow"
```

---

## Task 4: References (identical)

**Files:**
- Create: `.claude/reference/jira-confluence-api.md`
- Create: `.claude/reference/git-strategy.md`

- [ ] **Step 1: Write `.claude/reference/jira-confluence-api.md`**

```markdown
# JIRA REST API Reference

Look up the curl command for the task at hand and execute it.

- Base URL: `https://jira.navercorp.com/rest/api/2`
- Auth: `Authorization: Bearer $JIRA_PERSONAL_TOKEN`
- GET requests MUST NOT include `Content-Type` header (causes 401).
- Save responses with `-o /tmp/<name>.json` and parse with `jq` (avoids pipe encoding issues).
- Include `-H "Content-Type: application/json"` only for POST/PUT requests.

---

## Get issue

```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}" \
  -o /tmp/jira-issue.json
```

Key fields:
```bash
jq '.fields.summary, .fields.status.name, .fields.issuetype.name, .fields.assignee.displayName' /tmp/jira-issue.json
```

## Get current user

```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/myself" \
  -o /tmp/jira-myself.json
```

## Search issues (JQL)

```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/search?jql={URL-encoded-JQL}&maxResults=20" \
  -o /tmp/jira-search.json
```

Example JQL: `project = RTCOLLABPLATFORM AND status = "In Progress" ORDER BY updated DESC`

## Create issue

```bash
curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fields": {
      "project": {"key": "RTCOLLABPLATFORM"},
      "summary": "{title}",
      "issuetype": {"name": "Task"},
      "description": "{description}"
    }
  }' \
  "https://jira.navercorp.com/rest/api/2/issue" \
  -o /tmp/jira-created.json
```

Response: `jq -r .key /tmp/jira-created.json` → `RTCOLLABPLATFORM-42`

## Update issue

```bash
curl -s -X PUT -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fields": {
      "summary": "{new title}",
      "description": "{new description}"
    }
  }' \
  "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}"
```

## Transition status

Get available transitions:
```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}/transitions" \
  -o /tmp/jira-transitions.json
jq '.transitions[] | {id, name}' /tmp/jira-transitions.json
```

Execute transition:
```bash
curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"transition": {"id": "{transitionId}"}}' \
  "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}/transitions"
```

## Add comment

```bash
curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"body": "{comment body}"}' \
  "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}/comment" \
  -o /tmp/jira-comment.json
```

## Create issue link

```bash
curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": {"id": "10020"},
    "inwardIssue": {"key": "RTCOLLABPLATFORM-{A}"},
    "outwardIssue": {"key": "RTCOLLABPLATFORM-{B}"}
  }' \
  "https://jira.navercorp.com/rest/api/2/issueLink"
```

## Get project versions

```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/project/RTCOLLABPLATFORM/versions" \
  -o /tmp/jira-versions.json
jq '.[] | {id, name}' /tmp/jira-versions.json
```
```

- [ ] **Step 2: Write `.claude/reference/git-strategy.md`**

```markdown
# Git Strategy

## Branch Model

```
main
  └── develop          ← integration branch; all feature PRs target here
        ├── feat/RTCOLLABPLATFORM-N-short-description
        ├── fix/RTCOLLABPLATFORM-N-short-description
        └── chore/short-description

release/vX.X.X         ← cut from develop when preparing a release
  └── hotfix/short-description   ← critical fixes on a live release
```

## Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| Feature | `feat/RTCOLLABPLATFORM-{N}-{short-desc}` | `feat/RTCOLLABPLATFORM-42-json-tree-move` |
| Bug fix | `fix/RTCOLLABPLATFORM-{N}-{short-desc}` | `fix/RTCOLLABPLATFORM-55-gc-tombstone-drop` |
| Chore / tooling | `chore/{short-desc}` | `chore/update-connect-rpc-dependency` |
| Release | `release/v{X.X.X}` | `release/v0.6.36` |
| Hotfix | `hotfix/{short-desc}` | `hotfix/null-crash-on-detach` |

- Use lowercase and hyphens only — no underscores, no camelCase.
- Keep descriptions short (2–4 words).
- When a JIRA ticket exists, include the ticket number.

## Commit Messages

Format: `{type}: {description}`

| Type | When to use |
|------|-------------|
| `feat` | New functionality |
| `fix` | Bug fix |
| `chore` | Tooling, config, dependency updates |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `refactor` | Code change with no behaviour change |
| `task` | Non-feature work items (migration, cleanup) |

Rules:
- Lowercase after the colon
- No period at the end
- Imperative mood: "add json tree move" not "added json tree move"
- Reference JIRA ticket in the body or PR title, not in every commit subject
- Run `./gradlew formatKotlin` before committing

Examples:
```
feat: add JsonTree node move operation
fix: null crash when document detaches before sync completes
chore: upgrade connect-kotlin to 0.7.1
docs: add CRDT architecture overview to CLAUDE.md
test: cover empty root case in CrdtObjectTest
```

## Pull Request Flow

```
feat/RTCOLLABPLATFORM-42  →  PR  →  develop  →  (release cut)  →  main
```

1. Branch off `develop`
2. Implement, test, lint (`./gradlew formatKotlin yorkie:testDebugUnitTest lintKotlin`)
3. Open PR targeting `develop`
4. Merge after review approval

## Release Flow

1. Cut `release/vX.X.X` from `develop`
2. Bump `VERSION_NAME` in `gradle.properties`, stabilise on release branch
3. Run full quality gate: `./gradlew clean lintKotlin yorkie:testDebugUnitTest yorkie:jacocoDebugTestReport`
4. PR from `release/vX.X.X` → `main`
5. Tag `vX.X.X` on `main` after merge
6. Merge `main` back into `develop` to sync

## Hotfix Flow

1. Branch `hotfix/{desc}` from `main`
2. Fix and test
3. PR to `main`, then separately PR to `develop`

## Rules

- **Never commit directly to `main` or `develop`** — always via PR
- **Never force-push** to `develop`, `main`, or `release/**`
- **One logical change per PR** — split unrelated changes into separate branches
- **Delete branches after merge** — keep the remote clean
- **`./gradlew formatKotlin` before every push** — CI will reject unformatted code
```

- [ ] **Step 3: Verify**

```bash
ls .claude/reference/
# Expected: git-strategy.md  jira-confluence-api.md
```

- [ ] **Step 4: Commit**

```bash
git add .claude/reference/
git commit -m "chore: add reference docs — jira api and git strategy"
```

---

## Task 5: Hooks

**Files:**
- Create: `.claude/hooks/check-jira-token.sh`
- Create: `.claude/hooks/check-docker-yorkie.sh`

- [ ] **Step 1: Write `.claude/hooks/check-jira-token.sh`**

```bash
#!/bin/bash
# SessionStart hook: warn if JIRA_PERSONAL_TOKEN is not set.
# create-pr, todo-scan, and solve-jira skills call jira.navercorp.com and will fail without it.
if [ -z "$JIRA_PERSONAL_TOKEN" ]; then
    echo "WARNING: JIRA_PERSONAL_TOKEN is not set."
    echo "  Skills that call jira.navercorp.com (create-pr, todo-scan, solve-jira) will fail."
    echo "  Set it with: export JIRA_PERSONAL_TOKEN=<your-personal-token>"
fi
```

- [ ] **Step 2: Write `.claude/hooks/check-docker-yorkie.sh`**

```bash
#!/bin/bash
# SessionStart hook: warn if Docker or the Yorkie server container is not running.
# Instrumented tests (yorkie:connectedDebugAndroidTest) require a live Yorkie server.

if ! docker info > /dev/null 2>&1; then
    echo "WARNING: Docker is not running."
    echo "  Instrumented tests require the Yorkie server container."
    echo "  Start Docker, then run: docker compose -f docker/docker-compose.yml up --build -d"
    exit 0
fi

if ! docker ps --format '{{.Names}}' | grep -q 'yorkie'; then
    echo "WARNING: Yorkie server container is not running."
    echo "  Instrumented tests (connectedDebugAndroidTest) will fail without it."
    echo "  Start it with: docker compose -f docker/docker-compose.yml up --build -d"
fi
```

- [ ] **Step 3: Make hooks executable**

```bash
chmod +x .claude/hooks/check-jira-token.sh
chmod +x .claude/hooks/check-docker-yorkie.sh
```

- [ ] **Step 4: Verify shell syntax**

```bash
bash -n .claude/hooks/check-jira-token.sh && echo "ok"
bash -n .claude/hooks/check-docker-yorkie.sh && echo "ok"
# Expected: ok (twice)
```

- [ ] **Step 5: Commit**

```bash
git add .claude/hooks/
git commit -m "chore: add session-start hooks — jira token and docker yorkie checks"
```

---

## Task 6: Identical skills (create-jira, todo-scan)

**Files:**
- Create: `.claude/skills/create-jira/SKILL.md`
- Create: `.claude/skills/todo-scan/SKILL.md`

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
   - `--epic`: Epic key to link this task under (e.g. `10` → `RTCOLLABPLATFORM-10`)
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

If `--fix-version` is specified:
1. Fetch project versions and find the matching name.
2. If not found, create it:
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
- `--type epic` → add `"customfield_213535": "{title}"` (Epic Name, same as summary)
- `--epic` → add `"customfield_213534": "RTCOLLABPLATFORM-{N}"` (Epic Link)
- `--type subtask` + `--parent` → add `"parent": {"key": "RTCOLLABPLATFORM-{N}"}`

Create:
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
2. Apply only the fields provided (title, description, fix-version, relates-to).
3. `PUT /rest/api/2/issue/RTCOLLABPLATFORM-{N}` with the update payload.

## Phase 5: Report result

```
RTCOLLABPLATFORM-{N}: {title}
  Type: {Task|Bug|Epic|Subtask}
  Fix version: {version or none}
  Relates to: {key or none}
  URL: https://jira.navercorp.com/browse/RTCOLLABPLATFORM-{N}
```

On API failure: print the error and stop. Do not proceed to subsequent steps.
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

---

## Phase 1: Collect

### Scan scope

| `--scope` | What to scan |
|-----------|--------------|
| `all` (default) | `yorkie/src/main/` |
| `changed` | Files from `git diff main...HEAD --name-only` that fall in the above paths |
| `<module-path>` | That path only (e.g. `yorkie/src/main/kotlin/dev/yorkie/document`) |

Scan Kotlin files only (`*.kt`, `*.kts`). Use Grep with pattern `TODO\|FIXME` (case-insensitive).

Collect per item:
- File path + line number
- Marker (TODO or FIXME)
- Full comment text
- 5 lines of surrounding context

If 0 items found: print "No TODO/FIXME items found." and exit.

---

## Phase 1.5: Wontfix cache

Check for an open GitHub Issue with label `todo-sweep`:

```bash
gh issue list --label todo-sweep --state open --json number,body --limit 1
```

If found, parse the hidden `<!-- todo-scan-data ... -->` JSON block in the issue body.
Extract items where `classification` is `wontfix` and `judged_at` is within 60 days of today.
These items skip Phase 2 — inherit their previous classification and reason.

---

## Phase 2: Evaluate

For each item not in the wontfix cache, read the surrounding code and classify:

| Classification | Meaning | Action |
|----------------|---------|--------|
| `actionable` | Fix is clear and JIRA-ready | Create JIRA ticket, replace comment |
| `resolved` | Already implemented in the code | Remove comment |
| `wontfix` | Not fixable or not worth fixing | Skip, record reason |

**Priority for actionable**: `high` (bug risk) / `medium` (maintainability) / `low` (style)

Only classify as `resolved` if you are certain after reading the surrounding code. When in doubt, classify as `actionable` and let the developer decide.

---

## Phase 3: Act on actionable items

For each `actionable` item (skip if `--dry-run`):

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

Extract the created issue key from the response: `jq -r .key`.

### 3b. Replace TODO comment in source

Before: `// TODO: add null check for empty tree node`
After:  `// RTCOLLABPLATFORM-42: add null check for empty tree node`

Edit the file at the exact line — do not change surrounding code.

---

## Phase 4: Report and GitHub Issue

### Dry-run output

Print a markdown table and exit without creating JIRA tickets or GitHub Issues:

```
## TODO Scan Results

| # | File | Comment | Classification | Reason |
|---|------|---------|----------------|--------|
| 1 | yorkie/src/.../CrdtTree.kt:42 | Add null check | 🔴 actionable (high) | Bug risk |
| 2 | yorkie/src/.../Document.kt:15 | Refactor later | wontfix | Design undecided |

Actionable: 1 | Resolved: 0 | Wontfix: 1
```

### Create GitHub Issue (non-dry-run)

Check for existing open `todo-sweep` issue — if one exists, print its URL and exit without creating another.

Create `todo-sweep` label if it does not exist:

```bash
gh label create todo-sweep --color "#e4e669" --description "TODO scan summary" 2>/dev/null || true
```

Create the summary issue:

```bash
gh issue create \
  --title "TODO Scan $(date +%Y-%m-%d)" \
  --label todo-sweep \
  --body "{body below}"
```

Issue body structure:

```markdown
## TODO Scan Results

{results table — same format as dry-run, with JIRA links for actionable items}

---

<!-- todo-scan-data
{
  "scan_sha": "{git rev-parse HEAD}",
  "scanned_at": "YYYY-MM-DD",
  "items": [
    {
      "id": 1,
      "file": "yorkie/src/.../CrdtTree.kt",
      "line": 42,
      "marker": "TODO",
      "content": "Add null check for empty tree node",
      "classification": "actionable",
      "priority": "high",
      "jira_key": "RTCOLLABPLATFORM-42",
      "reason": "Bug risk: null node causes NPE in split operation"
    },
    {
      "id": 2,
      "file": "yorkie/src/.../Document.kt",
      "line": 15,
      "marker": "TODO",
      "content": "Refactor later",
      "classification": "wontfix",
      "reason": "Design undecided",
      "judged_at": "YYYY-MM-DD"
    }
  ]
}
-->
```

Print the created issue URL.
```

- [ ] **Step 3: Verify**

```bash
ls .claude/skills/
# Expected: create-jira  create-pr  solve-jira  team-review  todo-scan
```

- [ ] **Step 4: Commit**

```bash
git add .claude/skills/create-jira/ .claude/skills/todo-scan/
git commit -m "chore: add identical skills — create-jira and todo-scan"
```

---

## Task 7: Adapted skills (create-pr, team-review)

**Files:**
- Create: `.claude/skills/create-pr/SKILL.md`
- Create: `.claude/skills/team-review/SKILL.md`

- [ ] **Step 1: Write `.claude/skills/create-pr/SKILL.md`**

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
   - Use the same title format as the PR title (determined in Step 3)
4. Note the base branch — used as `gh pr create --base` target.

## Step 2: Find JIRA ticket

Look for `RTCOLLABPLATFORM-N` in this order:
1. `--jira` argument
2. Branch name (e.g. `feat/RTCOLLABPLATFORM-42-json-tree-move`)
3. Commit messages

If found, fetch the issue title:

```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}" \
  -o /tmp/jira-issue.json && jq -r .fields.summary /tmp/jira-issue.json
```

## Step 3: Write PR title and body

### Title

| Condition | Format | Example |
|-----------|--------|---------|
| JIRA ticket found | `[RTCOLLABPLATFORM-N] {Jira issue title}` | `[RTCOLLABPLATFORM-42] JsonTree node move operation` |
| No ticket | `{type}: {description}` | `fix: null crash on document detach` |

Max 70 characters. Types: `feat`, `fix`, `refactor`, `docs`, `chore`, `test`.

### Body

```markdown
> **RTCOLLABPLATFORM-N**: [Jira issue title](https://jira.navercorp.com/browse/RTCOLLABPLATFORM-N)

## Summary
- Bullet point summary of changes

## Changes
- Detailed change descriptions
- For complex changes, use `### 1. Subtitle` subsections explaining the intent

## Test plan
- [ ] Manual verification steps only

> Items run automatically by CI (lint, unit tests, coverage) are excluded.
```

Omit the JIRA blockquote line when no ticket is found.

## Step 4: User approval

Show the PR title and body. Ask:
- "Create PR" → proceed to Step 5
- "Edit" → revise and re-show, do not proceed until approved

## Step 5: Create PR

1. Push: `git push -u origin HEAD`. Stop with error if push fails — do not proceed.
2. Create the PR:
   ```bash
   gh pr create \
     --title "{title}" \
     --body "{body}" \
     --assignee @me \
     --base develop \
     [--draft if --draft flag given] \
     [--base {base} if --base flag given] \
     [--label skip-ai-review if --skip-ai-review flag given]
   ```
3. Auto-assign reviewers from repo contributors (exclude bots and self):
   ```bash
   gh api repos/{owner}/{repo}/contributors --jq '.[].login'
   gh api user --jq '.login'   # self — exclude
   ```
   Skip reviewer assignment in draft mode. If contributor lookup fails or returns only self, create PR without reviewers and print a warning.
4. Return the PR URL.
```

- [ ] **Step 2: Write `.claude/skills/team-review/SKILL.md`**

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

If `--target` is specified, skip to Step 2. Otherwise ask:

- **plan**: current plan mode content
- **changes**: uncommitted staged + unstaged changes
- **branch**: full branch diff against `--base` (default: `main`)

Review mode by target:

| Target | Review mode |
|--------|-------------|
| plan | plan |
| changes / branch | code |

## Step 2: Confirm method

If `--method subagent` is specified, skip. Otherwise confirm with the user before proceeding.
Uses parallel Task agents — one per selected reviewer, dispatched simultaneously.

## Step 3: Build review package

**code mode** — collect changed files:

```bash
# For --target changes:
git diff --name-only          # unstaged
git diff --cached --name-only # staged

# For --target branch:
git diff main...HEAD --name-only
```

Pass only the file list to agents. Each agent reads what it needs via `git diff -- <file>`.

**plan mode** — pass the plan content and any source file paths it references.

## Step 4: Select agents

Always run: `critic-reviewer`, `test-writer`

Also run `api-compat-checker` if any of these appear in the changed file list:
- `Client.kt`
- `Document.kt`
- `JsonObject.kt`
- `JsonArray.kt`
- `JsonText.kt`
- `JsonTree.kt`
- Any file matching `*.proto`

## Step 5: Project context (include in every agent prompt)

```
This is yorkie-android-sdk — an Android CRDT-based real-time collaboration SDK.
Documents are edited via JsonObject/JsonArray/JsonText/JsonTree (user-facing API) backed by
CrdtObject/CrdtArray/CrdtText/CrdtTree (internal CRDT layer). Client manages server sync via
Connect-RPC push-pull loop. Each Client and Document runs on a dedicated single-threaded dispatcher.

Critical invariants:
1. CRDT correctness: TimeTicket uniqueness, tombstone tracking in GC pairs, lamport clock ordering
2. Single-threaded dispatch: Client and Document operations must stay on their dedicated dispatcher
3. Mutex guards: all attachment state mutations in Client must hold the mutex
4. API stability: public methods on Client, Document, JsonObject/Array/Text/Tree must not break without deprecation

Current review mode: {plan or code}
```

## Step 6: Execute review

Dispatch all selected agents as Task agents simultaneously (`subagent_type` = agent name).
Each prompt includes: review mode + changed file list + project context.
Wait for all to complete. If one fails, continue with remaining results.

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

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/create-pr/ .claude/skills/team-review/
git commit -m "chore: add adapted skills — create-pr and team-review"
```

---

## Task 8: Adapted skill (solve-jira)

**Files:**
- Create: `.claude/skills/solve-jira/SKILL.md`

- [ ] **Step 1: Write `.claude/skills/solve-jira/SKILL.md`**

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
Planning always uses the current session model.

## Progress Checklist

Update this at the end of each phase:

```
- [ ] Phase 1: Issue confirmed, branch created
- [ ] Phase 2: Prompt written and approved
- [ ] Phase 3: Plan written
- [ ] Phase 4: Plan reviewed
- [ ] Phase 5: Implementation complete
- [ ] Phase 6: PR created, JIRA commented, plan cleaned up
```

---

## Phase 1: Confirm issue and create branch

1. Fetch the issue:
   ```bash
   curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
     "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}" \
     -o /tmp/jira-issue.json
   ```
   Stop if the issue does not exist.

2. Check assignee: fetch current user (`GET /rest/api/2/myself`).
   - `assignee.name == myself.name` → proceed
   - `assignee` is null → warn and continue
   - `assignee.name != myself.name` → stop (issue belongs to someone else)

3. Transition to "In Progress" if not already:
   ```bash
   curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
     "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}/transitions" \
     -o /tmp/jira-transitions.json
   # Find the "In Progress" transition ID, then:
   curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"transition":{"id":"{transitionId}"}}' \
     "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}/transitions"
   ```
   If transition is unavailable, ask the user whether to continue.

4. Branch naming:
   - Bug issue type → `fix/RTCOLLABPLATFORM-{N}`
   - All others → `feat/RTCOLLABPLATFORM-{N}`

5. If already on the correct branch, skip branch creation.

6. Create and checkout branch from `--from` base (default: `origin/develop`):
   ```bash
   git fetch origin develop
   git checkout -b {branch} --no-track origin/develop
   git push -u origin HEAD
   ```

---

## Phase 2–3: Prompt and Plan (Planning Subagent)

Both phases run in a single planning subagent (`run_in_background: true`, `mode: "plan"`).

### Phase 2: Write prompt

Dispatch a planning subagent with:
> "Issue title: {title}. Issue description: {description}. Explore the codebase (Read, Glob, Grep) to understand the issue. Write a prompt describing what needs to be implemented. Do NOT write a plan. Do NOT modify code. Return only the prompt."

Show the returned prompt to the user and wait for approval before proceeding to Phase 3.

### Phase 3: Write plan

Resume the same planning subagent with the approved prompt:
> "Based on this approved prompt: {prompt} — write a detailed implementation plan."

The plan must be self-contained for the implementation subagent. Include:
- Absolute paths of all files to modify
- Specific locations within each file (class name, function name, line range)
- Exact changes with code snippets
- New files to create (path + purpose)
- Test file paths and test cases to add or modify
- Change order if dependencies exist

Self-review checklist (subagent verifies before returning):
- [ ] All modified file paths are absolute
- [ ] Each file's change location is specified
- [ ] Changes are described with code snippets
- [ ] New files include path and purpose
- [ ] Test cases are specified
- [ ] Change order is documented if order matters

The subagent returns the plan text. The main session saves it to:
`docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md`

Show the full plan to the user.

---

## Phase 4: Review plan

Ask the user (use `AskUserQuestion`):

| Option | Description |
|--------|-------------|
| **Review** (recommended) | Run `team-review --target plan --method subagent` |
| **Skip** | Proceed to implementation without review |

After review, update the plan file with any feedback.

Then append this section to the plan file:

```markdown
## Post-implementation steps

1. Run `team-review --target changes --method subagent` for code review.
2. Classify findings:
   - **Fix**: bugs or regressions introduced by this change
   - **Skip**: pre-existing issues, out-of-scope suggestions
3. Apply all Fix items. Re-run tests and lint to confirm they pass.
4. Commit fixes (amend or new commit).
```

Show the final plan to the user and confirm before proceeding.

---

## Phase 5: Implementation

Delegate to an implementation subagent via Agent tool:
- `model`: `--exec-model` value or `sonnet`
- `run_in_background: true`

Agent prompt must include:
- Full plan file content
- "Follow CLAUDE.md rules."
- "Commit all changes. Commit message: `[RTCOLLABPLATFORM-{N}] {issue title}`"
- "Run `./gradlew formatKotlin` before committing."
- "Run `./gradlew yorkie:testDebugUnitTest` and `./gradlew lintKotlin` to verify."
- "Return: (1) team-review output, (2) Fix/Skip classification, (3) summary of fixes applied."
- Debugging budget: "If tests or lint fail, attempt a fix at most 2 times per failure. After 2 failed attempts, stop and report the failure details (error, attempted fixes, suspected cause)."

### If subagent exhausts debug budget

Ask the user:

| Option | Description |
|--------|-------------|
| **Debug myself** | Main session analyzes the error and fixes it directly |
| **Continue subagent** | Resume same subagent with 2 more attempts |
| **Stop** | Keep current state, note failures in PR description |

---

## Phase 6: Post-implementation

1. Report the team-review output and Fix/Skip summary to the user.

2. Create a draft PR:
   Invoke the `create-pr` skill: `create-pr --jira RTCOLLABPLATFORM-{N} --draft`

3. Add a JIRA comment:
   ```bash
   curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
     -H "Content-Type: application/json" \
     -d "{\"body\": \"Implementation complete.\n\nApproach: {one-line summary from plan}\n\nKey fixes from review: {Fix items}\n\nPR: {PR URL}\"}" \
     "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}/comment"
   ```

4. Delete the plan file:
   ```bash
   rm docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md
   ```
```

- [ ] **Step 2: Commit**

```bash
git add .claude/skills/solve-jira/
git commit -m "chore: add adapted skill — solve-jira"
```

---

## Task 9: Agents (critic-reviewer, api-compat-checker, test-writer)

**Files:**
- Create: `.claude/agents/critic-reviewer.md`
- Create: `.claude/agents/api-compat-checker.md`
- Create: `.claude/agents/test-writer.md`

- [ ] **Step 1: Write `.claude/agents/critic-reviewer.md`**

```markdown
---
name: critic-reviewer
description: Primary code reviewer for yorkie-android-sdk. Finds CRDT invariant violations, concurrency safety issues, and bugs. Used standalone or by team-review skill.
model: inherit
effort: medium
tools: Glob, Grep, Read, Bash
---

You are the primary code reviewer for yorkie-android-sdk — an Android CRDT-based real-time
collaboration SDK. Documents are edited via JsonObject/JsonArray/JsonText/JsonTree (user-facing API)
backed by CrdtObject/CrdtArray/CrdtText/CrdtTree. Client manages server sync via Connect-RPC
push-pull loop.

Your job is to find **real issues**: CRDT invariant violations, concurrency bugs, API boundary
errors, and missing test coverage.

**Do NOT trust the implementer's claims. Read the actual code and verify independently.**
**Every concern MUST include a concrete fix.** Do not flag problems without solutions.
**Only report issues you are confident about (≥80%).** Fewer high-quality findings beat many weak ones.

## What to check

### CRDT invariants (highest priority)

- `TimeTicket` uniqueness: new operations must not reuse an existing lamport+actorID combination
- Tombstone tracking: deleted CRDT nodes must be registered in GC pairs — silent drops corrupt
  document state permanently
- Operation ordering: operations applied out of lamport clock order will produce divergent state
  across clients
- `RgaTreeSplit` splits: every split must update both the left node's `insPrevID` and the right
  node's parent reference — dangling references cause sync failures
- `VersionVector` consistency: the vector in a `ChangeID` must reflect the state at the time of
  the change, not the current document state

### Concurrency safety

- `Client` and `Document` each run on a dedicated single-threaded dispatcher — flag any call that
  escapes to `Dispatchers.IO`, `Dispatchers.Default`, or `GlobalScope`
- Attachment state in `Client` (the `attachments` map) must be accessed only while holding the
  mutex — flag reads or writes outside the mutex guard
- `StateFlow` emissions: emitting from a non-owned dispatcher can cause ordering issues — flag
  emissions that occur outside the document's own scope
- No `GlobalScope` usage; coroutine scope must be cancelled on `Client.deactivate()`

### Bugs & crashes

- Null safety at API boundaries: `!!`, `.first()` / `.last()` on potentially empty collections,
  unsafe casts
- Index/offset arithmetic in `IndexTree` and `SplayTreeSet` — off-by-one errors are common in
  tree path conversions
- Empty collection edge cases in CRDT operations (empty document, zero-length text edit)
- Silent failures: `runCatching` that discards the result, empty `catch` blocks
- Coroutine cancellation not handled in long-running sync loops

### Test coverage

- New CRDT operation logic with no unit tests — high severity
- New sync/push-pull code paths not covered by instrumented tests
- New public API methods not covered

## Output format

```
### Summary
One-line summary

### Concerns
- [Severity: High/Medium/Low] Specific concern (`file:line`)
  - **Impact**: Potential consequence
  - **Fix**: Concrete solution

### Suggestions
- Minor improvements (`file:line`)

### Verdict
Approved / Approved with conditions / Rejected (reason)
```

Include `file:line` only for lines you have directly read. Do not guess line numbers.
```

- [ ] **Step 2: Write `.claude/agents/api-compat-checker.md`**

```markdown
---
name: api-compat-checker
description: Checks public SDK API stability for yorkie-android-sdk. Triggered by team-review when Client, Document, JsonObject/Array/Text/Tree, or .proto files are in the diff.
model: sonnet
tools: Glob, Grep, Read, Bash
---

# api-compat-checker

## When to use

- `Client` or `Document` public method signatures changed
- `JsonObject`, `JsonArray`, `JsonText`, or `JsonTree` public API changed
- Any `.proto` file changed (field numbers, message names, service methods)
- Before creating a PR that touches the public SDK surface

## What to check

### Public method contract

Flag if any of the following change without a prior deprecation cycle:
- Method removed from `Client`, `Document`, `JsonObject`, `JsonArray`, `JsonText`, or `JsonTree`
- Method signature changed (parameter type, return type, parameter order)
- `suspend` added or removed from a previously stable method
- Default argument removed (breaks call sites that relied on the default)

Safe additions:
- New method with no conflict — safe, note it
- New optional parameter with a default value — safe, note it

### Protobuf field stability

Protobuf field number changes are wire-breaking regardless of Kotlin visibility:
```
// ❌ Breaking — changes wire format for all existing clients
message Change {
  int32 client_seq = 1;  // was field 2 in previous version
}
```

Flag if:
- Any field number changes in `.proto` files
- Any message name changes (breaks generated class names)
- Any service method name changes in `yorkie.v1.YorkieService`

### Config and data class stability

Classes exposed in the public API must not be `data class`:
```kotlin
// ✅ Regular class — stable API
class SyncMode(val mode: SyncType = SyncType.Realtime)

// ❌ data class — unstable copy() / componentN() for consumers
data class SyncMode(val mode: SyncType = SyncType.Realtime)
```

Flag if a public API class is changed to `data class`.

### Deprecation before removal

```kotlin
@Deprecated(
    message = "Use attachAsync(document, initialPresence) instead.",
    replaceWith = ReplaceWith("attachAsync(document, initialPresence)")
)
```

Flag removals that skip this step.

### Java interop

`@JvmOverloads` must be present on public methods with default parameters that Java consumers
may call. Flag missing `@JvmOverloads` on newly added or modified public methods with defaults.

## Output format

```
### API Changes
- [Breaking/Non-breaking] Description (`file:line`)
  - **Impact**: Who is affected and how
  - **Required action**: What must happen before this merges

### Safe Changes
- Items reviewed and confirmed safe
```
```

- [ ] **Step 3: Write `.claude/agents/test-writer.md`**

```markdown
---
name: test-writer
description: Writes unit and instrumented tests matching yorkie-android-sdk conventions — English backtick names, MockK, CoroutineRule, JUnit 4, Given-When-Then. Triggered when user requests test writing.
model: sonnet
tools: Glob, Grep, Read, Write, Bash
---

# test-writer

## When to use

- After implementing new CRDT operation or sync logic
- After fixing a bug (regression test)
- When coverage of an existing class needs improvement

## Two test targets

### Unit tests — `yorkie/src/test/kotlin/dev/yorkie/`

JVM only, no emulator or Docker required.

Run all: `./gradlew yorkie:testDebugUnitTest`
Run single: `./gradlew yorkie:testDebugUnitTest --tests "dev.yorkie.document.crdt.CrdtTreeTest"`

Focus: CRDT data structures, converters, utility classes (`IndexTree`, `SplayTreeSet`).
Use MockK to mock `YorkieService` and coroutine collaborators.

### Instrumented tests — `yorkie/src/androidTest/kotlin/dev/yorkie/`

Require Android emulator AND running Yorkie Docker server:
```bash
docker compose -f docker/docker-compose.yml up --build -d
./gradlew yorkie:connectedDebugAndroidTest
```

Focus: document sync, presence, schema validation, full client lifecycle.
Use `TestUtils`, `ApiUtils`, `JsonTestUtils` helpers from the same test directory.
Always create one `Client` per test; always call `client.detachDocument(doc)` and
`client.deactivateAsync()` in `@After`.

## Conventions

### Test naming — English backticks

```kotlin
@Test
fun `crdt tree split preserves left and right node references`() { ... }

@Test
fun `document update async applies operation to root`() { ... }

@Test
fun `client attach fails when document is already attached`() { ... }
```

### Given-When-Then structure

```kotlin
@Test
fun `json object set returns previous value`() {
    // given
    val document = mockk<Document>(relaxed = true)
    val root = mockk<JsonObject>(relaxed = true)
    every { root.getOrNull("key") } returns "old"

    // when
    val previous = root.getOrNull("key")

    // then
    assertEquals("old", previous)
}
```

### CoroutineRule — always use for coroutine tests

```kotlin
class DocumentTest {
    @get:Rule
    val coroutineRule = CoroutineRule()

    @Test
    fun `update async applies changes to crdt root`() = runTest {
        // given
        val document = mockk<Document>(relaxed = true)
        coEvery { document.updateAsync(any()) } just Runs

        // when
        document.updateAsync { root -> root["key"] = "value" }
        advanceUntilIdle()

        // then
        coVerify { document.updateAsync(any()) }
    }
}
```

### MockK patterns

```kotlin
val document = mockk<Document>(relaxed = true)
val client = mockk<Client>(relaxed = true)
coEvery { document.updateAsync(any()) } just Runs
coVerify { document.updateAsync(any()) }
coVerify(exactly = 0) { client.activateAsync() }
```

### JUnit 4 only

```kotlin
// ✅ JUnit 4
@Test fun `my test`() { }
@Before fun setUp() { }
@After fun tearDown() { }
@get:Rule val rule = CoroutineRule()

// ❌ JUnit 5 — not used in this project
@BeforeEach
@ExtendWith
```

## What to prioritize

1. CRDT edge cases: empty document, single-character text, zero-length ranges
2. Concurrent operations: two edits at the same position
3. Client lifecycle: activate → attach → detach → deactivate in both success and error paths
4. GC tombstone collection: verify deleted nodes are registered in GC pairs
```

- [ ] **Step 4: Verify all agents created**

```bash
ls .claude/agents/
# Expected: api-compat-checker.md  critic-reviewer.md  test-writer.md
```

- [ ] **Step 5: Commit**

```bash
git add .claude/agents/
git commit -m "chore: add yorkie-specific agents — critic-reviewer, api-compat-checker, test-writer"
```

---

## Task 10: Final verification

- [ ] **Step 1: Verify complete structure**

```bash
find .claude -type f | sort
```

Expected output:
```
.claude/agents/api-compat-checker.md
.claude/agents/critic-reviewer.md
.claude/agents/test-writer.md
.claude/hooks/check-docker-yorkie.sh
.claude/hooks/check-jira-token.sh
.claude/reference/git-strategy.md
.claude/reference/jira-confluence-api.md
.claude/rules/changelog-rule.md
.claude/rules/git-workflow.md
.claude/rules/kdoc-rule.md
.claude/rules/kotlin-style.md
.claude/rules/testing-patterns.md
.claude/settings.json
.claude/skills/create-jira/SKILL.md
.claude/skills/create-pr/SKILL.md
.claude/skills/solve-jira/SKILL.md
.claude/skills/team-review/SKILL.md
.claude/skills/todo-scan/SKILL.md
```

- [ ] **Step 2: Verify settings.json is valid JSON**

```bash
jq . .claude/settings.json > /dev/null && echo "valid JSON"
# Expected: valid JSON
```

- [ ] **Step 3: Verify hooks are executable**

```bash
ls -la .claude/hooks/
# Expected: both files show -rwxr-xr-x permissions
```

- [ ] **Step 4: Verify settings.local.json is gitignored**

```bash
git check-ignore -v .claude/settings.local.json
# Expected: .gitignore:N:.claude/settings.local.json
```
