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
import dev.yorkie.document.crdt.toTreeNode
import dev.yorkie.document.operation.TreeEditOperation
import dev.yorkie.document.operation.TreeStyleOperation
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE
import dev.yorkie.util.TreePos
import dev.yorkie.util.YorkieException
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
     * `createSplitNode` returns new node which is split from the given node.
     */
    private fun createSplitNode(node: CrdtTreeNode, offset: Int): ElementNode {
        val parentNode = node.parent
        val type = if (node.isText && parentNode != null) {
            parentNode.type
        } else {
            node.type
        }

        val attributes = when {
            node.attributes.isNotEmpty() -> {
                node.attributes
            }

            node.isText && parentNode?.attributes?.isNotEmpty() == true -> {
                parentNode.attributes
            }

            else -> {
                emptyMap()
            }
        }

        val children = if (node.isText) {
            if (parentNode == null || parentNode.getChildrenText().length == offset) {
                emptyList()
            } else {
                val childrenText = parentNode.getChildrenText()
                listOf(
                    TextNode(
                        value = childrenText.slice(IntRange(offset, childrenText.length - 1)),
                    ),
                )
            }
        } else {
            val children = node.children
            children.slice(IntRange(offset, children.size - 1)).map {
                it.toTreeNode().toJsonTreeNode()
            }
        }

        return ElementNode(
            type = type,
            children = children,
            attributes = attributes,
        )
    }

    /**
     * `separateSplit` separates the split operation into insert and delete operations.
     */
    private fun separateSplit(
        treePos: TreePos<CrdtTreeNode>,
        path: List<Int>,
    ): List<Triple<List<Int>, List<Int>, TreeNode?>> {
        val node = treePos.node
        val parentPath = path.dropLast(1)
        val parentNode = node.parent
        val last = if (node.isText && parentNode != null) {
            parentNode.getChildrenText().length
        } else {
            node.children.size
        }
        val toPath = parentPath + last
        val insertPath = parentPath.toMutableList()
        insertPath[insertPath.size - 1] = insertPath.last() + 1

        val res = mutableListOf<Triple<List<Int>, List<Int>, TreeNode?>>()
        if (path != toPath) {
            res.add(
                Triple(
                    first = path,
                    second = toPath,
                    third = null,
                ),
            )
        }

        val newNode = createSplitNode(node, path.last())
        res.add(
            Triple(
                first = insertPath,
                second = insertPath,
                third = newNode,
            ),
        )

        return res
    }

    /**
     * `separateMerge` separates the merge operation into insert and delete operations.
     */
    private fun separateMerge(
        treePos: TreePos<CrdtTreeNode>,
        path: List<Int>,
    ): List<Triple<List<Int>, List<Int>, Array<TreeNode>>> {
        val parentNode = treePos.node
        val offset = treePos.offset
        val node = parentNode.children[offset]
        val leftSiblingNode = parentNode.children[offset - 1]
        val children = node.children
        val parentPath = path.dropLast(1)
        val res = mutableListOf<Triple<List<Int>, List<Int>, Array<TreeNode>>>()

        // Add initial fromPath -> toPath mapping
        res.add(
            Triple(
                first = path.toList(),
                second = parentPath + (offset + 1),
                third = emptyArray(),
            ),
        )

        // If no children, return early
        if (children.isEmpty()) {
            return res
        }

        // Determine insertPath
        val insertPath = buildList {
            addAll(parentPath)
            add(offset - 1)
            val length = if (leftSiblingNode.hasTextChild) {
                leftSiblingNode.getChildrenText().length
            } else {
                leftSiblingNode.children.size
            }
            add(length)
        }

        // Convert children to TreeNode
        val nodes = children.map {
            it.toTreeNode().toJsonTreeNode()
        }.toTypedArray()

        res.add(
            Triple(
                first = insertPath,
                second = insertPath,
                third = nodes,
            ),
        )

        return res
    }

    /**
     * `splitByPath` splits the tree by the given [path].
     */
    fun splitByPath(path: List<Int>) {
        if (path.isEmpty()) {
            throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "path should not be empty",
            )
        }

        val treePos = target.pathToTreePos(path)
        val commands = separateSplit(treePos, path)

        for (command in commands) {
            val (fromPath, toPath, content) = command
            val fromPos = target.pathToPos(fromPath)
            val toPos = target.pathToPos(toPath)

            editInternal(
                fromPos = fromPos,
                toPos = toPos,
                contents = if (content != null) {
                    arrayOf(content)
                } else {
                    emptyArray()
                },
            )
        }
    }

    /**
     * `mergeByPath` merges the tree by the given [path].
     */
    fun mergeByPath(path: List<Int>) {
        if (path.isEmpty()) {
            throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "path should not be empty",
            )
        }

        val treePos = target.pathToTreePos(path)
        if (treePos.node.isText) {
            throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "text node cannot be merged",
            )
        }

        val commands = separateMerge(treePos, path)
        for (command in commands) {
            val (fromPath, toPath, contents) = command
            val fromPos = target.pathToPos(fromPath)
            val toPos = target.pathToPos(toPath)
            editInternal(
                fromPos = fromPos,
                toPos = toPos,
                contents = contents,
            )
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
        val (_, gcPairs, diff) = target.style(range, attributes, ticket)

        context.push(
            TreeStyleOperation(
                target.createdAt,
                range.first,
                range.second,
                ticket,
                attributes.toMap(),
            ),
        )

        this.context.acc(diff)

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
        val (_, gcPairs, diff) = target.removeStyle(
            fromPos to toPos,
            attributesToRemove,
            executedAt,
        )

        this.context.acc(diff)

        gcPairs.forEach(context::registerGCPair)

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
        }

        val ticket = context.lastTimeTicket
        val crdtNodes = if (contents.firstOrNull()?.type == DEFAULT_TEXT_TYPE) {
            val compVal = if (contents.size == 1) {
                (contents.single() as TextNode).value
            } else {
                contents.joinTo(
                    StringBuilder(contents.sumOf { (it as TextNode).value.length }),
                    "",
                ) {
                    (it as TextNode).value
                }.toString()
            }
            listOf(CrdtTreeText(CrdtTreeNodeID(context.issueTimeTicket(), 0), compVal))
        } else {
            contents.map { createCrdtTreeNode(context, it) }
        }
        val (_, gcPairs, diff) = target.edit(
            fromPos to toPos,
            crdtNodes.map(CrdtTreeNode::deepCopy).ifEmpty { null },
            splitLevel,
            ticket,
            context::issueTimeTicket,
        )

        this.context.acc(diff)

        gcPairs.forEach(context::registerGCPair)

        context.push(
            TreeEditOperation(
                target.createdAt,
                fromPos,
                toPos,
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
            validateTextNodes(treeNodes)
        } else {
            require(treeNodes.none { it.type == DEFAULT_TEXT_TYPE }) {
                "element node and text node cannot be passed together"
            }
            treeNodes.forEach {
                (it as ElementNode).children.forEach(::validateTreeNodes)
            }
        }
    }

    /**
     * Ensures that a text node has a non-empty string value.
     */
    private fun validateTextNodes(nodes: Array<out TreeNode>) {
        require(
            nodes.all {
                it is TextNode && it.value.isNotEmpty()
            },
        )
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
