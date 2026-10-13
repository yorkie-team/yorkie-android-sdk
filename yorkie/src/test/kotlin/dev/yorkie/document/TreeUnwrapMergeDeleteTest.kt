package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_unwrap_merge_delete_test.ts` (JS SDK `b69169c0`, #1404) as JVM
 * in-process cross-sync tests.
 *
 * An unwrap -- a content-less edit over a paragraph's opening token, which
 * hoists its children into the root -- has to converge with a concurrent
 * delete that covered the same paragraph whole (#1331, yorkie#2042).
 *
 * §6.2 propagates a delete to the children a concurrent merge moved out of
 * the deleted node, but it used to skip that whenever the children had
 * landed in this edit's own merge destination. An unwrap moves them into the
 * root, and a delete that ends at the next paragraph's opening token merges
 * into the root too, so the skip fired and the hoisted text stayed alive on
 * the replica that unwrapped first. `declaredBoundaries` fixes this by
 * skipping only a boundary the edit's OWN positions declared, not every
 * source already forwarding into the edit's destination.
 */
class TreeUnwrapMergeDeleteTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    /**
     * Renders the tree with every node's id, tombstones included. XML
     * cannot tell two empty `<p>`s apart; this can, so it is what the
     * convergence checks below compare.
     */
    private fun treeShape(node: CrdtTreeNode): String {
        val removed = if (node.isRemoved) "x" else ""
        return if (node.isText) {
            "${node.id}$removed\"${node.value}\""
        } else {
            val children = node.allChildren.joinToString(",") { treeShape(it) }
            "${node.type}#${node.id}$removed[$children]"
        }
    }

    /**
     * [treeShape] minus the tombstones. Where two replicas agree on every
     * visible node's identity and order but not on where a tombstone sits
     * among them, this is the part of convergence that holds.
     */
    private fun liveTreeShape(node: CrdtTreeNode): String {
        return if (node.isText) {
            "${node.id}\"${node.value}\""
        } else {
            val children = node.children.joinToString(",") { liveTreeShape(it) }
            "${node.type}#${node.id}[$children]"
        }
    }

    /**
     * Seeds two replicas with `<r><p>ab</p><p>cd</p></r>`, synced, whose
     * token indexes are
     * ```
     *      0   1 2   3    4   5 6   7    8
     *     <r> <p> a b </p> <p> c d </p> </r>
     * ```
     */
    private suspend fun twoParagraphReplicas(): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        crossSync(d1, d2)
        return d1 to d2
    }

    @Test
    fun `tombstones the hoisted children a concurrent delete covered whole`() = runTest {
        val (d1, d2) = twoParagraphReplicas()

        // d1 unwraps p1: edit(0, 1) removes only its opening token, hoisting
        // ab into the root. d2 deletes p1 whole -- ab included -- and p2's
        // opening token: edit(0, 5).
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 1) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        // d2's delete covered ab, so it is gone on both replicas.
        assertEquals("<r>cd</r>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals("<r>cd</r>", d2.getRoot().getAs<JsonTree>("t").toXml())

        // The replicas agree on every live node and its order, but not on
        // where the tombstoned ab sits: the merge appends moved children to
        // the end of the destination, so each replica orders them by
        // arrival. That is yorkie's open merge-moved-child-order task,
        // shared by both SDKs and not introduced here. Pinned, so the day
        // it is fixed this test says so.
        assertEquals(liveTreeShape(d1.crdtTree().root), liveTreeShape(d2.crdtTree().root))
        assertNotEquals(
            treeShape(d1.crdtTree().root),
            treeShape(d2.crdtTree().root),
            "merge-moved child ordering now converges: assert treeShape equality",
        )
    }

    @Test
    fun `keeps the hoisted children when both replicas run the same unwrap`() = runTest {
        val (d1, d2) = twoParagraphReplicas()

        // The skip §6.2 still needs: each replica sees the other's merge
        // already done and must not read it as a delete of the children it
        // moved itself. edit(4, 5) removes p2's opening token, hoisting cd
        // into the root.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(4, 5) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(4, 5) }.await()

        crossSync(d1, d2)

        assertEquals("<r><p>ab</p>cd</r>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals("<r><p>ab</p>cd</r>", d2.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(treeShape(d1.crdtTree().root), treeShape(d2.crdtTree().root))
    }

    @Test
    fun `keeps the children of a paragraph a concurrent merge emptied`() = runTest {
        val (d1, d2) = twoParagraphReplicas()

        // d1 merges p2 into p1 (edit(1, 5) removes ab, p1's closing token
        // and p2's opening token), while d2 unwraps p2 by removing its
        // closing token (edit(7, 8)). d2's range starts inside p2, so it
        // deletes nothing p2 held -- cd survives.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 5) }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(7, 8) }.await()

        crossSync(d1, d2)

        assertEquals("<r><p>cd</p></r>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(treeShape(d1.crdtTree().root), treeShape(d2.crdtTree().root))
    }
}
