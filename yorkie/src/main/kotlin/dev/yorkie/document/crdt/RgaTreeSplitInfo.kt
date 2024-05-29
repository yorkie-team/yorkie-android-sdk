@file:Suppress("ktlint:standard:filename")

package dev.yorkie.document.crdt

import dev.yorkie.document.time.ActorID
import dev.yorkie.document.time.TimeTicket

internal data class RgaTreeSplitEditResult<T : RgaTreeSplitValue<T>>(
    val pos: RgaTreeSplitPos,
    val maxCreatedAtMap: Map<ActorID, TimeTicket>,
    val changes: List<RgaTreeSplit.ContentChange>,
    val gcPairs: List<GCPair<RgaTreeSplitNode<T>>>,
)
