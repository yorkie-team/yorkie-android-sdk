package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.Logger.Companion.logError

/**
 * [ElementRht] is a hashtable with logical clock(Replicated hashtable).
 */
internal class ElementRht<T : CrdtElement> : Iterable<ElementRht.Node<T>> {
    private val logTag = "ElementRhtTag"

    private val nodeMapByKey: MutableMap<String, Node<T>> = mutableMapOf()

    private val nodeMapByCreatedAt: MutableMap<TimeTicket, Node<T>> = mutableMapOf()

    /**
     * Sets the [value] using the given [key].
     * If the object exists in [nodeMapByKey] by same [key] then return [CrdtElement], otherwise null.
     */
    operator fun set(key: String, value: T): T? {
        var removed: T? = null
        val node = nodeMapByKey[key]
        if (node != null && !node.isRemoved && node.remove(value.createdAt)) {
            removed = node.value
        }

        val newNode = Node(key, value)
        nodeMapByCreatedAt[value.createdAt] = newNode
        if (node == null || node.value.createdAt < value.createdAt) {
            nodeMapByKey[key] = newNode
        }
        return removed
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
            ?: throw NoSuchElementException("The ElementRhtNode by $createdAt doesn't exist.")
        node.remove(executedAt)
        return node.value
    }

    /**
     * Removes the element in [nodeMapByKey] using the given [key] and [removedAt].
     * If the element removed successfully, removed [CrdtElement] will return.
     *
     * @throws NoSuchElementException if [Node] doesn't exist.
     */
    fun removeByKey(key: String, removedAt: TimeTicket): T? {
        val node = nodeMapByKey[key]
            ?: throw NoSuchElementException("$key doesn't exist in nodeMapByKey")
        return if (node.remove(removedAt)) node.value else null
    }

    /**
     * Returns [Node.strKey] of node based on [createdAt].
     * The node will be found in [nodeMapByCreatedAt] using [createdAt]
     *
     * @throws NoSuchElementException if [Node] doesn't exist.
     */
    fun subPathOf(createdAt: TimeTicket): String {
        return nodeMapByCreatedAt[createdAt]?.strKey
            ?: throw NoSuchElementException("ElementRhtNode's strKey by $createdAt doesn't exist")
    }

    /**
     * Physically deletes [element] from [nodeMapByCreatedAt] and [nodeMapByKey]
     */
    fun delete(element: T) {
        val node = nodeMapByCreatedAt[element.createdAt]
        if (node == null) {
            logError(logTag, "fail to find ${element.createdAt}")
            return
        }

        val nodeByKey = nodeMapByKey[node.strKey]
        if (node == nodeByKey) {
            nodeMapByKey.remove(nodeByKey.strKey)
        }
        nodeMapByCreatedAt.remove(node.value.createdAt)
    }

    /**
     * Checks the element exists in [nodeMapByKey] using [key].
     * If the [Node] is exist, then returns true, otherwise false.
     */
    fun has(key: String): Boolean {
        val node = nodeMapByKey[key] ?: return false
        return !node.isRemoved
    }

    /**
     * Returns the [CrdtElement] using given [key].
     *
     * @throws NoSuchElementException if [Node] doesn't exist.
     */
    operator fun get(key: String): T {
        return nodeMapByKey[key]?.value
            ?: throw NoSuchElementException("ElementRhtNode by $key doesn't exist")
    }

    /**
     * Returns the sequence of [nodeMapByKey]'s values
     */
    fun getKeyOfQueue(): Sequence<Node<T>> {
        return nodeMapByKey.values.asSequence()
    }

    override fun iterator(): Iterator<Node<T>> {
        return nodeMapByKey.values.iterator()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ElementRht<*>) {
            return false
        }
        return nodeMapByCreatedAt == other.nodeMapByCreatedAt
    }

    override fun hashCode(): Int {
        return nodeMapByCreatedAt.hashCode()
    }

    /**
     * [Node] is a node of [ElementRht].
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
