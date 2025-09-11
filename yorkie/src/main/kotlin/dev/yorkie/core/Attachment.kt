package dev.yorkie.core

import dev.yorkie.document.Document

internal data class Attachment(
    val document: Document,
    val documentID: String,
    val syncMode: Client.SyncMode = Client.SyncMode.Realtime,
    val remoteChangeEventReceived: Boolean = false,
    val cancelled: Boolean = false,
) {

    fun needRealTimeSync(): Boolean {
        if (syncMode == Client.SyncMode.RealtimeSyncOff) {
            return false
        }

        if (syncMode == Client.SyncMode.RealtimePushOnly) {
            return document.hasLocalChanges
        }

        return (
            syncMode != Client.SyncMode.Manual &&
                (document.hasLocalChanges || remoteChangeEventReceived)
            )
    }
}
