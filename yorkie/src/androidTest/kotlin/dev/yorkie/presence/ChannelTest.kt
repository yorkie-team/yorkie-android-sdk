package dev.yorkie.presence

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.yorkie.core.GENERAL_TIMEOUT
import dev.yorkie.core.ResourceStatus
import dev.yorkie.core.createClient
import dev.yorkie.core.toDocKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelTest {

    @Test
    fun test_single_client_channel_counter() {
        runBlocking {
            val c1 = createClient()
            c1.activateAsync().await()

            // Create channel counter
            val channelKey = "channel-${UUID.randomUUID()}".toDocKey()
            val channel = Channel(channelKey)

            // Test initial state
            assertEquals(channelKey, channel.getKey())
            assertEquals(ResourceStatus.Detached, channel.getStatus())
            assertFalse(channel.isAttached())
            assertEquals(0L, channel.getCount())

            // Attach channel counter
            c1.attachChannel(channel).await()

            // Verify attached state
            assertEquals(ResourceStatus.Attached, channel.getStatus())
            assertTrue(channel.isAttached())
            assertEquals(1L, channel.getCount())

            // Detach channel counter
            c1.detachChannel(channel).await()

            // Verify detached state
            assertEquals(ResourceStatus.Detached, channel.getStatus())
            assertFalse(channel.isAttached())

            c1.deactivateAsync().await()
            c1.close()
        }
    }

    @Test
    fun test_multiple_clients_channel_counter() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val c3 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()
            c3.activateAsync().await()

            // Create channel counters for the same room
            val channelKey = "channel-room-${UUID.randomUUID()}".toDocKey()
            val channel1 = Channel(channelKey)
            val channel2 = Channel(channelKey)
            val channel3 = Channel(channelKey)

            // First client attaches
            c1.attachChannel(channel1).await()
            assertEquals(1L, channel1.getCount())

            // Second client attaches
            c2.attachChannel(channel2).await()
            assertEquals(2L, channel2.getCount())

            // First client should receive the update
            withTimeout(GENERAL_TIMEOUT) {
                while (channel1.getCount() != 2L) {
                    delay(50)
                }
            }
            assertEquals(2L, channel1.getCount())

            // Third client attaches
            c3.attachChannel(channel3).await()
            assertEquals(3L, channel3.getCount())

            // Wait for all clients to sync
            withTimeout(GENERAL_TIMEOUT) {
                while (channel1.getCount() != 3L || channel2.getCount() != 3L) {
                    delay(50)
                }
            }
            assertEquals(3L, channel1.getCount())
            assertEquals(3L, channel2.getCount())

            // One client detaches
            c2.detachChannel(channel2).await()
            assertEquals(2L, channel2.getCount())

            // Other clients should see the count decrease
            withTimeout(GENERAL_TIMEOUT) {
                while (channel1.getCount() != 2L || channel3.getCount() != 2L) {
                    delay(50)
                }
            }
            assertEquals(2L, channel1.getCount())
            assertEquals(2L, channel3.getCount())

            // Cleanup
            c1.detachChannel(channel1).await()
            c3.detachChannel(channel3).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c3.deactivateAsync().await()
            c1.close()
            c2.close()
            c3.close()
        }
    }

    @Test
    fun test_channel_counter_event_subscription() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val channelKey = "channel-events-${UUID.randomUUID()}".toDocKey()
            val channel1 = Channel(channelKey)
            val channel2 = Channel(channelKey)

            // Track events on channel1
            val events = mutableListOf<ChannelEvent>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                channel1.eventStream.collect { event ->
                    events.add(event)
                }
            }

            // First client attaches
            c1.attachChannel(channel1).await()
            withTimeout(GENERAL_TIMEOUT) {
                while (events.isEmpty()) {
                    delay(50)
                }
            }

            // Should receive initialized event
            assertIs<ChannelEvent.Initialized>(events[0])
            assertEquals(1L, events[0].count)

            // Second client attaches
            c2.attachChannel(channel2).await()
            withTimeout(GENERAL_TIMEOUT) {
                while (events.size < 2) {
                    delay(50)
                }
            }

            // Should receive count-changed event
            assertIs<ChannelEvent.Changed>(events.last())
            assertEquals(2L, events.last().count)

            // Cleanup
            collectJob.cancel()
            c1.detachChannel(channel1).await()
            c2.detachChannel(channel2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_channel_counter_detach_reduces_count() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val channelKey = "channel-detach-${UUID.randomUUID()}".toDocKey()
            val channel1 = Channel(channelKey)
            val channel2 = Channel(channelKey)

            // Both attach
            c1.attachChannel(channel1).await()
            c2.attachChannel(channel2).await()

            withTimeout(GENERAL_TIMEOUT) {
                while (channel1.getCount() != 2L || channel2.getCount() != 2L) {
                    delay(50)
                }
            }
            assertEquals(2L, channel1.getCount())
            assertEquals(2L, channel2.getCount())

            // One detaches
            c1.detachChannel(channel1).await()
            assertEquals(1L, channel1.getCount())

            // Other channel should also see the decrease
            withTimeout(GENERAL_TIMEOUT) {
                while (channel2.getCount() != 1L) {
                    delay(50)
                }
            }
            assertEquals(1L, channel2.getCount())

            // Cleanup
            c2.detachChannel(channel2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_channel_heartbeat_keeps_session_alive() {
        runBlocking {
            // 1 second heartbeat interval for faster testing
            val c1 = createClient(
                options = dev.yorkie.core.Client.Options(
                    channelHeartbeatInterval = 1000.milliseconds,
                ),
            )
            c1.activateAsync().await()

            val channelKey = "channel-heartbeat-${UUID.randomUUID()}".toDocKey()
            val channel = Channel(channelKey)

            // Attach channel counter
            c1.attachChannel(channel).await()
            assertEquals(1L, channel.getCount())

            // Wait for 3 heartbeat cycles (3 seconds)
            // The channel should still be active because heartbeat refreshes TTL
            delay(3500)

            // Verify channel is still active
            assertTrue(channel.isAttached())
            assertEquals(1L, channel.getCount())

            // Cleanup
            c1.detachChannel(channel).await()
            c1.deactivateAsync().await()
            c1.close()
        }
    }

    @Test
    fun test_channel_manual_sync_mode() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()

            // Create channels for the same room
            val channelKey = "channel-manual-${UUID.randomUUID()}".toDocKey()
            val p1 = Channel(channelKey)
            val p2 = Channel(channelKey)

            // Attach client1 with manual sync mode (no watch stream)
            c1.attachChannel(p1, isRealtime = false).await()
            assertEquals(1L, p1.getCount())

            // Attach client2 with manual sync mode
            c2.attachChannel(p2, isRealtime = false).await()
            assertEquals(2L, p2.getCount())

            // In manual mode, p1's count doesn't update automatically
            // even though p2 was attached
            delay(500)
            assertEquals(1L, p1.getCount())

            // Must call syncAsync() explicitly to refresh TTL and fetch latest count
            c1.syncAsync(p1).await()
            assertEquals(2L, p1.getCount())

            // Detach p2 and verify p1 doesn't auto-update
            c2.detachChannel(p2).await()
            delay(500)
            assertEquals(2L, p1.getCount())

            // Sync to refresh TTL and fetch latest count after c2 detached
            c1.syncAsync(p1).await()
            assertEquals(1L, p1.getCount())

            // Cleanup
            c1.detachChannel(p1).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_channel_realtime_vs_manual_mode_comparison() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val c3 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()
            c3.activateAsync().await()

            val channelKey = "channel-mode-compare-${UUID.randomUUID()}".toDocKey()
            val realtimeChannel = Channel(channelKey)
            val manualChannel = Channel(channelKey)
            val thirdChannel = Channel(channelKey)

            // c1: Attach with realtime mode (default)
            c1.attachChannel(realtimeChannel).await()
            assertEquals(1L, realtimeChannel.getCount())

            // c2: Attach with manual mode
            c2.attachChannel(manualChannel, isRealtime = false).await()
            assertEquals(2L, manualChannel.getCount())

            // c1's realtime channel should automatically receive the update
            withTimeout(GENERAL_TIMEOUT) {
                while (realtimeChannel.getCount() != 2L) {
                    delay(50)
                }
            }
            assertEquals(2L, realtimeChannel.getCount())

            // c2's manual channel doesn't receive updates
            assertEquals(2L, manualChannel.getCount())

            // c3: Attach another client
            c3.attachChannel(thirdChannel, isRealtime = false).await()
            assertEquals(3L, thirdChannel.getCount())

            // c1's realtime channel receives the update automatically
            withTimeout(GENERAL_TIMEOUT) {
                while (realtimeChannel.getCount() != 3L) {
                    delay(50)
                }
            }
            assertEquals(3L, realtimeChannel.getCount())

            // c2's manual channel still doesn't update
            assertEquals(2L, manualChannel.getCount())

            // Cleanup
            c1.detachChannel(realtimeChannel).await()
            c2.detachChannel(manualChannel).await()
            c3.detachChannel(thirdChannel).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c3.deactivateAsync().await()
            c1.close()
            c2.close()
            c3.close()
        }
    }

    @Test
    fun test_channel_broadcast_received_by_subscriber() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val channelKey = "channel-broadcast-${UUID.randomUUID()}".toDocKey()
            val ch1 = Channel(channelKey)
            val ch2 = Channel(channelKey)

            // Collect all events on ch1 (including initialized) to confirm stream is ready
            val allEvents = mutableListOf<ChannelEvent>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                ch1.eventStream.collect { allEvents.add(it) }
            }

            c1.attachChannel(ch1).await()

            // Wait for ch1's watch stream to be established (initialized event)
            withTimeout(GENERAL_TIMEOUT) {
                while (allEvents.none { it is ChannelEvent.Initialized }) {
                    delay(50)
                }
            }

            c2.attachChannel(ch2).await()

            // Wait for ch1 to receive the count change from ch2 joining
            withTimeout(GENERAL_TIMEOUT) {
                while (allEvents.none { it is ChannelEvent.Changed }) {
                    delay(50)
                }
            }

            // c2 broadcasts on the channel key
            c2.broadcast(channelKey, "hello", "world").await()

            withTimeout(GENERAL_TIMEOUT) {
                while (allEvents.none { it is ChannelEvent.Broadcast }) {
                    delay(50)
                }
            }

            val broadcastEvents = allEvents.filterIsInstance<ChannelEvent.Broadcast>()

            assertEquals(1, broadcastEvents.size)
            assertEquals("hello", broadcastEvents[0].topic)
            assertEquals("world", broadcastEvents[0].payload)

            collectJob.cancel()
            c1.detachChannel(ch1).await()
            c2.detachChannel(ch2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_channel_broadcast_does_not_arrive_on_document_event_stream() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val channelKey = "channel-broadcast-isolation-${UUID.randomUUID()}".toDocKey()
            val docKey = "doc-broadcast-isolation-${UUID.randomUUID()}".toDocKey()
            val ch1 = Channel(channelKey)
            val d2 = dev.yorkie.document.Document(docKey)

            c1.attachChannel(ch1).await()
            c2.attachDocument(d2).await()
            delay(200)

            val docBroadcastEvents =
                mutableListOf<dev.yorkie.document.Document.Event.Broadcast>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                d2.events
                    .filterIsInstance<dev.yorkie.document.Document.Event.Broadcast>()
                    .collect { docBroadcastEvents.add(it) }
            }

            // c1 broadcasts on channel key — must NOT reach d2
            c1.broadcast(channelKey, "test-topic", "test-payload").await()
            delay(500)

            assertTrue(
                docBroadcastEvents.isEmpty(),
                "Document event stream must not receive a channel-scoped broadcast",
            )

            collectJob.cancel()
            c1.detachChannel(ch1).await()
            c2.detachDocument(d2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }
}
