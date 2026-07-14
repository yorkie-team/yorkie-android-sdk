package dev.yorkie.document.json

import dev.yorkie.document.crdt.CrdtPrimitive
import dev.yorkie.document.time.TimeTicket
import java.util.Date
import kotlin.test.assertEquals
import org.junit.Test

class JsonStringifierTest {

    @Test
    fun `should serialize bytes primitive as base64 string`() {
        // given
        val primitive = CrdtPrimitive(byteArrayOf(65, 66), TimeTicket.InitialTimeTicket)

        // when
        val json = primitive.toJson()

        // then
        assertEquals(""""QUI="""", json)
    }

    @Test
    fun `should serialize empty bytes primitive as empty string`() {
        // given
        val primitive = CrdtPrimitive(byteArrayOf(), TimeTicket.InitialTimeTicket)

        // when
        val json = primitive.toJson()

        // then
        assertEquals("\"\"", json)
    }

    @Test
    fun `should serialize date primitive as iso 8601 string`() {
        // given
        val primitive = CrdtPrimitive(Date(1_000L), TimeTicket.InitialTimeTicket)

        // when
        val json = primitive.toJson()

        // then
        assertEquals(""""1970-01-01T00:00:01.000Z"""", json)
    }
}
