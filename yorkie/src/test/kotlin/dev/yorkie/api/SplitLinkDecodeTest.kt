package dev.yorkie.api

import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Converter-level coverage of the Commit 1 (JS SDK c8928853, #1375) decode
 * split: a Set/Add/ArraySet payload (`PBJsonElementSimple.toCrdtElement`,
 * directly or nested via `dropSplitLinksInElement`'s `CrdtContainer` walk)
 * and operation content (`toCrdtTreeNodesWhenEdit`) both strip
 * `insPrevID`/`insNextID` on the way in, while a snapshot decode
 * (`PBJsonElement.toCrdtElement`) is untouched and keeps them -- spec 033
 * AC1, Commit 1 step 10.
 */
class SplitLinkDecodeTest {

    private fun hasAnySplitLink(node: CrdtTreeNode): Boolean {
        return node.insPrevID != null || node.insNextID != null ||
            node.allChildren.any(::hasAnySplitLink)
    }

    /** The first descendant of (or [node] itself) that directly carries a split-sibling link. */
    private fun findNodeWithDirectLink(node: CrdtTreeNode): CrdtTreeNode? {
        if (node.insPrevID != null || node.insNextID != null) return node
        return node.allChildren.firstNotNullOfOrNull(::findNodeWithDirectLink)
    }

    /** A real [CrdtTree] whose span has been split, built through [Document] so its split-sibling links are genuine. */
    private suspend fun splitTree(): CrdtTree {
        val document = Document("test-doc")
        document.setActor("000000000000000000000001")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") {
                        element("span") {
                            text { "abcde" }
                        }
                    }
                },
            )
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 3), listOf(0, 0, 3), splitLevel = 1)
        }.await()
        return document.getRootObject()["t"] as CrdtTree
    }

    // RED-then-GREEN target: `PBJsonElementSimple.toCrdtElement`'s
    // VALUE_TYPE_TREE branch. Reverting the `dropSplitLinksInElement(...)`
    // wrap back to a bare `value.toCrdtTree()` leaves these links in place.
    @Test
    fun `a simple-element decode strips a tree payload's split links`() = runTest {
        val tree = splitTree()
        assertTrue(hasAnySplitLink(tree.root), "precondition: the built tree actually has links")

        val decoded = tree.toPBJsonElementSimple().toCrdtElement() as CrdtTree
        assertFalse(hasAnySplitLink(decoded.root))
    }

    // Same target, through `dropSplitLinksInElement`'s CrdtContainer branch:
    // a tree nested inside an object member must be stripped too, not only
    // a tree sitting directly at the payload's root.
    @Test
    fun `a simple-element decode strips a nested tree's split links too`() = runTest {
        val tree = splitTree()
        val container = CrdtObject(InitialTimeTicket).apply { set("child", tree, tree.createdAt) }

        val decoded = container.toPBJsonElementSimple().toCrdtElement() as CrdtObject
        val decodedTree = decoded["child"] as CrdtTree
        assertFalse(hasAnySplitLink(decodedTree.root))
    }

    // RED-then-GREEN target: `toCrdtTreeNodesWhenEdit`'s `dropSplitLinks()`
    // call. Reverting it back to the bare `toCrdtTreeRootNode()` mapping
    // leaves the decoded content holding whatever links the wire bytes
    // carried.
    @Test
    fun `an edit-content decode strips split links from its root node`() = runTest {
        val tree = splitTree()
        val splitProduct = requireNotNull(findNodeWithDirectLink(tree.root)) {
            "precondition: the built tree has a node with a direct split-sibling link"
        }

        val decoded = listOf(splitProduct).toPBTreeNodesWhenEdit().toCrdtTreeNodesWhenEdit()
        assertFalse(hasAnySplitLink(requireNotNull(decoded).single()))
    }

    // Negative control: the snapshot decode path (`PBJsonElement.
    // toCrdtElement`) is explicitly untouched by this commit -- a tree
    // restored from a stored snapshot keeps whatever split-sibling links it
    // had when the snapshot was taken.
    @Test
    fun `a snapshot decode keeps a tree's split links`() = runTest {
        val tree = splitTree()
        assertTrue(hasAnySplitLink(tree.root))

        val decoded = tree.toPBTree().toCrdtElement() as CrdtTree
        assertTrue(hasAnySplitLink(decoded.root))
    }
}
