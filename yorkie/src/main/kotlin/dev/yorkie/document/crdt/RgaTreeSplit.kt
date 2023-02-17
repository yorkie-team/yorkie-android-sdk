package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.MaxTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.util.SplayTreeSet
import java.util.TreeMap

internal typealias RgaTreeSplitNodeRange = Pair<RgaTreeSplitNodePos, RgaTreeSplitNodePos>

/**
 * [RgaTreeSplit] is a block-based list with improved index-based lookup in RGA.
 * The difference from [RgaTreeList] is that it has data on a block basis to
 * reduce the size of CRDT metadata. When an edit occurs on a block,
 * the block is split.
 */
internal class RgaTreeSplit<T : RgaTreeSplitValue<T>> : Iterable<RgaTreeSplitNode<T>> {
    @Suppress("UNCHECKED_CAST")
    val head = RgaTreeSplitNode(InitialNodeID, InitialNodeValue) as RgaTreeSplitNode<T>
    private val treeByIndex =
        SplayTreeSet<RgaTreeSplitNode<T>> { it.contentLength }.apply { insert(head) }
    private val treeByID = TreeMap<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>().apply {
        put(head.id, head)
    }
    private val removedNodeMap = mutableMapOf<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>()

    val length
        get() = treeByIndex.length

    val removedNodesLength
        get() = removedNodeMap.size

    /**
     * Does following stpes.
     * 1. Split nodes with the given [range].
     * 2. Delete between the given [range].
     * 3. Insert a new node.
     * 4. Add removed nodes.
     */
    fun edit(
        range: RgaTreeSplitNodeRange,
        executedAt: TimeTicket,
        value: T?,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): Triple<RgaTreeSplitNodePos, Map<ActorID, TimeTicket>, List<ContentChange>> {
        // 1. Split nodes.
        val (toLeft, toRight) = findNodeWithSplit(range.second, executedAt)
        val (fromLeft, fromRight) = findNodeWithSplit(range.first, executedAt)

        // 2. Delete between from and to.
        val nodesToDelete = findBetween(fromRight, toRight)
        val (changes, latestCreatedAtMap, removedNodeMapByNodeKey) = deleteNodes(
            nodesToDelete,
            executedAt,
            latestCreatedAtMapByActor,
        )
        val caretID = toRight?.id ?: toLeft.id
        var caretPos = RgaTreeSplitNodePos(caretID, 0)

        // 3. Insert a new node.
        value?.let {
            val index = findIndexFromNodePos(fromLeft.createRange().second, true)
            val inserted = insertAfter(
                fromLeft,
                RgaTreeSplitNode(
                    RgaTreeSplitNodeID(executedAt, 0),
                    it,
                ),
            )
            if (changes.isNotEmpty() && changes.last().from == index) {
                changes[changes.lastIndex] = changes.last().copy(content = it.toString())
            } else {
                changes.add(
                    ContentChange(
                        executedAt.actorID,
                        index,
                        index,
                        value.toString(),
                    ),
                )
            }
            caretPos = RgaTreeSplitNodePos(inserted.id, inserted.contentLength)
        }

        // 4. Add removed nodes.
        removedNodeMapByNodeKey.forEach {
            removedNodeMap[it.key] = it.value
        }

        return Triple(caretPos, latestCreatedAtMap, changes)
    }

    /**
     * Splits and returns nodes at the given [pos].
     */
    fun findNodeWithSplit(
        pos: RgaTreeSplitNodePos,
        executedAt: TimeTicket,
    ): Pair<RgaTreeSplitNode<T>, RgaTreeSplitNode<T>?> {
        val absoluteID = pos.absoluteID
        var node = findFloorNodePreferToLeft(absoluteID)
        val relativeOffSet = absoluteID.offset - node.id.offset
        splitNode(node, relativeOffSet)

        while (node.hasNext && executedAt < node.next?.createdAt) {
            node = node.next ?: break
        }
        return node to node.next
    }

    private fun findFloorNodePreferToLeft(id: RgaTreeSplitNodeID): RgaTreeSplitNode<T> {
        var node = findFloorNode(id)
            ?: throw NoSuchElementException("the node of the given id should be found: $id")
        if (id.offset > 0 && node.id.offset == id.offset) {
            if (!node.hasInsertionPrev) return node
            node = requireNotNull(node.insertionPrev)
        }
        return node
    }

    private fun findFloorNode(id: RgaTreeSplitNodeID): RgaTreeSplitNode<T>? {
        val entry = treeByID.floorEntry(id) ?: return null
        return if (entry.key != id && !entry.key.hasSameCreatedAt(id)) {
            null
        } else {
            entry.value
        }
    }

    private fun splitNode(node: RgaTreeSplitNode<T>, offset: Int): RgaTreeSplitNode<T>? {
        if (offset > node.contentLength) {
            throw IllegalArgumentException("offset should be less than or equal to length")
        }
        if (offset == 0) {
            return node
        } else if (offset == node.contentLength) {
            return node.next
        }

        val splitNode = node.split(offset)
        treeByIndex.updateWeight(splitNode)
        insertAfter(node, splitNode)
        node.insertionNext?.setInsertionPrev(splitNode)
        splitNode.setInsertionPrev(node)

        return splitNode
    }

    /**
     * Insert the [newNode] after the given [prevNode].
     */
    fun insertAfter(
        prevNode: RgaTreeSplitNode<T>,
        newNode: RgaTreeSplitNode<T>,
    ): RgaTreeSplitNode<T> {
        val next = prevNode.next
        newNode.setPrev(prevNode)
        next?.setPrev(newNode)

        treeByID[newNode.id] = newNode
        treeByIndex.insertAfter(prevNode, newNode)
        return newNode
    }

    /**
     * Returns nodes between [fromNode] and [toNode].
     */
    fun findBetween(
        fromNode: RgaTreeSplitNode<T>?,
        toNode: RgaTreeSplitNode<T>?,
    ): List<RgaTreeSplitNode<T>> {
        var current: RgaTreeSplitNode<T>? = fromNode
        return buildList {
            while (current != toNode) {
                add(current ?: break)
                current = current?.next ?: break
            }
        }
    }

    private fun deleteNodes(
        candidates: List<RgaTreeSplitNode<T>>,
        executedAt: TimeTicket,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>?,
    ): Triple<
        MutableList<ContentChange>,
        Map<ActorID, TimeTicket>,
        Map<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>,
        > {
        if (candidates.isEmpty()) {
            return Triple(mutableListOf(), emptyMap(), emptyMap())
        }

        // There are 2 types of nodes in [candidates]: should delete, should not delete.
        // [nodesToKeep] contains nodes that should not be deleted.
        // It is used to find the boundary of the range to be deleted.
        val (nodesToDelete, nodesToKeep) = filterNodes(
            candidates,
            executedAt,
            latestCreatedAtMapByActor,
        )
        val createdAtMapByActor = mutableMapOf<ActorID, TimeTicket>()
        val removedNodeMap = mutableMapOf<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>()

        // First, we need to collect indexes for change.
        val changes = makeChanges(nodesToKeep, executedAt).toMutableList()
        nodesToDelete.forEach { node ->
            // Then, make nodes be tombstones and map that.
            val actorID = node.createdAt.actorID
            if (!createdAtMapByActor.containsKey(actorID) ||
                createdAtMapByActor[actorID] < node.id.createdAt
            ) {
                createdAtMapByActor[actorID] = node.id.createdAt
            }
            removedNodeMap[node.id] = node
            node.remove(executedAt)
        }
        // Finally, remove index nodes of tombstones.
        deleteIndexNodes(nodesToKeep)

        return Triple(changes, createdAtMapByActor, removedNodeMap)
    }

    private fun filterNodes(
        candidates: List<RgaTreeSplitNode<T>>,
        executedAt: TimeTicket,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>?,
    ): Pair<List<RgaTreeSplitNode<T>>, List<RgaTreeSplitNode<T>?>> {
        val isRemote = latestCreatedAtMapByActor != null
        val nodesToDelete = mutableListOf<RgaTreeSplitNode<T>>()
        val nodesToKeep = mutableListOf<RgaTreeSplitNode<T>?>()

        val (leftEdge, rightEdge) = findEdgesOfCandidates(candidates)
        nodesToKeep.add(leftEdge)

        candidates.forEach { node ->
            val actorID = node.createdAt.actorID
            val latestCreatedAt =
                if (isRemote) {
                    latestCreatedAtMapByActor?.get(actorID) ?: InitialTimeTicket
                } else {
                    MaxTimeTicket
                }
            if (node.canDelete(executedAt, latestCreatedAt)) {
                nodesToDelete.add(node)
            } else {
                nodesToKeep.add(node)
            }
        }
        nodesToKeep.add(rightEdge)

        return nodesToDelete to nodesToKeep
    }

    /**
     * Finds the edges outside [candidates].
     * If right edge is null, it means [candidates] contains the end of text.
     */
    private fun findEdgesOfCandidates(
        candidates: List<RgaTreeSplitNode<T>>,
    ): Pair<RgaTreeSplitNode<T>, RgaTreeSplitNode<T>?> {
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("findEdgesOfCandidates error: candidates is empty")
        }
        return requireNotNull(candidates.first().prev) to candidates.last().next
    }

    private fun makeChanges(
        boundaries: List<RgaTreeSplitNode<T>?>,
        executedAt: TimeTicket,
    ): List<ContentChange> {
        return buildList {
            var (fromIndex, toIndex) = 0 to 0
            for (index in 0..boundaries.size - 2) {
                val leftBoundary = boundaries[index]
                val rightBoundary = boundaries[index + 1]
                if (leftBoundary?.next == rightBoundary) continue

                fromIndex =
                    findIndexesFromRange(requireNotNull(leftBoundary?.next).createRange()).first
                toIndex = if (rightBoundary == null) {
                    treeByIndex.length
                } else {
                    findIndexesFromRange(requireNotNull(rightBoundary.prev).createRange()).second
                }
            }
            if (fromIndex < toIndex) {
                add(ContentChange(executedAt.actorID, fromIndex, toIndex))
            }
        }.reversed()
    }

    fun findIndexesFromRange(range: RgaTreeSplitNodeRange): Pair<Int, Int> {
        val (fromPos, toPos) = range
        return findIndexFromNodePos(fromPos, false) to findIndexFromNodePos(toPos, true)
    }

    private fun findIndexFromNodePos(
        pos: RgaTreeSplitNodePos,
        preferToLeft: Boolean,
    ): Int {
        val absoluteID = pos.absoluteID
        val node = if (preferToLeft) {
            findFloorNodePreferToLeft(absoluteID)
        } else {
            findFloorNode(absoluteID)
        } ?: throw NoSuchElementException("the node of the given ID should be found: $absoluteID")

        val index = treeByIndex.indexOf(node)
        val offset = if (node.isRemoved) 0 else absoluteID.offset - node.id.offset
        return index + offset
    }

    /**
     * Clears the index nodes of the given deletion [boundaries].
     * The [boundaries] mean the nodes that will not be deleted in the range.
     */
    private fun deleteIndexNodes(boundaries: List<RgaTreeSplitNode<T>?>) {
        for (index in 0..boundaries.size - 2) {
            val leftBoundary = boundaries[index]
            val rightBoundary = boundaries[index + 1]
            // If there is no node to delete between boundaries, do nothing.
            if (leftBoundary?.next != rightBoundary) {
                treeByIndex.cutOffRange(requireNotNull(leftBoundary), rightBoundary)
            }
        }
    }

    /**
     * Finds [RgaTreeSplitNodePos] of the given [index].
     */
    fun findNodePos(index: Int): RgaTreeSplitNodePos {
        val (node, offset) = treeByIndex.find(index)
        return node?.let {
            RgaTreeSplitNodePos(it.id, offset)
        } ?: throw NoSuchElementException("no node found with the given index: $index")
    }

    /**
     * Finds the node of the given [id].
     */
    fun findNode(id: RgaTreeSplitNodeID): RgaTreeSplitNode<T> {
        return requireNotNull(findFloorNode(id))
    }

    /**
     * Physically deletes nodes that have been removed.
     */
    fun deleteTextNodesWithGarbage(ticket: TimeTicket): Int {
        var count = 0
        removedNodeMap.toMap().forEach {
            val node = it.value
            if (node.isRemoved && node.removedAt <= ticket) {
                treeByIndex.delete(node)
                delete(node)
                treeByID.remove(node.id)
                removedNodeMap.remove(node.id)
                count++
            }
        }
        return count
    }

    /**
     * Physically deletes the given node from this [RgaTreeSplit].
     */
    fun delete(node: RgaTreeSplitNode<T>) {
        val prev = node.prev
        val next = node.next
        val insertionPrev = node.insertionPrev
        val insertionNext = node.insertionNext

        prev?.setNext(next)
        next?.setPrev(prev)
        node.setPrev(null)
        node.setNext(null)
        insertionPrev?.setInsertionNext(insertionNext)
        insertionNext?.setInsertionPrev(insertionPrev)
        node.setInsertionPrev(null)
        node.setInsertionNext(null)
    }

    override fun iterator(): Iterator<RgaTreeSplitNode<T>> {
        return RgaTreeSplitIterator(head)
    }

    private class RgaTreeSplitIterator<T : RgaTreeSplitValue<T>>(head: RgaTreeSplitNode<T>) :
        Iterator<RgaTreeSplitNode<T>> {
        private var node: RgaTreeSplitNode<T>? = head

        override fun hasNext(): Boolean {
            return node?.hasNext == true
        }

        override fun next(): RgaTreeSplitNode<T> {
            return requireNotNull(node?.next).apply {
                node = node?.next
            }
        }
    }

    fun deepCopy(): RgaTreeSplit<T> {
        val clone = RgaTreeSplit<T>()
        var node = head.next
        var prev = clone.head
        var current: RgaTreeSplitNode<T>
        while (node != null) {
            current = clone.insertAfter(prev, node.deepCopy())
            if (node.hasInsertionPrev) {
                val insertionPrevNode = clone.findNode(requireNotNull(node.insertionPrev).id)
                current.setInsertionPrev(insertionPrevNode)
            }
            prev = current
            node = node.next
        }
        return clone
    }

    override fun toString(): String {
        return buildString {
            this@RgaTreeSplit.forEach { node ->
                if (!node.isRemoved) append(node.value)
            }
        }
    }

    data class ContentChange(
        val actorID: ActorID,
        val from: Int,
        val to: Int,
        val content: String? = null,
    )

    companion object {
        private val InitialNodeID = RgaTreeSplitNodeID(InitialTimeTicket, 0)

        object InitialNodeValue : RgaTreeSplitValue<InitialNodeValue> {

            override fun deepCopy(): InitialNodeValue = this

            override val length: Int = 0

            override fun get(index: Int): Char = throw IndexOutOfBoundsException()

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this
        }
    }
}

internal interface RgaTreeSplitValue<T : RgaTreeSplitValue<T>> : CharSequence {

    fun deepCopy(): T
}

internal data class RgaTreeSplitNode<T : RgaTreeSplitValue<T>>(
    val id: RgaTreeSplitNodeID,
    private var _value: T,
    private var _removedAt: TimeTicket? = null,
) {
    var prev: RgaTreeSplitNode<T>? = null
        private set
    var next: RgaTreeSplitNode<T>? = null
        private set
    var insertionPrev: RgaTreeSplitNode<T>? = null
        private set
    var insertionNext: RgaTreeSplitNode<T>? = null
        private set

    val createdAt: TimeTicket
        get() = id.createdAt

    val length: Int
        get() = if (isRemoved) 0 else contentLength

    val contentLength: Int
        get() = _value.length

    val hasNext: Boolean
        get() = next != null

    val hasInsertionPrev: Boolean
        get() = insertionPrev != null

    val isRemoved: Boolean
        get() = _removedAt != null

    val removedAt: TimeTicket?
        get() = _removedAt

    val value
        get() = _value

    fun setPrev(node: RgaTreeSplitNode<T>?) {
        prev = node
        node?.next = this
    }

    fun setNext(node: RgaTreeSplitNode<T>?) {
        next = node
        node?.prev = this
    }

    fun setInsertionPrev(node: RgaTreeSplitNode<T>?) {
        insertionPrev = node
        node?.insertionNext = this
    }

    fun setInsertionNext(node: RgaTreeSplitNode<T>?) {
        insertionNext = node
        node?.insertionPrev = this
    }

    /**
     * Creates a new split node of the given [offset].
     */
    fun split(offset: Int): RgaTreeSplitNode<T> {
        return RgaTreeSplitNode(id.split(offset), splitValue(offset), _removedAt)
    }

    @Suppress("UNCHECKED_CAST")
    private fun splitValue(offset: Int): T {
        val valueBefore = _value
        _value = valueBefore.subSequence(0, offset) as T
        return valueBefore.subSequence(offset, valueBefore.length) as T
    }

    /**
     * Checks if this [RgaTreeSplitNode] can be deleted or not.
     */
    fun canDelete(executedAt: TimeTicket, latestCreatedAt: TimeTicket): Boolean {
        return createdAt <= latestCreatedAt && (isRemoved || _removedAt < executedAt)
    }

    /**
     * Removes this [RgaTreeSplitNode] at the given [executedAt].
     */
    fun remove(executedAt: TimeTicket?) {
        _removedAt = executedAt
    }

    fun createRange(): RgaTreeSplitNodeRange {
        return RgaTreeSplitNodeRange(RgaTreeSplitNodePos(id, 0), RgaTreeSplitNodePos(id, length))
    }

    fun deepCopy(): RgaTreeSplitNode<T> {
        return copy(_value = _value.deepCopy())
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return super.equals(other)
    }
}

internal data class RgaTreeSplitNodeID(
    val createdAt: TimeTicket,
    val offset: Int,
) : Comparable<RgaTreeSplitNodeID> {
    /**
     * Returns whether the given ID has the same creation time with this [RgaTreeSplitNodeID].
     */
    fun hasSameCreatedAt(other: RgaTreeSplitNodeID) = createdAt == other.createdAt

    /**
     * Creates a new [RgaTreeSplitNodeID] with the given [offset].
     */
    fun split(offset: Int): RgaTreeSplitNodeID {
        return RgaTreeSplitNodeID(createdAt, this.offset + offset)
    }

    override fun compareTo(other: RgaTreeSplitNodeID): Int {
        return if (createdAt != other.createdAt) {
            createdAt.compareTo(other.createdAt)
        } else {
            offset.compareTo(other.offset)
        }
    }
}

internal data class RgaTreeSplitNodePos(val id: RgaTreeSplitNodeID, val relativeOffSet: Int) {
    val absoluteID: RgaTreeSplitNodeID
        get() = RgaTreeSplitNodeID(id.createdAt, id.offset + relativeOffSet)
}
