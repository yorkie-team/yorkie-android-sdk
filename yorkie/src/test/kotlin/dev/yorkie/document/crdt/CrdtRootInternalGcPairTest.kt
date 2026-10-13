package dev.yorkie.document.crdt

import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.helper.oneWayDeliver
import dev.yorkie.util.DataSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Ports `root_internal_gc_pair_test.ts` (yorkie-js-sdk `dbff8892`, #1405;
 * itself a port of Go `TestRegisterElementBooksInternalTombstones`/
 * `TestRegisterElementSkipsTombstonedTreeRoot`, yorkie#2033) as JVM unit
 * tests.
 *
 * [CrdtRoot.registerElement] now books the tombstones an element carries
 * INSIDE itself (a [CrdtTree]'s removed nodes/attributes, a [CrdtText]'s
 * removed pieces, a [CrdtArray]'s dead positions) on every registration, not
 * only when [CrdtRoot] is first built from a snapshot — the old constructor-
 * only scan this folds into [CrdtRoot.registerElement] missed a Set/Add/
 * ArraySet payload and an undone container removal entirely. The first two
 * cases below hand-build a [CrdtTree] directly (mirrors
 * [CrdtTreeSplitLinkGuardTest.poisonedTree]'s fixture style) to drive
 * [CrdtRoot.registerElement] the same way [dev.yorkie.document.operation.SetOperation]
 * does; the last two drive it through a real [Document] undo/remote-Set path.
 */
class CrdtRootInternalGcPairTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    /** Hands out increasing-lamport tickets for one actor. */
    private fun ticketer(actor: String): () -> TimeTicket {
        var lamport = 0L
        return {
            lamport += 1
            TimeTicket(lamport, 0u, actor)
        }
    }

    private fun newRoot(): CrdtRoot =
        CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket, memberNodes = ElementRht()))

    /** Counts removed nodes still linked into the tree at key `"t"`. */
    private fun tombstonesIn(d: Document): Int {
        val tree = d.getRootObject()["t"] as CrdtTree
        var count = 0
        tree.indexTree.traverseAll { node, _ -> if (node.removedAt != null) count++ }
        return count
    }

    /**
     * Asserts the running `docSize` and garbage count equal what a root
     * rebuilt from the same content computes. Mirrors the JS test helper
     * `assertMatchesRebuild`.
     */
    private fun assertMatchesRebuild(d: Document, msg: String) {
        val rebuilt = CrdtRoot(d.getRootObject().deepCopy())
        assertEquals(rebuilt.docSize, d.getDocSize(), "$msg: docSize")
        assertEquals(rebuilt.garbageLength, d.garbageLength, "$msg: garbage")
    }

    /**
     * Port of Go `TestRegisterElementBooksInternalTombstones` (yorkie#2033).
     * Red at `ea06769d`: before this port, `registerElement` never
     * walked a freshly-registered element's own internal structure, so a
     * tombstone a Set payload carried inside its tree was invisible to gc
     * until the next snapshot rebuild.
     */
    @Test
    fun `books a tombstone inside the registered element`() {
        val issue = ticketer(actor1)
        val treeRoot = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), "r")
        val para = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), "p")
        treeRoot.append(para)
        val textNode = CrdtTreeText(CrdtTreeNodeID(issue(), 0), "hello")
        para.append(textNode)

        // The payload was captured while this node was already a tombstone.
        textNode.remove(issue())

        val ticket = issue()
        val tree = CrdtTree(treeRoot, ticket)
        val root = newRoot()
        root.rootObject.set("tree", tree, ticket)

        val before = root.garbageLength
        root.registerElement(tree, root.rootObject)
        assertEquals(
            before + 1,
            root.garbageLength,
            "a tombstone inside the registered element has to be collectable",
        )
        val gc = root.docSize.gc
        assertTrue(gc.data + gc.meta != 0, "its bytes belong to gc")

        assertEquals(1, root.garbageCollect(maxVectorOf(listOf(actor1))))
        assertEquals(DataSize(data = 0, meta = 0), root.docSize.gc)
    }

    /**
     * Port of Go `TestRegisterElementSkipsTombstonedTreeRoot` (yorkie#2033).
     * Red at `ea06769d`: the tree-wide scan booked a removed node
     * regardless of whether it had a parent, so a crafted payload marking
     * the root itself removed got the root booked as a collectable child —
     * [CrdtTree.gcPairs]'s `node.parent != null` guard is what this pins.
     */
    @Test
    fun `never books the tree root`() {
        val issue = ticketer(actor1)
        val treeRoot = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), "r")
        val para = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), "p")
        treeRoot.append(para)

        // A crafted payload marks every node removed, the root included.
        treeRoot.remove(issue())
        para.remove(issue())

        val ticket = issue()
        val tree = CrdtTree(treeRoot, ticket)
        val root = newRoot()
        root.rootObject.set("tree", tree, ticket)

        val before = root.garbageLength
        root.registerElement(tree, root.rootObject)
        assertEquals(
            before + 1,
            root.garbageLength,
            "only the parented tombstone is booked, never the root",
        )
        assertEquals(1, root.garbageCollect(maxVectorOf(listOf(actor1))))
    }

    /**
     * An undone container removal re-sets a deep copy of the whole subtree
     * under the ORIGINAL `SetOperation` path (`Document.history`'s reverse
     * op), so the tombstone the tree held before it was removed has to be
     * booked again on the way back in. Red at `ea06769d`.
     */
    @Test
    fun `books the tombstones an undone container removal brings back`() = runTest {
        val d = Document("test-doc")
        d.setActor(actor1)
        d.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "abc" } }
                },
            )
        }.await()
        d.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 3) }.await()
        d.updateAsync { root, _ -> root.remove("t") }.await()
        d.history.undoAsync().await()
        assertEquals("<r><p>ac</p></r>", d.getRoot().getAs<JsonTree>("t").toXml())
        // No rebuild check here: the orphaned tombstone tree still holds its
        // own pair for "b" until collection, as it does in Go.

        d.garbageCollect(maxVectorOf(listOf(actor1)))
        assertEquals(0, tombstonesIn(d), "the restored tombstone is collected")
        assertEquals(0, d.garbageLength)
        assertMatchesRebuild(d, "after gc")
    }

    /**
     * The same, but across the wire: a remote replica decodes the SAME Set
     * (not a locally-issued undo), proving the booking is not tied to the
     * local undo code path. Red at `ea06769d`.
     */
    @Test
    fun `books them on a replica that decodes the same Set`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "abc" } }
                },
            )
        }.await()
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(2, 3) }.await()
        d1.updateAsync { root, _ -> root.remove("t") }.await()
        oneWayDeliver(d1, d2)

        // The undo carries a Set whose tree still holds the tombstoned "b".
        d1.history.undoAsync().await()
        oneWayDeliver(d1, d2)

        assertEquals("<r><p>ac</p></r>", d2.getRoot().getAs<JsonTree>("t").toXml())

        d2.garbageCollect(maxVectorOf(listOf(actor1, actor2)))
        assertEquals(0, tombstonesIn(d2), "the decoded tombstone is collected")
        assertEquals(0, d2.garbageLength)
        assertMatchesRebuild(d2, "remote after gc")
    }
}
