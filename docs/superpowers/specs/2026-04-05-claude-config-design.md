# Claude Configuration Design for yorkie-android-sdk

**Date:** 2026-04-05
**Scope:** Define `.claude/` agents, skills, rules, hooks, and references for `yorkie-android-sdk`, mirroring `zero-plugins-android` with yorkie-domain adaptations.

---

## Context

`zero-plugins-android` has a mature Claude Code configuration. `yorkie-android-sdk` shares the same team, JIRA project (`RTCOLLABPLATFORM`), and git workflow, so the structure is mirrored. The key differences are:

- The SDK is more complex: CRDT correctness and public API stability are high-stakes concerns that warrant dedicated agent enforcement.
- Integration tests require a Docker-hosted Yorkie server — a second session-start hook is needed.
- The formatter is Kotlinter (`./gradlew formatKotlin`), not Spotless.

---

## File Structure

```
.claude/
├── settings.json
├── settings.local.json                  # gitignored, per-dev permission allowlist
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

---

## Agents

### `critic-reviewer`

Multi-layer code review agent dispatched by `team-review` on every review.

**Review layers (in priority order):**

1. **CRDT invariants**
   - `TimeTicket` uniqueness not broken by new operations
   - Tombstones registered in GC pairs; no silent drops
   - Operation ordering respects lamport clock progression
   - `RgaTreeSplit` node splits don't leave dangling parent/child references
   - `VersionVector` updates are consistent with `ChangeID`

2. **Concurrency safety**
   - `Client` and `Document` operations run on their dedicated single-threaded dispatcher
   - Mutex guards are held for all attachment state mutations in `Client`
   - `StateFlow` emissions happen on the correct coroutine scope
   - No shared mutable state accessed without synchronization

3. **General bugs**
   - Null safety at API boundaries
   - Index/offset arithmetic in `IndexTree` and `SplayTreeSet`
   - Empty collection edge cases in CRDT operations
   - Coroutine cancellation handled correctly

**Report format:** Summary → Concerns (with file:line) → Suggestions → Verdict.

---

### `api-compat-checker`

Validates public SDK surface stability. Dispatched by `team-review` when any of these are touched: `Client.kt`, `Document.kt`, `JsonObject.kt`, `JsonArray.kt`, `JsonText.kt`, `JsonTree.kt`, or any `.proto` file.

**Checks:**
- No removal or signature change on public methods without prior `@Deprecated` cycle
- Default arguments used to add parameters non-breakingly
- No protobuf field number changes (wire-breaking regardless of Kotlin visibility)
- Config or data classes exposed in the public API must not be data classes (constructor stability)
- `@JvmOverloads` present where needed for Java consumers

---

### `test-writer`

Writes tests following project conventions. Knows two test targets:

**Unit tests** (`src/test/kotlin/dev/yorkie/`):
- JVM only, no emulator or Docker needed
- MockK for mocking `YorkieService` and collaborators
- `CoroutineRule` + `runTest` + `advanceUntilIdle()` for async
- Focus: CRDT logic, converters, utility classes

**Instrumented tests** (`src/androidTest/kotlin/dev/yorkie/`):
- Require Yorkie Docker server (`docker compose -f docker/docker-compose.yml up --build -d`)
- Use `TestUtils`, `ApiUtils`, `JsonTestUtils` helpers
- Focus: document sync, presence, schema validation, full client lifecycle
- One `Client` per test; always detach and deactivate in teardown

**Conventions (both targets):**
- JUnit 4 only (no JUnit 5)
- Test names in English backticks, descriptive
- Given-When-Then structure
- `coEvery` / `coVerify` for suspend functions

---

## Rules

### `kotlin-style.md` *(identical to zero-plugins-android)*
PascalCase for all constant-like declarations: `const val`, singleton `val`, enum entries, sealed class objects.

### `kdoc-rule.md` *(identical)*
English-only, declarative tone. Brackets for class references (`[ClassName]`). KDoc above annotations. All tags (`@param`, `@return`, `@throws`) in declarative style.

### `testing-patterns.md` *(adapted)*
Same MockK + JUnit4 + `CoroutineRule` core. Added section: instrumented tests live in `src/androidTest/`, require Docker, use project test helpers. Unit tests cover CRDT logic; instrumented tests cover sync, presence, and schema.

### `changelog-rule.md` *(identical)*
Keep a Changelog format. Every entry must include `[RTCOLLABPLATFORM-N]`. Noun phrases, not verbs. Breaking changes prefixed `**Breaking**:`.

### `git-workflow.md` *(adapted — one change)*
Branch from `develop`. Branch naming: `feat/RTCOLLABPLATFORM-{N}-{desc}`, `fix/RTCOLLABPLATFORM-{N}-{desc}`, `chore/{desc}`. Commit format: `{type}: {description}`. **Run `./gradlew formatKotlin` before commits** (not `spotlessApply`). PR titles: `[RTCOLLABPLATFORM-N] {ticket title}`.

---

## Skills

### `solve-jira`
Identical to `zero-plugins-android`. Full 6-phase JIRA workflow: confirm issue → create branch → plan → implement (subagent) → PR → JIRA comment + cleanup.

### `team-review`
Same multi-agent orchestration. Always dispatches `critic-reviewer` + `test-writer`. Also dispatches `api-compat-checker` when `Client.kt`, `Document.kt`, `JsonObject/Array/Text/Tree.kt`, or `.proto` files are in the change set.

### `create-jira`
Identical to `zero-plugins-android`.

### `create-pr`
Same flow. Adapted: runs `./gradlew formatKotlin` (not `spotlessApply`) before auto-commit. PR targets `develop`.

### `todo-scan`
Identical to `zero-plugins-android`.

---

## Hooks

### `check-jira-token.sh` *(identical)*
Session-start. Warns if `JIRA_PERSONAL_TOKEN` is not set. Skills that call `jira.navercorp.com` will fail without it.

### `check-docker-yorkie.sh` *(new)*
Session-start. Checks:
1. Docker daemon is running
2. The `yorkie` container is up

If either check fails, prints a warning with the start command:
```
docker compose -f docker/docker-compose.yml up --build -d
```
Does not block — warning only, since most sessions don't run instrumented tests.

### `settings.json`
Both hooks wired to `SessionStart`. Same `PreToolUse` Bash guard: blocks commands touching `jira.navercorp.com` if `JIRA_PERSONAL_TOKEN` is unset.

---

## References

### `jira-confluence-api.md` *(identical)*
Curl reference for JIRA REST API: get/create/update issue, transitions, comments, issue links, project versions. All with `Authorization: Bearer $JIRA_PERSONAL_TOKEN`.

### `git-strategy.md` *(identical)*
Branch model (`main` ← `develop` ← feature/fix/chore), commit types, PR flow, release flow, hotfix flow.

---

## What is Identical vs. Adapted

| Item | Status |
|------|--------|
| `kotlin-style.md` | Identical |
| `kdoc-rule.md` | Identical |
| `changelog-rule.md` | Identical |
| `jira-confluence-api.md` | Identical |
| `git-strategy.md` | Identical |
| `create-jira` skill | Identical |
| `solve-jira` skill | Identical |
| `todo-scan` skill | Identical |
| `check-jira-token.sh` | Identical |
| `git-workflow.md` | Adapted — `formatKotlin` instead of `spotlessApply` |
| `testing-patterns.md` | Adapted — adds instrumented test guidance |
| `create-pr` skill | Adapted — `formatKotlin`, targets `develop` |
| `team-review` skill | Adapted — yorkie agents, `.proto` trigger |
| `critic-reviewer` agent | Adapted — CRDT invariants + concurrency layers |
| `api-compat-checker` agent | Adapted — SDK public surface + `.proto` field numbers |
| `test-writer` agent | Adapted — unit + instrumented test targets |
| `check-docker-yorkie.sh` | New |
