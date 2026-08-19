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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Test

/**
 * Ports `history_tree_concurrent_test.ts` (JS SDK 7b2ab7a4, v0.7.15,
 * JS #1315) as JVM in-process cross-sync tests (spec 006 AC5-AC7).
 *
 * Convergence of concurrent OVERLAPPING undo/redo once the deleted nodes
 * have been GC-purged (so restore takes the recreate path). The existing
 * `TreeRestoreConvergenceTest` reconcile cases undo before GC runs and so
 * never exercise this; [settle] forces explicit garbage collection on both
 * replicas before undo so restore takes the recreate path. Regression for
 * the multi-user tree undo corruption seen in wafflebase docs: split-aware
 * restore/retombstone that isolates each piece at the span boundaries so
 * all replicas converge on the same text-node segmentation.
 */
class TreeRestoreConcurrentTest {

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
     * Seeds both replicas with `<doc><p>0123456789</p></doc>` and returns
     * them already cross-synced.
     */
    private suspend fun seed(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") { element("p") { text { "0123456789" } } },
            )
        }.await()
        crossSync(d1, d2)
        return d1 to d2
    }

    /**
     * Drives both replicas' concurrent overlapping deletes ([r1] on [d1],
     * [r2] on [d2]), cross-syncs, then forces GC on both replicas (the
     * in-process analogue of JS's `settle` twice — `crossSync` passes an
     * empty [dev.yorkie.document.time.VersionVector] so its internal GC is a
     * no-op) so restore takes the recreate path. Asserts the purge actually
     * happened and both replicas converged post-delete before returning.
     */
    private suspend fun deleteOverlapping(
        d1: Document,
        d2: Document,
        r1: Pair<Int, Int>,
        r2: Pair<Int, Int>,
    ) {
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(r1.first, r1.second) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(r2.first, r2.second) }.await()
        crossSync(d1, d2)

        val vector = maxVectorOf(listOf(actor1, actor2))
        val purged1 = d1.garbageCollect(vector)
        val purged2 = d2.garbageCollect(vector)
        assertTrue(purged1 > 0, "d1 must purge the deleted run before undo")
        assertTrue(purged2 > 0, "d2 must purge the deleted run before undo")

        assertConverged(d1, d2)
    }

    private suspend fun assertConverged(
        d1: Document,
        d2: Document,
        label: String = "after deletes",
    ) {
        assertEquals(
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            d2.getRoot().getAs<JsonTree>("t").toXml(),
            "$label: replicas diverged",
        )
        assertEquals(
            identitySequence(d1.crdtTree()),
            identitySequence(d2.crdtTree()),
            "$label: replicas diverged on node identity",
        )
    }

    private suspend fun undoBoth(d1: Document, d2: Document) {
        d1.history.undoAsync().await()
        d2.history.undoAsync().await()
        crossSync(d1, d2)
    }

    private suspend fun redoBoth(d1: Document, d2: Document) {
        d1.history.redoAsync().await()
        d2.history.redoAsync().await()
        crossSync(d1, d2)
    }

    // (label, d1 range, d2 range) — the same six relations as the
    // per-relation matrix below, for the order/interleaving variants.
    private val overlapRelations = listOf(
        Triple("contained_by", 5 to 7, 3 to 9),
        Triple("contains", 3 to 9, 5 to 7),
        Triple("overlap_start", 5 to 9, 3 to 7),
        Triple("overlap_end", 3 to 7, 5 to 9),
        Triple("identical", 3 to 7, 3 to 7),
        Triple("adjacent", 3 to 5, 5 to 7),
    )

    /**
     * Both undos revive both deleted runs by identity, restoring the
     * pre-delete visible content on both replicas. (The internal text-node
     * segmentation may be finer than the original — isolate splits at the
     * span boundaries — but both replicas agree, which [assertConverged]
     * checks.)
     */
    private suspend fun assertUndoConvergesToInitial(r1: Pair<Int, Int>, r2: Pair<Int, Int>) {
        val (d1, d2) = seed()
        val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
        deleteOverlapping(d1, d2, r1, r2)

        undoBoth(d1, d2)
        assertConverged(d1, d2, "after undo")
        assertEquals(
            initial,
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            "undo must restore the initial visible content",
        )
    }

    /**
     * Both redos re-remove both runs by identity, back to the converged
     * post-delete state.
     */
    private suspend fun assertUndoRedoConvergesToPostDelete(
        r1: Pair<Int, Int>,
        r2: Pair<Int, Int>,
    ) {
        val (d1, d2) = seed()
        val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
        deleteOverlapping(d1, d2, r1, r2)
        val afterDeletes = d1.getRoot().getAs<JsonTree>("t").toXml()

        undoBoth(d1, d2)
        assertConverged(d1, d2, "after undo")
        assertEquals(
            initial,
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            "undo must restore the initial visible content",
        )

        redoBoth(d1, d2)
        assertConverged(d1, d2, "after redo")
        assertEquals(
            afterDeletes,
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            "redo must restore the post-delete visible content",
        )
    }

    // contained_by: d1's range [5,7) sits inside d2's wider [3,9).
    @Test
    fun `converges on undo of overlapping deletes contained_by`() = runTest {
        assertUndoConvergesToInitial(5 to 7, 3 to 9)
    }

    @Test
    fun `converges on undo redo of overlapping deletes contained_by`() = runTest {
        assertUndoRedoConvergesToPostDelete(5 to 7, 3 to 9)
    }

    // contains: mirror of contained_by, d1 is now the wider range.
    @Test
    fun `converges on undo of overlapping deletes contains`() = runTest {
        assertUndoConvergesToInitial(3 to 9, 5 to 7)
    }

    @Test
    fun `converges on undo redo of overlapping deletes contains`() = runTest {
        assertUndoRedoConvergesToPostDelete(3 to 9, 5 to 7)
    }

    // overlap_start: d1's range starts inside d2's range and extends past its end.
    @Test
    fun `converges on undo of overlapping deletes overlap_start`() = runTest {
        assertUndoConvergesToInitial(5 to 9, 3 to 7)
    }

    @Test
    fun `converges on undo redo of overlapping deletes overlap_start`() = runTest {
        assertUndoRedoConvergesToPostDelete(5 to 9, 3 to 7)
    }

    // overlap_end: mirror of overlap_start.
    @Test
    fun `converges on undo of overlapping deletes overlap_end`() = runTest {
        assertUndoConvergesToInitial(3 to 7, 5 to 9)
    }

    @Test
    fun `converges on undo redo of overlapping deletes overlap_end`() = runTest {
        assertUndoRedoConvergesToPostDelete(3 to 7, 5 to 9)
    }

    // identical: both replicas delete the exact same range.
    @Test
    fun `converges on undo of overlapping deletes identical`() = runTest {
        assertUndoConvergesToInitial(3 to 7, 3 to 7)
    }

    @Test
    fun `converges on undo redo of overlapping deletes identical`() = runTest {
        assertUndoRedoConvergesToPostDelete(3 to 7, 3 to 7)
    }

    // adjacent: the two ranges touch but never overlap.
    @Test
    fun `converges on undo of overlapping deletes adjacent`() = runTest {
        assertUndoConvergesToInitial(3 to 5, 5 to 7)
    }

    @Test
    fun `converges on undo redo of overlapping deletes adjacent`() = runTest {
        assertUndoRedoConvergesToPostDelete(3 to 5, 5 to 7)
    }

    // The convergence-exactness cases above only check the fully-drained end
    // state (after redo + GC), where a transiently mis-toggled gcPairMap can
    // self-cancel. This stops right after undo: every piece of the purged
    // runs is either untombstoned or recreated live, so a stray
    // register/unregister in executeRestore surfaces as phantom garbage or a
    // docSize mismatch here.
    @Test
    fun `undo alone leaves zero garbage and identical docSize on both replicas`() = runTest {
        val (d1, d2) = seed()
        val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
        deleteOverlapping(d1, d2, 5 to 7, 3 to 9)

        undoBoth(d1, d2)

        assertConverged(d1, d2, "after undo")
        assertEquals(initial, d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(0, d1.garbageLength, "no tombstone may survive undo of both deletes")
        assertEquals(0, d2.garbageLength, "no tombstone may survive undo of both deletes")
        assertEquals(DataSize(0, 0), d1.getDocSize().gc)
        assertEquals(DataSize(0, 0), d2.getDocSize().gc)
        assertEquals(
            d1.getDocSize(),
            d2.getDocSize(),
            "recreated and split-born pieces must account identically on both replicas",
        )
    }

    // undoBoth always undoes d1 first; the restore path must not depend on
    // that. Each relation converges under the reverse order too, and both
    // orders land on the same final node segmentation.
    @Test
    fun `converges on undo of overlapping deletes regardless of undo order`() = runTest {
        for ((label, r1, r2) in overlapRelations) {
            val (d1, d2) = seed()
            val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
            deleteOverlapping(d1, d2, r1, r2)

            d2.history.undoAsync().await()
            d1.history.undoAsync().await()
            crossSync(d1, d2)

            assertConverged(d1, d2, "$label: after reverse-order undo")
            assertEquals(
                initial,
                d1.getRoot().getAs<JsonTree>("t").toXml(),
                "$label: undo must restore the initial visible content",
            )

            val (e1, e2) = seed()
            deleteOverlapping(e1, e2, r1, r2)
            undoBoth(e1, e2)
            assertEquals(
                identitySequence(e1.crdtTree()),
                identitySequence(d1.crdtTree()),
                "$label: undo order must not change the final segmentation",
            )
        }
    }

    // The matrix batches both undos before a single sync. Here d1's undo is
    // synced first, so d2's own restore runs against d1's already-restored
    // segmentation (live pieces inside its span) instead of a fully purged
    // run — the mixed recreate-around-live path.
    @Test
    fun `converges when one replica syncs its undo before the other undoes`() = runTest {
        for ((label, r1, r2) in overlapRelations) {
            val (d1, d2) = seed()
            val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
            deleteOverlapping(d1, d2, r1, r2)

            d1.history.undoAsync().await()
            crossSync(d1, d2)
            d2.history.undoAsync().await()
            crossSync(d1, d2)

            assertConverged(d1, d2, "$label: after interleaved undo")
            assertEquals(
                initial,
                d1.getRoot().getAs<JsonTree>("t").toXml(),
                "$label: undo must restore the initial visible content",
            )
        }
    }

    // KNOWN LIMITATION (tracked, skipped): when a whole element is deleted
    // concurrently with a text edit INSIDE it and both undo AFTER GC, the
    // visible content converges but internal text-node segmentation can
    // differ (one replica un-tombstones the concurrent edit's finer split,
    // the other recreates the run monolithically from the element's span).
    // A sound fix needs the child sub-restore's split points to survive a
    // transiently-purged parent (e.g. undo-stack-aware GC so restore
    // un-tombstones in place). Merge-normalizing segmentation was tried and
    // rejected (non-commutative — broke GC/tombstone symmetry after redo).
    // Ported skipped from JS `history_tree_concurrent_test.ts` verbatim.
    @Ignore(
        "KNOWN: delete a whole <p> vs edit text inside it, both undo — " +
            "segmentation may differ though visible content converges; " +
            "upstream defers to undo-stack-aware GC (JS SDK 7b2ab7a4)",
    )
    @Test
    fun `KNOWN delete a whole p vs edit text inside it both undo`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "hello" } }
                    element("p") { text { "world" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d1 removes the whole first <p>; d2 replaces text inside it.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 7) }.await()
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(3, 5, text { "XY" })
        }.await()
        crossSync(d1, d2)
        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertConverged(d1, d2, "after ops")

        undoBoth(d1, d2)
        assertConverged(d1, d2, "after undo")
    }

    // KNOWN LIMITATION (tracked): deleting MULTIPLE elements concurrently
    // with an edit inside one of them, then both undo after GC, converges
    // on visible content but NOT on internal text-node segmentation. Root
    // cause: a child sub-restore is B1-skipped while its parent is
    // transiently purged, so the two replicas end with different split
    // points; the element-restore's span is monolithic and cannot
    // re-introduce them. Left skipped until undo-stack-aware GC lands.
    // Ported skipped from JS `history_tree_concurrent_test.ts` verbatim.
    @Ignore(
        "KNOWN: delete two <p> vs edit inside first, both undo — " +
            "segmentation differs though visible content converges; " +
            "upstream defers to undo-stack-aware GC (JS SDK 7b2ab7a4)",
    )
    @Test
    fun `KNOWN delete two p vs edit inside first both undo`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "aaaa" } }
                    element("p") { text { "bbbb" } }
                    element("p") { text { "cccc" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 12) }.await()
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(2, 4, text { "XY" })
        }.await()
        crossSync(d1, d2)
        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)

        undoBoth(d1, d2)
        assertConverged(d1, d2, "after undo")
    }
}
