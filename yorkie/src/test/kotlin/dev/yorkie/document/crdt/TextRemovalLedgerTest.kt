package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports `text_removal_ledger_test.ts` (JS SDK #1514, yorkie#2310) as JVM unit
 * tests: the CRDT-level removal ledger on [RgaTreeSplitNode] and [CrdtText],
 * no sync involved. Each `it.each` sub-case in the JS source is its own
 * `@Test` here for JUnit4 reporting.
 */
class TextRemovalLedgerTest {

    private val actorA = "000000000000000000000001"
    private val actorB = "000000000000000000000002"

    private fun a(lamport: Long) = TimeTicket(lamport, 0u, actorA)
    private fun b(lamport: Long) = TimeTicket(lamport, 0u, actorB)

    private fun vector(vararg entries: Pair<String, Long>) = VersionVector(entries.toMap())

    /** A text node created by actor A at lamport 1, removed at [removedAt]. */
    private fun node(removedAt: TimeTicket? = null): RgaTreeSplitNode<TextValue> {
        return RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x")).apply {
            setRemovedAt(removedAt)
        }
    }

    /** Builds "0123456789" as a single insertion created at [seed]. */
    private fun seededText(seed: TimeTicket): CrdtText {
        val text = CrdtText(RgaTreeSplit(), TimeTicket.InitialTimeTicket)
        text.edit(text.createRange(0, 0), "0123456789", seed)
        assertEquals("0123456789", text.toString())
        return text
    }

    private fun deleteRange(
        text: CrdtText,
        from: Int,
        to: Int,
        at: TimeTicket,
        versionVector: VersionVector? = null,
    ) = text.edit(text.createRange(from, to), "", at, versionVector = versionVector)

    // ---- RgaTreeSplitNode removal events ----

    @Test
    fun `pairs each actor events by place`() {
        // A's removal holds the node; B removed it and undid that.
        val n = node(a(5))
        n.setRemovalEvents(listOf(b(6), b(4)))
        assertEquals(a(5), n.removedAt)
        assertEquals(listOf(b(4), b(6)), n.getRemovalEvents())
    }

    @Test
    fun `rejects malformed events - removed_at repeated`() {
        val n = node(a(5))
        assertFailsWith<YorkieException> { n.setRemovalEvents(listOf(a(5))) }
        assertEquals(a(5), n.removedAt)
        assertTrue(n.getRemovalEvents().isEmpty())
    }

    @Test
    fun `rejects malformed events - event repeated`() {
        val n = node(null)
        assertFailsWith<YorkieException> { n.setRemovalEvents(listOf(a(5), a(5))) }
        assertNull(n.removedAt)
        assertTrue(n.getRemovalEvents().isEmpty())
    }

    @Test
    fun `rejects malformed events - removal without removedAt`() {
        val n = node(null)
        assertFailsWith<YorkieException> { n.setRemovalEvents(listOf(a(5))) }
        assertNull(n.removedAt)
        assertTrue(n.getRemovalEvents().isEmpty())
    }

    @Test
    fun `rejects malformed events - removed_at cancelled`() {
        val n = node(a(5))
        assertFailsWith<YorkieException> { n.setRemovalEvents(listOf(a(3))) }
        assertEquals(a(5), n.removedAt)
        assertTrue(n.getRemovalEvents().isEmpty())
    }

    @Test
    fun `writes nothing when removedAt says it all`() {
        val n = node()
        n.recordRemoval(a(5))
        assertTrue(n.getRemovalEvents().isEmpty())
        assertEquals(a(5), n.removedAt)
    }

    @Test
    fun `split copies the full removal ledger onto both halves`() {
        // Two concurrent, uncancelled deletions -- A's (the later ticket, so it stays
        // `removedAt`) and B's -- force the node into ledgered state: a `data class`'s
        // generated `copy()` only reconstructs the constructor parameters (id, value,
        // removedAt), so a `split()` that skipped the explicit `copyLedger` would start the
        // right half unledgered -- `getRemovalEvents()` would silently come back empty and
        // `heldFromPurge` would read only one deletion where two are recorded.
        val n = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("xy")).apply {
            setRemovedAt(a(5))
        }
        n.setRemovalEvents(listOf(b(3)))
        assertEquals(a(5), n.removedAt)
        assertEquals(listOf(b(3)), n.getRemovalEvents())
        assertTrue(n.heldFromPurge(vector()))

        val right = n.split(1)

        assertEquals(a(5), right.removedAt)
        assertEquals(listOf(b(3)), right.getRemovalEvents())
        assertTrue(right.heldFromPurge(vector()))
        assertEquals(a(5), right.uncancelledRemovalBy(actorA))
        assertEquals(b(3), right.uncancelledRemovalBy(actorB))
    }

    // ---- Text removal ledger ----

    @Test
    fun `returns removed spans in document order`() {
        // Restore anchors each recreated fragment after the one before it, so
        // the reverse of an edit has to list them left to right.
        val text = seededText(a(1))
        listOf(8, 6, 4, 2).forEachIndexed { i, pos ->
            text.edit(text.createRange(pos, pos), "x", a(2L + i))
        }
        assertEquals("01x23x45x67x89", text.toString())

        val result = deleteRange(text, 0, 14, a(10))
        assertEquals("01x23x45x67x89", result.removedSpans.joinToString("") { it.value.content })
    }

    @Test
    fun `keeps one uncancelled removal per actor`() {
        // A removal by an actor that already holds an uncancelled one is not
        // recorded: only a change that leaves its own earlier removal out of
        // its vector can send it.
        val text = seededText(a(1))
        deleteRange(text, 2, 4, a(2))

        // The same actor removes [1,3) of "01456789" again, with a vector
        // that knows the insertion but not its own removal of "23".
        deleteRange(text, 1, 3, a(5), vector(actorA to 1L))

        val node = text.rgaTreeSplit.first { it.id.offset == 2 }
        assertEquals(a(2), node.removedAt)
        assertTrue(node.getRemovalEvents().isEmpty())
    }

    @Test
    fun `cancels only the undoing actor removal`() {
        // A and B remove "2" concurrently, B with the later ticket. A's undo
        // has to cancel A's removal, not the latest one, and leave B holding
        // it.
        val text = seededText(a(1))
        val range = text.createRange(2, 3)
        deleteRange(text, 2, 3, a(2))
        text.edit(range, "", b(3), versionVector = vector(actorA to 1L))
        assertEquals("013456789", text.toString())

        text.restore(
            listOf(RestoreSpan(a(1), 2, 3, TextValue("2"))),
            a(4),
        )
        assertEquals("013456789", text.toString())
        val node = text.rgaTreeSplit.first { it.id.offset == 2 }
        assertNull(node.uncancelledRemovalBy(actorA))
        assertEquals(b(3), node.uncancelledRemovalBy(actorB))
    }

    @Test
    fun `reports nothing tombstoned by a redo of a removed piece`() {
        // A redo that re-removes a span another actor already tombstoned --
        // by a deletion the redo never saw -- splits that tombstone and hands
        // back the born-dead remainders as GC pairs, but nothing went live to
        // dead. It still records the redo on the piece, which the reverse
        // names.
        val seed = a(1)
        val text = seededText(seed)
        deleteRange(text, 2, 8, b(2))
        assertEquals("0189", text.toString())

        val result = text.retombstone(
            listOf(RestoreSpan(seed, 4, 6, TextValue("45"))),
            a(3),
            vector(actorA to 3L),
        )
        assertTrue(result.textChanges.isEmpty())
        assertTrue(result.gcPairs.isNotEmpty())
        assertEquals(1, result.recorded.size)
    }
}
