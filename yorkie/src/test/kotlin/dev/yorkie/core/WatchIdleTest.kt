package dev.yorkie.core

import android.util.Base64
import dev.yorkie.api.v1.DocEventType
import dev.yorkie.api.v1.WatchResponse
import dev.yorkie.api.v1.docEvent
import dev.yorkie.api.v1.docWatchEvent
import dev.yorkie.api.v1.watchEvent
import dev.yorkie.api.v1.watchHeartbeat
import dev.yorkie.api.v1.watchResponse
import dev.yorkie.document.Document
import dev.yorkie.presence.Channel
import dev.yorkie.util.YorkieException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins the Watch-idle reconnect path (JS SDK `client.ts`, `c69e6bda`/#1472, D5) -- the pure
 * [watchIdleTimeoutMs] plus the restructured receive loop against [MockYorkieService]. JS
 * `unit/client/watch_idle_test.ts`'s 3 pure cases and the applicable reader cases port here by
 * name; the single-timer/stop-timer and Node-`AbortController` cases are N/A on Android (named
 * below, not silently dropped) since there is one sequential reader per stream and no shared
 * timer or abort-signal construct to race.
 */
class WatchIdleTest {

    // --- JS watch_idle_test: the 3 pure `watchIdleTimeout` cases -----------------------

    @Test
    fun `is 0 when the server sends no heartbeats`() {
        assertEquals(0, watchIdleTimeoutMs(0))
        assertEquals(0, watchIdleTimeoutMs(-1))
        assertEquals(0, watchIdleTimeoutMs(-1_000))
    }

    @Test
    fun `is three heartbeat intervals`() {
        assertEquals(6_000, watchIdleTimeoutMs(2_000))
        assertEquals(1_500, watchIdleTimeoutMs(500))
    }

    @Test
    fun `is clamped to 1s and 24h`() {
        assertEquals(1_000, watchIdleTimeoutMs(1), "3ms would be well under the 1s floor")
        assertEquals(
            86_400_000,
            watchIdleTimeoutMs(1_000_000_000),
            "3 billion ms would be well over the 24h cap",
        )
        // CJ-LOW-1 (round 4 fix): heartbeatIntervalMs * 3 used to overflow Long past
        // Long.MAX_VALUE / 3 (~3.07e18) and wrap negative, pinning the result to the 1s
        // floor instead of the intended 24h cap -- the exact opposite of what an
        // absurdly large advertised interval should clamp to.
        assertEquals(
            86_400_000,
            watchIdleTimeoutMs(Long.MAX_VALUE),
            "an overflow-inducing interval must still clamp to the 24h cap, not wrap to 1s",
        )
        assertEquals(
            86_400_000,
            watchIdleTimeoutMs(4_000_000_000_000_000_000L),
            "4e18ms triggered the overflow before this fix (reconnected at 1s instead)",
        )
    }

    // --- Reader cases against MockYorkieService -----------------------------------------

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
     * M6 (team-review, RTCOLLABPLATFORM-789): polls on a real deadline instead of a single
     * fixed `delay` guess for a POSITIVE "this eventually happens" assertion -- a negative
     * "this does NOT happen within this window" assertion still needs a deterministic
     * quiet-period `delay` and is left alone. Same technique as
     * `DocumentTest.awaitCondition`/`ClientPersistenceTest.awaitCondition`.
     */
    private suspend fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) {
                "condition not met within ${timeoutMs}ms"
            }
            delay(20)
        }
    }

    private suspend fun newAttachedClient(
        docKey: String,
        service: MockYorkieService,
    ): Pair<Client, Document> {
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

    @Test
    fun `aborts a stream that goes silent and reconnects it`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            // idle = clamp(300, 1000..) = 1000ms
            service.documentWatchHeartbeatIntervalMsOverride = 100
            service.documentWatchFramePusher = { } // init frame only, then silence
            val (client, document) = newAttachedClient("watch-idle-reconnects", service)

            delay(100)
            assertEquals(1, service.documentWatchConnectCount, "the initial connection")

            waitUntil { service.documentWatchConnectCount >= 2 }

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `keeps a stream alive while heartbeats arrive`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            service.documentWatchHeartbeatIntervalMsOverride = 500 // idle = 1500ms
            service.documentWatchFramePusher = { channel: SendChannel<WatchResponse> ->
                repeat(5) {
                    delay(400) // well under the 1500ms idle timeout
                    channel.trySend(watchResponse { heartbeat = watchHeartbeat {} })
                }
            }
            val (client, document) = newAttachedClient("watch-idle-heartbeats-keep-alive", service)

            delay(2_200) // 5 heartbeats at 400ms, each well inside the 1500ms idle window
            assertEquals(
                1,
                service.documentWatchConnectCount,
                "heartbeats must keep resetting the idle clock, never reconnecting",
            )

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `never times out when the server advertises no heartbeat`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            // No override: proto3 default heartbeat_interval_ms = 0 -- idle timer never arms,
            // the receive timeout stays the existing (minutes-long) streamTimeout.
            service.documentWatchFramePusher = { } // silent, same as an armed stream would be
            val (client, document) = newAttachedClient("watch-idle-interval-zero", service)

            delay(1_500) // well past the 1000ms floor an ARMED stream would have idled at
            assertEquals(
                1,
                service.documentWatchConnectCount,
                "interval 0 must never idle, unlike an armed stream at the same elapsed time",
            )

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `leaves a cancellation the client asked for as it was`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            service.documentWatchHeartbeatIntervalMsOverride = 100 // idle = 1000ms
            service.documentWatchFramePusher = { } // silent
            val (client, document) = newAttachedClient("watch-idle-client-cancel", service)

            delay(100)
            assertEquals(1, service.documentWatchConnectCount)

            // A client-initiated detach cancels the watch job outright, well before the
            // 1000ms idle timeout would otherwise have fired.
            client.detachDocument(document).await()

            delay(1_300) // past when an idle timeout would have reconnected
            assertEquals(
                1,
                service.documentWatchConnectCount,
                "a client-initiated close must not be reported/reconnected as idle",
            )

            client.deactivateAsync().await()
            client.close()
        }
    }

    // --- M6 (team-review, RTCOLLABPLATFORM-789) additions ------------------------------

    @Test
    fun `channel watch aborts a silent stream and reconnects it`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            // idle = clamp(300, 1000..) = 1000ms -- same shape as the document twin above.
            service.channelWatchHeartbeatIntervalMsOverride = 100
            service.channelWatchFramePusher = { } // init frame only, then silence
            val client = newClient(service)
            client.activateAsync().await()
            val channel = Channel("watch-idle-channel-reconnects")
            client.attachChannel(channel, isRealtime = true).await()

            delay(100)
            assertEquals(1, service.channelWatchConnectCount, "the initial connection")

            waitUntil { service.channelWatchConnectCount >= 2 }

            client.detachChannel(channel).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `idle reconnect reports ErrWatchStreamIdle on a stream that advertised a heartbeat`() =
        runTest {
            runBlocking {
                val service = MockYorkieService()
                service.documentWatchHeartbeatIntervalMsOverride = 100 // idle = 1000ms
                service.documentWatchFramePusher = { } // silent
                val client = newClient(service)
                val causes = mutableListOf<Throwable>()
                client.onWatchStreamIdleCause = { _, cause -> causes += cause }
                client.activateAsync().await()
                val document = Document("watch-idle-reports-cause")
                client.attachDocument(
                    document,
                    syncMode = Client.SyncMode.Realtime,
                    disablePresence = true,
                ).await()

                waitUntil { causes.isNotEmpty() }
                assertTrue(
                    causes.first() is YorkieException &&
                        (causes.first() as YorkieException).code ==
                        YorkieException.Code.ErrWatchStreamIdle,
                    "a stream that advertised a positive heartbeat interval must report " +
                        "ErrWatchStreamIdle on idle, not a plain timeout",
                )

                client.detachDocument(document).await()
                client.deactivateAsync().await()
                client.close()
            }
        }

    @Test
    fun `idle reconnect reports a plain TimeoutException when interval is 0`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            // No override: proto3 default heartbeat_interval_ms = 0 -- idleArmed never
            // flips true, so the generic streamTimeout failure stays a TimeoutException.
            service.documentWatchFramePusher = { }
            val client = newClient(service)
            val causes = mutableListOf<Throwable>()
            client.onWatchStreamIdleCause = { _, cause -> causes += cause }
            client.activateAsync().await()
            val document = Document("watch-idle-reports-timeout")
            client.attachDocument(
                document,
                syncMode = Client.SyncMode.Realtime,
                disablePresence = true,
            ).await()

            // Never idles (streamTimeout is minutes-long): just a control that, had this
            // reconnected within the test's window, it would have reported a plain
            // TimeoutException rather than ErrWatchStreamIdle.
            delay(1_500)
            assertEquals(1, service.documentWatchConnectCount)
            assertTrue(causes.isEmpty())

            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun `a slow document event collector never makes an armed watch reconnect as idle`() = runTest {
        runBlocking {
            val service = MockYorkieService()
            service.documentWatchHeartbeatIntervalMsOverride = 300 // idle = 900ms
            service.documentWatchFramePusher = { channel: SendChannel<WatchResponse> ->
                // One real document-changed event for the slow collector to stall on,
                // THEN genuine heartbeats to keep the transport legitimately alive --
                // isolates this test to whether the EVENT's handling (not actual
                // stream silence) is ever misread as idle.
                channel.trySend(
                    watchResponse {
                        event = watchEvent {
                            docEvent = docWatchEvent {
                                this.documentId = "watch-idle-slow-collector"
                                event = docEvent {
                                    type =
                                        DocEventType.DOC_EVENT_TYPE_DOCUMENT_CHANGED
                                    publisher = "other"
                                }
                            }
                        }
                    },
                )
                repeat(6) {
                    delay(300) // well under the 900ms idle window
                    channel.trySend(watchResponse { heartbeat = watchHeartbeat {} })
                }
            }
            val (client, document) = newAttachedClient("watch-idle-slow-collector", service)
            val job = CoroutineScope(Dispatchers.IO).launch {
                document.events.collect { delay(1_500) } // D5: a stalled collector
            }

            delay(100)
            assertEquals(1, service.documentWatchConnectCount, "the initial connection")

            // Past the 1000ms idle window, with the collector still mid-stall on the
            // one event the pusher sent -- D5 scopes the idle timeout to the receive
            // call alone, so handler/collector suspension must never be misread as
            // the stream going silent.
            delay(1_200)
            assertEquals(
                1,
                service.documentWatchConnectCount,
                "a slow event collector must not make an armed watch reconnect as idle",
            )

            job.cancel()
            client.detachDocument(document).await()
            client.deactivateAsync().await()
            client.close()
        }
    }
}
