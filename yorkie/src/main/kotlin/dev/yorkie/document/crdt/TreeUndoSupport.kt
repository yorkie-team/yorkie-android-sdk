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
