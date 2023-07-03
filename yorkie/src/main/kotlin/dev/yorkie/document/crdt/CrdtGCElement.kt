package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtGCElement] represents the element which has garbage collection methods.
 */
internal abstract class CrdtGCElement : CrdtElement() {

    abstract val removedNodesLength: Int

    abstract fun deleteRemovedNodesBefore(executedAt: TimeTicket): Int
}
