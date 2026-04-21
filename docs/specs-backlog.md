# Specs Backlog

Non-blocker findings carried forward from prior sessions or from harness setup. `/harness plan` reads this file at its Step 0.5 before brainstorming.

## Format

```
### {YYYY-MM-DD} — phase/source — {short title}

**Source:** {where this came from, e.g. harness-adopt, round-2-qa.md §2b}
**File:line:** {path:line if known}
**Severity:** LOW | MEDIUM
**Why deferred:** {time-box | scope | needs design | pre-existing}
**Proposed action:** {fix | roll into next spec | watch}
```

---

### 2026-04-21 — phase-1 round-2 — `CrdtTreeNode.clone()` shares `_attributes` across split siblings

**Source:** `docs/round-reports/round-2-qa.md` HIGH-1 / improvements P1 (during RTCOLLABPLATFORM-643 review)
**File:line:** `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt:971-979` (Kotlin data-class `copy(...)` without replacing `_attributes`)
**Severity:** MEDIUM
**Why deferred:** pre-existing on `origin/develop`; out-of-scope for RTCOLLABPLATFORM-643 which ports the *cross-replica* End-token guard. This is an additional *within-replica* bug (styling the original bleeds into the split sibling's `Rht`). The End-token guard fixes the main user-facing divergence; the deep-copy fix closes the remaining semantic gap and lets the JS-spec "strong" assertion (`<p bold=true>hel</p><p>lo</p>`) be restored.
**Proposed action:** file RTCOLLABPLATFORM ticket — mirror `CrdtTreeNode.deepCopy()` at `CrdtTree.kt:1034-1056`, which already uses `_attributes = _attributes.deepCopy()`. A regression test would assert that styling the parent does NOT leak attributes onto a split sibling within a single replica (currently fails — clear Red-Green-Refactor shape).

### 2026-04-21 — phase-1 round-2 — `ClientTest.should retry on network failure…` flaky via `NoWhenBranchMatchedException`

**Source:** `docs/round-reports/round-2-qa.md` LOW-3 / improvements U2 (full-suite rerun during RTCOLLABPLATFORM-643 review)
**File:line:** `yorkie/src/main/kotlin/dev/yorkie/document/Document.kt:647` in `presenceEventReadyToBePublished` — exhaustive `when` missing a branch
**Severity:** LOW
**Why deferred:** unrelated to the End-token guard port; flakes only on full-suite reruns, passes in isolation and on subsequent full-suite runs. No code in this PR touches `Document.kt` or `ClientTest.kt`.
**Proposed action:** small ticket — add the missing sealed-type branch in the `when` inside `presenceEventReadyToBePublished`; ideally also register it in a future `docs/flaky-tests.md` registry per evaluator proposal U2.

### 2026-04-21 — harness-adopt — CRDT layer imports JSON layer (R1)

**Source:** `scripts/lint_architecture.sh` first run
**File:line:**
- `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtElement.kt:3` — `JsonStringifier.toJsonString`
- `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt:8` — `TreePosStructRange`
- `yorkie/src/main/kotlin/dev/yorkie/document/crdt/TextInfo.kt:3` — `escapeString`
- `yorkie/src/main/kotlin/dev/yorkie/document/crdt/Rht.kt:3` — `escapeString`
- `yorkie/src/main/kotlin/dev/yorkie/document/crdt/TreeInfo.kt:3` — `JsonTree`

**Severity:** MEDIUM
**Why deferred:** pre-existing; refactor crosses both layers and is worth its own spec
**Proposed action:** write a spec to move `JsonStringifier.toJsonString`, `escapeString`, and `TreePosStructRange` into a lower layer (`util/` or `document/crdt/` itself) so CRDT no longer depends on JSON. `TreeInfo.kt` → rethink the `JsonTree` reference.

### 2026-04-21 — harness-adopt — util imports document/time (R2)

**Source:** `scripts/lint_architecture.sh` first run
**File:line:** `yorkie/src/main/kotlin/dev/yorkie/util/IndexTree.kt:3` — `dev.yorkie.document.time.TimeTicket`

**Severity:** LOW
**Why deferred:** pre-existing; IndexTree is positional and arguably part of `document/` anyway
**Proposed action:** either move `IndexTree` out of `util/` into `document/` or pull `TimeTicket` into a shared base package.

### 2026-04-21 — harness-adopt — oversize files (R6)

**Source:** `scripts/lint_architecture.sh` first run
**File:line:**
- `yorkie/src/main/kotlin/dev/yorkie/core/Client.kt` — 1498 lines
- `yorkie/src/main/kotlin/dev/yorkie/document/crdt/CrdtTree.kt` — 1133 lines
- `yorkie/src/main/kotlin/dev/yorkie/document/Document.kt` — 973 lines
- `yorkie/src/main/kotlin/dev/yorkie/util/IndexTree.kt` — 823 lines
- `yorkie/src/main/kotlin/dev/yorkie/document/json/JsonTree.kt` — 652 lines
- `yorkie/src/main/kotlin/dev/yorkie/document/crdt/RgaTreeSplit.kt` — 659 lines
- `yorkie/src/main/kotlin/dev/yorkie/api/ElementConverter.kt` — 608 lines
- `yorkie/src/main/kotlin/dev/yorkie/util/SplayTreeSet.kt` — 407 lines
- `yorkie/src/main/kotlin/dev/yorkie/document/json/JsonArray.kt` — 345 lines

**Severity:** LOW
**Why deferred:** pre-existing; splitting is mechanical but requires per-file judgement to keep public APIs stable
**Proposed action:** spec-per-file refactor to split by responsibility (e.g., Client.kt → ClientConnection + ClientSyncLoop + ClientWatchStream + ClientPresence). Keep public surface unchanged. Alternative: raise `YORKIE_MAX_FILE_LINES` per directory and enforce a stricter ceiling only on new files — decide in a dedicated spec.
