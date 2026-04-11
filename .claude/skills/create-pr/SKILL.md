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
     --base develop \
     [--draft] [--base {base}] [--label skip-ai-review]
   ```
3. Auto-assign reviewers from repo contributors (exclude bots and self). Skip in draft mode.
4. Return the PR URL.
