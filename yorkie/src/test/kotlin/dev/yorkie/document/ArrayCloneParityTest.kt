package dev.yorkie.document

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.YorkieException
import dev.yorkie.util.addDataSizes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins two clone-parity requirements (RTCOLLABPLATFORM-772) on the array-set
 * / move path: `RgaTreeList.set` must anchor on the target element's creation
 * time, exactly like the op every peer replays
 * (`ArraySetOperation.execute` -> `CrdtArray.insertAfter`), so the clone
 * [Document.getRoot] exposes does not diverge from the root after a set
 * targets a moved element; and a local array move must register its dead
 * position node on the clone via `context.registerGCPair`, or the clone's
 * `docSize`/`garbageLength` drifts from the root and a later GC reopens the
 * anchor divergence.
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

    @Test
    fun `a local move registers its dead position on the clone`() = runTest {
        val document = Document("")
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

        assertEquals(1, document.garbageLength)
        assertEquals(document.garbageLength, requireNotNull(document.clone).root.garbageLength)
        assertEquals(document.getDocSize(), requireNotNull(document.clone).root.docSize)
    }

    @Test
    fun `a local moveFront registers its dead position on the clone`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("arr").apply {
                put(0)
                put(1)
                put(2)
            }
        }.await()

        document.updateAsync { root, _ ->
            val arr = root.getAs<JsonArray>("arr")
            arr.moveFront(arr[2].id)
        }.await()

        assertEquals(1, document.garbageLength)
        assertEquals(document.garbageLength, requireNotNull(document.clone).root.garbageLength)
        assertEquals(document.getDocSize(), requireNotNull(document.clone).root.docSize)
    }

    /**
     * Determination (critic Medium, JS-shared parity): where a set on a moved
     * element lands depends on whether this replica has collected the move's
     * dead position node yet. `buildMovedArray` leaves `arr` at `[0,2,1]`
     * either way, but `arr[1] = 99` resolves index 1 against the clone's
     * CURRENT [RgaTreeList] node map at call time — before GC that map still
     * carries the moved element's dead original-slot node (`markDead`, not yet
     * purged), and after GC that node is physically gone
     * ([Document.garbageCollect] purges both `clone.root` and `root`). The
     * result is `{"arr":[0,1,99]}` in the not-yet-collected test above and
     * `{"arr":[0,99,1]}` here for the exact same input sequence, so two
     * replicas with different GC state can genuinely diverge on this. JS
     * `rga_tree_list.ts` has the same dependence, so this is parity, not a
     * regression; pinned here rather than treated as a bug (RTCOLLABPLATFORM-772,
     * not filed upstream yet).
     */
    @Test
    fun `set after a collected move keeps the clone in step with the root`() = runTest {
        val document = Document("")
        buildMovedArray(document)

        document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))

        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr")[1] = 99 }.await()

        assertJsonContentEquals("""{"arr":[0,99,1]}""", document.toJson())
        assertJsonContentEquals(
            """{"arr":[0,99,1]}""",
            requireNotNull(document.clone).root.toJson(),
        )
    }

    /**
     * A bare position node (no element) holds no content; its size was never
     * counted in `live` in the first place, so a move must book its dead
     * old-position node to `gc` with `gcOnlySize` and leave `live` exactly
     * as it was. Before this was wired (`gcOnlySize = deadNode.dataSize` at
     * the registration in `MoveOperation.execute`/`JsonArray.moveInternal`/
     * `JsonArray.moveAfterByIndex`), the generic registration path moved the
     * dead node's own size OUT of live and added an extra time-ticket charge
     * on top -- self-consistent against a from-scratch rebuild (which hit
     * the identical unfixed path in `CrdtRoot`'s own snapshot scan), so no
     * prior ledger-exactness check against a rebuild could see it; only an
     * absolute before/after comparison can. Mirrors JS SDK e0609c7a's
     * `move_operation.ts`/`array.ts` (server twin yorkie#2012, `6731bb6c`,
     * #1368 commit body: "array dead position nodes were debited from live,
     * on the move path and in the snapshot-load scan").
     */
    @Test
    fun `a local move leaves live untouched and books exactly the dead node's size to gc`() =
        runTest {
            val document = Document("")
            document.updateAsync { root, _ ->
                root.setNewArray("arr").apply {
                    put(0)
                    put(1)
                }
            }.await()

            val before = document.getDocSize()
            document.updateAsync { root, _ ->
                root.getAs<JsonArray>("arr").moveAfterByIndex(0, 1)
            }.await()

            val array = document.getRootObject()["arr"] as CrdtArray
            val deadNode = array.getAllRGANodes().single { it.elementEntry == null }
            val after = document.getDocSize()

            assertEquals(
                before.live,
                after.live,
                "a bare position node holds no element; its size must never move through live",
            )
            assertEquals(addDataSizes(before.gc, deadNode.dataSize), after.gc)
        }
}
