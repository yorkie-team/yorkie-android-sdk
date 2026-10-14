package dev.yorkie.api

import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.document.time.ActorID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val ACTOR_A = "0000000000000000000000a1"

/**
 * Port of yorkie-js-sdk's `reissue_test.ts` (`ed25355e`, #1449, yorkie#2111).
 * [Document.pendingChanges] stands in for JS's `(doc as any).localChanges`
 * access -- both reach the same queued-but-unsynced [Operation] list.
 */
class ReissueTest {

    private suspend fun opsOf(document: Document): List<Operation> =
        document.pendingChanges().flatMap { it.operations }

    @Test
    fun `re-issues the tickets nested in Object and Array values`() = runTest {
        val document = Document("d")
        document.updateAsync { root, _ ->
            root.setNewObject("obj").apply {
                this["k"] = "v"
                setNewArray("arr").apply {
                    put(1)
                    putNewObject().apply { this["n"] = 2 }
                }
            }
        }.await()
        document.updateAsync { root, _ -> root.remove("obj") }.await()

        assertTrue(document.history.canUndo())
        document.history.undoAsync().await()

        val restoreOp = opsOf(document).last() as SetOperation
        val restoredObj = restoreOp.value as CrdtObject
        assertEquals(ActorID.INITIAL_ACTOR_ID, restoredObj.createdAt.actorID)

        val reissued = reissueOperations(listOf(restoreOp), ActorID.INITIAL_ACTOR_ID, ACTOR_A)
        val reissuedObj = (reissued.single() as SetOperation).value as CrdtObject

        // Top-level value, and every nested element's OWN createdAt, must be
        // re-issued -- an Object/Array Set value recurses (AC9 guard,
        // context §3.4a): the wire nests the full subtree's bytes.
        assertEquals(ACTOR_A, reissuedObj.createdAt.actorID)
        assertEquals("v", (reissuedObj["k"] as CrdtPrimitive).value)
        val arr = reissuedObj["arr"] as CrdtArray
        assertEquals(ACTOR_A, arr.createdAt.actorID)
        assertEquals(ACTOR_A, requireNotNull(arr[0]).createdAt.actorID)
        val nested = requireNotNull(arr[1]) as CrdtObject
        assertEquals(ACTOR_A, nested.createdAt.actorID)
        assertEquals(ACTOR_A, nested["n"].createdAt.actorID)
        assertEquals(2, (nested["n"] as CrdtPrimitive).value)

        // Content must be unchanged -- only identity moves.
        assertEquals(restoredObj.toJson(), reissuedObj.toJson())
    }

    @Test
    fun `does not mutate the given operations`() = runTest {
        val document = Document("d")
        document.updateAsync { root, _ ->
            root.setNewObject("obj").apply { this["k"] = "v" }
            root.setNewText("t").edit(0, 0, "hello")
        }.await()
        val ops = opsOf(document)
        val before = ops.map { it.toPBOperation().toByteArray().toList() }

        reissueOperations(ops, ActorID.INITIAL_ACTOR_ID, ACTOR_A)

        assertEquals(before, ops.map { it.toPBOperation().toByteArray().toList() })
    }

    @Test
    fun `keeps the content of a Text value`() = runTest {
        val document = Document("d")
        document.updateAsync { root, _ -> root.setNewText("t").edit(0, 0, "hello") }.await()
        document.updateAsync { root, _ -> root.remove("t") }.await()
        document.history.undoAsync().await()

        val restoreOp = opsOf(document).last()
        check(restoreOp is SetOperation) { "expected a SetOperation restoring the removed text" }
        val restoredText = restoreOp.value as CrdtText
        assertEquals("hello", restoredText.toString())

        val reissued = reissueOperations(listOf(restoreOp), ActorID.INITIAL_ACTOR_ID, ACTOR_A)
        val text = (reissued.single() as SetOperation).value as CrdtText

        assertEquals("hello", text.toString(), "content must survive the value rebuild")
        assertEquals(ACTOR_A, text.createdAt.actorID)
        assertTrue(
            text.rgaTreeSplit.all { it.id.createdAt.actorID != ActorID.INITIAL_ACTOR_ID },
            "every split node's own ticket must be re-issued too",
        )
    }

    @Test
    fun `leaves an attribute named like the actor alone`() = runTest {
        val document = Document("d")
        document.updateAsync { root, _ ->
            root.setNewTree("tree", element("doc") { element("p") { text { "ab" } } })
        }.await()
        document.updateAsync { root, _ ->
            (root["tree"] as JsonTree).style(0, 1, mapOf(ActorID.INITIAL_ACTOR_ID to "v"))
        }.await()

        val style = opsOf(document).last()
        check(style is TreeStyleOperation) { "expected a TreeStyleOperation" }

        val reissued = reissueOperations(listOf(style), ActorID.INITIAL_ACTOR_ID, ACTOR_A)
        val attrs = (reissued.single() as TreeStyleOperation).attributes

        // The attribute's KEY happens to look exactly like the `from` actor
        // string, but a map key is never actor-addressed -- only a
        // TimeTicket's own actor field is.
        assertEquals(mapOf(ActorID.INITIAL_ACTOR_ID to "v"), attrs)
    }

    @Test
    fun `rejects a to-actor with no 24-hex byte form`() = runTest {
        val document = Document("d")
        document.updateAsync { root, _ -> root.setNewObject("obj") }.await()
        val ops = opsOf(document)

        assertFailsWith<IllegalArgumentException> {
            reissueOperations(ops, ActorID.INITIAL_ACTOR_ID, "not-a-hex-actor")
        }
    }

    // Guard for the ArraySet shape too -- an undo of an array element set
    // (not just a plain remove) also carries a full nested value.
    @Test
    fun `re-issues the tickets of an ArraySet reverse op`() = runTest {
        val document = Document("d")
        document.updateAsync { root, _ ->
            root.setNewArray("arr").apply {
                putNewObject().apply { this["n"] = 1 }
            }
        }.await()
        document.updateAsync { root, _ ->
            (root["arr"] as dev.yorkie.document.json.JsonArray).setNewObject(0).apply {
                this["n"] = 2
            }
        }.await()
        document.history.undoAsync().await()

        val restoreOp = opsOf(document).last()
        check(restoreOp is ArraySetOperation) { "expected an ArraySetOperation restoring the slot" }
        val restored = restoreOp.value as CrdtObject
        assertEquals(ActorID.INITIAL_ACTOR_ID, restored.createdAt.actorID)

        val reissued = reissueOperations(listOf(restoreOp), ActorID.INITIAL_ACTOR_ID, ACTOR_A)
        val reissuedValue = (reissued.single() as ArraySetOperation).value as CrdtObject

        assertEquals(ACTOR_A, reissuedValue.createdAt.actorID)
        assertEquals(ACTOR_A, reissuedValue["n"].createdAt.actorID)
        assertEquals(1, (reissuedValue["n"] as CrdtPrimitive).value)
    }
}
