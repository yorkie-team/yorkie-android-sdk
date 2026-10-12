package dev.yorkie.document

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.json.JsonArray
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the reverse of an [dev.yorkie.document.operation.ArraySetOperation]
 * (RTCOLLABPLATFORM-772, JS `28f4ad26`/#1059): it must target the INSTALLED
 * element (`value.createdAt`), not the element the set displaced. Before the
 * fix, undo restored the wrong element and left the displaced one live
 * (`[1,2]` instead of `[1]`), and redo was a no-op.
 *
 * Also pins the companion reconcile for a container-valued set: when the
 * installed value is re-issued under a new id during undo/redo, member ops
 * built in the same update (`setNewObject(i)["k"] = v`) name the value's OLD
 * id as their parent, and that id must be retargeted too, or redo drops every
 * member (`[{}]` instead of `[{"v":5}]`).
 *
 * The third case becomes load-bearing once the array-set port (commit 4)
 * makes the displaced element collectable: undoing after a GC then anchors
 * on a purged node, so a correct reverse target is required to avoid an
 * `ErrInvalidArgument` throw.
 */
class ArraySetUndoTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    @Test
    fun `undo of an array set restores the previous element`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()

        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 2 }.await()
        assertJsonContentEquals("""{"arr":[2]}""", document.toJson())

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
        assertJsonContentEquals("""{"arr":[1]}""", requireNotNull(document.clone).root.toJson())

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[2]}""", document.toJson())

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
    }

    @Test
    fun `undo of an array set converges on a peer`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
        crossSync(d1, d2)
        assertJsonContentEquals("""{"arr":[1]}""", d2.toJson())

        d1.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 2 }.await()
        d1.history.undoAsync().await()
        crossSync(d1, d2)
        assertJsonContentEquals("""{"arr":[1]}""", d1.toJson())
        assertJsonContentEquals("""{"arr":[1]}""", d2.toJson())

        d1.history.redoAsync().await()
        crossSync(d1, d2)
        assertJsonContentEquals("""{"arr":[2]}""", d1.toJson())
        assertJsonContentEquals("""{"arr":[2]}""", d2.toJson())
    }

    @Test
    fun `undo after collection does not throw`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 2 }.await()

        document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
    }

    @Test
    fun `undo-time reconcile follows a chain of sets on the same slot`() = runTest {
        // Update-time history reconcile (RTCOLLABPLATFORM-772, JS
        // `28f4ad26`/#1059, document.ts:855-864): a later set on a slot must
        // retarget any pending undo entry left by an earlier set on that same
        // slot, or the earlier entry's stale target leaves the displaced
        // element live after two undos. `[1]`, `arr[0]=2`, `arr[0]=3`, undo,
        // undo gave `[1,2]` instead of `[1]` before the update-time reconcile
        // (`Document.kt`).
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 2 }.await()
        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 3 }.await()
        assertJsonContentEquals("""{"arr":[3]}""", document.toJson())

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[2]}""", document.toJson())
        assertJsonContentEquals("""{"arr":[2]}""", requireNotNull(document.clone).root.toJson())

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
        assertJsonContentEquals("""{"arr":[1]}""", requireNotNull(document.clone).root.toJson())

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[2]}""", document.toJson())

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[3]}""", document.toJson())
    }

    @Test
    fun `undo-time reconcile handles two array sets on different slots in one update`() = runTest {
        // The update-time reconcile loop walks every local ArraySetOperation in
        // the change (`for (op in change.operations)`), not just the first.
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("arr").apply {
                put(1)
                put(2)
            }
        }.await()

        document.updateAsync { root, _ ->
            val arr = root.getAs<JsonArray>("arr")
            arr[0] = 10
            arr[1] = 20
        }.await()
        assertJsonContentEquals("""{"arr":[10,20]}""", document.toJson())

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1,2]}""", document.toJson())

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[10,20]}""", document.toJson())
    }

    @Test
    fun `a skipHistory array set is not retargeted by a pending local undo entry`() = runTest {
        // Document.updateAsync's documented skipHistory contract: a skipHistory
        // write is treated like a remote change for history purposes, so it must
        // not run the update-time reconcile loop. A pending undo entry whose
        // target that write overwrites keeps its stale target and undoes as a
        // no-op or replay, never as a clean revert of the skipHistory write.
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 2 }.await()

        document.updateAsync(skipHistory = true) { root, _ ->
            root.getAs<JsonArray>("arr")[0] = 3
        }.await()
        assertJsonContentEquals("""{"arr":[3]}""", document.toJson())

        val undoResult = document.history.undoAsync().await()
        assertTrue(undoResult.isSuccess, "undo must not throw: ${undoResult.exceptionOrNull()}")
        val afterUndo = document.toJson()
        assertTrue(
            afterUndo.contains("3"),
            "expected the un-retargeted skipHistory value to survive undo, got $afterUndo",
        )
    }

    @Test
    fun `redo of a container-valued array set with one member restores the member`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonArray>("arr").setNewObject(0)["v"] = 5
        }.await()
        assertJsonContentEquals("""{"arr":[{"v":5}]}""", document.toJson())

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
        assertJsonContentEquals("""{"arr":[1]}""", requireNotNull(document.clone).root.toJson())

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[{"v":5}]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[{"v":5}]}""",
            requireNotNull(document.clone).root.toJson(),
        )

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
        assertJsonContentEquals("""{"arr":[1]}""", requireNotNull(document.clone).root.toJson())

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[{"v":5}]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[{"v":5}]}""",
            requireNotNull(document.clone).root.toJson(),
        )
    }

    @Test
    fun `redo of a container-valued array set with two members restores both members`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonArray>("arr").setNewObject(0).apply {
                this["a"] = 1
                this["b"] = 2
            }
        }.await()
        assertJsonContentEquals("""{"arr":[{"a":1,"b":2}]}""", document.toJson())

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[1]}""",
            requireNotNull(document.clone).root.toJson(),
        )

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[{"a":1,"b":2}]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[{"a":1,"b":2}]}""",
            requireNotNull(document.clone).root.toJson(),
        )

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[1]}""",
            requireNotNull(document.clone).root.toJson(),
        )

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[{"a":1,"b":2}]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[{"a":1,"b":2}]}""",
            requireNotNull(document.clone).root.toJson(),
        )
    }

    @Test
    fun `redo of an array-valued array set restores its items`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonArray>("arr").setNewArray(0).apply {
                put(7)
                put(8)
            }
        }.await()
        assertJsonContentEquals("""{"arr":[[7,8]]}""", document.toJson())

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
        assertJsonContentEquals("""{"arr":[1]}""", requireNotNull(document.clone).root.toJson())

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[[7,8]]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[[7,8]]}""",
            requireNotNull(document.clone).root.toJson(),
        )

        document.history.undoAsync().await()
        assertJsonContentEquals("""{"arr":[1]}""", document.toJson())
        assertJsonContentEquals("""{"arr":[1]}""", requireNotNull(document.clone).root.toJson())

        document.history.redoAsync().await()
        assertJsonContentEquals("""{"arr":[[7,8]]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[[7,8]]}""",
            requireNotNull(document.clone).root.toJson(),
        )
    }

    @Test
    fun `undo and redo of a container-valued array set converge on a peer over the wire`() =
        runTest {
            val d1 = Document("test-doc")
            val d2 = Document("test-doc")
            d1.setActor(actor1)
            d2.setActor(actor2)

            d1.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
            crossSync(d1, d2, overWire = true)

            d1.updateAsync { root, _ ->
                root.getAs<JsonArray>("arr").setNewObject(0)["v"] = 5
            }.await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[{"v":5}]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[{"v":5}]}""", d2.toJson())

            d1.history.undoAsync().await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[1]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[1]}""", d2.toJson())

            d1.history.redoAsync().await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[{"v":5}]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[{"v":5}]}""", d2.toJson())

            d1.history.undoAsync().await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[1]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[1]}""", d2.toJson())

            d1.history.redoAsync().await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[{"v":5}]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[{"v":5}]}""", d2.toJson())
        }

    @Test
    fun `undo-time reconcile chain of sets on the same slot converges on a peer over the wire`() =
        runTest {
            val d1 = Document("test-doc")
            val d2 = Document("test-doc")
            d1.setActor(actor1)
            d2.setActor(actor2)

            d1.updateAsync { root, _ -> root.setNewArray("arr").put(1) }.await()
            crossSync(d1, d2, overWire = true)

            d1.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 2 }.await()
            crossSync(d1, d2, overWire = true)
            d1.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[0] = 3 }.await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[3]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[3]}""", d2.toJson())

            d1.history.undoAsync().await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[2]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[2]}""", d2.toJson())

            d1.history.undoAsync().await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[1]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[1]}""", d2.toJson())

            d1.history.redoAsync().await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[2]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[2]}""", d2.toJson())

            d1.history.redoAsync().await()
            crossSync(d1, d2, overWire = true)
            assertJsonContentEquals("""{"arr":[3]}""", d1.toJson())
            assertJsonContentEquals("""{"arr":[3]}""", d2.toJson())
        }
}
