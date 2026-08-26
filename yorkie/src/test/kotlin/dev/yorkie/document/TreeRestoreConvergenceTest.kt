package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Regression tests for [dev.yorkie.document.crdt.CrdtTree]/`TreeEditOperation`
 * split-count and GC-identity fixes (spec 010, AC1, AC16).
 */
class TreeRestoreConvergenceTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    // F1: index 0 in `<doc><p>x</p></doc>` resolves directly to the tree's
    // own root element as fromParent (no element sits strictly between the
    // root and this boundary), so a requested splitLevel of 1 hits the
    // "reached tree root" guard on its only iteration and produces zero
    // real splits. Before the fix, the walk still emitted a TreeChange (and
    // therefore a reverse op) for this no-op, so undoing it would try to
    // delete boundary tokens that were never inserted, corrupting the tree.
    @Test
    fun `zero-split edit at the tree root pushes no undo entry`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("doc") { element("p") { text { "x" } } })
        }.await()

        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(0, 0, 1)
        }.await()

        assertEquals(
            "<doc><p>x</p></doc>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )

        // setNewTree itself is undo-able, so canUndo() is always true here —
        // the load-bearing check is that the zero-split edit pushed NO
        // separate entry of its own: a single undo must reverse the
        // ORIGINAL tree creation, not some phantom boundary-deletion of
        // tokens that were never actually inserted.
        document.history.undoAsync().await()
        assertNull(
            document.getRoot().getOrNull("t"),
            "a splitLevel walk that performed zero real splits must not push its own reverse op",
        )
    }

    // E2: a registered tree node's data-class hash covers mutable state
    // (childNodes, attributes). A concurrent remote edit that mutates a
    // registered node's children (e.g. splitting a tombstoned child)
    // relocates it to a different HashMap bucket, so a later hash-keyed
    // lookup by the SAME node instance misses. Hashing by the immutable id
    // instead keeps the bucket stable, mirroring RgaTreeSplitNode.
    @Test
    fun `CrdtTreeNode hashCode is stable across mutation so hash-keyed lookups survive`() {
        val id = CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 0)
        val node = CrdtTreeElement(id, "p")
        val map = HashMap<CrdtTreeNode, String>()
        map[node] = "registered"

        // Mutate the node's children AFTER it was used as a hash key.
        node.append(
            CrdtTreeElement(
                CrdtTreeNodeID(TimeTicket(1L, 0u, "actor-0"), 0),
                "span",
            ),
        )

        assertEquals(
            "registered",
            map[node],
            "hashing by immutable id must survive a mutation of childNodes",
        )
    }

    /**
     * Builds two replicas holding `<root><p>hello</p></root>` where d1
     * deletes the whole `<p>` and d2 concurrently inserts "X" inside
     * "hello". The remote insert splits the tombstoned text under the
     * already-registered `<p>`, mutating its child list.
     */
    private suspend fun buildDeleteWithConcurrentSplit(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { element("p") { text { "hello" } } })
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 7) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(3, 3, text { "X" }) }.await()
        crossSync(d1, d2)
        return d1 to d2
    }

    // Tree undo/redo rebuilds fresh nodes rather than reviving tombstones by
    // identity (unlike Text's identity-preserving restore), so undo/redo of
    // a delete whose remote counterpart concurrently split a registered
    // node must still leave both replicas purge-symmetric — a stale
    // gcPairMap entry (E2) would desync the two counts.
    @Test
    fun `undo redo of a concurrently-split delete purges symmetrically on both replicas`() =
        runTest {
            val (d1, d2) = buildDeleteWithConcurrentSplit()
            assertEquals(4, d1.garbageLength, "p, \"he\", \"llo\", and X are all tombstoned")

            d1.history.undoAsync().await()
            assertEquals(
                "<root><p>hello</p></root>",
                d1.getRoot().getAs<JsonTree>("t").toXml(),
                "undo rebuilds fresh nodes rather than reviving the tombstones by identity",
            )

            d1.history.redoAsync().await()
            assertEquals("<root></root>", d1.getRoot().getAs<JsonTree>("t").toXml())

            // d2 never undid, so it only ever tombstoned the original 4.
            val vector = maxVectorOf(listOf(actor1, actor2))
            val purged1 = d1.garbageCollect(vector)
            val purged2 = d2.garbageCollect(vector)

            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)
            assertEquals(d2.getDocSize(), d1.getDocSize())
            assertTrue(purged1 >= 4 && purged2 >= 4, "purged1=$purged1 purged2=$purged2")
        }
}
