package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RestoreSpan
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import dev.yorkie.util.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `text_restore_convergence_test.ts` (JS SDK 5d5cac63, #1293) as JVM
 * unit tests (AC8, AC9, AC13-anchor).
 */
class TextRestoreConvergenceTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun Document.crdtText(): CrdtText = getRootObject()["text"] as CrdtText

    /**
     * The live node-identity sequence of a text, in list order. Two replicas
     * converging must match on this (not just on rendered content) — DEC-3's
     * load-bearing check is same-[RgaTreeSplitNodeID], via structural
     * equality already free on the data class.
     */
    private fun identitySequence(text: CrdtText): List<RgaTreeSplitNodeID> =
        text.rgaTreeSplit.filterNot { it.isRemoved }.map { it.id }

    /**
     * Builds two replicas that both hold "0123456789", then concurrently
     * delete overlapping ranges — d1 deletes "45" (indices 4..6), d2 deletes
     * the superset "234567" (indices 2..8) — and cross-syncs to the
     * converged "0189". Each replica keeps its own delete on its undo stack.
     */
    private suspend fun buildOverlappingDeletes(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewText("text").edit(0, 0, "0123456789") }.await()
        crossSync(d1, d2)

        // delete "45"
        d1.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(4, 6, "") }.await()
        // delete "234567"
        d2.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(2, 8, "") }.await()
        crossSync(d1, d2)

        assertEquals("0189", d1.getRoot().getAs<JsonText>("text").toString())
        assertEquals(
            d1.getRoot().getAs<JsonText>("text").toString(),
            d2.getRoot().getAs<JsonText>("text").toString(),
        )
        return d1 to d2
    }

    // The feature's motivating case: two clients concurrently undo overlapping
    // deletions. The undos are identity-addressed, so restoring both must
    // revive the original insertion exactly once (a set union of the two
    // restored ranges), converging to identical content and identical node
    // ids on both replicas regardless of the order the restores are applied.
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
        assertEquals("0123456789", d1.getRoot().getAs<JsonText>("text").toString())
        assertEquals(
            identitySequence(d1.crdtText()),
            identitySequence(d2.crdtText()),
            "both replicas must converge to identical content AND node ids",
        )
    }

    @Test
    fun `converges to the same state under the opposite undo order d2 first`() = runTest {
        val (a1, a2) = runBothUndos(undoD1First = true)
        val (b1, b2) = runBothUndos(undoD1First = false)
        assertEquals("0123456789", a1.getRoot().getAs<JsonText>("text").toString())
        assertEquals("0123456789", b1.getRoot().getAs<JsonText>("text").toString())
        assertEquals(identitySequence(a1.crdtText()), identitySequence(a2.crdtText()))
        assertEquals(identitySequence(b1.crdtText()), identitySequence(b2.crdtText()))
        // E7: the two undo orderings must converge to each other, not just
        // each internally — otherwise a scrambled-but-self-consistent order
        // from one ordering would slip past the two assertions above.
        assertEquals(
            identitySequence(a1.crdtText()),
            identitySequence(b1.crdtText()),
            "both undo orderings must converge to the same node-identity sequence",
        )
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
    // across undo/redo cycles. The anchor is the post-delete state, NOT the
    // pristine pre-delete one: deleting "45" splits the insertion into
    // "0123"|"45"|"6789" and reviving un-tombstones "45" without re-merging
    // the splits, so the extra fragment metadata legitimately persists —
    // orthogonal to GC accounting. What must be exactly reversible is the
    // gc<->live movement, which this pins by round-tripping the cycle.
    @Test
    fun `reverses GC accounting exactly across delete undo redo undo`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ -> root.setNewText("text").edit(0, 0, "0123456789") }.await()

        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(4, 6, "") }.await()
        val deleted = document.getDocSize()
        assertNotEquals(DataSize(0, 0), deleted.gc, "delete registers GC")

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

    @Test
    fun `restore falls back to head and logs when the neighborhood is fully purged`() {
        val recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)
        try {
            // Direct RgaTreeSplit-layer call (bypassing EditOperation, which
            // never passes a null fallbackAnchor): a span whose insertion
            // has no piece anywhere in this split, and no fallback anchor
            // either — every ladder rung before head must fail.
            val split = RgaTreeSplit<TextValue>()
            val ticket = TimeTicket(1L, 0u, actor1)
            val span = RestoreSpan(ticket, 0, 2, TextValue("ab"))

            val result = split.restore(listOf(span), ticket)

            assertEquals(1, result.recreated.size)
            assertEquals("ab", result.recreated.single().value.content)
            val messages = recordingLogger.debugMessages
            val fired = messages.any { it.contains("restore anchor exhausted") }
            assertTrue(fired, "expected the head-fallback guard to log once, got $messages")
        } finally {
            // Reinstall a fresh, empty logger so captured state does not leak
            // into other test classes.
            Logger.init(RecordingLogger())
        }
    }

    // F2 / BLOCKER-1 (round-2 QA correction of the round-1 disclosure below,
    // which incorrectly reported the reviewer's exact probe as
    // non-reproducible): rung (c) previously picked the RIGHTMOST surviving
    // piece of the WHOLE insertion (`floorEntry(createdAt, MAX)`), not the
    // NEAREST surviving piece to the right of the gap. When a closer
    // same-insertion survivor exists between the gap and that rightmost
    // piece (e.g. style() split off a middle fragment that never got
    // deleted), rung (c) anchors the recreated fragment behind the WRONG,
    // farther-away survivor — scrambling the rebuilt order. A `ceilingEntry`
    // search from `gapEnd` finds the true nearest successor instead; hand
    // traced against the exact spec Scenario 1 probe and confirmed by
    // `undo-after-GC scrambles content into the exact spec scenario 1
    // order` below. The `chainAnchor` rung (d, still present) is a separate,
    // additionally-needed fix for the different case pinned by the test
    // immediately below this comment: a SINGLE delete spanning multiple
    // fragments of one insertion, all purged by one GC pass and recreated
    // by one undo call.
    @Test
    fun `restore chains multiple purged fragments of one insertion in order`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "0123456789")
        }.await()
        // Splits the single insertion into three live pieces (0-4, 4-6,
        // 6-10) sharing the same createdAt, without removing anything.
        document.updateAsync { root, _ ->
            root.getAs<JsonText>("text").style(4, 6, mapOf("b" to "1"))
        }.await()

        // One delete removes all three fragments in a single edit call —
        // removedSpans captures all three, so undo's restore() must
        // recreate all three within ONE call.
        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(0, 10, "") }.await()
        assertEquals("", document.getRoot().getAs<JsonText>("text").toString())

        val purged = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertTrue(purged > 0, "expected all three purged fragments to be collected")

        document.history.undoAsync().await()

        assertEquals(
            "0123456789",
            document.getRoot().getAs<JsonText>("text").toString(),
            "chainAnchor must rebuild a multi-fragment purge in left-to-right order," +
                " not reversed",
        )
    }

    // Exact spec Scenario 1 / F2 / BLOCKER-1 probe (round-2 QA P2): build
    // "0123456789", style(6,8), delete [2,4) then [0,2), GC, then undo BOTH
    // as two SEPARATE undoAsync() calls (not one restore() call — chainAnchor
    // alone does not cover this; see the comment above). Before the rung (c)
    // ceiling fix, undo1 alone produced "45670189" (the new "01" fragment
    // anchored behind the far-away "89" survivor instead of the near "45"
    // one) and undo2 compounded it into "2345670189".
    @Test
    fun `undo-after-GC scrambles content into the exact spec scenario 1 order`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "0123456789")
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonText>("text").style(6, 8, mapOf("b" to "1"))
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(2, 4, "") }.await()
        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(0, 2, "") }.await()
        assertEquals("456789", document.getRoot().getAs<JsonText>("text").toString())

        val purged = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertTrue(purged > 0, "expected both purged tombstones to be collected")

        document.history.undoAsync().await()
        document.history.undoAsync().await()

        assertEquals(
            "0123456789",
            document.getRoot().getAs<JsonText>("text").toString(),
            "two separate undos after a GC pass must not scramble character order",
        )
    }

    // Exact spec Scenario 2 / F3 / BLOCKER-2 probe (round-2 QA P1): build
    // "0123456789", delete [4,6), GC, undo (recreates the deleted "45" node),
    // then edit(6,6,"X") at the boundary the recreated node sits on. Before
    // the insertion-chain re-link fix, the recreated node was never linked
    // into insertionPrev/insertionNext, so findFloorNodePreferToLeft walked
    // the stale insertionPrev straight past it, computed an out-of-range
    // split offset, and JsonText.edit's IllegalArgumentException catch
    // swallowed it — silently dropping "X" (result stayed "0123456789").
    @Test
    fun `edit after undo of a GC'd delete actually inserts at the boundary`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewText("text").edit(0, 0, "0123456789")
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(4, 6, "") }.await()
        assertEquals("01236789", document.getRoot().getAs<JsonText>("text").toString())

        val purged = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertTrue(purged > 0, "expected the purged tombstone to be collected")

        document.history.undoAsync().await()
        assertEquals("0123456789", document.getRoot().getAs<JsonText>("text").toString())

        document.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(6, 6, "X") }.await()

        assertEquals(
            "012345X6789",
            document.getRoot().getAs<JsonText>("text").toString(),
            "an edit at the recreated node's boundary must not silently no-op",
        )
    }

    // F5 (corrected per coordinator review against yorkie-js-sdk): a node
    // landing in alreadyRemovedIDs only got there via canRemove()'s
    // LWW-won-concurrent-overwrite case — this op's timestamp is causally
    // AFTER the existing tombstone, so it legitimately becomes the node's
    // new causal owner. removedSpans is deliberately UNFILTERED by
    // alreadyRemovedIDs (matching JS SDK rga_tree_split.ts's edit(), which
    // does not filter there either — only the GC-pair list does, to avoid
    // double-toggling an already-registered pair). Its regression test is
    // in RgaTreeSplitTest.kt at the raw layer: reaching the alreadyRemovedIDs
    // branch needs a version vector that knows the node's creation but NOT
    // yet its specific removal (canRemove()'s tombstoneKnown=false case) —
    // a Document-level crossSync() between two replicas always fully
    // synchronizes causal knowledge first, so a superset delete issued
    // AFTER a crossSync can never actually reach that branch (canRemove()
    // correctly refuses it — confirmed empirically, not just by reading).

    // F13 has its regression test in CrdtTextTest.kt (raw CrdtText layer):
    // `removeStyle` (not a same-length style overwrite, which doesn't leave
    // a tombstoned entry inside the node's own attribute map — see F12) is
    // needed to construct a genuine attribute tombstone inside a node that
    // then gets deleted, GC'd, and recreated.
}
