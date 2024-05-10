package dev.yorkie.document.crdt

import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.time.ActorID
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE

/**
 * [TreeNode] represents the JSON representation of a node in the tree.
 * It is used to serialize and deserialize the tree.
 */
internal sealed class TreeNode {
    abstract val type: String
    abstract val childNodes: List<TreeNode>?
    abstract val value: String?
    abstract val attributes: Map<String, String>?

    fun toJsonTreeNode(): JsonTree.TreeNode {
        return this as JsonTree.TreeNode
    }

    override fun toString(): String {
        return if (type == DEFAULT_TEXT_TYPE) {
            """{"type":"$type","value":"$value"}"""
        } else {
            val ssb = StringBuilder(
                """{"type":"$type","children":[${
                    childNodes.orEmpty().joinToString(",")
                }]""",
            )
            val attributes = attributes
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

internal data class TreeElementNode(
    override val type: String,
    override val childNodes: List<TreeNode> = emptyList(),
    override val attributes: Map<String, String> = emptyMap(),
) : TreeNode(), JsonTree.ElementNode {
    override val value: String? = null

    @Suppress("UNCHECKED_CAST")
    override val children: List<JsonTree.TreeNode> = childNodes as List<JsonTree.TreeNode>

    override fun toString(): String {
        return super.toString()
    }
}

internal data class TreeTextNode(override val value: String = "") : TreeNode(), JsonTree.TextNode {
    override val type: String = DEFAULT_TEXT_TYPE

    override val childNodes: List<TreeNode>? = null

    override val attributes: Map<String, String>? = null
    override fun toString(): String {
        return super.toString()
    }
}

internal data class TreeChange(
    val actorID: ActorID,
    val type: TreeChangeType,
    val from: Int,
    val to: Int,
    val fromPath: List<Int>,
    val toPath: List<Int>,
    val value: List<TreeNode>? = null,
    val attributes: Map<String, String>? = null,
    val attributesToRemove: List<String>? = null,
    val splitLevel: Int = 0,
)

internal enum class TreeChangeType {
    Content,
    Style,
    RemoveStyle,
}
