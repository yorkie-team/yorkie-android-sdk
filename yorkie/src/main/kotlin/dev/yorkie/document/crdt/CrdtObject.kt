package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize

/**
 * [CrdtObject] represents an object data type, but unlike regular JSON, it has
 * [TimeTicket]s which are created by logical clock.
 */
internal data class CrdtObject(
    override val createdAt: TimeTicket,
    override var movedAt: TimeTicket? = null,
    override var removedAt: TimeTicket? = null,
    val memberNodes: ElementRht<CrdtElement> = ElementRht(),
) : CrdtContainer(), Iterable<Pair<String, CrdtElement>> {
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
    override fun purge(element: CrdtElement) {
        memberNodes.purge(element)
    }

    /**
     * Sets the given element of the given key.
     */
    fun set(
        key: String,
        value: CrdtElement,
        executedAt: TimeTicket,
    ): CrdtElement? {
        return memberNodes.set(key, value, executedAt)
    }

    /**
     * Removes the element of the given key.
     */
    override fun delete(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        return memberNodes.delete(createdAt, executedAt)
    }

    /**
     * Removes the element of the given key and execution time.
     */
    fun removeByKey(key: String, executedAt: TimeTicket): CrdtElement? {
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
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtObject {
        return copy(memberNodes = memberNodes.deepCopy())
    }

    override fun getDataSize(): DataSize = DataSize(
        data = 0,
        meta = getMetaUsage(),
    )

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
        val filteredNodes = memberNodes.filter { node ->
            !node.isRemoved
        }
        return filteredNodes.map { it.strKey to it.value }.iterator()
    }
}
