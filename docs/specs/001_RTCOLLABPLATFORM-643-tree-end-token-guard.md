---
status: active
chain: false
created: 2026-04-21
updated: 2026-04-21
jira: RTCOLLABPLATFORM-643
js_reference: https://github.com/yorkie-team/yorkie-js-sdk/pull/1211
epic: RTCOLLABPLATFORM-633
---

# Feature: Fix tree style divergence on concurrent split via End-token guard (port JS #1211)

## Problem

When two clients concurrently (a) apply a `style` / `removeStyle` to a tree element range and (b) split the element via `SplitElement`, the style operation can land spuriously on one of the split siblings and cause CRDT-tree divergence between replicas.

The root cause is that a `style` traversal visits *tokens*. An element emits a Start token and an End token. When a concurrent `SplitElement` has run, the original element's `insNextID` points at a newly-created split sibling. The End token of the original node now sits on the boundary between the two siblings. A styling client whose `VersionVector` does not yet know about the split will still replay its style over the post-split tree, hit the End token of the original, and apply attributes the originating client never intended — because from the originating client's perspective the split didn't exist yet.

JS has already fixed this in `yorkie-js-sdk#1211` (merged 2026-04-10, itself a port of Go `yorkie#1731`). Android has the same defect.

## Scope

**In scope**
- Port the End-token guard from `packages/sdk/src/document/crdt/tree.ts` to `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt`:
  - Add `private fun hasUnknownSplitSibling(node: CrdtTreeNode, versionVector: VersionVector): Boolean`.
  - In `CrdtTree.style(...)`: after the existing guard, skip the attribute-set loop when `tokenType == TokenType.End` AND `hasUnknownSplitSibling(node, versionVector)`.
  - In `CrdtTree.removeStyle(...)`: capture `tokenType` from the `traverseInPosRange` callback (currently destructured as `(node, _)`), then apply the same guard before the attribute-remove loop.
- Add a CRDT unit test in `yorkie/src/test/kotlin/dev/yorkie/document/crdt/` that reproduces the concurrent style + split scenario and asserts convergence between two replicas after cross-apply.

**Out of scope**
- Any refactoring of `CrdtTree` beyond the two call sites.
- Splitting `CrdtTree.kt` (1133 lines — tracked separately in `docs/specs-backlog.md`).
- Changes to `IndexTree.kt`, `RgaTreeSplit.kt`, or any other file — the JS PR touched exactly one file (tree.ts); the Android port should mirror that.
- Instrumented (androidTest) coverage — the JS reference did not add one; the Go PR this came from (yorkie#1731) covers the scenario in server-side tests; a unit test at the CRDT layer is sufficient.

## Background (key snippets from JS #1211)

From `packages/sdk/src/document/crdt/tree.ts`:

```ts
// helper — lines 1004-1035
private hasUnknownSplitSibling(
    node: CRDTTreeNode,
    versionVector: VersionVector,
): boolean {
    if (node.insNextID === undefined) return false;
    const sibling = this.nodeMapByID.get(node.insNextID);
    if (sibling === undefined || sibling.isText) return false;
    const knownLamport = versionVector.get(sibling.id.createdAt.actorID);
    return knownLamport === undefined
        || knownLamport < sibling.id.createdAt.lamport;
}
```

```ts
// guard — used at the top of the attribute-apply block inside style() and removeStyle()
if (tokenType === TokenType.End
    && versionVector !== undefined
    && this.hasUnknownSplitSibling(node, versionVector)) {
    return;
}
```

Note: `hasUnknownSplitSibling` deliberately omits the parent-equality check that `advancePastUnknownSplitSiblings` uses — in multi-level splits the sibling may have been reparented, yet `insNextID` remains the authoritative split witness.

## Scenarios

1. **Happy path (no concurrency):** a single client applies a style over a range — behavior unchanged, the guard never fires because `insNextID` is null or the sibling is already in the version vector.
2. **Concurrent split before style (the bug):**
   - A: `<p>hello world</p>`, styles the whole paragraph `bold=true`.
   - B: concurrently splits at the space → `<p>hello</p><p>world</p>`.
   - After cross-apply, both replicas must agree on the final tree shape and attribute set. Pre-fix, A's style lands spuriously on an End token; post-fix, the End token with an unknown split sibling is skipped.
3. **Concurrent split before `removeStyle`:** same shape as (2) but starting with `bold=true` and applying `removeStyle("bold")`. Today's code discards `tokenType` entirely in `removeStyle` — the guard can't even be written without first preserving `tokenType` from the callback.
4. **Edge — text sibling:** if `insNextID` points at a text node (e.g., a text split), `hasUnknownSplitSibling` returns false immediately. Important: the JS version checks `sibling.isText` explicitly; the Android port must match.
5. **Edge — `versionVector` is null** (older protocol paths): the guard does not fire (matches JS behavior). No crash, no divergence.

## Implementation Plan (what the generator should produce)

1. **`hasUnknownSplitSibling`** — add as a private method on `CrdtTree`, above `style`. Parameters: `node: CrdtTreeNode`, `versionVector: VersionVector`. Uses `nodeMapByID` (already exists) to resolve `node.insNextID`. Returns false for null sibling, text sibling, or known lamport.
2. **`style(...)` guard** — locate the `traverseInPosRange { (node, tokenType) -> ... }` lambda. After the existing preconditions (including the `canStyle` checks on the node) but before mutating the attribute map, insert:
    ```kotlin
    if (tokenType == TokenType.End &&
        versionVector != null &&
        hasUnknownSplitSibling(node, versionVector)) {
        return@traverseInPosRange
    }
    ```
3. **`removeStyle(...)` guard** — change the lambda from `{ (node, _) -> ... }` to `{ (node, tokenType) -> ... }` and insert the same guard before the attribute-remove loop.
4. **Test** — `CrdtTreeStyleDivergenceTest` (file name tentative — follow existing tree-test conventions). Given two `CrdtTree` replicas with the same initial tree, apply a style on replica A and a split on replica B concurrently; sync both; assert `toXml()` / structural equality is identical between A and B, and that the split sibling does not acquire an unintended attribute.

## Definition of Done

- [ ] `./gradlew yorkie:testDebugUnitTest` passes (all existing tests + the new divergence test)
- [ ] `./gradlew lintKotlin` clean
- [ ] `bash scripts/lint_architecture.sh` shows no NEW R1..R6 violations vs `origin/develop` baseline (existing pre-existing findings from `docs/specs-backlog.md` are not this PR's responsibility)
- [ ] Implementation matches the JS reference: exactly one Kotlin file touched (`CrdtTree.kt`), one test file added
- [ ] Port preserves JS behavior on the 5 scenarios above (unit tests cover scenarios 1, 2, 3; edges 4/5 covered as branch-coverage assertions in the same test class)
- [ ] Evidence captured: the new test's output line from `./gradlew yorkie:testDebugUnitTest --tests "...CrdtTreeStyleDivergenceTest"` pasted into the round report
- [ ] Commit message includes `RTCOLLABPLATFORM-643` and references JS `#1211`

## Harness Config Delta

none
