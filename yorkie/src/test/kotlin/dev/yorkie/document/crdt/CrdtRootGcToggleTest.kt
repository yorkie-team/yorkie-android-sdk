package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.util.DataSize
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.subDataSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Step 1b (spec 032, AC1, DC-B): [CrdtRoot.registerGCPair]'s toggle branch
 * (the SAME child registered twice) must release the first registration's
 * gc charge — ported from yorkie-js-sdk 190204f8 (`root.ts`), which adds
 * exactly one `subDataSize(docSize.gc, prev.gcOnlySize ?? prev.child.getDataSize())`
 * line to the toggle and touches nothing else.
 */
class CrdtRootGcToggleTest {

    private val actor = "000000000000000000000001"

    private fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

    @Test
    fun `toggle releases exactly the first registration's gc charge for an RhtNode child`() {
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val rht = Rht()
        // A removed RhtNode — e.g. CrdtText/CrdtTree book one of these via
        // `Rht.remove` when an attribute is removed from a live key.
        rht.set("bold", "true", tick(1))
        rht.remove("bold", tick(2))
        val tombstone = rht.getNodeMapByKey().getValue("bold")
        // CrdtTreeNode implements GCParent<RhtNode>; any such parent works.
        val node = CrdtTreeNode(CrdtTreeNodeID(tick(0), 0), "text")

        val gcBefore = root.docSize.gc
        val liveBefore = root.docSize.live

        // First registration: moves the tombstone's bytes from live to gc.
        root.registerGCPair(GCPair(node, tombstone))
        assertEquals(addDataSizes(gcBefore, tombstone.dataSize), root.docSize.gc)

        // Second registration of the SAME identity (toggle, e.g. the key was
        // revived by a newer write superseding this very tombstone): gc
        // returns to exactly its pre-first-registration value.
        root.registerGCPair(GCPair(node, tombstone))
        assertEquals(gcBefore, root.docSize.gc)
        // Measured: the toggle does not touch live at all — it only
        // reverses what the FIRST registration added to gc. Live stays at
        // whatever the first registration left it (reduced by the
        // tombstone's size), because the toggle's JS source (190204f8) adds
        // only a gc subtraction and nothing else.
        assertEquals(subDataSize(liveBefore, tombstone.dataSize), root.docSize.live)
    }

    // The only non-RhtNode gc child is an array dead-position node
    // (CrdtRoot.kt:119-122). Measures — rather than assumes — whether
    // the toggle must also reverse the extra TIME_TICKET_SIZE a non-RhtNode
    // child's FIRST registration adds to LIVE (registerGCPair:394-401),
    // since `unregisterAccounting` (the EXPLICIT-unregister reference point,
    // not this toggle) reverses that ticket but ALSO moves size back to
    // live, which this toggle must NOT do.
    //
    // RESULT: ported from the JS 190204f8 diff directly (not guessed) — JS's
    // toggle fix is ONE line, `subDataSize(docSize.gc, ...)`, with no
    // RhtNode-vs-not branching and no ticket adjustment. Measured here: gc
    // returns FULLY to its pre-first-registration value; live does NOT —
    // it stays short by exactly one TIME_TICKET_SIZE, the extra ticket the
    // first registration added to live for a non-RhtNode child, which only
    // the explicit `unregisterGCPair` (not this toggle) ever reverses.
    @Test
    fun `toggle for a non-RhtNode child releases gc fully but leaves live short one ticket`() {
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val array = CrdtArray(tick(1))
        val a = CrdtPrimitive("a", tick(2))
        val b = CrdtPrimitive("b", tick(3))
        listOf(a, b).forEach { array.insertAfter(array.lastCreatedAt, it) }
        // a's old position becomes a dead position node (non-RhtNode GCChild).
        array.moveAfter(b.createdAt, a.createdAt, tick(4))
        val deadNode = array.getAllRGANodes().single { it.elementEntry == null }

        val gcBefore = root.docSize.gc
        val liveBefore = root.docSize.live

        root.registerGCPair(GCPair(array.getRGATreeList(), deadNode))
        val liveAfterFirst = root.docSize.live
        assertEquals(addDataSizes(gcBefore, deadNode.dataSize), root.docSize.gc)

        root.registerGCPair(GCPair(array.getRGATreeList(), deadNode))

        // gc: fully released back to baseline.
        assertEquals(gcBefore, root.docSize.gc)
        // live: untouched by the toggle — stays at whatever the first
        // registration left it (short one TIME_TICKET_SIZE vs baseline, the
        // asymmetric ticket registerGCPair:394-401 adds for a non-RhtNode
        // child and which this toggle does not reverse).
        assertEquals(liveAfterFirst, root.docSize.live)
        assertEquals(
            DataSize(data = liveBefore.data, meta = liveBefore.meta - TIME_TICKET_SIZE),
            root.docSize.live,
        )
    }
}
