package dev.yorkie.core

import android.util.Base64
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchResponse
import dev.yorkie.api.v1.docEvent
import dev.yorkie.api.v1.docWatchEvent
import dev.yorkie.api.v1.watchEvent
import dev.yorkie.api.v1.watchResponse
import dev.yorkie.document.Document
import dev.yorkie.document.Document.Event.PresenceChanged.Others
import dev.yorkie.document.change.Change
import dev.yorkie.document.change.ChangeID
import dev.yorkie.document.change.ChangePack
import dev.yorkie.document.change.CheckPoint
import dev.yorkie.document.presence.PresenceChange
import dev.yorkie.document.time.VersionVector
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins JS `c41ab014` #1186: a peer's presence must survive this client's OWN watch stream
 * reconnecting, through the REAL [Client.handleWatchDocumentResponse] against
 * [MockYorkieService] -- a Document-only reproduction would not go RED on the `Client.kt` hunk
 * this pins (the prune loop removed from the watch-init branch).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchPresenceRetentionTest {

    private val actorB = "000000000000000000000b02"
    private val docKey = "watch-presence-retention"

    @Before
    fun setUp() {
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Base64::class)
    }

    private fun newClient(service: MockYorkieService): Client {
        val client = Client(
            host = "0.0.0.0",
            options = Client.Options(
                key = MockYorkieService.TEST_KEY,
                apiKey = MockYorkieService.TEST_KEY,
            ),
        )
        client.service = service
        return client
    }

    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "condition not met within ${timeoutMs}ms"
            }
            delay(20)
        }
    }

    private suspend fun newAttachedClient(service: MockYorkieService): Pair<Client, Document> {
        val client = newClient(service)
        client.activateAsync().await()
        val document = Document(docKey)
        client.attachDocument(
            document,
            syncMode = Client.SyncMode.Realtime,
            disablePresence = true,
        ).await()
        return client to document
    }

    /** Seeds [actorB]'s presence into [document] as if it arrived through an earlier sync,
     * without going through this client's own watch stream. */
    private suspend fun seedPeerPresence(document: Document, presence: Map<String, String>) {
        document.applyChangePack(
            ChangePack(
                document.getKey(),
                CheckPoint(1, 0u),
                listOf(
                    Change(
                        ChangeID(1u, 1, actorB, VersionVector()),
                        emptyList(),
                        PresenceChange.Put(presence),
                    ),
                ),
                null,
                false,
                VersionVector(),
            ),
        )
    }

    private fun watchedEvent(publisher: String) = watchResponse {
        event = watchEvent {
            docEvent = docWatchEvent {
                this.documentId = docKey
                event = docEvent {
                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_WATCHED
                    this.publisher = publisher
                }
            }
        }
    }

    @Test
    fun `retains a peer after a watch init that omits it`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            // idle = clamp(300, 1000..) = 1000ms, same shape as WatchIdleTest.
            service.documentWatchHeartbeatIntervalMsOverride = 100
            var connectionsSeen = 0
            service.documentWatchFramePusher = { channel: SendChannel<WatchResponse> ->
                connectionsSeen++
                if (connectionsSeen == 1) {
                    // Connection 1: init only, then silence -- the idle timer below
                    // reconnects it, well after the test has seeded actorB.
                } else {
                    // Connection 2 (the reconnect): actorB's own watch stream also
                    // reconnects, as a DOCUMENT_WATCHED.
                    delay(50)
                    channel.trySend(watchedEvent(actorB))
                }
            }
            val (client, document) = newAttachedClient(service)

            // actorB was already online and visible, as if synced and watched earlier --
            // before THIS client's own watch stream blips. `presences` is a `combine`d
            // StateFlow recomputed on the document's own dispatcher, asynchronous relative
            // to this call, so its visibility is polled rather than read immediately.
            seedPeerPresence(document, mapOf("name" to "b"))
            document.addOnlineClient(actorB)
            waitUntil { document.presences.value.containsKey(actorB) }

            // The reconnect's watch-init omits actorB (its stream blipped at the same time).
            service.watchInitClientIdsOverride = emptyList()

            val events = mutableListOf<Document.Event>()
            val collectJob = launch(UnconfinedTestDispatcher()) {
                document.events.collect(events::add)
            }

            // The idle timeout on connection 1 (before connection 2 even opens) already
            // drops actorB's online flag -- not exposed once offline...
            waitUntil(timeoutMs = 3_000) { !document.presences.value.containsKey(actorB) }
            // ...but retained rather than pruned, so the later Watched below can fire.
            assertEquals(mapOf("name" to "b"), document.allPresences.value[actorB])

            waitUntil { events.any { it is Others.Watched } }
            assertTrue(document.presences.value.containsKey(actorB))
            assertEquals(mapOf("name" to "b"), document.presences.value[actorB])

            collectJob.cancel()
            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }
}
