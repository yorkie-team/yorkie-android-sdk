@file:Suppress("ktlint:standard:filename")

package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.Logger.Companion.logDebug

/**
 * A content change an identity-preserving [CrdtTree.restore] or
 * [CrdtTree.retombstone] made, paired with the visible size it inserted
 * (zero for a deletion). The change alone does not carry that size — an
 * insertion reports a collapsed range — and undo-stack reconciliation
 * cannot shift the pending indices without it. Port yorkie-js-sdk
 * `790422ce` (#1421).
 */
internal data class TreeVisibleEdit(
    val change: TreeChange,
    val insertedSize: Int,
)

/**
 * The index range a node currently occupies and the paths to its
 * boundaries — the Kotlin shape of [visibleRangeOf]'s JS 4-tuple return.
 * Internal (not private) because [CrdtTree.retombstone] -- in a different
 * file in this package -- reads a non-collapsed range directly, where
 * [makeInsertionChange]'s collapsed-insertion shape does not apply.
 */
internal data class VisibleRange(
    val from: Int,
    val to: Int,
    val fromPath: List<Int>,
    val toPath: List<Int>,
)

/**
 * Returns the index range [node] currently occupies and the paths to its
 * boundaries, or null when [node] or an ancestor is removed (no room in the
 * index) or [node] is parentless (e.g. the tree root).
 *
 * [CrdtTree.toIndex]/[CrdtTree.indexToPath] throw on a tree they cannot walk
 * ("invalid pos", "unacceptable path"). Both run here inside
 * [dev.yorkie.document.operation.TreeEditOperation.execute], which for a
 * remote pack is driven from `Change.execute` off spans that arrived from
 * the wire: a throw there aborts the pack, the checkpoint never advances,
 * and the document is wedged for good on every replica that receives it.
 * Reporting the range is a courtesy to subscribers, not part of convergence
 * — the CRDT state is already settled by the time we measure — so a tree we
 * cannot measure degrades to "no position reported", exactly as a node
 * under a removed ancestor does above. Port yorkie-js-sdk `790422ce`
 * (#1421).
 */
internal fun CrdtTree.visibleRangeOf(node: CrdtTreeNode): VisibleRange? {
    var ancestor: CrdtTreeNode? = node
    while (ancestor != null) {
        if (ancestor.isRemoved) return null
        ancestor = ancestor.parent
    }
    val parent = node.parent ?: return null

    return try {
        val to = toIndex(parent, node)
        val from = to - node.paddedSize()
        VisibleRange(from, to, indexToPath(from), indexToPath(to))
    } catch (e: RuntimeException) {
        logDebug("CrdtTree", "failed to measure restored node: $e")
        null
    }
}

/**
 * Describes [node] becoming visible as an insertion at its current
 * position. A node still hidden under a removed ancestor, or one whose
 * position cannot be measured, produces nothing. Port yorkie-js-sdk
 * `790422ce` (#1421).
 */
internal fun CrdtTree.makeInsertionChange(
    node: CrdtTreeNode,
    editedAt: TimeTicket,
): TreeVisibleEdit? {
    val range = visibleRangeOf(node) ?: return null
    return TreeVisibleEdit(
        change = TreeChange(
            actorID = editedAt.actorID,
            type = TreeChangeType.Content,
            from = range.from,
            to = range.from,
            fromPath = range.fromPath,
            toPath = range.fromPath,
            value = listOf(node.toTreeNode()),
        ),
        // The change reports a collapsed range, as every insertion does, so
        // the size it added is not readable from it; reconciliation needs it.
        insertedSize = range.to - range.from,
    )
}

/**
 * Returns [id] re-pointed from the node [prev] names to the node [curr]
 * names, or [id] itself when it names a different node.
 *
 * The match is on the creation ticket ALONE, and the offset is carried over
 * from [id] rather than taken from [curr]: a node id names a node by its
 * creation ticket, while the offset says WHERE in that node the reference
 * lands -- the child index when a position names an element as its left
 * sibling, or the character offset inside a text node. Comparing the offset
 * too, as [CrdtTreeNodeID]'s data-class equality does, would miss every
 * reference that is not to the node's own head. Port yorkie-js-sdk
 * `b173c093` (#1426).
 */
internal fun replaceTreeNodeId(
    id: CrdtTreeNodeID,
    prev: CrdtTreeNodeID,
    curr: CrdtTreeNodeID,
): CrdtTreeNodeID {
    if (id.createdAt != prev.createdAt) return id
    return CrdtTreeNodeID(curr.createdAt, id.offset)
}

/**
 * Returns this position with [prev] replaced by [curr] wherever it names
 * the parent or the left sibling, or this position itself when it names
 * neither. Used when a split re-creates an element a merge removed under a
 * new id, so an operation recorded against the removed element's old id
 * follows it to the replacement (the tree counterpart of
 * [dev.yorkie.document.history.History.reconcileCreatedAt]). Port
 * yorkie-js-sdk `b173c093` (#1426).
 */
internal fun CrdtTreePos.replaceNodeID(prev: CrdtTreeNodeID, curr: CrdtTreeNodeID): CrdtTreePos {
    val newParentID = replaceTreeNodeId(parentID, prev, curr)
    val newLeftSiblingID = replaceTreeNodeId(leftSiblingID, prev, curr)
    return if (newParentID == parentID && newLeftSiblingID == leftSiblingID) {
        this
    } else {
        CrdtTreePos(newParentID, newLeftSiblingID)
    }
}

/**
 * Returns the id of the removed element whose children [splitNode] -- the
 * product of a split -- has just taken back, or null when the split
 * re-creates nothing.
 *
 * A merge moves a boundary element's children into the merge target and
 * tombstones the element, stamping each moved child with [CrdtTreeNode.mergedFrom]
 * naming it. Reversing that merge is a split, which mints a BRAND-NEW
 * element -- it cannot revive the tombstone, whose id belongs to a change
 * every replica already applied -- and moves the stamped children into it.
 * So a split product holding children stamped for a removed element is the
 * replacement for that element, and anything recorded against the element
 * has to follow (#1425).
 *
 * [findFloorNode] is taken as a resolver lambda rather than called on a
 * [CrdtTree] receiver: this function needs nothing from the tree beyond
 * that one lookup, so a hand-built node graph can exercise the narrow rule
 * directly, and the real [CrdtTree.findFloorNode] never has to leave the
 * size-guarded file for this new code to reach it.
 *
 * Derived from the tree rather than carried on the operation because a peer
 * has to reach the same conclusion from the change alone: the operation
 * encodes only the split's tickets, never what they replace.
 *
 * Narrow on purpose. A split that merely happens to cut through a merge
 * target keeps some of the source's children on the left, and its product
 * is a new sibling rather than the source's stand-in -- re-pointing at it
 * would move references onto a node holding only part of what they named.
 * Only a split that takes the source's children back WHOLE reverses the
 * merge, so [splitTarget] is checked for leftovers. Port yorkie-js-sdk
 * `b173c093` (#1426).
 */
internal fun mergeSourceOf(
    splitNode: CrdtTreeNode,
    splitTarget: CrdtTreeNode,
    findFloorNode: (CrdtTreeNodeID) -> CrdtTreeNode?,
): CrdtTreeNodeID? {
    fun stampedFor(node: CrdtTreeNode, id: CrdtTreeNodeID): Boolean =
        node.allChildren.any { child -> child.mergedFrom?.createdAt == id.createdAt }

    for (child in splitNode.allChildren) {
        val mergedFrom = child.mergedFrom ?: continue
        // The product carries its own mergedFrom copied off the node it
        // split (cloneElement); a child stamped for that same element was
        // merged in one level up and is not what this split re-created.
        val splitNodeMergedFrom = splitNode.mergedFrom
        if (splitNodeMergedFrom != null && mergedFrom.createdAt == splitNodeMergedFrom.createdAt) {
            continue
        }
        val source = findFloorNode(mergedFrom)
        if (source != null &&
            source.isRemoved &&
            source.id.createdAt == mergedFrom.createdAt &&
            !stampedFor(splitTarget, source.id)
        ) {
            return source.id
        }
    }

    return null
}
