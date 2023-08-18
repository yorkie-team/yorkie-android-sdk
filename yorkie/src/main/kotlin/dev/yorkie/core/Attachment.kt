package dev.yorkie.core

import dev.yorkie.document.Document

internal data class Attachment(
    val document: Document,
    val documentID: String,
    val isRealTimeSync: Boolean,
    val syncMode: Client.SyncMode = Client.SyncMode.PushPull,
    val remoteChangeEventReceived: Boolean = false,
)
