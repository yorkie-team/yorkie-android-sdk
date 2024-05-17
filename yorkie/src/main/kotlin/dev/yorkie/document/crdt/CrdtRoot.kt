package dev.yorkie.document.crdt

import androidx.annotation.VisibleForTesting
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
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
     * A hash set that contains the creation time of the removed element.
     * It is used to find the removed element when executing garbage collection.
     */
    private val removedElementSetByCreatedAt = mutableSetOf<TimeTicket>()

    /**
     * A hash set that contains the creation time of the element that has removed nodes.
     * It is used to find the element that has removed nodes when executing garbage collection.
     */
    private val elementHasRemovedNodesSetByCreatedAt = mutableSetOf<TimeTicket>()

    val elementMapSize
        get() = elementPairMapByCreatedAt.size

    val removedElementSetSize
        get() = removedElementSetByCreatedAt.size

    init {
        rootObject.getDescendants { element: CrdtElement, parent: CrdtContainer ->
            registerElement(element, parent)
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
    fun createSubPaths(createdAt: TimeTicket): List<String> {
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
    fun registerElement(element: CrdtElement, parent: CrdtContainer) {
        elementPairMapByCreatedAt[element.createdAt] = CrdtElementPair(element, parent)
    }

    /**
     * Registers the given [element] to the hash set.
     */
    fun registerRemovedElement(element: CrdtElement) {
        removedElementSetByCreatedAt.add(element.createdAt)
    }

    /**
     * Registers the given GC element to the hash set.
     */
    fun registerElementHasRemovedNodes(element: CrdtGCElement) {
        elementHasRemovedNodesSetByCreatedAt.add(element.createdAt)
    }

    /**
     * Returns length of nodes which can be garbage collected.
     */
    fun getGarbageLength(): Int {
        var count = 0
        val seen = mutableSetOf<TimeTicket>()
        removedElementSetByCreatedAt.forEach { createdAt ->
            seen += createdAt
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            if (pair.element is CrdtContainer) {
                pair.element.getDescendants { element, _ ->
                    seen += element.createdAt
                    false
                }
            }
        }
        count += seen.size

        elementHasRemovedNodesSetByCreatedAt.forEach { createdAt ->
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            val element = pair.element as CrdtGCElement
            count += element.removedNodesLength
        }

        return count
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
    fun garbageCollect(executedAt: TimeTicket): Int {
        var count = 0
        removedElementSetByCreatedAt.toSet().forEach { createdAt ->
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            if (pair.element.isRemoved && pair.element.removedAt <= executedAt) {
                pair.parent?.delete(pair.element)
                count += garbageCollectInternal(pair.element)
            }
        }

        val elementGarbageIterator = elementHasRemovedNodesSetByCreatedAt.iterator()
        while (elementGarbageIterator.hasNext()) {
            val createdAt = elementGarbageIterator.next()
            val pair = elementPairMapByCreatedAt[createdAt] ?: continue
            val element = pair.element as CrdtGCElement

            val removedNodeCount = element.deleteRemovedNodesBefore(executedAt)
            if (element.removedNodesLength == 0) {
                elementGarbageIterator.remove()
            }
            count += removedNodeCount
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
        removedElementSetByCreatedAt.remove(element.createdAt)
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
