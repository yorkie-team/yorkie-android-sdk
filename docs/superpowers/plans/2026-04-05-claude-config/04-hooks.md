# Task 5: Hooks

Creates both session-start hooks under `.claude/hooks/`.

---

- [ ] **Step 1: Write `.claude/hooks/check-jira-token.sh`**

```bash
#!/bin/bash
# SessionStart hook: warn if JIRA_PERSONAL_TOKEN is not set.
# create-pr, todo-scan, and solve-jira skills call jira.navercorp.com and will fail without it.
if [ -z "$JIRA_PERSONAL_TOKEN" ]; then
    echo "WARNING: JIRA_PERSONAL_TOKEN is not set."
    echo "  Skills that call jira.navercorp.com (create-pr, todo-scan, solve-jira) will fail."
    echo "  Set it with: export JIRA_PERSONAL_TOKEN=<your-personal-token>"
fi
```

- [ ] **Step 2: Write `.claude/hooks/check-docker-yorkie.sh`**

```bash
#!/bin/bash
# SessionStart hook: warn if Docker or the Yorkie server container is not running.
# Instrumented tests (yorkie:connectedDebugAndroidTest) require a live Yorkie server.

if ! docker info > /dev/null 2>&1; then
    echo "WARNING: Docker is not running."
    echo "  Instrumented tests require the Yorkie server container."
    echo "  Start Docker, then run: docker compose -f docker/docker-compose.yml up --build -d"
    exit 0
fi

if ! docker ps --format '{{.Names}}' | grep -q 'yorkie'; then
    echo "WARNING: Yorkie server container is not running."
    echo "  Instrumented tests (connectedDebugAndroidTest) will fail without it."
    echo "  Start it with: docker compose -f docker/docker-compose.yml up --build -d"
fi
```

- [ ] **Step 3: Make hooks executable**

```bash
chmod +x .claude/hooks/check-jira-token.sh
chmod +x .claude/hooks/check-docker-yorkie.sh
```

- [ ] **Step 4: Verify shell syntax and permissions**

```bash
bash -n .claude/hooks/check-jira-token.sh && echo "ok"
bash -n .claude/hooks/check-docker-yorkie.sh && echo "ok"
# Expected: ok (twice)

ls -la .claude/hooks/
# Expected: both files show -rwxr-xr-x permissions
```

- [ ] **Step 5: Commit**

```bash
git add .claude/hooks/
git commit -m "chore: add session-start hooks — jira token and docker yorkie checks"
```
