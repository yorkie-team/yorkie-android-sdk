package dev.yorkie.document.crdt

import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.operation.OpSource
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports the narrow rule of `CRDTTree.mergeSourceOf` (JS SDK `b173c093`,
 * #1426) as a standalone JVM unit test, plus the `TreeEditOperation`
 * getters/state it feeds (spec 036 Commit 2 unit 2B).
 *
 * [mergeSourceOf] takes its node lookup as a resolver lambda rather than a
 * [CrdtTree] receiver (D10, ratchet relief), so every narrow-rule case here
 * is a hand-built node graph with a purpose-built resolver -- no [CrdtTree]
 * required. The "Wiring" section below then confirms the real
 * [CrdtTree.findFloorNode] resolver and [CrdtTree.edit]'s own split loop
 * reach the same function, and that [TreeEditOperation] surfaces what it
 * reports.
 */
class CrdtTreeMergeSourceOfTest {

    private val actor = "000000000000000000000001"

    /** Hands out tickets with an increasing lamport for [actor]. */
    private fun ticketer(): () -> TimeTicket {
        var lamport = 0L
        return {
            lamport += 1
            TimeTicket(lamport, 0u, actor)
        }
    }

    // ---------------------------------------------------------------------
    // Part A -- the narrow rule, hand-built nodes + a resolver lambda.
    // ---------------------------------------------------------------------

    @Test
    fun `a split that takes a merge source's children back whole is re-pointed`() {
        val issue = ticketer()
        fun elementId() = CrdtTreeNodeID(issue(), 0)

        // The merge source: tombstoned, as a real merge leaves it.
        val source = CrdtTreeElement(elementId(), "p")
        source.remove(issue())

        val splitTarget = CrdtTreeElement(elementId(), "p") // the split's left side
        val splitNode = CrdtTreeElement(elementId(), "p") // the split product (right side)
        val movedChild = CrdtTreeText(elementId(), "cd").apply { mergedFrom = source.id }
        splitNode.append(movedChild)

        val resolver: (CrdtTreeNodeID) -> CrdtTreeNode? = { id -> source.takeIf { it.id == id } }

        assertEquals(source.id, mergeSourceOf(splitNode, splitTarget, resolver))
    }

    @Test
    fun `a partial split -- the target still holds a child from the same source -- returns none`() {
        val issue = ticketer()
        fun elementId() = CrdtTreeNodeID(issue(), 0)

        val source = CrdtTreeElement(elementId(), "p")
        source.remove(issue())

        val splitTarget = CrdtTreeElement(elementId(), "p")
        // A leftover: the target kept one of the source's children, so this
        // split only cut through the merge target rather than reversing it.
        splitTarget.append(CrdtTreeText(elementId(), "ab").apply { mergedFrom = source.id })

        val splitNode = CrdtTreeElement(elementId(), "p")
        splitNode.append(CrdtTreeText(elementId(), "cd").apply { mergedFrom = source.id })

        val resolver: (CrdtTreeNodeID) -> CrdtTreeNode? = { id -> source.takeIf { it.id == id } }

        assertNull(mergeSourceOf(splitNode, splitTarget, resolver))
    }

    @Test
    fun `a child stamped for the split product's own mergedFrom is skipped`() {
        val issue = ticketer()
        fun elementId() = CrdtTreeNodeID(issue(), 0)

        val source = CrdtTreeElement(elementId(), "p")
        source.remove(issue())

        val splitTarget = CrdtTreeElement(elementId(), "p")
        val splitNode = CrdtTreeElement(elementId(), "p").apply {
            // The product's OWN provenance equals the moved child's
            // mergedFrom -- cloneElement copies this off the node it split,
            // so it names a merge one level up, not what THIS split re-created.
            mergedFrom = source.id
        }
        splitNode.append(CrdtTreeText(elementId(), "cd").apply { mergedFrom = source.id })

        val resolver: (CrdtTreeNodeID) -> CrdtTreeNode? = { id -> source.takeIf { it.id == id } }

        assertNull(mergeSourceOf(splitNode, splitTarget, resolver))
    }

    @Test
    fun `a live forged mergedFrom returns none`() {
        val issue = ticketer()
        fun elementId() = CrdtTreeNodeID(issue(), 0)

        val source = CrdtTreeElement(elementId(), "p") // never removed
        val splitTarget = CrdtTreeElement(elementId(), "p")
        val splitNode = CrdtTreeElement(elementId(), "p")
        splitNode.append(CrdtTreeText(elementId(), "cd").apply { mergedFrom = source.id })

        val resolver: (CrdtTreeNodeID) -> CrdtTreeNode? = { id -> source.takeIf { it.id == id } }

        assertFalse(source.isRemoved)
        assertNull(mergeSourceOf(splitNode, splitTarget, resolver))
    }

    @Test
    fun `a forged mergedFrom naming no node in the tree returns none`() {
        val issue = ticketer()
        fun elementId() = CrdtTreeNodeID(issue(), 0)

        val splitTarget = CrdtTreeElement(elementId(), "p")
        val splitNode = CrdtTreeElement(elementId(), "p")
        splitNode.append(CrdtTreeText(elementId(), "cd").apply { mergedFrom = elementId() })

        val resolver: (CrdtTreeNodeID) -> CrdtTreeNode? = { null }

        assertNull(mergeSourceOf(splitNode, splitTarget, resolver))
    }

    @Test
    fun `a forged mergedFrom that resolves to a different createdAt returns none`() {
        val issue = ticketer()
        fun elementId() = CrdtTreeNodeID(issue(), 0)

        val requestedID = elementId()
        // A node the resolver hands back regardless of what was asked for --
        // the shape a real floor lookup landing on the wrong ticket would
        // take. mergeSourceOf's own createdAt check must catch it even
        // though the caller already "resolved" something.
        val unrelated = CrdtTreeElement(elementId(), "p").apply { remove(issue()) }

        val splitTarget = CrdtTreeElement(elementId(), "p")
        val splitNode = CrdtTreeElement(elementId(), "p")
        splitNode.append(CrdtTreeText(elementId(), "cd").apply { mergedFrom = requestedID })

        assertNull(mergeSourceOf(splitNode, splitTarget) { unrelated })
    }

    @Test
    fun `no mergedFrom on any child returns none`() {
        val issue = ticketer()
        fun elementId() = CrdtTreeNodeID(issue(), 0)

        val splitTarget = CrdtTreeElement(elementId(), "p")
        val splitNode = CrdtTreeElement(elementId(), "p")
        splitNode.append(CrdtTreeText(elementId(), "cd")) // no mergedFrom at all

        assertNull(mergeSourceOf(splitNode, splitTarget) { null })
    }

    // ---------------------------------------------------------------------
    // Part B -- CrdtTree.edit()'s own wiring: the real findFloorNode
    // resolver, reached through a real merge then a real split.
    // ---------------------------------------------------------------------

    @Test
    fun `CrdtTree edit reports the merge source its own split re-creates`() {
        val issue = ticketer()
        fun element(type: String) = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), type)
        fun text(value: String) = CrdtTreeText(CrdtTreeNodeID(issue(), 0), value)
        val issueFn: () -> TimeTicket = issue

        val tree = CrdtTree(element("r"), issue())
        val p1 = element("p")
        tree.edit(tree.findPos(0) to tree.findPos(0), listOf(p1), 0, issue(), issueFn)
        tree.edit(tree.findPos(1) to tree.findPos(1), listOf(text("ab")), 0, issue(), issueFn)
        val p2 = element("p")
        tree.edit(tree.findPos(4) to tree.findPos(4), listOf(p2), 0, issue(), issueFn)
        tree.edit(tree.findPos(5) to tree.findPos(5), listOf(text("cd")), 0, issue(), issueFn)
        assertEquals("<r><p>ab</p><p>cd</p></r>", tree.toXml())

        // Merge: delete the boundary between the two paragraphs -- p2's
        // "cd" moves into p1, p2 is tombstoned.
        val mergeResult = tree.edit(tree.findPos(3) to tree.findPos(5), null, 0, issue(), issueFn)
        assertEquals("<r><p>abcd</p></r>", tree.toXml())
        assertEquals(1, mergeResult.mergeLevel)
        assertEquals(listOf(p2), mergeResult.mergedNodes)

        // Split p1 back apart at the same boundary -- the product takes
        // back WHOLE what the merge moved in, so it re-creates p2.
        val splitResult = tree.edit(tree.findPos(3) to tree.findPos(3), null, 1, issue(), issueFn)
        assertEquals("<r><p>ab</p><p>cd</p></r>", tree.toXml())
        assertEquals(1, splitResult.splitRecreatedIDs.size)
        val (recreatedOld, recreatedNew) = splitResult.splitRecreatedIDs.single()
        assertEquals(p2.id, recreatedOld)
        val newNode = tree.findFloorNode(recreatedNew)
        assertNotNull(newNode)
        assertFalse(newNode.isRemoved)
        assertEquals("cd", newNode.getChildrenText())
    }

    // ---------------------------------------------------------------------
    // Part C -- the TreeEditOperation getters/state this unit adds.
    // ---------------------------------------------------------------------

    private val rootTicket = TimeTicket(1L, 0u, actor)
    private val treeTicket = TimeTicket(2L, 0u, actor)

    private fun makeTicket(lamport: Long) = TimeTicket(lamport, 0u, actor)

    private fun buildTreeRoot(): Pair<CrdtTree, CrdtRoot> {
        val rootNode = CrdtTreeElement(CrdtTreeNodeID(treeTicket, 0), DEFAULT_ROOT_TYPE)
        val tree = CrdtTree(rootNode, treeTicket)
        val obj = CrdtObject(createdAt = rootTicket, memberNodes = ElementRht())
        val root = CrdtRoot(obj)
        root.rootObject.set("tree", tree, treeTicket)
        root.registerElement(tree, root.rootObject)
        return tree to root
    }

    private fun makeEditOp(
        tree: CrdtTree,
        fromIndex: Int,
        toIndex: Int,
        contents: List<CrdtTreeNode>?,
        lamport: Long,
        splitLevel: Int = 0,
        splitTickets: List<TimeTicket> = emptyList(),
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
            splitTickets = splitTickets,
        )
    }

    @Test
    fun `a fresh TreeEditOperation reports empty ids and zero consumed tickets`() {
        val (tree, _) = buildTreeRoot()
        val freshOp = makeEditOp(tree, 0, 0, null, 3)

        assertEquals(emptyList(), freshOp.getReplacedIDs())
        assertEquals(emptyList(), freshOp.getSplitRecreatedIDs())
        assertEquals(0, freshOp.getConsumedSplitTicketCount())
    }

    @Test
    fun `a split reversing a merge reports both what it will replace and what it replaced`() {
        val (tree, root) = buildTreeRoot()
        val p1ID = CrdtTreeNodeID(makeTicket(3), 0)
        makeEditOp(
            tree,
            0,
            0,
            listOf(CrdtTreeElement(p1ID, "p")),
            3,
        ).execute(root, OpSource.Local, null)
        makeEditOp(tree, 1, 1, listOf(CrdtTreeText(CrdtTreeNodeID(makeTicket(4), 0), "ab")), 4)
            .execute(root, OpSource.Local, null)
        val p2ID = CrdtTreeNodeID(makeTicket(5), 0)
        makeEditOp(
            tree,
            4,
            4,
            listOf(CrdtTreeElement(p2ID, "p")),
            5,
        ).execute(root, OpSource.Local, null)
        makeEditOp(tree, 5, 5, listOf(CrdtTreeText(CrdtTreeNodeID(makeTicket(6), 0), "cd")), 6)
            .execute(root, OpSource.Local, null)
        assertEquals("<root><p>ab</p><p>cd</p></root>", tree.toXml())

        // Merge the two paragraphs: delete the boundary between them.
        val mergeOp = makeEditOp(tree, 3, 5, null, 7)
        val mergeResult = mergeOp.execute(root, OpSource.Local, null)
        assertEquals("<root><p>abcd</p></root>", tree.toXml())
        val reverseOp = mergeResult.reverseOps.single() as TreeEditOperation
        assertTrue(reverseOp.isUndoOp)

        // Before it runs, the reverse already knows what it will replace --
        // computed at merge time from the SAME mergedNodes CrdtTree.edit
        // reported (toReverseOperation's mergeLevel > 0 branch).
        assertEquals(listOf(p2ID), reverseOp.getReplacedIDs())

        // reconcileNodeID re-points replacedIDs too (the line unit 2A
        // flagged as owed): simulate a later split re-creating p2 under
        // ANOTHER id before this reverse ever runs.
        val laterID = CrdtTreeNodeID(makeTicket(50), 0)
        reverseOp.reconcileNodeID(p2ID, laterID)
        assertEquals(listOf(laterID), reverseOp.getReplacedIDs())
        // Restore the real id so the split below still finds the actual
        // tombstoned p2 (this test only exercises reconcileNodeID's effect
        // on replacedIDs in isolation, not a real re-point mid-flight).
        reverseOp.reconcileNodeID(laterID, p2ID)

        // Running it performs the real split: CrdtTree.edit reports what it
        // ACTUALLY re-created this time (TreeEditOperation.execute surfaces
        // it via getSplitRecreatedIDs, independent of replacedIDs above).
        reverseOp.execute(root, OpSource.Local, null)
        assertEquals("<root><p>ab</p><p>cd</p></root>", tree.toXml())
        val recreated = reverseOp.getSplitRecreatedIDs().single()
        assertEquals(p2ID, recreated.first)
        val newNode = tree.findFloorNode(recreated.second)
        assertNotNull(newNode)
        assertEquals("cd", newNode.getChildrenText())
    }

    @Test
    fun `onSplitTicketConsumed fires per ticket, issueTimeTicket consumes it not a fresh one`() {
        val (tree, root) = buildTreeRoot()
        val pID = CrdtTreeNodeID(makeTicket(3), 0)
        makeEditOp(
            tree,
            0,
            0,
            listOf(CrdtTreeElement(pID, "p")),
            3,
        ).execute(root, OpSource.Local, null)
        makeEditOp(tree, 1, 1, listOf(CrdtTreeText(CrdtTreeNodeID(makeTicket(4), 0), "ab")), 4)
            .execute(root, OpSource.Local, null)
        assertEquals("<root><p>ab</p></root>", tree.toXml())

        // A preset ticket, as the originating replica would have recorded
        // for this split (setSplitTickets) -- issueTimeTicket must consume
        // it instead of minting a fresh one.
        val presetTicket = makeTicket(100)
        val splitOp = makeEditOp(
            tree,
            fromIndex = 2,
            toIndex = 2,
            contents = null,
            lamport = 5,
            splitLevel = 1,
            splitTickets = listOf(presetTicket),
        )
        val consumed = mutableListOf<Int>()
        splitOp.onSplitTicketConsumed { index -> consumed.add(index) }

        splitOp.execute(root, OpSource.Local, null)

        assertEquals(listOf(0), consumed)
        assertEquals(1, splitOp.getConsumedSplitTicketCount())
        val newNode = tree.findFloorNode(CrdtTreeNodeID(presetTicket, 0))
        assertNotNull(newNode)
        assertEquals("b", newNode.getChildrenText())

        // Clearing the handler (pass null, as an undo/redo does once both
        // the clone and the root pass are done) must not throw and must
        // stop further notifications.
        splitOp.onSplitTicketConsumed(null)
    }
}
