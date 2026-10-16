package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.TreeRestoreSpan
import dev.yorkie.document.crdt.recreateParentForTest
import dev.yorkie.issueTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Ports `tree_restore_text_parent_test.ts` (JS SDK `5c8dd738`, #1475):
 * [CrdtTree.recreateParent] leaves a span unplaced for a missing parent
 * (pre-existing), a TEXT parent, or an identity a surviving node already
 * holds (exact offset, or a text run of the same insertion covering it).
 * Go's `recreateParent` applies the same three rules (yorkie#2168 /
 * #2175); Android must agree, or a stored history replays differently per
 * replica. None of these throws -- before this port, a text-parent span
 * reached [CrdtTreeNode.append]'s `check(!isText)` and crashed every
 * replica that applied the stored change.
 */
class TreeRestoreTextParentTest {

    @Test
    fun `a span naming a text node as parent is left unplaced`() {
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        val textAt = issueTime()
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeText(CrdtTreeNodeID(textAt, 0), "x")),
            0,
            issueTime(),
        )
        assertEquals("<root>x</root>", tree.toXml())
        val textID = CrdtTreeNodeID(textAt, 0)

        // A span describing an element whose parent is the live TEXT node --
        // never captured by a real edit(), only reachable via a forged/
        // corrupted stored span.
        val forged = TreeRestoreSpan(
            id = CrdtTreeNodeID(issueTime(), 0),
            nodeType = "p",
            isText = false,
            length = 0,
            parentID = textID,
        )

        val result = tree.restore(listOf(forged), issueTime())

        assertTrue(result.recreated.isEmpty(), "the node stays unplaced, not attached to text")
        assertEquals("<root>x</root>", tree.toXml(), "the tree is unchanged")
    }

    // The next two cases drive [CrdtTree.recreateParentForTest] directly --
    // [CrdtTree.restore]'s own text/element dispatch (`findPiecesOverlapping`
    // for a text span, the `node != null` shortcut for an element one)
    // already short-circuits before `recreateFromSpan` for a span that
    // merely targets a LIVE node's own already-indexed identity, so driving
    // this fixture through `restore()` end-to-end would pass even without
    // [recreateParent]'s own held-identity guard and not actually pin it.

    @Test
    fun `a recreate at an identity a surviving node already holds exactly is skipped`() {
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        val pID = CrdtTreeNodeID(issueTime(), 0)
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeElement(pID, "p")),
            0,
            issueTime(),
        )
        val textAt = issueTime()
        val textID = CrdtTreeNodeID(textAt, 0)
        tree.edit(
            tree.findPos(1) to tree.findPos(1),
            listOf(CrdtTreeText(textID, "ab")),
            0,
            issueTime(),
        )
        assertEquals("<root><p>ab</p></root>", tree.toXml())

        // A span claiming to recreate a DIFFERENT node at the exact same
        // identity (textAt, offset 0) that "ab" already live-holds.
        val forged = TreeRestoreSpan(
            id = textID,
            nodeType = "text",
            isText = true,
            length = 2,
            value = "ab",
            parentID = pID,
        )

        assertNull(tree.recreateParentForTest(forged, textID))
    }

    @Test
    fun `a recreate inside a surviving same-insertion text run is skipped`() {
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        val pID = CrdtTreeNodeID(issueTime(), 0)
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeElement(pID, "p")),
            0,
            issueTime(),
        )
        val textAt = issueTime()
        tree.edit(
            tree.findPos(1) to tree.findPos(1),
            listOf(CrdtTreeText(CrdtTreeNodeID(textAt, 0), "xyz")),
            0,
            issueTime(),
        )
        assertEquals("<root><p>xyz</p></root>", tree.toXml())

        // A span claiming to recreate offset 1 of the SAME insertion
        // ("xyz", createdAt=textAt) -- entirely covered by the surviving
        // live run [0, 3).
        val forged = TreeRestoreSpan(
            id = CrdtTreeNodeID(textAt, 1),
            nodeType = "text",
            isText = true,
            length = 1,
            value = "y",
            parentID = pID,
        )

        assertNull(tree.recreateParentForTest(forged, CrdtTreeNodeID(textAt, 1)))
    }

    @Test
    fun `recreateParent returns the parent when no rule blocks it`() {
        // Positive control for both guards above: a genuinely free identity
        // under a genuinely live element parent resolves normally.
        val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
        val tree = CrdtTree(root, issueTime())
        val pID = CrdtTreeNodeID(issueTime(), 0)
        tree.edit(
            tree.findPos(0) to tree.findPos(0),
            listOf(CrdtTreeElement(pID, "p")),
            0,
            issueTime(),
        )
        val freeAt = issueTime()
        val forged = TreeRestoreSpan(
            id = CrdtTreeNodeID(freeAt, 0),
            nodeType = "text",
            isText = true,
            length = 1,
            value = "z",
            parentID = pID,
        )

        assertNotNull(tree.recreateParentForTest(forged, CrdtTreeNodeID(freeAt, 0)))
    }

    /**
     * P5 asymmetric-purge convergence probe (constitution C9 / memory
     * "parity-port review blind spot": parity QA alone cannot catch a bug
     * shared with JS). Not in the JS suite. Two replicas apply the SAME
     * forged, held-identity-colliding restore span -- one purges the
     * holder's unrelated earlier tombstone before restoring, the other
     * after -- and must converge on leaving the forged span unplaced
     * regardless of when GC ran.
     */
    @Test
    fun `peers that purge an unrelated tombstone at different times still agree on a skip`() {
        /**
         * Builds `<root><p>z</p></root>`, deletes "z" (an unrelated
         * tombstone, orthogonal to the held-identity collision this probe
         * checks), optionally purges it right away, then creates the real
         * held-identity collision: "ab" lives where a forged span claims to
         * recreate.
         */
        fun buildFixture(
            purgeUnrelatedNow: Boolean,
        ): Triple<CrdtTree, CrdtTreeNodeID, TreeRestoreSpan> {
            val root = CrdtTreeElement(CrdtTreeNodeID(issueTime(), 0), "root")
            val tree = CrdtTree(root, issueTime())
            val pID = CrdtTreeNodeID(issueTime(), 0)
            tree.edit(
                tree.findPos(0) to tree.findPos(0),
                listOf(CrdtTreeElement(pID, "p")),
                0,
                issueTime(),
            )
            val unrelatedAt = issueTime()
            tree.edit(
                tree.findPos(1) to tree.findPos(1),
                listOf(CrdtTreeText(CrdtTreeNodeID(unrelatedAt, 0), "z")),
                0,
                issueTime(),
            )
            val deleteResult = tree.edit(tree.findPos(1) to tree.findPos(2), null, 0, issueTime())
            if (purgeUnrelatedNow) {
                deleteResult.gcPairs.forEach { it.parent.deleteChild(it.child) }
            }

            val textAt = issueTime()
            val textID = CrdtTreeNodeID(textAt, 0)
            tree.edit(
                tree.findPos(1) to tree.findPos(1),
                listOf(CrdtTreeText(textID, "ab")),
                0,
                issueTime(),
            )
            if (!purgeUnrelatedNow) {
                deleteResult.gcPairs.forEach { it.parent.deleteChild(it.child) }
            }
            val forged = TreeRestoreSpan(
                id = textID,
                nodeType = "text",
                isText = true,
                length = 2,
                value = "ab",
                parentID = pID,
            )
            return Triple(tree, textID, forged)
        }

        // Replica A purges the unrelated tombstone BEFORE the held-identity
        // text exists; replica B purges it AFTER -- the held-identity skip
        // below must agree regardless.
        val (treeA, textIDA, forgedA) = buildFixture(purgeUnrelatedNow = true)
        val (treeB, textIDB, forgedB) = buildFixture(purgeUnrelatedNow = false)

        assertNull(treeA.recreateParentForTest(forgedA, textIDA))
        assertNull(treeB.recreateParentForTest(forgedB, textIDB))
        assertEquals(treeA.toXml(), treeB.toXml())
        assertEquals("<root><p>ab</p></root>", treeA.toXml())
    }
}
