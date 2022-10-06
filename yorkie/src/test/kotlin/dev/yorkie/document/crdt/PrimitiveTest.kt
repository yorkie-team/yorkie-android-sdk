package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimitiveTest {
    @Test
    fun `Verify the value`() {
        val primitive = Primitive.of("hello", TimeTicket.InitialTimeTicket)
        assertTrue("hello" == primitive.value)
    }

    @Test
    fun `Verify that the value of Primitive and PrimitiveType are mapped correctly`() {
        var primitive = Primitive.of(true, TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Boolean == primitive.type)

        primitive = Primitive.of(1, TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Integer == primitive.type)

        primitive = Primitive.of(1L, TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Long == primitive.type)

        primitive = Primitive.of(1.toDouble(), TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Double == primitive.type)

        primitive = Primitive.of("hello", TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.String == primitive.type)

        primitive = Primitive.of(
            byteArrayOf(-0x01, -0x01, 0x02, -0x02),
            TimeTicket.InitialTimeTicket,
        )
        assertTrue(PrimitiveType.Bytes == primitive.type)

        primitive = Primitive.of(null, TimeTicket.InitialTimeTicket)
        assertTrue(PrimitiveType.Null == primitive.type)
    }
}
