package dev.yorkie.document.json

import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.time.TimeTicket
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonPrimitiveTest {

    @Test
    fun `should get value as requested type if castable`() {
        val json = JsonPrimitive(CrdtPrimitive(4, TimeTicket.InitialTimeTicket))
        assertEquals(4, json.getValueAs())
    }

    @Test
    fun `should throw TypeCastException if requested value type is not valid`() {
        assertThrows(TypeCastException::class.java) {
            val json = JsonPrimitive(CrdtPrimitive(4, TimeTicket.InitialTimeTicket))
            json.getValueAs<String>()
        }
    }

    @Test
    fun `should throw IllegalStateException when requested non-null value when it is null`() {
        assertThrows(IllegalStateException::class.java) {
            val json = JsonPrimitive(CrdtPrimitive(null, TimeTicket.InitialTimeTicket))
            json.getValueAs()
        }
    }

    @Test
    fun `should return null when requested type is not valid with getValueOrNull`() {
        val json = JsonPrimitive(CrdtPrimitive(4, TimeTicket.InitialTimeTicket))
        assertNull(json.getValueAsOrNull<String>())
    }

    @Test
    fun `should return null when value is null with getValueOrNull`() {
        val json = JsonPrimitive(CrdtPrimitive(null, TimeTicket.InitialTimeTicket))
        assertNull(json.getValueAsOrNull())
    }
}
