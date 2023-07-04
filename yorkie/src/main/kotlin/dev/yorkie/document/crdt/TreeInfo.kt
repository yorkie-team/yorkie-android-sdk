package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE

/**
 * [TreeNode] represents the JSON representation of a node in the tree.
 * It is used to serialize and deserialize the tree.
 */
public data class TreeNode(
    val type: String,
    val children: List<TreeNode>? = null,
    val value: String? = null,
    val attributes: Map<String, String>? = null,
) {

    override fun toString(): String {
        return if (type == DEFAULT_TEXT_TYPE) {
            """{"type":"$type","value":"$value"}"""
        } else {
            val ssb = StringBuilder(
                """{"type":"$type","children":[${
                    children.orEmpty().joinToString(",")
                }]""",
            )
            if (attributes?.isNotEmpty() == true) {
                ssb.append(",")
                ssb.append(
                    """"attributes":{${
                        attributes.entries.joinToString(",") { (key, value) ->
                            """"$key":"$value""""
                        }
                    }}""",
                )
            }
            ssb.append("}")
            ssb.toString()
        }
    }
}

internal data class TreeChange(
    val actorID: ActorID,
    val type: String,
    val from: Int,
    val to: Int,
    val fromPath: List<Int>,
    val toPath: List<Int>,
    val value: TreeNode? = null,
    val attributes: Map<String, String>? = null,
)

internal enum class TreeChangeType(val type: String) {
    Content("content"), Style("style")
}
