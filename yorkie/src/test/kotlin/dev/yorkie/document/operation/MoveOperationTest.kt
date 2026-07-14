package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtArray
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveOperationTest {

    private fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, "A")

    @Test
    fun `should move the element and register the dead position node for gc`() {
        // given — root.arr = [0, 1, 2]
        val array = CrdtArray(tick(1))
        val elements = (0..2).map { CrdtPrimitive(it, tick(2L + it)) }
        elements.forEach { array.insertAfter(array.lastCreatedAt, it) }
        val rootObject = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        rootObject.set("arr", array, tick(5))
        val root = CrdtRoot(rootObject)
        assertEquals(0, root.garbageLength)

        // when — move the element at index 2 after index 0
        val operation = MoveOperation(
            prevCreatedAt = elements[0].createdAt,
            createdAt = elements[2].createdAt,
            parentCreatedAt = array.createdAt,
            executedAt = tick(6),
        )
        val result = operation.execute(root)

        // then — [0, 2, 1], with a MoveOpInfo and the dead position registered for GC
        val opInfo = result.opInfos.single() as OperationInfo.MoveOpInfo
        assertEquals(2, opInfo.previousIndex)
        assertEquals(1, opInfo.index)
        assertEquals("1", array.subPathOf(elements[2].createdAt))
        assertEquals(1, root.garbageLength)

        // and — re-executing the same operation is idempotent and registers nothing new
        operation.execute(root)
        assertEquals("1", array.subPathOf(elements[2].createdAt))
        assertEquals(1, root.garbageLength)
    }

    @Test
    fun `should not execute when the parent is not an array`() {
        // given — root.obj is an object, not an array
        val rootObject = CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht())
        val child = CrdtObject(tick(1), memberNodes = ElementRht())
        rootObject.set("obj", child, tick(2))
        val root = CrdtRoot(rootObject)

        val result = MoveOperation(
            prevCreatedAt = tick(1),
            createdAt = tick(1),
            parentCreatedAt = child.createdAt,
            executedAt = tick(3),
        ).execute(root)

        assertEquals(emptyList<OperationInfo>(), result.opInfos)
    }
}
