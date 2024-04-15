package dev.yorkie.core

import dev.yorkie.document.Document

internal data class Attachment(
    val document: Document,
    val documentID: String,
    val syncMode: Client.SyncMode = Client.SyncMode.Realtime,
    val remoteChangeEventReceived: Boolean = false,
)
