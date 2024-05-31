package dev.yorkie.document.json

import dev.yorkie.document.change.ChangeContext
import dev.yorkie.document.crdt.CrdtTree
import dev.yorkie.document.crdt.CrdtTreeNode
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeElement
import dev.yorkie.document.crdt.CrdtTreeNode.Companion.CrdtTreeText
import dev.yorkie.document.crdt.CrdtTreeNodeID
import dev.yorkie.document.crdt.CrdtTreePos
import dev.yorkie.document.crdt.Rht
import dev.yorkie.document.crdt.TreeElementNode
import dev.yorkie.document.crdt.TreePosRange
import dev.yorkie.document.crdt.TreeTextNode
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE
import dev.yorkie.document.CrdtTreePosStruct as TreePosStruct

public typealias TreePosStructRange = Pair<TreePosStruct, TreePosStruct>

/**
 * [JsonTree] is a CRDT-based tree structure that is used to represent the document
 * tree of text-based editor such as ProseMirror.
 */
public class JsonTree internal constructor(
    internal val context: ChangeContext,
    override val target: CrdtTree,
) : JsonElement() {
    public val size: Int by target::size

    internal val indexTree by target::indexTree

    public val rootTreeNode: TreeNode
        get() = target.rootTreeNode.toJsonTreeNode()

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
    public fun style(
        fromIndex: Int,
        toIndex: Int,
        attributes: Map<String, String>,
    ) {
        require(fromIndex <= toIndex) {
            "from should be less than or equal to to"
        }

        val fromPos = target.findPos(fromIndex)
        val toPos = target.findPos(toIndex)
        styleByRange(fromPos to toPos, attributes)
    }

    private fun styleByRange(range: TreePosRange, attributes: Map<String, String>) {
        val ticket = context.issueTimeTicket()
        val (_, gcPairs, maxCreatedAtMapByActor) = target.style(range, attributes, ticket)

        context.push(
            TreeStyleOperation(
                target.createdAt,
                range.first,
                range.second,
                ticket,
                maxCreatedAtMapByActor,
                attributes.toMap(),
            ),
        )
        gcPairs.forEach(context::registerGCPair)
    }

    public fun removeStyle(
        fromIndex: Int,
        toIndex: Int,
        attributesToRemove: List<String>,
    ) {
        require(fromIndex <= toIndex) {
            "from should be less than or equal to to"
        }

        val fromPos = target.findPos(fromIndex)
        val toPos = target.findPos(toIndex)
        val executedAt = context.issueTimeTicket()
        target.removeStyle(fromPos to toPos, attributesToRemove, executedAt)

        context.push(
            TreeStyleOperation(
                target.createdAt,
                fromPos,
                toPos,
                executedAt,
                attributesToRemove = attributesToRemove,
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
        splitLevel: Int = 0,
    ) {
        require(fromPath.size == toPath.size) {
            "path length should be equal"
        }
        require(fromPath.isNotEmpty() && toPath.isNotEmpty()) {
            "path should not be empty"
        }

        val fromPos = target.pathToPos(fromPath)
        val toPos = target.pathToPos(toPath)
        editInternal(fromPos, toPos, splitLevel, *contents)
    }

    /**
     * Edits this tree with the given node.
     */
    public fun edit(
        fromIndex: Int,
        toIndex: Int,
        vararg content: TreeNode,
    ) {
        edit(fromIndex, toIndex, 0, *content)
    }

    /**
     * Edits this tree with the given node.
     */
    public fun edit(
        fromIndex: Int,
        toIndex: Int,
        splitLevel: Int,
        vararg contents: TreeNode,
    ) {
        require(fromIndex <= toIndex) {
            "from should be less than or equal to to"
        }

        val fromPos = target.findPos(fromIndex)
        val toPos = target.findPos(toIndex)
        editInternal(fromPos, toPos, splitLevel, *contents)
    }

    private fun editInternal(
        fromPos: CrdtTreePos,
        toPos: CrdtTreePos,
        splitLevel: Int = 0,
        vararg contents: TreeNode,
    ) {
        if (contents.isNotEmpty()) {
            validateTreeNodes(*contents)
            if (contents.first().type != DEFAULT_TEXT_TYPE) {
                contents.forEach { validateTreeNodes(it) }
            }
        }

        val ticket = context.lastTimeTicket
        val crdtNodes = if (contents.firstOrNull()?.type == DEFAULT_TEXT_TYPE) {
            val textNodes = contents.filterIsInstance<TextNode>()
            val compVal = if (textNodes.size == 1) {
                textNodes.single().value
            } else {
                textNodes.joinTo(StringBuilder(textNodes.sumOf { it.value.length }), "") {
                    it.value
                }.toString()
            }
            listOf(CrdtTreeText(CrdtTreeNodeID(context.issueTimeTicket(), 0), compVal))
        } else {
            contents.map { createCrdtTreeNode(context, it) }
        }
        val (_, gcPairs, maxCreatedAtMapByActor) = target.edit(
            fromPos to toPos,
            crdtNodes.map(CrdtTreeNode::deepCopy).ifEmpty { null },
            splitLevel,
            ticket,
            context::issueTimeTicket,
        )
        gcPairs.forEach(context::registerGCPair)

        context.push(
            TreeEditOperation(
                target.createdAt,
                fromPos,
                toPos,
                maxCreatedAtMapByActor,
                crdtNodes.ifEmpty { null },
                splitLevel,
                ticket,
            ),
        )
    }

    /**
     * Ensures that treeNodes consists of only one type.
     */
    private fun validateTreeNodes(vararg treeNodes: TreeNode) {
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
     * Returns the XML string of this tree.
     */
    public fun toXml(): String = target.toXml()

    /**
     * Returns the path of the given index.
     */
    public fun indexToPath(index: Int): List<Int> = target.indexToPath(index)

    /**
     * Converts the path [range] into the [TreePosStructRange].
     */
    public fun pathRangeToPosRange(range: Pair<List<Int>, List<Int>>): TreePosStructRange {
        val indexRange = target.pathToIndex(range.first) to target.pathToIndex(range.second)
        val posRange = target.indexRangeToPosRange(indexRange)
        return posRange.first.toStruct() to posRange.second.toStruct()
    }

    /**
     * Converts the index [range] into the [TreePosStructRange].
     */
    public fun indexRangeToPosRange(range: Pair<Int, Int>): TreePosStructRange {
        return target.indexRangeToPosStructRange(range)
    }

    /**
     * Converts the position [range] into the index range.
     */
    public fun posRangeToIndexRange(range: TreePosStructRange): Pair<Int, Int> {
        val posRange = range.first.toOriginal() to range.second.toOriginal()
        return target.posRangeToIndexRange(posRange)
    }

    /**
     *  Converts the position [range] into the path range.
     */
    public fun posRangeToPathRange(range: TreePosStructRange): Pair<List<Int>, List<Int>> {
        val posRange = range.first.toOriginal() to range.second.toOriginal()
        return target.posRangeToPathRange(posRange)
    }

    companion object {

        /**
         * Returns the root node of this tree.
         */
        internal fun buildRoot(initialRoot: ElementNode?, context: ChangeContext): CrdtTreeNode {
            val id = CrdtTreeNodeID(context.issueTimeTicket(), 0)
            if (initialRoot == null) {
                return CrdtTreeElement(id, DEFAULT_ROOT_TYPE)
            }
            // TODO(hackerwins): Need to use the ticket of operation of creating tree.
            return CrdtTreeElement(id, initialRoot.type).also { root ->
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
            val id = CrdtTreeNodeID(ticket, 0)

            when (treeNode) {
                is TextNode -> {
                    val textNode = CrdtTreeText(id, treeNode.value)
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
                    val elementNode = CrdtTreeElement(id, type, attributes = attrs)
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
            val id = CrdtTreeNodeID(ticket, 0)

            return when (content) {
                is TextNode -> {
                    CrdtTreeText(id, content.value)
                }

                is ElementNode -> {
                    CrdtTreeElement(
                        id,
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

    public interface ElementNode : TreeNode {
        public val children: List<TreeNode>
        public val attributes: Map<String, String>

        companion object {
            operator fun invoke(
                type: String,
                children: List<TreeNode> = emptyList(),
                attributes: Map<String, String> = emptyMap(),
            ): ElementNode {
                @Suppress("UNCHECKED_CAST")
                return TreeElementNode(
                    type,
                    children as List<dev.yorkie.document.crdt.TreeNode>,
                    attributes,
                )
            }
        }
    }

    public interface TextNode : TreeNode {
        public val value: String

        companion object {
            operator fun invoke(value: String): TextNode {
                return TreeTextNode(value)
            }
        }
    }
}
