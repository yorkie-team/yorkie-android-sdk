---
name: solve-jira
description: |
  JIRA ticket dispatcher. Fetches the issue, detects the type (bug, feature, refactor, etc.),
  and routes to the appropriate workflow skill. Use this when the user mentions a JIRA ticket
  number without specifying a workflow, e.g. "solve 42", "work on ticket 42",
  "RTCOLLABPLATFORM-42", "what should I do with ticket 123".
argument-hint: "[issue-number] [--from baseBranch] [--hotfix] [--exec-model opus|sonnet|haiku]"
---

## Step 1: Fetch and classify the issue

```bash
curl -s -H "Authorization: Bearer $JIRA_PERSONAL_TOKEN" \
  "https://jira.navercorp.com/rest/api/2/issue/RTCOLLABPLATFORM-{N}" \
  -o /tmp/jira-issue.json
```

Stop if issue does not exist.

Extract: `summary`, `issuetype.name`, `status.name`, `assignee.name`.

## Step 2: Detect the workflow type

Determine the workflow from the JIRA issue type and title keywords:

| Issue type | Title keywords | Workflow skill | Args to forward |
|------------|---------------|----------------|-----------------|
| Bug | — | `fix-bug-flow` | `{N} [--from] [--hotfix]` |
| Sub-task (parent is Bug) | "Fix" | `fix-bug-flow` | `{N} [--from] [--hotfix]` |
| Task / Story / Sub-task | "Add", "Implement", "Support", "Sync" | `new-feature-flow` | `{N} [--from] [--exec-model]` |
| Task / Story | "Refactor", "Clean", "Restructure" | `refactor-flow` | `{N} [--from] [--exec-model]` |
| Task / Story | "Migrate", "Remove", "Update", "Bump", "Config", "CI" | `new-feature-flow` | `{N} [--from] [--exec-model]` |

If the type is ambiguous, show the user the ticket summary and ask which workflow to use:
> "Ticket RTCOLLABPLATFORM-{N}: {summary}. Which workflow?"
> - **Bug fix** (`/fix-bug-flow {N}`)
> - **New feature** (`/new-feature-flow {N}`)
> - **Refactor** (`/refactor-flow {N}`)

## Step 3: Forward all arguments

Pass through any arguments the user provided (`--from`, `--hotfix`, `--exec-model`) to the
selected workflow skill. Invoke via the Skill tool:

```
/fix-bug-flow {N} [--from {base}] [--hotfix]
```
or
```
/new-feature-flow {N} [--from {base}] [--exec-model {model}]
```
or
```
/refactor-flow {N} [--from {base}] [--exec-model {model}]
```

The dispatched skill handles everything from branch creation to PR delivery.
