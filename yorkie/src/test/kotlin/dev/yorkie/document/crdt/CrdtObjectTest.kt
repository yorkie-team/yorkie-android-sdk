package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrdtObjectTest {
    private lateinit var target: CrdtObject
    private val actorIDs = listOf("A", "B", "C", "D", "E")
    private val timeTickets = actorIDs.map {
        TimeTicket(
            actorIDs.indexOf(it).toLong(),
            TimeTicket.INITIAL_DELIMITER,
            ActorID(it),
        )
    }
    private val crdtElements = (0..4).map { Primitive.of(it, timeTickets[it]) }

    @Before
    fun setUp() {
        target = CrdtObject.create(TimeTicket.InitialTimeTicket)
    }

    @Test
    fun `should handle set operations`() {
        assertEquals(0, target.keys.size)

        target["A"] = Primitive.of(0, timeTickets[0])
        assertEquals("A0", getStructureAsString())
        target["B"] = Primitive.of(1, timeTickets[1])
        assertEquals("A0B1", getStructureAsString())
        target["C"] = Primitive.of(2, timeTickets[2])
        assertEquals("A0B1C2", getStructureAsString())
        target["D"] = Primitive.of(3, timeTickets[3])
        assertEquals("A0B1C2D3", getStructureAsString())
        target["E"] = Primitive.of(4, timeTickets[4])
        assertEquals("A0B1C2D3E4", getStructureAsString())
    }

    @Test
    fun `should handle get operations`() {
        setTargetSampleValues()

        assertEquals("A", target["A"].createdAt.actorID.id)
        assertEquals("B", target["B"].createdAt.actorID.id)
        assertEquals("C", target["C"].createdAt.actorID.id)
        assertEquals("D", target["D"].createdAt.actorID.id)
        assertEquals("E", target["E"].createdAt.actorID.id)
    }

    @Test
    fun `should handle delete operations`() {
        setTargetSampleValues()
        assertEquals(5, target.rht.size)

        target.delete(crdtElements[0])
        assertEquals("B1C2D3E4", getStructureAsString())
        target.delete(crdtElements[3])
        assertEquals("B1C2E4", getStructureAsString())
        target.delete(Primitive.of(100, TimeTicket.InitialTimeTicket))
    }

    @Test
    fun `should handle remove operations`() {
        setTargetSampleValues()
        assertEquals(5, target.rht.size)

        target.remove(timeTickets[0], timeTickets[1])
        assertEquals("B1C2D3E4", getStructureAsString())
        target.remove(timeTickets[2], timeTickets[1])
        assertEquals("B1C2D3E4", getStructureAsString())
        assertThrows(NoSuchElementException::class.java) {
            target.remove(TimeTicket.InitialTimeTicket, TimeTicket.InitialTimeTicket)
        }
    }

    @Test
    fun `should handle removeByKey operations`() {
        setTargetSampleValues()
        assertEquals(5, target.rht.size)

        target.removeByKey(actorIDs[0], timeTickets[1])
        assertEquals("B1C2D3E4", getStructureAsString())
        target.removeByKey(actorIDs[2], timeTickets[1])
        assertEquals("B1C2D3E4", getStructureAsString())
        assertThrows(IllegalStateException::class.java) {
            target.removeByKey("F", TimeTicket.InitialTimeTicket)
        }
    }

    @Test
    fun `should handle subPathOf operations`() {
        setTargetSampleValues()

        assertEquals("A", target.subPathOf(timeTickets[0]))
        assertEquals("B", target.subPathOf(timeTickets[1]))
        assertEquals("C", target.subPathOf(timeTickets[2]))
        assertEquals("D", target.subPathOf(timeTickets[3]))
        assertEquals("E", target.subPathOf(timeTickets[4]))
        assertThrows(NoSuchElementException::class.java) {
            target.subPathOf(TimeTicket.InitialTimeTicket)
        }
    }

    @Test
    fun `should check if a key exists`() {
        setTargetSampleValues()

        assertTrue(target.has("A"))
        assertTrue(target.has("B"))
        assertTrue(target.has("C"))
        assertTrue(target.has("D"))
        assertTrue(target.has("E"))
        assertFalse(target.has("F"))
    }

    private fun setTargetSampleValues() {
        actorIDs.forEachIndexed { index, key ->
            target[key] = crdtElements[index]
        }
    }

    private fun getStructureAsString() = buildString {
        target.forEach { append(it.first + (it.second as Primitive).value) }
    }
}
