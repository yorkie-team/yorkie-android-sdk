package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.json.JsonCounter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented pin for #1401 (`bed81143`): an explicit [Client.syncAsync]
 * in [Client.SyncMode.RealtimePushOnly] must drop a pulled SNAPSHOT -- not just pulled changes --
 * while still acking the push (the headline this port exists for: the pre-#1401 guard only
 * tested `hasChanges`, so a snapshot-only pushPull response still replaced the root).
 *
 * Android has no JS-equivalent `Event.Snapshot` to assert against directly -- the root
 * content and `checkPoint.serverSeq` are the observable proxy instead: the dropped snapshot's keys must never reach [c1]'s document, and
 * the server seq must stay put until realtime sync resumes and pulls it for real.
 */
@RunWith(AndroidJUnit4::class)
class PushOnlySnapshotDropTest {

    @Test
    fun test_snapshot_pulled_in_push_only_mode_is_dropped_but_acked() {
        withTwoClientsAndDocuments(syncMode = Client.SyncMode.Manual) { c1, c2, d1, d2, _ ->
            d1.updateAsync { root, _ -> root.setNewCounter("counter", 0) }.await()
            c1.syncAsync(d1).await()
            c2.syncAsync(d2).await()
            assertEquals(0, d2.getRoot().getAs<JsonCounter>("counter").value)

            c1.changeSyncMode(d1, Client.SyncMode.RealtimePushOnly)

            // c2 pushes DEFAULT_SNAPSHOT_THRESHOLD changes in one pushPull so the server
            // starts answering a stale pushPull with a snapshot instead of incremental
            // changes -- the shape of response #1401 exists to drop in push-only/sync-off.
            repeat(DEFAULT_SNAPSHOT_THRESHOLD) { i ->
                d2.updateAsync { root, _ -> root["r$i"] = i }.await()
            }
            c2.syncAsync(d2).await()

            d1.updateAsync { root, _ -> root.getAs<JsonCounter>("counter").increase(1) }.await()
            val serverSeqBeforeSync = d1.checkPoint.serverSeq

            // An explicit sync always pulls -- the server's answer here is the snapshot
            // c2's push above just crossed the threshold for.
            c1.syncAsync(d1).await()

            // The pulled snapshot is dropped: the root is NOT replaced (none of c2's "r*"
            // keys reach d1), the counter still reads d1's own value, and the push is still
            // acked (nothing left to re-push).
            assertEquals(1, d1.getRoot().getAs<JsonCounter>("counter").value)
            assertFalse(d1.toJson().contains("\"r0\""))
            assertFalse(d1.hasLocalChanges())
            // The dropped pack's server seq stays put: the skipped snapshot is pulled again
            // only once realtime sync resumes below.
            assertEquals(serverSeqBeforeSync, d1.checkPoint.serverSeq)

            // c2 observes its own push independently of d1's push-only state.
            withTimeout(GENERAL_TIMEOUT) {
                while (d2.getRoot().getAs<JsonCounter>("counter").value != 1) {
                    c2.syncAsync(d2).await()
                    delay(50)
                }
            }

            // Back to Realtime: the dropped snapshot is pulled again for real and both
            // documents converge.
            c1.changeSyncMode(d1, Client.SyncMode.Realtime)
            withTimeout(GENERAL_TIMEOUT) {
                while (d1.toJson() != d2.toJson()) {
                    delay(100)
                }
            }
            assertEquals(d2.toJson(), d1.toJson())

            c1.changeSyncMode(d1, Client.SyncMode.Manual)
        }
    }
}
