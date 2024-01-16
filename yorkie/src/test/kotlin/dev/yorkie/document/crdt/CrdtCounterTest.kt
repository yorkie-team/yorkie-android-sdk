package dev.yorkie.document.crdt

import dev.yorkie.document.crdt.CrdtCounter.Companion.asCounterValue
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import java.nio.ByteBuffer
import java.util.Date
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CrdtCounterTest {

    @Test
    fun `verify increasing positive numeric data of Counter works properly`() {
        val int = CrdtCounter(1, InitialTimeTicket)
        val long = CrdtCounter(100L, InitialTimeTicket)

        val intOperand = CrdtPrimitive(1, InitialTimeTicket)
        val longOperand = CrdtPrimitive(100L, InitialTimeTicket)

        int.increase(intOperand)
        int.increase(longOperand)
        assertEquals(102, int.value)

        long.increase(intOperand)
        long.increase(longOperand)
        assertEquals(201L, long.value)
    }

    @Test
    fun `verify increasing with non numeric data type throws error`() {
        val int = CrdtCounter(10, InitialTimeTicket)
        val str = CrdtPrimitive("hello", InitialTimeTicket)
        val bool = CrdtPrimitive(true, InitialTimeTicket)
        val bytes = CrdtPrimitive(ByteArray(1), InitialTimeTicket)
        val date = CrdtPrimitive(Date(), InitialTimeTicket)
        val double = CrdtPrimitive(1.0, InitialTimeTicket)

        assertThrows(IllegalArgumentException::class.java) {
            int.increase(str)
        }
        assertThrows(IllegalArgumentException::class.java) {
            int.increase(bool)
        }
        assertThrows(IllegalArgumentException::class.java) {
            int.increase(bytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            int.increase(date)
        }
        assertThrows(IllegalArgumentException::class.java) {
            int.increase(double)
        }
        assertEquals(10, int.value)
    }

    @Test
    fun `verify increasing with negative numeric data works properly`() {
        val int = CrdtCounter(1, InitialTimeTicket)
        val long = CrdtCounter(100L, InitialTimeTicket)

        val intOperand = CrdtPrimitive(-1, InitialTimeTicket)
        val longOperand = CrdtPrimitive(-100L, InitialTimeTicket)

        int.increase(intOperand)
        int.increase(longOperand)
        assertEquals(-100, int.value)

        long.increase(intOperand)
        long.increase(longOperand)
        assertEquals(-1L, long.value)
    }

    @Test
    fun `verify converting bytes to counter and vice-versa works properly`() {
        val intInBytes = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(39).array()
        val intValue = intInBytes.asCounterValue(CrdtCounter.CounterType.IntegerCnt)
        assertEquals(39, intValue)
        assertArrayEquals(intInBytes, CrdtCounter(39, InitialTimeTicket).toBytes())

        val longInBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(333L).array()
        val longValue = longInBytes.asCounterValue(CrdtCounter.CounterType.LongCnt)
        assertEquals(333L, longValue)
        assertArrayEquals(longInBytes, CrdtCounter(333L, InitialTimeTicket).toBytes())
    }

    @Suppress("INTEGER_OVERFLOW")
    @Test
    fun `verify increase allows Int overflow`() {
        val maxInt = CrdtCounter(Int.MAX_VALUE, InitialTimeTicket)
        maxInt.increase(CrdtPrimitive(1, InitialTimeTicket))
        assertEquals(Int.MAX_VALUE + 1, maxInt.value)

        val minInt = CrdtCounter(Int.MIN_VALUE, InitialTimeTicket)
        minInt.increase(CrdtPrimitive(-1, InitialTimeTicket))
        assertEquals(Int.MIN_VALUE - 1, minInt.value)
    }

    @Test
    fun `should use same instance for value on deepCopy`() {
        val counter = CrdtCounter(4, InitialTimeTicket)
        val clone = counter.deepCopy() as CrdtCounter
        assertNotSame(counter, clone)
        assertSame(counter.value, clone.value)
    }
}
