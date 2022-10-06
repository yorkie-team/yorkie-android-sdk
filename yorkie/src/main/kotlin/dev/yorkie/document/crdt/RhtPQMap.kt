package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.MaxPriorityQueue
import dev.yorkie.util.YorkieLogger

/**
 * [RhtPQMap] is replicated hash table with a priority queue by creation time.
 */
internal class RhtPQMap<T : CrdtElement> private constructor() {
    private val logTag = "RhtPQMap"

    private val elementQueueMapByKey:
        MutableMap<String, MaxPriorityQueue<RhtPQMapNode<T>>> = mutableMapOf()

    private val nodeMapByCreatedAt: MutableMap<TimeTicket, RhtPQMapNode<T>> = mutableMapOf()

    /**
     * Sets the [value] using the given [key].
     * If the object exists in [elementQueueMapByKey] by same [key] then return [CrdtElement], otherwise null.
     */
    operator fun set(key: String, value: T): T? {
        var removed: T? = null
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
    private fun setInternal(key: String, value: T) {
        val node = RhtPQMapNode(key, value)
        val queue = elementQueueMapByKey.getOrPut(key) { MaxPriorityQueue() }
        queue.add(node)
        nodeMapByCreatedAt[value.createdAt] = node
    }

    /**
     * Deletes the Element in [nodeMapByCreatedAt] using the given key [createdAt],
     * and [TimeTicket] will be removed by [executedAt].
     * If the object exists in [nodeMapByCreatedAt] by same key [createdAt], it will return.
     *
     * @throws NoSuchElementException if [RhtPQMapNode] doesn't exist.
     */
    fun delete(createdAt: TimeTicket, executedAt: TimeTicket): T {
        val node = nodeMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("The RhtPQMapNode by $createdAt doesn't exist.")
        node.remove(executedAt)
        return node.value
    }

    /**
     * Returns [RhtPQMapNode.strKey] of node based on creation time.
     * The node will be found in [nodeMapByCreatedAt] using [createdAt]
     *
     * @throws NoSuchElementException if RHTPQMapNode doesn't exist.
     */
    fun subPathOf(createdAt: TimeTicket): String {
        return nodeMapByCreatedAt[createdAt]?.strKey
            ?: throw NoSuchElementException("RhtPQMapNode's strKey by $createdAt doesn't exist")
    }

    /**
     * Physically purges child [element] from [nodeMapByCreatedAt] and [elementQueueMapByKey]
     */
    fun purge(element: T) {
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
     * @throws NoSuchElementException if MaxPriorityQueue is empty.
     */
    fun deleteByKey(key: String, removedAt: TimeTicket): T {
        val queue = elementQueueMapByKey[key]
            ?: throw IllegalStateException("MaxPriorityQueue by $key doesn't exist")
        val node = queue.peek() ?: throw NoSuchElementException("MaxPriorityQueue is empty")
        node.remove(removedAt)
        return node.value
    }

    /**
     * Checks the element exists in [elementQueueMapByKey] using [key].
     * If the [RhtPQMapNode] is exist, then returns true, otherwise false.
     */
    fun has(key: String): Boolean {
        val queue = elementQueueMapByKey[key] ?: return false
        val node = queue.peek() ?: return false
        return !node.isRemoved()
    }

    /**
     * Returns the [CrdtElement] using given [key].
     *
     * @throws IllegalStateException if MaxPriorityQueue doesn't exist.
     */
    operator fun get(key: String): T {
        return elementQueueMapByKey[key]?.peek()?.value
            ?: throw IllegalStateException("MaxPriorityQueue by $key doesn't exist")
    }

    /**
     * Returns the sequence of [elementQueueMapByKey]'s values
     *
     * @throws NoSuchElementException if there is an empty MaxPriorityQueue.
     */
    fun getKeyOfQueue(): Sequence<RhtPQMapNode<T>> {
        return elementQueueMapByKey.values
            .asSequence()
            .map { it.element() }
    }

    companion object {
        /**
         * Creates a instance of [RhtPQMap]
         */
        fun <T : CrdtElement> create(): RhtPQMap<T> {
            return RhtPQMap()
        }
    }

    /**
     * [RhtPQMapNode] is a node of [RhtPQMap].
     */
    data class RhtPQMapNode<T : CrdtElement>(
        val strKey: String,
        val value: T,
    ) : Comparable<RhtPQMapNode<T>> {

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

        override fun compareTo(other: RhtPQMapNode<T>): Int {
            return value.createdAt.compareTo(other.value.createdAt)
        }
    }
}
