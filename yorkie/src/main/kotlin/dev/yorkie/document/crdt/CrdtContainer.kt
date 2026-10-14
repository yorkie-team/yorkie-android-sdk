package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtContainer] represents CrdtArray or CrdtObject.
 */
internal abstract class CrdtContainer : CrdtElement() {

    abstract fun subPathOf(createdAt: TimeTicket): String?

    abstract fun purge(element: CrdtElement)

    /**
     * The element-side form of [GCParent.purgeBarrierAt], for a container
     * whose [purge] unlinks a node of its own RGA order ([CrdtArray]). `null`
     * for every other container — [CrdtRoot.garbageCollect]'s element loop
     * then purges on [CrdtElement.removedAt] alone, as it always did. Ported
     * from JS `CRDTContainer.purgeBarrierAt` (yorkie-js-sdk `dbff8892`,
     * #1405).
     */
    open fun purgeBarrierAt(element: CrdtElement): TimeTicket? = null

    abstract fun delete(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement

    abstract fun getDescendants(callback: (CrdtElement, CrdtContainer) -> Boolean)
}
