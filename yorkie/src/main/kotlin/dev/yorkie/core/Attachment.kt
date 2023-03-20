package dev.yorkie.core

import dev.yorkie.document.Document

internal data class Attachment(
    val document: Document,
    val documentID: String,
    val isRealTimeSync: Boolean,
    val peerPresences: Peers = UninitializedPresences,
    var remoteChangeEventReceived: Boolean = false,
) {

    companion object {
        val UninitializedPresences = Peers()
    }
}
