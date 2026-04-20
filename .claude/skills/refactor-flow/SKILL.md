---
name: refactor-flow
description: |
  Refactoring workflow from JIRA ticket to PR. Fetches the refactoring task, analyzes current
  code, ensures tests pass before changes, refactors with zero behavior change, and creates PR.
  Use this whenever the user wants to refactor code from a JIRA ticket, e.g.
  "refactor-flow 123", "refactor ticket 123", "clean up code for ticket 123",
  "restructure the code in RTCOLLABPLATFORM-123".
argument-hint: "[issue-number] [--from baseBranch] [--exec-model opus|sonnet|haiku]"
---

## Execution Model

`--exec-model` sets the model for Phase 4 (implementation). Default: `sonnet`.

## Progress Checklist

```
- [ ] Phase 1: Issue confirmed, branch created
- [ ] Phase 2: Analysis complete, refactoring plan written
- [ ] Phase 3: Plan reviewed and approved
- [ ] Phase 4: Refactoring complete, all tests still pass
- [ ] Phase 5: PR created, JIRA updated
```

## Phase 1: Setup

1. Fetch the JIRA issue:
   ```bash
   curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
     "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}" \
     -o /tmp/jira-issue.json
   ```
   Extract: summary, description, status, assignee. Stop if issue does not exist.

2. Verify assignee matches current user (`GET /rest/api/2/myself`). Stop if assigned to someone else.

3. Transition to "In Progress" if status is "To Do" or "Open" (transition id: `21`).
   If already "In Progress", skip. Any other status — ask user first.

4. Determine the base branch:

   | Scenario | Flag | Base | Branch prefix | PR target |
   |----------|------|------|---------------|-----------|
   | Normal refactor | default | `origin/develop` | `refactor/` | `develop` |
   | From release | `--from release/vX.X.X` | `origin/release/vX.X.X` | `refactor/` | `release/vX.X.X` |

   Create the branch:
   ```bash
   git fetch origin {base}
   git checkout -b refactor/RTCOLLABPLATFORM-{N}-{short-desc} --no-track origin/{base}
   git push -u origin HEAD
   ```
   Derive `{short-desc}` from the issue title: lowercase, hyphens, max 4 words.

## Phase 2: Analysis and Plan

Refactoring means changing code structure without changing behavior. The plan needs to
capture what the code looks like now, what it should look like after, and why the change
improves things — while proving nothing breaks.

### Step 1: Baseline test run

Run the test suite before making any changes. This establishes the green baseline that
the refactoring must preserve:
```bash
./gradlew yorkie:testDebugUnitTest lintKotlin
```

Also capture an instrumented-test baseline (critical for refactors that touch sync,
presence, or CRDT paths):
```bash
docker compose -f docker/docker-compose.yml up --build -d
./scripts/config-yorkie-local-server.sh
./gradlew yorkie:connectedDebugAndroidTest
```

Record both results. If tests already fail, stop and tell the user — refactoring on a
broken baseline is risky.

### Step 2: Understand the current code

Read the JIRA description to understand what needs refactoring and why. Then explore
the relevant code:
- Read the files mentioned in the ticket
- Understand the current structure, dependencies, and public API surface
- Identify what tests cover the code being refactored

### Step 3: Write the refactoring plan

Save to `docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md`:

```markdown
# Refactor: [JIRA title]

> RTCOLLABPLATFORM-{N}: [title](https://jira.navercorp.com/browse/RTCOLLABPLATFORM-{N})

## Current State
[Describe the current code structure and why it needs refactoring]

## Goal
[What the code should look like after refactoring]

## Approach
[Step-by-step description of changes]

## Behavior Guarantee
- Baseline tests: all passing (N tests)
- Public API changes: none / list any intentional changes
- No new functionality added

## Files to Change
- `path/to/file.kt` — description of change

## Test Coverage
- Existing tests covering this code: [list test classes]
- Additional tests needed: [if any structural changes require new tests]
```

Show the plan to the user and wait for confirmation.

## Phase 3: Plan Review

Ask the user: **Review** (run `team-review --target plan --method subagent`) or **Skip**.

If reviewing:
1. Run team-review
2. Update plan with feedback
3. Confirm with user before proceeding

## Phase 4: Implementation

### Step 1: Refactor

Apply the changes described in the plan. Key principles:
- Make small, incremental commits — each commit should leave tests passing
- Do not add new features or fix bugs as part of the refactoring
- If you discover a bug while refactoring, note it but don't fix it — that's a separate ticket

Delegate to implementation subagent if the refactoring is large:
- `model`: `--exec-model` value or `sonnet`
- `run_in_background: true`

Prompt must include:
- Full plan content
- "Follow CLAUDE.md rules."
- "This is a refactor — zero behavior change. All existing tests must still pass."
- Commit message format: `refactor: {description}`
- Run `./gradlew formatKotlin` before committing
- Run `./gradlew yorkie:testDebugUnitTest lintKotlin` to verify after each commit
- Run instrumented tests before handing off: `docker compose -f docker/docker-compose.yml up --build -d && ./scripts/config-yorkie-local-server.sh && ./gradlew yorkie:connectedDebugAndroidTest`

### Step 2: Final verification

Run the fast suite:
```bash
./gradlew formatKotlin
./gradlew yorkie:testDebugUnitTest lintKotlin
```

Then run the instrumented suite against a local Yorkie server:
```bash
docker compose -f docker/docker-compose.yml up --build -d
./scripts/config-yorkie-local-server.sh
./gradlew yorkie:connectedDebugAndroidTest
```

Compare with the baseline from Phase 2 Step 1 — the same number of tests should pass
in both suites. If any test that previously passed now fails, the refactoring introduced
a regression. Fix it before proceeding.

### Step 3: Commit and push

```bash
git add {changed files}
git commit -m "refactor: {description}"
git push
```

Debugging budget: max 2 fix attempts per test failure. After 2 failures, stop and
ask the user: **Debug myself** / **Continue subagent** / **Stop**.

## Phase 5: Deliver

1. Create the PR:
   Invoke `create-pr --jira RTCOLLABPLATFORM-{N} --base {PR target branch}`.

2. Transition JIRA to "In Review" (transition id: `31`).

3. Add a JIRA comment summarizing:
   - What was refactored and why
   - Confirmation that all tests pass (before and after)
   - PR URL

4. Clean up the plan file:
   ```bash
   rm docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md
   ```
