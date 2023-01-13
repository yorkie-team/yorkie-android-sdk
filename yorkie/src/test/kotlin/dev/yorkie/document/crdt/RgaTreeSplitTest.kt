package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class RgaTreeSplitTest {
    private lateinit var target: RgaTreeSplit<TextValue>

    @Before
    fun setUp() {
        target = RgaTreeSplit()
    }

    @Test
    fun `should handle edit operations with case1`() {
        var range = RgaTreeSplitNodeRange(target.findNodePos(0), target.findNodePos(0))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("ABCD"))
        range = RgaTreeSplitNodeRange(target.findNodePos(1), target.findNodePos(3))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("12"))

        assertEquals("A12D", target.toJson())
    }

    @Test
    fun `should handle edit operations with case2`() {
        var range = RgaTreeSplitNodeRange(target.findNodePos(0), target.findNodePos(0))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("ABCD"))
        range = RgaTreeSplitNodeRange(target.findNodePos(3), target.findNodePos(3))
        target.edit(range, TimeTicket.InitialTimeTicket, TextValue("\n"))

        assertEquals("ABC\nD", target.toJson())
    }
}
