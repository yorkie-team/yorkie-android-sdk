package dev.yorkie.util

internal class SplayTree<V>(
    root: V? = null,
    private val lengthCalculator: (V) -> Int,
) {
    var root: Node<V>? = root?.let { Node(it, lengthCalculator) }
        private set

    val length
        get() = root?.weight ?: 0

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
            error("out of index range: pos: $target > node.length: ${node.length}")
        }
        return ValueToOffset(node.value, target)
    }

    fun indexOf(node: Node<V>?): Int {
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

    fun insert(value: V): Node<V> {
        return insertAfter(root, value)
    }

    fun insertAfter(target: Node<V>?, newValue: V): Node<V> {
        val newNode = Node(newValue, lengthCalculator)
        if (target == null) {
            root = newNode
            return newNode
        }
        splayNode(target)
        root = newNode
        newNode.right = target.right
        target.right?.parent = newNode
        newNode.left = target
        target.parent = newNode
        target.right = null
        updateWeight(target)
        updateWeight(newNode)

        return newNode;
    }

    fun splayNode(node: Node<V>?) {
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

    fun updateWeight(node: Node<V>) {
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
            val Empty = ValueToOffset(null, 0)
        }
    }

    data class Node<V>(val value: V, val lengthCalculator: (V) -> Int) {
        var left: Node<V>? = null
        var right: Node<V>? = null
        var parent: Node<V>? = null

        var weight = lengthCalculator.invoke(value)
            private set

        val length
            get() = lengthCalculator.invoke(value)

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
            weight = lengthCalculator.invoke(value)
        }

        fun unlink() {
            parent = null
            left = null
            right = null
            // should we initWeight?
        }
    }
}
