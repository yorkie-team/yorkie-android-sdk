package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.NullTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.util.SplayTreeSet

/**
 * [RgaTreeList] is replicated growable array.
 */
internal class RgaTreeList : Iterable<RgaTreeList.Node> {
    private val dummyHead = Node(
        CrdtPrimitive(
            value = 1,
            createdAt = NullTimeTicket,
            _removedAt = NullTimeTicket,
        ),
    )
    var last: Node = dummyHead
        private set

    private val nodeMapByIndex = SplayTreeSet<Node> {
        if (it.value.isRemoved) 0 else 1
    }.apply {
        insert(dummyHead)
    }

    private val nodeMapByCreatedAt = mutableMapOf<TimeTicket, Node>().apply {
        set(dummyHead.createdAt, dummyHead)
    }

    val length
        get() = nodeMapByIndex.length

    val head
        get() = dummyHead.value

    val lastCreatedAt
        get() = last.createdAt

    /**
     * Adds a new node with [value] after the last node.
     */
    fun insert(value: CrdtElement) {
        insertAfter(last.createdAt, value)
    }

    /**
     * Adds a new node with [value] after the node created at [prevCreatedAt].
     */
    fun insertAfter(
        prevCreatedAt: TimeTicket,
        value: CrdtElement,
        executedAt: TimeTicket = value.createdAt,
    ) {
        val prevNode = findNextBeforeExecutedAt(prevCreatedAt, executedAt)
        val newNode = Node.createAfter(prevNode, value)
        if (prevNode == last) {
            last = newNode
        }
        nodeMapByIndex.insertAfter(prevNode, newNode)
        nodeMapByCreatedAt[newNode.createdAt] = newNode
    }

    /**
     * Returns the node by the given [createdAt] and [executedAt].
     * It passes through nodes created after [executedAt] from the
     * given node and returns the next node.
     */
    private fun findNextBeforeExecutedAt(
        createdAt: TimeTicket,
        executedAt: TimeTicket,
    ): Node {
        var node = nodeMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("can't find the given node createdAt: $createdAt")

        var nodeNext = node.next
        while (nodeNext != null && executedAt < nodeNext.positionedAt) {
            node = nodeNext
            nodeNext = node.next
        }
        return node
    }

    /**
     * Moves the given [createdAt] element after the [prevCreatedAt] element.
     */
    fun moveAfter(
        prevCreatedAt: TimeTicket,
        createdAt: TimeTicket,
        executedAt: TimeTicket,
    ) {
        val prevNode = nodeMapByCreatedAt[prevCreatedAt]
            ?: throw NoSuchElementException("can't find the given node createdAt: $prevCreatedAt")
        val node = nodeMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("can't find the given node createdAt: $createdAt")

        if (prevNode != node && node.value.movedAt < executedAt) {
            delete(node)
            insertAfter(prevNode.createdAt, node.value, executedAt)
            node.value.move(executedAt)
        }
    }

    /**
     * Physically deletes the given [element].
     */
    fun delete(element: CrdtElement) {
        val node = nodeMapByCreatedAt[element.createdAt]
            ?: throw NoSuchElementException("can't find the given createdAt: ${element.createdAt}")
        delete(node)
    }

    private fun delete(node: Node) {
        if (last == node) {
            last = requireNotNull(node.prev)
        }
        node.delete()
        nodeMapByIndex.delete(node)
        nodeMapByCreatedAt.remove(node.value.createdAt)
    }

    /**
     * Returns the element of the given [createdAt].
     */
    fun get(createdAt: TimeTicket): CrdtElement? {
        return nodeMapByCreatedAt[createdAt]?.value
    }

    /**
     * Returns the sub path of the given element.
     */
    fun subPathOf(createdAt: TimeTicket): String {
        val node = nodeMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("can't find the given node createdAt: $createdAt")
        return nodeMapByIndex.indexOf(node).toString()
    }

    /**
     * Returns node of the given index.
     */
    fun getByIndex(index: Int): Node? {
        if (length <= index) return null

        val (value, offset) = nodeMapByIndex.find(index)
        var rgaNode = nodeMapByCreatedAt[value?.value?.createdAt ?: ""]

        if ((index == 0 && rgaNode == dummyHead) || offset > 0) {
            do {
                rgaNode = rgaNode?.next
            } while (rgaNode?.isRemoved == true)
        }
        return rgaNode
    }

    /**
     * Returns a creation time of the previous node.
     */
    fun getPrevCreatedAt(createdAt: TimeTicket): TimeTicket {
        var node = nodeMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("can't find the given node createdAt: $createdAt")
        do {
            node = node.prev ?: break
        } while (dummyHead != node && node.isRemoved)
        return node.value.createdAt
    }

    /**
     * Removes the node of the given [createdAt].
     */
    fun remove(createdAt: TimeTicket, executedAt: TimeTicket): CrdtElement {
        val node = nodeMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("can't find the given node createdAt: $createdAt")

        val alreadyRemoved = node.isRemoved
        if (node.remove(executedAt) && !alreadyRemoved) {
            nodeMapByIndex.splay(node)
        }
        return node.value
    }

    /**
     * Removes the node at the given [index]
     */
    fun removeByIndex(index: Int, executedAt: TimeTicket): CrdtElement? {
        val node = getByIndex(index) ?: return null
        if (node.remove(executedAt)) {
            nodeMapByIndex.splay(node)
        }
        return node.value
    }

    override fun iterator(): Iterator<Node> {
        return object : Iterator<Node> {
            var node = dummyHead

            override fun hasNext() = node.next != null

            override fun next(): Node {
                return requireNotNull(node.next).also { node = it }
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RgaTreeList) {
            return false
        }
        return nodeMapByCreatedAt == other.nodeMapByCreatedAt
    }

    override fun hashCode(): Int {
        return nodeMapByCreatedAt.hashCode()
    }

    class Node(val value: CrdtElement) {
        var prev: Node? = null
        var next: Node? = null

        val createdAt: TimeTicket
            get() = value.createdAt

        val positionedAt: TimeTicket
            get() = value.movedAt ?: createdAt

        val isRemoved
            get() = value.isRemoved

        fun remove(removedAt: TimeTicket): Boolean {
            return value.remove(removedAt)
        }

        // NOTE(7hong13): removed comment since this function literally do 'delete' the node.
        fun delete() {
            prev?.next = next
            next?.prev = prev
        }

        companion object {
            /**
             * Creates a new node with [value] after the node [prev].
             */
            fun createAfter(prev: Node, value: CrdtElement): Node {
                val newNode = Node(value)
                val prevNext = prev.next
                prev.next = newNode
                newNode.prev = prev
                newNode.next = prevNext
                if (prevNext != null) {
                    prevNext.prev = newNode
                }
                return newNode
            }
        }
    }
}
