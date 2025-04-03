package dev.yorkie.document.crdt

import androidx.annotation.VisibleForTesting
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.VersionVector
import dev.yorkie.util.Logger.Companion.logError

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

        if (element is CrdtContainer) {
            element.getDescendants { _element, _parent ->
                registerElement(_element, _parent)
                false
            }
        }
    }

    /**
     * Registers the given [element] to the hash set.
     */
    fun registerRemovedElement(element: CrdtElement) {
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
                pair.parent?.delete(pair.element)
                count += garbageCollectInternal(pair.element)
            }
        }

        val iterator = gcPairMap.values.iterator()
        while (iterator.hasNext()) {
            val pair = iterator.next()
            val removedAt = pair.child.removedAt
            if (removedAt != null && minSyncedVersionVector.afterOrEqual(removedAt)) {
                pair.parent.deleteChild(pair.child)
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
        elementPairMapByCreatedAt.remove(element.createdAt)
        gcElementSetByCreatedAt.remove(element.createdAt)
    }

    /**
     * Returns the JSON encoding of [rootObject].
     */
    fun toJson(): String {
        return rootObject.toJson()
    }

    companion object {
        private const val TAG = "CrdtRoot"
    }

    data class CrdtElementPair(
        val element: CrdtElement,
        val parent: CrdtContainer? = null,
    )
}
