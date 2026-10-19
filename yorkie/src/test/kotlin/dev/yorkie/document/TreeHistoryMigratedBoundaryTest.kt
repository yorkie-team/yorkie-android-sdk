package dev.yorkie.document

import dev.yorkie.document.json.JsonObject
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * M1 (critic/js-researcher, RTCOLLABPLATFORM-789 team-review): pins that
 * [Document.reconcileHistoryEdits]'s [dev.yorkie.document.operation.TreeEditOperation]
 * branch reconciles a pending LOCAL tree-undo entry from the edit's pre-edit index +
 * removed size ([dev.yorkie.document.operation.TreeEditOperation.normalizePos], a CRDT
 * value captured BEFORE the edit mutated the tree -- JS `tree_edit_operation.ts`'s
 * `preEditFromIdx`/`lastFromIdx`/`lastToIdx`), never the position the published
 * [dev.yorkie.document.operation.OperationInfo.TreeEditOpInfo] reports the edit landed
 * at: a remote split that migrates its boundary past a concurrent insert (section 7.3,
 * ported in [TreeSplitOpInfoTest]) reports a DIFFERENT position than the one it
 * actually consumed.
 *
 * Reproduces [TreeSplitOpInfoTest]'s "paragraph split with a concurrent element insert
 * at the boundary" case inside the FIRST of two paragraphs, while `a` separately keeps
 * a pending, offset-based split-undo entry from an UNRELATED pure split in the SECOND
 * paragraph -- unrelated so the exchange itself converges exactly as that already-pinned
 * case does; only its *reconciliation* is under test here. Both entries share the same
 * tree's `parentCreatedAt`, so `Document.reconcileHistoryEdits` reconciles the SECOND
 * paragraph's pending offset against the incoming migrated split too.
 *
 * Confirmed via a throwaway debug probe before writing this test: on `a`, applying `b`'s
 * split reports `TreeEditOpInfo.from/to = (10, 10)` (after the concurrently inserted
 * `<t>XY</t>`), while `TreeEditOperation.normalizePos()`'s pre-edit index is `(6, 6)`
 * (right after `<t>abc</t>`, before any of that migration). Reconciling the pending
 * second-paragraph split from the reported (10, 10) leaves its own in-range offset
 * UNSHIFTED (it sits to the position's left); reconciling from the pre-edit (6, 6) shifts
 * it forward by the remote split's measured content size, matching what the tree's
 * actual (bigger, post-split) index space requires. Undoing it afterward is the
 * observable check: correctly reconciled, it merges "mn"+"op" back into "mnop"; wrongly
 * reconciled (unshifted), it targets a stale pre-growth range in the new, larger tree.
 */
class TreeHistoryMigratedBoundaryTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private fun JsonObject.tree(key: String = "t") = getAs<JsonTree>(key)
    private suspend fun Document.tree(key: String = "t") = getRoot().getAs<JsonTree>(key)
    private suspend fun Document.xml(key: String = "t") = tree(key).toXml()

    @Test
    fun `undoing a pending split past a migrated remote boundary restores the right text`() =
        runTest {
            val a = Document("tree-history-migrated-boundary").apply { setActor(actor1) }
            val b = Document("tree-history-migrated-boundary").apply { setActor(actor2) }
            a.updateAsync { root, _ ->
                root.setNewTree(
                    "t",
                    element("doc") {
                        element("p") {
                            element("t") { text { "abc" } }
                        }
                        element("p") {
                            element("t") { text { "mnop" } }
                        }
                    },
                )
            }.await()
            crossSync(a, b, overWire = true)
            assertEquals("<doc><p><t>abc</t></p><p><t>mnop</t></p></doc>", a.xml())

            // a inserts a new <t>XY</t> sibling in the FIRST paragraph, at the SAME
            // boundary b is about to (concurrently, unseen by b) split that paragraph
            // at. Pushes a content-based undo entry (not offset-based).
            a.updateAsync { root, _ ->
                root.tree().editByPath(listOf(0, 1), listOf(0, 1), element("t") { text { "XY" } })
            }.await()
            assertEquals(
                "<doc><p><t>abc</t><t>XY</t></p><p><t>mnop</t></p></doc>",
                a.xml(),
            )

            // a ALSO has its own, UNRELATED pending split-undo entry in the SECOND
            // paragraph: split "mnop" into "mn"|"op" -- a PURE split (no content), so
            // its reverse is the offset-based toSplitReverseOperation
            // Document.reconcileHistoryEdits has to shift when a later edit touches
            // this same tree. Pushed LAST, so it is the one history.undoAsync() below
            // actually pops.
            a.updateAsync { root, _ ->
                root.tree().editByPath(listOf(1, 0, 2), listOf(1, 0, 2), splitLevel = 1)
            }.await()
            assertEquals(
                "<doc><p><t>abc</t><t>XY</t></p><p><t>mn</t><t>op</t></p></doc>",
                a.xml(),
            )
            assertEquals(true, a.history.canUndo())

            // b, unaware of a's insert, splits the FIRST paragraph at the ORIGINAL
            // boundary (right after <t>abc</t>). Section 7.3: when this reaches a, the
            // split migrates past a's concurrently inserted content and keeps it on the
            // LEFT paragraph -- its OWN OperationInfo reports a position AFTER <t>XY</t>,
            // but the pre-edit index CrdtTree.edit resolved before any of that migration
            // is right after <t>abc</t>, the value Document.reconcileHistoryEdits must
            // reconcile the SECOND paragraph's unrelated pending split from.
            b.updateAsync { root, _ ->
                root.tree().splitByPath(listOf(0, 1))
            }.await()

            crossSync(a, b, overWire = true)

            assertEquals(a.xml(), b.xml(), "replicas diverged")
            assertEquals(
                "<doc><p><t>abc</t><t>XY</t></p><p></p><p><t>mn</t><t>op</t></p></doc>",
                a.xml(),
                "control: b's split must land after a's concurrently inserted content, " +
                    "and the unrelated second-paragraph split must be untouched by it",
            )

            // Undo a's pending split in what is now the THIRD paragraph ("mn"|"op" ->
            // "mnop"). A correctly reconciled entry merges "mn" and "op" back in place;
            // a mis-reconciled one (anchored at its pre-remote-split, now-stale offset)
            // targets the wrong range in the bigger tree -- either throwing, or merging
            // across a boundary the remote split opened.
            a.history.undoAsync().await()
            crossSync(a, b, overWire = true)

            assertEquals(
                "<doc><p><t>abc</t><t>XY</t></p><p></p><p><t>mnop</t></p></doc>",
                a.xml(),
                "the undo must merge mn+op back into mnop without disturbing the " +
                    "remote split that grew the tree to its left",
            )
            assertEquals(a.xml(), b.xml(), "replicas diverged after undo")
        }

    // Review on RTCOLLABPLATFORM-789 (High): `removedNodesSize` was measured before the
    // merge moved the boundary paragraph's children out of it, so a Backspace at the start
    // of a paragraph reported the merged paragraph's whole former content as removed --
    // `<p>ab</p><p>cd</p>`, edit(3, 5) gave (3, 7) where JS gives (3, 5). Pins the JS value
    // through `normalizePos()` on the local change, for a merge whose source still holds
    // its children at measurement time.
    @Test
    fun `a merge reports the range it consumed, not the merged paragraph's content`() = runTest {
        val a = Document("tree-merge-consumed-range").apply { setActor(actor1) }
        a.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        // Backspace at the start of the second paragraph: the range covers `</p><p>`.
        a.updateAsync { root, _ -> root.tree().edit(3, 5) }.await()
        assertEquals("<doc><p>abcd</p></doc>", a.xml())

        val merge = a.pendingChanges().last().operations.single() as TreeEditOperation
        assertEquals(3 to 5, merge.normalizePos(), "JS lastFromIdx/lastToIdx")
    }

    // The same measurement, observed through the undo stack it feeds: `a` holds an
    // offset-based pending undo (a pure split of the THIRD paragraph) while `b`
    // concurrently merges the second paragraph into the first. Reconciled from the correct
    // (3, 5) the pending offset shifts left by the two tags the merge removed; from the
    // overcounted (3, 7) it shifts by two more, and the undo then merges the wrong pieces.
    @Test
    fun `an undo past a remote merge is reconciled by the range the merge consumed`() = runTest {
        val a = Document("tree-merge-undo-reconcile").apply { setActor(actor1) }
        val b = Document("tree-merge-undo-reconcile").apply { setActor(actor2) }
        a.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("doc") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                    element("p") {
                        element("t") { text { "mnop" } }
                    }
                },
            )
        }.await()
        crossSync(a, b, overWire = true)

        // a: pure split of the third paragraph's <t> ("mn"|"op"), the offset-based
        // pending undo entry Document.reconcileHistoryEdits shifts on a later remote
        // edit (same shape as the first case above).
        a.updateAsync { root, _ ->
            root.tree().editByPath(listOf(2, 0, 2), listOf(2, 0, 2), splitLevel = 1)
        }.await()
        assertEquals(
            "<doc><p>ab</p><p>cd</p><p><t>mn</t><t>op</t></p></doc>",
            a.xml(),
        )

        // b, concurrently: Backspace at the start of the second paragraph.
        b.updateAsync { root, _ -> root.tree().edit(3, 5) }.await()
        crossSync(a, b, overWire = true)
        assertEquals(a.xml(), b.xml(), "replicas diverged")
        assertEquals("<doc><p>abcd</p><p><t>mn</t><t>op</t></p></doc>", a.xml())

        a.history.undoAsync().await()
        crossSync(a, b, overWire = true)
        assertEquals(
            "<doc><p>abcd</p><p><t>mnop</t></p></doc>",
            a.xml(),
            "the undo must merge mn+op back, two positions left of where it was pushed",
        )
        assertEquals(a.xml(), b.xml(), "replicas diverged after undo")
    }
}
