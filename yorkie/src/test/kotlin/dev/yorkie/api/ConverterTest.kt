package dev.yorkie.api

import com.google.protobuf.kotlin.toByteStringUtf8
import dev.yorkie.api.v1.jSONElement
import dev.yorkie.api.v1.operation
import dev.yorkie.document.Document
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
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeList
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.document.crdt.RgaTreeSplitPos
import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.operation.AddOperation
import dev.yorkie.document.operation.EditOperation
import dev.yorkie.document.operation.IncreaseOperation
import dev.yorkie.document.operation.MoveOperation
import dev.yorkie.document.operation.Operation
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.operation.RemoveOperation
import dev.yorkie.document.operation.SetOperation
import dev.yorkie.document.operation.StyleOperation
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.ActorID.Companion.INITIAL_ACTOR_ID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.MaxTimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrUnimplemented
import java.util.Date
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ConverterTest {
    private val defaultPrimitive = CrdtPrimitive("default", InitialTimeTicket)

    @Test
    fun `should convert ByteString`() {
        val actorID = INITIAL_ACTOR_ID
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
        val change = Change(ChangeID.InitialChangeID, listOf(addOperation), null, null)
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
        val addChange = Change(
            ChangeID.InitialChangeID,
            listOf(addOperation),
            PresenceChange.Put(mapOf("a" to "b")),
            "add",
        )
        val setChange = Change(
            ChangeID.InitialChangeID,
            listOf(setOperation),
            PresenceChange.Clear,
            "set",
        )
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
        val change = Change(ChangeID.InitialChangeID, listOf(addOperation), null, "add")
        val changePack = ChangePack(
            "key",
            CheckPoint.InitialCheckPoint,
            listOf(change),
            null,
            null,
            isRemoved = false,
            VersionVector.INITIAL_VERSION_VECTOR,
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
        val change = Change(ChangeID.InitialChangeID, listOf(addOperation), null, "add")
        val changePack = ChangePack(
            "key",
            CheckPoint.InitialCheckPoint,
            listOf(change),
            "snapshot".toByteStringUtf8(),
            InitialTimeTicket,
            isRemoved = false,
            VersionVector.INITIAL_VERSION_VECTOR,
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
        val nodePos = RgaTreeSplitPos(
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
        val styleOperation = StyleOperation(
            nodePos,
            nodePos,
            emptyMap(),
            mapOf("style" to "bold"),
            InitialTimeTicket,
            InitialTimeTicket,
        )
        val treeEditOperation = TreeEditOperation(
            InitialTimeTicket,
            CrdtTreePos(CrdtTreeNodeID(InitialTimeTicket, 5), CrdtTreeNodeID(InitialTimeTicket, 5)),
            CrdtTreePos(
                CrdtTreeNodeID(InitialTimeTicket, 10),
                CrdtTreeNodeID(InitialTimeTicket, 10),
            ),
            mapOf(INITIAL_ACTOR_ID to MaxTimeTicket),
            listOf(CrdtTreeText(CrdtTreeNodeID(InitialTimeTicket, 0), "hi")),
            0,
            InitialTimeTicket,
        )
        val treeStyleOperation = TreeStyleOperation(
            InitialTimeTicket,
            CrdtTreePos(CrdtTreeNodeID(InitialTimeTicket, 5), CrdtTreeNodeID(InitialTimeTicket, 5)),
            CrdtTreePos(
                CrdtTreeNodeID(InitialTimeTicket, 10),
                CrdtTreeNodeID(InitialTimeTicket, 10),
            ),
            InitialTimeTicket,
            mapOf(INITIAL_ACTOR_ID to InitialTimeTicket),
            mapOf("a" to "b"),
            listOf("a"),
        )
        val converted = listOf(
            addOperation.toPBOperation(),
            setOperation.toPBOperation(),
            removeOperation.toPBOperation(),
            moveOperation.toPBOperation(),
            increaseOperation.toPBOperation(),
            editOperationWithoutAttrs.toPBOperation(),
            editOperationWithAttrs.toPBOperation(),
            styleOperation.toPBOperation(),
            treeEditOperation.toPBOperation(),
            treeStyleOperation.toPBOperation(),
        ).toOperations()

        assertEquals(addOperation, converted[0])
        assertEquals(setOperation, converted[1])
        assertEquals(removeOperation, converted[2])
        assertEquals(moveOperation, converted[3])
        assertEquals(increaseOperation, converted[4])
        assertEquals(editOperationWithoutAttrs, converted[5])
        assertEquals(editOperationWithAttrs, converted[6])
        assertEquals(styleOperation, converted[7])
        assertEquals(treeEditOperation, converted[8])
        assertEquals(treeStyleOperation, converted[9])
    }

    @Test
    fun `should throw YorkieException for unsupported operations and it contains ErrUnimplemented code`() {
        val exception1 = assertThrows(YorkieException::class.java) {
            val operation = TestOperation(InitialTimeTicket, InitialTimeTicket, InitialTimeTicket)
            operation.toPBOperation()
        }
        assertEquals(ErrUnimplemented, exception1.code)

        val exception2 = assertThrows(YorkieException::class.java) {
            val pbOperation = operation { }
            listOf(pbOperation).toOperations()
        }
        assertEquals(ErrUnimplemented, exception2.code)
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
            edit(indexRangeToPosRange(0, 0), "Text", InitialTimeTicket)
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
    fun `should throw YorkieException for unsupported CrdtElements and it contains ErrUnimplemented code`() {
        val exception1 = assertThrows(YorkieException::class.java) {
            val testCrdtElement = TestCrdtElement(InitialTimeTicket)
            testCrdtElement.toPBJsonElement()
        }
        assertEquals(ErrUnimplemented, exception1.code)

        val exception2 = assertThrows(YorkieException::class.java) {
            val testCrdtElement = TestCrdtElement(InitialTimeTicket)
            testCrdtElement.toPBJsonElementSimple()
        }
        assertEquals(ErrUnimplemented, exception2.code)

        val exception3 = assertThrows(YorkieException::class.java) {
            val testPBCrdtElement = jSONElement { }
            testPBCrdtElement.toCrdtElement()
        }
        assertEquals(ErrUnimplemented, exception3.code)
    }

    @Test
    fun `should convert ElementSimple`() {
        val crdtObject = CrdtObject(InitialTimeTicket, rht = ElementRht())
        val crdtArray = CrdtArray(InitialTimeTicket)
        val primitive = CrdtPrimitive("str", InitialTimeTicket)
        val crdtCounter = CrdtCounter(1, InitialTimeTicket)
        val crdtText = CrdtText(RgaTreeSplit(), InitialTimeTicket)
        val crdtTree = CrdtTree(
            CrdtTreeElement(CrdtTreeNodeID(InitialTimeTicket, 0), DEFAULT_ROOT_TYPE),
            InitialTimeTicket,
        )
        val crdtElements = listOf(
            crdtObject,
            crdtArray,
            primitive,
            crdtCounter,
            crdtText,
            crdtTree,
        )

        crdtElements.forEach {
            assertEquals(it.toJson(), it.toPBJsonElementSimple().toCrdtElement().toJson())
        }
    }

    @Test
    fun `should encode and decode tree properly`() = runTest {
        val document = Document(Document.Key(""))

        fun JsonObject.tree() = getAs<JsonTree>("t")

        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "12" } }
                    element("p") { text { "34" } }
                },
            ).editByPath(listOf(0, 1), listOf(1, 1))

            root.tree().style(0, 1, mapOf("b" to "t", "i" to "t"))
            assertEquals("""<r><p b="t" i="t">14</p></r>""", root.tree().toXml())

            root.tree().removeStyle(0, 1, listOf("i"))
        }.await()

        assertEquals("""<r><p b="t">14</p></r>""", document.getRoot().tree().toXml())
        assertEquals(4, document.getRoot().tree().size)

        val tree = document.getRoot().target["t"]
        val bytes = tree.toByteString()
        val obj = bytes.toCrdtTree()
        assertEquals(document.getRoot().tree().target.nodeSize, obj.nodeSize)
        assertEquals(document.getRoot().tree().size, obj.size)
        assertEquals(document.getRoot().tree().toXml(), obj.toXml())
    }

    private class TestOperation(
        override val parentCreatedAt: TimeTicket,
        override var executedAt: TimeTicket,
        override val effectedCreatedAt: TimeTicket,
    ) : Operation() {
        override fun execute(root: CrdtRoot): List<OperationInfo> = emptyList()
    }

    private class TestCrdtElement(
        override val createdAt: TimeTicket,
        override var _movedAt: TimeTicket? = null,
        override var _removedAt: TimeTicket? = null,
    ) : CrdtElement() {
        override fun deepCopy() = this
    }
}
