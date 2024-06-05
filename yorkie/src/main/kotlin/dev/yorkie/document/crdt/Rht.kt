package dev.yorkie.document.crdt

import dev.yorkie.document.json.escapeString
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo

/**
 * [Rht] is a replicated hash table by creation time.
 * For more details about RHT:
 * @link http://csl.skku.edu/papers/jpdc11.pdf
 */
internal class Rht : Collection<RhtNode> {
    private val nodeMapByKey = mutableMapOf<String, RhtNode>()
    private var numberOfRemovedElements = 0

    val nodeKeyValueMap: Map<String, String>
        get() {
            return nodeMapByKey.filterValues { !it.isRemoved }.entries.associate { (key, node) ->
                key to node.value
            }
        }

    fun set(
        key: String,
        value: String,
        executedAt: TimeTicket,
        isRemoved: Boolean = false,
    ): RhtSetResult {
        val prev = nodeMapByKey[key]
        if (prev?.isRemoved == true && prev.executedAt < executedAt) {
            numberOfRemovedElements--
        }

        if (prev?.executedAt < executedAt) {
            val node = RhtNode(key, value, executedAt, isRemoved)
            nodeMapByKey[key] = node
            if (prev?.isRemoved == true) {
                return RhtSetResult(prev, node)
            }
            return RhtSetResult(null, node)
        }

        if (prev?.isRemoved == true) {
            return RhtSetResult(prev, null)
        }
        return RhtSetResult(null, null)
    }

    /**
     * Removes the Element of the given [key].
     */
    fun remove(key: String, executedAt: TimeTicket): List<RhtNode> {
        val prev = nodeMapByKey[key]
        return buildList {
            if (prev == null) {
                numberOfRemovedElements++
                nodeMapByKey[key] = RhtNode(key, "", executedAt, true).also { add(it) }
            } else if (prev.executedAt < executedAt) {
                if (prev.isRemoved) {
                    add(prev)
                } else {
                    numberOfRemovedElements++
                }
                nodeMapByKey[key] = RhtNode(key, prev.value, executedAt, true).also { add(it) }
            }
        }
    }

    /**
     * Deletes the given child node.
     */
    fun delete(child: RhtNode) {
        val node = nodeMapByKey[child.key] ?: return
        if (node != child) {
            return
        }
        nodeMapByKey.remove(child.key)
        numberOfRemovedElements--
    }

    operator fun get(key: String): String? = nodeMapByKey[key]?.value

    fun has(key: String): Boolean = nodeMapByKey[key]?.isRemoved == false

    fun deepCopy(): Rht {
        val rht = Rht()
        nodeMapByKey.forEach {
            val node = it.value
            rht.set(node.key, node.value, node.executedAt, node.isRemoved)
        }
        return rht
    }

    /**
     * Converts the given [Rht] to XML String.
     */
    fun toXml(): String {
        return nodeMapByKey.filterValues { !it.isRemoved }.entries
            .sortedBy { it.key }
            .joinToString(" ") { (key, node) ->
                "$key=\"${node.value}\""
            }
    }

    fun toJson(): String {
        return nodeMapByKey.filterValues { !it.isRemoved }.entries
            .joinToString(",", "{", "}") { (key, node) ->
                "\"${escapeString(key)}\":\"${escapeString(node.value)}\""
            }
    }

    override fun iterator(): Iterator<RhtNode> {
        return nodeMapByKey.values.iterator()
    }

    override val size: Int
        get() = nodeMapByKey.size - numberOfRemovedElements

    override fun containsAll(elements: Collection<RhtNode>): Boolean = elements.all { contains(it) }

    override fun contains(element: RhtNode): Boolean = nodeMapByKey[element.key]?.isRemoved == false

    override fun equals(other: Any?): Boolean {
        if (other !is Rht) {
            return false
        }
        return nodeMapByKey == other.nodeKeyValueMap
    }

    override fun hashCode(): Int {
        return nodeMapByKey.hashCode()
    }

    override fun isEmpty(): Boolean = size == 0
}

data class RhtNode(
    val key: String,
    val value: String,
    val executedAt: TimeTicket,
    val isRemoved: Boolean,
) : GCChild {

    override val removedAt: TimeTicket? = executedAt.takeIf { isRemoved }
}

data class RhtSetResult(val prev: RhtNode?, val new: RhtNode?)
