package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtContainer] represents CRDTArray or CRDtObject.
 */
internal abstract class CrdtContainer(createdAt: TimeTicket) : CrdtElement(createdAt) {
    abstract fun subPathOf(createdAt: TimeTicket): String?

    abstract fun purge(element: CrdtElement)

    abstract fun delete(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement

    abstract fun getDescendants(callback: (CrdtElement, CrdtContainer) -> Boolean)
}
