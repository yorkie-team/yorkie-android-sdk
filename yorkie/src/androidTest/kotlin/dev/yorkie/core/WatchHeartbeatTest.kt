package dev.yorkie.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.document.Document
import dev.yorkie.presence.Channel
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented port of yorkie-js-sdk's `integration/watch_heartbeat_test.ts` (`c1a03877`/#1483),
 * the 3 cases, against a real Yorkie server (compose pin `yorkieteam/yorkie:0.7.25`, the minimum
 * that speaks the per-stream heartbeat, yorkie#2209). Nothing in the public API surfaces the
 * server's advertised `WatchInitialization.heartbeat_interval_ms` or a raw `WatchHeartbeat`
 * frame's arrival -- both are consumed purely to drive the internal idle-reconnect clock -- so
 * this reads them through [Client.onWatchStreamInitAdvertisedHeartbeatIntervalMs] /
 * [Client.onWatchHeartbeatReceived], a minimal `@VisibleForTesting` seam added alongside this
 * class (null, zero overhead, in production).
 *
 * Each case `Assume`s the server actually advertised a positive interval: a server image that
 * predates #2209 ignores the request field entirely and advertises its own global setting (`0`
 * by default), which these tests cannot exercise meaningfully -- mirrors the JS fixture's own
 * `skipReason` function rather than failing on an old server.
 */
@RunWith(AndroidJUnit4::class)
class WatchHeartbeatTest {

    // A stream advertised more than this would outlast the test waiting for its first
    // heartbeat; a server tuned that high has nothing to prove here (mirrors JS's
    // MaxWaitableIntervalMs).
    private val maxWaitableIntervalMs = 20_000L

    // How long a stream that asked for no heartbeat is watched for one -- longer than a
    // typical server minimum, so a server heartbeating it anyway would show up (mirrors JS's
    // NoHeartbeatWindowMs).
    private val noHeartbeatWindowMs = 8_000L

    private fun newDocKey(name: String) = "$name-${UUID.randomUUID()}".toDocKey()

    private suspend fun <T> awaitCondition(timeoutMs: Long = 5_000, block: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            block()?.let { return it }
            check(
                System.currentTimeMillis() < deadline,
            ) { "condition not met within ${timeoutMs}ms" }
            kotlinx.coroutines.delay(50)
        }
    }

    @Test
    fun advertises_the_clamped_interval_on_a_document_stream_and_heartbeats_it() = runBlocking {
        val advertised = AtomicLong(-1)
        val heartbeats = AtomicInteger(0)
        val client = createClient(
            Client.Options(watchHeartbeatInterval = 1_000.milliseconds),
        )
        client.onWatchStreamInitAdvertisedHeartbeatIntervalMs = { isDocument, intervalMs ->
            if (isDocument) advertised.set(intervalMs)
        }
        client.onWatchHeartbeatReceived = { isDocument ->
            if (isDocument) heartbeats.incrementAndGet()
        }
        client.activateAsync().await()
        val document = Document(newDocKey("watch-heartbeat-document"))
        try {
            client.attachDocument(document, disablePresence = true).await()

            val interval = awaitCondition(10_000) { advertised.get().takeIf { it >= 0 } }
            assumeTrue(
                "the server sends no heartbeats; it predates the per-stream heartbeat " +
                    "interval (yorkie-team/yorkie#2209)",
                interval > 0,
            )
            assumeTrue(
                "the server advertised ${interval}ms, too long to wait for a heartbeat here",
                interval <= maxWaitableIntervalMs,
            )

            awaitCondition(interval * 2 + 5_000) { heartbeats.get().takeIf { it > 0 } }

            client.detachDocument(document).await().getOrThrow()
        } finally {
            // M10 (team-review, RTCOLLABPLATFORM-789): an assumeTrue failure above throws
            // AssumptionViolatedException and skips the rest of this test body -- without
            // this finally, the client/document would leak (never detached/deactivated/
            // closed) on every server that predates #2209.
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun advertises_the_clamped_interval_on_a_channel_stream_and_heartbeats_it() = runBlocking {
        val advertised = AtomicLong(-1)
        val heartbeats = AtomicInteger(0)
        val client = createClient(
            Client.Options(watchHeartbeatInterval = 1_000.milliseconds),
        )
        client.onWatchStreamInitAdvertisedHeartbeatIntervalMs = { isDocument, intervalMs ->
            if (!isDocument) advertised.set(intervalMs)
        }
        client.onWatchHeartbeatReceived = { isDocument ->
            if (!isDocument) heartbeats.incrementAndGet()
        }
        client.activateAsync().await()
        val channel = Channel(newDocKey("watch-heartbeat-channel"))
        try {
            client.attachChannel(channel, isRealtime = true).await()

            val interval = awaitCondition(10_000) { advertised.get().takeIf { it >= 0 } }
            assumeTrue(
                "the server sends no heartbeats; it predates the per-stream heartbeat " +
                    "interval (yorkie-team/yorkie#2209)",
                interval > 0,
            )
            assumeTrue(
                "the server advertised ${interval}ms, too long to wait for a heartbeat here",
                interval <= maxWaitableIntervalMs,
            )

            awaitCondition(interval * 2 + 5_000) { heartbeats.get().takeIf { it > 0 } }

            client.detachChannel(channel).await().getOrThrow()
        } finally {
            // M10: same leak guard as the document case above.
            client.deactivateAsync().await()
            client.close()
        }
    }

    @Test
    fun sends_no_heartbeat_to_a_stream_that_asked_for_none() = runBlocking {
        val advertised = AtomicLong(-1)
        val heartbeats = AtomicInteger(0)
        val client = createClient(
            Client.Options(watchHeartbeatInterval = Duration.ZERO),
        )
        client.onWatchStreamInitAdvertisedHeartbeatIntervalMs = { isDocument, intervalMs ->
            if (isDocument) advertised.set(intervalMs)
        }
        client.onWatchHeartbeatReceived = { isDocument ->
            if (isDocument) heartbeats.incrementAndGet()
        }
        client.activateAsync().await()
        val document = Document(newDocKey("watch-heartbeat-none"))
        try {
            client.attachDocument(document, disablePresence = true).await()

            val interval = awaitCondition(10_000) { advertised.get().takeIf { it >= 0 } }
            assumeTrue(
                "the server advertised ${interval}ms to a stream that asked for none; it " +
                    "predates yorkie-team/yorkie#2209",
                interval == 0L,
            )

            // Longer than a typical server minimum interval, so a heartbeat would have
            // arrived had the server ignored the opt-out.
            kotlinx.coroutines.delay(noHeartbeatWindowMs)
            kotlin.test.assertEquals(0, heartbeats.get())

            client.detachDocument(document).await().getOrThrow()
        } finally {
            // M10: same leak guard as the two cases above.
            client.deactivateAsync().await()
            client.close()
        }
    }
}
