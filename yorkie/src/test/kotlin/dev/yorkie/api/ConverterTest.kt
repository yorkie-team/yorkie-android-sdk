package dev.yorkie.api

import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitNodePos
import dev.yorkie.document.crdt.RhtPQMap
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SelectOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class ConverterTest {
    private val defaultPrimitive = CrdtPrimitive("default", TimeTicket.InitialTimeTicket)

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
        val timeTicket = TimeTicket.InitialTimeTicket
        val converted = timeTicket.toPBTimeTicket().toTimeTicket()

        assertEquals(timeTicket, converted)
    }

    @Test
    fun `should convert Change`() {
        val addOperation = AddOperation(
            TimeTicket.InitialTimeTicket,
            defaultPrimitive,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val change = Change(ChangeID.InitialChangeID, listOf(addOperation), null)
        val converted = listOf(change.toPBChange()).toChanges().first()

        assertEquals(change, converted)
    }

    @Test
    fun `should convert Changes`() {
        val addOperation = AddOperation(
            TimeTicket.InitialTimeTicket,
            defaultPrimitive,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val setOperation = SetOperation(
            "set",
            defaultPrimitive,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val addChange = Change(ChangeID.InitialChangeID, listOf(addOperation), "add")
        val setChange = Change(ChangeID.InitialChangeID, listOf(setOperation), "set")
        val converted = listOf(addChange.toPBChange(), setChange.toPBChange()).toChanges()

        assertEquals(listOf(addChange, setChange), converted)
    }

    @Test
    fun `should convert ChangePack`() {
        val addOperation = AddOperation(
            TimeTicket.InitialTimeTicket,
            defaultPrimitive,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
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
    fun `should convert Operations`() {
        val addOperation = AddOperation(
            TimeTicket.InitialTimeTicket,
            defaultPrimitive,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val setOperation = SetOperation(
            "set",
            defaultPrimitive,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val removeOperation = RemoveOperation(
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val moveOperation = MoveOperation(
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val increaseOperation = IncreaseOperation(
            defaultPrimitive,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val nodePos = RgaTreeSplitNodePos(
            RgaTreeSplitNodeID(TimeTicket.InitialTimeTicket, 0),
            0,
        )
        val editOperation = EditOperation(
            nodePos,
            nodePos,
            mapOf(ActorID("edit") to TimeTicket.InitialTimeTicket),
            "edit",
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val selectOperation = SelectOperation(
            nodePos,
            nodePos,
            TimeTicket.InitialTimeTicket,
            TimeTicket.InitialTimeTicket,
        )
        val converted = listOf(
            addOperation.toPBOperation(),
            setOperation.toPBOperation(),
            removeOperation.toPBOperation(),
            moveOperation.toPBOperation(),
            increaseOperation.toPBOperation(),
            editOperation.toPBOperation(),
            selectOperation.toPBOperation(),
        ).toOperations()

        assertEquals(addOperation, converted[0])
        assertEquals(setOperation, converted[1])
        assertEquals(removeOperation, converted[2])
        assertEquals(moveOperation, converted[3])
        assertEquals(increaseOperation, converted[4])
        assertEquals(editOperation, converted[5])
        assertEquals(selectOperation, converted[6])
    }

    @Test
    fun `should convert CrdtObject`() {
        val crdtObject = CrdtObject(TimeTicket.InitialTimeTicket, rht = RhtPQMap())
        val converted = crdtObject.toPBJsonObject().toCrdtElement()

        assertEquals(crdtObject.toJson(), converted.toJson())
    }

    @Test
    fun `should convert CrdtArray`() {
        val crdtArray = CrdtArray(TimeTicket.InitialTimeTicket)
        val converted = crdtArray.toPBJsonArray().toCrdtElement()

        assertEquals(crdtArray.toJson(), converted.toJson())
    }

    @Test
    fun `should convert CrdtCounter`() {
        val counterInt = CrdtCounter(1, TimeTicket.InitialTimeTicket)
        assertEquals(counterInt.toJson(), counterInt.toPBCounter().toCrdtElement().toJson())

        val counterLong = CrdtCounter(100L, TimeTicket.InitialTimeTicket)
        assertEquals(counterLong.toJson(), counterLong.toPBCounter().toCrdtElement().toJson())
    }

    @Test
    fun `should convert CrdtPrimitive`() {
        val primitiveNull = CrdtPrimitive(null, TimeTicket.InitialTimeTicket)
        val primitiveBoolean = CrdtPrimitive(true, TimeTicket.InitialTimeTicket)
        val primitiveInteger = CrdtPrimitive(100, TimeTicket.InitialTimeTicket)
        val primitiveLong = CrdtPrimitive(100L, TimeTicket.InitialTimeTicket)
        val primitiveDouble = CrdtPrimitive(100.0, TimeTicket.InitialTimeTicket)
        val primitiveString = CrdtPrimitive("str", TimeTicket.InitialTimeTicket)
        val primitiveBytes = CrdtPrimitive("bytes".toByteArray(), TimeTicket.InitialTimeTicket)
        val primitiveDate = CrdtPrimitive(Date(100L), TimeTicket.InitialTimeTicket)
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
    fun `should convert ElementSimple`() {
        val crdtObject = CrdtObject(TimeTicket.InitialTimeTicket, rht = RhtPQMap())
        val crdtArray = CrdtArray(TimeTicket.InitialTimeTicket)
        val primitive = CrdtPrimitive("str", TimeTicket.InitialTimeTicket)
        val crdtElements = listOf(
            crdtObject,
            crdtArray,
            primitive,
        )

        crdtElements.forEach {
            assertEquals(it.toJson(), it.toPBJsonElementSimple().toCrdtElement().toJson())
        }
    }
}
