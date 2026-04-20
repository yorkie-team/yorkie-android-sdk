---
name: new-feature-flow
description: |
  Feature development workflow from JIRA ticket to PR. Fetches the feature request,
  writes implementation plan, implements with team-review, and creates PR(s).
  For large features, creates subtask tickets and a base feature branch with sub-PRs.
  Use this whenever the user wants to implement a new feature from a JIRA ticket, e.g.
  "new-feature-flow 123", "implement feature 123", "add feature from ticket 123",
  "work on feature RTCOLLABPLATFORM-123".
argument-hint: "[issue-number] [--from baseBranch] [--exec-model opus|sonnet|haiku]"
---

## Execution Model

`--exec-model` sets the model for Phase 4 (implementation). Default: `sonnet`.

## Progress Checklist

```
- [ ] Phase 1: Issue confirmed, branch created
- [ ] Phase 2: Research complete, implementation plan written
- [ ] Phase 3: Plan reviewed and approved
- [ ] Phase 4: Implementation complete
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
   | Normal feature | default | `origin/develop` | `feat/` | `develop` |
   | From release | `--from release/vX.X.X` | `origin/release/vX.X.X` | `feat/` | `release/vX.X.X` |

   Create the branch:
   ```bash
   git fetch origin {base}
   git checkout -b feat/RTCOLLABPLATFORM-{N}-{short-desc} --no-track origin/{base}
   git push -u origin HEAD
   ```
   Derive `{short-desc}` from the issue title: lowercase, hyphens, max 4 words.
   Store the PR target branch for use in Phase 5.

## Phase 2: Research and Plan

The goal is to understand what needs to be built and write a detailed implementation plan.
Features are larger than bug fixes and benefit from upfront planning to avoid rework.

### Step 1: Explore the codebase

Investigate the Android SDK codebase to understand:
- Which existing classes/files are relevant
- What patterns are already used for similar features
- What tests exist for related functionality
- What the public API surface looks like

### Step 2: Write the prompt

Write a prompt describing what to implement, informed by the JIRA ticket and codebase
exploration. Show to user and wait for approval.

### Step 3: Write the implementation plan

Write a detailed plan including:
- Absolute file paths with specific locations (class/function/line)
- Exact code snippets for each change
- New files to create
- Test cases (unit and instrumented where applicable)
- Change order (which files to modify first)

Save to: `docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md`

### Step 4: Assess scope and split if needed

After the plan is written, assess whether the feature should be delivered as a single PR
or split into multiple PRs. A single PR is appropriate when changes are small and cohesive.
Split when:
- The plan touches many files across different concerns (e.g., CRDT layer + API layer + tests)
- The total change is large enough that reviewing a single PR would be difficult
- Parts of the feature can be merged and tested independently

**If splitting into multiple PRs:**

1. Document the PR split in the plan with clear boundaries for each PR.

2. Create subtask JIRA tickets for each PR using `create-jira`:
   ```
   /create-jira --type subtask --parent {N} --title "{PR description}"
   ```

3. The branch created in Phase 1 becomes the **base feature branch**. Each subtask
   gets its own branch off this base and PRs back to it:
   ```
   Base branch: feat/RTCOLLABPLATFORM-{N}-{short-desc}     (PR target: develop)
     ├─ Sub-branch 1: feat/RTCOLLABPLATFORM-{sub1}-{desc}  (PR target: base branch)
     ├─ Sub-branch 2: feat/RTCOLLABPLATFORM-{sub2}-{desc}  (PR target: base branch)
     └─ Sub-branch 3: feat/RTCOLLABPLATFORM-{sub3}-{desc}  (PR target: base branch)
   ```

4. After all sub-PRs are merged into the base branch, create a final PR from the base
   branch to the PR target (develop or release).

## Phase 3: Plan Review

Ask the user: **Review** (run `team-review --target plan --method subagent`) or **Skip**.

If reviewing:
1. Run team-review
2. Update plan with feedback
3. Confirm with user before proceeding

## Phase 4: Implementation

### Single PR flow

1. Implement the changes as described in the plan
2. Verify:
   ```bash
   ./gradlew formatKotlin
   ./gradlew yorkie:testDebugUnitTest lintKotlin
   ```
3. Commit and push

Delegate to implementation subagent if the feature is large:
- `model`: `--exec-model` value or `sonnet`
- `run_in_background: true`

Prompt must include:
- Full plan content
- "Follow CLAUDE.md rules."
- Commit message format: `feat: {description}`
- Run `./gradlew formatKotlin` before committing
- Run `./gradlew yorkie:testDebugUnitTest lintKotlin` to verify

### Multi-PR flow

For each subtask PR:

1. Create the sub-branch from the base feature branch:
   ```bash
   git checkout feat/RTCOLLABPLATFORM-{N}-{short-desc}
   git pull origin feat/RTCOLLABPLATFORM-{N}-{short-desc}
   git checkout -b feat/RTCOLLABPLATFORM-{subN}-{desc} --no-track origin/feat/RTCOLLABPLATFORM-{N}-{short-desc}
   ```

2. Implement the subtask changes

3. Verify:
   ```bash
   ./gradlew formatKotlin
   ./gradlew yorkie:testDebugUnitTest lintKotlin
   ```

4. Commit, push, and create PR targeting the **base feature branch**:
   ```
   /create-pr --jira RTCOLLABPLATFORM-{subN} --base feat/RTCOLLABPLATFORM-{N}-{short-desc}
   ```

5. Wait for user to merge the sub-PR

6. Switch back to base branch, pull latest, and continue to next subtask

Debugging budget: max 2 fix attempts per failure. After 2 failures, stop and
ask the user: **Debug myself** / **Continue subagent** / **Stop**.

## Phase 5: Deliver

### Single PR

1. Create the PR:
   Invoke `create-pr --jira RTCOLLABPLATFORM-{N} --base {PR target branch}`.

### Multi-PR (after all sub-PRs merged)

1. Create the final PR from the base feature branch to the PR target:
   Invoke `create-pr --jira RTCOLLABPLATFORM-{N} --base {PR target branch}`.

### Common steps

2. Transition JIRA to "In Review" (transition id: `31`).

3. Add a JIRA comment summarizing:
   - What was implemented
   - Key design decisions
   - PR URL(s) — include all sub-PRs if multi-PR

4. Clean up the plan file:
   ```bash
   rm docs/superpowers/plans/RTCOLLABPLATFORM-{N}.md
   ```
