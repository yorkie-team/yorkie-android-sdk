package dev.yorkie.document.change

import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.document.presence.P
import dev.yorkie.document.presence.Presences.Companion.asPresences
import dev.yorkie.document.time.TimeTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChangeTest {
    private val actor = "000000000000000000000001"

    private fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

    /**
     * `opInfoCounts` must stay in lockstep with `executedOperations` — an
     * operation that does NOT execute (its target cannot be found) must
     * contribute NEITHER an `executedOperations` entry NOR an
     * `opInfoCounts` entry, or `reconcileHistoryEdits`'s index pairing
     * between the two lists drifts for every operation after it, even
     * though the dropped entry's own value would have been 0 either way.
     * A non-executing op in the MIDDLE of the list (not just at the end)
     * must not shift which executed op each retained count belongs to.
     */
    @Test
    fun `opInfoCounts stays aligned with executedOperations when a middle op does not execute`() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)

        val firstSetOp = SetOperation(
            key = "a",
            value = CrdtPrimitive("v", tick(1)),
            parentCreatedAt = obj.createdAt,
            executedAt = tick(1),
        )
        // A RemoveOperation whose parent cannot be found never executes:
        // RemoveOperation.execute's `else` branch reports `executed = false`.
        val removeOp = RemoveOperation(
            createdAt = tick(2),
            parentCreatedAt = TimeTicket.MaxTimeTicket,
            executedAt = tick(2),
        )
        val secondSetOp = SetOperation(
            key = "b",
            value = CrdtPrimitive("w", tick(3)),
            parentCreatedAt = obj.createdAt,
            executedAt = tick(3),
        )

        val change = Change(ChangeID.InitialChangeID, listOf(firstSetOp, removeOp, secondSetOp))

        val result = change.execute(root, emptyMap<String, P>().asPresences())

        assertEquals(2, result.executedOperations.size)
        assertEquals(
            listOf(firstSetOp, secondSetOp),
            result.executedOperations,
            "the skipped middle op must not leave a gap or reorder the survivors",
        )
        assertEquals(
            listOf(1, 1),
            result.opInfoCounts,
            "each retained count must still pair with its own executed op",
        )
    }

    @Test
    fun `a style op targeting a missing parent does not execute`() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val missingNodeId = RgaTreeSplitNodeID(tick(99), 0)

        val styleOp = StyleOperation(
            fromPos = RgaTreeSplitPos(missingNodeId, 0),
            toPos = RgaTreeSplitPos(missingNodeId, 0),
            attributes = mapOf("b" to "1"),
            parentCreatedAt = TimeTicket.MaxTimeTicket,
            executedAt = tick(1),
        )

        val result = styleOp.execute(root, OpSource.Local, null)

        assertFalse(result.executed, "a style op whose parent cannot be found must not execute")
    }

    @Test
    fun `a tree style op targeting a missing parent does not execute`() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val missingNodeId = CrdtTreeNodeID(tick(99), 0)

        val treeStyleOp = TreeStyleOperation(
            parentCreatedAt = TimeTicket.MaxTimeTicket,
            fromPos = CrdtTreePos(missingNodeId, missingNodeId),
            toPos = CrdtTreePos(missingNodeId, missingNodeId),
            executedAt = tick(1),
            attributes = mapOf("b" to "1"),
        )

        val result = treeStyleOp.execute(root, OpSource.Local, null)

        assertFalse(
            result.executed,
            "a tree style op whose parent cannot be found must not execute",
        )
    }
}
