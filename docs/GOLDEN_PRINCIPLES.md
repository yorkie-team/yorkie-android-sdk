# GOLDEN_PRINCIPLES.md — yorkie-android-sdk

Rules for agents working in this codebase. All rules are enforced — not advisory.

> **Note for evaluators (Stage 4):** When proposing harness improvements, append new rules under the `## Project Rules` or `## Evaluator-Discovered Rules` section. Do not modify the `## Core Rules` section.

---

## Core Rules (apply to every project)

### File Size
- **Max 300 lines per file.** If a file exceeds this, split it.
  - Enforcement: `scripts/lint_architecture.sh` file size check
  - Reason: Large files cause context overload and make the agent lose track of structure

### Logging
- **No `println`, `System.out`, or `System.err` in production code.** Use `dev.yorkie.util.YorkieLogger` (or Timber in examples).
  - Enforcement: architecture linter forbidden-pattern check
  - Reason: Unstructured output is invisible to observability tools

### Validation
- **All external data validated at entry points.** Proto messages are validated in `api/*Converter.kt`; never trust proto fields downstream.
  - Enforcement: evaluator checks — converters validate before handing domain code
  - Reason: Validation inside domain code = validation skipped when called internally

### Error Handling
- **No silent failures.** Every error is either thrown, returned via `Result`, or logged with context.
  - Enforcement: evaluator adversarial tests (what happens when X fails?)
  - Reason: Silent failures cause Stage 2 adversarial failures

### Testing
- **Tests written in the same round as the code.** No "I'll add tests later."
  - Enforcement: evaluator auto-fails on Testing = 0
  - Reason: Tests written after the fact optimize for the code that exists, not the spec

### Spec Compliance
- **Code matches the spec.** If implementation diverges, update the spec first.
  - Enforcement: evaluator Stage 3 spec fidelity check
  - Reason: Undocumented behavior is invisible to the next agent

---

## Project Rules

### Architecture
- **Two-layer rule.** `document/crdt/**` MUST NOT import `document/json/**`. CRDT is internal, JSON is public.
- **No upward imports from `util/**`.** Utilities stay pure; they don't know about `document`, `core`, or `api`.
- **Protobuf is an I/O boundary.** Proto types are allowed in `api/*Converter.kt` and in `core/**` (the Connect-RPC Client). Pure domain code (`document/**`, `util/**`) MUST NOT import generated proto classes.
- **`yorkie/` never depends on `examples/**` or `microbenchmark/**`.**
- Enforcement: `scripts/lint_architecture.sh`

### Naming
- Kotlin files: PascalCase matching the primary class (e.g., `CrdtObject.kt`, `JsonArray.kt`).
- Unit test methods: English, backtick-quoted, Given-When-Then (e.g., `` `should push local change before pulling remote`()``).
- Packages: lowercase, no underscores — `dev.yorkie.document.crdt`, `dev.yorkie.document.json`.

### Stack-Specific
- **Public API surface is fixed.** Only `Client`, `Document`, `JsonObject`, `JsonArray`, `JsonText`, `JsonTree`, `JsonCounter`, `JsonPrimitive`, and the `presence.*` types are intended for SDK consumers. A public signature must never return, accept, or expose a CRDT type.
- **Concurrency discipline.** Each `Client`/`Document` pair has a single-threaded coroutine dispatcher; do not share dispatchers across pairs, do not block on them, do not touch CRDT state from another dispatcher.
- **MockK for mocks, CoroutineRule for coroutines.** Do not introduce a second mocking or coroutine-test framework.
- **CRDT invariants.** Changes to CRDT data structures require proof — instrumented tests for concurrent editing scenarios must accompany any fix in `document/crdt/**` (see `critic-reviewer` and `api-compat-checker` agents in `.claude/agents/`).
- **Proto changes trigger converter updates.** Any modification under `yorkie/proto/yorkie/v1/` must be mirrored in `api/*Converter.kt` — enforced by the `protobuf-converter-checker` agent during review.

---

## Evaluator-Discovered Rules

> Rules added here by Stage 4 harness improvement proposals.
> Each rule includes: the bug it prevents, the round it was discovered, and the POC evidence.

<!-- Stage 4 appends here. Format:
### {rule name} (added round-{N}, {date})
- **Rule:** {specific rule}
- **Prevents:** {what bug or pattern this prevents}
- **Evidence:** {what happened in round N that triggered this}
- Enforcement: {how to verify — evaluator check, linter rule, etc.}
-->
