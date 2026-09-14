package dev.yorkie.document.operation

import dev.yorkie.api.toOperations
import dev.yorkie.api.toPBOperation
import dev.yorkie.document.Document
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Document-level contracts of an EXECUTED identity-preserving restore /
 * retombstone [EditOperation] — the object `Document.executeUndoRedo` appends
 * to its local changes, not the one `toReverseOperation` builds (2026-09-10
 * review of PR #359, threads 3975567735 and 3975567754).
 */
class EditOperationRestoreTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private suspend fun Document.text(): JsonText = getRoot().getAs("text")

    /** Per-character `(createdAt, absolute offset)` identity of the live text. */
    private fun identitySequence(document: Document): List<RgaTreeSplitNodeID> =
        (document.getRootObject()["text"] as CrdtText).rgaTreeSplit
            .filterNot { it.isRemoved }
            .flatMap { node ->
                (0 until node.contentLength).map {
                    RgaTreeSplitNodeID(node.id.createdAt, node.id.offset + it)
                }
            }

    /**
     * The last change this document would push, plus the same change as a
     * peer WITHOUT restore support decodes it: fields 8-10 (`restore_spans`,
     * `restore_mode`, `retombstone_spans`) stripped, base `Edit` kept.
     */
    private suspend fun Document.lastChangeAsOldPeerSeesIt(): Pair<EditOperation, Change> {
        val change = createChangePack().changes.last()
        val op = change.operations.single() as EditOperation
        val pbOp = op.toPBOperation()
        assertEquals(pbOp.edit.from, pbOp.edit.to, "serialized restore op must be zero-width")
        val strippedEdit = pbOp.edit.toBuilder()
            .clearRestoreSpans()
            .clearRestoreMode()
            .clearRetombstoneSpans()
            .build()
        val stripped = listOf(pbOp.toBuilder().setEdit(strippedEdit).build()).toOperations()
        return op to change.copy(operations = stripped)
    }

    private suspend fun Document.apply(change: Change) {
        applyChangePack(
            ChangePack(
                getKey(),
                CheckPoint.InitialCheckPoint,
                listOf(change),
                null,
                false,
                VersionVector(),
            ),
        )
    }

    // R2 (thread 3975567735): execute() re-resolved fromPos/toPos from the
    // clamped undo offsets for EVERY undo op before dispatching to
    // executeRestore, so the op that actually went on the wire carried a
    // live-range delete. A peer that strips the restore fields (old server
    // relay, older Android/JS/iOS SDK) applied `Edit(from, to, "")` and
    // deleted text nobody deleted. JS returns from its restore branch before
    // refining positions, so its wire shape is `(from, from)`.
    @Test
    fun `an executed restore op stays zero-width so an old peer applies a no-op`() = runTest {
        val a = Document("test-doc")
        val b = Document("test-doc")
        a.setActor(actor1)
        b.setActor(actor2)
        a.updateAsync { root, _ -> root.setNewText("text").edit(0, 0, "world") }.await()
        crossSync(a, b)
        a.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(0, 0, "hello") }.await()
        crossSync(a, b)
        assertEquals("helloworld", b.text().toString())

        // Undo the insert: a retombstone of "hello".
        a.history.undoAsync().await()
        assertEquals("world", a.text().toString())
        val (undoOp, undoForOldPeer) = a.lastChangeAsOldPeerSeesIt()
        assertEquals(undoOp.fromPos, undoOp.toPos, "executed undo op must be zero-width")
        b.apply(undoForOldPeer)
        assertEquals(
            "helloworld",
            b.text().toString(),
            "an old peer must apply the stripped undo as a no-op, never as a delete",
        )

        // Redo: a restore of "hello".
        a.history.redoAsync().await()
        assertEquals("helloworld", a.text().toString())
        val (redoOp, redoForOldPeer) = a.lastChangeAsOldPeerSeesIt()
        assertEquals(redoOp.fromPos, redoOp.toPos, "executed redo op must be zero-width")
        b.apply(redoForOldPeer)
        assertEquals(
            "helloworld",
            b.text().toString(),
            "an old peer must apply the stripped redo as a no-op, never as a delete",
        )
    }

    // R4 (thread 3975567754): executeRestore gated its reverse op on
    // `opInfos.isNotEmpty()`. When every targeted piece is already in the
    // requested state (here d2 deleted the same "45" before d1's redo) the
    // retombstone is a no-op, no reverse was pushed, and the popped redo
    // entry vanished — the NEXT undo then popped the original insert and
    // wiped the text. JS pushes the reverse unconditionally
    // (edit_operation.ts restore branch; document.ts pushes before its
    // empty-opInfos return), so the next undo revives "45" instead.
    @Test
    fun `a no-effect redo still pushes its undo counterpart like JS`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d1.updateAsync { root, _ -> root.setNewText("text").edit(0, 0, "0123456789") }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(4, 6, "") }.await()
        crossSync(d1, d2)
        d1.history.undoAsync().await()
        assertEquals("0123456789", d1.text().toString())
        crossSync(d1, d2)
        d2.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(4, 6, "") }.await()
        crossSync(d1, d2)
        assertEquals("01236789", d1.text().toString())

        // The retombstone finds "45" already removed: no opInfos, no change.
        d1.history.redoAsync().await()
        assertEquals("01236789", d1.text().toString())
        assertFalse(d1.history.canRedo())
        assertTrue(d1.history.canUndo())

        // JS parity: the reverse (a restore of "45") is on the undo stack, so
        // undoing revives "45" — even though d2 deleted it — rather than
        // popping the original insert and emptying the document.
        d1.history.undoAsync().await()
        assertEquals("0123456789", d1.text().toString())

        crossSync(d1, d2)
        assertEquals(d1.text().toString(), d2.text().toString())
        assertEquals(identitySequence(d1), identitySequence(d2))
    }
}
