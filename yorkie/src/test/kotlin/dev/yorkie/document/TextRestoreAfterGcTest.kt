package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RestoreSpan
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitPosRange
import dev.yorkie.document.crdt.TextValue
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.RestoreMode
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val ACTOR = "000000000000000000000001"
private const val PEER = "000000000000000000000002"

/**
 * Ports `270ffc66` (recreate purged text runs in order on restore, not
 * reversed) as JVM unit tests (AC6).
 *
 * Regression for the reversed-text undo (wafflebase#629). Typing character
 * by character makes each character its own single-char insertion. Deleting
 * a contiguous run and then undoing AFTER the tombstones were GC-purged
 * forces every character down restore's recreate path. Because no
 * character shares an insertion with any other, none of the same-insertion
 * anchor rungs fire; each recreated fragment must chain after the one
 * placed just before it, or the run comes back reversed ("my name" ->
 * "eman ym"). Un-tombstoning (no GC) already preserved order, which is why
 * this only reproduced once the run was purged.
 */
class TextRestoreAfterGcTest {

    @Test
    fun `recreates a purged multi-insertion run in document order on undo`() = runTest {
        val doc = Document("text-restore-after-gc")
        doc.setActor(ACTOR)
        doc.updateAsync { root, _ -> root.setNewText("t") }.await()

        val s = "hello my name is"
        for (i in s.indices) {
            val c = s[i].toString()
            doc.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(i, i, c) }.await()
        }
        assertEquals(s, doc.getRoot().getAs<JsonText>("t").toString())

        doc.updateAsync { root, _ ->
            root.getAs<JsonText>(
                "t",
            ).edit(6, 13, "")
        }.await() // "my name"
        assertEquals("hello  is", doc.getRoot().getAs<JsonText>("t").toString())

        val purged = doc.garbageCollect(maxVectorOf(listOf(ACTOR)))
        assertTrue(purged > 0, "the deleted run should be purged")

        doc.history.undoAsync().await()
        assertEquals(
            s,
            doc.getRoot().getAs<JsonText>("t").toString(),
            "a purged run must be recreated in document order, not reversed",
        )
    }

    @Test
    fun `single-insertion run is unaffected`() = runTest {
        val doc = Document("text-restore-after-gc-single")
        doc.setActor(ACTOR)
        doc.updateAsync { root, _ -> root.setNewText("t") }.await()
        doc.updateAsync { root, _ ->
            root.getAs<JsonText>("t").edit(0, 0, "hello my name is")
        }.await()
        doc.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(6, 13, "") }.await()
        doc.garbageCollect(maxVectorOf(listOf(ACTOR)))

        doc.history.undoAsync().await()
        assertEquals("hello my name is", doc.getRoot().getAs<JsonText>("t").toString())
    }

    // --- #1504 (06f5587b): keep attributes on the span a reversed insertion records ---
    //
    // The reverse of an insertion records the inserted run as a span. The undo only
    // re-tombstones it, so the span's value is never read; the redo flips the direction
    // and the same span becomes the content to restore. A replica that purged the node
    // rebuilds it from that span alone, so the span has to carry the attributes the edit
    // applied -- or a style applied to the run afterward -- or the two replicas diverge
    // (yorkie-js-sdk#1497, yorkie#2277).

    @Test
    fun `keeps the attributes of a re-inserted run when redoing after GC`() = runTest {
        val d1 = Document("text-redo-attrs-after-gc")
        val d2 = Document("text-redo-attrs-after-gc")
        d1.setActor(ACTOR)
        d2.setActor(PEER)
        d1.updateAsync { root, _ ->
            root.setNewText("t").edit(0, 0, "ab")
        }.await()
        crossSync(d1, d2)
        d1.clearHistory()

        d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(1, 1, "X", mapOf("b" to "1")) }
            .await()
        d1.history.undoAsync().await()
        crossSync(d1, d2)

        // Only d1 purges the undone run, so its redo must recreate the node from the
        // span while d2 revives the tombstone it still holds.
        assertTrue(d1.garbageCollect(maxVectorOf(listOf(ACTOR, PEER))) > 0)
        d1.history.redoAsync().await()
        crossSync(d1, d2)

        assertEquals("aXb", d1.getRoot().getAs<JsonText>("t").toString())
        assertEquals(mapOf("b" to "1"), d1.getRoot().getAs<JsonText>("t").values[1].attributes)
        assertEquals(d1.toJson(), d2.toJson())
    }

    @Test
    fun `keeps a style applied after the insert when redoing after GC`() = runTest {
        val d1 = Document("text-redo-late-attrs-after-gc")
        val d2 = Document("text-redo-late-attrs-after-gc")
        d1.setActor(ACTOR)
        d2.setActor(PEER)
        d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "ab") }.await()
        crossSync(d1, d2)
        d1.clearHistory()

        d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(1, 1, "X") }.await()
        crossSync(d1, d2)

        // The peer styles the inserted run after the fact, so the span recorded at
        // insert time knows nothing about it.
        d2.updateAsync { root, _ -> root.getAs<JsonText>("t").style(1, 2, mapOf("b" to "1")) }
            .await()
        crossSync(d1, d2)
        assertEquals(mapOf("b" to "1"), d1.getRoot().getAs<JsonText>("t").values[1].attributes)

        d1.history.undoAsync().await()
        crossSync(d1, d2)

        assertTrue(d1.garbageCollect(maxVectorOf(listOf(ACTOR, PEER))) > 0)
        d1.history.redoAsync().await()
        crossSync(d1, d2)

        assertEquals("aXb", d1.getRoot().getAs<JsonText>("t").toString())
        assertEquals(mapOf("b" to "1"), d1.getRoot().getAs<JsonText>("t").values[1].attributes)
        assertEquals(d1.toJson(), d2.toJson())
    }

    @Test
    fun `keeps the attributes across chained undo-redo with GC`() = runTest {
        val doc = Document("text-redo-attrs-chained")
        doc.setActor(ACTOR)
        doc.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "ab") }.await()
        doc.clearHistory()
        doc.updateAsync { root, _ ->
            root.getAs<JsonText>("t").edit(1, 1, "X", mapOf("b" to "1", "i" to "1"))
        }.await()

        repeat(3) {
            doc.history.undoAsync().await()
            doc.garbageCollect(maxVectorOf(listOf(ACTOR)))
            doc.history.redoAsync().await()

            assertEquals("aXb", doc.getRoot().getAs<JsonText>("t").toString())
            assertEquals(
                mapOf("b" to "1", "i" to "1"),
                doc.getRoot().getAs<JsonText>("t").values[1].attributes,
            )
        }
    }

    @Test
    fun `keeps a style covering part of the insert when redoing after GC`() = runTest {
        val d1 = Document("text-redo-partial-attrs-after-gc")
        val d2 = Document("text-redo-partial-attrs-after-gc")
        d1.setActor(ACTOR)
        d2.setActor(PEER)
        d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "ab") }.await()
        crossSync(d1, d2)
        d1.clearHistory()

        d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(1, 1, "XYZ") }.await()
        crossSync(d1, d2)
        d2.updateAsync { root, _ -> root.getAs<JsonText>("t").style(2, 3, mapOf("b" to "1")) }
            .await()
        crossSync(d1, d2)

        d1.history.undoAsync().await()
        crossSync(d1, d2)
        d1.garbageCollect(maxVectorOf(listOf(ACTOR, PEER)))
        d1.history.redoAsync().await()
        crossSync(d1, d2)

        assertEquals("aXYZb", d1.getRoot().getAs<JsonText>("t").toString())
        val values = d1.getRoot().getAs<JsonText>("t").values
        assertEquals(
            listOf(
                "a" to emptyMap(),
                "X" to emptyMap(),
                "Y" to mapOf("b" to "1"),
                "Z" to emptyMap(),
                "b" to emptyMap(),
            ),
            values.map { it.text to it.attributes },
        )
        assertEquals(d1.toJson(), d2.toJson())
    }

    @Test
    fun `keeps a plain re-inserted run unstyled when redoing after GC`() = runTest {
        val doc = Document("text-redo-plain-after-gc")
        doc.setActor(ACTOR)
        doc.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "ab") }.await()
        doc.clearHistory()
        doc.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(1, 1, "X") }.await()

        doc.history.undoAsync().await()
        doc.garbageCollect(maxVectorOf(listOf(ACTOR)))
        doc.history.redoAsync().await()

        assertEquals("aXb", doc.getRoot().getAs<JsonText>("t").toString())
        assertEquals(emptyMap(), doc.getRoot().getAs<JsonText>("t").values[1].attributes)
    }

    // #1504 case v: a remote-sourced restore carrying a stale span (missing
    // an attribute the live content gained since) must still execute its retombstone
    // correctly and produce NO reverse op -- source.producesReverseOps is false for
    // Remote, so the refreshed-or-not distinction is never externally observable here;
    // this pins the functional contract (no reverse payload, no exception) that
    // `source != OpSource.Remote` in executeRestore's guard protects. Diff review of
    // that guard is what confirms refreshSpans itself is skipped, not this test alone.
    @Test
    fun `a remote-sourced restore op never refreshes its stale spans`() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        val textCreatedAt = TimeTicket(1, 0u, ACTOR)
        val insertTicket = TimeTicket(2, 0u, ACTOR)
        val seedRgaTreeSplit = RgaTreeSplit<TextValue>()
        seedRgaTreeSplit.edit(
            RgaTreeSplitPosRange(seedRgaTreeSplit.indexToPos(0), seedRgaTreeSplit.indexToPos(0)),
            insertTicket,
            TextValue("ab"),
            versionVector = null,
        )
        SetOperation(
            key = "t",
            value = CrdtText(seedRgaTreeSplit, textCreatedAt),
            parentCreatedAt = obj.createdAt,
            executedAt = textCreatedAt,
        ).execute(root, OpSource.Local, null)
        // SetOperation registers a DEEP COPY of the value above; every further read goes
        // through the registered copy, never the seed object.
        val text = root.findByCreatedAt(textCreatedAt) as CrdtText

        // A style landed on the live run AFTER this stale span was captured -- were this
        // refreshed (as it would be for Local/UndoRedo), the retombstoned copy would pick
        // it up; for Remote it must not even try.
        val liveNode = text.rgaTreeSplit.treeByID.getValue(RgaTreeSplitNodeID(insertTicket, 0))
        liveNode.value.setAttribute("b", "late", TimeTicket(3, 0u, ACTOR))
        val staleSpan = RestoreSpan(insertTicket, 0, 2, TextValue("ab"))

        val restoreOp = EditOperation(
            fromPos = text.rgaTreeSplit.indexToPos(0),
            toPos = text.rgaTreeSplit.indexToPos(0),
            content = "",
            parentCreatedAt = textCreatedAt,
            executedAt = TimeTicket(4, 0u, ACTOR),
            attributes = emptyMap(),
            restoreSpans = null,
            restoreMode = RestoreMode.Restore,
            retombstoneSpans = listOf(staleSpan),
        )

        val result = restoreOp.execute(root, OpSource.Remote, null)

        assertEquals(emptyList(), result.reverseOps, "Remote discards the reverse op entirely")
        assertEquals("", text.toString(), "the retombstone itself still executes for Remote")
    }

    // #1504 upstream residual (not a port defect): a peer's style landing on the undone
    // run's TOMBSTONE -- concurrently with the undo, after the undo's reverse-op refresh
    // snapshot was already taken -- is invisible to that snapshot. Redoing after one
    // replica purges the run rebuilds it WITHOUT the style, while a replica that still
    // holds the tombstone keeps it: the two replicas diverge. The same scenario was run
    // against the real yorkie-js-sdk v0.7.26 and diverges identically there, and
    // `executeRestore` (EditOperation.kt:235-301) was traced hunk-by-hunk against JS
    // `06f5587b` with the same guard/order/substitution, so Android is a faithful port --
    // the gap lives in the upstream refresh-at-undo-time design. Characterization test
    // only, no production change: it pins the current (diverging) outcome so a future
    // upstream fix has a red test to turn green, per an upstream draft tracking
    // "refreshSpans at undo time cannot capture a style that lands on the tombstone
    // afterward".
    @Test
    fun `a style landing on the tombstone concurrently with the undo is lost on redo after GC`() =
        runTest {
            val d1 = Document("text-redo-concurrent-tombstone-style")
            val d2 = Document("text-redo-concurrent-tombstone-style")
            d1.setActor(ACTOR)
            d2.setActor(PEER)
            d1.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "ab") }.await()
            crossSync(d1, d2)
            d1.clearHistory()

            d1.updateAsync { root, _ -> root.getAs<JsonText>("t").edit(1, 1, "X") }.await()
            crossSync(d1, d2)

            // d1 undoes (its reverse-op refresh snapshot is taken here, before any style
            // exists); d2 concurrently styles the still-live X on its own side.
            d1.history.undoAsync().await()
            d2.updateAsync { root, _ -> root.getAs<JsonText>("t").style(1, 2, mapOf("b" to "1")) }
                .await()
            crossSync(d1, d2)
            crossSync(d1, d2)

            assertTrue(d1.garbageCollect(maxVectorOf(listOf(ACTOR, PEER))) > 0)
            d1.history.redoAsync().await()
            crossSync(d1, d2)
            crossSync(d1, d2)

            // d1 rebuilds X from the stale (pre-style) refresh span: no attributes survive.
            assertEquals(emptyMap(), d1.getRoot().getAs<JsonText>("t").values[1].attributes)
            // d2 never purged, so it un-tombstones its own copy, which kept the style.
            assertEquals(
                mapOf("b" to "1"),
                d2.getRoot().getAs<JsonText>("t").values[1].attributes,
            )
            // The replicas diverge -- pinning the known, JS-identical limitation, not
            // asserting this outcome is correct.
            assertTrue(
                d1.toJson() != d2.toJson(),
                "pinning the current (diverging) outcome; closing this gap needs an " +
                    "upstream fix to refreshSpans' undo-time snapshot, not an Android-only " +
                    "patch",
            )
        }
}
