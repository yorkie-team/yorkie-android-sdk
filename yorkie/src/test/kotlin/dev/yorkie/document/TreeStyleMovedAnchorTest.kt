package dev.yorkie.document

import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.document.time.VersionVector
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Ports `tree_style_moved_anchor_test.ts` (JS SDK 1c033ff5, mirrors
 * yorkie#1928) as JVM in-process cross-sync tests (AC7): a style/removeStyle
 * range whose declared end anchor sits inside a parent a concurrent merge
 * tombstoned must not leak onto content that merge moved into the merge
 * target on the applying replica.
 */
class TreeStyleMovedAnchorTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"
    private val actor3 = "000000000000000000000003"

    private fun Document.crdtTree(key: String = "t"): CrdtTree = getRootObject()[key] as CrdtTree

    private fun boldOf(node: CrdtTreeNode) = node.attributes["bold"]

    /** Finds the surviving (live) node of [type], ignoring tombstones. */
    private fun CrdtTree.findByType(type: String): CrdtTreeNode? {
        var found: CrdtTreeNode? = null
        indexTree.traverseAll { node, _ -> if (node.type == type && !node.isRemoved) found = node }
        return found
    }

    private suspend fun threeWaySync(
        d1: Document,
        d2: Document,
        d3: Document,
    ) {
        val pack1 = d1.createChangePack()
        val pack2 = d2.createChangePack()
        val pack3 = d3.createChangePack()

        suspend fun applyFrom(
            target: Document,
            source: Document,
            pack: ChangePack,
        ) {
            target.applyChangePack(
                ChangePack(
                    source.getKey(),
                    CheckPoint.InitialCheckPoint,
                    pack.changes,
                    null,
                    false,
                    VersionVector(),
                ),
            )
        }

        applyFrom(d1, d2, pack2)
        applyFrom(d1, d3, pack3)
        applyFrom(d2, d1, pack1)
        applyFrom(d2, d3, pack3)
        applyFrom(d3, d1, pack1)
        applyFrom(d3, d2, pack2)

        d1.applyChangePack(
            ChangePack(
                d1.getKey(),
                CheckPoint(0, pack1.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
        d2.applyChangePack(
            ChangePack(
                d2.getKey(),
                CheckPoint(0, pack2.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
        d3.applyChangePack(
            ChangePack(
                d3.getKey(),
                CheckPoint(0, pack3.checkPoint.clientSeq),
                emptyList(),
                null,
                false,
                VersionVector(),
            ),
        )
    }

    // AC7 (case 1, headline): style does not leak onto a sibling inserted
    // at the merge anchor while it never learned of the concurrent merge.
    @Test
    fun `style does not apply to a node inserted at the merge anchor`() = runTest {
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

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("x")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val x = tree1.findByType("x")
        assertNull(boldOf(requireNotNull(x)), "the interloper must not carry the concurrent style")
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 2): removeStyle leaves nothing on the interloper either —
    // same anchor shape, opposite attribute direction.
    @Test
    fun `removeStyle leaves nothing on a node inserted at the merge anchor`() = runTest {
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
        // Pre-existing bold on the whole document, synced first, so
        // removeStyle has something real to remove.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 9, mapOf("bold" to "x"))
        }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("x")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").removeStyle(0, 6, listOf("bold"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val x = tree1.findByType("x")
        assertNull(boldOf(requireNotNull(x)), "the interloper must carry no bold either way")
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 3): the styling client's OWN insert genuinely INSIDE the
    // declared range (not at the boundary) is still styled — the guard
    // must not over-fire on ordinary same-client content.
    @Test
    fun `own insert inside the styled range is still styled`() = runTest {
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

        // Insert a new element BEFORE the first paragraph — comfortably
        // inside [0,6) and untouched by d2's concurrent delete/merge of the
        // two paragraphs, so styling it is an ordinary case the guard must
        // not disturb.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 0, element("x")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val x = tree1.findByType("x")
        assertEquals("x", boldOf(requireNotNull(x)))
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 4): a subtree (element with a child) inserted at the merge
    // anchor is skipped as one unit — descendants included.
    @Test
    fun `interloper's descendants are skipped along with it`() = runTest {
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

        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").edit(8, 8, element("x") { element("y") { } })
        }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val x = tree1.findByType("x")
        val y = tree1.findByType("y")
        assertNull(boldOf(requireNotNull(x)))
        assertNull(boldOf(requireNotNull(y)))
        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 5): content already synced (known to both replicas) before
    // the concurrent merge is unaffected by the interloper filter — it is
    // simply ordinary, non-interloper content and stays styled.
    @Test
    fun `sibling synced before the merge is still styled`() = runTest {
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
        // Insert "x" at the merge anchor and sync it BEFORE the style/merge
        // race, so both replicas already know about it.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("x")) }.await()
        crossSync(d1, d2)

        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        assertEquals(d1.toJson(), d2.toJson())
    }

    // AC7 (case 6, fail-open): a node that reached the merge target via an
    // EARLIER, already-synced merge (so it carries a mergedFrom stamp) is
    // inside the styled range — it IS styled, since the guard fails open
    // on any stamp (stamp equality cannot prove range membership after a
    // chained merge).
    @Test
    fun `child from an earlier synced merge is still styled`() = runTest {
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
                    element("p") { text { "ef" } }
                },
            )
        }.await()
        crossSync(d1, d2)

        // Merge the SECOND paragraph into the first, and sync it, so "cd"
        // already carries mergedFrom by the time the next race starts —
        // fail-open must not treat already-known, already-stamped content
        // as an interloper of a LATER, unrelated merge.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(3, 5) }.await()
        crossSync(d1, d2)
        assertEquals("<r><p>abcd</p><p>ef</p></r>", d1.getRoot().getAs<JsonTree>("t").toXml())

        // Style the whole merged paragraph while d2 concurrently merges the
        // (now second) paragraph into it too.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "y"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(5, 7) }.await()

        crossSync(d1, d2)

        val tree1 = d1.crdtTree()
        val cd = tree1.findByType("p")
        assertEquals("y", boldOf(requireNotNull(cd)))
        assertEquals(d1.toJson(), d2.toJson())
    }

    // T-a (commit 2, ea693307 / yorkie-js-sdk#1329): port
    // tree_style_moved_anchor_test.ts 'styles the writer insert when a
    // merge reverses the range' (AC4, AC5, scenario 4).
    @Test
    fun `styles the writer insert when a merge reverses the range`() = runTest {
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

        // d1 inserts an empty <p> after the second paragraph, then styles a
        // range starting after `c` and ending inside its own insert. On d2
        // the concurrent merge moves `cd` behind the insert, so the
        // resolved range collapses and would miss the insert entirely.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("p")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(6, 9, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        assertEquals(
            "<r><p bold=\"x\"></p>cd</r>",
            d1.getRoot().getAs<JsonTree>("t").toXml(),
        )
        assertEquals(d1.toJson(), d2.toJson())
    }

    // T-b (commit 2, ea693307 / yorkie-js-sdk#1329): port
    // tree_style_moved_anchor_test.ts 'applies removeStyle to the writer
    // insert on both replicas' (AC4, AC5, scenario 5). Android cannot
    // assert the JS literal `{"type":"p","children":[],"attributes":{}}` —
    // TreeInfo.toJson omits `attributes` when the live map is empty
    // (pre-existing JSON-rendering difference, out of scope) — so this
    // asserts node-level on BOTH replicas that the inserted <p>'s attribute
    // Rht holds a REMOVED node for "bold" (entry/entry, not empty/empty).
    @Test
    fun `applies removeStyle to the writer insert on both replicas`() = runTest {
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

        // Same shape as T-a with removeStyle: the removal tombstone must
        // materialize on both replicas, not only on the writer.
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("p")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").removeStyle(6, 9, listOf("bold"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        // The merge removes both original paragraphs (only their text
        // content survives, moved to be direct children of <r>), so the
        // writer's inserted (initially empty) <p> is the only live <p> left.
        val insertedOnD1 = requireNotNull(d1.crdtTree().findByType("p"))
        val insertedOnD2 = requireNotNull(d2.crdtTree().findByType("p"))
        assertEquals(
            true,
            insertedOnD1.getAttrs().getNodeMapByKey()["bold"]?.isRemoved,
            "the removal tombstone must materialize on the writer",
        )
        assertEquals(
            true,
            insertedOnD2.getAttrs().getNodeMapByKey()["bold"]?.isRemoved,
            "the removal tombstone must materialize on the receiver too",
        )
        assertEquals(d1.toJson(), d2.toJson())
    }

    // T-c (commit 2, ea693307 / yorkie-js-sdk#1329): port
    // tree_style_moved_anchor_test.ts 'keeps a range that stays ordered
    // away from the insert' (AC4, AC5, scenario 6) — both anchors sit
    // inside the merged paragraph, so the resolved range moves with the
    // merge and stays ordered; the recovery must not widen it onto the
    // insert.
    @Test
    fun `keeps a range that stays ordered away from the insert`() = runTest {
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

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("p")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(6, 7, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()

        crossSync(d1, d2)

        assertEquals("<r><p></p>cd</r>", d1.getRoot().getAs<JsonTree>("t").toXml())
        assertEquals(d1.toJson(), d2.toJson())
    }

    // T-d (commit 2, ea693307 / yorkie-js-sdk#1329): port
    // tree_style_moved_anchor_test.ts 'keeps a reversed range away from an
    // insert unknown to the styler' (AC4, AC6, scenario 7) — three
    // clients: d3's insert is unknown to d1's style, so the version-vector
    // check keeps it unstyled even when the recovered traversal passes it.
    @Test
    fun `keeps a reversed range away from an insert unknown to the styler`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        val d3 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d3.setActor(actor3)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        threeWaySync(d1, d2, d3)

        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("p")) }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(6, 9, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()
        d3.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("b")) }.await()

        threeWaySync(d1, d2, d3)

        assertEquals(d1.toJson(), d2.toJson())
        assertEquals(d2.toJson(), d3.toJson())

        // The inserted <p> (writer's own) is styled; d3's <b> (unknown to
        // the styler's version vector) is not.
        val tree1 = d1.crdtTree()
        val p = requireNotNull(tree1.findByType("p"))
        val b = tree1.findByType("b")
        assertEquals("x", boldOf(p))
        assertNull(boldOf(requireNotNull(b)))
    }

    // T-PIN (commit 1, scenario 9): removeStyle resolves the RAW
    // findNodesAndSplitText anchors — no advancePastUnknownSplitSiblings —
    // matching JS tree.ts@v0.7.18, which never advances inside removeStyle
    // (style()/edit() do). B (the splitter) receives A's removeStyle whose
    // declared from-anchor sits right after the whole (pre-split) <p>; on
    // B the raw anchor resolves to the FIRST split product (holding "a"),
    // so the traversal enters the unknown second split product (holding
    // "c") and removes its "bold". Expected state is the executed JS
    // v0.7.18 vitest probe's (spec 011 C10; test/unit/document/
    // remove_style_raw_anchor_probe_test.ts against the JS worktree,
    // `pnpm vitest run` exit 0 — see the build report): B ends up
    // `<r><p><b bold="x">a</b></p><p><b>c</b></p><q>z</q></r>`.
    //
    // FINDING (drafted, not filed, as an upstream note per spec 021 AC2):
    // A (the writer) and B disagree in this exact interleaving — A's own
    // local execution resolves BEFORE it ever learns of B's split (so its
    // traversal never reaches the split at all and "c" keeps "bold"),
    // while B's remote application resolves AFTER the split already
    // exists. reversedFromAnchorRecovery never fires here (its guard
    // requires a MERGE-tombstoned declaredParent; a split does not remove
    // the original parent), so this is a pre-existing, orthogonal JS
    // defect unrelated to yorkie-js-sdk#1329, present at v0.7.18 (and,
    // since removeStyle's raw-anchor resolution is unchanged since it was
    // introduced, likely earlier tags too). Out of scope to fix here — the
    // JS commit is the behavioral contract for this parity port (lesson
    // all/003) — so this test pins ONLY B's state, not full A==B
    // convergence, mirroring the spec's own "on B the applying-replica
    // state equals the executed JS probe's" wording (not a blanket
    // toJson() equality assertion like the other cases in this file).
    //
    // RED on b0df16bb: the OLD advancing anchor skips past the second
    // split product entirely, so "c" keeps "bold" on B (converging with A
    // by accident). GREEN after commit 1.
    @Test
    fun `removeStyle resolves raw anchors past an unknown split`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") {
                        element("b") { text { "a" } }
                        element("b") { text { "c" } }
                    }
                    element("q") { text { "z" } }
                },
            )
        }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(2, 6, mapOf("bold" to "x"))
        }.await()
        crossSync(d1, d2)

        // B splits <p> between the two <b> (index 4), element split.
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(4, 4, 1) }.await()

        // Bump A's own lamport clock past B's split ticket via two
        // throwaway local styles on <q> (self-cancelling: removed again by
        // the removeStyle below), so findNodesAndSplitText's concurrent-
        // insert tie-break does not itself jump the raw anchor past the
        // split product before the raw-anchor difference can be observed.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(8, 11, mapOf("sentinel" to "y"))
        }.await()
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(8, 11, mapOf("sentinel" to "y"))
        }.await()

        // A concurrently removeStyles a range starting right after </p>
        // (A's view, before the split) and covering <q>.
        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").removeStyle(8, 11, listOf("bold", "sentinel"))
        }.await()

        crossSync(d1, d2)

        assertEquals(
            "<r><p><b bold=\"x\">a</b></p><p><b>c</b></p><q>z</q></r>",
            d2.getRoot().getAs<JsonTree>("t").toXml(),
        )
    }

    // AC7 (case 7): three-client convergence — d3 concurrently inserts at
    // the same merge anchor while d1 styles and d2 merges; all three agree.
    @Test
    fun `three-client convergence on the merge anchor style race`() = runTest {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        val d3 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d3.setActor(actor3)

        d1.updateAsync { root, _ ->
            root.setNewTree(
                "t",
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                },
            )
        }.await()
        threeWaySync(d1, d2, d3)

        d1.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 6, mapOf("bold" to "x"))
        }.await()
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 5) }.await()
        d3.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(8, 8, element("x")) }.await()

        threeWaySync(d1, d2, d3)

        assertEquals(d1.toJson(), d2.toJson())
        assertEquals(d2.toJson(), d3.toJson())
    }
}
