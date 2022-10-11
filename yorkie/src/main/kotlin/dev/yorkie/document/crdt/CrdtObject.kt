package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtObject] represents object datatype, but unlike regular JSON, it has time
 * tickets which is created by logical clock.
 */
internal class CrdtObject private constructor(
    createdAt: TimeTicket,
    private val memberNodes: RhtPQMap<CrdtElement>,
) : CrdtContainer(createdAt), Iterable<Pair<String, CrdtElement>> {
    val rht
        get() = memberNodes.toList()

    // NOTE(7hong13): the original comment from js-sdk is as follows:
    // `getKeys` returns array of this object.
    /**
     * Returns the array of keys in this object.
     */
    val keys
        get() = map { it.first }

    /**
     * Returns the sub path of the given element.
     */
    override fun subPathOf(createdAt: TimeTicket): String {
        return memberNodes.subPathOf(createdAt)
    }

    /**
     * Physically deletes the given [element].
     */
    override fun delete(element: CrdtElement) {
        memberNodes.delete(element)
    }

    /**
     * Sets the given element of the given key.
     */
    operator fun set(key: String, value: CrdtElement): CrdtElement? {
        return memberNodes.set(key, value)
    }

    /**
     * Removes the element of the given key.
     */
    override fun remove(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        return memberNodes.remove(createdAt, executedAt)
    }

    /**
     * Removes the element of the given key and execution time.
     */
    fun removeByKey(key: String, executedAt: TimeTicket): CrdtElement {
        return memberNodes.removeByKey(key, executedAt)
    }

    /**
     * Returns the value of the given key.
     */
    operator fun get(key: String): CrdtElement {
        return memberNodes[key]
    }

    /**
     * Returns whether the element exists of the given key or not.
     */
    fun has(key: String): Boolean {
        return memberNodes.has(key)
    }

    /**
     * Returns the JSON encoding of this object.
     */
    override fun toJson(): String {
        val json = map {
            "${it.first}:${it.second.toJson()}"
        }
        return json.joinToString(",")
    }

    /**
     * Returns the JavaScript object of this [CrdtObject].
     */
    fun toJS(): Any {
        TODO("To be implemented when it's actually needed")
    }

    /**
     * Returns the sorted JSON encoding of this object
     */
    override fun toSortedJson(): String {
        val json = keys.sorted().map {
            val node = memberNodes[it]
            "$it:${node.toSortedJson()}"
        }
        return json.joinToString(",")
    }

    /**
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtElement {
        val clone = create(createdAt)
        memberNodes.forEach {
            clone.memberNodes[it.strKey] = it.value.deepCopy()
        }
        clone.remove(removedAt)
        return clone
    }

    /**
     * Returns the descendants of this object by traversing.
     */
    override fun getDescendants(callback: (CrdtElement, CrdtContainer) -> Boolean) {
        memberNodes.forEach {
            val element = it.value
            if (callback(element, this)) return

            if (element is CrdtContainer) {
                element.getDescendants(callback)
            }
        }
    }

    override fun iterator(): Iterator<Pair<String, CrdtElement>> {
        val keySet = mutableSetOf<String>()
        val nodes = memberNodes.map { it }
        var index = 0

        return object : Iterator<Pair<String, CrdtElement>> {
            override fun hasNext(): Boolean {
                while (index < nodes.size) {
                    val node = nodes[index]
                    if (!keySet.contains(node.strKey)) {
                        keySet.add(node.strKey)
                        if (!node.isRemoved()) return true
                    }
                    index++
                }
                return false
            }

            override fun next(): Pair<String, CrdtElement> {
                val node = nodes[index++]
                return Pair(node.strKey, node.value)
            }
        }
    }

    companion object {
        /**
         * Creates a new instance of [CrdtObject].
         */
        fun create(createdAt: TimeTicket): CrdtObject {
            return CrdtObject(createdAt, RhtPQMap.create())
        }
    }
}
