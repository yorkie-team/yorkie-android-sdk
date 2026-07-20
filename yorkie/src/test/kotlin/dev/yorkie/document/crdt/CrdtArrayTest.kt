package dev.yorkie.document.crdt

import dev.yorkie.api.toCrdtArray
import dev.yorkie.api.toPBJsonArray
import dev.yorkie.api.toPBTimeTicket
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
            lamport = actorIDs.indexOf(it).toLong(),
            delimiter = TimeTicket.INITIAL_DELIMITER,
            actorID = it,
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

        target.delete(timeTickets[1], timeTickets[2])
        assertEquals("ACDEFG", target.toTestString())
        target.delete(timeTickets[2], timeTickets[3])
        assertEquals("ADEFG", target.toTestString())
        target.delete(timeTickets[3], timeTickets[4])
        assertEquals("AEFG", target.toTestString())
        target.delete(timeTickets[6], timeTickets[5])
        assertEquals("AEFG", target.toTestString())

        assertEquals(crdtElements.size - 3, target.length)
    }

    @Test
    fun `should handle delete operations`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.purge(crdtElements[0])
        assertEquals(crdtElements.size - 1, target.length)
        assertEquals("BCDEFG", target.toTestString())
        target.purge(crdtElements[1])
        assertEquals(crdtElements.size - 2, target.length)
        assertEquals("CDEFG", target.toTestString())
        target.purge(crdtElements[2])
        assertEquals(crdtElements.size - 3, target.length)
        assertEquals("DEFG", target.toTestString())
    }

    @Test
    fun `should handle removeByIndex operations`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.removeByIndex(0, timeTickets[2])
        assertEquals("BCDEFG", target.toTestString())
        target.removeByIndex(0, timeTickets[3])
        assertEquals("CDEFG", target.toTestString())
        target.removeByIndex(0, timeTickets[4])
        assertEquals("DEFG", target.toTestString())
        target.removeByIndex(0, timeTickets[5])
        assertEquals("EFG", target.toTestString())
        target.removeByIndex(crdtElements.size, timeTickets[6])
        assertEquals("EFG", target.toTestString())

        assertEquals(crdtElements.size - 4, target.length)
    }

    @Test
    fun `should handle insertion after the given element`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.insertAfter(timeTickets[0], CrdtPrimitive(1, createTimeTicket()))
        assertEquals("AHBCDEFG", target.toTestString())
    }

    @Test
    fun `should handle moving an element after the given element`() {
        assertEquals(0, target.length)

        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        assertEquals(crdtElements.size, target.length)

        target.moveAfter(timeTickets[5], timeTickets[4], createTimeTicket())
        assertEquals("ABCDFEG", target.toTestString())
        assertEquals(crdtElements.size, target.length)
    }

    // Ported from yorkie-js-sdk v0.7.11 (#1272): `last` must skip the bare position node
    // left at the tail after moving the last element, including through deepCopy.
    @Test
    fun `last should skip bare position nodes and survive deepCopy`() {
        // given
        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }

        // when — move the current last element (G) after A; its old tail slot becomes bare
        target.moveAfter(timeTickets[0], timeTickets[6], createTimeTicket())

        // then — last resolves to F, the last live element
        assertEquals(timeTickets[5], target.last.createdAt)

        // and the reconstructed clone (which restores the bare tail node) agrees
        val copied = target.deepCopy() as CrdtArray
        assertEquals(timeTickets[5], copied.last.createdAt)
    }

    @Test
    fun `should handle concurrent insertAfter operations`() {
        val sampleTarget1 = createSampleCrdtArray().apply {
            insertAfter(last.createdAt, crdtElements[0])
        }
        val sampleTarget2 = createSampleCrdtArray().apply {
            insertAfter(last.createdAt, crdtElements[0])
        }
        assertEquals(sampleTarget1.toTestString(), sampleTarget2.toTestString())

        sampleTarget1.insertAfter(timeTickets[0], crdtElements[1])
        sampleTarget1.insertAfter(timeTickets[0], crdtElements[2])
        sampleTarget2.insertAfter(timeTickets[0], crdtElements[2])
        sampleTarget2.insertAfter(timeTickets[0], crdtElements[1])
        assertEquals(sampleTarget1.toTestString(), sampleTarget2.toTestString())
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
        assertEquals(sampleTarget1.toTestString(), sampleTarget2.toTestString())

        sampleTarget1.moveAfter(timeTickets[3], timeTickets[1], timeTickets[1])
        sampleTarget1.moveAfter(timeTickets[3], timeTickets[2], timeTickets[2])
        sampleTarget2.moveAfter(timeTickets[3], timeTickets[2], timeTickets[2])
        sampleTarget2.moveAfter(timeTickets[3], timeTickets[1], timeTickets[1])
        assertEquals(sampleTarget1.toTestString(), sampleTarget2.toTestString())
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

    // #1227: deepCopy must preserve moved positions and dead position nodes so a later
    // move on the clone anchors correctly (copying only live nodes would lose them).
    @Test
    fun `should preserve moved and dead positions on deepCopy`() {
        crdtElements.forEach { target.insertAfter(target.last.createdAt, it) }
        // move E after F → [A, B, C, D, F, E, G]
        target.moveAfter(timeTickets[5], timeTickets[4], createTimeTicket())
        assertEquals("ABCDFEG", target.toTestString())

        val copy = target.deepCopy() as CrdtArray

        assertEquals("ABCDFEG", copy.toTestString())
        assertEquals(target.length, copy.length)
        // the moved element keeps its index in the clone
        assertEquals(target.subPathOf(timeTickets[4]), copy.subPathOf(timeTickets[4]))
    }

    // #1227: a move must survive a protobuf round-trip, including the dead position node
    // (serialized with position timestamps and no element). Uses hex actor IDs because the
    // converter encodes the actor ID as a 12-byte hex string.
    @Test
    fun `should preserve moves across a protobuf round-trip`() {
        val actor = "000000000000000000000001"
        fun ticket(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        val array = CrdtArray(ticket(0))
        val e0 = CrdtPrimitive(0, ticket(1))
        val e1 = CrdtPrimitive(1, ticket(2))
        val e2 = CrdtPrimitive(2, ticket(3))
        listOf(e0, e1, e2).forEach { array.insertAfter(array.last.createdAt, it) }
        // move e2 after e0 → [0, 2, 1], leaving a dead position node for e2's old slot
        array.moveAfter(e0.createdAt, e2.createdAt, ticket(5))

        val restored = array.toPBJsonArray().jsonArray.toCrdtArray()

        assertEquals(array.length, restored.length)
        // the moved element keeps its new index (1) after the round-trip
        assertEquals(array.subPathOf(e2.createdAt), restored.subPathOf(e2.createdAt))
        assertEquals(2, (restored[1] as CrdtPrimitive).value)
    }

    // #1227: a node carrying position_created_at without position_moved_at (defensive wire
    // shape) parses as a normal node.
    @Test
    fun `should parse a node with only position_created_at as a normal node`() {
        val actor = "000000000000000000000001"
        fun ticket(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, actor)

        val array = CrdtArray(ticket(0))
        val e0 = CrdtPrimitive(0, ticket(1))
        val e1 = CrdtPrimitive(1, ticket(2))
        listOf(e0, e1).forEach { array.insertAfter(array.last.createdAt, it) }

        val pb = array.toPBJsonArray().jsonArray
        val mutated = pb.toBuilder().setNodes(
            0,
            pb.getNodes(0).toBuilder()
                .setPositionCreatedAt(ticket(1).toPBTimeTicket())
                .build(),
        ).build()
        val restored = mutated.toCrdtArray()

        assertEquals(2, restored.length)
        assertEquals(0, (restored[0] as CrdtPrimitive).value)
        assertEquals(1, (restored[1] as CrdtPrimitive).value)
    }

    private fun createTimeTicket(): TimeTicket {
        return TimeTicket(crdtElements.size.toLong(), TimeTicket.INITIAL_DELIMITER, "H")
    }

    private fun createSampleCrdtArray(): CrdtArray {
        return CrdtArray(TimeTicket.InitialTimeTicket)
    }

    private fun CrdtArray.toTestString() = buildString {
        this@toTestString.forEach { append(it.createdAt.actorID) }
    }
}
