package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
import dev.yorkie.util.Logger.Companion.logError
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.subDataSize
import java.util.IdentityHashMap

/**
 * [CrdtRoot] is a structure that represents the root. It has a hash table of
 * all elements to find a specific element when applying remote changes
 * received from server.
 *
 * Every element has a unique [TimeTicket] at creation, which allows us to find
 * a particular element.
 */
internal class CrdtRoot(val rootObject: CrdtObject) {
    /**
     * A hash table that maps the creation time of an element to the element itself and its parent.
     */
    private val elementPairMapByCreatedAt =
        mutableMapOf(rootObject.createdAt to CrdtElementPair(rootObject))

    /**
     * A hash set that contains the creation time of the element that has removed nodes.
     * It is used to find the element that has removed nodes when executing garbage collection.
     */
    private val gcElementSetByCreatedAt = mutableSetOf<TimeTicket>()

    /**
     * Maps each removed [GCChild] to its [GCPair], keyed on object IDENTITY
     * (mirrors JS `gcPairMap` keyed by the child's ID string). Every
     * register/unregister/toggle site passes the same node instance, so
     * identity is exact, whereas `equals` is not: [RhtNode] is a data class
     * and one style op overwriting the same attribute on several nodes yields
     * structurally equal tombstoned copies that would toggle each other out
     * of the map; [CrdtTreeNode]'s data-class hash changes with its children
     * and attributes, so a value-keyed entry could become unreachable.
     * Purge order in [garbageCollect] does not depend on iteration order.
     */
    private val gcPairMap: MutableMap<GCChild, GCPair<*>> = IdentityHashMap()

    /**
     * Maps every element whose size counts toward [DocSize.gc] rather than
     * [DocSize.live] to the EXACT [DataSize] amount charged for it. An
     * element reaches gc by more routes than being removed itself: it can
     * also be swept in as a descendant of a removed [CrdtContainer], or be
     * registered already tombstoned (an undo's copy, a snapshot, the losing
     * side of a concurrent set). The amount, not a flag, is recorded because
     * [CrdtElement.getDataSize] is not stable — it grows by one
     * [TimeTicket.TIME_TICKET_SIZE] once [CrdtElement.removedAt] is set,
     * which can happen after the size first moves here.
     *
     * Keyed on object IDENTITY, not createdAt (yorkie-js-sdk#1350): an undo
     * restores a deep copy under the original createdAt while the original
     * is still a tombstone, so one slot per createdAt cannot hold both
     * charges. A zero amount means RELEASED: the element was orphaned by a
     * restore ([release]) and is held by neither side.
     */
    private val sizeInGC: MutableMap<CrdtElement, DataSize> = IdentityHashMap()

    /**
     * `docSize` is a structure that represents the size of the document.
     */
    var docSize: DocSize = DocSize(
        live = DataSize(
            data = 0,
            meta = 0,
        ),
        gc = DataSize(
            data = 0,
            meta = 0,
        ),
    )

    val elementMapSize
        get() = elementPairMapByCreatedAt.size

    val garbageLength: Int
        get() = getGarbageElementSetSize() + gcPairMap.size

    init {
        // Tombstones are not re-registered here: [registerElement] already
        // booked every one of them into gc (yorkie-js-sdk#1350).
        registerElement(rootObject, null)

        rootObject.getDescendants { element, _ ->
            if (element is GCCrdtElement) {
                element.gcPairs.forEach(::registerGCPair)
            }
            // Register dead position nodes in a CrdtArray so they are collected once all
            // peers have applied the winning move. Dead nodes have no element, so
            // getDescendants never yields them — register them explicitly here.
            if (element is CrdtArray) {
                element.getAllRGANodes().forEach { node ->
                    if (node.elementEntry == null && node.positionRemovedAt != null) {
                        registerGCPair(GCPair(element.getRGATreeList(), node))
                    }
                }
            }
            false
        }
    }

    /**
     * Returns the element of the given [createdAt].
     */
    fun findByCreatedAt(createdAt: TimeTicket): CrdtElement? {
        return elementPairMapByCreatedAt[createdAt]?.element
    }

    /**
     * Creates an array of the sub paths for the given element.
     */
    private fun createSubPaths(createdAt: TimeTicket): List<String> {
        var pair: CrdtElementPair = elementPairMapByCreatedAt[createdAt] ?: return emptyList()

        val subPaths = mutableListOf<String>()
        while (true) {
            val parent = pair.parent ?: break
            val currentCreatedAt = pair.element.createdAt
            val subPath = parent.subPathOf(currentCreatedAt)
            if (subPath == null) {
                logError(TAG, "fail to find the given element: $currentCreatedAt")
            } else {
                subPaths.add(0, subPath)
            }
            pair = elementPairMapByCreatedAt[parent.createdAt] ?: break
        }

        subPaths.add(0, "$")
        return subPaths
    }

    /**
     * Creates a path of the given element.
     */
    fun createPath(createdAt: TimeTicket): String {
        return createSubPaths(createdAt).joinToString(".")
    }

    /**
     * Registers [element] and its descendants to the hash table and books
     * their sizes, then books every one of them that already carries a
     * [CrdtElement.removedAt] into gc (yorkie-js-sdk#1350). An undo re-sets
     * a deep copy that keeps the members tombstoned before the container
     * was; a snapshot loads tombstones; and the losing side of a concurrent
     * set is tombstoned by [ElementRht.set] before it is registered. This
     * is the one place all of those routes pass through.
     *
     * Adoption is a second pass on purpose: adopting a tombstone moves its
     * whole subtree, and doing it during the first walk would move
     * descendants [DocSize.live] has not been charged for yet.
     */
    fun registerElement(element: CrdtElement, parent: CrdtContainer?) {
        registerLive(element, parent)
        adoptTombstones(element)
    }

    /**
     * Registers [element] and its descendants to the hash table and charges
     * [DocSize.live] for each.
     */
    private fun registerLive(element: CrdtElement, parent: CrdtContainer?) {
        elementPairMapByCreatedAt[element.createdAt] = CrdtElementPair(element, parent)
        docSize = docSize.copy(live = addDataSizes(docSize.live, element.getDataSize()))
        if (element is CrdtContainer) {
            element.getDescendants { elem, par ->
                elementPairMapByCreatedAt[elem.createdAt] = CrdtElementPair(elem, par)
                docSize = docSize.copy(live = addDataSizes(docSize.live, elem.getDataSize()))
                false
            }
        }
    }

    /**
     * Books every element of [element]'s subtree that already carries a
     * [CrdtElement.removedAt] into gc via [adoptRemovedElement].
     */
    private fun adoptTombstones(element: CrdtElement) {
        if (element.removedAt != null) {
            adoptRemovedElement(element)
        }
        if (element is CrdtContainer) {
            element.getDescendants { elem, _ ->
                if (elem.removedAt != null) {
                    adoptRemovedElement(elem)
                }
                false
            }
        }
    }

    /**
     * Moves [element]'s current size from [DocSize.live] to [DocSize.gc], or
     * tops up its charge in [sizeInGC] if it is already there. This is the
     * ONLY function that adds a size to gc, and it is idempotent: the same
     * [element] can be swept in more than once — by its own removal, and
     * again if a container above it is removed later — and each subsequent
     * call charges only the growth in [CrdtElement.getDataSize] since the
     * last charge. A released element (zero charge) is topped up in full
     * without touching live, which is no longer holding it.
     *
     * Returns whether this call moved a size [DocSize.live] was actually
     * holding — false when [element] was already charged and only topped up.
     */
    private fun moveSizeToGC(element: CrdtElement): Boolean {
        val size = element.getDataSize()
        val charged = sizeInGC[element]
        if (charged != null) {
            if (size != charged) {
                docSize = docSize.copy(
                    gc = addDataSizes(docSize.gc, subDataSize(size, charged)),
                )
                sizeInGC[element] = size
            }
            return false
        }
        docSize = docSize.copy(
            gc = addDataSizes(docSize.gc, size),
            live = subDataSize(docSize.live, size),
        )
        sizeInGC[element] = size
        return true
    }

    /**
     * Moves [element] — and, for a [CrdtContainer], every descendant — from
     * [DocSize.live] to [DocSize.gc] via [moveSizeToGC], and marks [element]
     * removed for [garbageCollect] to find later.
     *
     * [DocSize.live] gets one [TimeTicket.TIME_TICKET_SIZE] back only when
     * [moveSizeToGC] moved a size live held for [element] itself AND
     * [element] carries a [CrdtElement.removedAt]: that ticket is part of
     * the size just charged, but [registerElement] ran before it existed. An
     * element registered already tombstoned was adopted by
     * [registerElement], so a later removal of it moves nothing and gets no
     * refund.
     */
    fun registerRemovedElement(element: CrdtElement) {
        val moved = moveSizeToGC(element)
        if (element is CrdtContainer) {
            element.getDescendants { elem, _ ->
                moveSizeToGC(elem)
                false
            }
        }
        if (moved && element.removedAt != null) {
            docSize = docSize.copy(
                live = docSize.live.copy(meta = docSize.live.meta + TimeTicket.TIME_TICKET_SIZE),
            )
        }
        gcElementSetByCreatedAt.add(element.createdAt)
    }

    /**
     * Books an element that was already tombstoned when it was registered,
     * and its descendants, into gc and marks it for [garbageCollect]. It is
     * [registerRemovedElement] without the ticket refund: the size just
     * charged to live already included the [CrdtElement.removedAt] ticket.
     */
    private fun adoptRemovedElement(element: CrdtElement) {
        moveSizeToGC(element)
        if (element is CrdtContainer) {
            element.getDescendants { elem, _ ->
                moveSizeToGC(elem)
                false
            }
        }
        gcElementSetByCreatedAt.add(element.createdAt)
    }

    /**
     * Drops the [gcElementSetByCreatedAt] entry registered under [createdAt],
     * if there is one, and releases the cost of the element it resolves to
     * and its descendants. Returns whether an entry was dropped.
     *
     * Called by a set that restores an element under a createdAt a
     * tombstone already answers to (an undo of a removal). [ElementRht.set]
     * has by then re-pointed its index at the restored copy, so the stale
     * entry would resolve to live data. It runs on every replica, not only
     * the undoing one: peers apply the same undo as
     * [dev.yorkie.document.operation.OpSource.Remote].
     *
     * Deliberately narrow (yorkie-js-sdk#1341): the tombstone's descendants
     * stay in [elementPairMapByCreatedAt]. A peer may have added a member
     * into the container after the undoing replica took its copy, and
     * deregistering the subtree would evict that member, so a later change
     * addressed at it could no longer be applied.
     */
    fun unregisterRemovedElementPair(createdAt: TimeTicket): Boolean {
        if (createdAt !in gcElementSetByCreatedAt) {
            return false
        }
        val element = elementPairMapByCreatedAt[createdAt]?.element
        if (element != null) {
            release(element)
            if (element is CrdtContainer) {
                element.getDescendants { elem, _ ->
                    release(elem)
                    false
                }
            }
        }
        gcElementSetByCreatedAt.remove(createdAt)
        return true
    }

    /**
     * Forgets the cost of [element], which a restore made unreachable
     * without collecting it, via [releaseCharge], and records the release as
     * a zero charge: [element] stays addressable, so a later removal inside
     * its subtree must not take its size out of live a second time. Leaves
     * [elementPairMapByCreatedAt] alone — the slot may since belong to the
     * restored copy.
     */
    private fun release(element: CrdtElement) {
        releaseCharge(element, sizeInGC[element])
        sizeInGC[element] = DataSize(0, 0)
        if (elementPairMapByCreatedAt[element.createdAt]?.element === element) {
            gcElementSetByCreatedAt.remove(element.createdAt)
        }
    }

    /**
     * Subtracts [element]'s size from the side holding it. With no
     * [charged] amount it is still in [DocSize.live]. With a positive
     * amount, gc gives back exactly that, and live gives back the DRIFT —
     * the element's current size minus the charge — which is what edits
     * applied after the charge booked through live ([acc] for inserted or
     * styled content, [registerGCPair] for removed content). Without it, a
     * replica that removed the container first keeps those bytes in live
     * forever while a replica that edited first swept them into the charge
     * (Android-only; JS still books both into live). A zero (released)
     * charge is held by neither side.
     */
    private fun releaseCharge(element: CrdtElement, charged: DataSize?) {
        val size = element.getDataSize()
        docSize = when {
            charged == null -> docSize.copy(live = subDataSize(docSize.live, size))
            charged == DataSize(0, 0) -> docSize
            else -> docSize.copy(
                gc = subDataSize(docSize.gc, charged),
                live = subDataSize(docSize.live, subDataSize(size, charged)),
            )
        }
    }

    /**
     * Registers the given pair to hash table.
     */
    fun registerGCPair(pair: GCPair<*>) {
        val prev = gcPairMap[pair.child]
        if (prev != null) {
            gcPairMap.remove(pair.child)
            return
        }
        gcPairMap[pair.child] = pair

        pair.gcOnlySize?.let { size ->
            // The child's size was never counted in docSize.live (it was born
            // already-removed, or it was registered by the snapshot-load scan
            // where live only counts visible nodes), so there is nothing to
            // move out of live. Only the given size is added to gc; purge
            // subtracts the child's size from gc as usual.
            docSize = docSize.copy(gc = addDataSizes(docSize.gc, size))
            return
        }

        val size = pair.child.dataSize
        val docSizeLive = if (pair.child is RhtNode) {
            subDataSize(docSize.live, size)
        } else {
            val dataSize = subDataSize(docSize.live, size)
            dataSize.copy(
                meta = dataSize.meta + TimeTicket.TIME_TICKET_SIZE,
            )
        }
        val docSizeGc = addDataSizes(docSize.gc, size)
        docSize = docSize.copy(
            live = docSizeLive,
            gc = docSizeGc,
        )
    }

    /**
     * Removes the given [pair] from the hash table. Called when a
     * tombstoned node is revived (un-tombstoned) by an identity-preserving
     * undo, so that a later re-registration (redo) is not swallowed by the
     * toggle in [registerGCPair].
     *
     * Must be called AFTER the node's `removedAt` has been cleared, so
     * [GCChild.dataSize] no longer includes the tombstone ticket.
     */
    fun unregisterGCPair(pair: GCPair<*>) {
        gcPairMap[pair.child] ?: return
        gcPairMap.remove(pair.child)
        docSize = unregisterAccounting(pair)
    }

    /**
     * Mirrors [registerGCPair]'s accounting in reverse: moves the child's
     * size back from gc to live, and drops the tombstone ticket counted at
     * register time. Callers that must not touch [gcPairMap] directly (e.g.
     * an in-progress [MutableMap.values] iterator in [garbageCollect]) apply
     * this separately from the map removal.
     */
    private fun unregisterAccounting(pair: GCPair<*>): DocSize {
        val size = pair.child.dataSize
        val newLive = addDataSizes(docSize.live, size)
        val gcAfterSize = subDataSize(docSize.gc, size)
        val newGc = if (pair.child is RhtNode) {
            gcAfterSize
        } else {
            gcAfterSize.copy(meta = gcAfterSize.meta - TimeTicket.TIME_TICKET_SIZE)
        }
        return docSize.copy(live = newLive, gc = newGc)
    }

    private fun getGarbageElementSetSize(): Int {
        val seen = mutableSetOf<TimeTicket>()
        gcElementSetByCreatedAt.forEach { createdAt ->
            seen += createdAt
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            if (pair.element is CrdtContainer) {
                pair.element.getDescendants { element, _ ->
                    seen += element.createdAt
                    false
                }
            }
        }
        return seen.size
    }

    /**
     * Returns an iterator of the GC element pairs.
     * This is used to ensure all GC elements are properly serialized.
     */
    fun getGCElementPairs(): Sequence<CrdtElementPair> {
        return gcElementSetByCreatedAt.asSequence().mapNotNull { createdAt ->
            elementPairMapByCreatedAt[createdAt]
        }
    }

    /**
     * Copies itself deeply.
     */
    fun deepCopy(): CrdtRoot {
        return CrdtRoot(rootObject.deepCopy())
    }

    /**
     * Deletes elements that were removed before [executedAt].
     */
    fun garbageCollect(minSyncedVersionVector: VersionVector): Int {
        var count = 0
        gcElementSetByCreatedAt.toSet().forEach { createdAt ->
            // Neither lookup is guaranteed to hit (yorkie-js-sdk#1341): skip,
            // don't drop — the element's size is still charged to gc, which
            // only deregisterElement releases.
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            val parent = pair.parent ?: return@forEach
            val removedAt = pair.element.removedAt
            if (removedAt != null && minSyncedVersionVector.afterOrEqual(removedAt)) {
                parent.purge(pair.element)
                count += deregisterElement(pair.element)
            }
        }

        val iterator = gcPairMap.values.iterator()
        while (iterator.hasNext()) {
            val pair = iterator.next()
            val removedAt = pair.child.removedAt
            if (removedAt == null) {
                // Node was revived but its pair was not unregistered. Reverse
                // the GC accounting (gc -> live) and drop the stale entry via
                // this iterator (not unregisterGCPair's own gcPairMap.remove,
                // which would invalidate this iterator) so the registerGCPair
                // toggle can't be tripped later and docSize.gc/live stay
                // consistent.
                docSize = unregisterAccounting(pair)
                iterator.remove()
                continue
            }
            if (minSyncedVersionVector.afterOrEqual(removedAt)) {
                pair.parent.deleteChild(pair.child)
                docSize = DocSize(
                    live = docSize.live,
                    gc = subDataSize(docSize.gc, pair.child.dataSize),
                )
                iterator.remove()
                count++
            }
        }
        return count
    }

    /**
     * Removes [element] — and, for a [CrdtContainer], every descendant —
     * from the element table, releasing its accounted size via
     * [releaseCharge]. Returns the number of elements actually deregistered.
     *
     * Table entries are dropped by IDENTITY, not by key (yorkie-js-sdk#1341):
     * an undo restores a copy under a tombstone's createdAt — on Android also
     * every member of an array item, whose reverse is
     * [dev.yorkie.document.operation.AddOperation] plus one
     * [dev.yorkie.document.operation.SetOperation] per member — so a descendant being collected here can
     * share its createdAt with a live registration. Deleting by key would
     * evict the live element. Such a stale twin still releases its own
     * charge, but is not counted.
     */
    fun deregisterElement(element: CrdtElement): Int {
        var count = 0
        val callback = { elem: CrdtElement, _: CrdtContainer? ->
            val createdAt = elem.createdAt
            releaseCharge(elem, sizeInGC.remove(elem))
            if (elementPairMapByCreatedAt[createdAt]?.element === elem) {
                elementPairMapByCreatedAt.remove(createdAt)
                gcElementSetByCreatedAt.remove(createdAt)
                count++
            }
            false
        }
        callback(element, null)
        if (element is CrdtContainer) {
            element.getDescendants(callback)
        }
        return count
    }

    /**
     * Returns the JSON encoding of [rootObject].
     */
    fun toJson(): String {
        return rootObject.toJson()
    }

    /**
     * `acc` accumulates the given DataSize to Live.
     */
    fun acc(diff: DataSize) {
        docSize = docSize.copy(
            live = addDataSizes(docSize.live, diff),
        )
    }

    companion object {
        private const val TAG = "CrdtRoot"
    }

    data class CrdtElementPair(
        val element: CrdtElement,
        val parent: CrdtContainer? = null,
    )
}
