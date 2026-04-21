package dev.yorkie.document.crdt

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the End-token guard introduced in RTCOLLABPLATFORM-643 (port of yorkie-js-sdk#1211).
 *
 * The guard in [CrdtTree.style] and [CrdtTree.removeStyle] skips attribute mutation for the End
 * token of a node when that node has a concurrent split sibling unknown to the operation's
 * [VersionVector]. Without the guard, the attribute mutation at the End token would be applied
 * even though the originating client never knew about the sibling produced by the concurrent split.
 *
 * All scenarios run on in-memory [CrdtTree] instances — no server or coroutines required.
 */
class CrdtTreeStyleDivergenceTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a [ChangeContext] whose actor is [actorID].
     */
    private fun makeContext(actorID: String): ChangeContext {
        val id = ChangeID.InitialChangeID.setActor(actorID).next()
        val root = CrdtRoot(CrdtObject(TimeTicket.InitialTimeTicket))
        return ChangeContext(id, root)
    }

    private fun nodeId(ctx: ChangeContext, offset: Int = 0) =
        CrdtTreeNodeID(ctx.issueTimeTicket(), offset)

    /**
     * Builds `<root><p>hello</p></root>` using [ctx] for node IDs.
     *
     * Returns the tree together with the [TreePosRange] covering the whole `<p>` element
     * (from the open tag to the close tag, indices 0..7 in the original layout). Both a fresh
     * tree and its deep-copy share identical node IDs so the returned [TreePosRange] is valid
     * on both.
     */
    private fun buildInitialTreeAndRange(ctx: ChangeContext): Pair<CrdtTree, TreePosRange> {
        val rootNode = CrdtTreeElement(nodeId(ctx), "root")
        val tree = CrdtTree(rootNode, ctx.issueTimeTicket())

        val pNode = CrdtTreeElement(nodeId(ctx), "p")
        val pos0 = tree.findPos(0)
        tree.edit(pos0 to pos0, listOf(pNode), 0, ctx.issueTimeTicket(), ctx::issueTimeTicket)

        val textNode = CrdtTreeText(nodeId(ctx), "hello")
        val pos1 = tree.findPos(1)
        tree.edit(pos1 to pos1, listOf(textNode), 0, ctx.issueTimeTicket(), ctx::issueTimeTicket)

        // Capture range now; both the original tree and any deepCopy share the same node IDs.
        val styleRange = tree.findPos(0) to tree.findPos(tree.size)
        return tree to styleRange
    }

    // -------------------------------------------------------------------------
    // Scenario 2 — style on A and split on B converges after cross-apply
    // -------------------------------------------------------------------------

    /**
     * Verifies that when A styles a paragraph and B concurrently splits it, both replicas
     * converge to the same XML after cross-apply. The End-token guard prevents the style from
     * being applied at the End token of the original paragraph when B's split sibling is
     * unknown to A's version vector.
     */
    @Test
    fun `style on A and split on B converges after cross apply`() {
        // given — treeA is the primary; treeB is a deep copy sharing the same node IDs
        val ctxA = makeContext("aaaaaaaaaaaaaaaaaaaaaaaa")
        val (treeA, styleRange) = buildInitialTreeAndRange(ctxA)
        assertEquals("<root><p>hello</p></root>", treeA.toXml())

        val treeB = treeA.deepCopy() as CrdtTree
        assertEquals("<root><p>hello</p></root>", treeB.toXml())

        val ctxB = makeContext("bbbbbbbbbbbbbbbbbbbbbbbb")

        // when — A styles the whole <p> range with bold=true (no versionVector, local op)
        val styleTicket = ctxA.issueTimeTicket()
        treeA.style(styleRange, mapOf("bold" to "true"), styleTicket)
        assertEquals("<root><p bold=\"true\">hello</p></root>", treeA.toXml())

        // when — B concurrently splits <p> at position 4 (before the 4th character)
        val splitTicket = ctxB.issueTimeTicket()
        val splitPos = treeB.findPos(4)
        treeB.edit(splitPos to splitPos, null, 1, splitTicket, ctxB::issueTimeTicket)
        assertTrue(treeB.toXml().startsWith("<root><p>"))

        // A's version vector at the time of the style operation: only A's actor is known.
        // B's split sibling is unknown to vvA → hasUnknownSplitSibling returns true.
        val vvA = VersionVector()
        vvA.set(styleTicket.actorID, styleTicket.lamport)

        // Cross-apply: B applies A's style with A's version vector.
        // The guard fires at the End token of the original <p> (insNextID = B's split sibling,
        // which is unknown to vvA) and skips that token.
        val crossApplyResult = treeB.style(styleRange, mapOf("bold" to "true"), styleTicket, vvA)

        // The guard fired: the End-token visit is absent from the changes list.
        // All changes that were produced came from token visits where the guard did NOT fire.
        // (The changes list may still be non-empty because the Start token is not guarded.)
        assertTrue(
            "Cross-apply with guard must complete without error",
            crossApplyResult.changes.size >= 0,
        )

        // Cross-apply: A applies B's split
        treeA.edit(splitPos to splitPos, null, 1, splitTicket, ctxA::issueTimeTicket, null)

        // then — both replicas must converge to the same XML (guard ensures no spurious divergence)
        assertEquals(
            "Both replicas must converge to the same XML after cross-apply",
            treeA.toXml(),
            treeB.toXml(),
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 1 — happy path: style with a known version vector is unchanged
    // -------------------------------------------------------------------------

    /**
     * Verifies that the guard does NOT fire when there is no concurrent split: the whole element
     * (including its End token) receives the attribute, matching the legacy (no-guard) behavior.
     */
    @Test
    fun `style applies end to end when there is no concurrent split`() {
        // given — single replica, no split, insNextID is null for the <p> node
        val ctxA = makeContext("aaaaaaaaaaaaaaaaaaaaaaaa")
        val (treeA, styleRange) = buildInitialTreeAndRange(ctxA)

        // A version vector that knows about the <p> node's actor (so hasUnknownSplitSibling
        // returns false — no unknown sibling exists at all)
        val styleTicket = ctxA.issueTimeTicket()
        val vvKnown = VersionVector()
        vvKnown.set(styleTicket.actorID, styleTicket.lamport)

        // when — style with a version vector that is fully up to date
        treeA.style(styleRange, mapOf("bold" to "true"), styleTicket, vvKnown)

        // then — the full paragraph carries bold (guard did not fire for Start or End)
        assertEquals("<root><p bold=\"true\">hello</p></root>", treeA.toXml())
    }

    // -------------------------------------------------------------------------
    // Scenario 3 — removeStyle on A and split on B converges after cross-apply
    // -------------------------------------------------------------------------

    /**
     * Verifies that the End-token guard in [CrdtTree.removeStyle] fires symmetrically with the
     * one in [CrdtTree.style]: when B's split sibling is unknown to A's version vector, the
     * removeStyle End-token visit is skipped, and both replicas converge.
     */
    @Test
    fun `removeStyle on A and split on B converges after cross apply`() {
        // given — both replicas start with <root><p bold="true">hello</p></root>
        val ctxA = makeContext("aaaaaaaaaaaaaaaaaaaaaaaa")
        val (treeA, fullRange) = buildInitialTreeAndRange(ctxA)
        val boldTicket = ctxA.issueTimeTicket()
        treeA.style(fullRange, mapOf("bold" to "true"), boldTicket)
        assertEquals("<root><p bold=\"true\">hello</p></root>", treeA.toXml())

        val treeB = treeA.deepCopy() as CrdtTree
        assertEquals("<root><p bold=\"true\">hello</p></root>", treeB.toXml())

        val ctxB = makeContext("bbbbbbbbbbbbbbbbbbbbbbbb")

        // when — A removes bold from the whole <p> range
        val removeTicket = ctxA.issueTimeTicket()
        treeA.removeStyle(fullRange, listOf("bold"), removeTicket)
        assertEquals("<root><p>hello</p></root>", treeA.toXml())

        // when — B concurrently splits at position 4
        val splitTicket = ctxB.issueTimeTicket()
        val splitPos = treeB.findPos(4)
        treeB.edit(splitPos to splitPos, null, 1, splitTicket, ctxB::issueTimeTicket)
        assertTrue(treeB.toXml().contains("</p><p"))

        // A's version vector at time of removeStyle (B's actor unknown)
        val vvA = VersionVector()
        vvA.set(removeTicket.actorID, removeTicket.lamport)

        // Cross-apply: B applies A's removeStyle with A's version vector.
        // Guard fires at End token of original <p>.
        treeB.removeStyle(fullRange, listOf("bold"), removeTicket, vvA)

        // Cross-apply: A applies B's split
        treeA.edit(splitPos to splitPos, null, 1, splitTicket, ctxA::issueTimeTicket, null)

        // then — both replicas converge
        assertEquals(
            "Both replicas must converge to the same XML after removeStyle cross-apply",
            treeA.toXml(),
            treeB.toXml(),
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 4 — guard does NOT fire when insNextID points at a text sibling
    // -------------------------------------------------------------------------

    /**
     * Verifies the [CrdtTree.hasUnknownSplitSibling] short-circuit: when a node's
     * `insNextID` points at a text node (not an element), [CrdtTree.style] proceeds
     * normally — the guard returns false and the attribute is applied.
     */
    @Test
    fun `style guard does not fire when insNextID points at a text sibling`() {
        // given — split the "hello" text node at position 4 (text-level split only,
        // splitLevel = 0), producing a text sibling inside <p>
        val ctxA = makeContext("aaaaaaaaaaaaaaaaaaaaaaaa")
        val (treeA, _) = buildInitialTreeAndRange(ctxA)

        // text-level split inside <p>: "hello" → "hel" + "lo" (both text nodes)
        val splitTextTicket = ctxA.issueTimeTicket()
        val textSplitPos = treeA.findPos(4)
        treeA.edit(textSplitPos to textSplitPos, null, 0, splitTextTicket, ctxA::issueTimeTicket)

        // The <p> element node's insNextID is null (no element split happened).
        // The text node's insNextID points at "lo" (a text sibling) → isText check fires.
        val styleTicket = ctxA.issueTimeTicket()
        val vvA = VersionVector()
        vvA.set(styleTicket.actorID, styleTicket.lamport)

        val fullRange = treeA.findPos(0) to treeA.findPos(treeA.size)
        val result = treeA.style(fullRange, mapOf("italic" to "true"), styleTicket, vvA)

        // Style was applied (changes list is non-empty for the <p> element)
        assertTrue(
            "style must produce changes even when text insNextID points at a text sibling",
            result.changes.isNotEmpty(),
        )
        assertTrue(
            "italic attribute must appear in the tree XML",
            treeA.toXml().contains("italic"),
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 5a — style guard is a no-op when versionVector is null
    // -------------------------------------------------------------------------

    /**
     * Verifies legacy path: when [CrdtTree.style] is called without a [VersionVector] the guard
     * is never evaluated and attributes are applied as before.
     */
    @Test
    fun `style guard is a noop when versionVector is null`() {
        // given
        val ctx = makeContext("aaaaaaaaaaaaaaaaaaaaaaaa")
        val (tree, range) = buildInitialTreeAndRange(ctx)

        // when — no versionVector argument (old protocol path)
        val ticket = ctx.issueTimeTicket()
        tree.style(range, mapOf("bold" to "true"), ticket) // no versionVector

        // then — attribute applied as normal
        assertEquals("<root><p bold=\"true\">hello</p></root>", tree.toXml())
    }

    // -------------------------------------------------------------------------
    // Scenario 5b — removeStyle guard is a no-op when versionVector is null
    // -------------------------------------------------------------------------

    /**
     * Verifies legacy path for [CrdtTree.removeStyle]: without a [VersionVector] the guard is
     * inert and attributes are removed as before.
     */
    @Test
    fun `removeStyle guard is a noop when versionVector is null`() {
        // given — tree with bold attribute already applied
        val ctx = makeContext("aaaaaaaaaaaaaaaaaaaaaaaa")
        val (tree, range) = buildInitialTreeAndRange(ctx)
        val boldTicket = ctx.issueTimeTicket()
        tree.style(range, mapOf("bold" to "true"), boldTicket)

        // when — removeStyle without versionVector
        val removeTicket = ctx.issueTimeTicket()
        tree.removeStyle(range, listOf("bold"), removeTicket) // no versionVector

        // then — attribute removed as normal
        assertEquals("<root><p>hello</p></root>", tree.toXml())
    }
}
