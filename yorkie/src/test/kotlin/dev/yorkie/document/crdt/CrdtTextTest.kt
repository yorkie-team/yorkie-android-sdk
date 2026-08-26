package dev.yorkie.document.crdt

import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class CrdtTextTest {
    private lateinit var target: CrdtText

    @Before
    fun setUp() {
        target = CrdtText(RgaTreeSplit(), TimeTicket.InitialTimeTicket)
    }

    @Test
    fun `should handle edit operations with attributes`() {
        target.edit(
            target.indexRangeToPosRange(0, 0),
            "ABCD",
            TimeTicket.InitialTimeTicket,
            mapOf("b" to "1"),
        )
        assertEquals(
            """[{"attrs":{"b":"1"},"val":"ABCD"}]""",
            target.toJson(),
        )

        target.edit(target.indexRangeToPosRange(3, 3), "\n", TimeTicket.InitialTimeTicket)
        assertEquals(
            """[{"attrs":{"b":"1"},"val":"ABC"},{"val":"\n"},""" +
                """{"attrs":{"b":"1"},"val":"D"}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should handle edit operations without attributes`() {
        target.edit(target.indexRangeToPosRange(0, 0), "A", TimeTicket.InitialTimeTicket)
        assertEquals("""[{"val":"A"}]""", target.toJson())

        target.edit(target.indexRangeToPosRange(0, 0), "B", TimeTicket.InitialTimeTicket)
        assertEquals(
            """[{"val":"A"},{"val":"B"}]""",
            target.toJson(),
        )
    }

    // F12: Rht.set's live-overwrite branch must hand back a tombstoned copy
    // of the overwritten value so its bytes leave docSize.live — before the
    // fix it returned null, so the first style's value stayed phantom-live
    // forever and was never reachable by any GC pass.
    @Test
    fun `style overwrite of the same attribute leaves docSize live and is GC-reachable`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val editResult = text.edit(text.indexRangeToPosRange(0, 0), "01", tick(1))
        root.acc(editResult.dataSize)

        val firstStyle = text.style(text.indexRangeToPosRange(0, 2), mapOf("b" to "1"), tick(2))
        root.acc(firstStyle.dataSize)
        firstStyle.gcPairs.forEach(root::registerGCPair)
        assertEquals(
            0,
            root.garbageLength,
            "the first style has no predecessor to tombstone yet",
        )

        // Overwrite the same key — the first value's bytes must move into
        // gc, not stay double-counted as live forever. Comparing live
        // before/after would be misleading here: the new "2" value re-adds
        // the same number of bytes the old "1" value's tombstone removes
        // (equal-length overwrite), netting to zero net change in live even
        // though the fix is working — so pin the gc-side movement instead.
        val secondStyle = text.style(text.indexRangeToPosRange(0, 2), mapOf("b" to "2"), tick(3))
        root.acc(secondStyle.dataSize)
        secondStyle.gcPairs.forEach(root::registerGCPair)

        assertTrue(
            root.docSize.gc.data > 0,
            "the first style's overwritten value must move into docSize.gc, " +
                "not linger as live forever",
        )
        assertEquals(1, root.garbageLength, "the overwritten value must be reachable by GC")
        assertEquals(1, root.garbageCollect(maxVectorOf(listOf(actor))))
        assertEquals(0, root.garbageLength)
    }

    // E4: an already-removed owning node's single outer GCPair (gcOnlySize =
    // node.dataSize) already covers every attribute's bytes (TextValue's
    // getDataSize sums them in) — re-adding each attribute's own pair on top
    // double-counts them into docSize.gc.
    @Test
    fun `gcPairs does not double-count an attribute tombstone whose owning node is removed`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val split = RgaTreeSplit<TextValue>()
        val value = TextValue("cd").apply { setAttribute("bold", "true", tick(1)) }
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(tick(0), 0), value)
        split.insertAfter(split.head, node)
        node.remove(tick(2))

        val text = CrdtText(split, tick(3))

        val pairs = text.gcPairs
        assertEquals(
            1,
            pairs.size,
            "the owning node's single pair already covers its attribute bytes",
        )
        assertEquals(node.dataSize, pairs.single().gcOnlySize)
    }

    // F13: removeStyle (not a same-length style overwrite — Rht.set's
    // tombstoned copy is handed to the caller for GC registration but is
    // never itself stored back into the node's own attribute map, see F12)
    // leaves a genuine tombstoned RhtNode inside the still-live "cd" node's
    // own attributes. Deleting and GC'ing "cd" purges it; undo recreates it
    // via subSequence, which deliberately preserves the copied attribute
    // tombstone verbatim. CrdtText.restore() must register that copied
    // tombstone or its bytes are never reachable by any future GC pass.
    @Test
    fun `restore registers a recreated node's copied attribute tombstone for later GC`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val editResult = text.edit(text.indexRangeToPosRange(0, 0), "abcdef", tick(1))
        root.acc(editResult.dataSize)

        val styleResult =
            text.style(text.indexRangeToPosRange(2, 4), mapOf("bold" to "true"), tick(2))
        root.acc(styleResult.dataSize)
        styleResult.gcPairs.forEach(root::registerGCPair)

        val removeStyleResult =
            text.removeStyle(text.indexRangeToPosRange(2, 4), listOf("bold"), tick(3))
        removeStyleResult.gcPairs.forEach(root::registerGCPair)

        val deleteResult = text.edit(text.indexRangeToPosRange(2, 4), "", tick(4))
        root.acc(deleteResult.dataSize)
        deleteResult.gcPairs.forEach(root::registerGCPair)

        // Purge "cd" (and its now-orphaned bold tombstone) so restore() must
        // recreate it from scratch rather than un-tombstoning it in place.
        root.garbageCollect(maxVectorOf(listOf(actor)))
        assertEquals(0, root.garbageLength)

        val restoreResult = text.restore(deleteResult.removedSpans, tick(5))
        assertEquals(1, restoreResult.recreated.size)
        assertEquals(
            1,
            restoreResult.pendingGcPairs.size,
            "the recreated node's copied bold tombstone must be registered, not silently dropped",
        )
        restoreResult.pendingGcPairs.forEach(root::registerGCPair)

        assertEquals(1, root.garbageLength)
        assertEquals(1, root.garbageCollect(maxVectorOf(listOf(actor))))
        assertEquals(0, root.garbageLength)
    }

    // F11: the first of style's two sequential findNodeWithSplit calls can
    // already have buffered a born-dead split piece (splitting an
    // already-tombstoned node) before the second throws; StyleOperation must
    // drain and register it before propagating, not leave it stuck in
    // RgaTreeSplit's own buffer.
    @Test
    fun `StyleOperation drains a pending GC pair even when the second split throws`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)

        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val text = CrdtText(RgaTreeSplit(), tick(0))
        root.registerElement(text, obj)

        val t1 = tick(1)
        text.edit(text.indexRangeToPosRange(0, 0), "0123456789", t1)
        val deleteResult = text.edit(text.indexRangeToPosRange(4, 6), "", tick(2))
        deleteResult.gcPairs.forEach(root::registerGCPair)
        val garbageBefore = root.garbageLength

        // toPos lands INSIDE the tombstoned "45" node (id offset 5): splitting
        // it buffers a born-dead piece. fromPos is a nonexistent position, so
        // the second findNodeWithSplit call throws before StyleOperation ever
        // gets a TextStyleResult back.
        val insideTombstone = RgaTreeSplitPos(RgaTreeSplitNodeID(t1, 5), 0)
        val nonexistent = RgaTreeSplitPos(RgaTreeSplitNodeID(TimeTicket.MaxTimeTicket, 0), 0)

        val op = StyleOperation(
            fromPos = nonexistent,
            toPos = insideTombstone,
            attributes = mapOf("b" to "1"),
            parentCreatedAt = text.createdAt,
            executedAt = tick(3),
        )

        assertFailsWith<NoSuchElementException> {
            op.execute(root, OpSource.Local, null)
        }

        assertEquals(
            garbageBefore + 1,
            root.garbageLength,
            "the born-dead piece must be registered even though the operation threw",
        )
        assertTrue(
            text.rgaTreeSplit.drainPendingGcPairs().isEmpty(),
            "the buffer must already be drained, not left for a future caller to double-register",
        )
    }
}
