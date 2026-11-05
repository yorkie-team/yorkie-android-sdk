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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins JS `c41ab014` #1186 and `97f0aa01` #1501: a peer's presence must survive a
 * watch stream reconnection or an unwatch/watch cycle, through the REAL
 * [Client.handleWatchDocumentResponse] against [MockYorkieService] -- a Document-only
 * reproduction would not go RED on the `Client.kt` hunks these pin (the prune loop removed from
 * the watch-init branch, the `clearPresence` call dropped from `DOCUMENT_UNWATCHED`).
 */
// Generous relative to the real timers under test (a clamp(300,1000..)=1000ms idle
// timeout, 100ms heartbeat, 50-100ms simulated network delay in the mock pushers
// below): wide enough to absorb scheduler contention from the rest of the suite
// running concurrently without weakening what a timeout here actually proves -- the
// watch stream genuinely never recovered the peer's presence.
private const val GENEROUS_TIMEOUT_MS = 15_000L

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

    /**
     * Awaits [flow] reaching a value matching [predicate], reacting to the StateFlow's
     * own emissions instead of polling it on a fixed interval -- under load, a fixed
     * poll interval both reacts late and adds its own scheduling pressure.
     */
    private suspend fun <T> awaitState(
        flow: StateFlow<T>,
        timeoutMs: Long = GENEROUS_TIMEOUT_MS,
        predicate: (T) -> Boolean,
    ) {
        withTimeout(timeoutMs) { flow.first(predicate) }
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

    private fun unwatchedEvent(publisher: String) = watchResponse {
        event = watchEvent {
            docEvent = docWatchEvent {
                this.documentId = docKey
                event = docEvent {
                    type = DocEventType.DOC_EVENT_TYPE_DOCUMENT_UNWATCHED
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
            // to this call, so its visibility is awaited rather than read immediately.
            seedPeerPresence(document, mapOf("name" to "b"))
            document.addOnlineClient(actorB)
            awaitState(document.presences) { it.containsKey(actorB) }

            // The reconnect's watch-init omits actorB (its stream blipped at the same time).
            service.watchInitClientIdsOverride = emptyList()

            val watchedSignal = CompletableDeferred<Unit>()
            val collectJob = launch(UnconfinedTestDispatcher()) {
                document.events.collect { event ->
                    if (event is Others.Watched) watchedSignal.complete(Unit)
                }
            }

            // The idle timeout on connection 1 (before connection 2 even opens) already
            // drops actorB's online flag -- not exposed once offline...
            awaitState(document.presences) { !it.containsKey(actorB) }
            // ...but retained rather than pruned, so the later Watched below can fire.
            assertEquals(mapOf("name" to "b"), document.allPresences.value[actorB])

            withTimeout(GENEROUS_TIMEOUT_MS) { watchedSignal.await() }
            assertTrue(document.presences.value.containsKey(actorB))
            assertEquals(mapOf("name" to "b"), document.presences.value[actorB])

            collectJob.cancel()
            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `retains a peer across an unwatch slash watch cycle`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            service.documentWatchFramePusher = { channel: SendChannel<WatchResponse> ->
                delay(100)
                channel.trySend(unwatchedEvent(actorB))
                delay(100)
                channel.trySend(watchedEvent(actorB))
            }
            val (client, document) = newAttachedClient(service)

            seedPeerPresence(document, mapOf("name" to "b"))
            document.addOnlineClient(actorB)
            awaitState(document.presences) { it.containsKey(actorB) }

            val unwatchedSignal = CompletableDeferred<Unit>()
            val watchedSignal = CompletableDeferred<Unit>()
            val collectJob = launch(UnconfinedTestDispatcher()) {
                document.events.collect { event ->
                    when (event) {
                        is Others.Unwatched -> unwatchedSignal.complete(Unit)
                        is Others.Watched -> watchedSignal.complete(Unit)
                        else -> Unit
                    }
                }
            }

            withTimeout(GENEROUS_TIMEOUT_MS) { unwatchedSignal.await() }
            assertFalse(
                document.presences.value.containsKey(actorB),
                "an offline retained peer must never be exposed",
            )
            assertEquals(
                mapOf("name" to "b"),
                document.allPresences.value[actorB],
                "unwatch must keep the presence, only drop the online flag",
            )

            withTimeout(GENEROUS_TIMEOUT_MS) { watchedSignal.await() }
            assertTrue(document.presences.value.containsKey(actorB))
            assertEquals(mapOf("name" to "b"), document.presences.value[actorB])

            collectJob.cancel()
            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `unwatch of a publisher with no held presence is a no-op`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            service.documentWatchFramePusher = { channel: SendChannel<WatchResponse> ->
                delay(50)
                channel.trySend(unwatchedEvent(actorB))
            }
            val (client, document) = newAttachedClient(service)

            // actorB is online but its presence never arrived -- `presences.value[publisher]`
            // misses, so the early return in the DOCUMENT_UNWATCHED branch must fire without
            // publishing an Unwatched event or touching allPresences.
            document.addOnlineClient(actorB)

            val events = mutableListOf<Document.Event>()
            val collectJob = launch(UnconfinedTestDispatcher()) {
                document.events.collect(events::add)
            }

            delay(500) // quiet-period: nothing must fire for an un-held publisher.
            assertTrue(events.none { it is Others.Unwatched })
            assertFalse(document.allPresences.value.containsKey(actorB))

            collectJob.cancel()
            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }
}
