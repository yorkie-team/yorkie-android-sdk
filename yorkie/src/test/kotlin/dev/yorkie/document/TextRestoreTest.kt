package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports the identity-preserving undo/redo cases in `document_test.ts` (JS SDK
 * 5d5cac63, #1293) as JVM unit tests. Also carries the two
 * `text_restore_after_gc_test.ts` cases JS `cab55005` (#1442) added:
 * convergence when only the undoing replica purged the deleted run, with and
 * without its left neighbour also purged -- pinned green at the baseline,
 * since `cab55005` only changed `Text.normalizePos`'s cost, not this
 * restore/retombstone ladder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextRestoreTest {

    private fun Document.crdtText(): CrdtText = getRootObject()["text"] as CrdtText

    /**
     * Finds the id of the node whose live text equals [content], live or
     * tombstoned per [removed]. Structural equality on [RgaTreeSplitNodeID] is
     * the load-bearing identity check (DEC-3) — no test-string rendering is
     * needed since the data class already gives free `equals`/`hashCode`.
     */
    private fun idOf(
        text: CrdtText,
        content: String,
        removed: Boolean,
    ): RgaTreeSplitNodeID {
        return text.rgaTreeSplit.first { it.isRemoved == removed && it.value.content == content }.id
    }

    @Test
    fun `undo revives and redo re-tombstones the same node id`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "0123456789")
        }.await()

        // Delete "45" — the reverse op should carry restore spans, not a copy.
        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(4, 6, "") }.await()
        assertEquals("01236789", document.getRoot().getAs<JsonText>("text").toString())

        // Capture the tombstoned "45" node's identity before undo.
        val deletedId = idOf(document.crdtText(), "45", removed = true)

        // Undo revives the ORIGINAL node (un-tombstone), not a new copy: the
        // same id must now be live.
        document.history.undoAsync().await()
        assertEquals("0123456789", document.getRoot().getAs<JsonText>("text").toString())
        assertEquals(deletedId, idOf(document.crdtText(), "45", removed = false))

        // Redo re-tombstones exactly that node (same id).
        document.history.redoAsync().await()
        assertEquals("01236789", document.getRoot().getAs<JsonText>("text").toString())
        assertEquals(deletedId, idOf(document.crdtText(), "45", removed = true))

        // Undo again — the restore/retombstone cycle must be stable.
        document.history.undoAsync().await()
        assertEquals("0123456789", document.getRoot().getAs<JsonText>("text").toString())
        assertEquals(deletedId, idOf(document.crdtText(), "45", removed = false))
    }

    @Test
    fun `undo recreates original node identities after GC purges the tombstones`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "0123456789")
        }.await()

        // Two deletions that overlap the same original insertion node leave
        // two tombstoned spans "45" and "12".
        document.updateAsync { root, _ ->
            root.getAs<JsonText>(
                "text",
            ).edit(4, 6, "")
        }.await() // delete "45"
        document.updateAsync { root, _ ->
            root.getAs<JsonText>(
                "text",
            ).edit(1, 3, "")
        }.await() // "01236789" -> "036789"
        assertEquals("036789", document.getRoot().getAs<JsonText>("text").toString())

        val id45 = idOf(document.crdtText(), "45", removed = true)
        val id12 = idOf(document.crdtText(), "12", removed = true)

        // Garbage-collect: both tombstones are purged from the tree, so undo
        // can no longer un-tombstone — it must RECREATE the nodes under
        // their original ids via the gap-recreate path.
        val purged = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertEquals(2, purged)
        assertEquals(0, document.garbageLength)

        // Undo restores "12" (reverse order), then "45" — both under their
        // ORIGINAL identities, not fresh copies.
        document.history.undoAsync().await()
        assertEquals("01236789", document.getRoot().getAs<JsonText>("text").toString())
        assertEquals(id12, idOf(document.crdtText(), "12", removed = false))

        document.history.undoAsync().await()
        assertEquals("0123456789", document.getRoot().getAs<JsonText>("text").toString())
        assertEquals(id12, idOf(document.crdtText(), "12", removed = false))
        assertEquals(id45, idOf(document.crdtText(), "45", removed = false))
    }

    @Test
    fun `undo emits insertion-shaped opInfo and redo emits deletion-shaped opInfo`() = runTest {
        // Empty opInfos silently suppress remote propagation of the undo/redo
        // change (a change with no opInfos is swallowed), so restore/
        // retombstone must report the content change (AC11).
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "0123456789")
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(4, 6, "") }.await()

        val events = mutableListOf<Document.Event>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            document.events.collect(events::add)
        }

        document.history.undoAsync().await()
        val undoOps = (events.last() as Document.Event.LocalChange).changeInfo.operations
        assertEquals(1, undoOps.size)
        val undoOp = undoOps.single() as OperationInfo.EditOpInfo
        assertEquals(4, undoOp.from)
        assertEquals(4, undoOp.to)
        assertEquals("45", undoOp.value.text)

        document.history.redoAsync().await()
        val redoOps = (events.last() as Document.Event.LocalChange).changeInfo.operations
        assertEquals(1, redoOps.size)
        val redoOp = redoOps.single() as OperationInfo.EditOpInfo
        assertEquals(4, redoOp.from)
        assertEquals(6, redoOp.to)
        assertEquals("", redoOp.value.text)

        collectJob.cancel()
    }

    @Test
    fun `undo of a styled-text deletion reports the original attributes in opInfo`() = runTest {
        // JS parity (5d5cac63): ValueChange carries the full node value, so
        // toTextChanges reports the revived node's attributes. The opInfo for
        // an undo of a styled deletion must carry those attributes, not an
        // empty map, or attribute-aware subscribers render unstyled text.
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "bold", mapOf("bold" to "true"))
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(0, 4, "") }.await()
        assertEquals("", document.getRoot().getAs<JsonText>("text").toString())

        val events = mutableListOf<Document.Event>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            document.events.collect(events::add)
        }

        document.history.undoAsync().await()
        val undoOp = (events.last() as Document.Event.LocalChange).changeInfo.operations
            .single() as OperationInfo.EditOpInfo
        assertEquals("bold", undoOp.value.text)
        assertEquals(mapOf("bold" to "true"), undoOp.value.attributes)

        collectJob.cancel()
    }

    // Regression (round-3 fix, addendum R-1 / round-2 MEDIUM-1): undo of a
    // pure insert retombstones the inserted span by identity, which can
    // shrink the document below this SAME op's own undoFromOffset/
    // undoToOffset — captured once at insert time and never re-derived
    // across a restore/retombstone flip. Redo used to re-resolve those
    // stale offsets against the now-shrunk document in EditOperation.execute
    // and throw IndexOutOfBoundsException before ever reaching executeRestore.

    @Test
    fun `redo after undoing an insert that emptied the document restores content`() = runTest {
        val document = Document("test-doc")
        // setNewText in its OWN updateAsync block (spec 011 B3) — undoing the
        // content edit below must reverse only that edit, not the
        // SetOperation that created "text".
        document.updateAsync { root, _ -> root.setNewText("text") }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(0, 0, "0123456789")
        }.await()
        assertEquals("0123456789", document.getRoot().getAs<JsonText>("text").toString())
        assertTrue(document.history.canUndo())

        document.history.undoAsync().await()
        assertEquals("", document.getRoot().getAs<JsonText>("text").toString())

        document.history.redoAsync().await()
        assertEquals("0123456789", document.getRoot().getAs<JsonText>("text").toString())

        // Cycle again — the fix must hold on repeat toggles, not just once.
        document.history.undoAsync().await()
        assertEquals("", document.getRoot().getAs<JsonText>("text").toString())
    }

    @Test
    fun `redo after undoing a second insert that only partially shrinks the document`() = runTest {
        // Exact shape of the failing instrumented test
        // (JsonTextHistoryTest#test_undo_and_redo_insert): undo of the
        // SECOND insert shrinks the doc but does not empty it, still
        // leaving undoToOffset (captured at the grown length) stale against
        // the shrunk length redo must resolve against.
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "hello")
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonText>("text").edit(5, 5, " world")
        }.await()
        assertEquals("hello world", document.getRoot().getAs<JsonText>("text").toString())

        document.history.undoAsync().await()
        assertEquals("hello", document.getRoot().getAs<JsonText>("text").toString())

        document.history.redoAsync().await()
        assertEquals("hello world", document.getRoot().getAs<JsonText>("text").toString())
    }

    // JS `text_restore_after_gc_test.ts` cases added by `cab55005` (#1442):
    // which pieces survive a GC pass is per-replica state, so where a
    // restore anchors must not depend on it. Here only the undoing replica
    // (d1) has purged, so its restore rebuilds the run through the anchor
    // ladder, while the peer (d2) still holds the tombstones and revives
    // them in place; in the `purgeLeft` case the text left of the run is
    // ALSO purged on d1, so no piece of any neighbour survives there and the
    // first recreated fragment falls back to the operation's own position.
    // Pinned green at the baseline: Android's restore/retombstone ladder
    // is unchanged by `cab55005` (a Text position-lookup change only).
    private suspend fun runUndoingReplicaPurgedConvergence(
        purgeLeft: Boolean,
    ): Pair<Document, Document> {
        val actor1 = "000000000000000000000001"
        val actor2 = "000000000000000000000002"
        val d1 = Document("text-restore-after-gc-peer")
        val d2 = Document("text-restore-after-gc-peer")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d1.updateAsync { root, _ -> root.setNewText("t") }.await()

        val s = "hello my name is"
        for (i in s.indices) {
            val c = s[i].toString()
            d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(i, i, c) }.await()
        }
        crossSync(d1, d2)
        d1.clearHistory()

        if (purgeLeft) {
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>(
                    "t",
                ).edit(0, 6, "")
            }.await() // "hello "
            crossSync(d1, d2)
        }
        val from = if (purgeLeft) 0 else 6
        d1.updateAsync { root, _ ->
            root.getAs<JsonText>(
                "t",
            ).edit(from, from + 7, "")
        }.await() // "my name"
        crossSync(d1, d2)

        assertTrue(
            d1.garbageCollect(maxVectorOf(listOf(actor1, actor2))) > 0,
            "expected the undoing replica to actually purge the deleted run",
        )
        d1.history.undoAsync().await()
        crossSync(d1, d2)
        return d1 to d2
    }

    @Test
    fun `converges when only the undoing replica purged the run its neighbours survive`() =
        runTest {
            val (d1, d2) = runUndoingReplicaPurgedConvergence(purgeLeft = false)
            val want = "hello my name is"
            assertEquals(want, d1.getRoot().getAs<JsonText>("t").toString())
            assertEquals(want, d2.getRoot().getAs<JsonText>("t").toString())
            assertEquals(d1.toJson(), d2.toJson())
        }

    @Test
    fun `converges when only the undoing replica purged the run its left neighbour too`() =
        runTest {
            val (d1, d2) = runUndoingReplicaPurgedConvergence(purgeLeft = true)
            val want = "my name is"
            assertEquals(want, d1.getRoot().getAs<JsonText>("t").toString())
            assertEquals(want, d2.getRoot().getAs<JsonText>("t").toString())
            assertEquals(d1.toJson(), d2.toJson())
        }
}
