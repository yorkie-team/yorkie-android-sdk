package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.document.time.TimeTicket
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Android-authored C9 twin for [CrdtTree]'s `dissolveMerge` (JS has no
 * equivalent test, D18): a genuine local merge's own undo never reaches
 * [CrdtTree.restore] -- `TreeEditOperation`'s reverse-operation builder
 * always takes the split-reverse path when `mergeLevel > 0` ([CrdtTree.edit]'s
 * `spansComplete` guard forces `removedSpans` empty whenever a merge
 * happened), so the merge source stays tombstoned forever and a fresh
 * split-created element takes its place. This is exactly why upstream has no
 * test either: `dissolveMerge` exists for [CrdtTree.restore]'s
 * identity-preserving contract in general, not for any call site reachable
 * today. This test drives [CrdtTree.restore] directly -- the same way a
 * future identity-preserving merge-undo, or a decoded `RestoreMode.Restore`
 * operation, would -- to pin that it still dissolves the lineage correctly.
 * Spec 035 D18, Commit 3 unit 3B (port JS SDK `tree.ts` `dissolveMerge`
 * d3ffe946, #1406; yorkie#2033).
 */
class TreeRestoreDissolveMergeTest {

    private val actor = "000000000000000000000001"

    /** Hands out strictly increasing tickets for [actor]. */
    private fun ticketer(): () -> TimeTicket {
        var lamport = 0L
        return {
            lamport += 1
            TimeTicket(lamport, 0u, actor)
        }
    }

    @Test
    fun `restore dissolves a merge's lineage and a snapshot rebuild agrees`() {
        val issue = ticketer()
        fun element(type: String) = CrdtTreeElement(CrdtTreeNodeID(issue(), 0), type)
        fun text(value: String) = CrdtTreeText(CrdtTreeNodeID(issue(), 0), value)

        // <r><p>ab</p><p>cd</p></r>.
        val tree = CrdtTree(element("r"), issue())
        val p1 = element("p")
        tree.edit(tree.findPos(0) to tree.findPos(0), listOf(p1), 0, issue(), issue)
        tree.edit(tree.findPos(1) to tree.findPos(1), listOf(text("ab")), 0, issue(), issue)
        val p2 = element("p")
        tree.edit(tree.findPos(4) to tree.findPos(4), listOf(p2), 0, issue(), issue)
        val cdText = text("cd")
        tree.edit(tree.findPos(5) to tree.findPos(5), listOf(cdText), 0, issue(), issue)
        assertEquals("<r><p>ab</p><p>cd</p></r>", tree.toXml())

        // Merge p2 into p1: delete exactly the boundary tokens (p1's closing
        // token and p2's opening token), content-less -- <r><p>abcd</p></r>.
        tree.edit(tree.findPos(3) to tree.findPos(5), null, 0, issue())
        assertEquals("<r><p>abcd</p></r>", tree.toXml())
        assertEquals(p2.id, cdText.mergedFrom)
        assertTrue(p2.isRemoved)
        assertEquals(p1.id, p2.mergedInto)

        // Drive restore() directly, as a future identity-preserving
        // merge-undo (or a decoded RestoreMode.Restore op) would.
        val (untombstoned, _, _, _) = tree.restore(
            listOf(TreeRestoreSpan(id = p2.id, nodeType = p2.type, isText = false, length = 0)),
        )
        assertEquals(listOf(p2), untombstoned)
        // p2 revives as the empty shell it was left as -- the merge moved
        // only its CHILD (cdText) into p1, never p2 itself, so reviving
        // p2's identity does not relocate cdText back: dissolveMerge erases
        // the bookkeeping the merge left, not the physical move.
        assertEquals("<r><p>abcd</p><p></p></r>", tree.toXml())

        // dissolveMerge erased the lineage, not just the tombstone: the
        // source carries no forwarding pointer, and the child it moved
        // carries no stamp pointing back at it either.
        assertTrue(!p2.isRemoved)
        assertNull(p2.mergedInto)
        assertNull(cdText.mergedFrom)
        assertNull(cdText.mergedAt)

        // Re-wrapping the same node graph (the decoded-snapshot path) runs
        // rebuildMergeState again over these very objects: with cdText's
        // mergedFrom already cleared, it has nothing left to derive
        // p2.mergedInto from either.
        CrdtTree(tree.root, issue())
        assertNull(p2.mergedInto)
    }
}
