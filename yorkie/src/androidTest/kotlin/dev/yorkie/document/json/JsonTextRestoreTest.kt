package dev.yorkie.document.json

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import dev.yorkie.document.crdt.RgaTreeSplitNodeID
import dev.yorkie.helper.maxVectorOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    /**
     * The per-character origin identity of a text's live content, in document
     * order: one `(createdAt, absolute offset)` pair per character. Two replicas
     * converging must match on this, not just on rendered content (S4). Node
     * granularity is deliberately NOT compared: after GC, concurrent undos
     * recreate the purged run as differently-split nodes on each replica
     * (one replica recreates `[2,8)` whole, the other as `[2,4)+[4,6)+[6,8)`),
     * and RGA split boundaries are local state, not a convergence invariant.
     */
    private fun identitySequence(text: JsonText) =
        text.target.rgaTreeSplit.filterNot { it.isRemoved }.flatMap { node ->
            (0 until node.contentLength).map {
                RgaTreeSplitNodeID(node.id.createdAt, node.id.offset + it)
            }
        }

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

            // Purge both replicas' tombstones first (delete -> sync -> GC ->
            // undo -> sync), so each undo must recreate the deleted nodes from
            // scratch and the server relays recreate-path restore ops rather
            // than in-place un-tombstoning.
            val vector = maxVectorOf(listOf(d1.changeID.actor, d2.changeID.actor))
            assertTrue(d1.garbageCollect(vector) > 0)
            assertTrue(d2.garbageCollect(vector) > 0)

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
            // S4 / B2 (instrumented ask): converged content alone is not
            // enough — both replicas must also agree on node identity
            // through the real server round-trip, not just rendered text.
            assertEquals(
                identitySequence(d1.getRoot().getAs<JsonText>("text")),
                identitySequence(d2.getRoot().getAs<JsonText>("text")),
            )
        }
    }
}
