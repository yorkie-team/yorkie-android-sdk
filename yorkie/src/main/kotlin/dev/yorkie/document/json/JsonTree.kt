package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.Rht
import dev.yorkie.document.crdt.TreeRange
import dev.yorkie.document.json.JsonTree.TreeNode

/**
 * [JsonTree] is a CRDT-based tree structure that is used to represent the document
 * tree of text-based editor such as ProseMirror.
 */
public class JsonTree internal constructor(
    internal val initialRoot: ElementNode? = null,
    internal val context: ChangeContext,
    override val target: CrdtTree,
) : JsonElement(), Collection<TreeNode> {
    public override val size: Int by target::size

    internal val indexTree by target::indexTree

    /**
     * Returns the root node of this tree.
     */
    internal fun buildRoot(context: ChangeContext): CrdtTreeNode {
        if (initialRoot == null) {
            return CrdtTreeNode(CrdtTreePos(context.issueTimeTicket(), 0), "root")
        }

        // TODO(hackerwins): Need to use the ticket of operation of creating tree.
        return CrdtTreeNode(
            CrdtTreePos(context.issueTimeTicket(), 0),
            initialRoot.type,
        ).also { root ->
            initialRoot.children.forEach { child ->
                buildDescendants(child, root, context)
            }
        }
    }

    /**
     * Sets the [attributes] to the elements of the given [path].
     */
    public fun styleByPath(path: List<Int>, attributes: Map<String, String>) {
        require(path.isNotEmpty()) {
            "path should not be empty"
        }

        val treeRange = target.pathToPosRange(path)
        val ticket = context.issueTimeTicket()
        target.style(treeRange, attributes, ticket)

        // TODO: create operations
        // context.push()
    }

    /**
     * Sets the [attributes] to the elements of the given range.
     */
    public fun style(fromIndex: Int, toIndex: Int, attributes: Map<String, String>) {
        require(fromIndex <= toIndex) {
            "from should be less than or equal to to"
        }

        val fromPos = target.findPos(fromIndex)
        val toPos = target.findPos(toIndex)
        val ticket = context.issueTimeTicket()

        target.style(fromPos to toPos, attributes, ticket)
        // TODO: create operations
    }

    /**
     * Edits this tree with the given node and path.
     */
    public fun editByPath(fromPath: List<Int>, toPath: List<Int>, content: TreeNode?) {
        require(fromPath.size == toPath.size) {
            "path length should be equal"
        }
        require(fromPath.isNotEmpty() && toPath.isNotEmpty()) {
            "path should not be empty"
        }

        val fromPos = target.pathToPos(fromPath)
        val toPos = target.pathToPos(toPath)
        editByPos(fromPos, toPos, content)
    }

    /**
     * Edits this tree with the given node.
     */
    public fun edit(fromIndex: Int, toIndex: Int, content: TreeNode?) {
        require(fromIndex <= toIndex) {
            "from should be less than or equal to to"
        }

        val fromPos = target.findPos(fromIndex)
        val toPos = target.findPos(toIndex)
        editByPos(fromPos, toPos, content)
    }

    private fun editByPos(fromPos: CrdtTreePos, toPos: CrdtTreePos, content: TreeNode?) {
        val crdtNode = content?.let { createCrdtTreeNode(context, content) }
        val ticket = context.lastTimeTicket
        target.edit(fromPos to toPos, crdtNode?.deepcopy(), ticket)

        // TODO create operation

        if (fromPos.createdAt != toPos.createdAt || fromPos.offset != toPos.offset) {
            context.registerElementHasRemovedNodes(target)
        }
    }

    /**
     * Splits this tree at the given index.
     */
    public fun split(index: Int, depth: Int) {
        target.split(index, depth)
    }

    /**
     * Returns the XML string of this tree.
     */
    public fun toXml(): String = target.toXml()

    /**
     * Returns the path of the given index.
     */
    public fun indexToPath(index: Int): List<Int> = target.indexToPath(index)

    /**
     * Returns pair of CRDTTreePos of the given integer offsets.
     */
    public fun createRange(
        fromIndex: Int,
        toIndex: Int,
    ): TreeRange = target.createRange(fromIndex, toIndex)

    /**
     * Returns a pair of [CrdtTreeNode] of the given integer offsets.
     */
    public fun createRangeByPath(fromPath: List<Int>, toPath: List<Int>): TreeRange {
        return createRange(target.pathToIndex(fromPath), target.pathToIndex(toPath))
    }

    /**
     * Returns the integer offsets of the given [range].
     */
    public fun rangeToIndex(range: TreeRange): Pair<Int, Int> = target.rangeToIndex(range)

    /**
     *  Returns the path of the given [range].
     */
    public fun rangeToPath(range: TreeRange): Pair<List<Int>, List<Int>> = target.rangeToPath(range)

    override fun isEmpty(): Boolean = target.isEmpty()

    override fun contains(element: TreeNode): Boolean {
        return find { it == element } != null
    }

    override fun containsAll(elements: Collection<TreeNode>): Boolean {
        return toList().containsAll(elements)
    }

    override fun iterator(): Iterator<TreeNode> {
        return object : Iterator<TreeNode> {
            val targetIterator = target.iterator()

            override fun hasNext(): Boolean {
                return targetIterator.hasNext()
            }

            override fun next(): TreeNode {
                return targetIterator.next().toTreeNode()
            }

            private fun CrdtTreeNode.toTreeNode(): TreeNode {
                return if (isText) {
                    TextNode(value)
                } else {
                    ElementNode(
                        type,
                        attributes,
                        children.map {
                            it.toTreeNode()
                        },
                    )
                }
            }
        }
    }

    companion object {

        private fun buildDescendants(
            treeNode: TreeNode,
            parent: CrdtTreeNode,
            context: ChangeContext,
        ) {
            val type = treeNode.type
            val ticket = context.issueTimeTicket()
            val pos = CrdtTreePos(ticket, 0)

            when (treeNode) {
                is TextNode -> {
                    val textNode = CrdtTreeNode(pos, type, treeNode.value)
                    parent.append(textNode)
                    return
                }

                is ElementNode -> {
                    val attributes = treeNode.attributes
                    val attrs = Rht()

                    if (attributes.isNotEmpty()) {
                        attributes.forEach { (key, value) ->
                            attrs.set(key, value, ticket)
                        }
                    }
                    val elementNode = CrdtTreeNode(pos, type, _attributes = attrs)
                    parent.append(elementNode)
                    treeNode.children.forEach { child ->
                        buildDescendants(child, elementNode, context)
                    }
                }
            }
        }

        /**
         * [createCrdtTreeNode] returns [CrdtTreeNode] by given [TreeNode].
         */
        private fun createCrdtTreeNode(context: ChangeContext, content: TreeNode): CrdtTreeNode {
            val ticket = context.issueTimeTicket()
            val pos = CrdtTreePos(ticket, 0)

            return when (content) {
                is TextNode -> {
                    CrdtTreeNode(pos, content.type, content.value)
                }

                is ElementNode -> {
                    CrdtTreeNode(
                        pos,
                        content.type,
                        _attributes = Rht().apply {
                            content.attributes.forEach { (key, value) ->
                                set(key, value, ticket)
                            }
                        },
                    ).also { node ->
                        content.children.forEach {
                            buildDescendants(it, node, context)
                        }
                    }
                }
            }
        }
    }

    sealed interface TreeNode {
        val type: String
    }

    data class ElementNode(
        override val type: String,
        val attributes: Map<String, String>,
        val children: List<TreeNode>,
    ) : TreeNode

    data class TextNode(val value: String) : TreeNode {
        override val type: String = "text"
    }
}
