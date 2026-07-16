package dev.yorkie.document.crdt

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import dev.yorkie.document.CrdtTreeNodeIDStruct
import dev.yorkie.document.CrdtTreePosStruct
import dev.yorkie.document.JsonSerializable
import dev.yorkie.document.json.TreePosStructRange
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.InitialTimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.MAX_LAMPORT
import dev.yorkie.document.time.TimeTicket.Companion.TIME_TICKET_SIZE
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DataSize
import dev.yorkie.util.IndexTree
import dev.yorkie.util.IndexTreeNode
import dev.yorkie.util.IndexTreeNodeList
import dev.yorkie.util.TokenType
import dev.yorkie.util.TreePos
import dev.yorkie.util.TreeToken
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.traverseAll
import java.util.TreeMap

public typealias TreePosRange = Pair<CrdtTreePos, CrdtTreePos>

internal typealias CrdtTreeToken = TreeToken<CrdtTreeNode>

internal typealias TreeNodePair = Pair<CrdtTreeNode, CrdtTreeNode>

@SuppressLint("VisibleForTests")
internal data class CrdtTree(
    val root: CrdtTreeNode,
    override var createdAt: TimeTicket,
    override var movedAt: TimeTicket? = null,
    override var removedAt: TimeTicket? = null,
) : CrdtElement(), GCParent<CrdtTreeNode>, GCCrdtElement {

    override val gcPairs: List<GCPair<*>>
        get() = buildList {
            indexTree.traverse { node, _ ->
                if (node.removedAt != null) {
                    add(GCPair(this@CrdtTree, node))
                }
                addAll(node.gcPairs)
            }
        }

    internal val indexTree = IndexTree(root)

    private val nodeMapByID = TreeMap<CrdtTreeNodeID, CrdtTreeNode>()

    val rootTreeNode: TreeNode
        get() = indexTree.root.toTreeNode()

    init {
        indexTree.traverseAll { node, _ ->
            nodeMapByID[node.id] = node
        }
        rebuildMergeState()
    }

    private fun rebuildMergeState() {
        indexTree.traverseAll { node, _ ->
            val mergedFromID = node.mergedFrom ?: return@traverseAll
            val source = findFloorNode(mergedFromID) ?: return@traverseAll
            val target = node.parent ?: return@traverseAll
            if (source.mergedInto == null) {
                source.mergedInto = target.id
            }
        }
    }

    val size: Int
        get() = indexTree.size

    @VisibleForTesting
    val nodeSize: Int
        get() = nodeMapByID.size

    /**
     * Applies the given [attributes] of the given [range].
     */
    fun style(
        range: TreePosRange,
        attributes: Map<String, String>?,
        executedAt: TimeTicket,
        versionVector: VersionVector? = null,
    ): TreeOperationResult {
        var diff = DataSize(
            data = 0,
            meta = 0,
        )

        val (from, diffFrom) = findNodesAndSplitText(range.first, executedAt)
        val (fromParent, fromLeftRaw) = from
        val (to, diffTo) = findNodesAndSplitText(range.second, executedAt)
        val (toParent, toLeftRaw) = to

        diff = addDataSizes(diff, diffTo, diffFrom)

        // Advance past split siblings the editor did not know about so range
        // boundaries land after every unseen split product. Skip when leftRaw
        // equals the parent (leftmost-child sentinel).
        val fromLeft = if (fromLeftRaw === fromParent) {
            fromLeftRaw
        } else {
            advancePastUnknownSplitSiblings(fromLeftRaw, versionVector)
        }
        val toLeft = if (toLeftRaw === toParent) {
            toLeftRaw
        } else {
            advancePastUnknownSplitSiblings(toLeftRaw, versionVector)
        }

        val changes = mutableListOf<TreeChange>()

        val gcPairs = mutableListOf<GCPair<RhtNode>>()
        val prevAttributes = mutableMapOf<String, String>()
        val newAttrKeys = mutableListOf<String>()
        var capturedPrev = false
        traverseInPosRange(
            fromParent = fromParent,
            fromLeft = fromLeft,
            toParent = toParent,
            toLeft = toLeft,
        ) { (node, tokenType), _ ->
            val actorID = node.createdAt.actorID
            val clientLamportAtChange = getClientInfoForChange(actorID, versionVector)

            if (node.canStyle(executedAt, clientLamportAtChange) && attributes != null) {
                if (tokenType == TokenType.End &&
                    versionVector != null &&
                    hasUnknownSplitSibling(node, versionVector)
                ) {
                    return@traverseInPosRange
                }

                if (!capturedPrev) {
                    val attrs = node.getAttrs()
                    for ((key, _) in attributes) {
                        if (attrs.has(key)) {
                            prevAttributes[key] = attrs[key]!!
                        } else {
                            newAttrKeys.add(key)
                        }
                    }
                    capturedPrev = true
                }

                val parentOfNode = requireNotNull(node.parent)
                val previousNode = node.prevSibling ?: parentOfNode

                val updatedAttrPairs = node.setAttributes(attributes, executedAt)
                val affectedAttrs = updatedAttrPairs.fold(emptyMap<String, String>()) { acc, pair ->
                    val curr = pair.new
                    acc + curr?.let { mapOf(curr.key to attributes[curr.key].orEmpty()) }.orEmpty()
                }
                if (affectedAttrs.isNotEmpty()) {
                    TreeChange(
                        type = TreeChangeType.Style,
                        from = toIndex(parentOfNode, previousNode),
                        to = toIndex(node, node),
                        fromPath = toPath(parentOfNode, previousNode),
                        toPath = toPath(node, node),
                        actorID = executedAt.actorID,
                        attributes = affectedAttrs,
                    ).let(changes::add)
                }

                updatedAttrPairs.forEach { (prev, _) ->
                    prev?.let {
                        gcPairs.add(GCPair(node, prev))
                    }
                }

                for ((key, _) in attributes) {
                    val curr = node.getAttrs().getNodeMapByKey()[key]
                    if (curr != null && tokenType != TokenType.End) {
                        diff = addDataSizes(diff, curr.dataSize)
                    }
                }

                // Propagate style to unknown split siblings so that a style
                // operation whose range was determined before the split also
                // covers the right part of the split. Mirrors JS SDK PR #1224.
                if (tokenType == TokenType.Start && versionVector != null) {
                    var current = node
                    while (true) {
                        val nextID = current.insNextID ?: break
                        val next = findFloorNode(nextID) ?: break
                        if (next.isText) break
                        if (isSplitSiblingKnown(next, versionVector)) break

                        val siblingPairs = next.setAttributes(attributes, executedAt)
                        val siblingAffectedAttrs =
                            siblingPairs.fold(emptyMap<String, String>()) { acc, pair ->
                                val curr = pair.new
                                acc + curr?.let { mapOf(curr.key to curr.value) }.orEmpty()
                            }
                        if (siblingAffectedAttrs.isNotEmpty()) {
                            val parentOfNext = requireNotNull(next.parent)
                            val previousNext = next.prevSibling ?: parentOfNext
                            TreeChange(
                                type = TreeChangeType.Style,
                                from = toIndex(parentOfNext, previousNext),
                                to = toIndex(next, next),
                                fromPath = toPath(parentOfNext, previousNext),
                                toPath = toPath(next, next),
                                actorID = executedAt.actorID,
                                attributes = siblingAffectedAttrs,
                            ).let(changes::add)
                        }
                        siblingPairs.forEach { (prev, _) ->
                            prev?.let { gcPairs.add(GCPair(next, prev)) }
                        }
                        for ((key, _) in attributes) {
                            // The RHT always retains an entry for a key that was just
                            // set (the new node or the LWW-winning previous one).
                            val curr = next.getAttrs().getNodeMapByKey().getValue(key)
                            diff = addDataSizes(diff, curr.dataSize)
                        }
                        current = next
                    }
                }
            }
        }
        return TreeOperationResult(
            changes,
            gcPairs,
            diff,
            prevAttributes = prevAttributes,
            attributesToRemove = newAttrKeys,
        )
    }

    private fun toPath(parentNode: CrdtTreeNode, leftSiblingNode: CrdtTreeNode): List<Int> {
        return indexTree.treePosToPath(toCrdtTreePos(parentNode, leftSiblingNode))
    }

    private fun toCrdtTreePos(
        parentNode: CrdtTreeNode,
        leftSiblingNode: CrdtTreeNode,
        includeRemoved: Boolean = false,
    ): TreePos<CrdtTreeNode> {
        return when {
            !includeRemoved && parentNode.isRemoved -> {
                var child = parentNode
                var parent = parentNode
                while (parent.isRemoved) {
                    child = parent
                    parent = child.parent ?: break
                }

                val childOffset = parent.findOffset(child, includeRemoved)
                TreePos(parent, childOffset)
            }

            parentNode == leftSiblingNode -> TreePos(parentNode, 0)

            else -> {
                var offset = parentNode.findOffset(leftSiblingNode, includeRemoved)
                if (includeRemoved || !leftSiblingNode.isRemoved) {
                    if (leftSiblingNode.isText) {
                        return TreePos(leftSiblingNode, leftSiblingNode.paddedSize)
                    } else {
                        offset++
                    }
                }
                TreePos(parentNode, offset)
            }
        }
    }

    /**
     * Edits the tree with the given [range] and [contents].
     * If the [contents] is null, the [range] will be removed.
     */
    fun edit(
        range: TreePosRange,
        contents: List<CrdtTreeNode>?,
        splitLevel: Int,
        executedAt: TimeTicket,
        issueTimeTicket: (() -> TimeTicket)? = null,
        versionVector: VersionVector? = null,
    ): TreeOperationResult {
        var diff = DataSize(
            data = 0,
            meta = 0,
        )

        // 01. find nodes from the given range and split nodes.
        val (from, diffFrom) = findNodesAndSplitText(range.first, executedAt)
        val (fromParent, fromLeftRaw) = from
        val (to, diffTo) = findNodesAndSplitText(range.second, executedAt)
        val (toParent, toLeftRaw) = to

        diff = addDataSizes(diff, diffTo, diffFrom)

        // 01-1. Advance past split siblings the editor did not know about so
        // range boundaries land after every unseen split product. Skip when
        // leftRaw equals the parent (leftmost-child sentinel).
        val fromLeft = if (fromLeftRaw === fromParent) {
            fromLeftRaw
        } else {
            advancePastUnknownSplitSiblings(fromLeftRaw, versionVector)
        }
        val toLeft = if (toLeftRaw === toParent) {
            toLeftRaw
        } else {
            advancePastUnknownSplitSiblings(toLeftRaw, versionVector)
        }

        val fromIndex = toIndex(fromParent, fromLeft)
        val fromPath = toPath(fromParent, fromLeft)

        // §3 Range Narrowing — when fromLeft and toLeft are in different
        // parents (due to a concurrent element split), follow fromLeft's
        // insNextID chain to find a split sibling in toParent and narrow the
        // traversal range. The original fromParent/fromLeft are preserved for
        // merge, split, and insert steps. VV-independent for clone/root
        // consistency.
        var collectFromParent = fromParent
        var collectFromLeft = fromLeft
        if (fromLeft !== fromParent && fromParent !== toParent) {
            var current = fromLeft
            while (true) {
                val nextID = current.insNextID ?: break
                val next = findFloorNode(nextID) ?: break
                if (next.isText) break
                if (next.parent === toParent) {
                    // Narrow once a split sibling is found in toParent, matching
                    // JS #1233 §3 / iOS narrowedCollectRange. Skip narrowing when
                    // toLeft === toParent (leftmost child, offset 0): the narrowed
                    // range would run backwards and suppress the intended merge
                    // (JS #1237).
                    if (toLeft !== toParent) {
                        collectFromLeft = next
                        collectFromParent = toParent
                    }
                    break
                }
                current = next
            }
        }

        val nodesToBeRemoved = mutableListOf<CrdtTreeNode>()
        val tokensToBeRemoved = mutableListOf<CrdtTreeToken>()
        val toBeMovedToFromParents = mutableListOf<CrdtTreeNode>()
        val toBeMergedNodes = mutableListOf<CrdtTreeNode>()

        // Treat missing or empty VersionVector as local operation.
        val isLocal = versionVector == null || versionVector.size() == 0

        traverseInPosRange(
            fromParent = collectFromParent,
            fromLeft = collectFromLeft,
            toParent = toParent,
            toLeft = toLeft,
            includeRemoved = true,
        ) { (node, tokenType), ended ->
            // NOTE(hackerwins): If the node overlaps as a start tag with the
            // range then we need to move the remaining children to fromParent.
            // Fix 9: Skip merge for elements created by concurrent operations.
            // The editor didn't know about this element, so crossing into it is
            // an artifact of a concurrent split, not an intentional merge.
            // Also skip already-tombstoned nodes: when a prior merge moved their
            // children away, treating them as a fresh merge boundary blocks the
            // cascade-delete (03-1) from propagating the delete to those moved
            // children. Only live nodes need the merge treatment.
            if (tokenType == TokenType.Start && !ended && node.removedAt == null) {
                val nodeCreationKnown = if (isLocal) {
                    true
                } else {
                    val createdAtVV = versionVector?.get(node.createdAt.actorID)
                    createdAtVV != null && createdAtVV >= node.createdAt.lamport
                }
                if (nodeCreationKnown) {
                    toBeMergedNodes.add(node)
                    toBeMovedToFromParents.addAll(node.children)
                }
            }

            // Compute per-node creationKnown and tombstoneKnown for LWW semantics
            val creationKnown: Boolean = if (isLocal) {
                true
            } else {
                val createdAtVV = versionVector?.get(node.createdAt.actorID)
                createdAtVV != null && createdAtVV >= node.createdAt.lamport
            }

            var tombstoneKnown = false
            val nodeRemovedAt = node.removedAt
            if (nodeRemovedAt != null) {
                if (isLocal) {
                    tombstoneKnown = true
                } else {
                    val removedAtVV = versionVector?.get(nodeRemovedAt.actorID)
                    if (removedAtVV != null && removedAtVV >= nodeRemovedAt.lamport) {
                        tombstoneKnown = true
                    }
                }
            }

            // NOTE(sejongk): If the node is removable or its parent is going to
            // be removed, then this node should be removed.
            // Do not cascade-delete children of merge-boundary nodes
            // (toBeMergedNodes), because those children are moved rather than
            // deleted.
            val parentScheduledForDelete =
                node.parent in nodesToBeRemoved && node.parent !in toBeMergedNodes
            if (node.canDelete(
                    executedAt,
                    creationKnown,
                    tombstoneKnown,
                ) || parentScheduledForDelete
            ) {
                if (tokenType == TokenType.Text || tokenType == TokenType.Start) {
                    nodesToBeRemoved.add(node)

                    // Cascade delete to split siblings created by concurrent
                    // SplitElement. Only for element nodes.
                    val splitHead = node.insNextID
                    if (!node.isText && splitHead != null && node !in toBeMergedNodes) {
                        var next = findFloorNode(splitHead)
                        while (next != null) {
                            val splitCreationKnown = if (isLocal) {
                                true
                            } else {
                                val vv = versionVector?.get(next.createdAt.actorID)
                                vv != null && vv >= next.createdAt.lamport
                            }
                            if (!splitCreationKnown) {
                                val sibling = next
                                nodesToBeRemoved.add(sibling)
                                // Cascade through the full subtree, not just immediate children.
                                traverseAll(sibling) { n, _ ->
                                    if (n !== sibling) nodesToBeRemoved.add(n)
                                }
                            }
                            val followID = next.insNextID ?: break
                            next = findFloorNode(followID)
                        }
                    }
                }
                tokensToBeRemoved.add(TreeToken(node, tokenType))
            }
        }

        // NOTE(hackerwins): If concurrent deletion happens, we need to separate the
        // range(from, to) into multiple ranges.
        val changes = makeDeletionChanges(tokensToBeRemoved, executedAt).toMutableList()

        // Capture deep-copy snapshots of the top-level deleted nodes BEFORE they are
        // tombstoned, so that a reverse TreeEditOperation can convert them to plain
        // TreeNode snapshots for undo re-insertion. Only root-level removed nodes are
        // captured; their children are already included in each node's subtree via deepCopy().
        val removedNodes = nodesToBeRemoved
            .filter { it.parent !in nodesToBeRemoved }
            .map(CrdtTreeNode::deepCopy)

        // 02. Delete: delete the nodes that are marked as removed.
        val gcPairs = mutableListOf<GCPair<CrdtTreeNode>>()
        nodesToBeRemoved.forEach { node ->
            if (node.remove(executedAt)) {
                gcPairs.add(GCPair(this, node))
            }
        }

        // 03. Merge: move the nodes that are marked as moved.
        toBeMovedToFromParents.filter { it.removedAt == null }.forEach { node ->
            val oldParent = node.parent
            if (oldParent != null) {
                // Record source parent for split-skip check (Fix 8).
                node.mergedFrom = oldParent.id
                node.mergedAt = executedAt
                // Detach from old parent to prevent ghost references. Swallow
                // NoSuchElementException: a cascade delete of a split sibling
                // may have already detached the child.
                try {
                    oldParent.detachChild(node)
                } catch (_: NoSuchElementException) {
                    // Child already detached, skip.
                }
            }
            fromParent.append(node)
        }
        // Set forwarding pointer on merge-source nodes so future insertions
        // that land on the tombstoned parent redirect to the merge target.
        toBeMergedNodes.forEach { src -> src.mergedInto = fromParent.id }

        // 03-1. Propagate deletes to children moved by prior merges. When a
        // merge-source node is fully deleted (not itself a merge boundary),
        // its former children in the merge target should also be deleted.
        // Skip when mergedInto points to fromParent (concurrent merge).
        nodesToBeRemoved.forEach { node ->
            val mergedInto = node.mergedInto
            if (mergedInto != null &&
                node !in toBeMergedNodes &&
                mergedInto != fromParent.id
            ) {
                val mergeTarget = findFloorNode(mergedInto) ?: return@forEach
                mergeTarget.allChildren
                    .filter { it.mergedFrom == node.id }
                    .forEach { child ->
                        if (child.removedAt == null) {
                            if (child.remove(executedAt)) {
                                gcPairs.add(GCPair(this, child))
                            }
                            // Also tombstone descendants if the moved child is an element.
                            traverseAll(child) { n, _ ->
                                if (n !== child && n.removedAt == null) {
                                    if (n.remove(executedAt)) {
                                        gcPairs.add(GCPair(this, n))
                                    }
                                }
                            }
                        }
                    }
            }
        }

        // 04. Split: split the element nodes for the given split level.
        if (splitLevel > 0 && issueTimeTicket != null) {
            var parent = fromParent
            var left = fromLeft
            // `run` so an exhausted ancestor chain terminates the whole loop
            // (return@run), rather than re-splitting the same node.
            run {
                repeat(splitLevel) {
                    // §7.5 per-iteration advance: skip past unknown element split
                    // siblings at this ancestor level. skipActorID (§7.7) prevents
                    // advancing past our own split products.
                    if (left !== parent) {
                        left = advancePastUnknownSplitSiblings(
                            left,
                            versionVector,
                            relaxParentCheck = true,
                            skipActorID = executedAt.actorID,
                        )
                        val leftParent = left.parent
                        if (leftParent != null && leftParent !== parent) {
                            parent = leftParent
                        }
                    }

                    val splitOffset = if (left !== parent) {
                        parent.findOffset(left, includeRemoved = true) + 1
                    } else {
                        0
                    }
                    parent.split(
                        this,
                        splitOffset,
                        issueTimeTicket,
                        versionVector,
                    )
                    left = parent
                    parent = parent.parent ?: return@run
                }
            }
            changes.add(
                TreeChange(
                    type = TreeChangeType.Content,
                    from = fromIndex,
                    to = fromIndex,
                    fromPath = fromPath,
                    toPath = fromPath,
                    actorID = executedAt.actorID,
                ),
            )
        }

        // 05. insert the given node at the given position.
        if (contents?.isNotEmpty() == true) {
            val aliveContents = mutableListOf<CrdtTreeNode>()
            var leftInChildren = fromLeft

            contents.forEach { content ->
                // 03-1. insert the content nodes to the list.
                if (leftInChildren == fromParent) {
                    // 03-1-1. when there's no leftSibling, then insert content into very front of parent's children List
                    fromParent.insertAt(0, content)
                } else {
                    // 03-1-2. insert after leftSibling
                    fromParent.insertAfter(leftInChildren, content)
                }

                leftInChildren = content
                traverseAll(content) { node, _ ->
                    // if insertion happens during concurrent editing and parent node has been removed,
                    // make new nodes as tombstone immediately
                    if (fromParent.isRemoved) {
                        node.remove(executedAt)
                        gcPairs.add(GCPair(this, node))
                    } else {
                        diff = addDataSizes(diff, node.dataSize)
                    }
                    nodeMapByID[node.id] = node
                }
                if (!content.isRemoved) {
                    aliveContents.add(content)
                }
            }

            if (aliveContents.isNotEmpty()) {
                val value = aliveContents.map(CrdtTreeNode::toTreeNode)
                val lastChange = changes.lastOrNull()
                if (changes.isNotEmpty() && lastChange?.from == fromIndex) {
                    changes[changes.lastIndex] = lastChange.copy(value = value)
                } else {
                    changes.add(
                        TreeChange(
                            type = TreeChangeType.Content,
                            from = fromIndex,
                            to = fromIndex,
                            fromPath = fromPath,
                            toPath = fromPath,
                            actorID = executedAt.actorID,
                            value = value,
                        ),
                    )
                }
            }
        }
        // Count merged boundaries before their children were moved (above), so
        // the undo can regenerate them via split instead of re-inserting the
        // emptied shells. Mirrors JS SDK PR #1237.
        return TreeOperationResult(
            changes,
            gcPairs,
            diff,
            removedNodes,
            mergeLevel = toBeMergedNodes.size,
        )
    }

    /**
     * Converts nodes to be deleted to deletion changes.
     */
    private fun makeDeletionChanges(
        candidates: List<CrdtTreeToken>,
        executedAt: TimeTicket,
    ): List<TreeChange> {
        val changes = mutableListOf<TreeChange>()
        val ranges = mutableListOf<Pair<CrdtTreeToken, CrdtTreeToken>>()

        // Generate ranges by accumulating consecutive nodes.
        var start: CrdtTreeToken? = null
        var end: CrdtTreeToken
        for (i in candidates.indices) {
            val cur = candidates[i]
            val next = candidates.getOrNull(i + 1)
            if (start == null) {
                start = cur
            }
            end = cur

            val rightToken = findRightToken(cur)
            if (next == null || rightToken != next) {
                ranges.add(start to end)
                start = null
            }
        }

        // Convert each range to a deletion change.
        ranges.forEach { range ->
            val (_start, _end) = range
            val (fromLeft, fromLeftTokenType) = findLeftToken(_start)
            val (toLeft, toLeftTokenType) = _end
            val fromParent =
                fromLeft.takeIf { fromLeftTokenType == TokenType.Start } ?: fromLeft.parent
            val toParent = toLeft.takeIf { toLeftTokenType == TokenType.Start } ?: toLeft.parent

            if (fromParent == null || toParent == null) {
                return emptyList()
            }

            val fromIndex = toIndex(fromParent, fromLeft)
            val toIndex = toIndex(toParent, toLeft)
            if (fromIndex < toIndex) {
                // When the range is overlapped with the previous one, compact them.
                val lastChange = changes.lastOrNull()
                if (changes.isNotEmpty() && fromIndex == lastChange?.to) {
                    changes[changes.lastIndex] = lastChange.copy(
                        to = toIndex,
                        toPath = toPath(toParent, toLeft),
                    )
                } else {
                    changes.add(
                        TreeChange(
                            type = TreeChangeType.Content,
                            from = fromIndex,
                            to = toIndex,
                            fromPath = toPath(fromParent, fromLeft),
                            toPath = toPath(toParent, toLeft),
                            actorID = executedAt.actorID,
                        ),
                    )
                }
            }
        }
        return changes.reversed()
    }

    private fun findRightToken(treeToken: CrdtTreeToken): CrdtTreeToken {
        fun CrdtTreeNode.rightTokenType() = if (isText) TokenType.Text else TokenType.Start

        val (node, tokenType) = treeToken
        if (tokenType == TokenType.Start) {
            val children = node.allChildren
            return if (children.isEmpty()) {
                TreeToken(node, TokenType.End)
            } else {
                TreeToken(children.first(), children.first().rightTokenType())
            }
        }

        val parent = node.parent
        val siblings = parent?.allChildren ?: emptyList()
        val offset = siblings.indexOf(node)
        return if (parent != null && offset == siblings.size - 1) {
            TreeToken(parent, TokenType.End)
        } else {
            val next = siblings[offset + 1]
            TreeToken(next, next.rightTokenType())
        }
    }

    private fun findLeftToken(treeToken: CrdtTreeToken): CrdtTreeToken {
        fun CrdtTreeNode.leftTokenType() = if (isText) TokenType.Text else TokenType.End

        val (node, tokenType) = treeToken
        if (tokenType == TokenType.End) {
            val children = node.allChildren
            return if (children.isEmpty()) {
                TreeToken(node, TokenType.Start)
            } else {
                TreeToken(children.last(), children.last().leftTokenType())
            }
        }

        val parent = node.parent
        val siblings = parent?.allChildren ?: emptyList()
        val offset = siblings.indexOf(node)
        return if (parent != null && offset == 0) {
            TreeToken(parent, TokenType.Start)
        } else {
            val prev = siblings[offset - 1]
            TreeToken(prev, prev.leftTokenType())
        }
    }

    fun removeStyle(
        range: TreePosRange,
        attributeToRemove: List<String>,
        executedAt: TimeTicket,
        versionVector: VersionVector? = null,
    ): TreeOperationResult {
        var diff = DataSize(
            data = 0,
            meta = 0,
        )

        val (from, diffFrom) = findNodesAndSplitText(range.first, executedAt)
        val (fromParent, fromLeftRaw) = from
        val (to, diffTo) = findNodesAndSplitText(range.second, executedAt)
        val (toParent, toLeftRaw) = to

        diff = addDataSizes(diff, diffTo, diffFrom)

        // Advance past split siblings the editor did not know about so range
        // boundaries land after every unseen split product, matching style().
        // Skip when leftRaw equals the parent (leftmost-child sentinel).
        val fromLeft = if (fromLeftRaw === fromParent) {
            fromLeftRaw
        } else {
            advancePastUnknownSplitSiblings(fromLeftRaw, versionVector)
        }
        val toLeft = if (toLeftRaw === toParent) {
            toLeftRaw
        } else {
            advancePastUnknownSplitSiblings(toLeftRaw, versionVector)
        }

        val changes = mutableListOf<TreeChange>()
        val gcPairs = mutableListOf<GCPair<RhtNode>>()
        val prevAttributes = mutableMapOf<String, String>()
        var capturedPrev = false
        traverseInPosRange(fromParent, fromLeft, toParent, toLeft) { (node, tokenType), _ ->
            val actorID = node.createdAt.actorID
            val clientLamportAtChange = getClientInfoForChange(actorID, versionVector)

            if (node.canStyle(
                    executedAt,
                    clientLamportAtChange,
                ) && attributeToRemove.isNotEmpty()
            ) {
                if (tokenType == TokenType.End &&
                    versionVector != null &&
                    hasUnknownSplitSibling(node, versionVector)
                ) {
                    return@traverseInPosRange
                }

                if (!capturedPrev) {
                    val attrs = node.getAttrs()
                    for (key in attributeToRemove) {
                        if (attrs.has(key)) {
                            prevAttributes[key] = attrs[key]!!
                        }
                    }
                    capturedPrev = true
                }

                attributeToRemove.forEach { key ->
                    node.removeAttribute(key, executedAt)
                        .map { rhtNode -> GCPair(node, rhtNode) }
                        .let(gcPairs::addAll)
                }

                val parentOfNode = requireNotNull(node.parent)
                val previousNode = node.prevSibling ?: parentOfNode

                TreeChange(
                    type = TreeChangeType.RemoveStyle,
                    from = toIndex(parentOfNode, previousNode),
                    to = toIndex(node, node),
                    fromPath = toPath(parentOfNode, previousNode),
                    toPath = toPath(node, node),
                    actorID = executedAt.actorID,
                    attributesToRemove = attributeToRemove,
                ).let(changes::add)

                // Propagate remove-style to unknown split siblings so a
                // remove-style whose range was determined before the split
                // also covers the right part. Mirrors JS SDK PR #1224.
                if (tokenType == TokenType.Start && versionVector != null) {
                    var current = node
                    while (true) {
                        val nextID = current.insNextID ?: break
                        val next = findFloorNode(nextID) ?: break
                        if (next.isText) break
                        if (isSplitSiblingKnown(next, versionVector)) break

                        var removedAny = false
                        attributeToRemove.forEach { key ->
                            val removed = next.removeAttribute(key, executedAt)
                            if (removed.isNotEmpty()) removedAny = true
                            removed.map { rhtNode -> GCPair(next, rhtNode) }
                                .let(gcPairs::addAll)
                        }
                        if (removedAny) {
                            val parentOfNext = requireNotNull(next.parent)
                            val previousNext = next.prevSibling ?: parentOfNext
                            TreeChange(
                                type = TreeChangeType.RemoveStyle,
                                from = toIndex(parentOfNext, previousNext),
                                to = toIndex(next, next),
                                fromPath = toPath(parentOfNext, previousNext),
                                toPath = toPath(next, next),
                                actorID = executedAt.actorID,
                                attributesToRemove = attributeToRemove,
                            ).let(changes::add)
                        }
                        current = next
                    }
                }
            }
        }
        return TreeOperationResult(changes, gcPairs, diff, prevAttributes = prevAttributes)
    }

    private fun traverseInPosRange(
        fromParent: CrdtTreeNode,
        fromLeft: CrdtTreeNode,
        toParent: CrdtTreeNode,
        toLeft: CrdtTreeNode,
        includeRemoved: Boolean = false,
        callback: (CrdtTreeToken, Boolean) -> Unit,
    ) {
        val fromIndex = toIndex(fromParent, fromLeft, includeRemoved)
        val toIndex = toIndex(toParent, toLeft, includeRemoved)
        // When a concurrent merge redirects the to-position ahead of the
        // from-position, the range is empty — a prior step already handled it.
        if (fromIndex > toIndex) return
        indexTree.tokensBetween(fromIndex, toIndex, callback, includeRemoved)
    }

    fun registerNode(node: CrdtTreeNode) {
        nodeMapByID[node.id] = node
    }

    /**
     * Finds [TreePos] of the given [pos] and splits the text node if necessary.
     *
     * [CrdtTreePos] is a position in the CRDT perspective. This is
     * different from [TreePos] which is a position of the tree in the local perspective.
     *
     * If [executedAt] is given, then it is used to find the appropriate left node
     * for concurrent insertion.
     */
    fun findNodesAndSplitText(
        pos: CrdtTreePos,
        executedAt: TimeTicket? = null,
    ): Pair<TreeNodePair, DataSize> {
        var diff = DataSize(
            data = 0,
            meta = 0,
        )

        // 01. Find the parent and left sibling node of the given position.
        val (parent, leftSibling) = pos.toTreeNodePair(this)

        // 02. Determine whether the position is left-most and the exact parent
        // in the current tree.
        val isLeftMost = parent == leftSibling
        val realParent =
            leftSibling.parent.takeIf { leftSibling.parent != null && !isLeftMost }
                ?: parent

        // 02-1. If the parent has been tombstoned by a merge, redirect to the
        // merge destination using the forwarding pointer.
        val mergedIntoID = realParent.mergedInto
        if (realParent.isRemoved && isLeftMost && mergedIntoID != null) {
            val mergeTarget = findFloorNode(mergedIntoID)
            if (mergeTarget != null && !mergeTarget.isRemoved) {
                val allCh = mergeTarget.allChildren
                val firstChild = allCh.firstOrNull { child ->
                    child.mergedFrom == realParent.id
                }
                if (firstChild != null) {
                    val offset = allCh.indexOf(firstChild)
                    val redirectedLeft = if (offset <= 0) mergeTarget else allCh[offset - 1]
                    return Pair(
                        first = Pair(mergeTarget, redirectedLeft),
                        second = diff,
                    )
                }
                return Pair(
                    first = Pair(mergeTarget, mergeTarget),
                    second = diff,
                )
            }
        }

        // 03. Split text node if the left node is a text node.
        if (leftSibling.isText) {
            val (_, splitedDiff) = leftSibling.split(
                this,
                pos.leftSiblingID.offset - leftSibling.id.offset,
            )
            diff = splitedDiff
        }

        // 04. Find the appropriate left node. If some nodes are inserted at the
        // same position concurrently, then we need to find the appropriate left
        // node. This is similar to RGA.
        var updatedLeftSiblingNode = leftSibling
        executedAt?.let {
            val allChildren = realParent.allChildren
            val index = if (isLeftMost) 0 else allChildren.indexOf(leftSibling) + 1

            for (node in allChildren.drop(index)) {
                if (node.id.createdAt <= executedAt) break
                updatedLeftSiblingNode = node
            }
        }

        return Pair(
            first = Pair(realParent, updatedLeftSiblingNode),
            second = diff,
        )
    }

    fun findFloorNode(id: CrdtTreeNodeID): CrdtTreeNode? {
        val (key, value) = nodeMapByID.floorEntry(id) ?: return null
        return value.takeIf { key.createdAt == id.createdAt }
    }

    /**
     * Checks whether [node] has a split sibling (via [CrdtTreeNode.insNextID])
     * whose creation the editor did not know about. Prevents styling via End
     * tokens when a concurrent split extended the range into the split sibling.
     *
     * Unlike a traversal-advance helper, intentionally omits the parent-equality
     * check: in multi-level splits the sibling may have been moved to a
     * different parent by the recursive ancestor split. The End-token guard
     * must still fire because the node WAS split — [CrdtTreeNode.insNextID]
     * is only set by SplitElement.
     */
    /**
     * Walks the [CrdtTreeNode.insNextID] chain from [node], returning the last
     * element-type sibling whose creation the editor did not know about.
     * Stops at text nodes, on a sibling whose parent differs from the current
     * node's parent (moved by a higher-level split), or as soon as a known
     * sibling is encountered. Treats null or empty [versionVector] as a local
     * operation and returns [node] unchanged.
     */
    private fun advancePastUnknownSplitSiblings(
        node: CrdtTreeNode,
        versionVector: VersionVector?,
        relaxParentCheck: Boolean = false,
        skipActorID: String? = null,
    ): CrdtTreeNode {
        if (versionVector == null || versionVector.size() == 0) return node

        var current = node
        while (true) {
            val nextID = current.insNextID ?: return current
            val next = findFloorNode(nextID) ?: return current
            if (next.isText) return current
            // §7.5: skip the parent check at ancestor iterations of the split
            // loop, where a concurrent recursive split may have moved the
            // sibling to a different parent.
            if (!relaxParentCheck && next.parent !== current.parent) return current

            val actorID = next.id.createdAt.actorID
            // §7.7: stop at siblings created by the current operation's actor —
            // they are our own split products, not concurrent ones.
            if (skipActorID != null && actorID == skipActorID) return current

            val knownLamport = versionVector.get(actorID)
            val isUnknown = knownLamport == null || knownLamport < next.id.createdAt.lamport
            if (!isUnknown) return current

            current = next
        }
    }

    /**
     * Returns true when the creation of the split sibling [node] is known to
     * the given [versionVector]. Mirrors JS SDK `ticketKnown`. Used to stop
     * propagating a style/remove-style along the [CrdtTreeNode.insNextID] chain
     * once a sibling the editor already knew about is reached.
     */
    private fun isSplitSiblingKnown(node: CrdtTreeNode, versionVector: VersionVector): Boolean {
        val knownLamport = versionVector.get(node.createdAt.actorID) ?: return false
        return knownLamport >= node.createdAt.lamport
    }

    private fun hasUnknownSplitSibling(node: CrdtTreeNode, versionVector: VersionVector): Boolean {
        val insNextID = node.insNextID ?: return false
        val next = findFloorNode(insNextID) ?: return false
        if (next.isText) return false

        val actorID = next.id.createdAt.actorID
        val knownLamport = versionVector.get(actorID)
        return knownLamport == null || knownLamport < next.id.createdAt.lamport
    }

    fun checkPosRangeValid(posRange: TreePosRange): Boolean {
        return listOf(posRange.first, posRange.second).all {
            findFloorNode(it.parentID) != null && findFloorNode(it.leftSiblingID) != null
        }
    }

    /**
     * Move the given [source] range to the given [target] range.
     */
    fun move(
        target: Pair<Int, Int>,
        source: Pair<Int, Int>,
        executedAt: TimeTicket,
    ) {
        // TODO("Implement after JS SDK's implementation")
    }

    /**
     * Physically deletes the given [node] from [IndexTree].
     */
    override fun delete(node: CrdtTreeNode) {
        node.parent?.removeChild(node)
        nodeMapByID.remove(node.id)

        val insPrevID = node.insPrevID
        val insNextID = node.insNextID

        insPrevID?.let {
            val insPrev = findFloorNode(it)
            insPrev?.insNextID = insNextID
        }
        insNextID?.let {
            val insNext = findFloorNode(it)
            insNext?.insPrevID = insPrevID
        }

        node.insPrevID = null
        node.insNextID = null
    }

    /**
     * Finds the position of the given [index] in the tree.
     */
    fun findPos(index: Int, preferText: Boolean = true): CrdtTreePos {
        val treePos = indexTree.findTreePos(index, preferText)
        return treePos.toCrdtTreePos()
    }

    /**
     * Copies itself deeply.
     */
    override fun deepCopy(): CrdtElement {
        return copy(
            root = root.deepCopy(),
        )
    }

    override fun getDataSize(): DataSize {
        var data = 0
        var meta = 0

        indexTree.traverse { node, _ ->
            if (node.isRemoved) {
                return@traverse
            }

            val dataSize = node.dataSize
            data += dataSize.data
            meta += dataSize.meta
        }

        return DataSize(
            data = data,
            meta = meta + getMetaUsage(),
        )
    }

    /**
     * Converts the given [parentNode] to the index of the tree.
     */
    fun toIndex(
        parentNode: CrdtTreeNode,
        leftSiblingNode: CrdtTreeNode,
        includeRemoved: Boolean = false,
    ): Int {
        return indexTree.indexOf(
            toCrdtTreePos(parentNode, leftSiblingNode, includeRemoved),
            includeRemoved,
        )
    }

    /**
     * Converts the given path of the node to the range of the position.
     */
    fun pathToPosRange(path: List<Int>): TreePosRange {
        val fromIndex = pathToIndex(path)
        return findPos(fromIndex) to findPos(fromIndex + 1)
    }

    /**
     * Finds the position of the given index in the tree by [path].
     */
    fun pathToPos(path: List<Int>): CrdtTreePos {
        return findPos(indexTree.pathToIndex(path))
    }

    /**
     * Returns the XML encoding of this tree.
     */
    fun toXml(): String {
        return indexTree.root.toXml()
    }

    /**
     * Converts the given [index] to path.
     */
    fun indexToPath(index: Int): List<Int> {
        return indexTree.indexToPath(index)
    }

    /**
     * Converts the given [path] to index.
     */
    fun pathToIndex(path: List<Int>): Int {
        return indexTree.pathToIndex(path)
    }

    /**
     * `pathToTreePos` converts the given path of the node to the TreePos.
     */
    fun pathToTreePos(path: List<Int>): TreePos<CrdtTreeNode> {
        return indexTree.pathToTreePos(path)
    }

    /**
     * Returns the position range from the given [range].
     */
    fun indexRangeToPosRange(range: Pair<Int, Int>): TreePosRange {
        val (fromIndex, toIndex) = range
        val fromPos = findPos(fromIndex)
        return if (fromIndex == toIndex) {
            fromPos to fromPos
        } else {
            fromPos to findPos(toIndex)
        }
    }

    /**
     * Converts the [range] into [TreePosStructRange].
     */
    fun indexRangeToPosStructRange(range: Pair<Int, Int>): TreePosStructRange {
        val (fromIndex, toIndex) = range
        val fromPos = findPos(fromIndex)
        return if (fromIndex == toIndex) {
            fromPos.toStruct() to fromPos.toStruct()
        } else {
            fromPos.toStruct() to findPos(toIndex).toStruct()
        }
    }

    /**
     * Converts the given position [range] to the path range.
     */
    fun posRangeToPathRange(range: TreePosRange): Pair<List<Int>, List<Int>> {
        val (from, _) = findNodesAndSplitText(range.first)
        val (fromParent, fromLeft) = from
        val (to, _) = findNodesAndSplitText(range.second)
        val (toParent, toLeft) = to
        return toPath(fromParent, fromLeft) to toPath(toParent, toLeft)
    }

    /**
     * Converts the given position range to the path range.
     */
    fun posRangeToIndexRange(range: TreePosRange): Pair<Int, Int> {
        val (from, _) = findNodesAndSplitText(range.first)
        val (fromParent, fromLeft) = from
        val (to, _) = findNodesAndSplitText(range.second)
        val (toParent, toLeft) = to
        return toIndex(fromParent, fromLeft) to toIndex(toParent, toLeft)
    }

    /**
     * Converts the pos to parent and left sibling nodes.
     */
    private fun CrdtTreePos.toTreeNodePair(tree: CrdtTree): TreeNodePair {
        val parentNode = tree.findFloorNode(parentID)
        val leftNode = tree.findFloorNode(leftSiblingID)
        require(parentNode != null && leftNode != null) {
            "cannot find node of CrdtTreePos($parentID, $leftSiblingID)"
        }

        /**
         * NOTE(hackerwins): If the left node and the parent node are the same,
         * it means that the position is the left-most of the parent node.
         * We need to skip finding the left of the position.
         */
        val updatedLeftSiblingNode =
            if (leftSiblingID != parentID &&
                leftSiblingID.offset > 0 &&
                leftSiblingID.offset == leftNode.id.offset &&
                leftNode.insPrevID != null
            ) {
                leftNode.insPrevID?.let(tree::findFloorNode) ?: leftNode
            } else {
                leftNode
            }
        return parentNode to updatedLeftSiblingNode
    }

    /**
     * Creates a new instance of CRDTTreePos from the given TreePos.
     */
    private fun TreePos<CrdtTreeNode>.toCrdtTreePos(): CrdtTreePos {
        val (node, offset) = this

        var resultNode = node
        val leftNode = if (node.isText) {
            resultNode = requireNotNull(node.parent)
            node.parent?.takeIf {
                it.children.firstOrNull() == node && offset == 0
            } ?: node
        } else {
            if (offset == 0) node else node.children[offset - 1]
        }

        return CrdtTreePos(
            resultNode.id,
            CrdtTreeNodeID(leftNode.createdAt, leftNode.offset + offset),
        )
    }

    /**
     * Returns the client info for the change.
     */
    private fun getClientInfoForChange(actorID: String, versionVector: VersionVector?): Long {
        return versionVector?.let {
            versionVector.get(actorID) ?: 0L
        } ?: MAX_LAMPORT
    }
}

/**
 * [CrdtTreeNode] is a node of [CrdtTree]. It includes the logical clock and
 * links to other nodes to resolve conflicts.
 */
internal data class CrdtTreeNode(
    val id: CrdtTreeNodeID,
    override val type: String,
    private val _value: String? = null,
    override val childNodes: IndexTreeNodeList<CrdtTreeNode> = IndexTreeNodeList(mutableListOf()),
    private val _attributes: Rht = Rht(),
) : IndexTreeNode<CrdtTreeNode>(), GCChild, GCParent<RhtNode> {

    val gcPairs: List<GCPair<*>>
        get() = _attributes
            .filter { node -> node.removedAt != null }
            .map { node -> GCPair(this, node) }

    val attributes: Map<String, String>
        get() = _attributes.nodeKeyValueMap

    val attributesToXml: String
        get() = _attributes.toXml()

    val createdAt: TimeTicket
        get() = id.createdAt

    override var removedAt: TimeTicket? = null
        private set(value) {
            val removed = field == null && value != null
            field = value
            if (removed) {
                onRemovedListener?.onRemoved(this)
            }
        }

    override val dataSize: DataSize
        get() {
            var data = 0
            var meta = TIME_TICKET_SIZE

            if (isText) {
                data += visibleSize * 2
            }

            if (isRemoved) {
                meta += TIME_TICKET_SIZE
            }

            for (node in _attributes) {
                if (node.isRemoved) {
                    continue
                }

                val dataSize = node.dataSize
                data += dataSize.data
                meta += dataSize.meta
            }

            return DataSize(
                data = data,
                meta = meta,
            )
        }

    override val isRemoved: Boolean
        get() = removedAt != null

    val offset: Int
        get() = id.offset

    override var value: String = _value.orEmpty()
        get() {
            check(isText) {
                "cannot set value of element node: $type"
            }
            return field
        }
        set(value) {
            check(isText) {
                "cannot set value of element node: $type"
            }
            field = value
            visibleSize = value.length
            totalSize = value.length
        }

    var insPrevID: CrdtTreeNodeID? = null

    var insNextID: CrdtTreeNodeID? = null

    /**
     * Runtime-only forwarding pointer set when this node is tombstoned by a
     * merge. Records which parent received the children so that later
     * insertions landing on this tombstoned parent redirect to the merge
     * destination.
     */
    var mergedInto: CrdtTreeNodeID? = null

    /**
     * Runtime-only reverse pointer recording the source parent this node was
     * moved from during a merge. Used by splitElement to keep merge-moved
     * children in the original node instead of moving them to the split sibling
     * when the merge is concurrent with the split.
     */
    var mergedFrom: CrdtTreeNodeID? = null

    /**
     * Runtime-only timestamp recording when the merge that moved this node
     * was executed. Compared against the split editor's [VersionVector] to
     * detect concurrency.
     */
    var mergedAt: TimeTicket? = null

    val rhtNodes: Iterable<RhtNode>
        get() = _attributes

    fun getAttrs() = _attributes

    init {
        _value?.let { value = it }
    }

    /**
     * Clones this text node with the given [offset].
     */
    override fun cloneText(offset: Int): CrdtTreeNode {
        return clone(offset, id.createdAt).apply {
            mergedFrom = this@CrdtTreeNode.mergedFrom
            mergedAt = this@CrdtTreeNode.mergedAt
        }
    }

    /**
     * Clones this element node with the given [issueTimeTicket] function.
     */
    override fun cloneElement(issueTimeTicket: () -> TimeTicket) = clone(0, issueTimeTicket())

    private fun clone(offset: Int, createdAt: TimeTicket): CrdtTreeNode {
        return copy(
            id = CrdtTreeNodeID(createdAt, offset),
            _value = null,
            // Deep-copy attributes so a split node keeps its own styling and a
            // concurrent style operation whose range was computed before the
            // split also covers the right part of the split.
            _attributes = _attributes.deepCopy(),
            childNodes = IndexTreeNodeList(mutableListOf()),
        ).apply {
            removedAt = this@CrdtTreeNode.removedAt
        }
    }

    fun split(
        tree: CrdtTree,
        offset: Int,
        issueTimeTicket: (() -> TimeTicket)? = null,
        versionVector: VersionVector? = null,
    ): Pair<CrdtTreeNode?, DataSize> {
        val (split, diff) = if (isText) {
            splitText(offset, id.offset)
        } else {
            splitElement(offset, versionVector, requireNotNull(issueTimeTicket))
        }

        val node = this
        split?.apply {
            split.insPrevID = node.id
            node.insNextID?.let { insNextID ->
                val insNext = tree.findFloorNode(insNextID)
                split.insNextID = insNextID
                if (insNext != null) {
                    insNext.insPrevID = split.id

                    // §7.4 Empty Sibling Re-Parenting: when the existing insNext
                    // sibling lives in a different parent (from a prior parent-
                    // level split), move the new empty split sibling into that
                    // parent. Skip when insNext is tombstoned (e.g. by an undo
                    // boundary deletion): re-parenting into a removed element
                    // would make the new split sibling invisible.
                    val insNextParent = insNext.parent
                    if (!node.isText &&
                        insNextParent != null &&
                        !insNext.isRemoved &&
                        insNextParent !== split.parent &&
                        split.allChildren.isEmpty()
                    ) {
                        // No try/catch: `split` was just inserted by splitElement,
                        // so detachChild cannot fail here. Let a throw surface a
                        // real structural bug (matches JS invariant).
                        split.parent?.detachChild(split)
                        insNextParent.insertBefore(insNext, split)
                    }
                }
            }
            node.insNextID = split.id
            tree.registerNode(split)
        }

        return Pair(split, diff)
    }

    /**
     * Returns true when [child] was moved here by a concurrent merge and
     * its merge source is a child of this node. When the source is local
     * to this level the content must stay in the original (left) node so
     * that it is not incorrectly split away. When the source is external
     * (e.g. a sibling that was merged in) the child flows naturally to the
     * split (right) node.
     *
     * Only applies for remote splits (non-null, non-empty [versionVector]).
     * Local splits always know about all prior operations, so never veto.
     */
    override fun shouldKeepChildInLeft(
        child: CrdtTreeNode,
        versionVector: VersionVector?,
        allChildren: List<CrdtTreeNode>,
    ): Boolean {
        if (versionVector == null || versionVector.size() == 0) return false
        val mergedAt = child.mergedAt ?: return false
        val mergedFrom = child.mergedFrom ?: return false
        if (versionVector.afterOrEqual(mergedAt)) return false
        return allChildren.any { sibling -> sibling.id == mergedFrom }
    }

    override fun isSplitSiblingSkipForBoundaryMigration(child: CrdtTreeNode): Boolean =
        child.insPrevID != null && !child.isText

    override fun isUnknownToEditor(child: CrdtTreeNode, versionVector: VersionVector): Boolean {
        val knownLamport = versionVector.get(child.id.createdAt.actorID)
        return knownLamport == null || knownLamport < child.id.createdAt.lamport
    }

    fun setAttributes(
        attributes: Map<String, String>,
        executedAt: TimeTicket,
    ): List<RhtSetResult> {
        return attributes.map { (key, value) -> _attributes.set(key, value, executedAt) }
    }

    fun removeAttribute(key: String, executedAt: TimeTicket): List<RhtNode> {
        return _attributes.remove(key, executedAt)
    }

    /**
     * Marks the node as removed. Returns true if the node was newly tombstoned.
     */
    fun remove(executedAt: TimeTicket): Boolean {
        val alived = removedAt == null

        if (alived || removedAt < executedAt) {
            removedAt = executedAt
        }
        if (alived) {
            updateAncestorSize(-paddedSize())
        }
        return alived
    }

    /**
     * Copies itself deeply.
     */
    fun deepCopy(): CrdtTreeNode {
        val childNodes = mutableListOf<CrdtTreeNode>()
        allChildren.forEach {
            childNodes.add(it.deepCopy())
        }
        return copy(
            _value = _value,
            childNodes = IndexTreeNodeList(childNodes),
            _attributes = _attributes.deepCopy(),
        ).also {
            it.allChildren.forEach { child ->
                child.parent = it
            }
            it.visibleSize = visibleSize
            it.totalSize = totalSize
            it.removedAt = removedAt
            it.insPrevID = insPrevID
            it.insNextID = insNextID
            it.mergedInto = mergedInto
            it.mergedFrom = mergedFrom
            it.mergedAt = mergedAt
            if (it.isText) {
                it.value = value
            }
        }
    }

    /**
     * Checks if node is able to delete.
     *
     * @param editedAt The time when the edit operation was executed
     * @param creationKnown Whether the node's creation was visible at the operation's frontier
     * @param tombstoneKnown Whether the prior tombstone was visible at the operation's frontier
     *
     * LWW: Allow overwrite only when tombstoneKnown is false and editedAt is newer.
     */
    fun canDelete(
        editedAt: TimeTicket,
        creationKnown: Boolean,
        tombstoneKnown: Boolean,
    ): Boolean {
        // Skip if the node's creation was not visible to this operation.
        if (!creationKnown) {
            return false
        }

        if (removedAt == null) {
            return true
        }

        // LWW: Allow overwrite only when tombstoneKnown is false and editedAt is newer.
        if (!tombstoneKnown && editedAt > removedAt) {
            return true
        }

        return false
    }

    fun canStyle(executedAt: TimeTicket, clientLamportAtChange: Long): Boolean {
        if (isText) {
            return false
        }
        val nodeExisted = createdAt.lamport <= clientLamportAtChange
        return nodeExisted && (removedAt == null || executedAt > removedAt)
    }

    override fun delete(node: RhtNode) {
        _attributes.delete(node)
    }

    @Suppress("FunctionName")
    companion object {

        fun CrdtTreeText(id: CrdtTreeNodeID, value: String): CrdtTreeNode {
            return CrdtTreeNode(id, DEFAULT_TEXT_TYPE, value)
        }

        fun CrdtTreeElement(
            id: CrdtTreeNodeID,
            type: String,
            children: List<CrdtTreeNode> = emptyList(),
            attributes: Rht = Rht(),
        ) = CrdtTreeNode(id, type, null, IndexTreeNodeList(children.toMutableList()), attributes)
    }
}

/**
 * [CrdtTreePos] represent a position in the tree. It is used to identify a
 * position in the tree. It is composed of the parent ID and the left sibling ID.
 * If there's no left sibling in parent's children, then left sibling is parent.
 */
public data class CrdtTreePos internal constructor(
    val parentID: CrdtTreeNodeID,
    val leftSiblingID: CrdtTreeNodeID,
) : JsonSerializable<CrdtTreePos, CrdtTreePosStruct> {

    override fun toStruct(): CrdtTreePosStruct {
        return CrdtTreePosStruct(parentID.toStruct(), leftSiblingID.toStruct())
    }
}

/**
 * [CrdtTreeNodeID] represent an ID of a node in the tree. It is used to
 * identify a node in the tree. It is composed of the creation time of the node
 * and the offset from the beginning of the node if the node is split.
 *
 * Some of replicas may have nodes that are not split yet. In this case, we can
 * use `map.floorEntry()` to find the adjacent node.
 */
public data class CrdtTreeNodeID internal constructor(
    /**
     * Creation time of the node.
     */
    val createdAt: TimeTicket,

    /**
     * The distance from the beginning of the node when the node is split.
     */
    val offset: Int,
) : Comparable<CrdtTreeNodeID>, JsonSerializable<CrdtTreeNodeID, CrdtTreeNodeIDStruct> {

    override fun compareTo(other: CrdtTreeNodeID): Int {
        return compareValuesBy(this, other, { it.createdAt }, { it.offset })
    }

    override fun toStruct(): CrdtTreeNodeIDStruct {
        return CrdtTreeNodeIDStruct(createdAt.toStruct(), offset)
    }

    companion object {
        internal val InitialCrdtTreeNodeID = CrdtTreeNodeID(InitialTimeTicket, 0)
    }
}
