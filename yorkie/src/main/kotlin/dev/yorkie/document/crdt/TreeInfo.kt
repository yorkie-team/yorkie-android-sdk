package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID

/**
 * [TreeNode] represents the JSON representation of a node in the tree.
 * It is used to serialize and deserialize the tree.
 */
public data class TreeNode(
    val type: String,
    val children: List<TreeNode>? = null,
    val value: String? = null,
    val attributes: Map<String, String>? = null,
)

internal data class TreeChange(
    val actorID: ActorID,
    val type: String,
    val from: Int,
    val to: Int,
    val fromPath: List<Int>,
    val toPath: List<Int>,
    val content: TreeNode? = null,
    val attributes: Map<String, String>? = null,
)

internal enum class TreeChangeType(val type: String) {
    Content("content"), Style("style")
}
