package dev.yorkie.document.crdt

import com.google.gson.JsonParser
import dev.yorkie.api.toCrdtElement
import dev.yorkie.api.toPBJsonObject
import dev.yorkie.document.Document
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSync
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports yorkie-js-sdk `test/unit/document/crdt/element_rht_order_test.ts` (v0.7.22, `9970907c`,
 * yorkie-js-sdk#1343) plus a two-replica over-the-wire twin.
 *
 * [ElementRht.set] used to tombstone the previous occupant via [CrdtElement.remove], gated on the
 * raw `createdAt`, BEFORE deciding the LWW winner on [CrdtElement.getPositionedAt]. For an
 * occupant with `createdAt < executedAt < positionedAt` -- exactly what an undo/redo restore
 * leaves behind -- the two gates disagreed: the occupant was tombstoned yet stayed linked under
 * the key, the loser was never registered removed, and `get` reported the key absent. Because the
 * snapshot decoder ([dev.yorkie.api.ElementConverter.PBJsonObject.toCrdtObject]) replays every
 * decoded member through `set(key, value, value.getPositionedAt())` in the server's wire (map)
 * order, the same unchanged document could decode differently per attach.
 *
 * The fix (this release) moves the eviction inside the winner branch so both decisions read
 * `positionedAt`, mirroring Go `ElementRHT.SetWithExecutedAt`
 * (`yorkie/pkg/document/crdt/element_rht.go`) and making decode order-independent for every
 * permutation. Cases here are RED at baseline `f6037bbe` (the reverted hunk); GREEN after the fix
 * (this commit). Parity unverified -- JS not executed this session (source-read of
 * `element_rht.ts` @ v0.7.22, `9970907c`); the Android side (this class) was executed.
 *
 * Working draft executed as `ElementRhtOrderProbeTest` (7/7 RED at `f6037bbe`); ported verbatim
 * plus the C9 twin.
 */
class ElementRhtOrderTest {
    private val t1 = TimeTicket(1, 0u, "actorA")
    private val t3 = TimeTicket(3, 0u, "actorB")
    private val t4 = TimeTicket(4, 0u, "actorB")
    private val t5 = TimeTicket(5, 0u, "actorA")

    private fun <T> permute(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items.toList())
        return items.indices.flatMap { i ->
            val rest = items.subList(0, i) + items.subList(i + 1, items.size)
            permute(rest).map { tail -> listOf(items[i]) + tail }
        }
    }

    private fun members(): List<CrdtElement> {
        val live = CrdtPrimitive("kept", t1).apply { movedAt = t5 }
        val tomb = CrdtPrimitive("displaced", t3).apply { remove(t4) }
        return listOf(live, tomb)
    }

    @Test
    fun `resolves the key the same way in every arrival order`() {
        for (order in permute(listOf(0, 1))) {
            val rht = ElementRht<CrdtElement>()
            val elems = members()
            for (idx in order) rht.set("frame", elems[idx], elems[idx].getPositionedAt())
            val node = runCatching { rht["frame"] }.getOrNull()
            assertNotNull("order $order lost the key entirely", node)
            assertEquals("order $order", "\"kept\"", node!!.toJson())
        }
    }

    @Test
    fun `never tombstones the member that goes on to win`() {
        for (order in permute(listOf(0, 1))) {
            val rht = ElementRht<CrdtElement>()
            val elems = members()
            for (idx in order) rht.set("frame", elems[idx], elems[idx].getPositionedAt())
            val nodes = rht.map { it.value.toJson() to it.isRemoved }
            assertTrue(
                "order $order: live member must survive, got $nodes",
                nodes.any { it.first == "\"kept\"" && !it.second },
            )
        }
    }

    /**
     * The losing branch marks the incoming value removed so key iteration
     * skips it. A value that arrives already removed is already skipped, so
     * the marking has nothing to do -- but [CrdtElement.remove] accepts any
     * later ticket, so ungated it is not a no-op: it moves the tombstone's
     * `removedAt` off the ticket of the removal that actually happened and
     * onto the occupant's `positionedAt`.
     *
     * That is the shape the decoder replays on every snapshot load
     * (yorkie-js-sdk#1377, fixed yorkie-js-sdk#1395 `8d965dd2`): GC then waits
     * on the wrong ticket, and the replica's re-serialized snapshots and
     * `docSize.gc` differ from replicas that never reloaded. RED at
     * `4d353504` (the reverted hunk, `else if (!node.isRemoved)`), GREEN after
     * this commit's `else if (!node.isRemoved && !value.isRemoved)`.
     */
    @Test
    fun `leaves a losing tombstone removedAt alone`() {
        for (order in permute(listOf(0, 1))) {
            val rht = ElementRht<CrdtElement>()
            val elems = members()
            val tomb = elems[1]
            for (idx in order) rht.set("frame", elems[idx], elems[idx].getPositionedAt())

            assertTrue("order $order: tombstone revived", tomb.isRemoved)
            assertEquals(
                "order $order: removedAt moved from $t4 to ${tomb.removedAt}",
                t4,
                tomb.removedAt,
            )
        }
    }

    @Test
    fun `keeps the key readable when the loser is live`() {
        // Three readers of the tombstoned-winner bug would disagree: `has(key)` says false,
        // iteration finds a "kept" node that reports itself removed, and a fresh decode from
        // the snapshot may recover it. A missing key is at least consistent; this was worse.
        for (order in permute(listOf(0, 1))) {
            val rht = ElementRht<CrdtElement>()
            val live = CrdtPrimitive("kept", t1).apply { movedAt = t5 }
            val loser = CrdtPrimitive("displaced", t3)
            val elems = listOf(live, loser)
            for (idx in order) rht.set("frame", elems[idx], elems[idx].getPositionedAt())
            assertFalse("order $order: winner tombstoned", live.isRemoved)
            assertTrue("order $order: loser left live", loser.isRemoved)
            assertEquals(
                "order $order",
                """{"frame":"kept"}""",
                CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = rht).toJson(),
            )
        }
    }

    @Test
    fun `returns the evicted occupant only when the incoming value wins`() {
        // `SetOperation.execute` feeds the returned element to `registerRemovedElement`, so
        // reporting the winner on a losing set would double-book a live element as garbage.
        val rht = ElementRht<CrdtElement>()
        val first = CrdtPrimitive("first", t1)
        assertNull("an empty key evicts nothing", rht.set("frame", first, t1))
        val restored = CrdtPrimitive("kept", TimeTicket(2, 0u, "actorB"))
        assertSame(
            "a winning set reports the occupant it tombstoned",
            first,
            rht.set("frame", restored, t5),
        )
        assertTrue(first.isRemoved)
        val loser = CrdtPrimitive("late", t3)
        assertNull(
            "a losing set evicts nothing, so it reports nothing",
            rht.set("frame", loser, t3),
        )
        assertFalse("the winner stays live", restored.isRemoved)
        assertTrue("the loser marks itself removed", loser.isRemoved)
    }

    @Test
    fun `resolves a key carrying more than two members in every order`() {
        val elems = listOf(
            CrdtPrimitive("kept", t1).apply { movedAt = t5 },
            CrdtPrimitive("displaced", t3).apply { remove(t4) },
            CrdtPrimitive("late", TimeTicket(2, 0u, "actorC")),
        )
        for (order in permute(listOf(0, 1, 2))) {
            val rht = ElementRht<CrdtElement>()
            val copies = elems.map { it.deepCopy() }
            for (idx in order) rht.set("frame", copies[idx], copies[idx].getPositionedAt())
            val node = runCatching { rht["frame"] }.getOrNull()
            assertNotNull("order $order lost the key", node)
            assertEquals("order $order", "\"kept\"", node!!.toJson())
        }
    }

    private suspend fun docWithRestoredKey(redo: Boolean): Document {
        val doc = Document("d1")
        doc.updateAsync { root, _ -> root["frame"] = "v1" }.await()
        doc.updateAsync { root, _ -> root["frame"] = "v2" }.await()
        doc.history.undoAsync().await()
        assertEquals("""{"frame":"v1"}""", doc.toJson())
        if (redo) {
            doc.history.redoAsync().await()
            assertEquals("""{"frame":"v2"}""", doc.toJson())
        }
        return doc
    }

    private fun decodesIdentically(redo: Boolean) = runTest {
        val doc = docWithRestoredKey(redo)
        val want = doc.getRootObject().toJson()
        val encoded = doc.getRootObject().toPBJsonObject()
        val nodes = encoded.jsonObject.nodesList
        assertTrue("the key must carry a tombstone alongside the live member", nodes.size >= 2)
        assertTrue("permutation cost is factorial", nodes.size <= 3)
        for ((i, order) in permute(nodes.indices.toList()).withIndex()) {
            val shuffled = encoded.toBuilder().setJsonObject(
                encoded.jsonObject.toBuilder().clearNodes().addAllNodes(
                    order.map { nodes[it] },
                ).build(),
            ).build()
            val rebuilt = shuffled.toCrdtElement() as CrdtObject
            assertEquals("permutation #$i rebuilt a different object", want, rebuilt.toJson())
        }
    }

    @Test
    fun `decodes identically in every member order - key restored by undo`() = decodesIdentically(
        false,
    )

    @Test
    fun `decodes identically in every member order - key restored by undo then redo`() =
        decodesIdentically(true)

    /**
     * A snapshot round-trip must be a fixpoint on the tombstones it carries.
     * The decoder replays every decoded member through [ElementRht.set], and a
     * tombstone that sorts after the live occupant takes the losing branch --
     * which used to bump its `removedAt` to the occupant's `positionedAt`. The
     * document then measures differently depending on whether it has been
     * through a snapshot load (yorkie-js-sdk#1377, fixed yorkie-js-sdk#1395
     * `8d965dd2`).
     *
     * `createdAt(live) < createdAt(tomb) < removedAt(tomb) < movedAt(live)`:
     * the tombstone loses to the undo-restored occupant, and its own removal
     * is strictly older than the ticket that restored the occupant. Actor IDs
     * must be the wire's 24-hex form -- anything else fails
     * [dev.yorkie.api.toByteString]'s hex decode before the comparison is
     * even reached.
     */
    @Test
    fun `preserves each tombstone removedAt across a decode`() {
        val actorA = "000000000000000000000001"
        val actorB = "000000000000000000000002"
        val live = CrdtPrimitive("kept", TimeTicket(1, 0u, actorA)).apply {
            movedAt = TimeTicket(5, 0u, actorA)
        }
        val tomb = CrdtPrimitive("displaced", TimeTicket(3, 0u, actorB)).apply {
            remove(TimeTicket(4, 0u, actorB))
        }
        val rht = ElementRht<CrdtElement>()
        rht.set("frame", live, live.getPositionedAt())
        rht.set("frame", tomb, tomb.getPositionedAt())
        val root = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = rht)

        fun removedAts(obj: CrdtObject): Map<TimeTicket, TimeTicket?> =
            obj.memberNodes.associate { it.value.createdAt to it.value.removedAt }

        val want = removedAts(root)
        assertTrue(
            "the key must carry a tombstone for this to test anything",
            want.values.any { it != null },
        )

        val rebuilt = root.toPBJsonObject().toCrdtElement() as CrdtObject
        assertEquals(want, removedAts(rebuilt))
    }

    /**
     * The single-replica decoder cases above are pinned again over the
     * wire, converging two live replicas plus a third rebuilt directly from the first's root
     * bytes -- the exact shape seen live (a fresh client decoding an
     * undone container as empty in 9-11 of 12 runs; `Document.toJson()` emitting invalid JSON).
     * Both `d2.toJson()` and the rebuilt `d3.toJson()` must parse as JSON, pinning the
     * `JsonStringifier` invalid-JSON symptom shut.
     */
    @Test
    fun `converges over the wire after an undo-restored key`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor("000000000000000000000001")
        d2.setActor("000000000000000000000002")

        d1.updateAsync { root, _ -> root["frame"] = "v1" }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root["frame"] = "v2" }.await()
        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)

        assertEquals("""{"frame":"v1"}""", d1.toJson())
        assertEquals("""{"frame":"v1"}""", d2.toJson())

        val rebuilt = d1.getRootObject().toPBJsonObject().toCrdtElement() as CrdtObject
        assertEquals(d1.toJson(), rebuilt.toJson())

        JsonParser.parseString(d2.toJson())
        JsonParser.parseString(rebuilt.toJson())
    }
}
