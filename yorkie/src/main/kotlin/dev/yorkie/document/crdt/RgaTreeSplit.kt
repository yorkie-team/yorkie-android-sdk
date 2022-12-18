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
internal class RgaTreeSplit<T : RgaTreeSplitValue> : Iterable<RgaTreeSplitNode<T>> {
    val head: RgaTreeSplitNode<T> = RgaTreeSplitNode(InitialRgaTreeSplitNodeID)
    private val treeByIndex = SplayTreeSet<RgaTreeSplitNode<T>>().apply { insert(head) }
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
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>?,
    ): Triple<RgaTreeSplitNodePos, Map<ActorID, TimeTicket>, List<TextChange>> {
        // 1. Split nodes.
        val (fromLeft, fromRight) = findNodeWithSplit(range.first, executedAt)
        val (toLeft, toRight) = findNodeWithSplit(range.second, executedAt)

        // 2. Delete between from and to.
        val nodesToDelete = findBetween(fromRight, toRight)
        val (changes, latestCreatedAtMap, removedNodeMapByNodeKey) = deleteNodes(
            nodesToDelete,
            executedAt,
            latestCreatedAtMapByActor,
        )
        var caretPos = RgaTreeSplitNodePos(toRight.id, 0)

        // 3. Insert a new node.
        value?.let {
            val index = findIndexFromNodePos(fromLeft.createdRange().second, true)
            val inserted = insertAfter(
                fromLeft,
                RgaTreeSplitNode(
                    RgaTreeSplitNodeID(executedAt, 0),
                    it,
                ),
            )
            if (changes.isNotEmpty() && changes.last().from == index) {
                changes.last().content = it.toString()
            } else {
                changes.toMutableList().add(
                    TextChange(
                        TextChangeType.Content,
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
            removedNodeMapByNodeKey.toMutableMap()[it.key] = it.value
        }

        return Triple(caretPos, latestCreatedAtMap, changes)
    }

    /**
     * Splits and returns nodes at the given [pos].
     */
    fun findNodeWithSplit(
        pos: RgaTreeSplitNodePos,
        executedAt: TimeTicket,
    ): Pair<RgaTreeSplitNode<T>, RgaTreeSplitNode<T>> {
        val absoluteID = pos.absoluteID
        var node = findFloorNodePreferToLeft(absoluteID)
        val relativeOffSet = absoluteID.offset - node.id.offset
        splitNode(node, relativeOffSet)

        while (node.hasNext && executedAt < node.next?.createdAt) {
            node = requireNotNull(node.next)
        }
        // NOTE(7hong13): node.next can be null?
        return Pair(node, requireNotNull(node.next))
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
        return if (entry.key == id && entry.key.hasSameCreatedAt(id)) {
            null
        } else {
            entry.value
        }
    }

    private fun splitNode(node: RgaTreeSplitNode<T>, offset: Int): RgaTreeSplitNode<T>? {
        if (offset > node.contentLength) {
            error("offset should be less than or equal to length")
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
        prevNode.next?.setPrev(newNode)
        newNode.setPrev(prevNode)

        treeByID[newNode.id] = newNode
        treeByIndex.insertAfter(prevNode, newNode)
        return newNode
    }

    /**
     * Returns nodes between [fromNode] and [toNode].
     */
    fun findBetween(
        fromNode: RgaTreeSplitNode<T>,
        toNode: RgaTreeSplitNode<T>,
    ): List<RgaTreeSplitNode<T>> {
        var current: RgaTreeSplitNode<T> = fromNode
        return buildList {
            while (current != toNode) {
                add(current)
                current = current.next ?: break
            }
        }
    }

    private fun deleteNodes(
        candidates: List<RgaTreeSplitNode<T>>,
        executedAt: TimeTicket,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>?,
    ): Triple<
        List<TextChange>, Map<ActorID, TimeTicket>, Map<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>,
        > {
        if (candidates.isEmpty()) {
            return Triple(emptyList(), emptyMap(), emptyMap())
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
        val changes = makeChanges(nodesToKeep, executedAt)
        nodesToDelete.forEach { node ->
            // Then, make nodes be tombstones and map that.
            val actorID = node.createdAt.actorID
            if (!createdAtMapByActor.containsKey(actorID)
                || createdAtMapByActor[actorID] < node.id.createdAt
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
        val nodesToKeep = mutableListOf<RgaTreeSplitNode<T>>()

        val (leftEdge, rightEdge) = findEdgesOfCandidates(candidates)
        nodesToKeep.add(leftEdge)

        candidates.forEach { node ->
            val actorID = node.createdAt.actorID
            val latestCreatedAt =
                if (isRemote && latestCreatedAtMapByActor?.containsKey(actorID) == true) {
                    latestCreatedAtMapByActor[actorID] ?: InitialTimeTicket
                } else {
                    MaxTimeTicket
                }
            if (node.canDelete(executedAt, latestCreatedAt)) {
                nodesToDelete.add(node)
            } else {
                nodesToKeep.add(node)
            }
        }
        rightEdge?.let(nodesToKeep::add)

        return Pair(nodesToDelete, nodesToKeep)
    }

    /**
     * Finds the edges outside [candidates].
     * If right edge is null, it means [candidates] contains the end of text.
     */
    private fun findEdgesOfCandidates(
        candidates: List<RgaTreeSplitNode<T>>,
    ): Pair<RgaTreeSplitNode<T>, RgaTreeSplitNode<T>?> {
        if (candidates.isEmpty()) {
            error("findEdgesOfCandidates error: candidates is empty")
        }
        return Pair(requireNotNull(candidates.first().prev), candidates.last().next)
    }

    private fun makeChanges(
        boundaries: List<RgaTreeSplitNode<T>?>,
        executedAt: TimeTicket,
    ): List<TextChange> {
        return buildList {
            var (fromIndex, toIndex) = Pair(0, 0)
            for (index in 0..boundaries.size - 2) {
                val leftBoundary = requireNotNull(boundaries[index])
                val rightBoundary = boundaries[index + 1]
                if (leftBoundary.next == rightBoundary) continue

                fromIndex =
                    findIndexesFromRange(requireNotNull(leftBoundary.next).createdRange()).first
                toIndex = if (rightBoundary == null) {
                    treeByIndex.length
                } else {
                    findIndexesFromRange(requireNotNull(rightBoundary.next).createdRange()).second
                }
            }
            if (fromIndex < toIndex) {
                add(TextChange(TextChangeType.Content, executedAt.actorID, fromIndex, toIndex))
            }
        }.reversed()
    }

    fun findIndexesFromRange(range: RgaTreeSplitNodeRange): Pair<Int, Int> {
        val (fromPos, toPos) = range
        return Pair(findIndexFromNodePos(fromPos, false), findIndexFromNodePos(toPos, true))
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
            // NOTE(7hong13): below comment has a typo in JS-SDK.
            // If there is no node to delete between boundaries, do nothing.
            if (leftBoundary?.next != rightBoundary) {
                treeByIndex.deleteRange(requireNotNull(leftBoundary), rightBoundary)
            }
        }
    }

    // NOTE(7hong13): original comment from JS-SDK:
    // `findNodePos` finds RGATreeSplitNodePos of given offset.
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
     * Fins the node of the given [id].
     */
    fun findNode(id: RgaTreeSplitNodeID): RgaTreeSplitNode<T> {
        return requireNotNull(findFloorNode(id))
    }

    /**
     * Physically deletes nodes that have been removed.
     */
    fun deleteTextNodesWithGarbage(ticket: TimeTicket): Int {
        var count = 0
        removedNodeMap.forEach {
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

    private class RgaTreeSplitIterator<T : RgaTreeSplitValue>(
        private val head: RgaTreeSplitNode<T>,
    ) : Iterator<RgaTreeSplitNode<T>> {
        private var node = head

        override fun hasNext(): Boolean {
            if (node == head) {
                node = head.next ?: return false
            }
            return node.hasNext
        }

        override fun next(): RgaTreeSplitNode<T> {
            return requireNotNull(node.next)
        }
    }

    fun deepCopy(): RgaTreeSplit<T> {
        val clone = RgaTreeSplit<T>()
        var node = head.next
        var prev = clone.head
        var current: RgaTreeSplitNode<T>
        while (node != null) {
            current = clone.insertAfter(prev, node.copy())
            if (node.hasInsertionPrev) {
                val insertionPrevNode = clone.findNode(requireNotNull(node.insertionPrev).id)
                current.setInsertionPrev(insertionPrevNode)
            }
            prev = current
            node = node.next
        }
        return clone
    }

    companion object {
        private val InitialRgaTreeSplitNodeID = RgaTreeSplitNodeID(InitialTimeTicket, 0)
    }
}

internal data class RgaTreeSplitNode<T : RgaTreeSplitValue>(
    val id: RgaTreeSplitNodeID,
    private var value: T? = null,
) {
    var prev: RgaTreeSplitNode<T>? = null
        private set
    var next: RgaTreeSplitNode<T>? = null
        private set
    var insertionPrev: RgaTreeSplitNode<T>? = null
        private set
    var insertionNext: RgaTreeSplitNode<T>? = null
        private set

    var removedAt: TimeTicket? = null
        private set

    val createdAt: TimeTicket
        get() = id.createdAt

    val length: Int
        get() = removedAt?.let { contentLength } ?: 0

    val contentLength: Int
        get() = value?.length ?: 0

    val hasNext: Boolean
        get() = next != null

    val hasInsertionPrev: Boolean
        get() = insertionPrev != null

    val isRemoved: Boolean
        get() = removedAt != null

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
        return RgaTreeSplitNode(id.split(offset), splitValue(offset))
    }

    @Suppress("UNCHECKED_CAST")
    private fun splitValue(offset: Int): T {
        val newValue = requireNotNull(value).substring(0, offset) as T
        value = newValue
        return newValue.substring(offset, newValue.length) as T
    }

    // NOTE(7hong13): original comment from JS-SDK:
    // `canDelete` checks if node is able to delete.
    /**
     * Checks if this [RgaTreeSplitNode] can be deleted or not.
     */
    fun canDelete(executedAt: TimeTicket, latestCreatedAt: TimeTicket): Boolean {
        return latestCreatedAt < createdAt && (isRemoved || removedAt < executedAt)
    }

    // NOTE(7hong13): original comment from JS-SDK:
    // `remove` removes node of given edited time.
    /**
     * Removes this [RgaTreeSplitNode] at the given [executedAt].
     */
    fun remove(executedAt: TimeTicket?) {
        removedAt = executedAt
    }

    fun createdRange(): RgaTreeSplitNodeRange {
        return RgaTreeSplitNodeRange(RgaTreeSplitNodePos(id, 0), RgaTreeSplitNodePos(id, length))
    }
}

internal data class RgaTreeSplitNodeID(val createdAt: TimeTicket, val offset: Int) {
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
}

internal data class RgaTreeSplitNodePos(val id: RgaTreeSplitNodeID, val relativeOffSet: Int) {
    val absoluteID: RgaTreeSplitNodeID
        get() = RgaTreeSplitNodeID(id.createdAt, id.offset + relativeOffSet)
}

internal interface RgaTreeSplitValue {
    val length: Int

    fun substring(indexStart: Int, indexEnd: Int?): RgaTreeSplitValue
}
