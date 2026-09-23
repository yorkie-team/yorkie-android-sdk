package dev.yorkie.util

import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.issueTime
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ports the split-past-node-end guard from JS SDK `2ed28322` as a JVM unit
 * test (AC5): an out-of-range text-split offset is refused with a
 * controlled [YorkieException], not a raw
 * [IndexOutOfBoundsException]; in-range and boundary splits stay unchanged.
 */
class IndexTreeSplitGuardTest {

    private fun textNode(value: String) = CrdtTreeText(CrdtTreeNodeID(issueTime(), 0), value)

    @Test
    fun `refuses a split past the node's visible size`() {
        val exception = assertFailsWith<YorkieException> {
            textNode("hello").splitText(10, 0)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertEquals("split at 10 of 5: offset out of range", exception.errorMessage)
    }

    @Test
    fun `refuses a negative split offset`() {
        val exception = assertFailsWith<YorkieException> {
            textNode("hello").splitText(-1, 0)
        }
        assertEquals(YorkieException.Code.ErrInvalidArgument, exception.code)
        assertEquals("split at -1 of 5: offset out of range", exception.errorMessage)
    }

    @Test
    fun `boundary offsets 0 and visibleSize return no split`() {
        assertNull(textNode("hello").splitText(0, 0).first)
        assertNull(textNode("hello").splitText(5, 0).first)
    }

    @Test
    fun `in-range split still produces the right node`() {
        val node = textNode("hello")
        val right = node.splitText(2, 0).first
        assertEquals("he", node.value)
        assertEquals("llo", right?.value)
    }
}
