package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RestoreSpan
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.crossSyncOverWire
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

    // S5: same both-undos convergence scenario as `runBothUndos`, but routed
    // through crossSyncOverWire — proves RestoreSpan encode/decode survives
    // a real protobuf wire round-trip inside a convergence flow, not just
    // an in-memory hand-off (AC14).
    @Test
    fun `converges when both replicas undo overlapping deletes over the wire`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewText("text").edit(0, 0, "0123456789") }.await()
        crossSyncOverWire(d1, d2)

        // delete "45"
        d1.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(4, 6, "") }.await()
        // delete "234567"
        d2.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(2, 8, "") }.await()
        crossSyncOverWire(d1, d2)
        assertEquals("0189", d1.getRoot().getAs<JsonText>("text").toString())

        d1.history.undoAsync().await()
        crossSyncOverWire(d1, d2)
        d2.history.undoAsync().await()
        crossSyncOverWire(d1, d2)

        assertEquals("0123456789", d1.getRoot().getAs<JsonText>("text").toString())
        assertEquals(
            identitySequence(d1.crdtText()),
            identitySequence(d2.crdtText()),
            "restore payloads must survive a real wire round-trip and still converge",
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

    // F2 / BLOCKER-1 (spec 011 B2 supersedes prior round comments here):
    // earlier revisions of this file first reported the reviewer's rung (c)
    // probe as non-reproducible, then "fixed" it locally with a
    // nearest-successor `ceilingEntry` search plus an invented `chainAnchor`
    // rung. Spec 011 B2 reverts both: rung (c) is exactly the JS/server
    // rightmost-survivor form (`floorEntry(createdAt, MAX)` gated
    // `offset >= gapEnd`), hand-traced against `rga_tree_split.ts:992-1002`
    // and `rga_tree_split.go:896-914`. The scramble the ceilingEntry rung
    // "fixed" locally is a SHARED upstream bug, not an Android divergence —
    // see `undo-after-GC scrambles content into the exact spec scenario 1
    // order` below and the round build report's upstream issue draft. This
    // test still passes with the reverted rung: rung (b)
    // (`floorEntry(createdAt, gapStart - 1)`, same insertion) finds the
    // fragment this same restore() call just recreated immediately to its
    // left, chaining the run forward without the recreated fragments ever
    // reaching rung (c).
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

    // Spec 011 scenario 2 / B2 (upstream-shared scramble, disclosed not
    // fixed): build "0123456789", style(6,8), delete [2,4) then [0,2), GC,
    // then undo BOTH as two SEPARATE undoAsync() calls. Rung (c) anchors a
    // recreated fragment behind the RIGHTMOST surviving piece of the whole
    // insertion ("89"), not the nearer one ("45") — JS `rga_tree_split.ts` @
    // 5d5cac63 and server `rga_tree_split.go` @ v0.7.13 both do the exact
    // same thing (hand-traced; see the round build report's JS parity probe
    // and upstream issue draft), so this scramble reproduces identically
    // from the same input on every SDK — a shared upstream bug, not an
    // Android divergence. The nearest-successor fix is NOT applied here
    // (spec 011 binding decision): convergence with JS/server, not a
    // "better" local placement, is the goal this round. Tracked upstream —
    // see the build report's "Upstream issue draft" section.
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
            "2345670189",
            document.getRoot().getAs<JsonText>("text").toString(),
            "JS/server-parity placement scrambles character order after a GC pass" +
                " (shared upstream bug, tracked upstream — see the build report)",
        )
    }

    // AC4 two-client pair: the same scenario-2 sequence runs on replica A,
    // cross-syncing after each step so B's tree structure (split points from
    // style(), tombstones from the deletes) matches A's exactly. Both
    // replicas then purge the SAME tombstones with a vector covering the
    // editing actor, so B's restore (identity-based, relayed from A's undo
    // ops) ALSO takes the recreate-from-scratch path and lands on the SAME
    // rung-(c) anchor as A — convergence on the identical (scrambled) string
    // AND identical node-identity sequence is the invariant this pins; the
    // scramble itself is the disclosed upstream bug, not something this test
    // re-litigates.
    @Test
    fun `two replicas converge to the identical scrambled order after cross-sync`() = runTest {
        val a = Document("test-doc")
        val b = Document("test-doc")
        a.setActor(actor1)
        b.setActor(actor2)

        a.updateAsync { root, _ -> root.setNewText("text").edit(0, 0, "0123456789") }.await()
        crossSync(a, b)

        a.updateAsync { root, _ ->
            root.getAs<JsonText>("text").style(6, 8, mapOf("b" to "1"))
        }.await()
        crossSync(a, b)

        a.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(2, 4, "") }.await()
        crossSync(a, b)

        a.updateAsync { root, _ -> root.getAs<JsonText>("text").edit(0, 2, "") }.await()
        crossSync(a, b)

        assertEquals("456789", a.getRoot().getAs<JsonText>("text").toString())
        assertEquals("456789", b.getRoot().getAs<JsonText>("text").toString())

        val vector = maxVectorOf(listOf(actor1))
        val purgedA = a.garbageCollect(vector)
        val purgedB = b.garbageCollect(vector)
        assertTrue(purgedA > 0, "expected both purged tombstones to be collected on A")
        assertTrue(purgedB > 0, "expected both purged tombstones to be collected on B")

        a.history.undoAsync().await()
        a.history.undoAsync().await()
        assertEquals("2345670189", a.getRoot().getAs<JsonText>("text").toString())

        crossSync(a, b)

        assertEquals(
            a.getRoot().getAs<JsonText>("text").toString(),
            b.getRoot().getAs<JsonText>("text").toString(),
            "convergence on the identical (scrambled) string is the invariant;" +
                " order is the disclosed upstream bug",
        )
        assertEquals(
            identitySequence(a.crdtText()),
            identitySequence(b.crdtText()),
            "both replicas must converge to identical node ids too",
        )
    }

    // Spec 011 scenario 4 (reviewer probe, B2 AC2): insertion "abcde" purges
    // [0,1)="a", [1,2)="b", [3,4)="d" leaving [2,3)="c" and [4,5)="e" live.
    // Restoring [0,1) must anchor before the RIGHTMOST survivor ("e"), not
    // the nearer "c" — exactly the JS/server rung (c) placement
    // (`rga_tree_split.ts:992-1002`, `rga_tree_split.go:896-914`), confirming
    // the reverted rung matches both upstream implementations on this probe.
    @Test
    fun `restore anchors before the rightmost survivor not the nearest one`() {
        val split = RgaTreeSplit<TextValue>()
        val insertTick = TimeTicket(1L, 0u, actor1)

        split.edit(
            RgaTreeSplitPosRange(split.indexToPos(0), split.indexToPos(0)),
            insertTick,
            TextValue("abcde"),
            versionVector = null,
        )

        // Soft-delete "a" [0,1), "b" [1,2), "d" [3,4) as three separate ops
        // (each an independently purgeable node), leaving "c" and "e" live.
        // Live indices shift as each preceding piece is soft-deleted (its
        // length collapses to 0 in treeByIndex).
        val delTick = TimeTicket(2L, 0u, actor1)
        split.edit(
            RgaTreeSplitPosRange(split.indexToPos(0), split.indexToPos(1)),
            delTick,
            null,
            versionVector = null,
        ) // "a"
        split.edit(
            RgaTreeSplitPosRange(split.indexToPos(0), split.indexToPos(1)),
            delTick,
            null,
            versionVector = null,
        ) // "b" (now live index 0)
        split.edit(
            RgaTreeSplitPosRange(split.indexToPos(1), split.indexToPos(2)),
            delTick,
            null,
            versionVector = null,
        ) // "d" (live "cde" -> index 1)
        assertEquals("ce", split.toString())

        // Physically purge the three tombstones (mirrors a GC pass) so
        // restore() must recreate "a" from scratch rather than un-tombstone it.
        split.treeByID.values.filter { it.isRemoved }.toList().forEach(split::delete)

        val restoreSpan = RestoreSpan(insertTick, 0, 1, TextValue("a"))
        split.restore(listOf(restoreSpan), TimeTicket(3L, 0u, actor1))

        val liveContent = split.filterNot { it.isRemoved }.map { it.value.content }
        assertEquals(
            listOf("c", "a", "e"),
            liveContent,
            "the recreated \"a\" must anchor before the RIGHTMOST survivor \"e\"," +
                " not the nearer \"c\"",
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
