package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports the GC-symmetry and DocSize-exactness cases from `history_tree_test.ts`
 * (JS SDK fa6cc513) as JVM unit tests (AC9).
 */
class TreeRestoreConvergenceTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    /**
     * The live node-identity sequence of the tree, in postorder. Two
     * replicas converging must match on this (not just on rendered XML) —
     * same-[CrdtTreeNodeID] structural equality is the load-bearing check.
     */
    private fun identitySequence(tree: CrdtTree): List<CrdtTreeNodeID> = buildList {
        tree.indexTree.traverse { node, _ -> add(node.id) }
    }

    /**
     * Builds two replicas that both hold `<root>0123456789</root>`, then
     * concurrently delete overlapping ranges — d1 deletes "45" (indices
     * 4..6), d2 deletes the superset "234567" (indices 2..8) — and
     * cross-syncs to the converged "0189". Each replica keeps its own
     * delete on its undo stack.
     */
    private suspend fun buildOverlappingDeletes(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree("t", element("root"))
        }.await()
        crossSync(d1, d2)
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 0, text { "0123456789" }) }
            .await()
        crossSync(d1, d2)

        // delete "45"
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(4, 6) }.await()
        // delete "234567"
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 8) }.await()
        crossSync(d1, d2)

        assertEquals("<root>0189</root>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            d2.getRoot().getAs<JsonTree>("t").toXml(),
        )
        return d1 to d2
    }

    // The feature's motivating case: two clients concurrently undo
    // overlapping deletions. The undos are identity-addressed, so restoring
    // both must revive the original insertion exactly once (a set-union of
    // the two restored ranges), converging to identical content AND
    // identical node ids on both replicas regardless of order.
    private suspend fun runBothUndos(undoD1First: Boolean): Pair<Document, Document> {
        val (d1, d2) = buildOverlappingDeletes()
        if (undoD1First) {
            d1.history.undoAsync().await()
            crossSync(d1, d2)
            d2.history.undoAsync().await()
        } else {
            d2.history.undoAsync().await()
            crossSync(d1, d2)
            d1.history.undoAsync().await()
        }
        crossSync(d1, d2)
        return d1 to d2
    }

    @Test
    fun `converges when both replicas undo overlapping deletes d1 first`() = runTest {
        val (d1, d2) = runBothUndos(undoD1First = true)
        assertEquals("<root>0123456789</root>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(
            identitySequence(d1.crdtTree()),
            identitySequence(d2.crdtTree()),
            "both replicas must converge to identical content AND node ids",
        )
    }

    @Test
    fun `converges to the same state under the opposite undo order d2 first`() = runTest {
        val (a1, a2) = runBothUndos(undoD1First = true)
        val (b1, b2) = runBothUndos(undoD1First = false)
        assertEquals("<root>0123456789</root>", a1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals("<root>0123456789</root>", b1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(identitySequence(a1.crdtTree()), identitySequence(a2.crdtTree()))
        assertEquals(identitySequence(b1.crdtTree()), identitySequence(b2.crdtTree()))
    }

    @Test
    fun `purges symmetrically with docSize gc drained after both undos`() = runTest {
        val (d1, d2) = runBothUndos(undoD1First = true)
        val vector = maxVectorOf(listOf(actor1, actor2))

        val purged1 = d1.garbageCollect(vector)
        val purged2 = d2.garbageCollect(vector)
        assertEquals(purged1, purged2, "both replicas must purge the same count")
        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)

        assertEquals(
            DataSize(0, 0),
            d1.getDocSize().gc,
            "every revived node must leave docSize.gc empty",
        )
        assertEquals(
            DataSize(0, 0),
            d2.getDocSize().gc,
            "every revived node must leave docSize.gc empty",
        )
    }

    // unregisterGCPair (revive) must reverse registerGCPair (tombstone) bit
    // for bit, including the TimeTicketSize meta term, or docSize drifts
    // across undo/redo cycles.
    @Test
    fun `reverses GC accounting exactly across delete undo redo undo`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root"))
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>(
                "t",
            ).edit(0, 0, text { "0123456789" })
        }
            .await()

        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(4, 6) }.await()
        val deleted = document.getDocSize()
        assertEquals(false, deleted.gc == DataSize(0, 0), "delete registers GC")

        document.history.undoAsync().await()
        val revived = document.getDocSize()
        assertEquals(
            DataSize(0, 0),
            revived.gc,
            "revive must drain the tombstoned size out of gc, including the meta term",
        )

        document.history.redoAsync().await()
        assertEquals(
            deleted,
            document.getDocSize(),
            "redo must reproduce the tombstoned docSize exactly, including meta",
        )

        document.history.undoAsync().await()
        assertEquals(
            revived,
            document.getDocSize(),
            "the revived docSize is bit-identical across cycles, including meta",
        )
    }

    /**
     * Builds two replicas holding `<root><p>hello</p></root>` where d1
     * deletes the whole `<p>` and d2 concurrently inserts "X" inside
     * "hello". The remote insert splits the tombstoned text under the
     * already-registered `<p>`, mutating its child list between
     * registerGCPair and the undo-side unregisterGCPair.
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

    // Regression: gcPairMap must find a registered tree node even after a
    // concurrent remote edit mutated its children — keying by structural
    // (data-class) hash makes the unregister lookup miss and leaves a
    // stale entry behind.
    @Test
    fun `undo unregisters GC pairs for a revived element mutated by a concurrent split`() =
        runTest {
            // given: the delete registered 4 pairs (p, "he", "llo", X — the
            // concurrent X also converges to tombstoned)
            val (d1, _) = buildDeleteWithConcurrentSplit()
            assertEquals(4, d1.garbageLength)

            // when
            d1.history.undoAsync().await()

            // then: p, "he" and "llo" are revived and unregistered; only the
            // still-tombstoned X remains registered
            assertEquals(
                "<root><p>hello</p></root>",
                d1.getRoot().getAs<JsonTree>("t").toXml(),
            )
            assertEquals(
                1,
                d1.garbageLength,
                "revive must unregister every pair of the revived nodes",
            )
        }

    // Regression: a stale gcPairMap entry surviving undo makes redo add a
    // duplicate pair for the same node — the next GC then purges that node
    // twice and docSize.live permanently loses the size the missed
    // unregister never credited back.
    @Test
    fun `redo after a concurrent split purges each node exactly once`() = runTest {
        // given
        val (d1, d2) = buildDeleteWithConcurrentSplit()
        d1.history.undoAsync().await()
        d1.history.redoAsync().await()

        // when: d2 never undid, so it purges each of the 4 pairs exactly once
        val vector = maxVectorOf(listOf(actor1, actor2))
        val purged1 = d1.garbageCollect(vector)
        val purged2 = d2.garbageCollect(vector)

        // then
        assertEquals(purged2, purged1, "undo/redo must not duplicate GC pairs")
        assertEquals(0, d1.garbageLength)
        assertEquals(
            DataSize(0, 0),
            d1.getDocSize().gc,
            "each purged node must leave docSize.gc exactly once",
        )
    }
}
