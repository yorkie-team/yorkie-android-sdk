package dev.yorkie.document.history

import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.document.crdt.replaceNodeID
import dev.yorkie.document.crdt.replaceTreeNodeId
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.RestoreMode
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.Test

/**
 * Port yorkie-js-sdk `b173c093` (#1426): re-points the tree edits and tree
 * styles in both undo/redo stacks that target a tree, from the id of an
 * element a merge removed to the id a split re-creating it mints -- the
 * tree counterpart of [HistoryTest]'s `reconcileCreatedAt`. Covers
 * [replaceTreeNodeId]/[CrdtTreePos.replaceNodeID] directly (the low-level
 * re-point), then [TreeEditOperation.reconcileNodeID] /
 * [TreeStyleOperation.reconcileNodeID] (applying it to one operation), then
 * [History.reconcileTreeNodeID] (scanning both stacks, scoped to one tree).
 */
class HistoryReconcileTreeNodeIdTest {

    private fun ticket(lamport: Long) = TimeTicket(lamport, 0u, "actor-0")

    private fun treeEditOp(
        treeTicket: TimeTicket,
        pos: CrdtTreePos,
        executedAt: TimeTicket,
        restoreSpans: List<TreeRestoreSpan>? = null,
        restoreMode: RestoreMode? = null,
        retombstoneSpans: List<TreeRestoreSpan>? = null,
    ) = TreeEditOperation(
        parentCreatedAt = treeTicket,
        fromPos = pos,
        toPos = pos,
        contents = null,
        splitLevel = 0,
        executedAt = executedAt,
        restoreSpans = restoreSpans,
        restoreMode = restoreMode,
        retombstoneSpans = retombstoneSpans,
    )

    private fun treeStyleOp(
        treeTicket: TimeTicket,
        pos: CrdtTreePos,
        executedAt: TimeTicket,
    ) = TreeStyleOperation(
        parentCreatedAt = treeTicket,
        fromPos = pos,
        toPos = pos,
        executedAt = executedAt,
        attributes = mapOf("bold" to "true"),
    )

    // ---- replaceTreeNodeId / CrdtTreePos.replaceNodeID (the low-level re-point) ----

    @Test
    fun `replaceTreeNodeId carries the offset from id, never from curr`() {
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 7) // curr's own offset must be ignored
        val id = CrdtTreeNodeID(ticket(3), 2) // same creation ticket as prev, a different offset

        val replaced = replaceTreeNodeId(id, prev, curr)

        assertEquals(ticket(9), replaced.createdAt)
        assertEquals(2, replaced.offset, "the offset comes from id, never from curr")
    }

    @Test
    fun `replaceTreeNodeId returns id unchanged when it names a different node`() {
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val id = CrdtTreeNodeID(ticket(4), 0)

        assertEquals(id, replaceTreeNodeId(id, prev, curr))
    }

    @Test
    fun `CrdtTreePos replaceNodeID re-points the parent and the left sibling independently`() {
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val untouched = CrdtTreeNodeID(ticket(5), 0)
        val pos = CrdtTreePos(parentID = prev, leftSiblingID = untouched)

        val replaced = pos.replaceNodeID(prev, curr)

        assertEquals(curr, replaced.parentID)
        assertEquals(
            untouched,
            replaced.leftSiblingID,
            "a sibling naming a different node is untouched",
        )
    }

    @Test
    fun `CrdtTreePos replaceNodeID returns this when neither anchor names prev`() {
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val pos = CrdtTreePos(
            parentID = CrdtTreeNodeID(ticket(5), 0),
            leftSiblingID = CrdtTreeNodeID(ticket(6), 0),
        )

        assertSame(pos, pos.replaceNodeID(prev, curr))
    }

    // ---- TreeEditOperation.reconcileNodeID ----

    @Test
    fun `TreeEditOperation reconcileNodeID re-points fromPos and toPos`() {
        val treeTicket = ticket(2)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val op = treeEditOp(treeTicket, CrdtTreePos(prev, prev), ticket(4))

        op.reconcileNodeID(prev, curr)

        assertEquals(CrdtTreePos(curr, curr), op.fromPos)
        assertEquals(CrdtTreePos(curr, curr), op.toPos)
    }

    @Test
    fun `TreeEditOperation reconcileNodeID re-points every id in a restore span`() {
        val treeTicket = ticket(2)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val other = CrdtTreeNodeID(ticket(5), 0)
        val span = TreeRestoreSpan(
            id = prev,
            nodeType = "p",
            isText = false,
            length = 0,
            parentID = prev,
            leftSiblingID = other,
            rightSiblingID = prev,
        )
        val op = treeEditOp(
            treeTicket,
            CrdtTreePos(other, other),
            ticket(4),
            restoreSpans = listOf(span),
            restoreMode = RestoreMode.Restore,
        )

        op.reconcileNodeID(prev, curr)

        val reconciled = requireNotNull(op.restoreSpans).single()
        assertEquals(curr, reconciled.id, "span.id is the identity restore() looks the node up by")
        assertEquals(curr, reconciled.parentID)
        assertEquals(
            other,
            reconciled.leftSiblingID,
            "a sibling naming a different node is untouched",
        )
        assertEquals(curr, reconciled.rightSiblingID)
    }

    @Test
    fun `TreeEditOperation reconcileNodeID re-points retombstoneSpans too`() {
        val treeTicket = ticket(2)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val span = TreeRestoreSpan(id = prev, nodeType = "p", isText = false, length = 0)
        val op = treeEditOp(
            treeTicket,
            CrdtTreePos(prev, prev),
            ticket(4),
            restoreMode = RestoreMode.Retombstone,
            retombstoneSpans = listOf(span),
        )

        op.reconcileNodeID(prev, curr)

        assertEquals(curr, requireNotNull(op.retombstoneSpans).single().id)
    }

    @Test
    fun `TreeEditOperation reconcileNodeID leaves a span naming a different node untouched`() {
        val treeTicket = ticket(2)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val other = CrdtTreeNodeID(ticket(5), 0)
        val span = TreeRestoreSpan(id = other, nodeType = "p", isText = false, length = 0)
        val op = treeEditOp(
            treeTicket,
            CrdtTreePos(other, other),
            ticket(4),
            restoreSpans = listOf(span),
            restoreMode = RestoreMode.Restore,
        )

        op.reconcileNodeID(prev, curr)

        assertEquals(other, requireNotNull(op.restoreSpans).single().id)
        assertEquals(CrdtTreePos(other, other), op.fromPos)
    }

    // ---- TreeStyleOperation.reconcileNodeID ----

    @Test
    fun `TreeStyleOperation reconcileNodeID re-points fromPos and toPos`() {
        val treeTicket = ticket(2)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val op = treeStyleOp(treeTicket, CrdtTreePos(prev, prev), ticket(4))

        op.reconcileNodeID(prev, curr)

        assertEquals(CrdtTreePos(curr, curr), op.fromPos)
        assertEquals(CrdtTreePos(curr, curr), op.toPos)
    }

    // ---- History.reconcileTreeNodeID ----

    @Test
    fun `reconcileTreeNodeID re-points a TreeEditOperation entry in the undo stack`() {
        val history = History()
        val treeTicket = ticket(2)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val op = treeEditOp(treeTicket, CrdtTreePos(prev, prev), ticket(4))
        history.pushUndo(listOf(HistoryOperation.Op(op)))

        history.reconcileTreeNodeID(treeTicket, prev, curr)

        assertEquals(CrdtTreePos(curr, curr), op.fromPos)
    }

    @Test
    fun `reconcileTreeNodeID re-points a TreeStyleOperation entry in the redo stack`() {
        val history = History()
        val treeTicket = ticket(2)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val op = treeStyleOp(treeTicket, CrdtTreePos(prev, prev), ticket(4))
        history.pushRedo(listOf(HistoryOperation.Op(op)))

        history.reconcileTreeNodeID(treeTicket, prev, curr)

        assertEquals(CrdtTreePos(curr, curr), op.fromPos)
    }

    @Test
    fun `reconcileTreeNodeID does not re-point an entry targeting a different tree`() {
        val history = History()
        val treeTicket = ticket(2)
        val otherTreeTicket = ticket(20)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val sameTreeOp = treeEditOp(treeTicket, CrdtTreePos(prev, prev), ticket(4))
        val otherTreeOp = treeEditOp(otherTreeTicket, CrdtTreePos(prev, prev), ticket(5))
        history.pushUndo(
            listOf(HistoryOperation.Op(sameTreeOp), HistoryOperation.Op(otherTreeOp)),
        )

        history.reconcileTreeNodeID(treeTicket, prev, curr)

        assertEquals(CrdtTreePos(curr, curr), sameTreeOp.fromPos)
        assertEquals(
            CrdtTreePos(prev, prev),
            otherTreeOp.fromPos,
            "an entry targeting a different tree must be untouched -- a node id is only unique " +
                "within its own tree",
        )
    }

    @Test
    fun `reconcileTreeNodeID ignores a non-tree history operation`() {
        val history = History()
        val treeTicket = ticket(2)
        val prev = CrdtTreeNodeID(ticket(3), 0)
        val curr = CrdtTreeNodeID(ticket(9), 0)
        val nonTreeOp = IncreaseOperation(
            CrdtPrimitive(1, treeTicket),
            treeTicket,
            ticket(4),
        )
        history.pushUndo(listOf(HistoryOperation.Op(nonTreeOp)))

        // must not throw for an operation type reconcileTreeNodeID does not understand
        history.reconcileTreeNodeID(treeTicket, prev, curr)
    }
}
