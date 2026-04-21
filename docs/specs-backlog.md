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
