package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.issueTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports `tree_restore_span_kind_test.ts` (JS SDK `5c8dd738`, #1475):
 * [CrdtTree.restore] and [CrdtTree.retombstone] both skip a span whose
 * [TreeRestoreSpan.isText] flag disagrees with its
 * [TreeRestoreSpan.nodeType] -- a span the SDK itself never captures, but a
 * decoded/forged one (a stored history edited by hand, or a corrupted
 * payload) can carry. Neither path may throw or act on it.
 */
class TreeRestoreSpanKindTest {

    private fun buildTombstonedElement(): Pair<CrdtTree, CrdtTreeNodeID> {
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        val pID = CrdtTreeNodeID(issueTime(), 0)
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeElement(pID, "p")),
            0,
            issueTime(),
        )
        tree.edit(tree.findPos(0) to tree.findPos(2), null, 0, issueTime())
        assertEquals("<root></root>", tree.toXml())
        return tree to pID
    }

    private fun buildLiveText(): Pair<CrdtTree, CrdtTreeNodeID> {
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        val textID = CrdtTreeNodeID(issueTime(), 0)
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeText(textID, "ab")),
            0,
            issueTime(),
        )
        assertEquals("<root>ab</root>", tree.toXml())
        return tree to textID
    }

    @Test
    fun `restore skips a span whose isText disagrees with an element nodeType`() {
        val (tree, pID) = buildTombstonedElement()
        // isText=true but nodeType="p" (never DEFAULT_TEXT_TYPE): forged,
        // not a shape restore() ever captures on its own.
        val forged = TreeRestoreSpan(id = pID, nodeType = "p", isText = true, length = 0)

        val result = tree.restore(listOf(forged), issueTime())

        assertTrue(result.untombstoned.isEmpty())
        assertTrue(result.recreated.isEmpty())
        assertEquals("<root></root>", tree.toXml(), "the tombstoned p must stay tombstoned")
    }

    @Test
    fun `retombstone skips a span whose isText disagrees with an element nodeType`() {
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        val pID = CrdtTreeNodeID(issueTime(), 0)
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeElement(pID, "p")),
            0,
            issueTime(),
        )
        assertEquals("<root><p></p></root>", tree.toXml())
        val forged = TreeRestoreSpan(id = pID, nodeType = "p", isText = true, length = 0)

        val (pairs, _) = tree.retombstone(listOf(forged), issueTime())

        assertTrue(pairs.isEmpty())
        assertEquals("<root><p></p></root>", tree.toXml(), "the live p must stay live")
    }

    @Test
    fun `restore skips a span whose isText disagrees with a text nodeType`() {
        val (tree, textID) = buildLiveText()
        tree.retombstone(
            listOf(TreeRestoreSpan(id = textID, nodeType = "text", isText = true, length = 2)),
            issueTime(),
        )
        assertEquals("<root></root>", tree.toXml())
        // isText=false but nodeType="text" (DEFAULT_TEXT_TYPE): forged.
        val forged = TreeRestoreSpan(id = textID, nodeType = "text", isText = false, length = 0)

        val result = tree.restore(listOf(forged), issueTime())

        assertTrue(result.untombstoned.isEmpty())
        assertTrue(result.recreated.isEmpty())
        assertEquals("<root></root>", tree.toXml())
    }
}
