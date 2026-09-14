package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.OperationInfo
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.issueTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports the identity-preserving undo/redo cases in `history_tree_test.ts`
 * and `history_tree_split_test.ts` (JS SDK fa6cc513) as JVM unit tests
 * (AC7, AC8, AC10, AC11, AC13).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TreeRestoreTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    private fun CrdtTree.nodesOfType(type: String): List<CrdtTreeNode> = buildList {
        indexTree.traverseAll { node, _ -> if (node.type == type) add(node) }
    }

    /** Finds the id of the single text node whose value equals [content]. */
    private fun idOfText(
        tree: CrdtTree,
        content: String,
        removed: Boolean,
    ): CrdtTreeNodeID {
        var found: CrdtTreeNodeID? = null
        tree.indexTree.traverseAll { node, _ ->
            if (node.isText && node.isRemoved == removed && node.value == content) {
                found = node.id
            }
        }
        return requireNotNull(found) { "no text node '$content' removed=$removed" }
    }

    // AC7: undo revives the original node by identity (un-tombstone); redo
    // re-tombstones exactly that node.
    @Test
    fun `undo revives and redo re-tombstones the same node id`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") { element("p") { text { "hello" } } },
            )
        }.await()

        // Delete "hello" — the reverse op should carry restore spans, not a copy.
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 6) }.await()
        assertEquals("<root><p></p></root>", document.getRoot().getAs<JsonTree>("t").toXml())

        val deletedId = idOfText(document.crdtTree(), "hello", removed = true)

        document.history.undoAsync().await()
        assertEquals(
            "<root><p>hello</p></root>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertEquals(deletedId, idOfText(document.crdtTree(), "hello", removed = false))

        document.history.redoAsync().await()
        assertEquals("<root><p></p></root>", document.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(deletedId, idOfText(document.crdtTree(), "hello", removed = true))

        document.history.undoAsync().await()
        assertEquals(
            "<root><p>hello</p></root>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertEquals(deletedId, idOfText(document.crdtTree(), "hello", removed = false))
    }

    // AC7 (purged subtree): delete a whole element subtree, GC-purge its
    // tombstone, then undo must RECREATE the original identity, not a fresh
    // copy — the id captured before deletion must be the one found live
    // afterward.
    @Test
    fun `undo recreates a purged element subtree under its original identity`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") { element("p") { text { "hello" } } },
            )
        }.await()
        val pID = document.crdtTree().nodesOfType("p").single().id

        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 7) }.await()
        assertEquals("<doc></doc>", document.getRoot().getAs<JsonTree>("t").toXml())

        val purged = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertTrue(purged > 0, "the deleted subtree should be purged")
        assertEquals(0, document.garbageLength)

        document.history.undoAsync().await()
        assertEquals("<doc><p>hello</p></doc>", document.getRoot().getAs<JsonTree>("t").toXml())
        val revived = document.crdtTree().nodesOfType("p").single()
        assertEquals(pID, revived.id)
        assertTrue(!revived.isRemoved)

        document.history.redoAsync().await()
        assertEquals("<doc></doc>", document.getRoot().getAs<JsonTree>("t").toXml())
        val purgedAgain = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertTrue(purgedAgain > 0)

        document.history.undoAsync().await()
        assertEquals("<doc><p>hello</p></doc>", document.getRoot().getAs<JsonTree>("t").toXml())
    }

    // AC13 (backlog 002): tree undo-to-empty, then redo, must not throw and
    // must land on a legal final state — mirrors the clamp regression fixed
    // for Text in spec 004 round 3.
    @Test
    fun `undo to empty then redo does not throw`() = runTest {
        val document = Document("test-doc")
        // setNewTree's own Set op is not itself undoable — the tree must be
        // created empty, then filled via a separate (undoable) edit, mirroring
        // TextRestoreTest's setNewText().edit(...) split.
        document.updateAsync { root, _ -> root.setNewTree("t", element("root")) }.await()
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 0, text { "hi" }) }
            .await()
        assertEquals("<root>hi</root>", document.getRoot().getAs<JsonTree>("t").toXml())
        assertTrue(document.history.canUndo())

        document.history.undoAsync().await()
        assertEquals("<root></root>", document.getRoot().getAs<JsonTree>("t").toXml())

        document.history.redoAsync().await()
        assertEquals("<root>hi</root>", document.getRoot().getAs<JsonTree>("t").toXml())

        // Cycle again — the fix must hold on repeat toggles, not just once.
        document.history.undoAsync().await()
        assertEquals("<root></root>", document.getRoot().getAs<JsonTree>("t").toXml())
    }

    // AC10 (guard): a merge-involved edit emits empty spans and reverses via
    // copy-reinsert (the pre-existing path), never via identity restore. This
    // case covers the round-trip; that the reverse op really is the
    // copy-reinsert one (identical XML either way) is asserted on the op itself
    // in TreeEditOperationReverseTest's `reverse of a merge-involved delete
    // is copy-reinsert, not identity restore`.
    @Test
    fun `merge-involved edit reverses via copy-reinsert, not identity restore`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()

        // edit(3,5) removes </p><p>, merging the two paragraphs — a
        // merge-boundary deletion, not a plain one.
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(3, 5) }.await()
        assertEquals("<r><p>abcd</p></r>", document.getRoot().getAs<JsonTree>("t").toXml())

        document.history.undoAsync().await()
        assertEquals(
            "<r><p>ab</p><p>cd</p></r>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )

        document.history.redoAsync().await()
        assertEquals("<r><p>abcd</p></r>", document.getRoot().getAs<JsonTree>("t").toXml())
    }

    // AC10 (guard): a pure split's own boundary-deletion undo stays on the
    // re-split reverse path (redoSplitLevel), never identity restore — pinned
    // on the reverse op in TreeEditOperationReverseTest's `reverse of pure L1
    // split ...` (assertFalse(isRestoreOp)); this case covers the round-trip.
    @Test
    fun `pure split undo redo round trips via the re-split path`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { element("p") { text { "ab" } } })
        }.await()

        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 2, 1) }.await()
        assertEquals(
            "<root><p>a</p><p>b</p></root>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )

        document.history.undoAsync().await()
        assertEquals("<root><p>ab</p></root>", document.getRoot().getAs<JsonTree>("t").toXml())

        document.history.redoAsync().await()
        assertEquals(
            "<root><p>a</p><p>b</p></root>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )
    }

    // AC11: undo/redo must emit a non-empty opInfos list — an empty one is
    // silently dropped from local history and never propagates remotely
    // (Document.executeUndoRedo).
    @Test
    fun `undo and redo emit non-empty opInfos`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { text { "hello" } })
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 3) }.await()

        val events = mutableListOf<Document.Event>()
        val collectJob = launch(UnconfinedTestDispatcher()) {
            document.events.collect(events::add)
        }

        document.history.undoAsync().await()
        val undoOps = (events.last() as Document.Event.LocalChange).changeInfo.operations
        assertTrue(undoOps.isNotEmpty())
        assertTrue(undoOps.single() is OperationInfo.TreeEditOpInfo)

        document.history.redoAsync().await()
        val redoOps = (events.last() as Document.Event.LocalChange).changeInfo.operations
        assertTrue(redoOps.isNotEmpty())
        assertTrue(redoOps.single() is OperationInfo.TreeEditOpInfo)

        collectJob.cancel()
    }

    // AC11: the reverse-span payload stays stable across repeated undo/redo
    // cycles — asserted through the ids it revives, which is the property the
    // payload exists to preserve: a regenerated (copy-reinsert) reverse would
    // render the same XML with FRESH ids. The payload's own shape (ordering,
    // duplicate ids, pre-tombstoned exclusion) is asserted directly in
    // TreeEditOperationReverseTest's `restore span payload is parent-first ...`.
    @Test
    fun `reverse span fingerprint is stable across repeated undo redo cycles`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { text { "hello" } })
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 3) }.await()
        // "el" pre-tombstoned before the undo/redo cycles ever start.
        assertEquals("<root>hlo</root>", document.getRoot().getAs<JsonTree>("t").toXml())

        var revivedIDs: List<CrdtTreeNodeID>? = null
        repeat(4) {
            document.history.undoAsync().await()
            assertEquals(
                "<root>hello</root>",
                document.getRoot().getAs<JsonTree>("t").toXml(),
            )
            // Every cycle must revive the SAME node ids — no fresh ids, and no
            // resurrection of the "el" tombstoned before the cycles started.
            val ids = buildList {
                document.crdtTree().indexTree.traverse { node, _ -> add(node.id) }
            }
            revivedIDs?.let { assertEquals(it, ids, "revived ids drift across cycles") }
            revivedIDs = ids
            document.history.redoAsync().await()
            assertEquals(
                "<root>hlo</root>",
                document.getRoot().getAs<JsonTree>("t").toXml(),
            )
        }
        assertTrue(revivedIDs!!.isNotEmpty())
    }

    // AC11: a text span's length is in UTF-16 code units — an astral
    // character (surrogate pair) must report length 2, not 1.
    @Test
    fun `restore span length counts UTF-16 code units for an astral character`() = runTest {
        val astral = "𠮷" // 𠮷, one code point, two UTF-16 units
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { text { astral } })
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 2) }.await()
        assertEquals("<root></root>", document.getRoot().getAs<JsonTree>("t").toXml())

        // Sanity: the source string itself is 2 UTF-16 units (a surrogate pair).
        assertEquals(2, astral.length)
        idOfText(document.crdtTree(), astral, removed = true)

        document.history.undoAsync().await()
        assertEquals("<root>$astral</root>", document.getRoot().getAs<JsonTree>("t").toXml())
        val revived = document.crdtTree().let { tree ->
            var node: CrdtTreeNode? = null
            tree.indexTree.traverseAll { n, _ -> if (n.isText && n.value == astral) node = n }
            requireNotNull(node)
        }
        assertEquals(2, revived.value.length)
    }

    // AC8 (anchor ladder rung b): the captured left-sibling anchor still
    // exists after GC, so a purged middle node recreates directly after it.
    @Test
    fun `recreate anchors after a surviving left sibling`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("a")
                    element("b")
                    element("c")
                },
            )
        }.await()

        // Delete the middle <b> only.
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 4) }.await()
        assertEquals("<root><a></a><c></c></root>", document.getRoot().getAs<JsonTree>("t").toXml())

        val purged = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertTrue(purged > 0)

        document.history.undoAsync().await()
        assertEquals(
            "<root><a></a><b></b><c></c></root>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )
    }

    // AC8 (anchor ladder rung d): both captured siblings are purged, but the
    // parent survives — recreate falls back to the deterministic id-order
    // slot and still lands in the correct place.
    @Test
    fun `recreate falls back to id-order slot when both captured siblings are purged`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("a")
                    element("b")
                    element("c")
                },
            )
        }.await()

        // Delete all three in one edit: all of "a","b","c"'s captured
        // sibling anchors are purged together, so each must fall back to the
        // id-order slot on undo.
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 6) }.await()
        assertEquals("<root></root>", document.getRoot().getAs<JsonTree>("t").toXml())
        val purged = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertTrue(purged > 0)

        document.history.undoAsync().await()
        assertEquals(
            "<root><a></a><b></b><c></c></root>",
            document.getRoot().getAs<JsonTree>("t").toXml(),
        )
    }

    // AC8: a child's captured parentID resolves by IDENTITY, not merely "a
    // node happens to exist there now" — an ancestor recreated by an earlier
    // undo in the same session still satisfies it, because it shares the
    // ORIGINAL id.
    @Test
    fun `recreate resolves parentID by identity once the ancestor is itself recreated`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("p") { text { "x" } }
                },
            )
        }.await()

        // Delete the text "x" first (own undo entry), then delete the
        // parent <p> itself (second undo entry, purged before the first
        // undo runs) so the text's captured parentID no longer resolves.
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 2) }.await()
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 2) }.await()
        assertEquals("<root></root>", document.getRoot().getAs<JsonTree>("t").toXml())
        val purged = document.garbageCollect(maxVectorOf(listOf(document.changeID.actor)))
        assertTrue(purged > 0)

        // Undo the <p> deletion: recreates <p> under its original identity.
        document.history.undoAsync().await()
        assertEquals("<root><p></p></root>", document.getRoot().getAs<JsonTree>("t").toXml())

        // Undo the "x" deletion: its captured parentID pointed at the
        // ORIGINAL <p>. Since recreateFromSpan resolves the CURRENT parent
        // by identity (the recreated <p> above shares that original id), it
        // resolves and "x" reappears without throwing.
        document.history.undoAsync().await()
        assertEquals("<root><p>x</p></root>", document.getRoot().getAs<JsonTree>("t").toXml())
    }

    // B2 (review #360): when a delete cascades into a concurrently-created
    // split sibling, the captured spans must stay parent-before-child.
    // restore() recreates top-down and resolves each span's parent BY
    // IDENTITY, so a child that precedes its own parent is silently dropped
    // once GC has purged the subtree — permanent divergence against a replica
    // that had not yet run GC. The cascade walk is therefore preorder, unlike
    // JS's postorder `traverseAll` (upstream carries the same defect).
    @Test
    fun `cascaded split subtree captures restore spans parent before child`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        // d2 owns the content; d1 owns the split, so a version vector that
        // knows only d2 sees the split sibling as concurrently created.
        d2.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    // A left neighbour, so the delete range below does not start
                    // at the leftmost slot (a leftmost boundary advances past the
                    // split sibling too, which would collapse the range).
                    element("s")
                    element("p") {
                        element("b") { text { "x" } }
                        element("i") { text { "y" } }
                    }
                },
            )
        }.await()
        crossSync(d1, d2)

        // d1 splits <p> between <b> and <i>: the new right-hand <p> (the split
        // sibling) carries <i>y</i> as a two-level subtree.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 6, 1) }.await()
        assertEquals(
            "<doc><s></s><p><b>x</b></p><p><i>y</i></p></doc>",
            d1.getRoot().getAs<JsonTree>("t").toXml(),
        )

        // Delete the whole doc AS d2 SEES IT: the range comes from d2's own
        // tree (which has no split sibling) and carries d2's version vector,
        // exactly like the remote change d2 would push. At d1 the insNextID
        // cascade then tombstones the split sibling's whole subtree.
        val remoteRange = d2.crdtTree().let { it.indexRangeToPosRange(2 to it.size) }
        val tree = d1.crdtTree()
        // A ticket newer than every existing node, as a real d2 delete would be.
        var delimiter = 0u
        val editedAt = TimeTicket(100L, 0u, actor2)
        val result = tree.edit(
            remoteRange,
            null,
            0,
            editedAt,
            { TimeTicket(100L, ++delimiter, actor2) },
            VersionVector(mapOf(actor2 to TimeTicket.MAX_LAMPORT)),
        )
        assertEquals("<doc><s></s></doc>", tree.toXml())

        val spans = result.removedSpans
        assertTrue(spans.isNotEmpty(), "the delete must capture a complete span set")
        // The cascaded subtree is present: split sibling <p>, its <i> and "y".
        assertTrue(spans.any { it.nodeType == "i" }, "cascaded child captured")
        assertTrue(spans.any { it.isText && it.value == "y" }, "cascaded grandchild captured")
        val indexByID = spans.withIndex().associate { (index, span) -> span.id to index }
        spans.forEachIndexed { index, span ->
            val parentIndex = indexByID[span.parentID] ?: return@forEachIndexed
            assertTrue(
                parentIndex < index,
                "parent ${span.parentID} must precede its child ${span.id}",
            )
        }
    }

    // AC8 (parent-gone guard, direct CrdtTree level): when the span's
    // captured parent never existed at all (not merely "not yet
    // recreated"), restore skips the node silently — no throw — and the
    // rest of the tree is unaffected.
    @Test
    fun `restore skips a span whose parent never existed, without throwing`() {
        val tree = CrdtTree(CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root"), issueTime())
        val ghostParentID = CrdtTreeNodeID(issueTime(), 0)
        val span = TreeRestoreSpan(
            id = CrdtTreeNodeID(issueTime(), 0),
            nodeType = "p",
            isText = false,
            length = 0,
            parentID = ghostParentID,
        )

        val (untombstoned, recreated) = tree.restore(listOf(span))

        assertTrue(untombstoned.isEmpty())
        assertTrue(recreated.isEmpty())
        assertEquals("<root></root>", tree.toXml())
    }
}
