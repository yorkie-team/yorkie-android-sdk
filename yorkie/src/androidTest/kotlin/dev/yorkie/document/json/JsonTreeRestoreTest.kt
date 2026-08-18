package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.json.TreeBuilder.element
import dev.yorkie.document.json.TreeBuilder.text
import kotlin.test.assertEquals
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
            assertEquals(
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
                d2.getRoot().getAs<JsonTree>("tree").toXml(),
            )
            assertEquals(d1.toJson(), d2.toJson())

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
            assertEquals(
                d1.getRoot().getAs<JsonTree>("tree").toXml(),
                d2.getRoot().getAs<JsonTree>("tree").toXml(),
            )
            assertEquals(d1.toJson(), d2.toJson())
        }
    }
}
