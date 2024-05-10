package dev.yorkie.document.crdt

/**
 * Converts the given node to XML.
 */
internal fun CrdtTreeNode.toXml(): String {
    return if (isText) {
        value
    } else {
        val attrs = attributesToXml.let { if (it.isNotEmpty()) " $it" else it }
        val children = children.joinToString("") { it.toXml() }
        "<$type$attrs>$children</$type>"
    }
}

/**
 * Converts the given node to JSON.
 */
internal fun CrdtTreeNode.toTreeNode(): TreeNode {
    return if (isText) {
        TreeTextNode(value)
    } else {
        TreeElementNode(type, children.map(CrdtTreeNode::toTreeNode), attributes)
    }
}
