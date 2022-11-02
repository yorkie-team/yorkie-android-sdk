package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtContainer] represents CrdtArray or CrdtObject.
 */
internal abstract class CrdtContainer : CrdtElement() {

    abstract fun subPathOf(createdAt: TimeTicket): String?

    abstract fun delete(element: CrdtElement)

    abstract fun remove(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement

    abstract fun getDescendants(callback: (CrdtElement, CrdtContainer) -> Boolean)
}
