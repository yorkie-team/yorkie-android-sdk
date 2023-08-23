package dev.yorkie.document.crdt

import dev.yorkie.document.CrdtTreeNodeIDStruct
import dev.yorkie.document.CrdtTreePosStruct
import dev.yorkie.document.JsonSerializable
import dev.yorkie.document.json.TreePosStructRange
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.MaxTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.util.IndexTree
import dev.yorkie.util.IndexTreeNode
import dev.yorkie.util.TreePos
import java.util.TreeMap

public typealias TreePosRange = Pair<CrdtTreePos, CrdtTreePos>

internal class CrdtTree(
    val root: CrdtTreeNode,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtGCElement() {

    internal val indexTree = IndexTree(root)

    private val nodeMapByID = TreeMap<CrdtTreeNodeID, CrdtTreeNode>()

    private val removedNodeMap = mutableMapOf<Pair<TimeTicket, Int>, CrdtTreeNode>()

    init {
        indexTree.traverse { node, _ ->
            nodeMapByID[node.id] = node
        }
    }

    override val removedNodesLength: Int
        get() = removedNodeMap.size

    val size: Int
        get() = indexTree.size

    /**
     * Applies the given [attributes] of the given [range].
     */
    fun style(
        range: TreePosRange,
        attributes: Map<String, String>?,
        executedAt: TimeTicket,
    ): List<TreeChange> {
        val (fromParent, fromLeft) = findNodesAndSplitText(range.first, executedAt)
        val (toParent, toLeft) = findNodesAndSplitText(range.second, executedAt)
        // TODO(7hong13): check whether toPath is set correctly
        val changes = listOf(
            TreeChange(
                type = TreeChangeType.Style.type,
                from = toIndex(fromParent, fromLeft),
                to = toIndex(toParent, toLeft),
                fromPath = toPath(fromParent, fromLeft),
                toPath = toPath(fromParent, fromLeft),
                actorID = executedAt.actorID,
                attributes = attributes,
            ),
        )

        if (fromLeft != toLeft) {
            val (parent, fromChildIndex) = if (fromLeft.parent == toLeft.parent) {
                val leftParent = fromLeft.parent ?: return changes
                leftParent to leftParent.allChildren.indexOf(fromLeft) + 1
            } else {
                fromLeft to 0
            }

            val toChildIndex = parent.allChildren.indexOf(toLeft)

            for (i in fromChildIndex..toChildIndex) {
                val node = parent.allChildren[i]
                if (!node.isRemoved && attributes != null) {
                    attributes.forEach { (key, value) ->
                        node.setAttribute(key, value, executedAt)
                    }
                }
            }
        }

        return changes
    }

    private fun toPath(parentNode: CrdtTreeNode, leftSiblingNode: CrdtTreeNode): List<Int> {
        return indexTree.treePosToPath(toTreePos(parentNode, leftSiblingNode))
    }

    private fun toTreePos(
        parentNode: CrdtTreeNode,
        leftSiblingNode: CrdtTreeNode,
    ): TreePos<CrdtTreeNode> {
        return when {
            parentNode.isRemoved -> {
                var child = parentNode
                var parent = parentNode
                while (parent.isRemoved) {
                    child = parent
                    parent = child.parent ?: break
                }

                val childOffset = parent.findOffset(child)
                TreePos(parent, childOffset)
            }

            parentNode == leftSiblingNode -> TreePos(parentNode, 0)

            else -> {
                var offset = parentNode.findOffset(leftSiblingNode)
                if (!leftSiblingNode.isRemoved) {
                    if (leftSiblingNode.isText) {
                        return TreePos(leftSiblingNode, leftSiblingNode.paddedSize)
                    } else {
                        offset++
                    }
                }
                TreePos(parentNode, offset)
            }
        }
    }

    /**
     * Edits the tree with the given [range] and [contents].
     * If the [contents] is null, the [range] will be removed.
     */
    fun edit(
        range: TreePosRange,
        contents: List<CrdtTreeNode>?,
        executedAt: TimeTicket,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): Pair<List<TreeChange>, Map<ActorID, TimeTicket>> {
        // 01. split text nodes at the given range if needed.
        val (fromParent, fromLeft) = findNodesAndSplitText(range.first, executedAt)
        val (toParent, toLeft) = findNodesAndSplitText(range.second, executedAt)

        // NOTE(hackerwins): If concurrent deletion happens, we need to separate the
        // range(from, to) into multiple ranges.
        val changes = listOf(
            TreeChange(
                type = TreeChangeType.Content.type,
                from = toIndex(fromParent, fromLeft),
                to = toIndex(toParent, toLeft),
                fromPath = toPath(fromParent, fromLeft),
                toPath = toPath(toParent, toLeft),
                actorID = executedAt.actorID,
                value = contents?.map(CrdtTreeNode::toJson),
            ),
        )

        val toBeRemoved = mutableListOf<CrdtTreeNode>()
        val latestCreatedAtMap = mutableMapOf<ActorID, TimeTicket>()

        // 02. remove the nodes and update linked list and index tree.
        if (fromLeft != toLeft) {
            val (parent, fromChildIndex) = if (fromLeft.parent == toLeft.parent) {
                val leftParent = requireNotNull(fromLeft.parent)
                leftParent to leftParent.allChildren.indexOf(fromLeft) + 1
            } else {
                fromLeft to 0
            }

            val toChildIndex = parent.allChildren.indexOf(toLeft)

            for (i in fromChildIndex..toChildIndex) {
                val node = parent.allChildren[i]
                val actorID = node.createdAt.actorID
                val latestCreatedAt = latestCreatedAtMapByActor?.let {
                    latestCreatedAtMapByActor[actorID] ?: InitialTimeTicket
                } ?: MaxTimeTicket

                if (node.canDelete(executedAt, latestCreatedAt)) {
                    val latest = latestCreatedAtMap[actorID]
                    val createdAt = node.createdAt

                    if (latest == null || latest < createdAt) {
                        latestCreatedAtMap[actorID] = createdAt
                    }

                    traverseAll(node, 0) { treeNode, _ ->
                        if (treeNode.canDelete(executedAt, MaxTimeTicket)) {
                            val latestTicket = latestCreatedAtMap[actorID]
                            val ticket = treeNode.createdAt

                            if (latestTicket == null || latestTicket < ticket) {
                                latestCreatedAtMap[actorID] = ticket
                            }

                            if (!treeNode.isRemoved) {
                                toBeRemoved.add(treeNode)
                            }
                        }
                    }
                }
            }

            toBeRemoved.forEach { node ->
                node.remove(executedAt)
                if (node.isRemoved) {
                    removedNodeMap[node.createdAt to node.id.offset] = node
                }
            }
        }

        // 03. insert the given node at the given position.
        if (contents?.isNotEmpty() == true) {
            var leftInChildren = fromLeft

            contents.forEach { content ->
                // 03-1. insert the content nodes to the list.
                if (leftInChildren == fromParent) {
                    // 03-1-1. when there's no leftSibling, then insert content into very front of parent's children List
                    fromParent.insertAt(0, content)
                } else {
                    // 03-1-2. insert after leftSibling
                    fromParent.insertAfter(leftInChildren, content)
                }

                leftInChildren = content
                traverseAll(content) { node, _ ->
                    // if insertion happens during concurrent editing and parent node has been removed,
                    // make new nodes as tombstone immediately
                    if (fromParent.isRemoved) {
                        node.remove(executedAt)
                        removedNodeMap[node.id.createdAt to node.id.offset] = node
                    }
                    nodeMapByID[node.id] = node
                }
            }
        }
        return changes to latestCreatedAtMap
    }

    private fun traverseAll(
        node: CrdtTreeNode,
        depth: Int = 0,
        action: ((CrdtTreeNode, Int) -> Unit),
    ) {
        node.allChildren.forEach { child ->
            traverseAll(child, depth + 1, action)
        }
        action.invoke(node, depth)
    }

    /**
     * Finds [TreePos] of the given [pos] and splits the text node if necessary.
     *
     * [CrdtTreePos] is a position in the CRDT perspective. This is
     * different from [TreePos] which is a position of the tree in the local perspective.
     */
    fun findNodesAndSplitText(
        pos: CrdtTreePos,
        executedAt: TimeTicket,
    ): Pair<CrdtTreeNode, CrdtTreeNode> {
        val treeNodes =
            toTreeNodes(pos) ?: throw IllegalArgumentException("cannot find node at $pos")
        val (parentNode, leftSiblingNode) = treeNodes

        // Find the appropriate position. This logic is similar to
        // handling the insertion of the same position in RGA.
        if (leftSiblingNode.isText) {
            val absOffset = leftSiblingNode.id.offset
            val split = leftSiblingNode.split(pos.leftSiblingID.offset - absOffset, absOffset)
            split?.let {
                split.insPrevID = leftSiblingNode.id
                nodeMapByID[split.id] = split

                leftSiblingNode.insNextID?.let { insNextID ->
                    val insNext = findFloorNode(insNextID)
                    insNext?.insPrevID = split.id
                    split.insNextID = insNextID
                }

                leftSiblingNode.insNextID = split.id
            }
        }
        val index = if (parentNode == leftSiblingNode) {
            0
        } else {
            parentNode.allChildren.indexOf(leftSiblingNode) + 1
        }

        var updatedLeftSiblingNode = leftSiblingNode
        for (i in index until parentNode.allChildren.size) {
            val next = parentNode.allChildren[i]
            if (executedAt < next.id.createdAt) {
                updatedLeftSiblingNode = next
            } else {
                break
            }
        }
        return parentNode to updatedLeftSiblingNode
    }

    private fun findFloorNode(id: CrdtTreeNodeID): CrdtTreeNode? {
        val (key, value) = nodeMapByID.floorEntry(id) ?: return null
        return if (key == null || key.createdAt != id.createdAt) null else value
    }

    private fun toTreeNodes(pos: CrdtTreePos): Pair<CrdtTreeNode, CrdtTreeNode>? {
        val parentID = pos.parentID
        val leftSiblingID = pos.leftSiblingID
        val parentNode = findFloorNode(parentID) ?: return null
        val leftSiblingNode = findFloorNode(leftSiblingID) ?: return null

        val updatedLeftSiblingNode =
            if (leftSiblingID.offset > 0 && leftSiblingID.offset == leftSiblingNode.id.offset &&
                leftSiblingNode.insPrevID != null
            ) {
                findFloorNode(requireNotNull(leftSiblingNode.insPrevID)) ?: leftSiblingNode
            } else {
                leftSiblingNode
            }
        return parentNode to updatedLeftSiblingNode
    }

    /**
     * Edits the given [range] with the given [contents].
     * This method uses indexes instead of a pair of [TreePos] for testing.
     */
    fun editByIndex(
        range: Pair<Int, Int>,
        contents: List<CrdtTreeNode>?,
        executedAt: TimeTicket,
    ) {
        val fromPos = findPos(range.first)
        val toPos = findPos(range.second)
        edit(fromPos to toPos, contents, executedAt)
    }

    /**
     * Splits the node at the given [index].
     */
    fun split(index: Int, depth: Int = 1): TreePos<CrdtTreeNode> {
        TODO("Implement after JS SDK's implementation")
    }

    /**
     * Move the given [source] range to the given [target] range.
     */
    fun move(
        target: Pair<Int, Int>,
        source: Pair<Int, Int>,
        executedAt: TimeTicket,
    ) {
        TODO("Implement after JS SDK's implementation")
    }

    /**
     * Physically deletes nodes that have been removed.
     */
    override fun deleteRemovedNodesBefore(executedAt: TimeTicket): Int {
        val nodesToBeRemoved = removedNodeMap.filterValues { node ->
            node.removedAt != null && node.removedAt <= executedAt
        }.values.toMutableSet()

        nodesToBeRemoved.forEach { node ->
            node.parent?.removeChild(node)
            nodeMapByID.remove(node.id)
            delete(node)
            removedNodeMap.remove(node.createdAt to node.id.offset)
        }

        return nodesToBeRemoved.size
    }

    /**
     * Physically deletes the given [node] from [IndexTree].
     */
    private fun delete(node: CrdtTreeNode) {
        val insPrevID = node.insPrevID
        val insNextID = node.insNextID

        insPrevID?.let {
            val insPrev = findFloorNode(it)
            insPrev?.insNextID = insNextID
        }
        insNextID?.let {
            val insNext = findFloorNode(it)
            insNext?.insPrevID = insPrevID
        }

        node.insPrevID = null
        node.insNextID = null
    }

    /**
     * Finds the position of the given [index] in the tree.
     */
    fun findPos(index: Int, preferText: Boolean = true): CrdtTreePos {
        val (node, offset) = indexTree.findTreePos(index, preferText)
        var updatedNode = node
        val leftSibling = if (node.isText) {
            updatedNode = requireNotNull(node.parent)
            if (node.parent?.children?.firstOrNull() == node && offset == 0) {
                node.parent
            } else {
                node
            }
        } else {
            if (offset == 0) node else node.children[offset - 1]
        } ?: throw IllegalArgumentException("left sibling should not be null")
        return CrdtTreePos(
            updatedNode.id,
            CrdtTreeNodeID(leftSibling.createdAt, leftSibling.offset + offset),
        )
    }

    /**
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtElement {
        return CrdtTree(root.deepCopy(), createdAt, movedAt, removedAt)
    }

    /**
     * Converts the given [pos] to the index of the tree.
     */
    fun toIndex(parentNode: CrdtTreeNode, leftSiblingNode: CrdtTreeNode): Int {
        return indexTree.indexOf(toTreePos(parentNode, leftSiblingNode))
    }

    /**
     * Converts the given path of the node to the range of the position.
     */
    fun pathToPosRange(path: List<Int>): TreePosRange {
        val fromIndex = pathToIndex(path)
        return findPos(fromIndex) to findPos(fromIndex + 1)
    }

    /**
     * Finds the tree position path.
     */
    private fun pathToTreePos(path: List<Int>): TreePos<CrdtTreeNode> {
        return indexTree.pathToTreePos(path)
    }

    /**
     * Finds the position of the given index in the tree by [path].
     */
    fun pathToPos(path: List<Int>): CrdtTreePos {
        return findPos(indexTree.pathToIndex(path))
    }

    /**
     * Returns the XML encoding of this tree.
     */
    fun toXml(): String {
        return indexTree.root.toXml()
    }

    /**
     * Converts the given [index] to path.
     */
    fun indexToPath(index: Int): List<Int> {
        return indexTree.indexToPath(index)
    }

    /**
     * Converts the given [path] to index.
     */
    fun pathToIndex(path: List<Int>): Int {
        return indexTree.pathToIndex(path)
    }

    /**
     * Returns the position range from the given [range].
     */
    fun indexRangeToPosRange(range: Pair<Int, Int>): TreePosRange {
        val (fromIndex, toIndex) = range
        val fromPos = findPos(fromIndex)
        return if (fromIndex == toIndex) {
            fromPos to fromPos
        } else {
            fromPos to findPos(toIndex)
        }
    }

    /**
     * Converts the [range] into [TreePosStructRange].
     */
    fun indexRangeToPosStructRange(range: Pair<Int, Int>): TreePosStructRange {
        val (fromIndex, toIndex) = range
        val fromPos = findPos(fromIndex)
        return if (fromIndex == toIndex) {
            fromPos.toStruct() to fromPos.toStruct()
        } else {
            fromPos.toStruct() to findPos(toIndex).toStruct()
        }
    }

    /**
     * Converts the given position [range] to the path range.
     */
    fun posRangeToPathRange(
        range: TreePosRange,
        executedAt: TimeTicket,
    ): Pair<List<Int>, List<Int>> {
        val (fromParent, fromLeft) = findNodesAndSplitText(range.first, executedAt)
        val (toParent, toLeft) = findNodesAndSplitText(range.second, executedAt)
        return toPath(fromParent, fromLeft) to toPath(toParent, toLeft)
    }

    /**
     * Converts the given position range to the path range.
     */
    fun posRangeToIndexRange(
        range: TreePosRange,
        executedAt: TimeTicket,
    ): Pair<Int, Int> {
        val (fromParent, fromLeft) = findNodesAndSplitText(range.first, executedAt)
        val (toParent, toLeft) = findNodesAndSplitText(range.second, executedAt)
        return toIndex(fromParent, fromLeft) to toIndex(toParent, toLeft)
    }
}

/**
 * [CrdtTreeNode] is a node of [CrdtTree]. It includes the logical clock and
 * links to other nodes to resolve conflicts.
 */
@Suppress("DataClassPrivateConstructor")
internal data class CrdtTreeNode private constructor(
    val id: CrdtTreeNodeID,
    override val type: String,
    private val _value: String? = null,
    private val childNodes: MutableList<CrdtTreeNode> = mutableListOf(),
    private val _attributes: Rht = Rht(),
) : IndexTreeNode<CrdtTreeNode>(childNodes) {

    val attributes: Map<String, String>
        get() = _attributes.nodeKeyValueMap

    val attributesToXml: String
        get() = _attributes.toXml()

    val createdAt: TimeTicket
        get() = id.createdAt

    var removedAt: TimeTicket? = null
        private set

    override val isRemoved: Boolean
        get() = removedAt != null

    val offset: Int
        get() = id.offset

    override var value: String = _value.orEmpty()
        get() {
            check(isText) {
                "cannot set value of element node: $type"
            }
            return field
        }
        set(value) {
            check(isText) {
                "cannot set value of element node: $type"
            }
            field = value
            size = value.length
        }

    var insPrevID: CrdtTreeNodeID? = null

    var insNextID: CrdtTreeNodeID? = null

    val rhtNodes: List<Rht.Node>
        get() = _attributes.toList()

    init {
        _value?.let { value = it }
    }

    /**
     * Clones this node with the given [offset].
     */
    override fun clone(offset: Int): CrdtTreeNode {
        return copy(
            id = CrdtTreeNodeID(id.createdAt, offset),
            _value = null,
            childNodes = mutableListOf(),
        ).also {
            it.removedAt = this.removedAt
        }
    }

    fun setAttribute(key: String, value: String, executedAt: TimeTicket) {
        _attributes.set(key, value, executedAt)
    }

    /**
     * Marks the node as removed.
     */
    fun remove(executedAt: TimeTicket) {
        val notRemoved = removedAt == null

        if (notRemoved || removedAt < executedAt) {
            removedAt = executedAt
        }
        if (notRemoved) {
            updateAncestorSize()
        }
    }

    /**
     * Copies itself deeply.
     */
    fun deepCopy(): CrdtTreeNode {
        return copy(
            _value = _value,
            childNodes = _children.map { child ->
                child.deepCopy()
            }.toMutableList(),
            _attributes = _attributes.deepCopy(),
        ).also {
            it.size = size
            it.removedAt = removedAt
            it._children.forEach { child ->
                child.parent = it
            }
        }
    }

    /**
     * Checks if node is able to delete.
     */
    fun canDelete(executedAt: TimeTicket, latestCreatedAt: TimeTicket): Boolean {
        return createdAt <= latestCreatedAt && (removedAt == null || removedAt < executedAt)
    }

    @Suppress("FunctionName")
    companion object {

        fun CrdtTreeText(id: CrdtTreeNodeID, value: String): CrdtTreeNode {
            return CrdtTreeNode(id, DEFAULT_TEXT_TYPE, value)
        }

        fun CrdtTreeElement(
            id: CrdtTreeNodeID,
            type: String,
            children: List<CrdtTreeNode> = emptyList(),
            attributes: Rht = Rht(),
        ) = CrdtTreeNode(id, type, null, children.toMutableList(), attributes)
    }
}

/**
 * [CrdtTreePos] represent a position in the tree. It is used to identify a
 * position in the tree. It is composed of the parent ID and the left sibling ID.
 * If there's no left sibling in parent's children, then left sibling is parent.
 */
public data class CrdtTreePos internal constructor(
    val parentID: CrdtTreeNodeID,
    val leftSiblingID: CrdtTreeNodeID,
) : JsonSerializable<CrdtTreePos, CrdtTreePosStruct> {

    override fun toStruct(): CrdtTreePosStruct {
        return CrdtTreePosStruct(parentID.toStruct(), leftSiblingID.toStruct())
    }
}

/**
 * [CrdtTreeNodeID] represent an ID of a node in the tree. It is used to
 * identify a node in the tree. It is composed of the creation time of the node
 * and the offset from the beginning of the node if the node is split.
 *
 * Some of replicas may have nodes that are not split yet. In this case, we can
 * use `map.floorEntry()` to find the adjacent node.
 */
public data class CrdtTreeNodeID internal constructor(
    /**
     * Creation time of the node.
     */
    val createdAt: TimeTicket,

    /**
     * The distance from the beginning of the node when the node is split.
     */
    val offset: Int,
) : Comparable<CrdtTreeNodeID>, JsonSerializable<CrdtTreeNodeID, CrdtTreeNodeIDStruct> {

    override fun compareTo(other: CrdtTreeNodeID): Int {
        return compareValuesBy(this, other, { it.createdAt }, { it.offset })
    }

    override fun toStruct(): CrdtTreeNodeIDStruct {
        return CrdtTreeNodeIDStruct(createdAt.toStruct(), offset)
    }

    companion object {
        internal val InitialCrdtTreeNodeID = CrdtTreeNodeID(InitialTimeTicket, 0)
    }
}
