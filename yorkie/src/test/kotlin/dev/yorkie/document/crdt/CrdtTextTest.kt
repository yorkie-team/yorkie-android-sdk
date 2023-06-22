package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrdtTextTest {
    private lateinit var target: CrdtText

    @Before
    fun setUp() {
        target = CrdtText(RgaTreeSplit(), TimeTicket.InitialTimeTicket)
    }

    @Test
    fun `should handle edit operations with attributes`() {
        target.edit(
            target.createRange(0, 0),
            "ABCD",
            TimeTicket.InitialTimeTicket,
            mapOf("b" to "1"),
        )
        assertEquals(
            """[{"attrs":{"b":"1"},"val":"ABCD"}]""",
            target.toJson(),
        )

        target.edit(target.createRange(3, 3), "\n", TimeTicket.InitialTimeTicket)
        assertEquals(
            """[{"attrs":{"b":"1"},"val":"ABC"},{"val":"\n"},""" +
                """{"attrs":{"b":"1"},"val":"D"}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should handle edit operations without attributes`() {
        target.edit(target.createRange(0, 0), "A", TimeTicket.InitialTimeTicket)
        assertEquals("""[{"val":"A"}]""", target.toJson())

        target.edit(target.createRange(0, 0), "B", TimeTicket.InitialTimeTicket)
        assertEquals(
            """[{"val":"A"},{"val":"B"}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should handle select operations`() {
        target.edit(target.createRange(0, 0), "ABCD", TimeTicket.InitialTimeTicket)
        val executedAt = TimeTicket(1L, 1, ActorID.INITIAL_ACTOR_ID)
        assertNull(target.select(target.createRange(1, 3), TimeTicket.InitialTimeTicket))
        val textChange = target.select(target.createRange(2, 4), executedAt)
        assertEquals(TextChangeType.Selection, textChange?.type)
        assertEquals(2 to 4, textChange?.from to textChange?.to)
    }
}
