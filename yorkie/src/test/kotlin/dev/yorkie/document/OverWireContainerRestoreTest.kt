package dev.yorkie.document

import dev.yorkie.assertJsonContentEquals
import dev.yorkie.document.json.JsonArray
import dev.yorkie.helper.crossSync
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the container-value wire encoder (RTCOLLABPLATFORM-772, JS
 * `converter.ts:293-307` / Go `to_pb.go:577-598` @ v0.7.21) at the
 * document-convergence level: an undo
 * that restores a container value must reach a peer intact over a REAL
 * protobuf round trip ([crossSync] with `overWire = true`), not just
 * in-memory. Before the fix, `ElementConverter.toPBJsonElementSimple` sent
 * only `type` for a [dev.yorkie.document.crdt.CrdtObject]/
 * [dev.yorkie.document.crdt.CrdtArray] value, so every case below arrived on
 * the peer as an empty container.
 */
class OverWireContainerRestoreTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    // An object member removal, undone -----------------------------------

    @Test
    fun `undo of an object member removal converges over the wire`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewObject("obj")["k"] = 1
            root["keep"] = 0
        }.await()
        crossSync(d1, d2, overWire = true)

        d1.updateAsync("remove obj") { root, _ -> root.remove("obj") }.await()
        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)

        assertJsonContentEquals("""{"keep":0,"obj":{"k":1}}""", d1.toJson())
        assertJsonContentEquals("""{"keep":0,"obj":{"k":1}}""", d2.toJson())
    }

    // A container-valued array set, undone (combines the reverse-target and --
    // encoder fixes)

    @Test
    fun `undo of a container-valued array set converges over the wire`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewArray("arr").putNewObject().apply { this["a"] = 1 }
        }.await()
        crossSync(d1, d2, overWire = true)

        d1.updateAsync { root, _ ->
            root.getAs<JsonArray>("arr").setNewObject(0)["b"] = 2
        }.await()
        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)

        assertJsonContentEquals("""{"arr":[{"a":1}]}""", d1.toJson())
        assertJsonContentEquals("""{"arr":[{"a":1}]}""", d2.toJson())
    }

    // A two-level object removal, undone ----------------------------------

    @Test
    fun `undo of a two-level object removal converges over the wire`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewObject("o").apply {
                setNewObject("p")["q"] = 1
                setNewArray("r").apply {
                    put(1)
                    put(2)
                }
            }
        }.await()
        crossSync(d1, d2, overWire = true)

        d1.updateAsync("remove o") { root, _ -> root.remove("o") }.await()
        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)

        assertJsonContentEquals("""{"o":{"p":{"q":1},"r":[1,2]}}""", d1.toJson())
        assertJsonContentEquals("""{"o":{"p":{"q":1},"r":[1,2]}}""", d2.toJson())
    }
}
