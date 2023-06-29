package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.util.IndexTree
import dev.yorkie.util.IndexTreeNode
import dev.yorkie.util.TreePos
import dev.yorkie.util.traverse
import java.util.TreeMap

internal typealias TreeRange = Pair<CrdtTreePos, CrdtTreePos>

internal class CrdtTree(
    val root: CrdtTreeNode,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtGCElement() {

    private val head = CrdtTreeNode(CrdtTreePos.InitialCrdtTreePos, INITIAL_NODE_TYPE)

    private val indexTree = IndexTree(root)

    init {
        var previous = head
        indexTree.traverse { node, _ ->
            insertAfter(previous, node)
            previous = node
        }
    }

    private val nodeMapByPos = TreeMap<CrdtTreePos, CrdtTreeNode>()

    private val removedNodeMap = mutableMapOf<TimeTicket, CrdtTreeNode>()

    override val removedNodesLength: Int
        get() = removedNodeMap.size

    /**
     * Returns the nodes between the given range.
     */
    fun nodesBetweenByTree(
        from: Int,
        to: Int,
        action: ((CrdtTreeNode) -> Unit),
    ) {
        indexTree.nodesBetween(from, to, action)
    }

    /**
     * Returns the nodes between the given range.
     * [left] is inclusive, while [right] is exclusive.
     */
    fun nodesBetween(
        left: CrdtTreeNode,
        right: CrdtTreeNode,
        action: (CrdtTreeNode) -> Unit,
    ) {
        var current: CrdtTreeNode? = left
        while (current != right) {
            current?.let(action)
                ?: throw IllegalArgumentException("left and right are not in the same list")
            current = current.next
        }
    }

    /**
     * Finds the right node of the given [index] in postorder.
     */
    fun findPostorderRight(index: Int): CrdtTreeNode? {
        val pos = indexTree.findTreePos(index, true)
        return indexTree.findPostorderRight(pos)
    }

    /**
     * Finds [TreePos] of the given [CrdtTreePos].
     */
    fun findTreePos(
        pos: CrdtTreePos,
        executedAt: TimeTicket,
    ): Pair<TreePos<CrdtTreeNode>, CrdtTreeNode> {
        val treePos = toTreePos(pos) ?: throw IllegalArgumentException("cannot find node at $pos")

        // Find the appropriate position. This logic is similar to
        // handling the insertion of the same position in RGA.
        var current = treePos
        while (executedAt < current.node.next?.pos?.createdAt &&
            current.node.parent == current.node.next?.parent
        ) {
            val nextNode = current.node.next ?: break
            current = TreePos(nextNode, nextNode.size)
        }
        val right = requireNotNull(indexTree.findPostorderRight(treePos))
        return current to right
    }

    /**
     * Finds [TreePos] of the given [pos] and splits the text node if necessary.
     *
     * [CrdtTreePos] is a position in the CRDT perspective. This is
     * different from [TreePos] which is a position of the tree in the local perspective.
     */
    fun findTreePosWithSplitText(
        pos: CrdtTreePos,
        executedAt: TimeTicket,
    ): Pair<TreePos<CrdtTreeNode>, CrdtTreeNode> {
        val (current, right) = findTreePos(pos, executedAt)

        if (current.node.isText) {
            current.node.split(current.offset)?.let { split ->
                insertAfter(current.node, split)
                split.insertionPrev = current.node
            }
        }

        return current to right
    }

    /**
     * Inserts the [newNode] after the [prevNode]
     */
    fun insertAfter(prevNode: CrdtTreeNode, newNode: CrdtTreeNode) {
        val next = prevNode.next
        prevNode.next = newNode
        newNode.prev = prevNode
        next?.let {
            newNode.next = next
            next.prev = newNode
        }
        nodeMapByPos[newNode.pos] = newNode
    }

    private fun toTreePos(pos: CrdtTreePos): TreePos<CrdtTreeNode>? {
        val (key, value) = nodeMapByPos.floorEntry(pos) ?: return null
        return if (key?.createdAt == pos.createdAt) {
            val node =
                // Choose the left node if the position is on the boundary of the split nodes.
                if (pos.offset > 0 && pos.offset == value.pos.offset &&
                    value.insertionPrev != null
                ) {
                    value.insertionPrev
                } else {
                    value
                }
            TreePos(requireNotNull(node), pos.offset - node.pos.offset)
        } else {
            null
        }
    }

    fun style(
        range: TreeRange,
        attributes: Map<String, String>?,
        executedAt: TimeTicket,
    ) {
        TODO()
    }

    fun edit(
        range: TreeRange,
        content: CrdtTreeNode?,
        executedAt: TimeTicket,
    ): List<TreeChange> {
        // 01. split text nodes at the given range if needed.
        val (toPos, toRight) = findTreePosWithSplitText(range.second, executedAt)
        val (fromPos, fromRight) = findTreePosWithSplitText(range.first, executedAt)

        // NOTE(hackerwins): If concurrent deletion happens, we need to seperate the
        // range(from, to) into multiple ranges.
        val changes = listOf(
            TreeChange(
                type = TreeChangeType.Content.type,
                from = toIndex(range.first),
                to = toIndex(range.second),
                fromPath = indexTree.treePosToPath(fromPos),
                toPath = indexTree.treePosToPath(toPos),
                actorID = executedAt.actorID,
                content = content?.let(::toJsonInternal),
            ),
        )

        val toBeRemoved = mutableListOf<CrdtTreeNode>()
        // 02. remove the nodes and update linked list and index tree.
        if (fromRight != toRight) {
            nodesBetween(fromRight, toRight) { node ->
                if (!node.isRemoved) {
                    toBeRemoved.add(node)
                }
            }
        }

        val isRangeOnSameBranch = toPos.node.isAncestorOf(fromPos.node)
        toBeRemoved.forEach { node ->
            node.remove(executedAt)
            if (node.isRemoved) {
                removedNodeMap[node.createdAt] = node
            }
        }

        // move the alive children of the removed element node
        if (isRangeOnSameBranch) {
            val removedElementNode = when {
                fromPos.node.parent?.isRemoved == true -> fromPos.node.parent
                !fromPos.node.isText && fromPos.node.isRemoved -> fromPos.node
                else -> null
            }
            removedElementNode?.let { removedNode ->
                val elementNode = toPos.node
                val offset = elementNode.findBranchOffset(removedNode)
                removedNode.children.reversed().forEach { node ->
                    elementNode.insertAt(offset, node)
                }
            }
        } else if (fromPos.node.parent?.isRemoved == true) {
            val parent = requireNotNull(fromPos.node.parent)
            toPos.node.parent?.prepend(*parent.children.toTypedArray())
        }

        // 03. insert the given node at the given position.
        content?.let {
            // 03-1. insert the content nodes to the list.
            var previous = fromRight.prev ?: return@let
            traverse(content) { node, _ ->
                insertAfter(previous, node)
                previous = node
            }

            // 03-2. insert the content nodes to the tree.
            if (fromPos.node.isText) {
                if (fromPos.offset == 0) {
                    fromPos.node.parent?.insertBefore(fromPos.node, content)
                } else {
                    fromPos.node.parent?.insertAfter(fromPos.node, content)
                }
            } else {
                val target = fromPos.node
                target.insertAt(fromPos.offset, content)
            }
        }
        return changes
    }

    fun editByIndex(
        range: Pair<Int, Int>,
        content: CrdtTreeNode?,
        executedAt: TimeTicket,
    ) {
        TODO()
    }

    override fun deleteRemovedNodesBefore(executedAt: TimeTicket): Int {
        TODO("Not yet implemented")
    }

    fun delete(node: CrdtTreeNode) {
        TODO()
    }

    fun findPos(index: Int, preferText: Boolean = true): CrdtTreePos {
        TODO()
    }

    override fun deepCopy(): CrdtElement {
        TODO("Not yet implemented")
    }

    /**
     * Converts the given [node] to JSON.
     */
    private fun toJsonInternal(node: CrdtTreeNode): TreeNode {
        return if (node.isText) {
            TreeNode(node.type, value = node.value)
        } else {
            TreeNode(node.type, node.children.map(::toJsonInternal), attributes = node.attributes)
        }
    }

    /**
     * Converts the given [pos] to the index of the tree.
     */
    fun toIndex(pos: CrdtTreePos): Int {
        return toTreePos(pos)?.let(indexTree::indexOf) ?: -1
    }

    fun posToStartIndex(pos: CrdtTreePos): Int {
        TODO()
    }

    fun pathToPosRange(path: List<Int>): TreeRange {
        TODO()
    }

    fun pathTOTreePos(path: List<Int>): TreePos<CrdtTreeNode> {
        TODO()
    }

    fun pathToPos(path: List<Int>): CrdtTreePos {
        TODO()
    }

    fun toXml(): String {
        TODO()
    }

    fun indexToPath(index: Int): List<Int> {
        TODO()
    }

    fun pathToIndex(path: List<Int>): Int {
        TODO()
    }

    fun createRange(fromIndex: Int, toIndex: Int): TreeRange {
        TODO()
    }

    fun rangeToIndex(range: TreeRange): Pair<Int, Int> {
        TODO()
    }

    fun rangeToPath(range: TreeRange): Pair<List<Int>, List<Int>> {
        TODO()
    }

    companion object {
        private const val INITIAL_NODE_TYPE = "dummy"
    }
}

/**
 * [CrdtTreeNode] is a node of [CrdtTree]. It includes the logical clock and
 * links to other nodes to resolve conflicts.
 */
internal class CrdtTreeNode(
    val pos: CrdtTreePos,
    type: String,
    opts: String? = null,
    optsList: List<CrdtTreeNode>? = null,
    private val _attributes: Rht? = null,
) : IndexTreeNode<CrdtTreeNode>(type) {

    init {
        optsList?.let(_children::addAll)
    }

    val attributes: Map<String, String>?
        get() = _attributes?.nodeKeyValueMap

    val createdAt: TimeTicket
        get() = pos.createdAt

    private var removedAt: TimeTicket? = null

    override val isRemoved: Boolean
        get() = removedAt != null

    override var value: String = opts.orEmpty()
        get() {
            check(!isText) {
                "cannot set value of element node: $type"
            }
            return field
        }
        set(value) {
            check(!isText) {
                "cannot set value of element node: $type"
            }
            field = value
            size = value.length
        }

    var next: CrdtTreeNode? = null

    var prev: CrdtTreeNode? = null

    var insertionPrev: CrdtTreeNode? = null

    /**
     * Clones this node with the given [offset].
     */
    override fun clone(offset: Int): CrdtTreeNode {
        return CrdtTreeNode(CrdtTreePos(pos.createdAt, offset), type)
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
    fun deepcopy(): CrdtTreeNode {
        val clone = CrdtTreeNode(pos, type, _attributes = _attributes?.deepCopy())
        val node = this
        return clone.apply clone@{
            removedAt = node.removedAt
            value = node.value
            size = node.size
            _children.clear()
            _children.addAll(
                node._children.map { child ->
                    child.deepcopy().apply {
                        parent = this@clone
                    }
                },
            )
        }
    }
}

/**
 * [CrdtTreePos] represents a position in the tree. It indicates the virtual
 * location in the tree, so whether the node is splitted or not, we can find
 * the adjacent node to pos by calling `map.floorEntry()`.
 */
internal data class CrdtTreePos(
    /**
     * Creation time of the node.
     */
    val createdAt: TimeTicket,

    /**
     * The distance from the beginning of the node when the node is split.
     */
    val offset: Int,
) : Comparable<CrdtTreePos> {

    override fun compareTo(other: CrdtTreePos): Int {
        return compareValuesBy(this, other, { it.createdAt }, { it.offset })
    }

    companion object {
        internal val InitialCrdtTreePos = CrdtTreePos(InitialTimeTicket, 0)
    }
}
