package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CrdtArrayTest {
    private lateinit var target: CrdtArray
    private val actorIDs = listOf("A", "B", "C", "D", "E", "F", "G")
    private val timeTickets = actorIDs.map {
        TimeTicket(
            actorIDs.indexOf(it).toLong(),
            TimeTicket.INITIAL_DELIMITER,
            ActorID(it),
        )
    }
    private val crdtElements = timeTickets.map { CrdtPrimitive(1, it) }

    @Before
    fun setUp() {
        target = createSampleCrdtArray()
    }

    @Test
    fun `should handle remove operations`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.remove(timeTickets[1], timeTickets[2])
        assertEquals("ACDEFG", target.getStructureAsString())
        target.remove(timeTickets[2], timeTickets[3])
        assertEquals("ADEFG", target.getStructureAsString())
        target.remove(timeTickets[3], timeTickets[4])
        assertEquals("AEFG", target.getStructureAsString())
        target.remove(timeTickets[6], timeTickets[5])
        assertEquals("AEFG", target.getStructureAsString())

        assertEquals(crdtElements.size - 3, target.length)
    }

    @Test
    fun `should handle delete operations`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.delete(crdtElements[0])
        assertEquals(crdtElements.size - 1, target.length)
        assertEquals("BCDEFG", target.getStructureAsString())
        target.delete(crdtElements[1])
        assertEquals(crdtElements.size - 2, target.length)
        assertEquals("CDEFG", target.getStructureAsString())
        target.delete(crdtElements[2])
        assertEquals(crdtElements.size - 3, target.length)
        assertEquals("DEFG", target.getStructureAsString())
    }

    @Test
    fun `should handle removeByIndex operations`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.removeByIndex(0, timeTickets[2])
        assertEquals("BCDEFG", target.getStructureAsString())
        target.removeByIndex(0, timeTickets[3])
        assertEquals("CDEFG", target.getStructureAsString())
        target.removeByIndex(0, timeTickets[4])
        assertEquals("DEFG", target.getStructureAsString())
        target.removeByIndex(0, timeTickets[5])
        assertEquals("EFG", target.getStructureAsString())
        target.removeByIndex(crdtElements.size, timeTickets[6])
        assertEquals("EFG", target.getStructureAsString())

        assertEquals(crdtElements.size - 4, target.length)
    }

    @Test
    fun `should handle insertion after the given element`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.insertAfter(timeTickets[0], CrdtPrimitive(1, createTimeTicket()))
        assertEquals("AHBCDEFG", target.getStructureAsString())
    }

    @Test
    fun `should handle moving an element after the given element`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.moveAfter(timeTickets[5], timeTickets[4], createTimeTicket())
        assertEquals("ABCDFEG", target.getStructureAsString())
        assertEquals(crdtElements.size, target.length)
    }

    @Test
    fun `should handle concurrent insertAfter operations`() {
        val sampleTarget1 = createSampleCrdtArray().apply {
            insertAfter(last.createdAt, crdtElements[0])
        }
        val sampleTarget2 = createSampleCrdtArray().apply {
            insertAfter(last.createdAt, crdtElements[0])
        }
        assertEquals(sampleTarget1.getStructureAsString(), sampleTarget2.getStructureAsString())

        sampleTarget1.insertAfter(timeTickets[0], crdtElements[1])
        sampleTarget1.insertAfter(timeTickets[0], crdtElements[2])
        sampleTarget2.insertAfter(timeTickets[0], crdtElements[2])
        sampleTarget2.insertAfter(timeTickets[0], crdtElements[1])
        assertEquals(sampleTarget1.getStructureAsString(), sampleTarget2.getStructureAsString())
    }

    @Test
    fun `should handle concurrent moveAfter operations`() {
        val sampleTarget1 = createSampleCrdtArray().apply {
            crdtElements.forEach { insertAfter(last.createdAt, it) }
        }
        val sampleTarget2 = createSampleCrdtArray().apply {
            timeTickets
                .map { CrdtPrimitive(1, it) }
                .forEach { insertAfter(last.createdAt, it) }
        }
        assertEquals(sampleTarget1.getStructureAsString(), sampleTarget2.getStructureAsString())

        sampleTarget1.moveAfter(timeTickets[3], timeTickets[1], timeTickets[1])
        sampleTarget1.moveAfter(timeTickets[3], timeTickets[2], timeTickets[2])
        sampleTarget2.moveAfter(timeTickets[3], timeTickets[2], timeTickets[2])
        sampleTarget2.moveAfter(timeTickets[3], timeTickets[1], timeTickets[1])
        assertEquals(sampleTarget1.getStructureAsString(), sampleTarget2.getStructureAsString())
    }

    @Test
    fun `should handle get operations with index`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        assertEquals(crdtElements[0], target[0])
        assertEquals(null, target[10])
    }

    @Test
    fun `should handle get operations with value`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        assertEquals(crdtElements[0], target[timeTickets[0]])
        assertEquals(null, target[createTimeTicket()])
    }

    @Test
    fun `should handle getPrevCreatedAt operations`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        assertEquals(timeTickets[0], target.getPrevCreatedAt(timeTickets[1]))
        assertEquals(timeTickets[1], target.getPrevCreatedAt(timeTickets[2]))
        Assert.assertThrows(NoSuchElementException::class.java) {
            target.getPrevCreatedAt(createTimeTicket())
        }
    }

    private fun createTimeTicket(): TimeTicket {
        return TimeTicket(crdtElements.size.toLong(), TimeTicket.INITIAL_DELIMITER, ActorID("H"))
    }

    private fun createSampleCrdtArray(): CrdtArray {
        return CrdtArray(TimeTicket.InitialTimeTicket)
    }

    private fun CrdtArray.getStructureAsString() = buildString {
        this@getStructureAsString.forEach { append(it.createdAt.actorID.value) }
    }
}
