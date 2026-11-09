package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.VersionVector
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * [CrdtRoot]-level removal-ledger GC coverage, ported from JS SDK
 * #1514/yorkie#2310) that the Document-level [dev.yorkie.document.TextUndoRemovalLedgerTest]
 * suite does not reach directly: the prune-before-collect ordering pinned at
 * the [CrdtRoot] API, and a [CrdtRoot.deepCopy] clone registering its own
 * ledger holder independent of the source.
 */
class TextLedgerGcRootTest {

    private val actorA = "000000000000000000000001"
    private val actorB = "000000000000000000000002"

    private fun a(lamport: Long) = TimeTicket(lamport, 0u, actorA)
    private fun b(lamport: Long) = TimeTicket(lamport, 0u, actorB)

    private fun vector(vararg entries: Pair<String, Long>) = VersionVector(entries.toMap())

    /**
     * A one-member object root holding a Text under key "k" with one node
     * "x", registered as removed (a(2)) BEFORE [CrdtRoot] scans it, so its
     * GC pair exists in [root]'s `gcPairMap` the way a real delete would
     * leave it. The caller then evolves the node's ledger directly and must
     * call [CrdtRoot.registerLedgerHolder] itself (the one step
     * [dev.yorkie.document.operation.EditOperation.executeRestore] would
     * otherwise do) to exercise [CrdtRoot.garbageCollect]'s pruning.
     */
    private fun buildRegisteredRoot(): Triple<CrdtRoot, CrdtText, RgaTreeSplitNode<TextValue>> {
        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        node.setRemovedAt(a(2))
        val text = CrdtText(split, InitialTimeTicket)
        val obj = CrdtObject(InitialTimeTicket)
        obj.set("k", text, InitialTimeTicket)
        return Triple(CrdtRoot(obj), text, node)
    }

    @Test
    fun `pruneLedgers runs before collect so a newly-covered cancel is released in one pass`() {
        val (root, text, node) = buildRegisteredRoot()
        // A second removal (b(3)) then a's removal is cancelled: the node
        // stays a real tombstone (removedAt = b(3), one live removal) but
        // its ledger still carries the stale a(2)/cancel pair, so
        // heldFromPurge keeps it until that pair is known everywhere.
        node.recordRemoval(b(3))
        node.cancelRemoval(a(2), b(4))
        root.registerLedgerHolder(text)
        assertEquals(b(3), node.removedAt)
        assertTrue(node.hasCancels())

        // The cancel (b(4)) is not yet covered: collect must hold it, and
        // the GC pass purges nothing.
        assertEquals(0, root.garbageCollect(vector(actorA to 2L, actorB to 3L)))
        assertEquals(1, root.garbageLength)

        // Once the vector covers b(4), pruneLedgers drops the stale a(2)
        // pair BEFORE collect runs, and the single remaining removal (b(3),
        // itself covered) purges in the SAME garbageCollect call -- proof
        // the two run in that order, not pruned-this-pass /
        // purged-next-pass.
        assertEquals(1, root.garbageCollect(vector(actorA to 2L, actorB to 4L)))
        assertEquals(0, root.garbageLength)
    }

    @Test
    fun `deepCopy registers its own ledger holder independent of the source`() {
        val (root, text, node) = buildRegisteredRoot()
        node.recordRemoval(b(3))
        node.cancelRemoval(a(2), b(4))
        root.registerLedgerHolder(text)

        val clone = root.deepCopy()
        val cloneText = clone.rootObject["k"] as CrdtText
        val cloneNode = cloneText.rgaTreeSplit.first { it.id.offset == 0 }
        assertTrue(cloneNode.hasCancels(), "the clone's own node carries the same ledger")

        // The clone must register ITS OWN holder at deepCopy/registerElement
        // time (CrdtText is a data class, so a structural registration
        // would merge or miss distinct instances) -- pruning the clone alone
        // must release its cancel.
        clone.garbageCollect(vector(actorA to 2L, actorB to 4L))
        assertTrue(
            cloneNode.getRemovalEvents().isEmpty(),
            "the clone's ledger prunes independently",
        )

        // The source's own node and root are unaffected by the clone's pass.
        assertTrue(node.hasCancels(), "the source node's ledger is unaffected by the clone's GC")
        assertEquals(1, root.garbageLength)
    }
}
