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

class RhtPQMapTest {

    @Test
    fun `Verify the set function`() {
        val rhtpqMap = RhtPQMap.create<Primitive>()
        rhtpqMap["test1"] = Primitive.of("value1", TimeTicket.InitialTimeTicket)
        rhtpqMap["test2"] = Primitive.of("value2", TimeTicket.InitialTimeTicket)
        rhtpqMap["test3"] = Primitive.of("value3", TimeTicket.InitialTimeTicket)
        rhtpqMap["test4"] = Primitive.of("value4", TimeTicket.InitialTimeTicket)
        rhtpqMap["test5"] = Primitive.of("value5", TimeTicket.InitialTimeTicket)
        assertTrue(rhtpqMap["test1"].value == "value1")
        assertTrue(rhtpqMap["test2"].value == "value2")
        assertTrue(rhtpqMap["test3"].value == "value3")
        assertTrue(rhtpqMap["test4"].value == "value4")
        assertTrue(rhtpqMap["test5"].value == "value5")

        val nullObject = rhtpqMap.set(
            "test5",
            Primitive.of("value6", TimeTicket.InitialTimeTicket),
        )
        assertNull(nullObject)

        val removedPrimitive = rhtpqMap.set(
            "test5",
            Primitive.of(
                "value6",
                TimeTicket(1, TimeTicket.MAX_DELIMITER, ActorID.MAX_ACTOR_ID),
            ),
        )
        assertEquals("value5", removedPrimitive?.value)
        assertEquals("value6", rhtpqMap["test5"].value)
    }

    @Test
    fun `Verify the set function on concurrent situations`() {
        val rhtPQMap1 = RhtPQMap.create<Primitive>()
        rhtPQMap1["test1"] = Primitive.of(1, generateTimeTicket(1, 1, "1"))

        (2..3).forEach { index ->
            rhtPQMap1["test1"] = Primitive.of(
                index,
                generateTimeTicket(index.toLong(), index, "2"),
            )
        }

        val rhtPQMap2 = RhtPQMap.create<Primitive>()
        rhtPQMap2["test1"] = Primitive.of(1, generateTimeTicket(1, 1, "1"))
        (3 downTo 2).forEach { index ->
            rhtPQMap2["test1"] = Primitive.of(
                index,
                generateTimeTicket(index.toLong(), index, "3"),
            )
        }

        val value1 = rhtPQMap1["test1"].value as Int
        val value2 = rhtPQMap2["test1"].value as Int

        assertEquals(value1, value2)
    }

    @Test
    fun `Verify the get function`() {
        val rhtpqMap = RhtPQMap.create<Primitive>()
        rhtpqMap["test1"] = Primitive.of("value1", TimeTicket.InitialTimeTicket)
        rhtpqMap["test2"] = Primitive.of("value2", TimeTicket.InitialTimeTicket)
        rhtpqMap["test3"] = Primitive.of("value3", TimeTicket.InitialTimeTicket)
        rhtpqMap["test4"] = Primitive.of("value4", TimeTicket.InitialTimeTicket)
        rhtpqMap["test5"] = Primitive.of("value5", TimeTicket.InitialTimeTicket)

        assertFalse(rhtpqMap["test1"].value == "value2")
        assertTrue(rhtpqMap["test2"].value == "value2")
        assertTrue(rhtpqMap["test2"].value == "value2")

        assertThrows(IllegalStateException::class.java) {
            rhtpqMap["test6"]
        }
    }

    @Test
    fun `Verify the delete function`() {
        val rhtpqMap = RhtPQMap.create<Primitive>()
        val primitive1 = Primitive.of("value1", TimeTicket.InitialTimeTicket)
        rhtpqMap["test1"] = primitive1
        val removedPrimitive =
            rhtpqMap.delete(TimeTicket.InitialTimeTicket, TimeTicket.InitialTimeTicket)
        assertEquals(primitive1, removedPrimitive)

        assertThrows(NoSuchElementException::class.java) {
            rhtpqMap.delete(
                generateTimeTicket(99, 99, "3"),
                generateTimeTicket(100, 100, "3"),
            )
        }
        assertThrows(NoSuchElementException::class.java) {
            rhtpqMap.delete(
                generateTimeTicket(101, 101, "4"),
                TimeTicket.InitialTimeTicket,
            )
        }
    }

    @Test
    fun `Verify the deleteByKey function`() {
        val rhtpqMap = RhtPQMap.create<Primitive>()
        val primitive = Primitive.of("value1", TimeTicket.InitialTimeTicket)
        rhtpqMap["test1"] = primitive

        assertThrows(IllegalStateException::class.java) {
            rhtpqMap.deleteByKey("", TimeTicket.InitialTimeTicket)
        }

        val timeTicketForDeletion = generateTimeTicket(1, 1, "0")
        val removedPrimitive = rhtpqMap.deleteByKey("test1", timeTicketForDeletion)
        assertEquals(primitive, removedPrimitive)
        assertEquals(timeTicketForDeletion, rhtpqMap["test1"].removedAt)
    }

    @Test
    fun `Verify the keyOf function`() {
        val timeTicket = generateTimeTicket(0, 0, "0")
        val rhtpqMap = RhtPQMap.create<Primitive>()
        val primitive = Primitive.of("value1", timeTicket)
        rhtpqMap["test1"] = primitive
        val primitiveByKeyOf = rhtpqMap.subPathOf(timeTicket)
        assertEquals(primitive, rhtpqMap[primitiveByKeyOf])

        assertThrows(NoSuchElementException::class.java) {
            rhtpqMap.subPathOf(generateTimeTicket(1, 1, "11"))
        }
    }

    @Test
    fun `Verify the purge function`() {
        val rhtpqMap = RhtPQMap.create<Primitive>()

        val ticket1 = generateTimeTicket(0, 0, "11")
        val primitive1 = Primitive.of("value1", ticket1)
        rhtpqMap["test1"] = primitive1

        val ticket2 = generateTimeTicket(1, 1, "11")
        val primitive2 = Primitive.of("value2", ticket2)
        rhtpqMap["test2"] = primitive2

        val ticket3 = generateTimeTicket(2, 2, "11")
        val primitive3 = Primitive.of("value3", ticket3)
        rhtpqMap["test3"] = primitive3

        rhtpqMap.purge(primitive2)
        assertThrows(IllegalStateException::class.java) {
            rhtpqMap["test2"]
        }

        assertNotNull(rhtpqMap["test1"])
        assertNotNull(rhtpqMap["test3"])
    }

    @Test
    fun `Verify the has function`() {
        val rhtpqMap = RhtPQMap.create<Primitive>()
        assertFalse(rhtpqMap.has("test1"))

        val ticket1 = TimeTicket.InitialTimeTicket
        val primitive1 = Primitive.of("value1", ticket1)
        rhtpqMap["test1"] = primitive1
        assertTrue(rhtpqMap.has("test1"))

        rhtpqMap.delete(ticket1, generateTimeTicket(1, 2, "11"))
        assertFalse(rhtpqMap.has("test1"))
    }

    @Test
    fun `Verity the getKeyOfQueue() using sequence`() {
        val rhtpqMap = RhtPQMap.create<Primitive>()
        val list = mutableListOf<String>()
        for (i in 0..100000) {
            val key = "test$i"
            rhtpqMap[key] = Primitive.of("value$i", generateTimeTicket(i.toLong(), i, "11"))
            list.add(key)
        }

        assertTrue(list.size == 100001)

        rhtpqMap.getKeyOfQueue()
            .forEach { rhtpqMapNode ->
                list.remove(rhtpqMapNode.strKey)
            }
        assertTrue(list.isEmpty())
    }

    private fun generateTimeTicket(
        lamport: Long,
        delimiter: Int,
        actorID: String,
    ): TimeTicket {
        return TimeTicket(lamport, delimiter, ActorID(actorID))
    }
}
