package dev.yorkie.api

import com.google.protobuf.kotlin.toByteStringUtf8
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.operation
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtElement
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SelectOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Date

class ConverterTest {
    private val defaultPrimitive = CrdtPrimitive("default", InitialTimeTicket)

    @Test
    fun `should convert ByteString`() {
        val actorID = ActorID.INITIAL_ACTOR_ID
        val converted = actorID.toByteString().toActorID()
        val maxActorID = ActorID.MAX_ACTOR_ID
        val maxConverted = maxActorID.toByteString().toActorID()

        assertEquals(actorID, converted)
        assertEquals(maxActorID, maxConverted)
    }

    @Test
    fun `should convert ChangeID`() {
        val changeID = ChangeID.InitialChangeID
        val converted = changeID.toPBChangeID().toChangeID()

        assertEquals(changeID, converted)
    }

    @Test
    fun `should convert CheckPoint`() {
        val checkPoint = CheckPoint.InitialCheckPoint
        val converted = checkPoint.toPBCheckPoint().toCheckPoint()

        assertEquals(checkPoint, converted)
    }

    @Test
    fun `should convert TimeTicket`() {
        val timeTicket = InitialTimeTicket
        val converted = timeTicket.toPBTimeTicket().toTimeTicket()

        assertEquals(timeTicket, converted)
    }

    @Test
    fun `should convert Change`() {
        val addOperation = AddOperation(
            InitialTimeTicket,
            defaultPrimitive,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val change = Change(ChangeID.InitialChangeID, listOf(addOperation), null)
        val converted = listOf(change.toPBChange()).toChanges().first()

        assertEquals(change, converted)
    }

    @Test
    fun `should convert Changes`() {
        val addOperation = AddOperation(
            InitialTimeTicket,
            defaultPrimitive,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val setOperation = SetOperation(
            "set",
            defaultPrimitive,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val addChange = Change(ChangeID.InitialChangeID, listOf(addOperation), "add")
        val setChange = Change(ChangeID.InitialChangeID, listOf(setOperation), "set")
        val converted = listOf(addChange.toPBChange(), setChange.toPBChange()).toChanges()

        assertEquals(listOf(addChange, setChange), converted)
    }

    @Test
    fun `should convert ChangePack`() {
        val addOperation = AddOperation(
            InitialTimeTicket,
            defaultPrimitive,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val change = Change(ChangeID.InitialChangeID, listOf(addOperation), "add")
        val changePack = ChangePack(
            "key",
            CheckPoint.InitialCheckPoint,
            listOf(change),
            null,
            null,
        )
        val converted = changePack.toPBChangePack().toChangePack()

        assertEquals(changePack.documentKey, converted.documentKey)
        assertEquals(changePack.checkPoint, converted.checkPoint)
        assertEquals(changePack.changes, converted.changes)
        assertEquals(changePack.snapshot, converted.snapshot)
        assertEquals(changePack.minSyncedTicket, converted.minSyncedTicket)
    }

    @Test
    fun `should convert ChangePack with snapshot and minSyncedTicket`() {
        val addOperation = AddOperation(
            InitialTimeTicket,
            defaultPrimitive,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val change = Change(ChangeID.InitialChangeID, listOf(addOperation), "add")
        val changePack = ChangePack(
            "key",
            CheckPoint.InitialCheckPoint,
            listOf(change),
            "snapshot".toByteStringUtf8(),
            InitialTimeTicket,
        )
        val converted = changePack.toPBChangePack().toChangePack()

        assertEquals(changePack.documentKey, converted.documentKey)
        assertEquals(changePack.checkPoint, converted.checkPoint)
        assertEquals(changePack.changes, converted.changes)
        assertEquals(changePack.snapshot, converted.snapshot)
        assertEquals(changePack.minSyncedTicket, converted.minSyncedTicket)
    }

    @Test
    fun `should convert Operations`() {
        val addOperation = AddOperation(
            InitialTimeTicket,
            defaultPrimitive,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val setOperation = SetOperation(
            "set",
            defaultPrimitive,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val removeOperation = RemoveOperation(
            InitialTimeTicket,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val moveOperation = MoveOperation(
            InitialTimeTicket,
            InitialTimeTicket,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val increaseOperation = IncreaseOperation(
            defaultPrimitive,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val nodePos = RgaTreeSplitNodePos(
            RgaTreeSplitNodeID(InitialTimeTicket, 0),
            0,
        )
        val editOperationWithoutAttrs = EditOperation(
            nodePos,
            nodePos,
            mapOf(ActorID("edit") to InitialTimeTicket),
            "edit",
            InitialTimeTicket,
            InitialTimeTicket,
            mapOf(),
        )
        val editOperationWithAttrs = EditOperation(
            nodePos,
            nodePos,
            mapOf(ActorID("edit") to InitialTimeTicket),
            "edit",
            InitialTimeTicket,
            InitialTimeTicket,
            mapOf("style" to "bold"),
        )
        val selectOperation = SelectOperation(
            nodePos,
            nodePos,
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val styleOperation = StyleOperation(
            nodePos,
            nodePos,
            mapOf("style" to "bold"),
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val converted = listOf(
            addOperation.toPBOperation(),
            setOperation.toPBOperation(),
            removeOperation.toPBOperation(),
            moveOperation.toPBOperation(),
            increaseOperation.toPBOperation(),
            editOperationWithoutAttrs.toPBOperation(),
            editOperationWithAttrs.toPBOperation(),
            selectOperation.toPBOperation(),
            styleOperation.toPBOperation(),
        ).toOperations()

        assertEquals(addOperation, converted[0])
        assertEquals(setOperation, converted[1])
        assertEquals(removeOperation, converted[2])
        assertEquals(moveOperation, converted[3])
        assertEquals(increaseOperation, converted[4])
        assertEquals(editOperationWithoutAttrs, converted[5])
        assertEquals(editOperationWithAttrs, converted[6])
        assertEquals(selectOperation, converted[7])
        assertEquals(styleOperation, converted[8])
    }

    @Test
    fun `should throw IllegalArgumentException for unsupported operations`() {
        assertThrows(IllegalArgumentException::class.java) {
            val operation = TestOperation(InitialTimeTicket, InitialTimeTicket, InitialTimeTicket)
            operation.toPBOperation()
        }

        assertThrows(IllegalArgumentException::class.java) {
            val pbOperation = operation { }
            listOf(pbOperation).toOperations()
        }
    }

    @Test
    fun `should convert CrdtObject`() {
        val crdtObject =
            CrdtObject(InitialTimeTicket, InitialTimeTicket, InitialTimeTicket, ElementRht())
        val converted = crdtObject.toPBJsonObject().toCrdtElement()

        assertEquals(crdtObject.toJson(), converted.toJson())
    }

    @Test
    fun `should convert CrdtArray`() {
        val crdtArray = CrdtArray(
            InitialTimeTicket,
            InitialTimeTicket,
            InitialTimeTicket,
            RgaTreeList().apply { insert(CrdtPrimitive(null, InitialTimeTicket)) },
        )
        val converted = crdtArray.toPBJsonArray().toCrdtElement()

        assertEquals(crdtArray.toJson(), converted.toJson())
    }

    @Test
    fun `should convert CrdtCounter`() {
        val counterInt = CrdtCounter(1, InitialTimeTicket, InitialTimeTicket, InitialTimeTicket)
        assertEquals(counterInt.toJson(), counterInt.toPBCounter().toCrdtElement().toJson())

        val counterLong = CrdtCounter(100L, InitialTimeTicket, InitialTimeTicket, InitialTimeTicket)
        assertEquals(counterLong.toJson(), counterLong.toPBCounter().toCrdtElement().toJson())
    }

    @Test
    fun `should convert CrdtText`() {
        val crdtText = CrdtText(RgaTreeSplit(), InitialTimeTicket).apply {
            edit(createRange(0, 0), "Text", InitialTimeTicket)
        }
        val converted = crdtText.toPBText().toCrdtElement()
        assertEquals(crdtText.toJson(), converted.toJson())
    }

    @Test
    fun `should convert CrdtPrimitive`() {
        val primitiveNull = CrdtPrimitive(null, InitialTimeTicket)
        val primitiveBoolean = CrdtPrimitive(true, InitialTimeTicket)
        val primitiveInteger = CrdtPrimitive(100, InitialTimeTicket)
        val primitiveLong = CrdtPrimitive(100L, InitialTimeTicket)
        val primitiveDouble = CrdtPrimitive(100.0, InitialTimeTicket)
        val primitiveString = CrdtPrimitive("str", InitialTimeTicket)
        val primitiveBytes = CrdtPrimitive("bytes".toByteArray(), InitialTimeTicket)
        val primitiveDate = CrdtPrimitive(Date(100L), InitialTimeTicket)
        val primitives = listOf(
            primitiveNull,
            primitiveBoolean,
            primitiveInteger,
            primitiveLong,
            primitiveDouble,
            primitiveString,
            primitiveBytes,
            primitiveDate,
        )

        primitives.forEach {
            assertEquals(it.toJson(), it.toPBPrimitive().toCrdtElement().toJson())
        }
    }

    @Test
    fun `should throw IllegalArgumentException for unsupported CrdtElements`() {
        assertThrows(IllegalArgumentException::class.java) {
            val testCrdtElement = TestCrdtElement(InitialTimeTicket)
            testCrdtElement.toPBJsonElement()
        }

        assertThrows(IllegalArgumentException::class.java) {
            val testCrdtElement = TestCrdtElement(InitialTimeTicket)
            testCrdtElement.toPBJsonElementSimple()
        }

        assertThrows(IllegalArgumentException::class.java) {
            val testPBCrdtElement = jSONElement { }
            testPBCrdtElement.toCrdtElement()
        }
    }

    @Test
    fun `should convert ElementSimple`() {
        val crdtObject = CrdtObject(InitialTimeTicket, rht = ElementRht())
        val crdtArray = CrdtArray(InitialTimeTicket)
        val primitive = CrdtPrimitive("str", InitialTimeTicket)
        val crdtCounter = CrdtCounter(1, InitialTimeTicket)
        val crdtText = CrdtText(RgaTreeSplit(), InitialTimeTicket)
        val crdtElements = listOf(
            crdtObject,
            crdtArray,
            primitive,
            crdtCounter,
            crdtText,
        )

        crdtElements.forEach {
            assertEquals(it.toJson(), it.toPBJsonElementSimple().toCrdtElement().toJson())
        }
    }

    private class TestOperation(
        override val parentCreatedAt: TimeTicket,
        override var executedAt: TimeTicket,
        override val effectedCreatedAt: TimeTicket,
    ) : Operation() {
        override fun execute(root: CrdtRoot) {
            println("should throw IllegalArgumentException")
        }
    }

    private class TestCrdtElement(
        override val createdAt: TimeTicket,
        override var _movedAt: TimeTicket? = null,
        override var _removedAt: TimeTicket? = null,
    ) : CrdtElement() {
        override fun deepCopy() = this
    }
}
