@file:Suppress("ktlint:standard:filename")

package dev.yorkie.document.crdt

internal data class RgaTreeSplitEditResult<T : RgaTreeSplitValue<T>>(
    val pos: RgaTreeSplitPos,
    val changes: List<RgaTreeSplit.ContentChange>,
    val gcPairs: List<GCPair<RgaTreeSplitNode<T>>>,
)
