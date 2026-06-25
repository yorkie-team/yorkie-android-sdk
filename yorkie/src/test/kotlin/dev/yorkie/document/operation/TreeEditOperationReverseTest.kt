package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.TreeElementNode
import dev.yorkie.document.crdt.TreeTextNode
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class TreeEditOperationReverseTest {

    private val rootTicket = TimeTicket(1L, 0u, "actor-0")
    private val treeTicket = TimeTicket(2L, 0u, "actor-0")

    private fun makeTicket(lamport: Long): TimeTicket = TimeTicket(lamport, 0u, "actor-0")

    private fun buildTreeRoot(): Pair<CrdtTree, CrdtRoot> {
        val rootNode = CrdtTreeElement(CrdtTreeNodeID(treeTicket, 0), DEFAULT_ROOT_TYPE)
        val tree = CrdtTree(rootNode, treeTicket)
        val obj = CrdtObject(createdAt = rootTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        root.rootObject.set("tree", tree, treeTicket)
        root.registerElement(tree, root.rootObject)
        return tree to root
    }

    private fun makeTreeEditOp(
        tree: CrdtTree,
        fromIndex: Int,
        toIndex: Int,
        contents: List<CrdtTreeNode>?,
        lamport: Long,
    ): TreeEditOperation {
        val ticket = makeTicket(lamport)
        val (fromPos, toPos) = tree.indexRangeToPosRange(fromIndex to toIndex)
        return TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = contents,
            splitLevel = 0,
            executedAt = ticket,
        )
    }

    @Test
    fun `reverse of pure insert is a pure delete`() {
        // given: tree is <root><p></p></root>
        val (tree, root) = buildTreeRoot()
        val pTicket = makeTicket(3)
        val pNode = CrdtTreeElement(CrdtTreeNodeID(pTicket, 0), "p")
        val insertPOp = makeTreeEditOp(tree, 0, 0, listOf(pNode), 3)
        insertPOp.execute(root, OpSource.Local, null)
        // tree is now <root><p></p></root>, size = 2

        // when: insert a text node at index 1 (inside p)
        val textTicket = makeTicket(4)
        val textNode = CrdtTreeText(CrdtTreeNodeID(textTicket, 0), "hello")
        val op = makeTreeEditOp(tree, 1, 1, listOf(textNode), 4)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op should delete what was inserted — no re-insert content
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as TreeEditOperation
        assertTrue(reverseOp.isUndoOp)
        assertEquals(null, reverseOp.contents)
    }

    @Test
    fun `reverse of pure delete is a pure insert`() {
        // given: tree <root><p>hello</p></root>
        val (tree, root) = buildTreeRoot()
        val pTicket = makeTicket(3)
        val pNode = CrdtTreeElement(CrdtTreeNodeID(pTicket, 0), "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        val helloTicket = makeTicket(4)
        val helloNode = CrdtTreeText(CrdtTreeNodeID(helloTicket, 0), "hello")
        makeTreeEditOp(tree, 1, 1, listOf(helloNode), 4).execute(root, OpSource.Local, null)
        // tree is now <root><p>hello</p></root>

        // when: delete the text node (indices 1..6 — the 5 chars)
        val op = makeTreeEditOp(tree, 1, 6, null, 5)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op should re-insert the deleted text node via snapshots
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as TreeEditOperation
        assertTrue(reverseOp.isUndoOp)
        assertEquals(null, reverseOp.contents)
        val snapshots = reverseOp.removedNodeSnapshots
        assertNotNull(snapshots)
        assertTrue(snapshots.isNotEmpty())
        val snapshot = snapshots[0] as TreeTextNode
        assertEquals("hello", snapshot.value)
    }

    @Test
    fun `reverse of element delete restores the element node`() {
        // given: tree <root><p></p></root>
        val (tree, root) = buildTreeRoot()
        val pTicket = makeTicket(3)
        val pNode = CrdtTreeElement(CrdtTreeNodeID(pTicket, 0), "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        // tree is <root><p></p></root> — indices 0=<root>, 1=<p>, 2=</p>, 3=</root>

        // when: delete the p element (index 0..2 includes start and end tags = size 2)
        val op = makeTreeEditOp(tree, 0, 2, null, 4)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op should re-insert the p element via snapshots
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as TreeEditOperation
        assertTrue(reverseOp.isUndoOp)
        assertEquals(null, reverseOp.contents)
        val snapshots = reverseOp.removedNodeSnapshots
        assertNotNull(snapshots)
        assertTrue(snapshots.isNotEmpty())
        val snapshot = snapshots[0] as TreeElementNode
        assertEquals("p", snapshot.type)
    }

    @Test
    fun `isUndoOp is false for non-undo operations`() {
        val ticket = makeTicket(3)
        val (tree, _) = buildTreeRoot()
        val (fromPos, toPos) = tree.indexRangeToPosRange(0 to 0)
        val op = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = null,
            splitLevel = 0,
            executedAt = ticket,
        )
        assertFalse(op.isUndoOp)
    }

    @Test
    fun `isUndoOp is true when undoFromOffset is set`() {
        val ticket = makeTicket(3)
        val (tree, _) = buildTreeRoot()
        val (fromPos, toPos) = tree.indexRangeToPosRange(0 to 0)
        val op = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = null,
            splitLevel = 0,
            executedAt = ticket,
            undoFromOffset = 2,
            undoToOffset = 5,
        )
        assertTrue(op.isUndoOp)
    }

    @Test
    fun `reconcileOperation shifts range when remote insert is left of undo range`() {
        // given: undo range [4, 7), remote insert at [1, 1) inserts 3 chars
        val ticket = makeTicket(3)
        val (tree, _) = buildTreeRoot()
        val (fromPos, toPos) = tree.indexRangeToPosRange(0 to 0)
        val op = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = null,
            splitLevel = 0,
            executedAt = ticket,
            undoFromOffset = 4,
            undoToOffset = 7,
        )

        op.reconcileOperation(remoteFrom = 1, remoteTo = 1, remoteContentSize = 3)

        // both endpoints shift by +3
        assertEquals(7, op.undoFromOffset)
        assertEquals(10, op.undoToOffset)
    }

    @Test
    fun `reconcileOperation does nothing when remote edit is right of undo range`() {
        val ticket = makeTicket(3)
        val (tree, _) = buildTreeRoot()
        val (fromPos, toPos) = tree.indexRangeToPosRange(0 to 0)
        val op = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = null,
            splitLevel = 0,
            executedAt = ticket,
            undoFromOffset = 2,
            undoToOffset = 5,
        )

        op.reconcileOperation(remoteFrom = 6, remoteTo = 8, remoteContentSize = 1)

        assertEquals(2, op.undoFromOffset)
        assertEquals(5, op.undoToOffset)
    }

    @Test
    fun `reconcileOperation collapses range when remote contains undo range`() {
        val ticket = makeTicket(3)
        val (tree, _) = buildTreeRoot()
        val (fromPos, toPos) = tree.indexRangeToPosRange(0 to 0)
        val op = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = null,
            splitLevel = 0,
            executedAt = ticket,
            undoFromOffset = 3,
            undoToOffset = 6,
        )

        // remote deletes [1, 8) and inserts 2 nodes
        op.reconcileOperation(remoteFrom = 1, remoteTo = 8, remoteContentSize = 2)

        // collapses to point at remoteFrom + remoteContentSize
        assertEquals(3, op.undoFromOffset)
        assertEquals(3, op.undoToOffset)
    }

    @Test
    fun `no reverse op generated for remote operations`() {
        // given: tree <root><p></p></root>
        val (tree, root) = buildTreeRoot()
        val pTicket = makeTicket(3)
        val pNode = CrdtTreeElement(CrdtTreeNodeID(pTicket, 0), "p")
        val op = makeTreeEditOp(tree, 0, 0, listOf(pNode), 3)
        val result = op.execute(root, OpSource.Remote, null)

        // then: no reverse ops for remote operations
        assertTrue(result.reverseOps.isEmpty())
    }
}
