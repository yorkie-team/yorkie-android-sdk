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
import dev.yorkie.util.Logger.Companion.logDebug
import dev.yorkie.util.Logger.Companion.logError
import dev.yorkie.util.TokenType
import dev.yorkie.util.TreePos
import dev.yorkie.util.TreeToken
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.traverseAll
import java.util.TreeMap

public typealias TreePosRange = Pair<CrdtTreePos, CrdtTreePos>

internal typealias CrdtTreeToken = TreeToken<CrdtTreeNode>

internal typealias TreeNodePair = Pair<CrdtTreeNode, CrdtTreeNode>

/**
 * Judges whether a node reached [CrdtTree]'s merge target strictly AFTER
 * the merge-source tombstone a style/removeStyle range's declared end
 * anchor names — i.e., whether it is an "interloper" the styling client
 * never saw when it recorded that range. Returned by
 * [CrdtTree.mergedAnchorInterloperGuard]. Port 1c033ff5.
 */
private fun interface MergedAnchorInterloperGuard {
    fun isInterloper(node: CrdtTreeNode): Boolean
}

/**
 * [Boundary] selects how [CrdtTree.findNodesAndSplitText] resolves a
 * position inside a parent tombstoned by a merge. [Insert] places it at the
 * insertion boundary in the merge target (before the first moved child, so
 * RGA ordering breaks ties). [Range] places it right after the
 * merge-source tombstone itself, so a style/removeStyle range neither grows
 * over nor shrinks past nodes concurrently inserted at that anchor.
 */
internal enum class Boundary { Insert, Range }

/**
 * [TreeRestoreSpan] identifies a node this edit transitioned
 * visible -> tombstoned (or inserted), for identity-preserving Tree
 * undo/redo. Parallel to Text's [RestoreSpan], not shared: a tree span
 * carries the node's structure and its position anchors instead of a flat
 * offset interval, because id order is not sibling order in a tree.
 *
 * For a text node the span addresses the absolute-offset interval
 * `[id.offset, id.offset + length)` of the original insertion
 * (split-invariant); for an element node it is the whole node (`length` is
 * 0). [value]/[attrs] are deep copies so a GC-purged node can be recreated.
 * [leftSiblingID]/[rightSiblingID] are the deleted run's external boundary
 * anchors captured at tombstone time — redundant on purpose: since a run's
 * spans are carried together, [CrdtTree.restore] can rebuild the run's
 * internal order from the op itself and needs only ONE surviving boundary
 * to place it.
 */
internal data class TreeRestoreSpan(
    val id: CrdtTreeNodeID,
    val nodeType: String,
    val isText: Boolean,
    val length: Int,
    val value: String? = null,
    val attrs: Rht? = null,
    val parentID: CrdtTreeNodeID? = null,
    val leftSiblingID: CrdtTreeNodeID? = null,
    val rightSiblingID: CrdtTreeNodeID? = null,
)

/**
 * [CrdtTree.restore]'s result: [untombstoned] nodes were revived in place
 * (their GC pairs must be unregistered), [recreated] nodes are brand-new
 * (their size must be added to live), [pendingGcPairs] are pending GC pairs
 * for born-removed remainders split off a removed straddler (must be
 * registered BEFORE unregistering [untombstoned]'s GC pairs), and [diff] is
 * the metadata overhead of splitting live straddlers (must be `acc`ed to
 * live). Kotlin shape of the JS 4-tuple `[untombstoned, recreated, pairs,
 * diff]`.
 */
internal data class TreeRestoreResult(
    val untombstoned: List<CrdtTreeNode>,
    val recreated: List<CrdtTreeNode>,
    val pendingGcPairs: List<GCPair<CrdtTreeNode>>,
    val diff: DataSize,
)

@SuppressLint("VisibleForTests")
internal data class CrdtTree(
    val root: CrdtTreeNode,
    override var createdAt: TimeTicket,
    override var movedAt: TimeTicket? = null,
    override var removedAt: TimeTicket? = null,
) : CrdtElement(), GCParent<CrdtTreeNode>, GCCrdtElement {

    override val gcPairs: List<GCPair<*>>
        get() = buildList {
            // traverseAll (not traverse) is required to register tombstones —
            // including pieces split off a tombstoned node — after snapshot
            // load; the visible-only traversal skipped tombstones entirely.
            // These pairs carry gcOnlySize because the freshly built root's
            // getDataSize only counted visible nodes into docSize.live.
            indexTree.traverseAll { node, _ ->
                if (node.removedAt != null) {
                    add(GCPair(this@CrdtTree, node, gcOnlySize = node.dataSize))
                }
                addAll(node.gcPairs)
            }
        }

    internal val indexTree = IndexTree(root)

    private val nodeMapByID = TreeMap<CrdtTreeNodeID, CrdtTreeNode>()

    /**
     * Buffers GC pairs for nodes created already-tombstoned by splitting a
     * removed node. [edit], [style], and [removeStyle] drain this buffer via
     * [drainPendingGcPairs] into the GC pairs they already return.
     */
    private var pendingGcPairs = mutableListOf<GCPair<CrdtTreeNode>>()

    /**
     * Buffers a GC pair for [node], a piece born already-tombstoned by
     * splitting an already-removed node. [size] is the net-new size created
     * by the split; it is accounted to `docSize.gc` at registration since the
     * node was never live.
     */
    fun registerPendingGcPair(node: CrdtTreeNode, size: DataSize) {
        pendingGcPairs.add(GCPair(this, node, gcOnlySize = size))
    }

    /**
     * Returns the buffered GC pairs and clears the buffer.
     */
    fun drainPendingGcPairs(): List<GCPair<CrdtTreeNode>> {
        val pairs = pendingGcPairs
        pendingGcPairs = mutableListOf()
        return pairs
    }

    val rootTreeNode: TreeNode
        get() = indexTree.root.toTreeNode()

    init {
        // Plain put on the fast path; a document whose history carries a
        // duplicate CrdtTreeNodeID (a pre-v0.7.16 copy-reinsert undo) leaves
        // nodeMapByID smaller than the traversed node count, so re-register
        // every node through registerNode to resolve the winner (port
        // 2ed28322). Correct trees pay only the size-vs-count comparison.
        var nodeCount = 0
        indexTree.traverseAll { node, _ ->
            nodeMapByID[node.id] = node
            nodeCount++
        }
        if (nodeMapByID.size != nodeCount) {
            indexTree.traverseAll { node, _ -> registerNode(node) }
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

        // Boundary.Range (port 5c158690): a style range must never cross a
        // concurrent merge anchor, so both endpoints resolve right after the
        // merge-source tombstone rather than the insertion boundary.
        val (from, diffFrom) = findNodesAndSplitText(range.first, executedAt, Boundary.Range)
        val (fromParent, fromLeftRaw) = from
        val (to, diffTo) = findNodesAndSplitText(range.second, executedAt, Boundary.Range)
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

        // Widened to GCPair<*>: drained pending pairs below are
        // GCPair<CrdtTreeNode>, a different type parameter than the
        // GCPair<RhtNode> attribute pairs added by this loop.
        val gcPairs = mutableListOf<GCPair<*>>()
        val prevAttributes = mutableMapOf<String, String>()
        val newAttrKeys = mutableListOf<String>()
        var capturedPrev = false
        val shouldSkipToken = styleSkipPredicate(range.second, versionVector)
        traverseInPosRange(
            fromParent = fromParent,
            fromLeft = fromLeft,
            toParent = toParent,
            toLeft = toLeft,
        ) { (node, tokenType), _ ->
            val actorID = node.createdAt.actorID
            val clientLamportAtChange = getClientInfoForChange(actorID, versionVector)

            if (node.canStyle(executedAt, clientLamportAtChange) && attributes != null) {
                if (shouldSkipToken(node, tokenType)) {
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
        // This style operation's boundary splits (findNodesAndSplitText) can
        // land inside an already-tombstoned node and buffer a born-dead
        // piece; drain it so it is not left unregistered for GC.
        gcPairs.addAll(drainPendingGcPairs())
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
                    // Include removed children (allChildren) so tombstones move
                    // with the merge and survive as RGA anchors; a concurrent
                    // insert referencing one then resolves in the merge target
                    // and orders via the RGA tie-break (port c5d5c851).
                    toBeMovedToFromParents.addAll(node.allChildren)
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
        // Identity-preserving undo: capture one span per node THIS edit
        // transitions visible -> tombstoned. node.remove() returning true is
        // exactly that transition, so pre-tombstoned nodes and LWW
        // overwrites are excluded automatically (AC11). nodesToBeRemoved is
        // in traversal order -> parents precede children, which restore()
        // relies on when recreating purged subtrees.
        val removedSpans = mutableListOf<TreeRestoreSpan>()
        // Captured in the insert phase below: identity spans of the nodes
        // this edit inserts, so an undo re-removes them by identity (not by
        // index, which would clobber concurrently-restored content) and a
        // redo revives them.
        val insertedSpans = mutableListOf<TreeRestoreSpan>()
        nodesToBeRemoved.forEach { node ->
            if (node.remove(executedAt)) {
                gcPairs.add(GCPair(this, node))
                removedSpans.add(captureRestoreSpan(node))
            }
        }
        // Snapshot the GC-pair count right after the plain-delete loop: if
        // the merge phases below (03/03-1) or the born-dead split pieces
        // drained afterward add more pairs, this edit involved merge-child
        // propagation and removedSpans/insertedSpans are NOT a complete
        // description of the deletion (spansComplete guard, below).
        val deletePairCount = gcPairs.size

        // §6.3 Chained-Merge Flattening (Fix 20, port b2e66114): a merge
        // chain P->Q->R is kept flat so runtime state matches what
        // rebuildMergeState derives from a snapshot (which can only ever
        // represent the compressed chain, because it records one mergedFrom
        // pointer per child and reads the child's current physical parent).
        // The destination is resolved through resolveMergeTarget, so
        // children merged into an already-merged-away parent forward to the
        // final live target instead of piling up under the removed
        // intermediate.
        val dest = resolveMergeTarget(fromParent)

        // 03. Merge: move the nodes that are marked as moved. A moved child
        // must have a source parent to record; skip otherwise rather than
        // move an untracked node (Fix 8). Tombstoned children are moved too
        // (kept removed): they stay as RGA anchors so a concurrent insert
        // referencing one resolves in the merge target and orders via the
        // RGA tie-break, converging with the replica that inserted before
        // the merge. moveChild keeps the size accounting correct for both
        // live and tombstoned children (visible-neutral for the latter), so
        // index positions stay correct (port c5d5c851).
        toBeMovedToFromParents.forEach { node ->
            val oldParent = node.parent ?: return@forEach
            // mergedFrom/mergedAt are stamped only on the first move, so a
            // child carried through a chained merge keeps its original
            // source and the original merge ticket (Fix 20).
            if (node.mergedFrom == null) {
                node.mergedFrom = oldParent.id
                node.mergedAt = executedAt
            }
            dest.moveChild(node)
            // Point this child's original source at the resolved
            // destination, path-compressing a transitive source (a prior
            // merge whose children were just relocated again) from the
            // now-removed intermediate to the final target. mergedInto is
            // derived solely from a moved child (never from the
            // merge-source list directly), mirroring rebuildMergeState so
            // runtime and snapshot agree: a source with no moved child of
            // its own (an intermediate that only relayed another source's
            // children) is left unset on both paths.
            node.mergedFrom?.let(::findFloorNode)?.let { src -> src.mergedInto = dest.id }
        }

        // 03-1. Propagate deletes to children moved by prior merges. When a
        // merge-source node is fully deleted (not itself a merge boundary),
        // its former children in the merge target should also be deleted.
        // Skip when mergedInto points to the merge destination (concurrent
        // merge). Compare against the resolved dest, not fromParent: the
        // forwarding pointers above point at the flattened target (§6.3), so
        // a chained merge (dest !== fromParent) must recognize a
        // concurrent-merge boundary by dest.
        nodesToBeRemoved.forEach { node ->
            val mergedInto = node.mergedInto
            if (mergedInto != null &&
                node !in toBeMergedNodes &&
                mergedInto != dest.id
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
                    if (parent.parent == null) {
                        // The walk reached the tree root: stop before splitting it so
                        // the root is never split and its clone never orphaned. JS
                        // SDK 2ef3260b throws here instead; Android logs and stops
                        // the walk early (partial split), applying the edit's
                        // insertion normally — approved divergence.
                        logDebug(
                            TAG,
                            "splitLevel walk reached tree root; stopping before splitting root",
                        )
                        return@run
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

        // 05. insert the given node at the given position. Cross-change ID
        // reuse (an earlier change, another actor, or an ID this same edit's
        // own split is about to create) is dropped as a whole subtree here —
        // AFTER step 01's range resolution, which can itself split text and
        // create the very ID a content node carries (port 2ed28322).
        // insertedContentSize is measured on the still-detached content, so
        // it reflects the accepted-content span even if the insert loop
        // below tombstones the content under a removed fromParent.
        val acceptedContents = contents?.let { dropDuplicateContents(it, executedAt) }
        val insertedContentSize = acceptedContents?.sumOf { it.paddedSize } ?: 0
        if (acceptedContents?.isNotEmpty() == true) {
            val aliveContents = mutableListOf<CrdtTreeNode>()
            var leftInChildren = fromLeft

            // Merge-bound insert stamping (port 1c033ff5): an insert
            // declared inside a parent a concurrent merge tombstoned stamps
            // mergedFrom/mergedAt so mergedAnchorInterloperGuard can later
            // distinguish it from a genuine interloper.
            val declaredFromParent = range.first.toTreeNodePair(this).first
            val intendedParent = if (declaredFromParent !== fromParent &&
                declaredFromParent.isRemoved &&
                declaredFromParent.mergedInto != null &&
                resolveMergeTarget(declaredFromParent) === fromParent
            ) {
                declaredFromParent
            } else {
                null
            }
            val intendedMergedAt = intendedParent?.let { parent ->
                fromParent.allChildren
                    .firstOrNull { it.mergedFrom == parent.id && it.mergedAt != null }
                    ?.mergedAt ?: parent.removedAt
            }

            acceptedContents.forEach { content ->
                // 03-1. insert the content nodes to the list.
                if (leftInChildren == fromParent) {
                    // 03-1-1. when there's no leftSibling, then insert content into very front of parent's children List
                    fromParent.insertAt(0, content)
                } else {
                    // 03-1-2. insert after leftSibling
                    fromParent.insertAfter(leftInChildren, content)
                }

                leftInChildren = content
                if (intendedParent != null) {
                    content.mergedFrom = intendedParent.id
                    content.mergedAt = intendedMergedAt
                }
                traverseAll(content) { node, _ ->
                    // if insertion happens during concurrent editing and parent node has been removed,
                    // make new nodes as tombstone immediately
                    if (fromParent.isRemoved) {
                        node.remove(executedAt)
                        gcPairs.add(GCPair(this, node))
                    } else {
                        diff = addDataSizes(diff, node.dataSize)
                    }
                    registerNode(node)
                    // Capture this inserted node's identity span for
                    // identity-preserving insert undo/redo.
                    insertedSpans.add(captureRestoreSpan(node))
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
        // Both the boundary splits (step 01, findNodesAndSplitText) and the
        // splitLevel walk (step 04) can land inside an already-tombstoned
        // node and buffer a born-dead piece; drain them all so none are left
        // unregistered for GC.
        gcPairs.addAll(drainPendingGcPairs())

        // Identity-preserving restore only covers plain deletions. If this
        // edit merged nodes (mergeLevel > 0) or its merge propagation (03-1)
        // or a born-dead split piece (drainPendingGcPairs, above) added GC
        // pairs beyond the plain-delete loop, the captured spans don't fully
        // describe the deletion -> emit empty spans so the op layer keeps
        // the copy-reinsert reverse.
        val spansComplete = toBeMergedNodes.isEmpty() && gcPairs.size == deletePairCount

        // Count merged boundaries before their children were moved (above), so
        // the undo can regenerate them via split instead of re-inserting the
        // emptied shells. Mirrors JS SDK PR #1237.
        return TreeOperationResult(
            changes,
            gcPairs,
            diff,
            removedNodes,
            mergeLevel = toBeMergedNodes.size,
            removedSpans = if (spansComplete) removedSpans else emptyList(),
            // traverseAll is postorder (children before parent), so reverse
            // to get parent-before-child — the order restore() needs to
            // recreate a purged subtree top-down (a child's recreate
            // resolves its parent by identity).
            insertedSpans = if (spansComplete) insertedSpans.asReversed() else emptyList(),
            insertedContentSize = insertedContentSize,
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

        // Boundary.Range (port 5c158690): see the matching comment in style().
        val (from, diffFrom) = findNodesAndSplitText(range.first, executedAt, Boundary.Range)
        val (fromParent, fromLeftRaw) = from
        val (to, diffTo) = findNodesAndSplitText(range.second, executedAt, Boundary.Range)
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
        // Widened to GCPair<*>: drained pending pairs below are
        // GCPair<CrdtTreeNode>, a different type parameter than the
        // GCPair<RhtNode> attribute pairs added by this loop.
        val gcPairs = mutableListOf<GCPair<*>>()
        val prevAttributes = mutableMapOf<String, String>()
        var capturedPrev = false
        val shouldSkipToken = styleSkipPredicate(range.second, versionVector)
        traverseInPosRange(fromParent, fromLeft, toParent, toLeft) { (node, tokenType), _ ->
            val actorID = node.createdAt.actorID
            val clientLamportAtChange = getClientInfoForChange(actorID, versionVector)

            if (node.canStyle(
                    executedAt,
                    clientLamportAtChange,
                ) && attributeToRemove.isNotEmpty()
            ) {
                if (shouldSkipToken(node, tokenType)) {
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
        // This remove-style operation's boundary splits (findNodesAndSplitText)
        // can land inside an already-tombstoned node and buffer a born-dead
        // piece; drain it so it is not left unregistered for GC.
        gcPairs.addAll(drainPendingGcPairs())
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

    /**
     * Registers [node] under its [CrdtTreeNode.id] in [nodeMapByID], keeping
     * a live node over a tombstone when two nodes claim the same ID (a
     * document whose history re-inserted a deleted copy under its original
     * ID, port 2ed28322). A refused node stays reachable via tree traversal,
     * just not via lookup. Same-state pairs (both live or both removed)
     * stay last-registered-wins — element-split delimiter IDs legitimately
     * collide.
     */
    fun registerNode(node: CrdtTreeNode) {
        val entry = nodeMapByID.floorEntry(node.id)
        if (entry != null &&
            entry.value !== node &&
            entry.key == node.id &&
            node.isRemoved &&
            !entry.value.isRemoved
        ) {
            return
        }
        nodeMapByID[node.id] = node
    }

    /**
     * Filters [contents] before they are spliced into the tree at
     * [editedAt]: a content subtree whose ID was already claimed by a
     * DIFFERENT change (an earlier change, another actor, or an ID this
     * same edit's own split is about to create) is dropped as a whole
     * subtree — silently, never throwing, since such a change may already
     * be part of a stored history. A subtree whose reused ID belongs to
     * THIS SAME change/actor is kept: element-split delimiter IDs are
     * simulated (not carried on the wire pre-field-11), so they
     * legitimately collide with this edit's own content. Port 2ed28322.
     */
    fun dropDuplicateContents(
        contents: List<CrdtTreeNode>,
        editedAt: TimeTicket,
    ): List<CrdtTreeNode> {
        return contents.filterNot { content ->
            var reusedID: CrdtTreeNodeID? = null
            traverseAll(content) { node, _ ->
                if (node.id.createdAt.lamport == editedAt.lamport &&
                    node.id.createdAt.actorID == editedAt.actorID
                ) {
                    return@traverseAll
                }
                val entry = nodeMapByID.floorEntry(node.id)
                if (entry != null && entry.key == node.id) {
                    reusedID = node.id
                }
            }
            reusedID?.let { id ->
                logError(TAG) {
                    "dropping content subtree rooted at ${content.id}: " +
                        "$id is already registered by another change"
                }
            }
            reusedID != null
        }
    }

    /**
     * Finds [TreePos] of the given [pos] and splits the text node if necessary.
     *
     * [CrdtTreePos] is a position in the CRDT perspective. This is
     * different from [TreePos] which is a position of the tree in the local perspective.
     *
     * If [executedAt] is given, then it is used to find the appropriate left node
     * for concurrent insertion.
     *
     * [boundary] selects how a position inside a merged-away parent resolves
     * — see [Boundary].
     */
    fun findNodesAndSplitText(
        pos: CrdtTreePos,
        executedAt: TimeTicket? = null,
        boundary: Boundary = Boundary.Insert,
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
            // §9.3 Range Boundary at Merged-Away Anchors (port 5c158690): a
            // range boundary resolves to the position right after the
            // merge-source tombstone, not the insertion boundary below. The
            // insertion boundary sits before the first moved child, so it
            // would extend a style range over nodes concurrently inserted
            // between the tombstone and the moved children — nodes the
            // styling client saw outside its range (after the then-live
            // parent).
            if (boundary == Boundary.Range && realParent.parent != null) {
                return Pair(
                    first = Pair(realParent.parent!!, realParent),
                    second = diff,
                )
            }
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
     * Follows the [CrdtTreeNode.mergedInto] forwarding chain from [node]
     * while the current node is a merge-away tombstone, returning the final
     * live target. When a merge lands on a parent that a prior concurrent
     * merge already merged away (a chained merge P->Q->R, applied Q->R
     * before this P->Q), the children must flow to that parent's final
     * destination so the merge chain stays flat (P->R, not P->Q) and both
     * replicas converge. The `seen` set guards against a cycle from a
     * concurrent mutual merge (port b2e66114).
     */
    private fun resolveMergeTarget(node: CrdtTreeNode): CrdtTreeNode {
        var target = node
        val seen = mutableSetOf(target)
        while (true) {
            if (!target.isRemoved) break
            val mergedInto = target.mergedInto ?: break
            val next = findFloorNode(mergedInto) ?: break
            if (next in seen) break
            seen.add(next)
            target = next
        }
        return target
    }

    /**
     * Builds the interloper judgment for a style/removeStyle range whose
     * end anchor ([pos]) is declared inside a parent a concurrent merge
     * tombstoned (port 1c033ff5, mirrors yorkie#1928): fires only when the
     * declared end parent is a removed, merged-into tombstone sitting
     * DIRECTLY under its merge target and the styling client's
     * [versionVector] does not yet know about that removal. Returns null
     * when the guard does not apply (ordinary range, or the removal is
     * already known — [advancePastUnknownSplitSiblings]-style redirects
     * already cover the known case).
     *
     * The returned [MergedAnchorInterloperGuard.isInterloper] judges a node
     * by its highest ancestor still under the merge target (so a moved
     * subtree's descendants are judged as one unit) and fails open on any
     * [CrdtTreeNode.mergedFrom] stamp: the first-move rule keeps the
     * ORIGINAL source parent in the stamp even across a chained merge, so a
     * stamped node cannot be proven to be a genuine interloper by stamp
     * equality alone — only stamp-free nodes are positively judged.
     */
    private fun mergedAnchorInterloperGuard(
        pos: CrdtTreePos,
        versionVector: VersionVector?,
    ): MergedAnchorInterloperGuard? {
        if (versionVector == null) return null

        val declaredParent = pos.toTreeNodePair(this).first
        val removedAt = declaredParent.removedAt
        if (!declaredParent.isRemoved || declaredParent.mergedInto == null || removedAt == null) {
            return null
        }
        val isRemovalUnknown = versionVector.get(removedAt.actorID)
            .let { it == null || it < removedAt.lamport }
        if (!isRemovalUnknown) return null

        val target = resolveMergeTarget(declaredParent)
        if (target === declaredParent || declaredParent.parent !== target) return null

        val afterTombstone = mutableSetOf<CrdtTreeNode>()
        var passedTombstone = false
        for (child in target.allChildren) {
            if (child === declaredParent) {
                passedTombstone = true
                continue
            }
            if (passedTombstone) {
                afterTombstone.add(child)
            }
        }

        return MergedAnchorInterloperGuard { node ->
            var top = node
            while (top.parent != null && top.parent !== target) {
                top = requireNotNull(top.parent)
            }
            if (top.parent !== target) {
                false
            } else if (top.mergedFrom != null) {
                false
            } else {
                top in afterTombstone
            }
        }
    }

    /**
     * Bundles the existing End-token unknown-split-sibling skip with
     * [mergedAnchorInterloperGuard] into a single predicate [style] and
     * [removeStyle] both consult, so a token is skipped either because a
     * concurrent split extended the range unbeknownst to the editor, or
     * because it is a merge interloper the range's declared end never
     * intended to cover. Port 1c033ff5.
     */
    private fun styleSkipPredicate(
        pos: CrdtTreePos,
        versionVector: VersionVector?,
    ): (CrdtTreeNode, TokenType) -> Boolean {
        val anchorGuard = mergedAnchorInterloperGuard(pos, versionVector)
        return { node, tokenType ->
            if (tokenType == TokenType.End &&
                versionVector != null &&
                hasUnknownSplitSibling(node, versionVector)
            ) {
                true
            } else {
                anchorGuard != null && anchorGuard.isInterloper(node)
            }
        }
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
        // Guarded purge (port 2ed28322): only remove the map entry [node]
        // actually holds — an unconditional remove would unregister a
        // different live node sharing this ID.
        val entry = nodeMapByID.floorEntry(node.id)
        if (entry != null && entry.value === node && entry.key == node.id) {
            nodeMapByID.remove(node.id)
        }

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
     * Builds the [TreeRestoreSpan] for [node] at the point it is deleted or
     * inserted: its structure/value/attributes plus the external boundary
     * anchors ([TreeRestoreSpan.leftSiblingID]/[TreeRestoreSpan.rightSiblingID])
     * captured from its CURRENT physical siblings. Shared by [edit]'s delete
     * and insert phases (port fa6cc513).
     */
    private fun captureRestoreSpan(node: CrdtTreeNode): TreeRestoreSpan {
        val parent = node.parent
        val siblings = parent?.allChildren
        val index = siblings?.indexOf(node) ?: -1
        val leftSiblingID = siblings?.takeIf { index > 0 }?.get(index - 1)?.let(::leftAnchorID)
        val rightSiblingID = siblings
            ?.takeIf { index in 0 until siblings.size - 1 }
            ?.get(index + 1)
            ?.id
        return TreeRestoreSpan(
            id = node.id,
            nodeType = node.type,
            isText = node.isText,
            length = if (node.isText) node.value.length else 0,
            value = if (node.isText) node.value else null,
            attrs = node.getAttrs().deepCopy(),
            parentID = parent?.id,
            leftSiblingID = leftSiblingID,
            rightSiblingID = rightSiblingID,
        )
    }

    /**
     * Re-establishes the nodes described by [spans] under their ORIGINAL
     * identities (identity-preserving Tree undo): live -> skip (idempotent),
     * tombstoned -> [CrdtTreeNode.unremove] in place, purged ->
     * [recreateFromSpan]. [spans] must be in parent-before-child order
     * ([edit] captures them that way).
     *
     * Returns [TreeRestoreResult]:
     * - `untombstoned`: nodes revived in place (caller unregisters their GC
     *   pairs);
     * - `recreated`: brand-new nodes rebuilt for purged ranges (caller adds
     *   their size to live);
     * - `pendingGcPairs`: pending GC pairs for born-removed remainders split
     *   off a removed straddler (caller registers them BEFORE unregistering
     *   the untombstoned nodes);
     * - `diff`: the metadata overhead of splitting live straddlers (caller
     *   `acc`s it to live).
     *
     * A text piece may straddle a span boundary (a concurrent op, or a
     * post-GC recreate, can leave pieces whose boundaries do not line up
     * with the span). [isolateTextRange] splits the exact `[start, end)`
     * sub-range out of every overlapping piece — at the span boundaries,
     * live or removed — so all replicas converge on identical text-node
     * segmentation, instead of skipping the straddler.
     */
    fun restore(spans: List<TreeRestoreSpan>): TreeRestoreResult {
        val untombstoned = mutableListOf<CrdtTreeNode>()
        val recreated = mutableListOf<CrdtTreeNode>()
        var diff = DataSize(data = 0, meta = 0)

        for (span in spans) {
            if (!span.isText) {
                val node = findFloorNode(span.id)
                if (node != null && node.id == span.id) {
                    if (node.isRemoved) {
                        node.unremove()
                        untombstoned.add(node)
                    }
                    continue
                }
                recreateFromSpan(span, span.id.offset, span.length)?.let(recreated::add)
                continue
            }

            // Text: surviving pieces may be split finer than the span.
            val start = span.id.offset
            val end = start + span.length
            val pieces = findPiecesOverlapping(span.id.createdAt, start, end)

            var cursor = start
            var pieceIndex = 0
            while (cursor < end) {
                val piece = pieces.getOrNull(pieceIndex)
                val pieceStart = piece?.id?.offset ?: Int.MAX_VALUE
                val pieceEnd = if (piece != null) pieceStart + piece.value.length else Int.MAX_VALUE

                if (piece != null && pieceStart <= cursor) {
                    val overlapEnd = minOf(pieceEnd, end)
                    val (target, splitDiff) = isolateTextRange(piece, cursor, overlapEnd)
                    diff = addDataSizes(diff, splitDiff)
                    if (target.isRemoved) {
                        target.unremove()
                        untombstoned.add(target)
                    }
                    cursor = overlapEnd
                    if (overlapEnd >= pieceEnd) pieceIndex++
                } else {
                    val gapEnd = minOf(pieceStart, end)
                    recreateFromSpan(span, cursor, gapEnd - cursor)?.let(recreated::add)
                    cursor = gapEnd
                }
            }
        }

        // Splitting a removed straddler buffers born-removed remainders as
        // pending GC pairs (see CrdtTreeNode.split). The caller registers
        // these BEFORE unregistering the untombstoned targets, so a target
        // that was itself a split-born piece is walked gc->live correctly
        // (mirrors the Text path).
        val pairs = drainPendingGcPairs()
        return TreeRestoreResult(untombstoned, recreated, pairs, diff)
    }

    /**
     * Splits [piece] so that a node exactly covering the absolute-offset
     * interval `[from, to)` of its insertion exists, and returns it along
     * with the net metadata-size overhead the split(s) introduced.
     *
     * Splitting at the caller's boundaries — rather than skipping a piece
     * that straddles them — is what lets concurrent restores/retombstones
     * converge on the same text-node segmentation across replicas (the tree
     * analogue of [RgaTreeSplit]'s `isolateRange`). A live split's overhead
     * is a normal live-bucket cost the caller accumulates into its own
     * `diff`; a removed split buffers a pending GC pair internally via
     * [CrdtTreeNode.split] (contributing zero here) — the caller must still
     * drain and register those pairs.
     *
     * Requires `pieceStart <= from < to <= pieceEnd`.
     */
    private fun isolateTextRange(
        piece: CrdtTreeNode,
        from: Int,
        to: Int,
    ): Pair<CrdtTreeNode, DataSize> {
        var diff = DataSize(data = 0, meta = 0)
        var node = piece
        if (from > node.id.offset) {
            val (right, splitDiff) = node.split(this, from - node.id.offset)
            diff = addDataSizes(diff, splitDiff)
            node = requireNotNull(right)
        }
        if (to < node.id.offset + node.value.length) {
            val (_, splitDiff) = node.split(this, to - node.id.offset)
            diff = addDataSizes(diff, splitDiff)
        }
        return node to diff
    }

    /**
     * Re-deletes the nodes described by [spans] (redo of an
     * identity-preserving undo). Live pieces only; idempotent. A piece that
     * straddles a span boundary is split at that boundary via
     * [isolateTextRange] so only the in-span range is re-removed (symmetric
     * with [restore]'s isolate, so undo/redo stay mirror images and
     * segmentation stays convergent). Returns the GC pairs for the newly
     * tombstoned nodes and the live-split metadata overhead.
     */
    fun retombstone(
        spans: List<TreeRestoreSpan>,
        executedAt: TimeTicket,
    ): Pair<List<GCPair<CrdtTreeNode>>, DataSize> {
        val pairs = mutableListOf<GCPair<CrdtTreeNode>>()
        var diff = DataSize(data = 0, meta = 0)
        for (span in spans) {
            val start = span.id.offset
            // Upstream-inherited quirk (JS Math.max(span.length, 1)): a
            // zero-length text span would isolate and re-remove one character
            // past the span. Kept byte-parallel with JS 7b2ab7a4; such spans
            // are not capturable by edit() today.
            val end = start + maxOf(span.length, 1)
            val pieces = if (span.isText) {
                findPiecesOverlapping(span.id.createdAt, start, end)
            } else {
                listOfNotNull(findFloorNode(span.id)?.takeIf { it.id == span.id })
            }
            for (piece in pieces) {
                if (piece.isRemoved) continue
                var target = piece
                if (piece.isText) {
                    val from = maxOf(piece.id.offset, start)
                    val to = minOf(piece.id.offset + piece.value.length, end)
                    val (isolated, splitDiff) = isolateTextRange(piece, from, to)
                    target = isolated
                    diff = addDataSizes(diff, splitDiff)
                }
                if (target.remove(executedAt)) {
                    pairs.add(GCPair(this, target))
                }
            }
        }
        return pairs to diff
    }

    /**
     * Collects surviving pieces (live or tombstoned) of the text insertion
     * [createdAt] overlapping `[start, end)`, in ascending offset order, via
     * descending floor probes.
     */
    private fun findPiecesOverlapping(
        createdAt: TimeTicket,
        start: Int,
        end: Int,
    ): List<CrdtTreeNode> {
        val pieces = mutableListOf<CrdtTreeNode>()
        var probe = end - 1
        while (probe >= 0) {
            val node = findFloorNode(CrdtTreeNodeID(createdAt, probe)) ?: break
            if (!node.isText) break
            val nodeStart = node.id.offset
            val nodeEnd = nodeStart + node.value.length
            if (nodeEnd <= start) break
            if (nodeStart < end && nodeEnd > start) pieces.add(node)
            if (nodeStart <= start) break
            probe = nodeStart - 1
        }
        return pieces.reversed()
    }

    /**
     * Rebuilds a purged node (or purged text sub-range) under its original
     * identity and attaches it. Anchor ladder, each rung a floor-lookup +
     * parent-identity check:
     *  (a) same-insertion successor/predecessor piece (text) -> exact slot;
     *  (b) captured [TreeRestoreSpan.leftSiblingID], still parented under
     *      this parent -> after it;
     *  (c) captured [TreeRestoreSpan.rightSiblingID], still parented under
     *      this parent -> before it;
     *  (d) deterministic id-order slot: first index in the parent's current
     *      children whose id compares greater than the node's id (pure
     *      function of ids -> identical on every replica).
     * Parent genuinely absent (purged, and not part of this undo's spans)
     * -> returns null: the node stays unplaced/invisible; convergent,
     * because every replica resolves parent-absent identically.
     */
    private fun recreateFromSpan(
        span: TreeRestoreSpan,
        offset: Int,
        length: Int,
    ): CrdtTreeNode? {
        val parent = span.parentID?.let(::findFloorNode)
        if (parent == null || parent.id != span.parentID) {
            return null
        }

        val node = if (span.isText) {
            val spanValue = requireNotNull(span.value)
            val relativeOffset = offset - span.id.offset
            CrdtTreeNode.CrdtTreeText(
                CrdtTreeNodeID(span.id.createdAt, offset),
                spanValue.substring(relativeOffset, relativeOffset + length),
            )
        } else {
            CrdtTreeNode.CrdtTreeElement(
                span.id,
                span.nodeType,
                attributes = span.attrs?.deepCopy() ?: Rht(),
            )
        }

        val siblings = parent.allChildren

        // (a) same-insertion successor / predecessor piece (text): exact slot.
        if (span.isText) {
            val succ = findFloorNode(CrdtTreeNodeID(span.id.createdAt, offset + length))
            if (succ != null &&
                succ.isText &&
                succ.parent === parent &&
                succ.id.offset == offset + length
            ) {
                parent.insertAt(siblings.indexOf(succ), node)
                registerNode(node)
                return node
            }
            if (offset > span.id.offset || offset > 0) {
                val pred = findFloorNode(CrdtTreeNodeID(span.id.createdAt, offset - 1))
                if (pred != null && pred.isText && pred.parent === parent) {
                    parent.insertAfter(pred, node)
                    registerNode(node)
                    return node
                }
            }
        }

        // (b) captured left boundary sibling, if it still exists under this parent.
        val left = span.leftSiblingID?.let(::findFloorNode)
        if (left != null && left.parent === parent) {
            parent.insertAfter(left, node)
            registerNode(node)
            return node
        }

        // (c) captured right boundary sibling (redundant anchor): insert before it.
        val right = span.rightSiblingID?.let(::findFloorNode)
        if (right != null && right.parent === parent) {
            parent.insertAt(siblings.indexOf(right), node)
            registerNode(node)
            return node
        }

        // (d) deterministic id-order fallback: first slot whose child id > node id.
        val insertIndex = siblings.indexOfFirst { it.id > node.id }
            .let { if (it == -1) siblings.size else it }
        parent.insertAt(insertIndex, node)
        registerNode(node)
        return node
    }

    /**
     * Returns the id to store as a restore span's left-sibling anchor. For a
     * text node the anchor is its LAST character's offset, not its start: a
     * concurrent delete may later split the left neighbor, and only the
     * last-char offset floor-resolves to the rightmost surviving fragment
     * (the true left neighbor of the restored node). For elements (never
     * split by offset) the node's own id is exact. Right-sibling anchors
     * always use the start offset, which floor-resolves to the leftmost
     * fragment — the true right neighbor.
     */
    private fun leftAnchorID(sibling: CrdtTreeNode): CrdtTreeNodeID {
        if (!sibling.isText) return sibling.id
        return CrdtTreeNodeID(sibling.id.createdAt, sibling.id.offset + sibling.value.length - 1)
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

    companion object {
        private const val TAG = "CrdtTree"
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
        // Only reached when a root is built from a snapshot. Removed
        // attribute nodes are skipped by dataSize, so they were never
        // counted into docSize.live — hence gcOnlySize.
        get() = _attributes
            .filter { node -> node.removedAt != null }
            .map { node -> GCPair(this, node, gcOnlySize = node.dataSize) }

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
     * Clones this text node with the given [offset], carrying [mergedFrom] and
     * [mergedAt] so the split product keeps the merge stamp of the moved node
     * it came from.
     */
    override fun cloneText(offset: Int): CrdtTreeNode {
        return clone(offset, id.createdAt).apply {
            mergedFrom = this@CrdtTreeNode.mergedFrom
            mergedAt = this@CrdtTreeNode.mergedAt
        }
    }

    /**
     * Clones this element node with the given [issueTimeTicket] function.
     * The split product holds the other half of the same moved node, so it
     * carries the same merge stamp (as [cloneText] does).
     */
    override fun cloneElement(issueTimeTicket: () -> TimeTicket): CrdtTreeNode {
        return clone(0, issueTimeTicket()).apply {
            mergedFrom = this@CrdtTreeNode.mergedFrom
            mergedAt = this@CrdtTreeNode.mergedAt
        }
    }

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

        // A piece split off an already-tombstoned node inherits removedAt
        // without going through remove(), so no GC pair is created for it in
        // the normal deletion path. Register it here so it can be purged;
        // otherwise it stays in the tree forever. The piece was never live,
        // so its size goes straight to docSize.gc when the pair is
        // registered; report a zero diff to the caller (which accounts
        // diffs to docSize.live).
        if (split != null && split.removedAt != null) {
            tree.registerPendingGcPair(split, diff)
            return Pair(split, DataSize(data = 0, meta = 0))
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
     * Clears the tombstone of this node (identity-preserving restore).
     * Mirrors [remove]'s ancestor-size bookkeeping so the node becomes
     * visible again in place. No-op when the node is not removed.
     *
     * [IndexTreeNode.onRemovedListener] only ever wires the forward
     * (live -> removed) transition — see [IndexTreeNodeList.onUnremoved] —
     * so the parent's cached active-children list is refreshed explicitly
     * here via the CURRENT [parent] reference (never a stale one: a prior
     * [moveChild] already updated [parent] before this can run).
     */
    fun unremove() {
        if (removedAt == null) return
        removedAt = null
        updateAncestorSize(paddedSize())
        parent?.childNodes?.onUnremoved(this)
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
