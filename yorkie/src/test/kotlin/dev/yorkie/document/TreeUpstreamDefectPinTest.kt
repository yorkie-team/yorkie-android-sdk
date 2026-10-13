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
 * Characterization tests for KNOWN DEFECTS that this SDK deliberately shares
 * with yorkie-js-sdk (PR #360 reviews 5165084872 and 5194612367, findings
 * F1–F4). All are faithful ports: fixing any of them Android-only would make
 * an Android replica resolve the same relayed operations to a different tree
 * than a JS or iOS peer, which is worse than the shared defect (lesson
 * all/003, #359 B2). The fix must land in yorkie-js-sdk (and, for F1, the
 * server) first and then be ported.
 *
 * These tests pin the CURRENT outcome so a port is a deliberate, visible
 * flip — when one of them fails after a sync-up, update the expectation
 * together with the port and delete the defect note. F2's RECREATE route was
 * flipped this way (yorkie-js-sdk 248551a1, #1364): see `recreate under a
 * tombstoned parent is now born tombstoned` below. F1's style/removeStyle
 * case was flipped the same way by b69169c0 (#1404): see `fixed upstream
 * defect - merge relocating tombstones no longer breaks a concurrent
 * boundary style` below. F4 was flipped the same way by dbff8892 (#1405):
 * see `fixed upstream defect - copied attribute tombstones are now swept`
 * below. F3 was flipped the same way by `790422ce` (#1421): see `fixed
 * upstream defect - a remote identity restore now re-indexes pending tree
 * undos` below.
 *
 * - F1 (STYLE/REMOVESTYLE case, FIXED): `CrdtTree.edit`'s merge phase still
 *   relocates the merge source's TOMBSTONED children into the live merge
 *   target exactly as before (`allChildren` + `moveChild`, port of JS
 *   c5d5c851) — that root cause is untouched. What changed is how a style's
 *   reached set is resolved: `styleTargets`/`declaredBoundaries`/
 *   `DeclaredLineage` (yorkie-js-sdk b69169c0, #1404; yorkie#2038/#2070) walk
 *   the CHANGE's own positions rather than resolving the anchor in the
 *   receiving replica's current index space, so it no longer matters that a
 *   concurrent merge relocated the tombstone the anchor named — both
 *   replicas now resolve the same target and converge. A concurrent
 *   cross-boundary DELETE across the same relocated tombstone is NOT covered
 *   by this port (#1404(c)'s scope is style/removeStyle only) and may still
 *   share the underlying relocation defect; not probed by this file.
 *   Upstream issue draft: harness-docs round-1 build report of spec 014.
 * - F3 (FIXED): `TreeEditOperation.executeRestore` used to emit a single
 *   `TreeEditOpInfo` with `from == to == 0` and no nodes for a decoded remote
 *   restore, exactly like JS's pre-fix shape (`normalizePos() -> [0,0]`,
 *   `getContentSize() -> 0`), so `Document.reconcileHistoryEdits` shifted
 *   pending tree undos by 0 although the restore changed the index space; a
 *   later undo of a pure split then deleted live text. Port yorkie-js-sdk
 *   `790422ce` (#1421) now reports one populated `TreeEditOpInfo` per node
 *   the restore actually measured and records the per-node ranges as
 *   `executedRanges`, which `Document.reconcileHistoryEdits` replays one at a
 *   time, so the peer's restore now re-indexes the pending split-undo by the
 *   restored width; see `fixed upstream defect - a remote identity restore
 *   now re-indexes pending tree undos` below (was `known defect - ...`).
 * - F2 (UNREMOVE route, STILL OPEN): `CrdtTree.restore` un-tombstones a
 *   target with no removed-ancestor guard, exactly like JS `tree.ts`
 *   `restore`. The node is invisible yet counted live, and a replica that
 *   already purged the parent diverges from one that still holds its
 *   tombstone once the parent is revived. An Android-only guard was reverted
 *   on review 5194612367: same relayed op, different state on Android vs
 *   JS/iOS. yorkie-js-sdk #1364 does not touch this route — still pinned by
 *   `restore under a tombstoned parent diverges`.
 * - F2 (RECREATE route, FIXED): `recreateFromSpan` used to place a purged
 *   node LIVE under a still-tombstoned parent (same JS v0.7.14 shape as the
 *   unremove route). Port yorkie-js-sdk 248551a1 (#1364) now attaches it
 *   tombstoned with the parent's own `removedAt` and a pending `gcOnlySize`
 *   pair instead — never counted live, no orphan at the next purge. Both
 *   replicas resolve the same relayed op identically, so this stays
 *   convergent; see `recreate under a tombstoned parent is now born
 *   tombstoned` below (was `... is counted live`).
 * - F4 (FIXED): `recreateFromSpan` deep-copies the span's attribute snapshot
 *   with its tombstoned `RhtNode`s. Port yorkie-js-sdk `dbff8892` (#1405) now
 *   books each one through `getGCPairs`/`attach()` into `CrdtRoot`'s
 *   registered pairs, so the copies are reachable by the next sweep instead
 *   of sitting unreachable until a snapshot reload. UNRELATED to, and not
 *   fixed by, the split-path fix above (port #1363, which covers only
 *   `CrdtTreeNode.split`'s own copied tombstones, not `recreateFromSpan`'s);
 *   see `fixed upstream defect - copied attribute tombstones are now swept`
 *   below (was `... are never swept`).
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

    // F1 FIXED — yorkie-js-sdk b69169c0 (#1404; yorkie#2038/#2070). The
    // receiving replica no longer resolves a style's reached set in its own
    // index space: `styleTargets`/`declaredBoundaries`/`DeclaredLineage`
    // walk the CHANGE's own positions instead, so a concurrent merge that
    // tombstones and relocates the style's `to` anchor (the text "cd", moved
    // into the surviving <p>) no longer silently redirects it. d1 (ran the
    // merge) and d2 (ran the style) now resolve the same target and
    // converge. Flipped per this file's own documented process ("update the
    // expectation together with the port and delete the defect note" --
    // same flip F2's RECREATE route already got, port 248551a1 above).
    @Test
    fun `fixed upstream defect - merge relocating tombstones no longer breaks a boundary style`() =
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

            // Converged CURRENT outcome (yorkie-js-sdk b69169c0 parity).
            assertEquals("<r><p bold=\"x\"></p></r>", d1.xml())
            assertEquals("<r><p bold=\"x\"></p></r>", d2.xml())
            assertEquals(
                d1.xml(),
                d2.xml(),
                "converges now -- b69169c0 (#1404) fixed F1",
            )
        }

    // F3, NOW FIXED (port yorkie-js-sdk `790422ce`, #1421) — a peer's
    // identity restore used to report an index delta of 0, so d1's pending split-undo kept offsets that were stale by the
    // restored width and later destroyed live text ("34"). executeRestore
    // now measures and publishes the restore's real range (and
    // Document.reconcileHistoryEdits replays it), so d1's split-undo shifts
    // correctly and the paragraphs merge back whole.
    @Test
    fun `fixed upstream defect - a remote identity restore now re-indexes pending tree undos`() =
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

            // d2 undoes: an identity restore revives "01" on both replicas, and
            // its executedRanges now carry the real [from, to, insertedSize],
            // so d1's pending undo IS shifted +2.
            d2.history.undoAsync().await()
            crossSync(d1, d2)
            assertEquals("<doc><p>01234</p><p>56789</p></doc>", d1.xml())
            assertEquals(d1.xml(), d2.xml())

            // d1 undoes its split.
            d1.history.undoAsync().await()
            crossSync(d1, d2)

            // FIXED outcome (yorkie-js-sdk 790422ce parity, executed BOTH
            // sides): the paragraphs merge back whole
            // instead of the boundary deletion landing two characters early.
            assertEquals("<doc><p>0123456789</p></doc>", d1.xml())
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

    // F2, recreate route — NOW FIXED (port yorkie-js-sdk 248551a1, #1364):
    // "hello" is purged on BOTH replicas before <p> is tombstoned, so d1's
    // undo recreates it from its span under the tombstone. recreateFromSpan's
    // attach() now stamps the recreated node with <p>'s own removedAt instead
    // of placing it live: the node is NOT counted live, and registers a
    // pending gcOnlySize pair so a later purge of <p> does not orphan it.
    // Figures below are read off the executed green run, not guessed.
    @Test
    fun `recreate under a tombstoned parent is now born tombstoned`() = runTest {
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
        // FIXED: nothing renders (same as before the fix), but the recreated
        // node is NOT counted live — live size stays exactly what it was
        // pre-undo; its size instead lands in gc (gcOnlySize pair).
        assertEquals("<doc></doc>", d1.xml())
        assertEquals(d1.xml(), d2.xml())
        assertEquals(DataSize(data = 0, meta = 96), d1.getDocSize().live)
        assertEquals(DataSize(data = 10, meta = 96), d1.getDocSize().gc)
        assertEquals(d1.getDocSize(), d2.getDocSize())

        // The recreated node's gcOnlySize pair is reachable — a purge sweeps
        // it (plus <p>'s own tombstone) instead of leaving either an orphan.
        assertEquals(2, d1.garbageCollect(vector))
        assertEquals(DataSize(data = 0, meta = 0), d1.getDocSize().gc)

        // "hello" was purged, not revived, so reviving <p> leaves it empty —
        // both replicas converge on the SAME (correct) outcome, unlike the
        // pre-fix divergence this test used to pin.
        d2.history.undoAsync().await()
        crossSync(d1, d2)
        assertEquals("<doc><p></p></doc>", d1.xml())
        assertEquals(d1.xml(), d2.xml())
    }

    // F4, NOW FIXED (port yorkie-js-sdk dbff8892, #1405): `recreateFromSpan`
    // still deep-copies the span's attribute snapshot, tombstoned RhtNodes
    // included (removeStyle leaves its tombstone inside the Rht; an
    // overwrite's tombstone is a detached copy and never enters it), but
    // `attach()` now books each one through `CrdtRoot.registerGCPair` --
    // reachable by the next sweep instead of an unregistered leak. Figures
    // below are read off the executed green run, not guessed.
    @Test
    fun `fixed upstream defect - copied attribute tombstones are now swept`() = runTest {
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
        // The tombstone still rides along inside the recreated node's Rht --
        // recreateFromSpan's deep copy is unchanged; what moved is whether
        // CrdtRoot has it registered.
        assertEquals(1, recreated.gcPairs.size)
        assertEquals(
            1,
            document.garbageLength,
            "FIXED: the copied tombstone is registered, not an unreachable leak",
        )
        assertEquals(1, document.garbageCollect(vector))
        // The sweep purges the RhtNode out of the Rht itself, not merely out
        // of CrdtRoot's bookkeeping.
        assertEquals(0, recreated.gcPairs.size)
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
