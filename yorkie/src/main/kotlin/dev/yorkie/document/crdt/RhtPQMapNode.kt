package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.PQNode

/**
 * [RhtPQMapNode] is a node of RHTPQMap.
 */
internal class RhtPQMapNode(val strKey: String, value: CrdtElement) :
    PQNode<TimeTicket, CrdtElement>(value.createdAt, value) {

    /**
     * Checks whether this value was removed.
     */
    fun isRemoved(): Boolean {
        return value.isRemoved
    }

    /**
     * Removes a value base on removing time.
     */
    fun remove(removedAt: TimeTicket): Boolean {
        return value.remove(removedAt)
    }

    companion object {
        /**
         * Creates an instance of [RhtPQMapNode].
         */
        @JvmStatic
        fun of(strKey: String, value: CrdtElement): RhtPQMapNode {
            return RhtPQMapNode(strKey, value)
        }
    }

    override fun compareTo(other: PQNode<TimeTicket, CrdtElement>): Int {
        return key.compareTo(other.key)
    }
}
