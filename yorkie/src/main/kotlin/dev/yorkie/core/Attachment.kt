package dev.yorkie.core

import dev.yorkie.document.Document
import dev.yorkie.util.Logger.Companion.logDebug
import kotlinx.coroutines.Job

/**
 * `Attachment` is a class that manages the state of an attachable resource (Document or Presence).
 */
internal class Attachment<R : Attachable>(
    val resource: R,
    /**
     * Document ID for documents. Channel session ID for channels: starts
     * empty, populated by the first RefreshChannel response, cleared again
     * when the session expires (ErrSessionNotFound recovery).
     */
    var resourceId: String = "",
    var syncMode: Client.SyncMode? = null,
    /**
     * Declares that this attachment will not produce or consume tombstones.
     * The server skips minVV tracking and omits the response VersionVector
     * for this client. Documents only; carried on every PushPullChanges.
     */
    val disableGC: Boolean = false,
    /**
     * Server-fixated value declaring that this document does not produce,
     * consume, or store presence. Carried for visibility only; the wire
     * contract is set on the attach request, not on PushPullChanges.
     */
    val disablePresence: Boolean = false,
    /**
     * Clock used for all liveness/throttle timing. Defaults to the real wall clock;
     * tests inject a fake `var now` to drive [needSync] deterministically without
     * real delays, since the client runs its sync loop on a real (non-virtualizable)
     * dispatcher (see `AttachmentTest`).
     */
    private val nowProvider: () -> Long = System::currentTimeMillis,
    /**
     * How long (ms) a realtime document's watch stream may stay silent before this
     * attachment starts pull-fallback (see `Client.Options.watchFallbackDelay`, whose
     * value this is populated from — `Duration.INFINITE.inWholeMilliseconds ==
     * Long.MAX_VALUE`, so that value alone disables fallback with no separate
     * null/branch). Channels never read this (fallback is Document-realtime-only).
     */
    private val watchFallbackDelay: Long = Long.MAX_VALUE,
) {
    var changeEventReceived: Boolean? = syncMode?.let { false }
    var cancelled: Boolean = false

    /**
     * Set before detaching so an in-flight refresh that resumes from its
     * network await drops its side effects instead of resurrecting the
     * session. The mutex alone cannot do this: it only delays new acquirers,
     * it cannot alter an already-running critical section.
     */
    @Volatile
    var detaching: Boolean = false

    // Init 0 so the first sync-loop tick fires immediately: channel attach no
    // longer performs an RPC, the first RefreshChannel must not wait a full
    // heartbeat interval. Mirrors JS SDK v0.7.10.
    private var lastHeartbeatTime: Long = 0L
    var watchJobHolder: WatchJobHolder? = null

    /**
     * Wall-clock time (per [nowProvider]) of the last watch-stream frame received for
     * this attachment (any event kind — see `Client.markWatchResponseReceived` call
     * sites). Seeded at construction so a freshly attached document is not treated as
     * "instantly silent" before its watch stream has had a chance to connect.
     */
    var lastWatchResponseTime: Long = nowProvider()
        private set

    /**
     * `true` once pull fallback has engaged for this attachment (watch stream judged
     * silent past a threshold — see `Client.Options.watchFallbackDelay`) — used to log
     * the engage/disengage transition exactly once each way, not on every sync-loop
     * tick.
     */
    var watchFallbackEngaged: Boolean = false
        private set

    /**
     * Records that a watch-stream frame was just received for this attachment,
     * resetting its silence clock. Called from every received frame (proves the
     * stream is alive) and from stream (re)connection (the revival signal) — see
     * `Client.kt`'s watch-loop call sites.
     *
     * If fallback was engaged, this is the disengage signal: logs the transition via
     * `Logger` before resetting, so the silent failure mode #351 went undiagnosed
     * under is now itself observable.
     */
    fun markWatchResponseReceived() {
        if (watchFallbackEngaged) {
            watchFallbackEngaged = false
            logDebug(
                "WF",
                "pull fallback disengaged for ${resource.getKey()}: watch frame received",
            )
        }
        lastWatchResponseTime = nowProvider()
    }

    /**
     * `needRealtimeSync` returns whether the resource needs to be synced in real time.
     * Only applicable to Document resources with syncMode defined.
     */
    private fun needRealTimeSync(): Boolean {
        if (syncMode == Client.SyncMode.RealtimeSyncOff) {
            return false
        }

        if (syncMode == Client.SyncMode.RealtimePushOnly) {
            return resource.hasLocalChanges()
        }

        return (
            syncMode != Client.SyncMode.Manual &&
                (resource.hasLocalChanges() || (changeEventReceived ?: false))
            )
    }

    /**
     * Pull-fallback decision for #351 phase 1: in full [Client.SyncMode.Realtime], PULL is
     * normally gated by [needRealTimeSync] alone, which only flips true on a watch event
     * ([changeEventReceived]) or a local edit. If the watch stream dies silently (no error,
     * no reconnect — see issue #351), [changeEventReceived] never flips true again and PULL
     * starves forever. This degrades the attachment toward [Client.SyncMode.Polling]
     * semantics — pull on every tick — once the watch stream has been silent past
     * [watchFallbackDelay], throttled to at most once per [documentPollInterval] (via
     * [lastHeartbeatTime], reused exactly as the Polling branch above does — broadened by
     * `Client.syncInternal` to also reset for realtime attachments with fallback engaged,
     * so this clause has a moving baseline instead of firing every 50ms sync-loop tick).
     *
     * Only applies to a Document attachment with a genuinely open watch stream in full
     * realtime mode: [Client.SyncMode.RealtimePushOnly]/[Client.SyncMode.RealtimeSyncOff]
     * never open one, and neither does the brief window between attach and the watch job
     * actually starting.
     */
    private fun watchStreamSilent(documentPollInterval: Long): Boolean {
        if (syncMode != Client.SyncMode.Realtime || watchJobHolder == null) {
            return false
        }

        val now = nowProvider()
        val silent = now - lastWatchResponseTime >= watchFallbackDelay &&
            now - lastHeartbeatTime >= documentPollInterval

        if (silent && !watchFallbackEngaged) {
            watchFallbackEngaged = true
            logDebug(
                "WF",
                "pull fallback engaged for ${resource.getKey()}: " +
                    "watch silent >= ${watchFallbackDelay}ms",
            )
        }

        return silent
    }

    /**
     * `needSync` determines if the attachment needs sync.
     * This includes both document sync and presence heartbeat.
     */
    fun needSync(heartbeatInterval: Long, documentPollInterval: Long): Boolean {
        // For Document: check if realtime sync is needed
        if (resource is Document) {
            // Polling has no watch stream, so it pushes and pulls on a fixed
            // interval regardless of local changes. Mirrors JS SDK PR #1243.
            if (syncMode == Client.SyncMode.Polling) {
                return nowProvider() - lastHeartbeatTime >= documentPollInterval
            }
            return needRealTimeSync() || watchStreamSilent(documentPollInterval)
        }

        // For Channel in Manual mode: never auto-sync
        if (syncMode == Client.SyncMode.Manual) {
            return false
        }

        // For Channel: check if heartbeat is needed
        return nowProvider() - lastHeartbeatTime >= heartbeatInterval
    }

    /**
     * `updateHeartbeatTime` updates the last heartbeat time.
     */
    fun updateHeartbeatTime() {
        lastHeartbeatTime = nowProvider()
    }

    fun cancelWatchJob() {
        cancelled = true
        watchJobHolder?.job?.cancel()
        watchJobHolder = null
    }
}

data class WatchJobHolder(val key: String, val job: Job)
