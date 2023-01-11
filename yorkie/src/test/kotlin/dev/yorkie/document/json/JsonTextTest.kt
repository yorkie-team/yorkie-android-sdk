package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.crdt.RhtPQMap
import dev.yorkie.document.crdt.TextChangeType
import dev.yorkie.document.time.TimeTicket
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class JsonTextTest {

    private lateinit var target: JsonText

    @Before
    fun setUp() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, rht = RhtPQMap())
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
        assertEquals("""[{"attrs":{},"content":"Hello World"}]""", target.toJson())

        target.edit(6, 11, "Yorkie", mapOf("b" to "1"))
        assertEquals(
            """[{"attrs":{},"content":"Hello "},{"attrs":{"b":"1"},"content":"Yorkie"}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should handle style operations`() {
        target.edit(0, 0, "Hello World")
        assertEquals("""[{"attrs":{},"content":"Hello World"}]""", target.toJson())

        target.style(0, 1, mapOf("b" to "1"))
        assertEquals(
            """[{"attrs":{"b":"1"},"content":"H"},{"attrs":{},"content":"ello World"}]""",
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
}
