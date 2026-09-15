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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector

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

    @get:Rule
    val errors = ErrorCollector()

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    /** One overlap relation of the matrix: d1 deletes [r1] while d2 deletes [r2]. */
    private data class Relation(val label: String, val r1: Pair<Int, Int>, val r2: Pair<Int, Int>)

    // d1's range [5,7) sits inside d2's wider [3,9).
    private val containedBy = Relation("contained_by", 5 to 7, 3 to 9)

    // Mirror of contained_by: d1 is now the wider range.
    private val contains = Relation("contains", 3 to 9, 5 to 7)

    // d1's range starts inside d2's range and extends past its end.
    private val overlapStart = Relation("overlap_start", 5 to 9, 3 to 7)

    // Mirror of overlap_start.
    private val overlapEnd = Relation("overlap_end", 3 to 7, 5 to 9)

    // Both replicas delete the exact same range.
    private val identical = Relation("identical", 3 to 7, 3 to 7)

    // The two ranges touch but never overlap.
    private val adjacent = Relation("adjacent", 3 to 5, 5 to 7)

    /** The six relations; the explicit per-relation tests below use the same vals. */
    private val overlapRelations =
        listOf(containedBy, contains, overlapStart, overlapEnd, identical, adjacent)

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
     * them already cross-synced. [overWire] routes every relayed operation
     * through the protobuf converters, as a real server does.
     */
    private suspend fun seed(overWire: Boolean = false): Pair<Document, Document> {
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
        crossSync(d1, d2, overWire)
        return d1 to d2
    }

    /**
     * Drives both replicas' concurrent overlapping deletes ([relation]'s r1
     * on [d1], r2 on [d2]), cross-syncs, then forces GC on both replicas (the
     * in-process analogue of JS's `settle` twice — `crossSync` passes an
     * empty [dev.yorkie.document.time.VersionVector] so its internal GC is a
     * no-op) so restore takes the recreate path. Asserts the purge was total
     * and both replicas converged post-delete before returning.
     */
    private suspend fun deleteOverlapping(
        d1: Document,
        d2: Document,
        relation: Relation,
        overWire: Boolean = false,
    ) {
        val (_, r1, r2) = relation
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(r1.first, r1.second) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(r2.first, r2.second) }.await()
        crossSync(d1, d2, overWire)

        // Every tombstone is eligible under the max vector, so the purge must
        // be total: one surviving tombstone would send restore down the cheap
        // unremove() path and the matrix would silently stop exercising the
        // recreate + isolate path it exists for.
        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(0, d1.garbageLength, "every tombstone must be purged before undo")
        assertEquals(0, d2.garbageLength, "every tombstone must be purged before undo")
        assertEquals(DataSize(0, 0), d1.getDocSize().gc)
        assertEquals(DataSize(0, 0), d2.getDocSize().gc)

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

    private suspend fun undoBoth(
        d1: Document,
        d2: Document,
        overWire: Boolean = false,
    ) {
        d1.history.undoAsync().await()
        d2.history.undoAsync().await()
        crossSync(d1, d2, overWire)
    }

    private suspend fun redoBoth(
        d1: Document,
        d2: Document,
        overWire: Boolean = false,
    ) {
        d1.history.redoAsync().await()
        d2.history.redoAsync().await()
        crossSync(d1, d2, overWire)
    }

    /**
     * Undoes on [first], syncs, then undoes on [second] and syncs -- so the
     * second replica's restore runs against the first's already-restored
     * segmentation (live pieces inside its span) instead of a fully purged
     * run: the mixed recreate-around-live path.
     */
    private suspend fun undoInterleaved(
        first: Document,
        second: Document,
        overWire: Boolean = false,
    ) {
        first.history.undoAsync().await()
        crossSync(first, second, overWire)
        second.history.undoAsync().await()
        crossSync(first, second, overWire)
    }

    /**
     * Runs [block] once per relation in [overlapRelations], collecting each
     * relation's failure instead of stopping at the first, so one red
     * relation cannot hide the others. Cancellation still propagates.
     */
    private suspend fun forEachRelation(block: suspend (Relation) -> Unit) {
        for (relation in overlapRelations) {
            try {
                block(relation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errors.addError(AssertionError("${relation.label}: ${e.message}", e))
            }
        }
    }

    /**
     * Both undos revive both deleted runs by identity, restoring the
     * pre-delete visible content on both replicas. (The internal text-node
     * segmentation may be finer than the original — isolate splits at the
     * span boundaries — but both replicas agree, which [assertConverged]
     * checks.)
     */
    private suspend fun assertUndoConvergesToInitial(
        relation: Relation,
        overWire: Boolean = false,
    ) {
        val (d1, d2) = seed(overWire)
        val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
        deleteOverlapping(d1, d2, relation, overWire)

        undoBoth(d1, d2, overWire)
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
        relation: Relation,
        overWire: Boolean = false,
    ) {
        val (d1, d2) = seed(overWire)
        val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
        deleteOverlapping(d1, d2, relation, overWire)
        val afterDeletes = d1.getRoot().getAs<JsonTree>("t").toXml()

        undoBoth(d1, d2, overWire)
        assertConverged(d1, d2, "after undo")
        assertEquals(
            initial,
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            "undo must restore the initial visible content",
        )

        redoBoth(d1, d2, overWire)
        assertConverged(d1, d2, "after redo")
        assertEquals(
            afterDeletes,
            d1.getRoot().getAs<JsonTree>("t").toXml(),
            "redo must restore the post-delete visible content",
        )
    }

    @Test
    fun `converges on undo of overlapping deletes contained_by`() = runTest {
        assertUndoConvergesToInitial(containedBy)
    }

    @Test
    fun `converges on undo redo of overlapping deletes contained_by`() = runTest {
        assertUndoRedoConvergesToPostDelete(containedBy)
    }

    @Test
    fun `converges on undo of overlapping deletes contains`() = runTest {
        assertUndoConvergesToInitial(contains)
    }

    @Test
    fun `converges on undo redo of overlapping deletes contains`() = runTest {
        assertUndoRedoConvergesToPostDelete(contains)
    }

    @Test
    fun `converges on undo of overlapping deletes overlap_start`() = runTest {
        assertUndoConvergesToInitial(overlapStart)
    }

    @Test
    fun `converges on undo redo of overlapping deletes overlap_start`() = runTest {
        assertUndoRedoConvergesToPostDelete(overlapStart)
    }

    @Test
    fun `converges on undo of overlapping deletes overlap_end`() = runTest {
        assertUndoConvergesToInitial(overlapEnd)
    }

    @Test
    fun `converges on undo redo of overlapping deletes overlap_end`() = runTest {
        assertUndoRedoConvergesToPostDelete(overlapEnd)
    }

    @Test
    fun `converges on undo of overlapping deletes identical`() = runTest {
        assertUndoConvergesToInitial(identical)
    }

    @Test
    fun `converges on undo redo of overlapping deletes identical`() = runTest {
        assertUndoRedoConvergesToPostDelete(identical)
    }

    @Test
    fun `converges on undo of overlapping deletes adjacent`() = runTest {
        assertUndoConvergesToInitial(adjacent)
    }

    @Test
    fun `converges on undo redo of overlapping deletes adjacent`() = runTest {
        assertUndoRedoConvergesToPostDelete(adjacent)
    }

    // Everything above relays operations in memory. This routes every
    // relayed operation through the protobuf converters -- the only path
    // that exercises restore_spans encode/decode under convergence -- for the
    // whole matrix, undo and redo, and requires node-identity convergence.
    @Test
    fun `converges over the wire on undo and redo for every relation`() = runTest {
        forEachRelation { relation ->
            assertUndoRedoConvergesToPostDelete(relation, overWire = true)
        }
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
        deleteOverlapping(d1, d2, containedBy)

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

    // The matrix batches both undos before a single sync; each relation must
    // converge under the interleaved order too.
    @Test
    fun `converges when one replica syncs its undo before the other undoes`() = runTest {
        forEachRelation { relation ->
            val (d1, d2) = seed()
            val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
            deleteOverlapping(d1, d2, relation)

            undoInterleaved(d1, d2)

            assertConverged(d1, d2, "${relation.label}: after interleaved undo")
            assertEquals(
                initial,
                d1.getRoot().getAs<JsonTree>("t").toXml(),
                "${relation.label}: undo must restore the initial visible content",
            )
        }
    }

    // Order only matters when the second undo can see the first's result (two
    // local undos before one sync never interact). Here d2's undo is synced
    // first, so d1 restores around d2's segmentation; the result must match
    // both the forward-interleaved and the batched run node-for-node.
    @Test
    fun `converges on undo of overlapping deletes regardless of undo order`() = runTest {
        forEachRelation { relation ->
            val label = relation.label
            val (d1, d2) = seed()
            val initial = d1.getRoot().getAs<JsonTree>("t").toXml()
            deleteOverlapping(d1, d2, relation)

            undoInterleaved(d2, d1)

            assertConverged(d1, d2, "$label: after reverse-interleaved undo")
            assertEquals(
                initial,
                d1.getRoot().getAs<JsonTree>("t").toXml(),
                "$label: undo must restore the initial visible content",
            )

            val (f1, f2) = seed()
            deleteOverlapping(f1, f2, relation)
            undoInterleaved(f1, f2)
            assertEquals(
                identitySequence(f1.crdtTree()),
                identitySequence(d1.crdtTree()),
                "$label: forward and reverse interleaving must reach the same segmentation",
            )

            val (b1, b2) = seed()
            deleteOverlapping(b1, b2, relation)
            undoBoth(b1, b2)
            assertEquals(
                identitySequence(b1.crdtTree()),
                identitySequence(d1.crdtTree()),
                "$label: batched and interleaved undos must reach the same segmentation",
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
