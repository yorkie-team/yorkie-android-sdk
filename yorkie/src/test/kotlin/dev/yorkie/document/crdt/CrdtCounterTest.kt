package dev.yorkie.document.crdt

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
        val int = CrdtCounter.of(1, InitialTimeTicket)
        val double = CrdtCounter.of(10.0, InitialTimeTicket)
        val long = CrdtCounter.of(100L, InitialTimeTicket)

        val intOperand = Primitive.of(1, InitialTimeTicket)
        val doubleOperand = Primitive.of(10.0, InitialTimeTicket)
        val longOperand = Primitive.of(100L, InitialTimeTicket)

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
        val double = CrdtCounter.of(10.0, InitialTimeTicket)
        val str = Primitive.of("hello", InitialTimeTicket)
        val bool = Primitive.of(true, InitialTimeTicket)
        val bytes = Primitive.of(ByteArray(1), InitialTimeTicket)
        val date = Primitive.of(Date(), InitialTimeTicket)

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
        val int = CrdtCounter.of(1, InitialTimeTicket)
        val double = CrdtCounter.of(10.0, InitialTimeTicket)
        val long = CrdtCounter.of(100L, InitialTimeTicket)

        val intOperand = Primitive.of(-1, InitialTimeTicket)
        val doubleOperand = Primitive.of(-10.0, InitialTimeTicket)
        val longOperand = Primitive.of(-100L, InitialTimeTicket)

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
        val bytes = ByteBuffer.allocate(Double.SIZE_BYTES).putDouble(1.234).array()
        val doubleValue = CrdtCounter.valueFromBytes(CrdtCounter.CounterType.DoubleCnt, bytes)
        assertEquals(1.234, doubleValue)

        assertArrayEquals(bytes, CrdtCounter.of(1.234, InitialTimeTicket).toBytes())
    }
}
