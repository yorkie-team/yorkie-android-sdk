package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtArray] represents Array data type containing logical clocks.
 */
internal data class CrdtArray(
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
    val elements: RgaTreeList = RgaTreeList(),
) : CrdtContainer(), Iterable<CrdtElement> {
    val head
        get() = elements.head

    val last
        get() = elements.last.value

    val length
        get() = elements.length

    val lastCreatedAt
        get() = elements.lastCreatedAt

    /**
     * Returns the sub path of the given [createdAt] element.
     */
    override fun subPathOf(createdAt: TimeTicket): String {
        return elements.subPathOf(createdAt)
    }

    /**
     * Physically deletes the given [element].
     */
    override fun delete(element: CrdtElement) {
        elements.delete(element)
    }

    /**
     * Adds a new node with [value] after the node created at [prevCreatedAt].
     */
    fun insertAfter(prevCreatedAt: TimeTicket, value: CrdtElement) {
        elements.insertAfter(prevCreatedAt, value)
    }

    /**
     * Moves the given [createdAt] element after the [prevCreatedAt] element.
     */
    fun moveAfter(prevCreatedAt: TimeTicket, createdAt: TimeTicket, executedAt: TimeTicket) {
        elements.moveAfter(prevCreatedAt, createdAt, executedAt)
    }

    /**
     * Returns the element of the given [createdAt].
     */
    operator fun get(createdAt: TimeTicket): CrdtElement? {
        val node = elements.get(createdAt)
        if (node?.isRemoved == true) return null
        return node
    }

    /**
     * Returns the element of the given [index].
     */
    operator fun get(index: Int): CrdtElement? {
        val node = elements.getByIndex(index)
        return node?.value
    }

    /**
     * Returns a creation time of the previous node.
     */
    fun getPrevCreatedAt(createdAt: TimeTicket): TimeTicket {
        return elements.getPrevCreatedAt(createdAt)
    }

    /**
     * Removes the node of the given [createdAt].
     */
    override fun remove(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        return elements.remove(createdAt, executedAt)
    }

    /**
     * Removes the element of given [index] and [executedAt].
     */
    fun removeByIndex(index: Int, executedAt: TimeTicket): CrdtElement? {
        return elements.removeByIndex(index, executedAt)
    }

    /**
     * Traverses the descendants of this array.
     */
    override fun getDescendants(callback: (CrdtElement, CrdtContainer) -> Boolean) {
        for (node in elements) {
            val element = node.value
            if (callback(element, this)) return

            if (element is CrdtContainer) {
                element.getDescendants(callback)
            }
        }
    }

    /**
     * Returns the JSON encoding of this array.
     */
    override fun toJson(): String {
        val json = mutableListOf<String>()
        return forEach { json.add(it.toJson()) }.run {
            json.joinToString(",", "[", "]")
        }
    }

    override fun deepCopy(): CrdtElement {
        val clone = copy(elements = RgaTreeList())
        elements.forEach { node ->
            clone.elements.insertAfter(clone.lastCreatedAt, node.value.deepCopy())
        }
        return clone
    }

    override fun iterator(): Iterator<CrdtElement> {
        return object : Iterator<CrdtElement> {
            val values = elements.map { it.value }
            var index = 0

            override fun hasNext(): Boolean {
                while (index < values.size) {
                    if (!values[index].isRemoved) return true
                    index++
                }
                return false
            }

            override fun next(): CrdtElement {
                return values[index++]
            }
        }
    }
}
