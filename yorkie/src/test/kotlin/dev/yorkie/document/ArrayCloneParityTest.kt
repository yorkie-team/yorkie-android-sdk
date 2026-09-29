package dev.yorkie.document

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSync
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins defects (b) and (c) (RTCOLLABPLATFORM-772) on the array-set / move
 * path: (b) `RgaTreeList.set` must anchor on the target element's creation
 * time, exactly like the op every peer replays
 * (`ArraySetOperation.execute` -> `CrdtArray.insertAfter`), so the clone
 * [Document.getRoot] exposes does not diverge from the root after a set
 * targets a moved element; (c) a local array move must register its dead
 * position node on the clone via `context.registerGCPair`, or the clone's
 * `docSize`/`garbageLength` drifts from the root and a later GC reopens (b).
 */
class ArrayCloneParityTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private suspend fun buildMovedArray(document: Document) {
        document.updateAsync { root, _ ->
            root.setNewArray("arr").apply {
                put(0)
                put(1)
                put(2)
            }
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonArray>("arr").moveAfterByIndex(0, 2)
        }.await()
    }

    @Test
    fun `set after a move keeps the clone in step with the root`() = runTest {
        val document = Document("")
        buildMovedArray(document)

        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[1] = 99 }.await()
        assertJsonContentEquals("""{"arr":[0,1,99]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[0,1,99]}""",
            requireNotNull(document.clone).root.toJson(),
        )

        // A second, independent run with a different replacement value pins the
        // same fix, not a state left behind by the first run.
        val second = Document("")
        buildMovedArray(second)
        second.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[1] = 77 }.await()
        assertJsonContentEquals("""{"arr":[0,1,77]}""", second.toJson())
        assertJsonContentEquals(
            """{"arr":[0,1,77]}""",
            requireNotNull(second.clone).root.toJson(),
        )
    }

    @Test
    fun `set after a move matches the peer view`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        buildMovedArray(d1)
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[1] = 99 }.await()
        crossSync(d1, d2)

        val d1CloneJson = d1.getRoot().toJson()
        assertJsonContentEquals("""{"arr":[0,1,99]}""", d1CloneJson)
        assertJsonContentEquals(d1CloneJson, d2.toJson())
        assertJsonContentEquals(d1CloneJson, d1.toJson())
    }

    @Test
    fun `set with an unknown createdAt throws ErrInvalidArgument`() {
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", TimeTicket(1, TimeTicket.INITIAL_DELIMITER, actor1))
        list.insert(a)

        val unknown = TimeTicket(99, TimeTicket.INITIAL_DELIMITER, actor1)
        val thrown = assertFailsWith<YorkieException> {
            list.set(
                unknown,
                CrdtPrimitive("X", TimeTicket(100, 0u, actor1)),
                TimeTicket(100, 0u, actor1),
            )
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, thrown.code)
    }
}
