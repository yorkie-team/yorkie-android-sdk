package dev.yorkie.document

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonText
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
import dev.yorkie.util.addDataSizes
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `document_size_test.ts`'s container-GC cases (JS SDK `611e6e43`,
 * yorkie-js-sdk#1322, mirror of server yorkie#1934) plus both iOS-only
 * single-client cases from the merged yorkie-ios-sdk PR #271 squash
 * (`fd15fa3cf6`) as JVM unit tests (AC1-AC3, AC5, AC6, AC10, AC11).
 *
 * Kept as a sibling suite to `DocumentSizeTest` (already 750+ lines) rather
 * than appended to it, mirroring iOS `DocumentSizeContainerGCTests.swift`.
 * Two-replica cases use the in-process [crossSync] helper with actors
 * [actor1]/[actor2]; collection uses [maxVectorOf]; undo uses
 * `document.history.undoAsync()`.
 */
class DocumentSizeContainerGcTest {
    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private val emptyLive = DataSize(data = 0, meta = 24)
    private val emptyDocSize = DocSize(live = emptyLive, gc = DataSize(0, 0))

    // T1: removing a non-empty container -------------------------------

    @Test
    fun `removing a non-empty container - object`() = runTest {
        val document = Document("")
        val built = DataSize(data = 2, meta = 120)

        document.updateAsync { root, _ ->
            root.setNewObject("k")["a"] = "1"
        }.await()
        assertEquals(built, document.getDocSize().live)
        assertEquals(DataSize(0, 0), document.getDocSize().gc)

        document.updateAsync { root, _ -> root.remove("k") }.await()
        assertEquals(emptyLive, document.getDocSize().live)
        assertEquals(built, document.getDocSize().gc)

        assertEquals(
            document.garbageLength,
            document.garbageCollect(maxVectorOf(listOf(document.changeID.actor))),
        )
        assertEquals(emptyDocSize, document.getDocSize())
    }

    @Test
    fun `removing a non-empty container - array`() = runTest {
        val document = Document("")
        val built = DataSize(data = 2, meta = 96)

        document.updateAsync { root, _ ->
            root.setNewArray("k").put("a")
        }.await()
        assertEquals(built, document.getDocSize().live)
        assertEquals(DataSize(0, 0), document.getDocSize().gc)

        document.updateAsync { root, _ -> root.remove("k") }.await()
        assertEquals(emptyLive, document.getDocSize().live)
        assertEquals(built, document.getDocSize().gc)

        assertEquals(
            document.garbageLength,
            document.garbageCollect(maxVectorOf(listOf(document.changeID.actor))),
        )
        assertEquals(emptyDocSize, document.getDocSize())
    }

    @Test
    fun `removing a non-empty container - nested object`() = runTest {
        val document = Document("")
        val built = DataSize(data = 2, meta = 168)

        document.updateAsync { root, _ ->
            root.setNewObject("k").setNewObject("inner")["a"] = "1"
        }.await()
        assertEquals(built, document.getDocSize().live)
        assertEquals(DataSize(0, 0), document.getDocSize().gc)

        document.updateAsync { root, _ -> root.remove("k") }.await()
        assertEquals(emptyLive, document.getDocSize().live)
        assertEquals(built, document.getDocSize().gc)

        assertEquals(
            document.garbageLength,
            document.garbageCollect(maxVectorOf(listOf(document.changeID.actor))),
        )
        assertEquals(emptyDocSize, document.getDocSize())
    }

    // T2: removing a container holding an earlier tombstone -------------

    @Test
    fun `removing a container holding an earlier tombstone`() = runTest {
        val document = Document("")

        document.updateAsync { root, _ ->
            root.setNewObject("k").setNewObject("inner")["a"] = "1"
        }.await()

        document.updateAsync { root, _ ->
            root.getAs<JsonObject>("k").remove("inner")
        }.await()
        val gcAfterInner = document.getDocSize().gc

        document.updateAsync { root, _ -> root.remove("k") }.await()
        assertEquals(emptyLive, document.getDocSize().live)
        assertEquals(gcAfterInner.data, document.getDocSize().gc.data)

        assertEquals(
            document.garbageLength,
            document.garbageCollect(maxVectorOf(listOf(document.changeID.actor))),
        )
        assertEquals(emptyDocSize, document.getDocSize())
    }

    // T3: concurrently removing the same container -----------------------

    @Test
    fun `concurrently removing the same container`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewObject("k")["a"] = "1" }.await()
        crossSync(d1, d2)
        // JS/iOS reference asserts the replicas already agree before the
        // concurrent removes, so a later mismatch is attributable to them.
        assertEquals(d2.getDocSize(), d1.getDocSize())

        d1.updateAsync { root, _ -> root.remove("k") }.await()
        d2.updateAsync { root, _ -> root.remove("k") }.await()
        crossSync(d1, d2)

        assertJsonContentEquals("{}", d1.toJson())
        assertJsonContentEquals("{}", d2.toJson())
        assertEquals(d2.getDocSize(), d1.getDocSize())

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(emptyDocSize, d1.getDocSize())
        assertEquals(emptyDocSize, d2.getDocSize())
    }

    // T4: removing a member inside an already removed container ----------

    @Test
    fun `removing a member inside an already removed container`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewObject("k").apply {
                this["a"] = "1"
                this["b"] = "2"
            }
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.remove("k") }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonObject>("k").remove("a") }.await()
        crossSync(d1, d2)

        assertEquals(d2.getDocSize(), d1.getDocSize())

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(emptyDocSize, d1.getDocSize())
        assertEquals(emptyDocSize, d2.getDocSize())
    }

    // T5: restoring a container over a diverged tombstone -----------------

    /**
     * RTCOLLABPLATFORM-767 (a tie between a peer's write and an undo restore
     * leaking the peer's member) is iOS-only: iOS `ElementRHT.set` compares
     * `createdAt` vs `createdAt` and can tie on an undo restore, while
     * Android `ElementRht.set` compares `getPositionedAt() < executedAt`
     * (`ElementRht.kt:31`), so the tie cannot occur here. This case runs
     * un-quarantined on Android, and both replicas are asserted: d2 applies
     * the undo as `Remote` over a tombstone holding the member it wrote,
     * which the restored copy does not carry.
     */
    @Test
    fun `restoring a container over a diverged tombstone`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewObject("k")["a"] = "1" }.await()
        crossSync(d1, d2)
        val built = d1.getDocSize()

        d1.updateAsync { root, _ -> root.remove("k") }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonObject>("k")["b"] = "2" }.await()
        crossSync(d1, d2)

        d1.history.undoAsync().await()
        assertJsonContentEquals("""{"k":{"a":"1"}}""", d1.toJson())
        assertEquals(built, d1.getDocSize())
        crossSync(d1, d2)
        assertJsonContentEquals("""{"k":{"a":"1"}}""", d2.toJson())

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)
        assertEquals(built, d1.getDocSize())
        assertEquals(d1.getDocSize(), d2.getDocSize())
    }

    // T6: undoing the removal of an array container (Android reverse shape) --

    /**
     * Pins the Divergence-2 identity guard in
     * [dev.yorkie.document.crdt.CrdtRoot.deregisterElement]: Android's
     * array-remove undo reverses as `Add` + one child `Set` per member
     * (`RemoveOperation.childSetOps`), so the restored member is
     * re-registered under its original createdAt inside a NEW container
     * instance while the OLD tombstoned container still owns the same
     * descendant createdAt in its own member table. A later GC purge of
     * that old container must not delete the live twin's registration.
     */
    @Test
    fun `undoing the removal of an array container`() = runTest {
        val document = Document("")
        val built = DataSize(data = 2, meta = 144)

        document.updateAsync { root, _ ->
            root.setNewArray("k").putNewObject().apply { this["a"] = "1" }
        }.await()
        assertEquals(built, document.getDocSize().live)

        // The array ITEM's own createdAt is reconciled to a fresh ticket by
        // AddOperation's undo (Document.executeUndoRedo); the identity guard
        // this case pins is about the MEMBER inside it, whose createdAt
        // survives unchanged across the whole undo (Divergence 2).
        val originalMemberCreatedAt = requireNotNull(
            (document.getRootObject()["k"] as CrdtArray)[0] as? CrdtObject,
        )["a"].createdAt

        document.updateAsync { root, _ -> root.getAs<JsonArray>("k").removeAt(0) }.await()
        document.history.undoAsync().await()
        assertJsonContentEquals("""{"k":[{"a":"1"}]}""", document.toJson())

        val collected = document.garbageCollect(
            maxVectorOf(listOf(document.changeID.actor)),
        )
        assertEquals(built, document.getDocSize().live)
        assertEquals(0, document.garbageLength)
        // The old tombstoned container wrapper is real garbage and IS
        // collected (count 1); only its descendant twin (the restored
        // member, checked below) is skipped by the stale-twin guard.
        assertEquals(1, collected)

        assertNotNull(
            requireNotNull(document.clone).root.findByCreatedAt(originalMemberCreatedAt),
            "the restored member must still resolve via findByCreatedAt after GC",
        )
    }

    // T7 (iOS-only 73f7efe934): undoing the removal of an object container --

    /**
     * Pins that the Divergence-1 deregister block EXISTS at all (RED on
     * Android pre-fix, which has no such block). Because the single-client
     * copy and the previously-registered tombstone share every createdAt
     * here, this case does NOT distinguish registered-vs-copy identity — T8
     * (`undoing the removal of a container holding a tombstone`) does.
     */
    @Test
    fun `undoing the removal of an object container`() = runTest {
        val document = Document("")
        val built = DataSize(data = 2, meta = 120)

        document.updateAsync { root, _ -> root.setNewObject("k")["a"] = "1" }.await()
        assertEquals(built, document.getDocSize().live)

        document.updateAsync { root, _ -> root.remove("k") }.await()
        document.history.undoAsync().await()
        assertJsonContentEquals("""{"k":{"a":"1"}}""", document.toJson())
        assertEquals(built, document.getDocSize().live)
        assertEquals(DataSize(0, 0), document.getDocSize().gc)

        document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertEquals(built, document.getDocSize().live)
        assertEquals(DataSize(0, 0), document.getDocSize().gc)
    }

    // T8 (yorkie-js-sdk#1349 item 1): undoing the removal of a container --
    // holding a tombstone

    /**
     * Pins that a tombstone nested inside a restored container stays
     * collectable (yorkie-js-sdk#1349 item 1, fixed upstream by #1350:
     * [dev.yorkie.document.crdt.CrdtRoot.registerElement] adopts every
     * already tombstoned element of the copy). A JS-literal port of the
     * v0.7.17 recursive deregister alone regresses this: it collects 0 and
     * settles at 4 live elements instead of a fresh-built `{k:{a:'1'}}`.
     */
    @Test
    fun `undoing the removal of a container holding a tombstone`() = runTest {
        val document = Document("")

        document.updateAsync { root, _ ->
            root.setNewObject("k").apply {
                this["a"] = "1"
                this["b"] = "2"
            }
        }.await()
        assertEquals(DataSize(4, 168), document.getDocSize().live)

        document.updateAsync { root, _ -> root.getAs<JsonObject>("k").remove("b") }.await()
        assertEquals(DataSize(2, 120), document.getDocSize().live)
        assertEquals(DataSize(2, 72), document.getDocSize().gc)

        document.updateAsync { root, _ -> root.remove("k") }.await()
        assertEquals(DataSize(0, 24), document.getDocSize().live)
        assertEquals(DataSize(4, 192), document.getDocSize().gc)

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"k":{"a":"1"}}""", document.toJson())
        assertEquals(DataSize(2, 120), document.getDocSize().live)
        assertEquals(DataSize(2, 72), document.getDocSize().gc)
        assertEquals(1, document.garbageLength)

        val collected = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertEquals(1, collected)
        assertEquals(DataSize(2, 120), document.getDocSize().live)
        assertEquals(DataSize(0, 0), document.getDocSize().gc)
        assertEquals(3, requireNotNull(document.clone).root.elementMapSize)
        assertEquals(0, document.garbageLength)
    }

    // T9 (yorkie-js-sdk#1349 item 2, fixed by #1341, two replicas) -------

    /**
     * The same undo reaching a peer as `Remote` retires the tombstone's
     * stale gc entry there too
     * ([dev.yorkie.document.crdt.CrdtRoot.unregisterRemovedElementPair] is
     * not gated on the op source), so both replicas count no garbage after
     * the undo and both end empty after a later removal.
     */
    @Test
    fun `remote undo retires the tombstone on the peer too`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewObject("k")["a"] = "1" }.await()
        crossSync(d1, d2)

        d2.updateAsync { root, _ -> root.remove("k") }.await()
        crossSync(d1, d2)

        d2.history.undoAsync().await()
        crossSync(d1, d2)

        assertJsonContentEquals("""{"k":{"a":"1"}}""", d1.toJson())
        assertJsonContentEquals("""{"k":{"a":"1"}}""", d2.toJson())
        assertEquals(0, d1.garbageLength)
        assertEquals(0, d2.garbageLength)
        assertEquals(d2.getDocSize(), d1.getDocSize())

        d1.updateAsync { root, _ -> root.remove("k") }.await()
        crossSync(d1, d2)

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(emptyDocSize, d2.getDocSize())
        assertEquals(emptyDocSize, d1.getDocSize())
    }

    // T10: remote undo of an array-member removal (Android reverse shape) --

    /**
     * Both replicas converge after the undo of an array-member removal
     * reaches the peer as `Remote`: the peer never runs `SetOperation`'s
     * `UndoRedo` deregister, so the old tombstoned member's gc charge must
     * be released when the restored member takes its createdAt, and must
     * not shadow the restored member's own later removal.
     */
    @Test
    fun `remote undo of an array member removal converges`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        val built = DataSize(data = 2, meta = 144)
        val vector = maxVectorOf(listOf(actor1, actor2))

        d1.updateAsync { root, _ ->
            root.setNewArray("k").putNewObject().apply { this["a"] = "1" }
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonArray>("k").removeAt(0) }.await()
        crossSync(d1, d2)

        d1.history.undoAsync().await()
        crossSync(d1, d2)
        assertJsonContentEquals("""{"k":[{"a":"1"}]}""", d2.toJson())

        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(DocSize(live = built, gc = DataSize(0, 0)), d1.getDocSize())
        assertEquals(d1.getDocSize(), d2.getDocSize())
        assertEquals(0, d2.garbageLength)

        d1.updateAsync { root, _ -> root.getAs<JsonArray>("k").removeAt(0) }.await()
        crossSync(d1, d2)
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(DocSize(live = DataSize(0, 72), gc = DataSize(0, 0)), d1.getDocSize())
        assertEquals(d1.getDocSize(), d2.getDocSize())
    }

    // T11: removing text inside a concurrently removed container --------

    /**
     * d2 removes text while d1 concurrently removes the enclosing
     * container. d1 swept the text's full size into gc before the removal
     * reached it, so the removed nodes are debited from live a second time
     * there; until GC the two replicas split live/gc differently (d1's live
     * is transiently negative) but their totals — what the size limit reads
     * — agree. Collection releases the text's drift from live
     * (Android-only, beyond JS main), so both end empty.
     */
    @Test
    fun `removing text inside a concurrently removed container converges after gc`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewObject("k").setNewText("t").edit(0, 0, "hello")
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.remove("k") }.await()
        d2.updateAsync { root, _ ->
            root.getAs<JsonObject>("k").getAs<JsonText>("t").edit(0, 5, "")
        }.await()
        crossSync(d1, d2)
        assertEquals(d1.getDocSize().total(), d2.getDocSize().total())

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(emptyDocSize, d1.getDocSize())
        assertEquals(emptyDocSize, d2.getDocSize())
    }

    // T12: remote undo over a member a peer grew on the tombstone -------

    /**
     * d2 writes `k.b` while d1 removes `k`; d1's undo restores a copy
     * without `b`. On d2 (applying the undo as `Remote`) the orphaned `b`
     * must be released, not left charged to gc. Three rounds, then every
     * key removed: both replicas stay equal throughout and end empty.
     */
    @Test
    fun `remote undo over a member a peer grew on the tombstone converges`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        val vector = maxVectorOf(listOf(actor1, actor2))

        repeat(3) { i ->
            d1.updateAsync { root, _ -> root.setNewObject("k$i")["a"] = "1" }.await()
            crossSync(d1, d2)
            d1.updateAsync { root, _ -> root.remove("k$i") }.await()
            d2.updateAsync { root, _ -> root.getAs<JsonObject>("k$i")["b"] = "2" }.await()
            crossSync(d1, d2)
            d1.history.undoAsync().await()
            crossSync(d1, d2)
            d1.garbageCollect(vector)
            d2.garbageCollect(vector)
            assertEquals(d1.getDocSize(), d2.getDocSize())
            assertEquals(DataSize(0, 0), d2.getDocSize().gc)
            assertEquals(0, d2.garbageLength)
        }

        repeat(3) { i -> d1.updateAsync { root, _ -> root.remove("k$i") }.await() }
        crossSync(d1, d2)
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(emptyDocSize, d1.getDocSize())
        assertEquals(emptyDocSize, d2.getDocSize())
    }

    // T13: inserting text inside a concurrently removed container --------

    /**
     * d2 inserts into `k.t` while d1 removes `k`. d1 books the inserted
     * bytes into live after its sweep; collection releases that drift
     * (Android-only, beyond JS main), so both replicas end empty.
     */
    @Test
    fun `inserting text inside a concurrently removed container converges after gc`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewObject("k").setNewText("t").edit(0, 0, "hi")
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.remove("k") }.await()
        d2.updateAsync { root, _ ->
            root.getAs<JsonObject>("k").getAs<JsonText>("t").edit(2, 2, "world")
        }.await()
        crossSync(d1, d2)
        assertEquals(d1.getDocSize().total(), d2.getDocSize().total())

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(emptyDocSize, d1.getDocSize())
        assertEquals(emptyDocSize, d2.getDocSize())
    }

    // T14: undoing the removal of an array of arrays (single AddOperation) -

    /**
     * An array item that is not an object reverses as ONE
     * [dev.yorkie.document.operation.AddOperation] carrying the whole
     * subtree, tombstones included; registration adopts them, so the
     * replicas agree right after the undo and collection settles live at a
     * fresh build's.
     */
    @Test
    fun `undoing the removal of an array of arrays adopts its tombstones`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewArray("k").putNewArray().apply {
                put("a")
                put("b")
            }
        }.await()
        d1.updateAsync { root, _ -> (root.getAs<JsonArray>("k")[0] as JsonArray).removeAt(1) }
            .await()
        crossSync(d1, d2)
        val builtLive = d1.getDocSize().live

        d1.updateAsync { root, _ -> root.getAs<JsonArray>("k").removeAt(0) }.await()
        crossSync(d1, d2)
        d1.history.undoAsync().await()
        crossSync(d1, d2)
        assertJsonContentEquals("""{"k":[["a"]]}""", d2.toJson())
        assertEquals(builtLive, d1.getDocSize().live)
        assertEquals(d1.getDocSize(), d2.getDocSize())

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(DocSize(live = builtLive, gc = DataSize(0, 0)), d1.getDocSize())
        assertEquals(d1.getDocSize(), d2.getDocSize())
        assertEquals(0, d2.garbageLength)
    }

    // T15 (upstream parity pin): application order of remove vs. add -----

    /**
     * Pins JS-main behaviour, not a desired end state: a member added into
     * an already-removed container is booked into live on the replica that
     * applied the removal first, and swept into gc on the other. The split
     * differs until GC; the totals the size limit reads agree throughout,
     * and collection makes both equal.
     */
    @Test
    fun `adding into a concurrently removed container splits live and gc until gc`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewObject("k")["a"] = "1" }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.remove("k") }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonObject>("k")["c"] = "3" }.await()
        crossSync(d1, d2)
        assertNotEquals(d1.getDocSize(), d2.getDocSize())
        assertEquals(d1.getDocSize().total(), d2.getDocSize().total())

        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.garbageCollect(vector)
        d2.garbageCollect(vector)
        assertEquals(emptyDocSize, d1.getDocSize())
        assertEquals(emptyDocSize, d2.getDocSize())
    }

    private fun DocSize.total() = addDataSizes(live, gc)
}
