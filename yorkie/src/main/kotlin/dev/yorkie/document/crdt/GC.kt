package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize

/**
 * [GCPair] is a structure that represents a pair of parent and child for garbage
 * collection.
 *
 * [gcOnlySize] is set when [child]'s size was never counted in `docSize.live`:
 * a piece born already-removed by splitting an already-tombstoned node, or a
 * tombstone registered by the full snapshot-load scan (which only counts
 * visible nodes into live). When present, [CrdtRoot.registerGCPair] adds this
 * size to `docSize.gc` and leaves `docSize.live` untouched, instead of moving
 * [child]'s size from live to gc.
 */
internal data class GCPair<T : GCChild>(
    val parent: GCParent<T>,
    val child: T,
    val gcOnlySize: DataSize? = null,
)

/**
 * [GCParent] is an interface for the parent of the garbage collection target.
 */
internal interface GCParent<T : GCChild> {

    fun delete(node: T)

    @Suppress("UNCHECKED_CAST")
    fun deleteChild(node: GCChild) {
        delete(node as T)
    }
}

/**
 * [GCChild] is an interface for the child of the garbage collection target.
 */
internal sealed interface GCChild {
    val removedAt: TimeTicket?
    val dataSize: DataSize
}

internal sealed interface GCCrdtElement {
    val gcPairs: List<GCPair<*>>
}
