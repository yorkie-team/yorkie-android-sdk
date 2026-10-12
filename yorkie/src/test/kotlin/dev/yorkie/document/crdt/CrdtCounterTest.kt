package dev.yorkie.document.crdt

import dev.yorkie.document.crdt.CrdtCounter.Companion.asCounterValue
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        val intInBytes = ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(39)
            .array()
        val intValue = intInBytes.asCounterValue(CrdtCounter.CounterType.Int)
        assertEquals(39, intValue)
        assertArrayEquals(intInBytes, CrdtCounter(39, InitialTimeTicket).toBytes())

        val longInBytes = ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(333L)
            .array()
        val longValue = longInBytes.asCounterValue(CrdtCounter.CounterType.Long)
        assertEquals(333L, longValue)
        assertArrayEquals(longInBytes, CrdtCounter(333L, InitialTimeTicket).toBytes())
    }

    // Port of yorkie-js-sdk primitive_test.ts / counter_test.ts (ac60f3d9,
    // #1422; D8): a short counter payload reads as its zero-padded value
    // instead of throwing. Probe at b1fa0d39 confirmed BufferUnderflowException
    // on both counter types for a short payload.
    @Test
    fun `short Int and Long counter payloads decode zero-padded`() {
        // byte0=LSB .. zero-padded upper bytes: 1 + 2*256 = 513
        val shortTwoBytes = byteArrayOf(1, 2)
        assertEquals(513, shortTwoBytes.asCounterValue(CrdtCounter.CounterType.Int))
        assertEquals(513, shortTwoBytes.asCounterValue(CrdtCounter.CounterType.IntDedup))

        // 1 + 2*256 + 3*65536 = 197_121
        val shortThreeBytes = byteArrayOf(1, 2, 3)
        assertEquals(197_121L, shortThreeBytes.asCounterValue(CrdtCounter.CounterType.Long))

        // 0 bytes zero-pads to 0.
        val empty = ByteArray(0)
        assertEquals(0, empty.asCounterValue(CrdtCounter.CounterType.Int))
        assertEquals(0L, empty.asCounterValue(CrdtCounter.CounterType.Long))
    }

    @Test
    fun `over-length Int and Long counter payloads decode from only the leading width bytes`() {
        val trailingGarbage = byteArrayOf(9, 9, 9, 9)

        val fullInt = ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(39)
            .array()
        assertEquals(
            39,
            (fullInt + trailingGarbage).asCounterValue(CrdtCounter.CounterType.Int),
        )

        val fullLong = ByteBuffer.allocate(Long.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(333L)
            .array()
        assertEquals(
            333L,
            (fullLong + trailingGarbage).asCounterValue(CrdtCounter.CounterType.Long),
        )
    }

    // The #1422 "shared-buffer write" headline is about Primitive.fromBytes,
    // not the counter path, but asCounterValue reads off the SAME `copyOf`
    // defensive-copy policy; pin that it never mutates the ByteArray handed
    // to it (unlike a ByteString, a plain ByteArray IS mutable and shared by
    // reference, so this guarantee is load-bearing here, not just N/A).
    @Test
    fun `asCounterValue never mutates its input ByteArray`() {
        val source = byteArrayOf(1, 2, 3)
        val before = source.copyOf()

        source.asCounterValue(CrdtCounter.CounterType.Long)

        assertArrayEquals(before, source)
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

    // Port of JS SDK bb3c73dc unit test (v0.7.15, JS #1312): an Int counter's
    // Long-delta increase wraps the RESULT like Go's int32 arithmetic, not
    // just the delta. Kotlin `Int + Int` already wraps two's-complement and
    // `Long.toInt()` keeps the low 32 bits, so `CrdtCounter.increase`
    // (`value.toInt() + primitiveValue.toInt()`) is congruent to the fixed
    // JS by mod-2^32 arithmetic without any production change (spec 006
    // Stage B no-code determination).
    @Test
    fun `verify Int counter wraps when increased by an out-of-int32 Long`() {
        val counter = CrdtCounter(0, InitialTimeTicket)
        val outOfInt32 = CrdtPrimitive(2147483648L, InitialTimeTicket) // 2^31
        counter.increase(outOfInt32)
        assertEquals(Int.MIN_VALUE, counter.value)
    }

    @Test
    fun `verify Int counter wraps to Int MAX_VALUE from a nonzero base`() {
        val counter = CrdtCounter(-1, InitialTimeTicket)
        counter.increase(CrdtPrimitive(2147483648L, InitialTimeTicket)) // 2^31
        assertEquals(Int.MAX_VALUE, counter.value)
    }

    @Test
    fun `should use same instance for value on deepCopy`() {
        val counter = CrdtCounter(4, InitialTimeTicket)
        val clone = counter.deepCopy() as CrdtCounter
        assertNotSame(counter, clone)
        assertSame(counter.value, clone.value)
    }

    @Test
    fun `dedup counter reports zero before any actor is added`() {
        // given
        val counter = CrdtCounter.createDedup(InitialTimeTicket)

        // when - then
        assertEquals(CrdtCounter.CounterType.IntDedup, counter.type)
        assertEquals(0, counter.value)
    }

    @Test
    fun `dedup counter increments by unique actor`() {
        // given
        val counter = CrdtCounter.createDedup(InitialTimeTicket)
        val unit = CrdtPrimitive(1, InitialTimeTicket)

        // when
        counter.increaseDedup(unit, "alice")
        counter.increaseDedup(unit, "bob")
        counter.increaseDedup(unit, "carol")

        // then
        assertEquals(3, counter.value)
    }

    @Test
    fun `dedup counter ignores repeated actor`() {
        // given
        val counter = CrdtCounter.createDedup(InitialTimeTicket)
        val unit = CrdtPrimitive(1, InitialTimeTicket)

        // when
        counter.increaseDedup(unit, "alice")
        counter.increaseDedup(unit, "alice")
        counter.increaseDedup(unit, "alice")

        // then
        assertEquals(1, counter.value)
    }

    @Test
    fun `dedup counter rejects non-unit increment`() {
        // given
        val counter = CrdtCounter.createDedup(InitialTimeTicket)
        val two = CrdtPrimitive(2, InitialTimeTicket)

        // when - then
        assertThrows(IllegalArgumentException::class.java) {
            counter.increaseDedup(two, "alice")
        }
    }

    @Test
    fun `dedup counter rejects empty actor`() {
        // given
        val counter = CrdtCounter.createDedup(InitialTimeTicket)
        val unit = CrdtPrimitive(1, InitialTimeTicket)

        // when - then
        assertThrows(IllegalArgumentException::class.java) {
            counter.increaseDedup(unit, "")
        }
    }

    @Test
    fun `normal increase on dedup counter throws`() {
        // given
        val counter = CrdtCounter.createDedup(InitialTimeTicket)
        val unit = CrdtPrimitive(1, InitialTimeTicket)

        // when - then
        assertThrows(IllegalArgumentException::class.java) {
            counter.increase(unit)
        }
    }

    @Test
    fun `dedup counter deepCopy isolates HLL state`() {
        // given
        val original = CrdtCounter.createDedup(InitialTimeTicket)
        val unit = CrdtPrimitive(1, InitialTimeTicket)
        original.increaseDedup(unit, "alice")
        val clone = original.deepCopy() as CrdtCounter

        // when - mutate the clone
        clone.increaseDedup(unit, "bob")

        // then - original is untouched
        assertEquals(1, original.value)
        assertEquals(2, clone.value)
    }

    @Test
    fun `dedup counter round-trips through HLL bytes`() {
        // given
        val source = CrdtCounter.createDedup(InitialTimeTicket)
        val unit = CrdtPrimitive(1, InitialTimeTicket)
        repeat(50) { i ->
            source.increaseDedup(unit, "user-$i")
        }
        val bytes = checkNotNull(source.hllBytes())

        // when
        val restored = CrdtCounter.createDedup(InitialTimeTicket)
        restored.restoreHll(bytes)

        // then
        assertEquals(source.value, restored.value)
    }
}
