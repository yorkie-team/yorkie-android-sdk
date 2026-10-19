package dev.yorkie.document.time

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTicketTest {

    @Test
    fun `toTestString should format lamport, actor suffix and delimiter`() {
        val ticket = TimeTicket(
            lamport = 3,
            delimiter = 1u,
            actorID = "000000000000000000000001",
        )

        assertEquals("3:01:1", ticket.toTestString())
    }
}
