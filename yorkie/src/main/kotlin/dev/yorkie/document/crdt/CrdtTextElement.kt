package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtTextElement] represents [CrdtText] or [CrdtRichText].
 */
internal abstract class CrdtTextElement(createdAt: TimeTicket) : CrdtElement(createdAt) {
    abstract fun getRemovedNodesLen(): Int

    abstract fun deleteTextNodesWithGarbage(ticket: TimeTicket): Int
}
