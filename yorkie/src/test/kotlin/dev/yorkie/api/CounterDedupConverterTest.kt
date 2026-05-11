package dev.yorkie.api

import dev.yorkie.document.crdt.CrdtCounter
import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.crdt.Hll
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterDedupConverterTest {

    @Test
    fun `dedup counter survives proto round-trip with HLL state`() {
        // given - a dedup counter with some unique actors
        val source = CrdtCounter.createDedup(InitialTimeTicket)
        val unit = CrdtPrimitive(1, InitialTimeTicket)
        listOf("alice", "bob", "carol", "dave").forEach { source.increaseDedup(unit, it) }
        val sourceHll = checkNotNull(source.hllBytes())

        // when - serialize and deserialize through the JsonElement proto
        val pb = source.toPBCounter().counter
        val roundTripped = pb.toCrdtCounter()
        val rtHll = checkNotNull(roundTripped.hllBytes())

        // then - HLL bytes and cardinality match
        assertEquals(CrdtCounter.CounterType.IntDedup, roundTripped.type)
        assertEquals(source.value, roundTripped.value)
        assertArrayEquals(sourceHll, rtHll)
        assertEquals(Hll.HllRegisterCount, rtHll.size)
    }

    @Test
    fun `normal counter proto round-trip leaves hll bytes empty`() {
        // given
        val source = CrdtCounter(42, InitialTimeTicket)

        // when
        val pb = source.toPBCounter().counter
        val roundTripped = pb.toCrdtCounter()

        // then
        assertEquals(CrdtCounter.CounterType.Int, roundTripped.type)
        assertEquals(42, roundTripped.value)
        // No HLL state for non-dedup counters.
        assertTrue(pb.hllRegisters.isEmpty)
        assertNotNull(roundTripped) // sanity
    }
}
