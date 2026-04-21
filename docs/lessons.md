# Lessons

Patterns, anti-patterns, and decisions accumulated across sessions.
Categorized for actionability: patterns (follow), anti-patterns (avoid), decisions (respect).

The evaluator checks anti-patterns are not repeated in new code.

---

## 2026-04-21 — harness adopt

- **Decision**: Architecture enforcement done in bash + ripgrep, not as a Gradle task — we don't want the linter to depend on a Gradle configuration phase, and the rules are simple enough for grep.
- **Decision**: CRDT ↔ JSON separation is expressed as "R1" in `scripts/lint_architecture.sh`: `document/crdt/**` must not import `document/json/**`. Reversing this direction is never correct — the JSON layer wraps CRDT, not the other way round.
- **Decision**: Generated proto types (`dev.yorkie.api.v1.*`) are only allowed in `api/*Converter.kt`. Leaking proto into `document/**`, `core/**`, or `util/**` breaks the "proto is an I/O boundary" contract and makes future proto refactors much more expensive.
- **Pattern**: MockK + `CoroutineRule` + JUnit 4 + English backtick test names — existing `yorkie/src/test/` tests follow this; keep it consistent.
- **Pattern**: Each `Client`/`Document` pair owns a single-threaded coroutine dispatcher. Never share dispatchers across pairs. Never block.
