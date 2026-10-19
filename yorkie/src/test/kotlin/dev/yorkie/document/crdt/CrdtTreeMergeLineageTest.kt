package dev.yorkie.document.crdt

import dev.yorkie.api.toCrdtElement
import dev.yorkie.api.toPBTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports `tree_merge_lineage_test.ts` (JS SDK `d3ffe946`, #1406) as JVM unit
 * tests.
 *
 * A merge pointer (`mergedFrom`, and the `mergedInto` derived from it) is not
 * always server-derived: an element payload (Set/Add/ArraySet) keeps the
 * lineage a reverse-of-Remove legitimately carries, so both ends of a merge
 * relation can be client-supplied. Every reader therefore resolves them to
 * the exact element they name, never to a floor match or a text node. These
 * mirror yorkie's `tree_merge_lineage_test.go` (yorkie#2033).
 */
class CrdtTreeMergeLineageTest {

    private val actor = "000000000000000000000001"

    /** Hands out strictly increasing tickets for [actor], one per test. */
    private fun ticketer(): () -> TimeTicket {
        var lamport = 0L
        return {
            lamport += 1
            TimeTicket(lamport, 0u, actor)
        }
    }

    private class Fixture(
        val tree: CrdtTree,
        val p1: CrdtTreeNode,
        val p2: CrdtTreeNode,
        val p3: CrdtTreeNode,
        val text: CrdtTreeNode,
    )

    /**
     * Builds `<r><p></p><p>cd</p><p></p></r>`: p1 is the merge destination,
     * p2 holds the text that carries a lineage, and p3 is the unrelated live
     * element a forged pointer tries to name. Indices (0, 2, 3, 6) mirror
     * JS's `buildFixture` exactly: p1 at the root's leftmost, p2 right after
     * p1, the text inside p2, p3 right after p2. [issue] is the SAME ticket
     * source the calling test continues with, mirroring JS's one shared
     * `dummyContext` across `buildFixture` and the test body.
     */
    private fun buildFixture(issue: () -> TimeTicket): Fixture {
        fun element(type: String) = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), type)
        fun text(value: String) = CrdtTreeText(CrdtTreeNodeID(issue(), 0), value)

        val tree = CrdtTree(element("r"), issue())

        val p1 = element("p")
        tree.edit(tree.findPos(0) to tree.findPos(0), listOf(p1), 0, issue(), issue)

        val p2 = element("p")
        tree.edit(tree.findPos(2) to tree.findPos(2), listOf(p2), 0, issue(), issue)

        val textNode = text("cd")
        tree.edit(tree.findPos(3) to tree.findPos(3), listOf(textNode), 0, issue(), issue)

        val p3 = element("p")
        tree.edit(tree.findPos(6) to tree.findPos(6), listOf(p3), 0, issue(), issue)

        assertEquals("<r><p></p><p>cd</p><p></p></r>", tree.toXml())

        return Fixture(tree, p1, p2, p3, textNode)
    }

    /**
     * Returns an id with [node]'s `createdAt` and an offset it never had: a
     * floor lookup answers with the node, an exact lookup does not.
     */
    private fun forgedIdOf(node: CrdtTreeNode): CrdtTreeNodeID =
        CrdtTreeNodeID(node.id.createdAt, node.id.offset + 9)

    @Test
    fun `rebuildMergeState should not plant a pointer through a forged source`() {
        val issue = ticketer()
        val f = buildFixture(issue)
        // Tombstoned, so the "a live element is never a merge source" guard
        // is satisfied and the forged offset is the only thing left to
        // reject it: the test fails if findMergeNode here goes back to a
        // floor lookup.
        f.p3.remove(issue())
        f.text.mergedFrom = forgedIdOf(f.p3)
        f.text.mergedAt = issue()

        // Re-read exactly as the converter re-reads an element payload.
        CrdtTree(f.tree.root, issue())

        assertNull(
            f.p3.mergedInto,
            "a later delete would follow this pointer and tombstone p3 children",
        )
    }

    @Test
    fun `rebuildMergeState should still rebuild a genuine source`() {
        val issue = ticketer()
        val f = buildFixture(issue)
        // A genuine source is a tombstone: the merge removes the boundary
        // element before moving its children out of it.
        f.p3.remove(issue())
        f.text.mergedFrom = f.p3.id
        f.text.mergedAt = issue()

        CrdtTree(f.tree.root, issue())

        assertEquals(f.p2.id, f.p3.mergedInto)
    }

    @Test
    fun `rebuildMergeState should not plant a pointer on a live source`() {
        // The id is exact and names an element, so the shape checks pass;
        // only the source being live says no merge ever moved these
        // children. Planting here would arm the §6.2 cascade to tombstone
        // p3's own children the moment a later, unrelated edit removes p3.
        val issue = ticketer()
        val f = buildFixture(issue)
        f.text.mergedFrom = f.p3.id
        f.text.mergedAt = issue()
        assertNull(f.p3.removedAt)

        CrdtTree(f.tree.root, issue())

        assertNull(f.p3.mergedInto)
    }

    @Test
    fun `rebuildMergeState should not name a text node as a source`() {
        val issue = ticketer()
        val f = buildFixture(issue)
        // Removed as a genuine source would be, so only its being a text
        // node rejects it: the id is exact and the child's parent is an
        // element.
        f.text.remove(issue())
        f.p2.mergedFrom = f.text.id
        f.p2.mergedAt = issue()

        CrdtTree(f.tree.root, issue())

        assertNull(f.text.mergedInto)
    }

    /**
     * JS plants a child directly under a text node by pushing onto its raw
     * `_children` array, bypassing every API check, to pin that
     * `rebuildMergeState` rejects the resulting pointer via the
     * `node.parent.isText` guard. [CrdtTreeNode.append]/`prepend`/`insertAt`
     * (the only ways to attach a child in this SDK) all throw
     * `IllegalStateException("Text node cannot have children")` before the
     * forged state can even exist -- the JS shape is not constructible here
     * (a red run is not constructible). The guard is still ported in
     * [CrdtTree.rebuildMergeState] for parity with a crafted/corrupt
     * snapshot this reader does not itself validate; this test pins the
     * constructibility boundary instead of the (unreachable) rejection.
     */
    @Test
    fun `rebuildMergeState cannot plant a child under a text node (not constructible)`() {
        val issue = ticketer()
        val f = buildFixture(issue)
        f.p3.remove(issue())
        val child = CrdtTreeText(CrdtTreeNodeID(issue(), 0), "x")
        child.mergedFrom = f.p3.id
        child.mergedAt = issue()

        assertFailsWith<IllegalStateException> {
            f.text.append(child)
        }
    }

    @Test
    fun `a merge should not re-derive a pointer from a forged source`() {
        // The lineage stays on the node inside the live document, and a
        // merge re-derives mergedInto from it whenever it moves that node
        // again. Resolved by floor there, the forged offset would plant on
        // p3 the pointer the decode refused to.
        val issue = ticketer()
        val f = buildFixture(issue)
        // Tombstoned, as a genuine source is, so the merge's own "the
        // source must already be removed" guard cannot be what rejects
        // this: the forged offset has to be, which is what findMergeNode is
        // here for.
        f.p3.remove(issue())
        f.text.mergedFrom = forgedIdOf(f.p3)
        f.text.mergedAt = issue()

        // An ordinary merge of p2 into p1 moves the text carrying the
        // lineage.
        f.tree.edit(f.tree.findPos(1) to f.tree.findPos(3), null, 0, issue(), issue)
        assertEquals("<r><p>cd</p></r>", f.tree.toXml())

        assertNull(
            f.p3.mergedInto,
            "an ordinary merge must not plant a pointer on an unrelated node",
        )
    }

    @Test
    fun `a merge should not re-derive a pointer onto a live source`() {
        // Same path, the other half of the rule: the id is exact and names
        // an element, so only p3 still being live says no merge ever moved
        // this text out of it. Planting here would arm the §6.2 cascade to
        // tombstone p3's own children the moment a later, unrelated edit
        // removes p3.
        val issue = ticketer()
        val f = buildFixture(issue)
        f.text.mergedFrom = f.p3.id
        f.text.mergedAt = issue()
        assertNull(f.p3.removedAt)

        f.tree.edit(f.tree.findPos(1) to f.tree.findPos(3), null, 0, issue(), issue)
        assertEquals("<r><p>cd</p><p></p></r>", f.tree.toXml())

        assertNull(
            f.p3.mergedInto,
            "an ordinary merge must not plant a pointer on a live node",
        )
    }

    @Test
    fun `a merge should still re-derive a pointer from its own source`() {
        // The positive control for both guards above: in a merge the engine
        // itself stamps, the source is the boundary element step 02
        // tombstoned, named exactly, so the pointer must still be planted.
        val issue = ticketer()
        val f = buildFixture(issue)

        f.tree.edit(f.tree.findPos(1) to f.tree.findPos(3), null, 0, issue(), issue)
        assertEquals("<r><p>cd</p><p></p></r>", f.tree.toXml())

        assertEquals(f.p2.id, f.text.mergedFrom)
        assertTrue(f.p2.isRemoved)
        assertEquals(f.p1.id, f.p2.mergedInto)
    }

    @Test
    fun `a delete should not cascade through a floor-only destination`() {
        // p1 holds a child whose lineage names p2, and p2's pointer floors
        // onto p1 without naming it. Deleting p2 must not tombstone p1's
        // child.
        val issue = ticketer()
        val f = buildFixture(issue)
        f.tree.edit(
            f.tree.findPos(1) to f.tree.findPos(1),
            listOf(CrdtTreeText(CrdtTreeNodeID(issue(), 0), "ab")),
            0,
            issue(),
            issue,
        )
        val ab = f.p1.allChildren[0]
        ab.mergedFrom = f.p2.id
        ab.mergedAt = issue()
        f.p2.mergedInto = forgedIdOf(f.p1)

        // Delete p2 whole: <r><p>ab</p>|<p>cd</p>|<p></p></r>.
        f.tree.edit(f.tree.findPos(4) to f.tree.findPos(8), null, 0, issue(), issue)

        assertEquals("<r><p>ab</p><p></p></r>", f.tree.toXml())
    }

    @Test
    fun `resolveMergeTarget should not forward to a text node`() {
        val issue = ticketer()
        val f = buildFixture(issue)
        f.p1.remove(issue())
        f.p1.mergedInto = f.text.id

        val target = f.tree.resolveMergeTargetForTest(f.p1)

        assertSame(f.p1, target)
    }

    @Test
    fun `an insert should not be redirected into a text node`() {
        // §1.1 redirects an insert at the leftmost of a merged-away parent
        // into its merge target. A text node can hold no children, so a
        // pointer naming one must fall through to the normal path.
        val issue = ticketer()
        val f = buildFixture(issue)
        f.tree.edit(f.tree.findPos(0) to f.tree.findPos(2), null, 0, issue(), issue)
        assertEquals("<r><p>cd</p><p></p></r>", f.tree.toXml())
        f.p1.mergedInto = f.text.id

        val pos = CrdtTreePos(f.p1.id, f.p1.id)
        val inserted = CrdtTreeText(CrdtTreeNodeID(issue(), 0), "x")
        f.tree.edit(pos to pos, listOf(inserted), 0, issue(), issue)

        // The fall-through parks the insert under the removed p1, where the
        // born-dead branch tombstones it.
        assertSame(f.p1, inserted.parent)
        assertTrue(f.text.allChildren.isEmpty())
        assertEquals("<r><p>cd</p><p></p></r>", f.tree.toXml())
    }

    /**
     * The merge in [CrdtTree.edit]
     * stamps a moved child's [CrdtTreeNode.mergedAt] with the merge's own
     * ticket, once, on first move (`CrdtTree.kt:991-997`). A LATER,
     * unrelated op can still bump the merge source's OWN
     * [CrdtTreeNode.removedAt] through ordinary LWW ([CrdtTreeNode.remove]) --
     * e.g. a concurrent whole-subtree delete that also covers the
     * already-merged boundary element. [CrdtTree.rebuildMergeState] must not
     * let that later bump overwrite the wire-carried merge ticket on
     * snapshot reload, or a snapshot-loaded replica disagrees with the live
     * one on when the child was moved. Mirrors JS SDK `tree.ts`
     * `rebuildMergeState`'s back-compat guard (`d3ffe946`, #1406): "only
     * derive `mergedAt` when the node does not already carry one."
     */
    @Test
    fun `rebuildMergeState should not let a later source tombstone overwrite a wire mergedAt`() {
        val issue = ticketer()
        val f = buildFixture(issue)

        // An ordinary merge of p2 into p1 stamps text.mergedAt with the
        // merge's own ticket (CrdtTree.kt:996), equal to p2.removedAt here.
        f.tree.edit(f.tree.findPos(1) to f.tree.findPos(3), null, 0, issue(), issue)
        assertEquals("<r><p>cd</p><p></p></r>", f.tree.toXml())
        val mergeTicket = requireNotNull(f.text.mergedAt)
        assertEquals(f.p2.removedAt, mergeTicket)

        // A later, unrelated op bumps the already-removed p2's OWN
        // removedAt via ordinary LWW -- legitimate on its own, but must not
        // retroactively change when the merge moved `text`.
        val laterTicket = issue()
        f.p2.remove(laterTicket)
        assertTrue(laterTicket > mergeTicket)
        assertEquals(laterTicket, f.p2.removedAt)

        // In-place rebuild path: re-reading the SAME node graph must keep
        // the original merge ticket, not re-derive it from the now-bumped
        // source.removedAt.
        CrdtTree(f.tree.root, issue())
        assertEquals(mergeTicket, f.text.mergedAt)

        // Wire path: the encoder writes a non-null mergedAt, so a fresh
        // replica decoding the same bytes must resolve the same ticket, not
        // the source's later tombstone.
        val decoded = f.tree.toPBTree().toCrdtElement() as CrdtTree
        val decodedText = requireNotNull(decoded.findFloorNode(f.text.id))
        assertEquals(mergeTicket, decodedText.mergedAt)
    }

    /**
     * Positive control for the guard above: a pre-#313 snapshot never wrote
     * `mergedAt` at all, so a decoded node carries `mergedFrom` with
     * `mergedAt` still null. [CrdtTree.rebuildMergeState] must still derive
     * it from the source's `removedAt` in that case -- the `if (node.mergedAt
     * == null)` guard only blocks a wire value ALREADY present, not a
     * missing one.
     */
    @Test
    fun `rebuildMergeState should still derive mergedAt for a legacy snapshot with no mergedAt`() {
        val issue = ticketer()
        val f = buildFixture(issue)
        f.p2.remove(issue())
        f.text.mergedFrom = f.p2.id
        assertNull(f.text.mergedAt)

        CrdtTree(f.tree.root, issue())

        assertEquals(f.p2.removedAt, f.text.mergedAt)
    }

    /**
     * Android-authored extra (no JS equivalent): [CrdtTreeNode.unremove] on
     * its own drops a lingering [CrdtTreeNode.mergedInto], not only when
     * preceded by [CrdtTree.dissolveMerge] (every production call site --
     * [CrdtTree.restore]'s element branch -- always calls `dissolveMerge`
     * first, which already clears the field, so that call site alone cannot
     * tell `unremove` kept its own clearing). A live node holding one is a
     * pointer a snapshot-loading replica would not rebuild
     * ([CrdtTree.rebuildMergeState] only ever derives it on a removed
     * source), so `unremove` guards the invariant directly. Port JS SDK
     * `tree.ts` `CRDTTreeNode.unremove` (d3ffe946, #1406; yorkie#2033).
     */
    @Test
    fun `unremove clears a lingering mergedInto pointer on its own`() {
        val issue = ticketer()
        val f = buildFixture(issue)
        f.p3.remove(issue())
        f.p3.mergedInto = f.p1.id

        f.p3.unremove()

        assertNull(f.p3.mergedInto)
    }
}
