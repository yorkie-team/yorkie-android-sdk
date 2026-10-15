package dev.yorkie.api

import com.google.protobuf.kotlin.toByteString
import dev.yorkie.api.v1.JSONElementKt.counter
import dev.yorkie.api.v1.JSONElementKt.jSONArray
import dev.yorkie.api.v1.JSONElementKt.jSONObject
import dev.yorkie.api.v1.JSONElementKt.primitive
import dev.yorkie.api.v1.JSONElementKt.text
import dev.yorkie.api.v1.JSONElementKt.tree
import dev.yorkie.api.v1.OperationKt.add
import dev.yorkie.api.v1.OperationKt.arraySet
import dev.yorkie.api.v1.OperationKt.set
import dev.yorkie.api.v1.RestoreMode
import dev.yorkie.api.v1.changePack
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.jSONElementSimple
import dev.yorkie.api.v1.operation
import dev.yorkie.api.v1.treeNode
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.ArraySetOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Pins (JS SDK `d3ffe946` #1406): the dormant proto
 * fields this commit's hunks add -- `restore_mode`
 * on `Set`/`Add`/`ArraySet`, and `revived_at` on every `JSONElement` body --
 * decode to the SAME domain object a payload without them produces, and
 * re-encoding a decoded domain object never writes any of them back ("the
 * new fields are dormant; no converter reads or writes them yet" -- JS
 * commit body). A forward-compat pin, not a red-then-green port: red by
 * construction -- nothing decodes these fields before this commit
 * adds them, so there is no pre-port behaviour to revert and re-trip; merely
 * compiling this file against the regenerated bindings is the proof the
 * fields exist with the Go-synced numbers and types.
 *
 * `ChangePack.capabilities` graduated out of dormancy in spec 040 (JS
 * `b50f8900` #1477): it is now decoded (`ChangeConverterTest` carries the
 * live decode-contract tests). The encode-never-writes half of its pin stays
 * here, since D5 keeps that true regardless of decode.
 */
class ProtoSyncTest {

    private val actor = "000000000000000000000001"
    private val ticket = TimeTicket(1, 0u, actor)
    private val dormantTicket = TimeTicket(9, 0u, actor)

    private val simpleValue = jSONElementSimple {
        createdAt = ticket.toPBTimeTicket()
        type = PBValueType.VALUE_TYPE_NULL
    }

    // --- ChangePack.capabilities (field 9) ---
    // Decode is live as of spec 040 (JS `b50f8900` #1477) -- the full
    // decode-contract tests (with/without the field, `hasCapability`) live in
    // `ChangeConverterTest`. Only the encode-never-writes half is pinned here.

    private fun buildChangePack(withCapabilities: Boolean) = changePack {
        documentKey = "doc"
        if (withCapabilities) capabilities.addAll(listOf("restore-element"))
    }

    @Test
    fun `re-encoding a ChangePack never writes capabilities`() {
        val decoded = buildChangePack(withCapabilities = true).toChangePack()

        val reencoded = decoded.toPBChangePack()

        assertTrue(reencoded.capabilitiesList.isEmpty())
    }

    // --- Operation.Set/Add/ArraySet.restore_mode (field 5) ---

    private fun buildSetOperation(withRestoreMode: Boolean) = operation {
        set = set {
            parentCreatedAt = ticket.toPBTimeTicket()
            key = "k"
            value = simpleValue
            executedAt = ticket.toPBTimeTicket()
            if (withRestoreMode) restoreMode = RestoreMode.RESTORE_MODE_RESTORE
        }
    }

    @Test
    fun `a Set carrying restore_mode decodes to the same SetOperation as one without`() {
        val decoded = listOf(buildSetOperation(true)).toOperations().single()
        val decodedWithout = listOf(buildSetOperation(false)).toOperations().single()

        assertEquals(decodedWithout, decoded)
    }

    @Test
    fun `re-encoding a SetOperation never writes restore_mode`() {
        val decoded = listOf(buildSetOperation(true)).toOperations().single() as SetOperation

        val reencoded = decoded.toPBOperation()

        assertEquals(RestoreMode.RESTORE_MODE_UNSPECIFIED, reencoded.set.restoreMode)
    }

    private fun buildAddOperation(withRestoreMode: Boolean) = operation {
        add = add {
            parentCreatedAt = ticket.toPBTimeTicket()
            prevCreatedAt = ticket.toPBTimeTicket()
            value = simpleValue
            executedAt = ticket.toPBTimeTicket()
            if (withRestoreMode) restoreMode = RestoreMode.RESTORE_MODE_RESTORE
        }
    }

    @Test
    fun `an Add carrying restore_mode decodes to the same AddOperation as one without`() {
        val decoded = listOf(buildAddOperation(true)).toOperations().single()
        val decodedWithout = listOf(buildAddOperation(false)).toOperations().single()

        assertEquals(decodedWithout, decoded)
    }

    @Test
    fun `re-encoding an AddOperation never writes restore_mode`() {
        val decoded = listOf(buildAddOperation(true)).toOperations().single() as AddOperation

        val reencoded = decoded.toPBOperation()

        assertEquals(RestoreMode.RESTORE_MODE_UNSPECIFIED, reencoded.add.restoreMode)
    }

    private fun buildArraySetOperation(withRestoreMode: Boolean) = operation {
        arraySet = arraySet {
            parentCreatedAt = ticket.toPBTimeTicket()
            createdAt = ticket.toPBTimeTicket()
            value = simpleValue
            executedAt = ticket.toPBTimeTicket()
            if (withRestoreMode) restoreMode = RestoreMode.RESTORE_MODE_RESTORE
        }
    }

    @Test
    fun `an ArraySet carrying restore_mode decodes to the same ArraySetOperation as one without`() {
        val decoded = listOf(buildArraySetOperation(true)).toOperations().single()
        val decodedWithout = listOf(buildArraySetOperation(false)).toOperations().single()

        assertEquals(decodedWithout, decoded)
    }

    @Test
    fun `re-encoding an ArraySetOperation never writes restore_mode`() {
        val decoded =
            listOf(buildArraySetOperation(true)).toOperations().single() as ArraySetOperation

        val reencoded = decoded.toPBOperation()

        assertEquals(RestoreMode.RESTORE_MODE_UNSPECIFIED, reencoded.arraySet.restoreMode)
    }

    // --- JSONElement.*.revived_at (one per body, covering every JSONElement body) ---

    @Test
    fun `a JSONObject carrying revived_at decodes to the same CrdtObject as one without`() {
        fun build(withRevivedAt: Boolean) = jSONElement {
            jsonObject = jSONObject {
                createdAt = ticket.toPBTimeTicket()
                if (withRevivedAt) revivedAt = dormantTicket.toPBTimeTicket()
            }
        }

        val decoded = build(true).jsonObject.toCrdtObject()
        val decodedWithout = build(false).jsonObject.toCrdtObject()

        assertEquals(decodedWithout, decoded)
        assertFalse(decoded.toPBJsonObject().jsonObject.hasRevivedAt())
    }

    @Test
    fun `a JSONArray carrying revived_at decodes to the same CrdtArray as one without`() {
        fun build(withRevivedAt: Boolean) = jSONElement {
            jsonArray = jSONArray {
                createdAt = ticket.toPBTimeTicket()
                if (withRevivedAt) revivedAt = dormantTicket.toPBTimeTicket()
            }
        }

        val decoded = build(true).jsonArray.toCrdtArray()
        val decodedWithout = build(false).jsonArray.toCrdtArray()

        assertEquals(decodedWithout, decoded)
        assertFalse(decoded.toPBJsonArray().jsonArray.hasRevivedAt())
    }

    @Test
    fun `a Primitive carrying revived_at decodes to the same CrdtPrimitive as one without`() {
        fun build(withRevivedAt: Boolean) = jSONElement {
            primitive = primitive {
                type = PBValueType.VALUE_TYPE_NULL
                createdAt = ticket.toPBTimeTicket()
                if (withRevivedAt) revivedAt = dormantTicket.toPBTimeTicket()
            }
        }

        val decoded = build(true).primitive.toCrdtPrimitive()
        val decodedWithout = build(false).primitive.toCrdtPrimitive()

        assertEquals(decodedWithout, decoded)
        assertFalse(decoded.toPBPrimitive().primitive.hasRevivedAt())
    }

    @Test
    fun `a Text carrying revived_at decodes to the same CrdtText as one without`() {
        fun build(withRevivedAt: Boolean) = jSONElement {
            text = text {
                createdAt = ticket.toPBTimeTicket()
                if (withRevivedAt) revivedAt = dormantTicket.toPBTimeTicket()
            }
        }

        val decoded = build(true).text.toCrdtText()
        val decodedWithout = build(false).text.toCrdtText()

        // RgaTreeSplit is deliberately identity-equals (S8, RgaTreeSplit.kt)
        // -- compare the scalar/content projection instead of the whole
        // CrdtText, which two independently-built instances never ==.
        assertEquals(decodedWithout.createdAt, decoded.createdAt)
        assertEquals(decodedWithout.movedAt, decoded.movedAt)
        assertEquals(decodedWithout.removedAt, decoded.removedAt)
        assertEquals(decodedWithout.values, decoded.values)
        assertFalse(decoded.toPBText().text.hasRevivedAt())
    }

    @Test
    fun `a Counter carrying revived_at decodes to the same CrdtCounter as one without`() {
        fun build(withRevivedAt: Boolean) = jSONElement {
            counter = counter {
                type = PBValueType.VALUE_TYPE_INTEGER_CNT
                value = byteArrayOf(0, 0, 0, 0).toByteString()
                createdAt = ticket.toPBTimeTicket()
                if (withRevivedAt) revivedAt = dormantTicket.toPBTimeTicket()
            }
        }

        val decoded = build(true).counter.toCrdtCounter()
        val decodedWithout = build(false).counter.toCrdtCounter()

        assertEquals(decodedWithout, decoded)
        assertFalse(decoded.toPBCounter().counter.hasRevivedAt())
    }

    @Test
    fun `a Tree carrying revived_at decodes to the same CrdtTree as one without`() {
        val root = treeNode {
            id = CrdtTreeNodeID(ticket, 0).toPBTreeNodeID()
            type = "r"
            depth = 0
        }
        fun build(withRevivedAt: Boolean) = jSONElement {
            tree = tree {
                nodes.add(root)
                createdAt = ticket.toPBTimeTicket()
                if (withRevivedAt) revivedAt = dormantTicket.toPBTimeTicket()
            }
        }

        val decoded = build(true).tree.toCrdtTree()
        val decodedWithout = build(false).tree.toCrdtTree()

        assertEquals(decodedWithout, decoded)
        assertFalse(decoded.toPBTree().tree.hasRevivedAt())
    }
}
