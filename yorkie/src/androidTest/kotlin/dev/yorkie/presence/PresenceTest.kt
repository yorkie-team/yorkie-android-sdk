package dev.yorkie.presence

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresenceTest {

    @Test
    fun test_single_client_presence_counter() {
        runBlocking {
            val c1 = createClient()
            c1.activateAsync().await()

            // Create presence counter
            val presenceKey = "presence-${UUID.randomUUID()}".toDocKey()
            val presence = Presence(presenceKey)

            // Test initial state
            assertEquals(presenceKey, presence.getKey())
            assertEquals(ResourceStatus.Detached, presence.getStatus())
            assertFalse(presence.isAttached())
            assertEquals(0L, presence.getCount())

            // Attach presence counter
            c1.attachPresence(presence).await()

            // Verify attached state
            assertEquals(ResourceStatus.Attached, presence.getStatus())
            assertTrue(presence.isAttached())
            assertEquals(1L, presence.getCount())

            // Detach presence counter
            c1.detachPresence(presence).await()

            // Verify detached state
            assertEquals(ResourceStatus.Detached, presence.getStatus())
            assertFalse(presence.isAttached())

            c1.deactivateAsync().await()
            c1.close()
        }
    }

    @Test
    fun test_multiple_clients_presence_counter() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val c3 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()
            c3.activateAsync().await()

            // Create presence counters for the same room
            val presenceKey = "presence-room-${UUID.randomUUID()}".toDocKey()
            val presence1 = Presence(presenceKey)
            val presence2 = Presence(presenceKey)
            val presence3 = Presence(presenceKey)

            // First client attaches
            c1.attachPresence(presence1).await()
            delay(100)
            assertEquals(1L, presence1.getCount())

            // Second client attaches
            c2.attachPresence(presence2).await()
            delay(100)
            assertEquals(2L, presence2.getCount())

            // First client should receive the update
            delay(100)
            assertEquals(2L, presence1.getCount())

            // Third client attaches
            c3.attachPresence(presence3).await()
            delay(100)
            assertEquals(3L, presence3.getCount())

            // Wait for all clients to sync
            delay(100)
            assertEquals(3L, presence1.getCount())
            assertEquals(3L, presence2.getCount())

            // One client detaches
            c2.detachPresence(presence2).await()
            delay(100)
            assertEquals(2L, presence2.getCount())

            // Other clients should see the count decrease
            delay(100)
            assertEquals(2L, presence1.getCount())
            assertEquals(2L, presence3.getCount())

            // Cleanup
            c1.detachPresence(presence1).await()
            c3.detachPresence(presence3).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c3.deactivateAsync().await()
            c1.close()
            c2.close()
            c3.close()
        }
    }

    @Test
    fun test_presence_counter_event_subscription() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val presenceKey = "presence-events-${UUID.randomUUID()}".toDocKey()
            val presence1 = Presence(presenceKey)
            val presence2 = Presence(presenceKey)

            // Track events on presence1
            val events = mutableListOf<PresenceEvent>()
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) {
                presence1.eventStream.collect { event ->
                    events.add(event)
                }
            }

            // First client attaches
            c1.attachPresence(presence1).await()
            delay(100)

            // Should receive initialized event
            assertTrue(events.isNotEmpty())
            assertIs<PresenceEvent.Initialized>(events[0])
            assertEquals(1L, events[0].count)

            // Second client attaches
            c2.attachPresence(presence2).await()
            delay(200)

            // Should receive count-changed event
            assertTrue(events.size >= 2)
            assertIs<PresenceEvent.Changed>(events.last())
            assertEquals(2L, events.last().count)

            // Cleanup
            collectJob.cancel()
            c1.detachPresence(presence1).await()
            c2.detachPresence(presence2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_presence_counter_detach_reduces_count() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()

            val presenceKey = "presence-detach-${UUID.randomUUID()}".toDocKey()
            val presence1 = Presence(presenceKey)
            val presence2 = Presence(presenceKey)

            // Both attach
            c1.attachPresence(presence1).await()
            c2.attachPresence(presence2).await()
            delay(300)

            assertEquals(2L, presence1.getCount())
            assertEquals(2L, presence2.getCount())

            // One detaches
            c1.detachPresence(presence1).await()
            delay(300)

            // Detached presence should show updated count
            assertEquals(1L, presence1.getCount())

            // Other presence should also see the decrease
            delay(300)
            assertEquals(1L, presence2.getCount())

            // Cleanup
            c2.detachPresence(presence2).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_presence_heartbeat_keeps_session_alive() {
        runBlocking {
            // 1 second heartbeat interval for faster testing
            val c1 = createClient(
                options = dev.yorkie.core.Client.Options(
                    presenceHeartbeatInterval = 1000.milliseconds,
                ),
            )
            c1.activateAsync().await()

            val presenceKey = "presence-heartbeat-${UUID.randomUUID()}".toDocKey()
            val presence = Presence(presenceKey)

            // Attach presence counter
            c1.attachPresence(presence).await()
            assertEquals(1L, presence.getCount())

            // Wait for 3 heartbeat cycles (3 seconds)
            // The presence should still be active because heartbeat refreshes TTL
            delay(3500)

            // Verify presence is still active
            assertTrue(presence.isAttached())
            assertEquals(1L, presence.getCount())

            // Cleanup
            c1.detachPresence(presence).await()
            c1.deactivateAsync().await()
            c1.close()
        }
    }

    @Test
    fun test_presence_manual_sync_mode() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()

            // Create presences for the same room
            val presenceKey = "presence-manual-${UUID.randomUUID()}".toDocKey()
            val p1 = Presence(presenceKey)
            val p2 = Presence(presenceKey)

            // Attach client1 with manual sync mode (no watch stream)
            c1.attachPresence(p1, isRealtime = false).await()
            assertEquals(1L, p1.getCount())

            // Attach client2 with manual sync mode
            c2.attachPresence(p2, isRealtime = false).await()
            assertEquals(2L, p2.getCount())

            // In manual mode, p1's count doesn't update automatically
            // even though p2 was attached
            delay(500)
            assertEquals(1L, p1.getCount())

            // Must call syncPresence() explicitly to refresh TTL and fetch latest count
            c1.syncAsync(p1).await()
            assertEquals(2L, p1.getCount())

            // Detach p2 and verify p1 doesn't auto-update
            c2.detachPresence(p2).await()
            delay(500)
            assertEquals(2L, p1.getCount())

            // Sync to refresh TTL and fetch latest count after c2 detached
            c1.syncAsync(p1).await()
            assertEquals(1L, p1.getCount())

            // Cleanup
            c1.detachPresence(p1).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c1.close()
            c2.close()
        }
    }

    @Test
    fun test_presence_realtime_vs_manual_mode_comparison() {
        runBlocking {
            val c1 = createClient()
            val c2 = createClient()
            val c3 = createClient()

            c1.activateAsync().await()
            c2.activateAsync().await()
            c3.activateAsync().await()

            val presenceKey = "presence-mode-compare-${UUID.randomUUID()}".toDocKey()
            val realtimePresence = Presence(presenceKey)
            val manualPresence = Presence(presenceKey)
            val thirdPresence = Presence(presenceKey)

            // c1: Attach with realtime mode (default)
            c1.attachPresence(realtimePresence).await()
            assertEquals(1L, realtimePresence.getCount())

            // c2: Attach with manual mode
            c2.attachPresence(manualPresence, isRealtime = false).await()
            assertEquals(2L, manualPresence.getCount())

            // c1's realtime presence should automatically receive the update
            delay(500)
            assertEquals(2L, realtimePresence.getCount())

            // c2's manual presence doesn't receive updates
            assertEquals(2L, manualPresence.getCount())

            // c3: Attach another client
            c3.attachPresence(thirdPresence, isRealtime = false).await()
            assertEquals(3L, thirdPresence.getCount())

            // c1's realtime presence receives the update automatically
            delay(500)
            assertEquals(3L, realtimePresence.getCount())

            // c2's manual presence still doesn't update
            assertEquals(2L, manualPresence.getCount())

            // Cleanup
            c1.detachPresence(realtimePresence).await()
            c2.detachPresence(manualPresence).await()
            c3.detachPresence(thirdPresence).await()
            c1.deactivateAsync().await()
            c2.deactivateAsync().await()
            c3.deactivateAsync().await()
            c1.close()
            c2.close()
            c3.close()
        }
    }
}
