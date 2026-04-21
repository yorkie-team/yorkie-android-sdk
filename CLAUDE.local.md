# CLAUDE.local.md — Harness Engineering

This file supplements the team's `CLAUDE.md`. It is the agent's entry point for harness-driven work (specs → exec-plans → rounds → evaluator). Do NOT copy its rules into `CLAUDE.md`.

## Stack Summary

- Language: Kotlin 1.9.24 (JDK 17)
- Build: Gradle (Kotlin DSL) + `gradle/libs.versions.toml` + `build-logic/convention/`
- Android Gradle Plugin: 8.9.1
- Lint/Format: kotlinter (ktlint wrapper) + Android Lint
- Test: JUnit 4 + MockK + kotlinx-coroutines-test (unit); AndroidX Test + Espresso (instrumented)
- Coverage: JaCoCo
- RPC/Proto: Connect-Kotlin (gRPC) + buf plugin (protobuf-javalite)

## Key Commands

```bash
./gradlew yorkie:testDebugUnitTest            # Unit tests (JVM)
./gradlew yorkie:connectedDebugAndroidTest    # Instrumented tests (needs Yorkie server)
./gradlew lintKotlin                          # Kotlin lint (ktlint)
./gradlew formatKotlin                        # Format
./gradlew lint                                # Android lint
./gradlew yorkie:jacocoDebugTestReport        # Coverage
./gradlew build                               # Full build
bash scripts/verify.sh fast                   # lintKotlin + architecture lint
bash scripts/verify.sh full                   # fast + Android lint + unit tests + coverage
bash scripts/verify.sh session                # full + harness_check
bash scripts/init_check.sh                    # Smoke test: env, gradle, docker
docker compose -f docker/docker-compose.yml up --build -d  # Yorkie server for integration tests
./scripts/config-yorkie-local-server.sh       # Write YORKIE_SERVER_URL to local.properties
```

## Architecture (enforced)

Two-layer API inside `yorkie/src/main/kotlin/dev/yorkie/`:

```
yorkie/src/main/kotlin/dev/yorkie/
├── api/           # Protobuf converters (ChangeConverter, OperationConverter, ElementConverter, ...)
├── core/          # Client, sync loop, watch stream
├── document/
│   ├── crdt/      # INTERNAL CRDT implementations — never exposed
│   ├── json/      # PUBLIC JSON API wrappers (JsonObject, JsonArray, JsonText, JsonTree)
│   ├── operation/ # Operations (add, move, remove, edit, style, ...)
│   ├── change/    # Change, ChangeID, ChangePack
│   ├── time/      # TimeTicket, VersionVector
│   └── presence/  # Presence system
└── util/          # IndexTree, SplayTree, logging, ...
```

**Dependency rules (enforced by `scripts/lint_architecture.sh`):**
- `document/crdt/**` MUST NOT import `document/json/**` (CRDT is internal; JSON is public)
- `yorkie/` MUST NOT import `examples/**` or `microbenchmark/**`
- No `println`, `System.out`, `System.err` in `yorkie/src/main/**` (use Timber if logging is needed)
- Max 300 lines per Kotlin source file in `yorkie/src/main/kotlin/**` (excluding generated)

When adding a data operation: CRDT in `document/crdt/` → public wrapper in `document/json/` → `Operation` in `document/operation/` → proto converters in `api/OperationConverter.kt` + `api/ElementConverter.kt`.

## Documentation Map

| Document | Description |
|----------|-------------|
| `CLAUDE.md` | Team-owned project overview (do not edit here) |
| `CLAUDE.local.md` | Harness-specific agent instructions (this file) |
| `docs/ARCHITECTURE.md` | Layer rules, dependency direction, module boundaries |
| `docs/GOLDEN_PRINCIPLES.md` | Enforced code-hygiene rules for agents |
| `docs/specs/` | Feature specs (spec-driven development) |
| `docs/specs/backlog/` | Specs not yet promoted to active |
| `docs/exec-plans/active/` | In-progress exec-plans |
| `docs/exec-plans/completed/` | Archived exec-plans |
| `docs/round-reports/` | Build/QA/fix/improvements per round |
| `docs/progress.md` | Session-to-session handoff |
| `docs/lessons.md` | Patterns, anti-patterns, decisions |
| `.work/state.json` | Work state — owned by `/harness work` |

## Coding Conventions

1. Public API is only `dev.yorkie.core.Client`, `dev.yorkie.document.Document`, and the `dev.yorkie.document.json.*` types. Internal CRDT types MUST NOT appear in a public signature.
2. Use structured logging via `dev.yorkie.util.YorkieLogger` (or Timber in examples). No `println`, `System.out`, `System.err` in production code.
3. All external/network data validated at the `api/*Converter.kt` boundary — never trust proto fields downstream.
4. File size limit: 300 lines per Kotlin source file. Split when exceeded.
5. Concurrency: every `Client`/`Document` pair uses a dedicated single-threaded coroutine dispatcher — do not share dispatchers across pairs, do not block on them.
6. Test conventions: MockK for mocking, `CoroutineRule` for coroutine tests, JUnit 4, English backtick method names, Given-When-Then bodies.

## Session Start Ritual

1. Read `docs/progress.md` (what was done last session, what's next)
2. Read `docs/lessons.md` (patterns / anti-patterns / decisions)
3. Check `docs/exec-plans/active/` — resume active work if any
4. Run `bash scripts/init_check.sh` to verify env (JDK 17, gradlew, docker, local.properties)

## Session End Ritual

1. Run `bash scripts/verify.sh full` — must be clean
2. Update checkpoint in the active exec-plan
3. Update `docs/progress.md`
4. Capture new patterns/anti-patterns in `docs/lessons.md`
5. Run `bash scripts/harness_check.sh`
6. Commit all changes (see `.claude/rules/git-workflow.md` for branch/commit format)

## Pre-Implementation Gate (HARD RULE)

Before writing ANY code, verify:
1. `docs/exec-plans/active/` has tickets for the current work
2. `.work/state.json` shows `phase: "executing"` with an `active_ticket`
3. A spec exists in `docs/specs/` for the feature being built

If any condition is missing — STOP and create it first. If it gets a git commit, it gets a ticket.

## Work Lifecycle

```
Spec → Exec-Plan → Tickets → [Pick → Implement → Test → Verify DoD] → Complete
  ↑                                                                       │
  └── Discovery? Update spec first ←──────────────────────────────────────┘
```

- Spec states: `backlog/` → `docs/specs/` (active) → deprecated
- Ticket states: `todo` → `in_progress` → `done` | `blocked`
- 3 failures on the same ticket → mark blocked, escalate to user

## Definition of Done (every ticket)

- [ ] `./gradlew yorkie:testDebugUnitTest` passes
- [ ] `./gradlew lintKotlin` clean (and `./gradlew lint` for Android lint)
- [ ] `bash scripts/lint_architecture.sh` clean
- [ ] Implementation matches spec in `docs/specs/`
- [ ] For sync/watch/CRDT changes: corresponding instrumented test added or updated in `yorkie/src/androidTest/`
- [ ] Evidence captured in the exec-plan ticket

## Harness Directive

Use `/harness` for all feature work — specs, planning, implementation, evaluation. Do not write code outside the harness work cycle unless the change is a trivial chore explicitly opted out of tracking.
