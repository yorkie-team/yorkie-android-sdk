package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtText
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.RgaTreeSplit
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class StyleOperationReverseTest {

    private val rootTicket = TimeTicket(1L, 0u, "actor-0")
    private val textTicket = TimeTicket(2L, 0u, "actor-0")

    private fun makeTicket(lamport: Long): TimeTicket = TimeTicket(lamport, 0u, "actor-0")

    private fun buildTextRoot(): Pair<CrdtText, CrdtRoot> {
        val text = CrdtText(RgaTreeSplit(), textTicket)
        val obj = CrdtObject(createdAt = rootTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        root.rootObject.set("text", text, textTicket)
        root.registerElement(text, root.rootObject)
        return text to root
    }

    @Test
    fun `reverse of style sets previous attribute values`() {
        // given: "hello" with bold=true
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "hello", makeTicket(3), mapOf("bold" to "true"))

        // when: style range to bold=false
        val range = text.indexRangeToPosRange(0, 5)
        val op = StyleOperation(
            fromPos = range.first,
            toPos = range.second,
            attributes = mapOf("bold" to "false"),
            parentCreatedAt = textTicket,
            executedAt = makeTicket(4),
        )
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op restores bold=true
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as StyleOperation
        assertEquals(mapOf("bold" to "true"), reverseOp.attributes)
        assertTrue(reverseOp.attributesToRemove.isEmpty())
    }

    @Test
    fun `reverse of style on unstyled range produces remove-style op`() {
        // given: "hello" with no attributes
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "hello", makeTicket(3))

        // when: set bold=true on the range
        val range = text.indexRangeToPosRange(0, 5)
        val op = StyleOperation(
            fromPos = range.first,
            toPos = range.second,
            attributes = mapOf("bold" to "true"),
            parentCreatedAt = textTicket,
            executedAt = makeTicket(4),
        )
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op removes the newly added attribute
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as StyleOperation
        assertTrue(reverseOp.attributes.isEmpty())
        assertEquals(listOf("bold"), reverseOp.attributesToRemove)
    }

    @Test
    fun `reverse of removeStyle restores previous attribute value`() {
        // given: "hello" with bold=true
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "hello", makeTicket(3), mapOf("bold" to "true"))

        // when: remove bold via attributesToRemove
        val range = text.indexRangeToPosRange(0, 5)
        val op = StyleOperation(
            fromPos = range.first,
            toPos = range.second,
            attributes = emptyMap(),
            parentCreatedAt = textTicket,
            executedAt = makeTicket(4),
            attributesToRemove = listOf("bold"),
        )
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op restores bold=true
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as StyleOperation
        assertEquals(mapOf("bold" to "true"), reverseOp.attributes)
        assertTrue(reverseOp.attributesToRemove.isEmpty())
    }

    @Test
    fun `no reverse op generated for remote source`() {
        // given: "hello"
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "hello", makeTicket(3), mapOf("bold" to "true"))

        // when: remote style operation
        val range = text.indexRangeToPosRange(0, 5)
        val op = StyleOperation(
            fromPos = range.first,
            toPos = range.second,
            attributes = mapOf("bold" to "false"),
            parentCreatedAt = textTicket,
            executedAt = makeTicket(4),
        )
        val result = op.execute(root, OpSource.Remote, null)

        // then: no reverse op for remote
        assertTrue(result.reverseOps.isEmpty())
    }

    @Test
    fun `style then undo restores original attributes`() {
        // given: "hello" with bold=true
        val (text, root) = buildTextRoot()
        val insertTicket = makeTicket(3)
        text.edit(text.indexRangeToPosRange(0, 0), "hello", insertTicket, mapOf("bold" to "true"))

        // when: apply style (bold=false)
        val range = text.indexRangeToPosRange(0, 5)
        val applyTicket = makeTicket(4)
        val applyOp = StyleOperation(
            fromPos = range.first,
            toPos = range.second,
            attributes = mapOf("bold" to "false"),
            parentCreatedAt = textTicket,
            executedAt = applyTicket,
        )
        val applyResult = applyOp.execute(root, OpSource.Local, null)
        assertEquals("false", text.values.first().attributes["bold"])

        // then: execute reverse op to undo
        val reverseOp = applyResult.reverseOps[0] as StyleOperation
        reverseOp.executedAt = makeTicket(5)
        reverseOp.execute(root, OpSource.UndoRedo, null)
        assertEquals("true", text.values.first().attributes["bold"])
    }

    @Test
    fun `add style then undo removes attribute`() {
        // given: "hello" with no attributes
        val (text, root) = buildTextRoot()
        text.edit(text.indexRangeToPosRange(0, 0), "hello", makeTicket(3))

        // when: add bold=true
        val range = text.indexRangeToPosRange(0, 5)
        val applyOp = StyleOperation(
            fromPos = range.first,
            toPos = range.second,
            attributes = mapOf("bold" to "true"),
            parentCreatedAt = textTicket,
            executedAt = makeTicket(4),
        )
        val applyResult = applyOp.execute(root, OpSource.Local, null)
        assertEquals("true", text.values.first().attributes["bold"])

        // then: reverse op removes bold
        val reverseOp = applyResult.reverseOps[0] as StyleOperation
        reverseOp.executedAt = makeTicket(5)
        reverseOp.execute(root, OpSource.UndoRedo, null)
        val attrs = text.values.first().attributes
        assertTrue(!attrs.containsKey("bold"))
    }
}
