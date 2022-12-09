package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.MaxPriorityQueue
import dev.yorkie.util.YorkieLogger

/**
 * [RhtPQMap] is a replicated hash table that uses a priority queue based on creation time.
 */
internal class RhtPQMap<T : CrdtElement> : Iterable<RhtPQMap.Node<T>> {
    private val logTag = "RhtPQMap"

    private val elementQueueMapByKey: MutableMap<String, MaxPriorityQueue<Node<T>>> = mutableMapOf()

    private val nodeMapByCreatedAt: MutableMap<TimeTicket, Node<T>> = mutableMapOf()

    /**
     * Sets the [value] using the given [key].
     * If the object exists in [elementQueueMapByKey] by same [key] then return [CrdtElement], otherwise null.
     */
    operator fun set(key: String, value: T): T? {
        var removed: T? = null
        val queue = elementQueueMapByKey[key]
        if (queue?.isNotEmpty() == true) {
            val node = queue.peek() as Node
            if (!node.isRemoved && node.remove(value.createdAt)) {
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
        val node = Node(key, value)
        val queue = elementQueueMapByKey.getOrPut(key) { MaxPriorityQueue() }
        queue.add(node)
        nodeMapByCreatedAt[value.createdAt] = node
    }

    /**
     * Removes the element in [nodeMapByCreatedAt] using the given key [createdAt],
     * and [TimeTicket] will be removed by [executedAt].
     * If the object exists in [nodeMapByCreatedAt] by same key [createdAt], it will return.
     *
     * @throws NoSuchElementException if [Node] doesn't exist.
     */
    fun remove(createdAt: TimeTicket, executedAt: TimeTicket): T {
        val node = nodeMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("The RhtPQMapNode by $createdAt doesn't exist.")
        node.remove(executedAt)
        return node.value
    }

    /**
     * Returns [Node.strKey] of node based on [createdAt].
     * The node will be found in [nodeMapByCreatedAt] using [createdAt]
     *
     * @throws NoSuchElementException if RHTPQMapNode doesn't exist.
     */
    fun subPathOf(createdAt: TimeTicket): String {
        return nodeMapByCreatedAt[createdAt]?.strKey
            ?: throw NoSuchElementException("RhtPQMapNode's strKey by $createdAt doesn't exist")
    }

    /**
     * Physically deletes [element] from [nodeMapByCreatedAt] and [elementQueueMapByKey]
     */
    fun delete(element: T) {
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
     * Removes the element in [elementQueueMapByKey] using the given [key] and [removedAt].
     * If the element removed successfully, removed [CrdtElement] will return.
     *
     * @throws IllegalStateException if MaxPriorityQueue doesn't exist.
     * @throws NoSuchElementException if MaxPriorityQueue is empty.
     */
    fun removeByKey(key: String, removedAt: TimeTicket): T {
        val queue = elementQueueMapByKey[key]
            ?: throw IllegalStateException("MaxPriorityQueue by $key doesn't exist")
        val node = queue.peek() ?: throw NoSuchElementException("MaxPriorityQueue is empty")
        node.remove(removedAt)
        return node.value
    }

    /**
     * Checks the element exists in [elementQueueMapByKey] using [key].
     * If the [Node] is exist, then returns true, otherwise false.
     */
    fun has(key: String): Boolean {
        val queue = elementQueueMapByKey[key] ?: return false
        val node = queue.peek() ?: return false
        return !node.isRemoved
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
    fun getKeyOfQueue(): Sequence<Node<T>> {
        return elementQueueMapByKey.values
            .asSequence()
            .map { it.element() }
    }

    override fun iterator(): Iterator<Node<T>> {
        return object : Iterator<Node<T>> {
            val pqIterators = elementQueueMapByKey.values.map { it.iterator() }
            var nodes = buildList {
                pqIterators.forEach {
                    while (it.hasNext()) {
                        add(it.next())
                    }
                }
            }
            var index = 0

            override fun hasNext() = index < nodes.size

            override fun next(): Node<T> {
                return nodes[index++]
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RhtPQMap<*>) {
            return false
        }
        return nodeMapByCreatedAt == other.nodeMapByCreatedAt
    }

    override fun hashCode(): Int {
        return nodeMapByCreatedAt.hashCode()
    }

    /**
     * [Node] is a node of [RhtPQMap].
     */
    data class Node<T : CrdtElement>(
        val strKey: String,
        val value: T,
    ) : Comparable<Node<T>> {

        /**
         * Checks whether this value was removed.
         */
        val isRemoved: Boolean
            get() = value.isRemoved

        /**
         * Removes a value base on removing time.
         */
        fun remove(removedAt: TimeTicket): Boolean {
            return value.remove(removedAt)
        }

        override fun compareTo(other: Node<T>): Int {
            return value.createdAt.compareTo(other.value.createdAt)
        }
    }
}
