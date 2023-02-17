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
import kotlin.test.assertTrue

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
        assertEquals("""[{"val":"Hello World"}]""", target.toJson())

        target.edit(6, 11, "Yorkie", mapOf("b" to "1"))
        assertEquals(
            """[{"val":"Hello "},{"attrs":{"b":"1"},"val":"Yorkie"}]""",
            target.toJson(),
        )
    }

    @Test
    fun `should handle text edit operations of Hangul`() {
        target.edit(0, 0, "ㅎ")
        target.edit(0, 1, "하")
        target.edit(0, 1, "한")
        target.edit(0, 1, "하")
        target.edit(1, 1, "느")
        target.edit(1, 2, "늘")
        assertEquals("하늘", target.toString())
    }

    @Test
    fun `should handle deletion of nested nodes`() {
        var text = ""
        listOf(
            Triple(0, 0, "ABC"),
            Triple(3, 3, "DEF"),
            Triple(2, 4, "1"),
            Triple(1, 4, "2"),
        ).forEach { command ->
            text = text.replaceRange(command.first, command.second, command.third)
            target.edit(command.first, command.second, command.third)
            assertEquals(text, target.toString())
        }
    }

    @Test
    fun `should handle deletion of last nodes`() {
        var text = ""
        listOf(
            Triple(0, 0, "A"),
            Triple(1, 1, "B"),
            Triple(2, 2, "C"),
            Triple(3, 3, "DE"),
            Triple(5, 5, "F"),
            Triple(6, 6, "GHI"),
            Triple(9, 9, ""), // delete no last node
            Triple(8, 9, ""), // delete one last node with split
            Triple(6, 8, ""), // delete one last node without split
            Triple(4, 6, ""), // delete last nodes with split
            Triple(2, 4, ""), // delete last nodes without split
            Triple(0, 2, ""), // delete last nodes containing the first
        ).forEach { command ->
            text = text.replaceRange(command.first, command.second, command.third)
            target.edit(command.first, command.second, command.third)
            assertEquals(text, target.toString())
        }
    }

    @Test
    fun `should handle deletion with boundary nodes already removed`() {
        var text = ""
        listOf(
            Triple(0, 0, "1A1BCXEF1"),
            Triple(8, 9, ""),
            Triple(2, 3, ""),
            Triple(0, 1, ""), // ABCXEF
            Triple(0, 1, ""), // delete A with two removed boundaries
            Triple(0, 1, ""), // delete B with removed left boundary
            Triple(3, 4, ""), // delete F with removed right boundary
            Triple(1, 2, ""),
            Triple(0, 2, ""), // delete CE with removed inner node X
        ).forEach { command ->
            println(command)
            text = text.replaceRange(command.first, command.second, command.third)
            target.edit(command.first, command.second, command.third)
            assertEquals(text, target.toString())
        }
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
    fun `should handle text clear operations`() {
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
