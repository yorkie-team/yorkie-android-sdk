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
 * split-count and GC-identity fixes (spec 010, AC1, AC16; spec 011 B1/AC1
 * extends the split-count coverage to the PARTIAL, non-zero case).
 *
 * Split out of `TreeRestoreConvergenceTest` when the v0.7.14 sync-up added
 * its own same-named suite (the `history_tree_test.ts` GC-symmetry ports);
 * the two cover different concerns and together exceed the file-size policy.
 */
class TreeSplitUndoConvergenceTest {

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

    // Spec 011 B1: a PARTIAL (not zero) split still root-stops. CrdtTree
    // records the actual per-op split count on the TreeChange/TreeEditOpInfo
    // (`actualSplitLevel`), but TreeEditOperation.execute previously read the
    // REQUESTED splitLevel field for isPureSplit/boundarySize/redoSplitLevel
    // — so the reverse op deleted 2*requestedSplitLevel boundary tokens when
    // the walk actually produced fewer, eating into content the walk never
    // split. "ab" inside <p> at index2, requesting splitLevel=2: level 1
    // splits "p" (actual=1); level 2 would split "doc", but "doc" is the
    // tree root, so the walk stops there (actual stays 1).
    @Test
    fun `partial split reverse deletes only the boundary tokens actually inserted`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("doc") { element("p") { text { "ab" } } })
        }.await()

        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 2, 2) }.await()
        assertEquals(
            "<doc><p>a</p><p>b</p></doc>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )

        document.history.undoAsync().await()
        assertEquals(
            "<doc><p>ab</p></doc>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
            "undo of a partial split must delete exactly the boundary tokens the walk" +
                " actually inserted (actual=1), not the requested splitLevel's worth (2)",
        )
    }

    // Deeper case: "ab" inside <b> inside <p>, requesting splitLevel=3.
    // Level 1 splits "b" (actual=1), level 2 splits "p" (actual=2), level 3
    // would split "doc" — root-stop, actual stays 2.
    @Test
    fun `deeper partial split reverse round-trips at its actual level`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") { element("p") { element("b") { text { "ab" } } } },
            )
        }.await()

        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(3, 3, 3) }.await()
        assertEquals(
            "<doc><p><b>a</b></p><p><b>b</b></p></doc>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )

        document.history.undoAsync().await()
        assertEquals(
            "<doc><p><b>ab</b></p></doc>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
            "a deeper partial split must round-trip on its actual (not requested) level too",
        )
    }

    // Two-client pair (constitution C9): replica A performs the partial
    // split and its undo entirely locally, then cross-syncs — both replicas
    // must converge back to the original XML.
    @Test
    fun `two replicas converge after a partial split is undone`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree("t", element("doc") { element("p") { text { "ab" } } })
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 2, 2) }.await()
        assertEquals("<doc><p>a</p><p>b</p></doc>", d1.getRoot().getAs<JsonTree>("t").toXml())

        d1.history.undoAsync().await()
        assertEquals("<doc><p>ab</p></doc>", d1.getRoot().getAs<JsonTree>("t").toXml())

        crossSync(d1, d2)

        assertEquals(
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            d2.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertEquals("<doc><p>ab</p></doc>", d2.getRoot().getAs<JsonTree>("t").toXml())
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
