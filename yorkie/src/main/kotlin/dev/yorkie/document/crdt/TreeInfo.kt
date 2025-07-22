package dev.yorkie.document.crdt

import dev.yorkie.document.json.JsonTree
import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE

/**
 * [TreeNode] represents the JSON representation of a node in the tree.
 * It is used to serialize and deserialize the tree.
 */
sealed interface TreeNode {
    val type: String

    fun toJsonTreeNode(): JsonTree.TreeNode {
        return this as JsonTree.TreeNode
    }
}

data class TreeElementNode(
    override val type: String,
    val childNodes: List<TreeNode> = emptyList(),
    override val attributes: Map<String, String> = emptyMap(),
) : TreeNode, JsonTree.ElementNode {

    @Suppress("UNCHECKED_CAST")
    override val children: List<JsonTree.TreeNode> = childNodes as List<JsonTree.TreeNode>

    override fun toString(): String {
        val ssb = StringBuilder(
            """{"type":"$type","children":[${
                childNodes.joinToString(",")
            }]""",
        )
        val attributes = attributes
        if (attributes.isNotEmpty()) {
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
        return ssb.toString()
    }
}

@JvmInline
internal value class TreeTextNode(override val value: String = "") : TreeNode, JsonTree.TextNode {

    override val type: String
        get() = DEFAULT_TEXT_TYPE

    override fun toString(): String {
        return """{"type":"$type","value":"$value"}"""
    }
}

data class TreeChange(
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

enum class TreeChangeType {
    Content,
    Style,
    RemoveStyle,
}

internal data class TreeOperationResult(
    val changes: List<TreeChange>,
    val gcPairs: List<GCPair<*>> = emptyList(),
    val maxCreatedAtMap: Map<ActorID, TimeTicket>,
)
