package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.MaxPriorityQueue
import dev.yorkie.util.PQNode
import dev.yorkie.util.YorkieLogger

/**
 * [RhtPQMap] is replicated hash table with a priority queue by creation time.
 */
internal class RhtPQMap {
    private val logTag = "RhtPQMap"

    private val elementQueueMapByKey:
        MutableMap<String, MaxPriorityQueue<PQNode<TimeTicket, CrdtElement>>> = mutableMapOf()

    private val nodeMapByCreatedAt: MutableMap<TimeTicket, RhtPQMapNode> = mutableMapOf()

    /**
     * Sets the [value] using the given [key].
     * If the object exists in [elementQueueMapByKey] by same [key] then return [CrdtElement], otherwise null.
     */
    fun set(key: String, value: CrdtElement): CrdtElement? {
        var removed: CrdtElement? = null
        val queue = elementQueueMapByKey[key]
        if (queue != null && queue.size > 0) {
            val node = queue.peek() as RhtPQMapNode
            if (!node.isRemoved() && node.remove(value.createdAt)) {
                removed = node.value
            }
        }
        setInternal(key, value)
        return removed
    }

    /**
     * Sets the [value] using the given [key] internally.
     */
    private fun setInternal(key: String, value: CrdtElement) {
        val queueMap = elementQueueMapByKey
        if (!queueMap.contains(key)) {
            queueMap[key] = MaxPriorityQueue()
        }

        val node = RhtPQMapNode.of(key, value)
        val queue = queueMap[key]
            ?: throw IllegalStateException("The MaxPriorityQueue by $key doesn't exist.")
        queue.add(node)
        nodeMapByCreatedAt[value.createdAt] = node
    }

    /**
     * Deletes the Element in [nodeMapByCreatedAt] using the given key [createdAt],
     * and TimeTicket will be removed by [executedAt].
     * If the object exists in [nodeMapByCreatedAt] by same key [createdAt], it will return.
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
     * Returns [RhtPQMapNode.strKey] of node based on creation time.
     * The node will be found in [nodeMapByCreatedAt] using [createdAt]
     *
     * @throws IllegalStateException if RHTPQMapNode doesn't exist.
     */
    fun subPathOf(createdAt: TimeTicket): String {
        return nodeMapByCreatedAt[createdAt]?.strKey
            ?: throw IllegalStateException("RHTPQMapNode's strKey by $createdAt doesn't exist")
    }

    /**
     * Physically purges child [element] from [nodeMapByCreatedAt] and [elementQueueMapByKey]
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
     * Deletes the element in [elementQueueMapByKey] using the given [key] and [removedAt].
     * If the element removed successfully, removed [CrdtElement] will return.
     *
     * @throws IllegalStateException if MaxPriorityQueue doesn't exist.
     */
    fun deleteByKey(key: String, removedAt: TimeTicket): CrdtElement {
        val queue = elementQueueMapByKey[key]
            ?: throw IllegalStateException("MaxPriorityQueue by $key doesn't exist")
        val node = queue.peek() as RhtPQMapNode
        node.remove(removedAt)
        return node.value
    }

    /**
     * Checks the element exists in [elementQueueMapByKey] using [key].
     * If the RHTPQMapNode is exist, then returns true, otherwise false.
     */
    fun has(key: String): Boolean {
        val queue = elementQueueMapByKey[key] ?: return false
        val node = queue.peek() as RhtPQMapNode
        return !node.isRemoved()
    }

    /**
     * Returns the [CrdtElement] using given [key].
     *
     * @throws IllegalStateException if MaxPriorityQueue doesn't exist.
     */
    fun get(key: String): CrdtElement {
        return elementQueueMapByKey[key]?.peek()?.value
            ?: throw IllegalStateException("MaxPriorityQueue by $key doesn't exist")
    }

    /**
     * Returns the sequence of [elementQueueMapByKey]'s values
     */
    fun getKeyOfQueue(): Sequence<RhtPQMapNode> {
        return elementQueueMapByKey.values
            .asSequence()
            .map { it.element() as RhtPQMapNode }
    }

    companion object {
        /**
         * Creates a instance of [RhtPQMap]
         */
        @JvmStatic
        fun create(): RhtPQMap {
            return RhtPQMap()
        }
    }
}
