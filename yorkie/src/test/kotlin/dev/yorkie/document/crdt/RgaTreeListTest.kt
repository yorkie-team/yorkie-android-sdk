package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RgaTreeListTest {
    private lateinit var target: RgaTreeList
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
        target = RgaTreeList()
    }

    @Test
    fun `should handle insert operations`() {
        assertEquals(0, target.length)

        target.insert(crdtElements[0])
        assertEquals("A1", target.toTestString())
        target.insert(crdtElements[1])
        assertEquals("A1B1", target.toTestString())
        target.insert(crdtElements[2])
        assertEquals("A1B1C1", target.toTestString())
        target.insert(crdtElements[3])
        assertEquals("A1B1C1D1", target.toTestString())
        target.insert(crdtElements[4])
        assertEquals("A1B1C1D1E1", target.toTestString())
        target.insert(crdtElements[5])
        assertEquals("A1B1C1D1E1F1", target.toTestString())
        target.insert(crdtElements[6])
        assertEquals("A1B1C1D1E1F1G1", target.toTestString())

        assertEquals(crdtElements.size, target.length)
    }

    @Test
    fun `should handle remove operations`() {
        assertThrows(YorkieException::class.java) {
            target.delete(timeTickets[0], timeTickets[1])
        }

        assertEquals(0, target.length)

        crdtElements.forEach(target::insert)

        target.delete(timeTickets[1], timeTickets[2])
        assertEquals("A1B0C1D1E1F1G1", target.toTestString())
        target.delete(timeTickets[2], timeTickets[3])
        assertEquals("A1B0C0D1E1F1G1", target.toTestString())
        target.delete(timeTickets[3], timeTickets[4])
        assertEquals("A1B0C0D0E1F1G1", target.toTestString())
        target.delete(timeTickets[6], timeTickets[5])
        assertEquals("A1B0C0D0E1F1G1", target.toTestString())

        assertEquals(crdtElements.size - 3, target.length)
    }

    @Test
    fun `should handle removeByIndex operations`() {
        assertEquals(0, target.length)

        crdtElements.forEach(target::insert)

        target.removeByIndex(1, timeTickets[2])
        assertEquals("A1B0C1D1E1F1G1", target.toTestString())
        target.removeByIndex(1, timeTickets[3])
        assertEquals("A1B0C0D1E1F1G1", target.toTestString())
        target.removeByIndex(1, timeTickets[4])
        assertEquals("A1B0C0D0E1F1G1", target.toTestString())
        target.removeByIndex(1, timeTickets[5])
        assertEquals("A1B0C0D0E0F1G1", target.toTestString())
        target.removeByIndex(crdtElements.size, timeTickets[6])
        assertEquals("A1B0C0D0E0F1G1", target.toTestString())

        assertEquals(crdtElements.size - 4, target.length)
    }

    @Test
    fun `should handle delete operations`() {
        assertThrows(YorkieException::class.java) {
            target.purge(crdtElements[0])
        }

        assertEquals(0, target.length)

        crdtElements.forEach(target::insert)
        assertEquals(crdtElements.size, target.length)

        target.purge(crdtElements[0])
        assertEquals(crdtElements.size - 1, target.length)
        assertEquals("B1C1D1E1F1G1", target.toTestString())
        target.purge(crdtElements[1])
        assertEquals(crdtElements.size - 2, target.length)
        assertEquals("C1D1E1F1G1", target.toTestString())
        target.purge(crdtElements[2])
        assertEquals(crdtElements.size - 3, target.length)
        assertEquals("D1E1F1G1", target.toTestString())
    }

    @Test
    fun `should handle insertion after the given element`() {
        assertEquals(0, target.length)

        crdtElements.forEach(target::insert)
        assertEquals(crdtElements.size, target.length)

        target.insertAfter(timeTickets[0], CrdtPrimitive(1, createTimeTicket()))
        assertEquals("A1H1B1C1D1E1F1G1", target.toTestString())
    }

    @Test
    fun `should handle moving an element after the given element`() {
        assertThrows(YorkieException::class.java) {
            target.moveAfter(timeTickets[0], timeTickets[1], timeTickets[1])
        }

        crdtElements.forEach(target::insert)

        target.moveAfter(timeTickets[5], timeTickets[4], createTimeTicket())
        assertEquals("A1B1C1D1F1E1G1", target.toTestString())
        assertEquals(crdtElements.size, target.length)
    }

    @Test
    fun `should handle concurrent insertAfter operations`() {
        val sampleTarget1 = RgaTreeList().apply { insert(crdtElements[0]) }
        val sampleTarget2 = RgaTreeList().apply { insert(crdtElements[0]) }
        assertEquals(sampleTarget1.toTestString(), sampleTarget2.toTestString())

        sampleTarget1.insertAfter(timeTickets[0], crdtElements[1])
        sampleTarget1.insertAfter(timeTickets[0], crdtElements[2])
        sampleTarget2.insertAfter(timeTickets[0], crdtElements[2])
        sampleTarget2.insertAfter(timeTickets[0], crdtElements[1])
        assertEquals(sampleTarget1.toTestString(), sampleTarget2.toTestString())
    }

    @Test
    fun `should handle concurrent moveAfter operations`() {
        val sampleTarget1 = RgaTreeList().apply { crdtElements.forEach(this::insert) }
        val sampleTarget2 = RgaTreeList().apply {
            timeTickets.map { CrdtPrimitive(1, it) }.forEach(this::insert)
        }
        assertEquals(sampleTarget1.toTestString(), sampleTarget2.toTestString())

        sampleTarget1.moveAfter(timeTickets[3], timeTickets[1], timeTickets[1])
        sampleTarget1.moveAfter(timeTickets[3], timeTickets[2], timeTickets[2])
        sampleTarget2.moveAfter(timeTickets[3], timeTickets[2], timeTickets[2])
        sampleTarget2.moveAfter(timeTickets[3], timeTickets[1], timeTickets[1])
        assertEquals(sampleTarget1.toTestString(), sampleTarget2.toTestString())
    }

    @Test
    fun `should return null when key element does not exist`() {
        crdtElements.forEach(target::insert)

        val newTimeTicket = createTimeTicket()
        assertEquals(null, target.get(newTimeTicket))
        assertNotSame(null, target.get(timeTickets[0]))
    }

    // Ported from yorkie-js-sdk v0.7.6 (#1227): concurrent moves of the same element
    // resolve by LWW on the position; the later executedAt wins and the earlier one loses.
    @Test
    fun `should keep the later move on concurrent moves of the same element`() {
        // given — list [A, B, C, D]
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        val c = CrdtPrimitive("C", tick(3))
        val d = CrdtPrimitive("D", tick(4))
        listOf(a, b, c, d).forEach(list::insert)
        assertEquals(4, list.length)

        // when — op2 (lamport 6): move A after D → [B, C, D, A]
        val dead2 = list.moveAfter(d.createdAt, a.createdAt, tick(6))

        // then — op2 wins and returns a dead node for the old position
        assertNotNull(dead2)
        assertEquals(4, list.length)
        assertEquals("A", valueAt(list, 3))

        // when — op1 (lamport 5 < 6): move A after C → loses LWW
        val dead1 = list.moveAfter(c.createdAt, a.createdAt, tick(5))

        // then — op1 loses but still creates a bare dead position node; list unchanged
        assertNotNull(dead1)
        assertNotNull(dead1?.positionRemovedAt)
        assertNull(dead1?.elementEntry)
        assertEquals(4, list.length)
        assertEquals("A", valueAt(list, 3))
    }

    // Ported from yorkie-js-sdk v0.7.6 (#1227): concurrent moves of different elements commute.
    @Test
    fun `should commute concurrent moves of different elements`() {
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        val c = CrdtPrimitive("C", tick(3))
        val d = CrdtPrimitive("D", tick(4))
        listOf(a, b, c, d).forEach(list::insert)

        // op1 (lamport 5): move A after B → [B, A, C, D]
        list.moveAfter(b.createdAt, a.createdAt, tick(5))
        // op2 (lamport 6): move C after D → [B, A, D, C]
        list.moveAfter(d.createdAt, c.createdAt, tick(6))

        assertEquals(4, list.length)
        assertEquals("B", valueAt(list, 0))
        assertEquals("A", valueAt(list, 1))
        assertEquals("D", valueAt(list, 2))
        assertEquals("C", valueAt(list, 3))
    }

    // Ported from yorkie-js-sdk v0.7.6 (#1227): moveAfter returns a dead position node for GC
    // whether it wins or loses the LWW race.
    @Test
    fun `should return a dead position node on both LWW win and loss`() {
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        val c = CrdtPrimitive("C", tick(3))
        listOf(a, b, c).forEach(list::insert)

        // win: move A after C (lamport 5)
        val winnerDead = list.moveAfter(c.createdAt, a.createdAt, tick(5))
        assertNotNull(winnerDead)
        assertNotNull(winnerDead?.positionRemovedAt)
        assertNull(winnerDead?.elementEntry)
        assertEquals(3, list.length)

        // loss: move A again with an older executedAt (lamport 4)
        val loserDead = list.moveAfter(b.createdAt, a.createdAt, tick(4))
        assertNotNull(loserDead)
        assertNotNull(loserDead?.positionRemovedAt)
        assertNull(loserDead?.elementEntry)
    }

    @Test
    fun `should throw when the insertAfter anchor does not exist`() {
        target.insert(crdtElements[0])

        assertThrows(YorkieException::class.java) {
            target.insertAfter(createTimeTicket(), crdtElements[1])
        }
    }

    @Test
    fun `should resolve the insertAfter anchor by element key after its position was purged`() {
        // given — [A, B]; move A after B so A's original position becomes a dead node
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        listOf(a, b).forEach(list::insert)
        val dead = requireNotNull(list.moveAfter(b.createdAt, a.createdAt, tick(3)))

        // when — purge the dead position node and anchor an insert on A's element key
        list.delete(dead)
        list.insertAfter(a.createdAt, CrdtPrimitive("C", tick(4)))

        // then — the anchor resolves to A's current position: [B, A, C]
        assertEquals("B", valueAt(list, 0))
        assertEquals("A", valueAt(list, 1))
        assertEquals("C", valueAt(list, 2))
    }

    @Test
    fun `should throw when the moveAfter previous node does not exist`() {
        target.insert(crdtElements[0])
        target.insert(crdtElements[1])

        assertThrows(YorkieException::class.java) {
            target.moveAfter(createTimeTicket(), timeTickets[0], createTimeTicket())
        }
    }

    @Test
    fun `should ignore a duplicate moveAfter with the same executedAt`() {
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        listOf(a, b).forEach(list::insert)

        val dead = list.moveAfter(b.createdAt, a.createdAt, tick(3))
        val duplicate = list.moveAfter(b.createdAt, a.createdAt, tick(3))

        assertNotNull(dead)
        assertNull(duplicate)
        assertEquals("B", valueAt(list, 0))
        assertEquals("A", valueAt(list, 1))
    }

    @Test
    fun `should apply a later move over an earlier applied move`() {
        // given — [A, B, C]; first move A after B
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        val c = CrdtPrimitive("C", tick(3))
        listOf(a, b, c).forEach(list::insert)
        list.moveAfter(b.createdAt, a.createdAt, tick(4))

        // when — a later move of the same element (executedAt > posMovedAt) wins
        val dead = list.moveAfter(c.createdAt, a.createdAt, tick(5))

        // then — [B, C, A] and the first move's position is returned dead
        assertNotNull(dead)
        assertEquals("B", valueAt(list, 0))
        assertEquals("C", valueAt(list, 1))
        assertEquals("A", valueAt(list, 2))
    }

    @Test
    fun `should purge a dead position node only once`() {
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        listOf(a, b).forEach(list::insert)
        val dead = requireNotNull(list.moveAfter(b.createdAt, a.createdAt, tick(3)))
        assertEquals(3, list.allNodes().size)

        list.delete(dead)
        assertEquals(2, list.allNodes().size)

        // purging the same position again is a no-op
        list.delete(dead)
        assertEquals(2, list.allNodes().size)
    }

    @Test
    fun `should skip dead positions and removed elements in getPrevCreatedAt`() {
        // given — [A, B, C]; move B after C leaves a dead node between A and C
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        val c = CrdtPrimitive("C", tick(3))
        listOf(a, b, c).forEach(list::insert)
        list.moveAfter(c.createdAt, b.createdAt, tick(4))

        // then — C's previous skips the dead position node back to A
        assertEquals(a.createdAt, list.getPrevCreatedAt(c.createdAt))

        // and — removing A makes C's previous the dummy head
        list.delete(a.createdAt, tick(5))
        assertEquals(TimeTicket.InitialTimeTicket, list.getPrevCreatedAt(c.createdAt))
    }

    @Test
    fun `should throw from subPathOf for an unknown element`() {
        assertThrows(NoSuchElementException::class.java) {
            target.subPathOf(createTimeTicket())
        }
    }

    @Test
    fun `should replace an element with set`() {
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        listOf(a, b).forEach(list::insert)

        val removed = list.set(a.createdAt, CrdtPrimitive("A2", tick(3)), tick(3))

        assertEquals(a, removed)
        assertEquals(2, list.length)
        assertEquals("A2", valueAt(list, 0))
        assertEquals("B", valueAt(list, 1))

        assertThrows(YorkieException::class.java) {
            list.set(tick(99), CrdtPrimitive("X", tick(100)), tick(100))
        }
    }

    @Test
    fun `should compare lists by their element entries`() {
        val list = RgaTreeList().apply { insert(CrdtPrimitive("A", tick(1))) }

        assertEquals(list, list)
        assertNotEquals(list, "not a list")
        assertEquals(list.hashCode(), list.hashCode())
        assertEquals("", RgaTreeList().toTestString())
    }

    @Test
    fun `should count the position removal timestamp in the data size of a dead node`() {
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        listOf(a, b).forEach(list::insert)

        val live = requireNotNull(list.getByIndex(0))
        assertEquals(TimeTicket.TIME_TICKET_SIZE, live.dataSize.meta)

        val dead = requireNotNull(list.moveAfter(b.createdAt, a.createdAt, tick(3)))
        assertEquals(TimeTicket.TIME_TICKET_SIZE * 2, dead.dataSize.meta)
    }

    // Ported from yorkie-js-sdk v0.7.11 (#1272): the last-element accessor must skip
    // bare position nodes left behind by moves.
    @Test
    fun `lastElement should skip bare position node left after moving the last element`() {
        // given — list [A, B, C]
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        val c = CrdtPrimitive("C", tick(3))
        listOf(a, b, c).forEach(list::insert)

        // when — move the current last element (C) after A; its old tail slot
        // becomes a bare node and `last` keeps pointing at it
        list.moveAfter(a.createdAt, c.createdAt, tick(5))

        // then — lastElement resolves the last live element (B), not the stale slot
        assertEquals("B", (list.lastElement as CrdtPrimitive).value)
    }

    @Test
    fun `lastElement should skip consecutive trailing bare position nodes`() {
        // given — list [A, B, C]
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        val c = CrdtPrimitive("C", tick(3))
        listOf(a, b, c).forEach(list::insert)

        // when — two moves leave two consecutive bare nodes at the tail
        list.moveAfter(a.createdAt, c.createdAt, tick(5))
        list.moveAfter(a.createdAt, b.createdAt, tick(6))

        // then
        assertEquals("C", (list.lastElement as CrdtPrimitive).value)
    }

    @Test
    fun `lastElement should fall back to dummy head on empty list`() {
        val list = RgaTreeList()

        assertEquals(list.head, list.lastElement)
    }

    // TreeList.indexOf returns -1 for tombstones — deliberate JS-parity behavior change
    // from the SplayTreeSet migration (v0.7.11 #1269).
    @Test
    fun `subPathOf should return -1 for a removed element`() {
        // given — list [A, B]
        val list = RgaTreeList()
        val a = CrdtPrimitive("A", tick(1))
        val b = CrdtPrimitive("B", tick(2))
        listOf(a, b).forEach(list::insert)

        // when — tombstone B (not purged yet)
        list.delete(b.createdAt, tick(3))

        // then
        assertEquals("0", list.subPathOf(a.createdAt))
        assertEquals("-1", list.subPathOf(b.createdAt))
    }

    private fun tick(lamport: Long) = TimeTicket(lamport, TimeTicket.INITIAL_DELIMITER, "x")

    private fun valueAt(list: RgaTreeList, index: Int): Any? =
        (list.getByIndex(index)?.value as? CrdtPrimitive)?.value

    private fun createTimeTicket(): TimeTicket {
        return TimeTicket(crdtElements.size.toLong(), TimeTicket.INITIAL_DELIMITER, "H")
    }

    private fun RgaTreeList.toTestString() = buildString {
        this@toTestString.forEach {
            append("${it.createdAt.actorID}${if (it.isRemoved) 0 else 1}")
        }
    }
}
