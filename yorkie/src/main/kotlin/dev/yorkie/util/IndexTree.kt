package dev.yorkie.util

import com.google.common.annotations.VisibleForTesting

/**
 * About `index`, `path`, `size` and `TreePos` in crdt.IndexTree.
 *
 * `index` of crdt.IndexTree represents a absolute position of a node in the tree.
 * `size` is used to calculate the relative index of nodes in the tree.
 * `index` in yorkie.IndexTree inspired by ProseMirror's index.
 *
 * For example, empty paragraph's size is 0 and index 0 is the position of the:
 *    0
 * <p> </p>,                                p.size = 0
 *
 * If a paragraph has <i>, its size becomes 2 and there are 3 indexes:
 *     0   1    2
 *  <p> <i> </i> </p>                       p.size = 2, i.size = 0
 *
 * If the paragraph has <i> and <b>, its size becomes 4:
 *     0   1    2   3   4
 *  <p> <i> </i> <b> </b> </p>              p.size = 4, i.size = 0, b.size = 0
 *     0   1    2   3    4    5   6
 *  <p> <i> </i> <b> </b> <s> </s> </p>     p.size = 6, i.size = 0, b.size = 0, s.size = 0
 *
 * If a paragraph has text, its size becomes length of the characters:
 *     0 1 2 3
 *  <p> A B C </p>                          p.size = 3,   text.size = 3
 *
 * So the size of a node is the sum of the size and type of its children:
 *  `size = children(element type).length * 2 + children.reduce((child, acc) => child.size + acc, 0)`
 *
 * `TreePos` is also used to represent the position in the tree. It contains node and offset.
 * `TreePos` can be converted to `index` and vice versa.
 *
 * For example, if a paragraph has <i>, there are 3 indexes:
 *     0   1    2
 *  <p> <i> </i> </p>                       p.size = 2, i.size = 0
 *
 * In this case, index of TreePos(p, 0) is 0, index of TreePos(p, 1) is 2.
 * Index 1 can be converted to TreePos(i, 0).
 *
 * `path` of crdt.IndexTree represents a position like `index` in crdt.IndexTree.
 * It contains offsets of each node from the root node as elements except the last.
 * The last element of the path represents the position in the parent node.
 *
 * Let's say we have a tree like this:
 *                     0 1 2
 * <p> <i> a b </i> <b> c d </b> </p>
 *
 * The path of the position between 'c' and 'd' is [1, 1]. The first element of the
 * path is the offset of the <b> in <p> and the second element represents the position
 * between 'c' and 'd' in <b>.
 */

/**
 * [IndexTree] is a tree structure for linear indexing.
 */
internal class IndexTree<T : IndexTreeNode<T>>(val root: T) {

    val size: Int
        get() = root.size

    /**
     * Returns the nodes between the given range.
     */
    fun nodesBetween(
        from: Int,
        to: Int,
        action: (T, TagContained) -> Unit,
    ) {
        nodesBetweenInternal(root, from, to, action)
    }

    /**
     * Iterates the nodes between the given range.
     * If the given range is collapsed, the callback is not called.
     * It traverses the tree with postorder traversal.
     * NOTE(sejongk): Nodes should not be removed in callback, because it leads to wrong behaviors.
     */
    private fun nodesBetweenInternal(
        root: T,
        from: Int,
        to: Int,
        action: ((T, TagContained) -> Unit),
    ) {
        if (from > to) {
            throw IllegalArgumentException("from is greater than to: $from > $to")
        }
        if (from > root.size) {
            throw IllegalArgumentException("from is out of range: $from > ${root.size}")
        }
        if (to > root.size) {
            throw IllegalArgumentException("to is out of range: $to > ${root.size}")
        }
        if (from == to) return

        var pos = 0
        root.children.forEach { child ->
            // If the child is an element node, the size of the child.
            if (from - child.paddedSize < pos && pos < to) {
                // If the child is an element node, the range of the child
                // is from - 1 to to - 1. Because the range of the element node is from
                // the opening tag to the closing tag.
                val fromChild = if (child.isText) from - pos else from - pos - 1
                val toChild = if (child.isText) to - pos else to - pos - 1
                nodesBetweenInternal(
                    child,
                    fromChild.coerceAtLeast(0),
                    toChild.coerceAtMost(child.size),
                    action,
                )

                // If the range spans outside the child,
                // the callback is called with the child.
                if (fromChild < 0 || toChild > child.size || child.isText) {
                    val contained = when {
                        (fromChild < 0 && toChild > child.size) || child.isText -> TagContained.All
                        fromChild < 0 -> TagContained.Opening
                        else -> TagContained.Closing
                    }
                    action.invoke(child, contained)
                }
            }
            pos += child.paddedSize
        }
    }

    /**
     * Traverses the tree with postorder traversal.
     */
    fun traverse(action: ((T, Int) -> Unit)) {
        traverse(root, 0, action)
    }

    /**
     * Splits the node at the given [index].
     */
    fun split(index: Int, depth: Int = 1): TreePos<T> {
        val treePos = findTreePos(index, true)

        var node: T? = treePos.node
        var offset = treePos.offset
        repeat(depth) {
            val currentNode = node
            if (currentNode == null || currentNode == root) return@repeat

            currentNode.split(offset, 0)

            val nextOffset = currentNode.parent?.findOffset(currentNode) ?: return@repeat
            offset = if (offset == 0) nextOffset else nextOffset + 1
            node = currentNode.parent
        }

        return treePos
    }

    /**
     * Finds the position of the given [index] in the tree.
     */
    fun findTreePos(index: Int, preferText: Boolean = true): TreePos<T> {
        return findTreePosInternal(root, index, preferText)
    }

    /**
     * Fins the position of the given [index] in the given [node].
     */
    private fun findTreePosInternal(
        node: T,
        index: Int,
        preferText: Boolean = true,
    ): TreePos<T> {
        if (index > node.size) {
            throw IllegalArgumentException("index is out of range $index > ${node.size}")
        }
        if (node.isText) {
            return TreePos(node, index)
        }

        // offset is the index of the child node.
        // pos is the window of the index in the given node.
        var offset = 0
        var pos = 0
        node.children.forEach { child ->
            // The pos is in both sides of the text node, we should traverse
            // inside of the text node if preferText is true.
            if (preferText && child.isText && child.size >= index - pos) {
                return findTreePosInternal(child, index - pos, true)
            }

            // The position is in left side of the element node.
            if (index == pos) {
                return TreePos(node, offset)
            }

            // The position is in right side of the element node and preferText is false.
            if (!preferText && child.paddedSize == index - pos) {
                return TreePos(node, offset + 1)
            }

            // The position is in middle the element node.
            if (child.paddedSize > index - pos) {
                // If we traverse inside of the element node, we should skip the open.
                val skipOpenSize = 1
                return findTreePosInternal(child, index - pos - skipOpenSize, preferText)
            }

            pos += child.paddedSize
            offset += 1
        }

        // The position is in rightmost of the given node.
        return TreePos(node, offset)
    }

    /**
     * Returns path from the given [treePos].
     */
    fun treePosToPath(treePos: TreePos<T>): List<Int> {
        var node = treePos.node

        val path = if (node.isText) {
            val parent = node.parent
            val offset = parent?.findOffset(node) ?: return emptyList()
            if (offset == -1) {
                throw IllegalArgumentException("invalid treePos: $treePos")
            }

            val sizeOfLeftSiblings = addSizeOfLeftSiblings(parent, offset)
            node = parent
            mutableListOf(sizeOfLeftSiblings + treePos.offset)
        } else {
            mutableListOf(treePos.offset)
        }

        while (node.parent != null) {
            val parent = node.parent ?: break
            parent.findOffset(node).takeUnless { it == -1 }?.let(path::add)
                ?: throw IllegalArgumentException("invalid treePos: $treePos")
            node = parent
        }

        return path.reversed()
    }

    private fun addSizeOfLeftSiblings(parent: T, offset: Int): Int {
        return parent.children.take(offset).fold(0) { acc, leftSibling ->
            acc + if (leftSibling.isRemoved) 0 else leftSibling.paddedSize
        }
    }

    /**
     * Returns index from the given [path].
     */
    fun pathToIndex(path: List<Int>): Int {
        val treePos = pathToTreePos(path)
        return indexOf(treePos)
    }

    /**
     * Returns [TreePos] from the given [path].
     */
    fun pathToTreePos(path: List<Int>): TreePos<T> {
        if (path.isEmpty()) {
            throw IllegalArgumentException("unacceptable path")
        }

        var node = root
        for (index in 0 until path.size - 1) {
            val pathElement = path[index]
            node = node.children.getOrNull(pathElement)
                ?: throw IllegalArgumentException("unacceptable path")
        }

        return when {
            node.hasTextChild -> findTextPos(node, path.last())
            node.children.size < path.last() -> throw IllegalArgumentException("unacceptable path")
            else -> TreePos(node, path.last())
        }
    }

    private fun findTextPos(node: T, pathElement: Int): TreePos<T> {
        if (node.size < pathElement) {
            throw IllegalArgumentException("unacceptable path")
        }

        var updatedNode = node
        var updatedPathElement = pathElement
        for (index in node.children.indices) {
            val child = node.children[index]

            if (child.size < updatedPathElement) {
                updatedPathElement -= child.size
            } else {
                updatedNode = child
                break
            }
        }

        return TreePos(updatedNode, updatedPathElement)
    }

    private fun findLeftMost(node: T): T {
        return if (node.isText || node.children.isEmpty()) {
            node
        } else {
            findLeftMost(node.children.first())
        }
    }

    /**
     * Returns the index of the given tree [pos].
     */
    fun indexOf(pos: TreePos<T>): Int {
        var node = pos.node
        val offset = pos.offset

        var size = 0
        var depth = 1
        if (node.isText) {
            size += offset

            val parent = requireNotNull(node.parent)
            val offsetOfNode = parent.findOffset(node)
            if (offsetOfNode == -1) {
                throw IllegalArgumentException("invalid pos: $pos")
            }

            size += addSizeOfLeftSiblings(parent, offsetOfNode)
            node = parent
        } else {
            size += addSizeOfLeftSiblings(node, offset)
        }

        while (node.parent != null) {
            val parent = node.parent ?: break
            val offsetOfNode = parent.findOffset(node)
            if (offsetOfNode == -1) {
                throw IllegalArgumentException("invalid pos: $pos")
            }

            size += addSizeOfLeftSiblings(parent, offsetOfNode)
            depth++
            node = parent
        }

        return size + depth - 1
    }

    /**
     * Returns the path of the given [index].
     */
    fun indexToPath(index: Int): List<Int> {
        val treePos = findTreePos(index)
        return treePosToPath(treePos)
    }
}

/**
 * [TagContained] represents whether the opening or closing tag of a element is selected.
 */
internal enum class TagContained {
    All,
    Opening,
    Closing,
}

/**
 * [TreePos] is the position of a node in the tree.
 *
 * `offset` is the position of node's token. For example, if the node is an
 * element node, the offset is the index of the child node. If the node is a
 * text node, the offset is the index of the character.
 */
internal data class TreePos<T : IndexTreeNode<T>>(
    val node: T,
    val offset: Int,
)

/**
 * [IndexTreeNode] is the node of [IndexTree]. It is used to represent the
 * document of text-based editors.
 */
@Suppress("UNCHECKED_CAST")
internal abstract class IndexTreeNode<T : IndexTreeNode<T>>(children: MutableList<T>) {
    abstract val type: String

    protected val childrenInternal: MutableList<T> = children

    val isText
        get() = type == DEFAULT_TEXT_TYPE

    abstract val isRemoved: Boolean

    abstract var value: String
        protected set

    var parent: T? = null
        protected set

    var size = 0
        protected set

    val paddedSize: Int
        get() = size + if (isText) 0 else ELEMENT_PADDING_SIZE

    val nextSibling: T?
        get() {
            val offset = parent?.findOffset(this as T) ?: return null
            return parent?.children?.getOrNull(offset + 1)
        }

    /**
     * Returns the children of the node.
     * Tombstone nodes remain awhile in the tree during editing.
     * They will be removed after the editing is done.
     * So, we need to filter out the tombstone nodes to get the real children.
     */
    val children: List<T>
        get() = childrenInternal.filterNot { it.isRemoved }

    /**
     * Returns the children of the node including tombstones.
     */
    @VisibleForTesting
    val allChildren: List<T>
        get() = childrenInternal.toList()

    val hasTextChild: Boolean
        get() = children.any { it.isText }

    init {
        if (isText && childrenInternal.isNotEmpty()) {
            throw IllegalArgumentException("Text node cannot have children")
        }
    }

    /**
     * Updates the size of the ancestors.
     */
    fun updateAncestorSize() {
        var parent = parent
        val sign = if (isRemoved) -1 else 1

        while (parent != null) {
            parent.size += paddedSize * sign
            parent = parent.parent
        }
    }

    /**
     * Returns true if the node is an ancestor of the [targetNode].
     */
    fun isAncestorOf(targetNode: T): Boolean {
        if (this == targetNode) {
            return false
        }

        var node = targetNode
        while (node.parent != null) {
            if (node.parent == this) {
                return true
            }
            node = node.parent ?: break
        }
        return false
    }

    fun findOffset(node: T): Int {
        check(!isText) {
            "Text node cannot have children"
        }
        if (node.isRemoved) {
            val index = childrenInternal.indexOf(node)

            // If nodes are removed, the offset of the removed node is the number of
            // nodes before the node excluding the removed nodes.
            return allChildren.take(index).filterNot { it.isRemoved }.size
        }
        return children.indexOf(node)
    }

    /**
     * Returns offset of the given descendant node in this node.
     * If the given [node] is not a descendant of this node, it returns -1.
     */
    fun findBranchOffset(node: T): Int {
        check(!isText) {
            "Text node cannot have children"
        }

        var current: IndexTreeNode<T>? = node
        while (current != null) {
            val offset = childrenInternal.indexOf(current)
            if (offset != -1) {
                return offset
            }
            current = current.parent
        }

        return -1
    }

    /**
     * Appends the given nodes to the children.
     */
    fun append(vararg newNode: T) {
        check(!isText) {
            "Text node cannot have children"
        }

        childrenInternal.addAll(newNode)
        newNode.forEach { node ->
            node.parent = this as T

            if (!node.isRemoved) {
                node.updateAncestorSize()
            }
        }
    }

    /**
     * Prepends the given nodes to the children.
     */
    fun prepend(vararg newNode: T) {
        check(!isText) {
            "Text node cannot have children"
        }

        childrenInternal.addAll(0, newNode.toList())
        newNode.forEach { node ->
            node.parent = this as T

            if (!node.isRemoved) {
                node.updateAncestorSize()
            }
        }
    }

    /**
     * Inserts the [newNode] before the [targetNode].
     */
    fun insertBefore(targetNode: T, newNode: T) {
        check(!isText) {
            "Text node cannot have children"
        }

        val offset = childrenInternal.indexOf(targetNode).takeUnless { it == -1 }
            ?: throw NoSuchElementException("child not found")

        insertAtInternal(offset, newNode)
        newNode.updateAncestorSize()
    }

    /**
     * Inserts the [newNode] after the [targetNode].
     */
    fun insertAfter(targetNode: T, newNode: T) {
        check(!isText) {
            "Text node cannot have children"
        }

        val offset = childrenInternal.indexOf(targetNode).takeUnless { it == -1 }
            ?: throw NoSuchElementException("child not found")

        insertAtInternal(offset + 1, newNode)
        newNode.updateAncestorSize()
    }

    /**
     * Inserts the [newNode] at the given [offset].
     */
    fun insertAt(offset: Int, newNode: T) {
        check(!isText) {
            "Text node cannot have children"
        }

        insertAtInternal(offset, newNode)
        newNode.updateAncestorSize()
    }

    private fun insertAtInternal(offset: Int, newNode: T) {
        check(!isText) {
            "Text node cannot have children"
        }

        childrenInternal.add(offset, newNode)
        newNode.parent = this as T
    }

    /**
     * Removes the given [child].
     */
    fun removeChild(child: T) {
        check(!isText) {
            "Text node cannot have children"
        }

        val offset = childrenInternal.indexOf(child).takeUnless { it == -1 }
            ?: throw NoSuchElementException("child not found")

        childrenInternal.removeAt(offset)
        child.parent = null
    }

    /**
     * Splits the node at the given [offset].
     */
    fun split(offset: Int, absOffset: Int): T? {
        return if (isText) {
            splitText(offset, absOffset)
        } else {
            splitElement(offset)
        }
    }

    private fun splitText(offset: Int, absOffset: Int): T? {
        if (offset == 0 || offset == size) {
            return null
        }

        val leftValue = value.substring(0, offset)
        val rightValue = value.substring(offset).takeUnless { it.isEmpty() } ?: return null
        value = leftValue

        val rightNode = clone(offset + absOffset)
        rightNode.value = rightValue
        parent?.insertAfterInternal(this as T, rightNode)

        return rightNode
    }

    private fun splitElement(offset: Int): T {
        val clone = clone(offset)
        parent?.insertAfterInternal(this as T, clone)
        clone.updateAncestorSize()

        val leftChildren = childrenInternal.subList(0, offset)
        val rightChildren = childrenInternal.drop(offset)
        childrenInternal.clear()
        childrenInternal.addAll(leftChildren)
        clone.childrenInternal.clear()
        clone.childrenInternal.addAll(rightChildren)
        size = childrenInternal.fold(0) { acc, child ->
            acc + child.paddedSize
        }
        clone.size = clone.childrenInternal.fold(0) { acc, child ->
            acc + child.paddedSize
        }

        clone.childrenInternal.forEach { child ->
            child.parent = clone
        }

        return clone
    }

    private fun insertAfterInternal(targetNode: T, newNode: T) {
        check(!isText) {
            "Text node cannot have children"
        }

        val offset = childrenInternal.indexOf(targetNode).takeUnless { it == -1 }
            ?: throw NoSuchElementException("child not found")
        insertAtInternal(offset + 1, newNode)
    }

    abstract fun clone(offset: Int): T

    companion object {
        /**
         * [ELEMENT_PADDING_SIZE] is the size of an element node as a child of another element node.
         * The value is 2 since an element node could be considered
         * as a pair of opening and closing tags.
         */
        private const val ELEMENT_PADDING_SIZE = 2

        /**
         * [DEFAULT_TEXT_TYPE] is the default type of the text node.
         * It it used when the type of the text node is not specified.
         */
        internal const val DEFAULT_TEXT_TYPE = "text"

        /**
         * [DEFAULT_ROOT_TYPE] is the default type of the root node.
         * It is used when the type of the root node is not specified.
         */
        internal const val DEFAULT_ROOT_TYPE = "root"
    }
}
