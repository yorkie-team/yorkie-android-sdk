package dev.yorkie.document.operation

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
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
import dev.yorkie.document.crdt.countNodes
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import dev.yorkie.util.traverseAll
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports `undo_content_identity_test.ts` and `undo_copy_path_test.ts` (JS SDK
 * 4ec66cc0) as JVM tests (AC10): a copy-reinsert undo restores content
 * under IDs fresh in every naming field (no lineage splice into the source
 * chain), redo returns to the deleted state, a later ticket in the same
 * undo change never repeats a minted one, and a restore-mode reverse
 * (identity-preserving, spec 005) is left untouched by the fresh-ID path.
 */
class TreeUndoContentIdentityTest {

    private val actorID = "000000000000000000000001"

    private fun ticket(lamport: Long, delimiter: UInt = 0u) = TimeTicket(
        lamport,
        delimiter,
        actorID,
    )

    private fun buildTreeRoot(): Pair<CrdtTree, CrdtRoot> {
        // The root CrdtObject and the tree MUST carry distinct createdAt
        // tickets — CrdtRoot's parent-path map is keyed by createdAt, and a
        // shared ticket corrupts it, sending CrdtRoot.createSubPaths into
        // an infinite parent-walk.
        val treeTicket = ticket(1)
        val rootNode = CrdtTreeElement(CrdtTreeNodeID(treeTicket, 0), DEFAULT_ROOT_TYPE)
        val tree = CrdtTree(rootNode, treeTicket)
        val root =
            CrdtRoot(
                CrdtObject(createdAt = TimeTicket.InitialTimeTicket, memberNodes = ElementRht()),
            )
        root.rootObject.set("tree", tree, treeTicket)
        root.registerElement(tree, root.rootObject)
        return tree to root
    }

    private fun collectIds(node: CrdtTreeNode, into: MutableSet<CrdtTreeNodeID>) {
        traverseAll(node) { n, _ -> into.add(n.id) }
    }

    // AC10: every naming field is fresh — id, insPrevID/insNextID (never
    // set by buildFreshNodes), and mergedFrom/mergedAt/mergedInto (default
    // null on newly-constructed nodes) — matching the JS contract that a
    // copy must not splice into the chain it came from.
    @Test
    fun `buildFreshNodes mints fresh identity in every naming field`() {
        val paragraph = TreeElementNode(
            type = "p",
            childNodes = listOf(TreeTextNode("ab")),
        )
        val executedAt = ticket(5)

        val fresh = TreeEditOperation.buildFreshNodes(listOf(paragraph), executedAt)

        val node = fresh.single()
        assertNotEquals(executedAt, node.id.createdAt)
        assertEquals(executedAt.lamport, node.id.createdAt.lamport)
        assertNull(node.insPrevID)
        assertNull(node.insNextID)
        assertNull(node.mergedFrom)
        assertNull(node.mergedAt)
        assertNull(node.mergedInto)
        val child = node.children.single()
        assertNull(child.insPrevID)
        assertNull(child.mergedFrom)

        // Every node in the subtree consumed exactly one ticket, matching
        // the countNodes() contract the undo-execute reservation relies on.
        val mintedIds = mutableSetOf<CrdtTreeNodeID>()
        collectIds(node, mintedIds)
        assertEquals(paragraph.countNodes(), mintedIds.size)
    }

    // AC10: the reservation math Document.executeUndoRedo performs — reserve
    // countNodes() tickets right after executedAt — must leave the NEXT
    // ticket a later op in the same undo change issues clear of every id
    // buildFreshNodes will mint.
    @Test
    fun `a later ticket in the same undo change never repeats a minted id`() {
        val (_, root) = buildTreeRoot()
        val context = ChangeContext(prevId = ChangeID.InitialChangeID, root = root)

        val snapshot = TreeElementNode(
            type = "p",
            childNodes = listOf(TreeTextNode("ab"), TreeElementNode(type = "b")),
        )
        val executedAt = context.issueTimeTicket() // op.executedAt = ticket

        // Document.executeUndoRedo's C6 reservation.
        val ticketCount = listOf(snapshot).sumOf { it.countNodes() }
        repeat(ticketCount) { context.issueTimeTicket() }

        // A later op in the same undo/redo batch.
        val laterTicket = context.issueTimeTicket()

        val fresh = TreeEditOperation.buildFreshNodes(listOf(snapshot), executedAt)
        val mintedIds = mutableSetOf<CrdtTreeNodeID>()
        fresh.forEach { collectIds(it, mintedIds) }

        assertFalse(mintedIds.any { it.createdAt == laterTicket })
    }

    // AC10 (undo_copy_path_test.ts): a copy-reinsert reverse restores
    // content under an id distinct from the still-tombstoned original —
    // no duplicate id, content is live.
    @Test
    fun `undo copy path restores content under a fresh id without duplicating the original`() {
        val (tree, root) = buildTreeRoot()

        // The original node, already tombstoned in the tree (as if an
        // earlier delete ran) — its id must NOT be reused by the copy.
        val originalID = CrdtTreeNodeID(ticket(2), 0)
        val original = CrdtTreeText(originalID, "ab")
        original.remove(ticket(3))
        tree.registerNode(original)

        val snapshot = TreeTextNode("ab")
        val executedAt = ticket(4)
        val reverseOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPos(0),
            toPos = tree.findPos(0),
            contents = null,
            splitLevel = 0,
            executedAt = executedAt,
            undoFromOffset = 0,
            undoToOffset = 0,
            removedNodeSnapshots = listOf(snapshot),
        )

        reverseOp.execute(root, OpSource.UndoRedo, null)

        assertEquals("<root>ab</root>", tree.toXml())
        val revived = tree.findFloorNode(originalID)
        assertEquals(originalID, revived?.id, "the original tombstone keeps its own id")
        assertTrue(requireNotNull(revived).isRemoved, "the original stays a tombstone")

        // The newly created live node carries a DIFFERENT id.
        var liveID: CrdtTreeNodeID? = null
        tree.indexTree.traverseAll { node, _ ->
            if (!node.isRemoved && node.isText) liveID = node.id
        }
        assertNotEquals(originalID, liveID)
    }

    // AC10: redo (the reverse's own reverse) returns to the deleted state.
    @Test
    fun `redo returns to the deleted state`() {
        val (tree, root) = buildTreeRoot()
        val snapshot = TreeTextNode("ab")
        val executedAt = ticket(2)
        val reverseOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPos(0),
            toPos = tree.findPos(0),
            contents = null,
            splitLevel = 0,
            executedAt = executedAt,
            undoFromOffset = 0,
            undoToOffset = 0,
            removedNodeSnapshots = listOf(snapshot),
        )
        val result = reverseOp.execute(root, OpSource.UndoRedo, null)
        assertEquals("<root>ab</root>", tree.toXml())

        val redo = result.reverseOps.single()
        redo.executedAt = ticket(3)
        redo.execute(root, OpSource.UndoRedo, null)

        assertEquals("<root></root>", tree.toXml())
    }

    // AC10: a restore-mode reverse (identity-preserving, spec 005) is left
    // untouched by the fresh-id path — it keeps the ORIGINAL identity, not
    // a copy.
    @Test
    fun `restore-mode reverse keeps original identity`() {
        val (tree, root) = buildTreeRoot()
        val originalID = CrdtTreeNodeID(ticket(2), 0)
        val original = CrdtTreeText(originalID, "ab")

        val insertOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPos(0),
            toPos = tree.findPos(0),
            contents = listOf(original),
            splitLevel = 0,
            executedAt = ticket(2),
        )
        insertOp.execute(root, OpSource.Local, null)
        assertEquals("<root>ab</root>", tree.toXml())

        val deleteOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPos(0),
            toPos = tree.findPos(2),
            contents = null,
            splitLevel = 0,
            executedAt = ticket(3),
        )
        val deleteResult = deleteOp.execute(root, OpSource.Local, null)
        assertEquals("<root></root>", tree.toXml())

        val undo = deleteResult.reverseOps.single() as TreeEditOperation
        assertTrue(undo.isRestoreOp, "a plain delete's reverse is identity-preserving (spec 005)")
        assertNull(
            undo.removedNodeSnapshots,
            "restore-mode reverses carry no copy-reinsert snapshot",
        )

        undo.executedAt = ticket(4)
        undo.execute(root, OpSource.UndoRedo, null)
        assertEquals("<root>ab</root>", tree.toXml())
        assertEquals(
            originalID,
            tree.findFloorNode(originalID)?.id,
            "revives under the ORIGINAL id",
        )
    }
}
