package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class CrdtPrimitiveTest {
    @Test
    fun `Verify the value`() {
        val primitive = CrdtPrimitive("hello", TimeTicket.InitialTimeTicket)
        assertEquals("hello", primitive.value)
    }

    @Test
    fun `Verify that the value of Primitive and PrimitiveType are mapped correctly`() {
        var primitive = CrdtPrimitive(true, TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Boolean == primitive.type)

        primitive = CrdtPrimitive(1, TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Integer == primitive.type)

        primitive = CrdtPrimitive(1L, TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Long == primitive.type)

        primitive = CrdtPrimitive(1.toDouble(), TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Double == primitive.type)

        primitive = CrdtPrimitive("hello", TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.String == primitive.type)

        primitive = CrdtPrimitive(
            byteArrayOf(-0x01, -0x01, 0x02, -0x02),
            TimeTicket.InitialTimeTicket,
        )
        assertTrue(PrimitiveType.Bytes == primitive.type)

        primitive = CrdtPrimitive(null, TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Null == primitive.type)
    }

    @Test
    fun `should create new instance when deep copying Date and ByteArray`() {
        val datePrimitive = CrdtPrimitive(Date(1_000L), TimeTicket.InitialTimeTicket)
        val dateClone = datePrimitive.deepCopy() as CrdtPrimitive
        assertEquals(datePrimitive.value, dateClone.value)
        assertNotSame(datePrimitive.value, dateClone.value)

        val byteArrayPrimitive = CrdtPrimitive(
            ByteArray(4) { 1 },
            TimeTicket.InitialTimeTicket,
        )
        val byteArrayClone = byteArrayPrimitive.deepCopy() as CrdtPrimitive
        assertEquals(byteArrayPrimitive.value, byteArrayClone.value)
        assertNotSame(byteArrayPrimitive.value, byteArrayClone.value)
    }

    @Test
    fun `should return same instance when deep copying immutable values`() {
        val intPrimitive = CrdtPrimitive(1, TimeTicket.InitialTimeTicket)
        val intClone = intPrimitive.deepCopy() as CrdtPrimitive
        assertEquals(intPrimitive.value, intClone.value)
        assertSame(intPrimitive.value, intClone.value)

        val stringPrimitive = CrdtPrimitive("str", TimeTicket.InitialTimeTicket)
        val stringClone = stringPrimitive.deepCopy() as CrdtPrimitive
        assertEquals(stringPrimitive.value, stringClone.value)
        assertSame(stringPrimitive.value, stringClone.value)
    }
}
