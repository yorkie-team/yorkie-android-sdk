package dev.yorkie.document.operation

import dev.yorkie.document.Document
import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtObject
import dev.yorkie.document.crdt.CrdtRoot
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.ElementRht
import dev.yorkie.document.crdt.TreeElementNode
import dev.yorkie.document.crdt.TreeNode
import dev.yorkie.document.crdt.TreeTextNode
import dev.yorkie.document.crdt.countNodes
import dev.yorkie.document.crdt.toTreeNode
import dev.yorkie.document.history.History
import dev.yorkie.document.history.HistoryOperation
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import dev.yorkie.util.traverseAll
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `undo_content_identity_test.ts` and `undo_copy_path_test.ts` (JS SDK
 * 4ec66cc0) as JVM tests (AC10): a copy-reinsert undo restores content
 * under IDs fresh in every naming field (no lineage splice into the source
 * chain), redo returns to the deleted state, a later ticket in the same
 * undo change never repeats a minted one, and a restore-mode reverse
 * (identity-preserving, spec 005) is left untouched by the fresh-ID path.
 *
 * Also covers the reissue half of yorkie-js-sdk `b173c093` (#1426) --
 * `reissueContentIDs`' Android counterpart (the split-ticket half of that
 * commit was ported earlier; see `TreeRedoSplitStyleTest`). A copy-reinsert
 * reverse mints fresh ids for the content it restores; a later operation in
 * the same undo/redo entry, or sitting in either history stack, that still
 * names one of the removed originals must follow it to the fresh copy
 * (`TreeEditOperation.getReissuedContentIDs`/`onContentIDsReissued`, wired
 * through `Document.executeUndoRedoInternal`'s `pending`/`repointRest`
 * exactly like a split's `replacedIDs`). `removedNodeOldIDs`/
 * `CrdtTreeNode.idsPreOrder` pair each removed original with the fresh id
 * `buildFreshNodes` mints for it, since Android's copy-reinsert payload
 * (`removedNodeSnapshots`) is id-less by design (see `toTreeNode`), unlike
 * JS's `contents`, which keeps the original ids until `reissueContentIDs`
 * overwrites them.
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

    // AC10: the real Document.executeUndoRedo reservation branch, exercised
    // through history.undoAsync(). A copy-reinsert reverse is unreachable
    // via purely local edits (a merge source always tombstones inside the
    // deleted ancestor's subtree, so local spans stay complete), so the
    // undo stack is seeded directly — undoAsync still runs the branch under
    // test. Two snapshot ops in one batch: without the reservation, the
    // second op's executedAt lands inside the first op's minted delimiter
    // range and buildFreshNodes repeats an id.
    @Test
    fun `undoAsync reserves minted tickets so a second snapshot op in the batch cannot collide`() =
        runTest {
            val document = Document("test-doc")
            document.updateAsync { root, _ -> root.setNewTree("t", element("root")) }.await()
            val tree = document.getRootObject()["t"] as CrdtTree

            fun snapshotOp(snapshot: TreeNode) = TreeEditOperation(
                parentCreatedAt = tree.createdAt,
                fromPos = tree.findPos(0),
                toPos = tree.findPos(0),
                contents = null,
                splitLevel = 0,
                executedAt = TimeTicket.InitialTimeTicket,
                undoFromOffset = 0,
                undoToOffset = 0,
                removedNodeSnapshots = listOf(snapshot),
            )

            val first = snapshotOp(
                TreeElementNode(type = "p", childNodes = listOf(TreeTextNode("ab"))),
            )
            val second = snapshotOp(TreeTextNode("z"))

            val history = Document::class.java.getDeclaredField("internalHistory")
                .apply { isAccessible = true }
                .get(document) as History
            history.pushUndo(listOf(HistoryOperation.Op(first), HistoryOperation.Op(second)))

            val result = document.history.undoAsync().await()
            assertTrue(result.isSuccess, "undo must apply cleanly: $result")

            // Both restored subtrees are live and no node id repeats.
            val xml = document.getRoot().getAs<JsonTree>("t").toXml()
            assertTrue("ab" in xml && "z" in xml, "both snapshots must be restored: $xml")
            val ids = mutableListOf<CrdtTreeNodeID>()
            tree.indexTree.traverseAll { node, _ -> ids.add(node.id) }
            assertEquals(ids.size, ids.toSet().size, "minted node ids must be unique: $ids")
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

    // b173c093's reissue half -- toReverseOperation's own wiring: the
    // copy-reinsert branch pairs removedNodeOldIDs with removedNodeSnapshots,
    // pre-order, from the SAME removedNodes list (CrdtTreeNode.idsPreOrder).
    // Exercised through the @VisibleForTesting accessor directly (the branch
    // itself is unreachable via a real execute() -- see the class KDoc and
    // TreeEditOperationReverseTest's own `reverse of pure delete is a pure
    // insert`).
    @Test
    fun `toReverseOperation pairs removedNodeOldIDs with removedNodeSnapshots pre-order`() {
        val (tree, root) = buildTreeRoot()
        val pID = CrdtTreeNodeID(ticket(2), 0)
        val textID = CrdtTreeNodeID(ticket(3), 0)
        val p = CrdtTreeElement(pID, "p").apply { append(CrdtTreeText(textID, "ab")) }

        val reverseOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPos(0),
            toPos = tree.findPos(0),
            contents = null,
            splitLevel = 0,
            executedAt = ticket(4),
        ).toReverseOperationForTest(
            tree = tree,
            actualFrom = tree.findPos(0),
            fromIndex = 0,
            removedNodes = listOf(p),
        ) as TreeEditOperation

        assertEquals(listOf(pID, textID), reverseOp.removedNodeOldIDs)
        assertEquals(
            listOf(p.toTreeNode()),
            reverseOp.removedNodeSnapshots,
            "the snapshot itself still carries no ids (see toTreeNode)",
        )
    }

    // b173c093's reissue half -- execute()'s pairing: a copy-reinsert
    // reverse built with removedNodeOldIDs set (as toReverseOperation now
    // does, above) reports the [old, fresh] pairs buildFreshNodes minted,
    // in the SAME pre-order, through getReissuedContentIDs() -- the exact
    // pairs Document.executeUndoRedoInternal feeds to repointRest/pending.
    @Test
    fun `execute reports reissued id pairs in minting order`() {
        val (tree, root) = buildTreeRoot()
        val pID = CrdtTreeNodeID(ticket(2), 0)
        val textID = CrdtTreeNodeID(ticket(3), 0)
        val snapshot = TreeElementNode(type = "p", childNodes = listOf(TreeTextNode("ab")))
        val executedAt = ticket(5)

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
            removedNodeOldIDs = listOf(pID, textID),
        )

        assertEquals(
            emptyList(),
            reverseOp.getReissuedContentIDs(),
            "nothing minted before execute",
        )
        reverseOp.execute(root, OpSource.UndoRedo, null)

        val pairs = reverseOp.getReissuedContentIDs()
        assertEquals(2, pairs.size)
        assertEquals(pID, pairs[0].first)
        assertEquals(textID, pairs[1].first)
        // The fresh ids are whatever buildFreshNodes actually minted -- read
        // off the live tree rather than re-derived, so this does not
        // silently pass if buildFreshNodes's own minting order ever changes.
        // tree.root's only live child is the freshly built "p"; its only
        // live child is the freshly built text node -- self, then child,
        // matching idsPreOrder's own "self first" order.
        val liveP = tree.root.allChildren.single { !it.isRemoved }
        val liveText = liveP.children.single()
        assertEquals(listOf(liveP.id, liveText.id), pairs.map { it.second })
        assertTrue(pairs.none { it.first == it.second }, "old and fresh ids must differ")
    }

    // b173c093's reissue half -- onContentIDsReissued fires once per
    // execution (not once per node: unlike a split, buildFreshNodes never
    // stops partway) with the full pair list, and -- since an undo/redo
    // executes the SAME operation twice (clone pass, then root pass) --
    // reports the IDENTICAL pairs both times, because the ids are a pure
    // function of removedNodeSnapshots + executedAt, both fixed up front.
    @Test
    fun `onContentIDsReissued fires once per execution with identical pairs across two passes`() {
        val pID = CrdtTreeNodeID(ticket(2), 0)
        val snapshot = TreeTextNode("ab")
        val executedAt = ticket(5)

        fun freshOp() = TreeEditOperation(
            parentCreatedAt = ticket(1),
            fromPos = CrdtTreePos(CrdtTreeNodeID(ticket(1), 0), CrdtTreeNodeID(ticket(1), 0)),
            toPos = CrdtTreePos(CrdtTreeNodeID(ticket(1), 0), CrdtTreeNodeID(ticket(1), 0)),
            contents = null,
            splitLevel = 0,
            executedAt = executedAt,
            undoFromOffset = 0,
            undoToOffset = 0,
            removedNodeSnapshots = listOf(snapshot),
            removedNodeOldIDs = listOf(pID),
        )

        // Clone pass.
        val (tree1, root1) = buildTreeRoot()
        val op1 = freshOp()
        val calls = mutableListOf<List<Pair<CrdtTreeNodeID, CrdtTreeNodeID>>>()
        op1.onContentIDsReissued { calls.add(it) }
        op1.execute(root1, OpSource.UndoRedo, null)
        assertEquals(1, calls.size, "fires once per execution, not once per node")

        // Root pass: a SEPARATE TreeEditOperation instance built the exact
        // same way (mirrors Document.executeUndoRedoInternal running the
        // SAME op object against clone.root then root -- the tree differs
        // here only because buildTreeRoot() gives each its own; the id math
        // is the pure function under test).
        val (tree2, root2) = buildTreeRoot()
        val op2 = freshOp()
        op2.onContentIDsReissued { calls.add(it) }
        op2.execute(root2, OpSource.UndoRedo, null)

        assertEquals(2, calls.size)
        assertEquals(
            calls[0],
            calls[1],
            "identical pairs on both passes -- buildFreshNodes is pure",
        )

        op1.onContentIDsReissued(null)
        op2.onContentIDsReissued(null)
    }

    // b173c093's reissue half -- Document-level integration (same undo/redo
    // entry). The characterization this flips: TreeUpstreamDefectPinTest's
    // `known upstream defect - a style undone alongside a copy-reinsert is
    // dropped` demonstrated the SAME shape (style a node, delete it in one
    // update, undo) but -- per its own re-investigation -- that case resolves
    // through the IDENTITY-PRESERVING restore path (CrdtTree.edit's
    // spansComplete guard; see TreeEditOperationReverseTest's `reverse of
    // pure delete is a pure insert`), which keeps the ORIGINAL id and so
    // never exercises this mechanism at all; dropping the style there is the
    // CORRECT net effect of undoing the whole transaction atomically (no
    // bold existed before either op ran). The copy-reinsert reverse this
    // mechanism targets is reachable only via the narrow, GC-interaction
    // disclosure in CrdtTree.kt (`gcPairs.size != deletePairCount`) that
    // `toReverseOperation`'s own KDoc documents, which no reachable public
    // API sequence was found to trigger within this round -- so the
    // undo/redo entry is seeded directly (reflection onto `internalHistory`,
    // the same technique `undoAsync reserves minted tickets...` above already
    // uses for the identical reachability gap). The later op is a tree EDIT
    // (an insert), not a style: JS's OWN test suite for the sibling
    // split-ticket mechanism uses the same substitution (`split, then insert
    // into the new element`) -- a style's RANGE can structurally sweep in a
    // freshly re-created sibling regardless of id correctness (confirmed by
    // hand during this round), while a point insert addressed exactly at the
    // old id is unambiguous either way.
    @Test
    fun `undoAsync re-points a later same-entry op at the reissued content`() = runTest {
        val document = Document("test-doc")
        document.setActor(actorID)
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { element("p") { text { "ab" } } })
        }.await()
        val tree = document.getRootObject()["t"] as CrdtTree
        val pID = tree.indexTree.root.allChildren.first { it.type == "p" }.id

        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0), listOf(1))
        }.await()
        assertEquals("<root></root>", document.getRoot().getAs<JsonTree>("t").toXml())

        val history = Document::class.java.getDeclaredField("internalHistory")
            .apply { isAccessible = true }
            .get(document) as History
        history.popUndo() // the real delete's own (identity-preserving) reverse -- unused here

        val copyReinsertOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPos(0),
            toPos = tree.findPos(0),
            contents = null,
            splitLevel = 0,
            executedAt = TimeTicket.InitialTimeTicket,
            undoFromOffset = 0,
            undoToOffset = 0,
            removedNodeSnapshots = listOf(
                TreeElementNode(type = "p", childNodes = listOf(TreeTextNode("ab"))),
            ),
            removedNodeOldIDs = listOf(pID),
        )
        // The later, same-entry op: insert "X" as pID's first child -- naming
        // the OLD id precisely (not a range), so this only succeeds once
        // re-pointed at the fresh copy-reinsert's node.
        val insertXOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = CrdtTreePos(pID, pID),
            toPos = CrdtTreePos(pID, pID),
            contents = listOf(CrdtTreeText(CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 0), "X")),
            splitLevel = 0,
            executedAt = TimeTicket.InitialTimeTicket,
        )
        history.pushUndo(
            listOf(HistoryOperation.Op(copyReinsertOp), HistoryOperation.Op(insertXOp)),
        )

        val result = document.history.undoAsync().await()
        assertTrue(result.isSuccess, "undo must apply cleanly: $result")
        assertEquals(
            "<root><p>Xab</p></root>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
            "the insert must land on the fresh node, not vanish against the stale id",
        )
    }

    // b173c093's reissue half -- Document-level integration (CROSS-entry
    // sweep + GC survival). entry2 (the copy-reinsert, pushed LAST, popped
    // FIRST) re-points entry1 (pushed FIRST, still sitting on the undo
    // stack, popped SECOND) via History.reconcileTreeNodeID -- the same
    // signal a split's replacedIDs already drives for its own cross-entry
    // case. A garbage collection runs BETWEEN the two undo() calls, purging
    // the old (now fully dead) id outright, so entry1 only succeeds if it
    // was ALREADY re-pointed before the purge -- this is the "redo/undo no
    // longer throws / drops content after GC" shape, reproduced locally.
    @Test
    fun `a later history entry is re-pointed before a GC purges the old id`() = runTest {
        val document = Document("test-doc")
        document.setActor(actorID)
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { element("p") { text { "ab" } } })
        }.await()
        val tree = document.getRootObject()["t"] as CrdtTree
        val pID = tree.indexTree.root.allChildren.first { it.type == "p" }.id

        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0), listOf(1))
        }.await()

        val history = Document::class.java.getDeclaredField("internalHistory")
            .apply { isAccessible = true }
            .get(document) as History
        history.popUndo()

        val insertXOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = CrdtTreePos(pID, pID),
            toPos = CrdtTreePos(pID, pID),
            contents = listOf(CrdtTreeText(CrdtTreeNodeID(TimeTicket.InitialTimeTicket, 0), "X")),
            splitLevel = 0,
            executedAt = TimeTicket.InitialTimeTicket,
        )
        // entry1 (bottom, OLDER): pushed first -- popped SECOND.
        history.pushUndo(listOf(HistoryOperation.Op(insertXOp)))

        val copyReinsertOp = TreeEditOperation(
            parentCreatedAt = tree.createdAt,
            fromPos = tree.findPos(0),
            toPos = tree.findPos(0),
            contents = null,
            splitLevel = 0,
            executedAt = TimeTicket.InitialTimeTicket,
            undoFromOffset = 0,
            undoToOffset = 0,
            removedNodeSnapshots = listOf(
                TreeElementNode(type = "p", childNodes = listOf(TreeTextNode("ab"))),
            ),
            removedNodeOldIDs = listOf(pID),
        )
        // entry2 (top, NEWER): pushed second -- popped FIRST.
        history.pushUndo(listOf(HistoryOperation.Op(copyReinsertOp)))

        val firstUndo = document.history.undoAsync().await()
        assertTrue(firstUndo.isSuccess)
        assertEquals("<root><p>ab</p></root>", document.getRoot().getAs<JsonTree>("t").toXml())

        val purged = document.garbageCollect(maxVectorOf(listOf(actorID)))
        assertTrue(purged > 0, "the old (now unreferenced) tombstone must be purgeable")

        val secondUndo = document.history.undoAsync().await()
        assertTrue(secondUndo.isSuccess, "undo must apply cleanly after GC: $secondUndo")
        assertEquals(
            "<root><p>Xab</p></root>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
            "entry1 must have been re-pointed at the fresh id BEFORE the purge," +
                " or it now names an id nothing can find",
        )
    }
}
