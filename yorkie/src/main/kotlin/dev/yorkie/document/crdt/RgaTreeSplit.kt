package dev.yorkie.document.crdt

import android.annotation.SuppressLint
import dev.yorkie.document.JsonSerializable
import dev.yorkie.document.RgaTreeSplitNodeIDStruct
import dev.yorkie.document.RgaTreeSplitPosStruct
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.MAX_LAMPORT
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DataSize
import dev.yorkie.util.SplayTreeSet
import java.util.TreeMap

internal typealias RgaTreeSplitPosRange = Pair<RgaTreeSplitPos, RgaTreeSplitPos>

/**
 * [RgaTreeSplit] is a block-based list with improved index-based lookup in RGA.
 * The difference from [RgaTreeList] is that it has data on a block basis to
 * reduce the size of CRDT metadata. When an edit occurs on a block,
 * the block is split.
 */
internal class RgaTreeSplit<T : RgaTreeSplitValue<T>> :
    Iterable<RgaTreeSplitNode<T>>,
    GCParent<RgaTreeSplitNode<T>> {
    @Suppress("UNCHECKED_CAST")
    val head = RgaTreeSplitNode(InitialNodeID, InitialNodeValue) as RgaTreeSplitNode<T>

    val treeByIndex = SplayTreeSet<RgaTreeSplitNode<T>> {
        if (it.isRemoved) 0 else it.contentLength
    }.apply { insert(head) }

    val treeByID = TreeMap<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>().apply {
        put(head.id, head)
    }

    val length
        get() = treeByIndex.length

    /**
     * Does following stpes.
     * 1. Split nodes with the given [range].
     * 2. Delete between the given [range].
     * 3. Insert a new node.
     * 4. Add removed nodes.
     */
    fun edit(
        range: RgaTreeSplitPosRange,
        executedAt: TimeTicket,
        value: T?,
        maxCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
        versionVector: VersionVector?,
    ): RgaTreeSplitEditResult<T> {
        // 1. Split nodes.
        val (toLeft, toRight) = findNodeWithSplit(range.second, executedAt)
        val (fromLeft, fromRight) = findNodeWithSplit(range.first, executedAt)

        // 2. Delete between from and to.
        val nodesToDelete = findBetween(fromRight, toRight)
        val (changes, maxCreatedAtMap, removedNodes) = deleteNodes(
            nodesToDelete,
            executedAt,
            maxCreatedAtMapByActor,
            versionVector,
        )
        val caretID = toRight?.id ?: toLeft.id
        var caretPos = RgaTreeSplitPos(caretID, 0)

        // 3. Insert a new node.
        value?.let {
            val index = posToIndex(fromLeft.createPosRange().second, true)
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
            caretPos = RgaTreeSplitPos(inserted.id, inserted.contentLength)
        }

        // 4. Add removed nodes.
        val gcPairs = removedNodes.map { (_, node) -> GCPair(this, node) }

        return RgaTreeSplitEditResult(caretPos, maxCreatedAtMap, changes, gcPairs)
    }

    /**
     * Splits and returns nodes at the given [pos].
     */
    fun findNodeWithSplit(
        pos: RgaTreeSplitPos,
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
        var current = fromNode
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
        maxCreatedAtMapByActor: Map<ActorID, TimeTicket>?,
        versionVector: VersionVector?,
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
            maxCreatedAtMapByActor,
            versionVector,
        )
        val createdAtMapByActor = mutableMapOf<ActorID, TimeTicket>()
        val removedNodes = mutableMapOf<RgaTreeSplitNodeID, RgaTreeSplitNode<T>>()

        // First, we need to collect indexes for change.
        val changes = makeChanges(nodesToKeep, executedAt)
        nodesToDelete.forEach { node ->
            // Then, make nodes be tombstones and map that.
            val actorID = node.createdAt.actorID
            if (!createdAtMapByActor.containsKey(actorID) ||
                createdAtMapByActor[actorID] < node.id.createdAt
            ) {
                createdAtMapByActor[actorID] = node.id.createdAt
            }
            removedNodes[node.id] = node
            node.remove(executedAt)
        }
        // Finally, remove index nodes of tombstones.
        deleteIndexNodes(nodesToKeep)

        return Triple(changes, createdAtMapByActor, removedNodes)
    }

    @SuppressLint("VisibleForTests")
    private fun filterNodes(
        candidates: List<RgaTreeSplitNode<T>>,
        executedAt: TimeTicket,
        maxCreatedAtMapByActor: Map<ActorID, TimeTicket>?,
        versionVector: VersionVector?,
    ): Pair<List<RgaTreeSplitNode<T>>, List<RgaTreeSplitNode<T>?>> {
        val isRemote = maxCreatedAtMapByActor != null
        val nodesToDelete = mutableListOf<RgaTreeSplitNode<T>>()
        val nodesToKeep = mutableListOf<RgaTreeSplitNode<T>?>()

        val (leftEdge, rightEdge) = findEdgesOfCandidates(candidates)
        nodesToKeep.add(leftEdge)

        candidates.forEach { node ->
            val actorID = node.createdAt.actorID
            var maxCreatedAt: TimeTicket? = null
            var clientLamportAtChange: Long = 0

            if (versionVector == null && maxCreatedAtMapByActor.isNullOrEmpty()) {
                clientLamportAtChange = MAX_LAMPORT
            } else if (versionVector != null && versionVector.size() > 0) {
                clientLamportAtChange = versionVector.get(actorID.value) ?: 0
            } else {
                maxCreatedAt = maxCreatedAtMapByActor?.get(actorID) ?: InitialTimeTicket
            }

            if (node.canDelete(executedAt, maxCreatedAt, clientLamportAtChange)) {
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
    ): MutableList<ContentChange> {
        val changes = mutableListOf<ContentChange>()
        var (fromIndex, toIndex) = 0 to 0
        for (index in 0 until boundaries.lastIndex) {
            val leftBoundary = boundaries[index]
            val rightBoundary = boundaries[index + 1]
            if (leftBoundary?.next == rightBoundary) continue

            fromIndex =
                findIndexesFromRange(requireNotNull(leftBoundary?.next).createPosRange()).first
            toIndex = if (rightBoundary == null) {
                treeByIndex.length
            } else {
                findIndexesFromRange(requireNotNull(rightBoundary.prev).createPosRange()).second
            }
        }
        if (fromIndex < toIndex) {
            changes.add(ContentChange(executedAt.actorID, fromIndex, toIndex))
        }
        changes.reverse()
        return changes
    }

    fun findIndexesFromRange(range: RgaTreeSplitPosRange): Pair<Int, Int> {
        val (fromPos, toPos) = range
        return posToIndex(fromPos, false) to posToIndex(toPos, true)
    }

    private fun posToIndex(pos: RgaTreeSplitPos, preferToLeft: Boolean): Int {
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
     * Finds [RgaTreeSplitPos] of the given [index].
     */
    fun indexToPos(index: Int): RgaTreeSplitPos {
        val (node, offset) = treeByIndex.find(index)
        return node?.let {
            RgaTreeSplitPos(it.id, offset)
        } ?: throw NoSuchElementException("no node found with the given index: $index")
    }

    /**
     * Finds the node of the given [id].
     */
    fun findNode(id: RgaTreeSplitNodeID): RgaTreeSplitNode<T> {
        return requireNotNull(findFloorNode(id))
    }

    /**
     * Physically deletes the given node from this [RgaTreeSplit].
     */
    override fun delete(node: RgaTreeSplitNode<T>) {
        treeByIndex.delete(node)
        treeByID.remove(node.id)

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

            override fun getDataSize(): DataSize = DataSize(
                data = 0,
                meta = 0,
            )

            override val length: Int = 0

            override fun get(index: Int): Char = throw IndexOutOfBoundsException()

            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this
        }
    }
}

internal interface RgaTreeSplitValue<T : RgaTreeSplitValue<T>> : CharSequence {

    fun deepCopy(): T

    fun getDataSize(): DataSize
}

internal data class RgaTreeSplitNode<T : RgaTreeSplitValue<T>>(
    val id: RgaTreeSplitNodeID,
    private var _value: T,
    private var _removedAt: TimeTicket? = null,
) : GCChild {
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

    override val removedAt: TimeTicket?
        get() = _removedAt

    override val dataSize: DataSize
        get() {
            val dataSize = _value.getDataSize()
            var meta = dataSize.meta + TIME_TICKET_SIZE
            if (_removedAt != null) {
                meta += TIME_TICKET_SIZE
            }

            return DataSize(
                data = dataSize.data,
                meta = meta,
            )
        }

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
    fun canDelete(
        executedAt: TimeTicket,
        maxCreatedAt: TimeTicket?,
        clientLamportAtChange: Long,
    ): Boolean {
        val justRemoved = removedAt == null

        val nodeExisted = if (maxCreatedAt != null) {
            createdAt <= maxCreatedAt
        } else {
            createdAt.lamport <= clientLamportAtChange
        }

        if (nodeExisted && (removedAt == null || executedAt > removedAt)) {
            return justRemoved
        }
        return false
    }

    /**
     * Checks if node is able to set style.
     */
    fun canStyle(
        executedAt: TimeTicket,
        maxCreatedAt: TimeTicket?,
        clientLamportAtChange: Long,
    ): Boolean {
        val nodeExisted = if (maxCreatedAt != null) {
            createdAt <= maxCreatedAt
        } else {
            createdAt.lamport <= clientLamportAtChange
        }

        return nodeExisted && (removedAt == null || executedAt > removedAt)
    }

    /**
     * Removes this [RgaTreeSplitNode] at the given [executedAt].
     */
    fun remove(executedAt: TimeTicket?) {
        _removedAt = executedAt
    }

    fun createPosRange(): RgaTreeSplitPosRange {
        return RgaTreeSplitPosRange(RgaTreeSplitPos(id, 0), RgaTreeSplitPos(id, length))
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

public data class RgaTreeSplitNodeID internal constructor(
    val createdAt: TimeTicket,
    val offset: Int,
) : Comparable<RgaTreeSplitNodeID>, JsonSerializable<RgaTreeSplitNodeID, RgaTreeSplitNodeIDStruct> {
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
        return compareValuesBy(this, other, { it.createdAt }, { it.offset })
    }

    override fun toStruct(): RgaTreeSplitNodeIDStruct {
        return RgaTreeSplitNodeIDStruct(createdAt.toStruct(), offset)
    }
}

public data class RgaTreeSplitPos internal constructor(
    val id: RgaTreeSplitNodeID,
    val relativeOffSet: Int,
) : JsonSerializable<RgaTreeSplitPos, RgaTreeSplitPosStruct> {
    val absoluteID: RgaTreeSplitNodeID
        get() = RgaTreeSplitNodeID(id.createdAt, id.offset + relativeOffSet)

    override fun toStruct(): RgaTreeSplitPosStruct {
        return RgaTreeSplitPosStruct(id.toStruct(), relativeOffSet)
    }
}
