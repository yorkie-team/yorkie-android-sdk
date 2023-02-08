package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtObject] represents an object data type, but unlike regular JSON, it has
 * [TimeTicket]s which are created by logical clock.
 */
internal data class CrdtObject(
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
    private val rht: RhtPQMap<CrdtElement> = RhtPQMap(),
) : CrdtContainer(), Iterable<Pair<String, CrdtElement>> {
    val memberNodes
        get() = rht.toList()

    /**
     * Returns the array of keys in this object.
     */
    val keys
        get() = map { it.first }

    /**
     * Returns the sub path of the given element.
     */
    override fun subPathOf(createdAt: TimeTicket): String {
        return rht.subPathOf(createdAt)
    }

    /**
     * Physically deletes the given [element].
     */
    override fun delete(element: CrdtElement) {
        rht.delete(element)
    }

    /**
     * Sets the given element of the given key.
     */
    operator fun set(key: String, value: CrdtElement): CrdtElement? {
        return rht.set(key, value)
    }

    /**
     * Removes the element of the given key.
     */
    override fun remove(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        return rht.remove(createdAt, executedAt)
    }

    /**
     * Removes the element of the given key and execution time.
     */
    fun removeByKey(key: String, executedAt: TimeTicket): CrdtElement {
        return rht.removeByKey(key, executedAt)
    }

    /**
     * Returns the value of the given key.
     */
    operator fun get(key: String): CrdtElement {
        return rht[key]
    }

    /**
     * Returns whether the element exists of the given key or not.
     */
    fun has(key: String): Boolean {
        return rht.has(key)
    }

    /**
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtObject {
        val rhtClone = RhtPQMap<CrdtElement>().apply {
            rht.forEach { (strKey, value) ->
                set(strKey, value.deepCopy())
            }
        }
        return copy(rht = rhtClone)
    }

    /**
     * Returns the descendants of this object by traversing.
     */
    override fun getDescendants(callback: (CrdtElement, CrdtContainer) -> Boolean) {
        rht.forEach {
            val element = it.value
            if (callback(element, this)) return

            if (element is CrdtContainer) {
                element.getDescendants(callback)
            }
        }
    }

    override fun iterator(): Iterator<Pair<String, CrdtElement>> {
        val keySet = mutableSetOf<String>()
        val nodes = rht.map { it }
        var index = 0

        return object : Iterator<Pair<String, CrdtElement>> {
            override fun hasNext(): Boolean {
                while (index < nodes.size) {
                    val node = nodes[index]
                    if (!keySet.contains(node.strKey)) {
                        keySet.add(node.strKey)
                        if (!node.isRemoved) return true
                    }
                    index++
                }
                return false
            }

            override fun next(): Pair<String, CrdtElement> {
                val node = nodes[index++]
                return node.strKey to node.value
            }
        }
    }
}
