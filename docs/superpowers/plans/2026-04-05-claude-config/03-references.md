# Task 4: References (identical)

Creates both files under `.claude/reference/`. Content is identical to `zero-plugins-android` except `git-strategy.md` uses `./gradlew formatKotlin` instead of `spotlessApply`.

---

- [ ] **Step 1: Write `.claude/reference/jira-confluence-api.md`**

```markdown
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
```

- [ ] **Step 2: Write `.claude/reference/git-strategy.md`**

```markdown
# Git Strategy

## Branch Model

```
main
  └── develop          ← integration branch; all feature PRs target here
        ├── feat/RTCOLLABPLATFORM-N-short-description
        ├── fix/RTCOLLABPLATFORM-N-short-description
        └── chore/short-description

release/vX.X.X         ← cut from develop when preparing a release
  └── hotfix/short-description   ← critical fixes on a live release
```

## Branch Naming

| Type | Pattern | Example |
|------|---------|---------|
| Feature | `feat/RTCOLLABPLATFORM-{N}-{short-desc}` | `feat/RTCOLLABPLATFORM-42-json-tree-move` |
| Bug fix | `fix/RTCOLLABPLATFORM-{N}-{short-desc}` | `fix/RTCOLLABPLATFORM-55-gc-tombstone-drop` |
| Chore / tooling | `chore/{short-desc}` | `chore/update-connect-rpc-dependency` |
| Release | `release/v{X.X.X}` | `release/v0.6.36` |
| Hotfix | `hotfix/{short-desc}` | `hotfix/null-crash-on-detach` |

- Use lowercase and hyphens only — no underscores, no camelCase.
- Keep descriptions short (2–4 words).
- When a JIRA ticket exists, include the ticket number.

## Commit Messages

Format: `{type}: {description}`

| Type | When to use |
|------|-------------|
| `feat` | New functionality |
| `fix` | Bug fix |
| `chore` | Tooling, config, dependency updates |
| `docs` | Documentation only |
| `test` | Adding or updating tests |
| `refactor` | Code change with no behaviour change |
| `task` | Non-feature work items (migration, cleanup) |

Rules:
- Lowercase after the colon, no period at the end
- Imperative mood: "add json tree move" not "added json tree move"
- Reference JIRA ticket in the body or PR title, not in every commit subject
- Run `./gradlew formatKotlin` before committing

## Pull Request Flow

```
feat/RTCOLLABPLATFORM-42  →  PR  →  develop  →  (release cut)  →  main
```

1. Branch off `develop`
2. Implement, test, lint (`./gradlew formatKotlin yorkie:testDebugUnitTest lintKotlin`)
3. Open PR targeting `develop`
4. Merge after review approval

## Release Flow

1. Cut `release/vX.X.X` from `develop`
2. Bump `VERSION_NAME` in `gradle.properties`, stabilise on release branch
3. Run: `./gradlew clean lintKotlin yorkie:testDebugUnitTest yorkie:jacocoDebugTestReport`
4. PR `release/vX.X.X` → `main`
5. Tag `vX.X.X` on `main` after merge
6. Merge `main` back into `develop`

## Hotfix Flow

1. Branch `hotfix/{desc}` from `main`
2. Fix and test
3. PR to `main`, then separately to `develop`

## Rules

- **Never commit directly to `main` or `develop`** — always via PR
- **Never force-push** to `develop`, `main`, or `release/**`
- **One logical change per PR** — split unrelated changes into separate branches
- **Delete branches after merge** — keep the remote clean
- **`./gradlew formatKotlin` before every push** — CI will reject unformatted code
```

- [ ] **Step 3: Verify**

```bash
ls .claude/reference/
# Expected: git-strategy.md  jira-confluence-api.md
```

- [ ] **Step 4: Commit**

```bash
git add .claude/reference/
git commit -m "chore: add reference docs — jira api and git strategy"
```
