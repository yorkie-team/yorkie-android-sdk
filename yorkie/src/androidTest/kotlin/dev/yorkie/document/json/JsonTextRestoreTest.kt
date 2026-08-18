package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ports the overlapping-delete undo-convergence scenario from
 * `text_restore_convergence_test.ts` (JS SDK 5d5cac63, #1293) as an
 * instrumented two-client test against the real Yorkie server (AC12).
 *
 * NOTE: this scenario requires the server to relay `Operation.Edit`'s new
 * `restore_spans`/`restore_mode`/`retombstone_spans` fields (proto fields
 * 8/9/10) transparently between clients. The JS reference commit 5d5cac63
 * itself depends on a companion server-side change (yorkie-team/yorkie
 * #1875) for this; a server predating that change decodes an incoming Edit
 * into its own (older) schema and re-encodes it when relaying, silently
 * dropping the unknown fields — exactly the "old-peer no-op" wire contract
 * this spec's AC10 pins (content="", fromPos==toPos), just applied by the
 * relaying server instead of a receiving client. See round-1 build report.
 */
@RunWith(AndroidJUnit4::class)
class JsonTextRestoreTest {

    @Test
    fun test_overlapping_deletes_both_undo_converge() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ ->
                root.setNewText("text").edit(0, 0, "0123456789")
            }.await()
            c1.syncAsync().await()
            c2.syncAsync().await()
            assertEquals(d1.toJson(), d2.toJson())

            // Concurrent overlapping deletes: d1 deletes "45", d2 deletes the
            // superset "234567".
            d1.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(4, 6, "")
            }.await()
            d2.updateAsync { root, _ ->
                root.getAs<JsonText>("text").edit(2, 8, "")
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()
            assertEquals("0189", d1.getRoot().getAs<JsonText>("text").toString())
            assertEquals(
                d1.getRoot().getAs<JsonText>("text").toString(),
                d2.getRoot().getAs<JsonText>("text").toString(),
            )

            // Both undo their own overlapping delete — identity-preserving
            // restore must converge both replicas back to the original
            // content through the real server round-trip.
            d1.history.undoAsync().await()
            d2.history.undoAsync().await()

            c1.syncAsync().await()
            c2.syncAsync().await()
            c1.syncAsync().await()

            assertEquals("0123456789", d1.getRoot().getAs<JsonText>("text").toString())
            assertEquals(
                d1.getRoot().getAs<JsonText>("text").toString(),
                d2.getRoot().getAs<JsonText>("text").toString(),
            )
        }
    }
}
