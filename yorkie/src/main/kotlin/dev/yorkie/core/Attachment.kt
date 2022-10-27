package dev.yorkie.core

import dev.yorkie.document.Document

internal data class Attachment(
    val doc: Document,
    val isRealTimeSync: Boolean,
    // peerPresence,
    var remoteChangeEventReceived: Boolean = false,
)
