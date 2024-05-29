package dev.yorkie.document.crdt

import dev.yorkie.document.time.TimeTicket

/**
 * [GCPair] is a structure that represents a pair of parent and child for garbage
 * collection.
 */
internal data class GCPair<T : GCChild>(val parent: GCParent<T>, val child: T)

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
}

internal sealed interface GCCrdtElement {
    val gcPairs: List<GCPair<*>>
}
