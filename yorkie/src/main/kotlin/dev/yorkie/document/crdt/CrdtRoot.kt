package dev.yorkie.document.crdt

import com.google.common.annotations.VisibleForTesting
import dev.yorkie.document.time.TimeTicket
import dev.yorkie.document.time.TimeTicket.Companion.compareTo
import dev.yorkie.util.YorkieLogger

/**
 * [CrdtRoot] is a structure represents the root. It has a hash table of
 * all elements to find a specific element when applying remote changes
 * received from server.
 *
 * Every element has a unique time ticket at creation, which allows us to find
 * a particular element.
 */
internal class CrdtRoot(val rootObject: CrdtObject) {
    private val elementPairMapByCreatedAt =
        hashMapOf(rootObject.createdAt to CrdtElementPair(rootObject))
    private val removedElementSetByCreatedAt = hashSetOf<TimeTicket>()
    private val textWithGarbageSetByCreatedAt = hashSetOf<TimeTicket>()

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
            var subPath = parent.subPathOf(currentCreatedAt)
            if (subPath == null) {
                YorkieLogger.e(TAG, "fail to find the given element: $currentCreatedAt")
            } else {
                subPath = subPath.replace(Regex("/[\$.]/g"), "\\$&")
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
     * Registers the given [text] to the hast set.
     */
    fun registerTextWithGarbage(text: CrdtTextElement) {
        textWithGarbageSetByCreatedAt.add(text.createdAt)
    }

    /**
     * Returns length of nodes which can be garbage collected.
     */
    fun getGarbageLen(): Int {
        var count = 0
        removedElementSetByCreatedAt.forEach { createdAt ->
            count++
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            if (pair.element is CrdtContainer) {
                pair.element.getDescendants { _, _ ->
                    count++
                    false
                }
            }
        }

        textWithGarbageSetByCreatedAt.forEach { createdAt ->
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            val text = pair.element as CrdtTextElement
            count += text.getRemovedNodesLen()
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
        removedElementSetByCreatedAt.forEach { createdAt ->
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            if (pair.element.isRemoved && pair.element.removedAt <= executedAt) {
                pair.parent?.delete(pair.element)
                count += garbageCollectInternal(pair.element)
            }
        }

        textWithGarbageSetByCreatedAt.forEach { createdAt ->
            val pair = elementPairMapByCreatedAt[createdAt] ?: return@forEach
            val text = pair.element as CrdtTextElement

            val removedNodeCount = text.deleteTextNodesWithGarbage(executedAt)
            if (removedNodeCount > 0) {
                textWithGarbageSetByCreatedAt.remove(text.createdAt)
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
