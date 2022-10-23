package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertTrue
import org.junit.Test

class CrdtPrimitiveTest {
    @Test
    fun `Verify the value`() {
        val primitive = CrdtPrimitive("hello", TimeTicket.InitialTimeTicket)
        assertTrue("hello" == primitive.value)
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
}
