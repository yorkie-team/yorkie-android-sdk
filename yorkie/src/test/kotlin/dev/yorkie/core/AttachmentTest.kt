package dev.yorkie.core

import dev.yorkie.document.Document
import io.mockk.every
import io.mockk.spyk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Test

/**
 * Exercises [Attachment]'s watch-liveness / pull-fallback decision (#351 phase 1) against a
 * FAKE clock rather than real delays: the client's sync loop runs on a real, non-virtualizable
 * dispatcher (`createSingleThreadDispatcher`), so `runTest`'s virtual time cannot drive it and
 * `needSync` reads wall-clock time directly. Injecting `nowProvider` makes the decision itself
 * deterministic and fast, with no coroutines involved at all — see the exec-plan's "Design
 * decision" section.
 */
class AttachmentTest {

    private var now = 0L
    private val documents = mutableListOf<Document>()

    @After
    fun tearDown() {
        documents.forEach { it.close() }
    }

    /**
     * A real [Document] resource (not a bare mock — [Attachable] is an interface but
     * `resource is Document` is checked by [Attachment.needSync] itself, so a mock typed as
     * [Document] via [spyk] is what the production `resource is Document` check needs),
     * spied only to stub [Document.hasLocalChanges] (a real, unattached `Document` always has
     * local changes from its own initial empty-root commit, which would make every
     * `needRealTimeSync()`-driven assertion trivially true).
     */
    private fun documentResource(): Document {
        val document = spyk(Document(TEST_DOCUMENT_KEY))
        every { document.hasLocalChanges() } returns false
        documents += document
        return document
    }

    private fun attachment(
        syncMode: Client.SyncMode? = Client.SyncMode.Polling,
        watchFallbackDelay: Long = Long.MAX_VALUE,
        resource: Document = documentResource(),
    ) = Attachment(
        resource = resource,
        syncMode = syncMode,
        nowProvider = { now },
        watchFallbackDelay = watchFallbackDelay,
    )

    /**
     * Opens a fake watch stream on [this] attachment — [Attachment.watchStreamSilent] (via
     * [Attachment.needSync]) treats a null [Attachment.watchJobHolder] as "no watch stream is
     * even supposed to be live," which must never engage fallback regardless of silence.
     * The [Job] itself is never started; only its presence (non-null holder) matters here.
     */
    private fun Attachment<Document>.withOpenWatchStream() = apply {
        watchJobHolder = WatchJobHolder(resource.getKey(), Job())
    }

    /** A realtime attachment with an open watch stream — the only shape fallback ever applies to. */
    private fun realtimeAttachment(watchFallbackDelay: Long = 1_000L) =
        attachment(syncMode = Client.SyncMode.Realtime, watchFallbackDelay = watchFallbackDelay)
            .withOpenWatchStream()

    // ========== Y1: injectable clock drives needSync's Polling branch deterministically ==========

    @Test
    fun `needSync Polling branch is false before documentPollInterval elapses`() {
        val target = attachment(syncMode = Client.SyncMode.Polling)
        now = 1_000L
        target.updateHeartbeatTime()

        now = 1_999L // 999ms elapsed, poll interval 1_000ms
        assertFalse(target.needSync(heartbeatInterval = 1_000L, documentPollInterval = 1_000L))
    }

    @Test
    fun `needSync Polling branch is true once documentPollInterval elapses`() {
        val target = attachment(syncMode = Client.SyncMode.Polling)
        now = 1_000L
        target.updateHeartbeatTime()

        now = 2_000L // exactly 1_000ms elapsed
        assertTrue(target.needSync(heartbeatInterval = 1_000L, documentPollInterval = 1_000L))
    }

    @Test
    fun `updateHeartbeatTime resets the fake clock baseline used by needSync`() {
        val target = attachment(syncMode = Client.SyncMode.Polling)
        now = 5_000L
        target.updateHeartbeatTime()

        now = 5_500L
        assertFalse(target.needSync(heartbeatInterval = 1_000L, documentPollInterval = 1_000L))

        // A second updateHeartbeatTime() moves the baseline forward again, so the SAME
        // elapsed-time-since-first-call no longer trips needSync.
        target.updateHeartbeatTime()
        now = 6_000L
        assertFalse(target.needSync(heartbeatInterval = 1_000L, documentPollInterval = 1_000L))
    }

    // ========== Y1: markWatchResponseReceived resets the liveness clock ==========

    @Test
    fun `lastWatchResponseTime is seeded to the fake now at construction`() {
        now = 500L
        val target = attachment()
        assertEquals(500L, target.lastWatchResponseTime)
    }

    @Test
    fun `markWatchResponseReceived advances lastWatchResponseTime to the fake now`() {
        now = 500L
        val target = attachment()

        now = 1_500L
        target.markWatchResponseReceived()
        assertEquals(1_500L, target.lastWatchResponseTime)
    }

    @Test
    fun `markWatchResponseReceived does not engage fallback when not previously engaged`() {
        val target = attachment()
        target.markWatchResponseReceived()
        assertFalse(target.watchFallbackEngaged)
    }

    // ========== Y3: fallback decision (AC1-AC3), throttle, transition logging (#351 phase 1) ==========

    @Test
    fun `AC1 - fallback engages once the watch stream has been silent past the threshold`() {
        now = 0L
        val target = realtimeAttachment(watchFallbackDelay = 1_000L)
        target.updateHeartbeatTime() // poll-interval throttle baseline

        now = 1_000L // silence threshold (1_000ms) AND poll interval (500ms) both crossed
        assertTrue(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertTrue(target.watchFallbackEngaged)
    }

    @Test
    fun `AC2 - fallback disengages once a watch frame is received`() {
        now = 0L
        val target = realtimeAttachment(watchFallbackDelay = 1_000L)
        target.updateHeartbeatTime()
        now = 1_000L
        assertTrue(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertTrue(target.watchFallbackEngaged)

        // A watch frame arrives (stream revives): resets the silence clock and disengages.
        target.markWatchResponseReceived()
        assertFalse(target.watchFallbackEngaged)

        // A tick before the threshold would elapse again from this new baseline: no re-engage.
        now = 1_999L
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertFalse(target.watchFallbackEngaged)
    }

    @Test
    fun `AC3 - fallback never engages while watch frames keep arriving under the threshold`() {
        now = 0L
        val target = realtimeAttachment(watchFallbackDelay = 1_000L)
        target.updateHeartbeatTime()

        // Simulated frames every 400ms, well under the 1_000ms threshold, across many ticks.
        repeat(20) {
            now += 400L
            target.markWatchResponseReceived()
            assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
            assertFalse(target.watchFallbackEngaged)
        }
    }

    @Test
    fun `throttle - second tick within pollInterval of last sync does not re-fire`() {
        now = 0L
        val target = realtimeAttachment(watchFallbackDelay = 1_000L)
        target.updateHeartbeatTime()

        now = 1_000L
        assertTrue(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        // Simulates Client.syncInternal's broadened updateHeartbeatTime() reset after the
        // fallback-triggered sync actually runs.
        target.updateHeartbeatTime()

        // Still past the silence threshold (watch stream never revived), but well under
        // documentPollInterval (500ms) since the just-simulated sync — must not fire again,
        // else every 50ms sync-loop tick would pull: the AC7 storm this throttle exists for.
        now = 1_100L
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
    }

    @Test
    fun `throttle - fires again once documentPollInterval elapses since the last fallback sync`() {
        now = 0L
        val target = realtimeAttachment(watchFallbackDelay = 1_000L)
        target.updateHeartbeatTime()

        now = 1_000L
        assertTrue(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        target.updateHeartbeatTime()

        now = 1_500L // exactly documentPollInterval later
        assertTrue(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
    }

    @Test
    fun `boundary - a watch frame at T-epsilon resets the silence clock`() {
        now = 0L
        val target = realtimeAttachment(watchFallbackDelay = 1_000L)
        target.updateHeartbeatTime()

        now = 999L // one ms before the threshold
        target.markWatchResponseReceived()

        now = 1_000L // would have crossed the ORIGINAL threshold, but the clock just reset
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
    }

    @Test
    fun `guard - no open watch stream never engages fallback`() {
        now = 0L
        val target = attachment(syncMode = Client.SyncMode.Realtime, watchFallbackDelay = 1_000L)
        // Deliberately no withOpenWatchStream(): watchJobHolder stays null.
        target.updateHeartbeatTime()

        now = 10_000L // far past any threshold
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertFalse(target.watchFallbackEngaged)
    }

    @Test
    fun `guard - RealtimePushOnly never engages fallback`() {
        now = 0L
        val target = attachment(
            syncMode = Client.SyncMode.RealtimePushOnly,
            watchFallbackDelay = 1_000L,
        )
            .withOpenWatchStream()
        target.updateHeartbeatTime()

        now = 10_000L
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertFalse(target.watchFallbackEngaged)
    }

    @Test
    fun `guard - RealtimeSyncOff never engages fallback`() {
        now = 0L
        val target = attachment(
            syncMode = Client.SyncMode.RealtimeSyncOff,
            watchFallbackDelay = 1_000L,
        )
            .withOpenWatchStream()
        target.updateHeartbeatTime()

        now = 10_000L
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertFalse(target.watchFallbackEngaged)
    }

    @Test
    fun `guard - Manual never engages fallback`() {
        now = 0L
        val target = attachment(syncMode = Client.SyncMode.Manual, watchFallbackDelay = 1_000L)
            .withOpenWatchStream()
        target.updateHeartbeatTime()

        now = 10_000L
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertFalse(target.watchFallbackEngaged)
    }

    @Test
    fun `guard - Polling never engages fallback`() {
        now = 0L
        val target = attachment(syncMode = Client.SyncMode.Polling, watchFallbackDelay = 1_000L)
            .withOpenWatchStream()
        target.updateHeartbeatTime()

        now = 10_000L
        // needSync is true here, but via Polling's OWN branch, not fallback.
        assertTrue(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertFalse(target.watchFallbackEngaged)
    }

    @Test
    fun `guard - watchFallbackDelay of Long MAX_VALUE (INFINITE) disables fallback entirely`() {
        now = 0L
        val target = realtimeAttachment(watchFallbackDelay = Long.MAX_VALUE)
        target.updateHeartbeatTime()

        now = Long.MAX_VALUE / 2 // arbitrarily far in the future
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L))
        assertFalse(target.watchFallbackEngaged)
    }

    // ========== holdSyncFor's auth-hold gate ==========

    @Test
    fun `holdSyncFor - needSync is false one tick before the hold elapses`() {
        now = 1_000L
        val target = attachment(syncMode = Client.SyncMode.Polling)
        target.holdSyncFor(500L) // holds until now=1_500

        now = 1_499L
        assertFalse(
            target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 1L),
            "the poll interval alone would already fire -- the hold must still suppress it",
        )
    }

    @Test
    fun `holdSyncFor - boundary, needSync resumes exactly AT the hold's own deadline`() {
        now = 1_000L
        val target = attachment(syncMode = Client.SyncMode.Polling)
        target.holdSyncFor(500L) // holds until now=1_500

        now = 1_500L // == authHoldUntil, not past it
        assertTrue(
            target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 1L),
            "needSync's `nowProvider() < authHoldUntil` reads false at the boundary itself " +
                "-- the hold must already have resumed, not need one more tick past it",
        )
    }

    @Test
    fun `holdSyncFor - holds a Polling document even though its own poll interval elapsed`() {
        now = 1_000L
        val target = attachment(syncMode = Client.SyncMode.Polling)
        target.updateHeartbeatTime()
        target.holdSyncFor(10_000L)

        now = 2_000L // 1_000ms since updateHeartbeatTime -- Polling's own gate alone would fire
        assertFalse(target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 1_000L))
    }

    @Test
    fun `holdSyncFor - holds a Realtime document even with a change event pending`() {
        now = 1_000L
        val target = attachment(syncMode = Client.SyncMode.Realtime)
        target.changeEventReceived = true
        target.holdSyncFor(10_000L)

        now = 1_001L
        assertFalse(
            target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 500L),
            "needRealTimeSync() alone would fire on the pending change event -- the hold, " +
                "checked before the Document/Channel branch, must still suppress it",
        )
    }

    @Test
    fun `holdSyncFor - holds a Channel even though its heartbeat interval elapsed`() {
        now = 1_000L
        val target = Attachment(
            resource = dev.yorkie.presence.Channel("attachment-test-channel"),
            syncMode = Client.SyncMode.Realtime,
            nowProvider = { now },
        )
        target.holdSyncFor(50_000L) // holds until now=51_000

        now = 20_000L // 19_000ms since construction -- past the 1_000ms heartbeat interval below
        assertFalse(target.needSync(heartbeatInterval = 1_000L, documentPollInterval = 500L))
    }

    @Test
    fun `holdSyncFor - clearing does not resurrect a write-rejected park`() {
        now = 1_000L
        val target = attachment(syncMode = Client.SyncMode.Polling)
        target.updateHeartbeatTime()
        target.markWriteRejected()
        target.holdSyncFor(500L) // holds until now=1_500, independent of the park below

        now = 1_500L // the hold has elapsed...
        assertFalse(
            target.needSync(heartbeatInterval = 5_000L, documentPollInterval = 1L),
            "...but the write-rejected park is a SEPARATE, permanent gate the hold's own " +
                "expiry must not clear",
        )
    }

    private companion object {
        const val TEST_DOCUMENT_KEY = "attachment-test-doc"
    }
}
