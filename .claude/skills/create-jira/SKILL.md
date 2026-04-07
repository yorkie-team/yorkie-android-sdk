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
