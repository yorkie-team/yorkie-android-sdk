package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize

/**
 * [CrdtArray] represents an array data type containing [CrdtElement]s.
 */
internal data class CrdtArray(
    override var createdAt: TimeTicket,
    override var movedAt: TimeTicket? = null,
    override var removedAt: TimeTicket? = null,
    val elements: RgaTreeList = RgaTreeList(),
) : CrdtContainer(), Iterable<CrdtElement> {
    val head
        get() = elements.head

    /**
     * The value of the last live element, skipping bare position nodes left by
     * moves. Not anchor-safe: use [lastCreatedAt] for anchoring inserts.
     */
    val last
        get() = elements.lastElement

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
     * `delete` deletes the element of the given creation time.
     */
    override fun delete(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        return elements.delete(createdAt, executedAt)
    }

    /**
     * Adds a new node with [value] after the node created at [prevCreatedAt].
     */
    fun insertAfter(
        prevCreatedAt: TimeTicket,
        value: CrdtElement,
        executedAt: TimeTicket = value.createdAt,
    ) {
        elements.insertAfter(prevCreatedAt, value, executedAt)
    }

    /**
     * Moves the given [createdAt] element after the [prevCreatedAt] element using an LWW
     * position register. Returns the displaced dead position node the caller must register
     * for GC, or `null` when the move lost the LWW race / was already processed.
     */
    fun moveAfter(
        prevCreatedAt: TimeTicket,
        createdAt: TimeTicket,
        executedAt: TimeTicket,
    ): RgaTreeList.Node? {
        return elements.moveAfter(prevCreatedAt, createdAt, executedAt)
    }

    /**
     * Returns the underlying [RgaTreeList], used as the [GCParent] when registering dead
     * position nodes.
     */
    fun getRGATreeList(): RgaTreeList = elements

    /**
     * Returns all nodes in linked-list order, including dead position nodes.
     */
    fun getAllRGANodes(): List<RgaTreeList.Node> = elements.allNodes()

    /**
     * Returns the current position node key for the element identified by [elemCreatedAt].
     */
    fun posCreatedAt(elemCreatedAt: TimeTicket): TimeTicket {
        return elements.posCreatedAt(elemCreatedAt)
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
     * `set` sets the given element at the given position of the creation time.
     */
    fun set(
        createdAt: TimeTicket,
        value: CrdtElement,
        executedAt: TimeTicket,
    ): CrdtElement {
        return elements.set(createdAt, value, executedAt)
    }

    /**
     * Returns a creation time of the previous node.
     */
    fun getPrevCreatedAt(createdAt: TimeTicket): TimeTicket {
        return elements.getPrevCreatedAt(createdAt)
    }

    /**
     * `purge` physically purges element.
     */
    override fun purge(element: CrdtElement) {
        elements.purge(element)
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

    override fun deepCopy(): CrdtElement {
        // Iterate ALL position nodes (including moved and dead ones) and reconstruct each
        // with its position timestamps. Copying only live nodes would lose moved positions
        // and dead slots, so a later move on the clone could anchor after a wrong position.
        return copy(
            elements = RgaTreeList().apply {
                this@CrdtArray.elements.allNodes().forEach { node ->
                    val entry = node.elementEntry
                    val positionRemovedAt = node.positionRemovedAt
                    when {
                        entry != null -> {
                            val posMovedAt = entry.posMovedAt
                            if (posMovedAt != null) {
                                addMovedElement(
                                    value = entry.element.deepCopy(),
                                    positionCreatedAt = node.positionCreatedAt,
                                    positionMovedAt = posMovedAt,
                                )
                            } else {
                                insertAfter(lastCreatedAt, entry.element.deepCopy())
                            }
                        }

                        positionRemovedAt != null -> {
                            addDeadPosition(
                                positionCreatedAt = node.positionCreatedAt,
                                positionRemovedAt = positionRemovedAt,
                            )
                        }
                    }
                }
            },
        )
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

    /**
     * `getDataSize` returns the data usage of this element.
     */
    override fun getDataSize(): DataSize = DataSize(
        data = 0,
        meta = getMetaUsage(),
    )
}
