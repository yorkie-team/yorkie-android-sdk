package dev.yorkie.document.crdt

import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.issueTime
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports `tree_duplicate_id_test.ts` (JS SDK 2ed28322) as JVM unit tests
 * (AC1-AC4): a duplicate [CrdtTreeNodeID] — as carried by a document written
 * by an older client whose copy-reinsert undo kept the removed nodes'
 * original IDs — must resolve to the live node regardless of registration
 * order or snapshot rebuild, a guarded purge must not unregister a live
 * twin, [CrdtTree.dropDuplicateContents] must drop only genuine cross-change
 * reuse, and accepted-size threading must keep a dropped copy's reverse
 * operation from widening past what was truly inserted. Also pins the two
 * ordering-sensitive twins (cross-judge round-2 conditions): the drop check
 * runs AFTER step-01 range resolution (a split can create the very ID a
 * content carries), and an edit anchored through a duplicated ID lands
 * identically on a live tree and on one rebuilt by the constructor.
 *
 * Uses explicit [TimeTicket]s (not the shared [issueTime] helper) wherever a
 * case needs a genuinely different lamport or actor: [issueTime] draws from
 * one process-wide [dev.yorkie.DummyContext] whose lamport never advances
 * (only its delimiter does), so two [issueTime] calls always share one
 * lamport/actor — exactly the "own change" shape, not "an earlier change".
 */
class CrdtTreeDuplicateIdTest {

    private val actorID = "000000000000000000000001"

    private fun ticket(lamport: Long, delimiter: UInt = 0u) = TimeTicket(
        lamport,
        delimiter,
        actorID,
    )

    private fun issuePos(offset: Int = 0) = CrdtTreeNodeID(issueTime(), offset)

    private fun newTree() = CrdtTree(CrdtTreeElement(issuePos(), "root"), issueTime())

    // AC1/AC2: registerNode keeps the live node over a tombstone
    // regardless of which one was registered last ("operation order" — an
    // incremental sequence of registerNode calls, as a running session
    // would make them).
    @Test
    fun `registerNode keeps the live node over a tombstone regardless of registration order`() {
        val sharedID = CrdtTreeNodeID(issueTime(), 0)
        val live = CrdtTreeText(sharedID, "live")
        val tombstoned = CrdtTreeText(sharedID, "dead")
        tombstoned.remove(issueTime())

        val liveFirst = newTree()
        liveFirst.registerNode(live)
        liveFirst.registerNode(tombstoned)
        assertSame(live, liveFirst.findFloorNode(sharedID))

        val tombstoneFirst = newTree()
        tombstoneFirst.registerNode(tombstoned)
        tombstoneFirst.registerNode(live)
        assertSame(live, tombstoneFirst.findFloorNode(sharedID))
    }

    // AC2: the same duplicated ID resolves to the live node after a
    // snapshot rebuild too — the constructor's size-vs-count re-register
    // pass (A4) — regardless of which child was structurally appended
    // first.
    @Test
    fun `constructor resolves the live node after a snapshot rebuild regardless of append order`() {
        val sharedID = CrdtTreeNodeID(issueTime(), 0)

        val rootA = CrdtTreeElement(issuePos(), "root")
        val deadA = CrdtTreeText(sharedID, "dead")
        rootA.append(deadA)
        deadA.remove(issueTime())
        val liveA = CrdtTreeText(sharedID, "live")
        rootA.append(liveA)
        val treeA = CrdtTree(rootA, issueTime())
        assertSame(liveA, treeA.findFloorNode(sharedID))

        val rootB = CrdtTreeElement(issuePos(), "root")
        val liveB = CrdtTreeText(sharedID, "live")
        rootB.append(liveB)
        val deadB = CrdtTreeText(sharedID, "dead")
        rootB.append(deadB)
        deadB.remove(issueTime())
        val treeB = CrdtTree(rootB, issueTime())
        assertSame(liveB, treeB.findFloorNode(sharedID))
    }

    // AC2 (purge): purging the tombstoned twin leaves the live entry
    // resolvable — the guarded purge (A3) only removes the map entry the
    // purged node actually holds.
    @Test
    fun `purging the tombstoned twin leaves the live node resolvable`() {
        val sharedID = CrdtTreeNodeID(issueTime(), 0)
        val live = CrdtTreeText(sharedID, "live")
        val tombstoned = CrdtTreeText(sharedID, "dead")
        tombstoned.remove(issueTime())

        val tree = newTree()
        tree.registerNode(live)
        tree.registerNode(tombstoned)
        assertSame(live, tree.findFloorNode(sharedID))

        tree.delete(tombstoned)
        assertSame(live, tree.findFloorNode(sharedID))
    }

    // AC3: cross-change reuse from an earlier change (a strictly smaller
    // lamport, same actor) is dropped as a whole subtree.
    @Test
    fun `dropDuplicateContents drops a subtree reusing an id from an earlier change`() {
        val tree = newTree()
        val earlierID = CrdtTreeNodeID(ticket(1), 0)
        tree.registerNode(CrdtTreeText(earlierID, "existing"))

        val editedAt = ticket(2)
        val reused = CrdtTreeText(earlierID, "copy")
        val accepted = tree.dropDuplicateContents(listOf(reused), editedAt)

        assertTrue(accepted.isEmpty())
    }

    // AC3: cross-change reuse claimed by another actor is dropped, even
    // when the lamport happens to coincide with this edit's own.
    @Test
    fun `dropDuplicateContents drops a subtree reusing an id claimed by another actor`() {
        val tree = newTree()
        val otherActorID = CrdtTreeNodeID(TimeTicket(100L, 0u, "000000000000000000000099"), 0)
        tree.registerNode(CrdtTreeText(otherActorID, "existing"))

        val editedAt = ticket(100)
        val reused = CrdtTreeText(otherActorID, "copy")
        val accepted = tree.dropDuplicateContents(listOf(reused), editedAt)

        assertTrue(accepted.isEmpty())
    }

    // AC3: an own-change collision — e.g. an ID this same edit's split step
    // already registered before dropDuplicateContents runs (step 04
    // precedes step 05 in CrdtTree.edit) — is kept, since element-split
    // delimiters are simulated (not replayed) and legitimately collide.
    // "Own change" is recognized by lamport+actor alone (not delimiter), so
    // a different delimiter under the SAME editedAt lamport/actor still
    // counts as this edit's own.
    @Test
    fun `dropDuplicateContents keeps a subtree whose id collides with this edit's own change`() {
        val tree = newTree()
        val editedAt = ticket(5, delimiter = 3u)
        val ownDelimiterID = CrdtTreeNodeID(ticket(5, delimiter = 7u), 0)
        tree.registerNode(CrdtTreeText(ownDelimiterID, "split-product"))

        val ownContent = CrdtTreeText(ownDelimiterID, "own-insert")
        val accepted = tree.dropDuplicateContents(listOf(ownContent), editedAt)

        assertEquals(listOf(ownContent), accepted)
    }

    // AC3: a collision anywhere in a content's subtree drops the WHOLE
    // subtree, not just the colliding descendant.
    @Test
    fun `dropDuplicateContents drops the whole subtree when only a descendant collides`() {
        val tree = newTree()
        val earlierID = CrdtTreeNodeID(ticket(1), 0)
        tree.registerNode(CrdtTreeText(earlierID, "existing"))

        val editedAt = ticket(2)
        val element = CrdtTreeElement(CrdtTreeNodeID(editedAt, 0), "p")
        element.append(CrdtTreeText(earlierID, "reused-descendant"))
        val accepted = tree.dropDuplicateContents(listOf(element), editedAt)

        assertTrue(accepted.isEmpty())
    }

    // AC3 (ordering pin): the drop check runs AFTER step-01 range
    // resolution. Nothing carries (textID, 5) until resolving the insert
    // position splits the run there — moments before the copy is inserted
    // under that very ID. Hoisting dropDuplicateContents above
    // findNodesAndSplitText would keep (and insert) the copy, changing the
    // XML. Ports `drops content whose id this edit is about to create by
    // splitting`.
    @Test
    fun `dropDuplicateContents drops content whose id this edit is about to create by splitting`() {
        val treeTicket = ticket(1)
        val textID = CrdtTreeNodeID(ticket(2), 0)
        val root = CrdtTreeElement(CrdtTreeNodeID(treeTicket, 0), DEFAULT_ROOT_TYPE)
        root.append(CrdtTreeText(textID, "0123456789"))
        val tree = CrdtTree(root, treeTicket)
        assertEquals("<root>0123456789</root>", tree.toXml())

        val editedAt = ticket(3)
        val copy = CrdtTreeText(CrdtTreeNodeID(ticket(2), 5), "5")
        val pos = tree.findPos(5)
        val result = tree.edit(
            pos to pos,
            listOf(copy),
            0,
            editedAt,
            issueTimeTicket = { editedAt },
        )

        assertEquals("the copy is not inserted", "<root>0123456789</root>", tree.toXml())
        assertEquals(0, result.insertedContentSize)
    }

    // AC2 (anchored-edit pin): a position anchored through a duplicated ID
    // must land identically on a live tree (operation-order registration)
    // and on one rebuilt by the constructor from the same structure — the
    // original divergence bug was exactly a position resolving to a
    // different twin per registration order. Ports `applies an edit
    // anchored at a duplicated id after a rebuild`.
    @Test
    fun `an edit anchored at a duplicated id lands identically live and after a rebuild`() {
        val treeTicket = ticket(1)
        val textCreatedAt = ticket(2)

        fun liveText() = CrdtTreeText(CrdtTreeNodeID(textCreatedAt, 0), "0123456789")

        fun deadTwin() = CrdtTreeText(CrdtTreeNodeID(textCreatedAt, 0), "0123456789")
            .also { it.remove(ticket(3)) }

        // Operation-order tree: the live node is registered at construction;
        // the duplicated tombstone arrives later as an operation would
        // deliver it (the copy-reinsert-undo shape) — structure [live, dead].
        val opOrderRoot = CrdtTreeElement(CrdtTreeNodeID(treeTicket, 0), DEFAULT_ROOT_TYPE)
        opOrderRoot.append(liveText())
        val opOrderTree = CrdtTree(opOrderRoot, treeTicket)
        val lateDead = deadTwin()
        opOrderRoot.append(lateDead)
        opOrderTree.registerNode(lateDead)

        // Rebuilt tree: the SAME structure [live, dead] registered in
        // document order by the constructor (plain puts would leave the
        // tombstone as the map winner without the re-register pass).
        val rebuiltRoot = CrdtTreeElement(CrdtTreeNodeID(treeTicket, 0), DEFAULT_ROOT_TYPE)
        rebuiltRoot.append(liveText())
        rebuiltRoot.append(deadTwin())
        val rebuiltTree = CrdtTree(rebuiltRoot, treeTicket)

        fun deleteAnchoredAtDuplicate(tree: CrdtTree, editedAt: TimeTicket): String {
            val parentID = CrdtTreeNodeID(treeTicket, 0)
            val from = CrdtTreePos(parentID, CrdtTreeNodeID(textCreatedAt, 5))
            val to = CrdtTreePos(parentID, CrdtTreeNodeID(textCreatedAt, 6))
            tree.edit(from to to, null, 0, editedAt, issueTimeTicket = { editedAt })
            return tree.toXml()
        }

        val liveXml = deleteAnchoredAtDuplicate(opOrderTree, ticket(4))
        val rebuiltXml = deleteAnchoredAtDuplicate(rebuiltTree, ticket(4))

        assertEquals("<root>012346789</root>", liveXml)
        assertEquals("anchored edit must land identically", liveXml, rebuiltXml)
    }

    // AC4: a dropped copy's edit inserts nothing, and the accepted-size
    // measurement used to size the reverse operation is zero.
    @Test
    fun `dropped copy inserts nothing and contributes zero accepted size`() {
        val tree = newTree()
        val earlierID = CrdtTreeNodeID(ticket(1), 0)
        tree.registerNode(CrdtTreeText(earlierID, "existing"))
        assertEquals("<root></root>", tree.toXml())

        val editedAt = ticket(2)
        val reused = CrdtTreeText(earlierID, "y")
        val pos0 = tree.findPos(0)
        val result = tree.edit(pos0 to pos0, listOf(reused), 0, editedAt, ::issueTime)

        assertEquals("dropped copy must insert nothing", "<root></root>", tree.toXml())
        assertEquals(0, result.insertedContentSize)
    }

    // AC4: when one edit's contents mix a dropped copy with a genuinely
    // accepted insert, the reverse operation is identity-addressed
    // (retombstoneSpans) and names only the node that was actually
    // inserted — so redo can never reach past it to delete a neighbour.
    @Test
    fun `reverse of an edit with a dropped copy retombstones only the accepted insert`() {
        val treeTicket = ticket(1)
        val root = CrdtTreeElement(CrdtTreeNodeID(treeTicket, 0), DEFAULT_ROOT_TYPE)
        val tree = CrdtTree(root, treeTicket)
        val crdtRoot =
            CrdtRoot(
                CrdtObject(createdAt = TimeTicket.InitialTimeTicket, memberNodes = ElementRht()),
            )
        crdtRoot.rootObject.set("tree", tree, treeTicket)
        crdtRoot.registerElement(tree, crdtRoot.rootObject)

        val earlierID = CrdtTreeNodeID(ticket(2), 0)
        tree.registerNode(CrdtTreeText(earlierID, "existing"))

        val editedAt = ticket(3)
        val droppedCopy = CrdtTreeText(earlierID, "copy")
        val acceptedID = CrdtTreeNodeID(editedAt, 0)
        val acceptedText = CrdtTreeText(acceptedID, "hi")
        val op = TreeEditOperation(
            parentCreatedAt = treeTicket,
            fromPos = tree.findPos(0),
            toPos = tree.findPos(0),
            contents = listOf(droppedCopy, acceptedText),
            splitLevel = 0,
            executedAt = editedAt,
        )
        val result = op.execute(crdtRoot, OpSource.Local, null)

        assertEquals("<root>hi</root>", tree.toXml())
        val reverse = result.reverseOps.single() as TreeEditOperation
        assertTrue(reverse.isRestoreOp)
        assertEquals(1, reverse.retombstoneSpans?.size)
        assertEquals(acceptedID, reverse.retombstoneSpans?.single()?.id)
    }
}
