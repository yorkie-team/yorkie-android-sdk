package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
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
        assertTrue(CrdtPrimitive.Type.Boolean == primitive.type)

        primitive = CrdtPrimitive(1, TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Integer == primitive.type)

        primitive = CrdtPrimitive(1L, TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Long == primitive.type)

        primitive = CrdtPrimitive(1.toDouble(), TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Double == primitive.type)

        primitive = CrdtPrimitive("hello", TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.String == primitive.type)

        primitive = CrdtPrimitive(
            byteArrayOf(-0x01, -0x01, 0x02, -0x02),
            TimeTicket.InitialTimeTicket,
        )
        assertTrue(CrdtPrimitive.Type.Bytes == primitive.type)

        primitive = CrdtPrimitive(null, TimeTicket.InitialTimeTicket)
        assertTrue(CrdtPrimitive.Type.Null == primitive.type)
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
        assertNotSame(intPrimitive, intClone)
        assertSame(intPrimitive.value, intClone.value)

        val stringPrimitive = CrdtPrimitive("str", TimeTicket.InitialTimeTicket)
        val stringClone = stringPrimitive.deepCopy() as CrdtPrimitive
        assertNotSame(stringPrimitive, stringClone)
        assertSame(stringPrimitive.value, stringClone.value)

        val boolPrimitive = CrdtPrimitive(false, TimeTicket.InitialTimeTicket)
        val boolClone = boolPrimitive.deepCopy() as CrdtPrimitive
        assertNotSame(boolPrimitive, boolClone)
        assertSame(boolPrimitive.value, boolClone.value)
    }

    @Test
    fun `should sanitize value to supported types`() {
        val bytePrimitive = CrdtPrimitive(14.toByte(), TimeTicket.InitialTimeTicket)
        assertEquals(14, bytePrimitive.value)

        val shortPrimitive = CrdtPrimitive(1.toShort(), TimeTicket.InitialTimeTicket)
        assertEquals(1, shortPrimitive.value)

        val numberPrimitive = CrdtPrimitive(111.111.toBigDecimal(), TimeTicket.InitialTimeTicket)
        assertEquals(111.111, numberPrimitive.value)

        val unsupported = CrdtPrimitive(Unit, TimeTicket.InitialTimeTicket)
        assertNull(unsupported.value)
        assertEquals(CrdtPrimitive.Type.Null, unsupported.type)
    }
}
