package dev.yorkie.document

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.assertJsonContentEquals
import dev.yorkie.core.Client.SyncMode.Manual
import dev.yorkie.core.withTwoClientsAndDocuments
import kotlin.test.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers AC4: a `skipHistory = true` mutation syncs to a peer and both
 * replicas converge identically to an ordinary update.
 */
@RunWith(AndroidJUnit4::class)
class SkipHistoryTest {

    @Test
    fun skip_history_update_converges_to_peer_like_an_ordinary_update() {
        withTwoClientsAndDocuments(syncMode = Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync(skipHistory = true) { root, _ ->
                root["k1"] = "bookkeeping"
            }.await()

            c1.syncAsync().await()
            c2.syncAsync().await()

            assertJsonContentEquals("""{"k1":"bookkeeping"}""", d1.toJson())
            assertJsonContentEquals("""{"k1":"bookkeeping"}""", d2.toJson())

            // The skipHistory write left no undo entry on the originating client.
            assertFalse(d1.history.canUndo())
        }
    }
}
