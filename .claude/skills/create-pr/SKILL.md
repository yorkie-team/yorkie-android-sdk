---
name: create-pr
description: |
  Creates or updates a PR for yorkie-android-sdk with English body and JIRA integration.
  Auto-detects base branch from current branch name. Supports updating existing PRs.
  Triggered by "create PR", "make PR", "open PR", "pull request", "push and PR",
  "update PR", "update PR title", "update PR body".
argument-hint: "[--jira RTCOLLABPLATFORM-N] [--draft] [--base branch] [--skip-ai-review] [--update PR_NUMBER]"
---

## Mode Detection

- If `--update` is specified → **Update mode** (skip to Step 6)
- Otherwise → **Create mode** (Steps 1–5)

---

## Step 1: Analyze changes

1. Determine the base branch (in priority order):
   - `--base` argument if specified
   - Auto-detect from current branch name:

   | Branch pattern | Base | Reason |
   |----------------|------|--------|
   | `hotfix/*` | `main` | Hotfixes target main |
   | `feat/RTCOLLABPLATFORM-{N}-*` where a remote branch with same name exists | Check if PR already exists targeting a feature base branch | Sub-PR for large feature |
   | `fix/*` or `feat/*` | `develop` | Default workflow |
   | anything else | `develop` | Fallback |

   For sub-PRs: if the current branch was created from a feature base branch (e.g.,
   `feat/RTCOLLABPLATFORM-{sub}` branched from `feat/RTCOLLABPLATFORM-{parent}`),
   detect the parent feature branch as the base. Check by looking at the branch's
   upstream or by matching the parent ticket number from the branch name.

2. Verify base: `git rev-parse --verify {base}`. Stop with error if not found.
3. Collect: `git log {base}..HEAD --oneline` and `git diff {base}...HEAD`.
4. If no commits since base but uncommitted changes exist, commit all changes:
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

Title: `{Jira issue title}` (max 70 chars) or `{type}: {description}` if no ticket. Do NOT prefix the ticket number.

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
     --base {base} \
     [--draft] [--label skip-ai-review]
   ```
3. Auto-assign reviewers from repo contributors (exclude bots and self). Skip in draft mode.
4. Return the PR URL.

---

## Step 6: Update existing PR

Triggered by `--update PR_NUMBER` or when user says "update PR title/body".

1. Fetch the existing PR:
   ```bash
   gh pr view {PR_NUMBER} --json title,body,number,url
   ```
   Stop if PR does not exist.

2. Determine what to update:
   - If user specifies what to change (e.g., "update title to X") → apply that change
   - If no specific instruction → re-analyze changes (Step 1–3) and regenerate title and body

3. Show the updated title and body to the user. Ask "Update PR" or "Edit".

4. Apply the update:
   ```bash
   gh pr edit {PR_NUMBER} --title "{new_title}" --body "{new_body}"
   ```

5. Return the PR URL.
