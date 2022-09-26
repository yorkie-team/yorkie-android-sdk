package dev.yorkie.document.crdt

import android.util.Log
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.MaxPriorityQueue
import dev.yorkie.util.PQNode


/**
 * RHTPQMap is replicated hash table with priority queue by creation time.
 *
 * @internal
 */
internal class RHTPQMap {
    private val logTag = "RHTPQMap"

    private val elementQueueMapByKey: MutableMap<String, MaxPriorityQueue<PQNode<TimeTicket, CrdtElement>>> =
        mutableMapOf()
    private val nodeMapByCreatedAt: MutableMap<String, RHTPQMapNode> = mutableMapOf()

    /**
     * `set` sets the value of the given key.
     *
     * @param key to get queue object from [elementQueueMapByKey]
     * @param value The CrdtElement object to set [elementQueueMapByKey]
     * @return CrdtElement if the object exist in [elementQueueMapByKey] by same key, it will return.
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
     * `setInternal` sets the value of the given key internally.
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
        nodeMapByCreatedAt[value.createdAt.toIDString()] = node
    }

    /**
     * `delete` deletes deletes the Element of the given key.
     *
     * @param createdAt to find node from [nodeMapByCreatedAt]
     * @param executedAt The TimeTicket object to remove from node.
     * @return CrdtElement if the object exist in [elementQueueMapByKey] by same key, it will return.
     * @throws IllegalStateException if RHTPQMapNode doesn't exist.
     */
    fun delete(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        val createdAtId = createdAt.toIDString()
        val nodeMap = nodeMapByCreatedAt
        if (!nodeMap.contains(createdAtId)) {
            Log.e(logTag, "fail to find $createdAtId")
        }

        val node = nodeMap[createdAtId]
            ?: throw IllegalStateException("The RHTPQMapNode by $createdAtId doesn't exist.")
        node.remove(executedAt)
        return node.value
    }

    /**
     * `keyOf` returns a key of node based on creation time
     *
     * @param createdAt to find node from [nodeMapByCreatedAt]
     * @return RHTPQMapNode's strKey
     */
    fun keyOf(createdAt: TimeTicket): String {
        return nodeMapByCreatedAt[createdAt.toIDString()]?.strKey
            ?: throw IllegalStateException("RHTPQMapNode's strKey by $createdAt doesn't exist")
    }

    /**
     * `purge` physically purge child element.
     *
     * @param element to purge child element from [nodeMapByCreatedAt] and [elementQueueMapByKey]
     */
    fun purge(element: CrdtElement) {
        val nodeMap = nodeMapByCreatedAt
        val node = nodeMap[element.createdAt.toIDString()]

        if (node == null) {
            Log.e(logTag, "fail to find ${element.createdAt.toIDString()}")
            return
        }

        val queue = elementQueueMapByKey[node.strKey]
        if (queue == null) {
            Log.e(logTag, "fail to find queue of ${node.strKey}")
            return
        }

        queue.remove(node)
        nodeMap.remove(node.value.createdAt.toIDString())
    }

    /**
     * `deleteByKey` deletes the Element of the given key and removed time.
     *
     * @param key to get queue object from [elementQueueMapByKey]
     * @param removedAt The TimeTicket object to remove from node
     * @return CrdtElement the deleted element
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
     * `has` returns whether the element exists of the given key or not.
     *
     * @param key to get queue object from [elementQueueMapByKey]
     * @return true if the RHTPQMapNode wasn't removed, otherwise false.
     */
    fun has(key: String): Boolean {
        val queue = elementQueueMapByKey[key] ?: return false
        val node = queue.peek() as RHTPQMapNode
        return !node.isRemoved()
    }

    /**
     * `get` returns the value of the given key.
     *
     * @param key to get queue object from [elementQueueMapByKey]
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
