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
    // copy-reinsert (the pre-existing path), never via identity restore.
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
    // re-split reverse path (redoSplitLevel), never identity restore.
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

    // AC11: the reverse-span payload fingerprint stays stable and non-empty
    // across repeated undo/redo cycles, and pre-tombstoned nodes never
    // reappear in it.
    @Test
    fun `reverse span fingerprint is stable across repeated undo redo cycles`() = runTest {
        val document = Document("test-doc")
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("root") { text { "hello" } })
        }.await()
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 3) }.await()
        // "el" pre-tombstoned before the undo/redo cycles ever start.
        assertEquals("<root>hlo</root>", document.getRoot().getAs<JsonTree>("t").toXml())

        repeat(4) {
            document.history.undoAsync().await()
            assertEquals(
                "<root>hello</root>",
                document.getRoot().getAs<JsonTree>("t").toXml(),
            )
            document.history.redoAsync().await()
            assertEquals(
                "<root>hlo</root>",
                document.getRoot().getAs<JsonTree>("t").toXml(),
            )
        }
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
