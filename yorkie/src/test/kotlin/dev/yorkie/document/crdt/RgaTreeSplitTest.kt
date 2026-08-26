package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test

class RgaTreeSplitTest {
    private lateinit var target: RgaTreeSplit<TextValue>

    @Before
    fun setUp() {
        target = RgaTreeSplit()
    }

    @Test
    fun `should handle edit operations with case1`() {
        var range = RgaTreeSplitPosRange(target.indexToPos(0), target.indexToPos(0))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("ABCD"), versionVector = null)
        range = RgaTreeSplitPosRange(target.indexToPos(1), target.indexToPos(3))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("12"), versionVector = null)

        assertEquals("A12D", target.toString())
    }

    @Test
    fun `should handle edit operations with case2`() {
        var range = RgaTreeSplitPosRange(target.indexToPos(0), target.indexToPos(0))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("ABCD"), versionVector = null)
        range = RgaTreeSplitPosRange(target.indexToPos(3), target.indexToPos(3))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("\n"), versionVector = null)

        assertEquals("ABC\nD", target.toString())
    }

    // F5: removedSpans (unlike gcPairs) must NOT be filtered by
    // alreadyRemovedIDs. A node only lands in alreadyRemovedIDs via
    // canRemove()'s LWW-won-concurrent-overwrite case, meaning THIS op's
    // timestamp is causally after the existing tombstone — it legitimately
    // becomes the node's new causal owner, so its identity belongs in the
    // undo/restorable set. Only the GC-pair bookkeeping skips it, to avoid
    // double-toggling an already-registered pair. Matches JS SDK
    // rga_tree_split.ts's edit(), which does not filter removedSpans either.
    //
    // Reaching the alreadyRemovedIDs branch requires a version vector that
    // knows the node's creation (creationKnown) but not yet its specific
    // removal (tombstoneKnown=false) — a superset delete whose own version
    // vector is causally behind an already-applied tombstone on part of its
    // range.
    @Test
    fun `removedSpans include an already-removed node this op newly owns`() {
        val actor1 = "000000000000000000000001"
        val actor2 = "000000000000000000000002"
        fun tick(actor: String, lamport: Long) = TimeTicket(lamport, 0u, actor)

        // actor1 creates "0123456789" at lamport 1, then deletes "45"
        // ([4,6)) at lamport 2 — both local (no version vector yet).
        target.edit(
            RgaTreeSplitPosRange(target.indexToPos(0), target.indexToPos(0)),
            tick(actor1, 1),
            TextValue("0123456789"),
            versionVector = null,
        )
        target.edit(
            RgaTreeSplitPosRange(target.indexToPos(4), target.indexToPos(6)),
            tick(actor1, 2),
            null,
            versionVector = null,
        )

        // actor2's superset delete [2,6) ("2367" by visible index) carries a
        // version vector that knows actor1's creation (lamport 1) but NOT
        // yet actor1's removal (lamport 2) — genuinely concurrent from
        // actor2's causal perspective, so it becomes "45"'s new owner too.
        val vector = VersionVector(mapOf(actor1 to 1L))
        val result = target.edit(
            RgaTreeSplitPosRange(target.indexToPos(2), target.indexToPos(6)),
            tick(actor2, 3),
            null,
            vector,
        )

        assertEquals("0189", target.toString())
        assertEquals(
            listOf("23", "45", "67"),
            result.removedSpans.map { it.value.content },
            "removedSpans must include the already-removed node this op newly owns",
        )
        assertEquals(
            listOf("23", "67"),
            result.gcPairs.map { it.child.value.content },
            "gcPairs must still exclude it, to avoid double-toggling its existing registration",
        )
    }
}
