package dev.yorkie.document.crdt

import androidx.annotation.VisibleForTesting
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.DataSize
import dev.yorkie.util.DocSize
import dev.yorkie.util.Logger.Companion.logError
import dev.yorkie.util.addDataSizes
import dev.yorkie.util.subDataSize

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
     * A hash table that maps the IDString of GCChild to the
     * element itself and its parent.
     */
    private val gcPairMap = mutableMapOf<GCChild, GCPair<*>>()

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
     * Registers the given [element] to the hash set.
     */
    fun registerRemovedElement(element: CrdtElement) {
        val docSizeLive = subDataSize(docSize.live, element.getDataSize())
        docSize = docSize.copy(
            live = docSizeLive.copy(
                meta = docSizeLive.meta + TimeTicket.TIME_TICKET_SIZE,
            ),
            gc = addDataSizes(docSize.gc, element.getDataSize()),
        )
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
                count += garbageCollectInternal(pair.element)
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

    private fun garbageCollectInternal(element: CrdtElement): Int {
        var count = 0
        val callback = { elem: CrdtElement, _: CrdtContainer? ->
            deregisterElement(elem)
            count++
            false
        }
        callback(element, null)
        if (element is CrdtContainer) {
            element.getDescendants(callback)
        }
        return count
    }

    @VisibleForTesting
    fun deregisterElement(element: CrdtElement) {
        docSize = docSize.copy(
            gc = subDataSize(docSize.gc, element.getDataSize()),
        )
        elementPairMapByCreatedAt.remove(element.createdAt)
        gcElementSetByCreatedAt.remove(element.createdAt)
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
