package dev.yorkie.document

import dev.yorkie.document.change.Change
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.helper.oneWayDeliver
import dev.yorkie.helper.pull
import dev.yorkie.helper.push
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Ports JS `gc_rga_barrier_test.ts` (v0.7.24, yorkie-js-sdk `dbff8892`,
 * #1405; itself a port of Go's `gc_rga_barrier_test.go` /
 * `gc_rga_barrier_cost_test.go`, yorkie `ba82ed91`). A collecting replica
 * must not reorder the elements that survive collection: an insert whose RGA
 * forward skip stopped at a node on one replica must not run past that node
 * on a replica that already collected it.
 *
 * Every version vector that authorises a purge here is one the server could
 * genuinely compute: the element-wise min of the replicas' own vectors
 * ([minVV]), mirroring the server's `UpdateMinVersionVector`. [maxVectorOf]
 * is used only at the end, to assert that nothing is retained forever.
 *
 * Uses the one-way [push]/[pull]/[oneWayDeliver] helpers, not [crossSync]:
 * these cases need a replica that has pushed but not yet pulled its peer's
 * reply, which a self-acking two-way exchange cannot represent.
 *
 * The array-move cases reach an element's CURRENT POSITION via the internal
 * [CrdtArray.getAllRGANodes]/`elementEntry`, not the public [JsonArray]
 * insert surface: [JsonArray.put]'s anchor is element
 * identity, not position identity, so it cannot by itself reproduce the
 * shape a concurrent move leaves behind (a known follow-up, tracked
 * separately).
 */
class GcSuccessorBarrierTest {

    private fun newReplicas(): Pair<Document, Document> {
        val d1 = Document("test-doc").apply { setActor(ACTOR_1) }
        val d2 = Document("test-doc").apply { setActor(ACTOR_2) }
        return d1 to d2
    }

    private fun arrayTarget(d: Document): CrdtArray = d.getRootObject()["arr"] as CrdtArray

    private fun arrJson(d: Document): String = arrayTarget(d).toJson()

    /**
     * Resolves [elementCreatedAt] to its CURRENT position identity.
     * [JsonArray.put]'s `prevCreatedAt` resolves a POSITION key first
     * and falls back to an element key only through
     * [dev.yorkie.document.crdt.RgaTreeList]'s `nodeMapByPositionCreatedAt`,
     * which still holds a moved element's ORIGINAL position node under that
     * same key (a move never removes it, only marks it dead) -- so anchoring
     * a put directly on an [CrdtPrimitive.createdAt] after that element has
     * moved silently resolves to its OLD slot instead of its current one.
     * [CrdtArray.posCreatedAt] is the position-identity lookup the public
     * [JsonArray] surface has no equivalent of (JS `ArrayProxy.insertAfter`
     * does this internally); closing the
     * gap publicly is a known follow-up, tracked separately. Needed only for an anchor that may have moved -- an
     * anchor on an element never moved on [d] resolves to the same ticket
     * either way.
     */
    private fun currentPositionOf(d: Document, elementCreatedAt: TimeTicket): TimeTicket {
        return arrayTarget(d).posCreatedAt(elementCreatedAt)
    }

    private fun textOf(d: Document): CrdtText = d.getRootObject()["t"] as CrdtText

    private fun treeOf(d: Document): CrdtTree = d.getRootObject()["t"] as CrdtTree

    private fun createdAtOf(d: Document, value: String): TimeTicket {
        for (node in arrayTarget(d).getAllRGANodes()) {
            val element = node.elementEntry?.element as? CrdtPrimitive ?: continue
            if (element.value == value) return element.createdAt
        }
        error("element not found: $value")
    }

    private fun removedAtOf(d: Document, value: String): TimeTicket {
        for (node in arrayTarget(d).getAllRGANodes()) {
            val element = node.elementEntry?.element as? CrdtPrimitive ?: continue
            if (element.value == value) {
                return requireNotNull(element.removedAt) { "element $value is not removed" }
            }
        }
        error("element not found: $value")
    }

    private fun movedPositionTicket(d: Document): TimeTicket {
        for (node in arrayTarget(d).getAllRGANodes()) {
            val entry = node.elementEntry ?: continue
            val movedAt = entry.posMovedAt ?: continue
            return movedAt
        }
        error("no moved position node found")
    }

    private fun totalSize(docSize: DocSize): Int {
        return docSize.live.data + docSize.live.meta + docSize.gc.data + docSize.gc.meta
    }

    private fun assertMatchesRebuild(d: Document, msg: String) {
        val rebuilt = CrdtRoot(d.getRootObject().deepCopy())
        assertEquals(rebuilt.docSize, d.getDocSize(), "$msg: docSize")
        assertEquals(rebuilt.garbageLength, d.garbageLength, "$msg: garbage")
    }

    /**
     * Go's `time.MinVersionVector`: element-wise min, and 0 for an actor any
     * vector does not carry.
     */
    private fun minVV(vararg vectors: VersionVector): VersionVector {
        val actors = vectors.flatMap { it.vectorMap.keys }.toSet()
        return VersionVector().apply {
            actors.forEach { actor ->
                set(actor, vectors.minOf { it.vectorMap[actor] ?: 0L })
            }
        }
    }

    /**
     * [push], recording [d]'s version vector as of just before the push into
     * [rows] under [actor] — what a server would store and compute [minVV]
     * from. [actor] is passed explicitly (the known [setActor] value) rather
     * than read back off [d], since nothing here needs a generic accessor.
     */
    private suspend fun pushRecording(
        d: Document,
        actor: String,
        rows: MutableMap<String, VersionVector>,
    ): List<Change> {
        rows[actor] = VersionVector(d.getVersionVector().vectorMap)
        return push(d)
    }

    @Test
    fun `keeps insert order when a removed element was concurrently moved`() = runTest {
        val (remover, mover) = newReplicas()

        remover.updateAsync { r, _ ->
            val arr = r.setNewArray("arr")
            arr.put("a")
            arr.put("w")
        }.await()
        crossSync(remover, mover)
        assertEquals("""["a","w"]""", arrJson(mover))

        // Concurrent branch 1 (remover): remove "a".
        remover.updateAsync { r, _ -> r.getAs<JsonArray>("arr").removeAt(0) }.await()

        // Concurrent branch 2 (mover): move "a" after "w", then -- after a few
        // unrelated changes, so its ticket outruns the insert below -- append
        // "s" anchored on "a"'s new position.
        mover.updateAsync { r, _ -> r.getAs<JsonArray>("arr").moveAfterByIndex(1, 0) }.await()
        assertEquals("""["w","a"]""", arrJson(mover))
        repeat(3) { i -> mover.updateAsync { r, _ -> r["mover-$i"] = i }.await() }
        mover.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.put("s", currentPositionOf(mover, arr[1].id))
        }.await()
        assertEquals("""["w","a","s"]""", arrJson(mover))

        // The remover pushes only the removal. Both replicas have now seen
        // it, so it is causally stable; the move and the append are not.
        oneWayDeliver(remover, mover)
        assertEquals("""["w","s"]""", arrJson(mover))
        val vv = minVV(remover.getVersionVector(), mover.getVersionVector())

        // The remover keeps editing without pulling: one filler change, then
        // an insert anchored on "w". Its ticket sits strictly between the
        // move and the mover's append.
        remover.updateAsync { r, _ -> r["remover-0"] = 0 }.await()
        remover.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.put("x", arr[0].id)
        }.await()
        assertEquals("""["w","x"]""", arrJson(remover))

        val moveAt = movedPositionTicket(mover)
        val insertAt = createdAtOf(remover, "x")
        val appendAt = createdAtOf(mover, "s")
        assertTrue(insertAt > moveAt, "insert must be newer than move")
        assertTrue(appendAt > insertAt, "append must be newer than insert")
        assertTrue(vv.afterOrEqual(removedAtOf(remover, "a")))
        assertFalse(vv.afterOrEqual(moveAt))

        // The mover collects with that vector: the only difference between
        // them.
        mover.garbageCollect(vv)

        oneWayDeliver(remover, mover)
        crossSync(remover, mover)
        assertEquals(
            arrJson(remover),
            arrJson(mover),
            "collecting an element whose position node was created by a " +
                "concurrent move reordered a later concurrent insert",
        )

        // Holding the purge back must be a delay, not a leak.
        remover.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        mover.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        assertEquals(0, remover.garbageLength, "remover leaked garbage")
        assertEquals(0, mover.garbageLength, "mover leaked garbage")
        crossSync(remover, mover)
        assertEquals(arrJson(remover), arrJson(mover))
        assertMatchesRebuild(remover, "remover")
        assertMatchesRebuild(mover, "mover")
    }

    /**
     * Port of Go `TestConcurrentMovesOfSameElementServerMinVV`. The element
     * is moved twice, concurrently, by two actors. The slot the second move
     * abandons was created by the FIRST move, so the ticket that must stay
     * covered is the first move's, not the element's insert.
     */
    private suspend fun concurrentMovesOfOneElementConverge(collect: Boolean) {
        val (dA, dB) = newReplicas()
        val rows = mutableMapOf<String, VersionVector>()

        dA.updateAsync { r, _ ->
            val arr = r.setNewArray("arr")
            arr.put("a")
            arr.put("w")
            arr.put("v")
        }.await()
        pull(dB, pushRecording(dA, ACTOR_1, rows))
        pushRecording(dB, ACTOR_2, rows)
        assertEquals("""["a","w","v"]""", arrJson(dB))

        // A (local, unpushed): move "a" after "w" (m1), filler, then insert
        // "s" anchored on "a"'s new (m1) position.
        dA.updateAsync { r, _ -> r.getAs<JsonArray>("arr").moveAfterByIndex(1, 0) }.await()
        repeat(2) { i -> dA.updateAsync { r, _ -> r["fa-$i"] = i }.await() }
        dA.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.put("s", currentPositionOf(dA, arr[1].id))
        }.await()
        assertEquals("""["w","a","s","v"]""", arrJson(dA))

        // B (concurrently, from base): move "a" after "v" (m2). B pushes.
        dB.updateAsync { r, _ -> r.getAs<JsonArray>("arr").moveAfterByIndex(2, 0) }.await()
        assertEquals("""["w","v","a"]""", arrJson(dB))
        val fromB = pushRecording(dB, ACTOR_2, rows)

        // A pulls m2, then pushes its own backlog.
        pull(dA, fromB)
        val fromA = pushRecording(dA, ACTOR_1, rows)

        // A collects with the server's minVV. This is the only asymmetry.
        if (collect) {
            dA.garbageCollect(minVV(*rows.values.toTypedArray()))
        }

        // B, which has not pulled A's backlog yet, inserts "x" after "w".
        dB.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.put("x", arr[0].id)
        }.await()
        val fromB2 = pushRecording(dB, ACTOR_2, rows)

        pull(dB, fromA)
        pull(dA, fromB2)

        dA.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        dB.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        assertEquals(
            arrJson(dB),
            arrJson(dA),
            "replicas diverged permanently under a server-computed minVV",
        )
        assertEquals(0, dA.garbageLength, "A leaked garbage")
        assertEquals(0, dB.garbageLength, "B leaked garbage")
    }

    @Test
    fun `keeps concurrent moves of one element converged when the mover collects`() = runTest {
        concurrentMovesOfOneElementConverge(collect = true)
    }

    @Test
    fun `keeps concurrent moves of one element converged when it does not collect`() = runTest {
        concurrentMovesOfOneElementConverge(collect = false)
    }

    /**
     * Port of Go `TestConcurrentDeleteAndInsertThenGCKeepsTextOrder`: the
     * same defect in [dev.yorkie.document.crdt.RgaTreeSplit], with no move
     * anywhere in it. The tombstone left by the delete is what stops the
     * skip in `findNodeWithSplit`.
     */
    @Test
    fun `keeps text order after collecting a concurrently deleted node`() = runTest {
        val (remover, mover) = newReplicas()

        remover.updateAsync { r, _ ->
            val text = r.setNewText("t")
            text.edit(0, 0, "A")
            text.edit(0, 0, "W")
        }.await()
        crossSync(remover, mover)
        assertEquals("WA", textOf(mover).toString())

        // mover: bump its clock, then append "S" anchored right after "A".
        repeat(3) { i -> mover.updateAsync { r, _ -> r["mover-$i"] = i }.await() }
        mover.updateAsync { r, _ -> r.getAs<JsonText>("t").edit(2, 2, "S") }.await()
        assertEquals("WAS", textOf(mover).toString())

        // remover: delete "A". It reaches the mover, so it is causally stable.
        remover.updateAsync { r, _ -> r.getAs<JsonText>("t").edit(1, 2, "") }.await()
        oneWayDeliver(remover, mover)
        assertEquals("WS", textOf(mover).toString())
        val vv = minVV(remover.getVersionVector(), mover.getVersionVector())

        // remover keeps editing without pulling: insert "X" right after "W".
        remover.updateAsync { r, _ -> r["remover-0"] = 0 }.await()
        remover.updateAsync { r, _ -> r.getAs<JsonText>("t").edit(1, 1, "X") }.await()
        assertEquals("WX", textOf(remover).toString())

        mover.garbageCollect(vv)
        oneWayDeliver(remover, mover)
        crossSync(remover, mover)
        assertEquals(
            textOf(remover).toString(),
            textOf(mover).toString(),
            "text replicas diverged after collecting a tombstone",
        )

        remover.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        mover.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        assertEquals(0, remover.garbageLength, "remover leaked garbage")
        assertEquals(0, mover.garbageLength, "mover leaked garbage")
        assertMatchesRebuild(remover, "remover")
        assertMatchesRebuild(mover, "mover")
    }

    /**
     * Port of Go `TestConcurrentDeleteAndInsertThenGCKeepsTreeOrder`: the
     * same defect in [dev.yorkie.document.crdt.CrdtTree], whose sibling skip
     * in `findNodesAndSplitText` reads the parent's children with the
     * removed ones included.
     */
    @Test
    fun `keeps tree order after collecting a concurrently deleted node`() = runTest {
        val (remover, mover) = newReplicas()

        remover.updateAsync { r, _ ->
            val tree = r.setNewTree("t", element("doc") { element("p") {} })
            tree.edit(1, 1, text { "A" })
            tree.edit(1, 1, text { "W" })
        }.await()
        crossSync(remover, mover)
        assertEquals("<doc><p>WA</p></doc>", treeOf(mover).toXml())

        repeat(3) { i -> mover.updateAsync { r, _ -> r["mover-$i"] = i }.await() }
        mover.updateAsync { r, _ -> r.getAs<JsonTree>("t").edit(3, 3, text { "S" }) }.await()
        assertEquals("<doc><p>WAS</p></doc>", treeOf(mover).toXml())

        remover.updateAsync { r, _ -> r.getAs<JsonTree>("t").edit(2, 3) }.await()
        oneWayDeliver(remover, mover)
        assertEquals("<doc><p>WS</p></doc>", treeOf(mover).toXml())
        val vv = minVV(remover.getVersionVector(), mover.getVersionVector())

        remover.updateAsync { r, _ -> r["remover-0"] = 0 }.await()
        remover.updateAsync { r, _ -> r.getAs<JsonTree>("t").edit(2, 2, text { "X" }) }.await()
        assertEquals("<doc><p>WX</p></doc>", treeOf(remover).toXml())

        mover.garbageCollect(vv)
        oneWayDeliver(remover, mover)
        crossSync(remover, mover)
        assertEquals(
            treeOf(remover).toXml(),
            treeOf(mover).toXml(),
            "tree replicas diverged after collecting a tombstone",
        )

        remover.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        mover.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        assertEquals(0, remover.garbageLength, "remover leaked garbage")
        assertEquals(0, mover.garbageLength, "mover leaked garbage")
        assertMatchesRebuild(remover, "remover")
        assertMatchesRebuild(mover, "mover")
    }

    // --- GC successor barrier cost ---
    //
    // The barrier delays a purge, so its cost is retention. These pin what
    // separates an acceptable delay from a leak: retention is bounded by how
    // far behind the collection vector is, not by the array's size; it
    // drains completely once the vector catches up; and an array under a
    // byte limit still accepts every move.
    //
    // A drained document does NOT cost what it cost before the moves: a
    // moved element carries a movedAt ticket it did not carry before, and a
    // rebuild charges it. That is content, not retention, so the residue is
    // bounded by one ticket per moved element and checked against a rebuild.

    private suspend fun newStringArray(d: Document, n: Int) {
        d.updateAsync { r, _ ->
            val arr = r.setNewArray("arr")
            repeat(n) { i -> arr.put("e" + i.toString().padStart(3, '0')) }
        }.await()
    }

    private suspend fun dragToFront(d: Document, index: Int) {
        d.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.moveFront(arr[index].id)
        }.await()
    }

    /**
     * Port of Go `TestBarrierRetentionIsBoundedByLagNotByArraySize`.
     */
    private suspend fun boundsRetentionByLag(lag: Int) {
        val n = 200
        val lagL = lag.toLong()
        val doc = Document("barrier-cost-lag").apply { setActor(ACTOR_1) }
        newStringArray(doc, n)

        fun lagging(): VersionVector {
            val v = VersionVector(doc.getVersionVector().vectorMap)
            val cur = requireNotNull(v.get(ACTOR_1))
            if (cur > lagL) {
                v.set(ACTOR_1, cur - lagL)
            }
            return v
        }

        doc.garbageCollect(lagging())
        val baselineTotal = totalSize(doc.getDocSize())

        var peak = 0
        for (i in 1 until n) {
            dragToFront(doc, i)
            doc.garbageCollect(lagging())
            peak = maxOf(peak, doc.garbageLength)
        }
        assertTrue(peak <= lag, "retention must be bounded by the lag, not by the array size")

        doc.garbageCollect(doc.getVersionVector())
        assertEquals(0, doc.garbageLength, "retention must drain")
        assertEquals(DataSize(data = 0, meta = 0), doc.getDocSize().gc)
        assertEquals(
            baselineTotal + (n - 1) * TIME_TICKET_SIZE,
            totalSize(doc.getDocSize()),
            "a fully synced document costs its content: one movedAt per move",
        )
        assertMatchesRebuild(doc, "after dragging every element")
    }

    @Test
    fun `bounds retention by the lag not the array size when lag is 1`() = runTest {
        boundsRetentionByLag(lag = 1)
    }

    @Test
    fun `bounds retention by the lag not the array size when lag is 5`() = runTest {
        boundsRetentionByLag(lag = 5)
    }

    @Test
    fun `bounds retention by the lag not the array size when lag is 50`() = runTest {
        boundsRetentionByLag(lag = 50)
    }

    /**
     * Port of Go `TestBarrierKeepsAnArrayUnderItsSizeLimitMovable`.
     */
    @Test
    fun `keeps an array under its size limit movable`() = runTest {
        val doc = Document("barrier-cost-limit").apply { setActor(ACTOR_1) }
        doc.setMaxSizePerDocument(1000 + 19 * TIME_TICKET_SIZE)
        newStringArray(doc, 20)
        doc.garbageCollect(doc.getVersionVector())

        for (i in 1 until 20) {
            dragToFront(doc, i)
            doc.garbageCollect(doc.getVersionVector())
        }
        assertEquals(0, doc.garbageLength)
    }

    /**
     * Port of Go `TestBarrierCostsNothingOnConcurrentMoves`: two actors
     * moving elements concurrently, synced with a server-computed minVV.
     */
    @Test
    fun `costs nothing lasting on concurrent moves`() = runTest {
        val n = 60
        val rounds = 25
        val (d1, d2) = newReplicas()
        val rows = mutableMapOf<String, VersionVector>()

        newStringArray(d1, n)
        pull(d2, pushRecording(d1, ACTOR_1, rows))
        rows[ACTOR_2] = VersionVector(d2.getVersionVector().vectorMap)
        val syncedTotal = totalSize(d1.getDocSize())

        var peak = 0
        for (round in 0 until rounds) {
            dragToFront(d1, 1 + (round * 7) % (n - 1))
            dragToFront(d2, 1 + (round * 13 + 3) % (n - 1))

            val c1 = pushRecording(d1, ACTOR_1, rows)
            val c2 = pushRecording(d2, ACTOR_2, rows)
            pull(d2, c1)
            pull(d1, c2)

            val vv = minVV(*rows.values.toTypedArray())
            d1.garbageCollect(vv)
            d2.garbageCollect(vv)
            peak = maxOf(peak, d1.garbageLength, d2.garbageLength)
        }
        assertTrue(peak <= 2, "concurrent moves must not accumulate retention")

        rows[ACTOR_1] = VersionVector(d1.getVersionVector().vectorMap)
        rows[ACTOR_2] = VersionVector(d2.getVersionVector().vectorMap)
        val final = minVV(*rows.values.toTypedArray())
        d1.garbageCollect(final)
        d2.garbageCollect(final)

        assertEquals(d2.toJson(), d1.toJson(), "replicas must agree")
        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)
        assertEquals(d2.getDocSize(), d1.getDocSize())

        val drained = totalSize(d1.getDocSize())
        assertTrue(drained >= syncedTotal)
        assertTrue(drained <= syncedTotal + 2 * rounds * TIME_TICKET_SIZE)
        assertMatchesRebuild(d1, "d1 after concurrent moves")
        assertMatchesRebuild(d2, "d2 after concurrent moves")
    }

    companion object {
        private const val ACTOR_1 = "000000000000000000000001"
        private const val ACTOR_2 = "000000000000000000000002"
    }
}
