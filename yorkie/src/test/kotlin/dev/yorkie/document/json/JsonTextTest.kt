package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test

class JsonTextTest {

    private lateinit var target: JsonText

    @Before
    fun setUp() {
        val obj = CrdtObject(TimeTicket.InitialTimeTicket, rht = ElementRht())
        val text = CrdtText(RgaTreeSplit(), TimeTicket.InitialTimeTicket)
        target = JsonText(
            ChangeContext(ChangeID.InitialChangeID, CrdtRoot(obj)),
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
            // delete no last node
            Triple(9, 9, ""),
            // delete one last node with split
            Triple(8, 9, ""),
            // delete one last node without split
            Triple(6, 8, ""),
            // delete last nodes with split
            Triple(4, 6, ""),
            // delete last nodes without split
            Triple(2, 4, ""),
            // delete last nodes containing the first
            Triple(0, 2, ""),
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
            // ABCXEF
            Triple(0, 1, ""),
            // delete A with two removed boundaries
            Triple(0, 1, ""),
            // delete B with removed left boundary
            Triple(0, 1, ""),
            // delete F with removed right boundary
            Triple(3, 4, ""),
            Triple(1, 2, ""),
            // delete CE with removed inner node X
            Triple(0, 2, ""),
        ).forEach { command ->
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
    fun `should return null when index range is invalid with edit`() {
        target.edit(0, 0, "ABC")
        assertNull(target.edit(5, 0, "D"))
        assertNull(target.edit(5, 7, "E"))
    }

    @Test
    fun `should return false when index range is invalid with style`() {
        assertTrue(target.style(0, 0, emptyMap()))
        assertFalse(target.style(5, 0, emptyMap()))
        assertFalse(target.style(5, 7, emptyMap()))
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
