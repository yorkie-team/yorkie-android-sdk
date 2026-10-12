package dev.yorkie.document.crdt

import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

/**
 * Ports `tree_boundary_anchor_test.ts` (JS SDK `ec7558e9`, #1394) as JVM unit
 * tests (spec 033 AC4).
 *
 * [CrdtTree.leftAnchorID] and [CrdtTree.emptyRunReachesActor] are both
 * private, so this file goes through the `*ForTest` accessors added next to
 * each, mirroring iOS's `leftAnchorIDForTest`/`emptyRunReachesActorForTest`
 * (915dd77fe6).
 */
class CrdtTreeBoundaryAnchorTest {

    private val actorA = "000000000000000000000001"
    private val actorB = "000000000000000000000002"

    private fun ticketOf(lamport: Long, actorID: String) = TimeTicket(lamport, 0u, actorID)

    /**
     * Holds the tree [buildTree] returns, plus the three nodes its two cases
     * need direct handles to.
     */
    private data class Fixture(
        val tree: CrdtTree,
        val sibling: CrdtTreeNode,
        val removedChild: CrdtTreeNode,
    )

    /**
     * Builds a root holding [Fixture.sibling] (created by actor B, with its
     * `insNextID` pointing at a second `own` paragraph created by actor A)
     * followed by that `own` paragraph -- the shape
     * [CrdtTree.emptyRunReachesActorForTest] walks: an unknown concurrent
     * split sibling standing right before this actor's own split product.
     * [Fixture.sibling] holds one text child, [Fixture.removedChild].
     * Mirrors JS `tree_boundary_anchor_test.ts`'s `buildTree`.
     */
    private fun buildTree(): Fixture {
        val root = CrdtTreeElement(CrdtTreeNodeID(ticketOf(1, actorA), 0), "r")
        val sibling = CrdtTreeElement(CrdtTreeNodeID(ticketOf(5, actorB), 0), "p")
        val own = CrdtTreeElement(CrdtTreeNodeID(ticketOf(6, actorA), 0), "p")
        val removedChild = CrdtTreeText(CrdtTreeNodeID(ticketOf(7, actorB), 0), "ab")
        sibling.append(removedChild)
        root.append(sibling)
        root.append(own)
        sibling.insNextID = own.id

        return Fixture(CrdtTree(root, ticketOf(1, actorA)), sibling, removedChild)
    }

    @Test
    fun `does not let a tombstone change which side a split lands on`() {
        val (tree, sibling, removedChild) = buildTree()

        // A child stands between the boundary and our split, so the run is
        // not empty.
        assertFalse(tree.emptyRunReachesActorForTest(sibling, actorA, VersionVector()))

        // Removing that child must not flip the answer. This predicate
        // decides which side of a concurrent boundary an insertion lands on,
        // and every replica has to decide the same way; `isRemoved` is
        // mutable and delivery-order dependent, so reading the filtered
        // `children` here would make a replica that has already applied the
        // removal place the insertion differently from one that has not.
        // Regression lock only -- no source change this commit (Android
        // already reads `allChildren`, unfiltered; iOS-confirmed
        // 915dd77fe6, "emptyRunReachesActor already read the unfiltered
        // children, so its regression-lock test is ported with no source
        // change"). See the build report's RED-then-GREEN section for the
        // Commit-1 `allChildren`-to-`children` revert that demonstrates this
        // assertion actually depends on the guard.
        removedChild.remove(ticketOf(8, actorA))
        assertFalse(tree.emptyRunReachesActorForTest(sibling, actorA, VersionVector()))
    }

    @Test
    fun `anchors an empty text node on its own id, never offset -1`() {
        val (tree, _, _) = buildTree()

        val empty = CrdtTreeText(CrdtTreeNodeID(ticketOf(9, actorB), 0), "")
        val anchor = tree.leftAnchorIDForTest(empty)
        assertEquals(0, anchor.offset)
        assertEquals(empty.id, anchor)

        // A text node with characters still anchors on its last one.
        val text = CrdtTreeText(CrdtTreeNodeID(ticketOf(10, actorB), 3), "abc")
        assertEquals(5, tree.leftAnchorIDForTest(text).offset)
    }
}
