package dev.yorkie.api

import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.json.JsonArray
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the container-value wire encoder (RTCOLLABPLATFORM-772, JS
 * `converter.ts:293-307` / Go `to_pb.go:577-598` @ v0.7.21):
 * [dev.yorkie.api.ElementConverter]'s `toPBJsonElementSimple` must send a
 * [CrdtObject]/[CrdtArray] value's members, not just its `type`. Before the
 * fix, every op whose value is a container (the reverse of a removed object,
 * the reverse of a container-valued array set, an `AddOperation` restoring a
 * container) lost its members on the wire — the peer, and the server's
 * stored document, both received an empty container.
 */
class NestedValueConverterTest {

    @Test
    fun `a non-empty object value round trips through Set, Add and ArraySet operations`() =
        runTest {
            val document = Document("")
            document.updateAsync { root, _ ->
                root.setNewObject("nested").apply {
                    this["a"] = 1
                    setNewObject("o").apply {
                        setNewArray("b").apply {
                            put(1)
                            putNewObject().apply { this["c"] = 2 }
                        }
                    }
                    // A member that gets tombstoned — its identity must survive
                    // the round trip too, since the wire form of a container
                    // carries every member, live or removed (needed for GC).
                    setNewObject("gone")["z"] = 9
                }
            }.await()
            document.updateAsync { root, _ ->
                root.getAs<JsonObject>("nested").remove("gone")
            }.await()

            val original = document.getRoot().target["nested"] as CrdtObject
            assertEquals("""{"a":1,"o":{"b":[1,{"c":2}]}}""", original.toJson())

            val setOp = SetOperation("k", original, InitialTimeTicket, InitialTimeTicket)
            val addOp =
                AddOperation(InitialTimeTicket, original, InitialTimeTicket, InitialTimeTicket)
            val arraySetOp = ArraySetOperation(
                InitialTimeTicket,
                original,
                InitialTimeTicket,
                InitialTimeTicket,
            )

            val decodedSet = listOf(setOp.toPBOperation()).toOperations().single() as SetOperation
            val decodedAdd = listOf(addOp.toPBOperation()).toOperations().single() as AddOperation
            val decodedArraySet =
                listOf(arraySetOp.toPBOperation()).toOperations().single() as ArraySetOperation

            listOf(decodedSet.value, decodedAdd.value, decodedArraySet.value).forEach { decoded ->
                assertEquals(original.toJson(), decoded.toJson())
                // Byte-identical wire form implies every descendant createdAt/
                // removedAt round-tripped, including the tombstoned "gone".
                assertEquals(original.toByteString(), decoded.toByteString())
            }
        }

    @Test
    fun `a non-empty array value round trips through Set, Add and ArraySet operations`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("nestedArr").apply {
                put(1)
                putNewObject().apply { this["x"] = 1 }
                putNewObject().apply { this["y"] = 2 }
            }
        }.await()
        // Tombstone one element too, same reasoning as the object case above.
        document.updateAsync { root, _ ->
            root.getAs<JsonArray>("nestedArr").removeAt(2)
        }.await()

        val original = document.getRoot().target["nestedArr"] as CrdtArray
        assertEquals("""[1,{"x":1}]""", original.toJson())

        val setOp = SetOperation("k", original, InitialTimeTicket, InitialTimeTicket)
        val addOp =
            AddOperation(InitialTimeTicket, original, InitialTimeTicket, InitialTimeTicket)
        val arraySetOp = ArraySetOperation(
            InitialTimeTicket,
            original,
            InitialTimeTicket,
            InitialTimeTicket,
        )

        val decodedSet = listOf(setOp.toPBOperation()).toOperations().single() as SetOperation
        val decodedAdd = listOf(addOp.toPBOperation()).toOperations().single() as AddOperation
        val decodedArraySet =
            listOf(arraySetOp.toPBOperation()).toOperations().single() as ArraySetOperation

        listOf(decodedSet.value, decodedAdd.value, decodedArraySet.value).forEach { decoded ->
            assertEquals(original.toJson(), decoded.toJson())
            assertEquals(original.toByteString(), decoded.toByteString())
        }
    }

    @Test
    fun `an empty object and an empty array round trip with their createdAt preserved`() {
        val objectTicket = TimeTicket(5, 0u, ActorID.INITIAL_ACTOR_ID)
        val emptyObject = CrdtObject(objectTicket, memberNodes = ElementRht())
        val arrayTicket = TimeTicket(6, 0u, ActorID.INITIAL_ACTOR_ID)
        val emptyArray = CrdtArray(arrayTicket)

        val decodedObject = listOf(
            SetOperation("k", emptyObject, InitialTimeTicket, InitialTimeTicket).toPBOperation(),
        ).toOperations().single() as SetOperation
        val decodedArray = listOf(
            SetOperation("k", emptyArray, InitialTimeTicket, InitialTimeTicket).toPBOperation(),
        ).toOperations().single() as SetOperation

        assertEquals(objectTicket, decodedObject.value.createdAt)
        assertEquals("{}", decodedObject.value.toJson())
        assertEquals(arrayTicket, decodedArray.value.createdAt)
        assertEquals("[]", decodedArray.value.toJson())
    }

    @Test
    fun `a legacy value-less container decodes to an empty container with its createdAt`() {
        val legacyTicket = TimeTicket(9, 0u, ActorID.INITIAL_ACTOR_ID)

        // No `value` set at all — the legacy wire shape for a container,
        // predating the member-sending encoder.
        val legacyObject = jSONElementSimple {
            createdAt = legacyTicket.toPBTimeTicket()
            type = PBValueType.VALUE_TYPE_JSON_OBJECT
        }
        val legacyArray = jSONElementSimple {
            createdAt = legacyTicket.toPBTimeTicket()
            type = PBValueType.VALUE_TYPE_JSON_ARRAY
        }

        val decodedObject = legacyObject.toCrdtElement()
        val decodedArray = legacyArray.toCrdtElement()

        assertEquals(legacyTicket, decodedObject.createdAt)
        assertEquals("{}", decodedObject.toJson())
        assertEquals(legacyTicket, decodedArray.createdAt)
        assertEquals("[]", decodedArray.toJson())
    }

    // A reverse op's value must not carry its OWN top-level removedAt on the
    // wire — Android builds it with deepCopy() AFTER the delete that produced
    // it, so the copy inherited the removal ticket; Go, JS and iOS decode the
    // field and keep it, storing the "restored" container as a tombstone.

    @Test
    fun `an undone object member removal has no top-level removedAt on the wire`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewObject("obj").apply {
                this["k"] = 1
                // Tombstoned before the container itself is removed — GC needs
                // this to survive; it was never the bug.
                setNewObject("gone")["z"] = 9
            }
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonObject>("obj").remove("gone")
        }.await()
        document.updateAsync { root, _ -> root.remove("obj") }.await()
        document.history.undoAsync().await()

        val undoChange = document.createChangePack().changes.last()
        val setOp = undoChange.operations.filterIsInstance<SetOperation>().single()
        val wire = PBJsonElement.parseFrom(setOp.toPBOperation().set.value.value)

        assertFalse(wire.jsonObject.hasRemovedAt())
        val goneNode = wire.jsonObject.nodesList.single { it.key == "gone" }
        assertTrue(goneNode.element.jsonObject.hasRemovedAt())

        val decodedSet = listOf(setOp.toPBOperation()).toOperations().single() as SetOperation
        assertEquals("""{"k":1}""", decodedSet.value.toJson())
    }

    @Test
    fun `an undone array item removal has no top-level removedAt on the wire`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("arr").putNewObject().apply { this["a"] = 1 }
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonArray>("arr").removeAt(0) }.await()
        document.history.undoAsync().await()

        val undoChange = document.createChangePack().changes.last()
        val addOp = undoChange.operations.filterIsInstance<AddOperation>().single()
        val wire = PBJsonElement.parseFrom(addOp.toPBOperation().add.value.value)

        // The pre-existing RemoveOperation array-item decomposition already sends
        // the members as sibling SetOperations (nodesCount == 0 here); only the
        // wrapper's own top-level removedAt was the wire defect.
        assertFalse(wire.jsonObject.hasRemovedAt())
        assertEquals(0, wire.jsonObject.nodesCount)
        assertEquals("""[{"a":1}]""", document.getRoot().getAs<JsonArray>("arr").toJson())
    }

    @Test
    fun `an undone container-valued array set has no top-level removedAt on the wire`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("arr").putNewObject().apply { this["a"] = 1 }
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonArray>("arr").setNewObject(0)["b"] = 2
        }.await()
        document.history.undoAsync().await()

        val undoChange = document.createChangePack().changes.last()
        val arraySetOp =
            undoChange.operations.filterIsInstance<ArraySetOperation>().single()
        val wire = PBJsonElement.parseFrom(arraySetOp.toPBOperation().arraySet.value.value)

        assertFalse(wire.jsonObject.hasRemovedAt())
        val decodedArraySet =
            listOf(arraySetOp.toPBOperation()).toOperations().single() as ArraySetOperation
        assertEquals("""{"a":1}""", decodedArraySet.value.toJson())
    }

    @Test
    fun `an undone top-level tree removal has no top-level removedAt on the wire`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { element("p") { text { "hello" } } })
        }.await()
        val originalTreeJson = (document.getRoot().target["t"] as CrdtTree).toJson()

        document.updateAsync { root, _ -> root.remove("t") }.await()
        document.history.undoAsync().await()

        val undoChange = document.createChangePack().changes.last()
        val setOp = undoChange.operations.filterIsInstance<SetOperation>().single()
        val wire = PBJsonElement.parseFrom(setOp.toPBOperation().set.value.value)

        // Same root cause and same fix as the object/array cases, pre-existing
        // for the top-level Tree restore.
        assertTrue(wire.hasTree())
        assertFalse(wire.tree.hasRemovedAt())
        val decodedSet = listOf(setOp.toPBOperation()).toOperations().single() as SetOperation
        assertEquals(originalTreeJson, decodedSet.value.toJson())
    }

    // The cases above all exercise the CrdtObject branch of the strip
    // (ElementConverter.kt); the CrdtArray branch (`is CrdtArray ->
    // element.copy(removedAt = null)...`) had no pin of its own.

    @Test
    fun `an undone list-valued key removal has no top-level removedAt on the wire`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("listKey").apply {
                put(1)
                put(2)
            }
        }.await()
        document.updateAsync { root, _ -> root.remove("listKey") }.await()
        document.history.undoAsync().await()

        val undoChange = document.createChangePack().changes.last()
        val setOp = undoChange.operations.filterIsInstance<SetOperation>().single()
        val wire = PBJsonElement.parseFrom(setOp.toPBOperation().set.value.value)

        assertFalse(wire.jsonArray.hasRemovedAt())
        val decodedSet = listOf(setOp.toPBOperation()).toOperations().single() as SetOperation
        assertEquals("""[1,2]""", decodedSet.value.toJson())
    }

    @Test
    fun `an undone array-in-array item removal has no top-level removedAt on the wire`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewArray("outer").putNewArray().apply { put(1) }
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonArray>("outer").removeAt(0) }.await()
        document.history.undoAsync().await()

        val undoChange = document.createChangePack().changes.last()
        val addOp = undoChange.operations.filterIsInstance<AddOperation>().single()
        val wire = PBJsonElement.parseFrom(addOp.toPBOperation().add.value.value)

        assertFalse(wire.jsonArray.hasRemovedAt())
        val decodedAdd = listOf(addOp.toPBOperation()).toOperations().single() as AddOperation
        assertEquals("""[1]""", decodedAdd.value.toJson())
    }

    // The reverse of an ordinary key OVERWRITE (SetOperation.execute's own
    // `previousValue.deepCopy()`, built after `parentObject.set(...)` already
    // tombstoned `previousValue` in place) hits the same encoder gap as an
    // undone removal, but through a different producer. Unpinned before this
    // test.

    @Test
    fun `an overwritten object key's reverse has no top-level removedAt on the wire`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ -> root.setNewObject("a")["x"] = 1 }.await()
        document.updateAsync { root, _ -> root.setNewObject("a") }.await()
        document.history.undoAsync().await()

        val undoChange = document.createChangePack().changes.last()
        val setOp = undoChange.operations.filterIsInstance<SetOperation>().single()
        val wire = PBJsonElement.parseFrom(setOp.toPBOperation().set.value.value)

        assertFalse(wire.jsonObject.hasRemovedAt())
        val decodedSet = listOf(setOp.toPBOperation()).toOperations().single() as SetOperation
        assertEquals("""{"x":1}""", decodedSet.value.toJson())
    }

    // The strip must be a pure read of the op's value — repeated encoding is
    // byte-stable, and the value's own removedAt/JSON/data size, which GC and
    // other readers still consult, are left exactly as they were.

    @Test
    fun `encoding a removed value twice is idempotent and leaves it unmutated`() = runTest {
        val document = Document("")
        document.updateAsync { root, _ ->
            root.setNewObject("obj").apply { this["k"] = 1 }
        }.await()
        document.updateAsync { root, _ -> root.remove("obj") }.await()
        document.history.undoAsync().await()

        val undoChange = document.createChangePack().changes.last()
        val setOp = undoChange.operations.filterIsInstance<SetOperation>().single()
        val value = setOp.value as CrdtObject
        assertTrue(value.isRemoved, "the op's own value must still carry its removal ticket")

        val jsonBefore = value.toJson()
        val dataSizeBefore = value.getDataSize()
        val removedAtBefore = value.removedAt

        val bytes1 = setOp.toPBOperation().set.value.value
        val bytes2 = setOp.toPBOperation().set.value.value

        assertEquals(bytes1, bytes2)
        assertEquals(removedAtBefore, value.removedAt)
        assertEquals(jsonBefore, value.toJson())
        assertEquals(dataSizeBefore, value.getDataSize())
    }
}
