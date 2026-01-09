package dev.yorkie.core

import dev.yorkie.document.Document
import kotlinx.coroutines.Job

/**
 * `Attachment` is a class that manages the state of an attachable resource (Document or Presence).
 */
internal class Attachment<R : Attachable>(
    val resource: R,
    val resourceId: String,
    var syncMode: Client.SyncMode? = null,
) {
    var changeEventReceived: Boolean? = syncMode?.let { false }
    var cancelled: Boolean = false
    private var lastHeartbeatTime: Long = System.currentTimeMillis()
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
    fun needSync(heartbeatInterval: Long): Boolean {
        // For Document: check if realtime sync is needed
        if (resource is Document) {
            return needRealTimeSync()
        }

        // For Presence in Manual mode: never auto-sync
        if (syncMode == Client.SyncMode.Manual) {
            return false
        }

        // For Presence: check if heartbeat is needed
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
