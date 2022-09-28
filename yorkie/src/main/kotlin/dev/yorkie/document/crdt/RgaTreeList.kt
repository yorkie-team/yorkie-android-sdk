package dev.yorkie.document.crdt

import androidx.annotation.VisibleForTesting
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.util.SplayTreeSet

/**
 * RgaTreeList is replicated growable array.
 */
internal class RgaTreeList {
    val dummyHead = RgaTreeListNode(Primitive.of(1, InitialTimeTicket)).apply {
        value.removedAt = InitialTimeTicket
    }
    var last: RgaTreeListNode = dummyHead
        private set

    private val nodeMapByIndex = SplayTreeSet<CrdtElement> { if (it.isRemoved) 0 else 1 }.apply {
        insert(dummyHead.value)
    }
    private val nodeMapByCreatedAt = mutableMapOf<String, RgaTreeListNode>().apply {
        set(dummyHead.createdAt.toIDString(), dummyHead)
    }

    val length
        get() = nodeMapByIndex.length

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
        val newNode = RgaTreeListNode.createAfter(prevNode, value)
        if (prevNode == last) {
            last = newNode
        }
        nodeMapByIndex.insertAfter(prevNode.value, newNode.value)
        nodeMapByCreatedAt[newNode.createdAt.toIDString()] = newNode
    }

    /**
     * Returns the node by the given [createdAt] and [executedAt].
     * It passes through nodes created after [executedAt] from the
     * given node and returns the next node.
     */
    private fun findNextBeforeExecutedAt(
        createdAt: TimeTicket,
        executedAt: TimeTicket,
    ): RgaTreeListNode {
        var node = nodeMapByCreatedAt[createdAt.toIDString()]
            ?: error("can't find the given node createdAt: ${createdAt.toIDString()}")

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
        val prevNode = nodeMapByCreatedAt[prevCreatedAt.toIDString()]
            ?: error("can't find the given node createdAt: ${prevCreatedAt.toIDString()}")
        val node = nodeMapByCreatedAt[createdAt.toIDString()]
            ?: error("can't find the given node createdAt: ${createdAt.toIDString()}")

        if (prevNode != node &&
            (node.value.movedAt == null || checkNotNull(node.value.movedAt) < executedAt)
        ) {
            release(node)
            insertAfter(prevNode.createdAt, node.value, executedAt)
            node.value.movedAt = executedAt
        }
    }

    /**
     * Physically purges element.
     */
    fun purge(element: CrdtElement) {
        val node = nodeMapByCreatedAt[element.createdAt.toIDString()]
            ?: error("can't find the given createdAt: ${element.createdAt.toIDString()}")
        release(node)
    }

    private fun release(node: RgaTreeListNode) {
        if (last == node) {
            last = requireNotNull(node.prev)
        }
        node.release()
        nodeMapByIndex.delete(node.value)
        nodeMapByCreatedAt.remove(node.value.createdAt.toIDString())
    }

    /**
     * Returns the element of the given [createdAt].
     */
    fun get(createdAt: TimeTicket): CrdtElement? {
        return nodeMapByCreatedAt[createdAt.toIDString()]?.value
    }

    /**
     * Returns the key based on [createdAt] value of the node.
     */
    fun keyOf(createdAt: TimeTicket): String? {
        val node = nodeMapByCreatedAt[createdAt.toIDString()]
            ?: return null
        return nodeMapByIndex.indexOf(node.value).toString()
    }

    /**
     * Returns node of the given index.
     */
    fun getByIndex(index: Int): RgaTreeListNode? {
        if (length <= index) return null

        val (value, offset) = nodeMapByIndex.find(index)
        var rgaNode = nodeMapByCreatedAt[value?.createdAt ?: ""]

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
        var node = nodeMapByCreatedAt[createdAt.toIDString()]
            ?: error("can't find the given node createdAt: ${createdAt.toIDString()}")
        do {
            node = node.prev ?: break
        } while (dummyHead != node && node.isRemoved)
        return node.value.createdAt
    }

    /**
     * Deletes the node of the given creation time.
     */
    fun delete(createdAt: TimeTicket, editedAt: TimeTicket): CrdtElement {
        val node = nodeMapByCreatedAt[createdAt.toIDString()]
            ?: error("can't find the given node createdAt: ${createdAt.toIDString()}")

        val alreadyRemoved = node.isRemoved
        if (node.remove(editedAt) && !alreadyRemoved) {
            nodeMapByIndex.splay(node.value)
        }
        return node.value
    }

    /**
     * Deletes the node at the given [index]
     */
    fun deleteByIndex(index: Int, editedAt: TimeTicket): CrdtElement? {
        val node = getByIndex(index) ?: return null
        if (node.remove(editedAt)) {
            nodeMapByIndex.splay(node.value)
        }
        return node.value
    }

    @VisibleForTesting
    fun getNodesInListExceptHead(): List<RgaTreeListNode> {
        return buildList {
            var curr = dummyHead.next
            while (curr != null) {
                add(curr)
                curr = curr.next
            }
        }
    }

    companion object {
        fun create(): RgaTreeList {
            return RgaTreeList()
        }
    }

    @VisibleForTesting
    data class RgaTreeListNode(val value: CrdtElement) {
        var prev: RgaTreeListNode? = null
        var next: RgaTreeListNode? = null

        val createdAt: TimeTicket
            get() = value.createdAt

        val positionedAt: TimeTicket
            get() = value.movedAt ?: createdAt

        val isRemoved
            get() = value.isRemoved

        fun remove(removedAt: TimeTicket): Boolean {
            return value.remove(removedAt)
        }

        /**
         * Releases previous and next node.
         */
        fun release() {
            prev?.next = next
            next?.prev = prev
        }

        companion object {
            /**
             * Creates a new node with [value] after the node [prev].
             */
            fun createAfter(prev: RgaTreeListNode, value: CrdtElement): RgaTreeListNode {
                val newNode = RgaTreeListNode(value)
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
