package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementRhtTest {

    @Test
    fun `Verify the set function`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        elementRht.set(
            key = "test1",
            value = CrdtPrimitive("value1", TimeTicket.InitialTimeTicket.copy(lamport = 0)),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 0),
        )
        elementRht.set(
            key = "test1",
            value = CrdtPrimitive("value11", TimeTicket.InitialTimeTicket.copy(lamport = 0)),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 0),
        )
        elementRht.set(
            key = "test2",
            value = CrdtPrimitive("value2", TimeTicket.InitialTimeTicket.copy(lamport = 1)),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 1),
        )
        elementRht.set(
            key = "test3",
            value = CrdtPrimitive("value3", TimeTicket.InitialTimeTicket.copy(lamport = 2)),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 2),
        )
        elementRht.set(
            key = "test4",
            value = CrdtPrimitive("value4", TimeTicket.InitialTimeTicket.copy(lamport = 3)),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 3),
        )
        elementRht.set(
            key = "test5",
            value = CrdtPrimitive("value5", TimeTicket.InitialTimeTicket.copy(lamport = 4)),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 4),
        )
        assertTrue(elementRht["test1"].value == "value1")
        assertTrue(elementRht["test2"].value == "value2")
        assertTrue(elementRht["test3"].value == "value3")
        assertTrue(elementRht["test4"].value == "value4")
        assertTrue(elementRht["test5"].value == "value5")
        assertEquals(
            "test1: value11\ntest2: value2\ntest3: value3\ntest4: value4\n" +
                "test5: value5\n",
            elementRht.toTestString(),
        )

        val nullObject = elementRht.set(
            key = "test5",
            value = CrdtPrimitive("value6", TimeTicket.InitialTimeTicket),
            executedAt = TimeTicket.InitialTimeTicket,
        )
        assertNull(nullObject)

        val removedPrimitive = elementRht.set(
            key = "test5",
            value = CrdtPrimitive(
                "value6",
                TimeTicket.InitialTimeTicket.copy(lamport = 4),
            ),
            executedAt = TimeTicket.InitialTimeTicket.copy(lamport = 5),
        )
        assertEquals("value5", removedPrimitive?.value)
        assertEquals("value6", elementRht["test5"].value)
    }

    @Test
    fun `Verify the set function on concurrent situations`() {
        val elementRht1 = ElementRht<CrdtPrimitive>()
        val ticket1 = generateTimeTicket(1, 1, "1")
        elementRht1.set(key = "test1", value = CrdtPrimitive(1, ticket1), executedAt = ticket1)

        (2..3).forEach { index ->
            val ticket = generateTimeTicket(index.toLong(), index, "2")
            elementRht1.set(
                key = "test1",
                value = CrdtPrimitive(index, ticket),
                executedAt = ticket,
            )
        }

        val elementRht2 = ElementRht<CrdtPrimitive>()
        val ticket2 = generateTimeTicket(1, 1, "1")
        elementRht2.set(key = "test1", value = CrdtPrimitive(1, ticket2), executedAt = ticket2)
        (3 downTo 2).forEach { index ->
            val ticket = generateTimeTicket(index.toLong(), index, "3")
            elementRht2.set(
                key = "test1",
                value = CrdtPrimitive(index, ticket),
                executedAt = ticket,
            )
        }

        val value1 = elementRht1["test1"].value
        val value2 = elementRht2["test1"].value

        assertEquals(value1, value2)
    }

    @Test
    fun `Verify the get function`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        elementRht.set(
            key = "test1",
            value = CrdtPrimitive("value1", TimeTicket.InitialTimeTicket),
            executedAt = TimeTicket.InitialTimeTicket,
        )
        elementRht.set(
            key = "test2",
            value = CrdtPrimitive("value2", TimeTicket.InitialTimeTicket),
            executedAt = TimeTicket.InitialTimeTicket,
        )
        elementRht.set(
            key = "test3",
            value = CrdtPrimitive("value3", TimeTicket.InitialTimeTicket),
            executedAt = TimeTicket.InitialTimeTicket,
        )
        elementRht.set(
            key = "test4",
            value = CrdtPrimitive("value4", TimeTicket.InitialTimeTicket),
            executedAt = TimeTicket.InitialTimeTicket,
        )
        elementRht.set(
            key = "test5",
            value = CrdtPrimitive("value5", TimeTicket.InitialTimeTicket),
            executedAt = TimeTicket.InitialTimeTicket,
        )

        assertFalse(elementRht["test1"].value == "value2")
        assertTrue(elementRht["test2"].value == "value2")
        assertTrue(elementRht["test2"].value == "value2")

        assertThrows(NoSuchElementException::class.java) {
            elementRht["test6"]
        }
    }

    @Test
    fun `Verify the remove function`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        val primitive1 = CrdtPrimitive("value1", TimeTicket.InitialTimeTicket)
        elementRht.set(key = "test1", value = primitive1, executedAt = TimeTicket.InitialTimeTicket)
        val removedPrimitive =
            elementRht.delete(TimeTicket.InitialTimeTicket, TimeTicket.InitialTimeTicket)
        assertEquals(primitive1, removedPrimitive)

        assertThrows(YorkieException::class.java) {
            elementRht.delete(
                generateTimeTicket(99, 99, "3"),
                generateTimeTicket(100, 100, "3"),
            )
        }
        assertThrows(YorkieException::class.java) {
            elementRht.delete(
                generateTimeTicket(101, 101, "4"),
                TimeTicket.InitialTimeTicket,
            )
        }
    }

    @Test
    fun `Verify the removeByKey function`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        val primitive = CrdtPrimitive("value1", TimeTicket.InitialTimeTicket)
        elementRht.set(key = "test1", value = primitive, executedAt = TimeTicket.InitialTimeTicket)

        assertThrows(NoSuchElementException::class.java) {
            elementRht.removeByKey("", TimeTicket.InitialTimeTicket)
        }

        val timeTicketForDeletion = generateTimeTicket(1, 1, "0")
        val removedPrimitive = elementRht.removeByKey("test1", timeTicketForDeletion)
        assertEquals(primitive, removedPrimitive)
        assertEquals(timeTicketForDeletion, elementRht["test1"].removedAt)
    }

    @Test
    fun `Verify the subPathOf function`() {
        val timeTicket = generateTimeTicket(0, 0, "0")
        val elementRht = ElementRht<CrdtPrimitive>()
        val primitive = CrdtPrimitive("value1", timeTicket)
        elementRht.set(key = "test1", value = primitive, executedAt = timeTicket)
        val primitiveBySubPathOf = elementRht.subPathOf(timeTicket)
        assertEquals(primitive, elementRht[primitiveBySubPathOf])

        assertThrows(NoSuchElementException::class.java) {
            elementRht.subPathOf(generateTimeTicket(1, 1, "11"))
        }
    }

    @Test
    fun `Verify the delete function`() {
        val elementRht = ElementRht<CrdtPrimitive>()

        val ticket1 = generateTimeTicket(0, 0, "11")
        val primitive1 = CrdtPrimitive("value1", ticket1)
        elementRht.set(key = "test1", value = primitive1, executedAt = ticket1)

        val ticket2 = generateTimeTicket(1, 1, "11")
        val primitive2 = CrdtPrimitive("value2", ticket2)
        elementRht.set(key = "test2", value = primitive2, executedAt = ticket2)

        val ticket3 = generateTimeTicket(2, 2, "11")
        val primitive3 = CrdtPrimitive("value3", ticket3)
        elementRht.set(key = "test3", value = primitive3, executedAt = ticket3)

        elementRht.purge(primitive2)
        assertThrows(NoSuchElementException::class.java) {
            elementRht["test2"]
        }

        assertNotNull(elementRht["test1"])
        assertNotNull(elementRht["test3"])
    }

    @Test
    fun `Verify the has function`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        assertFalse(elementRht.has("test1"))

        val ticket1 = TimeTicket.InitialTimeTicket
        val primitive1 = CrdtPrimitive("value1", ticket1)
        elementRht.set(key = "test1", value = primitive1, executedAt = ticket1)
        assertTrue(elementRht.has("test1"))

        elementRht.delete(ticket1, generateTimeTicket(1, 2, "11"))
        assertFalse(elementRht.has("test1"))
    }

    @Test
    fun `Verity the getKeyOfQueue() using sequence`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        val list = mutableListOf<String>()
        for (i in 0..100000) {
            val key = "test$i"
            val ticket = generateTimeTicket(i.toLong(), i, "11")
            elementRht.set(key = key, value = CrdtPrimitive("value$i", ticket), executedAt = ticket)
            list.add(key)
        }

        assertTrue(list.size == 100001)

        elementRht.getKeyOfQueue()
            .forEach { elementRhtNode ->
                list.remove(elementRhtNode.strKey)
            }
        assertTrue(list.isEmpty())
    }

    private fun generateTimeTicket(
        lamport: Long,
        delimiter: Int,
        actorID: String,
    ): TimeTicket {
        return TimeTicket(lamport, delimiter.toUInt(), ActorID(actorID))
    }

    private fun ElementRht<CrdtPrimitive>.toTestString() = buildString {
        for (node in this@toTestString) {
            append("${node.strKey}: ${node.value.value}").appendLine()
        }
    }
}
