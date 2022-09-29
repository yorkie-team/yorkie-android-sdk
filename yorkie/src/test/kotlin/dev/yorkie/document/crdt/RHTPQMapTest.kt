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
import kotlin.random.Random

class RHTPQMapTest {
    @Test
    fun `Verify the set function`() {
        val rhtpqMap = RHTPQMap()
        rhtpqMap.set("test1", Primitive("value1", TimeTicket.InitialTimeTicket))
        rhtpqMap.set("test2", Primitive("value2", TimeTicket.InitialTimeTicket))
        rhtpqMap.set("test3", Primitive("value3", TimeTicket.InitialTimeTicket))
        rhtpqMap.set("test4", Primitive("value4", TimeTicket.InitialTimeTicket))
        rhtpqMap.set("test5", Primitive("value5", TimeTicket.InitialTimeTicket))
        assertTrue((rhtpqMap.get("test1") as? Primitive)?.value == "value1")
        assertTrue((rhtpqMap.get("test2") as? Primitive)?.value == "value2")
        assertTrue((rhtpqMap.get("test3") as? Primitive)?.value == "value3")
        assertTrue((rhtpqMap.get("test4") as? Primitive)?.value == "value4")
        assertTrue((rhtpqMap.get("test5") as? Primitive)?.value == "value5")

        val nullObject = rhtpqMap.set(
            "test5",
            Primitive("value6", TimeTicket.InitialTimeTicket),
        )
        assertNull(nullObject)

        val removedPrimitive = rhtpqMap.set(
            "test5",
            Primitive(
                "value6",
                TimeTicket(1, TimeTicket.MAX_DELIMITER, ActorID.MAX_ACTOR_ID),
            ),
        )
        assertTrue((removedPrimitive as? Primitive)?.value == "value5")
        assertTrue((rhtpqMap.get("test5") as? Primitive)?.value == "value6")
    }

    @Test
    fun `Verify the get function`() {
        val rhtpqMap = RHTPQMap()
        rhtpqMap.set("test1", Primitive("value1", TimeTicket.InitialTimeTicket))
        rhtpqMap.set("test2", Primitive("value2", TimeTicket.InitialTimeTicket))
        rhtpqMap.set("test3", Primitive("value3", TimeTicket.InitialTimeTicket))
        rhtpqMap.set("test4", Primitive("value4", TimeTicket.InitialTimeTicket))
        rhtpqMap.set("test5", Primitive("value5", TimeTicket.InitialTimeTicket))

        assertFalse((rhtpqMap.get("test1") as? Primitive)?.value == "value2")
        assertTrue((rhtpqMap.get("test2") as? Primitive)?.value == "value2")
        assertTrue((rhtpqMap.get("test2") as? Primitive)?.value == "value2")

        assertThrows(IllegalStateException::class.java) {
            rhtpqMap.get("test6")
        }
    }

    @Test
    fun `Verify the delete function`() {
        val rhtpqMap = RHTPQMap()
        val primitive1 = Primitive("value1", TimeTicket.InitialTimeTicket)
        rhtpqMap.set("test1", primitive1)
        val removedPrimitive =
            rhtpqMap.delete(TimeTicket.InitialTimeTicket, TimeTicket.InitialTimeTicket) as Primitive
        assertEquals(primitive1, removedPrimitive)

        assertThrows(IllegalStateException::class.java) {
            rhtpqMap.delete(generateRandomTimeTicket(), generateRandomTimeTicket())
            rhtpqMap.delete(generateRandomTimeTicket(), TimeTicket.InitialTimeTicket)
        }
    }

    @Test
    fun `Verify the deleteByKey function`() {
        val rhtpqMap = RHTPQMap()
        val primitive = Primitive("value1", TimeTicket.InitialTimeTicket)
        rhtpqMap.set("test1", primitive)

        assertThrows(IllegalStateException::class.java) {
            rhtpqMap.deleteByKey("", TimeTicket.InitialTimeTicket)
        }

        val timeTicketForDeletion = generateRandomTimeTicket()
        val removedPrimitive = rhtpqMap.deleteByKey("test1", timeTicketForDeletion)
        assertEquals(primitive, removedPrimitive)

        rhtpqMap.get("test1").removedAt?.let {
            assertEquals(it, timeTicketForDeletion)
        } ?: kotlin.run {
            throw IllegalStateException("removedAt should not be null after deleteByKey()")
        }
    }

    @Test
    fun `Verify the keyOf function`() {
        val timeTicket = generateRandomTimeTicket()
        val rhtpqMap = RHTPQMap()
        val primitive = Primitive("value1", timeTicket)
        rhtpqMap.set("test1", primitive)
        val primitiveByKeyOf = rhtpqMap.keyOf(timeTicket)
        assertEquals(primitive, rhtpqMap.get(primitiveByKeyOf))

        assertThrows(IllegalStateException::class.java) {
            rhtpqMap.keyOf(generateRandomTimeTicket())
        }
    }

    @Test
    fun `Verify the purge function`() {
        val rhtpqMap = RHTPQMap()

        val ticket1 = generateRandomTimeTicket()
        val primitive1 = Primitive("value1", ticket1)
        rhtpqMap.set("test1", primitive1)

        val ticket2 = generateRandomTimeTicket()
        val primitive2 = Primitive("value2", ticket2)
        rhtpqMap.set("test2", primitive2)

        val ticket3 = generateRandomTimeTicket()
        val primitive3 = Primitive("value3", ticket3)
        rhtpqMap.set("test3", primitive3)

        rhtpqMap.purge(primitive2)
        assertThrows(IllegalStateException::class.java) {
            rhtpqMap.get("test2")
        }

        assertNotNull(rhtpqMap.get("test1"))
        assertNotNull(rhtpqMap.get("test3"))
    }

    @Test
    fun `Verify the has function`() {
        val rhtpqMap = RHTPQMap()
        assertFalse(rhtpqMap.has("test1"))

        val ticket1 = TimeTicket.InitialTimeTicket
        val primitive1 = Primitive("value1", ticket1)
        rhtpqMap.set("test1", primitive1)
        assertTrue(rhtpqMap.has("test1"))

        val ticket2 = generateRandomTimeTicket()
        val primitive2 = Primitive("value2", ticket2)
        rhtpqMap.set("test2", primitive2)

        val ticket3 = generateRandomTimeTicket()
        val primitive3 = Primitive("value3", ticket3)
        rhtpqMap.set("test3", primitive3)

        rhtpqMap.delete(ticket1, generateRandomTimeTicket())
        assertFalse(rhtpqMap.has("test1"))
    }

    @Test
    fun `Verity the getKeyOfQueue() using sequence`() {
        val rhtpqMap = RHTPQMap()
        val list = mutableListOf<String>()
        for (i in 0..100000) {
            val key = "test$i"
            rhtpqMap.set(key, Primitive("value$i", generateRandomTimeTicket()))
            list.add(key)
        }

        assertTrue(list.size == 100001)

        rhtpqMap.getKeyOfQueue()
            .forEach { rhtpqMapNode ->
                list.remove(rhtpqMapNode.strKey)
            }
        assertTrue(list.isEmpty())
    }

    private fun generateRandomTimeTicket(): TimeTicket {
        return TimeTicket(
            Random.nextLong(),
            Random.nextInt(),
            ActorID(Random.nextInt().toString()),
        )
    }
}
