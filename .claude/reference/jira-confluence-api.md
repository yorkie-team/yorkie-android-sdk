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
