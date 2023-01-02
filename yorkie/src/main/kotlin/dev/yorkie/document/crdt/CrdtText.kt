package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

internal data class CrdtText(
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket?,
    override var _removedAt: TimeTicket?,
) : CrdtTextElement() {

    override fun getRemovedNodesLength(): Int {
        TODO("Not yet implemented")
    }

    override fun deleteTextNodesWithGarbage(executedAt: TimeTicket): Int {
        TODO("Not yet implemented")
    }

    override fun deepCopy(): CrdtElement {
        TODO("Not yet implemented")
    }

    fun edit(
        range: RgaTreeSplitNodeRange,
        content: String,
        executedAt: TimeTicket,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): Map<ActorID, TimeTicket> {
        TODO("Not yet implemented")
    }

    // NOTE(7hong13): updatedAt vs executedAt ?
    fun select(range: RgaTreeSplitNodeRange, updatedAt: TimeTicket) {
        TODO("Not yet implemented")
    }
}
