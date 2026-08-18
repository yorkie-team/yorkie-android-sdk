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
 * Result of [RgaTreeSplit.retombstone].
 */
internal data class RgaTreeSplitRetombstoneResult<T : RgaTreeSplitValue<T>>(
    val gcPairs: List<GCPair<RgaTreeSplitNode<T>>>,
    val changes: List<RgaTreeSplit.ContentChange>,
    val dataSize: DataSize,
)
