package dev.yorkie.api

import dev.yorkie.document.crdt.RestoreSpan
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.RestoreMode
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports `restore_converter_test.ts` (JS SDK 5d5cac63, #1293) as JVM unit
 * tests (AC10): converter round-trips restore/retombstone/companion-span
 * ops, an ordinary edit carries no restore payload, and a restore op's base
 * shape is a harmless no-op for a peer that ignores the new fields.
 */
class RestoreConverterTest {

    // A valid-hex actorID is required here (unlike domain-only tests): these
    // cases round-trip through the wire encoding, which hex-decodes the actor.
    private val actorID = "000000000000000000000001"
    private val seed = TimeTicket(1L, 0u, actorID)
    private val executedAt = TimeTicket(4L, 0u, actorID)
    private val pos = RgaTreeSplitPos(RgaTreeSplitNodeID(seed, 0), 0)

    private fun span(
        start: Int,
        end: Int,
        content: String,
    ) = RestoreSpan(seed, start, end, TextValue(content))

    private fun restoreOp(
        restoreSpans: List<RestoreSpan<TextValue>>? = null,
        restoreMode: RestoreMode? = null,
        retombstoneSpans: List<RestoreSpan<TextValue>>? = null,
        content: String = "",
    ) = EditOperation(
        fromPos = pos,
        toPos = pos,
        content = content,
        parentCreatedAt = InitialTimeTicket,
        executedAt = executedAt,
        attributes = emptyMap(),
        restoreSpans = restoreSpans,
        restoreMode = restoreMode,
        retombstoneSpans = retombstoneSpans,
    )

    private fun EditOperation.roundTrip() =
        listOf(toPBOperation()).toOperations().single() as EditOperation

    @Test
    fun `round-trips a restore operation over the wire`() {
        val spans = listOf(span(4, 6, "45"), span(2, 8, "234567"))
        val restored = restoreOp(
            restoreSpans = spans,
            restoreMode = RestoreMode.Restore,
        ).roundTrip()

        assertEquals(RestoreMode.Restore, restored.restoreMode)
        val got = requireNotNull(restored.restoreSpans)
        assertEquals(2, got.size)
        assertEquals(4, got[0].start)
        assertEquals(6, got[0].end)
        assertEquals("45", got[0].value.content)
        assertEquals("234567", got[1].value.content)
        assertEquals(seed, got[0].createdAt)
    }

    @Test
    fun `round-trips a retombstone operation`() {
        val restored = restoreOp(
            restoreSpans = listOf(span(4, 6, "45")),
            restoreMode = RestoreMode.Retombstone,
        ).roundTrip()

        assertEquals(RestoreMode.Retombstone, restored.restoreMode)
        assertEquals(1, restored.restoreSpans?.size)
    }

    @Test
    fun `round-trips the companion retombstoneSpans of a replace reverse`() {
        // The reverse of a replace revives the removed content (restoreSpans)
        // and re-removes the inserted content (retombstoneSpans), both by
        // identity. Both span sets must survive the wire or a peer diverges.
        val restored = restoreOp(
            restoreSpans = listOf(span(2, 4, "CD")),
            restoreMode = RestoreMode.Restore,
            retombstoneSpans = listOf(span(0, 2, "12")),
        ).roundTrip()

        assertEquals(RestoreMode.Restore, restored.restoreMode)
        assertEquals(1, restored.restoreSpans?.size)
        assertEquals("CD", restored.restoreSpans?.get(0)?.value?.content)
        assertEquals(1, restored.retombstoneSpans?.size)
        assertEquals("12", restored.retombstoneSpans?.get(0)?.value?.content)
        assertEquals(seed, restored.retombstoneSpans?.get(0)?.createdAt)
    }

    @Test
    fun `leaves ordinary edits without a restore payload`() {
        val restored = restoreOp(content = "hi").roundTrip()

        assertNull(restored.restoreSpans)
        assertEquals("hi", restored.content)
    }

    @Test
    fun `decodes to a harmless no-op for peers that ignore restore fields`() {
        // Mixed-version interop contract: a restore/undo op carries its
        // content only in restoreSpans; its base Edit fields are a
        // zero-width, empty-content edit (from === to, content === ""). A
        // peer without restore support drops the unknown fields and applies
        // just the base edit — inserting nothing and deleting nothing. This
        // pins that wire contract so a future change can't quietly start
        // emitting inline content on the restore path.
        val op =
            restoreOp(restoreSpans = listOf(span(4, 6, "45")), restoreMode = RestoreMode.Restore)

        val pbOp = op.toPBOperation()
        assertTrue(pbOp.hasEdit())
        assertEquals(
            "",
            pbOp.edit.content,
            "restore ops carry no inline content for an old peer to re-insert",
        )

        val restored = listOf(pbOp).toOperations().single() as EditOperation
        assertEquals(
            restored.fromPos,
            restored.toPos,
            "restore ops are zero-width, so an old peer deletes nothing either",
        )
        assertEquals("", restored.content)
        // A new peer still receives the full identity payload.
        assertEquals(RestoreMode.Restore, restored.restoreMode)
        assertEquals(1, restored.restoreSpans?.size)
    }
}
