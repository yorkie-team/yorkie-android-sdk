---
name: fix-bug-flow
description: |
  Bug-fix workflow from JIRA ticket to PR. Fetches the bug report, investigates root cause,
  writes a failing test (when possible), fixes the bug, and creates a PR.
  Use this whenever the user wants to fix a bug from a JIRA ticket, e.g. "fix bug 123",
  "fix-bug-flow 123", "fix-bug-flow RTCOLLABPLATFORM-123",
  "there's a bug in ticket 123 please fix it", "hotfix 123".
argument-hint: "[issue-number] [--from baseBranch] [--hotfix]"
---

## Progress Checklist

```
- [ ] Phase 1: Issue confirmed, branch created
- [ ] Phase 2: Investigation complete, root cause identified
- [ ] Phase 3: Fix implemented and verified
- [ ] Phase 4: PR created, JIRA updated
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

4. Determine the base branch and branch prefix:

   | Scenario | Flag / Detection | Base | Branch prefix | PR target |
   |----------|-----------------|------|---------------|-----------|
   | Normal bug | default | `origin/develop` | `fix/` | `develop` |
   | Release bug | `--from release/vX.X.X` | `origin/release/vX.X.X` | `fix/` | `release/vX.X.X` |
   | Hotfix | `--hotfix` | `origin/main` | `hotfix/` | `main` |

   If `--from` starts with `release/`, auto-detect as a release bug.
   If `--hotfix` is passed, base from `main` and use `hotfix/` prefix.

   Create the branch:
   ```bash
   git fetch origin {base}
   git checkout -b {prefix}RTCOLLABPLATFORM-{N}-{short-desc} --no-track origin/{base}
   git push -u origin HEAD
   ```
   Derive `{short-desc}` from the issue title: lowercase, hyphens, max 4 words.
   Store the PR target branch for use in Phase 4.

## Phase 2: Investigation

The goal is to understand *why* the bug happens and *where* in the code. Write findings
to a tracking plan so the work is documented.

### Step 1: Understand the bug

Read the JIRA description carefully. Identify:
- What is the expected behavior?
- What is the actual behavior?
- Are there reproduction steps, stack traces, or screenshots?

### Step 2: Locate the relevant code

Use the bug description to find the affected area. Typical investigation tools:
- Grep for error messages, class names, or method names mentioned in the ticket
- Read the relevant source files, trace the logic flow
- Check git blame / recent commits if the bug is a regression

### Step 3: Reproduce (when possible)

There are two paths depending on whether the bug can be reproduced with a unit test:

**Path A — Unit-testable bug:**
Write a failing test that demonstrates the bug. Run it to confirm it fails:
```bash
./gradlew yorkie:testDebugUnitTest --tests "dev.yorkie.{TestClass}"
```
This is the ideal path — a failing test proves the bug exists and later proves the fix works.

**Path B — Requires manual verification:**
If the bug involves UI, integration with a real server, multi-client sync, or device-specific
behavior, it cannot be reproduced via unit test. In this case:
- Document the reproduction steps in the plan
- Explain the root cause from code analysis (trace through the code to show where it breaks)
- The user will manually verify on a device after the fix is applied

### Step 4: Write the tracking plan

Save to `docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md`:

```markdown
# Bug Fix: [JIRA title]

> RTCOLLABPLATFORM-{N}: [title](https://jira.navercorp.com/browse/RTCOLLABPLATFORM-{N})

## Bug Summary
- **Expected**: ...
- **Actual**: ...

## Root Cause
[Explain what code path causes the bug and why]

## Reproduction
- [ ] Failing test: `{test class and method}` (or "Manual — see steps below")
- Reproduction steps (if manual): ...

## Fix Approach
[What will be changed and why]

## Files to Change
- `path/to/file.kt` — description of change
```

Show the plan to the user and wait for confirmation before proceeding.

## Phase 3: Fix and Verify

### Step 1: Implement the fix

Apply the fix as described in the plan. Follow CLAUDE.md rules — keep changes minimal
and focused on the bug. Do not refactor surrounding code.

### Step 2: Verify

Run the fast verification suite:
```bash
./gradlew formatKotlin
./gradlew yorkie:testDebugUnitTest lintKotlin
```

Then run instrumented tests against a local Yorkie server (required before PR):
```bash
docker compose -f docker/docker-compose.yml up --build -d
./scripts/config-yorkie-local-server.sh
./gradlew yorkie:connectedDebugAndroidTest
```

If a failing test was written in Phase 2, confirm it now passes.

If the bug is Path B (manual verification), tell the user:
> "The fix is applied. Please verify manually on device: [reproduction steps]"

Wait for the user to confirm before proceeding.

### Step 3: Commit

```bash
git add {changed files}
git commit -m "fix: {description of the fix}"
```

Debugging budget: max 2 fix attempts per test failure. After 2 failures, stop and
ask the user: **Debug myself** / **Continue** / **Stop**.

## Phase 4: Deliver

1. Create the PR:
   Invoke `create-pr --jira RTCOLLABPLATFORM-{N} --base {PR target branch}`.

2. Transition JIRA to "In Review" (transition id: `31`).

3. Add a JIRA comment summarizing:
   - Root cause
   - What was fixed
   - PR URL

4. Clean up the plan file:
   ```bash
   rm docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md
   ```
