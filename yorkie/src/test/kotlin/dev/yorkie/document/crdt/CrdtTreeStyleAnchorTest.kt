package dev.yorkie.document.crdt

import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.helper.crossSync
import dev.yorkie.issueTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `5c158690` (style ranges vs merge anchor) as JVM unit tests (AC5).
 */
class CrdtTreeStyleAnchorTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun issuePos(offset: Int = 0) = CrdtTreeNodeID(issueTime(), offset)

    private fun CrdtTreeNode.toList() = listOf(this)

    private fun CrdtTree.edit(range: Pair<Int, Int>, nodes: List<CrdtTreeNode>?) {
        val fromPos = findPos(range.first)
        val toPos = findPos(range.second)
        edit(fromPos to toPos, nodes, 0, issueTime(), ::issueTime)
    }

    // AC5 (unit): port 5c158690 tree_test.ts 'resolves a range boundary
    // right after the merge-source tombstone'.
    @Test
    fun `resolves a range boundary right after the merge-source tombstone`() {
        // 01. Create <root><p>ab</p><p>cd</p></root>.
        //       0   1 2 3    4   5 6 7     8
        // <root> <p> a b </p> <p> c d </p>  </root>
        val tree = CrdtTree(CrdtTreeElement(issuePos(), "root"), issueTime())
        tree.edit(0 to 0, CrdtTreeElement(issuePos(), "p").toList())
        tree.edit(1 to 1, CrdtTreeText(issuePos(), "ab").toList())
        tree.edit(4 to 4, CrdtTreeElement(issuePos(), "p").toList())
        tree.edit(5 to 5, CrdtTreeText(issuePos(), "cd").toList())
        assertEquals("<root><p>ab</p><p>cd</p></root>", tree.toXml())

        // 02. Capture the leftmost position inside the second paragraph,
        // then merge the second paragraph into the first.
        val pos = tree.findPos(5)
        tree.edit(3 to 5, null)
        assertEquals("<root><p>abcd</p></root>", tree.toXml())

        // 03. An insert boundary resolves into the merge target before the
        // moved children, while a range boundary resolves right after the
        // merge-source tombstone, leaving the moved children outside.
        val (insertParent, insertLeft) = tree.findNodesAndSplitText(pos, issueTime()).first
        assertEquals(3, tree.toIndex(insertParent, insertLeft))

        val (rangeParent, rangeLeft) =
            tree.findNodesAndSplitText(pos, issueTime(), Boundary.Range).first
        assertTrue(rangeLeft.isRemoved)
        assertEquals(6, tree.toIndex(rangeParent, rangeLeft))
    }

    // AC5 (case 1): port tree_style_anchor_test.ts 'does not style a node
    // concurrently inserted at the merged anchor'.
    @Test
    fun `does not style a node concurrently inserted at the merged anchor`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "tree",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d1 inserts an empty <p> after the second paragraph, then styles a
        // range that ends inside the second paragraph. The inserted <p> is
        // outside the styled range on d1's view.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(8, 8, element("p")) }.await()
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("tree").style(0, 5, mapOf("bold" to "x")) }
            .await()
        // d2 concurrently removes the range, merging across the paragraphs.
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(0, 5) }.await()

        crossSync(d1, d2)

        assertEquals("<r><p></p>cd</r>", d1.getRoot().getAs<JsonTree>("tree").toXml())
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC5 (case 2): port tree_style_anchor_test.ts 'does not leave
    // attributes from removeStyle on a concurrently inserted node'.
    @Test
    fun `does not leave attributes from removeStyle on a concurrently inserted node`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "tree",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(8, 8, element("p")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").removeStyle(0, 5, listOf("bold"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(0, 5) }.await()

        crossSync(d1, d2)

        assertEquals("<r><p></p>cd</r>", d1.getRoot().getAs<JsonTree>("tree").toXml())
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC5 (case 3): port tree_style_anchor_test.ts 'converges when the
    // range ends inside a chain-merged paragraph'.
    @Test
    fun `converges when the range ends inside a chain-merged paragraph`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "tree",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                    element("p") { text { "ef" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d1: insert an empty <p> at the end, then style a range ending at
        // the leftmost position inside the third paragraph. d2 concurrently
        // chain-merges: p3 into p2, then p2 into p1, so the range boundary
        // resolves through a merge-source whose target is itself removed.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(12, 12, element("p")) }
            .await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").style(0, 9, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(7, 9) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(3, 5) }.await()

        crossSync(d1, d2)

        assertEquals(
            "<r><p bold=\"x\">abcdef</p><p></p></r>",
            d1.getRoot().getAs<JsonTree>("tree").toXml(),
        )
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC5 (case 4): port tree_style_anchor_test.ts 'still styles the merged
    // content when the range covers it'.
    @Test
    fun `still styles the merged content when the range covers it`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "tree",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // The styled range covers the second paragraph entirely, so the
        // style lands on it regardless of the concurrent merge removing the
        // first paragraph.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("tree").style(4, 8, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(0, 4) }.await()

        crossSync(d1, d2)

        assertEquals("<r><p bold=\"x\">cd</p></r>", d1.getRoot().getAs<JsonTree>("tree").toXml())
        assertEquals(d1.toJson(), d2.toJson())
    }
}
