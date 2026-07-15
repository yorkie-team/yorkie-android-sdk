package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.util.DataSize
import dev.yorkie.util.TreeList
import dev.yorkie.util.TreeListNode
import dev.yorkie.util.TreeListValue
import dev.yorkie.util.YorkieException

/**
 * [RgaTreeList] is a replicated growable array using an LWW position register so that
 * concurrent array moves converge.
 *
 * Each element has a stable identity held in [ElementEntry], separate from its current
 * position slot ([Node]). When two concurrent moves target the same element the one with
 * the later `executedAt` wins; the old position becomes a *dead position node*
 * (`elementEntry == null`, `positionRemovedAt` set, length 0) that is garbage-collected.
 */
internal class RgaTreeList : Iterable<RgaTreeList.Node>, GCParent<RgaTreeList.Node> {
    private val dummyHead = Node.createDummy()

    var last: Node = dummyHead
        private set

    private val nodeMapByIndex = TreeList(dummyHead.indexNode)

    /**
     * Maps a position node's `positionCreatedAt` to the node itself.
     */
    private val nodeMapByPositionCreatedAt = mutableMapOf<TimeTicket, Node>().apply {
        set(dummyHead.positionCreatedAt, dummyHead)
    }

    /**
     * Maps an element's `createdAt` to its [ElementEntry].
     */
    private val elementMapByCreatedAt = mutableMapOf<TimeTicket, ElementEntry>()

    val length
        get() = nodeMapByIndex.length

    val head
        get() = dummyHead.value

    /**
     * Returns the position identity of the last node.
     */
    val lastCreatedAt
        get() = last.positionCreatedAt

    /**
     * Returns the value of the last live node, skipping bare position nodes
     * (created by moveAfter/addDeadPosition) that have no element. Falls back
     * to the dummy head's value on an empty list.
     */
    val lastElement: CrdtElement
        get() {
            var node = last
            while (node.elementEntry == null && node != dummyHead) {
                node = requireNotNull(node.prev)
            }
            return node.value
        }

    /**
     * Adds a new node with [value] after the last node.
     */
    fun insert(value: CrdtElement) {
        insertAfter(last.positionCreatedAt, value)
    }

    /**
     * Adds a new node with [value] after the node identified by [prevCreatedAt].
     *
     * [prevCreatedAt] is resolved as a position node key first, then an element key.
     */
    fun insertAfter(
        prevCreatedAt: TimeTicket,
        value: CrdtElement,
        executedAt: TimeTicket = value.createdAt,
    ): Node {
        val anchorNode = nodeMapByPositionCreatedAt[prevCreatedAt]
            ?: elementMapByCreatedAt[prevCreatedAt]?.positionNode
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "can't find the given node createdAt: $prevCreatedAt",
            )
        val anchor = findNextBeforeExecutedAt(anchorNode, executedAt)
        val newNode = Node.create(value, value.createdAt)

        // Wire the entry BEFORE splay-tree insertion so the length calculator sees 1.
        val entry = ElementEntry(value, newNode)
        newNode.elementEntry = entry
        elementMapByCreatedAt[value.createdAt] = entry

        insertNodeIntoStructures(newNode, anchor)
        return newNode
    }

    /**
     * Walks forward from [node] skipping nodes whose `positionedAt` is after [executedAt].
     */
    private fun findNextBeforeExecutedAt(node: Node, executedAt: TimeTicket): Node {
        var current = node
        while (true) {
            val next = current.next ?: break
            if (next.positionedAt <= executedAt) break
            current = next
        }
        return current
    }

    /**
     * Links [node] into the linked list and splay tree after [anchor].
     */
    private fun insertNodeIntoStructures(node: Node, anchor: Node) {
        val anchorNext = anchor.next
        anchor.next = node
        node.prev = anchor
        node.next = anchorNext
        anchorNext?.prev = node

        if (anchor == last) {
            last = node
        }

        nodeMapByIndex.insertAfter(anchor.indexNode, node.indexNode)
        nodeMapByPositionCreatedAt[node.positionCreatedAt] = node
    }

    /**
     * Inserts a bare position node (no element) after [prevNode], marked dead at [executedAt].
     */
    private fun insertDeadPositionAfter(prevNode: Node, executedAt: TimeTicket): Node {
        val anchor = findNextBeforeExecutedAt(prevNode, executedAt)
        val node = Node.create(CrdtPrimitive(null, executedAt), executedAt)
        insertNodeIntoStructures(node, anchor)
        node.markDead(executedAt)
        nodeMapByIndex.updateWeight(node.indexNode)
        return node
    }

    /**
     * Moves the element identified by [createdAt] to after [prevCreatedAt].
     *
     * Uses an LWW position register: the winning position is the one with the latest
     * [executedAt]. Returns the dead position node the caller must register for GC, or
     * `null` when the move was already processed (idempotency).
     */
    fun moveAfter(
        prevCreatedAt: TimeTicket,
        createdAt: TimeTicket,
        executedAt: TimeTicket,
    ): Node? {
        val entry = elementMapByCreatedAt[createdAt]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "can't find the given element createdAt: $createdAt",
            )
        val prevNode = nodeMapByPositionCreatedAt[prevCreatedAt]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "can't find the previous node createdAt: $prevCreatedAt",
            )

        // Idempotency: a node with this executedAt already exists.
        if (executedAt in nodeMapByPositionCreatedAt) {
            return null
        }

        // LWW loser: superseded by a later move of the same element. Still create a bare
        // dead position node so the GC pair set is complete.
        val posMovedAt = entry.posMovedAt
        if (posMovedAt != null && executedAt <= posMovedAt) {
            return insertDeadPositionAfter(prevNode, executedAt)
        }

        // LWW winner: build the new position node carrying the element, wire the entry,
        // then kill the old position node.
        val oldPositionNode = entry.positionNode
        val anchor = findNextBeforeExecutedAt(prevNode, executedAt)
        val newPositionNode = Node.create(entry.element, executedAt)

        newPositionNode.elementEntry = entry
        entry.positionNode = newPositionNode
        entry.posMovedAt = executedAt
        entry.element.movedAt = executedAt

        insertNodeIntoStructures(newPositionNode, anchor)

        // Kill the old position node but keep it in the index tree (weight 0) until GC
        // purges it: a later insert/append may still anchor after it (e.g. it was `last`).
        oldPositionNode.markDead(executedAt)
        nodeMapByIndex.updateWeight(oldPositionNode.indexNode)

        return oldPositionNode
    }

    /**
     * Restores a dead position node from a snapshot.
     */
    fun addDeadPosition(positionCreatedAt: TimeTicket, positionRemovedAt: TimeTicket) {
        val node = Node.create(CrdtPrimitive(null, positionCreatedAt), positionCreatedAt)
        node.markDead(positionRemovedAt)

        val prevNode = last
        prevNode.next = node
        node.prev = prevNode
        last = node
        nodeMapByIndex.insertAfter(prevNode.indexNode, node.indexNode)
        nodeMapByPositionCreatedAt[positionCreatedAt] = node
    }

    /**
     * Restores a moved element's position from a snapshot.
     */
    fun addMovedElement(
        value: CrdtElement,
        positionCreatedAt: TimeTicket,
        positionMovedAt: TimeTicket,
    ) {
        val anchor = findNextBeforeExecutedAt(last, positionCreatedAt)
        val node = Node.create(value, positionCreatedAt)

        val entry = ElementEntry(value, node).apply { posMovedAt = positionMovedAt }
        node.elementEntry = entry
        elementMapByCreatedAt[value.createdAt] = entry

        insertNodeIntoStructures(node, anchor)
    }

    /**
     * Returns the current position node key for the element identified by [elemCreatedAt].
     */
    fun posCreatedAt(elemCreatedAt: TimeTicket): TimeTicket {
        val entry = elementMapByCreatedAt[elemCreatedAt]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "can't find the given element createdAt: $elemCreatedAt",
            )
        return entry.positionNode.positionCreatedAt
    }

    /**
     * Returns all nodes in linked-list order, including dead position nodes.
     */
    fun allNodes(): List<Node> {
        val result = mutableListOf<Node>()
        var current = dummyHead.next
        while (current != null) {
            result.add(current)
            current = current.next
        }
        return result
    }

    /**
     * `delete` deletes the element of the given [createdAt].
     */
    fun delete(createdAt: TimeTicket, editedAt: TimeTicket): CrdtElement {
        val entry = elementMapByCreatedAt[createdAt]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "cant find the given node: $createdAt",
            )
        val node = entry.positionNode
        val alreadyRemoved = node.isRemoved
        if (node.remove(editedAt) && !alreadyRemoved) {
            nodeMapByIndex.updateWeight(node.indexNode)
        }
        return node.value
    }

    private fun release(node: Node) {
        if (last == node) {
            last = requireNotNull(node.prev)
        }
        node.release()
        nodeMapByIndex.delete(node.indexNode)
        nodeMapByPositionCreatedAt.remove(node.positionCreatedAt)
    }

    /**
     * Returns the element of the given [createdAt].
     */
    fun get(createdAt: TimeTicket): CrdtElement? {
        return elementMapByCreatedAt[createdAt]?.element
    }

    /**
     * Returns the sub path of the given element.
     */
    fun subPathOf(createdAt: TimeTicket): String {
        val entry = elementMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("can't find the given node createdAt: $createdAt")
        return nodeMapByIndex.indexOf(entry.positionNode.indexNode).toString()
    }

    /**
     * Returns node of the given [index].
     */
    fun getByIndex(index: Int): Node? {
        if (length <= index) return null
        return nodeMapByIndex.find(index).value
    }

    /**
     * Returns the position identity of the previous node, skipping dead position nodes
     * and tombstoned elements.
     */
    fun getPrevCreatedAt(createdAt: TimeTicket): TimeTicket {
        val entry = elementMapByCreatedAt[createdAt]
            ?: throw NoSuchElementException("can't find the given node createdAt: $createdAt")
        var node: Node? = entry.positionNode
        do {
            node = node?.prev
        } while (dummyHead != node && node != null && node.isRemoved)
        return (node ?: dummyHead).positionCreatedAt
    }

    /**
     * Physically removes the element node from the list (final GC step).
     */
    fun purge(element: CrdtElement) {
        val entry = elementMapByCreatedAt[element.createdAt]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "can't find the given node createdAt: ${element.createdAt}",
            )
        release(entry.positionNode)
        elementMapByCreatedAt.remove(element.createdAt)
    }

    /**
     * Physically removes a dead position node from the list (final GC step for moves).
     */
    private fun purgeDeadPosition(positionCreatedAt: TimeTicket) {
        val node = nodeMapByPositionCreatedAt[positionCreatedAt] ?: return
        release(node)
    }

    override fun delete(node: Node) {
        purgeDeadPosition(node.positionCreatedAt)
    }

    /**
     * Removes the node at the given [index].
     */
    fun removeByIndex(index: Int, executedAt: TimeTicket): CrdtElement? {
        val node = getByIndex(index) ?: return null
        if (node.remove(executedAt)) {
            nodeMapByIndex.updateWeight(node.indexNode)
        }
        return node.value
    }

    /**
     * `set` sets the given element at the given creation time.
     */
    fun set(
        createdAt: TimeTicket,
        element: CrdtElement,
        executedAt: TimeTicket,
    ): CrdtElement {
        val entry = elementMapByCreatedAt[createdAt]
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "cant find the given node: $createdAt",
            )
        insertAfter(entry.positionNode.positionCreatedAt, element, executedAt)
        return delete(createdAt, executedAt)
    }

    override fun iterator(): Iterator<Node> {
        return object : Iterator<Node> {
            var node = nextLiveNode(dummyHead)

            override fun hasNext() = node != null

            override fun next(): Node {
                val current = requireNotNull(node)
                node = nextLiveNode(current)
                return current
            }

            private fun nextLiveNode(from: Node): Node? {
                var current = from.next
                while (current != null && current.elementEntry == null) {
                    current = current.next
                }
                return current
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RgaTreeList) {
            return false
        }
        return elementMapByCreatedAt == other.elementMapByCreatedAt
    }

    override fun hashCode(): Int {
        return elementMapByCreatedAt.hashCode()
    }

    /**
     * [ElementEntry] holds the stable identity of an element and its current position node.
     */
    class ElementEntry(val element: CrdtElement, var positionNode: Node) {
        var posMovedAt: TimeTicket? = null
    }

    /**
     * [Node] represents a position slot in the list, not necessarily a live element.
     * A dead position node has `elementEntry == null` and its `positionRemovedAt` set.
     */
    class Node private constructor(
        val value: CrdtElement,
        val positionCreatedAt: TimeTicket,
    ) : GCChild, TreeListValue {
        /**
         * The [TreeListNode] backing this node's slot in the index tree.
         */
        val indexNode = TreeListNode(this)

        var prev: Node? = null
        var next: Node? = null

        var positionRemovedAt: TimeTicket? = null
            private set

        var elementEntry: ElementEntry? = null

        val createdAt: TimeTicket
            get() = value.createdAt

        /**
         * The LWW register key used to arbitrate insertion order.
         */
        val positionedAt: TimeTicket
            get() = positionCreatedAt

        /**
         * A node is removed when it is a bare position slot (no element) or
         * its element is tombstoned. Drives the index tree's live-node weight.
         */
        override val isRemoved: Boolean
            get() = elementEntry == null || value.isRemoved

        override val removedAt: TimeTicket?
            get() = positionRemovedAt

        override val dataSize: DataSize
            get() = DataSize(
                data = 0,
                meta = TIME_TICKET_SIZE + if (positionRemovedAt != null) TIME_TICKET_SIZE else 0,
            )

        fun remove(removedAt: TimeTicket): Boolean {
            if (elementEntry == null) {
                return false
            }
            return value.remove(removedAt)
        }

        fun markDead(at: TimeTicket) {
            positionRemovedAt = at
            elementEntry = null
        }

        fun release() {
            prev?.next = next
            next?.prev = prev

            prev = null
            next = null
        }

        companion object {
            fun create(value: CrdtElement, positionCreatedAt: TimeTicket): Node {
                return Node(value, positionCreatedAt)
            }

            fun createDummy(): Node {
                val dummyValue = CrdtPrimitive(
                    _value = 1,
                    createdAt = InitialTimeTicket,
                    removedAt = InitialTimeTicket,
                )
                return Node(dummyValue, InitialTimeTicket)
            }
        }
    }
}
