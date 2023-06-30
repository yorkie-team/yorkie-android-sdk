package dev.yorkie.document.crdt

/**
 * Converts the given [node] to XML.
 */
internal fun toXml(node: CrdtTreeNode): String {
    return if (node.isText) {
        node.value
    } else {
        val attrs = node.attributesToXml
        val children = node.children.joinToString("") { toXml(it) }
        "<${node.type}$attrs>$children</${node.type}>"
    }
}

/**
 * Converts the given [node] to JSON.
 */
internal fun toJson(node: CrdtTreeNode): TreeNode {
    return if (node.isText) {
        TreeNode(node.type, value = node.value)
    } else {
        TreeNode(node.type, node.children.map(::toJson), attributes = node.attributes)
    }
}
