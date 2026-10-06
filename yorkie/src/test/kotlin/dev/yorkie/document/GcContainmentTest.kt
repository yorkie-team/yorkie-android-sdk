package dev.yorkie.document

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `gc_containment_test.ts` (yorkie-js-sdk v0.7.21, `1ad69a12`, PR #1341,
 * closes #1340) as JVM unit tests, plus 2 extras carried from the merged
 * yorkie-ios-sdk PR #275 squash (`5054ed7e2a`) and 1 two-replica twin of
 * the array-assignment loop (JS cases 1/2 are single-replica).
 *
 * Cases 1 and 2 pin the array-set halves of the #1341 port
 * (`ArraySetOperation.execute` registers its value WITH a parent and
 * registers the element it displaces; `JsonArray.setValueInternal` registers
 * the displaced element on the clone) and fail without them. Cases 3-7 (plus
 * the iOS extras) pin the already-ported main ledger
 * (`CrdtRoot`/`SetOperation`/`ElementRht`), which PR #366 review r2
 * (`b55bfc02`/`1fb44383`, merged `1cfa2654`) ported early — GREEN before and
 * after this change; no source change accompanies them.
 */
class GcContainmentTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"
    private val actor3 = "000000000000000000000003"

    private fun rootOf(document: Document): CrdtRoot {
        val field = Document::class.java.getDeclaredField("root")
        field.isAccessible = true
        return field.get(document) as CrdtRoot
    }

    /**
     * Delivers [from]'s pending local changes to [to] only, without syncing
     * [to]'s own pending changes back, mirroring the JS SDK unit test
     * `deliver` helper used by `gc_containment_test.ts`'s multi-replica cases.
     */
    private suspend fun deliver(from: Document, to: Document) {
        val pack = from.createChangePack()
        to.applyChangePack(
            ChangePack(
                from.getKey(),
                CheckPoint.InitialCheckPoint,
                pack.changes,
                null,
                false,
                VersionVector(),
            ),
        )
        from.applyChangePack(
            ChangePack(
                from.getKey(),
                CheckPoint(0, pack.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
    }

    /**
     * Delivers [from]'s pending local changes to every document in [to],
     * mirroring the JS SDK unit test `broadcast` helper.
     */
    private suspend fun broadcast(from: Document, to: List<Document>) {
        val pack = from.createChangePack()
        to.forEach { receiver ->
            receiver.applyChangePack(
                ChangePack(
                    from.getKey(),
                    CheckPoint.InitialCheckPoint,
                    pack.changes,
                    null,
                    false,
                    VersionVector(),
                ),
            )
        }
        from.applyChangePack(
            ChangePack(
                from.getKey(),
                CheckPoint(0, pack.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
    }

    // JS case 1 --------------------------------------------------------

    @Test
    fun `collects an array element replaced by an array set`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("arr").putNewObject().apply { this["a"] = 1 }
        }.await()

        document.updateAsync { root, _ ->
            root.getAs<JsonArray>("arr").setNewObject(0)["b"] = 2
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr").removeAt(0) }.await()

        document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertEquals(0, document.garbageLength)
        assertJsonContentEquals("""{"arr":[]}""", document.toJson())
    }

    // JS case 2 --------------------------------------------------------

    @Test
    fun `collects the element an array assignment displaces`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(0) }.await()
        val vector = maxVectorOf(listOf(document.changeID.actor))

        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 1 }.await()
        document.garbageCollect(vector)
        val steady = document.getDocSize()

        for (v in 2..10) {
            document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = v }.await()
            document.garbageCollect(vector)
        }

        assertJsonContentEquals("""{"arr":[10]}""", document.toJson())
        assertEquals(0, document.garbageLength)
        assertEquals(steady, document.getDocSize())
    }

    // iOS extra: guards the clone-path half of the port -----------------

    @Test
    fun `an array assignment loop keeps the clone in step with the root`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(0) }.await()

        for (v in 1..10) {
            document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = v }.await()
        }

        assertJsonContentEquals(document.toJson(), requireNotNull(document.clone).root.toJson())
        assertEquals(document.getDocSize(), requireNotNull(document.clone).root.docSize)
    }

    // C9 twin: JS cases 1/2 are single-replica ---------------------------

    @Test
    fun `an array assignment loop converges on two replicas`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        val vector = maxVectorOf(listOf(actor1, actor2))

        d1.updateAsync { root, _ -> root.setNewArray("arr").put(0) }.await()
        crossSync(d1, d2)

        for (v in 1..5) {
            d1.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = v }.await()
            crossSync(d1, d2)
        }

        d1.garbageCollect(vector)
        d2.garbageCollect(vector)

        assertJsonContentEquals("""{"arr":[5]}""", d1.toJson())
        assertJsonContentEquals("""{"arr":[5]}""", d2.toJson())
        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)
        assertEquals(d1.getDocSize(), d2.getDocSize())
    }

    // JS case 3 (ledger pin; GREEN before and after) ---------------------

    @Test
    fun `collects the tombstone an undone object remove leaves on a peer`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewObject("obj")["k"] = 1
            root["keep"] = 0
        }.await()
        deliver(d1, d2)

        d1.updateAsync { root, _ -> root.remove("obj") }.await()
        d1.history.undoAsync().await()
        deliver(d1, d2)

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)

        assertJsonContentEquals("""{"keep":0,"obj":{"k":1}}""", d1.toJson())
        assertJsonContentEquals("""{"keep":0,"obj":{"k":1}}""", d2.toJson())
        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)
    }

    // JS case 4 (ledger pin; GREEN before and after) ---------------------

    @Test
    fun `survives a gc set member left behind by an older client`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("items").putNewObject().apply { this["a"] = 1 }
        }.await()

        // Stage a stale gcElementSetByCreatedAt member with no corresponding
        // registration, simulating a gc-set entry left behind by an older
        // client version. JS id `1:000000000000000000000000:999`.
        val staleCreatedAt = TimeTicket(1, 999u, "000000000000000000000000")
        val root = rootOf(document)
        val gcSetField = CrdtRoot::class.java.getDeclaredField("gcElementSetByCreatedAt")
        gcSetField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val gcSet = gcSetField.get(root) as MutableSet<TimeTicket>
        gcSet.add(staleCreatedAt)

        assertTrue(document.garbageLength > 0)
        val sizeBeforeCollect = document.getDocSize()

        document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertEquals(sizeBeforeCollect, document.getDocSize())
        assertJsonContentEquals("""{"items":[{"a":1}]}""", document.toJson())
    }

    // JS case 5 (ledger pin; GREEN before and after) ---------------------

    @Test
    fun `survives a gc set member whose element has no parent`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("items").putNewObject().apply { this["a"] = 1 }
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonArray>("items").removeAt(0) }.await()
        document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertJsonContentEquals("""{"items":[]}""", document.toJson())
        assertEquals(0, document.garbageLength)

        // A standalone, already-removed element registered with no parent:
        // registerElement's adoptTombstones books it into the gc set even
        // though its parent link is null (JS case 5).
        val orphan = CrdtPrimitive(1, TimeTicket(1, 998u, "000000000000000000000000"))
        orphan.remove(TimeTicket(1, 999u, "000000000000000000000000"))
        rootOf(document).registerElement(orphan, null)

        val sizeBeforeCollect = document.getDocSize()
        document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertEquals(sizeBeforeCollect, document.getDocSize())
        assertJsonContentEquals("""{"items":[]}""", document.toJson())
    }

    // JS case 6 (three replicas; JS itself asserts no convergence here —
    // only that applying the pack does not throw and the peer-added member
    // stays addressable, per the assertions added below)

    @Test
    fun `keeps a member a peer added into it addressable`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        val d3 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d3.setActor(actor3)

        d1.updateAsync { root, _ -> root.setNewObject("obj")["k"] = 1 }.await()
        broadcast(d1, listOf(d2, d3))

        var nId: TimeTicket? = null
        d2.updateAsync { root, _ ->
            nId = root.getAs<JsonObject>("obj").setNewObject("n").apply { this["y"] = 1 }.id
        }.await()
        broadcast(d2, listOf(d3))

        d1.updateAsync { root, _ -> root.remove("obj") }.await()
        d1.history.undoAsync().await()
        deliver(d1, d3)

        d2.updateAsync { root, _ ->
            root.getAs<JsonObject>("obj").getAs<JsonObject>("n")["x"] = 5
        }.await()
        // Applying d2's pack to d3 must not throw — this is the #1340 wedge.
        deliver(d2, d3)

        // "Must not throw" alone would stay green even if Android silently
        // skipped the op (a missing-parent op is dropped,
        // SetOperation.kt:74-76). Assert the peer-added member is still
        // resolvable on the receiver by its own identity, with d2's later edit
        // applied, and that collecting afterwards does not throw either.
        val resolvedN = rootOf(d3).findByCreatedAt(requireNotNull(nId))
        assertJsonContentEquals("""{"y":1,"x":5}""", requireNotNull(resolvedN).toJson())
        d3.garbageCollect(maxVectorOf(listOf(actor1, actor2, actor3)))
    }

    // iOS extra: the undoing replica's own ordering of case 6 ------------

    @Test
    fun `keeps a member the undoing replica holds addressable`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewObject("obj")["k"] = 1 }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.remove("obj") }.await()
        var nId: TimeTicket? = null
        d2.updateAsync { root, _ ->
            nId = root.getAs<JsonObject>("obj").setNewObject("n").apply { this["y"] = 1 }.id
        }.await()
        deliver(d2, d1)

        d1.history.undoAsync().await()

        d2.updateAsync { root, _ ->
            root.getAs<JsonObject>("obj").getAs<JsonObject>("n")["x"] = 5
        }.await()
        // Applying d2's pack to d1 (the undoing replica, which now holds the
        // member d2 added) must not throw.
        deliver(d2, d1)

        // Same addressability assertion as JS case 6 above.
        val resolvedN = rootOf(d1).findByCreatedAt(requireNotNull(nId))
        assertJsonContentEquals("""{"y":1,"x":5}""", requireNotNull(resolvedN).toJson())
        d1.garbageCollect(maxVectorOf(listOf(actor1, actor2)))
    }

    // JS case 7 (ledger pin; GREEN before and after) ---------------------

    @Test
    fun `survives being restored and removed repeatedly`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("obj")["k"] = 1 }.await()

        repeat(3) {
            document.updateAsync { root, _ -> root.remove("obj") }.await()
            document.history.undoAsync().await()
        }
        document.updateAsync { root, _ -> root.remove("obj") }.await()

        document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertJsonContentEquals("{}", document.toJson())
        assertEquals(0, document.garbageLength)
    }
}
