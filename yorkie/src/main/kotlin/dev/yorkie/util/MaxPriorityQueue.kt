package dev.yorkie.util

import java.util.PriorityQueue

/**
 * Simple extension of [PriorityQueue] to implement a max priority queue.
 * Although extension is not necessary, this is done to clearly specify that we are using a max priority queue.
 */
internal class MaxPriorityQueue<E : Comparable<E>>(
    initialCapacity: Int = DEFAULT_INITIAL_CAPACITY,
) : PriorityQueue<E>(initialCapacity, reverseOrder()) {

    companion object {
        private const val DEFAULT_INITIAL_CAPACITY = 11
    }
}

/**
 * [PQNode] is a node of [MaxPriorityQueue].
 */
internal abstract class PQNode<K, V>(internal val key: K, internal val value: V) :
    Comparable<PQNode<K, V>> {
    /**
     * Returns the key of this [PQNode].
     */
    fun getKey(): K {
        return key
    }

    /**
     * Returns the value of [PQNode].
     */
    fun getValue(): V {
        return this.value
    }
}
