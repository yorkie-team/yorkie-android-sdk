package dev.yorkie.util

import com.google.common.annotations.VisibleForTesting
import dev.yorkie.util.SplayTreeSet.LengthCalculator

/**
 * SplayTreeSet is weighted binary search tree set based on Splay tree.
 * original paper on Splay Trees:
 * @link https://www.cs.cmu.edu/~sleator/papers/self-adjusting.pdf
 */
// NOTE(skhugh): should SplayTree implement MutableSet?
internal class SplayTreeSet<V>(lengthCalculator: LengthCalculator<V>? = null) {
    @Suppress("UNCHECKED_CAST")
    private val lengthCalculator: LengthCalculator<V> =
        lengthCalculator ?: LengthCalculator.DEFAULT as LengthCalculator<V>

    private val valueToNodes = mutableMapOf<V, Node<V>>()

    @VisibleForTesting
    var root: Node<V>? = null
        private set

    val length
        get() = root?.weight ?: 0

    private val rightmost: Node<V>?
        get() {
            var node = root
            while (node?.right != null) {
                node = node.right
            }
            return node
        }

    private fun subtree(node: Node<V>?): SplayTreeSet<V> {
        return SplayTreeSet(lengthCalculator).also {
            it.root = node
        }
    }

    /**
     * Returns the value and offset of the given index.
     */
    @Suppress("UNCHECKED_CAST")
    fun find(pos: Int): ValueToOffset<V> {
        var target = pos
        val root = root
        if (root == null || target < 0) {
            return ValueToOffset.Empty as ValueToOffset<V>
        }
        var node: Node<V> = root
        while (true) {
            if (target <= node.leftWeight) {
                node = requireNotNull(node.left)
            } else if (node.right != null && node.leftWeight + node.length < target) {
                target -= node.leftWeight + node.length
                node = requireNotNull(node.right)
            } else {
                target -= node.leftWeight
                break
            }
        }
        if (target > node.length) {
            throw IndexOutOfBoundsException(
                "out of index range: pos: $target > node.length: ${node.length}",
            )
        }
        return ValueToOffset(node.value, target)
    }

    /**
     * Finds the index of the given [value] in BST.
     */
    fun indexOf(value: V?): Int {
        val node = valueToNodes[value]
        if (node == null || !node.hasLinks) {
            return -1
        }
        var index = 0
        var current: Node<V>? = node
        var prev: Node<V>? = null
        while (current != null) {
            if (prev == null || prev === current.right) {
                index += current.length + current.leftWeight
            }
            prev = current
            current = current.parent
        }
        return index - node.length
    }

    fun insert(value: V) {
        insertAfterInternal(root, value)
    }

    fun insertAfter(target: V?, newValue: V) {
        insertAfterInternal(target?.let(valueToNodes::get), newValue)
    }

    private fun insertAfterInternal(target: Node<V>?, newValue: V) {
        val newNode = valueToNodes[newValue]?.also {
            it.unlink()
            it.initWeight()
        } ?: Node(newValue, lengthCalculator)
        valueToNodes[newValue] = newNode

        if (target == null) {
            root = newNode
            return
        }
        splayInternal(target)
        root = newNode
        newNode.right = target.right
        target.right?.parent = newNode
        newNode.left = target
        target.parent = newNode
        target.right = null
        updateWeight(target)
        updateWeight(newNode)
        return
    }

    fun delete(value: V) {
        deleteInternal(
            valueToNodes[value]
                ?: throw IllegalArgumentException("requested value: $value is not in the tree"),
        )
    }

    private fun deleteInternal(node: Node<V>) {
        splayInternal(node)

        val leftTree = subtree(node.left)
        leftTree.root?.parent = null

        val rightTree = subtree(node.right)
        rightTree.root?.parent = null

        if (leftTree.root != null) {
            val rightmostNode = leftTree.rightmost
            leftTree.splayInternal(rightmostNode)
            leftTree.root?.right = rightTree.root
            if (rightTree.root != null) {
                rightTree.root?.parent = leftTree.root
            }
            root = leftTree.root
        } else {
            root = rightTree.root
        }

        node.unlink()
        root?.let(::updateWeight)
        valueToNodes.remove(node.value)
    }

    /**
     * Separates the range between given 2 boundaries from this Tree.
     * This function separates the range to delete as a subtree by splaying outer boundary nodes.
     * [leftBoundary] must exist because of 0-indexed initial dummy node of tree,
     * but [rightBoundary] can be null, which means range is from [leftBoundary] to the end of tree.
     * Refer to the design document in https://github.com/yorkie-team/yorkie/blob/main/design/range-deletion-in-splay-tree.md
     *
     * Boundary range are exclusive.
     */
    fun deleteRange(leftBoundary: V, rightBoundary: V? = null) {
        deleteRangeInternal(
            valueToNodes[leftBoundary]
                ?: throw IllegalArgumentException("leftBoundary cannot be null"),
            valueToNodes[rightBoundary],
        )
    }

    private fun deleteRangeInternal(leftBoundary: Node<V>, rightBoundary: Node<V>?) {
        splayInternal(leftBoundary)
        if (rightBoundary == null) {
            cutOffRight(leftBoundary)
            return
        }
        splayInternal(rightBoundary)
        if (rightBoundary.left != leftBoundary) {
            rotateRight(leftBoundary)
        }
        cutOffRight(leftBoundary)
    }

    private fun cutOffRight(node: Node<V>) {
        val nodesToFreeWeight = traversePostorder(node.right)
        nodesToFreeWeight.forEach {
            it.unlink()
            valueToNodes.remove(it.value)
        }
        node.right = null
        updateTreeWeight(node)
    }

    private fun updateTreeWeight(node: Node<V>?) {
        var target = node
        while (target != null) {
            updateWeight(target)
            target = target.parent
        }
    }

    private fun traversePostorder(node: Node<V>?): List<Node<V>> {
        return buildList { traversePostorderInternal(node) }
    }

    private fun MutableList<Node<V>>.traversePostorderInternal(node: Node<V>?) {
        if (node == null) {
            return
        }
        traversePostorderInternal(node.left)
        traversePostorderInternal(node.right)
        add(node)
    }

    @VisibleForTesting
    fun traverseInorder(node: Node<V>?): List<Node<V>> {
        return buildList { traverseInorderInternal(node) }
    }

    private fun MutableList<Node<V>>.traverseInorderInternal(node: Node<V>?) {
        if (node == null) {
            return
        }
        traverseInorderInternal(node.left)
        add(node)
        traverseInorderInternal(node.right)
    }

    fun splay(value: V) {
        splayInternal(valueToNodes[value])
    }

    private fun splayInternal(node: Node<V>?) {
        if (node == null) {
            return
        }
        while (true) {
            if (node.parent.isLeftChild && node.isRightChild) {
                // zig-zag
                rotateLeft(node)
                rotateRight(node)
            } else if (node.parent.isRightChild && node.isLeftChild) {
                // zig-zag
                rotateRight(node)
                rotateLeft(node)
            } else if (node.parent.isLeftChild && node.isLeftChild) {
                // zig-zig
                rotateRight(node.parent)
                rotateRight(node)
            } else if (node.parent.isRightChild && node.isRightChild) {
                // zig-zig
                rotateLeft(node.parent)
                rotateLeft(node)
            } else {
                // zig
                if (node.isLeftChild) {
                    rotateRight(node)
                } else if (node.isRightChild) {
                    rotateLeft(node)
                }
                updateWeight(node)
                return
            }
        }
    }

    /**
     * Recalculates weight of [node].
     */
    private fun updateWeight(node: Node<V>) {
        node.initWeight()
        node.increaseWeight(node.leftWeight)
        node.increaseWeight(node.rightWeight)
    }

    private fun rotateLeft(pivot: Node<V>?) {
        val root = pivot?.parent ?: return
        if (root.parent != null) {
            if (root.isLeftChild) {
                root.parent?.left = pivot
            } else {
                root.parent?.right = pivot
            }
        } else {
            this.root = pivot
        }
        pivot.parent = root.parent

        root.right = pivot.left
        root.right?.parent = root

        pivot.left = root
        pivot.left?.parent = pivot

        updateWeight(root)
        updateWeight(pivot)
    }

    private fun rotateRight(pivot: Node<V>?) {
        val root = pivot?.parent ?: return
        if (root.parent != null) {
            if (root.isLeftChild) {
                root.parent?.left = pivot
            } else {
                root.parent?.right = pivot
            }
        } else {
            this.root = pivot
        }
        pivot.parent = root.parent

        root.left = pivot.right
        root.left?.parent = root

        pivot.right = root
        pivot.right?.parent = pivot

        updateWeight(root)
        updateWeight(pivot)
    }

    private val Node<V>?.isLeftChild
        get() = this != null && parent?.left === this

    private val Node<V>?.isRightChild
        get() = this != null && parent?.right === this

    data class ValueToOffset<V>(val value: V?, val offset: Int) {

        companion object {
            val Empty by lazy { ValueToOffset(null, 0) }
        }
    }

    @VisibleForTesting
    data class Node<V>(val value: V, val lengthCalculator: LengthCalculator<V>) {
        var left: Node<V>? = null
        var right: Node<V>? = null
        var parent: Node<V>? = null

        var weight = length
            private set

        val length
            get() = lengthCalculator.calculateLength(value)

        val leftWeight
            get() = left?.weight ?: 0

        val rightWeight
            get() = right?.weight ?: 0

        val parentWeight
            get() = parent?.weight ?: 0

        val hasLinks
            get() = parent != null || left != null || right != null

        fun increaseWeight(weight: Int) {
            this.weight += weight
        }

        fun initWeight() {
            weight = length
        }

        fun unlink() {
            parent = null
            left = null
            right = null
            // NOTE(skhugh): should we initWeight?
        }
    }

    fun interface LengthCalculator<V> {
        fun calculateLength(value: V): Int

        companion object {
            val DEFAULT by lazy { LengthCalculator<Any> { 1 } }
        }
    }
}
