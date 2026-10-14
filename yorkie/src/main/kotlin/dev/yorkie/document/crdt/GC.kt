package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.subDataSize

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

    /**
     * [purgeBarrierAt] is an optional capability of a GC parent whose
     * surviving order is decided by which nodes are still linked.
     *
     * Every such container resolves a concurrent insert by walking forward
     * from the anchor and stopping at the first node whose positioning
     * ticket does not follow the insert: [RgaTreeList]'s
     * `findNextBeforeExecutedAt`, the skip in [RgaTreeSplit]'s
     * `findNodeWithSplit`, and the sibling skip in [CrdtTree]'s
     * `findNodesAndSplitText`. The walk reads the nodes currently linked,
     * tombstones included, so a tombstone with a small ticket is a hard
     * barrier that ends the walk. Purging it physically unlinks it, which
     * means collection mutates the input to the insertion rule: a replica
     * that has collected sends a still-in-flight insert past the node behind
     * the tombstone, a replica that has not does not, and the two orders
     * never reconverge.
     *
     * [GCChild.removedAt] alone does not authorise the unlink. What does is
     * the node that would become the walk's new stopping point: once that
     * node is causally stable, every future insert carries a ticket after
     * it, so every future walk stops there whether or not the tombstone in
     * front of it still exists. This returns that successor's positioning
     * ticket, which [CrdtRoot.garbageCollect] requires the version vector to
     * cover as well as [GCChild.removedAt], or `null` when [node] has no
     * successor (or is not a child of this parent's kind) and unlinking it
     * cannot move anything.
     *
     * Declared over [GCChild] (not the generic [T]) because the only caller
     * ([CrdtRoot]'s collection pass) only ever holds a type-erased
     * `GCPair<*>` — the same reason [deleteChild] exists beside [delete].
     * Each override narrows [node] with an `is`/`as?` check, mirroring JS's
     * own `instanceof` dispatch.
     *
     * Mirrors Go's `crdt.GCBarrier` (yorkie `ba82ed91`); ported from JS
     * `GCParent.purgeBarrierAt` (yorkie-js-sdk `dbff8892`, #1405).
     */
    fun purgeBarrierAt(node: GCChild): TimeTicket? = null
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

/**
 * Builds the GC pair for one RHT tombstone [child] a `style`/`removeStyle`
 * mints on [parent]'s attribute table. [attrWasLive] records whether
 * [child]'s key held a live value right before this write; [nodeIsLive]
 * records whether [parent] itself is still visible.
 *
 * A live attribute on a live node is debited from `live` normally (no
 * [GCPair.gcOnlySize]). Otherwise the tombstone was never counted in `live`:
 * a live attribute on an already-REMOVED node took its size out of `live`
 * already via the node's own removal, so this pair carries a zero
 * [GCPair.gcOnlySize] (nothing further to move); a tombstone minted over an
 * absent key, or superseding an already-tombstoned one, carries [child]'s
 * own size as its [GCPair.gcOnlySize] (it was gc from the start). Mirrors JS
 * `attrGCPair` (`crdt/tree.ts:926-940`), shared by [CrdtText] and [CrdtTree]
 * — both [CrdtTreeNode] and `TextValue` implement [GCParent] of [RhtNode].
 * Widened to the 4-input shape `canStyle` needs since #1368, which made
 * `canStyle` admit a removed node unconditionally rather than only while
 * this write is newer than the removal.
 */
internal fun attrGcPair(
    parent: GCParent<RhtNode>,
    child: RhtNode,
    attrWasLive: Boolean,
    nodeIsLive: Boolean,
): GCPair<RhtNode> = if (attrWasLive && nodeIsLive) {
    GCPair(parent, child)
} else {
    GCPair(parent, child, gcOnlySize = if (attrWasLive) DataSize(0, 0) else child.dataSize)
}

/**
 * Folds one [RhtWrite] into [size], mirroring JS `tree.ts:950-976`'s shared
 * attribute-write booking (`accAttrWrite`) — the ledger half of #1365/#1368:
 * a write to an RHT attribute table books via what [Rht.set] reported, not
 * by re-reading the map afterwards.
 *
 * If [write] revived a tombstone, [pairs] receives the pair that cancels its
 * earlier gc registration (the SAME object, so [CrdtRoot.registerGCPair]'s
 * identity-keyed toggle finds it). [target] is [DocSize.live] when
 * [nodeIsLive], else [DocSize.gc] — a write landing on an already-removed
 * node never touches live. If the write superseded a live node, that node's
 * size leaves [target] (RHT overrides immutably: no tombstone, nothing to
 * collect, but its bytes were counted and have to go). If the write
 * installed a node, its size enters [target].
 *
 * Returns the updated [DocSize] for the caller to fold forward — Kotlin has
 * no mutable-struct aliasing, so [size] itself is never mutated.
 */
internal fun accAttrWrite(
    write: RhtWrite,
    parent: GCParent<RhtNode>,
    nodeIsLive: Boolean,
    pairs: MutableList<GCPair<*>>,
    size: DocSize,
): DocSize {
    var result = size
    write.revived?.let { revived ->
        pairs.add(attrGcPair(parent, revived, attrWasLive = false, nodeIsLive = nodeIsLive))
    }
    write.superseded?.let { superseded ->
        result = if (nodeIsLive) {
            result.copy(live = subDataSize(result.live, superseded.dataSize))
        } else {
            result.copy(gc = subDataSize(result.gc, superseded.dataSize))
        }
    }
    write.installed?.let { installed ->
        result = if (nodeIsLive) {
            result.copy(live = addDataSizes(result.live, installed.dataSize))
        } else {
            result.copy(gc = addDataSizes(result.gc, installed.dataSize))
        }
    }
    return result
}
