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
