package dev.yorkie.api

import dev.yorkie.api.v1.treeNodes
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.Rht
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.YorkieException
import dev.yorkie.util.YorkieException.Code.ErrInvalidArgument
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports `tree_edit_content_sanitize_test.ts` (JS SDK `d3ffe946`, #1406) as JVM
 * unit tests (spec 035 AC8, Commit 3 unit 3C).
 *
 * A TreeEdit's content is always freshly created by the editing client, so it
 * can never legitimately be a split product, carry a merge lineage, or arrive
 * tombstoned -- yet the wire format carries every one of those fields on each
 * tree node. [dev.yorkie.api.toCrdtTreeNodesWhenEdit] drops them on the way
 * in, and rejects an empty content group outright. These mirror yorkie's
 * `tree_content_tombstone_test.go` and `tree_edit_content_missing_test.go`
 * (yorkie#2033).
 */
class TreeEditContentSanitizeTest {

    private val actor = "000000000000000000000001"

    private fun ticket(lamport: Long) = TimeTicket(lamport, 0u, actor)

    /**
     * Encodes [content] as a single TreeEdit content entry and decodes it
     * back, mirroring the converter-level round trip
     * [dev.yorkie.api.SplitLinkDecodeTest] already established for this
     * decode path.
     */
    private fun roundTrip(content: CrdtTreeNode): CrdtTreeNode {
        val decoded = listOf(content).toPBTreeNodesWhenEdit().toCrdtTreeNodesWhenEdit()
        return requireNotNull(decoded).single()
    }

    /** Builds `<p>hello</p>` as fresh, unattached content. */
    private fun buildContent(): Pair<CrdtTreeNode, CrdtTreeNode> {
        val text = CrdtTreeText(CrdtTreeNodeID(ticket(3), 0), "hello")
        val paragraph = CrdtTreeElement(CrdtTreeNodeID(ticket(2), 0), "p", listOf(text))
        return paragraph to text
    }

    @Test
    fun `should drop a tombstone the content carries`() {
        // A node born tombstoned under a live parent would be counted into
        // the live data size with no GC pair ever registered for it.
        val (paragraph, text) = buildContent()
        paragraph.remove(ticket(4))
        text.remove(ticket(4))

        val content = roundTrip(paragraph)

        assertFalse(content.isRemoved, "content arrived tombstoned")
        assertEquals(1, content.allChildren.size)
        assertFalse(
            content.allChildren.single().isRemoved,
            "content descendant arrived tombstoned",
        )
        // The revived text has to be back in its parent's visible size: the
        // decode excludes removed children from it, so a tombstone cleared
        // without that bookkeeping would size the content as if empty.
        assertEquals("hello".length + 2, content.paddedSize())
    }

    /**
     * Stamps a lineage the decoder really does derive a `mergedInto` from:
     * [text] names its own parent as the parent a merge moved it out of, and
     * that parent is a tombstone, which is the only shape `rebuildMergeState`
     * plants a forwarding pointer for.
     */
    private fun craftMergeLineage(paragraph: CrdtTreeNode, text: CrdtTreeNode) {
        paragraph.mergedFrom = CrdtTreeNodeID(ticket(5), 0)
        paragraph.mergedAt = ticket(7)
        paragraph.remove(ticket(6))
        text.mergedFrom = paragraph.id
        text.mergedAt = ticket(7)
    }

    @Test
    fun `should drop a merge lineage the content carries`() {
        // Only a merge may stamp mergedFrom/mergedAt; the §1.1 redirect and
        // the §6.2 delete propagation read them as trusted structural
        // pointers.
        val (paragraph, text) = buildContent()
        craftMergeLineage(paragraph, text)

        // The same field shape read by the same rebuild logic a real
        // CrdtTree runs on construction, without the sanitizer in between:
        // without this guard the mergedInto assertion below would hold for a
        // fixture the decoder never derives one from, and would keep holding
        // if the drop were deleted.
        val rawText = CrdtTreeText(CrdtTreeNodeID(ticket(3), 0), "hello")
        val rawParagraph = CrdtTreeElement(CrdtTreeNodeID(ticket(2), 0), "p")
        // prepend, as the shared decoder links its nodes: the factory's
        // child list leaves `parent` unset, and `rebuildMergeState` reads it.
        rawParagraph.prepend(rawText)
        craftMergeLineage(rawParagraph, rawText)
        CrdtTree(rawParagraph, ticket(8))
        assertEquals(
            rawParagraph.id,
            rawParagraph.mergedInto,
            "fixture does not make rebuildMergeState derive a mergedInto",
        )

        val content = roundTrip(paragraph)
        val decodedText = content.allChildren.single()

        assertNull(content.mergedFrom)
        assertNull(content.mergedAt)
        assertNull(decodedText.mergedFrom)
        assertNull(decodedText.mergedAt)
        // The decoder really does derive mergedInto from mergedFrom while it
        // builds this content -- the guard above proves it -- so it has to
        // go too: a source must not keep pointing at a destination no field
        // records any more. Two halves of the sanitizer erase it, the
        // lineage drop and `unremove` (a derived pointer only ever sits on a
        // tombstone), and the assertion is on the end state rather than on
        // either one.
        assertNull(content.mergedInto)
    }

    /** Builds `<p live="yes">hello</p>` whose attribute table also holds one removed entry. */
    private fun buildStyledContent(): CrdtTreeNode {
        val (_, text) = buildContent()
        val attrs = Rht()
        attrs.set("live", "yes", ticket(2))
        attrs.set("removed", "x".repeat(64), ticket(2))
        attrs.remove("removed", ticket(3))
        val paragraph = CrdtTreeElement(CrdtTreeNodeID(ticket(2), 0), "p", listOf(text), attrs)
        assertTrue(paragraph.getAttrs().getNodeMapByKey().getValue("removed").isRemoved)
        return paragraph
    }

    @Test
    fun `should keep an attribute tombstone the content carries`() {
        // Unlike the node tombstone above, a removed RHT entry on content is
        // NOT forgeable-only: the undo copy-reinsert path re-sends a deep
        // copy of nodes a real removeStyle tombstoned, and the reinserted
        // node has to keep rejecting the stale styles the original rejects.
        // Dropping it here would also make this decoder disagree with every
        // other producer and decoder of the same bytes -- an older SDK, the
        // JS SDK, the Go SDK, the snapshot and Set/Add paths -- which is
        // divergence, not hardening.
        val content = roundTrip(buildStyledContent())

        assertEquals("yes", content.attributes["live"])
        val removed = content.getAttrs().getNodeMapByKey()["removed"]
        assertNotNull(removed, "content lost an attribute tombstone")
        assertTrue(removed.isRemoved)
    }

    @Test
    fun `should book an attribute tombstone the content carries into gc`() {
        // What makes the kept entry harmless is that edit() registers it,
        // the way the snapshot and Set/Add payload paths register theirs.
        // Without a pair it is storage getDataSize charges to no one and
        // nothing ever purges: growth the document size cannot see.
        val content = roundTrip(buildStyledContent())
        val root = CrdtTreeElement(CrdtTreeNodeID(ticket(1), 0), "r")
        val tree = CrdtTree(root, ticket(1))
        var lamport = 20L
        val result = tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(content),
            0,
            ticket(10),
            issueTimeTicket = { ticket(lamport++) },
        )

        val attrPairs = result.gcPairs.filterNot { it.parent === tree }
        assertEquals(1, attrPairs.size, "no GC pair for the attribute tombstone")
        val pair = attrPairs.single()
        assertTrue(pair.child == content.getAttrs().getNodeMapByKey()["removed"])
        // Removed entries are skipped by dataSize, so these bytes never
        // entered live: the pair has to carry its own size to gc rather than
        // move it out of live, which would drive live down by bytes it never
        // held.
        assertNotNull(pair.gcOnlySize)
    }

    @Test
    fun `should drop split links the content carries`() {
        val (paragraph, text) = buildContent()
        text.insPrevID = CrdtTreeNodeID(ticket(3), 9)
        text.insNextID = CrdtTreeNodeID(ticket(3), 9)

        val decodedText = roundTrip(paragraph).allChildren.single()

        assertNull(decodedText.insPrevID)
        assertNull(decodedText.insNextID)
    }

    @Test
    fun `should reject an empty content group`() {
        // An empty group decodes to no root; carried into the operation it
        // is a crash on apply, where edit() deep-copies each content entry.
        val exception = assertFailsWith<YorkieException> {
            listOf(treeNodes { }).toCrdtTreeNodesWhenEdit()
        }
        assertEquals(ErrInvalidArgument, exception.code)
        assertEquals("tree edit content has an entry with no node", exception.errorMessage)
    }
}
