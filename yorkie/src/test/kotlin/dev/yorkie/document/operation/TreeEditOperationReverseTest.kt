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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // AC10: the re-split path, NOT identity restore — asserted directly on
        // the reverse op, since both paths render the same XML here.
        assertFalse(reverseOp.isRestoreOp)
        assertNull(reverseOp.restoreSpans)
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
        assertFalse(reverseOp.isRestoreOp)
        assertNull(reverseOp.restoreSpans)
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

    // B1 (review #360): an ordinary undo op consumes the resolved offset range
    // as its actual edit range. Clamping a stale offset to the live size would
    // re-insert the content at the END of the document and push that
    // misplacement to every peer as a concrete fromPos/toPos, so an
    // out-of-range offset must fail loudly instead.
    @Test
    fun `undo op whose offsets exceed the live size does not relocate content`() {
        // given: <root><p>hello</p></root> — size 7, so offsets 8..12 are stale
        // (e.g. the doc shrank from a concurrent remote delete after this undo
        // op was pushed onto the stack)
        val (tree, root) = buildTreeRoot()
        val pNode = CrdtTreeElement(CrdtTreeNodeID(makeTicket(3), 0), "p")
        makeTreeEditOp(tree, 0, 0, listOf(pNode), 3).execute(root, OpSource.Local, null)
        val helloNode = CrdtTreeText(CrdtTreeNodeID(makeTicket(4), 0), "hello")
        makeTreeEditOp(tree, 1, 1, listOf(helloNode), 4).execute(root, OpSource.Local, null)
        val before = tree.toXml()
        assertEquals(7, tree.size)

        val pos = tree.indexRangeToPosRange(0 to 0).first
        val staleUndo = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = pos,
            toPos = pos,
            contents = listOf(CrdtTreeText(CrdtTreeNodeID(makeTicket(9), 0), "stale")),
            splitLevel = 0,
            executedAt = makeTicket(10),
            undoFromOffset = 8,
            undoToOffset = 12,
        )

        // when / then: rejected, and the tree is untouched — no content lands
        // at the clamped (end-of-document) position
        assertFailsWith<IllegalArgumentException> {
            staleUndo.execute(root, OpSource.UndoRedo, null)
        }
        assertEquals(before, tree.toXml())
    }

    // I4 (review #360): a merge-involved delete must reverse via copy-reinsert
    // (a split), never via identity restore — CrdtTree.edit's spansComplete
    // guard. Asserted on the reverse op itself: the XML round-trip is identical
    // whichever path runs, so it cannot tell them apart.
    @Test
    fun `reverse of a merge-involved delete is copy-reinsert, not identity restore`() {
        // given: <root><p>ab</p><p>cd</p></root>
        val (tree, root) = buildTreeRoot()
        makeTreeEditOp(
            tree,
            0,
            0,
            listOf(CrdtTreeElement(CrdtTreeNodeID(makeTicket(3), 0), "p")),
            3,
        ).execute(root, OpSource.Local, null)
        makeTreeEditOp(
            tree,
            1,
            1,
            listOf(CrdtTreeText(CrdtTreeNodeID(makeTicket(4), 0), "ab")),
            4,
        ).execute(root, OpSource.Local, null)
        makeTreeEditOp(
            tree,
            4,
            4,
            listOf(CrdtTreeElement(CrdtTreeNodeID(makeTicket(5), 0), "p")),
            5,
        ).execute(root, OpSource.Local, null)
        makeTreeEditOp(
            tree,
            5,
            5,
            listOf(CrdtTreeText(CrdtTreeNodeID(makeTicket(6), 0), "cd")),
            6,
        ).execute(root, OpSource.Local, null)
        assertEquals("<root><p>ab</p><p>cd</p></root>", tree.toXml())

        // when: delete the </p><p> boundary — a merge, not a plain deletion
        val result = makeTreeEditOp(tree, 3, 5, null, 7).execute(root, OpSource.Local, null)
        assertEquals("<root><p>abcd</p></root>", tree.toXml())

        // then: the reverse is a split, carrying no identity payload
        val reverseOp = result.reverseOps.single() as TreeEditOperation
        assertFalse(reverseOp.isRestoreOp)
        assertNull(reverseOp.restoreSpans)
        assertNull(reverseOp.retombstoneSpans)
        assertEquals(1, reverseOp.splitLevel)
    }

    // I4 (review #360): the reverse span payload itself — non-empty, no
    // duplicate ids, every parentID resolvable, parent BEFORE child (restore()
    // recreates top-down and silently drops a child whose parent is missing),
    // and nodes tombstoned before this edit excluded.
    @Test
    fun `restore span payload is parent-first and excludes pre-tombstoned nodes`() {
        // given: <root><p>hello</p></root> with "el" already tombstoned
        val (tree, root) = buildTreeRoot()
        makeTreeEditOp(
            tree,
            0,
            0,
            listOf(CrdtTreeElement(CrdtTreeNodeID(makeTicket(3), 0), "p")),
            3,
        ).execute(root, OpSource.Local, null)
        makeTreeEditOp(
            tree,
            1,
            1,
            listOf(CrdtTreeText(CrdtTreeNodeID(makeTicket(4), 0), "hello")),
            4,
        ).execute(root, OpSource.Local, null)
        val preTombstone = makeTreeEditOp(tree, 2, 4, null, 5)
            .execute(root, OpSource.Local, null)
        val preTombstonedID =
            (preTombstone.reverseOps.single() as TreeEditOperation).restoreSpans!!.single().id
        assertEquals("<root><p>hlo</p></root>", tree.toXml())

        // when: delete the whole <p>, which spans the pre-tombstoned "el"
        val result = makeTreeEditOp(tree, 0, tree.size, null, 6)
            .execute(root, OpSource.Local, null)
        val spans = (result.reverseOps.single() as TreeEditOperation).restoreSpans
        requireNotNull(spans)

        // then
        assertTrue(spans.isNotEmpty())
        assertEquals(spans.size, spans.map { it.id }.distinct().size, "no duplicate span ids")
        assertTrue(spans.none { it.id == preTombstonedID }, "pre-tombstoned node excluded")
        assertTrue(spans.all { it.parentID != null }, "every span records its parent")
        val indexByID = spans.withIndex().associate { (i, span) -> span.id to i }
        spans.forEachIndexed { index, span ->
            val parentIndex = indexByID[span.parentID] ?: return@forEachIndexed
            assertTrue(
                parentIndex < index,
                "parent ${span.parentID} must precede its child ${span.id}",
            )
        }
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
