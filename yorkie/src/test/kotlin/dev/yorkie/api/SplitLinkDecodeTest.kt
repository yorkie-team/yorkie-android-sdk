package dev.yorkie.api

import dev.yorkie.api.v1.treeNode
import dev.yorkie.api.v1.treeNodes
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
 *
 * Extended for spec 035 unit 1E (JS SDK b69169c0, #1404): operation content
 * also rejects an entry decoding to no node and a depth naming no parent
 * (the latter SHARED with the snapshot decode path, [SnapshotConverterTest]
 * pins that half), and strips merge stamps (`dropMergeStamps`) alongside
 * the split-sibling links.
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

    // RED-then-GREEN target: `toCrdtTreeNodesWhenEdit`'s `dropEngineOnlyLinks()`
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

    // RED-then-GREEN target: `toCrdtTreeNodesWhenEdit`'s rejection of an
    // entry whose own `contentList` is empty (spec 035 D9, unit 1E).
    // Reverting the throw back to the old `mapNotNull { ... }` silently
    // drops the entry instead -- an empty result list (the only entry) then
    // reads as "delete the range", not an insert.
    @Test
    fun `an edit-content entry decoding to no node is rejected`() = runTest {
        val exception = assertFailsWith<YorkieException> {
            listOf(treeNodes { }).toCrdtTreeNodesWhenEdit()
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertEquals("tree edit content has an entry with no node", exception.errorMessage)
    }

    // RED-then-GREEN target: `toCrdtTreeRootNode`'s rejection of a depth
    // that names no parent (spec 035 D9, unit 1E). Reverting the throw back
    // to the old `?: continue` silently drops the orphaned node instead.
    // This reader is SHARED with the snapshot decode path --
    // `SnapshotConverterTest` pins that half of the same hunk.
    @Test
    fun `an edit-content node whose depth names an unwritten parent is rejected`() = runTest {
        val root = treeNode {
            id = CrdtTreeNodeID(TimeTicket(1, 0u, "000000000000000000000001"), 0).toPBTreeNodeID()
            type = "r"
            depth = 0
        }
        val orphan = treeNode {
            id = CrdtTreeNodeID(TimeTicket(2, 0u, "000000000000000000000001"), 0).toPBTreeNodeID()
            type = "p"
            depth = 2 // Depth 1 was never written -- a hole.
        }

        val exception = assertFailsWith<YorkieException> {
            listOf(treeNodes { content.addAll(listOf(orphan, root)) }).toCrdtTreeNodesWhenEdit()
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertEquals("invalid tree node depth: 2", exception.errorMessage)
    }

    // RED-then-GREEN target: `toCrdtTreeNodesWhenEdit`'s new `clearTombstones()`
    // call (spec 035 D10, unit 1E). Reverting it back to a bare
    // `dropEngineOnlyLinks()` call leaves a forged merge stamp on the decoded
    // node -- the exact shape a peer could use to steer `declaredBoundaries`
    // off a freshly inserted node.
    @Test
    fun `an edit-content decode strips merge stamps from its root node`() = runTest {
        val tree = splitTree()
        val node = requireNotNull(findNodeWithDirectLink(tree.root)) {
            "precondition: the built tree has a node with a direct split-sibling link"
        }
        val forgedActor = "000000000000000000000009"
        node.mergedFrom = CrdtTreeNodeID(TimeTicket(99, 0u, forgedActor), 0)
        node.mergedAt = TimeTicket(99, 0u, forgedActor)
        node.mergedInto = CrdtTreeNodeID(TimeTicket(99, 0u, forgedActor), 0)

        val decoded = listOf(node).toPBTreeNodesWhenEdit().toCrdtTreeNodesWhenEdit()
        val decodedNode = requireNotNull(decoded).single()
        assertNull(decodedNode.mergedFrom)
        assertNull(decodedNode.mergedAt)
        assertNull(decodedNode.mergedInto)
    }
}
