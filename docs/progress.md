# Progress

Session-to-session state handoff.

## Last Session

**Date**: 2026-04-21
**Session**: harness adopt — generated CLAUDE.local.md, architecture lint, verify lanes, harness.config.json, work state, and docs scaffolding

### Completed
- `CLAUDE.local.md` — harness supplement to team `CLAUDE.md`
- `docs/ARCHITECTURE.md` — explicit two-layer rule, module boundaries
- `docs/GOLDEN_PRINCIPLES.md` — core + project-specific enforced rules
- `docs/specs/_template.md`, `docs/exec-plans/_template.md`
- `harness.config.json` — verify lanes, evaluator components (unit + instrumented)
- `scripts/lint_architecture.sh` — R1..R6 checks (CRDT/JSON split, proto boundary, file size, etc.)
- `scripts/verify.sh` — fast / full / entropy / session lanes
- `scripts/init_check.sh` — env smoke test
- `scripts/harness_check.sh`, `scripts/maintenance.sh`
- `.work/state.json` — initialised at `phase: idle`

### Next
- Run `bash scripts/verify.sh fast` to confirm the architecture linter is clean against current code.
- Write the first feature spec in `docs/specs/backlog/` (e.g., carry the peer-presence reconnect work from the current branch into an explicit spec).
- Kick off `/harness work` on that spec.

### Decisions Made
- Architecture linter implemented as bash + ripgrep/grep (not a Kotlin/KSP AST tool) for zero build impact.
- Unit and instrumented tests modelled as **two** evaluator components; instrumented requires `docker compose` + emulator.
- `.claude/settings.local.json` was NOT modified — it already contains team permissions and a secret (`JIRA_PERSONAL_TOKEN`). No auto-format PostToolUse hook was added; `./gradlew formatKotlin` is expected before commits (per `.claude/rules/git-workflow.md`).

### Blockers
- None.
