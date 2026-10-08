package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.subDataSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CrdtRoot.registerGCPair]'s toggle branch (the SAME child registered
 * twice) must release the first registration's gc charge — ported from
 * yorkie-js-sdk 190204f8 (`root.ts`), which adds exactly one
 * `subDataSize(docSize.gc, prev.gcOnlySize ?? prev.child.getDataSize())`
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
    // (CrdtRoot.kt:119-122). Every production call site that registers one
    // (MoveOperation.execute, CrdtRoot's construction-time scan) always
    // passes an explicit `gcOnlySize = deadNode.dataSize`, so a toggle test
    // for this shape must register the same way rather than relying on the
    // parameter's default -- the default (null) path takes a different
    // branch in [CrdtRoot.registerGCPair] (it ALSO moves the child's size
    // out of live and adds a stray TIME_TICKET_SIZE), one no real call site
    // reaches.
    //
    // The toggle's non-RhtNode branch (`prev.gcOnlySize ?:
    // prev.child.dataSize`) must release the STORED first-registration
    // gcOnlySize, not whatever the child's dataSize happens to be NOW. The
    // one real non-RhtNode case (a dead array position node) can never
    // actually tell the two formulas apart -- its dataSize is a fixed
    // TIME_TICKET_SIZE*2 the moment it is dead and never changes afterward,
    // so gcOnlySize and the child's current dataSize are always equal for
    // it. This test exercises the written contract directly, independent
    // of that coincidence: register the SAME non-RhtNode child twice with
    // an explicit, deliberately mismatched gcOnlySize and confirm the
    // toggle releases exactly the stored amount -- and that live, which the
    // `gcOnlySize` registration path never touches (the child's size was
    // never counted there to begin with), stays untouched throughout.
    @Test
    fun `toggle for a non-RhtNode child releases the STORED gcOnlySize, not its current size`() {
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))
        val array = CrdtArray(tick(1))
        val a = CrdtPrimitive("a", tick(2))
        val b = CrdtPrimitive("b", tick(3))
        listOf(a, b).forEach { array.insertAfter(array.lastCreatedAt, it) }
        array.moveAfter(b.createdAt, a.createdAt, tick(4))
        val deadNode = array.getAllRGANodes().single { it.elementEntry == null }

        val gcBefore = root.docSize.gc
        val liveBefore = root.docSize.live
        // Deliberately different from deadNode.dataSize so the two
        // candidate formulas disagree.
        val staleGcOnlySize = addDataSizes(deadNode.dataSize, DataSize(data = 11, meta = 7))

        root.registerGCPair(GCPair(array.getRGATreeList(), deadNode, gcOnlySize = staleGcOnlySize))
        assertEquals(addDataSizes(gcBefore, staleGcOnlySize), root.docSize.gc)
        assertEquals("gcOnlySize never moves anything out of live", liveBefore, root.docSize.live)

        // Toggle: the SAME identity registered again (gcOnlySize on this
        // second call is irrelevant -- only the FIRST registration's stored
        // value is ever read on a toggle).
        root.registerGCPair(GCPair(array.getRGATreeList(), deadNode))

        assertEquals(
            "the toggle must release the stored gcOnlySize, not the child's current dataSize",
            gcBefore,
            root.docSize.gc,
        )
        assertEquals("the toggle never touches live either", liveBefore, root.docSize.live)
    }

    /**
     * The OTHER site that registers a dead array position node with
     * `gcOnlySize` -- [CrdtRoot]'s own construction-time scan
     * (`CrdtRoot.kt:117-128`), reached only when a root is built from an
     * already-decoded structure (a snapshot load, or a from-scratch rebuild
     * for a ledger comparison), never during a live move (that is the
     * toggle tests above, a different call site). [RgaTreeList.addDeadPosition]
     * is the exact shape `ElementConverter.kt` restores a decoded move's
     * displaced marker into, so this builds the scenario directly rather
     * than performing a real move -- a real move also sets the moved
     * element's own `movedAt`, which [CrdtElement.getDataSize] folds in and
     * would confound this comparison with an unrelated, pre-existing
     * ticket-accounting gap (tracked separately, not this site).
     */
    @Test
    fun `root construction charges a snapshot-restored dead array position node to gc only`() {
        fun buildObject(withDeadNode: Boolean): CrdtObject {
            return CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()).apply {
                set("arr", array(withDeadNode), tick(5))
            }
        }

        val deadNodeSize = array(withDeadNode = true).getRGATreeList()
            .allNodes().single { it.elementEntry == null }.dataSize

        val withoutDead = CrdtRoot(buildObject(withDeadNode = false))
        val withDead = CrdtRoot(buildObject(withDeadNode = true))

        // CrdtRoot's construction-time scan for a dead array position node
        // (CrdtRoot.kt:117-128) must never subtract it from live -- it was
        // never counted there (registerLive only visits elements, never a
        // bare position node).
        assertEquals(withoutDead.docSize.live, withDead.docSize.live)
        // The dead position node's own size must be charged to gc exactly once.
        assertEquals(addDataSizes(withoutDead.docSize.gc, deadNodeSize), withDead.docSize.gc)
    }

    private fun array(withDeadNode: Boolean): CrdtArray {
        val array = CrdtArray(tick(1))
        array.insertAfter(array.lastCreatedAt, CrdtPrimitive("a", tick(2)))
        if (withDeadNode) {
            array.getRGATreeList().addDeadPosition(tick(3), tick(4))
        }
        return array
    }
}
