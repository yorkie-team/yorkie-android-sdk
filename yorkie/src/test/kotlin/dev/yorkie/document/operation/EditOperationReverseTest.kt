package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class EditOperationReverseTest {

    private val rootTicket = TimeTicket(1L, 0u, "actor-0")
    private val textTicket = TimeTicket(2L, 0u, "actor-0")

    private fun makeTicket(lamport: Long): TimeTicket = TimeTicket(lamport, 0u, "actor-0")

    private fun buildTextRoot(): Pair<CrdtText, CrdtRoot> {
        val text = CrdtText(RgaTreeSplit(), textTicket)
        val obj = CrdtObject(createdAt = rootTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        // Set into the object's member nodes so that subPathOf resolves the key.
        root.rootObject.set("text", text, textTicket)
        // Register in the element map so that findByCreatedAt resolves the text.
        root.registerElement(text, root.rootObject)
        return text to root
    }

    private fun makeEditOp(
        text: CrdtText,
        fromIndex: Int,
        toIndex: Int,
        content: String,
        lamport: Long,
    ): EditOperation {
        val ticket = makeTicket(lamport)
        val range = text.indexRangeToPosRange(fromIndex, toIndex)
        return EditOperation(
            fromPos = range.first,
            toPos = range.second,
            content = content,
            parentCreatedAt = textTicket,
            executedAt = ticket,
            attributes = emptyMap(),
        )
    }

    @Test
    fun `reverse of pure insert is a pure delete`() {
        // given: text is "Hello"
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "Hello", makeTicket(3))

        // when: insert "X" at position 2
        val op = makeEditOp(text, 2, 2, "X", 4)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op should delete what was inserted (empty content = pure delete)
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as EditOperation
        assertEquals("", reverseOp.content)
        assertTrue(reverseOp.isUndoOp)
    }

    @Test
    fun `reverse of pure delete is a pure insert`() {
        // given: text is "Hello"
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "Hello", makeTicket(3))

        // when: delete [2,4) — deletes "ll"
        val op = makeEditOp(text, 2, 4, "", 4)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op should insert "ll" at position 2
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as EditOperation
        assertEquals("ll", reverseOp.content)
        assertTrue(reverseOp.isUndoOp)
    }

    @Test
    fun `reverse of replace deletes new and inserts old`() {
        // given: text is "Hello"
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "Hello", makeTicket(3))

        // when: replace [2,4)→"NEW" — replaces "ll" with "NEW"
        val op = makeEditOp(text, 2, 4, "NEW", 4)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op should delete "NEW" and insert "ll"
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as EditOperation
        assertEquals("ll", reverseOp.content)
        assertTrue(reverseOp.isUndoOp)
        // undo range is [2, 2+"NEW".length) = [2, 5)
        assertEquals(2, reverseOp.undoFromOffset)
        assertEquals(2 + "NEW".length, reverseOp.undoToOffset)
    }

    @Test
    fun `reverse preserves attributes when single segment`() {
        // given: text "Hello" inserted as one styled node
        val (text, root) = buildTextRoot()
        text.edit(
            text.indexRangeToPosRange(0, 0),
            "Hello",
            makeTicket(3),
            mapOf("bold" to "true"),
        )

        // when: delete [2,4) — deletes "ll" from the single node
        val op = makeEditOp(text, 2, 4, "", 4)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse content is "ll" and attributes are preserved (single-segment removal)
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as EditOperation
        assertEquals("ll", reverseOp.content)
        assertEquals(mapOf("bold" to "true"), reverseOp.attributes)
    }

    @Test
    fun `reverse drops attributes when multi-segment`() {
        // given: two styled nodes "He" (bold) and "llo" (italic)
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "He", makeTicket(3), mapOf("bold" to "true"))
        text.edit(text.indexRangeToPosRange(2, 2), "llo", makeTicket(4), mapOf("italic" to "true"))

        // when: delete [1,4) — crosses node boundary
        val op = makeEditOp(text, 1, 4, "", 5)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse attributes are empty for multi-segment removals
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as EditOperation
        assertEquals("ell", reverseOp.content)
        assertTrue(reverseOp.attributes.isEmpty())
    }

    @Test
    fun `non-undo op is not flagged as undo op`() {
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "Hello", makeTicket(3))
        val op = makeEditOp(text, 0, 0, "X", 4)
        assertFalse(op.isUndoOp)
    }

    // --- reconcileOperation cases ---
    // reconcileOperation adjusts the undoFromOffset / undoToOffset of an undo op.

    private fun makeUndoOp(undoFrom: Int, undoTo: Int): EditOperation {
        val ticket = makeTicket(10)
        val nodeId = RgaTreeSplitNodeID(ticket, 0)
        return EditOperation(
            fromPos = RgaTreeSplitPos(nodeId, 0),
            toPos = RgaTreeSplitPos(nodeId, 0),
            content = "",
            parentCreatedAt = ticket,
            executedAt = ticket,
            attributes = emptyMap(),
            undoFromOffset = undoFrom,
            undoToOffset = undoTo,
        )
    }

    @Test
    fun `reconcileOperation case 1 remote left of undo shifts both endpoints`() {
        // undo [5,8), remote edit replaces [2,4)→"XYZ" (delta = 3-2=+1)
        val op = makeUndoOp(5, 8)
        op.reconcileOperation(remoteFrom = 2, remoteTo = 4, remoteContentLen = 3)
        assertEquals(6, op.undoFromOffset)
        assertEquals(9, op.undoToOffset)
    }

    @Test
    fun `reconcileOperation case 2 remote right of undo no change`() {
        // undo [2,5), remote edit at [6,8)
        val op = makeUndoOp(2, 5)
        op.reconcileOperation(remoteFrom = 6, remoteTo = 8, remoteContentLen = 1)
        assertEquals(2, op.undoFromOffset)
        assertEquals(5, op.undoToOffset)
    }

    @Test
    fun `reconcileOperation case 3 undo contained by remote collapses to point`() {
        // undo [3,6), remote covers [2,8)→"XYZ" (content=3)
        val op = makeUndoOp(3, 6)
        op.reconcileOperation(remoteFrom = 2, remoteTo = 8, remoteContentLen = 3)
        // fromOffset = toOffset = remoteFrom + remoteContentLen = 2 + 3 = 5
        assertEquals(5, op.undoFromOffset)
        assertEquals(5, op.undoToOffset)
    }

    @Test
    fun `reconcileOperation case 4 remote contained by undo expands toPos only`() {
        // undo [2,10), remote edit at [4,6)→"ABCDE" (delta = 5-2=+3)
        val op = makeUndoOp(2, 10)
        op.reconcileOperation(remoteFrom = 4, remoteTo = 6, remoteContentLen = 5)
        assertEquals(2, op.undoFromOffset)
        assertEquals(13, op.undoToOffset) // 10 + (5-2) = 13
    }

    @Test
    fun `reconcileOperation case 5 overlap start shrinks from start`() {
        // undo [5,9), remote covers [3,7)→"AB" (content=2, delta = 2-4=-2)
        val op = makeUndoOp(5, 9)
        op.reconcileOperation(remoteFrom = 3, remoteTo = 7, remoteContentLen = 2)
        // fromOffset = remoteFrom + remoteContentLen = 3 + 2 = 5
        // toOffset = undoTo + delta = 9 + (2-4) = 7
        assertEquals(5, op.undoFromOffset)
        assertEquals(7, op.undoToOffset)
    }

    @Test
    fun `reconcileOperation case 6 overlap end truncates end`() {
        // undo [3,8), remote covers [6,10)→"X"
        val op = makeUndoOp(3, 8)
        op.reconcileOperation(remoteFrom = 6, remoteTo = 10, remoteContentLen = 1)
        // fromOffset unchanged, toOffset = remoteFrom = 6
        assertEquals(3, op.undoFromOffset)
        assertEquals(6, op.undoToOffset)
    }

    @Test
    fun `reconcileOperation is no-op for non-undo ops`() {
        val ticket = makeTicket(10)
        val nodeId = RgaTreeSplitNodeID(ticket, 0)
        val op = EditOperation(
            fromPos = RgaTreeSplitPos(nodeId, 0),
            toPos = RgaTreeSplitPos(nodeId, 5),
            content = "",
            parentCreatedAt = ticket,
            executedAt = ticket,
            attributes = emptyMap(),
            // undoFromOffset / undoToOffset default to NOT_AN_UNDO_OP
        )
        assertFalse(op.isUndoOp)
        op.reconcileOperation(remoteFrom = 0, remoteTo = 5, remoteContentLen = 10)
        // Should be unchanged since isUndoOp is false
        assertEquals(EditOperation.NOT_AN_UNDO_OP, op.undoFromOffset)
        assertEquals(EditOperation.NOT_AN_UNDO_OP, op.undoToOffset)
    }
}
