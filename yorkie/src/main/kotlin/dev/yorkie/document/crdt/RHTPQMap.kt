package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.MaxPriorityQueue
import dev.yorkie.util.PQNode
import dev.yorkie.util.YorkieLogger


/**
 * RHTPQMap is replicated hash table with priority queue by creation time.
 *
 * @internal
 */
internal class RHTPQMap {
    private val logTag = "RHTPQMap"

    private val elementQueueMapByKey: MutableMap<String, MaxPriorityQueue<PQNode<TimeTicket, CrdtElement>>> =
        mutableMapOf()
    private val nodeMapByCreatedAt: MutableMap<TimeTicket, RHTPQMapNode> = mutableMapOf()

    /**
     * Set the [value] using the given [key].
     * If the object exist in [elementQueueMapByKey] by same [key] then return [CrdtElement], otherwise null.
     */
    fun set(key: String, value: CrdtElement): CrdtElement? {
        var removed: CrdtElement? = null
        val queue = elementQueueMapByKey[key]
        if (queue != null && queue.size > 0) {
            val node = queue.peek() as RHTPQMapNode
            if (!node.isRemoved() && node.remove(value.createdAt)) {
                removed = node.value
            }
        }
        setInternal(key, value)
        return removed
    }

    /**
     * Set the [value] using the given [key] internally.
     */
    private fun setInternal(key: String, value: CrdtElement) {
        val queueMap = elementQueueMapByKey
        if (!queueMap.contains(key)) {
            queueMap[key] = MaxPriorityQueue()
        }

        val node = RHTPQMapNode.of(key, value)
        val queue = queueMap[key]
            ?: throw IllegalStateException("The MaxPriorityQueue by $key doesn't exist.")
        queue.add(node)
        nodeMapByCreatedAt[value.createdAt] = node
    }

    /**
     * Delete the Element from [nodeMapByCreatedAt] using the given key [createdAt], and TimeTicket will be removed by [executedAt].
     * If the object exist in [nodeMapByCreatedAt] by same key [createdAt], it will return.
     *
     * @throws IllegalStateException if RHTPQMapNode doesn't exist.
     */
    fun delete(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        val nodeMap = nodeMapByCreatedAt
        if (!nodeMap.contains(createdAt)) {
            YorkieLogger.e(logTag, "fail to find $createdAt")
        }

        val node = nodeMap[createdAt]
            ?: throw IllegalStateException("The RHTPQMapNode by $createdAt doesn't exist.")
        node.remove(executedAt)
        return node.value
    }

    /**
     * Return [RHTPQMapNode.strKey] of `node` based on creation time.
     * The `node` will be found in [nodeMapByCreatedAt] using [createdAt]
     *
     * @throws IllegalStateException if RHTPQMapNode doesn't exist.
     */
    fun keyOf(createdAt: TimeTicket): String {
        return nodeMapByCreatedAt[createdAt]?.strKey
            ?: throw IllegalStateException("RHTPQMapNode's strKey by $createdAt doesn't exist")
    }

    /**
     * Physically purge child [element] from [nodeMapByCreatedAt] and [elementQueueMapByKey]
     */
    fun purge(element: CrdtElement) {
        val nodeMap = nodeMapByCreatedAt
        val node = nodeMap[element.createdAt]

        if (node == null) {
            YorkieLogger.e(logTag, "fail to find ${element.createdAt}")
            return
        }

        val queue = elementQueueMapByKey[node.strKey]
        if (queue == null) {
            YorkieLogger.e(logTag, "fail to find queue of ${node.strKey}")
            return
        }

        queue.remove(node)
        nodeMap.remove(node.value.createdAt)
    }

    /**
     * Delete the element using the given [key] and [removedAt].
     * If the element removed successfully, [CrdtElement] will return.
     *
     * @throws IllegalStateException if MaxPriorityQueue doesn't exist.
     */
    fun deleteByKey(key: String, removedAt: TimeTicket): CrdtElement {
        val queue = elementQueueMapByKey[key]
            ?: throw IllegalStateException("MaxPriorityQueue by $key doesn't exist")
        val node = queue.peek() as RHTPQMapNode
        node.remove(removedAt)
        return node.value
    }

    /**
     * Check the element exists from [elementQueueMapByKey] using [key].
     * If the RHTPQMapNode is exist, then returns true, otherwise false.
     */
    fun has(key: String): Boolean {
        val queue = elementQueueMapByKey[key] ?: return false
        val node = queue.peek() as RHTPQMapNode
        return !node.isRemoved()
    }

    /**
     * Return the [CrdtElement] using given [key].
     *
     * @throws IllegalStateException if MaxPriorityQueue doesn't exist.
     */
    fun get(key: String): CrdtElement {
        return elementQueueMapByKey[key]?.peek()?.value
            ?: throw IllegalStateException("MaxPriorityQueue by $key doesn't exist")
    }

    companion object {
        /**
         * `create` creates a instance of RHTPQMap.
         */
        @JvmStatic
        fun create(): RHTPQMap {
            return RHTPQMap()
        }
    }
}
