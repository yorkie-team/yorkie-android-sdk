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
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_ROOT_TYPE
import dev.yorkie.util.IndexTreeNode.Companion.DEFAULT_TEXT_TYPE
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
     * Splits the tree at the given [path].
     *
     * Lowers to a single [editInternal] call with `splitLevel = 1` at the
     * resolved position — no node is copied or deleted; the split is
     * produced by [CrdtTree.edit]'s own split step. Mirrors JS
     * `document/json/tree.ts` `splitByPath` (v0.7.23).
     *
     * @throws YorkieException with ErrInvalidArgument if [path] is empty or
     *   resolves to the root node (a text node's element parent with no
     *   parent of its own, or an element node with no parent).
     */
    public fun splitByPath(path: List<Int>) {
        if (path.isEmpty()) {
            throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "path should not be empty",
            )
        }

        val treePos = target.pathToTreePos(path)
        val targetNode = if (treePos.node.isText) treePos.node.parent else treePos.node
        if (targetNode?.parent == null) {
            throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "the root node cannot be split",
            )
        }

        val pos = target.pathToPos(path)
        editInternal(fromPos = pos, toPos = pos, splitLevel = 1)
    }

    /**
     * Merges the element node at the given [path] into its left sibling.
     *
     * Lowers to a single empty [editInternal] call spanning the boundary
     * between the left sibling's end and [path]'s start — no node is
     * copied; [CrdtTree.edit]'s own merge step (03/03-1) moves the
     * children. Mirrors JS `document/json/tree.ts` `mergeByPath` (v0.7.23).
     *
     * @throws YorkieException if [path] is empty, resolves to a text node,
     *   or resolves to the first child (no left sibling).
     */
    public fun mergeByPath(path: List<Int>) {
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

        val parentNode = treePos.node
        val offset = treePos.offset
        val leftSibling = parentNode.children.getOrNull(offset - 1)
            ?: throw YorkieException(
                code = YorkieException.Code.ErrInvalidArgument,
                errorMessage = "the first child cannot be merged",
            )

        val parentPath = path.dropLast(1)
        val lastOffset = if (leftSibling.hasTextChild) {
            leftSibling.getChildrenText().length
        } else {
            leftSibling.children.size
        }
        val fromPath = parentPath + (offset - 1) + lastOffset
        val toPath = path + 0

        editInternal(fromPos = target.pathToPos(fromPath), toPos = target.pathToPos(toPath))
    }

    /**
     * Sets the [attributes] to the element at the given [path].
     */
    public fun styleByPath(path: List<Int>, attributes: Map<String, String>) {
        require(path.isNotEmpty()) {
            "path should not be empty"
        }

        val treeRange = target.pathToPosRange(path)
        styleByRange(treeRange, attributes)
    }

    /**
     * Sets the [attributes] to the elements in the path range
     * between [fromPath] and [toPath].
     */
    public fun styleByPath(
        fromPath: List<Int>,
        toPath: List<Int>,
        attributes: Map<String, String>,
    ) {
        require(fromPath.size == toPath.size) {
            "path length should be equal"
        }
        require(fromPath.isNotEmpty() && toPath.isNotEmpty()) {
            "path should not be empty"
        }

        val fromPos = target.pathToPos(fromPath)
        val toPos = target.pathToPos(toPath)
        styleByRange(fromPos to toPos, attributes)
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
        val (_, gcPairs, docSize) = target.style(range, attributes, ticket)

        context.push(
            TreeStyleOperation(
                target.createdAt,
                range.first,
                range.second,
                ticket,
                attributes.toMap(),
            ),
        )

        this.context.acc(docSize.live)
        this.context.accGC(docSize.gc)

        gcPairs.forEach(context::registerGCPair)
    }

    /**
     * Removes the attributes in [attributesToRemove] from the elements
     * in the given index range.
     */
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
        removeStyleByRange(fromPos to toPos, attributesToRemove)
    }

    /**
     * Removes the attributes in [attributesToRemove] from the elements
     * in the path range between [fromPath] and [toPath].
     */
    public fun removeStyleByPath(
        fromPath: List<Int>,
        toPath: List<Int>,
        attributesToRemove: List<String>,
    ) {
        require(fromPath.size == toPath.size) {
            "path length should be equal"
        }
        require(fromPath.isNotEmpty() && toPath.isNotEmpty()) {
            "path should not be empty"
        }

        val fromPos = target.pathToPos(fromPath)
        val toPos = target.pathToPos(toPath)
        removeStyleByRange(fromPos to toPos, attributesToRemove)
    }

    private fun removeStyleByRange(range: TreePosRange, attributesToRemove: List<String>) {
        val executedAt = context.issueTimeTicket()
        val (_, gcPairs, docSize) = target.removeStyle(
            range,
            attributesToRemove,
            executedAt,
        )

        this.context.acc(docSize.live)
        this.context.accGC(docSize.gc)

        gcPairs.forEach(context::registerGCPair)

        context.push(
            TreeStyleOperation(
                target.createdAt,
                range.first,
                range.second,
                executedAt,
                attributesToRemove = attributesToRemove,
            ),
        )
    }

    /**
     * Edits this tree with the given node and path.
     *
     * @throws YorkieException with ErrInvalidArgument if an edit boundary
     * resolves to an out-of-range text split offset (a document whose
     * history carries a duplicate tree node ID).
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
     *
     * @throws YorkieException with ErrInvalidArgument if an edit boundary
     * resolves to an out-of-range text split offset (a document whose
     * history carries a duplicate tree node ID).
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
     *
     * @throws YorkieException with ErrInvalidArgument if an edit boundary
     * resolves to an out-of-range text split offset (a document whose
     * history carries a duplicate tree node ID).
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
        // Records every ticket this edit's split step issues, in issue
        // order, so the pushed op carries them on the wire (port 4ec66cc0)
        // instead of leaving the applying replica to reconstruct them from
        // executedAt + contents.size, which under-counts once a content
        // has descendants.
        val splitTickets = mutableListOf<TimeTicket>()
        val (_, gcPairs, docSize) = target.edit(
            fromPos to toPos,
            crdtNodes.map(CrdtTreeNode::deepCopy).ifEmpty { null },
            splitLevel,
            ticket,
            { context.issueTimeTicket().also(splitTickets::add) },
        )

        this.context.acc(docSize.live)

        gcPairs.forEach(context::registerGCPair)

        context.push(
            TreeEditOperation(
                target.createdAt,
                fromPos,
                toPos,
                crdtNodes.ifEmpty { null },
                splitLevel,
                ticket,
                splitTickets = splitTickets,
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
        val indexRange = target.posRangeToIndexRange(posRange)
        drainPendingGcPairs()
        return indexRange
    }

    /**
     *  Converts the position [range] into the path range.
     */
    public fun posRangeToPathRange(range: TreePosStructRange): Pair<List<Int>, List<Int>> {
        val posRange = range.first.toOriginal() to range.second.toOriginal()
        val pathRange = target.posRangeToPathRange(posRange)
        drainPendingGcPairs()
        return pathRange
    }

    /**
     * Registers with [ChangeContext.root] any GC pairs [target] buffered
     * while resolving a position. [posRangeToIndexRange] and
     * [posRangeToPathRange] split text nodes to locate a position; when the
     * position lands inside a tombstoned node the split produces a
     * born-removed piece. This context's own root is always the one such a
     * pair should register onto: in the mutating call path it is the live
     * document root; in the [dev.yorkie.document.Document.getRoot] read
     * path it is that call's own clone snapshot, which
     * [dev.yorkie.document.Document.garbageCollect] sweeps alongside the
     * live root.
     */
    private fun drainPendingGcPairs() {
        target.drainPendingGcPairs().forEach(context::registerGCPair)
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
