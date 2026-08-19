package dev.yorkie.document.operation

import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        splitLevel: Int = 0,
    ): TreeEditOperation {
        val ticket = makeTicket(lamport)
        val (fromPos, toPos) = tree.indexRangeToPosRange(fromIndex to toIndex)
        return TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = fromPos,
            toPos = toPos,
            contents = contents,
            splitLevel = splitLevel,
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

    // Updated for stage E (port fa6cc513, spec 005): a pure deletion's
    // reverse now revives the removed node by ORIGINAL identity
    // (restoreSpans / RestoreMode.Restore) instead of copy-reinserting a
    // detached TreeNode snapshot with a fresh id at undo-apply time. The old
    // removedNodeSnapshots-based path is unreachable once CrdtTree.edit's
    // spansComplete guard is satisfied (no merge/split involved here), which
    // is always the case for these single-node deletions — see the AC15
    // disclosure in the round build report.
    @Test
    fun `reverse of pure delete is a pure insert`() {
        // given: tree <root><p>hello</p></root>
        val (tree, root) = buildTreeRoot()
        val pTicket = makeTicket(3)
        val pNode = CrdtTreeElement(CrdtTreeNodeID(pTicket, 0), "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        val helloID = CrdtTreeNodeID(makeTicket(4), 0)
        val helloNode = CrdtTreeText(helloID, "hello")
        makeTreeEditOp(tree, 1, 1, listOf(helloNode), 4).execute(root, OpSource.Local, null)
        // tree is now <root><p>hello</p></root>

        // when: delete the text node (indices 1..6 — the 5 chars)
        val op = makeTreeEditOp(tree, 1, 6, null, 5)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op restores "hello" by its ORIGINAL id, not a fresh copy
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as TreeEditOperation
        assertTrue(reverseOp.isUndoOp)
        assertTrue(reverseOp.isRestoreOp)
        assertEquals(null, reverseOp.contents)
        assertEquals(RestoreMode.Restore, reverseOp.restoreMode)
        val span = reverseOp.restoreSpans?.single()
        assertEquals(helloID, span?.id)
        assertEquals("hello", span?.value)
        assertTrue(span?.isText == true)
    }

    @Test
    fun `reverse of element delete restores the element node`() {
        // given: tree <root><p></p></root>
        val (tree, root) = buildTreeRoot()
        val pTicket = makeTicket(3)
        val pID = CrdtTreeNodeID(pTicket, 0)
        val pNode = CrdtTreeElement(pID, "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        // tree is <root><p></p></root> — indices 0=<root>, 1=<p>, 2=</p>, 3=</root>

        // when: delete the p element (index 0..2 includes start and end tags = size 2)
        val op = makeTreeEditOp(tree, 0, 2, null, 4)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse op restores the ORIGINAL <p> by identity, not a fresh copy
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as TreeEditOperation
        assertTrue(reverseOp.isUndoOp)
        assertTrue(reverseOp.isRestoreOp)
        assertEquals(null, reverseOp.contents)
        assertEquals(RestoreMode.Restore, reverseOp.restoreMode)
        val span = reverseOp.restoreSpans?.single()
        assertEquals(pID, span?.id)
        assertEquals("p", span?.nodeType)
        assertFalse(span?.isText == true)
    }

    // Updated for stage E (port fa6cc513, spec 005): both the "3" retombstone
    // and the "12" restore below now address their node by ORIGINAL identity
    // (restoreSpans/retombstoneSpans) instead of an index range or a fresh
    // ticket-9-derived copy. fromPos/toPos on an identity op are a vestigial
    // collapsed anchor (both equal actualFrom from the reversed edit's own
    // execution) — they no longer describe a real range, so the old
    // non-collapsed-range and fresh-id assertions no longer apply. See the
    // AC15 disclosure in the round build report.
    @Test
    fun `history edit retains the range and rebuilt contents it applies`() {
        // given: <root><p>123 456</p></root>, with "3" inserted by a separate change
        val (tree, root) = buildTreeRoot()
        val pNode = CrdtTreeElement(CrdtTreeNodeID(makeTicket(3), 0), "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        val prefixID = CrdtTreeNodeID(makeTicket(4), 0)
        val prefix = CrdtTreeText(prefixID, "12")
        makeTreeEditOp(tree, 1, 1, listOf(prefix), 4).execute(root, OpSource.Local, null)
        val threeID = CrdtTreeNodeID(makeTicket(5), 0)
        val three = CrdtTreeText(threeID, "3")
        val insertResult =
            makeTreeEditOp(tree, 3, 3, listOf(three), 5).execute(root, OpSource.Local, null)
        val suffix = CrdtTreeText(CrdtTreeNodeID(makeTicket(6), 0), " 456")
        makeTreeEditOp(tree, 4, 4, listOf(suffix), 6).execute(root, OpSource.Remote, null)
        assertEquals("<root><p>123 456</p></root>", tree.toXml())

        // when: the reverse of the "3" insert executes — an identity-preserving
        // retombstone of the ORIGINAL "3" node, not an index-based delete.
        val delete = insertResult.reverseOps.single() as TreeEditOperation
        assertTrue(delete.isRestoreOp)
        assertEquals(RestoreMode.Restore, delete.restoreMode)
        assertEquals(threeID, delete.retombstoneSpans?.single()?.id)
        delete.executedAt = makeTicket(7)
        val deleteResult = delete.execute(root, OpSource.UndoRedo, null)

        // then: "3" is retombstoned by identity and the surviving content
        // converges exactly as the old index-based delete did.
        assertTrue(deleteResult.opInfos.isNotEmpty())
        assertTrue(requireNotNull(tree.findFloorNode(threeID)).isRemoved)
        assertEquals("<root><p>12 456</p></root>", tree.toXml())

        // and: undoing the "12" deletion revives the ORIGINAL node by
        // identity (same ticket-4 id) — no fresh copy, no new ticket.
        val removeResult = makeTreeEditOp(tree, 1, 3, null, 8).execute(root, OpSource.Local, null)
        val restore = removeResult.reverseOps.single() as TreeEditOperation
        assertTrue(restore.isRestoreOp)
        assertEquals(RestoreMode.Restore, restore.restoreMode)
        assertEquals(prefixID, restore.restoreSpans?.single()?.id)
        restore.executedAt = makeTicket(9)
        restore.execute(root, OpSource.UndoRedo, null)

        val revived = requireNotNull(tree.findFloorNode(prefixID))
        assertFalse(revived.isRemoved)
        assertEquals(prefixID, revived.id)
        assertEquals("<root><p>12 456</p></root>", tree.toXml())
    }

    // #1234: pure splits at any level > 0 produce a boundary-deletion reverse op tagged
    // with redoSplitLevel so redo re-splits (was only splitLevel == 1 before).

    @Test
    fun `reverse of pure L1 split is a boundary-deletion tagged for redo`() {
        // given: <root><p>ab</p></root>
        val (tree, root) = buildTreeRoot()
        val pNode = CrdtTreeElement(CrdtTreeNodeID(makeTicket(3), 0), "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        val textNode = CrdtTreeText(CrdtTreeNodeID(makeTicket(4), 0), "ab")
        makeTreeEditOp(tree, 1, 1, listOf(textNode), 4).execute(root, OpSource.Local, null)

        // when: pure splitLevel=1 split between a and b
        val op = makeTreeEditOp(tree, 2, 2, null, 5, splitLevel = 1)
        val result = op.execute(root, OpSource.Local, null)

        // then: reverse is a splitLevel=0 boundary deletion tagged redoSplitLevel=1
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as TreeEditOperation
        assertTrue(reverseOp.isUndoOp)
        assertEquals(null, reverseOp.contents)
        assertEquals(0, reverseOp.splitLevel)
        assertEquals(1, reverseOp.redoSplitLevel)
    }

    @Test
    fun `reverse of pure L2 split is generated`() {
        // given: <root><div><p>ab</p></div></root>
        val (tree, root) = buildTreeRoot()
        val divNode = CrdtTreeElement(CrdtTreeNodeID(makeTicket(3), 0), "div")
        makeTreeEditOp(tree, 0, 0, listOf(divNode), 3).execute(root, OpSource.Local, null)
        val pNode = CrdtTreeElement(CrdtTreeNodeID(makeTicket(4), 0), "p")
        makeTreeEditOp(tree, 1, 1, listOf(pNode), 4).execute(root, OpSource.Local, null)
        val textNode = CrdtTreeText(CrdtTreeNodeID(makeTicket(5), 0), "ab")
        makeTreeEditOp(tree, 2, 2, listOf(textNode), 5).execute(root, OpSource.Local, null)

        // when: pure splitLevel=2 split (splits both p and div)
        val op = makeTreeEditOp(tree, 3, 3, null, 6, splitLevel = 2)
        val result = op.execute(root, OpSource.Local, null)

        // then: a reverse op IS produced (before #1234, splitLevel>1 gave null)
        assertEquals(1, result.reverseOps.size)
        val reverseOp = result.reverseOps[0] as TreeEditOperation
        assertTrue(reverseOp.isUndoOp)
        assertEquals(null, reverseOp.contents)
        assertEquals(0, reverseOp.splitLevel)
        assertEquals(2, reverseOp.redoSplitLevel)
    }

    @Test
    fun `split with content is not treated as a pure split`() {
        // given: <root><p>ab</p></root>
        val (tree, root) = buildTreeRoot()
        val pNode = CrdtTreeElement(CrdtTreeNodeID(makeTicket(3), 0), "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        val textNode = CrdtTreeText(CrdtTreeNodeID(makeTicket(4), 0), "ab")
        makeTreeEditOp(tree, 1, 1, listOf(textNode), 4).execute(root, OpSource.Local, null)

        // when: splitLevel=1 AND inserting content — not a pure split
        val insNode = CrdtTreeText(CrdtTreeNodeID(makeTicket(5), 0), "X")
        val op = makeTreeEditOp(tree, 2, 2, listOf(insNode), 5, splitLevel = 1)
        val result = op.execute(root, OpSource.Local, null)

        // then: no reverse op (isPureSplit false, splitLevel != 0)
        assertTrue(result.reverseOps.isEmpty())
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
    fun `collapsed history edit emits no change or reverse operation`() {
        val (tree, root) = buildTreeRoot()
        val pNode = CrdtTreeElement(CrdtTreeNodeID(makeTicket(3), 0), "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        val pos = tree.indexRangeToPosRange(1 to 1).first
        val op =
            TreeEditOperation(
                parentCreatedAt = treeTicket,
                fromPos = pos,
                toPos = pos,
                contents = null,
                splitLevel = 0,
                executedAt = makeTicket(4),
                undoFromOffset = 1,
                undoToOffset = 1,
            )

        val result = op.execute(root, OpSource.UndoRedo, null)

        assertTrue(result.opInfos.isEmpty())
        assertTrue(result.reverseOps.isEmpty())
        assertEquals("<root><p></p></root>", tree.toXml())
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

    // spec 006 AC4, at the operation level: restoring a sub-range of a
    // tombstoned run splits off born-removed remainders, and executeRestore
    // must register those pending pairs BEFORE unregistering the
    // untombstoned target — the target IS one of the freshly split nodes.
    // CrdtRoot.registerGCPair toggles on a re-registered key and
    // unregisterGCPair no-ops on a missing one, so the flipped order leaves
    // the LIVE target keyed in gcPairMap, visible here as phantom garbage.
    @Test
    fun `restore of a sub-range leaves exactly the split remainders as garbage`() {
        // given: <root>0123456789</root> with [3,9) deleted as one run
        val (tree, root) = buildTreeRoot()
        val textNode = CrdtTreeText(CrdtTreeNodeID(makeTicket(3), 0), "0123456789")
        makeTreeEditOp(tree, 0, 0, listOf(textNode), 3).execute(root, OpSource.Local, null)
        val deleteResult = makeTreeEditOp(tree, 3, 9, null, 4).execute(root, OpSource.Local, null)
        assertEquals(1, root.garbageLength, "the deleted run is one GC pair")

        // when: undo only [5,7) of it — the reverse op with its span narrowed
        val restore = deleteResult.reverseOps.single() as TreeEditOperation
        val span = requireNotNull(restore.restoreSpans).single()
        val narrowed = span.copy(
            id = span.id.copy(offset = 5),
            length = 2,
            value = span.value?.substring(2, 4),
        )
        restore.copy(restoreSpans = listOf(narrowed), executedAt = makeTicket(5))
            .execute(root, OpSource.UndoRedo, null)

        // then: "56" is live and out of the gc map; the [3,5) and [7,9)
        // remainders are the only garbage
        assertEquals("<root>012569</root>", tree.toXml())
        assertEquals(
            2,
            root.garbageLength,
            "exactly the two born-removed remainders may stay garbage",
        )
    }
}
