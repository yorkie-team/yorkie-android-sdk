package dev.yorkie.document.crdt

import dev.yorkie.document.CrdtTreePosStruct
import dev.yorkie.document.JsonSerializable
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.json.TreePosStructRange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.util.IndexTree
import dev.yorkie.util.IndexTreeNode
import dev.yorkie.util.TreePos
import dev.yorkie.util.traverse
import java.util.TreeMap

public typealias TreePosRange = Pair<CrdtTreePos, CrdtTreePos>

internal class CrdtTree(
    val root: CrdtTreeNode,
    override val createdAt: TimeTicket,
    override var _movedAt: TimeTicket? = null,
    override var _removedAt: TimeTicket? = null,
) : CrdtGCElement(), Collection<CrdtTreeNode> {

    private val head = CrdtTreeElement(CrdtTreePos.InitialCrdtTreePos, INITIAL_NODE_TYPE)

    internal val indexTree = IndexTree(root)

    private val nodeMapByPos = TreeMap<CrdtTreePos, CrdtTreeNode>()

    private val removedNodeMap = mutableMapOf<Pair<TimeTicket, Int>, CrdtTreeNode>()

    init {
        var previous = head
        indexTree.traverse { node, _ ->
            insertAfter(previous, node)
            previous = node
        }
    }

    override val removedNodesLength: Int
        get() = removedNodeMap.size

    override val size: Int
        get() = indexTree.size

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
     * Finds the right node of the given [index] in postorder.
     */
    fun findPostorderRight(index: Int): CrdtTreeNode? {
        val pos = indexTree.findTreePos(index, true)
        return indexTree.findPostorderRight(pos)
    }

    /**
     * Applies the given [attributes] of the given [range].
     */
    fun style(
        range: TreePosRange,
        attributes: Map<String, String>?,
        executedAt: TimeTicket,
    ): List<TreeChange> {
        val (_, toRight) = findTreePos(range.second, executedAt)
        val (_, fromRight) = findTreePos(range.first, executedAt)
        val changes = listOf(
            TreeChange(
                type = TreeChangeType.Style.type,
                from = toIndex(range.first),
                to = toIndex(range.second),
                fromPath = indexTree.indexToPath(posToStartIndex(range.first)),
                toPath = indexTree.indexToPath(posToStartIndex(range.first)),
                actorID = executedAt.actorID,
                attributes = attributes,
            ),
        )

        nodesBetween(fromRight, toRight) { node ->
            if (!node.isRemoved && attributes != null) {
                attributes.forEach { (key, value) ->
                    node.setAttribute(key, value, executedAt)
                }
            }
        }

        return changes
    }

    /**
     * Finds [TreePos] of the given [CrdtTreePos].
     */
    private fun findTreePos(
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

    /**
     * Edits the tree with the given [range] and [contents].
     * If the [contents] is null, the [range] will be removed.
     */
    fun edit(
        range: TreePosRange,
        contents: List<CrdtTreeNode>?,
        executedAt: TimeTicket,
    ): List<TreeChange> {
        // 01. split text nodes at the given range if needed.
        val (toPos, toRight) = findTreePosWithSplitText(range.second, executedAt)
        val (fromPos, fromRight) = findTreePosWithSplitText(range.first, executedAt)

        // NOTE(hackerwins): If concurrent deletion happens, we need to separate the
        // range(from, to) into multiple ranges.
        val changes = listOf(
            TreeChange(
                type = TreeChangeType.Content.type,
                from = toIndex(range.first),
                to = toIndex(range.second),
                fromPath = indexTree.treePosToPath(fromPos),
                toPath = indexTree.treePosToPath(toPos),
                actorID = executedAt.actorID,
                value = contents?.map(CrdtTreeNode::toJson),
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

            val isRangeOnSameBranch = toPos.node.isAncestorOf(fromPos.node)
            toBeRemoved.forEach { node ->
                node.remove(executedAt)
                if (node.isRemoved) {
                    removedNodeMap[node.createdAt to node.pos.offset] = node
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
        }

        // 03. insert the given node at the given position.
        if (contents?.isNotEmpty() == true) {
            // 03-1. insert the content nodes to the list.
            var previous = requireNotNull(fromRight.prev)
            var offset = fromPos.offset
            contents.forEach { content ->
                traverse(content) { node, _ ->
                    insertAfter(previous, node)
                    previous = node
                }

                // 03-2. insert the content nodes to the tree.
                val node = fromPos.node
                if (node.isText) {
                    if (fromPos.offset == 0) {
                        node.parent?.insertBefore(node, content)
                    } else {
                        node.parent?.insertAfter(node, content)
                    }
                } else {
                    node.insertAt(offset, content)
                    offset++
                }
            }
        }
        return changes
    }

    /**
     * Finds [TreePos] of the given [pos] and splits the text node if necessary.
     *
     * [CrdtTreePos] is a position in the CRDT perspective. This is
     * different from [TreePos] which is a position of the tree in the local perspective.
     */
    private fun findTreePosWithSplitText(
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

        if (current.node.isText) {
            current.node.split(current.offset)?.let { split ->
                insertAfter(current.node, split)
                split.insertionPrev = current.node
            }
        }

        val right = requireNotNull(indexTree.findPostorderRight(treePos))
        return current to right
    }

    /**
     * Inserts the [newNode] after the [prevNode]
     */
    private fun insertAfter(prevNode: CrdtTreeNode, newNode: CrdtTreeNode) {
        val next = prevNode.next
        prevNode.next = newNode
        newNode.prev = prevNode
        next?.let {
            newNode.next = next
            next.prev = newNode
        }
        nodeMapByPos[newNode.pos] = newNode
    }

    /**
     * Returns the nodes between the given range.
     * [left] is inclusive, while [right] is exclusive.
     */
    private fun nodesBetween(
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
     *  Returns start index of pos
     *       0   1   2 3 4 5 6    7  8
     *  <doc><p><tn>t e x t </tn></p></doc>
     *  If tree is just like above, and the pos is pointing index of 7
     * this returns 0 (start index of tag)
     */
    private fun posToStartIndex(pos: CrdtTreePos): Int {
        val treePos = toTreePos(pos)
        val index = toIndex(pos)
        val size = if (treePos?.node?.isText == true) {
            treePos.node.parent?.size
        } else {
            treePos?.node?.size
        } ?: -1

        return index - size - 1
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
            nodeMapByPos.remove(node.pos)
            delete(node)
            removedNodeMap.remove(node.createdAt to node.pos.offset)
        }

        return nodesToBeRemoved.size
    }

    /**
     * Physically deletes the given [node] from [IndexTree].
     */
    private fun delete(node: CrdtTreeNode) {
        val prev = node.prev
        val next = node.next
        prev?.next = next
        next?.prev = prev

        node.prev = null
        node.next = null
        node.insertionPrev = null
    }

    /**
     * Finds the position of the given [index] in the tree.
     */
    fun findPos(index: Int, preferText: Boolean = true): CrdtTreePos {
        val (node, offset) = indexTree.findTreePos(index, preferText)
        return CrdtTreePos(node.pos.createdAt, node.pos.offset + offset)
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
    fun toIndex(pos: CrdtTreePos): Int {
        return toTreePos(pos)?.let(indexTree::indexOf) ?: -1
    }

    /**
     * Converts the given path of the node to the range of the position.
     */
    fun pathToPosRange(path: List<Int>): TreePosRange {
        val index = pathToIndex(path)
        val (parentNode, offset) = pathToTreePos(path)

        if (parentNode.hasTextChild) {
            throw IllegalArgumentException("invalid path: $path")
        }

        val node = parentNode.children[offset]
        val fromIndex = index + node.size + 1
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
        val (node, offset) = indexTree.pathToTreePos(path)
        return CrdtTreePos(node.pos.createdAt, node.pos.offset + offset)
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
        val (fromPos, toPos) = indexRangeToPosRange(range)
        return fromPos.toStruct() to toPos.toStruct()
    }

    /**
     * Returns a pair of integer offsets of the tree.
     */
    fun rangeToIndex(range: TreePosRange): Pair<Int, Int> {
        return toIndex(range.first) to toIndex(range.second)
    }

    /**
     * Converts the given position [range] to the path range.
     */
    fun posRangeToPathRange(range: TreePosRange): Pair<List<Int>, List<Int>> {
        val fromPath = indexTree.indexToPath(toIndex(range.first))
        val toPath = indexTree.indexToPath(toIndex(range.second))
        return fromPath to toPath
    }

    override fun isEmpty() = indexTree.size == 0

    override fun iterator(): Iterator<CrdtTreeNode> {
        return object : Iterator<CrdtTreeNode> {
            var node = head.next

            override fun hasNext(): Boolean {
                while (node != null) {
                    if (node?.isRemoved == false) {
                        return true
                    }
                    node = node?.next
                }
                return false
            }

            override fun next(): CrdtTreeNode {
                return requireNotNull(node).also {
                    node = node?.next
                }
            }
        }
    }

    override fun containsAll(elements: Collection<CrdtTreeNode>): Boolean {
        return indexTree.root.children.containsAll(elements)
    }

    override fun contains(element: CrdtTreeNode): Boolean {
        return indexTree.root.children.contains(element)
    }

    companion object {
        private const val INITIAL_NODE_TYPE = "dummy"
    }
}

/**
 * [CrdtTreeNode] is a node of [CrdtTree]. It includes the logical clock and
 * links to other nodes to resolve conflicts.
 */
@Suppress("DataClassPrivateConstructor")
internal data class CrdtTreeNode private constructor(
    val pos: CrdtTreePos,
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
        get() = pos.createdAt

    var removedAt: TimeTicket? = null
        private set

    override val isRemoved: Boolean
        get() = removedAt != null

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

    var next: CrdtTreeNode? = null

    var prev: CrdtTreeNode? = null

    var insertionPrev: CrdtTreeNode? = null

    val rhtNodes: List<Rht.Node>
        get() = _attributes.toList()

    init {
        _value?.let { value = it }
    }

    /**
     * Clones this node with the given [offset].
     */
    override fun clone(offset: Int): CrdtTreeNode {
        return copy(pos = CrdtTreePos(pos.createdAt, offset))
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

    @Suppress("FunctionName")
    companion object {

        fun CrdtTreeText(pos: CrdtTreePos, value: String): CrdtTreeNode {
            return CrdtTreeNode(pos, DEFAULT_TEXT_TYPE, value)
        }

        fun CrdtTreeElement(
            pos: CrdtTreePos,
            type: String,
            children: List<CrdtTreeNode> = emptyList(),
            attributes: Rht = Rht(),
        ) = CrdtTreeNode(pos, type, null, children.toMutableList(), attributes)
    }
}

/**
 * [CrdtTreePos] represents a position in the tree. It indicates the virtual
 * location in the tree, so whether the node is split or not, we can find
 * the adjacent node to pos by calling `map.floorEntry()`.
 */
public data class CrdtTreePos(
    /**
     * Creation time of the node.
     */
    val createdAt: TimeTicket,

    /**
     * The distance from the beginning of the node when the node is split.
     */
    val offset: Int,
) : Comparable<CrdtTreePos>, JsonSerializable<CrdtTreePos, CrdtTreePosStruct> {

    override fun compareTo(other: CrdtTreePos): Int {
        return compareValuesBy(this, other, { it.createdAt }, { it.offset })
    }

    override fun toStruct(): CrdtTreePosStruct {
        return CrdtTreePosStruct(createdAt.toStruct(), offset)
    }

    companion object {
        internal val InitialCrdtTreePos = CrdtTreePos(InitialTimeTicket, 0)
    }
}
