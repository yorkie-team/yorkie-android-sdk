package dev.yorkie.api

import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNode
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.helper.RecordingLogger
import dev.yorkie.util.Logger
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Converter coverage for the `TextNode.removal_events` field
 * (ported from JS SDK #1514/yorkie#2310): encode writes it only for a
 * ledgered node, decode rebuilds the ledger or drops a malformed one without
 * throwing, and a pre-attach reissue rewrites it.
 */
class TextRemovalLedgerConverterTest {

    private val actorA = "000000000000000000000001"
    private val actorB = "000000000000000000000002"

    private fun a(lamport: Long) = TimeTicket(lamport, 0u, actorA)
    private fun b(lamport: Long) = TimeTicket(lamport, 0u, actorB)

    @AfterTest
    fun tearDown() {
        Logger.init(RecordingLogger())
    }

    @Test
    fun `encodes nothing for a node removedAt alone fully describes`() {
        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        node.recordRemoval(a(2))

        val pb = split.toPBTextNodes().single { it.id.createdAt.toTimeTicket() == a(1) }
        assertTrue(
            pb.removalEventsList.isEmpty(),
            "a node removedAt alone describes encodes byte-identical to v0.7.25 -- " +
                "no removal_events written",
        )
    }

    @Test
    fun `round-trips a ledgered node through encode and decode`() {
        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        // Two uncancelled removals (A and B) force the full ledger.
        node.recordRemoval(a(2))
        node.recordRemoval(b(3))

        val pb = split.toPBTextNodes().single { it.id.createdAt.toTimeTicket() == a(1) }
        assertTrue(pb.removalEventsList.isNotEmpty(), "a ledgered node writes removal_events")

        val decoded = pb.toRgaTreeSplitNode()
        assertEquals(node.removedAt, decoded.removedAt)
        assertEquals(node.getRemovalEvents(), decoded.getRemovalEvents())
        assertEquals(a(2), decoded.uncancelledRemovalBy(actorA))
        assertEquals(b(3), decoded.uncancelledRemovalBy(actorB))
    }

    @Test
    fun `decodes an absent removal_events field as an unledgered node (v0725 bytes)`() {
        // Simulates bytes a v0.7.25 (pre-ledger) encoder wrote: no
        // removal_events field at all, removedAt alone.
        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        node.setRemovedAt(a(5))

        val pb = split.toPBTextNodes().single { it.id.createdAt.toTimeTicket() == a(1) }
        assertTrue(pb.removalEventsList.isEmpty())

        val decoded = pb.toRgaTreeSplitNode()
        assertEquals(a(5), decoded.removedAt)
        assertTrue(decoded.getRemovalEvents().isEmpty(), "no ledger -- exactly the v0.7.25 meaning")
    }

    @Test
    fun `logs and drops a malformed ledger keeping removedAt`() {
        val recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)

        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        node.setRemovedAt(a(5))
        val pb = split.toPBTextNodes().single { it.id.createdAt.toTimeTicket() == a(1) }

        // A ledger repeating removed_at as its own event: no undo can
        // produce this (a malformed-decode probe, ported from JS/Go's
        // "loads a snapshot whose removal events break the rules").
        val malformed = pb.toBuilder().addRemovalEvents(a(5).toPBTimeTicket()).build()

        val decoded = malformed.toRgaTreeSplitNode()

        assertEquals(a(5), decoded.removedAt, "the node keeps removed_at")
        assertTrue(
            decoded.getRemovalEvents().isEmpty(),
            "the malformed ledger is dropped, not applied",
        )
        assertEquals(1, recordingLogger.errorMessages.size)
        assertTrue(recordingLogger.errorMessages.single().contains("malformed removal ledger"))
    }

    @Test
    fun `logs and drops a ledger with a repeated event ticket`() {
        val recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)

        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        node.setRemovedAt(a(5))
        val pb = split.toPBTextNodes().single { it.id.createdAt.toTimeTicket() == a(1) }

        val malformed = pb.toBuilder()
            .addRemovalEvents(b(3).toPBTimeTicket())
            .addRemovalEvents(b(3).toPBTimeTicket())
            .build()

        val decoded = malformed.toRgaTreeSplitNode()

        assertEquals(a(5), decoded.removedAt)
        assertTrue(decoded.getRemovalEvents().isEmpty())
        assertEquals(1, recordingLogger.errorMessages.size)
    }

    @Test
    fun `logs and drops a ledger that does not reproduce removed_at`() {
        val recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)

        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        node.setRemovedAt(a(5))
        val pb = split.toPBTextNodes().single { it.id.createdAt.toTimeTicket() == a(1) }

        // A single, later-lamport, uncancelled event: `setRemovalEvents` rebuilds the
        // ledger from `b(9)` and `a(5)` (both live, no cancel pairs it), and `recompute`
        // picks the LATER one (`b(9)`) as `removedAt` -- which does not match the node's
        // actual `removed_at` (`a(5)`) -- the "do not reproduce removed_at" guard, distinct
        // from the "repeated ticket" and "removed_at itself repeated" cases above.
        val malformed = pb.toBuilder().addRemovalEvents(b(9).toPBTimeTicket()).build()

        val decoded = malformed.toRgaTreeSplitNode()

        assertEquals(a(5), decoded.removedAt, "the node keeps removed_at")
        assertTrue(decoded.getRemovalEvents().isEmpty(), "the non-reproducing ledger is dropped")
        assertEquals(1, recordingLogger.errorMessages.size)
    }

    @Test
    fun `round-trips a ledgered node through CrdtText toPBText and PBText toCrdtText`() {
        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        node.recordRemoval(a(2))
        node.recordRemoval(b(3))
        val text = CrdtText(split, InitialTimeTicket)

        val decodedText = text.toPBText().text.toCrdtText()
        val decodedNode = decodedText.rgaTreeSplit.first { it.id.offset == 0 }

        assertEquals(node.removedAt, decodedNode.removedAt)
        assertEquals(node.getRemovalEvents(), decodedNode.getRemovalEvents())
        assertEquals(a(2), decodedNode.uncancelledRemovalBy(actorA))
        assertEquals(b(3), decodedNode.uncancelledRemovalBy(actorB))
    }

    @Test
    fun `reissue rewrites removal_events to the new actor`() {
        // A Set/Add/ArraySet whose value is a CrdtText (an undo of a Remove)
        // is the one path that carries a full Text subtree through reissue
        // (api/Reissue.kt's reissueTextValue) -- the realistic pre-attach
        // shape this rule protects.
        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        node.recordRemoval(a(2))
        node.recordRemoval(b(3))
        val text = CrdtText(split, InitialTimeTicket)
        val setOp = SetOperation("k", text, InitialTimeTicket, a(4))

        val toActor = "000000000000000000000009"
        val reissued = reissueOperations(listOf(setOp), actorA, toActor).single() as SetOperation
        val reissuedText = reissued.value as CrdtText
        val reissuedNode = reissuedText.rgaTreeSplit.first { it.id.offset == 0 }

        // a's events move to the new actor (lamport != 0); b's stay.
        assertEquals(TimeTicket(2L, 0u, toActor), reissuedNode.uncancelledRemovalBy(toActor))
        assertEquals(b(3), reissuedNode.uncancelledRemovalBy(actorB))
    }

    @Test
    fun `a pre-attach restore-after-GC stand-in drops its unpaired ledger on reissue`() {
        // PBTimeTicket.reissue skips lamport 0, so the stand-in removal
        // (lamport 0, minted by the undoing actor) stays on the OLD actor
        // while its cancel moves to the new one: the decoded ledger no
        // longer pairs by actor and is dropped by the converter's
        // setRemovalEvents guard, leaving the node live (accepted as
        // JS/iOS).
        val split = RgaTreeSplit<TextValue>()
        val node = RgaTreeSplitNode(RgaTreeSplitNodeID(a(1), 0), TextValue("x"))
        split.insertAfter(split.head, node)
        val stable = TimeTicket(0L, 0u, actorA)
        node.recordRemoval(stable)
        node.cancelRemoval(stable, a(5))
        assertEquals(null, node.removedAt, "the stand-in was cancelled -- the node is live")

        val text = CrdtText(split, InitialTimeTicket)
        val setOp = SetOperation("k", text, InitialTimeTicket, a(6))

        val recordingLogger = RecordingLogger()
        Logger.init(recordingLogger)

        val toActor = "000000000000000000000009"
        val reissued = reissueOperations(listOf(setOp), actorA, toActor).single() as SetOperation
        val reissuedText = reissued.value as CrdtText
        val reissuedNode = reissuedText.rgaTreeSplit.first { it.id.offset == 0 }

        assertEquals(null, reissuedNode.removedAt, "dropped-ledger node stays live, as JS/iOS")
        assertTrue(reissuedNode.getRemovalEvents().isEmpty())
        assertEquals(1, recordingLogger.errorMessages.size)
    }
}
