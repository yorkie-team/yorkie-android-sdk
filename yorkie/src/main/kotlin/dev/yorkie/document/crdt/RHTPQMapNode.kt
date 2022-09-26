package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.PQNode

/**
 * `RHTPQMapNode` is a node of RHTPQMap.
 */
internal class RHTPQMapNode(val strKey: String, value: CrdtElement) :
    PQNode<TimeTicket, CrdtElement>(value.createdAt, value) {

    /**
     * `isRemoved` checks whether this value was removed.
     */
    fun isRemoved(): Boolean {
        return value.isRemoved
    }

    /**
     * `remove` removes a value base on removing time.
     */
    fun remove(removedAt: TimeTicket): Boolean {
        return value.remove(removedAt);
    }

    companion object {
        /**
         * `of` creates a instance of RHTPQMapNode.
         */
        @JvmStatic
        fun of(strKey: String, value: CrdtElement): RHTPQMapNode {
            return RHTPQMapNode(strKey, value)
        }
    }

    // TODO("It's need to check this function, whether it is enough to compare two objects.")
    override fun compareTo(other: PQNode<TimeTicket, CrdtElement>): Int {
        return key.compareTo(other.key).takeUnless { it == 0 }
            ?: value.createdAt.compareTo(other.value.createdAt)
    }
}
