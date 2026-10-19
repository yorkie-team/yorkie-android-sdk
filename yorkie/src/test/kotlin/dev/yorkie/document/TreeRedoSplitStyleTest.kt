package dev.yorkie.document

import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports JS SDK `tree_redo_split_style_test.ts` (`b173c093`, #1426): redoing a
 * split that re-creates an element a merge took away must re-point every
 * operation still naming the merged-away element at its replacement, on the
 * replica that ran the split AND on a peer applying it from the wire.
 * Exercises the three re-pointing sites wired into [Document] (local
 * `updateAsync`, [Document.reconcileHistoryEdits], `executeUndoRedoInternal`)
 * end to end -- the CRDT-level `mergeSourceOf`/`replaceTreeNodeId` rule and
 * the per-operation state that feeds them are covered directly by
 * [dev.yorkie.document.crdt.CrdtTreeMergeSourceOfTest] and
 * [dev.yorkie.document.history.HistoryReconcileTreeNodeIdTest].
 *
 * `History.reconcileTreeNodeID re-points only the entries targeting the same
 * tree` -- the 15th JS case in the same upstream file -- is ported as
 * [dev.yorkie.document.history.HistoryReconcileTreeNodeIdTest], not repeated
 * here.
 */
class TreeRedoSplitStyleTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    /** `hello world` in an inline in a paragraph -- two levels to split. */
    private suspend fun seed(document: Document) {
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("paragraph") {
                        element("inline") {
                            text { "hello world" }
                        }
                    }
                },
            )
        }.await()
    }

    /** `<root><p>ab</p><p>cd</p></root>` -- two blocks to merge and split again. */
    private suspend fun seedBlocks(document: Document) {
        document.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("root") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
    }

    private suspend fun Document.tree(): JsonTree = getRoot().getAs("t")

    /**
     * Purges the tombstones both replicas have seen. [assertPurged] (default true) checks
     * the premise every "right before the redo this purge is supposed to matter for" call
     * site relies on: the undo actually left something to purge (the merge-away the next
     * redo is about to run through), not a no-op pass that would make the "after GC"
     * shapes indistinguishable from their `collect = false` twins. False at a call site
     * where nothing is guaranteed left to purge (e.g. a second pass right after the first
     * redo already collected it) -- an unconditional assertion there would be checking the
     * wrong premise, not strengthening this test.
     */
    private fun collectBoth(
        d1: Document,
        d2: Document,
        assertPurged: Boolean = true,
    ) {
        val vector = maxVectorOf(listOf(actor1, actor2))
        val d1Purged = d1.garbageCollect(vector)
        val d2Purged = d2.garbageCollect(vector)
        if (assertPurged) {
            assertTrue(d1Purged > 0, "d1 must have something to purge here")
            assertTrue(d2Purged > 0, "d2 must have something to purge here")
        }
    }

    /**
     * Runs one of the five split-and-style shapes through a full edit / undo
     * / redo / second-round-trip cycle, syncing to a peer after every step
     * (`overWire = true`, the only way a unit test exercises the restore-span
     * encode/decode path under convergence) and asserting the peer converges
     * at each one. [collect] purges tombstones right after the undo, mirroring
     * the GC variant of every JS case.
     */
    private suspend fun runShape(collect: Boolean, edit: (JsonTree) -> Unit) {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        seed(d1)
        d1.clearHistory()
        crossSync(d1, d2, overWire = true)

        d1.updateAsync { root, _ -> edit(root.getAs<JsonTree>("t")) }.await()
        val edited = d1.tree().toXml()
        crossSync(d1, d2, overWire = true)
        assertEquals(edited, d2.tree().toXml(), "edit")

        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)
        val undone = d1.tree().toXml()
        assertEquals(undone, d2.tree().toXml(), "undo")
        // Both have seen the undo, so its tombstones can be purged.
        if (collect) collectBoth(d1, d2)

        d1.history.redoAsync().await()
        assertEquals(edited, d1.tree().toXml(), "redo, locally")
        crossSync(d1, d2, overWire = true)
        assertEquals(edited, d2.tree().toXml(), "redo, on the peer")

        // A second round trip: the redo re-minted the split elements, so the
        // entry it pushed onto the undo stack has to name the new ones.
        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)
        assertEquals(undone, d1.tree().toXml(), "second undo, locally")
        assertEquals(undone, d2.tree().toXml(), "second undo, on the peer")
        d1.history.redoAsync().await()
        crossSync(d1, d2, overWire = true)
        assertEquals(edited, d1.tree().toXml(), "second redo, locally")
        assertEquals(edited, d2.tree().toXml(), "second redo, on the peer")
    }

    // -- 5 shapes, each without and with a GC pass between undo and redo ----

    @Test
    fun `peer applies redo of a mid split with the right piece bold`() = runTest {
        runShape(collect = false) { t ->
            t.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 1)
            t.styleByPath(listOf(0, 1), mapOf("bold" to "true"))
        }
    }

    @Test
    fun `peer applies redo of a mid split with the right piece bold after GC`() = runTest {
        runShape(collect = true) { t ->
            t.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 1)
            t.styleByPath(listOf(0, 1), mapOf("bold" to "true"))
        }
    }

    @Test
    fun `peer applies redo of an end split with the empty piece bold`() = runTest {
        runShape(collect = false) { t ->
            t.editByPath(listOf(0, 0, 11), listOf(0, 0, 11), splitLevel = 1)
            t.styleByPath(listOf(0, 1), mapOf("bold" to "true"))
        }
    }

    @Test
    fun `peer applies redo of an end split with the empty piece bold after GC`() = runTest {
        runShape(collect = true) { t ->
            t.editByPath(listOf(0, 0, 11), listOf(0, 0, 11), splitLevel = 1)
            t.styleByPath(listOf(0, 1), mapOf("bold" to "true"))
        }
    }

    @Test
    fun `peer applies redo of a two level split with the new paragraph bold`() = runTest {
        runShape(collect = false) { t ->
            t.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 2)
            t.styleByPath(listOf(1), mapOf("bold" to "true"))
        }
    }

    @Test
    fun `peer applies redo of a two level split with the new paragraph bold after GC`() = runTest {
        runShape(collect = true) { t ->
            t.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 2)
            t.styleByPath(listOf(1), mapOf("bold" to "true"))
        }
    }

    // The style names the inline the FIRST (innermost) split ticket minted,
    // nested under the paragraph the SECOND ticket minted -- only resolves if
    // the replacedIDs <-> ticket-index pairing is innermost-first (mergedAwayIDs).
    @Test
    fun `peer applies redo of a two level split with the new inline bold`() = runTest {
        runShape(collect = false) { t ->
            t.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 2)
            t.styleByPath(listOf(1, 0), mapOf("bold" to "true"))
        }
    }

    @Test
    fun `peer applies redo of a two level split with the new inline bold after GC`() = runTest {
        runShape(collect = true) { t ->
            t.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 2)
            t.styleByPath(listOf(1, 0), mapOf("bold" to "true"))
        }
    }

    // A tree EDIT (not a style) follows the split: its reverse travels as an
    // identity-preserving restore span naming the split-created element as
    // its parent, which also has to be re-pointed.
    @Test
    fun `peer applies redo of a split then an insert into the new element`() = runTest {
        runShape(collect = false) { t ->
            t.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 1)
            t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), text { "X" })
        }
    }

    @Test
    fun `peer applies redo of a split then an insert into the new element after GC`() = runTest {
        runShape(collect = true) { t ->
            t.editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 1)
            t.editByPath(listOf(0, 1, 0), listOf(0, 1, 0), text { "X" })
        }
    }

    // -- the split and the operation naming its elements are in DIFFERENT
    // history entries: only History.reconcileTreeNodeID, sweeping the
    // stacks, re-points the second one. ------------------------------------

    private suspend fun runCrossEntry(collect: Boolean) {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        seed(d1)
        d1.clearHistory()
        crossSync(d1, d2, overWire = true)

        // Entry 1: the split. Entry 2: a style naming what it created.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 0, 6), listOf(0, 0, 6), splitLevel = 1)
        }.await()
        val split = d1.tree().toXml()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").styleByPath(listOf(0, 1), mapOf("bold" to "true"))
        }.await()
        val styled = d1.tree().toXml()
        crossSync(d1, d2, overWire = true)
        assertEquals(styled, d2.tree().toXml(), "edits")

        // Undo the style, then the split. The redo stack now holds a
        // re-split entry and, above it, a style entry naming the pre-split
        // element.
        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)
        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)
        val merged = d1.tree().toXml()
        assertEquals(merged, d2.tree().toXml(), "both undone")
        if (collect) collectBoth(d1, d2)

        // Redoing the split mints new elements; the style entry still on
        // the stack has to follow them, or the peer cannot apply it.
        d1.history.redoAsync().await()
        crossSync(d1, d2, overWire = true)
        assertEquals(split, d1.tree().toXml(), "redo split, locally")
        assertEquals(split, d2.tree().toXml(), "redo split, on the peer")
        // Not "before the redo" this purge matters for (the style redo right below names
        // no tombstone) -- the first collectBoth above already purged what the split redo
        // just re-created, so this pass is a no-op by design, not a premise to check.
        if (collect) collectBoth(d1, d2, assertPurged = false)

        d1.history.redoAsync().await()
        assertEquals(styled, d1.tree().toXml(), "redo style, locally")
        crossSync(d1, d2, overWire = true)
        assertEquals(styled, d2.tree().toXml(), "redo style, on the peer")
    }

    @Test
    fun `re-points another history entry at the re-split elements`() = runTest {
        runCrossEntry(collect = false)
    }

    @Test
    fun `re-points another history entry at the re-split elements after GC`() = runTest {
        runCrossEntry(collect = true)
    }

    // -- not an undo/redo: a peer merges two blocks, then a plain split
    // separates them again, re-creating the merged-away block under a new
    // id. A history entry naming the old block has to follow it, whichever
    // replica split. ----------------------------------------------------

    @Test
    fun `re-points the history when the split is a local edit`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        seedBlocks(d1)
        d1.clearHistory()
        crossSync(d1, d2, overWire = true)

        // d1's entry names the second block; d2 merges it into the first.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").styleByPath(listOf(1), mapOf("bold" to "true"))
        }.await()
        crossSync(d1, d2, overWire = true)
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 2), listOf(1, 0))
        }.await()
        crossSync(d1, d2, overWire = true)

        // d1 splits the blocks apart again, then the merged-away block is
        // purged.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 2), listOf(0, 2), splitLevel = 1)
        }.await()
        crossSync(d1, d2, overWire = true)
        collectBoth(d1, d2)

        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)
        d1.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)
        assertEquals(d1.tree().toXml(), d2.tree().toXml())
    }

    @Test
    fun `re-points the history when the split arrives from a peer`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        seedBlocks(d1)
        d1.clearHistory()
        crossSync(d1, d2, overWire = true)

        // d2's entry names the second block; d1 merges it away and splits
        // again.
        d2.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").styleByPath(listOf(1), mapOf("bold" to "true"))
        }.await()
        crossSync(d1, d2, overWire = true)
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 2), listOf(1, 0))
        }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").editByPath(listOf(0, 2), listOf(0, 2), splitLevel = 1)
        }.await()
        crossSync(d1, d2, overWire = true)
        collectBoth(d1, d2)

        d2.history.undoAsync().await()
        crossSync(d1, d2, overWire = true)
        assertEquals(d1.tree().toXml(), d2.tree().toXml())
    }
}
