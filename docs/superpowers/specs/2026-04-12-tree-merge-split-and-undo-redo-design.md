# Tree Merge/Split & Multi-user Undo/Redo — Design Spec

## Overview

Two independent features scoped to JS SDK v0.6.36 parity, implemented as sequential tickets.

- **Ticket 1: Tree Merge/Split** — Expose existing internal `splitByPath()` and `mergeByPath()` as public API on `JsonTree`. Low risk, small scope.
- **Ticket 2: Multi-user Undo/Redo** — New `History` class, reverse operations on 6 operation types, `document.history` API. Larger scope.

---

## Ticket 1: Tree Merge/Split — Expose Public API

### Scope

Make `splitByPath()` and `mergeByPath()` public on `JsonTree`. Both methods already exist and work correctly as package-private methods. No proto, operation, or converter changes needed.

### Changes

#### `JsonTree.kt`

Add `public` modifier and KDoc to two methods:

```kotlin
/**
 * Splits the tree at the given [path].
 *
 * The node at [path] is split into two sibling nodes. Content after the
 * split point moves into a new node inserted immediately after. Internally
 * decomposes into one or two `edit()` calls (delete tail + insert new node).
 */
public fun splitByPath(path: List<Int>) { /* existing implementation */ }

/**
 * Merges the element node at the given [path] into its left sibling.
 *
 * The children of the node at [path] are moved into the preceding sibling,
 * then the now-empty node is deleted. Only element nodes can be merged;
 * merging a text node throws [YorkieException]. Internally decomposes into
 * one or two `edit()` calls (delete node + insert children).
 */
public fun mergeByPath(path: List<Int>) { /* existing implementation */ }
```

#### Tests — `JsonTreeTest.kt` (androidTest)

New instrumented tests:

| Test | Description |
|------|-------------|
| `test_tree_split_at_text_offset` | Split a `<p>` containing "helloworld" at offset 5 → two `<p>` nodes |
| `test_tree_split_at_element_boundary` | Split at a child element boundary |
| `test_tree_merge_adjacent_elements` | Merge two adjacent `<p>` siblings |
| `test_tree_merge_with_children` | Merge element whose children transfer to left sibling |
| `test_tree_concurrent_split` | Two clients split the same node concurrently, verify convergence |
| `test_tree_concurrent_merge` | Two clients merge concurrently, verify convergence |

### Files Changed

| File | Change |
|------|--------|
| `yorkie/src/main/kotlin/dev/yorkie/document/json/JsonTree.kt` | Add `public` to 2 methods, add KDoc |
| `yorkie/src/androidTest/kotlin/dev/yorkie/document/json/JsonTreeTest.kt` | Add 6 tests |

### Risk

Very low. No new logic — only visibility change + tests.

---

## Ticket 2: Multi-user Undo/Redo (v0.6.36 Parity)

### Scope

Implement client-side undo/redo for: Object set/remove, Array add/remove/set, Move, Counter increase, and Presence changes. No proto changes — undo/redo generates regular operations that sync normally.

**Not in scope:** Text.Edit, Text.Style, Tree.Edit, Tree.Style undo/redo (these are v0.7.x features).

### Architecture

#### New: `OpSource` enum

Location: `yorkie/src/main/kotlin/dev/yorkie/document/operation/OpSource.kt`

```kotlin
enum class OpSource {
    Local,
    Remote,
    UndoRedo,
}
```

Passed to `Operation.execute()` to handle corner cases (e.g., restoring removed elements during undo).

#### New: `HistoryOperation` sealed interface

Location: `yorkie/src/main/kotlin/dev/yorkie/document/history/HistoryOperation.kt`

```kotlin
sealed interface HistoryOperation {
    data class OperationChange(val operation: Operation) : HistoryOperation
    data class PresenceChange<P>(val value: P) : HistoryOperation
}
```

#### New: `History` class

Location: `yorkie/src/main/kotlin/dev/yorkie/document/history/History.kt`

```kotlin
class History {
    private val undoStack: ArrayDeque<List<HistoryOperation>> = ArrayDeque()
    private val redoStack: ArrayDeque<List<HistoryOperation>> = ArrayDeque()

    fun hasUndo(): Boolean
    fun hasRedo(): Boolean
    fun pushUndo(ops: List<HistoryOperation>)
    fun popUndo(): List<HistoryOperation>?
    fun pushRedo(ops: List<HistoryOperation>)
    fun popRedo(): List<HistoryOperation>?
    fun clearRedo()
    fun reconcileCreatedAt(prevCreatedAt: TimeTicket, currCreatedAt: TimeTicket)
}
```

- Max stack depth: 50 (matches JS SDK `MaxUndoRedoStackDepth`).
- `reconcileCreatedAt`: Scans both stacks and replaces old `createdAt`/`prevCreatedAt` references when elements are replaced during undo. Applies to `ArraySetOperation`, `RemoveOperation`, `AddOperation`, `MoveOperation`.

#### Modified: Operation classes — add `reverseOp` generation

Each operation's `execute()` method gains:
- Parameter: `source: OpSource`
- Return: adds `reverseOp: Operation?` to the result

| Operation | Reverse Operation |
|-----------|-------------------|
| `SetOperation` | Previous value exists → `SetOperation(oldValue)`. No previous → `RemoveOperation`. |
| `RemoveOperation` | `AddOperation` restoring the removed element. When `source == UndoRedo`, allow restoring tombstoned elements. |
| `AddOperation` | `RemoveOperation` targeting the added element. |
| `ArraySetOperation` | `ArraySetOperation` with the previous value. |
| `MoveOperation` | `MoveOperation` to the original position (using `prevCreatedAt` before the move). |
| `IncreaseOperation` | `IncreaseOperation` with negated delta value. |

Operations that do NOT generate reverse ops (out of scope):
- `EditOperation` (Text)
- `StyleOperation` (Text)
- `TreeEditOperation`
- `TreeStyleOperation`

#### Modified: `Change.execute()`

Signature change:
```kotlin
// before:
fun execute(root: CrdtRoot, presences: ...): List<OpInfo>

// after:
fun execute(root: CrdtRoot, presences: ..., source: OpSource = OpSource.Local): Pair<List<OpInfo>, List<HistoryOperation>>
```

Collects `reverseOps` from each operation execution (in reverse order, matching JS SDK `reverseOps.unshift()`).

#### Modified: `Document`

New fields:
```kotlin
private val internalHistory = History()
private var isUpdating = false
```

New public API:
```kotlin
val history = object {
    fun canUndo(): Boolean = internalHistory.hasUndo() && !isUpdating
    fun canRedo(): Boolean = internalHistory.hasRedo() && !isUpdating
    fun undo() = executeUndoRedo(isUndo = true)
    fun redo() = executeUndoRedo(isUndo = false)
}
```

Changes to `updateAsync()`:
1. Set `isUpdating = true` before calling the updater, reset after.
2. After executing the change, collect `reverseOps` and push to undo stack.
3. If the change produced operations, clear the redo stack.

New private method `executeUndoRedo(isUndo: Boolean)`:
1. Throw if `isUpdating` is true.
2. Pop from undo (or redo) stack.
3. Create a `ChangeContext`, iterate over the ops, set `executedAt` via `issueTimeTicket()`.
4. For `ArraySetOperation` and `AddOperation`, update `createdAt` on the value and call `reconcileCreatedAt` on the history.
5. Execute the change with `OpSource.UndoRedo` — collect new `reverseOps`.
6. Push `reverseOps` to the opposite stack (redo if undoing, undo if redoing).
7. Add the change to `localChanges` for sync.
8. Publish document change events.

### Tests

#### Unit tests — `DocumentTest.kt` or new `HistoryTest.kt`

| Test | Description |
|------|-------------|
| `test_undo_object_set` | Set a key, undo → key reverted |
| `test_undo_object_remove` | Remove a key, undo → key restored |
| `test_redo_after_undo` | Set, undo, redo → value restored |
| `test_undo_array_add_remove` | Array add then remove, undo each step |
| `test_undo_array_set` | Array set, undo → previous value |
| `test_undo_counter_increase` | Increase by 5, undo → decreased by 5 |
| `test_undo_move` | Move element, undo → moved back |
| `test_redo_cleared_on_new_edit` | Undo then make new edit → redo stack empty |
| `test_undo_stack_depth_limit` | Push 60 edits, undo stack has only 50 |
| `test_undo_throws_during_update` | Call undo inside updateAsync → throws |
| `test_undo_empty_stack_throws` | Call undo with nothing to undo → throws |

#### Instrumented tests — `ClientTest.kt` or new `UndoRedoTest.kt`

| Test | Description |
|------|-------------|
| `test_multi_user_undo` | Client A sets key, Client B sets key, Client A undoes → only A's change reverted |
| `test_undo_after_remote_sync` | Client A edits, sync, Client A undoes → change reverted, synced to B |
| `test_redo_syncs_to_remote` | Client A undoes then redoes → redo synced to B |

### Files Changed

| File | Change |
|------|--------|
| `yorkie/src/main/kotlin/dev/yorkie/document/operation/OpSource.kt` | New file |
| `yorkie/src/main/kotlin/dev/yorkie/document/history/HistoryOperation.kt` | New file |
| `yorkie/src/main/kotlin/dev/yorkie/document/history/History.kt` | New file |
| `yorkie/src/main/kotlin/dev/yorkie/document/operation/SetOperation.kt` | Add `reverseOp` + `source` param |
| `yorkie/src/main/kotlin/dev/yorkie/document/operation/RemoveOperation.kt` | Add `reverseOp` + `source` param |
| `yorkie/src/main/kotlin/dev/yorkie/document/operation/AddOperation.kt` | Add `reverseOp` + `source` param |
| `yorkie/src/main/kotlin/dev/yorkie/document/operation/ArraySetOperation.kt` | Add `reverseOp` + `source` param |
| `yorkie/src/main/kotlin/dev/yorkie/document/operation/MoveOperation.kt` | Add `reverseOp` + `source` param |
| `yorkie/src/main/kotlin/dev/yorkie/document/operation/IncreaseOperation.kt` | Add `reverseOp` + `source` param |
| `yorkie/src/main/kotlin/dev/yorkie/document/operation/Operation.kt` | Add `source` param to `execute()` signature |
| `yorkie/src/main/kotlin/dev/yorkie/document/change/Change.kt` | Return `reverseOps` from `execute()` |
| `yorkie/src/main/kotlin/dev/yorkie/document/Document.kt` | Add `History`, `history` API, `executeUndoRedo()` |
| `yorkie/src/test/kotlin/dev/yorkie/document/history/HistoryTest.kt` | New unit tests |
| `yorkie/src/androidTest/kotlin/dev/yorkie/document/UndoRedoTest.kt` | New instrumented tests |

### Risk

Medium. Touches the operation execution path which is core to the SDK. Key risks:
- `reconcileCreatedAt` must correctly track element replacement across undo/redo cycles
- `OpSource.UndoRedo` handling in `RemoveOperation` must correctly restore tombstoned elements
- Concurrent undo between multiple clients must converge

Mitigations: Comprehensive test coverage, direct port from JS SDK which has been battle-tested.

---

## Execution Order

1. **Tree Merge/Split** first (small, quick win, independent)
2. **Multi-user Undo/Redo** second (larger, benefits from focused review)

Each gets its own JIRA ticket, branch, and PR.
