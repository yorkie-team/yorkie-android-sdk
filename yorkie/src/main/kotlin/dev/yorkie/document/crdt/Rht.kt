package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo

/**
 * [Rht] is a replicated hash table by creation time.
 * For more details about RHT:
 * @link http://csl.skku.edu/papers/jpdc11.pdf
 */
internal class Rht : Iterable<Rht.Node> {
    private val nodeMapByKey = mutableMapOf<String, Node>()

    val nodeKeyValueMap: Map<String, String>
        get() {
            return nodeMapByKey.map { (key, node) ->
                key to node.value
            }.toMap()
        }

    fun set(key: String, value: String, executedAt: TimeTicket) {
        val prev = nodeMapByKey[key]
        if (prev?.executedAt < executedAt) {
            val node = Node(key, value, executedAt)
            nodeMapByKey[key] = node
        }
    }

    operator fun get(key: String): String? = nodeMapByKey[key]?.value

    fun has(key: String): Boolean = key in nodeMapByKey

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
        return nodeKeyValueMap.entries.joinToString(" ") { (key, value) ->
            "$key=\"$value\""
        }
    }

    override fun iterator(): Iterator<Node> {
        return nodeMapByKey.values.iterator()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Rht) {
            return false
        }
        return nodeMapByKey == other.nodeKeyValueMap
    }

    override fun hashCode(): Int {
        return nodeMapByKey.hashCode()
    }

    data class Node(val key: String, val value: String, val executedAt: TimeTicket)
}
