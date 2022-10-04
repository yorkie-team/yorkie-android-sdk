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
        get() = elements.dummyHead.value

    val last
        get() = elements.last.value

    val length
        get() = elements.length

    /**
     * Returns the sub path of the given [createdAt] element.
     */
    override fun subPathOf(createdAt: TimeTicket): String? {
        return elements.subPathOf(createdAt)
    }

    // NOTE(7hong13): Original comment from JS SDK is as follows:
    // `purge` physically purge child element.
    /**
     * Physically purges [elements].
     */
    override fun purge(element: CrdtElement) {
        elements.purge(element)
    }

    // NOTE(7hong13): Original comment from JS SDK is as follows:
    // `insertAfter` adds a new node with the value after the given node.
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
    fun get(createdAt: TimeTicket): CrdtElement? {
        val node = elements.get(createdAt)
        if (node?.isRemoved == true) return null
        return node
    }

    /**
     * Returns the element of the given [index].
     */
    fun getByIndex(index: Int): CrdtElement? {
        val node = elements.getByIndex(index)
        return node?.value
    }

    // NOTE(7hong13): Original comment from JS SDK is as follows:
    // `getPrevCreatedAt` returns the creation time of the previous element of the given element.
    /**
     * Returns a creation time of the previous node.
     */
    fun getPrevCreatedAt(createdAt: TimeTicket): TimeTicket {
        return elements.getPrevCreatedAt(createdAt)
    }

    // NOTE(7hong13): Original comment from JS SDK is as follows:
    // `delete` deletes the element of the given index.
    /**
     * Deletes the node of the given creation time.
     */
    override fun delete(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        return elements.delete(createdAt, executedAt)
    }

    /**
     * Deletes the element of given [index] and [editedAt].
     */
    fun deleteByIndex(index: Int, editedAt: TimeTicket): CrdtElement? {
        return elements.deleteByIndex(index, editedAt)
    }

    /**
     * Traverse the descendants of this array.
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

    // TODO(7hong13): implement toJS method (json parser)
    /**
     * Returns the javascript object of this array.
     */
    fun toJS(): Any {
        return ""
    }

    /**
     * Returns the sorted JSON encoding of this array.
     */
    override fun toSortedJson(): String {
        return this.toJson()
    }

    // TODO(7hong13): implement deepCopy method
    override fun deepCopy(): CrdtElement {
        return Primitive(1, TimeTicket.InitialTimeTicket)
    }

    override fun iterator(): Iterator<CrdtElement> {
        return object : Iterator<CrdtElement> {
            var node = elements.firstOrNull()

            override fun hasNext() = node?.isRemoved == false

            override fun next(): CrdtElement {
                return requireNotNull(node).value.also {
                    elements.drop(1)
                    node = elements.firstOrNull()
                }
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
