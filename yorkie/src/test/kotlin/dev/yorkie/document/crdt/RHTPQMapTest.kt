package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert
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

        val nullObject = rhtpqMap.set("test5",
            Primitive("value6", TimeTicket.InitialTimeTicket))
        assertNull(nullObject)

        val removedPrimitive = rhtpqMap.set("test5",
            Primitive("value6",
                TimeTicket(1, TimeTicket.MAX_DELIMITER, ActorID.MAX_ACTOR_ID)))
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
        val removedPrimitive = rhtpqMap.delete(TimeTicket.InitialTimeTicket, TimeTicket.InitialTimeTicket) as Primitive
        assertEquals(primitive1, removedPrimitive)

        assertThrows(IllegalStateException::class.java) {
            rhtpqMap.delete(generateRandomTimeTicket(), generateRandomTimeTicket())
            rhtpqMap.delete(generateRandomTimeTicket(), TimeTicket.InitialTimeTicket)
        }
    }

    private fun generateRandomTimeTicket(): TimeTicket {
        return TimeTicket(Random.nextLong(),
            Random.nextInt(), ActorID(Random.nextInt().toString()))
    }

}
