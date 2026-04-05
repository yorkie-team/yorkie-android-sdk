# Task 1: Scaffold directories + settings files

**Files:**
- Create: `.claude/settings.json`
- Create: `.claude/settings.local.json`
- Modify: `.gitignore`

---

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p .claude/hooks .claude/agents .claude/rules .claude/skills/solve-jira .claude/skills/team-review .claude/skills/create-jira .claude/skills/create-pr .claude/skills/todo-scan .claude/reference
```

- [ ] **Step 2: Write `.claude/settings.json`**

```json
{
  "SessionStart": [
    {
      "matcher": "*",
      "hooks": [
        {
          "type": "command",
          "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/check-jira-token.sh"
        },
        {
          "type": "command",
          "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/check-docker-yorkie.sh"
        }
      ]
    }
  ],
  "PreToolUse": [
    {
      "matcher": "Bash",
      "hooks": [
        {
          "type": "command",
          "command": "command=$(echo \"$CLAUDE_TOOL_INPUT\" | jq -r '.command // empty') && if echo \"$command\" | grep -q 'jira.navercorp.com'; then if [ -z \"$JIRA_PERSONAL_TOKEN\" ]; then echo 'BLOCK: JIRA_PERSONAL_TOKEN is not set. Set it with: export JIRA_PERSONAL_TOKEN=<your-token>' >&2; exit 2; fi; fi"
        }
      ]
    }
  ]
}
```

- [ ] **Step 3: Write `.claude/settings.local.json`**

```json
{
  "permissions": {
    "allow": [
      "Bash(git status*)",
      "Bash(git log*)",
      "Bash(git diff*)",
      "Bash(git branch*)",
      "Bash(git show*)",
      "Bash(git fetch*)",
      "Bash(git pull*)",
      "Bash(git add*)",
      "Bash(git commit*)",
      "Bash(git checkout*)",
      "Bash(git stash*)",
      "Bash(git merge-base*)",
      "Bash(git rev-parse*)",
      "Bash(git worktree*)",
      "Bash(./gradlew*)",
      "Bash(ls*)",
      "Bash(cat*)",
      "Bash(head*)",
      "Bash(tail*)",
      "Bash(find*)",
      "Bash(grep*)",
      "Bash(echo*)",
      "Bash(pwd)",
      "Bash(mkdir*)",
      "Bash(cp*)",
      "Bash(mv*)",
      "Bash(chmod*)",
      "Bash(which*)",
      "Bash(jq*)",
      "Bash(gh pr*)",
      "Bash(gh issue*)",
      "Bash(gh api*)",
      "Bash(gh auth status*)",
      "Bash(docker*)",
      "Edit"
    ]
  }
}
```

- [ ] **Step 4: Add `settings.local.json` to `.gitignore`**

Open `.gitignore` and add this line if not already present:

```
.claude/settings.local.json
```

- [ ] **Step 5: Verify**

```bash
ls .claude/
# Expected: agents  hooks  reference  rules  settings.json  settings.local.json  skills
jq . .claude/settings.json
# Expected: valid JSON with no errors
```

- [ ] **Step 6: Commit**

```bash
git add .claude/settings.json .gitignore
git commit -m "chore: scaffold .claude/ config directory and settings"
```
