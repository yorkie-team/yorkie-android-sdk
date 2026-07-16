package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import dev.yorkie.document.json.JsonPrimitive
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [Client.SyncMode.Polling]: a document synced without a watch stream
 * still pushes local changes and pulls remote changes on the poll interval.
 * Mirrors JS SDK PR #1243.
 */
@RunWith(AndroidJUnit4::class)
class SyncModePollingTest {

    @Test
    fun test_polling_pushes_and_pulls_without_watch_stream() {
        runBlocking {
            // Short poll interval keeps the test fast.
            val poller = createClient(Client.Options(documentPollInterval = 200.milliseconds))
            val peer = createClient()
            val key = UUID.randomUUID().toString().toDocKey()
            val pollingDoc = Document(key)
            val peerDoc = Document(key)

            poller.activateAsync().await()
            peer.activateAsync().await()
            poller.attachDocument(pollingDoc, syncMode = Client.SyncMode.Polling).await()
            peer.attachDocument(peerDoc, syncMode = Client.SyncMode.Manual).await()

            // Remote change committed by the peer — the poller must pull it on
            // its interval without any local activity or manual sync.
            peerDoc.updateAsync { root, _ -> root["k1"] = "v1" }.await()
            peer.syncAsync(peerDoc).await()

            withTimeout(5_000) {
                while (pollingDoc.getRoot().getOrNull("k1") == null) {
                    delay(100)
                }
            }
            assertEquals("v1", pollingDoc.getRoot().getAs<JsonPrimitive>("k1").value)

            // Local change on the poller must be pushed on its interval so the
            // peer sees it after a manual pull.
            pollingDoc.updateAsync { root, _ -> root["k2"] = "v2" }.await()
            withTimeout(5_000) {
                while (peerDoc.getRoot().getOrNull("k2") == null) {
                    delay(100)
                    peer.syncAsync(peerDoc).await()
                }
            }
            assertEquals("v2", peerDoc.getRoot().getAs<JsonPrimitive>("k2").value)

            poller.detachDocument(pollingDoc).await()
            peer.detachDocument(peerDoc).await()
            poller.deactivateAsync().await()
            peer.deactivateAsync().await()
            pollingDoc.close()
            peerDoc.close()
            poller.close()
            peer.close()
        }
    }
}
