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

---

## 2026-04-21 — round-2 RTCOLLABPLATFORM-643

- **Anti-pattern**: Using Kotlin data-class `copy(...)` to clone CRDT nodes that carry internal mutable state (`Rht`, `MutableList`, `MutableMap`). The copy is shallow — `val`-typed properties with mutable values still share references. Two "separate" nodes then mutate each other's state. Evidence: `CrdtTreeNode.clone()` at `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt:971-979` shares `_attributes` with the original. Fix pattern: always name every mutable-content property in `copy(...)` and pair with `.deepCopy()` / `.toMutableList()`. Cross-check: `CrdtTreeNode.deepCopy()` at `CrdtTree.kt:1034-1056` shows the correct pattern.
- **Anti-pattern**: Tautological assertions in tests (`assertTrue(list.size >= 0)`, `assertNotNull(nonNullTypedValue)`). They assert nothing the compiler/runtime doesn't already guarantee. Replace with an assertion on the actual property (exact size, specific content), or remove it and rely on the "test does not throw" contract.
- **Decision**: Porting a JS fix with file:line anchors in the spec cut a full planning round — the evaluator only needed one build + one QA to reach 13/14. Keep "JS reference + file:line pointers" in the spec for every sync-up ticket, not just "behaviour matches".
