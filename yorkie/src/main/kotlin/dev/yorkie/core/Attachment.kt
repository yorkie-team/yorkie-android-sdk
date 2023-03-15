package dev.yorkie.core

import dev.yorkie.document.Document

internal data class Attachment(
    val document: Document,
    var isRealTimeSync: Boolean,
    val peerPresences: Peers = UninitializedPresences,
    var remoteChangeEventReceived: Boolean = false,
) {

    companion object {
        val UninitializedPresences = Peers()
    }
}
