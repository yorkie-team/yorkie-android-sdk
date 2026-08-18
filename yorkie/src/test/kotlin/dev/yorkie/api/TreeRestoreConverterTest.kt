package dev.yorkie.api

import dev.yorkie.api.v1.OperationKt.treeEdit
import dev.yorkie.api.v1.nodeAttr
import dev.yorkie.api.v1.operation
import dev.yorkie.api.v1.treeNodeID
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.Rht
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.RestoreMode
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.util.YorkieException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import dev.yorkie.api.v1.RestoreMode as PbRestoreMode
import dev.yorkie.api.v1.TreeRestoreSpan as PbTreeRestoreSpan

/**
 * Ports `tree_restore_converter_test.ts` (JS SDK fa6cc513) as JVM unit
 * tests (AC12): converter round-trips element/text restore spans with
 * anchors and attributes, a pure-insert reverse, and the redo direction;
 * an ordinary tree edit carries no restore payload; 6 malformed spans throw
 * at decode.
 */
class TreeRestoreConverterTest {

    private val actorID = "000000000000000000000001"
    private val seed = TimeTicket(1L, 0u, actorID)
    private val execTicket = TimeTicket(4L, 0u, actorID)
    private val pos = CrdtTreePos(CrdtTreeNodeID(seed, 0), CrdtTreeNodeID(seed, 0))

    private fun id(lamport: Long, offset: Int = 0) = CrdtTreeNodeID(
        TimeTicket(lamport, 0u, actorID),
        offset,
    )

    private fun treeEditOp(
        restoreSpans: List<TreeRestoreSpan>? = null,
        restoreMode: RestoreMode? = null,
        retombstoneSpans: List<TreeRestoreSpan>? = null,
    ) = TreeEditOperation(
        parentCreatedAt = InitialTimeTicket,
        fromPos = pos,
        toPos = pos,
        contents = null,
        splitLevel = 0,
        executedAt = execTicket,
        restoreSpans = restoreSpans,
        restoreMode = restoreMode,
        retombstoneSpans = retombstoneSpans,
    )

    private fun TreeEditOperation.roundTrip() =
        listOf(toPBOperation()).toOperations().single() as TreeEditOperation

    @Test
    fun `round-trips element and text spans with anchors and attributes`() {
        val elementSpan = TreeRestoreSpan(
            id = id(2),
            nodeType = "p",
            isText = false,
            length = 0,
            attrs = Rht().apply { set("bold", "true", execTicket) },
            parentID = id(1),
            leftSiblingID = id(1, 1),
            rightSiblingID = id(5, 3),
        )
        val textSpan = TreeRestoreSpan(
            id = id(3),
            nodeType = "text",
            isText = true,
            length = 2,
            value = "ab",
            parentID = id(2),
        )
        val restored = treeEditOp(
            restoreSpans = listOf(elementSpan, textSpan),
            restoreMode = RestoreMode.Restore,
        ).roundTrip()

        assertEquals(RestoreMode.Restore, restored.restoreMode)
        val spans = requireNotNull(restored.restoreSpans)
        assertEquals(2, spans.size)

        assertEquals(elementSpan.id, spans[0].id)
        assertEquals("p", spans[0].nodeType)
        assertEquals(false, spans[0].isText)
        assertEquals(0, spans[0].length)
        assertNull(spans[0].value)
        assertEquals(mapOf("bold" to "true"), spans[0].attrs?.nodeKeyValueMap)
        assertEquals(elementSpan.parentID, spans[0].parentID)
        assertEquals(elementSpan.leftSiblingID, spans[0].leftSiblingID)
        assertEquals(elementSpan.rightSiblingID, spans[0].rightSiblingID)

        assertEquals(textSpan.id, spans[1].id)
        assertEquals(true, spans[1].isText)
        assertEquals(2, spans[1].length)
        assertEquals("ab", spans[1].value)
        assertEquals(textSpan.parentID, spans[1].parentID)
        assertNull(spans[1].leftSiblingID)
        assertNull(spans[1].rightSiblingID)
    }

    @Test
    fun `round-trips the companion retombstoneSpans of a pure-insert reverse`() {
        // The reverse of a pure insert retombstones the inserted node by
        // identity (retombstoneSpans); restoreSpans stays empty.
        val insertedSpan = TreeRestoreSpan(
            id = id(5),
            nodeType = "b",
            isText = false,
            length = 0,
            parentID = id(2),
        )
        val restored = treeEditOp(
            restoreSpans = emptyList(),
            restoreMode = RestoreMode.Restore,
            retombstoneSpans = listOf(insertedSpan),
        ).roundTrip()

        assertEquals(RestoreMode.Restore, restored.restoreMode)
        assertEquals(emptyList<TreeRestoreSpan>(), restored.restoreSpans)
        assertEquals(1, restored.retombstoneSpans?.size)
        assertEquals(insertedSpan.id, restored.retombstoneSpans?.single()?.id)
        assertEquals("b", restored.retombstoneSpans?.single()?.nodeType)
    }

    @Test
    fun `round-trips the redo direction`() {
        val span = TreeRestoreSpan(id = id(5), nodeType = "b", isText = false, length = 0)
        val restored = treeEditOp(
            restoreSpans = listOf(span),
            restoreMode = RestoreMode.Retombstone,
        ).roundTrip()

        assertEquals(RestoreMode.Retombstone, restored.restoreMode)
        assertEquals(span.id, restored.restoreSpans?.single()?.id)
    }

    @Test
    fun `leaves an ordinary tree edit without a restore payload`() {
        val op = treeEditOp()
        val pbOp = op.toPBOperation()

        assertTrue(pbOp.hasTreeEdit())
        assertTrue(pbOp.treeEdit.restoreSpansList.isEmpty())
        assertTrue(pbOp.treeEdit.retombstoneSpansList.isEmpty())
        assertEquals(PbRestoreMode.RESTORE_MODE_UNSPECIFIED, pbOp.treeEdit.restoreMode)

        val restored = op.roundTrip()
        assertNull(restored.restoreSpans)
        assertNull(restored.retombstoneSpans)
        assertNull(restored.restoreMode)
    }

    // --- Malformed spans: each blanks a required timestamp, or (the last
    // case) corrupts a text span's length/value invariant. ---

    private fun validSpanBuilder(): PbTreeRestoreSpan.Builder =
        PbTreeRestoreSpan.newBuilder().apply {
            id = treeNodeID {
                createdAt = seed.toPBTimeTicket()
                offset = 0
            }
            nodeType = "p"
            isText = false
            length = 0
        }

    private fun decode(span: PbTreeRestoreSpan): List<Operation> {
        val pbOp = operation {
            treeEdit = treeEdit {
                parentCreatedAt = InitialTimeTicket.toPBTimeTicket()
                from = pos.toPBTreePos()
                to = pos.toPBTreePos()
                executedAt = execTicket.toPBTimeTicket()
                restoreSpans.add(span)
                restoreMode = PbRestoreMode.RESTORE_MODE_RESTORE
            }
        }
        return listOf(pbOp).toOperations()
    }

    private fun assertMalformed(span: PbTreeRestoreSpan) {
        val exception = assertFailsWith<YorkieException> { decode(span) }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
    }

    @Test
    fun `throws when the span id has no createdAt`() {
        val span = validSpanBuilder().apply {
            id = treeNodeID { offset = 0 } // no createdAt
        }.build()
        assertMalformed(span)
    }

    @Test
    fun `throws when parentId is present without createdAt`() {
        val span = validSpanBuilder().apply {
            parentId = treeNodeID { offset = 1 } // no createdAt
        }.build()
        assertMalformed(span)
    }

    @Test
    fun `throws when leftSiblingId is present without createdAt`() {
        val span = validSpanBuilder().apply {
            leftSiblingId = treeNodeID { offset = 1 } // no createdAt
        }.build()
        assertMalformed(span)
    }

    @Test
    fun `throws when rightSiblingId is present without createdAt`() {
        val span = validSpanBuilder().apply {
            rightSiblingId = treeNodeID { offset = 1 } // no createdAt
        }.build()
        assertMalformed(span)
    }

    @Test
    fun `throws when an attribute has no updatedAt`() {
        val span = validSpanBuilder().apply {
            putAttributes("bold", nodeAttr { value = "true" }) // no updatedAt
        }.build()
        assertMalformed(span)
    }

    @Test
    fun `throws when a text span's length does not match its value length`() {
        // A valid text restore span first (isText, value/length agree), then
        // corrupt only the length on the encoded pb — mirrors a hostile or
        // corrupt remote payload rather than an SDK-produced one.
        val span = validSpanBuilder().apply {
            isText = true
            nodeType = "text"
            value = "ab"
            length = 3 // corrupted: value has only 2 chars
        }.build()
        assertMalformed(span)
    }

    @Test
    fun `throws when a text span's length is zero but its value is nonempty`() {
        val span = validSpanBuilder().apply {
            isText = true
            nodeType = "text"
            value = "ab"
            length = 0 // corrupted: capture always sets length = value.length
        }.build()
        assertMalformed(span)
    }

    @Test
    fun `throws when a text span's length is negative`() {
        val span = validSpanBuilder().apply {
            isText = true
            nodeType = "text"
            value = "ab"
            length = -1
        }.build()
        assertMalformed(span)
    }

    @Test
    fun `tolerates a nonzero length on an element span`() {
        // Parity pin: JS validates no span length at all; length is a dead field
        // on every element path, so element spans stay unvalidated by design.
        val span = validSpanBuilder().apply {
            length = 7
        }.build()

        val operations = decode(span)

        assertEquals(1, operations.size)
        assertEquals(7, (operations.single() as TreeEditOperation).restoreSpans?.single()?.length)
    }
}
