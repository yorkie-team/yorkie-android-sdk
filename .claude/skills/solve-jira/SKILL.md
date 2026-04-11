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

3. **JS sync check** — if the issue title contains a reference to `yorkie-js-sdk#` (e.g. `(yorkie-js-sdk#1099)`):
   - Fetch the PR's changed files:
     ```bash
     curl -s "https://api.github.com/repos/yorkie-team/yorkie-js-sdk/pulls/{PR_N}/files" \
       | python3 -c "import sys,json; [print(f['filename']) for f in json.load(sys.stdin)]"
     ```
   - If **all** changed files are outside `packages/sdk/src/` (e.g. only `packages/react/`, `examples/`, docs):
     - Inform the user: "This issue only touches the JS UI/integration layer — no Android SDK changes needed."
     - Transition issue to **Closed** (id: `61`) with resolution **Won't Fix** (id: `10500`):
       ```bash
       curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
         -H "Content-Type: application/json" \
         -d '{"transition":{"id":"61"},"fields":{"resolution":{"id":"10500"}}}' \
         "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}/transitions"
       ```
     - **Stop here.** Do not create a branch or proceed further.
   - If any changed files are under `packages/sdk/src/` → continue normally.

4. Check current status from issue `fields.status.name`:
   - `"In Progress"` → skip, no transition needed
   - `"To Do"` or `"Open"` → transition to "In Progress" (id: `21`):
     ```bash
     curl -s -X POST -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"transition": {"id": "21"}}' \
       "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}/transitions"
     ```
   - Any other status → ask user before transitioning.

5. Branch naming: Bug → `fix/RTCOLLABPLATFORM-{N}`, all others → `feat/RTCOLLABPLATFORM-{N}`

6. Create branch from `--from` base (default: `origin/develop`):
   ```bash
   git fetch origin develop
   git checkout -b {branch} --no-track origin/develop
   git push -u origin HEAD
   ```

## Phase 2–3: Prompt and Plan (Planning Subagent)

Both phases run in a single planning subagent (`run_in_background: true`, `mode: "plan"`).

**Phase 2a — JS SDK research (if applicable):** If the issue references a JS SDK PR (`yorkie-js-sdk#N`), or the feature exists in the JS SDK, spawn `yorkie-js-researcher` to research the JS implementation before planning. Include in the prompt:
- The JS PR number or feature name
- "Show the relevant JS implementation, key files, algorithm, and API surface"

Attach the researcher's output to the planning subagent's context.

**Phase 2b:** Subagent explores codebase and writes a prompt describing what to implement. Show to user, wait for approval.

**Phase 3:** Resume subagent with approved prompt to write detailed plan. Plan must include: absolute file paths, specific locations (class/function/line), exact code snippets, new files, test cases, change order. If JS research was done, reference the JS implementation and note any intentional deviations.

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
