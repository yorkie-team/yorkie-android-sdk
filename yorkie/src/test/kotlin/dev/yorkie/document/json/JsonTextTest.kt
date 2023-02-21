package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.TextChangeType
import dev.yorkie.document.time.TimeTicket
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonTextTest {

    private lateinit var target: JsonText

    @Before
    fun setUp() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, rht = ElementRht())
        val text = CrdtText(RgaTreeSplit(), TimeTicket.InitialTimeTicket)
        target = JsonText(
            ChangeContext(
                ChangeID.InitialChangeID,
                CrdtRoot(obj),
                null,
            ),
            text,
        )
    }

    @Test
    fun `should handle text edit operations`() {
        target.edit(0, 0, "Hello World")
        assertEquals("""[{"val":"Hello World"}]""", target.toJson())

        target.edit(6, 11, "Yorkie", mapOf("b" to "1"))
        assertEquals(
            """[{"val":"Hello "},{"attrs":{"b":"1"},"val":"Yorkie"}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should handle style operations`() {
        target.edit(0, 0, "Hello World")
        assertEquals("""[{"val":"Hello World"}]""", target.toJson())

        target.style(0, 1, mapOf("b" to "1"))
        assertEquals(
            """[{"attrs":{"b":"1"},"val":"H"},{"val":"ello World"}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should handle select operations`() {
        target.edit(0, 0, "ABCD")
        target.onChanges { changes ->
            if (changes.first().type == TextChangeType.Selection) {
                assertEquals(changes.first().from, 2)
                assertEquals(changes.first().to, 4)
            }
        }
        target.select(2, 4)
    }

    @Test
    fun `should handle text empty operations`() {
        target.edit(0, 0, "ABCD")
        assertTrue(target.toString().isNotEmpty())
        target.clear()
        assertTrue(target.toString().isEmpty())
    }

    @Test
    fun `should handle text delete operations`() {
        target.edit(0, 0, "ABCD")
        assertEquals(target.toString(), "ABCD")
        target.delete(1, 3)
        assertEquals(target.toString(), "AD")
    }
}
