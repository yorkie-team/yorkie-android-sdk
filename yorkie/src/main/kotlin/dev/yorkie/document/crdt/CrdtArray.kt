package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [CrdtArray] represents Array data type containing logical clocks.
 */
internal class CrdtArray(
    val elements: RgaTreeList,
    createdAt: TimeTicket,
) : CrdtContainer(createdAt), Iterable<CrdtElement> {
    val head
        get() = elements.head

    val last
        get() = elements.last.value

    val length
        get() = elements.length

    val lastCreated
        get() = elements.getLastCreatedAt()

    /**
     * Returns the sub path of the given [createdAt] element.
     */
    override fun subPathOf(createdAt: TimeTicket): String? {
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
            json.joinToString(",")
        }
    }

    /**
     * Returns the JavaScript object of this array.
     */
    fun toJS(): Any {
        TODO(
            "To be implemented when it's actually needed: ref to https://github.com/google/gson",
        )
    }

    /**
     * Returns the sorted JSON encoding of this array.
     */
    override fun toSortedJson(): String {
        return toJson()
    }

    override fun deepCopy(): CrdtElement {
        TODO("To be implemented when it's actually needed")
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

    companion object {
        /**
         * Creates a new instance of [CrdtArray].
         * */
        fun create(createdAt: TimeTicket): CrdtArray {
            return CrdtArray(RgaTreeList.create(), createdAt)
        }
    }
}
