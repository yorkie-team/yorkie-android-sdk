package dev.yorkie.util

internal fun <T : IndexTreeNode<T>> traverse(
    node: T,
    depth: Int = 0,
    action: ((T, Int) -> Unit),
) {
    node.children.forEach { child ->
        traverse(child, depth + 1, action)
    }
    action.invoke(node, depth)
}

internal fun <T : IndexTreeNode<T>> traverseAll(
    node: T,
    depth: Int = 0,
    action: ((T, Int) -> Unit),
) {
    node.allChildren.forEach { child ->
        traverseAll(child, depth + 1, action)
    }
    action.invoke(node, depth)
}

internal fun <T : IndexTreeNode<T>> findCommonAncestor(node1: T, node2: T): T? {
    if (node1 == node2) {
        return node1
    }

    val ancestorsOfNode1 = getAncestors(node1)
    val ancestorsOfNode2 = getAncestors(node2)

    var commonAncestor: T? = null
    for (index in ancestorsOfNode1.indices) {
        val ancestorOfNode1 = ancestorsOfNode1[index]
        val ancestorOfNode2 = ancestorsOfNode2.getOrNull(index)

        if (ancestorOfNode1 != ancestorOfNode2) break

        commonAncestor = ancestorOfNode1
    }
    return commonAncestor
}

private fun <T : IndexTreeNode<T>> getAncestors(node: T) = buildList {
    var parent = node.parent
    while (parent != null) {
        add(0, parent)
        parent = parent.parent
    }
}
