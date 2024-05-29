package dev.yorkie.core

import dev.yorkie.document.Document

internal data class Attachment(
    val document: Document,
    val documentID: String,
    val syncMode: Client.SyncMode = Client.SyncMode.Realtime,
    val remoteChangeEventReceived: Boolean = false,
) {

    fun needRealTimeSync(): Boolean {
        val needRealTimeSyncMode = if (syncMode == Client.SyncMode.RealtimePushOnly) {
            document.hasLocalChanges
        } else {
            syncMode.needRealTimeSync
        }
        return needRealTimeSyncMode && (document.hasLocalChanges || remoteChangeEventReceived)
    }
}
