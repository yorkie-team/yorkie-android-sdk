package dev.yorkie.document

import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import dev.yorkie.helper.maxVectorOf
import dev.yorkie.util.DataSize
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Characterization tests for four KNOWN DEFECTS that this SDK deliberately
 * shares with yorkie-js-sdk v0.7.14 (PR #360 reviews 5165084872 and
 * 5194612367, findings F1–F4; specs 014 and 016). All are faithful ports:
 * fixing any of them Android-only would make an Android replica resolve the
 * same relayed operations to a different tree than a JS or iOS peer, which
 * is worse than the shared defect (lesson all/003, #359 B2). The fix must
 * land in yorkie-js-sdk (and, for F1, the server) first and then be ported.
 *
 * These tests pin the CURRENT outcome so the port is a deliberate, visible
 * flip — when one of them fails after a sync-up, update the expectation
 * together with the port and delete the defect note.
 *
 * - F1: `CrdtTree.edit`'s merge phase relocates the merge source's TOMBSTONED
 *   children into the live merge target (`allChildren` + `moveChild`, port of
 *   JS c5d5c851). A concurrent position anchored on such a tombstone then
 *   resolves into the live target, so the `realParent.isRemoved` redirect and
 *   the `Boundary.Range` branch in `findNodesAndSplitText` never fire and a
 *   concurrent cross-boundary style (or delete) lands on the wrong nodes.
 *   Upstream issue draft: harness-docs round-1 build report of spec 014.
 * - F3: `TreeEditOperation.executeRestore` emits a `TreeEditOpInfo` with
 *   `from == to == 0` and no nodes for a decoded remote restore, exactly like
 *   JS (`normalizePos() -> [0,0]`, `getContentSize() -> 0`), so
 *   `Document.reconcileHistoryEdits` shifts pending tree undos by 0 although
 *   the restore changed the index space; a later undo of a pure split then
 *   deletes live text. Backlog candidate 009 (`tree-restore-history-reconcile-delta`).
 * - F2: `CrdtTree.restore` un-tombstones a target (and `recreateFromSpan`
 *   places a purged one live) with no removed-ancestor guard, exactly like JS
 *   `tree.ts` `restore`/`recreateFromSpan`. The node is invisible yet counted
 *   live, and a replica that already purged the parent diverges from one that
 *   still holds its tombstone once the parent is revived. An Android-only
 *   guard (spec 014) was reverted in spec 016 on review 5194612367: same
 *   relayed op, different state on Android vs JS/iOS.
 * - F4: `recreateFromSpan` deep-copies the span's attribute snapshot with its
 *   tombstoned `RhtNode`s and registers no GC pair for them (JS does the
 *   same), so the copies are unreachable by any sweep until a snapshot reload.
 */
class TreeUpstreamDefectPinTest {

    private val actor1 = "000000000000000000000001"
    private val actor2 = "000000000000000000000002"

    private suspend fun Document.xml(key: String = "t"): String =
        getRoot().getAs<JsonTree>(key).toXml()

    private suspend fun pair(init: () -> JsonTree.ElementNode): Pair<Document, Document> {
        val d1 = Document("test-doc")
        val d2 = Document("test-doc")
        d1.setActor(actor1)
        d2.setActor(actor2)
        d1.updateAsync { root, _ -> root.setNewTree("t", init()) }.await()
        crossSync(d1, d2)
        return d1 to d2
    }

    // KNOWN UPSTREAM DEFECT (F1) — do not fix Android-only. Reviewer's probe:
    // d1 merges the two paragraphs while d2 styles across their boundary.
    // The style's `to` anchor is the text "cd", which the merge tombstones
    // AND relocates into the surviving <p>, so on d1 the style collapses
    // inside that <p> and never reaches its tokens, while on d2 the style ran
    // before the merge arrived. The replicas do NOT converge.
    @Test
    fun `known upstream defect - merge relocating tombstones breaks a concurrent boundary style`() =
        runTest {
            val (d1, d2) = pair {
                element("r") {
                    element("p") { text { "ab" } }
                    element("p") { text { "cd" } }
                }
            }
            assertEquals("<r><p>ab</p><p>cd</p></r>", d1.xml())

            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 7) }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonTree>("t").style(3, 7, mapOf("bold" to "x"))
            }.await()
            crossSync(d1, d2)

            // Pinned CURRENT outcome (yorkie-js-sdk v0.7.14 parity): divergence.
            assertEquals("<r><p></p></r>", d1.xml())
            assertEquals("<r><p bold=\"x\"></p></r>", d2.xml())
            assertNotEquals(
                d1.xml(),
                d2.xml(),
                "pinned divergence — flip this when the upstream fix is ported",
            )
        }

    // KNOWN DEFECT (F3, JS parity; backlog 009) — a peer's identity restore
    // reports an index delta of 0, so d1's pending split-undo keeps offsets
    // that are stale by the restored width. Undoing the split then deletes
    // live text ("34") instead of merging the paragraphs back.
    @Test
    fun `known defect - a remote identity restore does not re-index pending tree undos`() =
        runTest {
            val (d1, d2) = pair { element("doc") { element("p") { text { "0123456789" } } } }

            // d1: pure split, leaving a boundary-deletion undo on its stack.
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 6, 1) }.await()
            crossSync(d1, d2)
            assertEquals("<doc><p>01234</p><p>56789</p></doc>", d1.xml())

            // d2 deletes "01"; d1's pending undo offsets shift by -2 (correct).
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 3) }.await()
            crossSync(d1, d2)
            assertEquals("<doc><p>234</p><p>56789</p></doc>", d1.xml())

            // d2 undoes: an identity restore revives "01" on both replicas, but
            // its opInfo carries delta 0, so d1's pending undo is NOT shifted +2.
            d2.history.undoAsync().await()
            crossSync(d1, d2)
            assertEquals("<doc><p>01234</p><p>56789</p></doc>", d1.xml())
            assertEquals(d1.xml(), d2.xml())

            // d1 undoes its split. Correct: <doc><p>0123456789</p></doc>.
            d1.history.undoAsync().await()
            crossSync(d1, d2)

            // Pinned CURRENT outcome (JS parity): the boundary deletion lands two
            // characters early and destroys "34" on BOTH replicas.
            assertEquals("<doc><p>012</p><p>56789</p></doc>", d1.xml())
            assertEquals(d1.xml(), d2.xml())
        }

    // KNOWN UPSTREAM DEFECT (F2, review 5194612367) — do not fix Android-only.
    // ngocann's probe: d1 deletes "hello", d2 then deletes the empty <p>; only
    // d2 purges. d1's undo un-tombstones "hello" under a <p> that is still a
    // tombstone on d1 (JS `restore` has no removed-ancestor guard) and finds no
    // parent at all on d2. Once d2 revives <p>, the replicas diverge.
    @Test
    fun `known upstream defect - restore under a tombstoned parent diverges`() = runTest {
        val (d1, d2) = pair { element("doc") { element("p") { text { "hello" } } } }
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 6) }.await()
        crossSync(d1, d2)
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 2) }.await()
        crossSync(d1, d2)
        assertEquals("<doc></doc>", d1.xml())
        assertEquals(d1.xml(), d2.xml())
        // Only d2 reaches the GC threshold: a normal sync-timing window.
        d2.garbageCollect(maxVectorOf(listOf(actor1, actor2)))

        d1.history.undoAsync().await()
        // Pinned: "hello" is un-tombstoned under the tombstoned <p> — hidden,
        // yet counted live (the reviewer's {data=10, meta=120}).
        assertEquals("<doc></doc>", d1.xml())
        assertEquals(DataSize(data = 10, meta = 120), d1.getDocSize().live)
        crossSync(d1, d2)

        d2.history.undoAsync().await()
        crossSync(d1, d2)
        // Pinned CURRENT outcome (yorkie-js-sdk v0.7.14 parity): divergence.
        assertEquals("<doc><p>hello</p></doc>", d1.xml())
        assertEquals("<doc><p></p></doc>", d2.xml())
        assertNotEquals(
            d1.xml(),
            d2.xml(),
            "pinned divergence — flip this when the upstream fix is ported",
        )
    }

    // F2, recreate route: "hello" is purged on BOTH replicas before <p> is
    // tombstoned, so d1's undo recreates it from its span under the tombstone.
    // JS `recreateFromSpan` returns the node live and placed: the replicas
    // converge here, but both count the hidden node as live.
    @Test
    fun `known upstream defect - recreate under a tombstoned parent is counted live`() = runTest {
        val (d1, d2) = pair { element("doc") { element("p") { text { "hello" } } } }
        val vector = maxVectorOf(listOf(actor1, actor2))
        d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(1, 6) }.await()
        crossSync(d1, d2)
        assertEquals(1, d1.garbageCollect(vector), "\"hello\" must be purged on d1")
        assertEquals(1, d2.garbageCollect(vector), "\"hello\" must be purged on d2")
        d2.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 2) }.await()
        crossSync(d1, d2)
        assertEquals("<doc></doc>", d1.xml())
        assertEquals(DataSize(data = 0, meta = 96), d1.getDocSize().live)

        d1.history.undoAsync().await()
        crossSync(d1, d2)
        // Pinned: nothing renders, yet both replicas count "hello" live.
        assertEquals("<doc></doc>", d1.xml())
        assertEquals(d1.xml(), d2.xml())
        assertEquals(DataSize(data = 10, meta = 120), d1.getDocSize().live)
        assertEquals(d1.getDocSize(), d2.getDocSize())

        d2.history.undoAsync().await()
        crossSync(d1, d2)
        assertEquals("<doc><p>hello</p></doc>", d1.xml())
        assertEquals(d1.xml(), d2.xml())
    }

    // KNOWN UPSTREAM DEFECT (F4) — JS `recreateFromSpan` deep-copies the span's
    // attribute snapshot, tombstoned RhtNodes included (removeStyle leaves its
    // tombstone inside the Rht; an overwrite's tombstone is a detached copy and
    // never enters it), and registers no GC pair for them: the copy stays
    // unreachable by any sweep until a snapshot reload.
    @Test
    fun `known upstream defect - copied attribute tombstones are never swept`() = runTest {
        val document = Document("test-doc")
        document.setActor(actor1)
        val vector = maxVectorOf(listOf(actor1))
        document.updateAsync { root, _ ->
            root.setNewTree("t", element("doc") { element("p") { text { "x" } } })
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").style(0, 3, mapOf("bold" to "a"))
        }.await()
        document.updateAsync { root, _ ->
            root.getAs<JsonTree>("t").removeStyle(0, 3, listOf("bold"))
        }.await()
        assertEquals(1, document.garbageLength, "the removed attribute is a tombstone in the Rht")
        document.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(0, 3) }.await()
        assertEquals(3, document.garbageCollect(vector), "<p>, \"x\" and the attribute are purged")

        document.history.undoAsync().await()
        assertEquals("<doc><p>x</p></doc>", document.xml())
        val recreated = (document.getRootObject()["t"] as CrdtTree).indexTree.root.allChildren
            .single { !it.isRemoved && it.type == "p" }
        // Pinned CURRENT outcome (JS parity): the tombstone rides along unregistered.
        assertEquals(1, recreated.gcPairs.size)
        assertEquals(
            0,
            document.garbageLength,
            "pinned leak — flip when the upstream fix is ported",
        )
        assertEquals(0, document.garbageCollect(vector))
        assertEquals(1, recreated.gcPairs.size)
    }

    // Control for the F3 pin: without the remote delete/restore the same
    // split-undo merges the paragraphs back correctly.
    @Test
    fun `control - undoing a pure split without a remote restore merges the paragraphs back`() =
        runTest {
            val (d1, d2) = pair { element("doc") { element("p") { text { "0123456789" } } } }
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("t").edit(6, 6, 1) }.await()
            crossSync(d1, d2)
            d1.history.undoAsync().await()
            crossSync(d1, d2)
            assertEquals("<doc><p>0123456789</p></doc>", d1.xml())
            assertEquals(d1.xml(), d2.xml())
        }
}
