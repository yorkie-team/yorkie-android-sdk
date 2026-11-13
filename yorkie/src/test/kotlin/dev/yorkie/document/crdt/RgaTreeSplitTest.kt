package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class RgaTreeSplitTest {
    private lateinit var target: RgaTreeSplit<TextValue>

    @Before
    fun setUp() {
        target = RgaTreeSplit()
    }

    @Test
    fun `should handle edit operations with case1`() {
        var range = RgaTreeSplitPosRange(target.indexToPos(0), target.indexToPos(0))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("ABCD"), versionVector = null)
        range = RgaTreeSplitPosRange(target.indexToPos(1), target.indexToPos(3))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("12"), versionVector = null)

        assertEquals("A12D", target.toString())
    }

    @Test
    fun `should handle edit operations with case2`() {
        var range = RgaTreeSplitPosRange(target.indexToPos(0), target.indexToPos(0))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("ABCD"), versionVector = null)
        range = RgaTreeSplitPosRange(target.indexToPos(3), target.indexToPos(3))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("\n"), versionVector = null)

        assertEquals("ABC\nD", target.toString())
    }

    // F5: removedSpans (unlike gcPairs) must NOT be filtered by
    // alreadyRemovedIDs. A node only lands in alreadyRemovedIDs via
    // canRemove()'s LWW-won-concurrent-overwrite case, meaning THIS op's
    // timestamp is causally after the existing tombstone — it legitimately
    // becomes the node's new causal owner, so its identity belongs in the
    // undo/restorable set. Only the GC-pair bookkeeping skips it, to avoid
    // double-toggling an already-registered pair. Matches JS SDK
    // rga_tree_split.ts's edit(), which does not filter removedSpans either.
    //
    // Reaching the alreadyRemovedIDs branch requires a version vector that
    // knows the node's creation (creationKnown) but not yet its specific
    // removal (tombstoneKnown=false) — a superset delete whose own version
    // vector is causally behind an already-applied tombstone on part of its
    // range.
    @Test
    fun `removedSpans include an already-removed node this op newly owns`() {
        val actor1 = "000000000000000000000001"
        val actor2 = "000000000000000000000002"
        fun tick(actor: String, lamport: Long) = TimeTicket(lamport, 0u, actor)

        // actor1 creates "0123456789" at lamport 1, then deletes "45"
        // ([4,6)) at lamport 2 — both local (no version vector yet).
        target.edit(
            RgaTreeSplitPosRange(target.indexToPos(0), target.indexToPos(0)),
            tick(actor1, 1),
            TextValue("0123456789"),
            versionVector = null,
        )
        target.edit(
            RgaTreeSplitPosRange(target.indexToPos(4), target.indexToPos(6)),
            tick(actor1, 2),
            null,
            versionVector = null,
        )

        // actor2's superset delete [2,6) ("2367" by visible index) carries a
        // version vector that knows actor1's creation (lamport 1) but NOT
        // yet actor1's removal (lamport 2) — genuinely concurrent from
        // actor2's causal perspective, so it becomes "45"'s new owner too.
        val vector = VersionVector(mapOf(actor1 to 1L))
        val result = target.edit(
            RgaTreeSplitPosRange(target.indexToPos(2), target.indexToPos(6)),
            tick(actor2, 3),
            null,
            vector,
        )

        assertEquals("0189", target.toString())
        assertEquals(
            listOf("23", "45", "67"),
            result.removedSpans.map { it.value.content },
            "removedSpans must include the already-removed node this op newly owns",
        )
        assertEquals(
            listOf("23", "67"),
            result.gcPairs.map { it.child.value.content },
            "gcPairs must still exclude it, to avoid double-toggling its existing registration",
        )
    }

    // Port yorkie-js-sdk `rga_tree_split.ts` splitNode guard (`ed25355e`,
    // #1449, yorkie#2111): JS's `substring`/`truncate` silently clamp a
    // negative offset to 0, duplicating content; Android instead used to
    // reach `TextValue.subSequence`'s `substring(negative, len)`, throwing
    // `StringIndexOutOfBoundsException` deep inside after already starting
    // the split. splitNode is a private implementation detail with no
    // reachable public caller that can pass a negative offset (every caller
    // derives it from a floor-matched node, which is always <= the target),
    // so this reaches it directly via reflection to pin the guard itself.
    @Test
    fun `splitNode rejects a negative offset before any mutation`() {
        val range = RgaTreeSplitPosRange(target.indexToPos(0), target.indexToPos(0))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("ABCD"), versionVector = null)
        val node = target.single()
        val method = RgaTreeSplit::class.java.getDeclaredMethod(
            "splitNode",
            RgaTreeSplitNode::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val before = target.toString()

        val cause = runCatching { method.invoke(target, node, -1) }.exceptionOrNull()
        assertTrue(
            cause is InvocationTargetException && cause.cause is IllegalArgumentException,
            "a negative offset must throw IllegalArgumentException, not mutate silently",
        )
        assertEquals(before, target.toString(), "a rejected split must leave the tree untouched")
    }

    // #1504 (06f5587b) case iv: a single span split across a live piece, a
    // PURGED gap (no surviving piece at all) and a TOMBSTONED-but-not-purged piece. The
    // purged gap must fall back to the original snapshot (all this replica still knows
    // about it); the surviving tombstone must be read from its CURRENT value, picking up
    // an attribute applied to it after the snapshot was taken -- the correctness crux
    // #1504 fixes (a peer's style, which canStyle admits even on a tombstone).
    @Test
    fun `refreshSpans splits a span across a live piece, a purged gap and a tombstoned piece`() {
        val actor = "000000000000000000000001"
        fun tick(lamport: Long) = TimeTicket(lamport, 0u, actor)
        val ins = tick(1)

        target.edit(
            RgaTreeSplitPosRange(target.indexToPos(0), target.indexToPos(0)),
            ins,
            TextValue("0123456789"),
            versionVector = null,
        )

        // Tombstone [4,6) ("45"), then physically purge it -- a true gap.
        target.edit(
            RgaTreeSplitPosRange(target.indexToPos(4), target.indexToPos(6)),
            tick(2),
            null,
            versionVector = null,
        )
        target.delete(target.treeByID.getValue(RgaTreeSplitNodeID(ins, 4)))

        // Tombstone [6,8) ("67") WITHOUT purging, then style it directly -- as if a peer
        // styled the tombstoned run after this replica's own snapshot was taken.
        target.edit(
            RgaTreeSplitPosRange(target.indexToPos(4), target.indexToPos(6)),
            tick(3),
            null,
            versionVector = null,
        )
        val survivingTombstone = target.treeByID.getValue(RgaTreeSplitNodeID(ins, 6))
        survivingTombstone.value.setAttribute("b", "late", tick(4))

        val snapshot = RestoreSpan(ins, 0, 10, TextValue("0123456789"))
        val refreshed = target.refreshSpans(listOf(snapshot))

        assertEquals(listOf(0 to 4, 4 to 6, 6 to 8, 8 to 10), refreshed.map { it.start to it.end })
        assertEquals(
            "0123",
            refreshed[0].value.content,
            "a live piece is read from its current value",
        )
        assertEquals("45", refreshed[1].value.content, "a purged gap falls back to the snapshot")
        assertEquals(
            emptyMap(),
            refreshed[1].value.attributes,
            "the snapshot fallback gains no attributes",
        )
        assertEquals(
            "67",
            refreshed[2].value.content,
            "a surviving tombstone is read from its current value",
        )
        assertEquals(
            mapOf("b" to "late"),
            refreshed[2].value.attributes,
            "an attribute applied to the tombstone after the snapshot must be picked up",
        )
        assertEquals("89", refreshed[3].value.content)
        assertEquals(emptyMap(), refreshed[3].value.attributes)
    }
}
