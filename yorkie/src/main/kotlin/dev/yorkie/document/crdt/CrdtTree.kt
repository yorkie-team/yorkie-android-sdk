package dev.yorkie.document.crdt

import com.google.common.annotations.VisibleForTesting
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
import dev.yorkie.util.TokenType
import dev.yorkie.util.TreePos
import dev.yorkie.util.TreeToken
import java.util.TreeMap

public typealias TreePosRange = Pair<CrdtTreePos, CrdtTreePos>

internal typealias CrdtTreeToken = TreeToken<CrdtTreeNode>

internal class CrdtTree(
    val root: CrdtTreeNode,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtGCElement() {

    internal val indexTree = IndexTree(root)

    private val nodeMapByID = TreeMap<CrdtTreeNodeID, CrdtTreeNode>()

    private val removedNodeMap = mutableMapOf<Pair<TimeTicket, Int>, CrdtTreeNode>()

    val rootTreeNode: TreeNode
        get() = indexTree.root.toTreeNode()

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
        val changes = listOf(
            TreeChange(
                type = TreeChangeType.Style,
                from = toIndex(fromParent, fromLeft),
                to = toIndex(toParent, toLeft),
                fromPath = toPath(fromParent, fromLeft),
                toPath = toPath(toParent, toLeft),
                actorID = executedAt.actorID,
                attributes = attributes,
            ),
        )

        traverseInPosRange(
            fromParent = fromParent,
            fromLeft = fromLeft,
            toParent = toParent,
            toLeft = toLeft,
        ) { (node, _), _ ->
            if (!node.isRemoved && attributes != null && !node.isText) {
                attributes.forEach { (key, value) ->
                    node.setAttribute(key, value, executedAt)
                }
            }
        }

        return changes
    }

    private fun toPath(parentNode: CrdtTreeNode, leftSiblingNode: CrdtTreeNode): List<Int> {
        return indexTree.treePosToPath(toCrdtTreePos(parentNode, leftSiblingNode))
    }

    private fun toCrdtTreePos(
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
        splitLevel: Int,
        executedAt: TimeTicket,
        issueTimeTicket: (() -> TimeTicket)? = null,
        latestCreatedAtMapByActor: Map<ActorID, TimeTicket>? = null,
    ): Pair<List<TreeChange>, Map<ActorID, TimeTicket>> {
        // 01. find nodes from the given range and split nodes.
        val (fromParent, fromLeft) = findNodesAndSplitText(range.first, executedAt)
        val (toParent, toLeft) = findNodesAndSplitText(range.second, executedAt)

        val fromIndex = toIndex(fromParent, fromLeft)
        val fromPath = toPath(fromParent, fromLeft)

        val nodesToBeRemoved = mutableListOf<CrdtTreeNode>()
        val tokensToBeRemoved = mutableListOf<CrdtTreeToken>()
        val toBeMovedToFromParents = mutableListOf<CrdtTreeNode>()
        val latestCreatedAtMap = mutableMapOf<ActorID, TimeTicket>()

        traverseInPosRange(
            fromParent = fromParent,
            fromLeft = fromLeft,
            toParent = toParent,
            toLeft = toLeft,
        ) { (node, tokenType), ended ->
            // NOTE(hackerwins): If the node overlaps as a start tag with the
            // range then we need to move the remaining children to fromParent.
            if (tokenType == TokenType.Start && !ended) {
                toBeMovedToFromParents.addAll(node.children)
            }

            val actorID = node.createdAt.actorID
            val latestCreatedAt = latestCreatedAtMapByActor?.let {
                latestCreatedAtMapByActor[actorID] ?: InitialTimeTicket
            } ?: MaxTimeTicket

            if (node.canDelete(executedAt, latestCreatedAt) || node.parent in nodesToBeRemoved) {
                val latest = latestCreatedAtMap[actorID]
                val createdAt = node.createdAt

                if (latest == null || latest < createdAt) {
                    latestCreatedAtMap[actorID] = createdAt
                }

                if (tokenType == TokenType.Text || tokenType == TokenType.Start) {
                    nodesToBeRemoved.add(node)
                }
                tokensToBeRemoved.add(TreeToken(node, tokenType))
            }
        }

        // NOTE(hackerwins): If concurrent deletion happens, we need to separate the
        // range(from, to) into multiple ranges.
        val changes = makeDeletionChanges(tokensToBeRemoved, executedAt).toMutableList()

        // 02. Delete: delete the nodes that are marked as removed.
        nodesToBeRemoved.forEach { node ->
            node.remove(executedAt)
            if (node.isRemoved) {
                removedNodeMap[node.createdAt to node.id.offset] = node
            }
        }

        // 03. Merge: move the nodes that are marked as moved.
        toBeMovedToFromParents.filter { it.removedAt == null }.forEach(fromParent::append)

        // 04. Split: split the element nodes for the given split level.
        if (splitLevel > 0 && issueTimeTicket != null) {
            var parent = fromParent
            var left = fromLeft
            repeat(splitLevel) {
                parent.split(this, parent.findOffset(left) + 1, issueTimeTicket)
                left = parent
                parent = parent.parent ?: return@repeat
            }
            changes.add(
                TreeChange(
                    type = TreeChangeType.Content,
                    from = fromIndex,
                    to = fromIndex,
                    fromPath = fromPath,
                    toPath = fromPath,
                    actorID = executedAt.actorID,
                ),
            )
        }

        // 05. insert the given node at the given position.
        if (contents?.isNotEmpty() == true) {
            val aliveContents = mutableListOf<CrdtTreeNode>()
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
                if (!content.isRemoved) {
                    aliveContents.add(content)
                }
            }

            if (aliveContents.isNotEmpty()) {
                val value = aliveContents.map(CrdtTreeNode::toTreeNode)
                val lastChange = changes.lastOrNull()
                if (changes.isNotEmpty() && lastChange?.from == fromIndex) {
                    changes[changes.lastIndex] = lastChange.copy(value = value)
                } else {
                    changes.add(
                        TreeChange(
                            type = TreeChangeType.Content,
                            from = fromIndex,
                            to = fromIndex,
                            fromPath = fromPath,
                            toPath = fromPath,
                            actorID = executedAt.actorID,
                            value = value,
                        ),
                    )
                }
            }
        }
        return changes to latestCreatedAtMap
    }

    /**
     * Converts nodes to be deleted to deletion changes.
     */
    private fun makeDeletionChanges(
        candidates: List<CrdtTreeToken>,
        executedAt: TimeTicket,
    ): List<TreeChange> {
        val changes = mutableListOf<TreeChange>()
        val ranges = mutableListOf<Pair<CrdtTreeToken, CrdtTreeToken>>()

        // Generate ranges by accumulating consecutive nodes.
        var start: CrdtTreeToken? = null
        var end: CrdtTreeToken
        for (i in candidates.indices) {
            val cur = candidates[i]
            val next = candidates.getOrNull(i + 1)
            if (start == null) {
                start = cur
            }
            end = cur

            val rightToken = findRightToken(cur)
            if (next == null || rightToken != next) {
                ranges.add(start to end)
                start = null
            }
        }

        // Convert each range to a deletion change.
        ranges.forEach { range ->
            val (_start, _end) = range
            val (fromLeft, fromLeftTokenType) = findLeftToken(_start)
            val (toLeft, toLeftTokenType) = _end
            val fromParent =
                fromLeft.takeIf { fromLeftTokenType == TokenType.Start } ?: fromLeft.parent
            val toParent = toLeft.takeIf { toLeftTokenType == TokenType.Start } ?: toLeft.parent

            if (fromParent == null || toParent == null) {
                return emptyList()
            }

            val fromIndex = toIndex(fromParent, fromLeft)
            val toIndex = toIndex(toParent, toLeft)
            if (fromIndex < toIndex) {
                // When the range is overlapped with the previous one, compact them.
                val lastChange = changes.lastOrNull()
                if (changes.isNotEmpty() && fromIndex == lastChange?.to) {
                    changes[changes.lastIndex] = lastChange.copy(
                        to = toIndex,
                        toPath = toPath(toParent, toLeft),
                    )
                } else {
                    changes.add(
                        TreeChange(
                            type = TreeChangeType.Content,
                            from = fromIndex,
                            to = toIndex,
                            fromPath = toPath(fromParent, fromLeft),
                            toPath = toPath(toParent, toLeft),
                            actorID = executedAt.actorID,
                        ),
                    )
                }
            }
        }
        return changes
    }

    private fun findRightToken(treeToken: CrdtTreeToken): CrdtTreeToken {
        fun CrdtTreeNode.rightTokenType() = if (isText) TokenType.Text else TokenType.Start

        val (node, tokenType) = treeToken
        if (tokenType == TokenType.Start) {
            val children = node.allChildren
            return if (children.isEmpty()) {
                TreeToken(node, TokenType.End)
            } else {
                TreeToken(children.first(), children.first().rightTokenType())
            }
        }

        val parent = node.parent
        val siblings = parent?.allChildren ?: emptyList()
        val offset = siblings.indexOf(node)
        return if (parent != null && offset == siblings.size - 1) {
            TreeToken(parent, TokenType.End)
        } else {
            val next = siblings[offset + 1]
            TreeToken(next, next.rightTokenType())
        }
    }

    private fun findLeftToken(treeToken: CrdtTreeToken): CrdtTreeToken {
        fun CrdtTreeNode.leftTokenType() = if (isText) TokenType.Text else TokenType.End

        val (node, tokenType) = treeToken
        if (tokenType == TokenType.End) {
            val children = node.allChildren
            return if (children.isEmpty()) {
                TreeToken(node, TokenType.Start)
            } else {
                TreeToken(children.last(), children.last().leftTokenType())
            }
        }

        val parent = node.parent
        val siblings = parent?.allChildren ?: emptyList()
        val offset = siblings.indexOf(node)
        return if (parent != null && offset == 0) {
            TreeToken(parent, TokenType.Start)
        } else {
            val prev = siblings[offset - 1]
            TreeToken(prev, prev.leftTokenType())
        }
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

    private fun traverseInPosRange(
        fromParent: CrdtTreeNode,
        fromLeft: CrdtTreeNode,
        toParent: CrdtTreeNode,
        toLeft: CrdtTreeNode,
        callback: (CrdtTreeToken, Boolean) -> Unit,
    ) {
        val fromIndex = toIndex(fromParent, fromLeft)
        val toIndex = toIndex(toParent, toLeft)
        indexTree.tokensBetween(fromIndex, toIndex, callback)
    }

    fun registerNode(node: CrdtTreeNode) {
        nodeMapByID[node.id] = node
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
        // 01. Find the parent and left sibling node of the given position.
        val (parent, leftSibling) = pos.toTreeNodes(this)

        // 02. Determine whether the position is left-most and the exact parent
        // in the current tree.
        val isLeftMost = parent == leftSibling
        val realParent =
            leftSibling.parent.takeIf { leftSibling.parent != null && !isLeftMost }
                ?: parent

        // 03. Split text node if the left node is a text node.
        if (leftSibling.isText) {
            leftSibling.split(this, pos.leftSiblingID.offset - leftSibling.id.offset)
        }

        // 04. Find the appropriate left node. If some nodes are inserted at the
        // same position concurrently, then we need to find the appropriate left
        // node. This is similar to RGA.
        val allChildren = realParent.allChildren
        val index = if (isLeftMost) 0 else allChildren.indexOf(leftSibling) + 1

        var updatedLeftSiblingNode = leftSibling
        for (node in allChildren.drop(index)) {
            if (node.id.createdAt <= executedAt) break
            updatedLeftSiblingNode = node
        }
        return realParent to updatedLeftSiblingNode
    }

    fun findFloorNode(id: CrdtTreeNodeID): CrdtTreeNode? {
        val (key, value) = nodeMapByID.floorEntry(id) ?: return null
        return value.takeIf { key.createdAt == id.createdAt }
    }

    /**
     * Edits the given [range] with the given [contents].
     * This method uses indexes instead of a pair of [TreePos] for testing.
     */
    @VisibleForTesting
    fun editT(
        range: Pair<Int, Int>,
        contents: List<CrdtTreeNode>? = null,
        splitLevel: Int,
        executedAt: TimeTicket,
        issueTimeTicket: () -> TimeTicket,
    ) {
        val fromPos = findPos(range.first)
        val toPos = findPos(range.second)
        edit(fromPos to toPos, contents, splitLevel, executedAt, issueTimeTicket)
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
        val treePos = indexTree.findTreePos(index, preferText)
        return treePos.toCrdtTreePos()
    }

    /**
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtElement {
        return CrdtTree(root.deepCopy(), createdAt, movedAt, removedAt)
    }

    /**
     * Converts the given [parentNode] to the index of the tree.
     */
    fun toIndex(parentNode: CrdtTreeNode, leftSiblingNode: CrdtTreeNode): Int {
        return indexTree.indexOf(toCrdtTreePos(parentNode, leftSiblingNode))
    }

    /**
     * Converts the given path of the node to the range of the position.
     */
    fun pathToPosRange(path: List<Int>): TreePosRange {
        val fromIndex = pathToIndex(path)
        return findPos(fromIndex) to findPos(fromIndex + 1)
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

    /**
     * Converts the pos to parent and left sibling nodes.
     */
    private fun CrdtTreePos.toTreeNodes(tree: CrdtTree): Pair<CrdtTreeNode, CrdtTreeNode> {
        val parentNode = tree.findFloorNode(parentID)
        val leftNode = tree.findFloorNode(leftSiblingID)
        require(parentNode != null && leftNode != null) {
            "cannot find node at $this"
        }

        /**
         * NOTE(hackerwins): If the left node and the parent node are the same,
         * it means that the position is the left-most of the parent node.
         * We need to skip finding the left of the position.
         */
        val updatedLeftSiblingNode =
            if (leftSiblingID != parentID &&
                leftSiblingID.offset > 0 &&
                leftSiblingID.offset == leftNode.id.offset &&
                leftNode.insPrevID != null
            ) {
                leftNode.insPrevID?.let(tree::findFloorNode) ?: leftNode
            } else {
                leftNode
            }
        return parentNode to updatedLeftSiblingNode
    }

    /**
     * Creates a new instance of CRDTTreePos from the given TreePos.
     */
    private fun TreePos<CrdtTreeNode>.toCrdtTreePos(): CrdtTreePos {
        val (node, offset) = this

        var resultNode = node
        val leftNode = if (node.isText) {
            resultNode = requireNotNull(node.parent)
            node.parent?.takeIf {
                it.children.firstOrNull() == node && offset == 0
            } ?: node
        } else {
            if (offset == 0) node else node.children[offset - 1]
        }

        return CrdtTreePos(
            resultNode.id,
            CrdtTreeNodeID(leftNode.createdAt, leftNode.offset + offset),
        )
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
     * Clones this text node with the given [offset].
     */
    override fun cloneText(offset: Int) = clone(offset, id.createdAt)

    /**
     * Clones this element node with the given [issueTimeTicket] function.
     */
    override fun cloneElement(issueTimeTicket: () -> TimeTicket) = clone(0, issueTimeTicket())

    private fun clone(offset: Int, createdAt: TimeTicket): CrdtTreeNode {
        return copy(
            id = CrdtTreeNodeID(createdAt, offset),
            _value = null,
            childNodes = mutableListOf(),
        ).apply {
            removedAt = this@CrdtTreeNode.removedAt
        }
    }

    fun split(
        tree: CrdtTree,
        offset: Int,
        issueTimeTicket: (() -> TimeTicket)? = null,
    ): CrdtTreeNode? {
        val split = if (isText) {
            splitText(offset, id.offset)
        } else {
            splitElement(offset, requireNotNull(issueTimeTicket))
        }

        val node = this
        split?.apply {
            split.insPrevID = node.id
            node.insNextID?.let {
                tree.findFloorNode(it)?.insPrevID = split.id
                split.insNextID = node.insNextID
            }
            node.insNextID = split.id
            tree.registerNode(split)
        }

        return split
    }

    fun setAttribute(key: String, value: String, executedAt: TimeTicket) {
        _attributes.set(key, value, executedAt)
    }

    /**
     * Marks the node as removed.
     */
    fun remove(executedAt: TimeTicket) {
        val alived = removedAt == null

        if (alived || removedAt < executedAt) {
            removedAt = executedAt
        }
        if (alived) {
            if (parent?.removedAt == null) {
                updateAncestorSize()
            } else {
                requireNotNull(parent).size -= paddedSize
            }
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
