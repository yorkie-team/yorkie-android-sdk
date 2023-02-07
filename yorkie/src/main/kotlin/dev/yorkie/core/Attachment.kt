package dev.yorkie.core

import dev.yorkie.document.Document
import dev.yorkie.document.time.ActorID

internal data class Attachment(
    val document: Document,
    val isRealTimeSync: Boolean,
    val peerPresences: MutableMap<ActorID, PresenceInfo> = UninitializedPresences,
    var remoteChangeEventReceived: Boolean = false,
) {

    companion object {
        val UninitializedPresences = object : HashMap<ActorID, PresenceInfo>() {

            override fun equals(other: Any?): Boolean = this === other
        }
    }
}
