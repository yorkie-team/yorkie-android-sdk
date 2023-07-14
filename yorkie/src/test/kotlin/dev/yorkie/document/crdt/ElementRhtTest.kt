package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
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
        elementRht["test1"] = CrdtPrimitive("value1", TimeTicket.InitialTimeTicket)
        elementRht["test1"] = CrdtPrimitive("value11", TimeTicket.InitialTimeTicket)
        elementRht["test2"] = CrdtPrimitive("value2", TimeTicket.InitialTimeTicket)
        elementRht["test3"] = CrdtPrimitive("value3", TimeTicket.InitialTimeTicket)
        elementRht["test4"] = CrdtPrimitive("value4", TimeTicket.InitialTimeTicket)
        elementRht["test5"] = CrdtPrimitive("value5", TimeTicket.InitialTimeTicket)
        assertTrue(elementRht["test1"].value == "value1")
        assertTrue(elementRht["test2"].value == "value2")
        assertTrue(elementRht["test3"].value == "value3")
        assertTrue(elementRht["test4"].value == "value4")
        assertTrue(elementRht["test5"].value == "value5")
        assertEquals(
            "test1: value1\ntest2: value2\ntest3: value3\ntest4: value4\n" +
                "test5: value5\n",
            elementRht.getStructureAsString(),
        )

        val nullObject = elementRht.set(
            "test5",
            CrdtPrimitive("value6", TimeTicket.InitialTimeTicket),
        )
        assertNull(nullObject)

        val removedPrimitive = elementRht.set(
            "test5",
            CrdtPrimitive(
                "value6",
                TimeTicket(1, TimeTicket.MAX_DELIMITER, ActorID.MAX_ACTOR_ID),
            ),
        )
        assertEquals("value5", removedPrimitive?.value)
        assertEquals("value6", elementRht["test5"].value)
    }

    @Test
    fun `Verify the set function on concurrent situations`() {
        val elementRht1 = ElementRht<CrdtPrimitive>()
        elementRht1["test1"] = CrdtPrimitive(1, generateTimeTicket(1, 1, "1"))

        (2..3L).forEach { index ->
            elementRht1["test1"] = CrdtPrimitive(
                index,
                generateTimeTicket(index, index, "2"),
            )
        }

        val elementRht2 = ElementRht<CrdtPrimitive>()
        elementRht2["test1"] = CrdtPrimitive(1, generateTimeTicket(1, 1, "1"))
        (3 downTo 2L).forEach { index ->
            elementRht2["test1"] = CrdtPrimitive(
                index,
                generateTimeTicket(index, index, "3"),
            )
        }

        val value1 = elementRht1["test1"].value
        val value2 = elementRht2["test1"].value

        assertEquals(value1, value2)
    }

    @Test
    fun `Verify the get function`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        elementRht["test1"] = CrdtPrimitive("value1", TimeTicket.InitialTimeTicket)
        elementRht["test2"] = CrdtPrimitive("value2", TimeTicket.InitialTimeTicket)
        elementRht["test3"] = CrdtPrimitive("value3", TimeTicket.InitialTimeTicket)
        elementRht["test4"] = CrdtPrimitive("value4", TimeTicket.InitialTimeTicket)
        elementRht["test5"] = CrdtPrimitive("value5", TimeTicket.InitialTimeTicket)

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
        elementRht["test1"] = primitive1
        val removedPrimitive =
            elementRht.remove(TimeTicket.InitialTimeTicket, TimeTicket.InitialTimeTicket)
        assertEquals(primitive1, removedPrimitive)

        assertThrows(NoSuchElementException::class.java) {
            elementRht.remove(
                generateTimeTicket(99, 99, "3"),
                generateTimeTicket(100, 100, "3"),
            )
        }
        assertThrows(NoSuchElementException::class.java) {
            elementRht.remove(
                generateTimeTicket(101, 101, "4"),
                TimeTicket.InitialTimeTicket,
            )
        }
    }

    @Test
    fun `Verify the removeByKey function`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        val primitive = CrdtPrimitive("value1", TimeTicket.InitialTimeTicket)
        elementRht["test1"] = primitive

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
        elementRht["test1"] = primitive
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
        elementRht["test1"] = primitive1

        val ticket2 = generateTimeTicket(1, 1, "11")
        val primitive2 = CrdtPrimitive("value2", ticket2)
        elementRht["test2"] = primitive2

        val ticket3 = generateTimeTicket(2, 2, "11")
        val primitive3 = CrdtPrimitive("value3", ticket3)
        elementRht["test3"] = primitive3

        elementRht.delete(primitive2)
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
        elementRht["test1"] = primitive1
        assertTrue(elementRht.has("test1"))

        elementRht.remove(ticket1, generateTimeTicket(1, 2, "11"))
        assertFalse(elementRht.has("test1"))
    }

    @Test
    fun `Verity the getKeyOfQueue() using sequence`() {
        val elementRht = ElementRht<CrdtPrimitive>()
        val list = mutableListOf<String>()
        for (i in 0..100000L) {
            val key = "test$i"
            elementRht[key] = CrdtPrimitive("value$i", generateTimeTicket(i, i, "11"))
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
        delimiter: Long,
        actorID: String,
    ): TimeTicket {
        return TimeTicket(lamport, delimiter, ActorID(actorID))
    }

    private fun ElementRht<CrdtPrimitive>.getStructureAsString() = buildString {
        this@getStructureAsString.forEach {
            append("${it.strKey}: ${it.value.value}").appendLine()
        }
    }
}
