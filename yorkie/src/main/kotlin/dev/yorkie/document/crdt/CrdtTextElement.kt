package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtTextElement] represents [CrdtText] or [CrdtRichText].
 */
internal abstract class CrdtTextElement : CrdtElement() {

    abstract fun getRemovedNodesLen(): Int

    abstract fun deleteTextNodesWithGarbage(executedAt: TimeTicket): Int
}
