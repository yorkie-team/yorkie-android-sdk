package dev.yorkie.core

import dev.yorkie.document.Document
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal data class Attachment(
    val document: Document,
    val documentID: String,
    val isRealTimeSync: Boolean,
    val peerPresences: Peers = UninitializedPresences,
    val remoteChangeEventReceived: Boolean = false,
    val watchJob: Job = SupervisorJob(),
) {

    companion object {
        val UninitializedPresences = Peers()
    }
}
