# Claude Configuration Implementation Plan — Index

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the full `.claude/` configuration for `yorkie-android-sdk` — agents, rules, skills, hooks, and settings — mirroring `zero-plugins-android` with yorkie-domain adaptations.

**Architecture:** All files live under `.claude/` in the repo root. Identical files are copied verbatim from `zero-plugins-android`; adapted files are modified copies; new files are written from scratch. No source code changes.

**Tech Stack:** Markdown (agents, rules, skills, references), Bash (hooks), JSON (settings).

**Spec:** `docs/superpowers/specs/2026-04-05-claude-config-design.md`

---

## Execution Order

| File | Tasks | Contents |
|------|-------|----------|
| `01-scaffold.md` | Task 1 | `.claude/settings.json`, `settings.local.json`, `.gitignore` |
| `02-rules.md` | Tasks 2–3 | All 5 rules under `.claude/rules/` |
| `03-references.md` | Task 4 | Both files under `.claude/reference/` |
| `04-hooks.md` | Task 5 | Both hooks under `.claude/hooks/` |
| `05-skills.md` | Tasks 6–8 | All 5 skills under `.claude/skills/` |
| `06-agents.md` | Task 9 + verify | All 3 agents + final structure verification |

Execute files in order. Each file is self-contained — read only the file for the task you are working on.

---

## Final `.claude/` Structure

```
.claude/
├── settings.json
├── settings.local.json              # gitignored
├── hooks/
│   ├── check-jira-token.sh
│   └── check-docker-yorkie.sh
├── agents/
│   ├── critic-reviewer.md
│   ├── api-compat-checker.md
│   └── test-writer.md
├── rules/
│   ├── kotlin-style.md
│   ├── kdoc-rule.md
│   ├── testing-patterns.md
│   ├── changelog-rule.md
│   └── git-workflow.md
├── skills/
│   ├── solve-jira/SKILL.md
│   ├── team-review/SKILL.md
│   ├── create-jira/SKILL.md
│   ├── create-pr/SKILL.md
│   └── todo-scan/SKILL.md
└── reference/
    ├── jira-confluence-api.md
    └── git-strategy.md
```
