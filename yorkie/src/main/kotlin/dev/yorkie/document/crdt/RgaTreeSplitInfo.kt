@file:Suppress("ktlint:standard:filename")

package dev.yorkie.document.crdt

import dev.yorkie.util.DataSize

internal data class RgaTreeSplitEditResult<T : RgaTreeSplitValue<T>>(
    val pos: RgaTreeSplitPos,
    val changes: List<RgaTreeSplit.ContentChange>,
    val gcPairs: List<GCPair<RgaTreeSplitNode<T>>>,
    val dataSize: DataSize,
    val removedValues: List<T> = emptyList(),
    val removedSpans: List<RestoreSpan<T>> = emptyList(),
)

/**
 * Result of [RgaTreeSplit.restore]. [untombstoned] and [recreated] nodes are
 * disjoint and exhaustively describe the effect of the restore: a live piece
 * in the span is skipped and appears in neither list.
 */
internal data class RgaTreeSplitRestoreResult<T : RgaTreeSplitValue<T>>(
    val untombstoned: List<RgaTreeSplitNode<T>>,
    val recreated: List<RgaTreeSplitNode<T>>,
    val changes: List<RgaTreeSplit.ContentChange>,
    val liveDiff: DataSize,
    val pendingGcPairs: List<GCPair<RgaTreeSplitNode<T>>>,
)

/**
 * Result of [RgaTreeSplit.retombstone]. [recorded] names the pieces the
 * removal was actually recorded on (a subset of the input spans: a piece
 * already removed in the redo's view, or purged, records nothing and is
 * absent here) -- the reverse of this op names only these.
 */
internal data class RgaTreeSplitRetombstoneResult<T : RgaTreeSplitValue<T>>(
    val gcPairs: List<GCPair<RgaTreeSplitNode<T>>>,
    val changes: List<RgaTreeSplit.ContentChange>,
    val dataSize: DataSize,
    val recorded: List<RestoreSpan<T>> = emptyList(),
)
