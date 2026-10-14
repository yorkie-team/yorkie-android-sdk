package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Ports JS `docsize_rebuild_drift_test.ts` (v0.7.24, `8fb5a71c`, #1392) in
 * full — both the RHT/removeStyle-related cases (the tombstone-value half,
 * [RgaTreeList]-unrelated) and the array-move cases below (the
 * `RgaTreeList.moveAfter`/`CrdtRoot.accMovedElement` half).
 *
 * The two single-replica attribute cases below cannot be witnessed by
 * [assertMatchesRebuild]: [dev.yorkie.document.crdt.CrdtTreeNode.deepCopy]/
 * `CrdtText`'s own node copy carries the tombstone's STORED value verbatim,
 * and the rebuild recomputes `getDataSize()` off that same (now-fixed,
 * key-only-when-removed) node — so a running size that also used the
 * now-fixed formula agrees with the rebuild regardless of whether the value
 * length used to matter. The observable claim is that a removed attribute's
 * value LENGTH stops mattering to `docSize`, so each of those two cases
 * styles with a long value and a short one and requires the same resulting
 * size (mirrors the JS test file's own comment on this exact limitation).
 */
class DocSizeRebuildDriftTest {

    private fun rebuilt(d: Document) = CrdtRoot(d.getRootObject().deepCopy())

    private fun assertMatchesRebuild(d: Document, msg: String) {
        val r = rebuilt(d)
        assertEquals(r.docSize.live, d.getDocSize().live, "$msg: live")
        assertEquals(r.docSize.gc, d.getDocSize().gc, "$msg: gc")
    }

    private suspend fun seededTree(): Document {
        val d = Document("test-doc")
        d.updateAsync { r, _ ->
            r.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "abcd" } }
                    element("p") { text { "efgh" } }
                },
            )
        }.await()
        return d
    }

    private suspend fun seededTreeReplicas(): Pair<Document, Document> {
        val d1 = Document("test-doc").apply { setActor(ACTOR_1) }
        val d2 = Document("test-doc").apply { setActor(ACTOR_2) }
        d1.updateAsync { r, _ ->
            r.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "abcd" } }
                    element("p") { text { "efgh" } }
                },
            )
        }.await()
        return d1 to d2
    }

    private suspend fun seededText(): Document {
        val d = Document("test-doc")
        d.updateAsync { r, _ -> r.setNewText("k").edit(0, 0, "abcdefghij") }.await()
        return d
    }

    @Test
    fun `charges nothing for the value of a removed tree attribute`() = runTest {
        suspend fun sizeAfterRemoving(value: String): DocSize {
            val d = seededTree()
            d.updateAsync { r, _ ->
                r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("bold" to value))
            }.await()
            d.updateAsync { r, _ ->
                r.getAs<JsonTree>("t").removeStyleByPath(listOf(0), listOf(1), listOf("bold"))
            }.await()
            return d.getDocSize()
        }

        assertEquals(
            sizeAfterRemoving("x".repeat(64)),
            sizeAfterRemoving("x"),
            "a removed attribute still charged its value",
        )
    }

    @Test
    fun `charges nothing for the value of a removed text attribute`() = runTest {
        suspend fun sizeAfterRemoving(value: String): DocSize {
            val d = seededText()
            d.updateAsync { r, _ -> r.getAs<JsonText>("k").style(0, 10, mapOf("bold" to value)) }
                .await()
            // CrdtText.removeStyle has no proxy method on JsonText; undoing
            // the style is the route the public API gives to it — same route
            // JS's own test uses.
            d.history.undoAsync().await()
            return d.getDocSize()
        }

        assertEquals(
            sizeAfterRemoving("x".repeat(64)),
            sizeAfterRemoving("x"),
            "a removed attribute still charged its value",
        )
    }

    @Test
    fun `charges nothing for the value dropped from a removed node`() = runTest {
        // The node holding the attribute is itself a tombstone, so the
        // container never counted the attribute into live: the dropped
        // value has to come out of the gc charge taken when the node was
        // removed.
        suspend fun sizeAfterRemoving(value: String): DocSize {
            val (d1, d2) = seededTreeReplicas()
            d1.updateAsync { r, _ ->
                r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("bold" to value))
            }.await()
            crossSync(d1, d2)

            d1.updateAsync { r, _ -> r.getAs<JsonTree>("t").editByPath(listOf(0), listOf(1)) }
                .await()
            d2.updateAsync { r, _ ->
                r.getAs<JsonTree>("t").removeStyleByPath(listOf(0), listOf(1), listOf("bold"))
            }.await()
            crossSync(d1, d2)
            return d2.getDocSize()
        }

        assertEquals(
            sizeAfterRemoving("x".repeat(64)),
            sizeAfterRemoving("x"),
            "a removed attribute on a tombstoned node still charged its value",
        )
    }

    @Test
    fun `agrees with a rebuild on a concurrent set and removeStyle`() = runTest {
        // A removed attribute holds no value, so the bytes it was charging
        // have to leave whichever side held them. In one order the
        // removeStyle sees the peer's value, in the other it sees its own.
        val (d1, d2) = seededTreeReplicas()
        d1.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("bold" to "true"))
        }.await()
        crossSync(d1, d2)
        assertMatchesRebuild(d1, "after the initial style")

        d1.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("bold" to "maybe"))
        }.await()
        d2.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").removeStyleByPath(listOf(0), listOf(1), listOf("bold"))
        }.await()
        crossSync(d1, d2)

        assertEquals(d1.toJson(), d2.toJson(), "replicas must converge first")
        assertMatchesRebuild(d1, "set applied before removeStyle")
        assertMatchesRebuild(d2, "removeStyle applied before set")
    }

    @Test
    fun `agrees with a rebuild when removeStyle lands on a removed node`() = runTest {
        // The node holding the attribute is itself a tombstone, so the
        // container never counted the attribute into live: the dropped
        // value comes out of the gc charge taken when the node was removed,
        // not out of live.
        val (d1, d2) = seededTreeReplicas()
        d1.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").styleByPath(listOf(0), listOf(1), mapOf("bold" to "true"))
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { r, _ -> r.getAs<JsonTree>("t").editByPath(listOf(0), listOf(1)) }.await()
        d2.updateAsync { r, _ ->
            r.getAs<JsonTree>("t").removeStyleByPath(listOf(0), listOf(1), listOf("bold"))
        }.await()
        crossSync(d1, d2)

        assertEquals(d1.toJson(), d2.toJson(), "replicas must converge first")
        assertMatchesRebuild(d1, "edit applied before removeStyle")
        assertMatchesRebuild(d2, "removeStyle applied before edit")
    }

    @Test
    fun `charges the movedAt ticket of a moved array element`() = runTest {
        val d = Document("test-doc")
        d.updateAsync { r, _ ->
            val arr = r.setNewArray("arr")
            arr.put("a")
            arr.put("b")
            arr.put("c")
        }.await()
        assertMatchesRebuild(d, "before any move")

        d.updateAsync { r, _ -> r.getAs<JsonArray>("arr").moveAfterByIndex(2, 0) }.await()
        assertEquals("""{"arr":["b","c","a"]}""", d.toJson())
        assertMatchesRebuild(d, "after one move")
    }

    @Test
    fun `charges the movedAt ticket exactly once across repeated moves`() = runTest {
        val d = Document("test-doc")
        d.updateAsync { r, _ ->
            val arr = r.setNewArray("arr")
            arr.put("a")
            arr.put("b")
            arr.put("c")
        }.await()

        // Move 'a' to the back, then keep moving that SAME element. A
        // re-move overwrites a ticket already charged; charging it again
        // would walk live up without bound on a list the user reorders
        // repeatedly.
        d.updateAsync { r, _ -> r.getAs<JsonArray>("arr").moveAfterByIndex(2, 0) }.await()
        assertEquals("""{"arr":["b","c","a"]}""", d.toJson())
        val afterFirst = d.getDocSize()

        d.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.moveFront(arr[2].id)
        }.await()
        d.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.moveLast(arr[0].id)
        }.await()
        assertEquals("""{"arr":["b","c","a"]}""", d.toJson())
        assertEquals(
            afterFirst.live,
            d.getDocSize().live,
            "a re-move must not charge the ticket again",
        )
        assertMatchesRebuild(d, "after three moves")
    }

    @Test
    fun `agrees with a rebuild on a concurrent move and remove`() = runTest {
        // The two replicas see the pair in opposite orders. In d2's order
        // the remove lands first, so the element is already a tombstone
        // when the move stamps its ticket -- live is not holding that
        // element at all, and the charge has to go to gc instead.
        val d1 = Document("test-doc").apply { setActor(ACTOR_1) }
        val d2 = Document("test-doc").apply { setActor(ACTOR_2) }

        d1.updateAsync { r, _ ->
            val arr = r.setNewArray("arr")
            arr.put("a")
            arr.put("b")
            arr.put("c")
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { r, _ -> r.getAs<JsonArray>("arr").moveAfterByIndex(2, 0) }.await()
        d2.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.remove(arr[0].id)
        }.await()
        crossSync(d1, d2)

        assertEquals(d1.toJson(), d2.toJson(), "replicas must converge first")
        assertMatchesRebuild(d1, "move applied before remove")
        assertMatchesRebuild(d2, "remove applied before move")
    }

    @Test
    fun `collects a move charged to gc without overshooting`() = runTest {
        // Same concurrent pair as above, then collected. When the remove
        // lands first the move's ticket is charged to gc, and
        // CrdtRoot.accMovedElement also tops up the element's sizeInGC
        // entry -- collection subtracts the element's CURRENT size, so
        // without the top-up it takes back one ticket more than was ever
        // put in and docSize.gc ends up short. Nothing but a collection can
        // witness that top-up.
        val d1 = Document("test-doc").apply { setActor(ACTOR_1) }
        val d2 = Document("test-doc").apply { setActor(ACTOR_2) }

        d1.updateAsync { r, _ ->
            val arr = r.setNewArray("arr")
            arr.put("a")
            arr.put("b")
            arr.put("c")
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { r, _ -> r.getAs<JsonArray>("arr").moveAfterByIndex(2, 0) }.await()
        d2.updateAsync { r, _ ->
            val arr = r.getAs<JsonArray>("arr")
            arr.remove(arr[0].id)
        }.await()
        crossSync(d1, d2)
        assertTrue(d2.getDocSize().gc.meta > 0, "the tombstone must be in gc")

        d1.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))
        d2.garbageCollect(maxVectorOf(listOf(ACTOR_1, ACTOR_2)))

        val empty = DataSize(data = 0, meta = 0)
        assertEquals(empty, d1.getDocSize().gc, "gc after collection on d1")
        assertEquals(empty, d2.getDocSize().gc, "gc after collection on d2")
        assertEquals(
            d1.getDocSize(),
            d2.getDocSize(),
            "the two delivery orders must collect to the same size",
        )
        assertMatchesRebuild(d1, "collected, move applied before remove")
        assertMatchesRebuild(d2, "collected, remove applied before move")
    }

    companion object {
        private const val ACTOR_1 = "000000000000000000000001"
        private const val ACTOR_2 = "000000000000000000000002"
    }
}
