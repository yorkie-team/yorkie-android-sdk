package dev.yorkie.core

import android.util.Base64
import dev.yorkie.core.MockYorkieService.Companion.TEST_KEY
import dev.yorkie.document.Document
import dev.yorkie.presence.Channel
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins `Options.watchHeartbeatInterval` and the `WatchRequest.heartbeat_interval_ms` it feeds on
 * both the document and the channel Watch request (JS SDK `client.ts`'s `toHeartbeatIntervalMs`,
 * `c1a03877`/#1483). JS `unit/client/watch_heartbeat_request_test.ts`'s `it.for([document,
 * channel])` x 6 cases ports here as 12 named cases below (NaN is N/A -- Kotlin [Duration] has
 * no NaN representation); the server's settled interval (read from the init frame) is
 * [WatchIdleTest]'s concern, not this file's.
 */
class WatchHeartbeatRequestTest {

    private enum class Kind { Document, Channel }

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

    private fun newClient(service: MockYorkieService, interval: Duration?): Client {
        val options = if (interval == null) {
            Client.Options(key = TEST_KEY, apiKey = TEST_KEY)
        } else {
            Client.Options(key = TEST_KEY, apiKey = TEST_KEY, watchHeartbeatInterval = interval)
        }
        val client = Client(host = "0.0.0.0", options = options)
        client.service = service
        return client
    }

    private suspend fun <T> awaitCondition(timeoutMs: Long = 5_000, block: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            block()?.let { return it }
            check(
                System.currentTimeMillis() < deadline,
            ) { "condition not met within ${timeoutMs}ms" }
            withContext(Dispatchers.Default) { delay(20) }
        }
    }

    /**
     * Opens a [kind] Watch stream on a client built with [interval] and returns the
     * `heartbeat_interval_ms` its request carried. Mirrors the JS helper `requestedInterval`.
     */
    private suspend fun requestedInterval(kind: Kind, interval: Duration?): Long {
        val service = MockYorkieService()
        val client = newClient(service, interval)
        client.activateAsync().await()

        return when (kind) {
            Kind.Document -> {
                val document = Document("watch-heartbeat-request-doc")
                client.attachDocument(document, disablePresence = true).await()
                val result = awaitCondition { service.lastDocumentWatchHeartbeatIntervalMs }
                client.detachDocument(document).await()
                result
            }
            Kind.Channel -> {
                val channel = Channel("watch-heartbeat-request-channel")
                client.attachChannel(channel, isRealtime = true).await()
                val result = awaitCondition { service.lastChannelWatchHeartbeatIntervalMs }
                client.detachChannel(channel).await()
                result
            }
        }.also {
            client.deactivateAsync().await()
            client.close()
        }
    }

    // --- document Watch request ------------------------------------------------------------

    @Test
    fun `document asks for the default interval when none is configured`() = runTest {
        assertEquals(20_000L, requestedInterval(Kind.Document, interval = null))
    }

    @Test
    fun `document asks for the configured interval`() = runTest {
        assertEquals(7_000L, requestedInterval(Kind.Document, interval = 7_000.milliseconds))
    }

    @Test
    fun `document asks for nothing when the interval is 0`() = runTest {
        assertEquals(0L, requestedInterval(Kind.Document, interval = ZERO))
    }

    @Test
    fun `document reads a negative interval as asking for nothing`() = runTest {
        // NaN is N/A on Android -- kotlin.time.Duration has no NaN representation.
        assertEquals(0L, requestedInterval(Kind.Document, interval = (-1).milliseconds))
    }

    @Test
    fun `document caps an interval too large for an int64`() = runTest {
        assertEquals(
            9_007_199_254_740_991L,
            requestedInterval(Kind.Document, interval = Duration.INFINITE),
        )
    }

    @Test
    fun `document rounds a fractional interval up instead of opting out`() = runTest {
        // 0.5ms would truncate to 0 (opt out) via inWholeMilliseconds; ceil keeps it a request
        // the server can clamp to its own minimum instead.
        assertEquals(1L, requestedInterval(Kind.Document, interval = 500.microseconds))
    }

    // --- channel Watch request -------------------------------------------------------------

    @Test
    fun `channel asks for the default interval when none is configured`() = runTest {
        assertEquals(20_000L, requestedInterval(Kind.Channel, interval = null))
    }

    @Test
    fun `channel asks for the configured interval`() = runTest {
        assertEquals(7_000L, requestedInterval(Kind.Channel, interval = 7_000.milliseconds))
    }

    @Test
    fun `channel asks for nothing when the interval is 0`() = runTest {
        assertEquals(0L, requestedInterval(Kind.Channel, interval = ZERO))
    }

    @Test
    fun `channel reads a negative interval as asking for nothing`() = runTest {
        assertEquals(0L, requestedInterval(Kind.Channel, interval = (-1).milliseconds))
    }

    @Test
    fun `channel caps an interval too large for an int64`() = runTest {
        assertEquals(
            9_007_199_254_740_991L,
            requestedInterval(Kind.Channel, interval = Duration.INFINITE),
        )
    }

    @Test
    fun `channel rounds a fractional interval up instead of opting out`() = runTest {
        assertEquals(1L, requestedInterval(Kind.Channel, interval = 500.microseconds))
    }

    // --- pure conversion, Android-only (directly unit-testable without a Client) ------------

    @Test
    fun `toHeartbeatIntervalMs is pure and matches the wired requests`() {
        assertEquals(20_000L, toHeartbeatIntervalMs(20_000.milliseconds))
        assertEquals(7_000L, toHeartbeatIntervalMs(7_000.milliseconds))
        assertEquals(0L, toHeartbeatIntervalMs(ZERO))
        assertEquals(0L, toHeartbeatIntervalMs((-1).milliseconds))
        assertEquals(9_007_199_254_740_991L, toHeartbeatIntervalMs(Duration.INFINITE))
        assertEquals(1L, toHeartbeatIntervalMs(500.microseconds))
        assertEquals(1_501L, toHeartbeatIntervalMs(1_500_200.microseconds))
    }
}
