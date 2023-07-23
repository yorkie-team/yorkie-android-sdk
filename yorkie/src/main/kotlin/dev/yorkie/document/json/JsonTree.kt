package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.Rht
import dev.yorkie.document.crdt.TreeRange
import dev.yorkie.document.json.JsonTree.TreeNode
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE

/**
 * [JsonTree] is a CRDT-based tree structure that is used to represent the document
 * tree of text-based editor such as ProseMirror.
 */
public class JsonTree internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtTree,
) : JsonElement(), Collection<TreeNode> {
    public override val size: Int by target::size

    internal val indexTree by target::indexTree

    /**
     * Sets the [attributes] to the elements of the given [path].
     */
    public fun styleByPath(path: List<Int>, attributes: Map<String, String>) {
        require(path.isNotEmpty()) {
            "path should not be empty"
        }

        val treeRange = target.pathToPosRange(path)
        styleByRange(treeRange, attributes)
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
        styleByRange(fromPos to toPos, attributes)
    }

    private fun styleByRange(
        range: TreeRange,
        attributes: Map<String, String>,
    ) {
        val ticket = context.issueTimeTicket()
        target.style(range, attributes, ticket)

        context.push(
            TreeStyleOperation(
                target.createdAt,
                range.first,
                range.second,
                attributes.toMap(),
                ticket,
            ),
        )
    }

    /**
     * Edits this tree with the given node and path.
     */
    public fun editByPath(
        fromPath: List<Int>,
        toPath: List<Int>,
        vararg contents: TreeNode,
    ) {
        require(fromPath.size == toPath.size) {
            "path length should be equal"
        }
        require(fromPath.isNotEmpty() && toPath.isNotEmpty()) {
            "path should not be empty"
        }

        val fromPos = target.pathToPos(fromPath)
        val toPos = target.pathToPos(toPath)
        editByPos(fromPos, toPos, contents.toList())
    }

    /**
     * Edits this tree with the given node.
     */
    public fun edit(fromIndex: Int, toIndex: Int, vararg contents: TreeNode) {
        require(fromIndex <= toIndex) {
            "from should be less than or equal to to"
        }

        val fromPos = target.findPos(fromIndex)
        val toPos = target.findPos(toIndex)
        editByPos(fromPos, toPos, contents.toList())
    }

    private fun editByPos(fromPos: CrdtTreePos, toPos: CrdtTreePos, contents: List<TreeNode>) {
        if (contents.isNotEmpty()) {
            validateTreeNodes(contents)
            if (contents.first().type != DEFAULT_TEXT_TYPE) {
                val children =
                    contents.filterIsInstance<ElementNode>().flatMap(ElementNode::children)
                validateTreeNodes(children)
            }
        }

        val crdtNodes = if (contents.firstOrNull()?.type == DEFAULT_TEXT_TYPE) {
            val compVal = contents
                .filterIsInstance<TextNode>()
                .joinToString("") { it.value }
            listOf(CrdtTreeText(CrdtTreePos(context.issueTimeTicket(), 0), compVal))
        } else {
            contents.map { createCrdtTreeNode(context, it) }
        }
        val ticket = context.lastTimeTicket
        target.edit(
            fromPos to toPos,
            crdtNodes.map { it.deepCopy() }.ifEmpty { null },
            ticket,
        )

        context.push(
            TreeEditOperation(
                target.createdAt,
                fromPos,
                toPos,
                crdtNodes.ifEmpty { null },
                ticket,
            ),
        )

        if (fromPos.createdAt != toPos.createdAt || fromPos.offset != toPos.offset) {
            context.registerElementHasRemovedNodes(target)
        }
    }

    /**
     * Ensures that treeNodes consists of only one type.
     */
    private fun validateTreeNodes(treeNodes: List<TreeNode>) {
        if (treeNodes.isEmpty()) return

        val firstTreeNodeType = treeNodes.first().type
        if (firstTreeNodeType == DEFAULT_TEXT_TYPE) {
            require(treeNodes.all { it.type == DEFAULT_TEXT_TYPE }) {
                "element node and text node cannot be passed together"
            }
            treeNodes.filterIsInstance<TextNode>().forEach(::validateTextNode)
        } else {
            require(treeNodes.none { it.type == DEFAULT_TEXT_TYPE }) {
                "element node and text node cannot be passed together"
            }
        }
    }

    /**
     * Ensures that a text node has a non-empty string value.
     */
    private fun validateTextNode(textNode: TextNode) {
        require(textNode.value.isNotEmpty()) {
            "text node cannot have empty value"
        }
    }

    /**
     * Returns the index of given path.
     */
    public fun pathToIndex(path: List<Int>): Int {
        return target.pathToIndex(path)
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

        /**
         * Returns the root node of this tree.
         */
        internal fun buildRoot(initialRoot: ElementNode?, context: ChangeContext): CrdtTreeNode {
            val pos = CrdtTreePos(context.issueTimeTicket(), 0)
            if (initialRoot == null) {
                return CrdtTreeElement(pos, DEFAULT_ROOT_TYPE)
            }
            // TODO(hackerwins): Need to use the ticket of operation of creating tree.
            return CrdtTreeElement(pos, initialRoot.type).also { root ->
                initialRoot.children.forEach { child ->
                    buildDescendants(child, root, context)
                }
            }
        }

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
                    val textNode = CrdtTreeText(pos, treeNode.value)
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
                    val elementNode = CrdtTreeElement(pos, type, attributes = attrs)
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
                    CrdtTreeText(pos, content.value)
                }

                is ElementNode -> {
                    CrdtTreeElement(
                        pos,
                        content.type,
                        attributes = Rht().apply {
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

    public sealed interface TreeNode {
        public val type: String
    }

    public data class ElementNode(
        public override val type: String,
        public val attributes: Map<String, String> = emptyMap(),
        public val children: List<TreeNode> = emptyList(),
    ) : TreeNode

    public data class TextNode(val value: String) : TreeNode {
        public override val type: String = DEFAULT_TEXT_TYPE
    }
}
