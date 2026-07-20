package dev.yorkie.core

import dev.yorkie.document.Document
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
     * `needSync` determines if the attachment needs sync.
     * This includes both document sync and presence heartbeat.
     */
    fun needSync(heartbeatInterval: Long, documentPollInterval: Long): Boolean {
        // For Document: check if realtime sync is needed
        if (resource is Document) {
            // Polling has no watch stream, so it pushes and pulls on a fixed
            // interval regardless of local changes. Mirrors JS SDK PR #1243.
            if (syncMode == Client.SyncMode.Polling) {
                return System.currentTimeMillis() - lastHeartbeatTime >= documentPollInterval
            }
            return needRealTimeSync()
        }

        // For Channel in Manual mode: never auto-sync
        if (syncMode == Client.SyncMode.Manual) {
            return false
        }

        // For Channel: check if heartbeat is needed
        return System.currentTimeMillis() - lastHeartbeatTime >= heartbeatInterval
    }

    /**
     * `updateHeartbeatTime` updates the last heartbeat time.
     */
    fun updateHeartbeatTime() {
        lastHeartbeatTime = System.currentTimeMillis()
    }

    fun cancelWatchJob() {
        cancelled = true
        watchJobHolder?.job?.cancel()
        watchJobHolder = null
    }
}

data class WatchJobHolder(val key: String, val job: Job)
