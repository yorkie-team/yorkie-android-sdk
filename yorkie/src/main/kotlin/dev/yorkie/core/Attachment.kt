package dev.yorkie.core

import dev.yorkie.document.Document
import dev.yorkie.document.time.ActorID

internal data class Attachment(
    val document: Document,
    val isRealTimeSync: Boolean,
    val peerPresences: MutableMap<ActorID, PresenceInfo> = mutableMapOf(),
    var remoteChangeEventReceived: Boolean = false,
)
