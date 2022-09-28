package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNotSame
import org.junit.Before
import org.junit.Test

class RgaTreeListTest {
    private lateinit var target: RgaTreeList
    private val actorIDs = listOf("A", "B", "C", "D", "E", "F", "G")
    private val timeTickets = actorIDs.map {
        TimeTicket(
            actorIDs.indexOf(it).toLong(),
            TimeTicket.INITIAL_DELIMITER,
            ActorID(it),
        )
    }
    private val crdtElements = timeTickets.map { Primitive(1, it) }

    @Before
    fun setUp() {
        target = RgaTreeList()
    }

    @Test
    fun `should handle insert operations`() {
        assertEquals(1, target.length)

        target.insert(crdtElements[0])
        assertEquals("A1", target.getStructureAsString())
        target.insert(crdtElements[1])
        assertEquals("A1B1", target.getStructureAsString())
        target.insert(crdtElements[2])
        assertEquals("A1B1C1", target.getStructureAsString())
        target.insert(crdtElements[3])
        assertEquals("A1B1C1D1", target.getStructureAsString())
        target.insert(crdtElements[4])
        assertEquals("A1B1C1D1E1", target.getStructureAsString())
        target.insert(crdtElements[5])
        assertEquals("A1B1C1D1E1F1", target.getStructureAsString())
        target.insert(crdtElements[6])
        assertEquals("A1B1C1D1E1F1G1", target.getStructureAsString())

        assertEquals(crdtElements.size + 1, target.length)
    }

    @Test
    fun `should handle delete operations`() {
        assertEquals(1, target.length)

        crdtElements.forEach(target::insert)

        target.delete(timeTickets[1], timeTickets[2])
        assertEquals("A1B0C1D1E1F1G1", target.getStructureAsString())
        target.delete(timeTickets[2], timeTickets[3])
        assertEquals("A1B0C0D1E1F1G1", target.getStructureAsString())
        target.delete(timeTickets[3], timeTickets[4])
        assertEquals("A1B0C0D0E1F1G1", target.getStructureAsString())
        target.delete(timeTickets[6], timeTickets[5])
        assertEquals("A1B0C0D0E1F1G1", target.getStructureAsString())

        assertEquals(crdtElements.size - 3 + 1, target.length)
    }

    @Test
    fun `should handle purge operations`() {
        assertEquals(1, target.length)

        crdtElements.forEach(target::insert)
        assertEquals(crdtElements.size + 1, target.length)

        target.purge(crdtElements[0])
        assertEquals(crdtElements.size - 1 + 1, target.length)
        assertEquals("B1C1D1E1F1G1", target.getStructureAsString())
        target.purge(crdtElements[1])
        assertEquals(crdtElements.size - 2 + 1, target.length)
        assertEquals("C1D1E1F1G1", target.getStructureAsString())
        target.purge(crdtElements[2])
        assertEquals(crdtElements.size - 3 + 1, target.length)
        assertEquals("D1E1F1G1", target.getStructureAsString())
    }

    @Test
    fun `should handle insertion after the given element`() {
        assertEquals(1, target.length)

        crdtElements.forEach(target::insert)
        assertEquals(crdtElements.size + 1, target.length)

        target.insertAfter(timeTickets[0], Primitive(1, createTimeTicket()))
        assertEquals("A1H1B1C1D1E1F1G1", target.getStructureAsString())
    }

    @Test
    fun `should handle moving an element after the given element`() {
        crdtElements.forEach(target::insert)

        target.moveAfter(timeTickets[5], timeTickets[4], createTimeTicket())
        assertEquals("A1B1C1D1F1E1G1", target.getStructureAsString())
        assertEquals(crdtElements.size + 1, target.length)
    }

    @Test
    fun `should handle concurrent insertAfter operations`() {
        val sampleTarget1 = RgaTreeList().apply { insert(crdtElements[0]) }
        val sampleTarget2 = RgaTreeList().apply { insert(crdtElements[0]) }
        assertEquals(sampleTarget1.getStructureAsString(), sampleTarget2.getStructureAsString())

        sampleTarget1.insertAfter(timeTickets[0], crdtElements[1])
        sampleTarget1.insertAfter(timeTickets[0], crdtElements[2])
        sampleTarget2.insertAfter(timeTickets[0], crdtElements[2])
        sampleTarget2.insertAfter(timeTickets[0], crdtElements[1])
        assertEquals(sampleTarget1.getStructureAsString(), sampleTarget2.getStructureAsString())
    }

    @Test
    fun `should handle concurrent moveAfter operations`() {
        val sampleTarget1 = RgaTreeList().apply { crdtElements.forEach(this::insert) }
        val sampleTarget2 = RgaTreeList().apply {
            timeTickets.map { Primitive(1, it) }.forEach(this::insert)
        }
        assertEquals(sampleTarget1.getStructureAsString(), sampleTarget2.getStructureAsString())

        sampleTarget1.moveAfter(timeTickets[3], timeTickets[1], timeTickets[1])
        sampleTarget1.moveAfter(timeTickets[3], timeTickets[2], timeTickets[2])
        sampleTarget2.moveAfter(timeTickets[3], timeTickets[2], timeTickets[2])
        sampleTarget2.moveAfter(timeTickets[3], timeTickets[1], timeTickets[1])
    }

    @Test
    fun `should return null when key element does not exist`() {
        crdtElements.forEach(target::insert)

        val newTimeTicket = createTimeTicket()
        assertEquals(null, target.get(newTimeTicket))
        assertNotSame(null, target.get(timeTickets[0]))
    }

    private fun createTimeTicket(): TimeTicket {
        return TimeTicket(crdtElements.size.toLong(), TimeTicket.INITIAL_DELIMITER, ActorID("H"))
    }

    private fun RgaTreeList.getStructureAsString(): String {
        val nodes = getNodesInListExceptHead()
        return nodes.joinToString("") {
            it.createdAt.actorID.id + if (it.isRemoved) 0 else 1
        }
    }
}
