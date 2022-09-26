package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test

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
    }
}
