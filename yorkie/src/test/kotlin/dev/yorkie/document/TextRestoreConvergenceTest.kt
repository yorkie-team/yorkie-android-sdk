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
}
