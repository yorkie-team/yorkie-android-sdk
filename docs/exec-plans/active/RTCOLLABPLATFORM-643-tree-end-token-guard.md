---
id: 001
title: "Fix tree style divergence on concurrent split via End-token guard"
status: todo
priority: P1
domain: document/crdt
layer: document/crdt
depends_on: []
jira: RTCOLLABPLATFORM-643
js_reference: https://github.com/yorkie-team/yorkie-js-sdk/pull/1211
spec_path: docs/specs/001_RTCOLLABPLATFORM-643-tree-end-token-guard.md
created: 2026-04-21
---

# Exec Plan — RTCOLLABPLATFORM-643 Tree End-token Guard

## Problem Summary

`CrdtTree.style` and `CrdtTree.removeStyle` walk tree tokens (Start / Text / End) across a range
and apply attribute mutations on every visited token. When peer A styles an element while peer B
concurrently `SplitElement`s that same element, A's `style` replay visits the End token of the
original element — whose `insNextID` now points at B's freshly-created split sibling that is NOT
yet in A's `VersionVector`. The attribute lands on the wrong side of the split, producing tree
divergence between replicas. JS fixed this in PR #1211 (port of Go #1731) with an End-token guard.
Android carries the identical defect and this plan ports the guard exactly.

## Affected Files

| Path | Responsibility | Why in scope |
|------|---------------|--------------|
| `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt` | Add `hasUnknownSplitSibling`; guard `style`; guard + destructure fix in `removeStyle` | Only file JS PR touched; spec forbids touching others |
| `yorkie/src/test/kotlin/dev/yorkie/document/crdt/CrdtTreeStyleDivergenceTest.kt` | New unit test — reproduces concurrent split + style/removeStyle scenario on two in-memory replicas and asserts convergence | Scenarios 1–5 from spec |

Verified file:line anchors against current `CrdtTree.kt`:

- `style(...)` signature at lines 73–78; `traverseInPosRange` callback destructures as `(node, tokenType), _` at line 99 — tokenType already in scope.
- `removeStyle(...)` signature at lines 467–472; `traverseInPosRange` callback destructures as `(node, _), _` at line 487 — tokenType is dropped and must be captured.
- `insNextID: CrdtTreeNodeID?` declared on `CrdtTreeNode` at line 920.
- `nodeMapByID: TreeMap<CrdtTreeNodeID, CrdtTreeNode>` declared on `CrdtTree` at line 52 (private; accessible within class for the new helper).
- `TokenType` enum at `util/IndexTree.kt` line 385 — values `Start | End | Text`.
- `VersionVector.get(actorID: String): Long?` at `document/time/VersionVector.kt` line 26 — exact API the guard needs.

## Ticket Graph

```
T1 (helper)  ────►  T2 (style guard)  ────┐
                                          ├──►  T4 (unit test)
T1 (helper)  ────►  T3 (removeStyle)  ────┘
```

T4 depends on T1 + T2 + T3. T1 is the sole prerequisite for T2 and T3. T2 and T3 are independent
of each other and may be landed in either order within a single round.

---

## Ticket T1 — add `hasUnknownSplitSibling` to `CrdtTree`

- **File:** `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt`
- **Insert location:** just above `fun style(...)` at line 73 (matching JS placement above the `style` method).
- **Symbol:** `private fun hasUnknownSplitSibling(node: CrdtTreeNode, versionVector: VersionVector): Boolean`
- **Minimum diff concept:**
  - Return `false` when `node.insNextID` is null.
  - Look the sibling up in `nodeMapByID`; return `false` when it is missing or `isText`.
  - Fetch `knownLamport = versionVector.get(sibling.id.createdAt.actorID)`.
  - Return `knownLamport == null || knownLamport < sibling.id.createdAt.lamport`.
- **No behavioural wiring yet** — pure helper, added but not called until T2 / T3.
- **DoD (copied from spec):**
  - [ ] Signature matches `private fun hasUnknownSplitSibling(node: CrdtTreeNode, versionVector: VersionVector): Boolean`.
  - [ ] Uses existing `nodeMapByID` — no new field introduced.
  - [ ] Text-sibling branch returns `false` (matches JS `sibling.isText`).
  - [ ] Unknown actor branch returns `true` (matches JS `knownLamport === undefined`).
  - [ ] No parent-equality short-circuit (spec explicitly forbids — reparented siblings must still be detected).
  - [ ] `./gradlew lintKotlin` clean.
  - [ ] `./gradlew yorkie:testDebugUnitTest` still green (no behaviour change yet).
- **Rollback:** single-commit revert removes the private helper; nothing else depends on it until T2/T3 land.

## Ticket T2 — gate End token in `CrdtTree.style`

- **File:** `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt`
- **Edit location:** inside the `traverseInPosRange { (node, tokenType), _ -> ... }` lambda at line 99, before the existing `val actorID = node.createdAt.actorID` at line 100.
- **Symbol:** `fun style(...)` — body-only change, signature unchanged.
- **Minimum diff concept:**
  ```kotlin
  if (tokenType == TokenType.End &&
      versionVector != null &&
      hasUnknownSplitSibling(node, versionVector)
  ) {
      return@traverseInPosRange
  }
  ```
- **Ordering note:** guard runs BEFORE any `canStyle` / attribute-mutation logic, mirroring JS `tree.ts` ordering.
- **DoD:**
  - [ ] `versionVector == null` path skips the guard — legacy callers unchanged (spec scenario 5).
  - [ ] Non-End tokens (Start / Text) are never affected by the guard.
  - [ ] End token with KNOWN sibling lamport still runs the attribute loop unchanged (spec scenario 1 regression check).
  - [ ] End token with UNKNOWN sibling short-circuits with `return@traverseInPosRange`, so `diff` / `changes` / `gcPairs` see no entry for that token (spec scenario 2).
  - [ ] `./gradlew yorkie:testDebugUnitTest` remains green.
- **Depends on:** T1.
- **Rollback:** revert only the inserted `if` block; the T1 helper survives.

## Ticket T3 — gate End token in `CrdtTree.removeStyle` (with destructuring fix)

- **File:** `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt`
- **Edit locations:**
  - Line 487: change `traverseInPosRange(fromParent, fromLeft, toParent, toLeft) { (node, _), _ ->` to `{ (node, tokenType), _ ->` so `tokenType` is in scope.
  - Insert the guard as the first statement of the lambda, mirroring T2.
- **Symbol:** `fun removeStyle(...)` — body-only change, signature unchanged.
- **Minimum diff concept:** identical guard block to T2, placed before `val actorID = node.createdAt.actorID` at line 488.
- **DoD:**
  - [ ] Lambda destructures `tokenType` (not `_`) — required to even write the guard.
  - [ ] End token with unknown split sibling skips the `node.removeAttribute` loop AND the `TreeChange(RemoveStyle, ...)` emission (spec scenario 3).
  - [ ] `versionVector == null` path is a no-op guard (unchanged behaviour).
  - [ ] `./gradlew lintKotlin` passes (no unused-parameter warnings from the rename).
  - [ ] Existing removeStyle unit tests in `CrdtTreeTest` still pass.
- **Depends on:** T1.
- **Rollback:** revert the lambda rename + inserted `if` block in `removeStyle` only.

## Ticket T4 — unit test `CrdtTreeStyleDivergenceTest`

- **File (new):** `yorkie/src/test/kotlin/dev/yorkie/document/crdt/CrdtTreeStyleDivergenceTest.kt`
- **Template source:** mirror `CrdtTreeTest.kt` conventions — `issueTime()` / `issuePos()` helpers from `dev.yorkie.TestUtils`, `CrdtTreeElement` / `CrdtTreeText` factories, `CrdtTree.edit(Pair<Int,Int>, ...)` adapter pattern.
- **Replica fixture:** construct two independent `CrdtTree` instances (`replicaA`, `replicaB`) each seeded with `<root><p>helloworld</p></root>`. Give each replica its own `ChangeContext` so they mint distinct `ActorID`s (copy the pattern from `CrdtTreeTest` — instantiate a fresh `DummyContext` equivalent per replica).
- **Cross-apply helper:** the test class needs a small helper that applies operation results from one replica onto the other by replaying ops against the peer tree using the same `TimeTicket`s — model it on the pattern used in `GCTest.kt` if present, otherwise construct via direct `style` / `edit` calls with each replica's `VersionVector` supplied explicitly.
- **Test methods (Given-When-Then, backtick English names):**
  - `` `style on A and split on B converges after cross apply` `` — covers scenario 2. Assert `replicaA.toXml() == replicaB.toXml()` AND neither split sibling carries an unintended attribute on the End-token side. Also assert the KNOWN-vector path (scenario 1): a single-replica style still writes the attribute end-to-end.
  - `` `removeStyle on A and split on B converges after cross apply` `` — covers scenario 3. Seed both replicas with `bold=true` before the concurrent remove+split, then cross-apply and assert convergence + no orphan attribute on the split sibling.
  - `` `hasUnknownSplitSibling returns false for text sibling` `` — scenario 4. Construct a node whose `insNextID` points at a text node; assert the attribute loop still runs (indirectly — via `style` producing a change). This exercises the `isText` branch.
  - `` `style guard is a noop when versionVector is null` `` — scenario 5. Call `style(range, attrs, executedAt)` without the `versionVector` argument; assert attributes apply unchanged (legacy-path regression).
- **Assertion style:** JUnit 4 `assertEquals` / `assertTrue` / `assertFalse`; no MockK required (pure CRDT).
- **DoD:**
  - [ ] Four `@Test` methods above all pass.
  - [ ] `./gradlew yorkie:testDebugUnitTest --tests "dev.yorkie.document.crdt.CrdtTreeStyleDivergenceTest"` green.
  - [ ] File under 300 lines (Golden Principle: file-size rule).
  - [ ] No `println` / `System.out` (Golden Principle: logging rule).
  - [ ] Output line from the `--tests` run captured in the round report.
- **Depends on:** T1, T2, T3.
- **Rollback:** delete the new test file; no production code depends on it.

## Acceptance Criteria ↔ Tickets

| Spec DoD item | Satisfied by |
|---------------|--------------|
| `./gradlew yorkie:testDebugUnitTest` passes (all + new divergence test) | T4 (green run); T1/T2/T3 do not regress existing tests |
| `./gradlew lintKotlin` clean | T1 DoD, T2 DoD, T3 DoD, T4 DoD |
| `bash scripts/lint_architecture.sh` shows no NEW violations | T4 file-size check; T1–T3 add no imports |
| Exactly one Kotlin production file touched + one test file added | Scope of T1 / T2 / T3 limited to `CrdtTree.kt`; T4 adds one test file |
| Scenario 1 (happy path unchanged) | T2 DoD (known-lamport branch preserves behaviour); T4 test 1 second-half assertion |
| Scenario 2 (style + split convergence) | T2 + T4 test 1 |
| Scenario 3 (removeStyle + split convergence) | T3 + T4 test 2 |
| Scenario 4 (text sibling edge) | T1 DoD (`isText` branch); T4 test 3 |
| Scenario 5 (null versionVector) | T2/T3 guard short-circuit; T4 test 4 |
| Commit references `RTCOLLABPLATFORM-643` and JS `#1211` | Git workflow rule (commit-time) — out of code-change scope |

## Rollback Plan

Tickets are independent commits by design:

1. If T4 fails (test flaky or wrong): `git revert <T4 sha>` — leaves the production guard intact; a follow-up ticket rewrites the test only.
2. If T3 breaks a lint / existing test: `git revert <T3 sha>` — keeps T1 + T2 shipped; `removeStyle` path stays on pre-fix behaviour until T3 re-lands.
3. If T2 breaks a lint / existing test: `git revert <T2 sha>` — keeps T1 + T3 shipped; `style` path stays on pre-fix behaviour until T2 re-lands.
4. If T1 breaks the build: `git revert <T1 sha>` — forces auto-revert of T2/T3/T4 (they will not compile). Single-point rollback.

Because the spec fixes a divergence bug (wrong attribute landing on the wrong node), partial rollback is safe: reverting any of T2/T3 returns that call site to the previously-shipped (buggy) behaviour, which is strictly no worse than main.

## Risks / Open Questions

- None flagged from the spec; it already maps 1:1 to a merged JS PR with the guard lines quoted verbatim.
- Cross-apply helper in T4 is the only novel code (JS test fixtures don't port cleanly). The test file should lean on the existing `CrdtTreeTest` patterns rather than inventing new infrastructure — if a genuine fixture gap appears, raise it back to the orchestrator rather than adding a second test utility layer.
