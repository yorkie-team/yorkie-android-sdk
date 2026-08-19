package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.issueTime
import dev.yorkie.util.DataSize
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports the GC-symmetry and DocSize-exactness cases from `history_tree_test.ts`
 * (JS SDK fa6cc513, spec 005 AC9) as JVM unit tests, plus the split-aware
 * restore/retombstone isolate cases (JS SDK 7b2ab7a4, spec 006 AC1-AC4):
 * isolate boundary mechanics, in-span-only restore/retombstone on straddling
 * pieces, and pending-pair ordering with DocSize/GC exactness.
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

    // --- spec 006 AC1-AC4: isolateTextRange's boundary branches, exercised
    // through CrdtTree.restore/retombstone directly (its "public", module-
    // internal entry points -- isolateTextRange itself stays private). A
    // straddling piece only arises once GC has purged a tombstone and a
    // narrower restore/retombstone later targets a sub-range of what
    // survives; building that precisely through a live 2-document undo/redo
    // sequence is what TreeRestoreConcurrentTest's overlap matrix already
    // covers end-to-end. These focused cases instead construct the
    // straddling piece directly (mirrors TreeRestoreTest's direct-CrdtTree
    // precedent) so each isolate branch and its exact return contract
    // (untombstoned/pendingGcPairs/diff or pairs/diff) is asserted without
    // depending on floor-search/GC-eligibility internals.

    /** Builds a tree holding `<root>[fullValue]</root>` then deletes `[deleteFrom, deleteTo)`. */
    private fun buildDeletedRun(
        fullValue: String,
        deleteFrom: Int,
        deleteTo: Int,
    ): Pair<CrdtTree, TreeRestoreSpan> {
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeText(CrdtTreeNodeID(issueTime(), 0), fullValue)),
            0,
            issueTime(),
        )
        val result = tree.edit(
            tree.findPos(deleteFrom) to tree.findPos(deleteTo),
            null,
            0,
            issueTime(),
        )
        return tree to result.removedSpans.single()
    }

    /** Narrows a captured [TreeRestoreSpan] to the absolute `[offset, offset + length)` sub-range. */
    private fun TreeRestoreSpan.narrowedTo(offset: Int, length: Int): TreeRestoreSpan {
        val relative = offset - id.offset
        return copy(
            id = id.copy(offset = offset),
            length = length,
            value = value?.substring(relative, relative + length),
        )
    }

    // AC1: an exact-boundary span needs no split at all.
    @Test
    fun `isolateTextRange is a no-op when the piece already matches the span exactly`() = runTest {
        val (tree, span) = buildDeletedRun("0123456789", 3, 9)

        val result = tree.restore(listOf(span))

        assertEquals(listOf("345678"), result.untombstoned.map { it.value })
        assertTrue(result.recreated.isEmpty())
        assertTrue(result.pendingGcPairs.isEmpty())
        assertEquals(DataSize(0, 0), result.diff)
        assertEquals("<root>0123456789</root>", tree.toXml())
    }

    // AC1: the span starts inside the piece but ends exactly at its
    // boundary -- only a left split is needed. The piece is still
    // tombstoned at isolate time, so the left remainder is a born-removed
    // pending pair and the split contributes zero diff (CrdtTreeNode.split's
    // removed-split contract).
    @Test
    fun `isolateTextRange splits only the left boundary and buffers the remainder`() = runTest {
        val (tree, span) = buildDeletedRun("0123456789", 3, 9)
        val narrow = span.narrowedTo(5, 4) // [5,9) of the same tombstoned run

        val result = tree.restore(listOf(narrow))

        assertEquals(listOf("5678"), result.untombstoned.map { it.value })
        assertEquals(1, result.pendingGcPairs.size, "the [3,5) remainder must be buffered for GC")
        assertEquals(DataSize(0, 0), result.diff, "a removed split contributes zero diff")
        assertEquals("<root>01256789</root>", tree.toXml(), "[3,5) must stay invisible")
    }

    // AC1: mirror of the above -- the span ends inside the piece but starts
    // exactly at its boundary, so only a right split is needed.
    @Test
    fun `isolateTextRange splits only the right boundary and buffers the remainder`() = runTest {
        val (tree, span) = buildDeletedRun("0123456789", 3, 9)
        val narrow = span.narrowedTo(3, 4) // [3,7) of the same tombstoned run

        val result = tree.restore(listOf(narrow))

        assertEquals(listOf("3456"), result.untombstoned.map { it.value })
        assertEquals(1, result.pendingGcPairs.size, "the [7,9) remainder must be buffered for GC")
        assertEquals(DataSize(0, 0), result.diff, "a removed split contributes zero diff")
        assertEquals("<root>01234569</root>", tree.toXml(), "[7,9) must stay invisible")
    }

    // AC1 (removed straddler, both boundaries) + AC2 (restore isolate is
    // in-span only): the span sits strictly inside the tombstoned run, so
    // both a left AND a right split are needed. Only [5,7) is untombstoned;
    // [3,5) and [7,9) are born-removed remainders -- buffered as pending
    // pairs (registered by the caller BEFORE the untombstoned unregister,
    // AC4) and stay invisible.
    @Test
    fun `isolateTextRange splits both boundaries, restoring only the in-span range`() = runTest {
        val (tree, span) = buildDeletedRun("0123456789", 3, 9)
        val narrow = span.narrowedTo(5, 2) // [5,7) strictly inside [3,9)

        val result = tree.restore(listOf(narrow))

        assertEquals(listOf("56"), result.untombstoned.map { it.value })
        assertTrue(result.recreated.isEmpty())
        assertEquals(
            2,
            result.pendingGcPairs.size,
            "both the [3,5) and [7,9) remainders must be buffered for GC",
        )
        assertEquals(DataSize(0, 0), result.diff, "removed splits contribute zero diff")
        assertEquals(
            "<root>012569</root>",
            tree.toXml(),
            "only [5,7) becomes visible; [3,5) and [7,9) stay tombstoned",
        )
    }

    // AC3: retombstone's isolate mirrors restore's -- a LIVE piece wider
    // than the redo span is split at both boundaries so only the in-span
    // range is re-removed; the live splits are real metadata overhead
    // (nonzero diff), unlike a removed split.
    @Test
    fun `retombstone isolates a live straddling piece to only the in-span range`() = runTest {
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        val insertedAt = issueTime()
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeText(CrdtTreeNodeID(insertedAt, 0), "0123456789")),
            0,
            issueTime(),
        )
        // A span narrower than the single live "0123456789" piece: [3,7).
        val span = TreeRestoreSpan(
            id = CrdtTreeNodeID(insertedAt, 3),
            nodeType = "text",
            isText = true,
            length = 4,
            value = "3456",
        )

        val (pairs, diff) = tree.retombstone(listOf(span), issueTime())

        assertEquals(1, pairs.size)
        assertEquals("3456", pairs.single().child.value)
        assertTrue(
            diff != DataSize(0, 0),
            "isolating a live piece must charge real metadata overhead",
        )
        assertEquals(
            "<root>012789</root>",
            tree.toXml(),
            "only [3,7) is re-removed; [0,3) and [7,10) stay visible",
        )
    }

    // AC4: executeRestore's ordering (restore's pending pairs registered
    // BEFORE the untombstoned nodes are unregistered) must keep docSize and
    // garbageLength exact through a full concurrent-overlap undo+redo+GC
    // cycle that isolates a removed straddler on both replicas (the
    // contained_by relation from TreeRestoreConcurrentTest's matrix).
    @Test
    fun `executeRestore ordering keeps docSize and garbageLength exact after undo redo GC`() =
        runTest {
            val d1 = Document("test-doc")
            val d2 = Document("test-doc")
            d1.setActor(actor1)
            d2.setActor(actor2)

            d1.updateAsync { root, _ ->
                root.setNewTree("t", element("root") { text { "0123456789" } })
            }.await()
            crossSync(d1, d2)

            // contained_by: d1's [5,7) sits inside d2's wider [3,9).
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(5, 7) }.await()
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(3, 9) }.await()
            crossSync(d1, d2)

            val vector = maxVectorOf(listOf(actor1, actor2))
            assertTrue(d1.garbageCollect(vector) > 0)
            assertTrue(d2.garbageCollect(vector) > 0)

            d1.history.undoAsync().await()
            d2.history.undoAsync().await()
            crossSync(d1, d2)
            assertEquals(
                "<root>0123456789</root>",
                d1.getRoot().getAs<JsonTree>("t").toXml(),
                "undo must restore the initial visible content",
            )

            d1.history.redoAsync().await()
            d2.history.redoAsync().await()
            crossSync(d1, d2)
            assertEquals(
                "<root>0129</root>",
                d1.getRoot().getAs<JsonTree>("t").toXml(),
                "redo must restore the converged post-delete visible content",
            )

            val purged1 = d1.garbageCollect(vector)
            val purged2 = d2.garbageCollect(vector)
            assertEquals(purged1, purged2, "both replicas must purge the same count")
            assertEquals(0, d1.garbageLength)
            assertEquals(0, d2.garbageLength)
            assertEquals(DataSize(0, 0), d1.getDocSize().gc)
            assertEquals(DataSize(0, 0), d2.getDocSize().gc)
        }
}
