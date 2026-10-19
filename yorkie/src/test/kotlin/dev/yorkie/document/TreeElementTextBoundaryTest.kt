package dev.yorkie.document

import dev.yorkie.DummyContext
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Ports `tree_element_text_boundary_test.ts` (JS SDK `8ad8ce77`, #1482): a
 * text reached at offset 0 anchors on its previous LIVE sibling, not only
 * when it happens to be the first child.
 *
 * `<doc><p><b>hello</b> world</p></doc>`: index 8 sits right after `<b>`'s
 * closing tag and right before " world" begins. The prior `toCrdtTreePos`
 * only special-cased a text node that is its parent's FIRST child, so
 * " world" (not `<p>`'s first child -- `<b>` is) fell through to anchoring
 * on itself, resolving one position too late (after the whole text, not
 * after the preceding element). `edit(8, 14)` with the bug deletes
 * nothing; with the fix it deletes " world".
 */
class TreeElementTextBoundaryTest {

    @Test
    fun `index 8 resolves after an element, not after the text that follows it`() {
        val target = JsonTree(
            DummyContext,
            CrdtTree(
                JsonTree.buildRoot(
                    element("doc") {
                        element("p") {
                            element("b") { text { "hello" } }
                            text { " world" }
                        }
                    },
                    DummyContext,
                ),
                InitialTimeTicket,
            ),
        )
        assertEquals("<doc><p><b>hello</b> world</p></doc>", target.toXml())

        target.edit(8, 14)

        assertEquals("<doc><p><b>hello</b></p></doc>", target.toXml())
    }

    @Test
    fun `a text first among visible children still anchors on its parent`() {
        // The first-child branch this fix keeps: no preceding sibling to
        // anchor index 0 on, so the text node's own parent is used, exactly
        // as before #1482.
        val target = JsonTree(
            DummyContext,
            CrdtTree(
                JsonTree.buildRoot(
                    element("doc") { element("p") { text { "hello" } } },
                    DummyContext,
                ),
                InitialTimeTicket,
            ),
        )
        assertEquals("<doc><p>hello</p></doc>", target.toXml())

        target.edit(1, 1, text { "X" })

        assertEquals("<doc><p>Xhello</p></doc>", target.toXml())
    }

    @Test
    fun `a text first among visible children anchors on its parent past a removed sibling`() {
        // A removed sibling in front (not a LIVE one) must not be picked up
        // by prevSibling -- prevSibling already only reports live siblings
        // (IndexTreeNode.prevSibling), so the first VISIBLE text child still
        // falls to the first-child branch.
        val target = JsonTree(
            DummyContext,
            CrdtTree(
                JsonTree.buildRoot(
                    element("doc") {
                        element("p") {
                            element("b") { text { "x" } }
                            text { "hello" }
                        }
                    },
                    DummyContext,
                ),
                InitialTimeTicket,
            ),
        )
        assertEquals("<doc><p><b>x</b>hello</p></doc>", target.toXml())
        target.edit(1, 4)
        assertEquals("<doc><p>hello</p></doc>", target.toXml())

        target.edit(1, 1, text { "X" })

        assertEquals("<doc><p>Xhello</p></doc>", target.toXml())
    }
}
