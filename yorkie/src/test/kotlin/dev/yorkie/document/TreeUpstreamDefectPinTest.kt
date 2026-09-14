package dev.yorkie.document

import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import dev.yorkie.helper.crossSync
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Characterization tests for two KNOWN DEFECTS that this SDK deliberately
 * shares with yorkie-js-sdk v0.7.14 (PR #360 review 5165084872, findings F1
 * and F3; spec 014). Both are faithful ports: fixing either Android-only
 * would make an Android replica resolve the same relayed operations to a
 * different tree than a JS or iOS peer, which is worse than the shared
 * defect. The fix must land in yorkie-js-sdk (and, for F1, the server) first
 * and then be ported.
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
