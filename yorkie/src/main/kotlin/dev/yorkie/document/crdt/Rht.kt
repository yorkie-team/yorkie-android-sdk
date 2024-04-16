package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo

/**
 * [Rht] is a replicated hash table by creation time.
 * For more details about RHT:
 * @link http://csl.skku.edu/papers/jpdc11.pdf
 */
internal class Rht : Collection<Rht.Node> {
    private val nodeMapByKey = mutableMapOf<String, Node>()
    private var numberOfRemovedElements = 0

    val nodeKeyValueMap: Map<String, String>
        get() {
            return nodeMapByKey.entries.associate { (key, node) ->
                key to node.value
            }
        }

    fun set(
        key: String,
        value: String,
        executedAt: TimeTicket,
    ) {
        val prev = nodeMapByKey[key]
        if (prev?.executedAt < executedAt) {
            if (prev?.isRemoved == false) {
                numberOfRemovedElements--
            }
            val node = Node(key, value, executedAt, false)
            nodeMapByKey[key] = node
        }
    }

    /**
     * Removes the Element of the given [key].
     */
    fun remove(key: String, executedAt: TimeTicket): String {
        val prev = nodeMapByKey[key]
        return when {
            prev == null -> {
                numberOfRemovedElements++
                nodeMapByKey[key] = Node(key, "", executedAt, true)
                ""
            }

            prev.executedAt < executedAt -> {
                if (!prev.isRemoved) {
                    numberOfRemovedElements++
                }
                nodeMapByKey[key] = Node(key, prev.value, executedAt, true)
                if (prev.isRemoved) "" else prev.value
            }

            else -> ""
        }
    }

    operator fun get(key: String): String? = nodeMapByKey[key]?.value

    fun has(key: String): Boolean = nodeMapByKey[key]?.isRemoved == false

    fun deepCopy(): Rht {
        val rht = Rht()
        nodeMapByKey.forEach {
            val node = it.value
            rht.set(node.key, node.value, node.executedAt)
        }
        return rht
    }

    /**
     * Converts the given [Rht] to XML String.
     */
    fun toXml(): String {
        return nodeMapByKey.filterValues { !it.isRemoved }.entries
            .joinToString(" ") { (key, node) ->
                "$key=\"${node.value}\""
            }
    }

    override fun iterator(): Iterator<Node> {
        return nodeMapByKey.values.iterator()
    }

    override val size: Int
        get() = nodeMapByKey.size - numberOfRemovedElements

    override fun containsAll(elements: Collection<Node>): Boolean = elements.all { contains(it) }

    override fun contains(element: Node): Boolean = nodeMapByKey[element.key]?.isRemoved == false

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

    data class Node(
        val key: String,
        val value: String,
        val executedAt: TimeTicket,
        val isRemoved: Boolean,
    )
}
