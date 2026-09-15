package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.Document
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ports the overlapping-delete undo-convergence scenario from
 * `history_tree_test.ts` (JS SDK fa6cc513) as an instrumented two-client
 * test against the real Yorkie server (AC14).
 *
 * NOTE: this scenario requires the server to relay `Operation.TreeEdit`'s
 * new `restore_spans`/`restore_mode`/`retombstone_spans` fields (proto
 * fields 8/9/10) transparently between clients — the Tree companion of the
 * Text relay pinned by `JsonTextRestoreTest` (server v0.7.14, docker image
 * pin bumped alongside this test). A server predating that relay decodes an
 * incoming TreeEdit into its own (older) schema and silently drops the
 * unknown fields when re-encoding.
 */
@RunWith(AndroidJUnit4::class)
class JsonTreeRestoreTest {

    /**
     * The live node-identity sequence of the document's tree, in postorder.
     * Two replicas converging must match on this, not just on rendered XML:
     * identical text can hide different text-node segmentation, which is the
     * divergence the split-aware restore exists to prevent.
     */
    private fun identitySequence(document: Document): List<CrdtTreeNodeID> = buildList {
        (document.getRootObject()["tree"] as CrdtTree).indexTree.traverse { node, _ ->
            add(
                node.id,
            )
        }
    }

    private suspend fun assertConverged(d1: Document, d2: Document) {
        assertEquals(
            d1.getRoot().getAs<JsonTree>("tree").toXml(),
            d2.getRoot().getAs<JsonTree>("tree").toXml(),
        )
        assertEquals(identitySequence(d1), identitySequence(d2))
        assertEquals(d1.toJson(), d2.toJson())
    }

    @Test
    fun test_overlapping_tree_deletes_both_undo_converge() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("root") { text { "0123456789" } },
                )
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(d1.toJson(), d2.toJson())

            // Concurrent overlapping deletes: d1 deletes "45", d2 deletes the
            // superset "234567".
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(4, 6) }.await()
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(2, 8) }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertEquals("<root>0189</root>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            assertConverged(d1, d2)

            // Both undo their own overlapping delete — identity-preserving
            // restore must converge both replicas back to the original
            // content (and, per the JVM-level TreeRestoreConvergenceTest,
            // identity) through the real server round-trip.
            d1.history.undoAsync().await()
            d2.history.undoAsync().await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            assertEquals("<root>0123456789</root>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            assertConverged(d1, d2)
        }
    }

    /**
     * Ports `history_tree_concurrent_test.ts`'s overlap scenario (JS SDK
     * 7b2ab7a4, v0.7.15, JS #1315) as an instrumented two-client test
     * against the real Yorkie server 0.7.15 (spec 006 AC10). Unlike
     * [test_overlapping_tree_deletes_both_undo_converge] above (which undoes
     * before either replica's tombstone is GC-purged), this settles several
     * extra sync rounds first so the server's returned min-synced version
     * vector advances enough for [dev.yorkie.document.Document.applyChangePack]'s
     * automatic client-side GC to purge the tombstones before either undo —
     * forcing restore onto the recreate + isolate path this spec ports.
     */
    @Test
    fun test_overlapping_tree_deletes_after_gc_purge_both_undo_converge() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewTree(
                    "tree",
                    element("root") { text { "0123456789" } },
                )
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(d1.toJson(), d2.toJson())

            // Concurrent overlapping deletes: d1 deletes "45", d2 deletes the
            // superset "234567".
            d1.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(4, 6) }.await()
            d2.updateAsync { root, _ -> root.getAs<JsonTree>("tree").edit(2, 8) }.await()
            assertTrue(d1.garbageLength > 0, "d1 must hold its tombstone before settling")
            assertTrue(d2.garbageLength > 0, "d2 must hold its tombstone before settling")

            // Settle several extra rounds so the server's min-synced version
            // vector advances past both tombstones and the client-side GC
            // inside applyChangePack purges them before either undo.
            repeat(5) {
                c1.syncAsync().await()
                c2.syncAsync().await()
            }
            assertEquals("<root>0189</root>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            assertConverged(d1, d2)
            // The purge is what separates this case from the one above: assert
            // it happened instead of trusting the round count.
            assertEquals(0, d1.garbageLength, "d1 must have purged every tombstone before undo")
            assertEquals(0, d2.garbageLength, "d2 must have purged every tombstone before undo")

            // Both undo their own overlapping delete after the purge —
            // restore now takes the recreate path and must isolate the
            // exact in-span sub-range from whatever the other replica
            // already recreated, converging both replicas back to the
            // original content through the real server round-trip.
            d1.history.undoAsync().await()
            d2.history.undoAsync().await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            assertEquals("<root>0123456789</root>", d1.getRoot().getAs<JsonTree>("tree").toXml())
            assertConverged(d1, d2)
        }
    }
}
