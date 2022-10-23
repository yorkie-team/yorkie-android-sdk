package dev.yorkie.document.crdt

import dev.yorkie.document.crdt.CrdtCounter.Companion.asCounterValue
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Date

class CrdtCounterTest {

    @Test
    fun `verify increasing positive numeric data of Counter works properly`() {
        val int = CrdtCounter(1, InitialTimeTicket)
        val double = CrdtCounter(10.0, InitialTimeTicket)
        val long = CrdtCounter(100L, InitialTimeTicket)

        val intOperand = CrdtPrimitive(1, InitialTimeTicket)
        val doubleOperand = CrdtPrimitive(10.0, InitialTimeTicket)
        val longOperand = CrdtPrimitive(100L, InitialTimeTicket)

        int.increase(intOperand)
        int.increase(doubleOperand)
        int.increase(longOperand)
        assertEquals(112, int.value)

        double.increase(intOperand)
        double.increase(doubleOperand)
        double.increase(longOperand)
        assertEquals(121.0, double.value)

        long.increase(intOperand)
        long.increase(doubleOperand)
        long.increase(longOperand)
        assertEquals(211L, long.value)
    }

    @Test
    fun `verify increasing with non numeric data type throws error`() {
        val double = CrdtCounter(10.0, InitialTimeTicket)
        val str = CrdtPrimitive("hello", InitialTimeTicket)
        val bool = CrdtPrimitive(true, InitialTimeTicket)
        val bytes = CrdtPrimitive(ByteArray(1), InitialTimeTicket)
        val date = CrdtPrimitive(Date(), InitialTimeTicket)

        assertThrows(IllegalArgumentException::class.java) {
            double.increase(str)
        }
        assertThrows(IllegalArgumentException::class.java) {
            double.increase(bool)
        }
        assertThrows(IllegalArgumentException::class.java) {
            double.increase(bytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            double.increase(date)
        }
        assertEquals(10.0, double.value)
    }

    @Test
    fun `verify increasing with negative numeric data works properly`() {
        val int = CrdtCounter(1, InitialTimeTicket)
        val double = CrdtCounter(10.0, InitialTimeTicket)
        val long = CrdtCounter(100L, InitialTimeTicket)

        val intOperand = CrdtPrimitive(-1, InitialTimeTicket)
        val doubleOperand = CrdtPrimitive(-10.0, InitialTimeTicket)
        val longOperand = CrdtPrimitive(-100L, InitialTimeTicket)

        int.increase(intOperand)
        int.increase(doubleOperand)
        int.increase(longOperand)
        assertEquals(-110, int.value)

        double.increase(intOperand)
        double.increase(doubleOperand)
        double.increase(longOperand)
        assertEquals(-101.0, double.value)

        long.increase(intOperand)
        long.increase(doubleOperand)
        long.increase(longOperand)
        assertEquals(-11L, long.value)
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

        val doubleInBytes = ByteBuffer.allocate(Double.SIZE_BYTES).putDouble(1.234).array()
        val doubleValue = doubleInBytes.asCounterValue(CrdtCounter.CounterType.DoubleCnt)
        assertEquals(1.234, doubleValue)
        assertArrayEquals(doubleInBytes, CrdtCounter(1.234, InitialTimeTicket).toBytes())
    }

    @Test
    fun `verify increase handles Int overflow gracefully`() {
        val maxInt = CrdtCounter(Int.MAX_VALUE, InitialTimeTicket)
        maxInt.increase(CrdtPrimitive(1, InitialTimeTicket))
        assertEquals(Int.MAX_VALUE + 1L, maxInt.value)

        val minInt = CrdtCounter(Int.MIN_VALUE, InitialTimeTicket)
        minInt.increase(CrdtPrimitive(-1, InitialTimeTicket))
        assertEquals(Int.MIN_VALUE - 1L, minInt.value)
    }
}
