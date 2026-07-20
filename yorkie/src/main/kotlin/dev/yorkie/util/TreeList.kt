package dev.yorkie.util

/**
 * [TreeListValue] represents the data stored in the nodes of [TreeList].
 */
internal interface TreeListValue {
    val isRemoved: Boolean
}

/**
 * [TreeListNode] is a node of [TreeList].
 *
 * It tracks two aggregates over its subtree:
 * - weight: number of non-removed (live) nodes (logical index)
 * - count: total nodes including tombstones (structural index)
 */
internal class TreeListNode<V : TreeListValue>(val value: V) {
    var left: TreeListNode<V>? = null
    var right: TreeListNode<V>? = null
    var parent: TreeListNode<V>? = null

    var red: Boolean = true

    /**
     * Returns 1 if the node is live, 0 if removed (tombstone).
     */
    val size: Int
        get() = if (value.isRemoved) 0 else 1

    var weight: Int = size
    var count: Int = 1

    /**
     * Returns the live-node weight of the left subtree, or 0 if absent.
     */
    val leftWeight: Int
        get() = left?.weight ?: 0

    /**
     * Returns the live-node weight of the right subtree, or 0 if absent.
     */
    val rightWeight: Int
        get() = right?.weight ?: 0

    /**
     * Returns the total node count of the left subtree, or 0 if absent.
     */
    val leftCount: Int
        get() = left?.count ?: 0

    /**
     * Returns the total node count of the right subtree, or 0 if absent.
     */
    val rightCount: Int
        get() = right?.count ?: 0
}

/**
 * Reports whether the given node is a red link. A null node is treated as
 * black, matching the standard LLRB convention.
 */
private fun <V : TreeListValue> isRed(node: TreeListNode<V>?): Boolean {
    return node?.red == true
}

/**
 * Recomputes the cached weight and count aggregates of [node] from its
 * children. Call this whenever the structure or liveness of a child changes.
 */
private fun <V : TreeListValue> updateNode(node: TreeListNode<V>) {
    node.weight = node.leftWeight + node.size + node.rightWeight
    node.count = node.leftCount + 1 + node.rightCount
}

/**
 * Performs a standard LLRB left rotation around [node], promoting its right
 * child. Parent pointers and aggregate counters are refreshed and the new
 * subtree root is returned.
 */
private fun <V : TreeListValue> rotateLeft(node: TreeListNode<V>): TreeListNode<V> {
    val right = requireNotNull(node.right)

    node.right = right.left
    node.right?.parent = node

    right.left = node
    right.parent = node.parent
    node.parent = right

    right.red = node.red
    node.red = true

    updateNode(node)
    updateNode(right)
    return right
}

/**
 * Performs a standard LLRB right rotation around [node], promoting its left
 * child. Parent pointers and aggregate counters are refreshed and the new
 * subtree root is returned.
 */
private fun <V : TreeListValue> rotateRight(node: TreeListNode<V>): TreeListNode<V> {
    val left = requireNotNull(node.left)

    node.left = left.right
    node.left?.parent = node

    left.right = node
    left.parent = node.parent
    node.parent = left

    left.red = node.red
    node.red = true

    updateNode(node)
    updateNode(left)
    return left
}

/**
 * Toggles the colors of [node] and both of its children, used during LLRB
 * splits and merges.
 */
private fun <V : TreeListValue> flipColors(node: TreeListNode<V>) {
    node.red = !node.red
    requireNotNull(node.left).let { it.red = !it.red }
    requireNotNull(node.right).let { it.red = !it.red }
}

/**
 * Ensures the left child or one of its children is red so a deletion
 * descending to the left can proceed without violating LLRB invariants.
 * The new subtree root is returned.
 */
private fun <V : TreeListValue> moveRedLeft(node: TreeListNode<V>): TreeListNode<V> {
    var current = node
    flipColors(current)
    if (isRed(current.right?.left)) {
        current.right = rotateRight(requireNotNull(current.right))
        current.right?.parent = current
        current = rotateLeft(current)
        flipColors(current)
    }
    return current
}

/**
 * Ensures the right child or one of its children is red so a deletion
 * descending to the right can proceed without violating LLRB invariants.
 * The new subtree root is returned.
 */
private fun <V : TreeListValue> moveRedRight(node: TreeListNode<V>): TreeListNode<V> {
    var current = node
    flipColors(current)
    if (isRed(current.left?.left)) {
        current = rotateRight(current)
        flipColors(current)
    }
    return current
}

/**
 * Removes the minimum (left-most) node from the subtree rooted at [node] and
 * returns the rebalanced subtree root. Used by delete when splicing in the
 * in-order successor.
 */
private fun <V : TreeListValue> removeMin(node: TreeListNode<V>): TreeListNode<V>? {
    var current = node
    if (current.left == null) {
        return null
    }

    if (!isRed(current.left) && !isRed(current.left?.left)) {
        current = moveRedLeft(current)
    }

    current.left = removeMin(requireNotNull(current.left))
    current.left?.parent = current

    return fixUp(current)
}

/**
 * Returns the left-most node of the subtree rooted at [node], which is the
 * in-order successor used during deletion.
 */
private fun <V : TreeListValue> minNode(node: TreeListNode<V>): TreeListNode<V> {
    var current = node
    while (current.left != null) {
        current = requireNotNull(current.left)
    }
    return current
}

/**
 * Restores LLRB invariants on the way back up after an insertion or deletion:
 * it leans right-red links left, splits 4-nodes, and refreshes the node's
 * aggregate counters.
 */
private fun <V : TreeListValue> fixUp(node: TreeListNode<V>): TreeListNode<V> {
    var current = node
    if (isRed(current.right) && !isRed(current.left)) {
        current = rotateLeft(current)
    }
    if (isRed(current.left) && isRed(current.left?.left)) {
        current = rotateRight(current)
    }
    if (isRed(current.left) && isRed(current.right)) {
        flipColors(current)
    }
    updateNode(current)
    return current
}

/**
 * Walks the subtree rooted at [node] in left-root-right order, invoking
 * [action] on every node (live and tombstoned).
 */
private fun <V : TreeListValue> traverseInOrder(
    node: TreeListNode<V>?,
    action: (TreeListNode<V>) -> Unit,
) {
    if (node == null) {
        return
    }
    traverseInOrder(node.left, action)
    action(node)
    traverseInOrder(node.right, action)
}

/**
 * [TreeList] is an order-statistic tree based on Left-leaning Red-Black Tree.
 * It is used by RgaTreeList to support index-based operations on a list with
 * tombstones, guaranteeing O(log N) worst-case for all operations.
 *
 * It maintains two weights per node:
 * - weight: count of non-removed nodes (for index-based lookup)
 * - count: total nodes including tombstones (for structural operations)
 */
internal class TreeList<V : TreeListValue>(root: TreeListNode<V>? = null) {
    private var root: TreeListNode<V>? = root?.also { it.red = false }

    /**
     * Returns the number of non-removed (live) nodes.
     */
    val length: Int
        get() = root?.weight ?: 0

    /**
     * Inserts the [target] node right after [prev] in the in-order traversal.
     * It uses structural (count-based) indexing to correctly handle tombstone
     * nodes.
     */
    fun insertAfter(prev: TreeListNode<V>, target: TreeListNode<V>) {
        target.left = null
        target.right = null
        target.parent = null
        target.red = true
        target.weight = target.size
        target.count = 1

        val index = structuralIndexOf(prev)
        root = insertByCount(root, index + 1, target)
        requireNotNull(root).apply {
            red = false
            parent = null
        }
    }

    /**
     * Inserts [newNode] at the given structural [index] within the subtree
     * rooted at [node], descending the tree using each node's left count
     * (tombstones included) and rebalancing on the way back up.
     */
    private fun insertByCount(
        node: TreeListNode<V>?,
        index: Int,
        newNode: TreeListNode<V>,
    ): TreeListNode<V> {
        if (node == null) {
            return newNode
        }

        if (index <= node.leftCount) {
            node.left = insertByCount(node.left, index, newNode)
            node.left?.parent = node
        } else {
            node.right = insertByCount(node.right, index - node.leftCount - 1, newNode)
            node.right?.parent = node
        }

        return fixUp(node)
    }

    /**
     * Returns the node at the given logical [index] (among non-removed nodes).
     * @throws YorkieException when the index is out of range.
     */
    fun find(index: Int): TreeListNode<V> {
        val rootNode = root
        if (rootNode == null || index < 0 || index >= length) {
            throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "out of index: tree size $length, index $index",
            )
        }

        var node: TreeListNode<V> = rootNode
        var target = index
        while (true) {
            when {
                target < node.leftWeight -> node = requireNotNull(node.left)
                target < node.leftWeight + node.size -> break
                else -> {
                    target -= node.leftWeight + node.size
                    node = requireNotNull(node.right)
                }
            }
        }
        return node
    }

    /**
     * Physically removes [node] from the tree. Unlike tombstoning, this
     * completely removes the node from the tree structure. It uses structural
     * (count-based) indexing and swaps the node structure (not values) with
     * its successor to preserve node identity.
     */
    fun delete(node: TreeListNode<V>) {
        val currentRoot = root ?: return

        if (!isRed(currentRoot.left) && !isRed(currentRoot.right)) {
            currentRoot.red = true
        }

        val index = structuralIndexOf(node)
        root = deleteByCount(currentRoot, index)

        root?.apply {
            red = false
            parent = null
        }
    }

    /**
     * Removes the node at the given structural [index] within the subtree
     * rooted at [node]. When deleting an internal node, it swaps in the
     * in-order successor by re-parenting rather than copying values so
     * external references to the surviving node remain valid.
     */
    private fun deleteByCount(node: TreeListNode<V>, index: Int): TreeListNode<V>? {
        var current = node
        if (index < current.leftCount) {
            if (!isRed(current.left) && !isRed(current.left?.left)) {
                current = moveRedLeft(current)
            }
            current.left = deleteByCount(requireNotNull(current.left), index)
            current.left?.parent = current
        } else {
            if (isRed(current.left)) {
                current = rotateRight(current)
            }

            if (index == current.leftCount && current.right == null) {
                return null
            }

            if (!isRed(current.right) && !isRed(current.right?.left)) {
                current = moveRedRight(current)
            }

            if (index == current.leftCount) {
                // Swap the successor into this position instead of copying values.
                // This preserves node identity so external references remain valid.
                val successor = minNode(requireNotNull(current.right))
                val newRight = removeMin(requireNotNull(current.right))

                successor.left = current.left
                successor.right = newRight
                successor.red = current.red
                successor.left?.parent = successor
                successor.right?.parent = successor

                current.left = null
                current.right = null
                current.parent = null

                current = successor
            } else {
                current.right =
                    deleteByCount(requireNotNull(current.right), index - current.leftCount - 1)
                current.right?.parent = current
            }
        }

        return fixUp(current)
    }

    /**
     * Propagates weight changes from the given [node] up to the root. Call
     * this after a node's liveness changes (i.e., after tombstoning).
     */
    fun updateWeight(node: TreeListNode<V>) {
        var current: TreeListNode<V>? = node
        while (current != null) {
            current.weight = current.leftWeight + current.size + current.rightWeight
            current = current.parent
        }
    }

    /**
     * Returns a string containing the metadata of the nodes for debugging.
     */
    fun toTestString(): String {
        val builder = StringBuilder()
        traverseInOrder(root) { node ->
            builder.append("[${node.weight},${node.size}]${node.value}")
        }
        return builder.toString()
    }

    /**
     * Returns the logical (live-node) index of the given [node], or -1 if the
     * node is a tombstone.
     */
    fun indexOf(node: TreeListNode<V>): Int {
        if (node.size == 0) {
            return -1
        }
        var index = node.leftWeight
        var current = node
        while (true) {
            val parent = current.parent ?: break
            if (current === parent.right) {
                index += parent.leftWeight + parent.size
            }
            current = parent
        }
        return index
    }

    /**
     * Returns the structural position of the [node], counting all nodes
     * including tombstones.
     */
    private fun structuralIndexOf(node: TreeListNode<V>): Int {
        var index = node.leftCount
        var current = node
        while (true) {
            val parent = current.parent ?: break
            if (current === parent.right) {
                index += parent.leftCount + 1
            }
            current = parent
        }
        return index
    }
}
