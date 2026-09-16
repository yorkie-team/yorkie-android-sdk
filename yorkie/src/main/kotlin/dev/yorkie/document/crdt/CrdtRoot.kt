package dev.yorkie.document.crdt

import androidx.annotation.VisibleForTesting
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
     * Maps every registered element's [TimeTicket.createdAt] to the EXACT
     * [DataSize] amount charged to [DocSize.gc] for it (narrowed to
     * whole-element moves — `acc` and a [GCPair]'s `gcOnlySize` book against
     * live regardless, so content edited into an already-removed [CrdtText]
     * or [CrdtTree] stays charged to live). An element reaches gc by more
     * routes than being removed itself: it can also be swept in as a
     * descendant of a removed [CrdtContainer]. [moveSizeToGC] is the ONLY
     * function that adds an entry here, and it records the amount, not a
     * flag, because [CrdtElement.getDataSize] is not stable — it grows by
     * one [TimeTicket.TIME_TICKET_SIZE] once [CrdtElement.removedAt] is set,
     * which can happen strictly after the element's size first moves here.
     */
    private val sizeInGC = mutableMapOf<TimeTicket, DataSize>()

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
        registerElement(rootObject, null)

        rootObject.getDescendants { element, _ ->
            if (element.removedAt != null) {
                registerRemovedElement(element)
            }
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
     * Registers the given [element] to the hash table.
     */
    fun registerElement(element: CrdtElement, parent: CrdtContainer?) {
        elementPairMapByCreatedAt[element.createdAt] = CrdtElementPair(element, parent)

        docSize = docSize.copy(
            live = addDataSizes(docSize.live, element.getDataSize()),
        )

        if (element is CrdtContainer) {
            element.getDescendants { elem, par ->
                elementPairMapByCreatedAt[elem.createdAt] = CrdtElementPair(elem, par)

                docSize = docSize.copy(
                    live = addDataSizes(docSize.live, elem.getDataSize()),
                )

                false
            }
        }
    }

    /**
     * Moves [element]'s current size from [DocSize.live] to [DocSize.gc], or
     * tops up its charge in [sizeInGC] if it is already there. This is the
     * ONLY function that adds a size to gc, and it is idempotent: the same
     * [element] can be swept in more than once — once by its own removal,
     * and again if a container above it is removed later — and each
     * subsequent call charges only the growth in [CrdtElement.getDataSize]
     * since the last charge (typically one [TimeTicket.TIME_TICKET_SIZE],
     * from a [CrdtElement.removedAt] ticket set after the first move).
     *
     * Returns whether this call moved a size [DocSize.live] was actually
     * holding — false when [element] was already charged and only topped up.
     */
    private fun moveSizeToGC(element: CrdtElement): Boolean {
        val createdAt = element.createdAt
        val size = element.getDataSize()
        val charged = sizeInGC[createdAt]
        if (charged != null) {
            docSize = docSize.copy(
                gc = addDataSizes(
                    docSize.gc,
                    DataSize(data = size.data - charged.data, meta = size.meta - charged.meta),
                ),
            )
            sizeInGC[createdAt] = size
            return false
        }
        docSize = docSize.copy(
            gc = addDataSizes(docSize.gc, size),
            live = subDataSize(docSize.live, size),
        )
        sizeInGC[createdAt] = size
        return true
    }

    /**
     * Moves [element] — and, for a [CrdtContainer], every descendant — from
     * [DocSize.live] to [DocSize.gc] via [moveSizeToGC], and marks [element]
     * removed for [garbageCollect] to find later.
     *
     * The one-[TimeTicket.TIME_TICKET_SIZE] live-meta refund applies only
     * when [moveSizeToGC] actually moved a size [DocSize.live] held for
     * [element] itself AND [element] carries a [CrdtElement.removedAt]
     * ticket — a descendant swept in by the same call is not itself removed
     * and never gets this refund; only the outermost removed element's own
     * tombstone ticket is refunded this way.
     *
     * Two pre-existing exceptions to the refund rule are NOT fixed here
     * (tracked upstream, yorkie-js-sdk#1349 item 3): a snapshot-loaded
     * tombstone is refunded once per OUTERMOST uncollected ancestor,
     * over-crediting nested ones; and the LWW-losing side of a concurrent
     * [dev.yorkie.document.operation.SetOperation] is registered
     * already-removed, so live never held its ticket to refund. A container
     * restored over a nested tombstone by an undo goes through
     * [adoptRemovedElement] instead, which never refunds — [registerElement]
     * already booked the restored copy at its post-removal size.
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
     * Moves [element] — and, for a [CrdtContainer], every descendant — into
     * [DocSize.gc] via [moveSizeToGC] and marks it removed for
     * [garbageCollect], WITHOUT [registerRemovedElement]'s live-meta ticket
     * refund: [dev.yorkie.document.operation.SetOperation] just registered
     * these copies at their post-removal size through [registerElement], so
     * [DocSize.live] never held a pre-removal size to refund.
     *
     * Keeps a tombstone nested inside a container restored by an undo
     * collectable — a JS-literal port of `611e6e43`'s recursive
     * deregistration would otherwise drop the nested tombstone's createdAt
     * from [gcElementSetByCreatedAt] while [registerElement] re-books its
     * copy into live, making it uncollectable (yorkie-js-sdk#1349 item 1).
     * Android ports the iOS fix (yorkie-ios-sdk `fd15fa3cf6`), a determination
     * diverging from JS v0.7.17. Kept as a plain method (not called from
     * [registerRemovedElement]) so a later JS sync landing on top of this
     * one composes cleanly.
     */
    fun adoptRemovedElement(element: CrdtElement) {
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
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            val removedAt = pair.element.removedAt
            if (removedAt != null && minSyncedVersionVector.afterOrEqual(removedAt)) {
                pair.parent?.purge(pair.element)
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
     * from the element table, releasing its accounted size. An element
     * charged to [DocSize.gc] (present in [sizeInGC]) is released from gc by
     * exactly the charged amount; an element that was never removed itself
     * (created inside an already-removed container, never swept in by
     * [moveSizeToGC]) is released from [DocSize.live] instead. Returns the
     * number of elements actually deregistered.
     *
     * Android's array-remove undo reverses as `Add` + one child `Set` per
     * member (`RemoveOperation.childSetOps`), re-registering a removed
     * container's descendants under their ORIGINAL createdAt inside a NEW,
     * separately-instantiated container, while the OLD tombstoned container
     * instance still owns the same descendant createdAts in its own member
     * table. When a later [garbageCollect] purges that old tombstoned
     * container and walks ITS descendants, this closure would otherwise
     * delete the live twin's registration by createdAt. Guard (Android-only
     * divergence forced by this reverse-op shape, not present in JS/iOS): if
     * the element table's entry for a createdAt no longer points at THIS
     * instance, a live twin already owns it — no-op, nothing was actually
     * collected.
     */
    @VisibleForTesting
    fun deregisterElement(element: CrdtElement): Int {
        var count = 0
        val callback = { elem: CrdtElement, _: CrdtContainer? ->
            val createdAt = elem.createdAt
            val registered = elementPairMapByCreatedAt[createdAt]
            if (registered != null && registered.element !== elem) {
                // Android-only stale-twin no-op — see KDoc above.
            } else {
                val charged = sizeInGC[createdAt]
                if (charged != null) {
                    docSize = docSize.copy(gc = subDataSize(docSize.gc, charged))
                    sizeInGC.remove(createdAt)
                } else {
                    docSize = docSize.copy(live = subDataSize(docSize.live, elem.getDataSize()))
                }
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
